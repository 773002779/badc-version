package com.sf.release.infra.repo.po;

import java.time.Instant;

/**
 * Read-only projection of one rollback version row: the latest successful production deployment of
 * one image digest on a deployment unit (rollback design section 2).
 *
 * <p>It exists so the version list is one statement (no per-digest query) and the service only
 * groups the batch count / environment kinds onto it. {@code publishNo}/{@code executedBy} are the
 * values of the <em>latest</em> deployment of the digest (the one ranked first). Boolean-free shape:
 * the row has no behaviour.
 */
public class RollbackVersionRow {

    private String digest;
    private Instant finishedAt;
    private String publishNo;
    private String executedBy;
    private Long instanceId;
    private String commitSha;
    private Integer batchCount;

    public String getDigest() {
        return digest;
    }

    public void setDigest(String digest) {
        this.digest = digest;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    public void setFinishedAt(Instant finishedAt) {
        this.finishedAt = finishedAt;
    }

    public String getPublishNo() {
        return publishNo;
    }

    public void setPublishNo(String publishNo) {
        this.publishNo = publishNo;
    }

    public String getExecutedBy() {
        return executedBy;
    }

    public void setExecutedBy(String executedBy) {
        this.executedBy = executedBy;
    }

    public Long getInstanceId() {
        return instanceId;
    }

    public void setInstanceId(Long instanceId) {
        this.instanceId = instanceId;
    }

    public String getCommitSha() {
        return commitSha;
    }

    public void setCommitSha(String commitSha) {
        this.commitSha = commitSha;
    }

    public Integer getBatchCount() {
        return batchCount;
    }

    public void setBatchCount(Integer batchCount) {
        this.batchCount = batchCount;
    }
}