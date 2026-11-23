package com.sf.release.infra.repo.po;

import java.time.Instant;

/**
 * One live {@code deploy_unit_lock} row joined to its pipeline instance (P3 fix round): the unit and
 * instance whose lease the renewer must extend, plus the instance status and the moment it paused.
 *
 * <p>{@code pausedAt} is what lets the renewer stop extending an abandoned pause (V24): a rejected
 * release nobody resubmits stays {@code PAUSED} forever, and an unbounded renewal would lock its
 * deployment unit for good. It is {@code null} for a running instance and for a pause recorded
 * before V24.
 */
public class LockedInstanceRow {

    private Long unitId;
    private Long instanceId;
    private String status;
    private Instant pausedAt;

    public Long getUnitId() {
        return unitId;
    }

    public void setUnitId(Long unitId) {
        this.unitId = unitId;
    }

    public Long getInstanceId() {
        return instanceId;
    }

    public void setInstanceId(Long instanceId) {
        this.instanceId = instanceId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Instant getPausedAt() {
        return pausedAt;
    }

    public void setPausedAt(Instant pausedAt) {
        this.pausedAt = pausedAt;
    }
}