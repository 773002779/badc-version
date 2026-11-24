package com.sf.release.application.notification;

import com.sf.release.domain.notification.NotificationStatus;
import com.sf.release.infra.repo.NotificationMapper;
import com.sf.release.infra.repo.NotificationSettingMapper;
import com.sf.release.infra.repo.po.NotificationPO;
import com.sf.release.infra.repo.po.NotificationSettingPO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * Delivers the pending WeCom rows (design sections 5 and 9).
 *
 * <p>One delivery is allowed to be attempted twice: a failed send is retried immediately once, and a
 * second failure closes the row {@code FAILED} with the cause (truncated to the column width) and
 * {@code attempts = 2}. A success on the first attempt records {@code attempts = 1}, a success on the
 * retry {@code attempts = 2} — the design's test expects exactly that.
 *
 * <p>No distributed lock: a single instance takes the rows in id order, and on a multi-instance
 * deployment the design accepts at-least-once delivery (two runners may both send one row). A failed
 * update, an exception from the sender or a malformed row is logged and only ends that row's attempt;
 * the next row is still processed, and no business transaction is involved.
 */
@Service
public class NotificationDispatchService {

    private static final Logger log = LoggerFactory.getLogger(NotificationDispatchService.class);

    /** The only attempts of one row: the first send and the one immediate retry. */
    static final int MAX_ATTEMPTS = 2;

    /** {@code notification.failure_reason} is {@code VARCHAR(512)}. */
    static final int MAX_FAILURE_REASON = 512;

    /** Default batch of one round, matching the design's {@code LIMIT 50}. */
    public static final int DEFAULT_BATCH = 50;

    static final int SETTING_ID = 1;

    private final NotificationMapper notificationMapper;
    private final NotificationSettingMapper settingMapper;
    private final NotificationSender sender;

    public NotificationDispatchService(NotificationMapper notificationMapper,
                                       NotificationSettingMapper settingMapper,
                                       NotificationSender sender) {
        this.notificationMapper = notificationMapper;
        this.settingMapper = settingMapper;
        this.sender = sender;
    }

    /**
     * Delivers up to {@code limit} pending WeCom rows.
     *
     * @return how many rows reached a terminal state in this round ({@code SENT} or {@code FAILED})
     */
    public int dispatchDue(int limit) {
        int batch = limit <= 0 ? DEFAULT_BATCH : limit;
        List<NotificationPO> rows = notificationMapper.selectPendingWeCom(batch);
        if (rows.isEmpty()) {
            return 0;
        }
        NotificationSettingPO setting = settingMapper.selectById(SETTING_ID);
        String webhook = setting == null ? null : trimToNull(setting.getWecomWebhook());
        boolean enabled = setting != null && Boolean.TRUE.equals(setting.getWecomEnabled());
        int finished = 0;
        int sent = 0;
        int failed = 0;
        for (NotificationPO row : rows) {
            try {
                if (!enabled || webhook == null) {
                    fail(row, "企微通知未启用或机器人地址未配置，无法发送");
                    failed++;
                } else if (deliver(row, webhook)) {
                    sent++;
                } else {
                    failed++;
                }
                finished++;
            } catch (RuntimeException ex) {
                // One row must not stop the round or another row's delivery.
                log.error("wecom notification dispatch failed: notificationId={}, reason={}",
                    row.getId(), ex.getMessage(), ex);
            }
        }
        if (finished > 0) {
            log.info("wecom notification round: sent={}, failed={}, candidates={}", sent, failed,
                rows.size());
        }
        return finished;
    }

    /**
     * One row with the immediate retry.
     *
     * @return {@code true} when the row became {@code SENT}
     */
    private boolean deliver(NotificationPO row, String webhook) {
        NotificationMessage message = new NotificationMessage(row.getTitle(), row.getBody(),
            row.getObjectLabel(), row.getCreatedAt());
        String lastFailure = null;
        int attempts = 0;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            attempts = attempt;
            try {
                sender.send(webhook, message);
                notificationMapper.updateSendOutcome(row.getId(), NotificationStatus.SENT.name(),
                    attempts, null, Instant.now());
                return true;
            } catch (RuntimeException ex) {
                lastFailure = senderReason(ex);
                log.warn("wecom notification attempt {} failed: notificationId={}, reason={}",
                    attempt, row.getId(), lastFailure);
            }
        }
        notificationMapper.updateSendOutcome(row.getId(), NotificationStatus.FAILED.name(),
            attempts, truncate(lastFailure == null ? "发送失败" : lastFailure, MAX_FAILURE_REASON),
            null);
        log.warn("wecom notification gave up after {} attempts: notificationId={}",
            attempts, row.getId());
        return false;
    }

    private void fail(NotificationPO row, String reason) {
        notificationMapper.updateSendOutcome(row.getId(), NotificationStatus.FAILED.name(),
            MAX_ATTEMPTS, truncate(reason, MAX_FAILURE_REASON), null);
        log.warn("wecom notification not sent: notificationId={}, reason={}", row.getId(), reason);
    }

    private static String senderReason(RuntimeException ex) {
        String message = ex.getMessage();
        if (message == null || message.isBlank()) {
            return ex.getClass().getSimpleName();
        }
        return message;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }
}