package com.sf.release.domain.notification;

/**
 * The notification channels of P4-B (design section 5). Only the two implemented channels are named:
 * the design deliberately leaves e-mail and Feishu out (Q5 is still open), so adding one later is a
 * new constant plus a {@code NotificationSender} implementation, not a schema change.
 */
public enum NotificationChannel {
    /** In-app: delivered when the row is written; {@code read_at} tracks the read. */
    INAPP,
    /** WeCom robot: written as {@code PENDING}, delivered by {@code NotificationDispatchJob}. */
    WECOM
}