package com.sf.release.audit;

import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;

/**
 * Fixtures shared by the P4-C audit tests: a full reset of the shared test schema plus the small rows
 * every trigger scenario needs.
 *
 * <p>Package {@code com.sf.release.audit}, not {@code ..web..}: the architecture rule forbids the web
 * package depending on domain/infra, and these tests need both.
 */
final class AuditFixtures {

    static final String USER = "audit-actor";

    private AuditFixtures() {
    }

    /** Empties every table the audit tests read and restores the seeded settings row. */
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

    /** A READY-ish item: gate CHECK_PASS, test TEST_PASS, the given operator passed it. */
    static long givenItem(JdbcTemplate jdbc, long taskId, long unitId, String crid, String status) {
        return givenItem(jdbc, taskId, unitId, crid, status, "CHECK_PASS", "TEST_PASS", USER);
    }

    static long givenItem(JdbcTemplate jdbc, long taskId, long unitId, String crid, String status,
                          String gateStatus, String testStatus, String lastTestPassBy) {
        jdbc.update("INSERT INTO release_item (task_id, crid, type, unit_id, build_plan_id,"
                + " source_branch, version, plan_release_date, status, gate_status, test_status,"
                + " last_test_pass_by, last_test_pass_at, row_version, created_at, updated_at)"
                + " VALUES (?, ?, 'UNIT', ?, 'P-BUILD', ?, 'v1', '2026-11-25', ?, ?, ?, ?, NOW(3),"
                + " 0, NOW(3), NOW(3))",
            taskId, crid, unitId, "release/" + crid, status, gateStatus, testStatus,
            lastTestPassBy);
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
                              String status, String currentNode, String digest, Instant testReadyAt) {
        Timestamp ready = testReadyAt == null ? null : Timestamp.from(testReadyAt);
        jdbc.update("INSERT INTO pipeline_instance (order_id, pipeline_no, exec_type, trigger_type,"
                + " current_node, status, image_digest, commit_sha, start_at, created_at,"
                + " test_ready_at)"
                + " VALUES (?, ?, 'ONLINE', ?, ?, ?, ?,"
                + " '0123456789abcdef0123456789abcdef01234567', NOW(3), NOW(3), ?)",
            orderId, pipelineNo, trigger, currentNode, status, digest, ready);
        Long id = jdbc.queryForObject(
            "SELECT id FROM pipeline_instance WHERE pipeline_no = ?", Long.class, pipelineNo);
        return id == null ? 0L : id;
    }

    static void givenSnapshot(JdbcTemplate jdbc, long orderId, long itemId, String submittedBy,
                              String digest) {
        jdbc.update("INSERT INTO admission_snapshot (order_id, item_id, submitted_by,"
                + " planned_release_time, in_window, frozen, freeze_ids, max_severity,"
                + " check_conclusion, waiver_ids, test_conclusion, test_pass_by, oa_ticket_no,"
                + " image_digest, chain_json, created_at)"
                + " VALUES (?, ?, ?, NOW(3), 1, 0, '[]', 'S3', 'PASS', '[]', 'TEST_PASS', ?,"
                + " 'OA-AUD-1', ?, '[]', NOW(3))",
            orderId, itemId, submittedBy, USER, digest);
    }

    static void givenApproval(JdbcTemplate jdbc, long orderId, long snapshotId, int stepNo,
                              String role, String status) {
        jdbc.update("INSERT INTO approval (order_id, snapshot_id, step_no, role, status,"
            + " created_at) VALUES (?, ?, ?, ?, ?, NOW(3))", orderId, snapshotId, stepNo, role,
            status);
    }

    static void givenBinding(JdbcTemplate jdbc, long unitId, String kind, String taskId) {
        jdbc.update("INSERT INTO coding_task_binding (unit_id, task_kind, task_id, enabled,"
            + " created_at, updated_at) VALUES (?, ?, ?, 1, NOW(3), NOW(3))", unitId, kind, taskId);
    }

    static long givenDe(JdbcTemplate jdbc, long unitId, String envKind, String deName, int batchNo) {
        jdbc.update("INSERT INTO env_de (unit_id, env_kind, de_name, deploy_batch_no,"
            + " created_at, updated_at) VALUES (?, ?, ?, ?, NOW(3), NOW(3))", unitId, envKind,
            deName, batchNo);
        Long id = jdbc.queryForObject(
            "SELECT id FROM env_de WHERE unit_id = ? AND de_name = ?", Long.class, unitId, deName);
        return id == null ? 0L : id;
    }

