package com.sf.release.control;

import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;

/**
 * Fixtures of the P5-B control tests (design §7): a clean shared schema, the identity/system/unit/
 * task/member rows every action needs, and the hand-written rows that put a real pipeline into the
 * state an action is about (a paused production release, a batch already online, a running rollback).
 *
 * <p>The tests prefer the real use cases ({@code ItemAppService#create},
 * {@code PublishAppService#publish}) for everything the platform itself creates, and only write rows
 * directly where the state is the <em>setup</em> (a batch that already succeeded, an approval chain
 * that is waiting, a historical deployed version). That keeps the assertions about the action under
 * test instead of about how the setup was faked.
 *
 * <p>The package is {@code com.sf.release.control}, not {@code ..web..} (design §7 item 8): the web
 * package keeps production classes only and may not depend on domain/infra.
 */
final class ControlFixtures {

    private ControlFixtures() {
    }

    /** Empties every table the control tests touch and restores the seeded notification setting. */
    static void clean(JdbcTemplate jdbc) {
        jdbc.update("DELETE FROM notification");
        jdbc.update("DELETE FROM notification_setting");
        jdbc.update("INSERT INTO notification_setting (id, wecom_enabled, wecom_webhook, updated_at)"
            + " VALUES (1, 0, NULL, NOW(3))");
        jdbc.update("DELETE FROM audit_log");
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

    static void givenScope(JdbcTemplate jdbc, String userId, long unitId) {
        jdbc.update("INSERT INTO user_scope (user_id, scope_type, scope_id) VALUES (?, 'UNIT', ?)",
            userId, unitId);
    }

    static long givenSubsystem(JdbcTemplate jdbc, String name, String owner) {
        jdbc.update("INSERT INTO subsystem (name, owner_id, status, created_at, updated_at)"
            + " VALUES (?, ?, 'ACTIVE', NOW(3), NOW(3))", name, owner);
        Long id = jdbc.queryForObject("SELECT id FROM subsystem WHERE name = ?", Long.class, name);
        return id == null ? 0L : id;
    }

    static long givenTask(JdbcTemplate jdbc, long subsystemId, String name, String creator) {
        jdbc.update("INSERT INTO release_task (subsystem_id, name, creator, purpose,"
                + " plan_finish_date, status, created_at, updated_at)"
                + " VALUES (?, ?, ?, '新功能', '2026-11-25', 'ACTIVE', NOW(3), NOW(3))",
            subsystemId, name, creator);
        Long id = jdbc.queryForObject("SELECT id FROM release_task WHERE name = ?", Long.class,
            name);
        return id == null ? 0L : id;
    }

    static void givenMember(JdbcTemplate jdbc, long taskId, String userId, String role) {
        jdbc.update("INSERT INTO release_task_member (task_id, user_id, role, created_at,"
            + " updated_at) VALUES (?, ?, ?, NOW(3), NOW(3))", taskId, userId, role);
    }

    static long givenUnit(JdbcTemplate jdbc, long subsystemId, String name, String owner) {
        jdbc.update("INSERT INTO deploy_unit (subsystem_id, name, type, owner_id,"
                + " framework_template, branch_mode, status, module_path, created_at, updated_at)"
                + " VALUES (?, ?, 'UNIT', ?, 'spring-boot', 'STANDARD', 'ACTIVE',"
                + " 'outbound-os/control', NOW(3), NOW(3))",
            subsystemId, name, owner);
        Long id = jdbc.queryForObject(
            "SELECT id FROM deploy_unit WHERE subsystem_id = ? AND name = ?", Long.class,
            subsystemId, name);
        return id == null ? 0L : id;
    }

    static void givenBinding(JdbcTemplate jdbc, long unitId, String kind, String codingTaskId) {
        jdbc.update("INSERT INTO coding_task_binding (unit_id, task_kind, task_id, enabled,"
                + " created_at, updated_at) VALUES (?, ?, ?, 1, NOW(3), NOW(3))",
            unitId, kind, codingTaskId);
    }

    static long givenDe(JdbcTemplate jdbc, long unitId, String envKind, String name, int batchNo) {
        jdbc.update("INSERT INTO env_de (unit_id, env_kind, de_name, deploy_batch_no,"
                + " created_at, updated_at) VALUES (?, ?, ?, ?, NOW(3), NOW(3))",
            unitId, envKind, name, batchNo);
        Long id = jdbc.queryForObject(
            "SELECT id FROM env_de WHERE unit_id = ? AND de_name = ?", Long.class, unitId, name);
        return id == null ? 0L : id;
    }

    /**
     * A 变更项 row in the state a scenario needs. Used only where the item is <em>setup</em> (a
     * historical {@code DONE} version, a {@code LIB} item); a live flow creates its items through the
     * real {@code ItemAppService}.
     */
    static long givenItem(JdbcTemplate jdbc, long taskId, long unitId, String crid, String branch,
                          String type, String status, String gateStatus) {
        jdbc.update("INSERT INTO release_item (task_id, crid, type, unit_id, build_plan_id,"
                + " source_branch, module_path, version, plan_release_date, status, gate_status,"
                + " test_status, row_version, created_at, updated_at)"
                + " VALUES (?, ?, ?, ?, 'P-BUILD-CONTROL', ?, NULL, 'v1', '2026-11-25', ?, ?,"
                + " 'TEST_PENDING', 0, NOW(3), NOW(3))",
            taskId, crid, type, unitId, branch, status, gateStatus);
        Long id = jdbc.queryForObject("SELECT id FROM release_item WHERE crid = ?", Long.class,
            crid);
        return id == null ? 0L : id;
    }

    static long givenOrder(JdbcTemplate jdbc, long itemId, String publishNo, String status,
                           String executedBy) {
        jdbc.update("INSERT INTO release_order (publish_no, item_id, planned_release_time,"
                + " window_result, deadline_at, status, row_version, created_at, executed_by)"
                + " VALUES (?, ?, NOW(3), 'IN', DATE_ADD(NOW(3), INTERVAL 2 HOUR), ?, 0, NOW(3), ?)",
            publishNo, itemId, status, executedBy);
        Long id = jdbc.queryForObject("SELECT id FROM release_order WHERE publish_no = ?",
            Long.class, publishNo);
        return id == null ? 0L : id;
    }

    static long givenInstance(JdbcTemplate jdbc, long orderId, String pipelineNo, String status,
                              String triggerType, String currentNode, String imageDigest) {
        jdbc.update("INSERT INTO pipeline_instance (order_id, pipeline_no, exec_type, trigger_type,"
                + " current_node, status, image_digest, commit_sha, start_at, end_at, created_at)"
                + " VALUES (?, ?, 'ONLINE', ?, ?, ?, ?, NULL, NOW(3), NULL, NOW(3))",
            orderId, pipelineNo, triggerType, currentNode, status, imageDigest);
        Long id = jdbc.queryForObject(
            "SELECT id FROM pipeline_instance WHERE pipeline_no = ?", Long.class, pipelineNo);
        return id == null ? 0L : id;
    }

    /** A fully deployed production version: the node and the batch task both {@code SUCCESS}. */
    static void givenDeployedVersion(JdbcTemplate jdbc, long instanceId, long deId, int batchNo,
                                     Instant finishedAt) {
        jdbc.update("INSERT INTO pipeline_node (instance_id, node_type, seq_no, status,"
                + " started_at, finished_at) VALUES (?, 'PROD_DEPLOY', 3, 'SUCCESS', ?, ?)",
            instanceId, Timestamp.from(finishedAt.minusSeconds(600)),
            Timestamp.from(finishedAt));
        jdbc.update("INSERT INTO node_task (instance_id, node_type, task_type, batch_no, target_id,"
                + " status, attempt, started_at, finished_at, updated_at)"
                + " VALUES (?, 'PROD_DEPLOY', 'DEPLOY', ?, ?, 'SUCCESS', 0, ?, ?, ?)",
            instanceId, batchNo, deId, Timestamp.from(finishedAt.minusSeconds(600)),
            Timestamp.from(finishedAt), Timestamp.from(finishedAt));
    }

    static long givenSnapshot(JdbcTemplate jdbc, long orderId, long itemId, String submittedBy) {
        jdbc.update("INSERT INTO admission_snapshot (order_id, item_id, planned_release_time,"
                + " in_window, frozen, created_at, submitted_by)"
                + " VALUES (?, ?, NOW(3), 1, 0, NOW(3), ?)", orderId, itemId, submittedBy);
        Long id = jdbc.queryForObject(
            "SELECT id FROM admission_snapshot WHERE order_id = ? ORDER BY id DESC LIMIT 1",
            Long.class, orderId);
        return id == null ? 0L : id;
    }

    static void givenApproval(JdbcTemplate jdbc, long orderId, long snapshotId, int stepNo,
                              String role, String status) {
        jdbc.update("INSERT INTO approval (order_id, snapshot_id, step_no, role, status, created_at)"
            + " VALUES (?, ?, ?, ?, ?, NOW(3))", orderId, snapshotId, stepNo, role, status);
    }

    static void givenGateCheck(JdbcTemplate jdbc, long itemId, String checkKind, String stage,
                               String status) {
        jdbc.update("INSERT INTO gate_check (item_id, check_kind, stage, status, severity, issues,"
                + " attempt, created_at, updated_at) VALUES (?, ?, ?, ?, 'NONE', 0, 0, NOW(3),"
                + " NOW(3))", itemId, checkKind, stage, status);
    }

    static void givenLock(JdbcTemplate jdbc, long unitId, long instanceId, Instant expireAt) {
        jdbc.update("INSERT INTO deploy_unit_lock (unit_id, instance_id, acquired_at, expire_at)"
            + " VALUES (?, ?, NOW(3), ?)", unitId, instanceId, Timestamp.from(expireAt));
    }
}