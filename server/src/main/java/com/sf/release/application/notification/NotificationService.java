package com.sf.release.application.notification;

import com.sf.release.domain.notification.NotificationChannel;
import com.sf.release.domain.notification.NotificationStatus;
import com.sf.release.infra.repo.NotificationMapper;
import com.sf.release.infra.repo.NotificationSettingMapper;
import com.sf.release.infra.repo.po.NotificationPO;
import com.sf.release.infra.repo.po.NotificationSettingPO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Writes the notifications of one event (design sections 2, 3 and 5): one in-app row per recipient
 * (delivered immediately, unread) and, when the WeCom channel is configured, one {@code PENDING} row
 * per recipient for {@link NotificationDispatchService} to deliver.
 *
 * <p><strong>Notification failures never change a business result.</strong> {@link #notify} resolves
 * the audience and writes the rows in a {@code REQUIRES_NEW} transaction, then swallows and logs every
 * exception; it never declares a checked exception and it never sends HTTP (only the dispatch job
 * does). The trigger points therefore call it bare, with no try/catch of their own.
 *
 * <p><strong>Dedupe is the unique key.</strong> The rows go in with {@code INSERT IGNORE} on
 * {@code dedupe_key = event|objectType|objectId|recipient|channel|ref}: repeating the same event for
 * the same object/recipient/channel/occurrence writes nothing, while a different recipient, channel
 * or occurrence is its own row.
 *
 * <p><strong>The transaction is explicit.</strong> A {@link TransactionTemplate} with
 * {@code PROPAGATION_REQUIRES_NEW} is used instead of {@code @Transactional} because a commit failure
 * is thrown by the transactional proxy <em>after</em> the method body returned, where the swallow
 * could not catch it; with the template the commit is inside the {@code try}. The business transaction
 * is suspended while the notification rows are written, which is the design's accepted window
 * (section 9): a business rollback can leave one notification behind.
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    /** Column widths of V26; the service truncates defensively instead of failing a business call. */
    static final int MAX_OBJECT_ID = 64;
    static final int MAX_OBJECT_LABEL = 200;
    static final int MAX_TITLE = 200;
    static final int MAX_BODY = 2000;
    static final int MAX_RECIPIENT = 32;
    static final int MAX_DEDUPE_KEY = 200;

    /** The single {@code notification_setting} row (V26 seed). */
    static final int SETTING_ID = 1;

    private final NotificationMapper notificationMapper;
    private final NotificationSettingMapper settingMapper;
    private final NotificationRecipientResolver recipientResolver;
    private final TransactionTemplate notificationTransaction;

    public NotificationService(NotificationMapper notificationMapper,
                               NotificationSettingMapper settingMapper,
                               NotificationRecipientResolver recipientResolver,
                               PlatformTransactionManager transactionManager) {
        this.notificationMapper = notificationMapper;
        this.settingMapper = settingMapper;
        this.recipientResolver = recipientResolver;
        this.notificationTransaction = new TransactionTemplate(transactionManager);
        this.notificationTransaction.setPropagationBehavior(
            TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Sends one event to its resolved audience. Never throws; an empty audience writes nothing.
     *
     * @param command the event, object, text and audience (design section 3)
     */
    public void notify(NotificationCommand command) {
        if (command == null) {
            return;
        }
        try {
            notificationTransaction.executeWithoutResult(status -> write(command));
        } catch (RuntimeException ex) {
            // The caller is a business use case: a notification must never change its result. The
            // dedupe key means a later retry of the same event would still be a single row.
            log.error("notification dropped: eventKey={}, objectId={}, reason={}",
                command.eventKey(), safe(command.objectId()), ex.getMessage(), ex);
        }
    }

    // ------------------------------------------------------------------ writing

    private void write(NotificationCommand command) {
        List<NotificationTarget> resolved = command.audience().resolve(recipientResolver,
            command.body());
        Map<String, NotificationTarget> targets = distinctTargets(resolved);
        if (targets.isEmpty()) {
            log.debug("notification has no recipient: eventKey={}, objectId={}",
                command.eventKey(), safe(command.objectId()));
            return;
        }
        NotificationSettingPO setting = settingMapper.selectById(SETTING_ID);
        String webhook = setting == null ? null : trimToNull(setting.getWecomWebhook());
        boolean wecom = setting != null && Boolean.TRUE.equals(setting.getWecomEnabled())
            && webhook != null;

        Instant now = Instant.now();
        int written = 0;
        for (NotificationTarget target : targets.values()) {
            written += insert(command, target, NotificationChannel.INAPP, now);
            if (wecom) {
                written += insert(command, target, NotificationChannel.WECOM, now);
            }
        }
        log.debug("notification written: eventKey={}, objectId={}, recipients={}, channels={},"
                + " rows={}", command.eventKey(), safe(command.objectId()), targets.size(),
            wecom ? "INAPP+WECOM" : "INAPP", written);
    }

    /** One row; {@code INSERT IGNORE} makes a repeated occurrence a no-op. */
    private int insert(NotificationCommand command, NotificationTarget target,
                       NotificationChannel channel, Instant now) {
        String recipient = truncate(target.userId().trim(), MAX_RECIPIENT);
        String body = target.body() == null || target.body().isBlank()
            ? command.body() : target.body();
        NotificationPO po = new NotificationPO();
        po.setEventKey(command.eventKey().name());
        po.setObjectType(command.eventKey().objectType().name());
        po.setObjectId(truncate(command.objectId(), MAX_OBJECT_ID));
        po.setObjectLabel(truncateOrNull(command.objectLabel(), MAX_OBJECT_LABEL));
        po.setRecipient(recipient);
        po.setChannel(channel.name());
        po.setTitle(truncate(command.title(), MAX_TITLE));
        po.setBody(truncate(body, MAX_BODY));
        po.setStatus(channel == NotificationChannel.INAPP
            ? NotificationStatus.SENT.name() : NotificationStatus.PENDING.name());
        po.setReadAt(null);
        po.setAttempts(0);
        po.setFailureReason(null);
        po.setDedupeKey(dedupeKey(command, recipient, channel));
        po.setCreatedAt(now);
        po.setSentAt(channel == NotificationChannel.INAPP ? now : null);
        return notificationMapper.insertIgnore(po);
    }

    /**
     * {@code event|objectType|objectId|recipient|channel|ref}. The components are bounded by the
     * columns, so the 200-character limit cannot be reached with the refs this platform supplies
     * (instance/batch/step/freeze ids); the truncation is a guard, not a normal path.
     */
    static String dedupeKey(NotificationCommand command, String recipient,
                            NotificationChannel channel) {
        String key = command.eventKey().name()
            + "|" + command.eventKey().objectType().name()
            + "|" + truncate(command.objectId(), MAX_OBJECT_ID)
            + "|" + recipient
            + "|" + channel.name()
            + "|" + command.ref();
        return truncate(key, MAX_DEDUPE_KEY);
    }

    private static Map<String, NotificationTarget> distinctTargets(List<NotificationTarget> resolved) {
        Map<String, NotificationTarget> targets = new LinkedHashMap<>();
        if (resolved == null) {
            return targets;
        }
        for (NotificationTarget target : resolved) {
            if (target == null || target.userId() == null || target.userId().isBlank()) {
                continue;
            }
            targets.putIfAbsent(target.userId().trim(), target);
        }
        return targets;
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static String truncateOrNull(String value, int max) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return truncate(value, max);
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}