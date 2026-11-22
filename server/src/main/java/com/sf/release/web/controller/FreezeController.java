package com.sf.release.web.controller;

import com.sf.release.application.CurrentUserAppService;
import com.sf.release.application.FreezeService;
import com.sf.release.application.FreezeView;
import com.sf.release.common.ApiResponse;
import com.sf.release.common.security.Permissions;
import com.sf.release.common.security.RequirePerm;
import com.sf.release.web.dto.FreezeDtos;
import com.sf.release.web.dto.FreezeDtos.CreateFreezeRequest;
import com.sf.release.web.dto.FreezeDtos.FreezeResponse;
import com.sf.release.web.dto.FreezeDtos.ReleaseFreezeRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/**
 * 封板 endpoints (tech design 4.8).
 *
 * <ul>
 *   <li>{@code GET  /api/v1/freezes?active=&subsystemId=&plannedAt=} — the 封板 banner and the
 *       history; any logged-in user, because everybody needs to know that production is frozen. With
 *       {@code plannedAt} the list answers "which freezes govern a release planned for that time"
 *       (a freeze declared for later included); without it, "which are in force now";</li>
 *   <li>{@code POST /api/v1/freezes} — declare a freeze ({@code control:freeze}, 部门经理);</li>
 *   <li>{@code POST /api/v1/freezes/{id}/release} — lift one ({@code control:unfreeze},
 *       运维组长), body {@code {reason}}.</li>
 * </ul>
 *
 * <p>The declaring/lifting user comes from the request identity, not from the body: an audit trail
 * the caller can type is no audit trail. Every rule (scope, subsystem, end after start, only
 * {@code ACTIVE} may be lifted) is enforced by {@link FreezeService}, so a non-HTTP caller gets the
 * same behaviour.
 */
@RestController
@RequestMapping("/api/v1/freezes")
public class FreezeController {

    private final FreezeService freezeService;
    private final CurrentUserAppService currentUserAppService;

    public FreezeController(FreezeService freezeService,
                            CurrentUserAppService currentUserAppService) {
        this.freezeService = freezeService;
        this.currentUserAppService = currentUserAppService;
    }

    @GetMapping
    public ApiResponse<List<FreezeResponse>> list(
            @RequestParam(name = "active", required = false) Boolean active,
            @RequestParam(name = "subsystemId", required = false) Long subsystemId,
            @RequestParam(name = "plannedAt", required = false) Instant plannedAt) {
        return ApiResponse.ok(FreezeDtos.of(freezeService.list(active, subsystemId, plannedAt)));
    }

    @RequirePerm(Permissions.CONTROL_FREEZE)
    @PostMapping
    public ApiResponse<FreezeResponse> create(
            @RequestBody(required = false) CreateFreezeRequest request) {
        CreateFreezeRequest body = request == null
            ? new CreateFreezeRequest(null, null, null, null, null, null) : request;
        FreezeView view = freezeService.create(body.scope(), body.subsystemId(), body.reason(),
            body.impact(), body.startAt(), body.endAt(), currentUserAppService.me().userId());
        return ApiResponse.ok(FreezeDtos.of(view));
    }

    @RequirePerm(Permissions.CONTROL_UNFREEZE)
    @PostMapping("/{id}/release")
    public ApiResponse<FreezeResponse> release(@PathVariable long id,
                                               @RequestBody(required = false)
                                               ReleaseFreezeRequest request) {
        FreezeView view = freezeService.release(id, currentUserAppService.me().userId(),
            request == null ? null : request.reason());
        return ApiResponse.ok(FreezeDtos.of(view));
    }
}