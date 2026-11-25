package com.sf.release.lib;

import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;

/**
 * Shared fixtures of the second-party library slice tests: the same subsystem/task/unit/binding
 * rows the other slices build, plus the LIB artifact coordinates of V29.
 *
 * <p>Kept in one place so the pipeline, endpoint and coordinate tests cannot drift apart on the
 * schema (for example forgetting a NOT NULL column) or on the release branch/version they assert.
 */
final class LibFixtures {

    static final String OWNER = "p6a-owner";
    static final String APPROVER = "p6a-approver";
    static final String BRANCH = "release/p6a";
    static final String VERSION = "1.2.3";
    static final LocalDate PLAN_DATE = LocalDate.of(2026, 11, 25);

    private LibFixtures() {
    }

    /**
     * Clears every table the slice writes, child-first. Gate/audit/notification rows are cleared
     * too because the tests assert on them (a leftover row would make a "no notification" or
     * "one publish audit" assertion meaningless).
     */
    static void reset(JdbcTemplate jdbc) {
        jdbc.update("DELETE FROM notification");
        jdbc.update("DELETE FROM audit_log");
        jdbc.update("DELETE FROM gate_check");
        jdbc.update("DELETE FROM gate_waiver");
        jdbc.update("DELETE FROM pipeline_item_failure");
        jdbc.update("DELETE FROM node_task");
        jdbc.update("DELETE FROM pipeline_node");
        jdbc.update("DELETE FROM pipeline_instance");
        jdbc.update("DELETE FROM release_order");
        jdbc.update("DELETE FROM deploy_unit_lock");
        jdbc.update("DELETE FROM release_item");
        jdbc.update("DELETE FROM release_task_member");
        jdbc.update("DELETE FROM release_task");
        jdbc.update("DELETE FROM env_de");
        jdbc.update("DELETE FROM coding_task_binding");
        jdbc.update("DELETE FROM deploy_unit");
        jdbc.update("DELETE FROM subsystem");
        jdbc.update("DELETE FROM app_user");
        jdbc.update("DELETE FROM user_role");
        jdbc.update("DELETE FROM user_scope");
    }

    static void givenUser(JdbcTemplate jdbc, String userId) {
        jdbc.update("INSERT INTO app_user (id, name, status, created_at)"
            + " VALUES (?, ?, 'ACTIVE', NOW(3))", userId, "用户" + userId);
    }

    static long givenSubsystem(JdbcTemplate jdbc, String name) {
        jdbc.update("INSERT INTO subsystem (name, owner_id, status, created_at, updated_at)"
            + " VALUES (?, ?, 'ACTIVE', NOW(3), NOW(3))", name, OWNER);
        Long id = jdbc.queryForObject("SELECT id FROM subsystem WHERE name = ?", Long.class, name);
        return id == null ? 0L : id;
    }

    static long givenTask(JdbcTemplate jdbc, long subsystemId, String name) {
        jdbc.update("INSERT INTO release_task (subsystem_id, name, creator, purpose,"
                + " plan_finish_date, status, created_at, updated_at)"
                + " VALUES (?, ?, ?, '新功能', ?, 'ACTIVE', NOW(3), NOW(3))",
            subsystemId, name, OWNER, PLAN_DATE);
        Long id = jdbc.queryForObject(
            "SELECT id FROM release_task WHERE subsystem_id = ? AND name = ?", Long.class,
            subsystemId, name);
        return id == null ? 0L : id;
    }

    /** A unit row; {@code type} is {@code UNIT} or {@code LIB}. */
    static long givenUnit(JdbcTemplate jdbc, long subsystemId, String name, String type,
                          String modulePath, String artifactGroupId, String artifactArtifactId) {
        jdbc.update("INSERT INTO deploy_unit (subsystem_id, name, type, owner_id,"
                + " framework_template, branch_mode, status, module_path, artifact_group_id,"
                + " artifact_artifact_id, created_at, updated_at)"
                + " VALUES (?, ?, ?, ?, 'spring-boot', 'STANDARD', 'ACTIVE', ?, ?, ?,"
                + " NOW(3), NOW(3))",
            subsystemId, name, type, OWNER, modulePath, artifactGroupId, artifactArtifactId);
        Long id = jdbc.queryForObject(
            "SELECT id FROM deploy_unit WHERE subsystem_id = ? AND name = ?", Long.class,
            subsystemId, name);
        return id == null ? 0L : id;
    }

    static void givenBinding(JdbcTemplate jdbc, long unitId, String kind, String taskId) {
        jdbc.update("INSERT INTO coding_task_binding (unit_id, task_kind, task_id, enabled,"
                + " created_at, updated_at) VALUES (?, ?, ?, 1, NOW(3), NOW(3))",
            unitId, kind, taskId);
    }

    static long givenDe(JdbcTemplate jdbc, long unitId, String envKind, String deName,
                        int batchNo) {
        jdbc.update("INSERT INTO env_de (unit_id, env_kind, de_name, deploy_batch_no,"
                + " created_at, updated_at) VALUES (?, ?, ?, ?, NOW(3), NOW(3))",
            unitId, envKind, deName, batchNo);
        Long id = jdbc.queryForObject(
            "SELECT id FROM env_de WHERE unit_id = ? AND de_name = ?", Long.class, unitId, deName);
        return id == null ? 0L : id;
    }
}