package com.sf.release.web.controller;

import com.sf.release.application.AdmissionView;
import com.sf.release.application.SubmitProdService;
import com.sf.release.common.ApiResponse;
import com.sf.release.common.security.DataScope;
import com.sf.release.common.security.Permissions;
import com.sf.release.common.security.RequirePerm;
import com.sf.release.web.dto.AdmissionDtos;
import com.sf.release.web.dto.AdmissionDtos.AdmissionResponse;
import com.sf.release.web.dto.AdmissionDtos.SubmitProdRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 生产准入 endpoints of one 发布单 (tech design 4.8).
 *
 * <ul>
 *   <li>{@code POST /api/v1/releases/{publishNo}/submit-prod} — submit ({@code
 *       control:submit-prod});</li>
 *   <li>{@code GET  /api/v1/releases/{publishNo}/admission} — the latest snapshot, the chain of that
 *       snapshot and the current statuses; any logged-in user inside the unit's data scope
 *       ({@code @DataScope}, the service enforces the scope);</li>
 *   <li>{@code POST /api/v1/releases/{publishNo}/resubmit} — resubmit after a rejection
 *       ({@code control:submit-prod}), new snapshot, old chain untouched.</li>
 * </ul>
 *
 * <p>Every refusal carries the failed admission steps with their reasons and the next action
 * (40002 for a gate/test finding, 40000 for the OA ticket or the planned time, 40001 for a repeat
 * submit), so the page can name the missing item instead of greying a button.
 *
 * <p>The controller only maps the web DTO to the service call; the permission point is enforced by
 * the interceptor annotation and again inside the service (which also has the caller identity for
 * the data-scope guard).
 */
@RestController
@RequestMapping("/api/v1/releases")
public class AdmissionController {

    private final SubmitProdService submitProdService;

    public AdmissionController(SubmitProdService submitProdService) {
        this.submitProdService = submitProdService;
    }

    @RequirePerm(Permissions.CONTROL_SUBMIT_PROD)
    @PostMapping("/{publishNo}/submit-prod")
    public ApiResponse<AdmissionResponse> submit(@PathVariable String publishNo,
                                                 @RequestBody(required = false)
                                                 SubmitProdRequest request) {
        SubmitProdRequest body = request == null ? new SubmitProdRequest(null, null) : request;
        AdmissionView view = submitProdService.submit(publishNo, body.plannedReleaseTime(),
            body.oaTicketNo());
        return ApiResponse.ok(AdmissionDtos.of(view));
    }

    @DataScope
    @GetMapping("/{publishNo}/admission")
    public ApiResponse<AdmissionResponse> admission(@PathVariable String publishNo) {
        return ApiResponse.ok(AdmissionDtos.of(submitProdService.admission(publishNo)));
    }

    @RequirePerm(Permissions.CONTROL_SUBMIT_PROD)
    @PostMapping("/{publishNo}/resubmit")
    public ApiResponse<AdmissionResponse> resubmit(@PathVariable String publishNo,
                                                   @RequestBody(required = false)
                                                   SubmitProdRequest request) {
        SubmitProdRequest body = request == null ? new SubmitProdRequest(null, null) : request;
        AdmissionView view = submitProdService.resubmit(publishNo, body.plannedReleaseTime(),
            body.oaTicketNo());
        return ApiResponse.ok(AdmissionDtos.of(view));
    }
}