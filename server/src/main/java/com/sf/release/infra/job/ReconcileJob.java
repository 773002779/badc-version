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
import com.sf.release.domain.pipeline.port.ItemFailureRepairer;
import com.sf.release.domain.pipeline.port.PipelineRepository;
import com.sf.release.infra.engine.EngineRegistry;
import com.sf.release.infra.repo.NodeTaskMapper;
import com.sf.release.infra.repo.PipelineInstanceMapper;
import com.sf.release.infra.repo.PipelineNodeMapper;
import com.sf.release.infra.repo.po.NodeTaskPO;
import com.sf.release.infra.repo.po.PipelineInstancePO;
import com.sf.release.infra.repo.po.PipelineNodePO;
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
 * <p><strong>One runner, bounded round.</strong> {@link #run()} first takes
 * {@code lock:job:reconcile} (5min lease); a second application instance skips this round. The
 * round renews its lease every minute and stops scanning at a hard round deadline (10min), so an
 * engine outage cannot stretch a round past its lease. A failed renewal means the lease is no
 * longer ours: the round aborts and the final unlock is skipped (unlocking would release another
 * runner's lease).
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
 * <p><strong>{@code PAUSED} is never repaired.</strong> An instance the orchestrator paused at a
 * node without a handler (phase 1 boundary: {@code VERIFY}/{@code PROD_DEPLOY}) is a deliberate
 * stop, not a dropped advance: replaying it would re-enter the same blocked node forever. The
 * candidate query only selects {@code RUNNING} rows and the loop double-checks the status.
 *
 * <p><strong>Stuck nodes.</strong> A RUNNING node whose every counted task is still
 * {@code PENDING} after ten minutes is reported with an ERROR naming the instance and the node
 * (I4). Nothing is changed: a handler may still be starting, and restarting a handler needs the
 * row-level entry claim that is P3.
 *
 * <p><strong>Listener failures.</strong> Unresolved {@code pipeline_item_failure} rows (V12) are
 * replayed through {@link ItemFailureRepairer}: a successful replay sets {@code resolved_at}, a
 * third failed attempt logs ERROR and leaves the row for operations, and every later round warns
 * that it is still unresolved.
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

    /** Hard stop of one round, so a slow engine cannot stretch a round indefinitely. */
    public static final Duration DEFAULT_ROUND_DEADLINE = Duration.ofMinutes(10);

    /** How often the round renews its lease; well below {@link #LOCK_TTL}. */
    public static final Duration DEFAULT_RENEW_INTERVAL = Duration.ofMinutes(1);

    /** A task is polled only when it has not been observed for this long. */
    static final Duration STALE_AFTER = Duration.ofSeconds(90);

    /** Consecutive failed queries that escalate a task to TIMEOUT (design §6.3). */
    static final int FAILURE_LIMIT = 3;

    /**
     * A RUNNING node whose every counted task is still PENDING after this long is reported as
     * stuck (I4). Ten minutes is far beyond a normal entry-to-first-trigger latency, so it is a
     * real anomaly rather than a slow engine call.
     */
    static final Duration STUCK_NODE_AGE = Duration.ofMinutes(10);

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
    private final PipelineNodeMapper nodeMapper;
    private final PipelineInstanceMapper instanceMapper;
    private final ItemFailureRepairer failureRepairer;
    private final Duration roundDeadline;
    private final Duration renewInterval;

    public ReconcileJob(DistributedLock lock,
                        EngineRegistry engines,
                        String engineType,
                        PipelineRepository repository,
                        PipelineOrchestrator orchestrator,
                        NodeTaskMapper taskMapper,
                        PipelineNodeMapper nodeMapper,
                        PipelineInstanceMapper instanceMapper,
                        ItemFailureRepairer failureRepairer) {
        this(lock, engines, engineType, repository, orchestrator, taskMapper, nodeMapper,
            instanceMapper, failureRepairer, DEFAULT_ROUND_DEADLINE, DEFAULT_RENEW_INTERVAL);
    }

    /**
     * @param roundDeadline hard stop of one round; the scan stops when it is reached and the
     *                      rest is deferred to the next round. Zero stops immediately and is
     *                      intended for tests.
     * @param renewInterval how often the round renews its distributed lease; the final unlock
     *                      is skipped when the last renewal failed (the lease is no longer
     *                      ours, so unlocking it would steal another runner's lease)
     */
    public ReconcileJob(DistributedLock lock,
                        EngineRegistry engines,
                        String engineType,
                        PipelineRepository repository,
                        PipelineOrchestrator orchestrator,
                        NodeTaskMapper taskMapper,
                        PipelineNodeMapper nodeMapper,
                        PipelineInstanceMapper instanceMapper,
                        ItemFailureRepairer failureRepairer,
                        Duration roundDeadline,
                        Duration renewInterval) {
        if (roundDeadline == null || roundDeadline.isNegative()) {
            throw new IllegalArgumentException("roundDeadline must not be negative");
        }
        if (renewInterval == null || renewInterval.isNegative()) {
            throw new IllegalArgumentException("renewInterval must not be negative");
        }
        if (failureRepairer == null) {
            throw new IllegalArgumentException("failureRepairer must not be null");
        }
        this.lock = lock;
        this.engines = engines;
        this.engineType = engineType;
        this.repository = repository;
        this.orchestrator = orchestrator;
        this.taskMapper = taskMapper;
        this.nodeMapper = nodeMapper;
        this.instanceMapper = instanceMapper;
        this.failureRepairer = failureRepairer;
        this.roundDeadline = roundDeadline;
        this.renewInterval = renewInterval;
    }

    /** One reconcile round; guarded so only one application instance runs it. */
    @Scheduled(fixedDelay = 30_000)
    public void run() {
        if (!lock.tryLock(LOCK_KEY, LOCK_TTL)) {
            log.debug("reconcile round skipped: {} is held by another runner", LOCK_KEY);
            return;
        }
        Round round = new Round(Instant.now());
        try {
            int polled = reconcileWaitingTasks(round);
            int resolved = round.held() ? drainItemFailures(round) : 0;
            int repaired = round.held() ? repairRunningInstances(round) : 0;
            int stuck = round.held() ? reportStuckNodes(round) : 0;
            if (polled > 0 || repaired > 0 || resolved > 0 || stuck > 0) {
                log.info("reconcile round finished: polledTasks={}, repairedInstances={},"
                    + " resolvedItemFailures={}, stuckNodes={}", polled, repaired, resolved,
                    stuck);
            }
        } catch (RuntimeException ex) {
            // The scheduler must never die because one round hit a transient DB/engine error.
            log.error("reconcile round failed; the next round retries", ex);
        } finally {
            round.release();
        }
    }

    // ------------------------------------------------------------------ waiting tasks

    private int reconcileWaitingTasks(Round round) {
        Instant now = Instant.now();
        List<NodeTaskPO> stale = taskMapper.selectStaleWaiting(now.minus(STALE_AFTER), now,
            SCAN_LIMIT);
        int polled = 0;
        for (NodeTaskPO po : stale) {
            Instant moment = Instant.now();
            if (round.expired(moment)) {
                log.warn("reconcile round deadline ({} min) reached after {} task(s);"
                    + " {} task(s) deferred to the next round", roundDeadline.toMinutes(), polled,
                    stale.size() - polled);
                break;
            }
            if (!round.ensureLease(moment)) {
                break;
            }
            NodeTask task;
            try {
                task = po.toDomain();
            } catch (RuntimeException ex) {
                log.warn("reconcile skipping malformed node_task row id={}", po.getId(), ex);
                continue;
            }
            try {
                reconcileTask(task, moment);
                polled++;
            } catch (RuntimeException ex) {
                // A single bad task must not abort the rest of the round.
                log.error("reconcile failed for taskId={}, externalId={}", task.id(),
                    task.externalId(), ex);
            }
        }
        return polled;
    }

    /** Replays persisted listener/transition failures (V12); bounded by {@link #SCAN_LIMIT}. */
    private int drainItemFailures(Round round) {
        Instant now = Instant.now();
        if (round.expired(now)) {
            log.warn("reconcile round deadline reached before draining item transition failures");
            return 0;
        }
        if (!round.ensureLease(now)) {
            return 0;
        }
        try {
            return failureRepairer.retryUnresolved(SCAN_LIMIT);
        } catch (RuntimeException ex) {
            log.error("reconcile item-failure drain failed", ex);
            return 0;
        }
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
            // A successful UNKNOWN query is a successful observation for bookkeeping: reset the
            // failure counter and move updated_at forward (non-terminal, no status change). A
            // fail/fail/UNKNOWN/fail sequence must not reach the 3-failure TIMEOUT, and the row
            // must not be re-queried in the next round immediately.
            log.warn("reconcile saw an unmapped engine status word for taskId={}, externalId={}:"
                + " {}; the task stays {}", task.id(), task.externalId(), result.message(),
                task.status());
            taskMapper.recordQuerySuccess(task.id(), now);
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

    private int repairRunningInstances(Round round) {
        // PENDING is included on purpose: publish/rerun commit the instance and then start it.
        // A crash (or a handler that threw before the first node was entered) between those two
        // steps leaves a PENDING instance nobody would ever start; this pass re-enters its first
        // node, and entering a node is CAS-guarded so a replay is harmless.
        List<PipelineInstancePO> running = instanceMapper.selectList(
            Wrappers.<PipelineInstancePO>lambdaQuery()
                .in(PipelineInstancePO::getStatus, InstanceStatus.PENDING.name(),
                    InstanceStatus.RUNNING.name())
                .orderByAsc(PipelineInstancePO::getId)
                .last("LIMIT " + SCAN_LIMIT));
        int repaired = 0;
        for (PipelineInstancePO candidate : running) {
            Instant moment = Instant.now();
            if (round.expired(moment)) {
                log.warn("reconcile round deadline ({} min) reached; advance repair deferred",
                    roundDeadline.toMinutes());
                break;
            }
            if (!round.ensureLease(moment)) {
                break;
            }
            long instanceId = candidate.getId() == null ? 0L : candidate.getId();
            try {
                PipelineInstance instance = repository.loadInstance(instanceId);
                if (instance == null) {
                    continue;
                }
                if (instance.status() == InstanceStatus.PAUSED) {
                    // A deliberate stop at a node without a handler: never replay the advance.
                    log.debug("reconcile skipping paused instance: instanceId={}", instanceId);
                    continue;
                }
                PipelineNode current = instance.node(instance.currentNode());
                if (current == null) {
                    continue;
                }
                if (current.status() == NodeStatus.PENDING) {
                    // The instance was committed but its first node was never entered: this is the
                    // publish/rerun -> start gap, repaired by entering the node again.
                    log.info("reconcile entering an unstarted node: instanceId={}, node={}",
                        instanceId, current.nodeType());
                    orchestrator.advance(instanceId);
                    repaired++;
                    continue;
                }
                if (current.status() != NodeStatus.RUNNING) {
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

    // ------------------------------------------------------------------ stuck node detection

    /**
     * Reports nodes that were entered but whose every counted task is still {@code PENDING} after
     * {@link #STUCK_NODE_AGE} (I4). No state is changed: the row-level entry claim that would let
     * a node handler be started safely is P3, and inventing a failure here could kill a pipeline
     * whose handler is simply slow to commit its first trigger. The ERROR names the instance and
     * the node so operations can act on it.
     */
    private int reportStuckNodes(Round round) {
        Instant now = Instant.now();
        if (round.expired(now)) {
            log.warn("reconcile round deadline ({} min) reached before the stuck-node scan",
                roundDeadline.toMinutes());
            return 0;
        }
        if (!round.ensureLease(now)) {
            return 0;
        }
        List<PipelineNodePO> stuck;
        try {
            stuck = nodeMapper.selectStuckNodes(now.minus(STUCK_NODE_AGE), SCAN_LIMIT);
        } catch (RuntimeException ex) {
            log.error("reconcile stuck-node scan failed; the next round retries", ex);
            return 0;
        }
        for (PipelineNodePO node : stuck) {
            log.error("reconcile: pipeline node stuck with every counted task PENDING for more"
                    + " than {} minutes; no state change (row-level entry claim is P3):"
                    + " instanceId={}, node={}, startedAt={}",
                STUCK_NODE_AGE.toMinutes(), node.getInstanceId(), node.getNodeType(),
                node.getStartedAt());
        }
        return stuck.size();
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

    /**
     * One round's hard stop and its lease bookkeeping.
     *
     * <p>The round stops scanning at {@link #roundDeadline}; the remaining work is deferred to
     * the next round. The distributed lease is renewed every {@link #renewInterval}; when a
     * renewal fails the lease is no longer ours, so the round stops and the final unlock is
     * skipped (an unconditional unlock would release another runner's lease).
     */
    private final class Round {

        private final Instant deadline;
        private Instant lastRenewal;
        private boolean held = true;

        private Round(Instant start) {
            this.deadline = start.plus(roundDeadline);
            this.lastRenewal = start;
        }

        private boolean held() {
            return held;
        }

        private boolean expired(Instant now) {
            return !now.isBefore(deadline);
        }

        private boolean ensureLease(Instant now) {
            if (!held) {
                return false;
            }
            if (Duration.between(lastRenewal, now).compareTo(renewInterval) < 0) {
                return true;
            }
            if (!lock.renew(LOCK_KEY, LOCK_TTL)) {
                held = false;
                log.warn("reconcile lease {} could not be renewed; aborting the round without"
                    + " unlocking (the lease may belong to another runner)", LOCK_KEY);
                return false;
            }
            lastRenewal = now;
            return true;
        }

        private void release() {
            if (held) {
                lock.unlock(LOCK_KEY);
            } else {
                log.debug("reconcile lease was lost; skipping unlock of {}", LOCK_KEY);
            }
        }
    }

    /** Logical task identity, mirroring the orchestrator's latest-attempt fold. */
    private record TaskKey(TaskType taskType, int batchNo, long targetId) {
    }
}