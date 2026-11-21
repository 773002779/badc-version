package com.sf.release.infra.repo.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.sf.release.domain.gate.GateCheck;
import com.sf.release.domain.gate.GateCheckStatus;
import com.sf.release.domain.gate.Severity;
import com.sf.release.domain.pipeline.CheckType;

import java.time.Instant;

/**
 * Persistence object for {@code gate_check} (V17). {@code check_kind} stores the
 * {@link CheckType} name and {@code stage} its stage; both are part of the unique key
 * {@code (item_id, check_kind, stage)} that makes a repeated report an overwrite instead of a
 * duplicate. A row whose {@code check_kind} is no longer known (an older reader after a check was
 * renamed) is skipped by {@link #toDomain()} returning {@code null} — the caller must handle it.
 */
@TableName("gate_check")
public class GateCheckPO {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long itemId;
    private Long orderId;
    private Long instanceId;
    private String checkKind;
    private String stage;
    private String status;
    private String severity;
    private Integer issues;
    private String scannedObject;
    private String externalId;
    private String reportUrl;
    private String summary;
    private Integer attempt;
    private Instant startedAt;
    private Instant finishedAt;
    private Long durationMs;
    private Instant deadlineAt;
    private Instant createdAt;
    private Instant updatedAt;

    public static GateCheckPO of(GateCheck check) {
        GateCheckPO po = new GateCheckPO();
        po.id = check.id();
        po.itemId = check.itemId();
        po.orderId = check.orderId();
        po.instanceId = check.instanceId();
        po.checkKind = check.checkType().name();
        po.stage = check.checkType().stage().name();
        po.status = check.status().name();
        po.severity = check.severity().name();
        po.issues = check.issues();
        po.scannedObject = check.scannedObject();
        po.externalId = check.externalId();
        po.reportUrl = check.reportUrl();
        po.summary = check.summary();
        po.attempt = check.attempt();
        po.startedAt = check.startedAt();
        po.finishedAt = check.finishedAt();
        po.deadlineAt = check.deadlineAt();
        return po;
    }

    /** The domain value, or {@code null} when {@code check_kind} is not a known check type. */
    public GateCheck toDomain() {
        CheckType type = CheckType.fromName(checkKind);
        if (type == null) {
            return null;
        }
        return new GateCheck(
            id,
            itemId == null ? 0L : itemId,
            orderId,
            instanceId,
            type,
            status == null || status.isBlank()
                ? GateCheckStatus.PENDING : GateCheckStatus.valueOf(status),
            severity == null || severity.isBlank() ? Severity.NONE : Severity.valueOf(severity),
            issues == null ? 0 : issues,
            scannedObject,
            externalId,
            reportUrl,
            summary,
            attempt == null ? 0 : attempt,
            startedAt,
            finishedAt,
            deadlineAt);
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

    public Long getInstanceId() {
        return instanceId;
    }

    public void setInstanceId(Long instanceId) {
        this.instanceId = instanceId;
    }

    public String getCheckKind() {
        return checkKind;
    }

    public void setCheckKind(String checkKind) {
        this.checkKind = checkKind;
    }

    public String getStage() {
        return stage;
    }

    public void setStage(String stage) {
        this.stage = stage;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getSeverity() {
        return severity;
    }

    public void setSeverity(String severity) {
        this.severity = severity;
    }

    public Integer getIssues() {
        return issues;
    }

    public void setIssues(Integer issues) {
        this.issues = issues;
    }

    public String getScannedObject() {
        return scannedObject;
    }

    public void setScannedObject(String scannedObject) {
        this.scannedObject = scannedObject;
    }

    public String getExternalId() {
        return externalId;
    }

    public void setExternalId(String externalId) {
        this.externalId = externalId;
    }

    public String getReportUrl() {
        return reportUrl;
    }

    public void setReportUrl(String reportUrl) {
        this.reportUrl = reportUrl;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public Integer getAttempt() {
        return attempt;
    }

    public void setAttempt(Integer attempt) {
        this.attempt = attempt;
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

    public Instant getDeadlineAt() {
        return deadlineAt;
    }

    public void setDeadlineAt(Instant deadlineAt) {
        this.deadlineAt = deadlineAt;
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