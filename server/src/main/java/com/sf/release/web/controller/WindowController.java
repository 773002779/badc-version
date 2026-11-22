package com.sf.release.web.controller;

import com.sf.release.application.WindowCheckView;
import com.sf.release.application.WindowService;
import com.sf.release.application.WindowView;
import com.sf.release.common.ApiResponse;
import com.sf.release.common.security.Permissions;
import com.sf.release.common.security.RequirePerm;
import com.sf.release.web.dto.WindowDtos.CheckWindowRequest;
import com.sf.release.web.dto.WindowDtos.SaveWindowRequest;
import com.sf.release.web.dto.WindowDtos.WindowCheckResponse;
import com.sf.release.web.dto.WindowDtos.WindowResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 发布窗口 endpoints (tech design 4.8).
 *
 * <ul>
 *   <li>{@code GET  /api/v1/windows?subsystemId=} — the effective window (subsystem override, else
 *       the GLOBAL default marked {@code inherited}); any logged-in user, since every developer
 *       needs to know when production releases are allowed;</li>
 *   <li>{@code PUT  /api/v1/windows} — save one scope; {@code setting:manage};</li>
 *   <li>{@code POST /api/v1/windows/check} — is a planned instant inside? any logged-in user,
 *       because the submit dialog asks before it sends anything.</li>
 * </ul>
 *
 * <p>The controller only maps web DTOs to application commands; every rule (overlaps, start ==
 * end, subsystem existence) is enforced by {@link WindowService} so the same validation applies to
 * the admission flow that does not come through HTTP.
 */
@RestController
@RequestMapping("/api/v1/windows")
public class WindowController {

    private final WindowService windowService;

    public WindowController(WindowService windowService) {
        this.windowService = windowService;
    }

    @GetMapping
    public ApiResponse<WindowResponse> get(
            @RequestParam(name = "subsystemId", required = false) Long subsystemId) {
        WindowView view = windowService.effectiveWindow(
            subsystemId == null ? 0L : subsystemId);
        return ApiResponse.ok(view == null ? WindowResponse.absent()
            : WindowResponse.of(view, List.of()));
    }

    @RequirePerm(Permissions.SETTING_MANAGE)
    @PutMapping
    public ApiResponse<WindowResponse> save(
            @RequestBody(required = false) SaveWindowRequest request) {
        WindowService.SaveResult result = windowService.save(toCommand(request));
        return ApiResponse.ok(WindowResponse.of(result.window(), result.warnings()));
    }

    @PostMapping("/check")
    public ApiResponse<WindowCheckResponse> check(
            @RequestBody(required = false) CheckWindowRequest request) {
        CheckWindowRequest body = request == null ? new CheckWindowRequest(null, null) : request;
        long subsystemId = body.subsystemId() == null ? 0L : body.subsystemId();
        WindowCheckView view = windowService.check(subsystemId, body.at());
        return ApiResponse.ok(WindowCheckResponse.of(view));
    }

    private static WindowService.SaveCommand toCommand(SaveWindowRequest request) {
        if (request == null) {
            return null;
        }
        return new WindowService.SaveCommand(
            request.scope(),
            request.subsystemId(),
            request.enabled(),
            request.timezone(),
            request.rules() == null ? List.of() : request.rules().stream()
                .map(rule -> rule == null ? null
                    : new WindowService.RuleInput(rule.dayOfWeek(), rule.start(), rule.end()))
                .toList(),
            request.exceptions() == null ? List.of() : request.exceptions().stream()
                .map(exception -> exception == null ? null
                    : new WindowService.ExceptionInput(exception.date(), exception.mode(),
                        exception.start(), exception.end()))
                .toList());
    }
}