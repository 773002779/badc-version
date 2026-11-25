package com.sf.release.infra.repo;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sf.release.common.BizError;
import com.sf.release.common.BizException;
import com.sf.release.domain.pipeline.InstanceStatus;
import com.sf.release.domain.pipeline.NodeStatus;
import com.sf.release.domain.pipeline.NodeTask;
import com.sf.release.domain.pipeline.NodeTaskStatus;
import com.sf.release.domain.pipeline.NodeType;
import com.sf.release.domain.pipeline.PipelineInstance;
import com.sf.release.domain.pipeline.PipelineNode;
import com.sf.release.domain.pipeline.TaskType;
import com.sf.release.domain.pipeline.UnitPipelineConfig;
import com.sf.release.domain.pipeline.port.PipelineRepository;
import com.sf.release.domain.resource.EnvKind;
import com.sf.release.domain.resource.TaskKind;
import com.sf.release.infra.repo.po.CodingTaskBindingPO;
import com.sf.release.infra.repo.po.DeployUnitPO;
import com.sf.release.infra.repo.po.EnvDePO;
import com.sf.release.infra.repo.po.NodeTaskPO;
import com.sf.release.infra.repo.po.PipelineInstancePO;
import com.sf.release.infra.repo.po.PipelineNodePO;
import com.sf.release.infra.repo.po.ReleaseItemPO;
import com.sf.release.infra.repo.po.ReleaseOrderPO;
import com.sf.release.infra.repo.po.UnitPipelineConfigPO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * MyBatis-Plus implementation of the pipeline persistence port. Every state change is a status
 * compare-and-set, which is the DB-level idempotency the orchestrator relies on (tech design
 * §4.3).
 */
@Repository
public class MybatisPipelineRepository implements PipelineRepository {

    private static final Logger log = LoggerFactory.getLogger(MybatisPipelineRepository.class);

    private static final List<String> TERMINAL_TASK_STATUSES = List.of(
        NodeTaskStatus.SUCCESS.name(), NodeTaskStatus.FAILED.name(),
        NodeTaskStatus.TIMEOUT.name(), NodeTaskStatus.SKIPPED.name());
    private static final int MAX_SUMMARY_LENGTH = 500;
    private static final int MAX_DIGEST_LENGTH = 80;

    /**
     * Width of {@code pipeline_instance.commit_sha} (V14). Git SHA-1 is 40 characters and
     * SHA-256 is 64; the column is 64 wide and a longer value is a business error instead of a
     * silent truncation, because a cut commit would compare unequal to the commit it names.
     */
    private static final int MAX_COMMIT_SHA_LENGTH = 64;
    private static final int MAX_STATUS_REASON_LENGTH = 255;

    /**
     * How long a claimed task may stay {@code RUNNING} without an external id before the reconcile
     * pass treats the trigger as lost (independent review I4). Comfortably longer than an engine
     * trigger call, so a slow engine is not mistaken for a crash, yet short enough that a crash
     * does not leave the instance {@code RUNNING} for hours.
     */
    public static final Duration CLAIM_GRACE = Duration.ofMinutes(15);

    private final PipelineInstanceMapper instanceMapper;
    private final PipelineNodeMapper nodeMapper;
    private final NodeTaskMapper taskMapper;
    private final ReleaseOrderMapper orderMapper;
    private final ReleaseItemMapper itemMapper;
    private final CodingTaskBindingMapper bindingMapper;
    private final EnvDeMapper envDeMapper;
    private final DeployUnitMapper unitMapper;
    private final UnitPipelineConfigMapper pipelineConfigMapper;

    public MybatisPipelineRepository(PipelineInstanceMapper instanceMapper,
                                     PipelineNodeMapper nodeMapper,
                                     NodeTaskMapper taskMapper,
                                     ReleaseOrderMapper orderMapper,
                                     ReleaseItemMapper itemMapper,
                                     CodingTaskBindingMapper bindingMapper,
                                     EnvDeMapper envDeMapper,
                                     DeployUnitMapper unitMapper,
                                     UnitPipelineConfigMapper pipelineConfigMapper) {
        this.instanceMapper = instanceMapper;
        this.nodeMapper = nodeMapper;
        this.taskMapper = taskMapper;
        this.orderMapper = orderMapper;
        this.itemMapper = itemMapper;
        this.bindingMapper = bindingMapper;
        this.envDeMapper = envDeMapper;
        this.unitMapper = unitMapper;
        this.pipelineConfigMapper = pipelineConfigMapper;
    }

