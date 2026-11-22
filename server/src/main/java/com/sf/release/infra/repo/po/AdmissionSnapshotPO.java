package com.sf.release.infra.repo.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;

/**
 * Persistence object for {@code admission_snapshot} (V21): the immutable record of one 生产准入
 * decision (tech design 4.5).
 *
 * <p>The columns that must be queried or compared are real columns; the lists that are evidence
 * only ({@code freeze_ids}, {@code waiver_ids}, {@code chain_json}) are TEXT holding JSON. A
 * resubmission inserts a new row instead of updating this one, which is what makes the snapshot
 * trustworthy for audit and for the executor (design 4.6: the executor compares the snapshot, it
 * does not re-evaluate the window).
 *
 * <p>The insert model belongs to the next task of the round (准入判定 writes it); this PO only
 * mirrors the table and the read the pages need.
 */
@TableName("admission_snapshot")
public class AdmissionSnapshotPO {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long orderId;
    private Long itemId;
    private Instant plannedReleaseTime;
    private Boolean inWindow;
    private Long windowId;
    private Boolean frozen;
    private String freezeIds;
    private String maxSeverity;
    private String checkConclusion;
    private String waiverIds;
    private String testConclusion;
    private String testPassBy;
    private String oaTicketNo;
    private String imageDigest;
    private String chainJson;
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

    public Long getItemId() {
        return itemId;
    }

    public void setItemId(Long itemId) {
        this.itemId = itemId;
    }

    public Instant getPlannedReleaseTime() {
        return plannedReleaseTime;
    }

    public void setPlannedReleaseTime(Instant plannedReleaseTime) {
        this.plannedReleaseTime = plannedReleaseTime;
    }

    public Boolean getInWindow() {
        return inWindow;
    }

    public void setInWindow(Boolean inWindow) {
        this.inWindow = inWindow;
    }

    public Long getWindowId() {
        return windowId;
    }

    public void setWindowId(Long windowId) {
        this.windowId = windowId;
    }

    public Boolean getFrozen() {
        return frozen;
    }

    public void setFrozen(Boolean frozen) {
        this.frozen = frozen;
    }

    public String getFreezeIds() {
        return freezeIds;
    }

    public void setFreezeIds(String freezeIds) {
        this.freezeIds = freezeIds;
    }

    public String getMaxSeverity() {
        return maxSeverity;
    }

    public void setMaxSeverity(String maxSeverity) {
        this.maxSeverity = maxSeverity;
    }

    public String getCheckConclusion() {
        return checkConclusion;
    }

    public void setCheckConclusion(String checkConclusion) {
        this.checkConclusion = checkConclusion;
    }

    public String getWaiverIds() {
        return waiverIds;
    }

    public void setWaiverIds(String waiverIds) {
        this.waiverIds = waiverIds;
    }

    public String getTestConclusion() {
        return testConclusion;
    }

    public void setTestConclusion(String testConclusion) {
        this.testConclusion = testConclusion;
    }

    public String getTestPassBy() {
        return testPassBy;
    }

    public void setTestPassBy(String testPassBy) {
        this.testPassBy = testPassBy;
    }

    public String getOaTicketNo() {
        return oaTicketNo;
    }

    public void setOaTicketNo(String oaTicketNo) {
        this.oaTicketNo = oaTicketNo;
    }

    public String getImageDigest() {
        return imageDigest;
    }

    public void setImageDigest(String imageDigest) {
        this.imageDigest = imageDigest;
    }

    public String getChainJson() {
        return chainJson;
    }

    public void setChainJson(String chainJson) {
        this.chainJson = chainJson;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}