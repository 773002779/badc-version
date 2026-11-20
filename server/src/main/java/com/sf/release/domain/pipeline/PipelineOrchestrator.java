package com.sf.release.domain.pipeline;

import com.sf.release.common.BizError;
import com.sf.release.common.BizException;
import com.sf.release.domain.engine.EngineException;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Event-driven advancement of a pipeline instance (tech design §5.2).
 *
 * <p><strong>One advancer at a time.</strong> {@link #advance(long)} first takes
 * {@code lock:pipe:{instanceId}} (30s). A caller that cannot take it retries a bounded number of
 * times (short sleeps, no lock held in between) so it can see the winner's commit; after the last
 * attempt it returns because the callback was already persisted and the 30s reconcile (Task 14)
 * remains the durable backstop.
 *
 * <p><strong>Advance rules implemented here.</strong>
 * <ol>
 *   <li>Every successful advance renews the 部署单元 lease first. A false renewal marks the
 *       instance {@code FAILED} with a lock-lost reason and stops; the pipeline never continues
 *       without its unit. The unit lock is released (ownership-scoped, after the transaction
 *       commits) when the instance reaches a terminal state.</li>
 *   <li>A {@code PENDING} node is entered: instance {@code → RUNNING}, node
 *       {@code → RUNNING}, then its {@link NodeHandler} is called <em>after</em> the transaction
 *       commits, so an engine HTTP call never holds the instance/node row locks or outlives the
 *       short instance lease. The handler's existence is validated inside the transaction, so a
 *       missing handler rolls the entry back and fails loudly instead of silently skipping.</li>
 *   <li>The running node completes only when every counted task is {@code SUCCESS} (or
 *       {@code SKIPPED}). Counting uses the <strong>latest attempt</strong> of every logical
 *       {@code (task_type, batch_no, target_id)} key (design §5.4 keeps old rows) and ignores
 *       {@code CHECK} tasks, whose results arrive asynchronously and must not gate {@code VERIFY}
 *       completion (design §5.2 rule 4). The same rules are mirrored in the SQL completion
 *       guard.</li>
 *   <li>On completion, the next node of the instance starts; after the last node the instance
 *       becomes {@code DONE}. Events are published inside the same transaction.</li>
 *   <li>A {@code FAILED}/{@code TIMEOUT} task (latest attempt, non-{@code CHECK}) fails the node
 *       and the instance, skips the still-{@code PENDING} siblings of that node, keeps every
 *       already-{@code SUCCESS} row and never starts the next node (production batches keep their
 *       successful batches).</li>
 *   <li>A handler that throws after its node entry was committed fails the node and the instance
 *       in a new short transaction, with an explicit reason; the rollback guarantee of the entry
 *       is replaced by that failure path.</li>
 *   <li>A committed terminal task completion is dispatched to its node handler's
 *       {@link NodeHandler#onTaskCompleted} hook <em>before</em> {@link #advance(long)}, so a
 *       chained step (for example {@code CODE_CHECK → PKG_BUILD}) exists as
 *       {@code WAITING_EXTERNAL} when the node completion is evaluated; a handler failure
 *       recorded as a failed task is then seen by the normal failure path. The hook and the
 *       node handler run under a re-entrancy guard: an {@code advance} called from inside a
 *       handler is ignored instead of re-entering the instance lock.</li>
 * </ol>
 */
public final class PipelineOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(PipelineOrchestrator.class);

    /** Bounded re-attempts when another caller holds the instance lock (100ms apart). */
    static final int ADVANCE_LOCK_ATTEMPTS = 3;
    static final long ADVANCE_LOCK_RETRY_MILLIS = 100L;

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

    /**
     * Set while a {@link NodeHandler} runs on this thread. The instance lock is held by the
     * orchestrator during the node entry, so a handler that calls {@link #advance(long)} would
     * recurse into the same lock; such a call is ignored with a warning instead.
     */
    private final ThreadLocal<Boolean> handlerRunning = ThreadLocal.withInitial(() -> false);

    /**
     * Instance whose advance was requested from inside a handler and therefore had to be
     * postponed. The outer advance runs it once after releasing the instance lock, so a
     * completion delivered on the triggering thread (in-process callback) cannot leave the
     * node RUNNING with all tasks SUCCESS and the next node unentered.
     */
    private final ThreadLocal<Long> deferredAdvance = new ThreadLocal<>();

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
            if (completion.changed()) {
                // After the commit: the handler may start the next task of its chain, and the
                // advance below then sees a node that is not complete yet (or the failure the
                // handler persisted).
                notifyHandlerTaskCompleted(completion.instanceId(), taskId);
            }
            advance(completion.instanceId());
        }
    }

    /**
     * Dispatches one committed completion to its node handler. A handler exception is logged
     * and swallowed here: the handler is expected to persist a task failure itself (for
     * example an engine exception), and the following {@link #advance(long)} then runs the
     * normal node/instance failure path. The guard prevents a handler from calling
     * {@code advance} synchronously.
     */
    private void notifyHandlerTaskCompleted(long instanceId, long taskId) {
        PipelineInstance instance = repository.loadInstance(instanceId);
        NodeTask completed = repository.loadTask(taskId);
        if (instance == null || completed == null) {
            log.warn("task completion not dispatched: instanceId={}, taskId={}, found={}",
                instanceId, taskId, instance != null && completed != null);
            return;
        }
        boolean nested = Boolean.TRUE.equals(handlerRunning.get());
        handlerRunning.set(true);
        try {
            handlerRegistry.onTaskCompleted(instance, completed);
        } catch (EngineException ex) {
            // The handler already persisted this engine failure through the completion
            // service (task FAILED + event); the advance below runs the normal failure path.
            log.warn("node handler could not start the next step: instanceId={}, taskId={},"
                + " taskType={}, reason={}", instanceId, taskId, completed.taskType(),
                ex.getMessage());
        } catch (RuntimeException ex) {
            // Any other handler bug must not be swallowed: fail the node/instance through the
            // same path as a node-entry handler failure (design §6.4: no silent failure).
            failHandlerCompletion(instance, completed, ex);
        } finally {
            restoreHandlerGuard(nested);
        }
    }

    /**
     * Advances one instance if this caller can take the instance lock. The lock winner performs
     * the completion decision, status writes and event publication in one transaction; the node
     * handler is invoked after that transaction commits but still inside the instance lock.
     */
    public void advance(long instanceId) {
        if (Boolean.TRUE.equals(handlerRunning.get())) {
            log.warn("advance requested from inside a node handler; deferring it until the"
                + " instance lock is released: instanceId={}", instanceId);
            deferredAdvance.set(instanceId);
            return;
        }
        for (int attempt = 1; attempt <= ADVANCE_LOCK_ATTEMPTS; attempt++) {
            if (pipelineLock.acquire(instanceId)) {
                try {
                    AdvanceOutcome outcome = transactionRunner.inTransaction(
                        () -> advanceInTransaction(instanceId));
                    runAfterCommit(outcome);
                } finally {
                    pipelineLock.release(instanceId);
                }
                runDeferredAdvance();
                return;
            }
            if (attempt < ADVANCE_LOCK_ATTEMPTS) {
                log.debug("pipeline {} advance lock busy, retrying ({}/{})", instanceId, attempt,
                    ADVANCE_LOCK_ATTEMPTS);
                if (!pauseBeforeRetry(instanceId)) {
                    return;
                }
            }
        }
        log.debug("pipeline {} advance lock stayed busy; callback remains persisted for reconcile",
            instanceId);
    }

    // ------------------------------------------------------------------ advance steps

    private AdvanceOutcome advanceInTransaction(long instanceId) {
        PipelineInstance instance = repository.loadInstance(instanceId);
        if (instance == null) {
            throw new BizException(BizError.PARAM_INVALID, "流水线实例不存在",
                Map.of("instanceId", instanceId));
        }
        PipelineRepository.InstanceContext context = repository.contextOf(instanceId);
        if (TERMINAL.contains(instance.status())) {
            log.debug("pipeline {} is already terminal: {}, releasing its unit if still held",
                instanceId, instance.status());
            return AdvanceOutcome.terminal(instanceId, context);
        }
        Instant now = Instant.now();
        if (!renewUnitLease(instance, context, now)) {
            return AdvanceOutcome.terminal(instanceId, context);
        }

        PipelineNode current = instance.node(instance.currentNode());
        if (current == null) {
            throw new IllegalStateException("流水线实例 " + instanceId + " 的当前节点 "
                + instance.currentNode() + " 不在节点列表中");
        }
        if (current.status() == NodeStatus.PENDING) {
            return enterNode(instance, current, context, now);
        }
        if (current.status() != NodeStatus.RUNNING) {
            log.debug("pipeline {} node {} is {}, nothing to advance", instanceId,
                current.nodeType(), current.status());
            return AdvanceOutcome.idle(instanceId, context);
        }

        NodeTask failedTask = firstFailed(current.tasks());
        if (failedTask != null) {
            failNode(instance, current, context, failedTask, now);
            return AdvanceOutcome.terminal(instanceId, context);
        }
        if (!allTasksSucceeded(current.tasks())) {
            return AdvanceOutcome.idle(instanceId, context);
        }
        if (!repository.finishNodeIfComplete(current.id(), now)) {
            // The SQL guard found a non-success latest attempt or another advance already
            // completed the node.
            log.debug("pipeline {} node {} completion guard rejected the transition", instanceId,
                current.nodeType());
            return AdvanceOutcome.idle(instanceId, context);
        }
        eventBus.publish(new NodeCompletedEvent(instanceId, context.orderId(),
            current.nodeType(), NodeStatus.SUCCESS, null, now));

        PipelineNode next = nextNode(instance, current);
        if (next == null) {
            if (repository.finishInstance(instanceId, InstanceStatus.DONE, now)) {
                log.info("pipeline finished: instanceId={}, status=DONE", instanceId);
                eventBus.publish(new InstanceFinishedEvent(instanceId, context.orderId(),
                    InstanceStatus.DONE, "所有节点已完成", now));
                return AdvanceOutcome.terminal(instanceId, context);
            }
            log.debug("pipeline {} was already finished by another caller", instanceId);
            return AdvanceOutcome.idle(instanceId, context);
        }
        return enterNode(instance, next, context, now);
    }

    /**
     * Enters {@code node}: validates the handler (inside the transaction, so a missing handler
     * rolls back), moves the instance, flips the node to {@code RUNNING} and returns the entry
     * for invocation after the commit.
     */
    private AdvanceOutcome enterNode(PipelineInstance instance, PipelineNode node,
                                     PipelineRepository.InstanceContext context, Instant now) {
        // Fail inside the transaction: a missing handler must leave the pipeline untouched.
        handlerRegistry.require(node.nodeType());
        boolean first = instance.status() == InstanceStatus.PENDING;
        boolean moved = first
            ? repository.startInstance(instance.id(), node.nodeType(), now)
            : repository.moveToNode(instance.id(), instance.currentNode(), node.nodeType());
        if (!moved) {
            log.debug("pipeline {} was already moved away from node {}", instance.id(),
                node.nodeType());
            return AdvanceOutcome.idle(instance.id(), context);
        }
        if (!repository.startNode(node.id(), now)) {
            log.debug("pipeline {} node {} was already started", instance.id(), node.nodeType());
            return AdvanceOutcome.idle(instance.id(), context);
        }
        PipelineInstance entered = enteredInstance(instance, node, now);
        log.info("pipeline node entered: instanceId={}, node={}, seq={}", instance.id(),
            node.nodeType(), node.seq());
        return AdvanceOutcome.entered(instance.id(), context, entered, node);
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

    // ------------------------------------------------------------------ after-commit work

    /**
     * Runs the node handler (and the terminal unit-lock release) after the advance transaction
     * committed, still inside the instance-lock critical section.
     */
    private void runAfterCommit(AdvanceOutcome outcome) {
        if (outcome.enteredInstance() != null) {
            RuntimeException handlerFailure = null;
            boolean nested = Boolean.TRUE.equals(handlerRunning.get());
            handlerRunning.set(true);
            try {
                handlerRegistry.onNodeEnter(outcome.enteredInstance(), outcome.enteredNode());
            } catch (RuntimeException ex) {
                handlerFailure = ex;
            } finally {
                restoreHandlerGuard(nested);
            }
            if (handlerFailure != null) {
                failHandlerEntry(outcome, handlerFailure);
                return;
            }
        }
        if (outcome.terminal()) {
            releaseUnitLock(outcome.instanceId(), outcome.unitId());
        }
    }

    /**
     * Restores the re-entrancy flag after a handler call. A nested handler call (a stub engine
     * that reports a completion synchronously) must not clear the flag of its caller, otherwise
     * the outer handler could still recurse into {@link #advance(long)}.
     */
    private void restoreHandlerGuard(boolean nested) {
        if (nested) {
            handlerRunning.set(true);
        } else {
            handlerRunning.remove();
        }
    }

    /**
     * A handler failed after its node entry committed. The entry is not rolled back anymore, so
     * the failure is persisted explicitly: node (and pending siblings) failed, instance failed,
     * events published, unit lock released.
     */
    private void failHandlerEntry(AdvanceOutcome outcome, RuntimeException cause) {
        NodeType nodeType = outcome.enteredNode().nodeType();
        failNodeAndInstance(outcome.instanceId(), outcome.orderId(), outcome.unitId(), nodeType,
            "节点入口执行失败（node=" + nodeType + "）：" + messageOf(cause), cause);
    }

    /**
     * A completion hook failed with something other than the engine failure the handler
     * already persisted. The completion itself is committed, so the pipeline is failed
     * explicitly through the same path as {@link #failHandlerEntry} — never swallowed.
     */
    private void failHandlerCompletion(PipelineInstance instance, NodeTask completed,
                                       RuntimeException cause) {
        NodeType nodeType = completed.nodeType();
        PipelineRepository.InstanceContext context = repository.contextOf(instance.id());
        failNodeAndInstance(instance.id(), context.orderId(), context.unitId(), nodeType,
            "节点任务完成回调执行失败（node=" + nodeType + "）：" + messageOf(cause), cause);
    }

    /**
     * Fails one node (and the instance) after a handler failed, keeping already successful
     * task rows and skipping the still-pending siblings. Shared by the node-entry and the
     * completion-hook failure paths.
     */
    private void failNodeAndInstance(long instanceId, long orderId, long unitId,
                                     NodeType nodeType, String reason, RuntimeException cause) {
        log.error("pipeline node handler failed after commit: instanceId={}, node={}",
            instanceId, nodeType, cause);
        boolean failurePersisted = false;
        try {
            transactionRunner.inTransaction(() -> {
                PipelineInstance instance = repository.loadInstance(instanceId);
                if (instance == null || TERMINAL.contains(instance.status())) {
                    return null;
                }
                Instant now = Instant.now();
                PipelineNode node = instance.node(nodeType);
                boolean nodeFailed = node != null && node.status() == NodeStatus.RUNNING
                    && repository.finishNode(node.id(), NodeStatus.FAILED, now);
                if (nodeFailed) {
                    repository.skipPendingTasks(instance.id(), nodeType, now);
                    eventBus.publish(new NodeCompletedEvent(instance.id(), orderId,
                        nodeType, NodeStatus.FAILED, reason, now));
                }
                if (repository.finishInstance(instance.id(), InstanceStatus.FAILED, now)) {
                    eventBus.publish(new InstanceFinishedEvent(instance.id(), orderId,
                        InstanceStatus.FAILED, reason, now));
                }
                return null;
            });
            failurePersisted = true;
        } catch (RuntimeException persistenceFailure) {
            log.error("could not persist the handler failure of pipeline {} (node {})",
                instanceId, nodeType, persistenceFailure);
        }
        if (failurePersisted) {
            // Keep the unit lock when the failure could not be persisted: the instance may
            // still be RUNNING, and its unit must stay reserved for the reconcile path.
            releaseUnitLock(instanceId, unitId);
        }
    }

    /**
     * Runs one advance that a handler asked for while holding the re-entrancy guard. Called
     * after the outer advance released the instance lock (the lock is not re-entrant), so the
     * postponed step completes the node and enters the next one exactly once.
     */
    private void runDeferredAdvance() {
        Long deferred = deferredAdvance.get();
        if (deferred == null) {
            return;
        }
        deferredAdvance.remove();
        if (Boolean.TRUE.equals(handlerRunning.get())) {
            deferredAdvance.set(deferred);
            return;
        }
        log.debug("running deferred advance: instanceId={}", deferred);
        advance(deferred);
    }

    /**
     * Releases the 部署单元 lock after a terminal status committed. Ownership-scoped: a lost
     * lock (row already belongs to a new holder) is left alone. Called outside the transaction
     * on purpose — a DB delete inside it would roll back while the distributed unlock would not.
     */
    private void releaseUnitLock(long instanceId, long unitId) {
        if (unitId <= 0) {
            return;
        }
        try {
            if (unitLease.release(unitId, instanceId)) {
                log.info("unit lock released: unitId={}, instanceId={}", unitId, instanceId);
            } else {
                log.debug("unit lock not released (no longer owned): unitId={}, instanceId={}",
                    unitId, instanceId);
            }
        } catch (RuntimeException ex) {
            log.warn("unit lock release failed: unitId={}, instanceId={}", unitId, instanceId, ex);
        }
    }

    private static boolean pauseBeforeRetry(long instanceId) {
        try {
            Thread.sleep(ADVANCE_LOCK_RETRY_MILLIS);
            return true;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            log.debug("interrupted while waiting for the advance lock of pipeline {}", instanceId);
            return false;
        }
    }

    private static String messageOf(RuntimeException ex) {
        String message = ex.getMessage();
        return message == null || message.isBlank() ? ex.getClass().getSimpleName() : message;
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Completion fold: only the latest attempt of each logical task key counts, and
     * {@code CHECK} results never gate the node (they are evaluated by the gate engine).
     * A latest {@code SKIPPED} attempt is neutral, as before.
     */
    private static boolean allTasksSucceeded(List<NodeTask> tasks) {
        for (NodeTask task : latestAttempts(tasks).values()) {
            if (task.taskType() == TaskType.CHECK || task.status() == NodeTaskStatus.SKIPPED) {
                continue;
            }
            if (task.status() != NodeTaskStatus.SUCCESS) {
                return false;
            }
        }
        return true;
    }

    /** Failure fold with the same latest-attempt/{@code CHECK}-exclusion rules. */
    private static NodeTask firstFailed(List<NodeTask> tasks) {
        for (NodeTask task : latestAttempts(tasks).values()) {
            if (task.taskType() != TaskType.CHECK && FAILING.contains(task.status())) {
                return task;
            }
        }
        return null;
    }

    /**
     * The latest row per logical key {@code (task_type, batch_no, target_id)}; the DB unique key
     * also includes {@code attempt}, so old retry rows are retained (design §5.4).
     */
    private static Map<TaskKey, NodeTask> latestAttempts(List<NodeTask> tasks) {
        Map<TaskKey, NodeTask> latest = new HashMap<>();
        for (NodeTask task : tasks) {
            TaskKey key = new TaskKey(task.taskType(), task.batchNo(), task.targetId());
            NodeTask current = latest.get(key);
            if (current == null || task.attempt() > current.attempt()) {
                latest.put(key, task);
            }
        }
        return latest;
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

    /** Logical task identity used by the latest-attempt fold. */
    private record TaskKey(TaskType taskType, int batchNo, long targetId) {
    }

    /**
     * What {@link #advanceInTransaction(long)} produced and what must still happen after the
     * transaction commits: entering a node (handler call) and/or releasing the terminal unit
     * lock.
     */
    private record AdvanceOutcome(long instanceId, long orderId, long unitId, boolean terminal,
                                  PipelineInstance enteredInstance, PipelineNode enteredNode) {

        static AdvanceOutcome idle(long instanceId,
                                   PipelineRepository.InstanceContext context) {
            return new AdvanceOutcome(instanceId, context.orderId(), context.unitId(), false,
                null, null);
        }

        static AdvanceOutcome terminal(long instanceId,
                                       PipelineRepository.InstanceContext context) {
            return new AdvanceOutcome(instanceId, context.orderId(), context.unitId(), true,
                null, null);
        }

        static AdvanceOutcome entered(long instanceId,
                                      PipelineRepository.InstanceContext context,
                                      PipelineInstance enteredInstance, PipelineNode enteredNode) {
            return new AdvanceOutcome(instanceId, context.orderId(), context.unitId(), false,
                enteredInstance, enteredNode);
        }
    }
}