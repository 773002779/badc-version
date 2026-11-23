package com.sf.release.web.dto;

import com.sf.release.application.ReleaseQueryService;
import com.sf.release.application.ReleaseQueryService.NodeView;
import com.sf.release.application.ReleaseQueryService.OrderView;
import com.sf.release.application.ReleaseQueryService.ReleaseRecord;
import com.sf.release.application.ReleaseQueryService.TaskView;
import com.sf.release.application.ReleaseRecordsService;

import java.time.Instant;
import java.util.List;

/**
 * Response bodies of {@code /api/v1/releases}: the 发布内容 detail/history (Task 16) and the global
 * 发布记录 page (GAP-10, P4-A Task 2).
 *
 * <p>The web layer owns the wire shape — field names, null semantics (a {@code null} field is
 * part of the contract and must stay present in the JSON) and ordering — while the application
 * read model stays HTTP-agnostic. That is why the records mirror
 * {@link ReleaseQueryService}'s and {@link ReleaseRecordsService}'s views and are mapped here
 * instead of being serialized directly: the use case never depends on the web package (the
 * dependency direction stays {@code web -> application}).
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
 *       column;</li>
 *   <li>on a global-record row, {@code pipelineNo}/{@code pipelineStatus} are null for an order
 *       that was never executed and {@code executedBy} is null for a historical order (V25).</li>
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
            view.statusReason(),
            view.gateStatus(),
            view.testStatus(),
            view.triggerType(),
            view.rerunFromNode(),
            view.fullRerun(),
            view.commitSha(),
            view.imageDigest(),
            orderOf(view.order()),
            view.nodes().stream().map(ReleaseDtos::nodeOf).toList(),
            view.itemId(),
            view.unitId());
    }

    /** Maps the instance history of one 发布单, preserving the newest-first order. */
    public static List<ReleaseRecordView> recordsOf(List<ReleaseRecord> records) {
        return records.stream()
            .map(record -> new ReleaseRecordView(record.pipelineNo(), record.triggerType(),
                record.status(), record.startAt(), record.endAt(), record.operator()))
            .toList();
    }

    /**
     * Maps one page of the global 发布记录 list (GAP-10). Each row carries the machine status value
     * plus its Chinese label, and the newest pipeline of the order ({@code null} when none).
     */
    public static ReleaseRecordsPageResponse recordsPageOf(
            ReleaseRecordsService.PageResult page) {
        return new ReleaseRecordsPageResponse(
            page.total(),
            page.pageNo(),
            page.pageSize(),
            page.records().stream().map(ReleaseDtos::recordRowOf).toList());
    }

    private static ReleaseRecordRowView recordRowOf(
            ReleaseRecordsService.ReleaseRecordView view) {
        return new ReleaseRecordRowView(
            view.publishNo(),
            view.itemId(),
            view.crid(),
            view.version(),
            view.subsystemId(),
            view.subsystemName(),
            view.unitId(),
            view.unitName(),
            view.orderStatus(),
            view.orderStatusLabel(),
            view.itemStatus(),
            view.itemStatusLabel(),
            view.plannedReleaseTime(),
            view.createdAt(),
            view.executedBy(),
            view.pipelineNo(),
            view.pipelineStatus());
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
        String statusReason,
        String gateStatus,
        String testStatus,
        String triggerType,
        String rerunFromNode,
        Boolean fullRerun,
        String commitSha,
        String imageDigest,
        ReleaseOrderView order,
        List<ReleaseNodeView> nodes,
        Long itemId,
        Long unitId) {
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

    /**
     * One page of the global 发布记录 list (GAP-10): {@code total} is the full match count, the
     * page number/size echo the request and {@code records} is the newest-first page.
     */
    public record ReleaseRecordsPageResponse(
        long total,
        long pageNo,
        long pageSize,
        List<ReleaseRecordRowView> records) {

        public ReleaseRecordsPageResponse {
            records = records == null ? List.of() : List.copyOf(records);
        }
    }

    /**
     * One release order row of the global list. Statuses are the stable machine value plus the
     * Chinese label; {@code pipelineNo}/{@code pipelineStatus} describe the newest execution of the
     * order and are {@code null} before the first execution.
     */
    public record ReleaseRecordRowView(
        String publishNo,
        Long itemId,
        String crid,
        String version,
        Long subsystemId,
        String subsystemName,
        Long unitId,
        String unitName,
        String orderStatus,
        String orderStatusLabel,
        String itemStatus,
        String itemStatusLabel,
        Instant plannedReleaseTime,
        Instant createdAt,
        String executedBy,
        String pipelineNo,
        String pipelineStatus) {
    }
}
