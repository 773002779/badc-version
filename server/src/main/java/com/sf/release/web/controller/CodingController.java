package com.sf.release.web.controller;

import com.sf.release.application.CodingConnAppService;
import com.sf.release.application.CodingConnAppService.CodingConnView;
import com.sf.release.common.ApiResponse;
import com.sf.release.common.security.Permissions;
import com.sf.release.common.security.RequirePerm;
import com.sf.release.web.dto.CodingDtos.ConnResponse;
import com.sf.release.web.dto.CodingDtos.ProbeResponse;
import com.sf.release.web.dto.CodingDtos.SaveConnRequest;
import com.sf.release.web.dto.CodingDtos.TestConnRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * CODING 对接配置 endpoints. The connection is platform-wide (one CODING tenant), so every
 * handler requires {@code setting:manage}; a plain employee never sees the URL or the masked
 * credential.
 *
 * <ul>
 *   <li>{@code GET  /api/v1/coding/conn} — current connection, {@code configured:false} when none;</li>
 *   <li>{@code PUT  /api/v1/coding/conn} — save; a blank token keeps the stored credential;</li>
 *   <li>{@code POST /api/v1/coding/conn/test} — probe the draft or the stored connection.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/coding")
public class CodingController {

    private final CodingConnAppService connAppService;

    public CodingController(CodingConnAppService connAppService) {
        this.connAppService = connAppService;
    }

    /**
     * The current connection: the tenant-wide GLOBAL row, or — with {@code subsystemId} (PART-05)
     * — that subsystem's override. {@code configured:false} when the requested row was never saved.
     */
    @RequirePerm(Permissions.SETTING_MANAGE)
    @GetMapping("/conn")
    public ApiResponse<ConnResponse> get(@RequestParam(required = false) Long subsystemId) {
        CodingConnView view = subsystemId == null
            ? connAppService.globalOrNull()
            : connAppService.subsystemOrNull(subsystemId);
        return ApiResponse.ok(view == null ? ConnResponse.absent() : ConnResponse.of(view));
    }

    /**
     * Saves the GLOBAL connection, or — with {@code subsystemId} — that subsystem's override
     * (resolution order GLOBAL→SUBSYSTEM on the engine side). A blank token keeps the stored
     * credential.
     */
    @RequirePerm(Permissions.SETTING_MANAGE)
    @PutMapping("/conn")
    public ApiResponse<ConnResponse> save(@Valid @RequestBody SaveConnRequest request,
                                         @RequestParam(required = false) Long subsystemId) {
        return ApiResponse.ok(ConnResponse.of(connAppService.save(
            subsystemId == null ? "GLOBAL" : "SUBSYSTEM", subsystemId,
            request.baseUrl(), request.team(), request.project(),
            request.token())));
    }

    @RequirePerm(Permissions.SETTING_MANAGE)
    @PostMapping("/conn/test")
    public ApiResponse<ProbeResponse> test(
            @RequestBody(required = false) TestConnRequest request) {
        TestConnRequest body = request == null
            ? new TestConnRequest(null, null, null, null) : request;
        return ApiResponse.ok(ProbeResponse.of(connAppService.testForm(
            body.baseUrl(), body.team(), body.project(), body.token())));
    }
}