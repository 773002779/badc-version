package com.sf.release.infra.repo.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.sf.release.domain.approval.ApprovalRole;
import com.sf.release.domain.approval.ApprovalStatus;
import com.sf.release.domain.approval.ApprovalStep;

import java.time.Instant;

/**
 * Persistence object for {@code approval} (V21/V22): one step of the 分级审批 chain of one 发布单.
 *
 * <p>{@code (snapshot_id, step_no)} is unique (V22), so a chain can be appended without a lock — a
 * concurrent duplicate append fails on the key instead of silently creating two step 2s — and a new
 * admission snapshot can build its own chain from step 1 while the earlier generations stay
 * untouched. The role and the status are stored as their enum names, which are part of the API
 * contract.
 */
@TableName("approval")
public class ApprovalPO {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long orderId;

    /**
     * The admission snapshot the step belongs to. A chain is snapshot-scoped: the freeze hooks and
     * the resubmission build and read steps per {@code admission_snapshot}, so a rejected
     * generation keeps its rows and the next one starts clean.
     */
    private Long snapshotId;

    private Integer stepNo;
    private String role;
    private String status;
    private String decidedBy;
    private Instant decidedAt;
    private String comment;
    private Instant createdAt;

    public static ApprovalPO of(long orderId, long snapshotId, ApprovalStep step,
                                Instant createdAt) {
        ApprovalPO po = new ApprovalPO();
        po.orderId = orderId;
        po.snapshotId = snapshotId;
        po.stepNo = step.stepNo();
        po.role = step.role().name();
        po.status = step.status().name();
        po.decidedBy = step.decidedBy();
        po.decidedAt = step.decidedAt();
        po.comment = step.comment();
        po.createdAt = createdAt;
        return po;
    }

    /**
     * The domain step.
     *
     * @throws IllegalStateException a required column is NULL. Coercing (for example
     *                               {@code OPS_LEAD}/{@code PENDING}/step 1) would invent an approval
     *                               that nobody configured, so a corrupt row throws instead.
     */
    public ApprovalStep toDomain() {
        if (stepNo == null) {
            throw new IllegalStateException("approval " + id + " has no step_no");
        }
        if (role == null || role.isBlank()) {
            throw new IllegalStateException("approval " + id + " has no role");
        }
        if (status == null || status.isBlank()) {
            throw new IllegalStateException("approval " + id + " has no status");
        }
        return new ApprovalStep(stepNo, ApprovalRole.valueOf(role), ApprovalStatus.valueOf(status),
            decidedBy, decidedAt, comment);
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getOrderId() {
        return orderId;
    }

    public void setOrderId(Long orderId) {
        this.orderId = orderId;
    }

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

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getDecidedBy() {
        return decidedBy;
    }

    public void setDecidedBy(String decidedBy) {
        this.decidedBy = decidedBy;
    }

    public Instant getDecidedAt() {
        return decidedAt;
    }

    public void setDecidedAt(Instant decidedAt) {
        this.decidedAt = decidedAt;
    }

    public String getComment() {
        return comment;
    }

    public void setComment(String comment) {
        this.comment = comment;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}