package com.sf.release.domain.window;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * A release window (需求 5.6, tech design 4.1): a weekly rule set plus date exceptions, evaluated
 * in one zone. The application layer decides <em>which</em> window is effective for a subsystem
 * (subsystem override, else the GLOBAL default); this class only answers the time questions.
 *
 * <p><strong>Not configured vs. switched off.</strong> Two states are easily confused and mean
 * opposite things:
 * <ul>
 *   <li><em>not configured</em> — there is no window at all. The application layer returns
 *       {@code null} instead of this object, and the answer is OUTSIDE the window
 *       ({@code configured = false, inWindow = false}): an unconfigured platform must still require
 *       approval, never become "always exempt".</li>
 *   <li><em>switched off</em> — {@code enabled = false}. The operator deliberately removed the
 *       constraint, so every instant is inside ({@code configured = true, inWindow = true}).</li>
 * </ul>
 * The check result carries both {@code configured} and {@code enabled} so the page can say which
 * one applies instead of leaving the operator guessing why something is allowed.
 *
 * <p><strong>Evaluation order</strong> (exception takes precedence over the weekday rules):
 * <ol>
 *   <li>a {@code CLOSED} exception on the point's date closes the whole date;</li>
 *   <li>an {@code OPEN} exception on the point's date opens the whole date, or only its time span
 *       when it has one (and then the point must be inside that span);</li>
 *   <li>without an exception on the date, a cross-midnight {@code OPEN} exception of the previous
 *       date is still open during its tail;</li>
 *   <li>otherwise the weekday rules decide, including the previous day's cross-midnight tail.</li>
 * </ol>
 *
 * <p>All scans are minute-stepped and bounded by {@link #MAX_SEARCH_DAYS} days, so a broken rule
 * cannot make a request loop forever.
 */
public record ReleaseWindow(WindowScope scope, long subsystemId, boolean enabled, ZoneId zoneId,
                            List<WindowRule> rules, List<WindowException> exceptions) {

    /** Sentinel subsystem id of the GLOBAL row (MySQL unique keys treat NULLs as distinct). */
    public static final long GLOBAL_SUBSYSTEM_ID = 0L;

    /** Zone the rules are written in unless the operator configured another one. */
    public static final ZoneId DEFAULT_ZONE = ZoneId.of("Asia/Shanghai");

    /** Longest look-ahead of {@link #nextWindow(LocalDateTime, int)} (design 4.1: 最多 7 天). */
    public static final int MAX_SEARCH_DAYS = 7;

    public ReleaseWindow {
        Objects.requireNonNull(scope, "scope must not be null");
        zoneId = zoneId == null ? DEFAULT_ZONE : zoneId;
        rules = rules == null ? List.of() : List.copyOf(rules);
        exceptions = exceptions == null ? List.of() : List.copyOf(exceptions);
    }

    /** The local date-time of an instant in this window's zone (the domain's only clock seam). */
    public LocalDateTime local(Instant at) {
        return LocalDateTime.ofInstant(at, zoneId);
    }

    /** {@link #covers(LocalDateTime)} for an instant, converted into this window's zone. */
    public boolean covers(Instant at) {
        return covers(local(at));
    }

    /**
     * True when the local point is inside the window. A switched-off window covers everything; a
     * window with rules but no matching exception/rule covers nothing.
     */
    public boolean covers(LocalDateTime at) {
        Objects.requireNonNull(at, "at must not be null");
        if (!enabled) {
            return true;
        }
        LocalDate day = at.toLocalDate();
        WindowException own = exceptionOn(day);
        if (own != null) {
            // The exception on this date is authoritative: CLOSED closes, OPEN opens all day or
            // only its span. A span that contradicts the rules wins, because the operator wrote it
            // for exactly this date.
            return own.mode() == WindowException.Mode.OPEN && own.covers(at);
        }
        WindowException previous = exceptionOn(day.minusDays(1));
        if (previous != null && previous.mode() == WindowException.Mode.OPEN
                && previous.hasTimes() && previous.covers(at)) {
            return true;
        }
        return ruleCovers(at);
    }

    /**
     * The contiguous span that contains {@code at}, if any. Scanning stops at
     * {@link #MAX_SEARCH_DAYS} days in each direction, which is longer than any legal slot
     * (30 minutes to 12 hours) but bounded for consecutive whole-day exceptions.
     */
    public Optional<WindowSpan> spanAt(LocalDateTime at) {
        Objects.requireNonNull(at, "at must not be null");
        if (!enabled) {
            return Optional.empty();
        }
        LocalDateTime point = at.truncatedTo(ChronoUnit.MINUTES);
        if (!covers(point)) {
            return Optional.empty();
        }
        LocalDateTime backLimit = point.minusDays(MAX_SEARCH_DAYS);
        LocalDateTime start = point;
        while (start.isAfter(backLimit) && covers(start.minusMinutes(1))) {
            start = start.minusMinutes(1);
        }
        LocalDateTime forwardLimit = point.plusDays(MAX_SEARCH_DAYS);
        LocalDateTime end = point;
        while (covers(end.plusMinutes(1)) && end.plusMinutes(1).isBefore(forwardLimit)) {
            end = end.plusMinutes(1);
        }
        // `end` is the last covered minute, or the bound while still covered; the span is
        // half-open, so its exclusive end is one minute later.
        return Optional.of(new WindowSpan(start, end.plusMinutes(1)));
    }

    /**
     * The first span <em>after</em> the one containing {@code from} — or the first upcoming span
     * when {@code from} is outside. Searching is minute-stepped and capped at
     * {@link #MAX_SEARCH_DAYS} days (a smaller {@code searchDays} narrows it further). Empty when
     * the window is switched off or nothing opens within the horizon.
     *
     * <p>Skipping the whole current span on purpose: the caller already has that span from
     * {@link #spanAt(LocalDateTime)} and asks "and after this one?".
     */
    public Optional<WindowSpan> nextWindow(LocalDateTime from, int searchDays) {
        Objects.requireNonNull(from, "from must not be null");
        if (!enabled) {
            return Optional.empty();
        }
        int days = Math.min(Math.max(searchDays, 1), MAX_SEARCH_DAYS);
        LocalDateTime cursor = from.truncatedTo(ChronoUnit.MINUTES);
        LocalDateTime limit = cursor.plusDays(days);
        while (!cursor.isAfter(limit) && covers(cursor)) {
            cursor = cursor.plusMinutes(1);
        }
        while (!cursor.isAfter(limit)) {
            if (covers(cursor)) {
                // The horizon bounds where a span may START; the span's own end is resolved
                // independently so a window found just before the horizon still reports its real
                // end (a 22:00-02:00 slot found at day 7 must say 02:00, not "the horizon").
                LocalDateTime endLimit = cursor.plusDays(MAX_SEARCH_DAYS);
                LocalDateTime end = cursor;
                while (covers(end.plusMinutes(1)) && end.plusMinutes(1).isBefore(endLimit)) {
                    end = end.plusMinutes(1);
                }
                return Optional.of(new WindowSpan(cursor, end.plusMinutes(1)));
            }
            cursor = cursor.plusMinutes(1);
        }
        return Optional.empty();
    }

    /** The exception configured for a date, or {@code null}. At most one per date is persisted. */
    public WindowException exceptionOn(LocalDate date) {
        for (WindowException exception : exceptions) {
            if (exception.date().equals(date)) {
                return exception;
            }
        }
        return null;
    }

    private boolean ruleCovers(LocalDateTime at) {
        int today = at.getDayOfWeek().getValue();
        int yesterday = at.minusDays(1).getDayOfWeek().getValue();
        LocalTime time = at.toLocalTime();
        for (WindowRule rule : rules) {
            if (rule.dayOfWeek() == today && rule.coversOwnDay(time)) {
                return true;
            }
            if (rule.dayOfWeek() == yesterday && rule.coversNextDay(time)) {
                return true;
            }
        }
        return false;
    }
}