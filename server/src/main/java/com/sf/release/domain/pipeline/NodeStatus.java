package com.sf.release.domain.pipeline;

/**
 * Lifecycle of a {@link PipelineNode}. A node is the grouping unit the orchestrator advances:
 * it becomes {@code SUCCESS} only when every task under it succeeded, {@code FAILED} when one
 * task failed (remaining tasks are skipped), and {@code SKIPPED} when an earlier node failed.
 */
public enum NodeStatus {
    PENDING,
    RUNNING,
    SUCCESS,
    FAILED,
    SKIPPED
}