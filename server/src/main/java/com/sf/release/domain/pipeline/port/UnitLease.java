package com.sf.release.domain.pipeline.port;

/**
 * Renewal half of the 部署单元 lock needed by the pipeline (tech design §9: the unit lease is
 * four hours and must be extended while the pipeline runs).
 */
public interface UnitLease {

    /**
     * Extends the unit lock of {@code instanceId}.
     *
     * @return {@code false} when the row no longer belongs to that instance (the lock was lost)
     */
    boolean renew(long unitId, long instanceId);

    /**
     * Releases the unit lock only when the {@code deploy_unit_lock} row still belongs to
     * {@code instanceId} (tech design §9: release when the instance reaches a terminal state).
     * Ownership scoping means a caller that already lost the lock cannot free the new holder's
     * row or key.
     *
     * @return {@code true} when this call removed the row (and the distributed key)
     */
    boolean release(long unitId, long instanceId);
}