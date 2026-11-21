package com.sf.release.infra.repo.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.sf.release.domain.pipeline.NodeTask;
import com.sf.release.domain.pipeline.NodeTaskStatus;
import com.sf.release.domain.pipeline.NodeType;
import com.sf.release.domain.pipeline.TaskType;

import java.time.Instant;

/**
 * Persistence object for {@code node_task}. {@code batch_no} and {@code target_id} are
 * {@code NOT NULL DEFAULT 0} (the platform ruling for nullable unique-key columns);
 * {@code target_id} is the DE id for {@code DEPLOY} tasks and the check-type code for
 * {@code VERIFY/CHECK} tasks.
 */
@TableName("node_task")
public class NodeTaskPO {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long instanceId;
    private String nodeType;
    private String taskType;
    private Integer batchNo;
    private Long targetId;
    private String status;
    private Integer attempt;
    private String externalId;
    private String externalUrl;
    private String resultSummary;
    private Instant deadlineAt;
    private Instant startedAt;
    private Instant finishedAt;
    private Long durationMs;

    /**
     * Last observed progress of the row (V11). It is the gate of the 30s reconcile poll and is
     * not part of the {@link NodeTask} aggregate: {@code started_at} cannot express "the engine
     * was queried and still reports RUNNING".
     */
    private Instant updatedAt;

    /** Consecutive failed engine queries (V11); reset by every successful query. */
    private Integer queryFailures;

    public static NodeTaskPO of(NodeTask task, long instanceId) {
        NodeTaskPO po = new NodeTaskPO();
        po.id = task.id();
        po.instanceId = instanceId;
        po.nodeType = task.nodeType().name();
        po.taskType = task.taskType().name();
        po.batchNo = task.batchNo();
        po.targetId = task.targetId();
        po.status = task.status().name();
        po.attempt = task.attempt();
        po.externalId = task.externalId();
        po.externalUrl = task.externalUrl();
        po.resultSummary = task.resultSummary();
        po.deadlineAt = task.deadlineAt();
        po.startedAt = task.startedAt();
        po.finishedAt = task.finishedAt();
        po.durationMs = task.durationMs();
        return po;
    }

    public NodeTask toDomain() {
        return new NodeTask(
            id,
            instanceId,
            NodeType.valueOf(nodeType),
            TaskType.valueOf(taskType),
            batchNo == null ? 0 : batchNo,
            targetId == null ? 0L : targetId,
            status == null || status.isBlank()
                ? NodeTaskStatus.PENDING : NodeTaskStatus.valueOf(status),
            attempt == null ? 0 : attempt,
            externalId,
            externalUrl,
            resultSummary,
            deadlineAt,
            startedAt,
            finishedAt,
            durationMs);
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

    public String getTaskType() {
        return taskType;
    }

    public void setTaskType(String taskType) {
        this.taskType = taskType;
    }

    public Integer getBatchNo() {
        return batchNo;
    }

    public void setBatchNo(Integer batchNo) {
        this.batchNo = batchNo;
    }

    public Long getTargetId() {
        return targetId;
    }

    public void setTargetId(Long targetId) {
        this.targetId = targetId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Integer getAttempt() {
        return attempt;
    }

    public void setAttempt(Integer attempt) {
        this.attempt = attempt;
    }

    public String getExternalId() {
        return externalId;
    }

    public void setExternalId(String externalId) {
        this.externalId = externalId;
    }

    public String getExternalUrl() {
        return externalUrl;
    }

    public void setExternalUrl(String externalUrl) {
        this.externalUrl = externalUrl;
    }

    public String getResultSummary() {
        return resultSummary;
    }

    public void setResultSummary(String resultSummary) {
        this.resultSummary = resultSummary;
    }

    public Instant getDeadlineAt() {
        return deadlineAt;
    }

    public void setDeadlineAt(Instant deadlineAt) {
        this.deadlineAt = deadlineAt;
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

    public Long getDurationMs() {
        return durationMs;
    }

    public void setDurationMs(Long durationMs) {
        this.durationMs = durationMs;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public Integer getQueryFailures() {
        return queryFailures;
    }

    public void setQueryFailures(Integer queryFailures) {
        this.queryFailures = queryFailures;
    }
}