    @Override
    public PipelineInstance loadInstance(long instanceId) {
        PipelineInstancePO instance = instanceMapper.selectById(instanceId);
        if (instance == null) {
            return null;
        }
        List<PipelineNode> nodes = new ArrayList<>();
        for (PipelineNodePO node : nodeMapper.selectList(
                Wrappers.<PipelineNodePO>lambdaQuery()
                    .eq(PipelineNodePO::getInstanceId, instanceId)
                    .orderByAsc(PipelineNodePO::getSeqNo))) {
            List<NodeTask> tasks = taskMapper.selectList(
                    Wrappers.<NodeTaskPO>lambdaQuery()
                        .eq(NodeTaskPO::getInstanceId, instanceId)
                        .eq(NodeTaskPO::getNodeType, node.getNodeType())
                        .orderByAsc(NodeTaskPO::getId))
                .stream()
                .map(NodeTaskPO::toDomain)
                .toList();
            nodes.add(node.toDomain(tasks));
        }
        return instance.toDomain(nodes);
    }

    @Override
    public PipelineInstance latestInstanceOf(long itemId) {
        ReleaseOrderPO order = orderMapper.selectOne(Wrappers.<ReleaseOrderPO>lambdaQuery()
            .eq(ReleaseOrderPO::getItemId, itemId)
            .orderByDesc(ReleaseOrderPO::getId)
            .last("LIMIT 1"));
        if (order == null) {
            return null;
        }
        PipelineInstancePO instance = instanceMapper.selectOne(
            Wrappers.<PipelineInstancePO>lambdaQuery()
                .eq(PipelineInstancePO::getOrderId, order.getId())
                .orderByDesc(PipelineInstancePO::getId)
                .last("LIMIT 1"));
        return instance == null ? null : loadInstance(instance.getId());
    }

    @Override
    public NodeTask loadTask(long taskId) {
        NodeTaskPO task = taskMapper.selectById(taskId);
        return task == null ? null : task.toDomain();
    }

    @Override
    public InstanceContext contextOf(long instanceId) {
        PipelineInstancePO instance = instanceMapper.selectById(instanceId);
        if (instance == null || instance.getOrderId() == null || instance.getOrderId() <= 0) {
            return new InstanceContext(instanceId, 0L, 0L);
        }
        long orderId = instance.getOrderId();
        ReleaseOrderPO order = orderMapper.selectById(orderId);
        if (order == null || order.getItemId() == null) {
            return new InstanceContext(instanceId, orderId, 0L);
        }
        ReleaseItemPO item = itemMapper.selectById(order.getItemId());
        long unitId = item == null || item.getUnitId() == null ? 0L : item.getUnitId();
        return new InstanceContext(instanceId, orderId, unitId);
    }

    @Override
    public long itemIdOf(long instanceId) {
        PipelineInstancePO instance = instanceMapper.selectById(instanceId);
        if (instance == null || instance.getOrderId() == null || instance.getOrderId() <= 0) {
            return 0L;
        }
        ReleaseOrderPO order = orderMapper.selectById(instance.getOrderId());
        return order == null || order.getItemId() == null ? 0L : order.getItemId();
    }

