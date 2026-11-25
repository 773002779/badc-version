package com.sf.release.reminder;

import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Fixtures of the P6-E approval-reminder tests (design §4): a clean schema with the seeded
 * {@code app_setting} sections restored, the identities/roles the reminder rules address, and the
 * small row set behind a pending approval step (system, unit, task, item, order, snapshot, step).
 *
 * <p>The package is {@code com.sf.release.reminder}, not {@code ..web..}: the architecture rule keeps
 * the web package down to controllers/DTOs, and these tests need the real schema, the real mappers
 * and the real application services.
 */
final class ReminderFixtures {

    static final String OWNER = "rem-owner";
    static final String ADMIN_A = "rem-admin-a";
    static final String ADMIN_B = "rem-admin-b";
    static final String OPS_LEAD = "rem-ops-lead";
    static final String DEPT_MANAGER = "rem-dept-manager";
    static final String CREATOR = "rem-creator";
    static final String DEV = "rem-dev";

    static final String BRANCH = "release/p6e";
    static final String VERSION = "p6e-1.0.0";
    static final LocalDate PLAN_DATE = LocalDate.of(2026, 11, 26);

    /** The V33 seed payload, also the expected "default" the approval endpoint returns. */
    static final String APPROVAL_SEED_JSON =
        "{\"enabled\":true,\"remindHours\":12,\"escalateHours\":24}";
    static final String GATE_SEED_JSON = "{\"enabledChecks\":[\"SONAR\",\"COMPONENT_VULN\","
        + "\"ARCHITECTURE\",\"VERSION_DEFECT\",\"SECURITY_DYNAMIC\",\"AUTO_TEST\",\"DB_NORM\"]}";
    static final String APPROVER_SEED_JSON = "{\"opsLead\":[],\"deptManager\":[]}";
    static final String LOCK_SEED_JSON = "{\"pauseBoundHours\":168}";

    private ReminderFixtures() {
    }

    /**
     * Child-first delete of every table the slice reads or writes, then the seeded settings rows.
     * The notification side is reset too, because the reminder rows are the assertions.
     */
    static void clean(JdbcTemplate jdbc) {
        jdbc.update("DELETE FROM notification");
        jdbc.update("DELETE FROM notification_setting");
        jdbc.update("INSERT INTO notification_setting (id, wecom_enabled, wecom_webhook, updated_at)"
            + " VALUES (1, 0, NULL, NOW(3))");
        jdbc.update("DELETE FROM audit_log");
        jdbc.update("DELETE FROM emergency_release");
        jdbc.update("DELETE FROM rollback_record");
        jdbc.update("DELETE FROM gate_waiver");
        jdbc.update("DELETE FROM gate_check");
        jdbc.update("DELETE FROM pipeline_item_failure");
        jdbc.update("DELETE FROM node_task");
        jdbc.update("DELETE FROM pipeline_node");
        jdbc.update("DELETE FROM pipeline_instance");
        jdbc.update("DELETE FROM deploy_unit_lock");
        jdbc.update("DELETE FROM approval");
        jdbc.update("DELETE FROM admission_snapshot");
        jdbc.update("DELETE FROM release_order");
        jdbc.update("DELETE FROM release_item");
        jdbc.update("DELETE FROM release_task_member");
        jdbc.update("DELETE FROM release_task");
        jdbc.update("DELETE FROM env_de");
        jdbc.update("DELETE FROM coding_task_binding");
        jdbc.update("DELETE FROM deploy_unit");
        jdbc.update("DELETE FROM freeze_event");
        jdbc.update("DELETE FROM window_exception");
        jdbc.update("DELETE FROM window_rule");
        jdbc.update("DELETE FROM release_window");
        jdbc.update("DELETE FROM subsystem");
        jdbc.update("DELETE FROM user_scope");
        jdbc.update("DELETE FROM user_role");
        jdbc.update("DELETE FROM app_user");
        resetSettings(jdbc);
    }

    /** Re-inserts the four seeded sections, restoring the documented defaults. */
    static void resetSettings(JdbcTemplate jdbc) {
        jdbc.update("DELETE FROM app_setting");
        insertSetting(jdbc, "GATE", GATE_SEED_JSON);
        insertSetting(jdbc, "APPROVER", APPROVER_SEED_JSON);
        insertSetting(jdbc, "LOCK", LOCK_SEED_JSON);
        insertSetting(jdbc, "APPROVAL", APPROVAL_SEED_JSON);
    }

    /** Restores only the P6-E section, for a test that deliberately broke it. */
    static void resetApprovalSetting(JdbcTemplate jdbc) {
        jdbc.update("DELETE FROM app_setting WHERE setting_key = 'APPROVAL'");
        insertSetting(jdbc, "APPROVAL", APPROVAL_SEED_JSON);
    }

    static void insertSetting(JdbcTemplate jdbc, String key, String json) {
        jdbc.update("INSERT INTO app_setting (setting_key, value_json, updated_at)"
            + " VALUES (?, ?, NOW(3))", key, json);
    }

