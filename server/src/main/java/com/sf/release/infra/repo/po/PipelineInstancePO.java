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
    private String imageDigest;
    private String commitSha;
    private Instant startAt;
    private Instant endAt;
    private Instant createdAt;

    public static PipelineInstancePO of(PipelineInstance instance) {
        PipelineInstancePO po = new PipelineInstancePO();
        po.id = instance.id();
        po.orderId = instance.orderId();
        po.pipelineNo = instance.pipelineNo();
        po.execType = instance.execType().name();
        po.triggerType = instance.triggerType().name();
        po.currentNode = instance.currentNode() == null ? null : instance.currentNode().name();
        po.status = instance.status().name();
        po.imageDigest = instance.imageDigest();
        po.commitSha = instance.commitSha();
        po.startAt = instance.startAt();
        po.endAt = instance.endAt();
        po.createdAt = instance.createdAt();
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
            nodes);
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

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}