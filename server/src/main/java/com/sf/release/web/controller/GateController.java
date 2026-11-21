package com.sf.release.web.controller;

import com.sf.release.application.GateCheckService;
import com.sf.release.application.GateService;
import com.sf.release.application.GateWaiverService;
import com.sf.release.common.ApiResponse;
import com.sf.release.common.security.DataScope;
import com.sf.release.common.security.Permissions;
import com.sf.release.common.security.RequirePerm;
import com.sf.release.web.dto.GateDtos;
import com.sf.release.web.dto.GateDtos.AdmissionView;
import com.sf.release.web.dto.GateDtos.GateViewView;
import com.sf.release.web.dto.GateDtos.ReasonRequest;
import com.sf.release.web.dto.GateDtos.WaiverDecisionRequest;
import com.sf.release.web.dto.GateDtos.WaiverRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The gate endpoints of one 变更项 (P2): the verification panel, the three test decisions, a single
 * check refresh, the waiver flow and the production admission.
 *
 * <p><strong>Read vs decide.</strong> {@code GET} answers "why can/can't this go to production";
 * every {@code POST} is a decision and returns the whole refreshed panel, so the page never has to
 * guess what changed.
 *
 * <p><strong>Permissions.</strong> The button-level points are already part of the frozen catalogue:
 * {@code gate:test-pass}, {@code gate:test-revoke} and {@code gate:waive}. Requesting a waiver is a
 * task-member action (the developer owns the finding), so it has no permission annotation and the
 * service checks task membership instead; approving needs {@code gate:waive} and a different person
 * (40008 on self-approval).
 *
 * <p><strong>Data scope.</strong> Every handler is scope-guarded inside its service
 * ({@code ScopeGuard} → 40009 on a foreign unit); the annotations here mirror that for the read
 * endpoints.
 */
@RestController
@RequestMapping("/api/v1/items/{itemId}/gate")
public class GateController {

    private final GateService gateService;
    private final GateCheckService checkService;
    private final GateWaiverService waiverService;

    public GateController(GateService gateService, GateCheckService checkService,
                          GateWaiverService waiverService) {
        this.gateService = gateService;
        this.checkService = checkService;
        this.waiverService = waiverService;
    }

    /** The whole verification panel: both gates, every check, the waiver and the admission. */
    @DataScope
    @GetMapping
    public ApiResponse<GateViewView> view(@PathVariable long itemId) {
        return ApiResponse.ok(GateDtos.of(gateService.view(itemId)));
    }

    /** The production admission alone (the submit button's "what is missing"). */
    @DataScope
    @GetMapping("/admission")
    public ApiResponse<AdmissionView> admission(@PathVariable long itemId) {
        return ApiResponse.ok(GateDtos.admissionOf(gateService.admission(itemId)));
    }

    /** Marks the test passed; any {@code TEST} member of the task may do it (C18). */
    @RequirePerm(Permissions.GATE_TEST_PASS)
    @PostMapping("/test-pass")
    public ApiResponse<GateViewView> testPass(@PathVariable long itemId) {
        return ApiResponse.ok(GateDtos.of(gateService.passTest(itemId)));
    }

    /** Marks the test failed; the reason is required and visible to everyone. */
    @RequirePerm(Permissions.GATE_TEST_PASS)
    @PostMapping("/test-fail")
    public ApiResponse<GateViewView> testFail(@PathVariable long itemId,
                                              @RequestBody ReasonRequest request) {
        return ApiResponse.ok(GateDtos.of(
            gateService.failTest(itemId, request == null ? null : request.reason())));
    }

    /** Revokes a recorded pass; only the person who passed it may do it (C19). */
    @RequirePerm(Permissions.GATE_TEST_REVOKE)
    @PostMapping("/test-revoke")
    public ApiResponse<GateViewView> testRevoke(@PathVariable long itemId,
                                                @RequestBody ReasonRequest request) {
        return ApiResponse.ok(GateDtos.of(
            gateService.revokeTest(itemId, request == null ? null : request.reason())));
    }

    /**
     * Re-runs one check ("检查项可单独刷新"). Uses the test-verification permission: the person who
     * drives the verification step is the one who re-runs a stale check.
     */
    @RequirePerm(Permissions.GATE_TEST_PASS)
    @PostMapping("/checks/{kind}/refresh")
    public ApiResponse<GateViewView> refreshCheck(@PathVariable long itemId,
                                                  @PathVariable String kind,
                                                  @RequestParam(defaultValue = "0")
                                                  long instanceId) {
        checkService.refresh(itemId, kind, instanceId);
        return ApiResponse.ok(GateDtos.of(gateService.evaluateAndView(itemId)));
    }

    /** Requests a waiver for the item's medium findings (a task member may ask). */
    @PostMapping("/waiver")
    public ApiResponse<GateViewView> requestWaiver(@PathVariable long itemId,
                                                   @RequestBody WaiverRequest request) {
        return ApiResponse.ok(GateDtos.of(waiverService.request(itemId,
            request == null ? null : request.reason(),
            request == null ? null : request.impact(),
            request == null ? null : request.expireAt())));
    }

    /** Approves a pending waiver; the approver must not be the requester. */
    @RequirePerm(Permissions.GATE_WAIVE)
    @PostMapping("/waiver/{waiverId}/approve")
    public ApiResponse<GateViewView> approveWaiver(@PathVariable long itemId,
                                                   @PathVariable long waiverId,
                                                   @RequestBody(required = false)
                                                   WaiverDecisionRequest request) {
        return ApiResponse.ok(GateDtos.of(waiverService.approve(waiverId,
            request == null ? null : request.note())));
    }

    /** Rejects a pending waiver; the note is required. */
    @RequirePerm(Permissions.GATE_WAIVE)
    @PostMapping("/waiver/{waiverId}/reject")
    public ApiResponse<GateViewView> rejectWaiver(@PathVariable long itemId,
                                                  @PathVariable long waiverId,
                                                  @RequestBody WaiverDecisionRequest request) {
        return ApiResponse.ok(GateDtos.of(waiverService.reject(waiverId,
            request == null ? null : request.note())));
    }
}