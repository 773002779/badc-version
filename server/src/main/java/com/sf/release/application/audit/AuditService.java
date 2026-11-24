package com.sf.release.application.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sf.release.common.BizException;
import com.sf.release.domain.audit.AuditAction;
import com.sf.release.domain.audit.AuditObjectType;
import com.sf.release.infra.repo.AuditLogMapper;
import com.sf.release.infra.repo.po.AuditLogPO;
import com.sf.release.infra.security.UserContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.Map;

/**
 * Writes one {@code audit_log} row per action (audit design section 3).
 *
 * <p><strong>Audit failures never change a business result.</strong> {@link #record(AuditCommand)}
 * writes in a {@code REQUIRES_NEW} transaction and swallows every exception with an ERROR log: the
 * trigger points call it bare, after the business write and before returning, with no try/catch of
 * their own. The explicit {@link TransactionTemplate} (not {@code @Transactional}) is the same
 * choice as {@link com.sf.release.application.notification.NotificationService}: a commit failure is
 * thrown by the proxy <em>after</em> the method body returned, where a swallow could not catch it,
 * while with the template the commit is inside the {@code try}.
 *
 * <p><strong>Rows are a stream, not an outbox.</strong> No dedupe: repeating the same action writes
 * another row, because the audit trail records occurrences, not state. There is deliberately no
 * update or delete API.
 *
 * <p><strong>Actor and trace.</strong> The actor is {@link UserContext#current()} at write time
 * (never a request parameter); a job without a request context (the freeze expiry scan) records
 * {@code SYSTEM}. The trace id is the MDC key {@code traceId} written by {@code TraceIdFilter}, and
 * is {@code null} outside a request. {@code created_at} is the instant the row is written.
 *
 * <p><strong>The non-atomic window is accepted.</strong> Because the row commits in its own
 * transaction, a business rollback after the audit call can leave an audit row for an action that
 * did not happen. The design records this trade-off (section 7); the alternative is an outbox.
 */
@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    /** Column widths of V27; the service truncates defensively instead of failing a business call. */
    static final int MAX_ACTOR = 32;
    static final int MAX_ACTION = 48;
    static final int MAX_OBJECT_TYPE = 32;
    static final int MAX_OBJECT_ID = 64;
    static final int MAX_OBJECT_LABEL = 200;
    static final int MAX_SUMMARY = 512;
    static final int MAX_REASON = 512;
    static final int MAX_TRACE_ID = 64;

    /** The actor recorded when no request context exists (a scheduled job). */
    static final String SYSTEM_ACTOR = "SYSTEM";

    /** The MDC key {@code TraceIdFilter} writes. */
    static final String TRACE_ID_MDC_KEY = "traceId";

    private final AuditLogMapper auditLogMapper;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate auditTransaction;

    public AuditService(AuditLogMapper auditLogMapper, ObjectMapper objectMapper,
                        PlatformTransactionManager transactionManager) {
        this.auditLogMapper = auditLogMapper;
        this.objectMapper = objectMapper;
        this.auditTransaction = new TransactionTemplate(transactionManager);
        this.auditTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Records one event. Never throws: a null command or a write failure is logged and ignored.
     *
     * @param command the action, object, summary, reason, changed fields and scope (section 3)
     */
    public void record(AuditCommand command) {
        if (command == null || command.action() == null) {
            return;
        }
        try {
            AuditLogPO row = toRow(command);
            auditTransaction.executeWithoutResult(status -> auditLogMapper.insertAudit(row));
        } catch (RuntimeException ex) {
            // The caller is a business use case: an audit row must never change its result.
            log.error("audit row dropped: action={}, objectType={}, objectId={}, reason={}",
                command.action(), command.objectType(), safe(command.objectId()), ex.getMessage(),
                ex);
        }
    }

    // ------------------------------------------------------------------ row assembly

    private AuditLogPO toRow(AuditCommand command) {
        AuditAction action = command.action();
        AuditObjectType type = command.objectType() == null ? action.objectType()
            : command.objectType();
        AuditLogPO po = new AuditLogPO();
        po.setActor(truncate(actor(), MAX_ACTOR));
        po.setAction(truncate(action.name(), MAX_ACTION));
        po.setObjectType(truncate(type == null ? AuditObjectType.SETTING.name() : type.name(),
            MAX_OBJECT_TYPE));
        po.setObjectId(truncate(command.objectId() == null ? "" : command.objectId(),
            MAX_OBJECT_ID));
        po.setObjectLabel(truncateOrNull(command.objectLabel(), MAX_OBJECT_LABEL));
        po.setSummary(truncate(summaryOf(command, action), MAX_SUMMARY));
        po.setReason(truncateOrNull(command.reason(), MAX_REASON));
        po.setBeforeJson(json(command.before()));
        po.setAfterJson(json(command.after()));
        po.setScopeUnitId(command.scopeUnitId());
        po.setScopeSubsystemId(command.scopeSubsystemId());
        po.setTraceId(truncateOrNull(MDC.get(TRACE_ID_MDC_KEY), MAX_TRACE_ID));
        po.setCreatedAt(Instant.now());
        return po;
    }

    /**
     * The caller, or {@code SYSTEM} when there is no request context. A missing context is exactly
     * the scheduled-job case (the freeze expiry scan); a caller inside a request always has one, so
     * the fallback cannot hide a missing identity on a user action.
     */
    private static String actor() {
        try {
            String userId = UserContext.current().userId();
            return userId == null || userId.isBlank() ? SYSTEM_ACTOR : userId;
        } catch (BizException ex) {
            return SYSTEM_ACTOR;
        }
    }

    /** A row always has a summary; the action label is the honest fallback for an empty one. */
    private static String summaryOf(AuditCommand command, AuditAction action) {
        String summary = command.summary();
        if (summary == null || summary.isBlank()) {
            return action.label();
        }
        return summary;
    }

    private String json(Map<String, Object> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(values);
        } catch (JsonProcessingException ex) {
            // The command comes from our own trigger points; an unserializable value is a bug, not
            // a reason to fail the business call. The columns are a best-effort field subset.
            log.warn("audit before/after could not be serialized: {}", ex.getMessage());
            return null;
        }
    }

    // ------------------------------------------------------------------ helpers

    private static String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static String truncateOrNull(String value, int max) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return truncate(value, max);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}