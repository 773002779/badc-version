package com.sf.release.domain.pipeline;

import java.time.Instant;
import java.util.List;

/**
 * One execution node of a {@link PipelineInstance}. {@code seq} is the 0-based execution
 * order; the online pipeline is {@code BUILD} (0), {@code TEST_DEPLOY} (1), {@code VERIFY} (2)
 * and {@code PROD_DEPLOY} (3). The node owns its {@link NodeTask}s, pre-generated together with
 * the instance (tech design §5.1). A node starts {@code PENDING} and is moved by the
 * orchestrator (Task 11); the order of the tasks inside a node is their insertion order.
 */
public record PipelineNode(
    Long id,
    Long instanceId,
    NodeType nodeType,
    int seq,
    NodeStatus status,
    Instant startedAt,
    Instant finishedAt,
    List<NodeTask> tasks) {

    public PipelineNode {
        seq = Math.max(seq, 0);
        status = status == null ? NodeStatus.PENDING : status;
        tasks = tasks == null ? List.of() : List.copyOf(tasks);
    }

    /** A pre-generated node without an id/instance id yet. */
    public static PipelineNode pending(NodeType nodeType, int seq, List<NodeTask> tasks) {
        return new PipelineNode(null, null, nodeType, seq, NodeStatus.PENDING, null, null, tasks);
    }
}