    @Override
    public boolean completeTask(long taskId, NodeTaskStatus status, String digest, String message,
                                Instant now) {
        NodeTask task = loadTask(taskId);
        if (task == null) {
            return false;
        }
        boolean terminal = TERMINAL_TASK_STATUSES.contains(status.name());
        LambdaUpdateWrapper<NodeTaskPO> update = Wrappers.<NodeTaskPO>lambdaUpdate()
            .eq(NodeTaskPO::getId, taskId)
            .notIn(NodeTaskPO::getStatus, TERMINAL_TASK_STATUSES)
            .set(NodeTaskPO::getStatus, status.name())
            .set(NodeTaskPO::getUpdatedAt, now);
        if (terminal) {
            update.set(NodeTaskPO::getFinishedAt, now);
            if (task.startedAt() != null) {
                update.set(NodeTaskPO::getDurationMs,
                    Math.max(0L, Duration.between(task.startedAt(), now).toMillis()));
            }
        } else if (task.startedAt() == null) {
            update.set(NodeTaskPO::getStartedAt, now);
        }
        if (message != null && !message.isBlank()) {
            update.set(NodeTaskPO::getResultSummary, truncate(message, MAX_SUMMARY_LENGTH));
        }
        int rows = taskMapper.update(null, update);

        if (rows == 1 && status == NodeTaskStatus.SUCCESS
                && task.taskType() == TaskType.IMG_BUILD
                && digest != null && !digest.isBlank()) {
            instanceMapper.update(null, Wrappers.<PipelineInstancePO>lambdaUpdate()
                .eq(PipelineInstancePO::getId, task.instanceId())
                .set(PipelineInstancePO::getImageDigest,
                    truncate(digest.trim(), MAX_DIGEST_LENGTH)));
        }
        return rows == 1;
    }

    @Override
    public boolean startInstance(long instanceId, NodeType nodeType, Instant now) {
        return instanceMapper.update(null, Wrappers.<PipelineInstancePO>lambdaUpdate()
            .eq(PipelineInstancePO::getId, instanceId)
            .eq(PipelineInstancePO::getStatus, InstanceStatus.PENDING.name())
            .set(PipelineInstancePO::getStatus, InstanceStatus.RUNNING.name())
            .set(PipelineInstancePO::getStartAt, now)
            .set(PipelineInstancePO::getCurrentNode, nodeType.name())) == 1;
    }

    @Override
    public boolean moveToNode(long instanceId, NodeType fromNode, NodeType toNode) {
        return instanceMapper.update(null, Wrappers.<PipelineInstancePO>lambdaUpdate()
            .eq(PipelineInstancePO::getId, instanceId)
            .eq(PipelineInstancePO::getStatus, InstanceStatus.RUNNING.name())
            .eq(PipelineInstancePO::getCurrentNode, fromNode.name())
            .set(PipelineInstancePO::getCurrentNode, toNode.name())) == 1;
    }

    @Override
    public boolean startNode(long nodeId, Instant now) {
        return nodeMapper.update(null, Wrappers.<PipelineNodePO>lambdaUpdate()
            .eq(PipelineNodePO::getId, nodeId)
            .eq(PipelineNodePO::getStatus, NodeStatus.PENDING.name())
            .set(PipelineNodePO::getStatus, NodeStatus.RUNNING.name())
            .set(PipelineNodePO::getStartedAt, now)) == 1;
    }

    @Override
    public boolean claimTaskStart(long taskId) {
        Instant now = Instant.now();
        // The claim grace (I4): a crash between the claim and markTaskWaitingExternal leaves a
        // RUNNING task with no external id; this deadline is what lets the reconcile pass time it
        // out instead of leaving the instance RUNNING forever. It is comfortably longer than any
        // engine trigger call, so a slow trigger is not mistaken for a lost one.
        return nodeMapper.claimTaskStart(taskId, now, now.plus(CLAIM_GRACE)) == 1;
    }

    @Override
    public boolean finishNodeIfComplete(long nodeId, Instant now) {
        return nodeMapper.completeIfAllTasksSucceeded(nodeId, now) == 1;
    }

    @Override
    public boolean finishNodeSatisfiedByHandler(long nodeId, Instant now) {
        return nodeMapper.completeSatisfiedByHandler(nodeId, now) == 1;
    }

    @Override
    public boolean finishNode(long nodeId, NodeStatus status, Instant now) {
        return nodeMapper.update(null, Wrappers.<PipelineNodePO>lambdaUpdate()
            .eq(PipelineNodePO::getId, nodeId)
            .eq(PipelineNodePO::getStatus, NodeStatus.RUNNING.name())
            .set(PipelineNodePO::getStatus, status.name())
            .set(PipelineNodePO::getFinishedAt, now)) == 1;
    }

    @Override
    public boolean skipPendingTasks(long instanceId, NodeType nodeType, Instant now) {
        return taskMapper.skipPending(instanceId, nodeType.name(), now) >= 1;
    }

