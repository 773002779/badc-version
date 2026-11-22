package com.sf.release.window;

import com.sf.release.domain.window.ReleaseWindow;
import com.sf.release.domain.window.WindowException;
import com.sf.release.domain.window.WindowRule;
import com.sf.release.domain.window.WindowScope;
import com.sf.release.domain.window.WindowSpan;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The pure release-window decision (tech design 4.1): weekday rules, cross-midnight spans, date
 * exceptions and the "next window" search. No Spring, no database.
 *
 * <p>The rules pinned here are the ones a release must be able to trust:
 * <ul>
 *   <li>a {@code 22:00-02:00} rule covers the previous weekday's evening <em>and</em> the next
 *       day's early hours — a 01:00 planned time belongs to the previous day's rule;</li>
 *   <li>an exception on a date is authoritative for that date: {@code CLOSED} closes it, an
 *       {@code OPEN} span opens only that span, an {@code OPEN} without times opens the day;</li>
 *   <li>a switched-off window is always inside, a window with no rules is always outside;</li>
 *   <li>{@code nextWindow} skips the span the caller is already standing in.</li>
 * </ul>
 */
class ReleaseWindowTest {

    /** A known Monday; the ISO weekday contract is asserted first. */
    private static final LocalDate MONDAY = LocalDate.of(2026, 11, 23);
    private static final LocalDate TUESDAY = MONDAY.plusDays(1);
    private static final LocalDate WEDNESDAY = MONDAY.plusDays(2);
    private static final LocalDate FRIDAY = MONDAY.plusDays(4);
    private static final LocalDate SATURDAY = MONDAY.plusDays(5);

    private static final int MON = 1;
    private static final int TUE = 2;
    private static final int SAT = 6;

    private static final LocalTime NINE = LocalTime.of(9, 0);
    private static final LocalTime TEN = LocalTime.of(10, 0);
    private static final LocalTime EIGHTEEN = LocalTime.of(18, 0);
    private static final LocalTime TWENTY_TWO = LocalTime.of(22, 0);
    private static final LocalTime TWO = LocalTime.of(2, 0);

    @Test
    void the_fixture_dates_are_the_weekdays_the_test_assumes() {
        assertThat(MONDAY.getDayOfWeek()).isEqualTo(DayOfWeek.MONDAY);
        assertThat(TUESDAY.getDayOfWeek()).isEqualTo(DayOfWeek.TUESDAY);
        assertThat(SATURDAY.getDayOfWeek()).isEqualTo(DayOfWeek.SATURDAY);
    }

    // ------------------------------------------------------------------ weekday rules

    @Test
    void a_normal_rule_covers_its_span_half_open() {
        ReleaseWindow window = window(new WindowRule(MON, NINE, EIGHTEEN));

        assertThat(window.covers(at(MONDAY, 8, 59))).as("just before the start").isFalse();
        assertThat(window.covers(at(MONDAY, 9, 0))).as("the start is inside").isTrue();
        assertThat(window.covers(at(MONDAY, 17, 59))).isTrue();
        assertThat(window.covers(at(MONDAY, 18, 0))).as("the end is outside").isFalse();
        assertThat(window.covers(at(TUESDAY, 10, 0))).as("another weekday is outside").isFalse();
    }

    @Test
    void a_cross_midnight_rule_covers_the_next_days_early_hours() {
        ReleaseWindow window = window(new WindowRule(MON, TWENTY_TWO, TWO));

        assertThat(window.covers(at(MONDAY, 21, 59))).isFalse();
        assertThat(window.covers(at(MONDAY, 22, 0))).isTrue();
        assertThat(window.covers(at(MONDAY, 23, 59))).isTrue();
        assertThat(window.covers(at(TUESDAY, 0, 0))).isTrue();
        assertThat(window.covers(at(TUESDAY, 1, 0)))
            .as("01:00 belongs to the previous day's 22:00-02:00 rule")
            .isTrue();
        assertThat(window.covers(at(TUESDAY, 2, 0))).as("the tail ends half-open").isFalse();
        assertThat(window.covers(at(WEDNESDAY, 1, 0)))
            .as("the rule is for Monday, not every night")
            .isFalse();
        assertThat(window.covers(at(MONDAY, 1, 0)))
            .as("Monday 01:00 belongs to Sunday's rule, not Monday's own evening")
            .isFalse();
    }

    @Test
    void a_window_without_rules_covers_nothing() {
        assertThat(window().covers(at(MONDAY, 10, 0))).isFalse();
    }

