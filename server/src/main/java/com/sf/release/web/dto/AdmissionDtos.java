package com.sf.release.web.dto;

import com.sf.release.application.AdmissionStepView;
import com.sf.release.application.AdmissionView;
import com.sf.release.application.ApprovalStepView;
import com.sf.release.application.ExecuteProdService;

import java.time.Instant;
import java.util.List;

/**
 * Request and response bodies of the 提交生产 / 准入 endpoints (tech design 4.8).
 *
 * <p>The web layer owns the wire shape; {@link AdmissionView} stays HTTP-agnostic. Names and Chinese
 * labels are both sent (roles, step statuses, admission step keys), so the page renders the evidence
 * without keeping its own copy of the vocabulary.
 *
 * <p><strong>The six admission steps travel with every fresh judgement.</strong> {@code steps} is
 * present (and {@code admitted} is non-null) on submit, resubmit and {@code admission-check} — that
 * is what lets the dialog say "检查未完成，等它跑完" instead of greying the button. The stored
 * admission read answers {@code admitted = null, steps = []}: the snapshot keeps the outcome, not the
 * step sentences (re-evaluating it would recompute the window, which design 4.6 forbids).
 *
 * <p>{@code snapshot} is {@code null} when the 发布单 was never submitted (the page shows "尚未提交
 * 生产") and on {@code admission-check} (nothing is written). A refusal keeps
 * {@code reasons}/{@code failedSteps}/{@code nextAction} for compatibility and adds the same steps
 * as {@code data.steps}. {@code review} is the pre-execution review of the stored snapshot
 * ({@link com.sf.release.application.ReviewService}), filled by the admission read
 * ({@code GET .../admission}) and {@code null} on the submit/resubmit/admission-check answers.
 */
public final class AdmissionDtos {

    private AdmissionDtos() {
    }

    /**
     * {@code POST /api/v1/releases/{publishNo}/approve} (design 4.4). The comment is optional: an
     * approval without a note is still an approval, while a rejection requires one.
     */
    public record ApproveRequest(String comment) {
    }

    /**
     * {@code POST /api/v1/releases/{publishNo}/reject} (design 4.4). The reason is mandatory and is
     * stored on the rejected step as its {@code comment}.
     */
    public record RejectRequest(String reason) {
    }

    /**
     * {@code POST /api/v1/releases/{publishNo}/submit-prod},
     * {@code POST /api/v1/releases/{publishNo}/resubmit} and
     * {@code POST /api/v1/releases/{publishNo}/admission-check}. On resubmit both fields are
     * optional: an omitted value reuses what the previous submission recorded (design 4.3: 重跑复用，
     * 不重填). On the draft check they are simply the values currently in the dialog.
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

    /** One of the six admission steps of a fresh judgement. */
    public record AdmissionStepResponse(
        String key,
        String label,
        boolean passed,
        String reason,
        String nextAction) {
    }

    /** The admission snapshot (design 4.5) as the page reads it. */
    public record SnapshotResponse(
        long id,
        long orderId,
        long itemId,
        String submittedBy,
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
        Boolean admitted,
        List<AdmissionStepResponse> steps,
        Object review) {
    }

    /** The execute endpoint's answer (design 4.8): the status after the call, the node and batches. */
    public record ExecuteResponse(
        String publishNo,
        String status,
        String node,
        int batchCount,
        Object review,
        String statusReason) {
    }

    /**
     * The status is re-read after the pipeline was driven, so a handler that failed or paused right
     * away is visible (with the instance's {@code statusReason}); it is not an optimistic echo of the
     * request.
     */
    public static ExecuteResponse of(ExecuteProdService.ExecuteResult result) {
        return new ExecuteResponse(result.publishNo(), result.status(), result.node(),
            result.batchCount(), result.review(), result.statusReason());
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
            view.admitted(),
            view.steps().stream().map(AdmissionDtos::admissionStepOf).toList(),
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
            snapshot.submittedBy(),
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

    private static AdmissionStepResponse admissionStepOf(AdmissionStepView step) {
        return new AdmissionStepResponse(
            step.key(),
            step.label(),
            step.passed(),
            step.reason(),
            step.nextAction());
    }
}