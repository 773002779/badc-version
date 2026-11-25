package com.sf.release.application;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sf.release.common.BizError;
import com.sf.release.common.BizException;
import com.sf.release.domain.engine.CicdEngine;
import com.sf.release.domain.pipeline.InstanceStatus;
import com.sf.release.domain.pipeline.NodeStatus;
import com.sf.release.domain.pipeline.NodeType;
import com.sf.release.domain.pipeline.PipelineInstance;
import com.sf.release.domain.pipeline.PipelineNode;
import com.sf.release.domain.pipeline.event.InstanceFinishedEvent;
import com.sf.release.domain.pipeline.port.PipelineEventBus;
import com.sf.release.domain.pipeline.port.PipelineRepository;
import com.sf.release.domain.pipeline.port.UnitLease;
import com.sf.release.infra.repo.DeployUnitLockMapper;
import com.sf.release.infra.repo.PipelineInstanceMapper;
import com.sf.release.infra.repo.po.DeployUnitLockPO;
import com.sf.release.infra.repo.po.PipelineInstancePO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The single termination path of a pipeline instance (P5-B design §2), shared by stop, exit, close
 * and archive.
 *
 * <p><strong>One business transaction, then best effort.</strong> {@link #terminate(long, String)}
 * writes the business state and publishes {@link InstanceFinishedEvent} with
 * {@link InstanceStatus#TERMINATED} <em>inside the caller's transaction</em>, so an instance can never
 * end without its 变更项/任务/approval/check cascade committing with it. The engine cancellations and
 * the 部署单元 lease release run in {@link #afterCommit(Collection)} <em>after</em> that transaction
 * commits: a DB rollback cannot undo an outbound cancel and a released distributed lock cannot be
 * taken back, so the order is deliberate (the same rule the orchestrator follows for a terminal
 * release).
 *
 * <p><strong>What is kept and what is skipped.</strong> Only unfinished work is cancelled: the still
 * {@code PENDING} tasks and the {@code RUNNING}/{@code WAITING_EXTERNAL} ones whose engine job is then
 * cancelled best-effort, plus the {@code RUNNING} node itself. Every {@code SUCCESS} row stays as the
 * evidence of what already went online — a stop never recollects a deployed batch (J8/J21, design §8).
 * {@code FAILED}/{@code TIMEOUT} rows are history and stay untouched too.
 *
 * <p><strong>The event is published, not simulated.</strong> A termination is an end like any other:
 * the listeners that converge a rollback record ({@code RollbackCompletionService}) or a
 * {@code pipeline_item_failure} row need to see it. It is published inside the caller's transaction,
 * exactly like {@code PipelineOrchestrator#finishInstance} publishes {@code DONE}/{@code FAILED}.
 *
 * <p><strong>Idempotency.</strong> {@link #terminate(long, String)} answers {@code terminated=false}
 * for an instance that is already terminal and writes nothing: the callers turn that into their own
 * 40001 ("already stopped/exited/closed"), so a repeated action is refused instead of silently
 * succeeding (design §1). A lost status CAS on an instance that is <em>still</em> active is an error
 * (40001 with the current status), never a half termination.
 */
@Service
public class PipelineTerminationService {

    private static final Logger log = LoggerFactory.getLogger(PipelineTerminationService.class);

    /** The statuses a termination may end. {@code PAUSED} is included on purpose (see the CAS). */
    private static final Set<InstanceStatus> ACTIVE = EnumSet.of(
        InstanceStatus.PENDING, InstanceStatus.RUNNING, InstanceStatus.PAUSED);

    private final PipelineRepository repository;
    private final PipelineInstanceMapper instanceMapper;
    private final DeployUnitLockMapper lockMapper;
    private final UnitLease unitLease;
    private final CicdEngine engine;
    private final PipelineEventBus eventBus;

    /**
     * The audit row does not belong here: it belongs to the action (stop/exit/close/archive), which
     * knows its own object type, object id and reason. This service only ends the instance.
     */
    public PipelineTerminationService(PipelineRepository repository,
                                      PipelineInstanceMapper instanceMapper,
                                      DeployUnitLockMapper lockMapper,
                                      UnitLease unitLease,
                                      CicdEngine engine,
                                      PipelineEventBus eventBus) {
        this.repository = Objects.requireNonNull(repository, "repository must not be null");
        this.instanceMapper = Objects.requireNonNull(instanceMapper,
            "instanceMapper must not be null");
        this.lockMapper = Objects.requireNonNull(lockMapper, "lockMapper must not be null");
        this.unitLease = Objects.requireNonNull(unitLease, "unitLease must not be null");
        this.engine = Objects.requireNonNull(engine, "engine must not be null");
        this.eventBus = Objects.requireNonNull(eventBus, "eventBus must not be null");
    }

    /**
     * Terminates the in-flight instances of one 发布单, newest first. An online order has at most one
     * live instance (the unit lock serialises publishes), so the first result is "the" instance; the
     * list shape keeps a leaked older row from being silently ignored.
     *
     * @return one entry per instance that was actually terminated, newest first
     */
    public List<Terminated> terminateOrder(long orderId, String reason) {
        List<Terminated> terminated = new ArrayList<>();
        for (PipelineInstancePO active : instanceMapper.selectActiveByOrder(orderId)) {
            addIfTerminated(terminated, active.getId(), reason);
        }
        return List.copyOf(terminated);
    }

    /**
     * Terminates every in-flight instance of one 变更项: the online instance of its 发布单 plus the
     * daily instances that hang off the item directly (V28). Closing an item must end all of them.
     */
    public List<Terminated> terminateItem(long itemId, String reason) {
        List<Terminated> terminated = new ArrayList<>();
        for (PipelineInstancePO active : instanceMapper.selectActiveByItem(itemId)) {
            addIfTerminated(terminated, active.getId(), reason);
        }
        return List.copyOf(terminated);
    }

    /**
     * Terminates every in-flight instance of one 发布任务, including the ones that are not tied to an
     * unfinished 变更项 (a rollback instance of a {@code DONE} item, a daily release of an item that is
     * still {@code PENDING}).
     */
    public List<Terminated> terminateTask(long taskId, String reason) {
        List<Terminated> terminated = new ArrayList<>();
        for (PipelineInstancePO active : instanceMapper.selectActiveByTask(taskId)) {
            addIfTerminated(terminated, active.getId(), reason);
        }
        return List.copyOf(terminated);
    }

    /**
     * All instance ids of one 发布任务 (online through the order, daily through the item), used by
     * the archive cascade to release every unit lock the task still holds.
     */
    public List<Long> instanceIdsOfTask(long taskId) {
        List<Long> ids = instanceMapper.selectIdsByTask(taskId);
        return ids == null ? List.of() : List.copyOf(ids);
    }

    /**
     * Terminates one instance inside the caller's transaction.
     *
     * @param reason operator-facing reason stored in {@code status_reason} and in the result summary
     *               of every skipped task
     * @return {@code terminated=false} when the instance was already terminal (nothing was written);
     *     otherwise the instance, its pipeline number, its unit and the engine jobs to cancel
     * @throws BizException 40000 the instance does not exist; 40001 the status CAS was lost while the
     *                      instance is still active (with the observed status in the payload)
     */
    public Terminated terminate(long instanceId, String reason) {
        PipelineInstance instance = repository.loadInstance(instanceId);
        if (instance == null) {
            throw new BizException(BizError.PARAM_INVALID, "流水线实例不存在",
                Map.of("instanceId", instanceId));
        }
        PipelineRepository.InstanceContext context = repository.contextOf(instanceId);
        Instant now = Instant.now();
        if (!ACTIVE.contains(instance.status())) {
            log.info("pipeline termination skipped, the instance is already terminal:"
                + " instanceId={}, status={}", instanceId, instance.status());
            return Terminated.notTerminated(instanceId, instance.pipelineNo(), context.unitId());
        }
        boolean terminated = repository.terminateInstance(instanceId, reason, now);
        if (!terminated) {
            // Another writer changed the row between our read and the CAS. Re-read once: an instance
            // that is terminal now was terminated concurrently (idempotent no-op); one that is still
            // active means the CAS lost for a different reason and must not be swallowed.
            PipelineInstance current = repository.loadInstance(instanceId);
            InstanceStatus status = current == null ? null : current.status();
            if (status != null && !ACTIVE.contains(status)) {
                log.info("pipeline termination raced with another terminal write: instanceId={},"
                    + " status={}", instanceId, status);
                return Terminated.notTerminated(instanceId, instance.pipelineNo(), context.unitId());
            }
            throw new BizException(BizError.STATE_NOT_ALLOWED,
                "流水线状态已变化，终止未生效，请刷新后重试",
                Map.of("instanceId", instanceId,
                    "currentStatus", status == null ? "UNKNOWN" : status.name()));
        }
        // Only unfinished work is cancelled; a SUCCESS batch (already online) is never recalled.
        List<String> externalIds = repository.skipUnfinishedTasks(instanceId, reason, now);
        skipRunningNode(instance, now);
        eventBus.publish(new InstanceFinishedEvent(instanceId, context.orderId(),
            InstanceStatus.TERMINATED, reason, now));
        log.info("pipeline terminated: instanceId={}, pipelineNo={}, orderId={}, unitId={},"
                + " engineJobsToCancel={}, reason={}", instanceId, instance.pipelineNo(),
            context.orderId(), context.unitId(), externalIds.size(), reason);
        return new Terminated(instanceId, instance.pipelineNo(), context.unitId(), true,
            externalIds);
    }

    /**
     * Cancels the engine jobs of every terminated instance (best effort: a cancellation failure is a
     * WARN, never an error the operator sees, because the business state is already committed) and
     * releases the 部署单元 lease. Runs after the business transaction committed.
     */
    public void afterCommit(Collection<Terminated> terminated) {
        if (terminated == null || terminated.isEmpty()) {
            return;
        }
        for (Terminated result : terminated) {
            for (String externalId : result.externalIds()) {
                cancelQuietly(externalId);
            }
            releaseUnitLockQuietly(result);
        }
    }

    /**
     * Releases every 部署单元 lock a 发布任务 still holds (P5-B archive: "释放全部锁"). Runs after
     * the business transaction committed, like the per-instance release.
     *
     * <p><strong>Never steals a live lock.</strong> The archive terminates the task's in-flight
     * instances itself, so a row that is still owned by an {@code ACTIVE} instance can only be a
     * publish that raced the archive (it checked the task before the archive committed). Such a
     * row is skipped: the racing publish is the owner and will release it when it ends. Every
     * other row — a leaked lock of an already terminal instance, or one whose owner disappeared —
     * is released ownership-scoped, so a failure is a WARN.
     */
    public void releaseTaskLocks(long taskId) {
        List<Long> instanceIds = instanceIdsOfTask(taskId);
        if (instanceIds.isEmpty()) {
            return;
        }
        List<DeployUnitLockPO> locks = lockMapper.selectList(
            Wrappers.<DeployUnitLockPO>lambdaQuery()
                .in(DeployUnitLockPO::getInstanceId, instanceIds));
        for (DeployUnitLockPO lock : locks) {
            Long owner = lock.getInstanceId();
            if (owner == null || owner <= 0) {
                continue;
            }
            if (stillActive(owner)) {
                log.debug("unit lock kept: its instance is still active after the archive:"
                    + " unitId={}, instanceId={}", lock.getUnitId(), owner);
                continue;
            }
            releaseUnitLock(lock.getUnitId(), owner);
        }
    }

    /**
     * Releases one unit lock ownership-scoped (the instance id must still own the row). Runs after the
     * commit for the same reason as the engine cancel: a delete cannot roll back a distributed unlock.
     */
    public void releaseUnitLock(long unitId, long instanceId) {
        if (unitId <= 0 || instanceId <= 0) {
            return;
        }
        try {
            if (unitLease.release(unitId, instanceId)) {
                log.info("unit lock released after termination: unitId={}, instanceId={}", unitId,
                    instanceId);
            } else {
                log.debug("unit lock was not released (no longer owned): unitId={}, instanceId={}",
                    unitId, instanceId);
            }
        } catch (RuntimeException ex) {
            log.warn("unit lock release failed after termination: unitId={}, instanceId={},"
                + " reason={}", unitId, instanceId, messageOf(ex));
        }
    }

    // ------------------------------------------------------------------ internals

    private void addIfTerminated(List<Terminated> terminated, Long instanceId, String reason) {
        if (instanceId == null || instanceId <= 0) {
            return;
        }
        Terminated result = terminate(instanceId, reason);
        if (result.terminated()) {
            terminated.add(result);
        }
    }

    /**
     * Marks the node the instance was running {@code SKIPPED}. A node is "finished" by the skip
     * because it will never produce a result: leaving it {@code RUNNING} would make the instance look
     * mid-execution in every page even though it is {@code TERMINATED}. Nodes that are still
     * {@code PENDING} are left alone — they never started, and the skipped tasks already say so.
     */
    private void skipRunningNode(PipelineInstance instance, Instant now) {
        NodeType currentNode = instance.currentNode();
        if (currentNode == null) {
            return;
        }
        PipelineNode node = instance.node(currentNode);
        if (node == null || node.status() != NodeStatus.RUNNING) {
            return;
        }
        if (repository.finishNode(node.id(), NodeStatus.SKIPPED, now)) {
            log.info("pipeline node skipped by termination: instanceId={}, node={}",
                instance.id(), currentNode);
        }
    }

    private void releaseUnitLockQuietly(Terminated result) {
        releaseUnitLock(result.unitId(), result.instanceId());
    }

    /**
     * Whether the instance behind a lock row is still live. An unreadable status is treated as
     * live, which is the safe direction: never release a lock whose owner cannot be proven gone.
     */
    private boolean stillActive(long instanceId) {
        PipelineInstancePO owner = instanceMapper.selectById(instanceId);
        if (owner == null) {
            return false;
        }
        String status = owner.getStatus();
        if (status == null || status.isBlank()) {
            return true;
        }
        try {
            return ACTIVE.contains(InstanceStatus.valueOf(status));
        } catch (IllegalArgumentException unknown) {
            return true;
        }
    }

    private void cancelQuietly(String externalId) {
        if (externalId == null || externalId.isBlank()) {
            return;
        }
        try {
            engine.cancel(externalId);
            log.info("engine job cancelled after termination: externalId={}", externalId);
        } catch (RuntimeException ex) {
            log.warn("engine job could not be cancelled after termination: externalId={},"
                + " reason={}", externalId, messageOf(ex));
        }
    }

    private static String messageOf(RuntimeException ex) {
        String message = ex.getMessage();
        return message == null || message.isBlank() ? ex.getClass().getSimpleName() : message;
    }

    /**
     * One terminated (or already terminal) instance and what still has to happen outside the
     * transaction: the engine jobs to cancel and the unit lock to release.
     */
    public record Terminated(long instanceId, String pipelineNo, long unitId, boolean terminated,
                             List<String> externalIds) {

        public Terminated {
            externalIds = externalIds == null ? List.of() : List.copyOf(externalIds);
        }

        static Terminated notTerminated(long instanceId, String pipelineNo, long unitId) {
            return new Terminated(instanceId, pipelineNo, unitId, false, List.of());
        }
    }
}