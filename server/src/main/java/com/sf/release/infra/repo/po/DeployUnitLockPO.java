package com.sf.release.infra.repo.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;

/**
 * Persistence object for {@code deploy_unit_lock}. {@code unit_id} is the primary key (one
 * holder per unit); the row is deleted when the instance reaches a terminal state or the
 * holder releases it, and an {@code expire_at} in the past may be reclaimed.
 */
@TableName("deploy_unit_lock")
public class DeployUnitLockPO {

    @TableId(value = "unit_id", type = IdType.INPUT)
    private Long unitId;

    private Long instanceId;
    private Instant acquiredAt;
    private Instant expireAt;

    public static DeployUnitLockPO of(long unitId, long instanceId, Instant acquiredAt,
                                      Instant expireAt) {
        DeployUnitLockPO po = new DeployUnitLockPO();
        po.unitId = unitId;
        po.instanceId = instanceId;
        po.acquiredAt = acquiredAt;
        po.expireAt = expireAt;
        return po;
    }

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

    public Instant getAcquiredAt() {
        return acquiredAt;
    }

    public void setAcquiredAt(Instant acquiredAt) {
        this.acquiredAt = acquiredAt;
    }

    public Instant getExpireAt() {
        return expireAt;
    }

    public void setExpireAt(Instant expireAt) {
        this.expireAt = expireAt;
    }
}