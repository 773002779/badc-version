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
 *   <li>{@code VERIFY}: one {@code CHECK} per enabled {@link CheckType} — by default the seven
 *       plan check types — because there is no check-catalogue table yet; {@code targetId} is the
 *       check-type code (P6-B §2.1: a disabled check produces no task);</li>
 *   <li>{@code PROD_DEPLOY}: one {@code DEPLOY} per PROD/DR/BLUE DE, carrying the DE's
 *       {@code deploy_batch_no}; tasks and DEs are ordered by batch then id so the serial
 *       deployment is deterministic (C14).</li>
 * </ol>
 *
 * <p>The daily pipeline ({@link #createDaily}) is a two-node simplification of the online one;
 * the second-party library pipeline is {@link #createLib} (P6-A design §1/§3), a three-node
 * graph without image build, test deploy, verification or production deploy.
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
     *
     * <p>This overload keeps the original contract (every {@link CheckType} enabled) for callers
     * that have no settings context; the publish use case passes the platform's enabled set to
     * {@link #createOnline(ReleaseOrder, ReleaseItem, List, Set)}.
     */
    public static PipelineInstance createOnline(ReleaseOrder order, ReleaseItem item,
                                                List<EnvDe> envDes) {
        return createOnline(order, item, envDes, Set.of(CheckType.values()), BatchOrder.FORWARD);
    }

    /**
     * The pre-P6-C overload: every check and the forward batch order. Kept for callers (and tests)
     * that have no settings context.
     */
    public static PipelineInstance createOnline(ReleaseOrder order, ReleaseItem item,
                                                List<EnvDe> envDes, Set<CheckType> enabledChecks) {
        return createOnline(order, item, envDes, enabledChecks, BatchOrder.FORWARD);
    }

    /**
     * Pre-generates an online instance whose {@code VERIFY} node carries one {@code CHECK} task per
     * <em>enabled</em> check type (P6-B design §2.1), and whose production tasks carry the unit's
     * batch order (P6-C design §2).
     *
     * <p>A disabled check produces no new task: the switch is a configuration of what a new
     * pipeline should run, not a deletion of history. Existing instances keep their rows; the gate
     * ignores the rows of a disabled check when it evaluates (see {@code GateService}).
     *
     * <p><strong>Reverse batches.</strong> With {@link BatchOrder#REVERSE} a production task's batch
     * number becomes {@code maxBatch + 1 - originalBatch} (the rollback transformation), so the
     * handler's lowest-batch-first rule runs the highest original batch first. The test-deploy
     * node has no batch (0) and is not affected, and nothing about an existing instance changes.
     *
     * @param enabledChecks the enabled check types; {@code null} means "all", an empty set is a
     *                      programming error because the settings service refuses to save zero
     *                      checks (the design routes "run nothing" through the explicit waiver path)
     * @param batchOrder    the production batch order; {@code null} means {@link BatchOrder#FORWARD}
     */
    public static PipelineInstance createOnline(ReleaseOrder order, ReleaseItem item,
                                                List<EnvDe> envDes, Set<CheckType> enabledChecks,
                                                BatchOrder batchOrder) {
        Objects.requireNonNull(order, "order must not be null");
        Objects.requireNonNull(item, "item must not be null");
        if (item.type() != UnitType.UNIT) {
            // The application service rejects LIB items before calling the factory; this guard
            // keeps the online factory from silently building the wrong pipeline.
            throw new IllegalArgumentException(
                "the online pipeline requires a UNIT item, got " + item.type());
        }
        Set<CheckType> enabled = enabledChecks == null
            ? Set.of(CheckType.values()) : Set.copyOf(enabledChecks);
        if (enabled.isEmpty()) {
            throw new IllegalArgumentException(
                "the online pipeline requires at least one enabled check type");
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
        BatchOrder batchOrderOrDefault =
            batchOrder == null ? BatchOrder.FORWARD : batchOrder;
        int maxBatch = des.stream()
            .filter(PipelineFactory::isProductionFamily)
            .mapToInt(EnvDe::deployBatchNo)
            .max().orElse(0);
        List<EnvDe> prodDes = des.stream()
            .filter(PipelineFactory::isProductionFamily)
            // Reverse numbering runs the highest original batch first; the pre-generation order
            // follows the effective batch number so the persisted rows are already deterministic.
            .sorted(batchOrderOrDefault == BatchOrder.REVERSE
                ? Comparator.comparingInt((EnvDe de) -> reversedBatch(de, maxBatch))
                    .thenComparing(de -> de.id() == null ? 0L : de.id())
                : deOrder)
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

        List<NodeTask> checkTasks = new ArrayList<>(enabled.size());
        for (CheckType checkType : CheckType.values()) {
            if (!enabled.contains(checkType)) {
                continue;
            }
            checkTasks.add(NodeTask.pending(NodeType.VERIFY, TaskType.CHECK, NO_BATCH,
                checkType.code()));
        }
        nodes.add(PipelineNode.pending(NodeType.VERIFY, 2, checkTasks));

        List<NodeTask> prodTasks = new ArrayList<>(prodDes.size());
        for (EnvDe de : prodDes) {
            prodTasks.add(NodeTask.pending(NodeType.PROD_DEPLOY, TaskType.DEPLOY,
                batchOrderOrDefault == BatchOrder.REVERSE
                    ? reversedBatch(de, maxBatch) : de.deployBatchNo(),
                targetId(de)));
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
        return createRerun(order, item, envDes, triggerType, fromNode, imageDigest, commitSha,
            previous, null, BatchOrder.FORWARD);
    }

    /**
     * The enabled-check-aware rerun (P6-B design §2.1): a rerun is a new instance, so it carries
     * only the currently enabled {@code CHECK} tasks. The previous execution's completed batches
     * are still carried over unchanged.
     */
    public static PipelineInstance createRerun(ReleaseOrder order, ReleaseItem item,
                                               List<EnvDe> envDes, TriggerType triggerType,
                                               NodeType fromNode, String imageDigest,
                                               String commitSha, PipelineInstance previous,
                                               Set<CheckType> enabledChecks) {
        return createRerun(order, item, envDes, triggerType, fromNode, imageDigest, commitSha,
            previous, enabledChecks, BatchOrder.FORWARD);
    }

    /**
     * The P6-C-aware rerun (P6-C design §2): the new instance carries the unit's batch order, so a
     * rerun resumes in the same order as a fresh publish. The previous execution's completed
     * production batches are carried over <em>by DE target</em>, not by batch number: reversing is
     * a renumbering, and a batch number read from the previous (possibly differently numbered)
     * instance would point at the wrong batch.
     */
    public static PipelineInstance createRerun(ReleaseOrder order, ReleaseItem item,
                                               List<EnvDe> envDes, TriggerType triggerType,
                                               NodeType fromNode, String imageDigest,
                                               String commitSha, PipelineInstance previous,
                                               Set<CheckType> enabledChecks,
                                               BatchOrder batchOrder) {
        Objects.requireNonNull(fromNode, "fromNode must not be null");
        PipelineInstance base = createOnline(order, item, envDes, enabledChecks, batchOrder);
        PipelineNode start = base.node(fromNode);
        if (start == null) {
            throw new IllegalArgumentException(
                "the online pipeline has no node " + fromNode);
        }
        Set<Long> completedTargets = fromNode == NodeType.PROD_DEPLOY && previous != null
            ? completedProductionTargets(previous) : Set.of();
        List<PipelineNode> nodes = new ArrayList<>(base.nodes().size());
        for (PipelineNode node : base.nodes()) {
            if (node.seq() < start.seq()) {
                nodes.add(skipped(node, fromNode, base.createdAt()));
            } else if (node.nodeType() == NodeType.PROD_DEPLOY && !completedTargets.isEmpty()) {
                nodes.add(skipCompletedBatches(node, completedTargets, base.createdAt()));
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
     * Pre-generates a second-party library instance for {@code order} (P6-A design §1/§3): the
     * three-node graph {@code BUILD → LIB_SECURITY → LIB_PUBLISH}.
     *
     * <ol>
     *   <li>{@code BUILD} (seq 0): {@code CODE_CHECK → PKG_BUILD}. There is deliberately
     *       <strong>no IMG_BUILD</strong>: a library produces an artifact, not an image, and the
     *       existing {@code BuildNodeHandler} simply finds no successor after {@code PKG_BUILD}.</li>
     *   <li>{@code LIB_SECURITY} (seq 1): no {@code node_task} rows. Its three checks
     *       ({@code SONAR}, {@code COMPONENT_VULN}, {@code VERSION_DEFECT}) live in
     *       {@code gate_check}, exactly like the {@code VERIFY} node's checks, and the handler
     *       decides the node through the gate. Pre-generating {@code CHECK} rows here would never
     *       be mirrored (the check service mirrors onto {@code VERIFY}) and would only look like
     *       unfinished work.</li>
     *   <li>{@code LIB_PUBLISH} (seq 2): one {@code LIB_PUBLISH} task, the artifact upload
     *       driven by the engine's publish endpoint.</li>
     * </ol>
     *
     * <p><strong>Identity.</strong> A LIB execution is still an online-style release: it uses
     * the real 发布单 ({@code order.id}) and therefore the same release order, unit lock and
     * audit as a deployment, while its {@code exec_type} is {@link ExecType#LIB}. The instance
     * starts {@code PENDING} at {@code BUILD}, so the caller commits it and starts it afterwards
     * (or the reconcile pass enters it). There is no DE configuration to pass: a library has no
     * environment, so the parameter does not exist rather than being silently ignored.
     */
    public static PipelineInstance createLib(ReleaseOrder order, ReleaseItem item, Instant now) {
        Objects.requireNonNull(order, "order must not be null");
        Objects.requireNonNull(item, "item must not be null");
        if (item.type() != UnitType.LIB) {
            throw new IllegalArgumentException(
                "the library pipeline requires a LIB item, got " + item.type());
        }
        Instant when = now == null ? Instant.now() : now;
        List<PipelineNode> nodes = List.of(
            PipelineNode.pending(NodeType.BUILD, 0, List.of(
                NodeTask.pending(NodeType.BUILD, TaskType.CODE_CHECK, NO_BATCH, 0),
                NodeTask.pending(NodeType.BUILD, TaskType.PKG_BUILD, NO_BATCH, 0))),
            PipelineNode.pending(NodeType.LIB_SECURITY, 1, List.of()),
            PipelineNode.pending(NodeType.LIB_PUBLISH, 2, List.of(
                NodeTask.pending(NodeType.LIB_PUBLISH, TaskType.LIB_PUBLISH, NO_BATCH, 0))));
        return new PipelineInstance(null, order.id(), newPipelineNo(when), ExecType.LIB,
            TriggerType.INITIAL, NodeType.BUILD, InstanceStatus.PENDING, null, null, null, null,
            when, null, nodes);
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

    /** The node stays current; only the DEs of batches that already succeeded are marked skipped. */
    private static PipelineNode skipCompletedBatches(PipelineNode node,
                                                      Set<Long> completedTargets,
                                                      Instant now) {
        List<NodeTask> tasks = node.tasks().stream()
            .map(task -> completedTargets.contains(task.targetId())
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
     * The DE targets whose production batch already succeeded: every latest attempt in the batch
     * is {@code SUCCESS} (a latest {@code SKIPPED} is neutral, mirroring the orchestrator's
     * completion fold). A batch with a failed/incomplete latest task is not returned, so the whole
     * batch is retried.
     *
     * <p>The result is deliberately expressed as <em>target ids</em>, not batch numbers: the new
     * instance may renumber its batches (P6-C {@code REVERSE}) while the DE identity does not
     * change, so carrying the previous batch numbers over would skip the wrong tasks.
     */
    private static Set<Long> completedProductionTargets(PipelineInstance previous) {
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
        Set<Long> targets = new HashSet<>();
        for (NodeTask task : latest.values()) {
            if (succeeded.contains(task.batchNo())) {
                targets.add(task.targetId());
            }
        }
        return targets;
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

    /**
     * The reverse batch number of one production DE: {@code maxBatch + 1 - original}. It is the
     * same transformation {@link #createRollback} uses, applied here to a <em>new</em> online
     * instance only (P6-C design §2), so the highest original batch runs first.
     */
    private static int reversedBatch(EnvDe de, int maxBatch) {
        return maxBatch + 1 - de.deployBatchNo();
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