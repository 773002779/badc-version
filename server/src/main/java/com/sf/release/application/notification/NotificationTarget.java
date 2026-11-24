package com.sf.release.application.notification;

import java.util.Objects;

/**
 * One resolved notification recipient and, when it differs from the command body, the body this
 * person must see (design section 4: a role with nobody falls back to the system owner and the body
 * says so; a freeze aggregates each person's own release list).
 *
 * <p>{@code body == null} means "use the command body unchanged": the common case of a task-member
 * event.
 */
public record NotificationTarget(String userId, String body) {

    public NotificationTarget {
        Objects.requireNonNull(userId, "userId must not be null");
    }
}