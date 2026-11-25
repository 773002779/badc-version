package com.sf.release.application;

import com.sf.release.common.BizError;
import com.sf.release.common.BizException;
import com.sf.release.domain.engine.CicdEngine;
import com.sf.release.domain.engine.EngineException;
import com.sf.release.domain.engine.PublishCmd;
import com.sf.release.domain.engine.TriggerResult;
import com.sf.release.domain.pipeline.NodeHandler;
import com.sf.release.domain.pipeline.NodeTask;
import com.sf.release.domain.pipeline.NodeTaskCompletionService;
import com.sf.release.domain.pipeline.NodeTaskStatus;
import com.sf.release.domain.pipeline.NodeType;
import com.sf.release.domain.pipeline.PipelineInstance;
import com.sf.release.domain.pipeline.PipelineNode;
import com.sf.release.domain.pipeline.TaskType;
import com.sf.release.domain.pipeline.UnitPipelineConfig;
import com.sf.release.domain.pipeline.port.PipelineRepository;
import com.sf.release.domain.pipeline.port.TransactionRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * Executes the {@code LIB_PUBLISH} node: uploads the second-party library artifact through the
 * engine ({@link CicdEngine#startPublish}) and then waits for its result (P6-A design §1/§3).
 *
 * <p><strong>Coordinates come from the unit, the version from the 变更项.</strong>
 * {@link PipelineRepository#libPublishContextOf} resolves the release order (publish number), the
 * unit's enabled {@code LIB_PUBLISH} binding, the artifact coordinates (V29) and the item's
 * version. The platform never derives or bumps a version in this slice.
 *
 * <p><strong>One trigger.</strong> Only the latest {@code LIB_PUBLISH} task that is still
 * {@code PENDING} is started; a re-entry after the task moved to {@code WAITING_EXTERNAL} starts
 * nothing, so a retry can never upload the same artifact twice. The task waits with a 60-minute
 * deadline; the webhook or the reconcile poll advances it (the job kind of a
 * {@code LIB_PUBLISH} task is {@code PUBLISH}).
 *
 * <p><strong>Configuration and engine failures are persisted.</strong> A missing binding or
 * coordinate marks the task {@code FAILED} with an explicit reason and fails the node; an
 * {@link EngineException} stores {@code ENGINE_UNAVAILABLE}/{@code ENGINE_TIMEOUT} plus the
 * adapter message through {@link NodeTaskCompletionService} (never a silent failure), then the
 * orchestrator runs its normal node/instance failure path.
 */
@Component
public final class LibPublishNodeHandler implements NodeHandler {

    private static final Logger log = LoggerFactory.getLogger(LibPublishNodeHandler.class);

    /** The publish deadline of P6-A design §3: {@code deadline_at = start + 60min}. */
    static final Duration PUBLISH_DEADLINE = Duration.ofMinutes(60);

    private final CicdEngine engine;
    private final PipelineRepository repository;
    private final TransactionRunner transactionRunner;
    private final NodeTaskCompletionService completionService;

    public LibPublishNodeHandler(CicdEngine engine, PipelineRepository repository,
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
        return NodeType.LIB_PUBLISH;
    }

    /**
     * Starts the one pending artifact upload. The aggregate is re-read, so a re-entry can see
     * that the task already has an engine job.
     */
    @Override
    public void start(PipelineInstance instance) {
        PipelineInstance current = repository.loadInstance(instance.id());
        if (current == null) {
            return;
        }
        PipelineNode node = current.node(NodeType.LIB_PUBLISH);
        if (node == null) {
            return;
        }
        NodeTask task = latestPublishTask(node);
        if (task == null || task.status() != NodeTaskStatus.PENDING) {
            return;
        }
        PipelineRepository.LibPublishContext context =
            repository.libPublishContextOf(current.id());
        String missing = missingConfiguration(context);
        if (missing != null) {
            String reason = "STATE_NOT_ALLOWED: " + missing;
            failTask(task, reason);
            throw new BizException(BizError.STATE_NOT_ALLOWED, reason,
                Map.of("instanceId", current.id(), "unitId", context.unitId(),
                    "taskId", task.id()));
        }
        trigger(current, context, task);
    }

    /**
     * Resolves the command, calls the engine <em>outside</em> any transaction, then persists the
     * external identifiers, the {@code WAITING_EXTERNAL} status and its deadline in one short
     * transaction — the same boundary as the build and deploy handlers, so an HTTP call never
     * holds a row lock.
     */
    private void trigger(PipelineInstance instance, PipelineRepository.LibPublishContext context,
                         NodeTask task) {
        PublishCmd cmd = new PublishCmd(context.taskId(), context.groupId(), context.artifactId(),
            context.version(), instance.pipelineNo(), task.attempt());
        TriggerResult result;
        try {
            result = engine.startPublish(cmd);
        } catch (EngineException ex) {
            failTask(task, engineFailure(ex));
            throw ex;
        }
        Instant now = Instant.now();
        // P6-C: an artifact upload is a build-family step, so it uses the unit's build timeout
        // (the design's safe subset leaves LIB unaffected otherwise).
        Duration deadline = buildDeadlineOf(instance.id());
        boolean stored = transactionRunner.inTransaction(() -> repository.markTaskWaitingExternal(
            task.id(), result.externalId(), result.externalUrl(), now.plus(deadline), now));
        if (!stored) {
            log.warn("library publish task was not moved to WAITING_EXTERNAL: instanceId={},"
                + " taskId={}, externalId={}", instance.id(), task.id(), result.externalId());
        }
        log.info("library artifact publish triggered: instanceId={}, artifact={}:{},"
                + " version={}, externalId={}", instance.id(), context.groupId(),
            context.artifactId(), context.version(), result.externalId());
    }

    /**
     * The publish timeout of this pipeline (P6-C design §2: {@code LIB_PUBLISH} uses the build
     * timeout): the unit's configured value, or the P6-A constant when there is no row.
     */
    private Duration buildDeadlineOf(long instanceId) {
        UnitPipelineConfig config = repository.pipelineConfigOf(instanceId);
        return config == null ? PUBLISH_DEADLINE : config.buildDeadline();
    }

    /**
     * The first missing piece of the publish configuration, or {@code null}. Each message names
     * the exact missing item so the operator can fix the unit instead of guessing.
     */
    private static String missingConfiguration(PipelineRepository.LibPublishContext context) {
        if (context.unitId() <= 0) {
            return "流水线实例没有可解析的二方库单元";
        }
        if (context.taskId() == null || context.taskId().isBlank()) {
            return "二方库未绑定 LIB_PUBLISH 入库任务（unitId=" + context.unitId() + "）";
        }
        if (context.groupId() == null || context.groupId().isBlank()
                || context.artifactId() == null || context.artifactId().isBlank()) {
            return "二方库未配置制品坐标（unitId=" + context.unitId() + "）";
        }
        if (context.version() == null || context.version().isBlank()) {
            return "变更项没有版本号，无法入库制品（publishNo=" + context.publishNo() + "）";
        }
        return null;
    }

    /** The latest attempt of the node's one {@code LIB_PUBLISH} task, or {@code null}. */
    private static NodeTask latestPublishTask(PipelineNode node) {
        NodeTask latest = null;
        for (NodeTask task : node.tasks()) {
            if (task.taskType() != TaskType.LIB_PUBLISH) {
                continue;
            }
            if (latest == null || task.attempt() > latest.attempt()) {
                latest = task;
            }
        }
        return latest;
    }

    /**
     * Marks the task failed through the completion service, so the usual completion event is
     * published. The exception is rethrown by the caller so the orchestrator runs the node and
     * instance failure path in a new short transaction.
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