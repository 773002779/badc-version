package com.sf.release.infra.repo.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.sf.release.domain.resource.UnitType;
import com.sf.release.domain.task.GateStatus;
import com.sf.release.domain.task.ItemStatus;
import com.sf.release.domain.task.ReleaseItem;

import java.time.Instant;
import java.time.LocalDate;

/**
 * Persistence object for {@code release_item}. Column names are derived from the camel-case
 * fields; {@code type}, {@code status} and {@code gate_status} are stored as their uppercase
 * enum names. {@code row_version} is read and written explicitly by the state transitions
 * (CAS), never by MyBatis-Plus' own version plugin.
 */
@TableName("release_item")
public class ReleaseItemPO {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long taskId;
    private String crid;
    private String type;
    private Long unitId;
    private String buildPlanId;
    private String sourceBranch;
    private String modulePath;
    private String version;
    private LocalDate planReleaseDate;
    private String status;
    private String gateStatus;
    private String baseCommit;
    private String lastTestPassBy;
    private Instant lastTestPassAt;
    private Integer rowVersion;
    private Instant createdAt;
    private Instant updatedAt;

    public static ReleaseItemPO of(ReleaseItem item) {
        ReleaseItemPO po = new ReleaseItemPO();
        po.id = item.id();
        po.taskId = item.taskId();
        po.crid = item.crid();
        po.type = item.type().name();
        po.unitId = item.unitId();
        po.buildPlanId = item.buildPlanId();
        po.sourceBranch = item.sourceBranch();
        po.modulePath = item.modulePath();
        po.version = item.version();
        po.planReleaseDate = item.planReleaseDate();
        po.status = item.status().name();
        po.gateStatus = item.gateStatus().name();
        po.baseCommit = item.baseCommit();
        po.lastTestPassBy = item.lastTestPassBy();
        po.lastTestPassAt = item.lastTestPassAt();
        po.rowVersion = item.rowVersion();
        po.createdAt = item.createdAt();
        po.updatedAt = item.updatedAt();
        return po;
    }

    public ReleaseItem toDomain() {
        return new ReleaseItem(
            id,
            taskId,
            crid,
            type == null || type.isBlank() ? UnitType.UNIT : UnitType.valueOf(type),
            unitId,
            buildPlanId,
            sourceBranch,
            modulePath,
            version,
            planReleaseDate,
            status == null || status.isBlank() ? ItemStatus.PENDING : ItemStatus.valueOf(status),
            gateStatus == null || gateStatus.isBlank()
                ? GateStatus.TEST_PENDING : GateStatus.valueOf(gateStatus),
            baseCommit,
            lastTestPassBy,
            lastTestPassAt,
            rowVersion == null ? 0 : rowVersion,
            createdAt,
            updatedAt);
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getTaskId() {
        return taskId;
    }

    public void setTaskId(Long taskId) {
        this.taskId = taskId;
    }

    public String getCrid() {
        return crid;
    }

    public void setCrid(String crid) {
        this.crid = crid;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public Long getUnitId() {
        return unitId;
    }

    public void setUnitId(Long unitId) {
        this.unitId = unitId;
    }

    public String getBuildPlanId() {
        return buildPlanId;
    }

    public void setBuildPlanId(String buildPlanId) {
        this.buildPlanId = buildPlanId;
    }

    public String getSourceBranch() {
        return sourceBranch;
    }

    public void setSourceBranch(String sourceBranch) {
        this.sourceBranch = sourceBranch;
    }

    public String getModulePath() {
        return modulePath;
    }

    public void setModulePath(String modulePath) {
        this.modulePath = modulePath;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public LocalDate getPlanReleaseDate() {
        return planReleaseDate;
    }

    public void setPlanReleaseDate(LocalDate planReleaseDate) {
        this.planReleaseDate = planReleaseDate;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getGateStatus() {
        return gateStatus;
    }

    public void setGateStatus(String gateStatus) {
        this.gateStatus = gateStatus;
    }

    public String getBaseCommit() {
        return baseCommit;
    }

    public void setBaseCommit(String baseCommit) {
        this.baseCommit = baseCommit;
    }

    public String getLastTestPassBy() {
        return lastTestPassBy;
    }

    public void setLastTestPassBy(String lastTestPassBy) {
        this.lastTestPassBy = lastTestPassBy;
    }

    public Instant getLastTestPassAt() {
        return lastTestPassAt;
    }

    public void setLastTestPassAt(Instant lastTestPassAt) {
        this.lastTestPassAt = lastTestPassAt;
    }

    public Integer getRowVersion() {
        return rowVersion;
    }

    public void setRowVersion(Integer rowVersion) {
        this.rowVersion = rowVersion;
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