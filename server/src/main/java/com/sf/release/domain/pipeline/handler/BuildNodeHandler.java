package com.sf.release.domain.pipeline.handler;

import com.sf.release.domain.engine.BuildCmd;
import com.sf.release.domain.engine.CicdEngine;
import com.sf.release.domain.engine.EngineException;
import com.sf.release.domain.engine.TriggerResult;
import com.sf.release.domain.pipeline.NodeHandler;
import com.sf.release.domain.pipeline.NodeTask;
import com.sf.release.domain.pipeline.NodeTaskStatus;
import com.sf.release.domain.pipeline.NodeType;
import com.sf.release.domain.pipeline.PipelineInstance;
import com.sf.release.domain.pipeline.PipelineNode;
import com.sf.release.domain.pipeline.TaskType;
import com.sf.release.domain.pipeline.port.PipelineRepository;
import com.sf.release.domain.pipeline.port.TransactionRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Executes the {@code BUILD} node as a strictly sequential chain
 * {@code CODE_CHECK → PKG_BUILD → IMG_BUILD} (tech design §5.1/§6.3).
 *
 * <p><strong>One trigger per entry.</strong> {@link #start(PipelineInstance)} starts only the
 * first pending step of the chain, or nothing when a step is already
 * {@code WAITING_EXTERNAL}/{@code RUNNING} (a re-entry after a recoverable failure must not
 * trigger a second engine job). Each step calls {@link CicdEngine#triggerBuild(BuildCmd)},
 * stores {@code external_id}/{@code external_url}, sets the task {@code WAITING_EXTERNAL} with
 * a 60-minute deadline and returns immediately. The next step starts from
 * {@link #onTaskCompleted(PipelineInstance, NodeTask)} when the previous step reports
 * {@code SUCCESS}, so a long-running engine job never blocks a thread here.
 *
 * <p><strong>Persistence boundaries.</strong> No pipeline row lock is held while the engine
 * call runs: the command is resolved first, the HTTP call happens outside a transaction, and
 * the status write then uses a short {@link TransactionRunner} transaction. The orchestrator's
 * completion transaction is already committed when the hook runs, so the handler reads the
 * already-persisted completion state.
 *
 * <p><strong>Failures.</strong> An {@link EngineException} marks the current task
 * {@code FAILED} with {@code ENGINE_UNAVAILABLE}/{@code ENGINE_TIMEOUT} and the engine message
 * in {@code result_summary}, then propagates: the orchestrator's failure path fails the node
 * and the instance (this handler never duplicates that). A {@code FAILED}/{@code TIMEOUT}
 * completion is not retried and never starts the next step.
 *
 * <p><strong>Artifacts.</strong> A successful {@code IMG_BUILD} persists
 * {@code pipeline_instance.image_digest} (already written by the completion service) and
 * {@code commit_sha} in one short transaction; the digest is the input of the production
 * same-image check (J27).
 */
public final class BuildNodeHandler implements NodeHandler {

    private static final Logger log = LoggerFactory.getLogger(BuildNodeHandler.class);

    /** Build timeout from tech design §5.4/§6.3: {@code deadline_at = start + 60min}. */
    static final Duration BUILD_DEADLINE = Duration.ofMinutes(60);

    /** The fixed order of the three build tasks. */
    private static final List<TaskType> CHAIN = List.of(
        TaskType.CODE_CHECK, TaskType.PKG_BUILD, TaskType.IMG_BUILD);

    private final CicdEngine engine;
    private final PipelineRepository repository;
    private final TransactionRunner transactionRunner;

    public BuildNodeHandler(CicdEngine engine, PipelineRepository repository,
                            TransactionRunner transactionRunner) {
        this.engine = Objects.requireNonNull(engine, "engine must not be null");
        this.repository = Objects.requireNonNull(repository, "repository must not be null");
        this.transactionRunner = Objects.requireNonNull(transactionRunner,
            "transactionRunner must not be null");
    }

    @Override
    public NodeType nodeType() {
        return NodeType.BUILD;
    }

    @Override
    public void start(PipelineInstance instance) {
        PipelineNode node = instance.node(NodeType.BUILD);
        if (node == null) {
            return;
        }
        NodeTask next = firstStartable(node);
        if (next != null) {
            trigger(instance, next);
        }
    }

    /**
     * Starts the successor of a successful step, or records the built image after a successful
     * {@code IMG_BUILD}. A failure is left to the orchestrator's failure path: it must stop the
     * chain, not advance it.
     */
    @Override
    public void onTaskCompleted(PipelineInstance instance, NodeTask completed) {
        if (completed.nodeType() != NodeType.BUILD
                || completed.status() != NodeTaskStatus.SUCCESS) {
            return;
        }
        if (completed.taskType() == TaskType.IMG_BUILD) {
            persistBuiltImage(instance);
            return;
        }
        TaskType nextType = successor(completed.taskType());
        if (nextType == null) {
            return;
        }
        PipelineNode node = instance.node(NodeType.BUILD);
        if (node == null) {
            return;
        }
        NodeTask next = taskOf(node, nextType);
        if (next != null && next.status() == NodeTaskStatus.PENDING) {
            trigger(instance, next);
        }
    }

    /**
     * Resolves the command, calls the engine <em>outside</em> any transaction, then persists
     * the external identifiers and the {@code WAITING_EXTERNAL} status in one short
     * transaction.
     */
    private void trigger(PipelineInstance instance, NodeTask task) {
        PipelineRepository.BuildContext source = repository.buildContextOf(instance.id());
        BuildCmd cmd = new BuildCmd(source.unitId(), source.planId(), source.branch(),
            source.commitSha(), instance.pipelineNo(), task.attempt());
        TriggerResult result;
        try {
            result = engine.triggerBuild(cmd);
        } catch (EngineException ex) {
            failTask(task, engineFailure(ex));
            throw ex;
        }
        Instant now = Instant.now();
        boolean stored = transactionRunner.inTransaction(() -> repository.markTaskWaitingExternal(
            task.id(), result.externalId(), result.externalUrl(), now.plus(BUILD_DEADLINE), now));
        if (!stored) {
            log.warn("task was not moved to WAITING_EXTERNAL: instanceId={}, taskId={},"
                + " externalId={}", instance.id(), task.id(), result.externalId());
        }
    }

    /**
     * Persists {@code image_digest} (read back from the committed completion) and the built
     * {@code commit_sha} for the production same-image check.
     */
    private void persistBuiltImage(PipelineInstance instance) {
        PipelineRepository.BuildContext source = repository.buildContextOf(instance.id());
        String commitSha = source.commitSha() == null ? instance.commitSha() : source.commitSha();
        if (instance.imageDigest() == null && commitSha == null) {
            return;
        }
        boolean stored = transactionRunner.inTransaction(() -> repository.recordBuiltImage(
            instance.id(), instance.imageDigest(), commitSha));
        if (!stored) {
            log.warn("built image was not recorded on the instance: instanceId={}, digest={}",
                instance.id(), instance.imageDigest());
        }
    }

    /**
     * The first step that may be started: the chain is sequential, so a later {@code PENDING}
     * task is never started while an earlier one is still running or waiting.
     */
    private static NodeTask firstStartable(PipelineNode node) {
        for (TaskType type : CHAIN) {
            NodeTask task = taskOf(node, type);
            if (task == null || task.status() == NodeTaskStatus.SUCCESS
                    || task.status() == NodeTaskStatus.SKIPPED) {
                continue;
            }
            return task.status() == NodeTaskStatus.PENDING ? task : null;
        }
        return null;
    }

    private static NodeTask taskOf(PipelineNode node, TaskType type) {
        for (NodeTask task : node.tasks()) {
            if (task.taskType() == type) {
                return task;
            }
        }
        return null;
    }

    private static TaskType successor(TaskType type) {
        int index = CHAIN.indexOf(type);
        return index < 0 || index + 1 >= CHAIN.size() ? null : CHAIN.get(index + 1);
    }

    /**
     * Marks the current task failed with the engine message. The exception is rethrown by the
     * caller so the orchestrator runs its failure path in a new short transaction; this handler
     * does not fail the node or the instance itself.
     */
    private void failTask(NodeTask task, String summary) {
        Instant now = Instant.now();
        transactionRunner.inTransaction(() -> repository.completeTask(
            task.id(), NodeTaskStatus.FAILED, null, summary, now));
    }

    /** {@code ENGINE_UNAVAILABLE}/{@code ENGINE_TIMEOUT} plus the adapter message. */
    private static String engineFailure(EngineException ex) {
        String message = ex.getMessage();
        if (message == null || message.isBlank()) {
            message = ex.getError().getMessage();
        }
        return ex.getError().name() + ": " + message;
    }
}