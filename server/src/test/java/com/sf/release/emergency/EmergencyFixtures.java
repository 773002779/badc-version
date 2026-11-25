package com.sf.release.emergency;

import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;

/**
 * Fixtures of the P6-D emergency-release tests (design §2/§5): a clean schema, the identity/system/
 * unit/task rows every case needs, a 变更项 + 发布单 in the state the release is about, and the
 * hand-written rows that put a real execution into the state the freeze/execute cases need (a paused
 * production instance, its pre-generated batches, a lease).
 *
 * <p>The helpers write rows directly because the state <em>is</em> the setup (a signing approval
 * chain, a frozen system, a paused instance). The code under test — the service, the review, the
 * batch-boundary probe and the real execute use case — is the production one.
 *
 * <p>Package {@code com.sf.release.emergency}, not {@code ..web..} (design §5 item 7).
 */
final class EmergencyFixtures {

    static final String ADMIN = "em-admin";
    static final String DEV = "em-dev";
    static final String TESTER = "em-tester";
    static final String OPS = "em-ops";
    static final String PRODUCT = "em-product";
    static final String LEAD = "em-lead";
    static final String OWNER = "em-owner";
    static final String NOBODY = "em-nobody";

    static final String REASON = "线上故障需立即修复，走紧急放行";
    static final String DIGEST = "sha256:emergency";
    static final String BUILD_PLAN = "P-BUILD-EM";
    static final String PROD_PLAN = "P-PROD-EM";

    private final JdbcTemplate jdbc;

