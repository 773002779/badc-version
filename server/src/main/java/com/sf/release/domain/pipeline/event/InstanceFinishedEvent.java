package com.sf.release.domain.pipeline.event;

import com.sf.release.domain.pipeline.InstanceStatus;

import java.time.Instant;
import java.util.Objects;

/**
 * A pipeline instance reached a terminal state ({@code DONE}, {@code FAILED}, ...).
 *
 * <p>{@code reason} is always filled for a failure and names the concrete cause, for example
 * the failed task's message or "部署单元锁丢失" when the unit lease could not be renewed. It is
 * the only durable failure explanation of an instance (the {@code pipeline_instance} table has
 * no reason column), so it is also logged.
 */
public record InstanceFinishedEvent(
    long instanceId,
    long orderId,
    InstanceStatus status,
    String reason,
    Instant endedAt) {

    public InstanceFinishedEvent {
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(endedAt, "endedAt must not be null");
    }
}