    @Override
    public boolean finishInstance(long instanceId, InstanceStatus status, Instant now) {
        return instanceMapper.update(null, Wrappers.<PipelineInstancePO>lambdaUpdate()
            .eq(PipelineInstancePO::getId, instanceId)
            .in(PipelineInstancePO::getStatus, InstanceStatus.PENDING.name(),
                InstanceStatus.RUNNING.name())
            .set(PipelineInstancePO::getStatus, status.name())
            .set(PipelineInstancePO::getEndAt, now)) == 1;
    }

    /**
     * {@code PENDING/RUNNING/PAUSED → TERMINATED} while recording why (P5-B design §2 step 2).
     *
     * <p>{@link #finishInstance(long, InstanceStatus, Instant)} is the normal completion path and
     * matches only {@code PENDING}/{@code RUNNING}. A release waiting for approval or for production
     * execution is {@code PAUSED}, and a stop/exit/close/archive must end it too, so this CAS widens
     * the matched statuses instead of changing the completion path everybody else relies on.
     */
    @Override
    public boolean terminateInstance(long instanceId, String reason, Instant now) {
        return instanceMapper.update(null, Wrappers.<PipelineInstancePO>lambdaUpdate()
            .eq(PipelineInstancePO::getId, instanceId)
            .in(PipelineInstancePO::getStatus, InstanceStatus.PENDING.name(),
                InstanceStatus.RUNNING.name(), InstanceStatus.PAUSED.name())
            .set(PipelineInstancePO::getStatus, InstanceStatus.TERMINATED.name())
            .set(PipelineInstancePO::getStatusReason,
                truncate(reason == null ? "" : reason, MAX_STATUS_REASON_LENGTH))
            .set(PipelineInstancePO::getEndAt, now)) == 1;
    }

    /**
     * Marks the unfinished tasks of one instance {@code SKIPPED} and answers their engine jobs
     * (P5-B design §2 step 3/4). The external ids are read first, so the caller can cancel them after
     * its transaction commits; the successful batches are untouched by construction, because only
     * {@code PENDING}/{@code RUNNING}/{@code WAITING_EXTERNAL} rows are updated.
     */
    @Override
    public List<String> skipUnfinishedTasks(long instanceId, String reason, Instant now) {
        List<String> externalIds = taskMapper.selectActiveExternalIds(instanceId);
        String summary = truncate("已取消：" + (reason == null ? "" : reason), MAX_SUMMARY_LENGTH);
        taskMapper.skipUnfinished(instanceId, summary, now);
        return externalIds == null ? List.of() : List.copyOf(externalIds);
    }

    /**
     * {@code PENDING/RUNNING → FAILED} while recording why. Same status CAS as
     * {@link #finishInstance(long, InstanceStatus, Instant)}; the reason is what makes a failed
     * pipeline diagnosable from the row.
     */
    @Override
    public boolean failInstance(long instanceId, String reason, Instant now) {
        return instanceMapper.update(null, Wrappers.<PipelineInstancePO>lambdaUpdate()
            .eq(PipelineInstancePO::getId, instanceId)
            .in(PipelineInstancePO::getStatus, InstanceStatus.PENDING.name(),
                InstanceStatus.RUNNING.name())
            .set(PipelineInstancePO::getStatus, InstanceStatus.FAILED.name())
            .set(PipelineInstancePO::getStatusReason,
                truncate(reason, MAX_STATUS_REASON_LENGTH))
            .set(PipelineInstancePO::getEndAt, now)) == 1;
    }

    /**
     * {@code PENDING/RUNNING → PAUSED}: the node the pipeline cannot enter becomes the current
     * node and the reason is stored for operators. The status CAS is what makes a second
     * caller (another thread, a replayed reconcile advance) a no-op instead of a duplicate
     * pause event.
     */
    @Override
    public boolean pauseInstance(long instanceId, NodeType blockedNode, String reason, Instant now) {
        return instanceMapper.update(null, Wrappers.<PipelineInstancePO>lambdaUpdate()
            .eq(PipelineInstancePO::getId, instanceId)
            .in(PipelineInstancePO::getStatus, InstanceStatus.PENDING.name(),
                InstanceStatus.RUNNING.name())
            .set(PipelineInstancePO::getStatus, InstanceStatus.PAUSED.name())
            .set(PipelineInstancePO::getCurrentNode, blockedNode.name())
            .set(PipelineInstancePO::getStatusReason, truncate(reason, MAX_STATUS_REASON_LENGTH))
            .set(PipelineInstancePO::getEndAt, now)
            .set(PipelineInstancePO::getPausedAt, now)) == 1;
    }

