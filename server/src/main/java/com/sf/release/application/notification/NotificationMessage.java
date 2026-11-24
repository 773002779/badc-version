package com.sf.release.application.notification;

import java.time.Instant;

/**
 * The display fields a {@code NotificationSender} needs (design section 5): the same title/body the
 * in-app row shows, plus the object label and creation time that the WeCom markdown signs with.
 */
public record NotificationMessage(String title, String body, String objectLabel,
                                  Instant createdAt) {
}