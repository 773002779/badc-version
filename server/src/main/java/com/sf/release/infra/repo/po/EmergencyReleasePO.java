package com.sf.release.infra.repo.po;

import java.time.Instant;

/**
 * Persistence object for one row of {@code emergency_release} (V32, P6-D design §2): the append-only
 * history of an administrator's J11 break-glass release. The {@code release_order} columns are the
 * "most recent" read; this table keeps every occurrence, so an order that is rerun and released
 * again keeps both facts.
 *
 * <p>No behaviour and no update: like the audit log, a history row is written once and never
 * rewritten. The mapper's surface has a single insert.
 */
public class EmergencyReleasePO {

    private Long id;
    private Long orderId;
    private String publishNo;
    private String reason;
    private String actor;
    private Instant createdAt;

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

    public String getPublishNo() {
        return publishNo;
    }

    public void setPublishNo(String publishNo) {
        this.publishNo = publishNo;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public String getActor() {
        return actor;
    }

    public void setActor(String actor) {
        this.actor = actor;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}