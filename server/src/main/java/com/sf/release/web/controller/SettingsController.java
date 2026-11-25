package com.sf.release.web.controller;

import com.sf.release.application.settings.SettingsService;
import com.sf.release.common.ApiResponse;
import com.sf.release.common.security.Permissions;
import com.sf.release.common.security.RequirePerm;
import com.sf.release.web.dto.SettingsDtos;
import com.sf.release.web.dto.SettingsDtos.ApprovalSettingsRequest;
import com.sf.release.web.dto.SettingsDtos.ApprovalSettingsResponse;
import com.sf.release.web.dto.SettingsDtos.ApproverSettingsRequest;
import com.sf.release.web.dto.SettingsDtos.ApproverSettingsResponse;
import com.sf.release.web.dto.SettingsDtos.GateSettingsRequest;
import com.sf.release.web.dto.SettingsDtos.GateSettingsResponse;
import com.sf.release.web.dto.SettingsDtos.LockSettingsRequest;
import com.sf.release.web.dto.SettingsDtos.LockSettingsResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The P6-B platform settings blocks (design §3): gate switches, approval-role lists and the
 * abandoned-pause bound. Every endpoint needs {@code setting:manage}.
 *
 * <ul>
 *   <li>{@code GET/PUT /api/v1/settings/gate} — the enabled {@code CheckType} names;</li>
 *   <li>{@code GET/PUT /api/v1/settings/approvers} — the {@code OPS_LEAD} /
 *       {@code DEPT_MANAGER} member lists;</li>
 *   <li>{@code GET/PUT /api/v1/settings/lock} — {@code pauseBoundHours} (1–8760);</li>
 *   <li>{@code GET/PUT /api/v1/settings/approval} — the P6-E approval SLA: {@code enabled} plus
 *       {@code remindHours} (1–72) and {@code escalateHours} (2–168, above the reminder).</li>
 * </ul>
 *
 * <p><strong>The saving user comes from the request identity.</strong> The body never carries an
 * actor; the service reads {@code UserContext}. Each PUT writes one {@code SETTING_UPDATE} audit row
 * with the whole section before/after and no scope, i.e. a platform-level setting only an
 * ALL-scope audit reader can see.
 *
 * <p><strong>The web layer stays free of domain enums.</strong> Check names are strings on the
 * wire; validation against {@code CheckType} belongs to the application service.
 */
@RestController
@RequestMapping("/api/v1")
public class SettingsController {

    private final SettingsService settings;

    public SettingsController(SettingsService settings) {
        this.settings = settings;
    }

    @RequirePerm(Permissions.SETTING_MANAGE)
    @GetMapping("/settings/gate")
    public ApiResponse<GateSettingsResponse> gate() {
        return ApiResponse.ok(SettingsDtos.gateOf(settings.gate()));
    }

    @RequirePerm(Permissions.SETTING_MANAGE)
    @PutMapping("/settings/gate")
    public ApiResponse<GateSettingsResponse> saveGate(
            @Valid @RequestBody GateSettingsRequest request) {
        return ApiResponse.ok(SettingsDtos.gateOf(settings.saveGate(request.enabledChecks())));
    }

    @RequirePerm(Permissions.SETTING_MANAGE)
    @GetMapping("/settings/approvers")
    public ApiResponse<ApproverSettingsResponse> approvers() {
        return ApiResponse.ok(SettingsDtos.approversOf(settings.approvers()));
    }

    @RequirePerm(Permissions.SETTING_MANAGE)
    @PutMapping("/settings/approvers")
    public ApiResponse<ApproverSettingsResponse> saveApprovers(
            @Valid @RequestBody ApproverSettingsRequest request) {
        return ApiResponse.ok(SettingsDtos.approversOf(
            settings.saveApprovers(request.opsLead(), request.deptManager())));
    }

    @RequirePerm(Permissions.SETTING_MANAGE)
    @GetMapping("/settings/lock")
    public ApiResponse<LockSettingsResponse> lock() {
        return ApiResponse.ok(SettingsDtos.lockOf(settings.lock()));
    }

    @RequirePerm(Permissions.SETTING_MANAGE)
    @PutMapping("/settings/lock")
    public ApiResponse<LockSettingsResponse> saveLock(
            @Valid @RequestBody LockSettingsRequest request) {
        return ApiResponse.ok(SettingsDtos.lockOf(settings.saveLock(request.pauseBoundHours())));
    }

    @RequirePerm(Permissions.SETTING_MANAGE)
    @GetMapping("/settings/approval")
    public ApiResponse<ApprovalSettingsResponse> approval() {
        return ApiResponse.ok(SettingsDtos.approvalOf(settings.approvalSla()));
    }

    @RequirePerm(Permissions.SETTING_MANAGE)
    @PutMapping("/settings/approval")
    public ApiResponse<ApprovalSettingsResponse> saveApproval(
            @Valid @RequestBody ApprovalSettingsRequest request) {
        return ApiResponse.ok(SettingsDtos.approvalOf(settings.saveApprovalSla(
            request.enabled(), request.remindHours(), request.escalateHours())));
    }
}
