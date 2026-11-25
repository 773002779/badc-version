package com.sf.release.application;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sf.release.application.notification.NotificationAudiences;
import com.sf.release.application.notification.NotificationCommand;
import com.sf.release.application.notification.NotificationService;
import com.sf.release.domain.notification.NotificationEventKey;
import com.sf.release.domain.pipeline.InstanceStatus;
import com.sf.release.domain.pipeline.TriggerType;
import com.sf.release.domain.pipeline.event.InstanceFinishedEvent;
import com.sf.release.domain.rollback.RollbackRecord;
import com.sf.release.domain.rollback.RollbackStatus;
import com.sf.release.domain.task.ItemEvent;
import com.sf.release.domain.task.ItemStatus;
import com.sf.release.infra.repo.PipelineInstanceMapper;
import com.sf.release.infra.repo.ReleaseOrderMapper;
import com.sf.release.infra.repo.RollbackRecordMapper;
import com.sf.release.infra.repo.po.PipelineInstancePO;
import com.sf.release.infra.repo.po.ReleaseOrderPO;
import com.sf.release.infra.repo.po.RollbackRecordPO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;

/**
 * Converges one rollback record when its pipeline instance reaches a terminal state (rollback design
 * section 5, P4-A R2).
 *
 * <p><strong>{@code DONE} → record {@code SUCCESS}.</strong> The source 变更项 is moved
 * {@code DONE → ROLLED_BACK} through the same state machine + {@code row_version} CAS as every other
 * item transition, and the source release order mirrors {@code ROLLED_BACK}. When the source item is
 * <em>no longer</em> {@code DONE} (a rerun or a concurrent release already moved it), only the record
 * success is written and the item/order are left alone: this rollback did not change them.
 *
 * <p><strong>{@code FAILED} → record {@code FAILED} + reason.</strong> The 变更项 is deliberately not
 * changed by a failed rollback: the previous image is still what production runs, so pretending the
 * release was rolled back would be false.
 *
 * <p><strong>Idempotency.</strong> The record status is a compare-and-set from {@code RUNNING}, so a
 * duplicated {@link InstanceFinishedEvent} (a replay, a second listener invocation) finds nothing to
 * update and does nothing. An instance without a record (every normal release) is ignored at the
 * first query.
 *
 * <p><strong>Transaction.</strong> The listener runs in the pipeline advance transaction (the event
 * is published inside it), so the record and the item/order converge with the instance status instead
 * of being a best-effort side effect. Item/order failures are caught and logged: the record must not
 * be lost because the source order disappeared, and the pipeline transaction must keep committing.
 */
@Service
public class RollbackCompletionService {

    private static final Logger log = LoggerFactory.getLogger(RollbackCompletionService.class);

    /** The column is {@code VARCHAR(255)} (V25). */
    private static final int MAX_FAILURE_REASON_LENGTH = 255;

    /** How many times a lost item-status CAS is re-read before the completion gives up. */
    private static final int MAX_CAS_ATTEMPTS = 3;

    private final RollbackRecordMapper recordMapper;
    private final PipelineInstanceMapper instanceMapper;
    private final ReleaseOrderMapper orderMapper;
    private final ItemTransitions transitions;
    private final NotificationService notifications;

    public RollbackCompletionService(RollbackRecordMapper recordMapper,
                                     PipelineInstanceMapper instanceMapper,
                                     ReleaseOrderMapper orderMapper,
                                     ItemTransitions transitions,
                                     NotificationService notifications) {
        this.recordMapper = Objects.requireNonNull(recordMapper, "recordMapper must not be null");
        this.instanceMapper = Objects.requireNonNull(instanceMapper,
            "instanceMapper must not be null");
        this.orderMapper = Objects.requireNonNull(orderMapper, "orderMapper must not be null");
        this.transitions = Objects.requireNonNull(transitions, "transitions must not be null");
        this.notifications = Objects.requireNonNull(notifications,
            "notifications must not be null");
    }

