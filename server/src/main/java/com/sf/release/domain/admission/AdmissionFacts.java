package com.sf.release.domain.admission;

import com.sf.release.domain.gate.GateConclusion;
import com.sf.release.domain.task.TestStatus;

import java.time.Instant;
import java.util.List;

/**
 * Everything the pure 准入 judgement needs (tech design 4.5), already resolved by the application
 * layer: the gate conclusion, the recorded test dimension, the OA ticket the operator typed, one
 * window check per planned instant, and whether a freeze is in force at submission time.
 *
 * <p>{@code now} is passed in instead of read here, so the judgement is deterministic and testable
 * without a clock. {@code plannedAt} may be {@code null} (the dialog omitted it): the window and
 * planned-time steps then fail with "请填写" instead of guessing.
 *
 * <p>{@code gateReasons} are the {@code GateDecision.reasons()} of the check gate — the missing
 * items the operator must read. {@code waivable} says whether a waiver could still lift the block
 * (中危 only).
 */
public record AdmissionFacts(
    String oaTicketNo,
    Instant plannedAt,
    Instant now,
    GateConclusion gateConclusion,
    boolean waivable,
    List<String> gateReasons,
    TestStatus testStatus,
    boolean inWindow,
    boolean frozen) {

    public AdmissionFacts {
        gateConclusion = gateConclusion == null ? GateConclusion.RUNNING : gateConclusion;
        gateReasons = gateReasons == null ? List.of() : List.copyOf(gateReasons);
        testStatus = testStatus == null ? TestStatus.TEST_PENDING : testStatus;
    }
}