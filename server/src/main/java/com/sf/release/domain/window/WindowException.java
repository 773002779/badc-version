package com.sf.release.domain.window;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Objects;

/**
 * One calendar-date exception of a {@link ReleaseWindow} (holiday, platform freeze, one-off
 * evening release). An exception is authoritative for its date: it replaces the weekday rules
 * instead of adding to them (tech design 4.1).
 *
 * <ul>
 *   <li>{@link Mode#CLOSED} closes the whole date, whatever the weekday rules say;</li>
 *   <li>{@link Mode#OPEN} without times opens the whole date;</li>
 *   <li>{@link Mode#OPEN} with times opens only that span. Like a rule, the span may cross
 *       midnight ({@code 22:00-02:00} covers the date's evening and the following day's night),
 *       and its tail is honoured on the following date unless that date has an exception of its
 *       own.</li>
 * </ul>
 *
 * <p>A {@code CLOSED} exception therefore also wins over the previous date's cross-midnight tail:
 * an operator who closed a date meant the date, not "except the 00:00-02:00 remainder".
 */
public record WindowException(LocalDate date, Mode mode, LocalTime start, LocalTime end) {

    /** The two exception kinds; {@code CLOSED} ignores the time bounds. */
    public enum Mode {
        OPEN,
        CLOSED
    }

    public WindowException {
        Objects.requireNonNull(date, "date must not be null");
        Objects.requireNonNull(mode, "mode must not be null");
    }

    /** An open date without times: the whole day is inside the window. */
    public static WindowException openAllDay(LocalDate date) {
        return new WindowException(date, Mode.OPEN, null, null);
    }

    /** A fully closed date. */
    public static WindowException closed(LocalDate date) {
        return new WindowException(date, Mode.CLOSED, null, null);
    }

    /** True when this OPEN exception carries a time span; a partial bound is not a span. */
    public boolean hasTimes() {
        return mode == Mode.OPEN && start != null && end != null;
    }

    /** True when the time span ends on the following day. */
    public boolean crossesMidnight() {
        return hasTimes() && end.isBefore(start);
    }

    /**
     * Covers the given local point: its own date when this exception is on it, or the tail when
     * this exception is on the previous date and crosses midnight.
     */
    public boolean covers(LocalDateTime at) {
        LocalDate day = at.toLocalDate();
        LocalTime time = at.toLocalTime();
        if (day.equals(date)) {
            if (mode == Mode.CLOSED) {
                return true;
            }
            if (!hasTimes() || start.equals(end)) {
                return true;
            }
            if (crossesMidnight()) {
                return !time.isBefore(start);
            }
            return !time.isBefore(start) && time.isBefore(end);
        }
        if (mode == Mode.OPEN && crossesMidnight() && day.equals(date.plusDays(1))) {
            return time.isBefore(end);
        }
        return false;
    }
}