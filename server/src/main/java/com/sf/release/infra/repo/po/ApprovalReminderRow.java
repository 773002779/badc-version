package com.sf.release.infra.repo.po;

import java.time.Instant;

/**
 * Read-only projection of one due approval step (P6-E design §3): a {@code PENDING} step of an
 * order's <em>newest</em> admission snapshot whose 变更项 is still {@code PENDING_APPROVAL} and whose
 * {@code created_at} is at least the reminder threshold old.
 *
 * <p>The row carries what the reminder body shows (发布单号, the signing role, the wait) and the keys
 * the audience rules need (the system and its owner). The waiting time itself is measured by the
 * service against the scan instant, so a fixed-clock test can assert an exact hour count.
 */
public class ApprovalReminderRow {

    private Long snapshotId;
    private Integer stepNo;
    private Long orderId;
    private String publishNo;
    private String role;
    private Instant waitingSince;
    private Long subsystemId;
    private String ownerId;
    private String creator;

    public Long getSnapshotId() {
        return snapshotId;
    }

    public void setSnapshotId(Long snapshotId) {
        this.snapshotId = snapshotId;
    }

    public Integer getStepNo() {
        return stepNo;
    }

    public void setStepNo(Integer stepNo) {
        this.stepNo = stepNo;
    }

    public Long getOrderId() {
        return orderId;
    }

    public void setOrderId(Long orderId) {
        this.orderId = orderId;
    }

    public String getPublishNo() {
        return publishNo;
    }

    public void setPublishNo(String publishNo) {
        this.publishNo = publishNo;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public Instant getWaitingSince() {
        return waitingSince;
    }

    public void setWaitingSince(Instant waitingSince) {
        this.waitingSince = waitingSince;
    }

    public Long getSubsystemId() {
        return subsystemId;
    }

    public void setSubsystemId(Long subsystemId) {
        this.subsystemId = subsystemId;
    }

    public String getOwnerId() {
        return ownerId;
    }

    public void setOwnerId(String ownerId) {
        this.ownerId = ownerId;
    }

    public String getCreator() {
        return creator;
    }

    public void setCreator(String creator) {
        this.creator = creator;
    }
}
