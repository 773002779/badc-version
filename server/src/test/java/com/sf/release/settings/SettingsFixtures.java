package com.sf.release.settings;

import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Shared fixtures of the P6-B settings tests (design §5): a clean schema with the V30 seeds
 * restored, the identities/roles the HTTP seam needs, and the small row sets the effect tests put
 * behind a settings save (a published pipeline, historical check rows, a paused instance).
 *
 * <p>The package is {@code com.sf.release.settings}, not {@code ..web..}: the architecture rule
 * keeps the web package down to controllers/DTOs, and these tests need the real schema and the
 * application services.
 */
final class SettingsFixtures {

    static final String OWNER = "settings-owner";
    static final String ADMIN = "settings-admin";
    static final String DEV = "settings-dev";
    static final String OPS = "settings-ops";
    static final String OLD_OPS_LEAD = "settings-old-ops-lead";
    static final String OLD_DEPT_MANAGER = "settings-old-dept-manager";
    static final String NEW_LEAD = "settings-new-lead";
    static final String NEW_MANAGER = "settings-new-manager";
    static final String INACTIVE = "settings-inactive";

    static final String BRANCH = "release/p6b";
    static final String VERSION = "p6b-1.0.0";
    static final LocalDate PLAN_DATE = LocalDate.of(2026, 11, 25);

    /** The V30 seed payloads, also the expected "default" the GET endpoints return. */
    static final String GATE_SEED_JSON = "{\"enabledChecks\":[\"SONAR\",\"COMPONENT_VULN\","
        + "\"ARCHITECTURE\",\"VERSION_DEFECT\",\"SECURITY_DYNAMIC\",\"AUTO_TEST\",\"DB_NORM\"]}";
    static final String APPROVER_SEED_JSON = "{\"opsLead\":[],\"deptManager\":[]}";
    static final String LOCK_SEED_JSON = "{\"pauseBoundHours\":168}";

    private SettingsFixtures() {
    }

    /**
     * Child-first delete of every table the slice reads or writes, then the V30 seeds. The
     * notification/window blocks are deliberately not touched: P6-B leaves them alone.
     */
    static void clean(JdbcTemplate jdbc) {
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
        jdbc.update("DELETE FROM user_scope");
        jdbc.update("DELETE FROM user_role");
        jdbc.update("DELETE FROM app_user");
        resetSettings(jdbc);
    }

    /** Re-inserts the three seeded sections, restoring the documented defaults. */
    static void resetSettings(JdbcTemplate jdbc) {
        jdbc.update("DELETE FROM app_setting");
        insertSetting(jdbc, "GATE", GATE_SEED_JSON);
        insertSetting(jdbc, "APPROVER", APPROVER_SEED_JSON);
        insertSetting(jdbc, "LOCK", LOCK_SEED_JSON);
    }

    static void insertSetting(JdbcTemplate jdbc, String key, String json) {
        jdbc.update("INSERT INTO app_setting (setting_key, value_json, updated_at)"
            + " VALUES (?, ?, NOW(3))", key, json);
    }

    static void givenUser(JdbcTemplate jdbc, String userId) {
        jdbc.update("INSERT INTO app_user (id, name, status, created_at)"
            + " VALUES (?, ?, 'ACTIVE', NOW(3))", userId, "用户" + userId);
    }

    static void givenInactiveUser(JdbcTemplate jdbc, String userId) {
        jdbc.update("INSERT INTO app_user (id, name, status, created_at)"
            + " VALUES (?, ?, 'DISABLED', NOW(3))", userId, "用户" + userId);
    }

