package com.sf.release.domain.rollback;

import java.time.Instant;

/**
 * One production rollback record (rollback design section 2-5): who rolled back which deployment
 * unit, from which online version to which target image, why, and how it ended.
 *
 * <p><strong>Why a separate record and not a release order.</strong> A rollback is a stop-loss
 * action, not a release: it does not create a new 发布单, needs no approval and must not show up as
 * another publish in the release records. It still names the source release
 * ({@code sourceOrderId}/{@code sourceItemId}/{@code sourceDigest}/{@code sourceCommit}) so the
 * evidence survives after the source order changed, and the target version
 * ({@code targetDigest}/{@code targetPublishNo}) so the exact image is auditable.
 *
 * <p>The record starts {@link RollbackStatus#RUNNING} in the same transaction that creates the
 * pipeline instance, and becomes {@code SUCCESS}/{@code FAILED} when the instance finishes. Text
 * fields are trimmed; blank values that are optional become {@code null}.
 */
public record RollbackRecord(
    Long id,
    long unitId,
    Long instanceId,
    String pipelineNo,
    Long sourceOrderId,
    Long sourceItemId,
    String sourcePublishNo,
    String sourceDigest,
    String sourceCommit,
    String targetDigest,
    String targetPublishNo,
    String reason,
    String requestedBy,
    RollbackStatus status,
    String failureReason,
    Instant createdAt,
    Instant finishedAt) {

    public RollbackRecord {
        pipelineNo = trimToNull(pipelineNo);
        sourcePublishNo = trimToNull(sourcePublishNo);
        sourceDigest = trimToNull(sourceDigest);
        sourceCommit = trimToNull(sourceCommit);
        targetDigest = trimToNull(targetDigest);
        targetPublishNo = trimToNull(targetPublishNo);
        failureReason = trimToNull(failureReason);
        status = status == null ? RollbackStatus.RUNNING : status;
    }

    /** A fresh record, before the instance exists; the caller inserts then binds the instance. */
    public static RollbackRecord running(long unitId, Long sourceOrderId, Long sourceItemId,
                                         String sourcePublishNo, String sourceDigest,
                                         String sourceCommit, String targetDigest,
                                         String targetPublishNo, String reason, String requestedBy,
                                         Instant now) {
        return new RollbackRecord(null, unitId, null, null, sourceOrderId, sourceItemId,
            sourcePublishNo, sourceDigest, sourceCommit, targetDigest, targetPublishNo, reason,
            requestedBy, RollbackStatus.RUNNING, null, now, null);
    }

    /** The same record after the instance id and its display number were assigned. */
    public RollbackRecord withInstance(Long newInstanceId, String newPipelineNo) {
        return new RollbackRecord(id, unitId, newInstanceId, newPipelineNo, sourceOrderId,
            sourceItemId, sourcePublishNo, sourceDigest, sourceCommit, targetDigest,
            targetPublishNo, reason, requestedBy, status, failureReason, createdAt, finishedAt);
    }

    /** The terminal success state. */
    public RollbackRecord succeeded(Instant finishedAt) {
        return new RollbackRecord(id, unitId, instanceId, pipelineNo, sourceOrderId, sourceItemId,
            sourcePublishNo, sourceDigest, sourceCommit, targetDigest, targetPublishNo, reason,
            requestedBy, RollbackStatus.SUCCESS, null, createdAt, finishedAt);
    }

    /** The terminal failure state; the 变更项 is deliberately not changed by a failure. */
    public RollbackRecord failed(String failureReason, Instant finishedAt) {
        return new RollbackRecord(id, unitId, instanceId, pipelineNo, sourceOrderId, sourceItemId,
            sourcePublishNo, sourceDigest, sourceCommit, targetDigest, targetPublishNo, reason,
            requestedBy, RollbackStatus.FAILED, failureReason, createdAt, finishedAt);
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}