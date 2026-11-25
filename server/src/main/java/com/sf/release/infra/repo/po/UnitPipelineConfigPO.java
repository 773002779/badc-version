package com.sf.release.infra.repo.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.sf.release.domain.pipeline.BatchOrder;
import com.sf.release.domain.pipeline.UnitPipelineConfig;

import java.time.Instant;

/**
 * Persistence object for {@code unit_pipeline_config} (V31, P6-C design §1).
 *
 * <p>One row per deployment unit; the unit id is the primary key, so a save is an update or an
 * insert (upsert) and a missing row means "the documented defaults". The columns are nullable only
 * at this layer's defensive edge: the DDL makes them {@code NOT NULL} with defaults, but a row
 * written by a foreign build must still be readable, so {@link #toDomain()} substitutes the
 * constant when a field comes back null.
 */
@TableName("unit_pipeline_config")
public class UnitPipelineConfigPO {

    @TableId(value = "unit_id", type = IdType.INPUT)
    private Long unitId;

    private Boolean envChecksEnabled;
    private Integer buildTimeoutMinutes;
    private Integer deployTimeoutMinutes;
    private String batchOrder;
    private String updatedBy;
    private Instant updatedAt;

    /** The domain value, with an out-of-range/missing column degraded to the constant default. */
    public UnitPipelineConfig toDomain() {
        return new UnitPipelineConfig(
            !Boolean.FALSE.equals(envChecksEnabled),
            buildTimeoutMinutes == null
                ? UnitPipelineConfig.DEFAULT_BUILD_TIMEOUT_MINUTES : buildTimeoutMinutes,
            deployTimeoutMinutes == null
                ? UnitPipelineConfig.DEFAULT_DEPLOY_TIMEOUT_MINUTES : deployTimeoutMinutes,
            batchOrder == null ? BatchOrder.FORWARD : BatchOrder.parse(batchOrder));
    }

    public Long getUnitId() {
        return unitId;
    }

    public void setUnitId(Long unitId) {
        this.unitId = unitId;
    }

    public Boolean getEnvChecksEnabled() {
        return envChecksEnabled;
    }

    public void setEnvChecksEnabled(Boolean envChecksEnabled) {
        this.envChecksEnabled = envChecksEnabled;
    }

    public Integer getBuildTimeoutMinutes() {
        return buildTimeoutMinutes;
    }

    public void setBuildTimeoutMinutes(Integer buildTimeoutMinutes) {
        this.buildTimeoutMinutes = buildTimeoutMinutes;
    }

    public Integer getDeployTimeoutMinutes() {
        return deployTimeoutMinutes;
    }

    public void setDeployTimeoutMinutes(Integer deployTimeoutMinutes) {
        this.deployTimeoutMinutes = deployTimeoutMinutes;
    }

    public String getBatchOrder() {
        return batchOrder;
    }

    public void setBatchOrder(String batchOrder) {
        this.batchOrder = batchOrder;
    }

    public String getUpdatedBy() {
        return updatedBy;
    }

    public void setUpdatedBy(String updatedBy) {
        this.updatedBy = updatedBy;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}