    static void givenUser(JdbcTemplate jdbc, String userId, String... roles) {
        givenUser(jdbc, userId);
        for (String role : roles) {
            jdbc.update("INSERT INTO user_role (user_id, role) VALUES (?, ?)", userId, role);
        }
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

    /** A {@code UNIT} deploy unit of the online pipeline. */
    static long givenUnit(JdbcTemplate jdbc, long subsystemId, String name) {
        jdbc.update("INSERT INTO deploy_unit (subsystem_id, name, type, owner_id,"
                + " framework_template, branch_mode, status, module_path, created_at, updated_at)"
                + " VALUES (?, ?, 'UNIT', ?, 'spring-boot', 'STANDARD', 'ACTIVE',"
                + " 'outbound-os/p6b', NOW(3), NOW(3))",
            subsystemId, name, OWNER);
        Long id = jdbc.queryForObject(
            "SELECT id FROM deploy_unit WHERE subsystem_id = ? AND name = ?", Long.class,
            subsystemId, name);
        return id == null ? 0L : id;
    }

    /** A {@code LIB} deploy unit with the artifact coordinates {@code LibAppService} validates. */
    static long givenLibUnit(JdbcTemplate jdbc, long subsystemId, String name) {
        jdbc.update("INSERT INTO deploy_unit (subsystem_id, name, type, owner_id,"
                + " framework_template, branch_mode, status, module_path, artifact_group_id,"
                + " artifact_artifact_id, created_at, updated_at)"
                + " VALUES (?, ?, 'LIB', ?, 'spring-boot', 'STANDARD', 'ACTIVE', 'p6b-sdk',"
                + " 'com.sf.p6b', 'p6b-sdk', NOW(3), NOW(3))",
            subsystemId, name, OWNER);
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

    static long givenDe(JdbcTemplate jdbc, long unitId, String envKind, String name, int batchNo) {
        jdbc.update("INSERT INTO env_de (unit_id, env_kind, de_name, deploy_batch_no,"
                + " created_at, updated_at) VALUES (?, ?, ?, ?, NOW(3), NOW(3))",
            unitId, envKind, name, batchNo);
        Long id = jdbc.queryForObject(
            "SELECT id FROM env_de WHERE unit_id = ? AND de_name = ?", Long.class, unitId, name);
        return id == null ? 0L : id;
    }

    /** A hand-written check row: the effect tests own the "history" the gate must read. */
    static void givenGateCheck(JdbcTemplate jdbc, long itemId, String checkKind, String stage,
                               String status, String severity) {
        jdbc.update("INSERT INTO gate_check (item_id, check_kind, stage, status, severity, issues,"
                + " attempt, created_at, updated_at) VALUES (?, ?, ?, ?, ?, 0, 0, NOW(3), NOW(3))",
            itemId, checkKind, stage, status, severity);
    }

    /** A paused pipeline instance and its live lock row, for the renew-job tests. */
    static long givenPausedInstance(JdbcTemplate jdbc, long unitId, String pipelineNo,
                                    Instant pausedAt) {
        jdbc.update("INSERT INTO pipeline_instance (order_id, pipeline_no, exec_type, trigger_type,"
                + " current_node, status, paused_at, created_at)"
                + " VALUES (0, ?, 'ONLINE', 'INITIAL', 'PROD_DEPLOY', 'PAUSED', ?, NOW(3))",
            pipelineNo, Timestamp.from(pausedAt));
        Long id = jdbc.queryForObject(
            "SELECT id FROM pipeline_instance WHERE pipeline_no = ?", Long.class, pipelineNo);
        if (id == null) {
            throw new IllegalStateException("pipeline instance was not inserted: " + pipelineNo);
        }
        jdbc.update("INSERT INTO deploy_unit_lock (unit_id, instance_id, acquired_at, expire_at)"
            + " VALUES (?, ?, NOW(3), ?)", unitId, id,
            Timestamp.from(Instant.now().plusSeconds(60)));
        return id;
    }

    static Instant lockExpireAt(JdbcTemplate jdbc, long unitId) {
        Timestamp value = jdbc.queryForObject(
            "SELECT expire_at FROM deploy_unit_lock WHERE unit_id = ?", Timestamp.class, unitId);
        return value == null ? null : value.toInstant();
    }

    static void setLockExpireAt(JdbcTemplate jdbc, long unitId, Instant expireAt) {
        jdbc.update("UPDATE deploy_unit_lock SET expire_at = ? WHERE unit_id = ?",
            Timestamp.from(expireAt), unitId);
    }
}