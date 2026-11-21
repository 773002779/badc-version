package com.sf.release.domain.gate;

/**
 * What the check gate concluded (requirements §4.3/FR-Q-02). Four operator-facing outcomes plus
 * {@link #INVALID} for a result that no longer applies:
 *
 * <ul>
 *   <li>{@link #RUNNING}: at least one check has not finished (or the engine is unreachable) —
 *       this is <em>never</em> read as a pass;</li>
 *   <li>{@link #PASS}: every check finished and the highest severity is 低危 or below;</li>
 *   <li>{@link #WAIVED}: the only blocking findings are 中危 and an approved, unexpired waiver
 *       covers the current snapshot;</li>
 *   <li>{@link #BLOCK}: a blocking finding remains. {@link GateDecision#waivable()} tells the page
 *       whether a waiver could still lift it (中危) or not (高危/严重);</li>
 *   <li>{@link #INVALID}: a recorded result was invalidated (commit/image change, redeploy). It
 *       blocks and is not waivable; the checks must run again.</li>
 * </ul>
 */
public enum GateConclusion {
    RUNNING,
    PASS,
    WAIVED,
    BLOCK,
    INVALID;

    /** True when this conclusion lets the check gate through. */
    public boolean admits() {
        return this == PASS || this == WAIVED;
    }

    /** Chinese label for the API/page; the enum name is the API value. */
    public String label() {
        return switch (this) {
            case RUNNING -> "检查中";
            case PASS -> "检查通过";
            case WAIVED -> "已豁免";
            case BLOCK -> "检查阻断";
            case INVALID -> "结果失效";
        };
    }
}