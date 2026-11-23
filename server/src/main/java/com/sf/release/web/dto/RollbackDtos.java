package com.sf.release.web.dto;

import com.sf.release.application.RollbackRecordView;
import com.sf.release.application.RollbackService;
import com.sf.release.application.RollbackVersionView;

import java.time.Instant;
import java.util.List;

/**
 * Request and response bodies of the 生产回滚 endpoints (rollback design section 4).
 *
 * <p>The web layer owns the wire shape; the application views stay HTTP-agnostic. Statuses and
 * environment kinds are sent as the stable machine value plus their Chinese label, so the page
 * renders the drawer without keeping its own copy of the vocabulary and the web package never
 * imports a domain enum.
 */
public final class RollbackDtos {

    private RollbackDtos() {
    }

    /** {@code POST /api/v1/rollbacks}. {@code unitId} may be absent only to be rejected as 40000. */
    public record ExecuteRollbackRequest(Long unitId, String targetDigest, String reason) {
    }

    /** {@code GET /api/v1/units/{id}/rollback-versions}. */
    public record VersionListResponse(VersionResponse current, List<VersionResponse> history,
                                      List<String> warnings) {

        public VersionListResponse {
            history = history == null ? List.of() : List.copyOf(history);
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
        }
    }

    /** One deployable version: the image, its latest deployment and the environments it covers. */
    public record VersionResponse(
        String digest,
        Instant finishedAt,
        String publishNo,
        String executedBy,
        int batchCount,
        List<EnvironmentResponse> environments) {

        public VersionResponse {
            environments = environments == null ? List.of() : List.copyOf(environments);
        }
    }

    /** One environment family; {@code label} is the Chinese name of {@code kind}. */
    public record EnvironmentResponse(String kind, String label) {
    }

    /** The answer of {@code POST /api/v1/rollbacks}. {@code freezeWarning} is null when no freeze. */
    public record ExecuteResponse(
        long rollbackId,
        String pipelineNo,
        long unitId,
        String targetDigest,
        String freezeWarning) {
    }

    /** One rollback record as the page shows it. */
    public record RecordResponse(
        long id,
        long unitId,
        Long instanceId,
        String pipelineNo,
        Long sourceOrderId,
        Long sourceItemId,
        String sourcePublishNo,
        String sourceDigest,
        String sourceCommit,
        String targetDigest,
        String targetPublishNo,
        String reason,
        String requestedBy,
        String status,
        String statusLabel,
        String failureReason,
        Instant createdAt,
        Instant finishedAt) {
    }

    /** One page of rollback records, newest first. */
    public record PageResponse(long total, long pageNo, long pageSize,
                               List<RecordResponse> records) {

        public PageResponse {
            records = records == null ? List.of() : List.copyOf(records);
        }
    }

    public static ExecuteResponse of(RollbackService.ExecuteResult result) {
        return new ExecuteResponse(result.rollbackId(), result.pipelineNo(), result.unitId(),
            result.targetDigest(), result.freezeWarning());
    }

    public static VersionListResponse of(RollbackService.VersionList list) {
        return new VersionListResponse(version(list.current()),
            list.history().stream().map(RollbackDtos::version).toList(), list.warnings());
    }

    public static VersionResponse version(RollbackVersionView view) {
        return new VersionResponse(view.digest(), view.finishedAt(), view.publishNo(),
            view.executedBy(), view.batchCount(),
            view.environments().stream()
                .map(environment -> new EnvironmentResponse(environment.kind(),
                    environment.label()))
                .toList());
    }

    public static RecordResponse record(RollbackRecordView view) {
        return new RecordResponse(view.id(), view.unitId(), view.instanceId(), view.pipelineNo(),
            view.sourceOrderId(), view.sourceItemId(), view.sourcePublishNo(), view.sourceDigest(),
            view.sourceCommit(), view.targetDigest(), view.targetPublishNo(), view.reason(),
            view.requestedBy(), view.status(), view.statusLabel(), view.failureReason(),
            view.createdAt(), view.finishedAt());
    }

    public static PageResponse page(RollbackService.PageResult page) {
        return new PageResponse(page.total(), page.pageNo(), page.pageSize(),
            page.records().stream().map(RollbackDtos::record).toList());
    }
}