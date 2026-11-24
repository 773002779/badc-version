package com.sf.release.web.dto;

import com.sf.release.application.DailyReleaseService;
import com.sf.release.application.DailyReleaseService.DailyDetail;
import com.sf.release.application.DailyReleaseService.DailyNode;
import com.sf.release.application.DailyReleaseService.DailyPage;
import com.sf.release.application.DailyReleaseService.DailyRecord;
import com.sf.release.application.DailyReleaseService.DailyTask;
import com.sf.release.application.DailyReleaseService.StartResult;

import java.time.Instant;
import java.util.List;

/**
 * Response bodies of the daily release endpoints (P5-A design §5).
 *
 * <p>The web layer owns the wire shape — field names, null semantics and ordering — while the
 * application read model stays HTTP-agnostic ({@code web -> application} only). Every status and
 * node/task kind travels as its machine string, so this class imports no domain or infrastructure
 * enum (architecture rule, P5-A design §7 item 8).
 *
 * <p><strong>Null fields are meaningful.</strong>
 * <ul>
 *   <li>{@code requestedBy} is the caller who started it and is always present for a daily
 *       instance;</li>
 *   <li>{@code branch} is the 变更项's read-only source branch ({@code null} when the item row is
 *       gone);</li>
 *   <li>{@code commitSha} / {@code imageDigest} stay {@code null} until the daily build reports
 *       them (a failed or still-building instance has none);</li>
 *   <li>{@code startAt} / {@code endAt} are {@code null} before the pipeline starts / before it
 *       stops;</li>
 *   <li>{@code externalUrl} is the engine log link the task row carries, {@code null} until a
 *       deploy was triggered.</li>
 * </ul>
 */
public final class DailyDtos {

    private DailyDtos() {
    }

    /** The start response: {@code {pipelineNo, instanceId}} (design §5). */
    public static StartView of(StartResult result) {
        return new StartView(result.pipelineNo(), result.instanceId());
    }

    /** Maps one page of daily executions, preserving the newest-first order. */
    public static DailyPageView of(DailyPage page) {
        return new DailyPageView(page.total(), page.pageNo(), page.pageSize(),
            page.records().stream().map(DailyDtos::recordOf).toList());
    }

    /** Maps the daily detail with its nodes and tasks in pipeline order. */
    public static DailyDetailView of(DailyDetail detail) {
        return new DailyDetailView(
            detail.pipelineNo(),
            detail.status(),
            detail.requestedBy(),
            detail.branch(),
            detail.commitSha(),
            detail.createdAt(),
            detail.startAt(),
            detail.endAt(),
            detail.imageDigest(),
            detail.nodes().stream().map(DailyDtos::nodeOf).toList());
    }

    private static DailyRecordView recordOf(DailyRecord record) {
        return new DailyRecordView(record.pipelineNo(), record.status(), record.requestedBy(),
            record.branch(), record.commitSha(), record.createdAt(), record.startAt(),
            record.endAt(), record.imageDigest());
    }

    private static DailyNodeView nodeOf(DailyNode node) {
        return new DailyNodeView(node.nodeType(), node.status(),
            node.tasks().stream().map(DailyDtos::taskOf).toList());
    }

    private static DailyTaskView taskOf(DailyTask task) {
        return new DailyTaskView(task.taskType(), task.status(), task.externalUrl(),
            task.summary());
    }

    /** Start response: the new pipeline number and its instance id. */
    public record StartView(String pipelineNo, long instanceId) {
    }

    /**
     * One page of daily executions: {@code total} is the full match count, the page number/size
     * echo the request and {@code records} is the newest-first page.
     */
    public record DailyPageView(
        long total,
        int pageNo,
        int pageSize,
        List<DailyRecordView> records) {

        public DailyPageView {
            records = records == null ? List.of() : List.copyOf(records);
        }
    }

    /** One daily execution row: time, branch/commit, requester, result and image. */
    public record DailyRecordView(
        String pipelineNo,
        String status,
        String requestedBy,
        String branch,
        String commitSha,
        Instant createdAt,
        Instant startAt,
        Instant endAt,
        String imageDigest) {
    }

    /** The full daily instance: the row above plus every node with its tasks. */
    public record DailyDetailView(
        String pipelineNo,
        String status,
        String requestedBy,
        String branch,
        String commitSha,
        Instant createdAt,
        Instant startAt,
        Instant endAt,
        String imageDigest,
        List<DailyNodeView> nodes) {

        public DailyDetailView {
            nodes = nodes == null ? List.of() : List.copyOf(nodes);
        }
    }

    /** One node of the daily pipeline ({@code BUILD} seq 0, {@code DAILY_DEPLOY} seq 1). */
    public record DailyNodeView(String nodeType, String status, List<DailyTaskView> tasks) {

        public DailyNodeView {
            tasks = tasks == null ? List.of() : List.copyOf(tasks);
        }
    }

    /**
     * One task of a daily node. {@code externalUrl} is the engine log link the row stores, which
     * the page opens as the "日志链接" of the design (§6).
     */
    public record DailyTaskView(
        String taskType,
        String status,
        String externalUrl,
        String summary) {
    }
}