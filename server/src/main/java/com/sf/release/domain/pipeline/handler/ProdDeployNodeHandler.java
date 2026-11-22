package com.sf.release.domain.pipeline.handler;

import com.sf.release.common.BizError;
import com.sf.release.common.BizException;
import com.sf.release.domain.engine.CicdEngine;
import com.sf.release.domain.engine.DeployCmd;
import com.sf.release.domain.engine.EngineException;
import com.sf.release.domain.engine.TriggerResult;
import com.sf.release.domain.pipeline.InstanceStatus;
import com.sf.release.domain.pipeline.NodeHandler;
import com.sf.release.domain.pipeline.NodeTask;
import com.sf.release.domain.pipeline.NodeTaskCompletionService;
import com.sf.release.domain.pipeline.NodeTaskStatus;
import com.sf.release.domain.pipeline.NodeType;
import com.sf.release.domain.pipeline.PipelineInstance;
import com.sf.release.domain.pipeline.PipelineNode;
import com.sf.release.domain.pipeline.TaskType;
import com.sf.release.domain.pipeline.port.PipelineRepository;
import com.sf.release.domain.pipeline.port.ProductionBoundaryProbe;
import com.sf.release.domain.pipeline.port.TransactionRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Executes the {@code PROD_DEPLOY} node: the pre-generated production batches, <strong>one batch at
 * a time</strong> (tech design 4.7, requirements C14/J17).
 *
 * <p><strong>Serial batches, parallel DEs.</strong> {@code PipelineFactory} pre-generates one
 * {@code DEPLOY} task per production-family DE, carrying the DE's {@code deploy_batch_no}. Entering
 * the node triggers <em>only the lowest batch that is not fully successful</em>; every
 * {@code PENDING} task of that batch is triggered in one tight loop, each with its own
 * {@code external_id} and an 8-minute deadline. When the last task of the batch succeeds,
 * {@link #onTaskCompleted(PipelineInstance, NodeTask)} re-enters {@link #start(PipelineInstance)},
 * which re-checks the boundary and triggers the next batch. A failed task never triggers a later
 * batch: the orchestrator fails the node and the instance, and this handler simply has no success
 * path left.
 *
 * <p><strong>The batch boundary is re-checked, not remembered.</strong>
 * {@link ProductionBoundaryProbe#blockedReason(long)} answers "freeze in force / final gate block /
 * release not executing yet" at every boundary (design 4.7), and a block pauses the instance with
 * that sentence as {@code status_reason} instead of triggering anything. The pause does not fail the
 * node: an operator resumes through {@code POST /releases/{publishNo}/execute} once the block is
 * gone, and the handler picks the next batch up on re-entry.
 *
 * <p><strong>Idempotent re-entry by claim.</strong> {@link #start(PipelineInstance)} re-reads the
 * committed aggregate and triggers only the batch's still-{@code PENDING} tasks; each one is claimed
 * ({@code PENDING → RUNNING}, {@link PipelineRepository#claimTaskStart(long)}) <em>before</em> the
 * engine call. A duplicate entry (a replay, a recovery, a resume after the batch-boundary pause, or a
 * second completion callback racing the first) therefore never triggers a second deploy for the same
 * DE, and a partially started batch completes the rest of its DEs instead of staying stuck. The
 * production deploy plan and the DE coordinates come from
 * {@link PipelineRepository#prodDeployContextOf(long)}; the image is the one the instance already
 * built ({@code image_digest}) — production never rebuilds.
 *
 * <p><strong>Failures.</strong> An {@link EngineException} on one DE marks that task {@code FAILED}
 * through the completion service and aborts the fan-out (the same rule as the test-deploy handler;
 * the orchestrator then fails the node). A missing binding, a missing built image or an unknown DE is
 * a configuration/state failure: the batch's startable tasks are failed with a reason and the
 * handler aborts before any engine call, so no deploy is ever sent with a null plan or image. There
 * is deliberately no {@link #failureReason}: a failed counted task is the orchestrator's failure
 * signal (P1 behaviour).
 */
public final class ProdDeployNodeHandler implements NodeHandler {

    private static final Logger log = LoggerFactory.getLogger(ProdDeployNodeHandler.class);

    /** Production deploy timeout (tech design §5.4): {@code deadline_at = start + 8min}. */
    private static final Duration DEPLOY_DEADLINE = Duration.ofMinutes(8);

    private final CicdEngine engine;
    private final PipelineRepository repository;
    private final TransactionRunner transactionRunner;
    private final NodeTaskCompletionService completionService;
    private final ProductionBoundaryProbe boundary;

    public ProdDeployNodeHandler(CicdEngine engine, PipelineRepository repository,
                                 TransactionRunner transactionRunner,
                                 NodeTaskCompletionService completionService,
                                 ProductionBoundaryProbe boundary) {
        this.engine = Objects.requireNonNull(engine, "engine must not be null");
        this.repository = Objects.requireNonNull(repository, "repository must not be null");
        this.transactionRunner = Objects.requireNonNull(transactionRunner,
            "transactionRunner must not be null");
        this.completionService = Objects.requireNonNull(completionService,
            "completionService must not be null");
        this.boundary = Objects.requireNonNull(boundary, "boundary must not be null");
    }

    @Override
    public NodeType nodeType() {
        return NodeType.PROD_DEPLOY;
    }

    /**
     * Triggers the lowest batch that still has work, after the boundary check. Only the batch's
     * {@code PENDING} tasks are started (a task already triggered, waiting or terminal is skipped by
     * its claim); a batch with a failed task is left to the orchestrator's failure path; a paused or
     * terminal instance is not driven at all.
     */
    @Override
    public void start(PipelineInstance instance) {
        PipelineInstance current = repository.loadInstance(instance.id());
        if (current == null) {
            return;
        }
        if (current.status() != InstanceStatus.RUNNING) {
            // A pause (its own boundary check or an operator stop) or a terminal state must not be
            // undone by a re-entry; the resume path re-enters after flipping the instance back.
            log.debug("production deploy not started: instanceId={} is {}", current.id(),
                current.status());
            return;
        }
        PipelineNode node = current.node(NodeType.PROD_DEPLOY);
        if (node == null) {
            return;
        }
        Batch batch = nextBatch(node);
        if (batch == null) {
            return;
        }
        if (batch.failed()) {
            log.debug("production batch has a failed task, not triggering more: instanceId={},"
                + " batchNo={}", current.id(), batch.batchNo());
            return;
        }
        String blocked = boundary.blockedReason(current.id());
        if (blocked != null) {
            if (repository.pauseInstance(current.id(), NodeType.PROD_DEPLOY, blocked,
                    Instant.now())) {
                log.warn("production deploy paused at a batch boundary: instanceId={},"
                    + " batchNo={}, reason={}", current.id(), batch.batchNo(), blocked);
            }
            return;
        }
        PipelineRepository.DeployContext context = repository.prodDeployContextOf(current.id());
        List<NodeTask> pending = batch.pending();
        if (pending.isEmpty()) {
            return;
        }
        if (context.planId() == null || context.planId().isBlank()) {
            String reason = "UNIT_NOT_CONFIGURED: no enabled PROD_DEPLOY binding for unit "
                + context.unitId();
            failTasks(pending, reason);
            throw new BizException(BizError.UNIT_NOT_CONFIGURED, reason);
        }
        if (current.imageDigest() == null || current.imageDigest().isBlank()) {
            String reason = "STATE_NOT_ALLOWED: the pipeline instance has no built image digest,"
                + " refusing to deploy to production";
            failTasks(pending, reason);
            throw new BizException(BizError.STATE_NOT_ALLOWED, reason);
        }
        for (NodeTask task : pending) {
            // Claim before calling the engine: the completion callbacks run outside the instance
            // lock, so two "the batch succeeded" decisions could otherwise trigger the same task
            // twice. Only the caller whose compare-and-set matched calls the engine; a task that is
            // already RUNNING/WAITING_EXTERNAL is not PENDING any more and is skipped here.
            if (!repository.claimTaskStart(task.id())) {
                log.debug("production task was claimed by another caller: instanceId={},"
                    + " taskId={}", current.id(), task.id());
                continue;
            }
            PipelineRepository.DeployTarget target = context.targetOf(task.targetId());
            if (target == null) {
                String reason = "DE_NOT_CONFIGURED: env_de " + task.targetId()
                    + " is not configured for the unit";
                failTask(task, reason);
                // No silent stuck batch: the orchestrator fails the node/instance instead of
                // leaving the remaining deploys PENDING forever.
                throw new IllegalStateException(reason);
            }
            trigger(current, context, target, task);
        }
    }

    /**
     * When the completed task's batch now has every latest-attempt task {@code SUCCESS}/{@code
     * SKIPPED}, re-enter {@link #start(PipelineInstance)} so the next batch (and its boundary check)
     * runs. A failure does nothing: the orchestrator fails the node and the instance.
     */
    @Override
    public void onTaskCompleted(PipelineInstance instance, NodeTask completed) {
        if (completed.nodeType() != NodeType.PROD_DEPLOY
                || completed.taskType() != TaskType.DEPLOY) {
            return;
        }
        if (completed.status() != NodeTaskStatus.SUCCESS
                && completed.status() != NodeTaskStatus.SKIPPED) {
            return;
        }
        PipelineInstance current = repository.loadInstance(instance.id());
        if (current == null) {
            return;
        }
        PipelineNode node = current.node(NodeType.PROD_DEPLOY);
        if (node == null) {
            return;
        }
        Batch batch = batchOf(node, completed.batchNo());
        if (batch == null || !batch.succeeded()) {
            return;
        }
        log.info("production batch succeeded: instanceId={}, batchNo={}", current.id(),
            completed.batchNo());
        start(current);
    }

    /**
     * Resolves the command, calls the engine <em>outside</em> any transaction, then persists the
     * external identifiers, the {@code WAITING_EXTERNAL} status and its deadline in one short
     * transaction.
     */
    private void trigger(PipelineInstance instance, PipelineRepository.DeployContext context,
                         PipelineRepository.DeployTarget target, NodeTask task) {
        DeployCmd cmd = new DeployCmd(context.unitId(), context.planId(), target.envKind(),
            target.deName(), instance.imageDigest(), instance.pipelineNo(), task.attempt());
        TriggerResult result;
        try {
            result = engine.triggerDeploy(cmd);
        } catch (EngineException ex) {
            failTask(task, engineFailure(ex));
            throw ex;
        }
        Instant now = Instant.now();
        boolean stored = transactionRunner.inTransaction(() -> repository.markTaskWaitingExternal(
            task.id(), result.externalId(), result.externalUrl(), now.plus(DEPLOY_DEADLINE), now));
        if (!stored) {
            log.warn("production task was not moved to WAITING_EXTERNAL: instanceId={}, taskId={},"
                + " externalId={}", instance.id(), task.id(), result.externalId());
        }
    }

    /**
     * The deploy tasks of {@code node} grouped by batch, each batch in trigger order: the latest
     * attempt per logical DE key {@code (batch_no, target_id)} (a manual retry inserts a new attempt
     * row and keeps the old failed one, design §5.4), batches ascending by number.
     */
    private static Map<Integer, Batch> batchesOf(PipelineNode node) {
        Map<DeployKey, NodeTask> latest = new LinkedHashMap<>();
        for (NodeTask task : node.tasks()) {
            if (task.taskType() != TaskType.DEPLOY) {
                continue;
            }
            DeployKey key = new DeployKey(task.batchNo(), task.targetId());
            NodeTask candidate = latest.get(key);
            if (candidate == null || task.attempt() > candidate.attempt()) {
                latest.put(key, task);
            }
        }
        Map<Integer, List<NodeTask>> grouped = new TreeMap<>();
        for (NodeTask task : latest.values()) {
            grouped.computeIfAbsent(task.batchNo(), ignored -> new ArrayList<>()).add(task);
        }
        Map<Integer, Batch> batches = new TreeMap<>();
        grouped.forEach((batchNo, tasks) -> batches.put(batchNo, new Batch(batchNo, tasks)));
        return batches;
    }

    /** The lowest batch with any task not {@code SUCCESS}/{@code SKIPPED}, or {@code null}. */
    private static Batch nextBatch(PipelineNode node) {
        for (Batch batch : batchesOf(node).values()) {
            if (!batch.succeeded()) {
                return batch;
            }
        }
        return null;
    }

    private static Batch batchOf(PipelineNode node, int batchNo) {
        return batchesOf(node).get(batchNo);
    }

    /**
     * Marks the affected deploy tasks {@code FAILED} through the completion service in one short
     * transaction, so a configuration failure publishes the usual completion events instead of
     * silently dropping the tasks.
     */
    private void failTasks(List<NodeTask> tasks, String summary) {
        transactionRunner.inTransaction(() -> {
            for (NodeTask task : tasks) {
                completionService.apply(task.id(), NodeTaskStatus.FAILED, null, summary);
            }
            return null;
        });
    }

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

    /** Logical deploy identity used by the latest-attempt fold. */
    private record DeployKey(int batchNo, long targetId) {
    }

    /** One production batch and its latest-attempt deploy tasks. */
    private record Batch(int batchNo, List<NodeTask> tasks) {

        Batch {
            tasks = List.copyOf(tasks);
        }

        /** A task of this batch failed/timed out; the orchestrator fails the node from here. */
        boolean failed() {
            return tasks.stream().anyMatch(task ->
                task.status() == NodeTaskStatus.FAILED
                    || task.status() == NodeTaskStatus.TIMEOUT);
        }

        /** Every task is {@code SUCCESS} or neutral {@code SKIPPED}. */
        boolean succeeded() {
            return !tasks.isEmpty() && tasks.stream().allMatch(task ->
                task.status() == NodeTaskStatus.SUCCESS
                    || task.status() == NodeTaskStatus.SKIPPED);
        }

        /** The tasks of the batch that still need an engine trigger. */
        List<NodeTask> pending() {
            return tasks.stream()
                .filter(task -> task.status() == NodeTaskStatus.PENDING)
                .toList();
        }
    }
}