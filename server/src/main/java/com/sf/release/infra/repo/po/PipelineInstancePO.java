package com.sf.release.infra.repo.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.sf.release.domain.pipeline.ExecType;
import com.sf.release.domain.pipeline.InstanceStatus;
import com.sf.release.domain.pipeline.NodeType;
import com.sf.release.domain.pipeline.PipelineInstance;
import com.sf.release.domain.pipeline.PipelineNode;
import com.sf.release.domain.pipeline.TriggerType;

import java.time.Instant;
import java.util.List;

/**
 * Persistence object for {@code pipeline_instance}. The enum columns are stored as uppercase
 * names. {@code current_node} is nullable; the rest of the aggregate ({@code pipeline_node} and
 * {@code node_task}) is loaded separately by the repository/orchestrator.
 */
@TableName("pipeline_instance")
public class PipelineInstancePO {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long orderId;
    private String pipelineNo;
    private String execType;
    private String triggerType;
    private String currentNode;
    private String status;
    private String statusReason;
    private String imageDigest;
    private String commitSha;
    private Instant startAt;
    private Instant endAt;

    /**
     * When the first TEST deployment completed (V9). It is the durable exactly-once claim of the
     * test-environment-ready event, and the verification panel reads it as "the test may be
     * decided now". It is not part of the {@link com.sf.release.domain.pipeline.PipelineInstance}
     * aggregate (that record stays about state, not about one event's bookkeeping).
     */
    private Instant testReadyAt;

    /**
     * When the instance entered {@code PAUSED} (V24), cleared on resume. The unit-lease renewer
     * uses it to stop extending an abandoned pause: without it a rejected or forgotten release
     * would hold its deployment unit forever. Not part of the aggregate either — it is lease
     * bookkeeping for an operation on the row.
     */
    private Instant pausedAt;

    private Instant createdAt;

    /**
     * Daily identity (V28): the 变更项, the deployment unit and who started the daily release.
     * Only a {@code DAILY} instance fills them; an ONLINE instance keeps them {@code NULL} and is
     * resolved through its release order ({@code order -> item -> unit}). They are part of the
     * aggregate since P5-A: a daily instance has {@code order_id = 0} and no other way to name its
     * owner.
     */
    private Long itemId;
    private Long unitId;
    private String requestedBy;

    public static PipelineInstancePO of(PipelineInstance instance) {
        PipelineInstancePO po = new PipelineInstancePO();
        po.id = instance.id();
        po.orderId = instance.orderId();
        po.pipelineNo = instance.pipelineNo();
        po.execType = instance.execType().name();
        po.triggerType = instance.triggerType().name();
        po.currentNode = instance.currentNode() == null ? null : instance.currentNode().name();
        po.status = instance.status().name();
        po.statusReason = instance.statusReason();
        po.imageDigest = instance.imageDigest();
        po.commitSha = instance.commitSha();
        po.startAt = instance.startAt();
        po.endAt = instance.endAt();
        po.createdAt = instance.createdAt();
        po.itemId = instance.itemId();
        po.unitId = instance.unitId();
        po.requestedBy = instance.requestedBy();
        return po;
    }

    public PipelineInstance toDomain() {
        return toDomain(List.of());
    }

    /** Rebuilds the aggregate root with the nodes loaded from {@code pipeline_node}. */
    public PipelineInstance toDomain(List<PipelineNode> nodes) {
        return new PipelineInstance(
            id,
            orderId,
            pipelineNo,
            execType == null || execType.isBlank() ? ExecType.ONLINE : ExecType.valueOf(execType),
            triggerType == null || triggerType.isBlank()
                ? TriggerType.INITIAL : TriggerType.valueOf(triggerType),
            currentNode == null || currentNode.isBlank()
                ? null : NodeType.valueOf(currentNode),
            status == null || status.isBlank()
                ? InstanceStatus.PENDING : InstanceStatus.valueOf(status),
            imageDigest,
            commitSha,
            startAt,
            endAt,
            createdAt,
            statusReason,
            nodes,
            itemId,
            unitId,
            requestedBy);
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

    public String getPipelineNo() {
        return pipelineNo;
    }

    public void setPipelineNo(String pipelineNo) {
        this.pipelineNo = pipelineNo;
    }

    public String getExecType() {
        return execType;
    }

    public void setExecType(String execType) {
        this.execType = execType;
    }

    public String getTriggerType() {
        return triggerType;
    }

    public void setTriggerType(String triggerType) {
        this.triggerType = triggerType;
    }

    public String getCurrentNode() {
        return currentNode;
    }

    public void setCurrentNode(String currentNode) {
        this.currentNode = currentNode;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    /** Explanation stored when the instance is {@code PAUSED} (V13); null otherwise. */
    public String getStatusReason() {
        return statusReason;
    }

    public void setStatusReason(String statusReason) {
        this.statusReason = statusReason;
    }

    public String getImageDigest() {
        return imageDigest;
    }

    public void setImageDigest(String imageDigest) {
        this.imageDigest = imageDigest;
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

    public Instant getTestReadyAt() {
        return testReadyAt;
    }

    public void setTestReadyAt(Instant testReadyAt) {
        this.testReadyAt = testReadyAt;
    }

    public Instant getPausedAt() {
        return pausedAt;
    }

    public void setPausedAt(Instant pausedAt) {
        this.pausedAt = pausedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    /** Daily only: the 变更项 (V28); {@code null} for an online instance. */
    public Long getItemId() {
        return itemId;
    }

    public void setItemId(Long itemId) {
        this.itemId = itemId;
    }

    /** Daily only: the deployment unit (V28); {@code null} for an online instance. */
    public Long getUnitId() {
        return unitId;
    }

    public void setUnitId(Long unitId) {
        this.unitId = unitId;
    }

    /** Daily only: who started it (V28); online records the operator on the order instead. */
    public String getRequestedBy() {
        return requestedBy;
    }

    public void setRequestedBy(String requestedBy) {
        this.requestedBy = requestedBy;
    }
}