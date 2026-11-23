package com.sf.release.infra.repo.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.sf.release.domain.pipeline.ReleaseOrder;

import java.time.Instant;

/** Persistence object for {@code release_order}; the publish number is the unique business key. */
@TableName("release_order")
public class ReleaseOrderPO {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String publishNo;
    private Long itemId;
    private String oaTicketNo;
    private Instant plannedReleaseTime;
    private String windowResult;
    private Instant deadlineAt;
    private String status;
    private Integer rowVersion;
    private Instant createdAt;

    /**
     * The operator of the last production execution of this order (V25). It is not part of the
     * {@link ReleaseOrder} domain aggregate: it is operational attribution written by the execute
     * use case, read by the rollback version list as the "publisher" column. Historical rows keep
     * it {@code null}.
     */
    private String executedBy;

    public static ReleaseOrderPO of(ReleaseOrder order) {
        ReleaseOrderPO po = new ReleaseOrderPO();
        po.id = order.id();
        po.publishNo = order.publishNo();
        po.itemId = order.itemId();
        po.oaTicketNo = order.oaTicketNo();
        po.plannedReleaseTime = order.plannedReleaseTime();
        po.windowResult = order.windowResult();
        po.deadlineAt = order.deadlineAt();
        po.status = order.status();
        po.rowVersion = order.rowVersion();
        po.createdAt = order.createdAt();
        // The domain record has no operator field: an insert never carries one, the execute use
        // case writes it explicitly on the row.
        po.executedBy = null;
        return po;
    }

    public ReleaseOrder toDomain() {
        return new ReleaseOrder(id, publishNo, itemId, oaTicketNo, plannedReleaseTime,
            windowResult, deadlineAt, status, rowVersion == null ? 0 : rowVersion, createdAt);
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getPublishNo() {
        return publishNo;
    }

    public void setPublishNo(String publishNo) {
        this.publishNo = publishNo;
    }

    public Long getItemId() {
        return itemId;
    }

    public void setItemId(Long itemId) {
        this.itemId = itemId;
    }

    public String getOaTicketNo() {
        return oaTicketNo;
    }

    public void setOaTicketNo(String oaTicketNo) {
        this.oaTicketNo = oaTicketNo;
    }

    public Instant getPlannedReleaseTime() {
        return plannedReleaseTime;
    }

    public void setPlannedReleaseTime(Instant plannedReleaseTime) {
        this.plannedReleaseTime = plannedReleaseTime;
    }

    public String getWindowResult() {
        return windowResult;
    }

    public void setWindowResult(String windowResult) {
        this.windowResult = windowResult;
    }

    public Instant getDeadlineAt() {
        return deadlineAt;
    }

    public void setDeadlineAt(Instant deadlineAt) {
        this.deadlineAt = deadlineAt;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Integer getRowVersion() {
        return rowVersion;
    }

    public void setRowVersion(Integer rowVersion) {
        this.rowVersion = rowVersion;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public String getExecutedBy() {
        return executedBy;
    }

    public void setExecutedBy(String executedBy) {
        this.executedBy = executedBy;
    }
}