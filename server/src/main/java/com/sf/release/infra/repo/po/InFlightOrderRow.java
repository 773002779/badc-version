package com.sf.release.infra.repo.po;

import java.time.Instant;

/**
 * Read-only projection of one "in-flight release" row: a {@code release_order} whose 变更项 is
 * already in the production flow ({@code SUBMITTED / PENDING_APPROVAL / READY}) together with the
 * owning deployment unit's system (tech design 4.2, the 封板 in-flight handling).
 *
 * <p>It exists so the freeze use cases find the affected orders with <strong>one</strong> statement
 * (order + item + unit) instead of a per-order lookup, and so the subsystem filter is a SQL
 * predicate rather than an in-memory pass over every in-flight release.
 *
 * <p>The row is not an aggregate and has no behaviour: the application service decides what to do
 * with each order (append the second signature, pull the item back into approval).
 */
public class InFlightOrderRow {

    private Long orderId;
    private String publishNo;
    private Long orderRowVersion;
    private String orderStatus;
    private Long itemId;
    private String itemStatus;
    private Long unitId;
    private Long subsystemId;
    private Instant plannedReleaseTime;

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

    public Long getOrderRowVersion() {
        return orderRowVersion;
    }

    public void setOrderRowVersion(Long orderRowVersion) {
        this.orderRowVersion = orderRowVersion;
    }

    public String getOrderStatus() {
        return orderStatus;
    }

    public void setOrderStatus(String orderStatus) {
        this.orderStatus = orderStatus;
    }

    public Long getItemId() {
        return itemId;
    }

    public void setItemId(Long itemId) {
        this.itemId = itemId;
    }

    public String getItemStatus() {
        return itemStatus;
    }

    public void setItemStatus(String itemStatus) {
        this.itemStatus = itemStatus;
    }

    public Long getUnitId() {
        return unitId;
    }

    public void setUnitId(Long unitId) {
        this.unitId = unitId;
    }

    public Long getSubsystemId() {
        return subsystemId;
    }

    public void setSubsystemId(Long subsystemId) {
        this.subsystemId = subsystemId;
    }

    public Instant getPlannedReleaseTime() {
        return plannedReleaseTime;
    }

    public void setPlannedReleaseTime(Instant plannedReleaseTime) {
        this.plannedReleaseTime = plannedReleaseTime;
    }
}