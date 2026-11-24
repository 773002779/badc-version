package com.sf.release.application.notification;

import com.sf.release.common.BizError;
import com.sf.release.common.BizException;
import com.sf.release.domain.notification.NotificationEventKey;
import com.sf.release.infra.repo.NotificationMapper;
import com.sf.release.infra.repo.po.NotificationPO;
import com.sf.release.infra.security.CurrentUser;
import com.sf.release.infra.security.UserContext;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * The notification centre reads of one caller (design section 6): the caller's own in-app rows only,
 * the unread count, one mark-read and the mark-all-read.
 *
 * <p>The recipient is always {@link UserContext#current()}, never a request parameter: a notification
 * is personal, so there is no endpoint that reads or writes somebody else's row. A row of another
 * recipient is deliberately indistinguishable from a missing one (40000) to avoid enumerating ids.
 *
 * <p>The page is {@code created_at DESC, id DESC} and the in-app channel only: the WeCom delivery rows
 * are not shown as separate messages (the in-app row is the message; the WeCom row is its delivery
 * attempt).
 */
@Service
public class NotificationQueryService {

    /** Page size ceiling, shared with the other paged read services. */
    static final int MAX_PAGE_SIZE = 100;

    private final NotificationMapper notificationMapper;

    public NotificationQueryService(NotificationMapper notificationMapper) {
        this.notificationMapper = notificationMapper;
    }

    /**
     * One page of the caller's in-app notifications, newest first.
     *
     * @param unreadOnly {@code true} restricts the page (and {@code total}) to unread rows
     * @throws BizException 40000 an invalid page request
     */
    public Page page(int pageNo, int pageSize, boolean unreadOnly) {
        requirePage(pageNo, pageSize);
        String recipient = currentUserId();
        int offset = (pageNo - 1) * pageSize;
        int unread = unreadOnly ? 1 : 0;
        List<NotificationPO> rows = notificationMapper.selectInAppPage(recipient, unread, offset,
            pageSize);
        long total = notificationMapper.countInApp(recipient, unread);
        long unreadCount = notificationMapper.countUnread(recipient);
        return new Page(total, pageNo, pageSize, unreadCount,
            rows.stream().map(NotificationQueryService::toView).toList());
    }

    /** The caller's unread in-app count. */
    public long unread() {
        return notificationMapper.countUnread(currentUserId());
    }

    /**
     * Marks one of the caller's rows read; a second call is a successful no-op (the design asks for
     * an idempotent read).
     *
     * @throws BizException 40000 the id does not name one of the caller's in-app rows
     */
    public NotificationView markRead(long id) {
        if (id <= 0) {
            throw new BizException(BizError.PARAM_INVALID, "通知不存在", Map.of("id", id));
        }
        String recipient = currentUserId();
        NotificationPO row = notificationMapper.selectInApp(id, recipient);
        if (row == null) {
            // Another recipient's row is answered exactly like a missing one: 40000, no enumeration.
            throw new BizException(BizError.PARAM_INVALID, "通知不存在", Map.of("id", id));
        }
        if (row.getReadAt() == null) {
            notificationMapper.markRead(id, recipient, Instant.now());
            row = notificationMapper.selectInApp(id, recipient);
        }
        return row == null ? null : toView(row);
    }

    /** Marks every unread in-app row of the caller read; returns how many changed. */
    public int markAllRead() {
        return notificationMapper.markAllRead(currentUserId(), Instant.now());
    }

    // ------------------------------------------------------------------ helpers

    private static String currentUserId() {
        CurrentUser user = UserContext.current();
        return user.userId();
    }

    private static void requirePage(int pageNo, int pageSize) {
        if (pageNo < 1) {
            throw new BizException(BizError.PARAM_INVALID, "页码必须从 1 开始",
                Map.of("pageNo", pageNo));
        }
        if (pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
            throw new BizException(BizError.PARAM_INVALID,
                "每页条数必须在 1 到 " + MAX_PAGE_SIZE + " 之间", Map.of("pageSize", pageSize));
        }
    }

    private static NotificationView toView(NotificationPO po) {
        NotificationEventKey event = po.event();
        return new NotificationView(
            po.getId() == null ? 0L : po.getId(),
            po.getEventKey(),
            event == null ? po.getEventKey() : event.label(),
            po.getObjectType(),
            po.getObjectId(),
            po.getObjectLabel(),
            po.getTitle(),
            po.getBody(),
            po.getStatus(),
            po.read(),
            po.getCreatedAt(),
            po.getSentAt(),
            po.getFailureReason());
    }

    /** The last-created notification as the page shows it. */
    public record NotificationView(
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

    /** One page plus the caller's unread count (the badge refreshes from the same answer). */
    public record Page(long total, long pageNo, long pageSize, long unread,
                       List<NotificationView> records) {

        public Page {
            records = records == null ? List.of() : List.copyOf(records);
        }
    }
}