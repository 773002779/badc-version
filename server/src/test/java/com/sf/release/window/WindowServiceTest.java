package com.sf.release.window;

import com.sf.release.application.WindowCheckView;
import com.sf.release.application.WindowService;
import com.sf.release.application.WindowService.ExceptionInput;
import com.sf.release.application.WindowService.RuleInput;
import com.sf.release.application.WindowService.SaveCommand;
import com.sf.release.application.WindowService.SaveResult;
import com.sf.release.application.WindowView;
import com.sf.release.common.BizError;
import com.sf.release.common.BizException;
import com.sf.release.infra.repo.ReleaseWindowMapper;
import com.sf.release.infra.repo.SubsystemMapper;
import com.sf.release.infra.repo.WindowExceptionMapper;
import com.sf.release.infra.repo.WindowRuleMapper;
import com.sf.release.infra.repo.po.ReleaseWindowPO;
import com.sf.release.infra.repo.po.WindowRulePO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The 发布窗口 service against the real test schema: persistence of rules and exceptions, the
 * global→subsystem fallback, the save validation, the short/long-slot warnings and the check
 * answer (in window, span, next, configured, windowId).
 *
 * <p>Every test seeds its own rows: the test schema is cleaned when the Spring context is created,
 * not between test methods.
 */
@SpringBootTest
@ActiveProfiles("test")
class WindowServiceTest {

    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final LocalDate MONDAY = LocalDate.of(2026, 11, 23);
    private static final LocalDate TUESDAY = MONDAY.plusDays(1);
    private static final int MON = 1;
    private static final int TUE = 2;
    private static final LocalTime NINE = LocalTime.of(9, 0);
    private static final LocalTime TEN = LocalTime.of(10, 0);
    private static final LocalTime TWELVE = LocalTime.of(12, 0);
    private static final LocalTime EIGHTEEN = LocalTime.of(18, 0);
    private static final LocalTime TWENTY_TWO = LocalTime.of(22, 0);
    private static final LocalTime TWO = LocalTime.of(2, 0);

    @Autowired WindowService windows;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;

    /**
     * How long the helper transaction holds its uncommitted row. A timing assumption: it gives the
     * test thread time to reach the unique-key check and block on the row. See the race test.
     */
    private static final long LOCK_HOLD_MS = 300;

    @BeforeEach
    void cleanFixtures() {
        jdbc.update("DELETE FROM window_exception");
        jdbc.update("DELETE FROM window_rule");
        jdbc.update("DELETE FROM release_window");
        jdbc.update("DELETE FROM subsystem");
    }

    // ------------------------------------------------------------------ read model

    @Test
    void save_then_read_round_trips_the_global_window() {
        SaveResult result = saveGlobal(true,
            List.of(new RuleInput(TUE, NINE, TEN), new RuleInput(MON, NINE, EIGHTEEN)),
            List.of(new ExceptionInput(MONDAY.plusDays(9), "CLOSED", null, null)));

        assertThat(result.warnings()).isEmpty();
        WindowView view = windows.effectiveWindow(0L);

        assertThat(view).isNotNull();
        assertThat(view.id()).isEqualTo(result.window().id());
        assertThat(view.scope()).isEqualTo("GLOBAL");
        assertThat(view.subsystemId()).isZero();
        assertThat(view.enabled()).isTrue();
        assertThat(view.inherited()).isFalse();
        assertThat(view.timezone()).isEqualTo("Asia/Shanghai");
        assertThat(view.rules()).extracting(WindowView.RuleView::dayOfWeek)
            .as("rules come back ordered by weekday")
            .containsExactly(MON, TUE);
        assertThat(view.exceptions()).hasSize(1);
        assertThat(view.exceptions().get(0).mode()).isEqualTo("CLOSED");
        assertThat(view.exceptions().get(0).start()).isNull();
    }

    @Test
    void an_unconfigured_platform_has_no_window_at_all() {
        assertThat(windows.effectiveWindow(0L)).isNull();
        assertThat(windows.effectiveWindow(42L)).isNull();
    }

