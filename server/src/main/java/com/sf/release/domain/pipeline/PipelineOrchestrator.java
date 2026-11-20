package com.sf.release.domain.pipeline;

import com.sf.release.common.BizError;
import com.sf.release.common.BizException;
import com.sf.release.domain.pipeline.event.InstanceFinishedEvent;
import com.sf.release.domain.pipeline.event.NodeCompletedEvent;
import com.sf.release.domain.pipeline.port.PipelineEventBus;
import com.sf.release.domain.pipeline.port.PipelineLock;
import com.sf.release.domain.pipeline.port.PipelineRepository;
import com.sf.release.domain.pipeline.port.TransactionRunner;
import com.sf.release.domain.pipeline.port.UnitLease;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Event-driven advancement of a pipeline instance (tech design §5.2).
 *
 * <p><strong>One advancer at a time.</strong> {@link #advance(long)} first takes
 * {@code lock:pipe:{instanceId}} (30s). A caller that cannot take it returns immediately: the
 * callback has already been persisted by {@link NodeTaskCompletionService}, and the current
 * holder's advance covers it.
 *
 * <p><strong>Advance rules implemented here.</strong>
 * <ol>
 *   <li>Every successful advance renews the 部署单元 lease first. A false renewal marks the
 *       instance {@code FAILED} with a lock-lost reason and stops; the pipeline never continues
 *       without its unit.</li>
 *   <li>A {@code PENDING} node is entered: instance {@code → RUNNING}, node
 *       {@code → RUNNING}, and its {@link NodeHandler} is called. A missing handler throws
 *       (never a silent skip).</li>
 *   <li>The running node completes only when every non-{@code SKIPPED} task is
 *       {@code SUCCESS}. The completion is also guarded in SQL, because task completions land
 *       outside the instance lock.</li>
 *   <li>On completion, the next node of the instance starts; after the last node the instance
 *       becomes {@code DONE}. Events are published inside the same transaction.</li>
 *   <li>A {@code FAILED}/{@code TIMEOUT} task fails the node and the instance, skips the
 *       still-{@code PENDING} siblings of that node, keeps every already-{@code SUCCESS} row and
 *       never starts the next node (production batches keep their successful batches).</li>
 *   <li>Every decision and event of one advance is one transaction; duplicate callbacks produce
 *       no second completion, no second event and no second node start (CAS on node status).</li>
 * </ol>
 *
 * <p>Starting the node's handler inside the transaction means an engine trigger failure rolls
 * the node entry back, so a retry does not find a half-entered node. The handler itself is
 * Task 12/13 work; this class contains no engine call.
 */
public final class PipelineOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(PipelineOrchestrator.class);

    private static final Set<NodeTaskStatus> FAILING = EnumSet.of(
        NodeTaskStatus.FAILED, NodeTaskStatus.TIMEOUT);
    private static final Set<InstanceStatus> TERMINAL = EnumSet.of(
        InstanceStatus.DONE, InstanceStatus.FAILED, InstanceStatus.CLOSED,
        InstanceStatus.TERMINATED);

    private final PipelineRepository repository;
    private final NodeTaskCompletionService completionService;
    private final PipelineLock pipelineLock;
    private final UnitLease unitLease;
    private final TransactionRunner transactionRunner;
    private final NodeHandlerRegistry handlerRegistry;
    private final PipelineEventBus eventBus;

    public PipelineOrchestrator(PipelineRepository repository,
                                NodeTaskCompletionService completionService,
                                PipelineLock pipelineLock,
                                UnitLease unitLease,
                                TransactionRunner transactionRunner,
                                NodeHandlerRegistry handlerRegistry,
                                PipelineEventBus eventBus) {
        this.repository = Objects.requireNonNull(repository, "repository must not be null");
        this.completionService = Objects.requireNonNull(completionService,
            "completionService must not be null");
        this.pipelineLock = Objects.requireNonNull(pipelineLock, "pipelineLock must not be null");
        this.unitLease = Objects.requireNonNull(unitLease, "unitLease must not be null");
        this.transactionRunner = Objects.requireNonNull(transactionRunner,
            "transactionRunner must not be null");
        this.handlerRegistry = Objects.requireNonNull(handlerRegistry,
            "handlerRegistry must not be null");
        this.eventBus = Objects.requireNonNull(eventBus, "eventBus must not be null");
    }

    /**
     * Handles one completion callback: persist it idempotently and, for a terminal result,
     * advance the instance. The completion is persisted first, so losing the advance lock does
     * not lose the result.
     *
     * @param status {@link NodeTaskStatus}; {@code TaskStatus} in the task brief is the release
     *               task lifecycle ({@code ACTIVE}/{@code ARCHIVED}) and cannot describe a
     *               {@code node_task}, so this is the node-task status
     */
    public void onTaskCompleted(long taskId, NodeTaskStatus status, String digest, String message) {
        Objects.requireNonNull(status, "status must not be null");
        NodeTaskCompletionService.Completion completion = transactionRunner.inTransaction(
            () -> completionService.apply(taskId, status, digest, message));
        if (completion.terminal()) {
            advance(completion.instanceId());
        }
    }

    /**
     * Advances one instance if this caller can take the instance lock. The lock winner performs
     * the completion decision, status writes and event publication in one transaction.
     */
    public void advance(long instanceId) {
        if (!pipelineLock.acquire(instanceId)) {
            log.debug("pipeline {} is being advanced by another caller, skipping", instanceId);
            return;
        }
        try {
            transactionRunner.inTransaction(() -> advanceInTransaction(instanceId));
        } finally {
            pipelineLock.release(instanceId);
        }
    }

    // ------------------------------------------------------------------ advance steps

    private void advanceInTransaction(long instanceId) {
        PipelineInstance instance = repository.loadInstance(instanceId);
        if (instance == null) {
            throw new BizException(BizError.PARAM_INVALID, "流水线实例不存在",
                Map.of("instanceId", instanceId));
        }
        if (TERMINAL.contains(instance.status())) {
            log.debug("pipeline {} is already terminal: {}", instanceId, instance.status());
            return;
        }
        PipelineRepository.InstanceContext context = repository.contextOf(instanceId);
        Instant now = Instant.now();
        if (!renewUnitLease(instance, context, now)) {
            return;
        }

        PipelineNode current = instance.node(instance.currentNode());
        if (current == null) {
            throw new IllegalStateException("流水线实例 " + instanceId + " 的当前节点 "
                + instance.currentNode() + " 不在节点列表中");
        }
        if (current.status() == NodeStatus.PENDING) {
            enterNode(instance, current, now);
            return;
        }
        if (current.status() != NodeStatus.RUNNING) {
            log.debug("pipeline {} node {} is {}, nothing to advance", instanceId,
                current.nodeType(), current.status());
            return;
        }

        NodeTask failedTask = firstFailed(current.tasks());
        if (failedTask != null) {
            failNode(instance, current, context, failedTask, now);
            return;
        }
        if (!allTasksSucceeded(current.tasks())) {
            return;
        }
        if (!repository.finishNodeIfComplete(current.id(), now)) {
            // The SQL guard found a failed task or another advance already completed the node.
            log.debug("pipeline {} node {} completion guard rejected the transition", instanceId,
                current.nodeType());
            return;
        }
        eventBus.publish(new NodeCompletedEvent(instanceId, context.orderId(),
            current.nodeType(), NodeStatus.SUCCESS, null, now));

        PipelineNode next = nextNode(instance, current);
        if (next == null) {
            if (repository.finishInstance(instanceId, InstanceStatus.DONE, now)) {
                log.info("pipeline finished: instanceId={}, status=DONE", instanceId);
                eventBus.publish(new InstanceFinishedEvent(instanceId, context.orderId(),
                    InstanceStatus.DONE, "所有节点已完成", now));
            }
            return;
        }
        enterNode(instance, next, now);
    }

    /**
     * Enters {@code node}: moves the instance (first start or next node), flips the node to
     * {@code RUNNING} and calls its handler. All three changes are CAS; a missing handler throws
     * and the surrounding transaction rolls the entry back.
     */
    private void enterNode(PipelineInstance instance, PipelineNode node, Instant now) {
        boolean first = instance.status() == InstanceStatus.PENDING;
        boolean moved = first
            ? repository.startInstance(instance.id(), node.nodeType(), now)
            : repository.moveToNode(instance.id(), instance.currentNode(), node.nodeType());
        if (!moved) {
            log.debug("pipeline {} was already moved away from node {}", instance.id(),
                node.nodeType());
            return;
        }
        if (!repository.startNode(node.id(), now)) {
            log.debug("pipeline {} node {} was already started", instance.id(), node.nodeType());
            return;
        }
        PipelineInstance entered = enteredInstance(instance, node, now);
        handlerRegistry.onNodeEnter(entered, entered.node(node.nodeType()));
        log.info("pipeline node entered: instanceId={}, node={}, seq={}", instance.id(),
            node.nodeType(), node.seq());
    }

    /**
     * Stops an instance because a task failed or timed out: the node and instance become
     * {@code FAILED}, the still-{@code PENDING} tasks of the node are {@code SKIPPED}, earlier
     * {@code SUCCESS} rows are untouched and the next node is never started.
     */
    private void failNode(PipelineInstance instance, PipelineNode node,
                          PipelineRepository.InstanceContext context, NodeTask failedTask,
                          Instant now) {
        if (!repository.finishNode(node.id(), NodeStatus.FAILED, now)) {
            log.debug("pipeline {} node {} was already finished", instance.id(), node.nodeType());
            return;
        }
        repository.skipPendingTasks(instance.id(), node.nodeType(), now);
        String reason = failureReason(failedTask);
        log.warn("pipeline node failed: instanceId={}, node={}, taskId={}, reason={}",
            instance.id(), node.nodeType(), failedTask.id(), reason);
        eventBus.publish(new NodeCompletedEvent(instance.id(), context.orderId(),
            node.nodeType(), NodeStatus.FAILED, reason, now));
        if (repository.finishInstance(instance.id(), InstanceStatus.FAILED, now)) {
            eventBus.publish(new InstanceFinishedEvent(instance.id(), context.orderId(),
                InstanceStatus.FAILED, reason, now));
        }
    }

    /**
     * Renews the unit lease before doing any work. A pipeline that cannot prove it still owns
     * its 部署单元 must stop; it is failed with an explicit reason instead of continuing to
     * deploy over another publisher's unit.
     *
     * @return {@code true} when the advance may continue
     */
    private boolean renewUnitLease(PipelineInstance instance,
                                   PipelineRepository.InstanceContext context, Instant now) {
        if (context.unitId() <= 0) {
            // A daily instance has no release order/unit lock (design §5.1/§9): nothing to renew.
            return true;
        }
        if (unitLease.renew(context.unitId(), instance.id())) {
            return true;
        }
        String reason = "部署单元锁丢失（unitId=" + context.unitId() + ", instanceId="
            + instance.id() + "），流水线停止推进";
        log.error("pipeline advance aborted: instanceId={}, {}", instance.id(), reason);
        if (repository.finishInstance(instance.id(), InstanceStatus.FAILED, now)) {
            eventBus.publish(new InstanceFinishedEvent(instance.id(), context.orderId(),
                InstanceStatus.FAILED, reason, now));
        }
        return false;
    }

    // ------------------------------------------------------------------ helpers

    private static boolean allTasksSucceeded(List<NodeTask> tasks) {
        for (NodeTask task : tasks) {
            if (task.status() != NodeTaskStatus.SKIPPED
                    && task.status() != NodeTaskStatus.SUCCESS) {
                return false;
            }
        }
        return true;
    }

    private static NodeTask firstFailed(List<NodeTask> tasks) {
        for (NodeTask task : tasks) {
            if (FAILING.contains(task.status())) {
                return task;
            }
        }
        return null;
    }

    private static PipelineNode nextNode(PipelineInstance instance, PipelineNode current) {
        PipelineNode next = null;
        for (PipelineNode node : instance.nodes()) {
            if (node.seq() > current.seq() && (next == null || node.seq() < next.seq())) {
                next = node;
            }
        }
        return next;
    }

    private static String failureReason(NodeTask task) {
        if (task.resultSummary() != null && !task.resultSummary().isBlank()) {
            return task.resultSummary();
        }
        return "任务 " + task.taskType() + " " + task.status();
    }

    /**
     * The aggregate as the handler should see it after the node entry: the node is
     * {@code RUNNING} and the instance points at it. The in-memory nodes are a copy; the
     * persisted rows were already updated by CAS.
     */
    private static PipelineInstance enteredInstance(PipelineInstance instance, PipelineNode node,
                                                    Instant now) {
        List<PipelineNode> nodes = instance.nodes().stream()
            .map(candidate -> candidate.nodeType() == node.nodeType()
                ? new PipelineNode(candidate.id(), candidate.instanceId(),
                    candidate.nodeType(), candidate.seq(), NodeStatus.RUNNING, now,
                    candidate.finishedAt(), candidate.tasks())
                : candidate)
            .toList();
        return new PipelineInstance(instance.id(), instance.orderId(), instance.pipelineNo(),
            instance.execType(), instance.triggerType(), node.nodeType(),
            InstanceStatus.RUNNING, instance.imageDigest(), instance.commitSha(),
            instance.startAt() == null ? now : instance.startAt(), null,
            instance.createdAt(), nodes);
    }
}