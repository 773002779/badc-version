package com.sf.release.domain.pipeline.event;

import com.sf.release.domain.pipeline.NodeStatus;
import com.sf.release.domain.pipeline.NodeType;

import java.time.Instant;
import java.util.Objects;

/**
 * A pipeline node finished, either {@link NodeStatus#SUCCESS} or {@link NodeStatus#FAILED}.
 * The application layer maps it to the 变更项 main-state events of tech design §3.2
 * ({@code BUILD_OK}, {@code BUILD_FAIL}, {@code TEST_DEPLOY_OK}, {@code TEST_DEPLOY_FAIL},
 * {@code PROD_OK}, {@code PROD_FAIL}).
 *
 * <p>{@code orderId} is {@code 0} for an instance without a release order (the daily pipeline);
 * such an instance does not change an item state.
 */
public record NodeCompletedEvent(
    long instanceId,
    long orderId,
    NodeType nodeType,
    NodeStatus status,
    String message,
    Instant occurredAt) {

    public NodeCompletedEvent {
        Objects.requireNonNull(nodeType, "nodeType must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
    }
}