    @Test
    void a_switched_off_window_covers_everything_and_has_no_next_span() {
        ReleaseWindow disabled = new ReleaseWindow(WindowScope.GLOBAL, 0L, false,
            ReleaseWindow.DEFAULT_ZONE, List.of(), List.of());

        assertThat(disabled.covers(at(MONDAY, 3, 0))).isTrue();
        assertThat(disabled.covers(at(SATURDAY, 23, 0))).isTrue();
        assertThat(disabled.spanAt(at(MONDAY, 3, 0))).isEmpty();
        assertThat(disabled.nextWindow(at(MONDAY, 3, 0), 7)).isEmpty();
    }

    @Test
    void a_rule_length_is_measured_across_midnight() {
        assertThat(new WindowRule(MON, NINE, EIGHTEEN).length()).isEqualTo(Duration.ofHours(9));
        assertThat(new WindowRule(MON, TWENTY_TWO, TWO).length()).isEqualTo(Duration.ofHours(4));
    }

    // ------------------------------------------------------------------ exceptions

    @Test
    void a_closed_exception_closes_the_whole_date_but_not_other_weeks() {
        ReleaseWindow window = withExceptions(
            List.of(WindowException.closed(MONDAY)),
            new WindowRule(MON, NINE, EIGHTEEN));

        assertThat(window.covers(at(MONDAY, 10, 0))).isFalse();
        assertThat(window.covers(at(MONDAY.plusWeeks(1), 10, 0)))
            .as("only the configured date is closed")
            .isTrue();
    }

    @Test
    void a_closed_exception_wins_over_the_previous_days_tail() {
        ReleaseWindow window = withExceptions(
            List.of(WindowException.closed(TUESDAY)),
            new WindowRule(MON, TWENTY_TWO, TWO));

        assertThat(window.covers(at(TUESDAY, 1, 0)))
            .as("01:00 on a closed date is closed, even though Monday's rule would cover it")
            .isFalse();
        assertThat(window.covers(at(MONDAY, 23, 0)))
            .as("the closed date has no effect on the previous evening")
            .isTrue();
    }

    @Test
    void an_open_exception_with_times_opens_only_that_span() {
        ReleaseWindow window = withExceptions(
            List.of(new WindowException(FRIDAY, WindowException.Mode.OPEN,
                LocalTime.of(20, 0), TWENTY_TWO)),
            new WindowRule(MON, NINE, EIGHTEEN));

        assertThat(window.covers(at(FRIDAY, 19, 0))).isFalse();
        assertThat(window.covers(at(FRIDAY, 20, 0))).isTrue();
        assertThat(window.covers(at(FRIDAY, 21, 30))).isTrue();
        assertThat(window.covers(at(FRIDAY, 22, 0))).isFalse();
    }

    @Test
    void an_open_exception_with_times_replaces_the_rule_of_the_same_date() {
        ReleaseWindow window = withExceptions(
            List.of(new WindowException(MONDAY, WindowException.Mode.OPEN,
                LocalTime.of(20, 0), TWENTY_TWO)),
            new WindowRule(MON, NINE, EIGHTEEN));

        assertThat(window.covers(at(MONDAY, 10, 0)))
            .as("the exception is authoritative: the 09:00-18:00 rule does not apply")
            .isFalse();
        assertThat(window.covers(at(MONDAY, 21, 0))).isTrue();
    }

    @Test
    void an_open_exception_without_times_opens_the_whole_date() {
        ReleaseWindow window = withExceptions(List.of(WindowException.openAllDay(SATURDAY)));

        assertThat(window.covers(at(SATURDAY, 0, 0))).isTrue();
        assertThat(window.covers(at(SATURDAY, 3, 0))).isTrue();
        assertThat(window.covers(at(SATURDAY, 23, 59))).isTrue();
        assertThat(window.covers(at(FRIDAY, 12, 0))).isFalse();
    }

    @Test
    void an_open_exception_span_may_cross_midnight() {
        ReleaseWindow window = withExceptions(
            List.of(new WindowException(MONDAY, WindowException.Mode.OPEN, TWENTY_TWO, TWO)));

        assertThat(window.covers(at(MONDAY, 23, 0))).isTrue();
        assertThat(window.covers(at(TUESDAY, 1, 0)))
            .as("the open span's tail is honoured on the next date")
            .isTrue();
        assertThat(window.covers(at(TUESDAY, 2, 0))).isFalse();
        assertThat(window.covers(at(MONDAY, 21, 0))).isFalse();
    }

    // ------------------------------------------------------------------ spanAt

    @Test
    void span_at_returns_the_containing_span() {
        ReleaseWindow window = window(new WindowRule(MON, TWENTY_TWO, TWO));

        Optional<WindowSpan> span = window.spanAt(at(MONDAY, 23, 0));

        assertThat(span).isPresent();
        assertThat(span.orElseThrow().start()).isEqualTo(at(MONDAY, 22, 0));
        assertThat(span.orElseThrow().end()).isEqualTo(at(TUESDAY, 2, 0));
    }

