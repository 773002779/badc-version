package com.sf.release.records;

import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;

/**
 * Row fixtures shared by the global release-records tests (P4-A Task 2 / GAP-10).
 *
 * <p>The page is a read over {@code release_order + release_item + deploy_unit + subsystem} plus
 * the newest {@code pipeline_instance}; the fixtures therefore write exactly those rows instead of
 * driving a publish or a pipeline. Keeping the writers in one place is what lets the service test
 * and the HTTP test agree on the same shape without copying a hundred INSERTs.
 */
final class ReleaseRecordsFixtures {

    private final JdbcTemplate jdbc;
    private int seq;

    ReleaseRecordsFixtures(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    void cleanDatabase() {
        jdbc.update("DELETE FROM rollback_record");
        jdbc.update("DELETE FROM pipeline_item_failure");
        jdbc.update("DELETE FROM node_task");
        jdbc.update("DELETE FROM pipeline_node");
        jdbc.update("DELETE FROM pipeline_instance");
        jdbc.update("DELETE FROM deploy_unit_lock");
        jdbc.update("DELETE FROM release_order");
        jdbc.update("DELETE FROM release_item");
        jdbc.update("DELETE FROM release_task_member");
        jdbc.update("DELETE FROM release_task");
        jdbc.update("DELETE FROM env_de");
        jdbc.update("DELETE FROM coding_task_binding");
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

    void givenUser(String userId, String... roles) {
        jdbc.update("INSERT INTO app_user (id, name, status, created_at)"
            + " VALUES (?, ?, 'ACTIVE', NOW(3))", userId, "用户" + userId);
        for (String role : roles) {
            jdbc.update("INSERT INTO user_role (user_id, role) VALUES (?, ?)", userId, role);
        }
    }

    void givenUnitScope(String userId, long unitId) {
        jdbc.update("INSERT INTO user_scope (user_id, scope_type, scope_id) VALUES (?, 'UNIT', ?)",
            userId, unitId);
    }

    long givenSubsystem(String name) {
        jdbc.update("INSERT INTO subsystem (name, owner_id, status, created_at, updated_at)"
            + " VALUES (?, ?, 'ACTIVE', NOW(3), NOW(3))", name, "owner-fixture");
        Long id = jdbc.queryForObject("SELECT id FROM subsystem WHERE name = ?", Long.class, name);
        return id == null ? 0L : id;
    }

    long givenUnit(long subsystemId, String name) {
        int n = ++seq;
        jdbc.update("INSERT INTO deploy_unit (subsystem_id, name, type, owner_id,"
                + " framework_template, branch_mode, status, module_path, created_at, updated_at)"
                + " VALUES (?, ?, 'UNIT', 'owner-fixture', 'spring-boot', 'STANDARD', 'ACTIVE', ?,"
                + " NOW(3), NOW(3))",
            subsystemId, name, "module/rec-" + n);
        Long id = jdbc.queryForObject(
            "SELECT id FROM deploy_unit WHERE subsystem_id = ? AND name = ?", Long.class,
            subsystemId, name);
        return id == null ? 0L : id;
    }

    long givenTask(long subsystemId, String name) {
        jdbc.update("INSERT INTO release_task (subsystem_id, name, creator, purpose,"
                + " plan_finish_date, status, created_at, updated_at)"
                + " VALUES (?, ?, 'owner-fixture', '新功能', '2026-11-30', 'ACTIVE', NOW(3), NOW(3))",
            subsystemId, name);
        Long id = jdbc.queryForObject(
            "SELECT id FROM release_task WHERE subsystem_id = ? AND name = ?", Long.class,
            subsystemId, name);
        return id == null ? 0L : id;
    }

    long givenItem(long taskId, long unitId, String crid, String version, String status) {
        int n = ++seq;
        jdbc.update("INSERT INTO release_item (task_id, crid, type, unit_id, build_plan_id,"
                + " source_branch, version, plan_release_date, status, gate_status, test_status,"
                + " row_version, created_at, updated_at)"
                + " VALUES (?, ?, 'UNIT', ?, 'P-BUILD', ?, ?, '2026-11-26', ?, 'CHECK_PASS',"
                + " 'TEST_PASS', 0, NOW(3), NOW(3))",
            taskId, crid, unitId, "release/rec-" + n, version, status);
        Long id = jdbc.queryForObject(
            "SELECT id FROM release_item WHERE task_id = ? AND source_branch = ?", Long.class,
            taskId, "release/rec-" + n);
        return id == null ? 0L : id;
    }

    long givenOrder(long itemId, String publishNo, String status, Instant createdAt,
                    Instant plannedReleaseTime, String executedBy) {
        jdbc.update("INSERT INTO release_order (publish_no, item_id, oa_ticket_no,"
                + " planned_release_time, window_result, deadline_at, status, row_version,"
                + " created_at, executed_by)"
                + " VALUES (?, ?, 'OA-REC', ?, 'OUT', DATE_ADD(?, INTERVAL 2 HOUR), ?, 0, ?, ?)",
            publishNo, itemId, timestamp(plannedReleaseTime), timestamp(plannedReleaseTime),
            status, timestamp(createdAt), executedBy);
        Long id = jdbc.queryForObject(
            "SELECT id FROM release_order WHERE publish_no = ?", Long.class, publishNo);
        return id == null ? 0L : id;
    }

    long givenInstance(long orderId, String pipelineNo, String status) {
        jdbc.update("INSERT INTO pipeline_instance (order_id, pipeline_no, exec_type,"
                + " trigger_type, current_node, status, image_digest, commit_sha, start_at, end_at,"
                + " created_at)"
                + " VALUES (?, ?, 'ONLINE', 'INITIAL', 'PROD_DEPLOY', ?, 'sha256:rec', NULL,"
                + " NOW(3), NULL, NOW(3))",
            orderId, pipelineNo, status);
        Long id = jdbc.queryForObject(
            "SELECT id FROM pipeline_instance WHERE pipeline_no = ?", Long.class, pipelineNo);
        return id == null ? 0L : id;
    }

    private static Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }
}