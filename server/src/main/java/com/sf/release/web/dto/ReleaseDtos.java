package com.sf.release.web.dto;

import com.sf.release.application.ReleaseQueryService;
import com.sf.release.application.ReleaseQueryService.NodeView;
import com.sf.release.application.ReleaseQueryService.OrderView;
import com.sf.release.application.ReleaseQueryService.ReleaseRecord;
import com.sf.release.application.ReleaseQueryService.TaskView;

import java.time.Instant;
import java.util.List;

/**
 * Response bodies of {@code /api/v1/releases} (Task 16).
 *
 * <p>The web layer owns the wire shape — field names, null semantics (a {@code null} field is
 * part of the contract and must stay present in the JSON) and ordering — while the application
 * read model stays HTTP-agnostic. That is why the records mirror
 * {@link ReleaseQueryService}'s views and are mapped here instead of being serialized directly:
 * the use case never depends on the web package (the dependency direction stays
 * {@code web -> application}).
 *
 * <p><strong>Null fields are meaningful.</strong>
 * <ul>
 *   <li>{@code gateStatus} is null in phase 1; the gate task fills the same field name later;</li>
 *   <li>{@code triggerType} is null only for an order without an execution;</li>
 *   <li>{@code rerunFromNode} and {@code fullRerun} are null for a first run or a rollback and
 *       are set for {@code RERUN_FROM_NODE} / {@code RERUN_FULL};</li>
 *   <li>{@code commitSha} / {@code imageDigest} are null until an execution builds or starts
 *       from an image (and stay null for a full rebuild until it reports one);</li>
 *   <li>{@code operator} is null in phase 1 because {@code pipeline_instance} has no creator
 *       column.</li>
 * </ul>
 */
public final class ReleaseDtos {

    private ReleaseDtos() {
    }

    /** Maps the read model of one 发布单 to its response body. */
    public static ReleaseDetail of(ReleaseQueryService.ReleaseDetail view) {
        return new ReleaseDetail(
            view.publishNo(),
            view.pipelineNo(),
            view.status(),
            view.gateStatus(),
            view.testStatus(),
            view.triggerType(),
            view.rerunFromNode(),
            view.fullRerun(),
            view.commitSha(),
            view.imageDigest(),
            orderOf(view.order()),
            view.nodes().stream().map(ReleaseDtos::nodeOf).toList(),
            view.itemId());
    }

    /** Maps the instance history of one 发布单, preserving the newest-first order. */
    public static List<ReleaseRecordView> recordsOf(List<ReleaseRecord> records) {
        return records.stream()
            .map(record -> new ReleaseRecordView(record.pipelineNo(), record.triggerType(),
                record.status(), record.startAt(), record.endAt(), record.operator()))
            .toList();
    }

    private static ReleaseOrderView orderOf(OrderView order) {
        return order == null ? null
            : new ReleaseOrderView(order.oaTicketNo(), order.plannedReleaseTime());
    }

    private static ReleaseNodeView nodeOf(NodeView node) {
        return new ReleaseNodeView(node.nodeType(), node.seqNo(), node.status(),
            node.startedAt(), node.finishedAt(),
            node.tasks().stream().map(ReleaseDtos::taskOf).toList());
    }

    private static ReleaseTaskView taskOf(TaskView task) {
        return new ReleaseTaskView(task.taskType(), task.batchNo(), task.targetId(),
            task.status(), task.durationMs(), task.resultSummary(), task.externalUrl());
    }

    /**
     * The aggregated view of one 发布单: the newest execution, the rerun hand-off fields, the
     * order's admission fields and every node with its tasks in pipeline order.
     */
    public record ReleaseDetail(
        String publishNo,
        String pipelineNo,
        String status,
        String gateStatus,
        String testStatus,
        String triggerType,
        String rerunFromNode,
        Boolean fullRerun,
        String commitSha,
        String imageDigest,
        ReleaseOrderView order,
        List<ReleaseNodeView> nodes,
        Long itemId) {
    }

    /** Admission fields of the 发布单. */
    public record ReleaseOrderView(String oaTicketNo, Instant plannedReleaseTime) {
    }

    /** One node of the pipeline, in pipeline order, with its tasks. */
    public record ReleaseNodeView(
        String nodeType,
        int seqNo,
        String status,
        Instant startedAt,
        Instant finishedAt,
        List<ReleaseTaskView> tasks) {
    }

    /**
     * One {@code node_task}: {@code batchNo} is the production batch (0 elsewhere),
     * {@code targetId} the DE id or the check-type code.
     */
    public record ReleaseTaskView(
        String taskType,
        int batchNo,
        long targetId,
        String status,
        Long durationMs,
        String resultSummary,
        String externalUrl) {
    }

    /** One execution in the history of a 发布单. */
    public record ReleaseRecordView(
        String pipelineNo,
        String triggerType,
        String status,
        Instant startAt,
        Instant endAt,
        String operator) {
    }
}