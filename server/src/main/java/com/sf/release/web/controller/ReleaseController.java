package com.sf.release.web.controller;

import com.sf.release.application.ReleaseQueryService;
import com.sf.release.application.ReleaseResumeService;
import com.sf.release.application.ReleaseResumeService.ResumeResult;
import com.sf.release.common.ApiResponse;
import com.sf.release.common.security.DataScope;
import com.sf.release.common.security.Permissions;
import com.sf.release.common.security.RequirePerm;
import com.sf.release.web.dto.ReleaseDtos;
import com.sf.release.web.dto.ReleaseDtos.ReleaseDetail;
import com.sf.release.web.dto.ReleaseDtos.ReleaseRecordView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 发布内容 read endpoints (Task 16):
 *
 * <ul>
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
    private final ReleaseResumeService releaseResumeService;

    public ReleaseController(ReleaseQueryService releaseQueryService,
                             ReleaseResumeService releaseResumeService) {
        this.releaseQueryService = releaseQueryService;
        this.releaseResumeService = releaseResumeService;
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