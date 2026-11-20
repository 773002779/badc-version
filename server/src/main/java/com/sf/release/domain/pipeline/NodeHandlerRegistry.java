package com.sf.release.domain.pipeline;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The handlers the orchestrator may enter, keyed by {@link NodeType}.
 *
 * <p><strong>No silent skip.</strong> {@link #onNodeEnter} throws when the node has no
 * handler instead of leaving the pipeline stuck in a state nobody can explain. A missing
 * bean is a wiring error (Task 12/13 not deployed) and must fail the advance loudly; the
 * surrounding transaction rolls the node entry back.
 *
 * <p>The registry is mutable on purpose: Task 12/13 handlers are registered from
 * configuration (the domain package cannot use Spring stereotypes), and tests replace the
 * set per case. A second handler for the same node type is rejected instead of silently
 * winning.
 */
public final class NodeHandlerRegistry {

    private final Map<NodeType, NodeHandler> handlers = new ConcurrentHashMap<>();

    public NodeHandlerRegistry() {
    }

    public NodeHandlerRegistry(Collection<NodeHandler> initial) {
        if (initial != null) {
            initial.forEach(this::register);
        }
    }

    /**
     * Registers {@code handler} for its node type.
     *
     * @throws IllegalArgumentException when another handler already owns that node type
     */
    public void register(NodeHandler handler) {
        Objects.requireNonNull(handler, "handler must not be null");
        NodeType type = Objects.requireNonNull(handler.nodeType(), "handler node type is required");
        NodeHandler existing = handlers.putIfAbsent(type, handler);
        if (existing != null) {
            throw new IllegalArgumentException(
                "节点 " + type + " 已经注册了 NodeHandler：" + existing.getClass().getName());
        }
    }

    /** Drops every handler; used by configuration replacement and by tests. */
    public void clear() {
        handlers.clear();
    }

    /** The registered handler of {@code nodeType}, or {@code null}. */
    public NodeHandler handler(NodeType nodeType) {
        return nodeType == null ? null : handlers.get(nodeType);
    }

    /** All registered node types, for diagnostics. */
    public List<NodeType> nodeTypes() {
        return List.copyOf(handlers.keySet());
    }

    /**
     * Enters {@code node} of {@code instance}: looks the handler up and calls
     * {@link NodeHandler#start(PipelineInstance)}.
     *
     * @throws IllegalStateException when no handler is registered for the node type
     */
    public void onNodeEnter(PipelineInstance instance, PipelineNode node) {
        Objects.requireNonNull(instance, "instance must not be null");
        Objects.requireNonNull(node, "node must not be null");
        NodeHandler handler = handlers.get(node.nodeType());
        if (handler == null) {
            throw new IllegalStateException(
                "节点 " + node.nodeType() + "（instanceId=" + instance.id()
                    + "）没有注册 NodeHandler，拒绝静默跳过该节点");
        }
        handler.start(instance);
    }
}