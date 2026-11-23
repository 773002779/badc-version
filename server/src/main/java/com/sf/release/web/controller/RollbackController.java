package com.sf.release.web.controller;

import com.sf.release.application.RollbackService;
import com.sf.release.common.ApiResponse;
import com.sf.release.common.security.Permissions;
import com.sf.release.common.security.RequirePerm;
import com.sf.release.web.dto.RollbackDtos;
import com.sf.release.web.dto.RollbackDtos.ExecuteResponse;
import com.sf.release.web.dto.RollbackDtos.ExecuteRollbackRequest;
import com.sf.release.web.dto.RollbackDtos.PageResponse;
import com.sf.release.web.dto.RollbackDtos.RecordResponse;
import com.sf.release.web.dto.RollbackDtos.VersionListResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 生产回滚 endpoints (rollback design section 4, P4-A):
 *
 * <ul>
 *   <li>{@code GET  /api/v1/units/{id}/rollback-versions} — the current online version, up to ten
 *       historical versions and the drawer's must-read notes; any logged-in user inside the unit's
 *       data scope (the page greys the button, it does not hide it);</li>
 *   <li>{@code POST /api/v1/rollbacks} — execute immediately ({@code rollback:execute}:
 *       operations/administrator). The body is {@code {unitId, targetDigest, reason}};</li>
 *   <li>{@code GET  /api/v1/rollbacks?unitId=&page=&size=} — the record page, newest first, data
 *       scoped;</li>
 *   <li>{@code GET  /api/v1/rollbacks/{id}} — one record with the instance number, the result and
 *       the reason.</li>
 * </ul>
 *
 * <p>The caller identity comes from the request, never from the body, and every business rule
 * (validation order, data scope, lock, instance creation) is enforced by {@link RollbackService}, so
 * a non-HTTP caller gets exactly the same behaviour. The controller is a thin translation layer: it
 * must not import a domain enum (architecture rule), which is why the DTOs carry the application
 * views and the machine values as strings.
 */
@RestController
@RequestMapping("/api/v1")
public class RollbackController {

    private final RollbackService rollbackService;

    public RollbackController(RollbackService rollbackService) {
        this.rollbackService = rollbackService;
    }

    @GetMapping("/units/{id}/rollback-versions")
    public ApiResponse<VersionListResponse> versions(@PathVariable long id) {
        return ApiResponse.ok(RollbackDtos.of(rollbackService.versions(id)));
    }

    @RequirePerm(Permissions.ROLLBACK_EXECUTE)
    @PostMapping("/rollbacks")
    public ApiResponse<ExecuteResponse> execute(
            @RequestBody(required = false) ExecuteRollbackRequest request) {
        ExecuteRollbackRequest body = request == null
            ? new ExecuteRollbackRequest(null, null, null) : request;
        return ApiResponse.ok(RollbackDtos.of(rollbackService.execute(body.unitId(),
            body.targetDigest(), body.reason())));
    }

    @GetMapping("/rollbacks")
    public ApiResponse<PageResponse> page(
            @RequestParam(name = "unitId", required = false) Long unitId,
            @RequestParam(name = "page", defaultValue = "1") int page,
            @RequestParam(name = "size", defaultValue = "10") int size) {
        return ApiResponse.ok(RollbackDtos.page(rollbackService.page(unitId, page, size)));
    }

    @GetMapping("/rollbacks/{id}")
    public ApiResponse<RecordResponse> detail(@PathVariable long id) {
        return ApiResponse.ok(RollbackDtos.record(rollbackService.detail(id)));
    }
}