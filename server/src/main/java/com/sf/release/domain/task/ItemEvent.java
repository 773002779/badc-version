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
     * Engine callback: the test deployment succeeded. The main state already is
     * {@code TESTING} by then, so this event is an idempotent acknowledgement
     * ({@code TESTING → TESTING}) rather than a status change; the deployment itself is
     * recorded on the pipeline node.
     */
    TEST_DEPLOY_OK,
    /** Engine callback: the test deployment failed ({@code TESTING → FAILED}). */
    TEST_DEPLOY_FAIL,
    /** The gate evaluator concluded that both gates are satisfied: {@code TESTING → GATE_PENDING}. */
    GATE_PASS,
    /**
     * The gate evaluator concluded that at least one gate is not satisfied:
     * {@code TESTING → GATE_FAILED}. A failed decision can never reach {@code SUBMITTED}, so
     * it cannot be admitted to production by mistake.
     */
    GATE_FAIL,
    /** The developer submits the production release ({@code GATE_PENDING → SUBMITTED}). */
    SUBMIT,
    /** Every required approver approved ({@code PENDING_APPROVAL → READY}). */
    APPROVE_ALL,
    /** An approver rejected ({@code PENDING_APPROVAL → REJECTED}). */
    REJECT,
    /** Operations starts the production deployment ({@code READY → PROD_DEPLOYING}). */
    EXECUTE,
    /**
     * Operations starts a rerun that resumes at {@code PROD_DEPLOY} ({@code TESTING →
     * PROD_DEPLOYING}). A from-node rerun reuses the image and the approved admission, so the
     * release is already in {@code TESTING}; re-approving it would be a second signature for the
     * same admission. The normal first execution stays {@link #EXECUTE}.
     */
    EXECUTE_RERUN,
    /** Engine callback: the production deployment succeeded ({@code PROD_DEPLOYING → DONE}). */
    PROD_OK,
    /** Engine callback: the production deployment failed ({@code PROD_DEPLOYING → FAILED}). */
    PROD_FAIL,
    /** A freeze became effective while the item was submitted or ready. */
    FREEZE_HIT,
    /**
     * A check result became invalid and revokes production admission:
     * {@code TESTING/GATE_PENDING/SUBMITTED/READY → GATE_FAILED}. While {@code TESTING} this
     * covers environment-level checks that run after the test deployment.
     */
    CHECK_INVALID,
    /** The test pass was revoked: {@code TESTING/GATE_PENDING → GATE_FAILED}. */
    TEST_REVOKE,
    /** Rerun from the failed node, reusing the image and image-level checks: {@code GATE_FAILED/FAILED → TESTING}. */
    RERUN,
    /**
     * Full rerun after the commit changed (requirements §4.5): the image and all checks are
     * rebuilt, so the item re-enters {@code BUILDING} ({@code GATE_FAILED/FAILED → BUILDING}).
     */
    RERUN_FULL,
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