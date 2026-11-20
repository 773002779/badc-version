package com.sf.release.domain.pipeline;

/**
 * Lifecycle of a {@link NodeTask} (tech design §5.1):
 * {@code PENDING, RUNNING, WAITING_EXTERNAL, SUCCESS, FAILED, TIMEOUT, SKIPPED}.
 *
 * <p>{@code WAITING_EXTERNAL} is the state of a long-running engine call that is tracked by
 * {@code external_id}; it is resolved by a callback or by reconciliation.
 */
public enum NodeTaskStatus {
    PENDING,
    RUNNING,
    WAITING_EXTERNAL,
    SUCCESS,
    FAILED,
    TIMEOUT,
    SKIPPED
}