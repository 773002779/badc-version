package com.sf.release.infra.repo.po;

/**
 * Read-only projection of one 封板-impacted release (design section 4.3): a {@code release_order}
 * whose 变更项 is inside the production flow when a freeze is declared or lifted, plus the task and
 * system the recipient aggregation needs.
 *
 * <p>The row carries what the notification body shows — the publish number and the current stage —
 * and the keys the resolver needs to find the four member roles and the system owner. It is not an
 * aggregate: the application resolver owns the "one person, one row" aggregation.
 */
public class NotificationOrderRow {

    private Long orderId;
    private String publishNo;
    private Long taskId;
    private String itemStatus;
    private Long subsystemId;
    private String subsystemName;
    private String unitName;

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

    public Long getTaskId() {
        return taskId;
    }

    public void setTaskId(Long taskId) {
        this.taskId = taskId;
    }

    public String getItemStatus() {
        return itemStatus;
    }

    public void setItemStatus(String itemStatus) {
        this.itemStatus = itemStatus;
    }

    public Long getSubsystemId() {
        return subsystemId;
    }

    public void setSubsystemId(Long subsystemId) {
        this.subsystemId = subsystemId;
    }

    public String getSubsystemName() {
        return subsystemName;
    }

    public void setSubsystemName(String subsystemName) {
        this.subsystemName = subsystemName;
    }

    public String getUnitName() {
        return unitName;
    }

    public void setUnitName(String unitName) {
        this.unitName = unitName;
    }
}