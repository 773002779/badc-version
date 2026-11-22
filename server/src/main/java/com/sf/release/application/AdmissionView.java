package com.sf.release.application;

import com.sf.release.infra.repo.po.AdmissionSnapshotPO;

import java.time.Instant;
import java.util.List;

/**
 * The read model of one 发布单's production admission (tech design 4.8, {@code GET
 * /api/v1/releases/{publishNo}/admission}, and the answer every submit/resubmit returns).
 *
 * <p>{@code snapshot} is the <em>latest</em> admission snapshot and {@code chain} the approval steps
 * of that snapshot — not of the order. After a rejection and a resubmission the old snapshot and its
 * steps stay in the database as history, and this view shows the current generation only.
 *
 * <p>{@code review} is deliberately {@code null}: the pre-execution review (design 4.6: freeze,
 * waiver expiry, image digest) belongs to the execution task of the round. The field is present so
 * the page can already render the section and so adding it later does not change the response
 * shape.
 */
public record AdmissionView(
    String publishNo,
    long orderId,
    long itemId,
    String itemStatus,
    String orderStatus,
    SnapshotView snapshot,
    List<ApprovalStepView> chain,
    String nextAction,
    Object review) {

    public AdmissionView {
        chain = chain == null ? List.of() : List.copyOf(chain);
    }

    /**
     * The immutable admission snapshot as the page and the audit read it. The JSON columns
     * ({@code freeze_ids}, {@code waiver_ids}, {@code chain_json}) are decoded into lists; an
     * unreadable column fails loudly instead of showing an empty list.
     */
    public record SnapshotView(
        long id,
        long orderId,
        long itemId,
        Instant plannedReleaseTime,
        boolean inWindow,
        Long windowId,
        boolean frozen,
        List<Long> freezeIds,
        String maxSeverity,
        String checkConclusion,
        List<Long> waiverIds,
        String testConclusion,
        String testPassBy,
        String oaTicketNo,
        String imageDigest,
        List<String> chain,
        Instant createdAt) {

        public SnapshotView {
            freezeIds = freezeIds == null ? List.of() : List.copyOf(freezeIds);
            waiverIds = waiverIds == null ? List.of() : List.copyOf(waiverIds);
            chain = chain == null ? List.of() : List.copyOf(chain);
        }
    }

    /** The stored snapshot, or {@code null} when the 发布单 was never submitted. */
    public static SnapshotView snapshotOf(AdmissionSnapshotPO po) {
        if (po == null) {
            return null;
        }
        return new SnapshotView(
            po.getId() == null ? 0L : po.getId(),
            po.getOrderId() == null ? 0L : po.getOrderId(),
            po.getItemId() == null ? 0L : po.getItemId(),
            po.getPlannedReleaseTime(),
            Boolean.TRUE.equals(po.getInWindow()),
            po.getWindowId(),
            Boolean.TRUE.equals(po.getFrozen()),
            SnapshotJson.longsOf(po.getFreezeIds()),
            po.getMaxSeverity(),
            po.getCheckConclusion(),
            SnapshotJson.longsOf(po.getWaiverIds()),
            po.getTestConclusion(),
            po.getTestPassBy(),
            po.getOaTicketNo(),
            po.getImageDigest(),
            SnapshotJson.stringsOf(po.getChainJson()),
            po.getCreatedAt());
    }
}