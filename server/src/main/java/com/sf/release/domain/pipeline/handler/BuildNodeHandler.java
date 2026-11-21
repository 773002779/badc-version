package com.sf.release.domain.pipeline.handler;

import com.sf.release.domain.engine.BuildCmd;
import com.sf.release.domain.engine.CicdEngine;
import com.sf.release.domain.engine.EngineException;
import com.sf.release.domain.engine.TriggerResult;
import com.sf.release.domain.pipeline.NodeHandler;
import com.sf.release.domain.pipeline.NodeTask;
import com.sf.release.domain.pipeline.NodeTaskCompletionService;
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
 * first startable step of the chain (the latest attempt per task type, never a superseded
 * failed row), or nothing when a step is already {@code WAITING_EXTERNAL}/{@code RUNNING} (a
 * re-entry after a recoverable failure must not trigger a second engine job). Each step calls
 * {@link CicdEngine#triggerBuild(BuildCmd)}, stores {@code external_id}/{@code external_url},
 * sets the task {@code WAITING_EXTERNAL} with a 60-minute deadline and returns immediately.
 * The next step starts from {@link #onTaskCompleted(PipelineInstance, NodeTask)} when the
 * previous step reports {@code SUCCESS}, so a long-running engine job never blocks a thread.
 *
 * <p><strong>Commit provenance.</strong> The command carries the item's read-only
 * {@code source_branch} and the commit the pipeline already knows (initially {@code null}: the
 * engine builds the branch head). It never carries the item's pre-build {@code base_commit}.
 * When a trigger response reports a commit, that commit is persisted on the instance and used
 * by the remaining steps, so all three steps build the same SHA. A successful
 * {@code IMG_BUILD} persists {@code image_digest} (already written by the completion service)
 * and the reported {@code commit_sha}; when no commit was ever reported it stays {@code NULL}
 * and a WARN names the instance — no value is fabricated.
 *
 * <p><strong>Persistence boundaries.</strong> No pipeline row lock is held while the engine
 * call runs: the command is resolved first, the HTTP call happens outside a transaction, and
 * the status/commit write then uses a short {@link TransactionRunner} transaction. The
 * orchestrator's completion transaction is already committed when the hook runs, so the
 * handler reads the already-persisted completion state.
 *
 * <p><strong>Failures.</strong> An {@link EngineException} marks the current task
 * {@code FAILED} through {@link NodeTaskCompletionService} (so the normal completion event is
 * published) with {@code ENGINE_UNAVAILABLE}/{@code ENGINE_TIMEOUT} and the engine message in
 * {@code result_summary}, then propagates: the orchestrator's failure path fails the node and
 * the instance (this handler never duplicates that). A {@code FAILED}/{@code TIMEOUT}
 * completion is not retried and never starts the next step.
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
    private final NodeTaskCompletionService completionService;

    public BuildNodeHandler(CicdEngine engine, PipelineRepository repository,
                            TransactionRunner transactionRunner,
                            NodeTaskCompletionService completionService) {
        this.engine = Objects.requireNonNull(engine, "engine must not be null");
        this.repository = Objects.requireNonNull(repository, "repository must not be null");
        this.transactionRunner = Objects.requireNonNull(transactionRunner,
            "transactionRunner must not be null");
        this.completionService = Objects.requireNonNull(completionService,
            "completionService must not be null");
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
     * the external identifiers, the {@code WAITING_EXTERNAL} status and — when the engine
     * reported one — the commit in one short transaction.
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
        boolean stored = transactionRunner.inTransaction(() -> {
            boolean marked = repository.markTaskWaitingExternal(task.id(), result.externalId(),
                result.externalUrl(), now.plus(BUILD_DEADLINE), now);
            if (result.commitSha() != null) {
                // Later steps build the same commit, not the branch head again.
                repository.recordCommit(instance.id(), result.commitSha());
            }
            return marked;
        });
        if (!stored) {
            log.warn("task was not moved to WAITING_EXTERNAL: instanceId={}, taskId={},"
                + " externalId={}", instance.id(), task.id(), result.externalId());
        }
    }

    /**
     * Persists {@code image_digest} (read back from the committed completion) and the
     * engine-reported built {@code commit_sha} for the production same-image check. A missing
     * commit is stored as {@code NULL} with a WARN; it is never replaced by the item's
     * pre-build baseline.
     */
    private void persistBuiltImage(PipelineInstance instance) {
        String commitSha = instance.commitSha();
        if (commitSha == null) {
            log.warn("engine reported no commit for the built image;"
                + " pipeline_instance.commit_sha stays NULL: instanceId={}, pipelineNo={}",
                instance.id(), instance.pipelineNo());
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

    /**
     * The latest attempt of {@code type}. A manual retry inserts a new {@code attempt} row and
     * keeps the old failed row (design §5.4), so the first row of a type may be superseded; the
     * fold matches the orchestrator's latest-attempt rule.
     */
    private static NodeTask taskOf(PipelineNode node, TaskType type) {
        NodeTask latest = null;
        for (NodeTask task : node.tasks()) {
            if (task.taskType() == type
                    && (latest == null || task.attempt() > latest.attempt())) {
                latest = task;
            }
        }
        return latest;
    }

    private static TaskType successor(TaskType type) {
        int index = CHAIN.indexOf(type);
        return index < 0 || index + 1 >= CHAIN.size() ? null : CHAIN.get(index + 1);
    }

    /**
     * Marks the current task failed through the completion service, so the usual
     * {@code NodeTaskCompletedEvent} is published (the design forbids silent engine failures).
     * The exception is rethrown by the caller so the orchestrator runs its failure path in a
     * new short transaction; this handler does not fail the node or the instance itself.
     */
    private void failTask(NodeTask task, String summary) {
        transactionRunner.inTransaction(() -> {
            completionService.apply(task.id(), NodeTaskStatus.FAILED, null, summary);
            return null;
        });
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