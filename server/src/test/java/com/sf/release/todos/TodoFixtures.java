package com.sf.release.todos;

import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;

/**
 * Fixtures of the P5-C 待办中心 tests: a full reset of the shared test schema plus the small rows each
 * group needs. Package {@code com.sf.release.todos}, not {@code ..web..}: the tests need the real
 * repository and the security context, and the architecture rule forbids the web package from
 * depending on {@code infra}.
 */
final class TodoFixtures {

    /** The caller of the service tests. */
    static final String ME = "todo-me";

    /** A second person, used to own rows that must not appear. */
    static final String OTHER = "todo-other";

    private TodoFixtures() {
    }

    /** Empties every table the todo tests read and restores the seeded settings row. */
    static void clean(JdbcTemplate jdbc) {
        jdbc.update("DELETE FROM audit_log");
        jdbc.update("DELETE FROM notification");
        jdbc.update("DELETE FROM notification_setting");
        jdbc.update("INSERT INTO notification_setting (id, wecom_enabled, wecom_webhook, updated_at)"
            + " VALUES (1, 0, NULL, NOW(3))");
        jdbc.update("DELETE FROM coding_conn");
        jdbc.update("DELETE FROM deploy_unit_lock");
        jdbc.update("DELETE FROM rollback_record");
        jdbc.update("DELETE FROM pipeline_item_failure");
        jdbc.update("DELETE FROM node_task");
        jdbc.update("DELETE FROM pipeline_node");
        jdbc.update("DELETE FROM pipeline_instance");
        jdbc.update("DELETE FROM env_de");
        jdbc.update("DELETE FROM coding_task_binding");
        jdbc.update("DELETE FROM release_order");
        jdbc.update("DELETE FROM release_item");
        jdbc.update("DELETE FROM release_task_member");
        jdbc.update("DELETE FROM release_task");
        jdbc.update("DELETE FROM deploy_unit");
        jdbc.update("DELETE FROM freeze_event");
        jdbc.update("DELETE FROM approval");
        jdbc.update("DELETE FROM admission_snapshot");
        jdbc.update("DELETE FROM gate_waiver");
        jdbc.update("DELETE FROM gate_check");
        jdbc.update("DELETE FROM window_exception");
        jdbc.update("DELETE FROM window_rule");
        jdbc.update("DELETE FROM release_window");
        jdbc.update("DELETE FROM subsystem");
        jdbc.update("DELETE FROM user_scope");
        jdbc.update("DELETE FROM user_role");
        jdbc.update("DELETE FROM app_user");
    }

    static void givenUser(JdbcTemplate jdbc, String userId, String... roles) {
        jdbc.update("INSERT INTO app_user (id, name, status, created_at) VALUES (?, ?, 'ACTIVE',"
            + " NOW(3))", userId, "用户" + userId);
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
        Long id = jdbc.queryForObject("SELECT id FROM deploy_unit WHERE name = ?", Long.class, name);
        return id == null ? 0L : id;
    }

    static long givenTask(JdbcTemplate jdbc, long subsystemId, String name, String creator,
                          Instant updatedAt) {
        Timestamp at = Timestamp.from(updatedAt);
        jdbc.update("INSERT INTO release_task (subsystem_id, name, creator, purpose, status,"
                + " created_at, updated_at) VALUES (?, ?, ?, NULL, 'ACTIVE', ?, ?)",
            subsystemId, name, creator, at, at);
        Long id = jdbc.queryForObject("SELECT id FROM release_task WHERE name = ?", Long.class,
            name);
        return id == null ? 0L : id;
    }

    static void givenMember(JdbcTemplate jdbc, long taskId, String userId, String role) {
        jdbc.update("INSERT INTO release_task_member (task_id, user_id, role, created_at,"
            + " updated_at) VALUES (?, ?, ?, NOW(3), NOW(3))", taskId, userId, role);
    }