    @Test
    void span_at_is_empty_outside() {
        ReleaseWindow window = window(new WindowRule(MON, TWENTY_TWO, TWO));

        assertThat(window.spanAt(at(MONDAY, 21, 0))).isEmpty();
    }

    @Test
    void span_at_of_a_whole_day_exception_is_that_day() {
        ReleaseWindow window = withExceptions(List.of(WindowException.openAllDay(SATURDAY)));

        WindowSpan span = window.spanAt(at(SATURDAY, 12, 0)).orElseThrow();

        assertThat(span.start()).isEqualTo(SATURDAY.atStartOfDay());
        assertThat(span.end()).isEqualTo(SATURDAY.plusDays(1).atStartOfDay());
    }

    // ------------------------------------------------------------------ nextWindow

    @Test
    void next_window_from_before_the_span_returns_that_span() {
        ReleaseWindow window = window(new WindowRule(MON, TWENTY_TWO, TWO));

        WindowSpan next = window.nextWindow(at(MONDAY, 21, 0), 7).orElseThrow();

        assertThat(next.start()).isEqualTo(at(MONDAY, 22, 0));
        assertThat(next.end()).isEqualTo(at(TUESDAY, 2, 0));
    }

    @Test
    void next_window_inside_a_span_skips_the_whole_span() {
        ReleaseWindow window = window(new WindowRule(MON, TWENTY_TWO, TWO));

        WindowSpan next = window.nextWindow(at(MONDAY, 23, 0), 7).orElseThrow();

        assertThat(next.start())
            .as("Monday's 22:00-02:00 is the only slot, so the next one is a week later")
            .isEqualTo(at(MONDAY.plusWeeks(1), 22, 0));
        assertThat(next.end()).isEqualTo(at(TUESDAY.plusWeeks(1), 2, 0));
    }

    @Test
    void next_window_of_a_daily_rule_is_the_next_day() {
        ReleaseWindow window = window(new WindowRule(MON, TWENTY_TWO, TWO),
            new WindowRule(TUE, TWENTY_TWO, TWO));

        WindowSpan next = window.nextWindow(at(MONDAY, 23, 0), 7).orElseThrow();

        assertThat(next.start())
            .as("the span being stood in is skipped, the Tuesday slot is next")
            .isEqualTo(at(TUESDAY, 22, 0));
        assertThat(next.end()).isEqualTo(at(WEDNESDAY, 2, 0));
    }

    @Test
    void next_window_finds_the_next_weekday_occurrence() {
        ReleaseWindow window = window(new WindowRule(MON, NINE, TEN));

        assertThat(window.nextWindow(at(TUESDAY, 12, 0), 7).orElseThrow().start())
            .isEqualTo(at(MONDAY.plusWeeks(1), 9, 0));
    }

    @Test
    void next_window_respects_the_search_horizon() {
        ReleaseWindow window = window(new WindowRule(MON, NINE, TEN));

        assertThat(window.nextWindow(at(TUESDAY, 12, 0), 3))
            .as("the next Monday is six days away")
            .isEmpty();
    }

    @Test
    void next_window_uses_an_open_exception_span() {
        ReleaseWindow window = window(new WindowRule(SAT, LocalTime.of(8, 0), LocalTime.of(9, 0)));

        WindowSpan next = window.nextWindow(at(FRIDAY, 12, 0), 7).orElseThrow();

        assertThat(next.start()).isEqualTo(at(SATURDAY, 8, 0));
        assertThat(next.end()).isEqualTo(at(SATURDAY, 9, 0));
    }

    @Test
    void next_window_of_a_whole_day_exception_is_the_whole_next_day() {
        ReleaseWindow window = withExceptions(List.of(WindowException.openAllDay(SATURDAY)));

        WindowSpan next = window.nextWindow(at(FRIDAY, 12, 0), 7).orElseThrow();

        assertThat(next.start()).isEqualTo(SATURDAY.atStartOfDay());
        assertThat(next.end()).isEqualTo(SATURDAY.plusDays(1).atStartOfDay());
    }

    // ------------------------------------------------------------------ fixtures

    private static ReleaseWindow window(WindowRule... rules) {
        return new ReleaseWindow(WindowScope.GLOBAL, 0L, true, ReleaseWindow.DEFAULT_ZONE,
            List.of(rules), List.of());
    }

    private static ReleaseWindow withExceptions(List<WindowException> exceptions,
                                                WindowRule... rules) {
        return new ReleaseWindow(WindowScope.GLOBAL, 0L, true, ReleaseWindow.DEFAULT_ZONE,
            List.of(rules), exceptions);
    }

    private static LocalDateTime at(LocalDate date, int hour, int minute) {
        return date.atTime(hour, minute);
    }
}