    @Test
    void a_subsystem_without_its_own_window_inherits_the_global_one() {
        long subsystemId = givenSubsystem("W-INHERIT");
        saveGlobal(true, List.of(new RuleInput(MON, NINE, EIGHTEEN)), List.of());

        WindowView view = windows.effectiveWindow(subsystemId);

        assertThat(view).isNotNull();
        assertThat(view.inherited()).isTrue();
        assertThat(view.scope()).isEqualTo("GLOBAL");
        assertThat(view.rules()).hasSize(1);
    }

    @Test
    void a_subsystem_window_overrides_the_global_one() {
        long subsystemId = givenSubsystem("W-OVERRIDE");
        saveGlobal(true, List.of(new RuleInput(MON, NINE, EIGHTEEN)), List.of());
        windows.save(new SaveCommand("SUBSYSTEM", subsystemId, true, null,
            List.of(new RuleInput(TUE, TEN, TWELVE)), List.of()));

        WindowView view = windows.effectiveWindow(subsystemId);

        assertThat(view.inherited()).isFalse();
        assertThat(view.scope()).isEqualTo("SUBSYSTEM");
        assertThat(view.subsystemId()).isEqualTo(subsystemId);
        assertThat(view.rules()).extracting(WindowView.RuleView::dayOfWeek)
            .containsExactly(TUE);
        assertThat(windows.effectiveWindow(0L).rules())
            .as("the global default is untouched")
            .hasSize(1);
    }

    @Test
    void saving_the_same_scope_twice_replaces_its_children() {
        saveGlobal(true, List.of(new RuleInput(MON, NINE, TEN), new RuleInput(MON, TEN, TWELVE)),
            List.of());
        saveGlobal(true, List.of(new RuleInput(MON, NINE, EIGHTEEN)), List.of());

        assertThat(count("SELECT COUNT(*) FROM release_window")).isOne();
        assertThat(count("SELECT COUNT(*) FROM window_rule")).isOne();
        assertThat(windows.effectiveWindow(0L).rules()).hasSize(1);
    }

    // ------------------------------------------------------------------ save validation

    @Test
    void a_rule_with_equal_start_and_end_is_rejected() {
        assertParamInvalid(() -> saveGlobal(true,
            List.of(new RuleInput(MON, TEN, TEN)), List.of()), "start");
    }

    @Test
    void overlapping_rules_of_the_same_weekday_are_rejected() {
        assertParamInvalid(() -> saveGlobal(true, List.of(
            new RuleInput(MON, NINE, TWELVE),
            new RuleInput(MON, LocalTime.of(11, 0), EIGHTEEN)), List.of()), "rules");
    }

    @Test
    void rules_that_touch_at_their_boundary_are_accepted() {
        saveGlobal(true, List.of(
            new RuleInput(MON, NINE, TWELVE),
            new RuleInput(MON, TWELVE, EIGHTEEN)), List.of());

        assertThat(windows.effectiveWindow(0L).rules()).hasSize(2);
    }

    @Test
    void rules_of_different_weekdays_may_overlap_in_time() {
        saveGlobal(true, List.of(
            new RuleInput(MON, NINE, EIGHTEEN),
            new RuleInput(TUE, NINE, EIGHTEEN)), List.of());

        assertThat(windows.effectiveWindow(0L).rules()).hasSize(2);
    }

    @Test
    void a_bad_weekday_is_rejected() {
        assertParamInvalid(() -> saveGlobal(true,
            List.of(new RuleInput(0, NINE, EIGHTEEN)), List.of()), "dayOfWeek");
    }

    @Test
    void a_short_slot_is_saved_with_a_warning() {
        SaveResult result = saveGlobal(true,
            List.of(new RuleInput(MON, TWENTY_TWO, LocalTime.of(22, 10))), List.of());

        assertThat(result.warnings()).hasSize(1);
        assertThat(result.warnings().get(0)).contains("少于 30 分钟");
        assertThat(windows.effectiveWindow(0L).rules()).hasSize(1);
    }

