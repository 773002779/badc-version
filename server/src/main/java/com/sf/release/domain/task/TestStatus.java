package com.sf.release.domain.task;

/**
 * The <strong>test</strong> dimension of a 变更项 (requirements §4.4, tech design §3.3), persisted as
 * {@code release_item.test_status} and orthogonal to both the main {@link ItemStatus} and the check
 * dimension ({@link GateStatus}).
 *
 * <p><strong>Why a separate column.</strong> P1 kept both dimensions in {@code gate_status}, which
 * only works while one of them is unused: recording a check result erased a rejected test. The page
 * shows the two gates separately ("这个发布测过了吗 / 代码查过了吗"), so they are stored separately.
 *
 * <p>{@link #TEST_PENDING} is the creation value. Only {@link #TEST_PASS} lets the production
 * admission through; a waiver lifts the check dimension only (FR-Q-03).
 */
public enum TestStatus {
    /** Not tested yet, or the test is running. */
    TEST_PENDING,
    /** Passed; who and when are in {@code last_test_pass_by/at}. */
    TEST_PASS,
    /** Failed; the reason is in {@code test_fail_reason}. */
    TEST_FAIL,
    /** A recorded pass was revoked by the person who gave it (C19); the reason is kept. */
    TEST_REVOKED;

    public boolean passed() {
        return this == TEST_PASS;
    }

    /** Chinese label for the API/page; the enum name is the API value. */
    public String label() {
        return switch (this) {
            case TEST_PENDING -> "待测试";
            case TEST_PASS -> "测试通过";
            case TEST_FAIL -> "测试不通过";
            case TEST_REVOKED -> "测试已撤回";
        };
    }

    /** Parses a persisted value; an unknown word degrades to {@link #TEST_PENDING}. */
    public static TestStatus parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return TEST_PENDING;
        }
        try {
            return valueOf(raw.trim());
        } catch (IllegalArgumentException ex) {
            return TEST_PENDING;
        }
    }
}