package com.sf.release.domain.freeze;

import java.time.Instant;
import java.util.Objects;

/**
 * One 生产封板 (tech design 4.2): a declared time range in which production releases need the two
 * signatures of design 4.4 ({@code OPS_LEAD} and {@code DEPT_MANAGER}) and in-flight releases are
 * pulled back into approval.
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

    /**
     * True while this freeze is <em>in force</em> at {@code at} (status and half-open range). This
     * is the display question "is production frozen right now"; the admission/review question is
     * {@link #governsAt(Instant, Instant)}.
     */
    public boolean inForceAt(Instant at) {
        return status == FreezeStatus.ACTIVE && at != null
            && !at.isBefore(startAt) && at.isBefore(endAt);
    }

    /**
     * True when this freeze <strong>governs</strong> a release: it is still {@code ACTIVE} and has
     * not ended, and it starts no later than the later of {@code now} and the release's planned
     * time.
     *
     * <p>That is the single predicate of the admission step, the in-flight handling and the
     * pre-execution review (design 4.2/4.5): a freeze declared for next week already governs a
     * release planned for next week, even though it is not in force yet — the decision to hand the
     * release over is taken now, so the requirement is raised now (fail-closed). A freeze that
     * starts after the release is planned does not govern it: that release will be gone before the
     * freeze begins.
     *
     * <p>{@code plannedAt == null} means "no plan known" and degenerates to
     * {@link #inForceAt(Instant)} at {@code now}, which is what the banner (no planned time) needs.
     */
    public boolean governsAt(Instant now, Instant plannedAt) {
        if (status != FreezeStatus.ACTIVE || now == null || !endAt.isAfter(now)) {
            return false;
        }
        Instant threshold = plannedAt == null || plannedAt.isBefore(now) ? now : plannedAt;
        return !startAt.isAfter(threshold);
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