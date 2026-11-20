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
 * <p>Phase 1 has no handler in this task: Task 12 registers the build handler, Task 13 the
 * test-deploy handler. The orchestrator never skips a node without one — see
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
}