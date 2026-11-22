package com.sf.release.web.dto;

import com.sf.release.application.AdmissionView;
import com.sf.release.application.ApprovalStepView;

import java.time.Instant;
import java.util.List;

/**
 * Request and response bodies of the 提交生产 / 准入 endpoints (tech design 4.8).
 *
 * <p>The web layer owns the wire shape; {@link AdmissionView} stays HTTP-agnostic. Names and Chinese
 * labels are both sent (roles, step statuses), so the page renders the chain without keeping its own
 * copy of the vocabulary.
 *
 * <p>{@code snapshot} is {@code null} when the 发布单 was never submitted — the page shows "尚未
 * 提交生产" and does not have to treat an absent snapshot as an error. {@code review} is
 * deliberately {@code null} until the execution task adds the pre-execution review (design 4.6);
 * the field exists now so the response shape will not change later.
 */
public final class AdmissionDtos {

    private AdmissionDtos() {
    }

    /**
     * {@code POST /api/v1/releases/{publishNo}/submit-prod} and
     * {@code POST /api/v1/releases/{publishNo}/resubmit}. On resubmit both fields are optional: an
     * omitted value reuses what the previous submission recorded (design 4.3: 重跑复用，不重填).
     */
    public record SubmitProdRequest(Instant plannedReleaseTime, String oaTicketNo) {
    }

    /** One approval step of the current snapshot's chain. */
    public record ApprovalStepResponse(
        int stepNo,
        String role,
        String roleLabel,
        String status,
        String statusLabel,
        String decidedBy,
        Instant decidedAt,
        String comment) {
    }

    /** The admission snapshot (design 4.5) as the page reads it. */
    public record SnapshotResponse(
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
    }

    /** The whole admission view of one 发布单. */
    public record AdmissionResponse(
        String publishNo,
        long orderId,
        long itemId,
        String itemStatus,
        String orderStatus,
        SnapshotResponse snapshot,
        List<ApprovalStepResponse> chain,
        String nextAction,
        Object review) {
    }

    public static AdmissionResponse of(AdmissionView view) {
        return new AdmissionResponse(
            view.publishNo(),
            view.orderId(),
            view.itemId(),
            view.itemStatus(),
            view.orderStatus(),
            snapshotOf(view.snapshot()),
            view.chain().stream().map(AdmissionDtos::stepOf).toList(),
            view.nextAction(),
            view.review());
    }

    private static SnapshotResponse snapshotOf(AdmissionView.SnapshotView snapshot) {
        if (snapshot == null) {
            return null;
        }
        return new SnapshotResponse(
            snapshot.id(),
            snapshot.orderId(),
            snapshot.itemId(),
            snapshot.plannedReleaseTime(),
            snapshot.inWindow(),
            snapshot.windowId(),
            snapshot.frozen(),
            snapshot.freezeIds(),
            snapshot.maxSeverity(),
            snapshot.checkConclusion(),
            snapshot.waiverIds(),
            snapshot.testConclusion(),
            snapshot.testPassBy(),
            snapshot.oaTicketNo(),
            snapshot.imageDigest(),
            snapshot.chain(),
            snapshot.createdAt());
    }

    private static ApprovalStepResponse stepOf(ApprovalStepView step) {
        return new ApprovalStepResponse(
            step.stepNo(),
            step.role(),
            step.roleLabel(),
            step.status(),
            step.statusLabel(),
            step.decidedBy(),
            step.decidedAt(),
            step.comment());
    }
}