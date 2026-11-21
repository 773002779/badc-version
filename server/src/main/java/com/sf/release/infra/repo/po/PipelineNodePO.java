package com.sf.release.infra.repo.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.sf.release.domain.pipeline.NodeStatus;
import com.sf.release.domain.pipeline.NodeTask;
import com.sf.release.domain.pipeline.NodeType;
import com.sf.release.domain.pipeline.PipelineNode;

import java.time.Instant;
import java.util.List;

/** Persistence object for {@code pipeline_node}; {@code seq_no} is the execution order. */
@TableName("pipeline_node")
public class PipelineNodePO {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long instanceId;
    private String nodeType;
    private Integer seqNo;
    private String status;
    private Instant startedAt;
    private Instant finishedAt;

    public static PipelineNodePO of(PipelineNode node, long instanceId) {
        PipelineNodePO po = new PipelineNodePO();
        po.id = node.id();
        po.instanceId = instanceId;
        po.nodeType = node.nodeType().name();
        po.seqNo = node.seq();
        po.status = node.status().name();
        po.startedAt = node.startedAt();
        po.finishedAt = node.finishedAt();
        return po;
    }

    public PipelineNode toDomain() {
        return toDomain(List.of());
    }

    /** Rebuilds the node with the tasks loaded from {@code node_task}. */
    public PipelineNode toDomain(List<NodeTask> tasks) {
        return new PipelineNode(
            id,
            instanceId,
            NodeType.valueOf(nodeType),
            seqNo == null ? 0 : seqNo,
            status == null || status.isBlank() ? NodeStatus.PENDING : NodeStatus.valueOf(status),
            startedAt,
            finishedAt,
            tasks);
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getInstanceId() {
        return instanceId;
    }

    public void setInstanceId(Long instanceId) {
        this.instanceId = instanceId;
    }

    public String getNodeType() {
        return nodeType;
    }

    public void setNodeType(String nodeType) {
        this.nodeType = nodeType;
    }

    public Integer getSeqNo() {
        return seqNo;
    }

    public void setSeqNo(Integer seqNo) {
        this.seqNo = seqNo;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(Instant startedAt) {
        this.startedAt = startedAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    public void setFinishedAt(Instant finishedAt) {
        this.finishedAt = finishedAt;
    }
}