    @Test
    void a_long_slot_is_saved_with_a_warning() {
        SaveResult result = saveGlobal(true,
            List.of(new RuleInput(MON, LocalTime.of(8, 0), LocalTime.of(21, 0))), List.of());

        assertThat(result.warnings()).hasSize(1);
        assertThat(result.warnings().get(0)).contains("超过 12 小时");
        assertThat(windows.effectiveWindow(0L).rules()).hasSize(1);
    }

    @Test
    void a_subsystem_window_requires_an_existing_subsystem() {
        assertParamInvalid(() -> windows.save(new SaveCommand("SUBSYSTEM", 999_999L, true, null,
            List.of(), List.of())), "subsystemId");
    }

    @Test
    void a_bad_scope_is_rejected() {
        assertParamInvalid(() -> windows.save(new SaveCommand("TENANT", null, true, null,
            List.of(), List.of())), "scope");
    }

    @Test
    void a_bad_timezone_is_rejected() {
        assertParamInvalid(() -> windows.save(new SaveCommand("GLOBAL", null, true, "Mars/Base",
            List.of(), List.of())), "timezone");
    }

    @Test
    void two_exceptions_on_the_same_date_are_rejected() {
        assertParamInvalid(() -> saveGlobal(true, List.of(), List.of(
            new ExceptionInput(MONDAY, "OPEN", null, null),
            new ExceptionInput(MONDAY, "CLOSED", null, null))), "date");
    }

    @Test
    void an_unknown_exception_mode_is_rejected() {
        assertParamInvalid(() -> saveGlobal(true, List.of(), List.of(
            new ExceptionInput(MONDAY, "MAYBE", null, null))), "mode");
    }

    @Test
    void an_exception_with_only_one_bound_is_rejected() {
        assertParamInvalid(() -> saveGlobal(true, List.of(), List.of(
            new ExceptionInput(MONDAY, "OPEN", TWENTY_TWO, null))), "start");
    }

    @Test
    void a_closed_exception_drops_any_submitted_times() {
        saveGlobal(true, List.of(), List.of(
            new ExceptionInput(MONDAY, "CLOSED", TWENTY_TWO, TWO)));

        WindowView.ExceptionView stored = windows.effectiveWindow(0L).exceptions().get(0);

        assertThat(stored.mode()).isEqualTo("CLOSED");
        assertThat(stored.start()).isNull();
        assertThat(stored.end()).isNull();
    }

    // ------------------------------------------------------------------ check

    @Test
    void a_check_without_a_window_is_outside_and_not_configured() {
        WindowCheckView check = windows.check(0L, instant(MONDAY, 10, 0));

        assertThat(check.inWindow()).isFalse();
        assertThat(check.configured()).isFalse();
        assertThat(check.enabled()).isFalse();
        assertThat(check.windowId()).isNull();
        assertThat(check.span()).isNull();
        assertThat(check.next()).isNull();
        assertThat(check.timezone()).isNull();
        assertThat(check.scope()).isNull();
        assertThat(check.inherited()).isFalse();
    }

    @Test
    void a_check_inside_the_window_reports_the_span_and_the_next_window() {
        saveGlobal(true, List.of(new RuleInput(MON, TWENTY_TWO, TWO)), List.of());

        WindowCheckView check = windows.check(0L, instant(TUESDAY, 1, 0));

        assertThat(check.configured()).isTrue();
        assertThat(check.enabled()).isTrue();
        assertThat(check.inWindow()).isTrue();
        assertThat(check.windowId()).isNotNull();
        assertThat(check.timezone()).isEqualTo("Asia/Shanghai");
        assertThat(check.scope()).isEqualTo("GLOBAL");
        assertThat(check.inherited()).isFalse();
        assertThat(check.span().start()).isEqualTo(MONDAY.atTime(22, 0));
        assertThat(check.span().end()).isEqualTo(TUESDAY.atTime(2, 0));
        assertThat(check.next().start()).isEqualTo(MONDAY.plusWeeks(1).atTime(22, 0));
        assertThat(check.next().end()).isEqualTo(TUESDAY.plusWeeks(1).atTime(2, 0));
    }

    @Test
    void a_negative_subsystem_id_is_a_parameter_error() {
        assertParamInvalid(() -> windows.effectiveWindow(-1L), "subsystemId");
        assertParamInvalid(() -> windows.check(-1L, Instant.now()), "subsystemId");
    }

