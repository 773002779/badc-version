package com.sf.release.domain.pipeline;

import com.sf.release.domain.resource.EnvDe;
import com.sf.release.domain.resource.EnvKind;
import com.sf.release.domain.resource.UnitType;
import com.sf.release.domain.task.ReleaseItem;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Builds the pre-generated pipeline of a 发布单 without touching the database (tech design
 * §5.1). The factory is pure domain: the caller (application service) persists the returned
 * aggregate in one transaction, which is exactly the aggregate consistency boundary
 * ("实例创建时预生成全部节点与任务", §3.1).
 *
 * <p><strong>Online shape (4 nodes, fixed order).</strong>
 * <ol>
 *   <li>{@code BUILD}: {@code CODE_CHECK → PKG_BUILD → IMG_BUILD};</li>
 *   <li>{@code TEST_DEPLOY}: one {@code DEPLOY} per TEST DE, no batch (batch 0);</li>
 *   <li>{@code VERIFY}: one {@code CHECK} per {@link CheckType} — the seven plan check types,
 *       because there is no check-catalogue table yet; {@code targetId} is the check-type code;</li>
 *   <li>{@code PROD_DEPLOY}: one {@code DEPLOY} per PROD/DR/BLUE DE, carrying the DE's
 *       {@code deploy_batch_no}; tasks and DEs are ordered by batch then id so the serial
 *       deployment is deterministic (C14).</li>
 * </ol>
 *
 * <p>The daily pipeline and the second-party library pipeline are later tasks; the online shape
 * is exposed through {@link #createOnline} and {@link #createRerun}.
 */
public final class PipelineFactory {

    private static final DateTimeFormatter DATE =
        DateTimeFormatter.ofPattern("yyyyMMdd", Locale.ROOT).withZone(ZoneOffset.UTC);

    private static final int NO_BATCH = 0;

    private PipelineFactory() {
    }

    /**
     * Pre-generates an online instance for {@code order}. The instance is not persisted and
     * has no database ids yet; its {@code nodes} carry the tasks of every node.
     */
    public static PipelineInstance createOnline(ReleaseOrder order, ReleaseItem item,
                                                List<EnvDe> envDes) {
        Objects.requireNonNull(order, "order must not be null");
        Objects.requireNonNull(item, "item must not be null");
        if (item.type() != UnitType.UNIT) {
            // The application service rejects LIB items before calling the factory; this guard
            // keeps the online factory from silently building the wrong pipeline.
            throw new IllegalArgumentException(
                "the online pipeline requires a UNIT item, got " + item.type());
        }
        List<EnvDe> des = envDes == null ? List.of() : List.copyOf(envDes);

        Instant now = Instant.now();
        Comparator<EnvDe> deOrder = Comparator
            .comparingInt(EnvDe::deployBatchNo)
            .thenComparing(de -> de.id() == null ? 0L : de.id());

        List<EnvDe> testDes = des.stream()
            .filter(de -> de.envKind() == EnvKind.TEST)
            .sorted(deOrder)
            .toList();
        List<EnvDe> prodDes = des.stream()
            .filter(PipelineFactory::isProductionFamily)
            .sorted(deOrder)
            .toList();

        List<PipelineNode> nodes = new ArrayList<>(4);
        nodes.add(PipelineNode.pending(NodeType.BUILD, 0, List.of(
            NodeTask.pending(NodeType.BUILD, TaskType.CODE_CHECK, NO_BATCH, 0),
            NodeTask.pending(NodeType.BUILD, TaskType.PKG_BUILD, NO_BATCH, 0),
            NodeTask.pending(NodeType.BUILD, TaskType.IMG_BUILD, NO_BATCH, 0))));

        List<NodeTask> testTasks = new ArrayList<>(testDes.size());
        for (EnvDe de : testDes) {
            testTasks.add(NodeTask.pending(NodeType.TEST_DEPLOY, TaskType.DEPLOY, NO_BATCH,
                targetId(de)));
        }
        nodes.add(PipelineNode.pending(NodeType.TEST_DEPLOY, 1, testTasks));

        List<NodeTask> checkTasks = new ArrayList<>(CheckType.values().length);
        for (CheckType checkType : CheckType.values()) {
            checkTasks.add(NodeTask.pending(NodeType.VERIFY, TaskType.CHECK, NO_BATCH,
                checkType.code()));
        }
        nodes.add(PipelineNode.pending(NodeType.VERIFY, 2, checkTasks));

        List<NodeTask> prodTasks = new ArrayList<>(prodDes.size());
        for (EnvDe de : prodDes) {
            prodTasks.add(NodeTask.pending(NodeType.PROD_DEPLOY, TaskType.DEPLOY,
                de.deployBatchNo(), targetId(de)));
        }
        nodes.add(PipelineNode.pending(NodeType.PROD_DEPLOY, 3, prodTasks));

        return new PipelineInstance(null, order.id(), newPipelineNo(now),
            ExecType.ONLINE, TriggerType.INITIAL, NodeType.BUILD, InstanceStatus.PENDING,
            null, null, null, null, now, nodes);
    }

    /**
     * Pre-generates a rerun instance for {@code order} (tech design §5.3). The graph is the same
     * online graph; the instance starts at {@code fromNode} and the nodes before it are marked
     * {@code SKIPPED} together with their tasks, because a rerun from a failed node reuses the
     * previous execution's work instead of repeating it. The caller passes the previous image
     * digest and commit for a from-node rerun (they are the input of the test/production
     * deploy), and {@code null} for a full rerun. When the resume point is {@code PROD_DEPLOY},
     * the previous execution's successful production batches are carried over as {@code SKIPPED}
     * tasks, so already deployed batches are not deployed again (requirements §4.5/J17); only the
     * failed batch and the later batches stay {@code PENDING}.
     */
    public static PipelineInstance createRerun(ReleaseOrder order, ReleaseItem item,
                                               List<EnvDe> envDes, TriggerType triggerType,
                                               NodeType fromNode, String imageDigest,
                                               String commitSha, PipelineInstance previous) {
        Objects.requireNonNull(fromNode, "fromNode must not be null");
        PipelineInstance base = createOnline(order, item, envDes);
        PipelineNode start = base.node(fromNode);
        if (start == null) {
            throw new IllegalArgumentException(
                "the online pipeline has no node " + fromNode);
        }
        Set<Integer> completedBatches = fromNode == NodeType.PROD_DEPLOY && previous != null
            ? completedProductionBatches(previous) : Set.of();
        List<PipelineNode> nodes = new ArrayList<>(base.nodes().size());
        for (PipelineNode node : base.nodes()) {
            if (node.seq() < start.seq()) {
                nodes.add(skipped(node, fromNode, base.createdAt()));
            } else if (node.nodeType() == NodeType.PROD_DEPLOY && !completedBatches.isEmpty()) {
                nodes.add(skipCompletedBatches(node, completedBatches, base.createdAt()));
            } else {
                nodes.add(node);
            }
        }
        return new PipelineInstance(null, base.orderId(), base.pipelineNo(), ExecType.ONLINE,
            triggerType, fromNode, InstanceStatus.PENDING, imageDigest, commitSha,
            null, null, base.createdAt(), nodes);
    }

    /**
     * A pre-generated node the rerun does not execute. Its tasks are {@code SKIPPED} as well so
     * the aggregate never shows an earlier node as still waiting to run.
     */
    private static PipelineNode skipped(PipelineNode node, NodeType fromNode, Instant now) {
        List<NodeTask> tasks = node.tasks().stream()
            .map(task -> skippedTask(task, "重新发布从 " + fromNode + " 开始，跳过该节点", now))
            .toList();
        return new PipelineNode(null, null, node.nodeType(), node.seq(), NodeStatus.SKIPPED,
            null, now, tasks);
    }

    /** The node stays current; only the batches that already succeeded are marked skipped. */
    private static PipelineNode skipCompletedBatches(PipelineNode node,
                                                      Set<Integer> completedBatches,
                                                      Instant now) {
        List<NodeTask> tasks = node.tasks().stream()
            .map(task -> completedBatches.contains(task.batchNo())
                ? skippedTask(task, "上一轮该批次已成功，重新发布不重复部署", now)
                : task)
            .toList();
        return new PipelineNode(node.id(), node.instanceId(), node.nodeType(), node.seq(),
            node.status(), node.startedAt(), node.finishedAt(), tasks);
    }

    private static NodeTask skippedTask(NodeTask task, String summary, Instant now) {
        return new NodeTask(null, null, task.nodeType(), task.taskType(), task.batchNo(),
            task.targetId(), NodeTaskStatus.SKIPPED, 0, null, null, summary, null, null, now,
            null);
    }

    /**
     * Production batches whose latest attempt of every deploy task is {@code SUCCESS} (a latest
     * {@code SKIPPED} is neutral, mirroring the orchestrator's completion fold). A batch with a
     * failed/incomplete latest task is not returned, so the whole batch is retried.
     */
    private static Set<Integer> completedProductionBatches(PipelineInstance previous) {
        PipelineNode node = previous.node(NodeType.PROD_DEPLOY);
        if (node == null) {
            return Set.of();
        }
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
        Set<Integer> succeeded = new HashSet<>();
        Set<Integer> notSucceeded = new HashSet<>();
        for (NodeTask task : latest.values()) {
            if (task.status() == NodeTaskStatus.SUCCESS) {
                succeeded.add(task.batchNo());
            } else if (task.status() != NodeTaskStatus.SKIPPED) {
                notSucceeded.add(task.batchNo());
            }
        }
        succeeded.removeAll(notSucceeded);
        return succeeded;
    }

    /** Latest-attempt identity of one production deploy task. */
    private record DeployKey(int batchNo, long targetId) {
    }

    /** {@code PROD}, {@code DR} and {@code BLUE} DEs are deployed by the PROD_DEPLOY node. */
    private static boolean isProductionFamily(EnvDe de) {
        return de.envKind() == EnvKind.PROD
            || de.envKind() == EnvKind.DR
            || de.envKind() == EnvKind.BLUE;
    }

    private static long targetId(EnvDe de) {
        return de.id() == null ? 0L : de.id();
    }

    /**
     * Display number of a pipeline instance, for example {@code PL-20261121-1A2B3C}. It is a
     * business key ({@code pipeline_instance.uk_pipeline_no}), not the database identity.
     */
    private static String newPipelineNo(Instant now) {
        String suffix = UUID.randomUUID().toString().replace("-", "")
            .substring(0, 6).toUpperCase(Locale.ROOT);
        return "PL-" + DATE.format(now) + "-" + suffix;
    }
}