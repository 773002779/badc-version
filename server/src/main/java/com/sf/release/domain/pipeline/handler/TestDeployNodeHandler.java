package com.sf.release.domain.pipeline.handler;

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
import com.sf.release.domain.pipeline.event.TestEnvReadyEvent;
import com.sf.release.domain.pipeline.port.PipelineEventBus;
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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Executes the {@code TEST_DEPLOY} node: one independent deploy per configured TEST DE
 * (tech design §5.1/§5.2).
 *
 * <p><strong>Fan-out, no chain.</strong> The pipeline pre-generates one {@code DEPLOY} task per
 * TEST DE. Unlike the BUILD chain, these tasks have no order: entering the node triggers
 * <em>every</em> startable task in one tight loop, each calling
 * {@link CicdEngine#triggerDeploy(DeployCmd)}, storing its own {@code external_id} and moving to
 * {@code WAITING_EXTERNAL} with an 8-minute deadline. The loop never waits for a DE status
 * (deploys are independent); it only serializes the trigger calls. A re-entry — for example the
 * recovery path of Task 14 — triggers only the latest attempt of a task still {@code PENDING},
 * so an already running deploy is never triggered twice.
 *
 * <p><strong>Image.</strong> The command carries the image the instance already built
 * ({@code image_digest}/{@code image_tag}); the TEST_DEPLOY node never rebuilds. The deploy plan
 * is the unit's enabled {@code TEST_DEPLOY} binding and the DE coordinates come from
 * {@code env_de} rows with {@code env_kind = TEST}.
 *
 * <p><strong>Failures.</strong> An {@link EngineException} on one DE marks only that task
 * {@code FAILED} through {@link NodeTaskCompletionService} (so the normal completion event is
 * published) with {@code ENGINE_UNAVAILABLE}/{@code ENGINE_TIMEOUT} and the engine message in
 * {@code result_summary}, then aborts the fan-out and rethrows; the orchestrator fails the node
 * and the instance, while the already-{@code SUCCESS} DE rows are left untouched. A callback
 * that reports one DE {@code FAILED}/{@code TIMEOUT} is handled the same way by the
 * orchestrator; this handler does not fail the sibling records.
 *
 * <p><strong>Ready event.</strong> {@link TestEnvReadyEvent} is published at most once per
 * instance, from {@link #onTaskCompleted(PipelineInstance, NodeTask)} after the completion
 * transaction committed, and only when every latest-attempt {@code DEPLOY} task of the node is
 * {@code SUCCESS}. A partial success publishes nothing.
 *
 * <p><strong>Persistence boundaries.</strong> No pipeline row lock is held while the engine call
 * runs: the command is resolved first, the HTTP call happens outside a transaction, and the
 * status write then uses a short {@link TransactionRunner} transaction. The handler never calls
 * {@code advance} and never fails the node or the instance itself; the orchestrator owns those
 * transitions.
 */
public final class TestDeployNodeHandler implements NodeHandler {

    private static final Logger log = LoggerFactory.getLogger(TestDeployNodeHandler.class);

    /** Deploy timeout from tech design §5.4: {@code deadline_at = start + 8min}. */
    private static final Duration DEPLOY_DEADLINE = Duration.ofMinutes(8);

    private final CicdEngine engine;
    private final PipelineRepository repository;
    private final TransactionRunner transactionRunner;
    private final NodeTaskCompletionService completionService;
    private final PipelineEventBus eventBus;

    /**
     * Instances whose ready event this process already published. The completion hook runs
     * after the completing transaction but outside the instance lock, so the last two DE
     * callbacks could otherwise both observe "all succeeded". Publishing is idempotent per
     * instance and notification-only, so a duplicate is worse than a replay.
     */
    private final Set<Long> readyPublished = ConcurrentHashMap.newKeySet();

    public TestDeployNodeHandler(CicdEngine engine, PipelineRepository repository,
                                 TransactionRunner transactionRunner,
                                 NodeTaskCompletionService completionService,
                                 PipelineEventBus eventBus) {
        this.engine = Objects.requireNonNull(engine, "engine must not be null");
        this.repository = Objects.requireNonNull(repository, "repository must not be null");
        this.transactionRunner = Objects.requireNonNull(transactionRunner,
            "transactionRunner must not be null");
        this.completionService = Objects.requireNonNull(completionService,
            "completionService must not be null");
        this.eventBus = Objects.requireNonNull(eventBus, "eventBus must not be null");
    }

    @Override
    public NodeType nodeType() {
        return NodeType.TEST_DEPLOY;
    }

    /**
     * Triggers every TEST DE whose latest attempt is still {@code PENDING}. The tasks are
     * independent: one trigger never waits for another DE's status.
     *
     * <p>The aggregate is re-read first: a re-entry may be handed a stale instance (for example
     * a handler invoked twice, or the Task 14 recovery path), and only the committed task status
     * can prove that a DE has no engine job yet.
     */
    @Override
    public void start(PipelineInstance instance) {
        PipelineInstance current = repository.loadInstance(instance.id());
        if (current == null) {
            return;
        }
        PipelineNode node = current.node(NodeType.TEST_DEPLOY);
        if (node == null) {
            return;
        }
        List<NodeTask> startable = startableDeploys(node);
        if (startable.isEmpty()) {
            return;
        }
        PipelineRepository.DeployContext context = repository.deployContextOf(current.id());
        if (current.imageDigest() == null) {
            log.warn("deploying without a built image digest: instanceId={}, pipelineNo={}",
                current.id(), current.pipelineNo());
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
     * Publishes the ready event after a successful DE completion, when every latest-attempt
     * deploy task of the node succeeded. A failure never publishes it: the orchestrator's
     * failure path fails the node and the instance.
     */
    @Override
    public void onTaskCompleted(PipelineInstance instance, NodeTask completed) {
        if (completed.nodeType() != NodeType.TEST_DEPLOY
                || completed.status() != NodeTaskStatus.SUCCESS) {
            return;
        }
        publishReadyIfAllDeploysSucceeded(instance);
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
            log.warn("task was not moved to WAITING_EXTERNAL: instanceId={}, taskId={},"
                + " externalId={}", instance.id(), task.id(), result.externalId());
        }
    }

    /**
     * Re-reads the committed aggregate in its own short transaction and publishes
     * {@link TestEnvReadyEvent} when the node's latest-attempt deploy tasks all succeeded. The
     * publication happens after that read transaction returns, so it is strictly post-commit.
     */
    private void publishReadyIfAllDeploysSucceeded(PipelineInstance instance) {
        PipelineInstance current = transactionRunner.inTransaction(
            () -> repository.loadInstance(instance.id()));
        if (current == null) {
            return;
        }
        PipelineNode node = current.node(NodeType.TEST_DEPLOY);
        if (node == null || !allDeploysSucceeded(node)) {
            return;
        }
        PipelineRepository.DeployContext context = repository.deployContextOf(current.id());
        Instant now = Instant.now();
        TestEnvReadyEvent event = new TestEnvReadyEvent(context.publishNo(), current.id(), now);
        if (!readyPublished.add(current.id())) {
            log.debug("test env ready event already published: instanceId={}", current.id());
            return;
        }
        eventBus.publish(event);
        log.info("test environment ready: instanceId={}, publishNo={}, pipelineNo={}",
            current.id(), context.publishNo(), current.pipelineNo());
    }

    private static List<NodeTask> startableDeploys(PipelineNode node) {
        List<NodeTask> startable = new ArrayList<>();
        for (NodeTask task : latestDeploys(node).values()) {
            if (task.status() == NodeTaskStatus.PENDING) {
                startable.add(task);
            }
        }
        return startable;
    }

    /**
     * The latest attempt per logical deploy key {@code (task_type, batch_no, target_id)}; a
     * manual retry inserts a new {@code attempt} row and keeps the old failed row (design §5.4),
     * so the first row of a DE may be superseded.
     */
    private static Map<DeployKey, NodeTask> latestDeploys(PipelineNode node) {
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
        return latest;
    }

    /** Every latest-attempt deploy task succeeded; no TEST DE at all is not a ready environment. */
    private static boolean allDeploysSucceeded(PipelineNode node) {
        Map<DeployKey, NodeTask> latest = latestDeploys(node);
        if (latest.isEmpty()) {
            return false;
        }
        for (NodeTask task : latest.values()) {
            if (task.status() != NodeTaskStatus.SUCCESS) {
                return false;
            }
        }
        return true;
    }

    /**
     * Marks the current task failed through the completion service, so the usual
     * {@code NodeTaskCompletedEvent} is published (the design forbids silent engine failures).
     * The exception is rethrown by the caller so the orchestrator runs its failure path in a new
     * short transaction; this handler does not fail the node or the instance itself.
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