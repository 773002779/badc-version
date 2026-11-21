package com.sf.release.domain.task;

/**
 * The gate dimension of a 变更项 (tech design §3.3), persisted as
 * {@code release_item.gate_status} and orthogonal to the main {@link ItemStatus}: the main
 * state says "how far the item got", the gate status says "what each of the two gates
 * concluded". {@code GateEvaluator} (a later task) computes it; Task 8 only stores and
 * carries the value, and never derives a transition from it.
 */
public enum GateStatus {
    /** Not tested yet, or the test is running. */
    TEST_PENDING,
    /** Test passed; the passing user and time are kept in {@code last_test_pass_by/at}. */
    TEST_PASS,
    /** Test failed. */
    TEST_FAIL,
    /** A previously recorded test pass was revoked by the tester. */
    TEST_REVOKED,
    /** The check is running. */
    CHECK_RUNNING,
    /** The check passed (highest severity below medium). */
    CHECK_PASS,
    /** A medium-severity finding is covered by an approved waiver. */
    CHECK_WAIVED,
    /** The check failed (high severity is not waivable). */
    CHECK_FAIL,
    /** The check result is no longer valid (commit/image change or redeploy). */
    CHECK_INVALID
}