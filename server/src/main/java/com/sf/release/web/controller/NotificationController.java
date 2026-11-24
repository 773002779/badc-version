package com.sf.release.web.controller;

import com.sf.release.application.notification.NotificationQueryService;
import com.sf.release.application.notification.NotificationSettingService;
import com.sf.release.common.ApiResponse;
import com.sf.release.common.security.Permissions;
import com.sf.release.common.security.RequirePerm;
import com.sf.release.web.dto.NotificationDtos;
import com.sf.release.web.dto.NotificationDtos.MarkAllReadResponse;
import com.sf.release.web.dto.NotificationDtos.MarkReadResponse;
import com.sf.release.web.dto.NotificationDtos.NotificationPageResponse;
import com.sf.release.web.dto.NotificationDtos.NotificationSettingRequest;
import com.sf.release.web.dto.NotificationDtos.NotificationSettingResponse;
import com.sf.release.web.dto.NotificationDtos.UnreadCountResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 站内通知 and the 「消息通知」 settings (design section 6, P4-B).
 *
 * <ul>
 *   <li>{@code GET  /api/v1/notifications?page=&size=&unreadOnly=} — the caller's own in-app rows,
 *       newest first, with the unread badge in the same answer;</li>
 *   <li>{@code GET  /api/v1/notifications/unread-count} — the badge only;</li>
 *   <li>{@code POST /api/v1/notifications/{id}/read} — mark one own row read (idempotent); another
 *       recipient's id answers 40000 exactly like a missing row, so ids cannot be enumerated;</li>
 *   <li>{@code POST /api/v1/notifications/read-all} — mark all own unread rows, returns the count;</li>
 *   <li>{@code GET/PUT /api/v1/settings/notifications} — the WeCom switch and webhook, needing
 *       {@code setting:manage}. The saving user comes from the request identity, not the body.</li>
 * </ul>
 *
 * <p>The recipient of every notification handler is the authenticated caller: the controller never
 * takes a user id from the request, and the application service reads it from the request context.
 * The settings endpoints are the only ones with a permission annotation, like the CODING block.
 */
@RestController
@RequestMapping("/api/v1")
public class NotificationController {

    private final NotificationQueryService queryService;
    private final NotificationSettingService settingService;

    public NotificationController(NotificationQueryService queryService,
                                  NotificationSettingService settingService) {
        this.queryService = queryService;
        this.settingService = settingService;
    }

    @GetMapping("/notifications")
    public ApiResponse<NotificationPageResponse> page(
            @RequestParam(name = "page", defaultValue = "1") int page,
            @RequestParam(name = "size", defaultValue = "10") int size,
            @RequestParam(name = "unreadOnly", defaultValue = "false") boolean unreadOnly) {
        return ApiResponse.ok(NotificationDtos.pageOf(queryService.page(page, size, unreadOnly)));
    }

    @GetMapping("/notifications/unread-count")
    public ApiResponse<UnreadCountResponse> unreadCount() {
        return ApiResponse.ok(new UnreadCountResponse(queryService.unread()));
    }

    @PostMapping("/notifications/{id}/read")
    public ApiResponse<MarkReadResponse> markRead(@PathVariable long id) {
        queryService.markRead(id);
        return ApiResponse.ok(new MarkReadResponse(id, true));
    }

    @PostMapping("/notifications/read-all")
    public ApiResponse<MarkAllReadResponse> readAll() {
        return ApiResponse.ok(new MarkAllReadResponse(queryService.markAllRead()));
    }

    @RequirePerm(Permissions.SETTING_MANAGE)
    @GetMapping("/settings/notifications")
    public ApiResponse<NotificationSettingResponse> settings() {
        return ApiResponse.ok(NotificationDtos.settingOf(settingService.view()));
    }

    @RequirePerm(Permissions.SETTING_MANAGE)
    @PutMapping("/settings/notifications")
    public ApiResponse<NotificationSettingResponse> save(
            @Valid @RequestBody NotificationSettingRequest request) {
        return ApiResponse.ok(NotificationDtos.settingOf(
            settingService.save(request.wecomEnabled(), request.wecomWebhook())));
    }
}