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
import com.sf.release.domain.pipeline.port.PipelineRepository;
import com.sf.release.domain.resource.EnvKind;
import com.sf.release.domain.resource.TaskKind;
import com.sf.release.infra.repo.po.CodingTaskBindingPO;
import com.sf.release.infra.repo.po.EnvDePO;
import com.sf.release.infra.repo.po.NodeTaskPO;
import com.sf.release.infra.repo.po.PipelineInstancePO;
import com.sf.release.infra.repo.po.PipelineNodePO;
import com.sf.release.infra.repo.po.ReleaseItemPO;
import com.sf.release.infra.repo.po.ReleaseOrderPO;
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

    public MybatisPipelineRepository(PipelineInstanceMapper instanceMapper,
                                     PipelineNodeMapper nodeMapper,
                                     NodeTaskMapper taskMapper,
                                     ReleaseOrderMapper orderMapper,
                                     ReleaseItemMapper itemMapper,
                                     CodingTaskBindingMapper bindingMapper,
                                     EnvDeMapper envDeMapper) {
        this.instanceMapper = instanceMapper;
        this.nodeMapper = nodeMapper;
        this.taskMapper = taskMapper;
        this.orderMapper = orderMapper;
        this.itemMapper = itemMapper;
        this.bindingMapper = bindingMapper;
        this.envDeMapper = envDeMapper;
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