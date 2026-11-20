package com.sf.release.domain.pipeline;

import java.time.Instant;
import java.util.List;

/**
 * A 流水线实例: one execution of a {@link ReleaseOrder} (tech design §5.1, aggregate
 * boundary §3.1). The instance is the aggregate root of its pre-generated {@link PipelineNode}s
 * and their {@link NodeTask}s; {@code nodes} is the in-memory content built by
 * {@link PipelineFactory} and persisted into three tables.
 *
 * <p>Every execution creates a new instance (initial run, rerun-from-node, full rerun,
 * rollback) while the publish number of the order stays unchanged. {@code currentNode} points
 * at the node the orchestrator is about to run; a fresh online instance starts in
 * {@code BUILD} with {@link InstanceStatus#PENDING}.
 */
public record PipelineInstance(
    Long id,
    Long orderId,
    String pipelineNo,
    ExecType execType,
    TriggerType triggerType,
    NodeType currentNode,
    InstanceStatus status,
    String imageDigest,
    String commitSha,
    Instant startAt,
    Instant endAt,
    Instant createdAt,
    List<PipelineNode> nodes) {

    public PipelineInstance {
        pipelineNo = trimToNull(pipelineNo);
        imageDigest = trimToNull(imageDigest);
        commitSha = trimToNull(commitSha);
        execType = execType == null ? ExecType.ONLINE : execType;
        triggerType = triggerType == null ? TriggerType.INITIAL : triggerType;
        status = status == null ? InstanceStatus.PENDING : status;
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
    }

    /** The node with the given type, or {@code null} when this instance does not have it. */
    public PipelineNode node(NodeType type) {
        for (PipelineNode node : nodes) {
            if (node.nodeType() == type) {
                return node;
            }
        }
        return null;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}