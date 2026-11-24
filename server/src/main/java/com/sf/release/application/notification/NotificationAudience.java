package com.sf.release.application.notification;

import java.util.List;

/**
 * Resolves the recipients of one notification from the event/object facts, in the application layer
 * (design section 4). It receives the base body so a fallback can annotate it and a freeze can append
 * its per-recipient release list; it never touches the web layer.
 *
 * <p>The functional shape keeps {@code NotificationService.notify(cmd)} as the only entry point of a
 * trigger point: the command carries an audience, not a pre-resolved recipient list, so a trigger
 * point never queries members or roles itself.
 */
@FunctionalInterface
public interface NotificationAudience {

    /** Empty result means "nobody to notify": nothing is written. */
    List<NotificationTarget> resolve(NotificationRecipientResolver resolver, String baseBody);
}