    @Override
    public boolean resumeInstance(long instanceId, Instant now) {
        return instanceMapper.update(null, Wrappers.<PipelineInstancePO>lambdaUpdate()
            .eq(PipelineInstancePO::getId, instanceId)
            .eq(PipelineInstancePO::getStatus, InstanceStatus.PAUSED.name())
            .set(PipelineInstancePO::getStatus, InstanceStatus.RUNNING.name())
            .set(PipelineInstancePO::getStatusReason, null)
            .set(PipelineInstancePO::getEndAt, null)
            .set(PipelineInstancePO::getPausedAt, null)) == 1;
    }

    @Override
    public BuildContext buildContextOf(long instanceId) {
        PipelineInstancePO instance = instanceMapper.selectById(instanceId);
        if (instance == null || instance.getOrderId() == null || instance.getOrderId() <= 0) {
            return new BuildContext(0L, null, null, null);
        }
        ReleaseOrderPO order = orderMapper.selectById(instance.getOrderId());
        if (order == null || order.getItemId() == null) {
            return new BuildContext(0L, null, null, null);
        }
        ReleaseItemPO item = itemMapper.selectById(order.getItemId());
        if (item == null) {
            return new BuildContext(0L, null, null, null);
        }
        long unitId = item.getUnitId() == null ? 0L : item.getUnitId();
        CodingTaskBindingPO binding = unitId <= 0 ? null : bindingMapper.selectOne(
            Wrappers.<CodingTaskBindingPO>lambdaQuery()
                .eq(CodingTaskBindingPO::getUnitId, unitId)
                .eq(CodingTaskBindingPO::getTaskKind, TaskKind.BUILD.name())
                .eq(CodingTaskBindingPO::getEnabled, true));
        // The enabled BUILD binding is authoritative; the item's snapshot is the fallback
        // when the binding was removed or disabled after the item was created.
        String planId = binding == null ? null : trimToNull(binding.getTaskId());
        if (planId == null) {
            planId = trimToNull(item.getBuildPlanId());
        }
        String commitSha = trimToNull(instance.getCommitSha());
        return new BuildContext(unitId, planId, trimToNull(item.getSourceBranch()), commitSha);
    }

    @Override
    public DeployContext deployContextOf(long instanceId) {
        return resolveDeployContext(instanceId, TaskKind.TEST_DEPLOY,
            List.of(EnvKind.TEST.name()));
    }

    /**
     * The production counterpart of {@link #deployContextOf(long)}: the unit's enabled
     * {@code PROD_DEPLOY} binding and its production-family DEs ({@code PROD}, {@code DR},
     * {@code BLUE}), ordered by batch then id so the handler's per-batch fan-out is deterministic.
     */
    @Override
    public DeployContext prodDeployContextOf(long instanceId) {
        return resolveDeployContext(instanceId, TaskKind.PROD_DEPLOY,
            List.of(EnvKind.PROD.name(), EnvKind.DR.name(), EnvKind.BLUE.name()));
    }

    /**
     * The build coordinates of a daily instance (P5-A design §3). A daily instance carries its
     * own {@code item_id/unit_id} (V28) because it has no release order to resolve them through;
     * the plan, branch and commit rules are the online ones (enabled {@code BUILD} binding, the
     * item's snapshot as fallback, the commit the pipeline already reported).
     */
    @Override
    public BuildContext dailyBuildContextOf(long instanceId) {
        PipelineInstancePO instance = instanceMapper.selectById(instanceId);
        if (instance == null || instance.getItemId() == null || instance.getItemId() <= 0) {
            return new BuildContext(0L, null, null, null);
        }
        ReleaseItemPO item = itemMapper.selectById(instance.getItemId());
        if (item == null) {
            return new BuildContext(0L, null, null, null);
        }
        long unitId = dailyUnitId(instance, item);
        CodingTaskBindingPO binding = unitId <= 0 ? null : bindingMapper.selectOne(
            Wrappers.<CodingTaskBindingPO>lambdaQuery()
                .eq(CodingTaskBindingPO::getUnitId, unitId)
                .eq(CodingTaskBindingPO::getTaskKind, TaskKind.BUILD.name())
                .eq(CodingTaskBindingPO::getEnabled, true));
        // Same authority order as the online context: the enabled BUILD binding wins, the item's
        // creation-time snapshot is the fallback.
        String planId = binding == null ? null : trimToNull(binding.getTaskId());
        if (planId == null) {
            planId = trimToNull(item.getBuildPlanId());
        }
        return new BuildContext(unitId, planId, trimToNull(item.getSourceBranch()),
            trimToNull(instance.getCommitSha()));
    }

