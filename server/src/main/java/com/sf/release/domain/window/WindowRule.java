package com.sf.release.domain.window;

import java.time.Duration;
import java.time.LocalTime;
import java.util.Objects;

/**
 * One recurring weekly slot of a {@link ReleaseWindow}: an ISO weekday plus a local time span.
 *
 * <p>The span is half-open, {@code [start, end)}: a release planned exactly at {@code start} is
 * inside the window, one planned exactly at {@code end} is outside. When {@code end} is before
 * {@code start} the slot <strong>crosses midnight</strong>: {@code Monday 22:00-02:00} covers
 * Monday 22:00-24:00 and Tuesday 00:00-02:00. There is no separate "which date does it belong to"
 * concept — a point is covered iff it falls in the slot's same-day part or in the previous day's
 * tail (tech design 4.1).
 *
 * <p>{@code start == end} is degenerate: it can be read as "zero length" or as "the whole day", so
 * the application service rejects it instead of guessing. The domain treats it as never covering.
 */
public record WindowRule(int dayOfWeek, LocalTime start, LocalTime end) {

    /** ISO-8601 weekday of Monday. */
    public static final int MIN_DAY_OF_WEEK = 1;
    /** ISO-8601 weekday of Sunday. */
    public static final int MAX_DAY_OF_WEEK = 7;

    public WindowRule {
        if (dayOfWeek < MIN_DAY_OF_WEEK || dayOfWeek > MAX_DAY_OF_WEEK) {
            throw new IllegalArgumentException("dayOfWeek must be 1..7: " + dayOfWeek);
        }
        Objects.requireNonNull(start, "start must not be null");
        Objects.requireNonNull(end, "end must not be null");
    }

    /** True when the slot ends on the following day, for example 22:00-02:00. */
    public boolean crossesMidnight() {
        return end.isBefore(start);
    }

    /** True when both bounds are equal; rejected by the service, never covers anything here. */
    public boolean degenerate() {
        return start.equals(end);
    }

    /**
     * Covers the time of day on this rule's own weekday. A cross-midnight slot covers everything
     * from {@code start} to midnight here; its tail is {@link #coversNextDay(LocalTime)}.
     */
    public boolean coversOwnDay(LocalTime time) {
        if (degenerate()) {
            return false;
        }
        if (crossesMidnight()) {
            return !time.isBefore(start);
        }
        return !time.isBefore(start) && time.isBefore(end);
    }

    /** Covers the time of day on the weekday after this rule's own weekday (tail only). */
    public boolean coversNextDay(LocalTime time) {
        return crossesMidnight() && time.isBefore(end);
    }

    /**
     * Length of the slot, measured across midnight for a crossing one (22:00-02:00 is four hours).
     * The service uses this to warn about slots shorter than 30 minutes or longer than 12 hours.
     */
    public Duration length() {
        Duration span = Duration.between(start, end);
        return span.isNegative() ? span.plusDays(1) : span;
    }
}