    @EventListener
    @Transactional
    public void onInstanceFinished(InstanceFinishedEvent event) {
        if (event.status() != InstanceStatus.DONE && event.status() != InstanceStatus.FAILED
                && event.status() != InstanceStatus.TERMINATED) {
            // A pause or a close is not a rollback result: the record stays RUNNING (an abandoned
            // rollback is visible as RUNNING, which is the truth).
            return;
        }
        RollbackRecordPO po = recordMapper.selectByInstanceId(event.instanceId());
        if (po == null) {
            return;
        }
        PipelineInstancePO instance = instanceMapper.selectById(event.instanceId());
        if (instance == null
                || !TriggerType.ROLLBACK.name().equals(instance.getTriggerType())) {
            log.warn("rollback record {} belongs to a non-rollback instance {}; ignored",
                po.getId(), event.instanceId());
            return;
        }
        RollbackRecord record = po.toDomain();
        if (record.status() != RollbackStatus.RUNNING) {
            log.debug("rollback record {} already finished as {}; duplicate event ignored",
                record.id(), record.status());
            return;
        }
        if (event.status() == InstanceStatus.DONE) {
            completeSuccess(record, event.endedAt());
        } else {
            completeFailure(record, event.reason(), event.endedAt());
        }
    }

    /**
     * Marks the record {@code SUCCESS} and, only when the source item is still {@code DONE}, applies
     * {@code ROLLBACK} to it and mirrors {@code ROLLED_BACK} on the source order.
     */
    private void completeSuccess(RollbackRecord record, Instant now) {
        if (!finishRecord(record.id(), RollbackStatus.SUCCESS, null, now)) {
            return;
        }
        notifyRollback(record, true, null);
        if (record.sourceItemId() == null) {
            log.info("rollback {} succeeded without a source item; no item state to change",
                record.id());
            return;
        }
        ItemTransitions.Outcome outcome;
        try {
            outcome = rollbackItem(record.sourceItemId());
        } catch (RuntimeException ex) {
            log.error("rollback {} succeeded but its source item {} could not be transitioned: {}",
                record.id(), record.sourceItemId(), ex.getMessage(), ex);
            return;
        }
        if (outcome != ItemTransitions.Outcome.APPLIED) {
            log.info("rollback {} succeeded; source item {} is no longer DONE ({}),"
                    + " item/order state unchanged", record.id(), record.sourceItemId(), outcome);
            return;
        }
        syncOrderRolledBack(record.sourceOrderId());
        log.info("rollback {} succeeded: source item {} moved to ROLLED_BACK", record.id(),
            record.sourceItemId());
    }

    /**
     * Applies {@code DONE → ROLLED_BACK}, re-reading on a lost {@code row_version} CAS. A concurrent
     * writer that moved the item away from {@code DONE} then yields {@code NOT_ALLOWED}, which is
     * the "no change" case the design describes.
     */
    private ItemTransitions.Outcome rollbackItem(long itemId) {
        ItemTransitions.Outcome outcome = ItemTransitions.Outcome.CAS_LOST;
        for (int attempt = 1; attempt <= MAX_CAS_ATTEMPTS; attempt++) {
            outcome = transitions.apply(itemId, ItemEvent.ROLLBACK);
            if (outcome != ItemTransitions.Outcome.CAS_LOST) {
                return outcome;
            }
            log.debug("rollback item transition lost a CAS, retrying ({}/{}): itemId={}", attempt,
                MAX_CAS_ATTEMPTS, itemId);
        }
        return outcome;
    }

    /** Marks the record {@code FAILED}; the source item is deliberately not changed. */
    private void completeFailure(RollbackRecord record, String reason, Instant now) {
        String failure = truncate(reason == null || reason.isBlank() ? "流水线失败" : reason,
            MAX_FAILURE_REASON_LENGTH);
        if (!finishRecord(record.id(), RollbackStatus.FAILED, failure, now)) {
            return;
        }
        notifyRollback(record, false, failure);
        log.info("rollback {} failed; source item {} kept as it was", record.id(),
            record.sourceItemId());
    }

