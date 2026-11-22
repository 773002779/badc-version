package com.sf.release.application;

import java.time.Instant;

/**
 * One 封板 as the page shows it (the 封板 banner and the history list).
 *
 * <p>{@code status} is the stored lifecycle word and {@code statusLabel} its Chinese label;
 * {@code active} answers the separate question "is it in force <em>now</em>", because an
 * {@code ACTIVE} freeze may be scheduled for later (or its {@code end_at} may already have passed
 * while the daily scan has not closed it yet). A page that only looked at {@code status} would show
 * a scheduled freeze as if production were blocked right now.
 */
public record FreezeView(
    long id,
    String scope,
    String scopeLabel,
    Long subsystemId,
    String reason,
    String impact,
    String initiator,
    Instant startAt,
    Instant endAt,
    String status,
    String statusLabel,
    String releasedBy,
    Instant releasedAt,
    boolean active,
    Instant createdAt) {
}