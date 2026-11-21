package com.sf.release.domain.pipeline;

/**
 * Strategy that starts one pipeline node (tech design §5.2). The orchestrator decides
 * <em>when</em> a node is entered; the handler decides <em>how</em> that node is executed
 * (trigger builds, deploy to DEs, run checks).
 *
 * <p><strong>No transaction is held while this runs.</strong> The orchestrator validates the
 * handler and commits the node-entry status first, then calls {@link #start(PipelineInstance)}
 * after that commit (still inside the short {@code lock:pipe:*} critical section). Engine HTTP
 * calls therefore do not hold pipeline row locks. A handler that needs to persist something
 * must do so through its own repository call and a short transaction; it must not assume the
 * orchestrator's transaction, and it must not call {@link PipelineOrchestrator#advance(long)}
 * synchronously from the same thread (the instance lock is still held).
 *
 * <p>Task 12 provides the build handler, Task 13 the test-deploy handler. The orchestrator
 * never skips a node without one — see
 * {@link NodeHandlerRegistry#onNodeEnter(PipelineInstance, PipelineNode)}.
 */
public interface NodeHandler {

    /** The node this handler executes; one handler per node type. */
    NodeType nodeType();

    /**
     * Starts {@code instance}'s node. The handler owns the engine calls and task status
     * transitions of that node; it must not assume it runs once only (a re-entry after a
     * recoverable failure is possible).
     */
    void start(PipelineInstance instance);

    /**
     * Called by the orchestrator <em>after</em> a terminal task completion of this handler's
     * node has committed, so a handler can start the next task of its chain. The hook must not
     * open a long transaction around an engine call (the orchestrator's instance lock is held
     * and the completion already committed) and it must never call
     * {@link PipelineOrchestrator#advance(long)}: the orchestrator's advance runs right after
     * this hook returns, and a re-entrant call is ignored on purpose.
     *
     * <p>The default is a no-op, so handlers without a chain need not implement it. An
     * exception thrown here is logged by the orchestrator and does not hide a task failure the
     * handler already persisted.
     */
    default void onTaskCompleted(PipelineInstance instance, NodeTask completed) {
    }

    /**
     * Whether this node is complete even though its counted tasks do not say so — the hook the
     * {@code VERIFY} node needs, because its completion is decided by the gate rather than by the
     * {@code CHECK} task rows (which never gate a node, see the orchestrator's completion fold).
     *
     * <p>The orchestrator asks this only for a node whose counted tasks are not all successful; a
     * node with no counted task at all (a check-only {@code VERIFY}) otherwise never completes. The
     * hook must be <strong>cheap and read-only</strong>: it runs inside the advance transaction,
     * while the instance lock is held. A {@code false} answer leaves the behaviour of P1 untouched
     * (the node keeps running).
     */
    default boolean mayComplete(PipelineInstance instance, PipelineNode node) {
        return false;
    }

    /**
     * The reason this node must fail now, or {@code null} when it may keep running. This is how a
     * handler reports a <em>final</em> failure that is not a task failure: a blocked gate, a
     * revoked test pass. The orchestrator fails the node and the instance with that reason, keeping
     * every already-successful task and skipping the pending siblings, so the unit lock is released
     * and the item can be rerun — instead of leaving a node {@code RUNNING} forever.
     */
    default String failureReason(PipelineInstance instance, PipelineNode node) {
        return null;
    }
}