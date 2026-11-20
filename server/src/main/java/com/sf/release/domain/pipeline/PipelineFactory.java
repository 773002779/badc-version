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
import java.util.List;
import java.util.Locale;
import java.util.Objects;
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
 * <p>The daily pipeline, the second-party library pipeline and rerun plans are later tasks;
 * this factory deliberately exposes only {@code createOnline}.
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