package com.sf.release.infra.repo.po;

/**
 * One live {@code deploy_unit_lock} row joined to its pipeline instance (P3 fix round): the unit and
 * instance whose lease the renewer must extend. A plain projection, not an aggregate — the job only
 * needs the two ids.
 */
public class LockedInstanceRow {

    private Long unitId;
    private Long instanceId;

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
}