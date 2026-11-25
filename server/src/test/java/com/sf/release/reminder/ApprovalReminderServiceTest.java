package com.sf.release.reminder;

import com.sf.release.application.reminder.ApprovalReminderService;
import com.sf.release.application.reminder.ApprovalReminderService.ScanResult;
import com.sf.release.application.settings.SettingsService;
import com.sf.release.infra.job.ApprovalReminderJob;
import com.sf.release.infra.security.TestUserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The P6-E approval-reminder behaviour (design §1/§3/§4), against the real schema and the real
 * notification outbox. Time is fixed by construction: every fixture is created at {@link #CREATED}
 * and every scan is given an explicit {@code now}, so "11h / 12h / 24h" is exact rather than a sleep.
 *
 * <ul>
 *   <li>thresholds and the two-level message (remind, then escalate);</li>
 *   <li>escalation suppresses the reminder, and the notification unique key keeps each level to one
 *       row per recipient/channel/occurrence across scans;</li>
 *   <li>only the newest admission snapshot's <em>pending</em> steps of a {@code PENDING_APPROVAL}
 *       item are scanned;</li>
 *   <li>recipient rules: the effective approver list, the {@code user_role} fallback, the system
 *       owner and the ADMIN escalation;</li>
 *   <li>the config thresholds, the disabled switch and the 12/24 fallback;</li>
 *   <li>no audit rows are written by a scan.</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("test")
class ApprovalReminderServiceTest {

    private static final Instant CREATED = Instant.parse("2026-11-26T00:00:00.000Z");

    @Autowired ApprovalReminderService reminders;
    @Autowired SettingsService settings;
    @Autowired JdbcTemplate jdbc;

    private long subsystemId;
    private long unitId;
    private long taskId;

    @BeforeEach
    void setUp() {
        ReminderFixtures.clean(jdbc);
        ReminderFixtures.givenUser(jdbc, ReminderFixtures.OWNER);
        ReminderFixtures.givenUser(jdbc, ReminderFixtures.ADMIN_A, "ADMIN");
        ReminderFixtures.givenUser(jdbc, ReminderFixtures.ADMIN_B, "ADMIN");
        ReminderFixtures.givenUser(jdbc, ReminderFixtures.OPS_LEAD, "OPS_LEAD");
        ReminderFixtures.givenUser(jdbc, ReminderFixtures.DEPT_MANAGER, "DEPT_MANAGER");
        ReminderFixtures.givenUser(jdbc, ReminderFixtures.CREATOR);
        subsystemId = ReminderFixtures.givenSubsystem(jdbc, "REM-SYS", ReminderFixtures.OWNER);
        unitId = ReminderFixtures.givenUnit(jdbc, subsystemId, "rem-unit", ReminderFixtures.OWNER);
        taskId = ReminderFixtures.givenTask(jdbc, subsystemId, "REM-TASK", ReminderFixtures.CREATOR);
    }

    @AfterEach
    void tearDown() {
        TestUserContext.clear();
        ReminderFixtures.resetSettings(jdbc);
    }

    // ------------------------------------------------------------------ thresholds

    @Test
    void eleven_hours_sends_nothing() {
        givenPendingStep("PO-11H", "OPS_LEAD", "PENDING_APPROVAL");

        ScanResult result = reminders.scan(CREATED.plus(Duration.ofHours(11)), 200);

        assertThat(result.scanned()).isZero();
        assertThat(notifications()).isEmpty();
    }

    @Test
    void twelve_hours_reminds_the_pending_steps_role() {
        Pending step = givenPendingStep("PO-12H", "OPS_LEAD", "PENDING_APPROVAL");

        ScanResult result = reminders.scan(CREATED.plus(Duration.ofHours(12)), 200);

        assertThat(result.scanned()).isEqualTo(1);
        assertThat(result.reminded()).isEqualTo(1);
        assertThat(result.escalated()).isZero();
        List<Map<String, Object>> rows = notifications();
        assertThat(rows).hasSize(1);
        Map<String, Object> row = rows.get(0);
        assertThat(row.get("event_key")).isEqualTo("APPROVAL_REMIND");
        assertThat(row.get("object_type")).isEqualTo("ORDER");
        assertThat(row.get("object_id")).isEqualTo("PO-12H");
        assertThat(row.get("recipient")).isEqualTo(ReminderFixtures.OPS_LEAD);
        assertThat(row.get("title")).isEqualTo("审批待处理提醒");
        assertThat((String) row.get("body"))
            .contains("发布单 PO-12H", "「运维组长」", "12 小时", "/releases/PO-12H");
        assertThat((String) row.get("dedupe_key"))
            .endsWith("|approval:" + step.snapshotId() + ":1:REMIND");
    }

    @Test
    void twenty_three_hours_sends_the_reminder_once_and_no_escalation() {
        givenPendingStep("PO-23H", "OPS_LEAD", "PENDING_APPROVAL");

        reminders.scan(CREATED.plus(Duration.ofHours(12)), 200);
        reminders.scan(CREATED.plus(Duration.ofHours(23)), 200);

        assertThat(notificationCount("APPROVAL_REMIND")).isEqualTo(1);
        assertThat(notificationCount("APPROVAL_ESCALATE")).isZero();
    }

    @Test
    void twenty_four_hours_escalates_and_later_scans_write_nothing_new() {
        givenPendingStep("PO-24H", "DEPT_MANAGER", "PENDING_APPROVAL");

        reminders.scan(CREATED.plus(Duration.ofHours(12)), 200);
        reminders.scan(CREATED.plus(Duration.ofHours(24)), 200);
        reminders.scan(CREATED.plus(Duration.ofHours(25)), 200);

        assertThat(notificationCount("APPROVAL_REMIND")).isEqualTo(1);
        assertThat(notificationCount("APPROVAL_ESCALATE")).isEqualTo(3);
        assertThat(recipientsOf("APPROVAL_ESCALATE"))
            .containsExactly(ReminderFixtures.ADMIN_A, ReminderFixtures.ADMIN_B,
                ReminderFixtures.OWNER);
        assertThat(bodyOf("APPROVAL_ESCALATE"))
            .contains("「部门经理」", "24 小时", "/releases/PO-24H");
    }

    @Test
    void escalation_suppresses_the_reminder_for_a_step_first_seen_after_the_escalation() {
        givenPendingStep("PO-LATE", "OPS_LEAD", "PENDING_APPROVAL");

        // The first scan already sees 25 hours: the step is escalated, never also reminded.
        reminders.scan(CREATED.plus(Duration.ofHours(25)), 200);

        assertThat(notificationCount("APPROVAL_REMIND")).isZero();
        assertThat(notificationCount("APPROVAL_ESCALATE")).isEqualTo(3);
    }

    @Test
    void two_scans_at_the_same_instant_write_each_level_once() {
        givenPendingStep("PO-DEDUPE", "OPS_LEAD", "PENDING_APPROVAL");

        reminders.scan(CREATED.plus(Duration.ofHours(13)), 200);
        reminders.scan(CREATED.plus(Duration.ofHours(13)), 200);

        assertThat(notificationCount("APPROVAL_REMIND")).isEqualTo(1);
    }

    // ------------------------------------------------------------------ scope

    @Test
    void only_the_newest_snapshot_of_an_order_is_scanned() {
        Pending old = givenPendingStep("PO-LATEST", "OPS_LEAD", "PENDING_APPROVAL");
        long newer = ReminderFixtures.givenSnapshot(jdbc, old.orderId(), old.itemId(), "rem-submitter");
        ReminderFixtures.givenApproval(jdbc, old.orderId(), newer, 1, "OPS_LEAD", "APPROVED",
            CREATED.plusSeconds(60));

        reminders.scan(CREATED.plus(Duration.ofHours(13)), 200);

        assertThat(notifications()).isEmpty();
    }

    @Test
    void a_new_generation_without_a_pending_step_does_not_revive_the_old_one() {
        Pending old = givenPendingStep("PO-NOGEN", "OPS_LEAD", "PENDING_APPROVAL");
        // A resubmission created a new admission snapshot but required no signature: the old
        // generation's pending step is dead and must not be reminded.
        ReminderFixtures.givenSnapshot(jdbc, old.orderId(), old.itemId(), "rem-submitter");

        reminders.scan(CREATED.plus(Duration.ofHours(13)), 200);

        assertThat(notifications()).isEmpty();
    }

    @Test
    void decided_and_canceled_steps_are_never_reminded() {
        givenPendingStepWithStatus("PO-APPROVED", "OPS_LEAD", "APPROVED", "PENDING_APPROVAL");
        givenPendingStepWithStatus("PO-CANCELED", "OPS_LEAD", "CANCELED", "PENDING_APPROVAL");
        givenPendingStepWithStatus("PO-REJECTED", "OPS_LEAD", "REJECTED", "PENDING_APPROVAL");

        reminders.scan(CREATED.plus(Duration.ofHours(13)), 200);

        assertThat(notifications()).isEmpty();
    }

    @Test
    void a_release_that_left_the_pending_approval_state_is_not_reminded() {
        givenPendingStep("PO-READY", "OPS_LEAD", "READY");
        givenPendingStep("PO-REJECTEDITEM", "OPS_LEAD", "REJECTED");

        reminders.scan(CREATED.plus(Duration.ofHours(13)), 200);

        assertThat(notifications()).isEmpty();
    }

    @Test
    void the_scan_limit_bounds_one_round() {
        givenPendingStep("PO-LIMIT-1", "OPS_LEAD", "PENDING_APPROVAL");
        givenPendingStep("PO-LIMIT-2", "OPS_LEAD", "PENDING_APPROVAL");
        givenPendingStep("PO-LIMIT-3", "OPS_LEAD", "PENDING_APPROVAL");

        ScanResult result = reminders.scan(CREATED.plus(Duration.ofHours(13)), 2);

        assertThat(result.scanned()).isEqualTo(2);
        assertThat(result.reminded()).isEqualTo(2);
        assertThat(notificationCount("APPROVAL_REMIND")).isEqualTo(2);
    }

    // ------------------------------------------------------------------ recipients

    @Test
    void the_effective_approver_list_is_the_reminder_audience() {
        ReminderFixtures.givenUser(jdbc, "rem-extra-lead", "OPS_LEAD");
        TestUserContext.allUnits(ReminderFixtures.ADMIN_A);
        settings.saveApprovers(List.of(ReminderFixtures.OPS_LEAD),
            List.of(ReminderFixtures.DEPT_MANAGER));
        givenPendingStep("PO-LIST", "OPS_LEAD", "PENDING_APPROVAL");

        reminders.scan(CREATED.plus(Duration.ofHours(13)), 200);

        assertThat(recipientsOf("APPROVAL_REMIND"))
            .containsExactly(ReminderFixtures.OPS_LEAD);
    }

    @Test
    void an_empty_stored_list_falls_back_to_the_user_role_rows() {
        ReminderFixtures.givenUser(jdbc, "rem-legacy-lead", "OPS_LEAD");
        givenPendingStep("PO-ROLE", "OPS_LEAD", "PENDING_APPROVAL");

        reminders.scan(CREATED.plus(Duration.ofHours(13)), 200);

        assertThat(recipientsOf("APPROVAL_REMIND"))
            .containsExactly("rem-legacy-lead", ReminderFixtures.OPS_LEAD);
    }

    @Test
    void a_role_without_any_holder_notifies_the_system_owner() {
        jdbc.update("DELETE FROM user_role WHERE role = 'OPS_LEAD'");
        givenPendingStep("PO-NOBODY", "OPS_LEAD", "PENDING_APPROVAL");

        reminders.scan(CREATED.plus(Duration.ofHours(13)), 200);

        assertThat(recipientsOf("APPROVAL_REMIND"))
            .containsExactly(ReminderFixtures.OWNER);
        assertThat(bodyOf("APPROVAL_REMIND")).contains("（角色无人，已转系统负责人）");
    }

    @Test
    void escalation_targets_the_admins_plus_the_system_owner() {
        givenPendingStep("PO-ESC", "OPS_LEAD", "PENDING_APPROVAL");

        reminders.scan(CREATED.plus(Duration.ofHours(25)), 200);

        assertThat(recipientsOf("APPROVAL_ESCALATE")).containsExactly(
            ReminderFixtures.ADMIN_A, ReminderFixtures.ADMIN_B, ReminderFixtures.OWNER);
        assertThat(bodyOf("APPROVAL_ESCALATE")).contains("系统管理员", "系统负责人");
    }

    @Test
    void an_owner_who_is_also_an_admin_is_escalated_to_once() {
        jdbc.update("DELETE FROM user_role WHERE role = 'ADMIN'");
        jdbc.update("INSERT INTO user_role (user_id, role) VALUES (?, 'ADMIN')",
            ReminderFixtures.OWNER);
        jdbc.update("INSERT INTO user_role (user_id, role) VALUES (?, 'ADMIN')",
            ReminderFixtures.ADMIN_B);
        givenPendingStep("PO-ESC-DEDUP", "OPS_LEAD", "PENDING_APPROVAL");

        reminders.scan(CREATED.plus(Duration.ofHours(25)), 200);

        assertThat(recipientsOf("APPROVAL_ESCALATE"))
            .containsExactly(ReminderFixtures.ADMIN_B, ReminderFixtures.OWNER);
    }

    // ------------------------------------------------------------------ configuration

    @Test
    void a_shortened_sla_moves_both_thresholds() {
        TestUserContext.allUnits(ReminderFixtures.ADMIN_A);
        settings.saveApprovalSla(true, 2, 4);
        givenPendingStep("PO-SHORT", "OPS_LEAD", "PENDING_APPROVAL");

        reminders.scan(CREATED.plus(Duration.ofHours(1)), 200);
        assertThat(notifications()).isEmpty();

        reminders.scan(CREATED.plus(Duration.ofHours(3)), 200);
        assertThat(notificationCount("APPROVAL_REMIND")).isEqualTo(1);

        reminders.scan(CREATED.plus(Duration.ofHours(5)), 200);
        assertThat(notificationCount("APPROVAL_ESCALATE")).isEqualTo(3);
    }

    @Test
    void a_disabled_sla_sends_nothing_at_all() {
        TestUserContext.allUnits(ReminderFixtures.ADMIN_A);
        settings.saveApprovalSla(false, 12, 24);
        givenPendingStep("PO-OFF", "OPS_LEAD", "PENDING_APPROVAL");

        ScanResult result = reminders.scan(CREATED.plus(Duration.ofHours(30)), 200);

        assertThat(result.scanned()).isZero();
        assertThat(notifications()).isEmpty();
    }

    @Test
    void a_missing_approval_row_falls_back_to_the_enabled_twelve_twenty_four_defaults() {
        jdbc.update("DELETE FROM app_setting WHERE setting_key = 'APPROVAL'");
        givenPendingStep("PO-FALLBACK", "OPS_LEAD", "PENDING_APPROVAL");

        reminders.scan(CREATED.plus(Duration.ofHours(13)), 200);

        assertThat(notificationCount("APPROVAL_REMIND")).isEqualTo(1);
    }

    @Test
    void a_malformed_approval_row_falls_back_to_the_enabled_twelve_twenty_four_defaults() {
        jdbc.update("UPDATE app_setting SET value_json = '[]' WHERE setting_key = 'APPROVAL'");
        givenPendingStep("PO-MALFORMED", "OPS_LEAD", "PENDING_APPROVAL");

        reminders.scan(CREATED.plus(Duration.ofHours(13)), 200);

        assertThat(notificationCount("APPROVAL_REMIND")).isEqualTo(1);
    }

    // ------------------------------------------------------------------ side effects / job

    @Test
    void a_scan_writes_no_audit_rows() {
        givenPendingStep("PO-NOAUDIT", "OPS_LEAD", "PENDING_APPROVAL");

        reminders.scan(CREATED.plus(Duration.ofHours(25)), 200);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_log", Integer.class)).isZero();
    }

    @Test
    void the_job_scans_with_its_clock() {
        givenPendingStep("PO-JOB", "OPS_LEAD", "PENDING_APPROVAL");
        ApprovalReminderJob job = new ApprovalReminderJob(reminders,
            Clock.fixed(CREATED.plus(Duration.ofHours(13)), ZoneOffset.UTC));

        job.scanApprovals();

        assertThat(notificationCount("APPROVAL_REMIND")).isEqualTo(1);
    }

    // ------------------------------------------------------------------ fixtures / helpers

    /** One 发布单 with one admission snapshot and one pending step created at {@link #CREATED}. */
    private Pending givenPendingStep(String publishNo, String role, String itemStatus) {
        return givenPendingStepWithStatus(publishNo, role, "PENDING", itemStatus);
    }

    private Pending givenPendingStepWithStatus(String publishNo, String role, String approvalStatus,
                                               String itemStatus) {
        long itemId = ReminderFixtures.givenItem(jdbc, taskId, unitId, "CR-" + publishNo,
            itemStatus);
        long orderId = ReminderFixtures.givenOrder(jdbc, itemId, publishNo, "READY");
        long snapshotId = ReminderFixtures.givenSnapshot(jdbc, orderId, itemId, "rem-submitter");
        ReminderFixtures.givenApproval(jdbc, orderId, snapshotId, 1, role, approvalStatus, CREATED);
        return new Pending(orderId, snapshotId, itemId);
    }

    private List<Map<String, Object>> notifications() {
        return jdbc.queryForList("SELECT event_key, object_type, object_id, recipient, channel,"
            + " title, body, dedupe_key FROM notification WHERE channel = 'INAPP'"
            + " ORDER BY event_key, recipient");
    }

    private long notificationCount(String eventKey) {
        return ReminderFixtures.notificationCount(jdbc, eventKey);
    }

    private List<String> recipientsOf(String eventKey) {
        return jdbc.queryForList("SELECT recipient FROM notification WHERE event_key = ?"
            + " AND channel = 'INAPP' ORDER BY recipient", String.class, eventKey);
    }

    private String bodyOf(String eventKey) {
        return jdbc.queryForObject("SELECT body FROM notification WHERE event_key = ? LIMIT 1",
            String.class, eventKey);
    }

    private record Pending(long orderId, long snapshotId, long itemId) {
    }
}
