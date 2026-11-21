package com.sf.release.domain.pipeline.port;

/**
 * Asks the orchestrator to advance one instance, without exposing it to callers that must not
 * hold a reference to it.
 *
 * <p><strong>Why a port.</strong> The gate use cases have to re-drive the pipeline after a check
 * result or a test decision (the {@code VERIFY} node completes on the gate, not on task rows), but
 * they are also <em>used by</em> the {@code VERIFY} node handler, which the orchestrator owns
 * through {@link com.sf.release.domain.pipeline.NodeHandlerRegistry}. A direct dependency would be
 * a construction cycle. This one-method port breaks it: the implementation gets the orchestrator
 * lazily, and tests pass a lambda.
 *
 * <p>An implementation must be idempotent: {@code advance} already is (status CAS plus the
 * instance lock), so callers may ask for an advance whenever they changed something that could
 * unblock the node.
 */
public interface PipelineAdvancer {

    /** Advances {@code instanceId} if it is not terminal; a no-op when the instance is gone. */
    void advance(long instanceId);
}