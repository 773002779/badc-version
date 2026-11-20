package com.sf.release.infra.repo;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
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
    private static final int MAX_COMMIT_SHA_LENGTH = 40;
    private static final int MAX_STATUS_REASON_LENGTH = 255;

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
    public boolean finishNodeIfComplete(long nodeId, Instant now) {
        return nodeMapper.completeIfAllTasksSucceeded(nodeId, now) == 1;
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
            .set(PipelineInstancePO::getEndAt, now)) == 1;
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
                .eq(CodingTaskBindingPO::getTaskKind, TaskKind.TEST_DEPLOY.name())
                .eq(CodingTaskBindingPO::getEnabled, true));
        String planId = binding == null ? null : trimToNull(binding.getTaskId());
        List<DeployTarget> targets = unitId <= 0 ? List.of() : envDeMapper.selectList(
                Wrappers.<EnvDePO>lambdaQuery()
                    .eq(EnvDePO::getUnitId, unitId)
                    .eq(EnvDePO::getEnvKind, EnvKind.TEST.name())
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
        String commit = trimToNull(commitSha);
        if (commit == null) {
            return false;
        }
        return instanceMapper.update(null, Wrappers.<PipelineInstancePO>lambdaUpdate()
            .eq(PipelineInstancePO::getId, instanceId)
            .set(PipelineInstancePO::getCommitSha,
                truncate(commit, MAX_COMMIT_SHA_LENGTH))) == 1;
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
        String commit = trimToNull(commitSha);
        LambdaUpdateWrapper<PipelineInstancePO> update =
            Wrappers.<PipelineInstancePO>lambdaUpdate()
                .eq(PipelineInstancePO::getId, instanceId);
        if (digest != null) {
            update.set(PipelineInstancePO::getImageDigest, truncate(digest, MAX_DIGEST_LENGTH));
        }
        // Explicitly write the commit, including NULL: the engine reported "no commit" and a
        // stale value must not survive as if it belonged to this image.
        update.set(PipelineInstancePO::getCommitSha,
            commit == null ? null : truncate(commit, MAX_COMMIT_SHA_LENGTH));
        return instanceMapper.update(null, update) == 1;
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