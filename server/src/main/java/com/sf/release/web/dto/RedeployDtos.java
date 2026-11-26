package com.sf.release.web.dto;

import com.sf.release.application.SingleDeRedeployService.RedeployResult;
import com.sf.release.application.TaskLogService.LogView;

import java.util.List;

/**
 * Response bodies of the P6-G task actions on the 发布内容 page:
 *
 * <ul>
 *   <li>{@code POST /api/v1/releases/{publishNo}/tasks/{taskId}/redeploy} — the new execution the
 *       redeploy created;</li>
 *   <li>{@code GET /api/v1/releases/{publishNo}/tasks/{taskId}/logs?offset=&limit=} — one page of
 *       the engine's raw log text.</li>
 * </ul>
 *
 * <p>The web layer owns the wire shape; the application services stay HTTP-agnostic. Every field
 * that is meaningful when absent stays present as {@code null}/empty rather than being dropped.
 */
public final class RedeployDtos {

    private RedeployDtos() {
    }

    /** Maps the redeploy read model, ordering the retried DE ids so the JSON is deterministic. */
    public static RedeployResponse of(RedeployResult view) {
        return new RedeployResponse(
            view.publishNo(),
            view.pipelineNo(),
            view.instanceId(),
            view.fromNode(),
            view.batchNo(),
            view.targetDeIds().stream().sorted().toList(),
            view.targetDeName());
    }

    /** Maps one log page; {@code content} is the raw engine body and is never re-encoded. */
    public static TaskLogResponse of(LogView view) {
        return new TaskLogResponse(view.content(), view.truncated(), view.offset(), view.limit());
    }

    /**
     * The new pipeline instance of a single-DE redeploy. {@code batchNo} is the retried production
     * batch and {@code targetDeIds} the whole batch (the failed DE plus its siblings), so the page
     * can say 「同批次一起重试」 without another read.
     */
    public record RedeployResponse(
        String publishNo,
        String pipelineNo,
        long instanceId,
        String fromNode,
        int batchNo,
        List<Long> targetDeIds,
        String targetDeName) {

        public RedeployResponse {
            targetDeIds = targetDeIds == null ? List.of() : List.copyOf(targetDeIds);
        }
    }

    /**
     * One page of log text. {@code truncated} means a full page was returned, so another page may
     * exist at {@code offset + limit}; {@code content} is the engine's text unchanged.
     */
    public record TaskLogResponse(
        String content,
        boolean truncated,
        int offset,
        int limit) {
    }
}