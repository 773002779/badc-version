package com.sf.release.domain.gate;

import com.sf.release.domain.task.GateStatus;

import java.util.List;

/**
 * The result of one check-gate evaluation: what the page shows and what the state machine acts
 * on. It carries the arithmetic too ({@code passed} of {@code total}, highest severity, number of
 * blocking findings), because requirements FR-G-05 forbid a greyed-out button without the reason.
 *
 * <p>{@link #gateStatus()} is the persisted orthogonal dimension
 * ({@code release_item.gate_status}); {@link #admits()} is what the production admission needs.
 * The two are derived from the same {@link GateConclusion} so a row and a decision cannot
 * disagree.
 */
public record GateDecision(
    GateConclusion conclusion,
    Severity maxSeverity,
    int passed,
    int total,
    int blocking,
    boolean waivable,
    List<String> reasons) {

    public GateDecision {
        conclusion = conclusion == null ? GateConclusion.RUNNING : conclusion;
        maxSeverity = maxSeverity == null ? Severity.NONE : maxSeverity;
        reasons = reasons == null ? List.of() : List.copyOf(reasons);
    }

    /** True when the check gate lets the release through. */
    public boolean admits() {
        return conclusion.admits();
    }

    /** The persisted {@code release_item.gate_status} value of this conclusion. */
    public GateStatus gateStatus() {
        return switch (conclusion) {
            case RUNNING -> GateStatus.CHECK_RUNNING;
            case PASS -> GateStatus.CHECK_PASS;
            case WAIVED -> GateStatus.CHECK_WAIVED;
            case BLOCK -> GateStatus.CHECK_FAIL;
            case INVALID -> GateStatus.CHECK_INVALID;
        };
    }

    /** A one-line operator summary, never empty. */
    public String summary() {
        return switch (conclusion) {
            case RUNNING -> "检查未完成（" + (total - passed) + "/" + total + "）";
            case PASS -> "检查通过（" + passed + "/" + total + "），最高等级 " + maxSeverity.label();
            case WAIVED -> "已豁免（" + passed + "/" + total + "），最高等级 " + maxSeverity.label();
            case BLOCK -> "检查阻断：" + blocking + " 项未通过，最高等级 " + maxSeverity.label()
                + (waivable ? "（可申请豁免）" : "（不可豁免）");
            case INVALID -> "检查结果失效，需要重新检查";
        };
    }
}