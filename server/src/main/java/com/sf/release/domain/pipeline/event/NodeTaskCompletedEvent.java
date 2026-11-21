package com.sf.release.domain.pipeline.event;

import com.sf.release.domain.pipeline.NodeTaskStatus;
import com.sf.release.domain.pipeline.NodeType;
import com.sf.release.domain.pipeline.TaskType;

import java.time.Instant;
import java.util.Objects;

/**
 * One {@code node_task} reached a terminal result (SUCCESS/FAILED/TIMEOUT/SKIPPED). Published
 * after the idempotent completion CAS changed the row, so a duplicate or late callback never
 * produces a second event.
 *
 * <p>{@code digest} is the image digest reported with a successful {@code IMG_BUILD} task (it
 * is persisted on the pipeline instance); {@code message} is the engine/failure reason written
 * to {@code node_task.result_summary}.
 */
public record NodeTaskCompletedEvent(
    long instanceId,
    long taskId,
    NodeType nodeType,
    TaskType taskType,
    NodeTaskStatus status,
    String digest,
    String message,
    Instant occurredAt) {

    public NodeTaskCompletedEvent {
        Objects.requireNonNull(nodeType, "nodeType must not be null");
        Objects.requireNonNull(taskType, "taskType must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
    }
}