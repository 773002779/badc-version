package com.sf.release.web.dto;

import com.sf.release.application.audit.AuditQueryService;
import com.sf.release.application.audit.AuditView;

import java.time.Instant;
import java.util.List;

/**
 * Response bodies of {@code /api/v1/audit} (audit design section 4).
 *
 * <p>The row is the design's list verbatim: {@code action} is the machine code and
 * {@code actionLabel} its Chinese name, so the page owns no copy of the enum (the web package must
 * not import a domain enum). {@code before}/{@code after} are the stored JSON texts; the page
 * pretty-prints them, the CSV export keeps them in one cell.
 *
 * <p>Paging is {@code page}/{@code size} in, {@code pageNo}/{@code pageSize} out (R44), the same
 * shape as the release-records and notification pages.
 */
public final class AuditDtos {

    private AuditDtos() {
    }

    public static AuditRowResponse rowOf(AuditView view) {
        return new AuditRowResponse(
            view.id(),
            view.action(),
            view.actionLabel(),
            view.actor(),
            view.objectType(),
            view.objectId(),
            view.objectLabel(),
            view.summary(),
            view.reason(),
            view.before(),
            view.after(),
            view.traceId(),
            view.createdAt());
    }

    public static AuditPageResponse pageOf(AuditQueryService.Page page) {
        return new AuditPageResponse(
            page.total(),
            page.pageNo(),
            page.pageSize(),
            page.records().stream().map(AuditDtos::rowOf).toList());
    }

    /** One audit row: the design's fields, action label included. */
    public record AuditRowResponse(
        long id,
        String action,
        String actionLabel,
        String actor,
        String objectType,
        String objectId,
        String objectLabel,
        String summary,
        String reason,
        String before,
        String after,
        String traceId,
        Instant createdAt) {
    }

    /** One page of audit rows: {@code total} is the full match count under the same filter. */
    public record AuditPageResponse(
        long total,
        long pageNo,
        long pageSize,
        List<AuditRowResponse> records) {

        public AuditPageResponse {
            records = records == null ? List.of() : List.copyOf(records);
        }
    }
}