    @Test
    void a_check_outside_the_window_reports_the_next_one() {
        saveGlobal(true, List.of(new RuleInput(MON, TWENTY_TWO, TWO)), List.of());

        WindowCheckView check = windows.check(0L, instant(MONDAY, 21, 0));

        assertThat(check.inWindow()).isFalse();
        assertThat(check.span()).isNull();
        assertThat(check.next()).isNotNull();
        assertThat(check.next().start()).isEqualTo(MONDAY.atTime(22, 0));
    }

    @Test
    void a_switched_off_window_answers_inside_and_says_why() {
        SaveResult saved = saveGlobal(false, List.of(), List.of());

        WindowCheckView check = windows.check(0L, instant(TUESDAY, 3, 0));

        assertThat(check.inWindow())
            .as("enabled=false is the constraint switched off: always inside, even with no rules")
            .isTrue();
        assertThat(check.configured()).isTrue();
        assertThat(check.enabled()).isFalse();
        assertThat(check.windowId()).isEqualTo(saved.window().id());
        assertThat(check.timezone()).isEqualTo("Asia/Shanghai");
        assertThat(check.scope()).isEqualTo("GLOBAL");
        assertThat(check.inherited()).isFalse();
        assertThat(check.span()).isNull();
        assertThat(check.next()).isNull();
    }

    @Test
    void a_check_uses_the_subsystem_override() {
        long subsystemId = givenSubsystem("W-CHECK");
        saveGlobal(true, List.of(new RuleInput(MON, TWENTY_TWO, TWO)), List.of());
        windows.save(new SaveCommand("SUBSYSTEM", subsystemId, true, null,
            List.of(new RuleInput(TUE, NINE, TEN)), List.of()));

        WindowCheckView override = windows.check(subsystemId, instant(TUESDAY, 9, 30));

        assertThat(override.inWindow())
            .as("the override opens Tuesday morning")
            .isTrue();
        assertThat(override.scope()).isEqualTo("SUBSYSTEM");
        assertThat(override.inherited())
            .as("the subsystem has its own window, it is not inherited")
            .isFalse();
        assertThat(override.windowId()).isNotEqualTo(windows.effectiveWindow(0L).id());
        assertThat(windows.check(subsystemId, instant(MONDAY, 23, 0)).inWindow())
            .as("the global evening rule does not apply to the overridden subsystem")
            .isFalse();
        assertThat(windows.check(0L, instant(MONDAY, 23, 0)).inWindow()).isTrue();
    }

    @Test
    void a_check_of_a_subsystem_without_an_override_uses_the_global_window() {
        long subsystemId = givenSubsystem("W-FALLBACK");
        SaveResult saved = saveGlobal(true, List.of(new RuleInput(MON, TWENTY_TWO, TWO)),
            List.of());

        WindowCheckView check = windows.check(subsystemId, instant(TUESDAY, 1, 0));

        assertThat(check.inWindow()).isTrue();
        assertThat(check.windowId()).isEqualTo(saved.window().id());
        assertThat(check.scope()).isEqualTo("GLOBAL");
        assertThat(check.inherited()).isTrue();
    }

    @Test
    void a_check_without_an_explicit_instant_uses_now() {
        // A window that opens on a fixed date two days from now: if a null `at` were silently
        // replaced by some default (epoch, a fixed date), the next window would not be this date.
        LocalDate target = LocalDate.now(SHANGHAI).plusDays(2);
        saveGlobal(true, List.of(), List.of(new ExceptionInput(target, "OPEN", null, null)));

        WindowCheckView viaNull = windows.check(0L, null);
        WindowCheckView viaNow = windows.check(0L, Instant.now());

        assertThat(viaNull.configured()).isTrue();
        assertThat(viaNull.inWindow()).isFalse();
        assertThat(viaNull.next().start()).isEqualTo(target.atStartOfDay());
        assertThat(viaNow.next().start()).isEqualTo(target.atStartOfDay());
    }

