package com.sf.release.application.notification;

import com.sf.release.domain.notification.NotificationEventKey;

import java.util.Objects;

/**
 * What a trigger point hands to {@link NotificationService#notify(NotificationCommand)} (design
 * section 3: "触发点只调用 notify(cmd)").
 *
 * <p><strong>The object type is not a parameter.</strong> It is read from the event key, so an event
 * and its object can never disagree, and the dedupe key
 * {@code event|objectType|objectId|recipient|channel|ref} has one builder.
 *
 * <p><strong>{@code ref}</strong> is the per-occurrence discriminator the trigger supplies (instance
 * id, batch number, approval step, freeze id, ...). Two triggers of the same event for different
 * occurrences therefore produce different rows; repeating the same occurrence produces none.
 *
 * <p>{@code objectLabel} is optional display text (the publish number a click opens, the freeze
 * scope); {@code title}/{@code body} are the plain text the page shows and the WeCom markdown
 * derives from.
 */
public record NotificationCommand(
    NotificationEventKey eventKey,
    String objectId,
    String objectLabel,
    String title,
    String body,
    String ref,
    NotificationAudience audience) {

    public NotificationCommand {
        Objects.requireNonNull(eventKey, "eventKey must not be null");
        Objects.requireNonNull(audience, "audience must not be null");
        objectId = objectId == null ? "" : objectId;
        ref = ref == null ? "" : ref;
        title = title == null || title.isBlank() ? eventKey.label() : title;
        body = body == null ? "" : body;
    }
}