    static void givenUser(JdbcTemplate jdbc, String userId, String... roles) {
        jdbc.update("INSERT INTO app_user (id, name, status, created_at)"
            + " VALUES (?, ?, 'ACTIVE', NOW(3))", userId, "用户" + userId);
        for (String role : roles) {
            jdbc.update("INSERT INTO user_role (user_id, role) VALUES (?, ?)", userId, role);
        }
    }

    static long givenSubsystem(JdbcTemplate jdbc, String name, String owner) {
        jdbc.update("INSERT INTO subsystem (name, owner_id, status, created_at, updated_at)"
            + " VALUES (?, ?, 'ACTIVE', NOW(3), NOW(3))", name, owner);
        Long id = jdbc.queryForObject("SELECT id FROM subsystem WHERE name = ?", Long.class, name);
        return id == null ? 0L : id;
    }

    static long givenUnit(JdbcTemplate jdbc, long subsystemId, String name, String owner) {
        jdbc.update("INSERT INTO deploy_unit (subsystem_id, name, type, owner_id, status,"
                + " created_at, updated_at)"
                + " VALUES (?, ?, 'UNIT', ?, 'ACTIVE', NOW(3), NOW(3))", subsystemId, name, owner);
        Long id = jdbc.queryForObject(
            "SELECT id FROM deploy_unit WHERE subsystem_id = ? AND name = ?", Long.class,
            subsystemId, name);
        return id == null ? 0L : id;
    }

    static long givenTask(JdbcTemplate jdbc, long subsystemId, String name, String creator) {
        jdbc.update("INSERT INTO release_task (subsystem_id, name, creator, status, created_at,"
                + " updated_at) VALUES (?, ?, ?, 'ACTIVE', NOW(3), NOW(3))", subsystemId, name,
            creator);
        Long id = jdbc.queryForObject(
            "SELECT id FROM release_task WHERE subsystem_id = ? AND name = ?", Long.class,
            subsystemId, name);
        return id == null ? 0L : id;
    }

    /** A {@code UNIT} 变更项 in the given state (PENDING_APPROVAL for a real reminder). */
    static long givenItem(JdbcTemplate jdbc, long taskId, long unitId, String crid, String status) {
        // One branch per change item: uk_task_unit_branch allows the same branch under different
        // tasks/units, but several reminder orders sit in one task and one unit here.
        String branch = BRANCH + "/" + crid;
        jdbc.update("INSERT INTO release_item (task_id, crid, type, unit_id, build_plan_id,"
                + " source_branch, version, plan_release_date, status, gate_status, test_status,"
                + " row_version, created_at, updated_at) VALUES (?, ?, 'UNIT', ?, 'P-BUILD',"
                + " ?, ?, ?, ?, 'CHECK_PASS', 'TEST_PASS', 0, NOW(3), NOW(3))",
            taskId, crid, unitId, branch, VERSION, PLAN_DATE, status);
        Long id = jdbc.queryForObject("SELECT id FROM release_item WHERE crid = ?", Long.class,
            crid);
        return id == null ? 0L : id;
    }

    static long givenOrder(JdbcTemplate jdbc, long itemId, String publishNo, String status) {
        jdbc.update("INSERT INTO release_order (publish_no, item_id, planned_release_time,"
                + " window_result, deadline_at, status, row_version, created_at)"
                + " VALUES (?, ?, NOW(3), 'OUT', DATE_ADD(NOW(3), INTERVAL 2 HOUR), ?, 0, NOW(3))",
            publishNo, itemId, status);
        Long id = jdbc.queryForObject("SELECT id FROM release_order WHERE publish_no = ?",
            Long.class, publishNo);
        return id == null ? 0L : id;
    }

    static long givenSnapshot(JdbcTemplate jdbc, long orderId, long itemId, String submittedBy) {
        jdbc.update("INSERT INTO admission_snapshot (order_id, item_id, planned_release_time,"
                + " in_window, frozen, created_at, submitted_by)"
                + " VALUES (?, ?, NOW(3), 1, 0, NOW(3), ?)",
            orderId, itemId, submittedBy);
        Long id = jdbc.queryForObject(
            "SELECT id FROM admission_snapshot WHERE order_id = ? ORDER BY id DESC LIMIT 1",
            Long.class, orderId);
        return id == null ? 0L : id;
    }

    static void givenApproval(JdbcTemplate jdbc, long orderId, long snapshotId, int stepNo,
                              String role, String status, Instant createdAt) {
        jdbc.update("INSERT INTO approval (order_id, snapshot_id, step_no, role, status,"
                + " created_at) VALUES (?, ?, ?, ?, ?, ?)",
            orderId, snapshotId, stepNo, role, status, Timestamp.from(createdAt));
    }

    /** The number of rows holding {@code event_key} on the in-app channel. */
    static long notificationCount(JdbcTemplate jdbc, String eventKey) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM notification"
            + " WHERE event_key = ? AND channel = 'INAPP'", Long.class, eventKey);
        return count == null ? 0L : count;
    }
}
