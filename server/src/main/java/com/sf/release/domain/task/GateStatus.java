package com.sf.release.domain.task;

/**
 * The <strong>check</strong> dimension of a 变更项 (tech design §3.3), persisted as
 * {@code release_item.gate_status} and orthogonal to the main {@link ItemStatus} and to the test
 * dimension ({@link TestStatus}, P2 / V19: the two dimensions used to share this column).
 *
 * <p>{@link GateDecision} computes it from the recorded check rows and the waiver; nothing here is
 * derived from the main status. {@code TEST_*} values are deliberately absent: the test dimension
 * has its own column, and mixing them is what let a check evaluation overwrite a rejected test.
 */
public enum GateStatus {
    /** No result yet, or at least one check is running/unknown. */
    CHECK_RUNNING,
    /** Every check finished and the highest severity is 低危 or below. */
    CHECK_PASS,
    /** The only blocking findings are 中危 and an approved, unexpired waiver covers the snapshot. */
    CHECK_WAIVED,
    /** A blocking finding remains (高危/严重 never waivable, 中危 without a waiver). */
    CHECK_FAIL,
    /** The recorded result no longer applies (commit/image change, redeploy, expired waiver). */
    CHECK_INVALID
}