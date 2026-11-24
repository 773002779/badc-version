package com.sf.release.notification;

import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;

/**
 * Fixtures of the P4-B notification tests: the small rows every test needs (identity, system, unit,
 * task and its members, item, order, instance) plus a full reset of the shared test schema.
 *
 * <p>Package {@code com.sf.release.notification} (design section 8): the architecture rule forbids
 * the web package depending on domain/infra, so no notification test lives under {@code ..web..},
 * including the controller test.
 */
final class NotificationFixtures {

    private NotificationFixtures() {
    }

    /** Empties every table the notification tests read, and restores the seeded settings row. */
    static void clean(JdbcTemplate jdbc) {
        jdbc.update("DELETE FROM notification");
        jdbc.update("DELETE FROM notification_setting");
        jdbc.update("INSERT INTO notification_setting (id, wecom_enabled, wecom_webhook, updated_at)"
            + " VALUES (1, 0, NULL, NOW(3))");
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
        jdbc.update("DELETE FROM subsystem");
        jdbc.update("DELETE FROM user_scope");
        jdbc.update("DELETE FROM user_role");
        jdbc.update("DELETE FROM app_user");
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
        Long id = jdbc.queryForObject("SELECT id FROM deploy_unit WHERE name = ?", Long.class, name);
        return id == null ? 0L : id;
    }

    static long givenTask(JdbcTemplate jdbc, long subsystemId, String name, String creator) {
        jdbc.update("INSERT INTO release_task (subsystem_id, name, creator, status, created_at,"
            + " updated_at) VALUES (?, ?, ?, 'ACTIVE', NOW(3), NOW(3))", subsystemId, name,
            creator);
        Long id = jdbc.queryForObject("SELECT id FROM release_task WHERE name = ?", Long.class,
            name);
        return id == null ? 0L : id;
    }

    static void givenMember(JdbcTemplate jdbc, long taskId, String userId, String role) {
        jdbc.update("INSERT INTO release_task_member (task_id, user_id, role, created_at,"
            + " updated_at) VALUES (?, ?, ?, NOW(3), NOW(3))", taskId, userId, role);
    }

    static long givenItem(JdbcTemplate jdbc, long taskId, long unitId, String crid, String status) {
        jdbc.update("INSERT INTO release_item (task_id, crid, type, unit_id, build_plan_id,"
                + " source_branch, version, plan_release_date, status, gate_status, test_status,"
                + " row_version, created_at, updated_at) VALUES (?, ?, 'UNIT', ?, 'P-BUILD',"
                + " ?, 'v1', '2026-11-25', ?, 'CHECK_PASS', 'TEST_PASS', 0, NOW(3),"
                + " NOW(3))",
            taskId, crid, unitId, "release/" + crid, status);
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

    static long givenInstance(JdbcTemplate jdbc, long orderId, String pipelineNo, String trigger,
                              String status, Instant pausedAt) {
        Timestamp paused = pausedAt == null ? null : Timestamp.from(pausedAt);
        jdbc.update("INSERT INTO pipeline_instance (order_id, pipeline_no, exec_type, trigger_type,"
                + " current_node, status, start_at, end_at, created_at, paused_at)"
                + " VALUES (?, ?, 'ONLINE', ?, 'PROD_DEPLOY', ?, NOW(3), NULL, NOW(3), ?)",
            orderId, pipelineNo, trigger, status, paused);
        Long id = jdbc.queryForObject("SELECT id FROM pipeline_instance WHERE pipeline_no = ?",
            Long.class, pipelineNo);
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

    static long givenRollbackRecord(JdbcTemplate jdbc, long unitId, long instanceId,
                                    Long sourceOrderId, String status, String requestedBy) {
        jdbc.update("INSERT INTO rollback_record (unit_id, instance_id, pipeline_no,"
                + " source_order_id, source_item_id, source_publish_no, source_digest,"
                + " source_commit, target_digest, target_publish_no, reason, requested_by, status,"
                + " failure_reason, created_at, finished_at)"
                + " VALUES (?, ?, NULL, ?, NULL, 'PO-OLD', 'sha256:old', NULL, 'sha256:target',"
                + " 'PO-TARGET', '线上故障止损', ?, ?, NULL, NOW(3), NULL)",
            unitId, instanceId, sourceOrderId, requestedBy, status);
        Long id = jdbc.queryForObject("SELECT MAX(id) FROM rollback_record", Long.class);
        return id == null ? 0L : id;
    }

    static void givenLock(JdbcTemplate jdbc, long unitId, long instanceId, Instant expireAt) {
        jdbc.update("INSERT INTO deploy_unit_lock (unit_id, instance_id, acquired_at, expire_at)"
            + " VALUES (?, ?, NOW(3), ?)", unitId, instanceId, Timestamp.from(expireAt));
    }
}