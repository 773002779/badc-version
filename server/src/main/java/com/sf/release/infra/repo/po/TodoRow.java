package com.sf.release.infra.repo.po;

import java.time.Instant;

/**
 * One row of a todo-centre group query (P5-C design sections 1 and 2). The four groups share this
 * flat read row because they all answer the same shape: what object, what label, what status, which
 * unit, since when it has been waiting, and the ids the application layer needs to build the
 * click-through link.
 *
 * <p>The group-specific columns are {@code null} outside their group and the application layer reads
 * only the ones its statement selects: {@code taskId}/{@code itemId} for 待测试, {@code publishNo}
 * for 审批/执行, and none beyond {@code objectId} for 我发起的. Keeping one row type (instead of four)
 * makes the query budget visible: four detail statements, four count statements, no follow-up.
 */
public class TodoRow {

    /** The object of the row: {@code release_item.id}, {@code release_order.id} or {@code release_task.id}. */
    private Long objectId;

    /** Human label: the item's CRID, the order's publish number or the task name. */
    private String label;

    /** The object's stored status word; the service maps it through the domain enum's label. */
    private String status;

    /** The deployment unit whose scope gate admitted the row (the task has no unit of its own). */
    private Long unitId;

    /** When the object entered this waiting state (test ready / snapshot created / order created / task updated). */
    private Instant waitingSince;

    /** The owning task id of a test/execute row, used by the {@code /tasks/{id}?item=} link. */
    private Long taskId;

    /** The release item id of a test row, used by the {@code /tasks/{id}?item=} link. */
    private Long itemId;

    /** The publish number of an approval/execute row, used by the {@code /releases/{no}} link. */
    private String publishNo;

    public Long getObjectId() {
        return objectId;
    }

    public void setObjectId(Long objectId) {
        this.objectId = objectId;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Long getUnitId() {
        return unitId;
    }

    public void setUnitId(Long unitId) {
        this.unitId = unitId;
    }

    public Instant getWaitingSince() {
        return waitingSince;
    }

    public void setWaitingSince(Instant waitingSince) {
        this.waitingSince = waitingSince;
    }

    public Long getTaskId() {
        return taskId;
    }

    public void setTaskId(Long taskId) {
        this.taskId = taskId;
    }

    public Long getItemId() {
        return itemId;
    }

    public void setItemId(Long itemId) {
        this.itemId = itemId;
    }

    public String getPublishNo() {
        return publishNo;
    }

    public void setPublishNo(String publishNo) {
        this.publishNo = publishNo;
    }
}