package com.sf.release.infra.repo.po;

import java.time.Instant;

/**
 * Read-only projection of one 发布单 header read: the release order, the owning 变更项's
 * deployment unit and the newest pipeline instance of that order (Task 16).
 *
 * <p>It exists so the release detail is aggregated with <strong>one</strong> statement instead of
 * an order lookup, an item lookup and an instance lookup. The instance columns are {@code null}
 * when the order has no execution yet (the publish transaction creates both, so this is a
 * defensive shape, not the normal state).
 *
 * <p>The row is not an aggregate and has no behaviour: the query service turns it into the
 * response view. Column aliases are camelCase so the automatic mapping needs no result map.
 */
public class ReleaseHeadRow {

    private String publishNo;
    private String oaTicketNo;
    private Instant plannedReleaseTime;

    /** Owning deployment unit of the item; the data-scope guard reads it. */
    private Long unitId;

    /** The 变更项 id, so the page can open its verification panel without another lookup. */
    private Long itemId;

    /**
     * The release task owning the item, so the member read of the P6-F 人员列 needs no second
     * item lookup (the members are keyed by task).
     */
    private Long taskId;

    private Long instanceId;
    private String pipelineNo;
    private String triggerType;
    private String status;
    private String statusReason;
    private String gateStatus;
    private String testStatus;
    private String imageDigest;
    private String commitSha;
    private Instant startAt;
    private Instant endAt;

    /** The J11 read marker of the order (V32, P6-D design §2/§3); {@code null} or 0 = never used. */
    private Integer emergencyRelease;
    private String emergencyReason;
    private String emergencyBy;
    private Instant emergencyAt;

    public String getPublishNo() {
        return publishNo;
    }

    public void setPublishNo(String publishNo) {
        this.publishNo = publishNo;
    }

    public String getOaTicketNo() {
        return oaTicketNo;
    }

    public void setOaTicketNo(String oaTicketNo) {
        this.oaTicketNo = oaTicketNo;
    }

    public Instant getPlannedReleaseTime() {
        return plannedReleaseTime;
    }

    public void setPlannedReleaseTime(Instant plannedReleaseTime) {
        this.plannedReleaseTime = plannedReleaseTime;
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

    public Long getItemId() {
        return itemId;
    }

    public void setItemId(Long itemId) {
        this.itemId = itemId;
    }

    public Long getTaskId() {
        return taskId;
    }

    public void setTaskId(Long taskId) {
        this.taskId = taskId;
    }

    public String getPipelineNo() {
        return pipelineNo;
    }

    public void setPipelineNo(String pipelineNo) {
        this.pipelineNo = pipelineNo;
    }

    public String getTriggerType() {
        return triggerType;
    }

    public void setTriggerType(String triggerType) {
        this.triggerType = triggerType;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getImageDigest() {
        return imageDigest;
    }

    public void setImageDigest(String imageDigest) {
        this.imageDigest = imageDigest;
    }

    public String getGateStatus() {
        return gateStatus;
    }

    public void setGateStatus(String gateStatus) {
        this.gateStatus = gateStatus;
    }

    public String getTestStatus() {
        return testStatus;
    }

    public void setTestStatus(String testStatus) {
        this.testStatus = testStatus;
    }

    public String getStatusReason() {
        return statusReason;
    }

    public void setStatusReason(String statusReason) {
        this.statusReason = statusReason;
    }

    public String getCommitSha() {
        return commitSha;
    }

    public void setCommitSha(String commitSha) {
        this.commitSha = commitSha;
    }

    public Instant getStartAt() {
        return startAt;
    }

    public void setStartAt(Instant startAt) {
        this.startAt = startAt;
    }

    public Instant getEndAt() {
        return endAt;
    }

    public void setEndAt(Instant endAt) {
        this.endAt = endAt;
    }

    public Integer getEmergencyRelease() {
        return emergencyRelease;
    }

    public void setEmergencyRelease(Integer emergencyRelease) {
        this.emergencyRelease = emergencyRelease;
    }

    public String getEmergencyReason() {
        return emergencyReason;
    }

    public void setEmergencyReason(String emergencyReason) {
        this.emergencyReason = emergencyReason;
    }

    public String getEmergencyBy() {
        return emergencyBy;
    }

    public void setEmergencyBy(String emergencyBy) {
        this.emergencyBy = emergencyBy;
    }

    public Instant getEmergencyAt() {
        return emergencyAt;
    }

    public void setEmergencyAt(Instant emergencyAt) {
        this.emergencyAt = emergencyAt;
    }
}