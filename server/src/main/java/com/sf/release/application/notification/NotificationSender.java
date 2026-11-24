package com.sf.release.application.notification;

/**
 * A delivery channel of the notification subsystem (design section 1: only the interface and the
 * channel enum are kept for e-mail/Feishu; implementations may come later).
 *
 * <p>A sender is called only from {@link NotificationDispatchService}, never from a business
 * transaction. Implementations throw on failure; the dispatcher owns the one retry and the row
 * outcome.
 */
public interface NotificationSender {

    /**
     * Delivers one message to {@code webhook}.
     *
     * @throws NotificationSendException the message could not be delivered
     */
    void send(String webhook, NotificationMessage message);
}