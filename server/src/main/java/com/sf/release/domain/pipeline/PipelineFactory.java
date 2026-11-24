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
 * <p>The daily pipeline ({@link #createDaily}) is a two-node simplification of the online one
 * and the second-party library pipeline is a later task; the online shape is exposed through
 * {@link #createOnline} and {@link #createRerun}.
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
            null, null, null, null, now, null, nodes);
    }

    /**
     * Pre-generates a production rollback instance (rollback design section 3, P4-A R2). A
     * rollback is a stop-loss action: it does not build or test anything, so the instance has
     * exactly one {@code PROD_DEPLOY} node (seq 0) carrying one {@code DEPLOY} task per
     * production-family DE, at the <em>target</em> image.
     *
     * <p><strong>Reversed batches.</strong> Each task's batch number becomes
     * {@code maxBatch + 1 - originalBatch}. The production handler always triggers the lowest
     * unfinished batch first, so the highest original batch runs first: C14's forward order
     * (primary production, primary DR, blue-green production, blue-green DR) is rolled back in
     * reverse. A production DE without a batch number (0) keeps every task in the one reversed
     * batch {@code maxBatch + 1}; with a single batch that degrades to batch 1.
     *
     * <p>The instance starts {@code PENDING} at {@code PROD_DEPLOY} with the target digest, so the
     * caller can commit it in one transaction and start it afterwards. The source commit is not
     * copied: the rollback deploys an existing image, and the target image's commit is not part of
     * the version list.
     */
    public static PipelineInstance createRollback(ReleaseOrder order, ReleaseItem item,
                                                  List<EnvDe> envDes, String targetDigest,
                                                  Instant now) {
        Objects.requireNonNull(order, "order must not be null");
        Objects.requireNonNull(item, "item must not be null");
        if (item.type() != UnitType.UNIT) {
            throw new IllegalArgumentException(
                "the rollback pipeline requires a UNIT item, got " + item.type());
        }
        String digest = targetDigest == null ? null : targetDigest.trim();
        if (digest == null || digest.isEmpty()) {
            throw new IllegalArgumentException("the rollback pipeline requires a target image digest");
        }
        Instant when = now == null ? Instant.now() : now;
        List<EnvDe> prodDes = (envDes == null ? List.<EnvDe>of() : envDes).stream()
            .filter(PipelineFactory::isProductionFamily)
            // Trigger order is the highest original batch first, then the DE id.
            .sorted(Comparator.comparingInt(EnvDe::deployBatchNo).reversed()
                .thenComparing(de -> de.id() == null ? 0L : de.id()))
            .toList();
        int maxBatch = prodDes.stream().mapToInt(EnvDe::deployBatchNo).max().orElse(0);
        List<NodeTask> tasks = new ArrayList<>(prodDes.size());
        for (EnvDe de : prodDes) {
            int reversed = maxBatch + 1 - de.deployBatchNo();
            tasks.add(NodeTask.pending(NodeType.PROD_DEPLOY, TaskType.DEPLOY, reversed,
                targetId(de)));
        }
        List<PipelineNode> nodes = List.of(
            PipelineNode.pending(NodeType.PROD_DEPLOY, 0, tasks));
        return new PipelineInstance(null, order.id(), newPipelineNo(when), ExecType.ONLINE,
            TriggerType.ROLLBACK, NodeType.PROD_DEPLOY, InstanceStatus.PENDING, digest, null,
            null, null, when, null, nodes);
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
            null, null, base.createdAt(), null, nodes);
    }

    /**
     * Pre-generates a daily release instance (P5-A design §3): the simplified
     * {@code BUILD → DAILY_DEPLOY} pipeline. It is not a 发布单, so the instance has
     * {@code orderId = 0}, {@link ExecType#DAILY}, no unit lock and no item state change; it
     * names its owner through {@code itemId/unitId/requestedBy}.
     *
     * <p><strong>Nodes.</strong>
     * <ol>
     *   <li>{@code BUILD} (seq 0): the same {@code CODE_CHECK → PKG_BUILD → IMG_BUILD} chain as
     *       an online release, because the daily pipeline reuses the unit's one build task
     *       (J4: the daily image never becomes a release image, but it is built by the same
     *       chain);</li>
     *   <li>{@code DAILY_DEPLOY} (seq 1): one {@code DEPLOY} task per target DE,
     *       {@code batch_no = deploy_batch_no} (display only — daily deploys are not batched)
     *       and {@code target_id = env_de.id}.</li>
     * </ol>
     *
     * <p><strong>Target DE selection.</strong> The unit's {@code env_kind = DAILY} DEs, in batch
     * then id order; when the unit has none, its {@code TEST} DEs (C24 "reuse the test deploy
     * task, or a dedicated daily deploy task"). The caller validates the empty case with a
     * business error <em>before</em> creating anything, so the {@link IllegalArgumentException}
     * here is a programming-error guard, not the user-facing 40001.
     */
    public static PipelineInstance createDaily(ReleaseItem item, long unitId, List<EnvDe> envDes,
                                               String requestedBy, Instant now) {
        Objects.requireNonNull(item, "item must not be null");
        Objects.requireNonNull(item.id(), "a daily pipeline requires a persisted item");
        if (item.type() != UnitType.UNIT) {
            // The application service rejects LIB items before calling the factory; this guard
            // keeps the daily factory from silently building a pipeline for a library.
            throw new IllegalArgumentException(
                "the daily pipeline requires a UNIT item, got " + item.type());
        }
        Instant when = now == null ? Instant.now() : now;
        Comparator<EnvDe> deOrder = Comparator
            .comparingInt(EnvDe::deployBatchNo)
            .thenComparing(de -> de.id() == null ? 0L : de.id());
        List<EnvDe> des = envDes == null ? List.<EnvDe>of() : List.copyOf(envDes);
        List<EnvDe> dailyDes = des.stream()
            .filter(de -> de.envKind() == EnvKind.DAILY)
            .sorted(deOrder)
            .toList();
        List<EnvDe> targets = dailyDes.isEmpty()
            ? des.stream().filter(de -> de.envKind() == EnvKind.TEST).sorted(deOrder).toList()
            : dailyDes;
        if (targets.isEmpty()) {
            throw new IllegalArgumentException(
                "the daily pipeline requires a DAILY or TEST DE for unit " + unitId);
        }

        List<PipelineNode> nodes = new ArrayList<>(2);
        nodes.add(PipelineNode.pending(NodeType.BUILD, 0, List.of(
            NodeTask.pending(NodeType.BUILD, TaskType.CODE_CHECK, NO_BATCH, 0),
            NodeTask.pending(NodeType.BUILD, TaskType.PKG_BUILD, NO_BATCH, 0),
            NodeTask.pending(NodeType.BUILD, TaskType.IMG_BUILD, NO_BATCH, 0))));

        List<NodeTask> deployTasks = new ArrayList<>(targets.size());
        for (EnvDe de : targets) {
            // The batch number is display-only here: the daily handler triggers every target in
            // one go and never waits for a batch boundary (P5-A design §4/§8).
            deployTasks.add(NodeTask.pending(NodeType.DAILY_DEPLOY, TaskType.DEPLOY,
                de.deployBatchNo(), targetId(de)));
        }
        nodes.add(PipelineNode.pending(NodeType.DAILY_DEPLOY, 1, deployTasks));

        return new PipelineInstance(null, 0L, newPipelineNo(when), ExecType.DAILY,
            TriggerType.INITIAL, NodeType.BUILD, InstanceStatus.PENDING, null, null, null, null,
            when, null, nodes, item.id(), unitId, requestedBy);
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