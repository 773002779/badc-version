package com.sf.release.domain.task;

import java.util.Set;

/**
 * Persisted status of a 变更项 (release item), from the main state machine
 * (tech design §3.2). Task 7 only needs the classification used by task progress; the
 * transition matrix ({@code ItemStateMachine}) belongs to Task 8.
 *
 * <p>{@link #isDone()} is the success end state (production deployment succeeded; for a
 * 二方库, the artifact was stored) and is what FR-T-04 counts in the completion rate.
 * {@link #isTerminal()} lists the closed-out side ends of the flow. {@code DONE} is
 * deliberately not in that set: it is the success end state and may still be rolled back,
 * so a {@link #isBlocking()} item is {@code !isDone() && !isTerminal()} — exactly the
 * 「有阻塞」 definition of FR-T-04 / J9.
 *
 * <p>Note that "terminal" here means "no longer blocking", <em>not</em> "no outgoing
 * events": {@link #DONE} still accepts {@code ROLLBACK}, and {@link #GATE_FAILED},
 * {@link #FAILED} and {@link #REJECTED} can still be rerun, resubmitted, closed or exited.
 * The states without any outgoing event are {@code CLOSED}, {@code TERMINATED} and
 * {@code ROLLED_BACK} (see {@code ItemStateMachine}).
 */
public enum ItemStatus {
    PENDING,
    BUILDING,
    TESTING,
    GATE_PENDING,
    SUBMITTED,
    PENDING_APPROVAL,
    READY,
    PROD_DEPLOYING,
    DONE,
    GATE_FAILED,
    FAILED,
    REJECTED,
    CLOSED,
    TERMINATED,
    ROLLED_BACK;

    private static final Set<ItemStatus> CLOSED_OUT = Set.of(
        GATE_FAILED, FAILED, REJECTED, CLOSED, TERMINATED, ROLLED_BACK);

    /** The success end state: counted in the completion rate. */
    public boolean isDone() {
        return this == DONE;
    }

    /** A closed-out side end: neither in progress nor a failure to act on any more. */
    public boolean isTerminal() {
        return CLOSED_OUT.contains(this);
    }

    /**
     * True while the item is somewhere in the pipeline and not yet done: 待测试 / 待审批 /
     * 待执行 and every other in-flight state.
     */
    public boolean isBlocking() {
        return !isDone() && !isTerminal();
    }
}