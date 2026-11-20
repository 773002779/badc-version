package com.sf.release.application;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sf.release.domain.pipeline.port.ItemFailureRepairer;
import com.sf.release.domain.pipeline.port.TransactionRunner;
import com.sf.release.domain.task.ItemEvent;
import com.sf.release.infra.repo.PipelineItemFailureMapper;
import com.sf.release.infra.repo.po.PipelineItemFailurePO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;

/**
 * Durable record and repair of failed 变更项 transitions (V12, tech design §6.4).
 *
 * <p><strong>Never breaks the listener.</strong> {@link #record} inserts the failure in its own
 * {@code REQUIRES_NEW} transaction, so the row survives even when the surrounding pipeline
 * transaction rolls back; every failure while recording is caught and logged. The listener
 * therefore cannot throw into the advance path.
 *
 * <p><strong>Retry contract.</strong> {@link #retryUnresolved(int)} replays the stored
 * {@code ItemEvent} against the item with the normal state machine + {@code row_version} CAS.
 * Each replay increments {@code attempts}; a success sets {@code resolved_at} in the same
 * transaction as the item update. After three failed attempts the row is left unresolved for
 * operations (one ERROR when the third attempt fails, a WARN each later round because it is
 * still unresolved). Rows whose event is currently not allowed or whose CAS was lost also
 * consume an attempt: the state may become eligible later, but it must not loop forever.
 */
@Service
public class ItemFailureAppService implements ItemFailureRepairer {

    private static final Logger log = LoggerFactory.getLogger(ItemFailureAppService.class);

    /** Attempts after which a row is left for ops instead of retried (tech design §6.4). */
    public static final int MAX_ATTEMPTS = 3;

    private static final int MAX_ERROR_LENGTH = 500;

    private final PipelineItemFailureMapper failureMapper;
    private final ItemTransitions transitions;
    private final TransactionRunner transactionRunner;
    private final TransactionTemplate requiresNew;

    public ItemFailureAppService(PipelineItemFailureMapper failureMapper,
                                 ItemTransitions transitions,
                                 TransactionRunner transactionRunner,
                                 PlatformTransactionManager transactionManager) {
        this.failureMapper = failureMapper;
        this.transitions = transitions;
        this.transactionRunner = transactionRunner;
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Persists one failed transition. Never throws: a recording failure is logged, because the
     * listener must not break the pipeline transaction.
     */
    public void record(long instanceId, long itemId, String eventName, String errorMessage) {
        try {
            requiresNew.execute(status -> {
                failureMapper.insert(PipelineItemFailurePO.of(instanceId, itemId, eventName,
                    truncate(errorMessage, MAX_ERROR_LENGTH), Instant.now()));
                return null;
            });
        } catch (RuntimeException ex) {
            log.error("could not persist the failed item transition: instanceId={}, itemId={},"
                + " event={}", instanceId, itemId, eventName, ex);
        }
    }

    @Override
    public int retryUnresolved(int limit) {
        List<PipelineItemFailurePO> rows = failureMapper.selectList(
            Wrappers.<PipelineItemFailurePO>lambdaQuery()
                .isNull(PipelineItemFailurePO::getResolvedAt)
                .lt(PipelineItemFailurePO::getAttempts, MAX_ATTEMPTS)
                .orderByAsc(PipelineItemFailurePO::getId)
                .last("LIMIT " + Math.max(limit, 1)));
        int resolved = 0;
        for (PipelineItemFailurePO row : rows) {
            if (retry(row)) {
                resolved++;
            }
        }
        Long exhausted = failureMapper.selectCount(
            Wrappers.<PipelineItemFailurePO>lambdaQuery()
                .isNull(PipelineItemFailurePO::getResolvedAt)
                .ge(PipelineItemFailurePO::getAttempts, MAX_ATTEMPTS));
        if (exhausted != null && exhausted > 0) {
            log.warn("{} unresolved item transition failure(s) exhausted {} retries;"
                + " operations action required", exhausted, MAX_ATTEMPTS);
        }
        return resolved;
    }

    /** Replays one row; returns {@code true} when the transition was applied and resolved. */
    private boolean retry(PipelineItemFailurePO row) {
        int attempt = (row.getAttempts() == null ? 0 : row.getAttempts()) + 1;
        ItemEvent event;
        try {
            event = ItemEvent.valueOf(row.getEventName());
        } catch (IllegalArgumentException ex) {
            finishAttempt(row, attempt, "非法的 ItemEvent：" + row.getEventName());
            return false;
        }
        boolean applied;
        try {
            applied = Boolean.TRUE.equals(transactionRunner.inTransaction(() -> {
                if (transitions.apply(row.getItemId(), event) != ItemTransitions.Outcome.APPLIED) {
                    return false;
                }
                failureMapper.update(null, Wrappers.<PipelineItemFailurePO>lambdaUpdate()
                    .eq(PipelineItemFailurePO::getId, row.getId())
                    .isNull(PipelineItemFailurePO::getResolvedAt)
                    .set(PipelineItemFailurePO::getAttempts, attempt)
                    .set(PipelineItemFailurePO::getResolvedAt, Instant.now()));
                return true;
            }));
        } catch (RuntimeException ex) {
            finishAttempt(row, attempt, messageOf(ex));
            return false;
        }
        if (applied) {
            log.info("item transition failure resolved: failureId={}, instanceId={}, itemId={},"
                + " event={}, attempt={}", row.getId(), row.getInstanceId(), row.getItemId(),
                event, attempt);
            return true;
        }
        finishAttempt(row, attempt, "transition not applied (state or CAS)");
        return false;
    }

    /**
     * Persists the failed attempt. The third failure is an ERROR (the row is left for ops), a
     * later round only WARNs that it is still unresolved.
     */
    private void finishAttempt(PipelineItemFailurePO row, int attempt, String message) {
        boolean updated;
        try {
            updated = failureMapper.update(null, Wrappers.<PipelineItemFailurePO>lambdaUpdate()
                .eq(PipelineItemFailurePO::getId, row.getId())
                .isNull(PipelineItemFailurePO::getResolvedAt)
                .set(PipelineItemFailurePO::getAttempts, attempt)
                .set(PipelineItemFailurePO::getErrorMessage,
                    truncate(message, MAX_ERROR_LENGTH))) == 1;
        } catch (RuntimeException ex) {
            log.error("could not record the retry attempt of item failure {}", row.getId(), ex);
            return;
        }
        if (!updated) {
            return;
        }
        if (attempt >= MAX_ATTEMPTS) {
            log.error("item transition failure exhausted {} attempts, left unresolved for"
                + " operations: failureId={}, instanceId={}, itemId={}, event={}, reason={}",
                MAX_ATTEMPTS, row.getId(), row.getInstanceId(), row.getItemId(),
                row.getEventName(), message);
        } else {
            log.warn("item transition retry failed ({}/{}): failureId={}, instanceId={},"
                + " itemId={}, event={}, reason={}", attempt, MAX_ATTEMPTS, row.getId(),
                row.getInstanceId(), row.getItemId(), row.getEventName(), message);
        }
    }

    private static String messageOf(RuntimeException ex) {
        String message = ex.getMessage();
        return message == null || message.isBlank()
            ? ex.getClass().getSimpleName() : message;
    }

    private static String truncate(String value, int maxLength) {
        String text = value == null || value.isBlank() ? "unknown error" : value;
        return text.length() <= maxLength ? text : text.substring(0, maxLength);
    }
}