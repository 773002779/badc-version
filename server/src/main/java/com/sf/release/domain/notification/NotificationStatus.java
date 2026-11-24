package com.sf.release.domain.notification;

/**
 * Delivery status of one notification row (design section 2).
 *
 * <p>An in-app row is created {@link #SENT}: it is delivered to the recipient's notification centre
 * the moment it exists, and {@code read_at} carries the unread state. A WeCom row starts
 * {@link #PENDING} and ends {@link #SENT} or, after the one allowed retry, {@link #FAILED}. There is
 * no automatic re-delivery of a {@code FAILED} row in this phase (design section 1).
 */
public enum NotificationStatus {
    PENDING,
    SENT,
    FAILED
}