    @Test
    void the_check_result_uses_the_configured_timezone() {
        windows.save(new SaveCommand("GLOBAL", null, true, "UTC",
            List.of(new RuleInput(MON, TWENTY_TWO, TWO)), List.of()));

        WindowCheckView check = windows.check(0L, Instant.parse("2026-11-24T01:00:00Z"));

        assertThat(check.inWindow())
            .as("01:00 UTC is Monday 22:00-24:00 in the UTC window")
            .isTrue();
        assertThat(check.span().start()).isEqualTo(LocalDateTime.of(2026, 11, 23, 22, 0));
    }

    // ------------------------------------------------------------------ input bounds

    @Test
    void a_rule_time_with_seconds_is_rejected() {
        assertParamInvalid(() -> saveGlobal(true, List.of(
            new RuleInput(MON, LocalTime.of(22, 0, 30), LocalTime.of(23, 0))), List.of()), "start");
        assertParamInvalid(() -> saveGlobal(true, List.of(
            new RuleInput(MON, LocalTime.of(22, 0), LocalTime.of(23, 0, 0, 1))), List.of()), "end");
    }

    @Test
    void an_exception_time_with_seconds_is_rejected() {
        assertParamInvalid(() -> saveGlobal(true, List.of(), List.of(
            new ExceptionInput(MONDAY, "OPEN", LocalTime.of(20, 0, 0, 500_000_000),
                TWENTY_TWO))), "start");
    }

    @Test
    void more_rules_than_the_page_can_show_are_rejected() {
        // 7 weekdays x 4 one-hour slots are all valid and non-overlapping; the 29th is the one the
        // cap must refuse. (Repeating one slot 29 times would be refused by the overlap rule, which
        // would make the assertion pass for the wrong reason.)
        List<RuleInput> rules = weekdaySlots(4);
        rules.add(new RuleInput(MON, LocalTime.of(8, 0), LocalTime.of(9, 0)));
        assertThat(rules).hasSize(29);

        assertParamInvalid(() -> saveGlobal(true, rules, List.of()), "rules");
    }

    @Test
    void twenty_eight_non_overlapping_rules_are_accepted() {
        List<RuleInput> rules = weekdaySlots(4);

        saveGlobal(true, rules, List.of());

        assertThat(windows.effectiveWindow(0L).rules()).hasSize(28);
    }

    @Test
    void more_exceptions_than_the_contract_allows_are_rejected() {
        List<ExceptionInput> exceptions = new ArrayList<>();
        for (int i = 0; i <= 100; i++) {
            exceptions.add(new ExceptionInput(MONDAY.plusDays(i), "CLOSED", null, null));
        }
        assertParamInvalid(() -> saveGlobal(true, List.of(), exceptions), "exceptions");
    }

    @Test
    void a_concurrently_created_header_is_reused_instead_of_failing() {
        ReleaseWindowMapper windowMapper = mock(ReleaseWindowMapper.class);
        WindowRuleMapper ruleMapper = mock(WindowRuleMapper.class);
        WindowExceptionMapper exceptionMapper = mock(WindowExceptionMapper.class);
        SubsystemMapper subsystemMapper = mock(SubsystemMapper.class);
        ReleaseWindowPO raced = new ReleaseWindowPO();
        raced.setId(77L);
        raced.setScope("GLOBAL");
        raced.setSubsystemId(0L);
        raced.setEnabled(true);
        raced.setTimezone("Asia/Shanghai");
        when(windowMapper.selectByScope("GLOBAL", 0L)).thenReturn(null);
        when(windowMapper.selectByScopeForUpdate("GLOBAL", 0L)).thenReturn(raced);
        when(windowMapper.insert(any(ReleaseWindowPO.class)))
            .thenThrow(new DuplicateKeyException("simulated concurrent save"));

        WindowService racing = new WindowService(windowMapper, ruleMapper, exceptionMapper,
            subsystemMapper);
        SaveResult result = racing.save(new SaveCommand("GLOBAL", null, true, null,
            List.of(new RuleInput(MON, NINE, EIGHTEEN)), List.of()));

        assertThat(result.window().id())
            .as("the save adopts the row the other transaction created")
            .isEqualTo(77L);
        assertThat(result.window().rules()).hasSize(1);
        verify(ruleMapper).insert(any(WindowRulePO.class));
    }

