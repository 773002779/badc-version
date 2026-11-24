package com.sf.release.infra.repo.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;

/**
 * Persistence object for {@code audit_log} (V27, audit design section 2).
 *
 * <p>The JSON columns stay {@link String}s at this layer: the service serializes the changed fields
 * with the application {@code ObjectMapper} and stores the JSON text, so MyBatis never has to own a
 * Jackson type handler. A read returns the MySQL-normalized JSON text, which is exactly what the
 * audit page and the CSV export show.
 */
@TableName("audit_log")
public class AuditLogPO {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String actor;
    private String action;
    private String objectType;
    private String objectId;
    private String objectLabel;
    private String summary;
    private String reason;
    private String beforeJson;
    private String afterJson;
    private Long scopeUnitId;
    private Long scopeSubsystemId;
    private String traceId;
    private Instant createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getActor() {
        return actor;
    }

    public void setActor(String actor) {
        this.actor = actor;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public String getObjectType() {
        return objectType;
    }

    public void setObjectType(String objectType) {
        this.objectType = objectType;
    }

    public String getObjectId() {
        return objectId;
    }

    public void setObjectId(String objectId) {
        this.objectId = objectId;
    }

    public String getObjectLabel() {
        return objectLabel;
    }

    public void setObjectLabel(String objectLabel) {
        this.objectLabel = objectLabel;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public String getBeforeJson() {
        return beforeJson;
    }

    public void setBeforeJson(String beforeJson) {
        this.beforeJson = beforeJson;
    }

    public String getAfterJson() {
        return afterJson;
    }

    public void setAfterJson(String afterJson) {
        this.afterJson = afterJson;
    }

    public Long getScopeUnitId() {
        return scopeUnitId;
    }

    public void setScopeUnitId(Long scopeUnitId) {
        this.scopeUnitId = scopeUnitId;
    }

    public Long getScopeSubsystemId() {
        return scopeSubsystemId;
    }

    public void setScopeSubsystemId(Long scopeSubsystemId) {
        this.scopeSubsystemId = scopeSubsystemId;
    }

    public String getTraceId() {
        return traceId;
    }

    public void setTraceId(String traceId) {
        this.traceId = traceId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}