    static long givenItem(JdbcTemplate jdbc, long taskId, long unitId, String crid, String status,
                          String testStatus, Instant updatedAt) {
        Timestamp at = Timestamp.from(updatedAt);
        jdbc.update("INSERT INTO release_item (task_id, crid, type, unit_id, build_plan_id,"
                + " source_branch, module_path, version, plan_release_date, status, gate_status,"
                + " test_status, last_test_pass_by, last_test_pass_at, row_version, created_at,"
                + " updated_at)"
                + " VALUES (?, ?, 'UNIT', ?, 'P-TODO-BUILD', ?, NULL, 'v1', '2026-11-25', ?,"
                + " 'CHECK_PASS', ?, NULL, NULL, 0, ?, ?)",
            taskId, crid, unitId, "release/" + crid, status, testStatus, at, at);
        Long id = jdbc.queryForObject("SELECT id FROM release_item WHERE crid = ?", Long.class,
            crid);
        return id == null ? 0L : id;
    }

    static long givenOrder(JdbcTemplate jdbc, long itemId, String publishNo, String status,
                           Instant createdAt) {
        jdbc.update("INSERT INTO release_order (publish_no, item_id, oa_ticket_no,"
                + " planned_release_time, window_result, deadline_at, status, row_version,"
                + " created_at)"
                + " VALUES (?, ?, NULL, ?, 'OUT', NULL, ?, 0, ?)",
            publishNo, itemId, Timestamp.from(createdAt), status, Timestamp.from(createdAt));
        Long id = jdbc.queryForObject("SELECT id FROM release_order WHERE publish_no = ?",
            Long.class, publishNo);
        return id == null ? 0L : id;
    }

    static long givenInstance(JdbcTemplate jdbc, long orderId, String pipelineNo, String status,
                              Instant testReadyAt) {
        Timestamp at = Timestamp.from(testReadyAt == null
            ? Instant.parse("2026-01-01T00:00:00Z") : testReadyAt);
        jdbc.update("INSERT INTO pipeline_instance (order_id, pipeline_no, exec_type,"
                + " trigger_type, current_node, status, image_digest, commit_sha, start_at,"
                + " created_at, test_ready_at)"
                + " VALUES (?, ?, 'ONLINE', 'INITIAL', 'TEST_DEPLOY', ?, 'sha256:todo',"
                + " '0123456789abcdef0123456789abcdef01234567', ?, ?, ?)",
            orderId, pipelineNo, status, at, at, testReadyAt == null ? null : at);
        Long id = jdbc.queryForObject(
            "SELECT id FROM pipeline_instance WHERE pipeline_no = ?", Long.class, pipelineNo);
        return id == null ? 0L : id;
    }

    static long givenSnapshot(JdbcTemplate jdbc, long orderId, long itemId, String submittedBy,
                              Instant createdAt) {
        jdbc.update("INSERT INTO admission_snapshot (order_id, item_id, submitted_by,"
                + " planned_release_time, in_window, frozen, freeze_ids, max_severity,"
                + " check_conclusion, waiver_ids, test_conclusion, test_pass_by, oa_ticket_no,"
                + " image_digest, chain_json, created_at)"
                + " VALUES (?, ?, ?, ?, 1, 0, '[]', 'S3', 'PASS', '[]', 'TEST_PASS', ?,"
                + " 'OA-TODO-1', 'sha256:todo', '[]', ?)",
            orderId, itemId, submittedBy, Timestamp.from(createdAt), OTHER,
            Timestamp.from(createdAt));
        Long id = jdbc.queryForObject("SELECT MAX(id) FROM admission_snapshot", Long.class);
        return id == null ? 0L : id;
    }

    static void givenApproval(JdbcTemplate jdbc, long orderId, long snapshotId, int stepNo,
                              String role, String status) {
        jdbc.update("INSERT INTO approval (order_id, snapshot_id, step_no, role, status,"
            + " created_at) VALUES (?, ?, ?, ?, ?, NOW(3))", orderId, snapshotId, stepNo, role,
            status);
    }
}