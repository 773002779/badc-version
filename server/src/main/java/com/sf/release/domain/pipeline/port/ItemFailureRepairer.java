package com.sf.release.domain.pipeline.port;

/**
 * Drains the durable record of failed 变更项 transitions (V12 {@code pipeline_item_failure}).
 * The pipeline event listener persists a row when an item transition cannot be applied; the
 * 30s reconcile calls this port to replay it.
 *
 * <p>Implementations must be idempotent: replaying a transition that already happened is a
 * no-op (the item state machine plus the {@code row_version} CAS decide), and a row that was
 * resolved by an earlier round must not be applied again.
 */
public interface ItemFailureRepairer {

    /**
     * Retries the unresolved failures that still have attempts left.
     *
     * @param limit maximum rows handled by one call
     * @return the number of rows resolved by this call
     */
    int retryUnresolved(int limit);
}