package com.sf.release.infra.repo.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.sf.release.domain.resource.EnvDe;
import com.sf.release.domain.resource.EnvKind;

import java.time.Instant;

/** Persistence object for {@code env_de}; {@code deploy_batch_no} is {@code NOT NULL DEFAULT 0}. */
@TableName("env_de")
public class EnvDePO {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long unitId;
    private String envKind;
    private String envTag;
    private String deName;
    private String resourceName;
    private String codingDeployTarget;
    private int deployBatchNo;
    private Instant createdAt;
    private Instant updatedAt;

    public static EnvDePO of(EnvDe de) {
        EnvDePO po = new EnvDePO();
        po.id = de.id();
        po.unitId = de.unitId();
        po.envKind = de.envKind().name();
        po.envTag = de.envTag();
        po.deName = de.deName();
        po.resourceName = de.resourceName();
        po.codingDeployTarget = de.codingDeployTarget();
        po.deployBatchNo = de.deployBatchNo();
        po.createdAt = de.createdAt();
        po.updatedAt = de.updatedAt();
        return po;
    }

    public EnvDe toDomain() {
        return new EnvDe(id, unitId, EnvKind.valueOf(envKind), envTag, deName, resourceName,
            codingDeployTarget, deployBatchNo, createdAt, updatedAt);
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getUnitId() {
        return unitId;
    }

    public void setUnitId(Long unitId) {
        this.unitId = unitId;
    }

    public String getEnvKind() {
        return envKind;
    }

    public void setEnvKind(String envKind) {
        this.envKind = envKind;
    }

    public String getEnvTag() {
        return envTag;
    }

    public void setEnvTag(String envTag) {
        this.envTag = envTag;
    }

    public String getDeName() {
        return deName;
    }

    public void setDeName(String deName) {
        this.deName = deName;
    }

    public String getResourceName() {
        return resourceName;
    }

    public void setResourceName(String resourceName) {
        this.resourceName = resourceName;
    }

    public String getCodingDeployTarget() {
        return codingDeployTarget;
    }

    public void setCodingDeployTarget(String codingDeployTarget) {
        this.codingDeployTarget = codingDeployTarget;
    }

    public int getDeployBatchNo() {
        return deployBatchNo;
    }

    public void setDeployBatchNo(int deployBatchNo) {
        this.deployBatchNo = deployBatchNo;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}