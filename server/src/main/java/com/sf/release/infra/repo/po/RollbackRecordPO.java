package com.sf.release.infra.repo.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.sf.release.domain.rollback.RollbackRecord;
import com.sf.release.domain.rollback.RollbackStatus;

import java.time.Instant;

/** Persistence object for {@code rollback_record} (V25). */
@TableName("rollback_record")
public class RollbackRecordPO {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long unitId;
    private Long instanceId;
    private String pipelineNo;
    private Long sourceOrderId;
    private Long sourceItemId;
    private String sourcePublishNo;
    private String sourceDigest;
    private String sourceCommit;
    private String targetDigest;
    private String targetPublishNo;
    private String reason;
    private String requestedBy;
    private String status;
    private String failureReason;
    private Instant createdAt;
    private Instant finishedAt;

    public static RollbackRecordPO of(RollbackRecord record) {
        RollbackRecordPO po = new RollbackRecordPO();
        po.id = record.id();
        po.unitId = record.unitId();
        po.instanceId = record.instanceId();
        po.pipelineNo = record.pipelineNo();
        po.sourceOrderId = record.sourceOrderId();
        po.sourceItemId = record.sourceItemId();
        po.sourcePublishNo = record.sourcePublishNo();
        po.sourceDigest = record.sourceDigest();
        po.sourceCommit = record.sourceCommit();
        po.targetDigest = record.targetDigest();
        po.targetPublishNo = record.targetPublishNo();
        po.reason = record.reason();
        po.requestedBy = record.requestedBy();
        po.status = record.status().name();
        po.failureReason = record.failureReason();
        po.createdAt = record.createdAt();
        po.finishedAt = record.finishedAt();
        return po;
    }

    public RollbackRecord toDomain() {
        return new RollbackRecord(id, unitId == null ? 0L : unitId, instanceId, pipelineNo,
            sourceOrderId, sourceItemId, sourcePublishNo, sourceDigest, sourceCommit, targetDigest,
            targetPublishNo, reason, requestedBy, parseStatus(), failureReason, createdAt,
            finishedAt);
    }

    private RollbackStatus parseStatus() {
        if (status == null || status.isBlank()) {
            return RollbackStatus.RUNNING;
        }
        return RollbackStatus.valueOf(status);
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

    public Long getInstanceId() {
        return instanceId;
    }

    public void setInstanceId(Long instanceId) {
        this.instanceId = instanceId;
    }

    public String getPipelineNo() {
        return pipelineNo;
    }

    public void setPipelineNo(String pipelineNo) {
        this.pipelineNo = pipelineNo;
    }

    public Long getSourceOrderId() {
        return sourceOrderId;
    }

    public void setSourceOrderId(Long sourceOrderId) {
        this.sourceOrderId = sourceOrderId;
    }

    public Long getSourceItemId() {
        return sourceItemId;
    }

    public void setSourceItemId(Long sourceItemId) {
        this.sourceItemId = sourceItemId;
    }

    public String getSourcePublishNo() {
        return sourcePublishNo;
    }

    public void setSourcePublishNo(String sourcePublishNo) {
        this.sourcePublishNo = sourcePublishNo;
    }

    public String getSourceDigest() {
        return sourceDigest;
    }

    public void setSourceDigest(String sourceDigest) {
        this.sourceDigest = sourceDigest;
    }

    public String getSourceCommit() {
        return sourceCommit;
    }

    public void setSourceCommit(String sourceCommit) {
        this.sourceCommit = sourceCommit;
    }

    public String getTargetDigest() {
        return targetDigest;
    }

    public void setTargetDigest(String targetDigest) {
        this.targetDigest = targetDigest;
    }

    public String getTargetPublishNo() {
        return targetPublishNo;
    }

    public void setTargetPublishNo(String targetPublishNo) {
        this.targetPublishNo = targetPublishNo;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public String getRequestedBy() {
        return requestedBy;
    }

    public void setRequestedBy(String requestedBy) {
        this.requestedBy = requestedBy;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public void setFailureReason(String failureReason) {
        this.failureReason = failureReason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    public void setFinishedAt(Instant finishedAt) {
        this.finishedAt = finishedAt;
    }
}