    /**
     * The deploy coordinates of a daily instance (P5-A design §4): the enabled
     * {@code DAILY_DEPLOY} binding with the unit's {@code TEST_DEPLOY} binding as the C24
     * fallback, and the {@code DAILY} DEs (or the {@code TEST} DEs when the unit has no daily
     * one). There is no release order, so the publish number is {@code null}.
     */
    @Override
    public DeployContext dailyDeployContextOf(long instanceId) {
        PipelineInstancePO instance = instanceMapper.selectById(instanceId);
        if (instance == null || instance.getItemId() == null || instance.getItemId() <= 0) {
            return new DeployContext(0L, null, null, List.of());
        }
        ReleaseItemPO item = itemMapper.selectById(instance.getItemId());
        if (item == null) {
            return new DeployContext(0L, null, null, List.of());
        }
        long unitId = dailyUnitId(instance, item);
        if (unitId <= 0) {
            return new DeployContext(0L, null, null, List.of());
        }
        String planId = enabledBindingTaskId(unitId, TaskKind.DAILY_DEPLOY);
        if (planId == null) {
            planId = enabledBindingTaskId(unitId, TaskKind.TEST_DEPLOY);
        }
        List<DeployTarget> targets = dailyDeployTargets(unitId);
        return new DeployContext(unitId, null, planId, targets);
    }

    /**
     * The artifact-publish coordinates of a library instance (P6-A design §3): resolved through
     * the release order like every online context, plus the unit's artifact coordinates and the
     * 变更项 version. A missing binding/coordinate is returned as {@code null}, so the handler
     * can fail the task with an explicit reason instead of calling the engine with a null task.
     */
    @Override
    public LibPublishContext libPublishContextOf(long instanceId) {
        PipelineInstancePO instance = instanceMapper.selectById(instanceId);
        if (instance == null || instance.getOrderId() == null || instance.getOrderId() <= 0) {
            return new LibPublishContext(0L, null, null, null, null, null);
        }
        ReleaseOrderPO order = orderMapper.selectById(instance.getOrderId());
        if (order == null || order.getItemId() == null) {
            return new LibPublishContext(0L, null, null, null, null, null);
        }
        String publishNo = trimToNull(order.getPublishNo());
        ReleaseItemPO item = itemMapper.selectById(order.getItemId());
        if (item == null) {
            return new LibPublishContext(0L, publishNo, null, null, null, null);
        }
        long unitId = item.getUnitId() == null ? 0L : item.getUnitId();
        if (unitId <= 0) {
            return new LibPublishContext(0L, publishNo, null, null, null,
                trimToNull(item.getVersion()));
        }
        DeployUnitPO unit = unitMapper.selectById(unitId);
        return new LibPublishContext(unitId, publishNo,
            enabledBindingTaskId(unitId, TaskKind.LIB_PUBLISH),
            unit == null ? null : trimToNull(unit.getArtifactGroupId()),
            unit == null ? null : trimToNull(unit.getArtifactArtifactId()),
            trimToNull(item.getVersion()));
    }

    /**
     * The unit pipeline configuration of an instance (P6-C design §2), resolved through the same
     * order → item → unit path as the other contexts, or from the instance's own {@code unit_id}
     * when it has no order (the daily pipeline).
     *
     * <p><strong>Never throws to the pipeline.</strong> No row, no resolvable unit and a failed
     * read all answer {@code null}; the handlers then use their documented constants. This is the
     * "读失败/无行回退常量默认" rule of the design, enforced at the one place every consumer calls.
     */
    @Override
    public UnitPipelineConfig pipelineConfigOf(long instanceId) {
        try {
            long unitId = unitIdOfInstance(instanceId);
            if (unitId <= 0) {
                return null;
            }
            UnitPipelineConfigPO po = pipelineConfigMapper.selectById(unitId);
            return po == null ? null : po.toDomain();
        } catch (RuntimeException ex) {
            log.warn("unit pipeline config could not be read; the handler defaults are used:"
                + " instanceId={}, reason={}", instanceId, ex.getMessage());
            return null;
        }
    }

