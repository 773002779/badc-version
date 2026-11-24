package com.sf.release.application.notification;

/**
 * A delivery attempt failed. The message names the cause only (HTTP status, engine error, host); the
 * full webhook is never included, because it is a sensitive configuration value (design section 5).
 */
public class NotificationSendException extends RuntimeException {

    public NotificationSendException(String message) {
        super(message);
    }

    public NotificationSendException(String message, Throwable cause) {
        super(message, cause);
    }
}