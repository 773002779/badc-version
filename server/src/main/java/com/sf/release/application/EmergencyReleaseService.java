package com.sf.release.application;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sf.release.application.audit.AuditCommand;
import com.sf.release.application.audit.AuditService;
import com.sf.release.application.notification.NotificationAudiences;
import com.sf.release.application.notification.NotificationCommand;
import com.sf.release.application.notification.NotificationService;
import com.sf.release.common.BizError;
import com.sf.release.common.BizException;
import com.sf.release.common.security.Roles;
import com.sf.release.domain.audit.AuditAction;
import com.sf.release.domain.notification.NotificationEventKey;
import com.sf.release.domain.task.ItemEvent;
import com.sf.release.domain.task.ItemStatus;
import com.sf.release.domain.task.MemberRole;
import com.sf.release.infra.repo.DeployUnitMapper;
import com.sf.release.infra.repo.EmergencyReleaseMapper;
import com.sf.release.infra.repo.ReleaseItemMapper;
import com.sf.release.infra.repo.ReleaseOrderMapper;
import com.sf.release.infra.repo.po.DeployUnitPO;
import com.sf.release.infra.repo.po.EmergencyReleasePO;
import com.sf.release.infra.repo.po.ReleaseItemPO;
import com.sf.release.infra.repo.po.ReleaseOrderPO;
import com.sf.release.infra.security.CurrentUser;
import com.sf.release.infra.security.UserContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * The J11 紧急放行 use case (P6-D design §1-§3): an administrator forces a release that is still
 * waiting for a signature to {@code READY}, skipping the approval step and the 封板 wait.
 *
 * <p><strong>ADMIN role only, not a permission point.</strong> The design deliberately does not
 * reuse {@code control:approve}: the caller must hold the platform {@link Roles#ADMIN} role even
 * when the configurable role mapping would grant an approval point (C20/J11). A non-administrator
 * answers 40009 before the order is even read.
 *
 * <p><strong>The state matrix is narrow.</strong> Only {@code SUBMITTED} (submitted, not yet judged)
 * and {@code PENDING_APPROVAL} (signing in progress) may be released:
 * <ul>
 *   <li>{@code PENDING_APPROVAL → APPROVE_ALL → READY}, with the still-pending steps of the newest
 *       admission snapshot voided ({@code CANCELED}) because nobody has to sign any more;</li>
 *   <li>{@code SUBMITTED → NO_APPROVAL → READY}, the same edge the admission takes when it decides
 *       that no signature is required.</li>
 * </ul>
 * {@code READY} is refused with "already executable" and every other state with the normal
 * submit/rerun advice; both answer 40001 carrying {@code currentStatus} (design §1).
 *
 * <p><strong>One business transaction, then best effort.</strong> The approval cancellation, the item
 * transition, the order mirror (status {@code READY} + the four emergency columns) and the
 * {@code emergency_release} history row commit together. The audit row
 * ({@code EMERGENCY_RELEASE}, ORDER) and the notification ({@code EMERGENCY_RELEASE} to the task's
 * OPS/DEV members and creator) are written afterwards through the failure-swallowing
 * {@link AuditService}/{@link NotificationService}, exactly like the P5-B control actions: neither can
 * revert the release (design §5).
 *
 * <p><strong>Irreversible.</strong> There is no undo: the marker and the history row stay as the
 * evidence, and a later release goes through a rerun (design §6). The service never clears
 * {@code emergency_release}.
 *
 * <p><strong>What is not bypassed.</strong> The image/digest, gate and waiver checks still run at
 * execution time; only the approval step and the 封板 wait are skipped (design §1/§6). The freeze
 * bypass is implemented where the freeze would refuse the execution — {@link ReviewService} and
 * {@link ProductionBoundaryProbeService} — not here.
 */
@Service
public class EmergencyReleaseService {

    private static final Logger log = LoggerFactory.getLogger(EmergencyReleaseService.class);

    /** The design caps the operator reason at 200 (the columns are 512 wide). */
    static final int MAX_REASON_LENGTH = 200;

    /** The only states an emergency release accepts (design §1). */
    private static final EnumSet<ItemStatus> ALLOWED = EnumSet.of(
        ItemStatus.SUBMITTED, ItemStatus.PENDING_APPROVAL);

    private final ReleaseOrderMapper orderMapper;
    private final ReleaseItemMapper itemMapper;
    private final DeployUnitMapper unitMapper;
    private final EmergencyReleaseMapper emergencyReleaseMapper;
    private final ItemTransitions transitions;
    private final ApprovalService approvalService;
    private final AuditService audit;
    private final NotificationService notifications;
    private final TransactionTemplate transactionTemplate;

    public EmergencyReleaseService(ReleaseOrderMapper orderMapper,
                                   ReleaseItemMapper itemMapper,
                                   DeployUnitMapper unitMapper,
                                   EmergencyReleaseMapper emergencyReleaseMapper,
                                   ItemTransitions transitions,
                                   ApprovalService approvalService,
                                   AuditService audit,
                                   NotificationService notifications,
                                   PlatformTransactionManager transactionManager) {
        this.orderMapper = Objects.requireNonNull(orderMapper, "orderMapper must not be null");
        this.itemMapper = Objects.requireNonNull(itemMapper, "itemMapper must not be null");
        this.unitMapper = Objects.requireNonNull(unitMapper, "unitMapper must not be null");
        this.emergencyReleaseMapper = Objects.requireNonNull(emergencyReleaseMapper,
            "emergencyReleaseMapper must not be null");
        this.transitions = Objects.requireNonNull(transitions, "transitions must not be null");
        this.approvalService = Objects.requireNonNull(approvalService,
            "approvalService must not be null");
        this.audit = Objects.requireNonNull(audit, "audit must not be null");
        this.notifications = Objects.requireNonNull(notifications,
            "notifications must not be null");
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * 紧急放行 one 发布单.
     *
     * @throws BizException 40000 a blank/unknown publish number, a missing item or a blank/oversized
     *                       reason; 40001 the item is not {@code SUBMITTED}/{@code PENDING_APPROVAL}
     *                       (with {@code currentStatus}) or a concurrent writer changed it; 40009 the
     *                       caller does not hold the ADMIN role
     */
    public EmergencyResult release(String publishNo, String reason) {
        CurrentUser user = requireAdmin();
        String no = requirePublishNo(publishNo);
        ReleaseOrderPO order = requireOrder(no);
        ReleaseItemPO item = requireItem(order);
        ItemStatus before = AdmissionService.statusOf(item);
        requireAllowed(order, item, before);
        String why = requireReason(reason);
        long orderId = order.getId();
        long itemId = item.getId();
        long unitId = item.getUnitId() == null ? 0L : item.getUnitId();
        String actor = user.userId();
        Instant now = Instant.now();

        Applied applied = transactionTemplate.execute(tx ->
            apply(order, itemId, why, actor, now));
        Applied result = Objects.requireNonNull(applied, "emergency release result");

        log.info("emergency release: publishNo={}, orderId={}, itemId={}, previousStatus={},"
                + " approvalsCancelled={}, by={}, reason={}", no, orderId, itemId, result.before(),
            result.cancelled(), actor, why);
        auditEmergency(orderId, no, item.getCrid(), unitId, result, actor, why, now);
        notifyEmergency(orderId, no, item.getCrid(), actor, why, now);
        return new EmergencyResult(ItemStatus.READY.name(), ItemStatus.READY.name(), true);
    }

    // ------------------------------------------------------------------ transaction

    /**
     * The one business transaction: void the pending signatures, apply the state-machine edge, mirror
     * {@code READY} + the four emergency columns onto the order and append the history row.
     */
    private Applied apply(ReleaseOrderPO order, long itemId, String reason, String actor,
                          Instant now) {
        long orderId = order.getId();
        // FIRST statement: the same order-row lock the approval service uses, so a signature and an
        // emergency release of one order cannot interleave.
        ReleaseOrderPO locked = orderMapper.selectByIdForUpdate(orderId);
        if (locked == null) {
            throw new IllegalStateException("release_order disappeared during emergency release: "
                + orderId);
        }
        ReleaseItemPO current = itemMapper.selectById(itemId);
        ItemStatus status = current == null ? null : AdmissionService.statusOf(current);
        ItemEvent event = eventFor(status);
        if (event == null) {
            // Re-checked inside the transaction: the pre-check outside may be stale.
            throw refusal(locked.getPublishNo(), itemId, status);
        }
        // Nobody has to sign a release the administrator has already released. A logical no-op (an
        // order that was never submitted) returns 0; a storage failure aborts the transaction.
        int cancelled = approvalService.cancelPendingOfLatestSnapshot(orderId, "紧急放行：" + reason);
        ItemTransitions.Outcome outcome = transitions.apply(itemId, event);
        if (outcome != ItemTransitions.Outcome.APPLIED) {
            ReleaseItemPO after = itemMapper.selectById(itemId);
            throw new BizException(BizError.STATE_NOT_ALLOWED,
                "变更项状态已变化，紧急放行未生效，请刷新后重试",
                Map.of("publishNo", String.valueOf(locked.getPublishNo()), "itemId", itemId,
                    "event", event.name(), "outcome", outcome.name(),
                    "currentStatus", after == null ? "UNKNOWN"
                        : AdmissionService.statusOf(after).name()));
        }
        markOrder(locked, reason, actor, now);
        appendHistory(locked, reason, actor, now);
        return new Applied(status, cancelled);
    }

    /**
     * Writes {@code READY} and the four emergency columns with the {@code row_version} CAS every order
     * status change uses; a lost CAS aborts the transaction instead of leaving the item released and
     * the order not marked.
     */
    private void markOrder(ReleaseOrderPO locked, String reason, String actor, Instant now) {
        long orderId = locked.getId();
        int version = locked.getRowVersion() == null ? 0 : locked.getRowVersion();
        int rows = orderMapper.update(null, Wrappers.<ReleaseOrderPO>lambdaUpdate()
            .eq(ReleaseOrderPO::getId, orderId)
            .eq(ReleaseOrderPO::getRowVersion, version)
            .set(ReleaseOrderPO::getStatus, ItemStatus.READY.name())
            .set(ReleaseOrderPO::getEmergencyRelease, 1)
            .set(ReleaseOrderPO::getEmergencyReason, reason)
            .set(ReleaseOrderPO::getEmergencyBy, actor)
            .set(ReleaseOrderPO::getEmergencyAt, now)
            .set(ReleaseOrderPO::getRowVersion, version + 1));
        if (rows != 1) {
            throw new BizException(BizError.STATE_NOT_ALLOWED,
                "发布单状态已变化，紧急放行未生效，请刷新后重试",
                Map.of("publishNo", String.valueOf(locked.getPublishNo()), "orderId", orderId));
        }
    }

    private void appendHistory(ReleaseOrderPO locked, String reason, String actor, Instant now) {
        EmergencyReleasePO po = new EmergencyReleasePO();
        po.setOrderId(locked.getId());
        po.setPublishNo(locked.getPublishNo());
        po.setReason(reason);
        po.setActor(actor);
        po.setCreatedAt(now);
        emergencyReleaseMapper.insertEmergencyRelease(po);
    }

    // ------------------------------------------------------------------ validation

    private static CurrentUser requireAdmin() {
        CurrentUser user = UserContext.current();
        if (!user.hasRole(Roles.ADMIN)) {
            throw new BizException(BizError.FORBIDDEN,
                "紧急放行仅平台管理员（ADMIN）可操作",
                Map.of("roles", String.valueOf(user.roles())));
        }
        return user;
    }

    private static String requirePublishNo(String raw) {
        String no = trimToNull(raw);
        if (no == null) {
            throw new BizException(BizError.PARAM_INVALID, "发布单号不能为空",
                Map.of("publishNo", "不能为空"));
        }
        return no;
    }

    private ReleaseOrderPO requireOrder(String publishNo) {
        ReleaseOrderPO order = orderMapper.selectOne(Wrappers.<ReleaseOrderPO>lambdaQuery()
            .eq(ReleaseOrderPO::getPublishNo, publishNo));
        if (order == null) {
            throw new BizException(BizError.PARAM_INVALID, "发布单不存在：" + publishNo,
                Map.of("publishNo", publishNo));
        }
        return order;
    }

    private ReleaseItemPO requireItem(ReleaseOrderPO order) {
        ReleaseItemPO item = order.getItemId() == null ? null
            : itemMapper.selectById(order.getItemId());
        if (item == null) {
            throw new BizException(BizError.PARAM_INVALID, "变更项不存在",
                Map.of("publishNo", order.getPublishNo(), "itemId", order.getItemId()));
        }
        return item;
    }

    private static String requireReason(String raw) {
        String reason = trimToNull(raw);
        if (reason == null) {
            throw new BizException(BizError.PARAM_INVALID, "紧急放行理由不能为空",
                Map.of("reason", "不能为空"));
        }
        if (reason.length() > MAX_REASON_LENGTH) {
            throw new BizException(BizError.PARAM_INVALID,
                "紧急放行理由长度不能超过 " + MAX_REASON_LENGTH,
                Map.of("reason", "长度超限"));
        }
        return reason;
    }

    private static void requireAllowed(ReleaseOrderPO order, ReleaseItemPO item,
                                       ItemStatus status) {
        if (status == null || !ALLOWED.contains(status)) {
            throw refusal(order.getPublishNo(), item.getId(), status);
        }
    }

    /** The 40001 body, with the design's wording for the two special states (design §1). */
    private static BizException refusal(String publishNo, long itemId, ItemStatus status) {
        String current = status == null ? "UNKNOWN" : status.name();
        String message;
        if (status == ItemStatus.READY) {
            message = "发布单已经是待执行状态（READY），无需紧急放行";
        } else {
            message = "只有已提交（SUBMITTED）或待审批（PENDING_APPROVAL）的发布单可以紧急放行，当前是 "
                + current + "；请先走正常提交或重跑";
        }
        return new BizException(BizError.STATE_NOT_ALLOWED, message,
            Map.of("publishNo", publishNo, "itemId", itemId, "currentStatus", current));
    }

    /** The state-machine event of an allowed status, or {@code null} (the matrix of design §1). */
    private static ItemEvent eventFor(ItemStatus status) {
        if (status == ItemStatus.PENDING_APPROVAL) {
            return ItemEvent.APPROVE_ALL;
        }
        if (status == ItemStatus.SUBMITTED) {
            return ItemEvent.NO_APPROVAL;
        }
        return null;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    // ------------------------------------------------------------------ audit and notification

    private void auditEmergency(long orderId, String publishNo, String crid, long unitId,
                                Applied applied, String actor, String reason, Instant at) {
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("itemStatus", applied.before() == null ? "UNKNOWN"
            : applied.before().name());
        before.put("emergencyRelease", false);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("itemStatus", ItemStatus.READY.name());
        after.put("orderStatus", ItemStatus.READY.name());
        after.put("emergencyRelease", true);
        after.put("emergencyReason", reason);
        after.put("emergencyBy", actor);
        after.put("emergencyAt", at);
        after.put("approvalsCancelled", applied.cancelled());
        AuditCommand.Builder builder = AuditCommand.of(AuditAction.EMERGENCY_RELEASE, publishNo,
                publishNo, "紧急放行：发布单 " + publishNo
                    + "（变更项 " + (crid == null ? "" : crid) + "）")
            .reason(reason)
            .before(before)
            .after(after);
        if (unitId > 0) {
            builder.scopeUnitId(unitId);
            Long subsystemId = subsystemOfUnit(unitId);
            if (subsystemId != null && subsystemId > 0) {
                builder.scopeSubsystemId(subsystemId);
            }
        }
        audit.record(builder.build());
        log.debug("emergency release audited: orderId={}, publishNo={}", orderId, publishNo);
    }

    private Long subsystemOfUnit(long unitId) {
        DeployUnitPO unit = unitMapper.selectById(unitId);
        return unit == null ? null : unit.getSubsystemId();
    }

    /**
     * {@code EMERGENCY_RELEASE}: the task's OPS/DEV members plus its creator (the same audience as
     * the P5-B stop/exit events). The body names the administrator, the reason and what was skipped
     * versus what still runs, so the recipients can act on it.
     */
    private void notifyEmergency(long orderId, String publishNo, String crid, String actor,
                                 String reason, Instant at) {
        StringBuilder body = new StringBuilder("发布单 ").append(publishNo);
        if (crid != null) {
            body.append("（变更项 ").append(crid).append("）");
        }
        body.append(" 已被管理员 ").append(actor).append(" 紧急放行。原因：").append(reason)
            .append("。待签审批已作废，封板等待已跳过；镜像/门禁/豁免复核仍照常执行。")
            .append("紧急放行不可撤销，再次发布需重跑。");
        notifications.notify(new NotificationCommand(NotificationEventKey.EMERGENCY_RELEASE,
            publishNo, publishNo, "紧急放行：发布单 " + publishNo, body.toString(),
            "emergency:" + orderId + ":" + at.toEpochMilli(),
            NotificationAudiences.taskMembers(orderId, EnumSet.of(MemberRole.OPS,
                MemberRole.DEV))));
    }

    // ------------------------------------------------------------------ response records

    /** What the endpoint answers (design §3): the mirrored statuses and the fixed {@code true}. */
    public record EmergencyResult(String status, String itemStatus, boolean emergency) {
    }

    /** Internal carrier of the committed transaction: the previous item status and the voids. */
    private record Applied(ItemStatus before, int cancelled) {
    }
}