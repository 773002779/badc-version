package com.sf.release.infra.repo.po;

import java.time.Instant;

/**
 * Read-only projection of one daily release instance (P5-A design §5): the instance row plus the
 * owning 变更项's source branch, joined in <strong>one</strong> statement so the daily records page
 * and the daily detail do not need a second item lookup.
 *
 * <p>It is not an aggregate and has no behaviour; the application service turns it into the read
 * view. Column aliases are camelCase so the automatic mapping needs no result map.
 */
public class DailyInstanceRow {

    private Long instanceId;
    private Long itemId;
    private Long unitId;
    private String pipelineNo;
    private String status;
    private String requestedBy;
    private String branch;
    private String commitSha;
    private String imageDigest;
    private Instant startAt;
    private Instant endAt;
    private Instant createdAt;

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

    public Long getUnitId() {
        return unitId;
    }

    public void setUnitId(Long unitId) {
        this.unitId = unitId;
    }

    public String getPipelineNo() {
        return pipelineNo;
    }

    public void setPipelineNo(String pipelineNo) {
        this.pipelineNo = pipelineNo;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getRequestedBy() {
        return requestedBy;
    }

    public void setRequestedBy(String requestedBy) {
        this.requestedBy = requestedBy;
    }

    /** The 变更项's read-only source branch; {@code null} when the item row is gone. */
    public String getBranch() {
        return branch;
    }

    public void setBranch(String branch) {
        this.branch = branch;
    }

    public String getCommitSha() {
        return commitSha;
    }

    public void setCommitSha(String commitSha) {
        this.commitSha = commitSha;
    }

    public String getImageDigest() {
        return imageDigest;
    }

    public void setImageDigest(String imageDigest) {
        this.imageDigest = imageDigest;
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

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}