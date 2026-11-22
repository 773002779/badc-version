package com.sf.release.application;

import com.sf.release.domain.admission.AdmissionDecision;

import java.time.Instant;
import java.util.List;

/**
 * One resolved 准入 judgement plus the evidence the admission snapshot records (tech design 4.5).
 *
 * <p>The decision is the pure verdict ({@link AdmissionDecision}); everything else in this record
 * is what the submit use case must write: the order fields ({@code plannedAt}, {@code
 * oaTicketNo}, {@code inWindow} → {@code window_result}) and the snapshot columns ({@code
 * windowId}, {@code freezeIds}, {@code maxSeverity}, {@code checkConclusion}, {@code waiverIds},
 * {@code testConclusion}, {@code testPassBy}, {@code imageDigest}).
 *
 * <p>{@code commitSha} is deliberately absent: design 4.6 compares the <em>image digest</em> only
 * ("实例镜像与快照 digest 不一致 → 40007"), and {@code admission_snapshot} has no commit column, so
 * carrying the commit around would invite a check nobody agreed to.
 *
 * <p>{@code waiverExpireAt} is <em>not</em> stored either: {@code admission_snapshot} has no column
 * for it, and the expiry of a waiver is mutable state that the review re-reads from
 * {@code gate_waiver} by id (design 4.6: "快照里的豁免必须仍是 APPROVED 且未过期"). Copying it into
 * the snapshot would let the two disagree.
 *
 * <p>Only the waiver that actually lifted the gate is listed: a {@code WAIVED} conclusion implies it
 * counted for the same commit and image, while an approved waiver next to a passing gate never
 * counted and would make the review check a waiver that is irrelevant.
 */
public record AdmissionPlan(
    String publishNo,
    long orderId,
    long itemId,
    long unitId,
    long subsystemId,
    Instant plannedAt,
    String oaTicketNo,
    AdmissionDecision decision,
    Long windowId,
    boolean inWindow,
    List<Long> freezeIds,
    String maxSeverity,
    String checkConclusion,
    List<Long> waiverIds,
    Instant waiverExpireAt,
    String testConclusion,
    String testPassBy,
    String imageDigest) {

    public AdmissionPlan {
        oaTicketNo = oaTicketNo == null ? null : oaTicketNo.trim();
        freezeIds = freezeIds == null ? List.of() : List.copyOf(freezeIds);
        waiverIds = waiverIds == null ? List.of() : List.copyOf(waiverIds);
    }

    /** True when at least one freeze was in force at submission time. */
    public boolean frozen() {
        return !freezeIds.isEmpty();
    }

    /** The {@code release_order.window_result} value of this judgement. */
    public String windowResult() {
        return inWindow ? "IN" : "OUT";
    }
}