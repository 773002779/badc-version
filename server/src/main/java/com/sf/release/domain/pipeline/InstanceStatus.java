package com.sf.release.domain.pipeline;

/**
 * Lifecycle of a {@link PipelineInstance}. The terminal states named by tech design §9 are
 * {@code DONE}, {@code FAILED}, {@code CLOSED} and {@code TERMINATED}; a newly created
 * instance is {@code PENDING} until the orchestrator starts its first node.
 *
 * <p><strong>{@code PAUSED} — an explicit stop, not a failure.</strong> The instance is
 * {@code PAUSED} when it has to enter a node that has no registered {@code NodeHandler} (in
 * phase 1: {@code VERIFY} and {@code PROD_DEPLOY}, which belong to later tasks). The node that
 * blocked the pipeline stays {@code current_node} and {@code status_reason} says why. Pausing
 * is written together with the completion of the previous node, so the work already done is
 * never rolled back and the reconcile loop cannot replay the same failing entry forever. In
 * phase 1 nothing resumes a paused instance, so it behaves as a stopped state: it releases its
 * deployment-unit lease and is not advanced again.
 */
public enum InstanceStatus {
    PENDING,
    RUNNING,
    PAUSED,
    DONE,
    FAILED,
    CLOSED,
    TERMINATED
}