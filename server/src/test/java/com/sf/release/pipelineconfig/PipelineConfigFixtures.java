package com.sf.release.pipelineconfig;

import com.sf.release.domain.resource.EnvDe;
import com.sf.release.infra.repo.po.EnvDePO;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.util.List;

/**
 * Shared fixtures of the P6-C unit pipeline configuration tests (design §5).
 *
 * <p>The package is {@code com.sf.release.pipelineconfig}, not {@code ..web..}: the architecture
 * rule keeps the web package down to controllers/DTOs, and these tests need the real schema, the
 * application services and (for the rollback assertion) the domain factory.
 *
 * <p>{@link #clean(JdbcTemplate)} deletes every table this slice reads or writes, including
 * {@code unit_pipeline_config}, then restores the V30 seeds so a test that changed the platform
 * gate switches cannot leak into another slice. {@code unit_pipeline_config} has no seed: an
 * absent row is the documented default.
 */
final class PipelineConfigFixtures {

    static final String OWNER = "p6c-owner";
    static final String ADMIN = "p6c-admin";
    static final String DEV = "p6c-dev";
    static final String UNIT_SETTINGS = "p6c-unit-settings";
    static final String SCOPE_ROLE = "P6C_UNIT_SETTINGS";

    static final String BRANCH = "release/p6c";
    static final String VERSION = "p6c-1.0.0";
    static final LocalDate PLAN_DATE = LocalDate.of(2026, 11, 26);

    /** The V30 gate seed payload, the documented "every check enabled" default. */
    static final String GATE_SEED_JSON = "{\"enabledChecks\":[\"SONAR\",\"COMPONENT_VULN\","
        + "\"ARCHITECTURE\",\"VERSION_DEFECT\",\"SECURITY_DYNAMIC\",\"AUTO_TEST\",\"DB_NORM\"]}";

    private PipelineConfigFixtures() {
    }

    /**
     * Child-first delete of every table the slice reads or writes, then the platform settings
     * seeds. The notification/window blocks are deliberately not touched.
     */
    static void clean(JdbcTemplate jdbc) {
        jdbc.update("DELETE FROM unit_pipeline_config");
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
        jdbc.update("DELETE FROM role_permission WHERE role = ?", SCOPE_ROLE);
        resetSettings(jdbc);
    }

    /** Re-inserts the three seeded sections, restoring the documented defaults. */
    static void resetSettings(JdbcTemplate jdbc) {
        jdbc.update("DELETE FROM app_setting");
        jdbc.update("INSERT INTO app_setting (setting_key, value_json, updated_at)"
            + " VALUES ('GATE', ?, NOW(3))", GATE_SEED_JSON);
        jdbc.update("INSERT INTO app_setting (setting_key, value_json, updated_at)"
            + " VALUES ('APPROVER', '{\"opsLead\":[],\"deptManager\":[]}', NOW(3))");
        jdbc.update("INSERT INTO app_setting (setting_key, value_json, updated_at)"
            + " VALUES ('LOCK', '{\"pauseBoundHours\":168}', NOW(3))");
    }

    static void givenUser(JdbcTemplate jdbc, String userId, String... roles) {
        jdbc.update("INSERT INTO app_user (id, name, status, created_at)"
            + " VALUES (?, ?, 'ACTIVE', NOW(3))", userId, "用户" + userId);
        for (String role : roles) {
            jdbc.update("INSERT INTO user_role (user_id, role) VALUES (?, ?)", userId, role);
        }
    }

    /** A non-ADMIN role that holds {@code setting:manage} but is limited to explicit unit scopes. */
    static void givenUnitSettingsRole(JdbcTemplate jdbc) {
        jdbc.update("INSERT INTO role_permission (role, perm) VALUES (?, 'setting:manage')",
            SCOPE_ROLE);
    }

    static void givenScope(JdbcTemplate jdbc, String userId, long unitId) {
        jdbc.update("INSERT INTO user_scope (user_id, scope_type, scope_id)"
            + " VALUES (?, 'UNIT', ?)", userId, unitId);
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
                + " 'outbound-os/p6c', NOW(3), NOW(3))",
            subsystemId, name, OWNER);
        Long id = jdbc.queryForObject(
            "SELECT id FROM deploy_unit WHERE subsystem_id = ? AND name = ?", Long.class,
            subsystemId, name);
        return id == null ? 0L : id;
    }

    /** A {@code LIB} deploy unit with the artifact coordinates the publish path validates. */
    static long givenLibUnit(JdbcTemplate jdbc, long subsystemId, String name) {
        jdbc.update("INSERT INTO deploy_unit (subsystem_id, name, type, owner_id,"
                + " framework_template, branch_mode, status, module_path, artifact_group_id,"
                + " artifact_artifact_id, created_at, updated_at)"
                + " VALUES (?, ?, 'LIB', ?, 'spring-boot', 'STANDARD', 'ACTIVE', 'p6c-sdk',"
                + " 'com.sf.p6c', 'p6c-sdk', NOW(3), NOW(3))",
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

    /** The stored unit row; a missing row is exactly what "defaults" means for the tests. */
    static void givenConfig(JdbcTemplate jdbc, long unitId, boolean envChecksEnabled,
                            int buildTimeoutMinutes, int deployTimeoutMinutes, String batchOrder) {
        jdbc.update("INSERT INTO unit_pipeline_config (unit_id, env_checks_enabled,"
                + " build_timeout_minutes, deploy_timeout_minutes, batch_order, updated_by,"
                + " updated_at) VALUES (?, ?, ?, ?, ?, ?, NOW(3))",
            unitId, envChecksEnabled ? 1 : 0, buildTimeoutMinutes, deployTimeoutMinutes,
            batchOrder, OWNER);
    }

    static List<EnvDe> envDesOf(JdbcTemplate jdbc, long unitId) {
        return jdbc.query("SELECT * FROM env_de WHERE unit_id = ? ORDER BY deploy_batch_no, id",
                (rs, rowNum) -> {
                    EnvDePO po = new EnvDePO();
                    po.setId(rs.getLong("id"));
                    po.setUnitId(rs.getLong("unit_id"));
                    po.setEnvKind(rs.getString("env_kind"));
                    po.setEnvTag(rs.getString("env_tag"));
                    po.setDeName(rs.getString("de_name"));
                    po.setResourceName(rs.getString("resource_name"));
                    po.setCodingDeployTarget(rs.getString("coding_deploy_target"));
                    po.setDeployBatchNo(rs.getInt("deploy_batch_no"));
                    return po.toDomain();
                }, unitId);
    }
}