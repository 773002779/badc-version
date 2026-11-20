package com.sf.release.infra.job;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sf.release.common.Masker;
import com.sf.release.domain.engine.CicdEngine;
import com.sf.release.domain.engine.EngineException;
import com.sf.release.domain.engine.EngineResult;
import com.sf.release.domain.engine.EngineStatus;
import com.sf.release.domain.engine.JobKind;
import com.sf.release.domain.pipeline.InstanceStatus;
import com.sf.release.domain.pipeline.NodeStatus;
import com.sf.release.domain.pipeline.NodeTask;
import com.sf.release.domain.pipeline.NodeTaskStatus;
import com.sf.release.domain.pipeline.NodeType;
import com.sf.release.domain.pipeline.PipelineInstance;
import com.sf.release.domain.pipeline.PipelineNode;
import com.sf.release.domain.pipeline.PipelineOrchestrator;
import com.sf.release.domain.pipeline.TaskType;
import com.sf.release.domain.pipeline.port.DistributedLock;
import com.sf.release.domain.pipeline.port.PipelineRepository;
import com.sf.release.infra.engine.EngineRegistry;
import com.sf.release.infra.repo.NodeTaskMapper;
import com.sf.release.infra.repo.PipelineInstanceMapper;
import com.sf.release.infra.repo.po.NodeTaskPO;
import com.sf.release.infra.repo.po.PipelineInstancePO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The 30s polling backstop of the pipeline (tech design §5.2/§6.3): the webhook is real-time
 * but not reliable, so pending external jobs are reconciled against the engine and dropped
 * advances are replayed.
 *
 * <p><strong>One runner.</strong> {@link #run()} first takes {@code lock:job:reconcile} (5min
 * lease); a second application instance skips this round. The lease is released in
 * {@code finally}, so a crashed round does not block the next one for longer than the lease. A
 * round that outlives the lease could overlap another instance; both only ever write through
 * the completion-service CAS, so an overlap wastes queries but cannot corrupt a status.
 *
 * <p><strong>Which tasks are polled.</strong> {@code WAITING_EXTERNAL} tasks whose
 * {@code updated_at} is older than 90s, plus every such task whose {@code deadline_at} passed
 * (a timeout must not wait for a fresh observation). A successful non-terminal query moves
 * {@code updated_at} forward and resets the failure counter; a failed query leaves
 * {@code updated_at} alone and increments {@code query_failures}. Three consecutive failures —
 * or a passed deadline on a job the engine still reports as QUEUED/RUNNING — mark the task
 * {@code TIMEOUT} with a WARN, cancel the engine job best-effort and let the orchestrator run
 * its normal failure path. {@code UNKNOWN} is never terminal: it changes nothing and is logged.
 *
 * <p><strong>Advance repair.</strong> RUNNING instances whose current node has every latest
 * non-CHECK task {@code SUCCESS}/{@code SKIPPED} are handed to
 * {@link PipelineOrchestrator#advance(long)} again. This repairs a callback that committed while
 * the pipe lock was busy or a crash between the completion commit and the advance. The
 * orchestrator's instance lock and CAS make the replay idempotent. CHECK-only nodes are not
 * repaired here: their completion belongs to the asynchronous gate/check path (design §5.2 rule
 * 4), and a repair would start the next node before the checks are evaluated.
 *
 * <p><strong>No handler re-entry.</strong> An instance whose node was entered but whose handler
 * never ran (all tasks still {@code PENDING}) is not restarted here: calling a handler is not
 * idempotent and would need a row-level entry claim (Task 13 hand-off). That gap is recorded in
 * the task report instead of being papered over.
 */
public class ReconcileJob {

    /** Distributed key of the single-runner guard. */
    public static final String LOCK_KEY = "lock:job:reconcile";

    /** Lease of the job lock; a crashed round blocks another runner for at most this long. */
    public static final Duration LOCK_TTL = Duration.ofMinutes(5);

    /** A task is polled only when it has not been observed for this long. */
    static final Duration STALE_AFTER = Duration.ofSeconds(90);

    /** Consecutive failed queries that escalate a task to TIMEOUT (design §6.3). */
    static final int FAILURE_LIMIT = 3;

    /** Upper bound of tasks/instances handled per round, so one round cannot run unbounded. */
    static final int SCAN_LIMIT = 200;

    private static final Logger log = LoggerFactory.getLogger(ReconcileJob.class);

    private static final Set<NodeTaskStatus> SUCCESSFUL = EnumSet.of(
        NodeTaskStatus.SUCCESS, NodeTaskStatus.SKIPPED);

    private final DistributedLock lock;
    private final EngineRegistry engines;
    private final String engineType;
    private final PipelineRepository repository;
    private final PipelineOrchestrator orchestrator;
    private final NodeTaskMapper taskMapper;
    private final PipelineInstanceMapper instanceMapper;

    public ReconcileJob(DistributedLock lock,
                        EngineRegistry engines,
                        String engineType,
                        PipelineRepository repository,
                        PipelineOrchestrator orchestrator,
                        NodeTaskMapper taskMapper,
                        PipelineInstanceMapper instanceMapper) {
        this.lock = lock;
        this.engines = engines;
        this.engineType = engineType;
        this.repository = repository;
        this.orchestrator = orchestrator;
        this.taskMapper = taskMapper;
        this.instanceMapper = instanceMapper;
    }

    /** One reconcile round; guarded so only one application instance runs it. */
    @Scheduled(fixedDelay = 30_000)
    public void run() {
        if (!lock.tryLock(LOCK_KEY, LOCK_TTL)) {
            log.debug("reconcile round skipped: {} is held by another runner", LOCK_KEY);
            return;
        }
        try {
            Instant now = Instant.now();
            int polled = reconcileWaitingTasks(now);
            int repaired = repairRunningInstances();
            if (polled > 0 || repaired > 0) {
                log.info("reconcile round finished: polledTasks={}, repairedInstances={}",
                    polled, repaired);
            }
        } catch (RuntimeException ex) {
            // The scheduler must never die because one round hit a transient DB/engine error.
            log.error("reconcile round failed; the next round retries", ex);
        } finally {
            lock.unlock(LOCK_KEY);
        }
    }

    // ------------------------------------------------------------------ waiting tasks

    private int reconcileWaitingTasks(Instant now) {
        List<NodeTaskPO> stale = taskMapper.selectStaleWaiting(now.minus(STALE_AFTER), now,
            SCAN_LIMIT);
        int polled = 0;
        for (NodeTaskPO po : stale) {
            NodeTask task;
            try {
                task = po.toDomain();
            } catch (RuntimeException ex) {
                log.warn("reconcile skipping malformed node_task row id={}", po.getId(), ex);
                continue;
            }
            try {
                reconcileTask(task, now);
                polled++;
            } catch (RuntimeException ex) {
                // A single bad task must not abort the rest of the round.
                log.error("reconcile failed for taskId={}, externalId={}", task.id(),
                    task.externalId(), ex);
            }
        }
        return polled;
    }

    private void reconcileTask(NodeTask task, Instant now) {
        JobKind kind = kindFor(task);
        if (kind == null) {
            log.debug("reconcile skipping non-engine task: taskId={}, type={}", task.id(),
                task.taskType());
            return;
        }
        if (task.externalId() == null) {
            return;
        }
        CicdEngine engine = engineOrNull();
        EngineResult result;
        try {
            if (engine == null) {
                throw EngineException.unavailable("引擎未注册：" + engineType);
            }
            result = engine.query(task.externalId(), kind);
        } catch (EngineException ex) {
            onQueryFailure(task, ex.getMessage(), engine);
            return;
        } catch (RuntimeException ex) {
            onQueryFailure(task, messageOf(ex), engine);
            return;
        }
        onQueryResult(task, result, engine, now);
    }

    private void onQueryResult(NodeTask task, EngineResult result, CicdEngine engine, Instant now) {
        EngineStatus status = result.status();
        NodeTaskStatus taskStatus = switch (status) {
            case SUCCESS -> NodeTaskStatus.SUCCESS;
            case FAILED, CANCELED -> NodeTaskStatus.FAILED;
            case QUEUED, RUNNING, UNKNOWN -> null;
        };
        if (taskStatus != null) {
            orchestrator.onTaskCompleted(task.id(), taskStatus, result.imageDigest(),
                resultMessage(status, result));
            return;
        }
        if (status == EngineStatus.UNKNOWN) {
            // Non-terminal on purpose: an unmapped vendor word must not kill a live task.
            log.warn("reconcile saw an unmapped engine status word for taskId={}, externalId={}:"
                + " {}; the task stays {}", task.id(), task.externalId(), result.message(),
                task.status());
            return;
        }
        if (deadlinePassed(task, now)) {
            timeoutTask(task, engine, "deadline_at 已过，引擎仍报告 " + status);
            return;
        }
        taskMapper.recordQuerySuccess(task.id(), now);
    }

    private void onQueryFailure(NodeTask task, String reason, CicdEngine engine) {
        if (taskMapper.recordQueryFailure(task.id()) != 1) {
            // The task left WAITING_EXTERNAL (a webhook won the race); nothing to escalate.
            return;
        }
        Integer counter = taskMapper.selectQueryFailures(task.id());
        int failures = counter == null ? 0 : counter;
        String masked = Masker.mask(reason == null ? "unknown" : reason);
        if (failures >= FAILURE_LIMIT) {
            log.warn("reconcile: taskId={}, externalId={} failed {} consecutive engine queries,"
                + " marking TIMEOUT; last reason: {}", task.id(), task.externalId(), failures,
                masked);
            timeoutTask(task, engine, "连续 " + failures + " 次查询失败：" + masked);
            return;
        }
        log.warn("reconcile: engine query failed for taskId={}, externalId={} ({}/{}): {}",
            task.id(), task.externalId(), failures, FAILURE_LIMIT, masked);
    }

    /**
     * Escalates one task to {@code TIMEOUT}: cancel the engine job best-effort (design §5.4),
     * then let the orchestrator run its normal failure path (node and instance FAILED, pending
     * siblings skipped). Never swallows the reason: it becomes {@code result_summary}.
     */
    private void timeoutTask(NodeTask task, CicdEngine engine, String reason) {
        log.warn("reconcile: taskId={}, externalId={} TIMEOUT: {}", task.id(),
            task.externalId(), reason);
        cancelQuietly(engine, task.externalId());
        orchestrator.onTaskCompleted(task.id(), NodeTaskStatus.TIMEOUT, null,
            "reconcile: " + reason);
    }

    private static String resultMessage(EngineStatus status, EngineResult result) {
        if (status == EngineStatus.SUCCESS) {
            return null;
        }
        String raw = result.message() == null ? status.name() : result.message();
        return "引擎状态：" + status + "（原始状态词：" + raw + "）";
    }

    // ------------------------------------------------------------------ advance repair

    private int repairRunningInstances() {
        List<PipelineInstancePO> running = instanceMapper.selectList(
            Wrappers.<PipelineInstancePO>lambdaQuery()
                .eq(PipelineInstancePO::getStatus, InstanceStatus.RUNNING.name())
                .orderByAsc(PipelineInstancePO::getId)
                .last("LIMIT " + SCAN_LIMIT));
        int repaired = 0;
        for (PipelineInstancePO candidate : running) {
            long instanceId = candidate.getId() == null ? 0L : candidate.getId();
            try {
                PipelineInstance instance = repository.loadInstance(instanceId);
                if (instance == null) {
                    continue;
                }
                PipelineNode current = instance.node(instance.currentNode());
                if (current == null || current.status() != NodeStatus.RUNNING) {
                    continue;
                }
                if (!nodeLooksComplete(current)) {
                    continue;
                }
                log.info("reconcile replaying a dropped advance: instanceId={}, node={}",
                    instanceId, current.nodeType());
                orchestrator.advance(instanceId);
                repaired++;
            } catch (RuntimeException ex) {
                log.error("reconcile advance repair failed: instanceId={}", instanceId, ex);
            }
        }
        return repaired;
    }

    /**
     * Same completion fold as the orchestrator: latest attempt per
     * {@code (task_type, batch_no, target_id)}, {@code CHECK} excluded, every counted task
     * {@code SUCCESS}/{@code SKIPPED}. A node without any non-CHECK task is not repaired.
     */
    private static boolean nodeLooksComplete(PipelineNode node) {
        Map<TaskKey, NodeTask> latest = new HashMap<>();
        for (NodeTask task : node.tasks()) {
            if (task.taskType() == TaskType.CHECK) {
                continue;
            }
            TaskKey key = new TaskKey(task.taskType(), task.batchNo(), task.targetId());
            NodeTask current = latest.get(key);
            if (current == null || task.attempt() > current.attempt()) {
                latest.put(key, task);
            }
        }
        if (latest.isEmpty()) {
            return false;
        }
        for (NodeTask task : latest.values()) {
            if (!SUCCESSFUL.contains(task.status())) {
                return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------------ helpers

    /**
     * The engine job kind of a task. Build-chain tasks use {@code build.query}; every deploy
     * task (TEST/PROD/DAILY) uses {@code deploy.query}. CHECK/AUTO_TEST are not engine jobs.
     */
    static JobKind kindFor(NodeTask task) {
        return switch (task.taskType()) {
            case CODE_CHECK, PKG_BUILD, IMG_BUILD -> JobKind.BUILD;
            case DEPLOY, ROLLBACK -> JobKind.DEPLOY;
            case CHECK, AUTO_TEST -> null;
        };
    }

    private static boolean deadlinePassed(NodeTask task, Instant now) {
        return task.deadlineAt() != null && task.deadlineAt().isBefore(now);
    }

    private CicdEngine engineOrNull() {
        return engines.find(engineType).orElse(null);
    }

    private void cancelQuietly(CicdEngine engine, String externalId) {
        if (engine == null || externalId == null) {
            return;
        }
        try {
            engine.cancel(externalId);
        } catch (RuntimeException ex) {
            log.warn("reconcile could not cancel engine job {}: {}", externalId,
                Masker.mask(messageOf(ex)));
        }
    }

    private static String messageOf(RuntimeException ex) {
        String message = ex.getMessage();
        return message == null || message.isBlank() ? ex.getClass().getSimpleName() : message;
    }

    /** Logical task identity, mirroring the orchestrator's latest-attempt fold. */
    private record TaskKey(TaskType taskType, int batchNo, long targetId) {
    }
}