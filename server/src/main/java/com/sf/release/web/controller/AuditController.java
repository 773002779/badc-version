package com.sf.release.web.controller;

import com.sf.release.application.audit.AuditQueryService;
import com.sf.release.application.audit.AuditQueryService.AuditQuery;
import com.sf.release.common.ApiResponse;
import com.sf.release.common.security.DataScope;
import com.sf.release.web.dto.AuditDtos;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 审计日志 endpoints (audit design section 4). Any logged-in caller may read; the rows are filtered
 * by the caller's data scope (fail-closed) in {@link AuditQueryService}.
 *
 * <ul>
 *   <li>{@code GET /api/v1/audit?objectType=&objectId=&action=&actor=&from=&to=&unitId=&page=&size=}
 *       — one page, newest first, with the full match count;</li>
 *   <li>{@code GET /api/v1/audit/export?<same filters>} — the same rows as a CSV download
 *       ({@code text/csv; charset=UTF-8}, UTF-8 BOM, Chinese header, at most 5000 rows).</li>
 * </ul>
 *
 * <p>{@code unitId} is the unit-detail convenience filter: it restricts the page to the unit-scoped
 * rows of that unit. An explicit unit outside the caller's scope answers an empty page, not 40009:
 * the audit page must not reveal whether a unit exists.
 *
 * <p>The export is a raw byte response, not the {@link ApiResponse} envelope, because the browser
 * downloads it as a file; a validation failure still answers the JSON envelope (the global handler
 * wins before a body is written).
 */
@RestController
@RequestMapping("/api/v1/audit")
public class AuditController {

    private static final DateTimeFormatter FILE_STAMP =
        DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final AuditQueryService auditQueryService;

    public AuditController(AuditQueryService auditQueryService) {
        this.auditQueryService = auditQueryService;
    }

    @DataScope
    @GetMapping
    public ApiResponse<AuditDtos.AuditPageResponse> page(
            @RequestParam(name = "objectType", required = false) String objectType,
            @RequestParam(name = "objectId", required = false) String objectId,
            @RequestParam(name = "action", required = false) String action,
            @RequestParam(name = "actor", required = false) String actor,
            @RequestParam(name = "from", required = false) Instant from,
            @RequestParam(name = "to", required = false) Instant to,
            @RequestParam(name = "unitId", required = false) Long unitId,
            @RequestParam(name = "page", defaultValue = "1") int page,
            @RequestParam(name = "size", defaultValue = "10") int size) {
        return ApiResponse.ok(AuditDtos.pageOf(
            auditQueryService.page(page, size,
                new AuditQuery(objectType, objectId, action, actor, from, to, unitId))));
    }

    @DataScope
    @GetMapping("/export")
    public ResponseEntity<byte[]> export(
            @RequestParam(name = "objectType", required = false) String objectType,
            @RequestParam(name = "objectId", required = false) String objectId,
            @RequestParam(name = "action", required = false) String action,
            @RequestParam(name = "actor", required = false) String actor,
            @RequestParam(name = "from", required = false) Instant from,
            @RequestParam(name = "to", required = false) Instant to,
            @RequestParam(name = "unitId", required = false) Long unitId) {
        byte[] csv = auditQueryService.exportCsv(
            new AuditQuery(objectType, objectId, action, actor, from, to, unitId));
        String filename = "audit-" + FILE_STAMP.format(LocalDateTime.now()) + ".csv";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(new MediaType("text", "csv", StandardCharsets.UTF_8));
        headers.set(HttpHeaders.CONTENT_DISPOSITION,
            "attachment; filename=\"" + filename + "\"");
        headers.setContentLength(csv.length);
        return new ResponseEntity<>(csv, headers, HttpStatus.OK);
    }
}