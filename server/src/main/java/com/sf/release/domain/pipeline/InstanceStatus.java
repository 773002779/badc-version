package com.sf.release.domain.pipeline;

/**
 * Lifecycle of a {@link PipelineInstance}. The terminal states named by tech design §9 are
 * {@code DONE}, {@code FAILED}, {@code CLOSED} and {@code TERMINATED}; a newly created
 * instance is {@code PENDING} until the orchestrator starts its first node.
 */
public enum InstanceStatus {
    PENDING,
    RUNNING,
    DONE,
    FAILED,
    CLOSED,
    TERMINATED
}