    /**
     * {@code ROLLBACK_DONE} (design section 3): one event per finished rollback, success and failure
     * distinguished by the title and the body. The ref carries the terminal status so the two
     * records of one instance (impossible by the CAS, but explicit) could not collide; the instance
     * cannot finish twice, so the ref is in practice the record id.
     */
    private void notifyRollback(RollbackRecord record, boolean success, String failureReason) {
        if (notifications == null || record.id() == null) {
            return;
        }
        String title = success ? "回滚完成" : "回滚失败";
        StringBuilder body = new StringBuilder("部署单元 #").append(record.unitId())
            .append(success ? " 已回滚到镜像 " : " 回滚到镜像 ").append(record.targetDigest());
        if (record.targetPublishNo() != null) {
            body.append("（发布单 ").append(record.targetPublishNo()).append("）");
        }
        if (!success) {
            body.append(" 失败：").append(failureReason == null ? "流水线失败" : failureReason);
        }
        body.append('。');
        notifications.notify(new NotificationCommand(
            NotificationEventKey.ROLLBACK_DONE, String.valueOf(record.unitId()), null, title,
            body.toString(), record.id() + ":" + (success ? "SUCCESS" : "FAILED"),
            NotificationAudiences.rollback(record.unitId(), record.sourceOrderId(),
                record.requestedBy())));
    }

    /**
     * The terminal status compare-and-set from {@code RUNNING}.
     *
     * @return {@code true} when this call finished the record
     */
    private boolean finishRecord(long recordId, RollbackStatus status, String failureReason,
                                 Instant now) {
        int rows = recordMapper.update(null, Wrappers.<RollbackRecordPO>lambdaUpdate()
            .eq(RollbackRecordPO::getId, recordId)
            .eq(RollbackRecordPO::getStatus, RollbackStatus.RUNNING.name())
            .set(RollbackRecordPO::getStatus, status.name())
            .set(RollbackRecordPO::getFailureReason, failureReason)
            .set(RollbackRecordPO::getFinishedAt, now));
        if (rows != 1) {
            log.debug("rollback record {} was not finished as {}: another event won",
                recordId, status);
            return false;
        }
        return true;
    }

    /**
     * Mirrors {@code ROLLED_BACK} on the source release order with the {@code row_version} CAS every
     * order status change uses. A failure is logged instead of thrown: the record success is the
     * rollback's own result and must survive an unrelated order write.
     */
    private void syncOrderRolledBack(Long orderId) {
        if (orderId == null || orderId <= 0) {
            return;
        }
        try {
            ReleaseOrderPO current = orderMapper.selectByIdForUpdate(orderId);
            if (current == null) {
                log.warn("source release order {} of the rollback disappeared", orderId);
                return;
            }
            String rolledBack = ItemStatus.ROLLED_BACK.name();
            if (rolledBack.equals(current.getStatus())) {
                return;
            }
            int version = current.getRowVersion() == null ? 0 : current.getRowVersion();
            int rows = orderMapper.update(null, Wrappers.<ReleaseOrderPO>lambdaUpdate()
                .eq(ReleaseOrderPO::getId, orderId)
                .eq(ReleaseOrderPO::getRowVersion, version)
                .set(ReleaseOrderPO::getStatus, rolledBack)
                .set(ReleaseOrderPO::getRowVersion, version + 1));
            if (rows != 1) {
                log.warn("source release order {} changed concurrently; ROLLED_BACK not mirrored",
                    orderId);
            }
        } catch (RuntimeException ex) {
            log.error("could not mirror ROLLED_BACK on source release order {}: {}", orderId,
                ex.getMessage(), ex);
        }
    }

    private static String truncate(String value, int maxLength) {
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}