    /**
     * The deployment unit behind an instance: the order → item → unit path for an online-style
     * execution, the row's own {@code unit_id} (V28) for a daily execution.
     */
    private long unitIdOfInstance(long instanceId) {
        PipelineInstancePO instance = instanceMapper.selectById(instanceId);
        if (instance == null) {
            return 0L;
        }
        if (instance.getOrderId() != null && instance.getOrderId() > 0) {
            ReleaseOrderPO order = orderMapper.selectById(instance.getOrderId());
            if (order == null || order.getItemId() == null) {
                return 0L;
            }
            ReleaseItemPO item = itemMapper.selectById(order.getItemId());
            return item == null || item.getUnitId() == null ? 0L : item.getUnitId();
        }
        return instance.getUnitId() == null ? 0L : instance.getUnitId();
    }

    /**
     * The unit of a daily instance: the row's own {@code unit_id} first, the item's unit as a
     * defensive fallback (the service always writes both).
     */
    private static long dailyUnitId(PipelineInstancePO instance, ReleaseItemPO item) {
        if (instance.getUnitId() != null && instance.getUnitId() > 0) {
            return instance.getUnitId();
        }
        return item.getUnitId() == null ? 0L : item.getUnitId();
    }

    /** The CODING task id of one enabled binding kind, or {@code null} when there is none. */
    private String enabledBindingTaskId(long unitId, TaskKind kind) {
        CodingTaskBindingPO binding = bindingMapper.selectOne(
            Wrappers.<CodingTaskBindingPO>lambdaQuery()
                .eq(CodingTaskBindingPO::getUnitId, unitId)
                .eq(CodingTaskBindingPO::getTaskKind, kind.name())
                .eq(CodingTaskBindingPO::getEnabled, true));
        return binding == null ? null : trimToNull(binding.getTaskId());
    }

    /**
     * The daily deploy targets: the unit's {@code DAILY} DEs, or its {@code TEST} DEs when it has
     * no daily DE (C24). Ordered by batch then id, the same deterministic order the factory used.
     */
    private List<DeployTarget> dailyDeployTargets(long unitId) {
        List<DeployTarget> daily = envDeTargets(unitId, EnvKind.DAILY.name());
        return daily.isEmpty() ? envDeTargets(unitId, EnvKind.TEST.name()) : daily;
    }

    private List<DeployTarget> envDeTargets(long unitId, String envKind) {
        return envDeMapper.selectList(Wrappers.<EnvDePO>lambdaQuery()
                .eq(EnvDePO::getUnitId, unitId)
                .eq(EnvDePO::getEnvKind, envKind)
                .orderByAsc(EnvDePO::getDeployBatchNo)
                .orderByAsc(EnvDePO::getId))
            .stream()
            .map(de -> new DeployTarget(de.getId() == null ? 0L : de.getId(),
                de.getEnvKind(), de.getDeName()))
            .toList();
    }

    private DeployContext resolveDeployContext(long instanceId, TaskKind kind,
                                               List<String> envKinds) {
        PipelineInstancePO instance = instanceMapper.selectById(instanceId);
        if (instance == null || instance.getOrderId() == null || instance.getOrderId() <= 0) {
            return new DeployContext(0L, null, null, List.of());
        }
        ReleaseOrderPO order = orderMapper.selectById(instance.getOrderId());
        if (order == null || order.getItemId() == null) {
            return new DeployContext(0L, null, null, List.of());
        }
        String publishNo = trimToNull(order.getPublishNo());
        ReleaseItemPO item = itemMapper.selectById(order.getItemId());
        if (item == null) {
            return new DeployContext(0L, publishNo, null, List.of());
        }
        long unitId = item.getUnitId() == null ? 0L : item.getUnitId();
        CodingTaskBindingPO binding = unitId <= 0 ? null : bindingMapper.selectOne(
            Wrappers.<CodingTaskBindingPO>lambdaQuery()
                .eq(CodingTaskBindingPO::getUnitId, unitId)
                .eq(CodingTaskBindingPO::getTaskKind, kind.name())
                .eq(CodingTaskBindingPO::getEnabled, true));
        String planId = binding == null ? null : trimToNull(binding.getTaskId());
        List<DeployTarget> targets = unitId <= 0 ? List.of() : envDeMapper.selectList(
                Wrappers.<EnvDePO>lambdaQuery()
                    .eq(EnvDePO::getUnitId, unitId)
                    .in(EnvDePO::getEnvKind, envKinds)
                    .orderByAsc(EnvDePO::getDeployBatchNo)
                    .orderByAsc(EnvDePO::getId))
            .stream()
            .map(de -> new DeployTarget(de.getId() == null ? 0L : de.getId(),
                de.getEnvKind(), de.getDeName()))
            .toList();
        return new DeployContext(unitId, publishNo, planId, targets);
    }

