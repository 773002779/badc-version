package com.sf.release.web.dto;

import com.sf.release.application.WindowCheckView;
import com.sf.release.application.WindowSpanView;
import com.sf.release.application.WindowView;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

/**
 * Request and response bodies of {@code /api/v1/windows} (tech design 4.8).
 *
 * <p>Responses are built from the application read models, so this layer never touches a domain
 * record or enum. Dates travel as ISO-8601 dates ({@code "2026-11-25"}), instants as ISO-8601 UTC
 * ({@code "2026-11-22T14:00:00Z"}) and local times as ISO-8601 times — Jackson renders
 * {@code LocalTime} as {@code "22:00:00"} (it always writes the seconds field) while the
 * deserializer accepts both {@code "22:00"} and {@code "22:00:00"}. Those are the Jackson defaults
 * of this project; no per-field format is imposed, so a client that echoes a stored value back
 * always round-trips.
 *
 * <p>{@link WindowResponse#configured()} keeps the two "allowed anyway" cases apart: an
 * unconfigured platform answers {@code configured:false} with {@code enabled:false} (nothing is
 * inside, so a release still needs approval), while a configured window with {@code enabled:false}
 * is a deliberately switched-off constraint (everything is inside).
 */
public final class WindowDtos {

    private WindowDtos() {
    }

    /** One weekly slot of the save form. */
    public record RuleRequest(int dayOfWeek, LocalTime start, LocalTime end) {
    }

    /** One date exception of the save form; {@code mode} defaults to {@code OPEN} on the server. */
    public record ExceptionRequest(LocalDate date, String mode, LocalTime start, LocalTime end) {
    }

    /**
     * Save form of one window. {@code enabled} defaults to true; {@code rules}/{@code exceptions}
     * may be omitted (an empty window: configured but nothing inside, which is a legitimate way to
     * say "no production window at all").
     */
    public record SaveWindowRequest(
        String scope,
        Long subsystemId,
        Boolean enabled,
        String timezone,
        List<RuleRequest> rules,
        List<ExceptionRequest> exceptions) {
    }

    /** Check form: which subsystem, and when (omitted {@code at} means now). */
    public record CheckWindowRequest(Long subsystemId, Instant at) {
    }

    /** One weekly slot as stored. */
    public record RuleResponse(int dayOfWeek, LocalTime start, LocalTime end) {

        static RuleResponse of(WindowView.RuleView rule) {
            return new RuleResponse(rule.dayOfWeek(), rule.start(), rule.end());
        }
    }

    /** One date exception as stored. */
    public record ExceptionResponse(LocalDate date, String mode, LocalTime start, LocalTime end) {

        static ExceptionResponse of(WindowView.ExceptionView exception) {
            return new ExceptionResponse(exception.date(), exception.mode(), exception.start(),
                exception.end());
        }
    }

    /**
     * The effective window. {@code inherited} says the subsystem fell back to the GLOBAL default;
     * {@code warnings} carries the short/long-slot notes of the last save (empty on a read).
     */
    public record WindowResponse(
        boolean configured,
        Long id,
        String scope,
        long subsystemId,
        boolean enabled,
        boolean inherited,
        String timezone,
        List<RuleResponse> rules,
        List<ExceptionResponse> exceptions,
        List<String> warnings) {

        /** No window configured: every other field is empty/false. */
        public static WindowResponse absent() {
            return new WindowResponse(false, null, null, 0L, false, false, null, List.of(),
                List.of(), List.of());
        }

        public static WindowResponse of(WindowView view, List<String> warnings) {
            return new WindowResponse(true, view.id(), view.scope(), view.subsystemId(),
                view.enabled(), view.inherited(), view.timezone(),
                view.rules().stream().map(RuleResponse::of).toList(),
                view.exceptions().stream().map(ExceptionResponse::of).toList(),
                warnings == null ? List.of() : List.copyOf(warnings));
        }
    }

    /** A span of the window, in the window's local time. */
    public record SpanResponse(LocalDateTime start, LocalDateTime end) {

        static SpanResponse of(WindowSpanView span) {
            return span == null ? null : new SpanResponse(span.start(), span.end());
        }
    }

    /**
     * The check answer. {@code configured}/{@code enabled} tell the page which of the two
     * "allowed" cases applies; {@code windowId} is null when nothing is configured.
     */
    public record WindowCheckResponse(
        boolean inWindow,
        SpanResponse span,
        SpanResponse next,
        boolean configured,
        Long windowId,
        boolean enabled) {

        public static WindowCheckResponse of(WindowCheckView view) {
            return new WindowCheckResponse(view.inWindow(), SpanResponse.of(view.span()),
                SpanResponse.of(view.next()), view.configured(), view.windowId(), view.enabled());
        }
    }
}