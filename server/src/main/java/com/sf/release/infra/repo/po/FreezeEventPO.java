package com.sf.release.infra.repo.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.sf.release.domain.freeze.FreezeEvent;
import com.sf.release.domain.freeze.FreezeScope;
import com.sf.release.domain.freeze.FreezeStatus;

import java.time.Instant;

/**
 * Persistence object for {@code freeze_event} (V21), one 封板 record (tech design 4.2).
 *
 * <p>A freeze is in force when {@code status = ACTIVE} and {@code start_at <= now < end_at}; the
 * mapper's active queries encode that predicate. {@code subsystem_id} is NULL for a GLOBAL freeze
 * and set for a SUBSYSTEM one, so "which system" is never confused with the sentinel 0 used by
 * {@code release_window}.
 */
@TableName("freeze_event")
public class FreezeEventPO {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String scope;
    private Long subsystemId;
    private String reason;
    private String impact;
    private String initiatorId;
    private Instant startAt;
    private Instant endAt;
    private String status;
    private String releasedBy;
    private Instant releasedAt;
    private Instant createdAt;

    public static FreezeEventPO of(FreezeEvent event) {
        FreezeEventPO po = new FreezeEventPO();
        po.id = event.id();
        po.scope = event.scope().name();
        po.subsystemId = event.subsystemId();
        po.reason = event.reason();
        po.impact = event.impact();
        po.initiatorId = event.initiatorId();
        po.startAt = event.startAt();
        po.endAt = event.endAt();
        po.status = event.status().name();
        po.releasedBy = event.releasedBy();
        po.releasedAt = event.releasedAt();
        po.createdAt = event.createdAt();
        return po;
    }

    /**
     * The domain freeze.
     *
     * @throws IllegalStateException a required column is NULL or not a known word. Defaulting a
     *                               missing status to ACTIVE would invent a freeze that blocks
     *                               releases, so a corrupt row throws instead.
     */
    public FreezeEvent toDomain() {
        if (scope == null || scope.isBlank()) {
            throw new IllegalStateException("freeze " + id + " has no scope");
        }
        if (status == null || status.isBlank()) {
            throw new IllegalStateException("freeze " + id + " has no status");
        }
        if (startAt == null || endAt == null) {
            throw new IllegalStateException("freeze " + id + " has no start_at/end_at");
        }
        return new FreezeEvent(id, FreezeScope.valueOf(scope), subsystemId, reason, impact,
            initiatorId, startAt, endAt, FreezeStatus.valueOf(status), releasedBy, releasedAt,
            createdAt);
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getScope() {
        return scope;
    }

    public void setScope(String scope) {
        this.scope = scope;
    }

    public Long getSubsystemId() {
        return subsystemId;
    }

    public void setSubsystemId(Long subsystemId) {
        this.subsystemId = subsystemId;
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

    public String getInitiatorId() {
        return initiatorId;
    }

    public void setInitiatorId(String initiatorId) {
        this.initiatorId = initiatorId;
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

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getReleasedBy() {
        return releasedBy;
    }

    public void setReleasedBy(String releasedBy) {
        this.releasedBy = releasedBy;
    }

    public Instant getReleasedAt() {
        return releasedAt;
    }

    public void setReleasedAt(Instant releasedAt) {
        this.releasedAt = releasedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}