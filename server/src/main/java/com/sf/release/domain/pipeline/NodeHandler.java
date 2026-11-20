package com.sf.release.domain.pipeline;

/**
 * Strategy that starts one pipeline node (tech design §5.2). The orchestrator decides
 * <em>when</em> a node is entered; the handler decides <em>how</em> that node is executed
 * (trigger builds, deploy to DEs, run checks).
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