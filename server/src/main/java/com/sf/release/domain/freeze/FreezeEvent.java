package com.sf.release.domain.freeze;

import java.time.Instant;
import java.util.Objects;

/**
 * One 生产封板 (tech design 4.2): a declared time range in which production releases need a second
 * signature ({@code DEPT_MANAGER}) and in-flight releases are pulled back into approval.
 *
 * <p><strong>In force</strong> is {@code status == ACTIVE} <em>and</em> {@code start_at <= at <
 * end_at}; the half-open range means a freeze that ends exactly at {@code at} no longer blocks and
 * one that starts at {@code at} does. Declaring a freeze makes it {@link FreezeStatus#ACTIVE}
 * immediately, so a future-dated declaration is already recorded but only in force from its start.
 *
 * <p><strong>Scope.</strong> {@link FreezeScope#GLOBAL} applies to every system; a
 * {@link FreezeScope#SUBSYSTEM} freeze keeps the target in {@code subsystemId} and applies to that
 * system only. A GLOBAL freeze stores a {@code null} subsystem id (the table has no unique key that
 * would need a sentinel).
 *
 * <p>Rows are immutable history: {@link #released(String, Instant)} and {@link #expired(Instant)}
 * return a copy, a freeze is never deleted, and the release actor/time are the audit trail.
 */
public record FreezeEvent(
    Long id,
    FreezeScope scope,
    Long subsystemId,
    String reason,
    String impact,
    String initiatorId,
    Instant startAt,
    Instant endAt,
    FreezeStatus status,
    String releasedBy,
    Instant releasedAt,
    Instant createdAt) {

    /** Longest stored text of {@code reason}/{@code impact}, matching the DDL. */
    public static final int MAX_TEXT = 512;

    /** Longest stored user id ({@code initiator_id}/{@code released_by}), matching the DDL. */
    public static final int MAX_USER_ID = 32;

    public FreezeEvent {
        Objects.requireNonNull(scope, "scope is required");
        Objects.requireNonNull(startAt, "startAt is required");
        Objects.requireNonNull(endAt, "endAt is required");
        if (!endAt.isAfter(startAt)) {
            throw new IllegalArgumentException(
                "a freeze must end after it starts: " + startAt + " .. " + endAt);
        }
        reason = trimToNull(reason);
        impact = trimToNull(impact);
        initiatorId = trimToNull(initiatorId);
        releasedBy = trimToNull(releasedBy);
        status = status == null ? FreezeStatus.ACTIVE : status;
    }

    /** True while this freeze blocks production at {@code at} (status and half-open range). */
    public boolean inForceAt(Instant at) {
        return status == FreezeStatus.ACTIVE && at != null
            && !at.isBefore(startAt) && at.isBefore(endAt);
    }

    /** True when a caller of {@code subsystemId} is affected by this freeze. */
    public boolean appliesTo(long subsystemId) {
        if (scope == FreezeScope.GLOBAL) {
            return true;
        }
        return subsystemId > 0 && Objects.equals(this.subsystemId, subsystemId);
    }

    /**
     * The same freeze, lifted by {@code actor} at {@code at}.
     *
     * @throws IllegalStateException the freeze is not {@link FreezeStatus#ACTIVE} any more
     */
    public FreezeEvent released(String actor, Instant at) {
        if (status != FreezeStatus.ACTIVE) {
            throw new IllegalStateException("freeze " + id + " is not active: " + status);
        }
        return new FreezeEvent(id, scope, subsystemId, reason, impact, initiatorId, startAt, endAt,
            FreezeStatus.RELEASED, trimToNull(actor), at, createdAt);
    }

    /**
     * The same freeze, closed by the expiry scan.
     *
     * @throws IllegalStateException the freeze is not {@link FreezeStatus#ACTIVE} any more
     */
    public FreezeEvent expired(Instant at) {
        if (status != FreezeStatus.ACTIVE) {
            throw new IllegalStateException("freeze " + id + " is not active: " + status);
        }
        return new FreezeEvent(id, scope, subsystemId, reason, impact, initiatorId, startAt, endAt,
            FreezeStatus.EXPIRED, releasedBy, releasedAt, createdAt);
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}