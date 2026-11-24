package com.sf.release.web.dto;

import com.sf.release.application.notification.NotificationQueryService;
import com.sf.release.application.notification.NotificationQueryService.NotificationView;
import com.sf.release.application.notification.NotificationSettingService.NotificationSettingView;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

/**
 * Response and request bodies of the notification endpoints (design section 6).
 *
 * <p>The row carries the machine {@code eventKey} plus the server-side {@code eventLabel}: the page
 * never keeps a copy of the enum (and the web package must not import a domain enum). The other
 * fields are the design's row structure verbatim; {@code read} is the {@code read_at != null} the
 * page shows.
 *
 * <p>Paging is {@code page}/{@code size} in, {@code pageNo}/{@code pageSize} out (R44), the same
 * shape as the release-records page.
 */
public final class NotificationDtos {

    private NotificationDtos() {
    }

    public static NotificationRowResponse rowOf(NotificationView view) {
        return new NotificationRowResponse(
            view.id(),
            view.eventKey(),
            view.eventLabel(),
            view.objectType(),
            view.objectId(),
            view.objectLabel(),
            view.title(),
            view.body(),
            view.status(),
            view.read(),
            view.createdAt(),
            view.sentAt(),
            view.failureReason());
    }

    public static NotificationPageResponse pageOf(NotificationQueryService.Page page) {
        return new NotificationPageResponse(
            page.total(),
            page.pageNo(),
            page.pageSize(),
            page.unread(),
            page.records().stream().map(NotificationDtos::rowOf).toList());
    }

    public static NotificationSettingResponse settingOf(NotificationSettingView view) {
        return new NotificationSettingResponse(view.wecomEnabled(), view.wecomWebhook(),
            view.updatedBy(), view.updatedAt());
    }

    /** One in-app notification as the notification centre shows it. */
    public record NotificationRowResponse(
        long id,
        String eventKey,
        String eventLabel,
        String objectType,
        String objectId,
        String objectLabel,
        String title,
        String body,
        String status,
        boolean read,
        Instant createdAt,
        Instant sentAt,
        String failureReason) {
    }

    /** One page of the caller's notifications plus the unread badge. */
    public record NotificationPageResponse(
        long total,
        long pageNo,
        long pageSize,
        long unread,
        List<NotificationRowResponse> records) {

        public NotificationPageResponse {
            records = records == null ? List.of() : List.copyOf(records);
        }
    }

    /** {@code GET /api/v1/notifications/unread-count}. */
    public record UnreadCountResponse(long unread) {
    }

    /** {@code POST /api/v1/notifications/{id}/read}; a second call is still {@code read:true}. */
    public record MarkReadResponse(long id, boolean read) {
    }

    /** {@code POST /api/v1/notifications/read-all}: how many rows this call marked. */
    public record MarkAllReadResponse(int updated) {
    }

    /** The 「消息通知」 block. The webhook is returned as-is to {@code setting:manage}. */
    public record NotificationSettingResponse(
        boolean wecomEnabled,
        String wecomWebhook,
        String updatedBy,
        Instant updatedAt) {
    }

    /** Save form of the block; a blank webhook clears it, an omitted switch is a parameter error. */
    public record NotificationSettingRequest(
        @NotNull(message = "企微通知开关不能为空")
        Boolean wecomEnabled,

        @Size(max = 512, message = "企微机器人地址长度不能超过 512")
        String wecomWebhook) {
    }
}