    @Test
    @Timeout(10)
    void a_header_committed_by_another_transaction_is_adopted() throws Exception {
        // A second connection inserts the GLOBAL header and holds it uncommitted. Our insert
        // blocks on the unique key, then fails after the holder commits; only a LOCKING re-read
        // can see the committed row (a plain one still sees the pre-collision snapshot).
        CountDownLatch inserted = new CountDownLatch(1);
        AtomicReference<Throwable> holderFailure = new AtomicReference<>();
        Thread holder = new Thread(() -> {
            try (Connection connection = dataSource.getConnection()) {
                connection.setAutoCommit(false);
                try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO release_window (scope, subsystem_id, enabled, timezone,"
                        + " created_at, updated_at)"
                        + " VALUES ('GLOBAL', 0, 1, 'UTC', NOW(3), NOW(3))")) {
                    statement.executeUpdate();
                }
                inserted.countDown();
                Thread.sleep(LOCK_HOLD_MS);
                connection.commit();
            } catch (Throwable failure) {
                holderFailure.set(failure);
                inserted.countDown();
            }
        }, "window-race-holder");
        holder.start();
        try {
            assertThat(inserted.await(5, TimeUnit.SECONDS)).isTrue();

            SaveResult result = saveGlobal(true,
                List.of(new RuleInput(MON, NINE, EIGHTEEN)), List.of());

            assertThat(result.window().id())
                .as("the save adopts the committed header instead of failing")
                .isNotNull();
            assertThat(result.window().scope()).isEqualTo("GLOBAL");
            assertThat(result.window().rules()).hasSize(1);
            assertThat(count("SELECT COUNT(*) FROM release_window")).isOne();
            assertThat(count("SELECT COUNT(*) FROM window_rule")).isOne();
        } finally {
            holder.join(5_000);
        }
        assertThat(holderFailure.get()).isNull();
    }

    // ------------------------------------------------------------------ fixtures

    private SaveResult saveGlobal(boolean enabled, List<RuleInput> rules,
                                  List<ExceptionInput> exceptions) {
        return windows.save(new SaveCommand("GLOBAL", null, enabled, null, rules, exceptions));
    }

    private long givenSubsystem(String name) {
        jdbc.update("INSERT INTO subsystem (name, owner_id, status, created_at, updated_at)"
            + " VALUES (?, 'w-owner', 'ACTIVE', NOW(3), NOW(3))", name);
        Long id = jdbc.queryForObject("SELECT id FROM subsystem WHERE name = ?", Long.class, name);
        return id == null ? 0L : id;
    }

    private long count(String sql) {
        Long value = jdbc.queryForObject(sql, Long.class);
        return value == null ? 0L : value;
    }

    private static Instant instant(LocalDate date, int hour, int minute) {
        return date.atTime(hour, minute).atZone(SHANGHAI).toInstant();
    }

    /**
     * Non-overlapping one-hour slots for every weekday: 7 days x {@code slotsPerDay}, at
     * 00:00-01:00, 02:00-03:00 and so on. Used to test the rule-count cap with input that the
     * overlap rule would accept.
     */
    private static List<RuleInput> weekdaySlots(int slotsPerDay) {
        List<RuleInput> rules = new ArrayList<>();
        for (int day = 1; day <= 7; day++) {
            for (int slot = 0; slot < slotsPerDay; slot++) {
                LocalTime start = LocalTime.of(slot * 2, 0);
                rules.add(new RuleInput(day, start, start.plusHours(1)));
            }
        }
        return rules;
    }

    private static void assertParamInvalid(org.assertj.core.api.ThrowableAssert.ThrowingCallable call,
                                           String field) {
        assertThatThrownBy(call).isInstanceOfSatisfying(BizException.class, ex -> {
            assertThat(ex.getError()).isEqualTo(BizError.PARAM_INVALID);
            assertThat((Map<String, Object>) ex.getPayload())
                .as("the payload names the offending field")
                .containsKey(field);
        });
    }
}