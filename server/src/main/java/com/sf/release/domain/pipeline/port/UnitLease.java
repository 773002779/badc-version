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
}