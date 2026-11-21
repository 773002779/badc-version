package com.sf.release.web.controller;

import com.sf.release.application.CurrentUserAppService;
import com.sf.release.application.CurrentUserAppService.MeView;
import com.sf.release.common.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/v1/me}: the identity and effective permissions of the caller.
 *
 * <p>Exists so the page can disable an action <em>with a reason</em> instead of hiding it or letting
 * the user discover a 40009 after clicking (requirements FR-G-05: no grey button without a reason).
 * The permission list is the effective one resolved by the request interceptor; nothing here can
 * widen the caller's own authority.
 */
@RestController
@RequestMapping("/api/v1/me")
public class MeController {

    private final CurrentUserAppService currentUserAppService;

    public MeController(CurrentUserAppService currentUserAppService) {
        this.currentUserAppService = currentUserAppService;
    }

    @GetMapping
    public ApiResponse<MeView> me() {
        return ApiResponse.ok(currentUserAppService.me());
    }
}