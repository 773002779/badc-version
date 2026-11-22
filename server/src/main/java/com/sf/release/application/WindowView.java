package com.sf.release.application;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * Read model of one effective release window (see {@code WindowService.effectiveWindow}).
 *
 * <p>{@code inherited = true} means the caller asked for a subsystem and no subsystem override
 * exists, so the GLOBAL default applies; the page shows that instead of pretending the subsystem
 * has a window of its own. {@code enabled = false} means the operator switched the constraint off
 * ("always inside"), which is not the same as "no window configured" — the latter makes
 * {@code effectiveWindow} return {@code null} and the page renders {@code configured: false}.
 *
 * <p>The nested {@link RuleView}/{@link ExceptionView} records are the flattened domain records:
 * they keep the web layer free of {@code domain} types.
 */
public record WindowView(Long id, String scope, long subsystemId, boolean enabled, String timezone,
                         List<RuleView> rules, List<ExceptionView> exceptions, boolean inherited) {

    public WindowView {
        rules = rules == null ? List.of() : List.copyOf(rules);
        exceptions = exceptions == null ? List.of() : List.copyOf(exceptions);
    }

    /** One weekly slot; {@code dayOfWeek} is the ISO weekday (1 = Monday .. 7 = Sunday). */
    public record RuleView(int dayOfWeek, LocalTime start, LocalTime end) {
    }

    /** One date exception; {@code mode} is {@code OPEN} or {@code CLOSED}. */
    public record ExceptionView(LocalDate date, String mode, LocalTime start, LocalTime end) {
    }
}