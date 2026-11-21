package com.sf.release.infra.repo.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.sf.release.domain.gate.GateWaiver;
import com.sf.release.domain.gate.GateWaiverStatus;

import java.time.Instant;

/** Persistence object for {@code gate_waiver} (V17). */
@TableName("gate_waiver")
public class GateWaiverPO {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long itemId;
    private Long orderId;
    private String status;
    private String reason;
    private String impact;
    private String requestedBy;
    private Instant requestedAt;
    private String approvedBy;
    private Instant approvedAt;
    private String decideReason;
    private Instant expireAt;
    private String commitSha;
    private String imageDigest;
    private Instant createdAt;
    private Instant updatedAt;

    public static GateWaiverPO of(GateWaiver waiver) {
        GateWaiverPO po = new GateWaiverPO();
        po.id = waiver.id();
        po.itemId = waiver.itemId();
        po.orderId = waiver.orderId();
        po.status = waiver.status().name();
        po.reason = waiver.reason();
        po.impact = waiver.impact();
        po.requestedBy = waiver.requestedBy();
        po.requestedAt = waiver.requestedAt();
        po.approvedBy = waiver.approvedBy();
        po.approvedAt = waiver.approvedAt();
        po.decideReason = waiver.decideReason();
        po.expireAt = waiver.expireAt();
        po.commitSha = waiver.commitSha();
        po.imageDigest = waiver.imageDigest();
        po.createdAt = waiver.createdAt();
        po.updatedAt = waiver.updatedAt();
        return po;
    }

    public GateWaiver toDomain() {
        return new GateWaiver(
            id,
            itemId == null ? 0L : itemId,
            orderId,
            status == null || status.isBlank()
                ? GateWaiverStatus.PENDING : GateWaiverStatus.valueOf(status),
            reason,
            impact,
            requestedBy,
            requestedAt,
            approvedBy,
            approvedAt,
            decideReason,
            expireAt,
            commitSha,
            imageDigest,
            createdAt,
            updatedAt);
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getItemId() {
        return itemId;
    }

    public void setItemId(Long itemId) {
        this.itemId = itemId;
    }

    public Long getOrderId() {
        return orderId;
    }

    public void setOrderId(Long orderId) {
        this.orderId = orderId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public String getImpact() {
        return impact;
    }

    public void setImpact(String impact) {
        this.impact = impact;
    }

    public String getRequestedBy() {
        return requestedBy;
    }

    public void setRequestedBy(String requestedBy) {
        this.requestedBy = requestedBy;
    }

    public Instant getRequestedAt() {
        return requestedAt;
    }

    public void setRequestedAt(Instant requestedAt) {
        this.requestedAt = requestedAt;
    }

    public String getApprovedBy() {
        return approvedBy;
    }

    public void setApprovedBy(String approvedBy) {
        this.approvedBy = approvedBy;
    }

    public Instant getApprovedAt() {
        return approvedAt;
    }

    public void setApprovedAt(Instant approvedAt) {
        this.approvedAt = approvedAt;
    }

    public String getDecideReason() {
        return decideReason;
    }

    public void setDecideReason(String decideReason) {
        this.decideReason = decideReason;
    }

    public Instant getExpireAt() {
        return expireAt;
    }

    public void setExpireAt(Instant expireAt) {
        this.expireAt = expireAt;
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