    static void givenCheck(JdbcTemplate jdbc, long itemId, Long orderId, String kind, String stage,
                           String status, String severity) {
        jdbc.update("INSERT INTO gate_check (item_id, order_id, check_kind, stage, status, severity,"
                + " issues, attempt, created_at, updated_at)"
                + " VALUES (?, ?, ?, ?, ?, ?, 0, 1, NOW(3), NOW(3))",
            itemId, orderId, kind, stage, status, severity);
    }

    static long givenWaiver(JdbcTemplate jdbc, long itemId, Long orderId, String status,
                            String requestedBy, Instant expireAt, String digest) {
        jdbc.update("INSERT INTO gate_waiver (item_id, order_id, status, reason, impact,"
                + " requested_by, requested_at, approved_by, approved_at, decide_reason, expire_at,"
                + " commit_sha, image_digest, created_at, updated_at)"
                + " VALUES (?, ?, ?, '已知问题', '影响可控', ?, NOW(3), NULL, NULL, NULL, ?, NULL, ?,"
                + " NOW(3), NOW(3))",
            itemId, orderId, status, requestedBy, Timestamp.from(expireAt), digest);
        Long id = jdbc.queryForObject("SELECT MAX(id) FROM gate_waiver", Long.class);
        return id == null ? 0L : id;
    }

    static void givenNode(JdbcTemplate jdbc, long instanceId, String nodeType, int seq, String status) {
        jdbc.update("INSERT INTO pipeline_node (instance_id, node_type, seq_no, status, started_at)"
            + " VALUES (?, ?, ?, ?, NULL)", instanceId, nodeType, seq, status);
    }

    static void givenNodeTask(JdbcTemplate jdbc, long instanceId, String nodeType, String taskType,
                              int batchNo, long targetId, String status) {
        jdbc.update("INSERT INTO node_task (instance_id, node_type, task_type, batch_no, target_id,"
                + " status, attempt) VALUES (?, ?, ?, ?, ?, ?, 0)",
            instanceId, nodeType, taskType, batchNo, targetId, status);
    }

    static void givenDeployTask(JdbcTemplate jdbc, long instanceId, long deId, int batchNo,
                                String status, Instant finishedAt) {
        Integer nodes = jdbc.queryForObject("SELECT COUNT(*) FROM pipeline_node"
            + " WHERE instance_id = ? AND node_type = 'PROD_DEPLOY'", Integer.class, instanceId);
        if (nodes == null || nodes == 0) {
            jdbc.update("INSERT INTO pipeline_node (instance_id, node_type, seq_no, status,"
                    + " started_at, finished_at)"
                    + " VALUES (?, 'PROD_DEPLOY', 0, 'SUCCESS', NOW(3), ?)",
                instanceId, Timestamp.from(finishedAt));
        }
        jdbc.update("INSERT INTO node_task (instance_id, node_type, task_type, batch_no, target_id,"
                + " status, attempt, started_at, finished_at, updated_at)"
                + " VALUES (?, 'PROD_DEPLOY', 'DEPLOY', ?, ?, ?, 0, ?, ?, ?)",
            instanceId, batchNo, deId, status, Timestamp.from(finishedAt.minusSeconds(600)),
            Timestamp.from(finishedAt), Timestamp.from(finishedAt));
    }

    static long givenFreezeInForce(JdbcTemplate jdbc, long subsystemId, String reason,
                                   String initiator, Instant startAt, Instant endAt) {
        jdbc.update("INSERT INTO freeze_event (scope, subsystem_id, reason, impact, initiator_id,"
                + " start_at, end_at, status, created_at)"
                + " VALUES ('SUBSYSTEM', ?, ?, NULL, ?, ?, ?, 'ACTIVE', NOW(3))",
            subsystemId, reason, initiator, Timestamp.from(startAt), Timestamp.from(endAt));
        Long id = jdbc.queryForObject("SELECT MAX(id) FROM freeze_event", Long.class);
        return id == null ? 0L : id;
    }

    /** One fully deployed forward release, for the rollback version list. */
    static long givenVersion(JdbcTemplate jdbc, long taskId, long subsystemId, long unitId,
                             String publishNo, String digest, long deId, Instant finishedAt) {
        long itemId = givenItem(jdbc, taskId, unitId, "CR-" + publishNo, "DONE");
        long orderId = givenOrder(jdbc, itemId, publishNo, "DONE");
        jdbc.update("UPDATE release_order SET executed_by = 'audit-old' WHERE id = ?", orderId);
        long instanceId = givenInstance(jdbc, orderId, "PL-" + publishNo, "INITIAL", "DONE",
            "PROD_DEPLOY", digest, null);
        givenDeployTask(jdbc, instanceId, deId, 1, "SUCCESS", finishedAt);
        return instanceId;
    }
}