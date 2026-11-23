package com.sf.release.web.controller;

import com.sf.release.application.ReleaseQueryService;
import com.sf.release.application.ReleaseRecordsService;
import com.sf.release.application.ReleaseRecordsService.ReleaseRecordsQuery;
import com.sf.release.application.ReleaseResumeService;
import com.sf.release.application.ReleaseResumeService.ResumeResult;
import com.sf.release.common.ApiResponse;
import com.sf.release.common.security.DataScope;
import com.sf.release.common.security.Permissions;
import com.sf.release.common.security.RequirePerm;
import com.sf.release.web.dto.ReleaseDtos;
import com.sf.release.web.dto.ReleaseDtos.ReleaseDetail;
import com.sf.release.web.dto.ReleaseDtos.ReleaseRecordView;
import com.sf.release.web.dto.ReleaseDtos.ReleaseRecordsPageResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 发布内容 read endpoints (Task 16):
 *
 * <ul>
 *   <li>{@code GET /api/v1/releases?page=&size=&keyword=&status=&systemId=&unitId=} — the global
 *       发布记录 page (GAP-10): one row per release order across tasks and systems, newest first,
 *       with the newest pipeline of the order. Data scoped and fail-closed; the filters never
 *       widen the scope;</li>
 *   <li>{@code GET /api/v1/releases/{publishNo}} — the aggregated view of the 发布单: its newest
 *       execution with the four nodes in pipeline order and their tasks, the rerun hand-off
 *       fields ({@code triggerType}, {@code rerunFromNode}, {@code fullRerun}) and the order's
 *       admission fields. {@code gateStatus} is present and null until the gate task lands;</li>
 *   <li>{@code GET /api/v1/releases/{publishNo}/records} — the instance history of the same
 *       发布单, newest execution first, so the page can show every run under one publish
 *       number.</li>
 * </ul>
 *
 * <p>Every handler is authenticated by {@code PermissionInterceptor}; reading release content
 * needs no button permission but the owning deployment unit must be inside the caller's data
 * scope (enforced by {@link ReleaseQueryService} through {@code ScopeGuard}). An unknown publish
 * number answers with the business code 40000 and the number in the payload.
 *
 * <p>Both endpoints are read-only: no write, no paging of tasks, no gate computation.
 */
@RestController
@RequestMapping("/api/v1/releases")
public class ReleaseController {

    private final ReleaseQueryService releaseQueryService;
    private final ReleaseRecordsService releaseRecordsService;
    private final ReleaseResumeService releaseResumeService;

    public ReleaseController(ReleaseQueryService releaseQueryService,
                             ReleaseRecordsService releaseRecordsService,
                             ReleaseResumeService releaseResumeService) {
        this.releaseQueryService = releaseQueryService;
        this.releaseRecordsService = releaseRecordsService;
        this.releaseResumeService = releaseResumeService;
    }

    /**
     * The global 发布记录 page (GAP-10). Every filter is optional; {@code page} starts at 1 and
     * {@code size} is between 1 and 100. The data scope is applied by the service (fail-closed);
     * the filters never widen it.
     */
    @DataScope
    @GetMapping
    public ApiResponse<ReleaseRecordsPageResponse> records(
            @RequestParam(name = "page", defaultValue = "1") int page,
            @RequestParam(name = "size", defaultValue = "10") int size,
            @RequestParam(name = "keyword", required = false) String keyword,
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "systemId", required = false) Long systemId,
            @RequestParam(name = "unitId", required = false) Long unitId) {
        return ApiResponse.ok(ReleaseDtos.recordsPageOf(releaseRecordsService.page(
            new ReleaseRecordsQuery(page, size, keyword, status, systemId, unitId))));
    }

    @DataScope
    @GetMapping("/{publishNo}")
    public ApiResponse<ReleaseDetail> detail(@PathVariable String publishNo) {
        return ApiResponse.ok(ReleaseDtos.of(releaseQueryService.detail(publishNo)));
    }

    @DataScope
    @GetMapping("/{publishNo}/records")
    public ApiResponse<List<ReleaseRecordView>> records(@PathVariable String publishNo) {
        return ApiResponse.ok(ReleaseDtos.recordsOf(releaseQueryService.records(publishNo)));
    }

    /**
     * Resumes a paused execution (P2). Requires {@code control:submit-prod}: resuming continues a
     * release, which is a release decision, not a read.
     */
    @RequirePerm(Permissions.CONTROL_SUBMIT_PROD)
    @PostMapping("/{publishNo}/resume")
    public ApiResponse<ResumeResult> resume(@PathVariable String publishNo) {
        return ApiResponse.ok(releaseResumeService.resume(publishNo));
    }
}