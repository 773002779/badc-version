package com.sf.release.domain.task;

/**
 * Events that drive the main state machine of a 变更项 (tech design §3.2). The first block
 * is the shared type set of the implementation plan; the second block holds the events that
 * only appear in the technical design's transition matrix.
 */
public enum ItemEvent {
    /** The user starts an online release: {@code PENDING → BUILDING}. */
    PUBLISH,
    /** Engine callback: the build node succeeded ({@code BUILDING → TESTING}). */
    BUILD_OK,
    /** Engine callback: the build failed ({@code BUILDING → FAILED}). The design calls it FAIL. */
    BUILD_FAIL,
    /**
     * Engine callback: the test deployment succeeded. It is part of the plan's shared type
     * set but has no row in the §3.2 matrix, because the main state already is
     * {@code TESTING} by then; pipeline tasks record it on the node, not on the item.
     */
    TEST_DEPLOY_OK,
    /** Engine callback: the test deployment failed ({@code TESTING → FAILED}). */
    TEST_DEPLOY_FAIL,
    /**
     * The gate evaluator finished: the positive conclusion is {@code TESTING → GATE_PENDING}.
     * The negative conclusion is expressed with {@link #TEST_REVOKE} / {@link #CHECK_INVALID},
     * which both reach {@code GATE_FAILED}.
     */
    GATE_DECIDE,
    /** The developer submits the production release ({@code GATE_PENDING → SUBMITTED}). */
    SUBMIT,
    /** Every required approver approved ({@code PENDING_APPROVAL → READY}). */
    APPROVE_ALL,
    /** An approver rejected ({@code PENDING_APPROVAL → REJECTED}). */
    REJECT,
    /** Operations starts the production deployment ({@code READY → PROD_DEPLOYING}). */
    EXECUTE,
    /** Engine callback: the production deployment succeeded ({@code PROD_DEPLOYING → DONE}). */
    PROD_OK,
    /** Engine callback: the production deployment failed ({@code PROD_DEPLOYING → FAILED}). */
    PROD_FAIL,
    /** A freeze became effective while the item was submitted or ready. */
    FREEZE_HIT,
    /** A check result became invalid: {@code TESTING/GATE_PENDING/PENDING_APPROVAL → GATE_FAILED}. */
    CHECK_INVALID,
    /** The test pass was revoked: {@code TESTING/GATE_PENDING → GATE_FAILED}. */
    TEST_REVOKE,
    /** The user reruns a failed item ({@code GATE_FAILED/FAILED → TESTING}). */
    RERUN,
    /** Owner or administrator closes the item ({@code any non-terminal → CLOSED}). */
    CLOSE,
    /** The user leaves the pipeline ({@code any non-terminal → TERMINATED}). */
    EXIT,
    /** The done item was rolled back ({@code DONE → ROLLED_BACK}). */
    ROLLBACK,

    /** The admission engine determined that approvals are required ({@code SUBMITTED → PENDING_APPROVAL}). */
    NEED_APPROVAL,
    /** The admission engine determined that no approval is required ({@code SUBMITTED → READY}). */
    NO_APPROVAL,
    /** The admission snapshot recheck failed ({@code PENDING_APPROVAL → GATE_FAILED}). */
    GATE_INVALID,
    /** The developer resubmits after a rejection ({@code REJECTED → SUBMITTED}). */
    RESUBMIT
}