package com.sf.release.application;

import java.time.Instant;

/**
 * One rollback record as the page shows it (rollback design section 4): the source release that was
 * online, the target image, who asked, why, and the result.
 *
 * <p>{@code status} is the stable machine word ({@code RUNNING}/{@code SUCCESS}/{@code FAILED}) and
 * {@code statusLabel} its Chinese label, the same convention the freeze and admission APIs use. Both
 * are plain strings so the web layer never imports a domain enum.
 */
public record RollbackRecordView(
    long id,
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
    String status,
    String statusLabel,
    String failureReason,
    Instant createdAt,
    Instant finishedAt) {
}