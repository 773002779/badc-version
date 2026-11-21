package com.sf.release.infra.repo.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;

/**
 * One failed 变更项 transition, recorded by the pipeline event listener (V12) so a swallowed
 * listener failure still has a durable repair path. {@code attempts} counts reconcile retries;
 * {@code resolved_at} is set once a retry applied the transition.
 */
@TableName("pipeline_item_failure")
public class PipelineItemFailurePO {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long instanceId;
    private Long itemId;
    private String eventName;
    private String errorMessage;
    private Integer attempts;
    private Instant createdAt;
    private Instant resolvedAt;

    public static PipelineItemFailurePO of(long instanceId, long itemId, String eventName,
                                           String errorMessage, Instant createdAt) {
        PipelineItemFailurePO po = new PipelineItemFailurePO();
        po.instanceId = instanceId;
        po.itemId = itemId;
        po.eventName = eventName;
        po.errorMessage = errorMessage;
        po.attempts = 0;
        po.createdAt = createdAt;
        return po;
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

    public Long getItemId() {
        return itemId;
    }

    public void setItemId(Long itemId) {
        this.itemId = itemId;
    }

    public String getEventName() {
        return eventName;
    }

    public void setEventName(String eventName) {
        this.eventName = eventName;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public Integer getAttempts() {
        return attempts;
    }

    public void setAttempts(Integer attempts) {
        this.attempts = attempts;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getResolvedAt() {
        return resolvedAt;
    }

    public void setResolvedAt(Instant resolvedAt) {
        this.resolvedAt = resolvedAt;
    }
}