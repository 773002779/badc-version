package com.sf.release.infra.repo.po;

import java.time.Instant;
import java.time.LocalDate;

/**
 * One {@code release_item} row joined with the name of its {@code deploy_unit}, as the task
 * detail's item list shows it. The join is a {@code LEFT JOIN} so an item whose unit row is
 * gone still reads with {@code unitName == null} instead of disappearing.
 */
public class TaskItemRow {

    private Long id;
    private String crid;
    private Long unitId;
    private String unitName;
    private String type;
    private String version;
    private String sourceBranch;
    private LocalDate planReleaseDate;
    private String status;
    private String gateStatus;
    private Instant createdAt;
    private Instant updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getCrid() {
        return crid;
    }

    public void setCrid(String crid) {
        this.crid = crid;
    }

    public Long getUnitId() {
        return unitId;
    }

    public void setUnitId(Long unitId) {
        this.unitId = unitId;
    }

    public String getUnitName() {
        return unitName;
    }

    public void setUnitName(String unitName) {
        this.unitName = unitName;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public String getSourceBranch() {
        return sourceBranch;
    }

    public void setSourceBranch(String sourceBranch) {
        this.sourceBranch = sourceBranch;
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