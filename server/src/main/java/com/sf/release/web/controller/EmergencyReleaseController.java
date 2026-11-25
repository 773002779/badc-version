package com.sf.release.web.controller;

import com.sf.release.application.EmergencyReleaseService;
import com.sf.release.common.ApiResponse;
import com.sf.release.web.dto.ControlDtos.ReasonRequest;
import com.sf.release.web.dto.EmergencyDtos;
import com.sf.release.web.dto.EmergencyDtos.EmergencyResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 紧急放行 endpoint (P6-D design §3): {@code POST /api/v1/releases/{publishNo}/emergency-release}
 * with the body {@code {reason}}.
 *
 * <p><strong>No {@code @RequirePerm}.</strong> J11 allows only the platform {@code ADMIN} role, which
 * is strictly narrower than every permission point (even a caller granted {@code control:approve}
 * may not use it), so there is no permission point to pin here. The interceptor still authenticates
 * the request; {@link EmergencyReleaseService} owns the ADMIN check and answers 40009 for everybody
 * else.
 *
 * <p>The answer echoes the resulting statuses: {@code {status, itemStatus, emergency:true}}, the same
 * shape as the P5-B control actions plus the fixed emergency flag.
 *
 * <p>The controller is a thin translation layer and imports no domain or infrastructure enum; the
 * business rules (state matrix, approval voiding, order marker, audit, notification) live in the
 * application service.
 */
@RestController
@RequestMapping("/api/v1/releases")
public class EmergencyReleaseController {

    private final EmergencyReleaseService emergencyReleaseService;

    public EmergencyReleaseController(EmergencyReleaseService emergencyReleaseService) {
        this.emergencyReleaseService = emergencyReleaseService;
    }

    /** 紧急放行: ADMIN only; the reason is mandatory (1..200 characters). */
    @PostMapping("/{publishNo}/emergency-release")
    public ApiResponse<EmergencyResponse> release(@PathVariable String publishNo,
                                                   @Valid @RequestBody ReasonRequest request) {
        return ApiResponse.ok(EmergencyDtos.of(
            emergencyReleaseService.release(publishNo, request.reason())));
    }
}