    EmergencyFixtures(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------------ clean schema

    /** Empties every table these tests touch and restores the seeded notification setting. */
    void clean() {
        jdbc.update("DELETE FROM emergency_release");
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

    // ------------------------------------------------------------------ identity and resources

    /** The users and the subsystem/task/member/unit rows the notification audience resolves. */
    Base base() {
        givenUser(ADMIN, "ADMIN");
        givenUser(DEV, "DEV");
        givenUser(TESTER, "TEST");
        givenUser(OPS, "OPS");
        givenUser(PRODUCT, "PRODUCT");
        givenUser(LEAD, "OPS_LEAD");
        givenUser(OWNER);
        givenUser(NOBODY);

        long subsystemId = givenSubsystem("SCC-EMERGENCY", OWNER);
        long taskId = givenTask(subsystemId, "202611-EMERGENCY", OWNER);
        givenMember(taskId, DEV, "DEV");
        givenMember(taskId, TESTER, "TEST");
        givenMember(taskId, OPS, "OPS");
        givenMember(taskId, PRODUCT, "PRODUCT");

        long unitId = givenUnit(subsystemId, "emergency-api", OWNER);
        givenBinding(unitId, "BUILD", BUILD_PLAN);
        givenBinding(unitId, "TEST_DEPLOY", "P-TEST-EM");
        givenBinding(unitId, "PROD_DEPLOY", PROD_PLAN);
        givenBinding(unitId, "DAILY_DEPLOY", "P-DAILY-EM");
        givenDe(unitId, "TEST", "em-sit", 0);
        long prodOne = givenDe(unitId, "PROD", "em-prod-1", 1);
        long prodTwo = givenDe(unitId, "DR", "em-prod-2", 2);
        return new Base(subsystemId, taskId, unitId, prodOne, prodTwo);
    }

    void givenUser(String userId, String... roles) {
        jdbc.update("INSERT INTO app_user (id, name, status, created_at)"
            + " VALUES (?, ?, 'ACTIVE', NOW(3))", userId, "用户" + userId);
        for (String role : roles) {
            jdbc.update("INSERT INTO user_role (user_id, role) VALUES (?, ?)", userId, role);
        }
    }

    void givenScope(String userId, long unitId) {
        jdbc.update("INSERT INTO user_scope (user_id, scope_type, scope_id) VALUES (?, 'UNIT', ?)",
            userId, unitId);
    }

    long givenSubsystem(String name, String owner) {
        jdbc.update("INSERT INTO subsystem (name, owner_id, status, created_at, updated_at)"
            + " VALUES (?, ?, 'ACTIVE', NOW(3), NOW(3))", name, owner);
        return idOf("SELECT id FROM subsystem WHERE name = ?", name);
    }

    long givenTask(long subsystemId, String name, String creator) {
        jdbc.update("INSERT INTO release_task (subsystem_id, name, creator, purpose,"
                + " plan_finish_date, status, created_at, updated_at)"
                + " VALUES (?, ?, ?, '新功能', '2026-11-26', 'ACTIVE', NOW(3), NOW(3))",
            subsystemId, name, creator);
        return idOf("SELECT id FROM release_task WHERE name = ?", name);
    }

    void givenMember(long taskId, String userId, String role) {
        jdbc.update("INSERT INTO release_task_member (task_id, user_id, role, created_at,"
            + " updated_at) VALUES (?, ?, ?, NOW(3), NOW(3))", taskId, userId, role);
    }

    long givenUnit(long subsystemId, String name, String owner) {
        jdbc.update("INSERT INTO deploy_unit (subsystem_id, name, type, owner_id,"
                + " framework_template, branch_mode, status, module_path, created_at, updated_at)"
                + " VALUES (?, ?, 'UNIT', ?, 'spring-boot', 'STANDARD', 'ACTIVE',"
                + " 'outbound-os/emergency', NOW(3), NOW(3))",
            subsystemId, name, owner);
        return idOf("SELECT id FROM deploy_unit WHERE subsystem_id = ? AND name = ?",
            subsystemId, name);
    }

    void givenBinding(long unitId, String kind, String codingTaskId) {
        jdbc.update("INSERT INTO coding_task_binding (unit_id, task_kind, task_id, enabled,"
                + " created_at, updated_at) VALUES (?, ?, ?, 1, NOW(3), NOW(3))",
            unitId, kind, codingTaskId);
    }

    long givenDe(long unitId, String envKind, String name, int batchNo) {
        jdbc.update("INSERT INTO env_de (unit_id, env_kind, de_name, deploy_batch_no,"
                + " created_at, updated_at) VALUES (?, ?, ?, ?, NOW(3), NOW(3))",
            unitId, envKind, name, batchNo);
        return idOf("SELECT id FROM env_de WHERE unit_id = ? AND de_name = ?", unitId, name);
    }

    // ------------------------------------------------------------------ item / order / pipeline

    long givenItem(long taskId, long unitId, String crid, String branch, String status,
                   String gateStatus) {
        jdbc.update("INSERT INTO release_item (task_id, crid, type, unit_id, build_plan_id,"
                + " source_branch, module_path, version, plan_release_date, status, gate_status,"
                + " test_status, row_version, created_at, updated_at)"
                + " VALUES (?, ?, 'UNIT', ?, ?, ?, NULL, 'v1', '2026-11-26', ?, ?, 'TEST_PENDING',"
                + " 0, NOW(3), NOW(3))",
            taskId, crid, unitId, BUILD_PLAN, branch, status, gateStatus);
        return idOf("SELECT id FROM release_item WHERE crid = ?", crid);
    }

    long givenOrder(long itemId, String publishNo, String status) {
        jdbc.update("INSERT INTO release_order (publish_no, item_id, planned_release_time,"
                + " window_result, deadline_at, status, row_version, created_at)"
                + " VALUES (?, ?, NOW(3), 'IN', DATE_ADD(NOW(3), INTERVAL 2 HOUR), ?, 0, NOW(3))",
            publishNo, itemId, status);
        return idOf("SELECT id FROM release_order WHERE publish_no = ?", publishNo);
    }

    long givenInstance(long orderId, String pipelineNo, String status, String imageDigest) {
        jdbc.update("INSERT INTO pipeline_instance (order_id, pipeline_no, exec_type, trigger_type,"
                + " current_node, status, status_reason, image_digest, commit_sha, start_at,"
                + " paused_at, created_at)"
                + " VALUES (?, ?, 'ONLINE', 'INITIAL', 'PROD_DEPLOY', ?, '停在生产边界', ?, NULL,"
                + " NOW(3), NOW(3), NOW(3))",
            orderId, pipelineNo, status, imageDigest);
        return idOf("SELECT id FROM pipeline_instance WHERE pipeline_no = ?", pipelineNo);
    }

    /** The production node and its two pre-generated batches (one DE in each). */
    void givenProductionWork(long instanceId, Base base) {
        jdbc.update("INSERT INTO pipeline_node (instance_id, node_type, seq_no, status, started_at)"
            + " VALUES (?, 'PROD_DEPLOY', 3, 'RUNNING', NOW(3))", instanceId);
        givenDeployTask(instanceId, base.prodOne(), 1);
        givenDeployTask(instanceId, base.prodTwo(), 2);
    }

    long givenDeployTask(long instanceId, long deId, int batchNo) {
        jdbc.update("INSERT INTO node_task (instance_id, node_type, task_type, batch_no, target_id,"
            + " status, attempt) VALUES (?, 'PROD_DEPLOY', 'DEPLOY', ?, ?, 'PENDING', 0)",
            instanceId, batchNo, deId);
        return idOf("SELECT id FROM node_task WHERE instance_id = ? AND node_type = 'PROD_DEPLOY'"
            + " AND target_id = ?", instanceId, deId);
    }

    long givenSnapshot(long orderId, long itemId, String submittedBy, String digest) {
        jdbc.update("INSERT INTO admission_snapshot (order_id, item_id, submitted_by,"
                + " planned_release_time, in_window, window_id, frozen, freeze_ids, max_severity,"
                + " check_conclusion, waiver_ids, test_conclusion, test_pass_by, oa_ticket_no,"
                + " image_digest, chain_json, created_at)"
                + " VALUES (?, ?, ?, NOW(3), 0, NULL, 0, '[]', 'S3', 'PASS', '[]', 'TEST_PASS', ?,"
                + " 'OA-EM-1', ?, '[]', NOW(3))",
            orderId, itemId, submittedBy, OPS, digest);
        return idOf("SELECT id FROM admission_snapshot WHERE order_id = ? ORDER BY id DESC"
            + " LIMIT 1", orderId);
    }

    void givenApproval(long orderId, long snapshotId, int stepNo, String role, String status) {
        jdbc.update("INSERT INTO approval (order_id, snapshot_id, step_no, role, status, created_at)"
            + " VALUES (?, ?, ?, ?, ?, NOW(3))", orderId, snapshotId, stepNo, role, status);
    }

    // ------------------------------------------------------------------ freeze / gate / lease

    long givenFreezeInForce(long subsystemId) {
        Instant now = Instant.now();
        jdbc.update("INSERT INTO freeze_event (scope, subsystem_id, reason, impact, initiator_id,"
                + " start_at, end_at, status, created_at)"
                + " VALUES ('SUBSYSTEM', ?, '大促封板', NULL, ?, ?, ?, 'ACTIVE', NOW(3))",
            subsystemId, OPS, Timestamp.from(now.minusSeconds(60)),
            Timestamp.from(now.plusSeconds(3600)));
        return idOf("SELECT MAX(id) FROM freeze_event");
    }

    void givenGateCheck(long itemId, long orderId, String status, String severity) {
        jdbc.update("INSERT INTO gate_check (item_id, order_id, check_kind, stage, status, severity,"
                + " issues, attempt, created_at, updated_at)"
                + " VALUES (?, ?, 'SONAR', 'IMAGE', ?, ?, 1, 1, NOW(3), NOW(3))",
            itemId, orderId, status, severity);
    }

    long givenWaiver(long itemId, long orderId, String status, Instant expireAt) {
        jdbc.update("INSERT INTO gate_waiver (item_id, order_id, status, reason, impact,"
                + " requested_by, requested_at, approved_by, approved_at, decide_reason, expire_at,"
                + " commit_sha, image_digest, created_at, updated_at)"
                + " VALUES (?, ?, ?, '已知问题', '影响可控', ?, NOW(3), ?, NOW(3), '同意', ?, NULL,"
                + " ?, NOW(3), NOW(3))",
            itemId, orderId, status, OPS, OPS, Timestamp.from(expireAt), DIGEST);
        return idOf("SELECT MAX(id) FROM gate_waiver");
    }

    // ------------------------------------------------------------------ helpers

    private long idOf(String sql, Object... args) {
        Long id = jdbc.queryForObject(sql, Long.class, args);
        return id == null ? 0L : id;
    }

    /** The ids every scenario resolves from the base fixture. */
    record Base(long subsystemId, long taskId, long unitId, long prodOne, long prodTwo) {
    }
}