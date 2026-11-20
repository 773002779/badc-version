package com.sf.release.domain.pipeline;

import java.time.Instant;

/**
 * One task under a {@link PipelineNode}. The idempotency key of the table is
 * {@code (instanceId, nodeType, taskType, batchNo, targetId, attempt)}; the record mirrors it
 * with the two sentinel columns defaulted to {@code 0} instead of {@code NULL}.
 *
 * <p><strong>targetId semantics.</strong> For a {@code DEPLOY} task it is the id of the
 * {@code env_de} to deploy to; for a {@code VERIFY/CHECK} task it is the stable
 * {@link CheckType#code()} because a check has no DE target. {@code batchNo} is the
 * {@code deploy_batch_no} of a production-family DE and {@code 0} for every other task.
 *
 * <p>Every pre-generated task starts {@link NodeTaskStatus#PENDING} with {@code attempt = 0}
 * and no external tracking fields; the orchestrator fills those in later tasks.
 */
public record NodeTask(
    Long id,
    Long instanceId,
    NodeType nodeType,
    TaskType taskType,
    int batchNo,
    long targetId,
    NodeTaskStatus status,
    int attempt,
    String externalId,
    String externalUrl,
    String resultSummary,
    Instant deadlineAt,
    Instant startedAt,
    Instant finishedAt,
    Long durationMs) {

    public NodeTask {
        batchNo = Math.max(batchNo, 0);
        attempt = Math.max(attempt, 0);
        status = status == null ? NodeTaskStatus.PENDING : status;
        externalId = trimToNull(externalId);
        externalUrl = trimToNull(externalUrl);
        resultSummary = trimToNull(resultSummary);
    }

    /** A pre-generated task without an id/instance id yet. */
    public static NodeTask pending(NodeType nodeType, TaskType taskType, int batchNo,
                                   long targetId) {
        return new NodeTask(null, null, nodeType, taskType, batchNo, targetId,
            NodeTaskStatus.PENDING, 0, null, null, null, null, null, null, null);
    }

    /**
     * The check type of a {@code VERIFY/CHECK} task, decoded from {@code targetId};
     * {@code null} for every other task.
     */
    public CheckType checkType() {
        return taskType == TaskType.CHECK ? CheckType.fromCode(targetId) : null;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}