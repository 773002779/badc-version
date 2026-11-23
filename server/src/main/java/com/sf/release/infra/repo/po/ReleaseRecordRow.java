package com.sf.release.infra.repo.po;

import java.time.Instant;

/**
 * Read-only projection of one row of the global release-records page (GAP-10, P4-A Task 2): one
 * {@code release_order} with its 变更项, deployment unit, system and newest pipeline instance.
 *
 * <p>The row exists so the page is one joined statement (plus one count) instead of a per-order
 * follow-up query: {@code pipelineNo}/{@code pipelineStatus} come from the correlated
 * {@code MAX(pipeline_instance.id)} join and are {@code null} for an order that was never executed.
 * Boolean-free shape: the row has no behaviour.
 */
public class ReleaseRecordRow {

    private String publishNo;
    private Long itemId;
    private String crid;
    private String version;
    private Long subsystemId;
    private String subsystemName;
    private Long unitId;
    private String unitName;
    private String orderStatus;
    private String itemStatus;
    private Instant plannedReleaseTime;
    private Instant createdAt;
    private String executedBy;
    private String pipelineNo;
    private String pipelineStatus;

    public String getPublishNo() {
        return publishNo;
    }

    public void setPublishNo(String publishNo) {
        this.publishNo = publishNo;
    }

    public Long getItemId() {
        return itemId;
    }

    public void setItemId(Long itemId) {
        this.itemId = itemId;
    }

    public String getCrid() {
        return crid;
    }

    public void setCrid(String crid) {
        this.crid = crid;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public Long getSubsystemId() {
        return subsystemId;
    }

    public void setSubsystemId(Long subsystemId) {
        this.subsystemId = subsystemId;
    }

    public String getSubsystemName() {
        return subsystemName;
    }

    public void setSubsystemName(String subsystemName) {
        this.subsystemName = subsystemName;
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

    public String getOrderStatus() {
        return orderStatus;
    }

    public void setOrderStatus(String orderStatus) {
        this.orderStatus = orderStatus;
    }

    public String getItemStatus() {
        return itemStatus;
    }

    public void setItemStatus(String itemStatus) {
        this.itemStatus = itemStatus;
    }

    public Instant getPlannedReleaseTime() {
        return plannedReleaseTime;
    }

    public void setPlannedReleaseTime(Instant plannedReleaseTime) {
        this.plannedReleaseTime = plannedReleaseTime;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public String getExecutedBy() {
        return executedBy;
    }

    public void setExecutedBy(String executedBy) {
        this.executedBy = executedBy;
    }

    public String getPipelineNo() {
        return pipelineNo;
    }

    public void setPipelineNo(String pipelineNo) {
        this.pipelineNo = pipelineNo;
    }

    public String getPipelineStatus() {
        return pipelineStatus;
    }

    public void setPipelineStatus(String pipelineStatus) {
        this.pipelineStatus = pipelineStatus;
    }
}