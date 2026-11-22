package com.sf.release.application;

import com.sf.release.domain.admission.AdmissionDecision;
import com.sf.release.domain.admission.AdmissionStep;
import com.sf.release.infra.repo.po.AdmissionSnapshotPO;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The read model of one 发布单's production admission (tech design 4.8, {@code GET
 * /api/v1/releases/{publishNo}/admission}, and the answer every submit/resubmit/admission-check
 * returns).
 *
 * <p>{@code snapshot} is the <em>latest</em> admission snapshot and {@code chain} the approval steps
 * of that snapshot — not of the order. After a rejection and a resubmission the old snapshot and its
 * steps stay in the database as history, and this view shows the current generation only.
 *
 * <p>{@code admitted} and {@code steps} are the evidence of a <em>fresh</em> judgement (submit,
 * resubmit, admission-check). A read of the stored admission answers them with {@code null} /
 * {@code empty}: the snapshot records the outcome (conclusion, window, freeze, chain), not the six
 * step sentences, and re-evaluating it would recompute a window that design 4.6 says must not be
 * recomputed.
 *
 * <p>{@code review} is the pre-execution review preview (design 4.6: freeze in force, waiver
 * validity, image digest) computed by {@link ReviewService} for the <em>stored</em> snapshot; it is
 * {@code null} until the release has a snapshot. It is a preview, not a promise: the execute use
 * case re-runs the same review right before touching the pipeline.
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
    Boolean admitted,
    List<AdmissionStepView> steps,
    Object review) {

    public AdmissionView {
        chain = chain == null ? List.of() : List.copyOf(chain);
        steps = steps == null ? List.of() : List.copyOf(steps);
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
            po.getSubmittedBy(),
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

    /** The six judged steps of a fresh decision, flattened for the wire. */
    public static List<AdmissionStepView> stepsOf(AdmissionDecision decision) {
        return decision.steps().stream()
            .map(step -> new AdmissionStepView(step.key().name(), step.key().label(),
                step.passed(), step.reason(), step.nextAction()))
            .toList();
    }

    /** The six judged steps of a fresh decision as a payload list (the refusal body). */
    public static List<Map<String, Object>> stepPayloads(AdmissionDecision decision) {
        List<Map<String, Object>> payloads = new ArrayList<>(decision.steps().size());
        for (AdmissionStep step : decision.steps()) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("key", step.key().name());
            payload.put("label", step.key().label());
            payload.put("passed", step.passed());
            payload.put("reason", step.reason());
            payload.put("nextAction", step.nextAction());
            payloads.add(payload);
        }
        return List.copyOf(payloads);
    }
}