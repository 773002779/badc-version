package com.sf.release.domain.pipeline.handler;

import com.sf.release.common.BizError;
import com.sf.release.common.BizException;
import com.sf.release.domain.engine.CicdEngine;
import com.sf.release.domain.engine.DeployCmd;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Executes the daily release's {@code DAILY_DEPLOY} node (P5-A design §4).
 *
 * <p><strong>One trigger per target, all at once.</strong> The node was pre-generated with one
 * {@code DEPLOY} task per target DE (the unit's {@code DAILY} DEs, or its {@code TEST} DEs when it
 * has none). {@link #start(PipelineInstance)} triggers <em>every</em> still-{@code PENDING} task in
 * one loop — a daily release has no C14 batching — each calling
 * {@link CicdEngine#triggerDeploy(DeployCmd)} and moving to {@code WAITING_EXTERNAL} with a
 * 60-minute deadline. A re-entry (a recovery advance) re-reads the aggregate and triggers only the
 * latest attempt of a task that is still {@code PENDING}, so an already running deploy is never
 * triggered twice.
 *
 * <p><strong>No chain.</strong> One successful target does not trigger anything else: the siblings
 * were already triggered, and the orchestrator completes the node when every counted task of the
 * node is terminal-successful (its normal fold). That is why this handler has no
 * {@code onTaskCompleted} override: it has no next step to start.
 *
 * <p><strong>Deploy plan and image.</strong> The plan is the unit's enabled {@code DAILY_DEPLOY}
 * binding, falling back to its {@code TEST_DEPLOY} binding (C24); the DE coordinates come from the
 * pre-generated task's {@code target_id}. The image is the one the daily {@code BUILD} just built
 * ({@code image_digest}). A missing binding, a missing digest or a DE no longer configured for the
 * unit is a configuration/state failure: the affected tasks are marked {@code FAILED} with the
 * reason in {@code result_summary} and the handler aborts <em>before</em> sending a deploy with a
 * null plan/image or to an unknown environment. The daily pipeline never validates that the image
 * equals a test/production image: it has its own image by design (J4, design §8).
 *
 * <p><strong>Failures.</strong> An {@link EngineException} marks only that target {@code FAILED}
 * through {@link NodeTaskCompletionService} (so the usual completion event is published) with
 * {@code ENGINE_UNAVAILABLE}/{@code ENGINE_TIMEOUT} plus the adapter message, then aborts the
 * fan-out and rethrows. The orchestrator's normal failure path then fails the node and the
 * instance (no retry, no silence); the other targets that were already triggered keep running and
 * their rows are untouched — a daily release never rolls back a deploy it already sent, and a
 * daily failure never touches the item, its release order or its unit lock.
 *
 * <p><strong>Persistence boundaries.</strong> No pipeline row lock is held while the engine call
 * runs: the command is resolved first, the HTTP call happens outside a transaction, and the status
 * write uses one short {@link TransactionRunner} transaction.
 */
public final class DailyDeployNodeHandler implements NodeHandler {

    private static final Logger log = LoggerFactory.getLogger(DailyDeployNodeHandler.class);

    /** Daily deploy timeout (P5-A design §4): {@code deadline_at = start + 60min}. */
    static final Duration DEPLOY_DEADLINE = Duration.ofMinutes(60);

    private final CicdEngine engine;
    private final PipelineRepository repository;
    private final TransactionRunner transactionRunner;
    private final NodeTaskCompletionService completionService;

    public DailyDeployNodeHandler(CicdEngine engine, PipelineRepository repository,
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
        return NodeType.DAILY_DEPLOY;
    }

    /**
     * Triggers every target whose latest attempt is still {@code PENDING}.
     *
     * <p>The aggregate is re-read first: a re-entry may be handed a stale instance, and only the
     * committed task status can prove that a target has no engine job yet.
     */
    @Override
    public void start(PipelineInstance instance) {
        PipelineInstance current = repository.loadInstance(instance.id());
        if (current == null) {
            return;
        }
        PipelineNode node = current.node(NodeType.DAILY_DEPLOY);
        if (node == null) {
            return;
        }
        List<NodeTask> startable = startableDeploys(node);
        if (startable.isEmpty()) {
            return;
        }
        PipelineRepository.DeployContext context = repository.dailyDeployContextOf(current.id());
        if (context.planId() == null || context.planId().isBlank()) {
            String reason = "UNIT_NOT_CONFIGURED: no enabled DAILY_DEPLOY or TEST_DEPLOY binding"
                + " for unit " + context.unitId();
            failDeploys(startable, reason);
            throw new BizException(BizError.UNIT_NOT_CONFIGURED, reason);
        }
        if (current.imageDigest() == null || current.imageDigest().isBlank()) {
            String reason = "STATE_NOT_ALLOWED: the daily pipeline instance has no built image"
                + " digest, refusing to deploy";
            failDeploys(startable, reason);
            throw new BizException(BizError.STATE_NOT_ALLOWED, reason);
        }
        for (NodeTask task : startable) {
            PipelineRepository.DeployTarget target = context.targetOf(task.targetId());
            if (target == null) {
                String reason = "DE_NOT_CONFIGURED: env_de " + task.targetId()
                    + " is not configured for the unit";
                failTask(task, reason);
                // No silent stuck node: the orchestrator fails the node/instance instead of
                // leaving the remaining deploys WAITING_EXTERNAL forever.
                throw new IllegalStateException(reason);
            }
            trigger(current, context, target, task);
        }
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
            log.warn("daily deploy task was not moved to WAITING_EXTERNAL: instanceId={},"
                    + " taskId={}, externalId={}", instance.id(), task.id(), result.externalId());
        }
    }

    /**
     * The latest attempt per logical deploy key {@code (task_type, batch_no, target_id)} that is
     * still {@code PENDING}. A manual retry inserts a new {@code attempt} row and keeps the old
     * failed one (design §5.4), so the first row of a DE may be superseded.
     */
    private static List<NodeTask> startableDeploys(PipelineNode node) {
        Map<DeployKey, NodeTask> latest = new LinkedHashMap<>();
        for (NodeTask task : node.tasks()) {
            if (task.taskType() != TaskType.DEPLOY) {
                continue;
            }
            DeployKey key = new DeployKey(task.taskType(), task.batchNo(), task.targetId());
            NodeTask candidate = latest.get(key);
            if (candidate == null || task.attempt() > candidate.attempt()) {
                latest.put(key, task);
            }
        }
        List<NodeTask> startable = new ArrayList<>(latest.size());
        for (NodeTask task : latest.values()) {
            if (task.status() == NodeTaskStatus.PENDING) {
                startable.add(task);
            }
        }
        return startable;
    }

    /**
     * Marks the affected deploy tasks {@code FAILED} through the completion service in one short
     * transaction, so a configuration failure publishes the usual completion events instead of
     * silently dropping the tasks.
     */
    private void failDeploys(List<NodeTask> tasks, String summary) {
        transactionRunner.inTransaction(() -> {
            for (NodeTask task : tasks) {
                completionService.apply(task.id(), NodeTaskStatus.FAILED, null, summary);
            }
            return null;
        });
    }

    /**
     * Marks one task failed through the completion service, so the usual
     * {@code NodeTaskCompletedEvent} is published (the design forbids silent engine failures).
     * The exception is rethrown by the caller; this handler never fails the node or the instance
     * itself, the orchestrator owns those transitions.
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

    /** Logical deploy identity used by the latest-attempt fold. */
    private record DeployKey(TaskType taskType, int batchNo, long targetId) {
    }
}