    @Override
    public boolean claimTestReady(long instanceId) {
        return instanceMapper.claimTestReady(instanceId, Instant.now()) == 1;
    }

    @Override
    public boolean recordCommit(long instanceId, String commitSha) {
        String commit = requireCommitSha(commitSha, "recordCommit");
        if (commit == null) {
            return false;
        }
        return instanceMapper.update(null, Wrappers.<PipelineInstancePO>lambdaUpdate()
            .eq(PipelineInstancePO::getId, instanceId)
            .set(PipelineInstancePO::getCommitSha, commit)) == 1;
    }

    @Override
    public boolean markTaskWaitingExternal(long taskId, String externalId, String externalUrl,
                                           Instant deadlineAt, Instant now) {
        return taskMapper.update(null, Wrappers.<NodeTaskPO>lambdaUpdate()
            .eq(NodeTaskPO::getId, taskId)
            .in(NodeTaskPO::getStatus, NodeTaskStatus.PENDING.name(),
                NodeTaskStatus.RUNNING.name())
            .set(NodeTaskPO::getStatus, NodeTaskStatus.WAITING_EXTERNAL.name())
            .set(NodeTaskPO::getExternalId, externalId)
            .set(NodeTaskPO::getExternalUrl, externalUrl)
            .set(NodeTaskPO::getDeadlineAt, deadlineAt)
            .set(NodeTaskPO::getStartedAt, now)
            .set(NodeTaskPO::getUpdatedAt, now)) == 1;
    }

    @Override
    public boolean recordBuiltImage(long instanceId, String imageDigest, String commitSha) {
        String digest = trimToNull(imageDigest);
        String commit = requireCommitSha(commitSha, "recordBuiltImage");
        LambdaUpdateWrapper<PipelineInstancePO> update =
            Wrappers.<PipelineInstancePO>lambdaUpdate()
                .eq(PipelineInstancePO::getId, instanceId);
        if (digest != null) {
            update.set(PipelineInstancePO::getImageDigest, truncate(digest, MAX_DIGEST_LENGTH));
        }
        // Explicitly write the commit, including NULL: the engine reported "no commit" and a
        // stale value must not survive as if it belonged to this image.
        update.set(PipelineInstancePO::getCommitSha, commit);
        return instanceMapper.update(null, update) == 1;
    }

    /**
     * The commit to store, or {@code null} when none was reported. A value wider than the column
     * is rejected with a business error: silently cutting the SHA would make the stored commit
     * name a different (shorter) value than the engine reported, which is exactly the kind of
     * mismatch the rerun baseline and the production same-image check must not see.
     *
     * @throws BizException 40000 when {@code value} is wider than {@value #MAX_COMMIT_SHA_LENGTH}
     */
    private static String requireCommitSha(String value, String action) {
        String commit = trimToNull(value);
        if (commit == null) {
            return null;
        }
        if (commit.length() > MAX_COMMIT_SHA_LENGTH) {
            throw new BizException(BizError.PARAM_INVALID,
                "commit sha 长度超过 " + MAX_COMMIT_SHA_LENGTH + " 个字符，拒绝截断写入（"
                    + action + "）",
                Map.of("commitSha", commit, "maxLength", MAX_COMMIT_SHA_LENGTH,
                    "actualLength", commit.length()));
        }
        return commit;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String truncate(String value, int maxLength) {
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}