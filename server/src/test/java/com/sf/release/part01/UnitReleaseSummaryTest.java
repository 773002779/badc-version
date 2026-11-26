package com.sf.release.part01;

import com.sf.release.application.UnitReleaseSummaryService;
import com.sf.release.common.BizError;
import com.sf.release.common.BizException;
import com.sf.release.infra.security.TestUserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PART-01 design §3.1: the unit list's three summary columns against the real schema.
 *
 * <p>Pinned here: the newest successful production deploy is the same definition the rollback
 * drawer shows (the seed's batch 1, not the failed batch 2); a lock row is surfaced as-is (an
 * expired lease stays visible until the reclaim job runs); a unit without production history
 * answers two null sections instead of an error; unknown unit 40000 and out-of-scope 40009.
 */
@SpringBootTest
@ActiveProfiles("test")
class UnitReleaseSummaryTest {

    private static final String OWNER = "p01-sum-owner";
    private static final String PUBLISH = "PO-P01-SUM";
    private static final String PIPELINE = "PL-P01-SUM";
    private static final String DIGEST = "sha256:p01-sum-image";
    private static final int PARAM_INVALID_CODE = 40000;
    private static final int FORBIDDEN_CODE = 40009;

    @Autowired UnitReleaseSummaryService summaryService;
    @Autowired JdbcTemplate jdbc;

    private long unitId;
    private long subsystemId;

    @BeforeEach
    void resetFixtures() {
        TestUserContext.allUnits(OWNER);
        jdbc.update("DELETE FROM node_task WHERE instance_id IN"
            + " (SELECT id FROM pipeline_instance WHERE pipeline_no = ?)", PIPELINE);
        jdbc.update("DELETE FROM pipeline_node WHERE instance_id IN"
            + " (SELECT id FROM pipeline_instance WHERE pipeline_no = ?)", PIPELINE);
        jdbc.update("DELETE FROM deploy_unit_lock WHERE unit_id IN"
            + " (SELECT id FROM deploy_unit WHERE name = 'p01-sum-api')");
        jdbc.update("DELETE FROM pipeline_instance WHERE pipeline_no = ?", PIPELINE);
        jdbc.update("DELETE FROM release_order WHERE publish_no = ?", PUBLISH);
        jdbc.update("DELETE FROM release_item WHERE crid LIKE 'CR-P01-SUM%'");
        jdbc.update("DELETE FROM release_task_member WHERE task_id IN"
            + " (SELECT id FROM release_task WHERE name = 'P01-SUM-TASK')");
        jdbc.update("DELETE FROM release_task WHERE name = 'P01-SUM-TASK'");
        jdbc.update("DELETE FROM env_de WHERE unit_id IN"
            + " (SELECT id FROM deploy_unit WHERE name = 'p01-sum-api')");
        jdbc.update("DELETE FROM deploy_unit WHERE name = 'p01-sum-api'");
        jdbc.update("DELETE FROM subsystem WHERE name = 'SCC-P01-SUM'");
        jdbc.update("DELETE FROM app_user WHERE id = ?", OWNER);

        jdbc.update("INSERT INTO app_user (id, name, status, created_at) VALUES (?, ?, 'ACTIVE', NOW(3))",
            OWNER, "用户" + OWNER);
        jdbc.update("INSERT INTO subsystem (name, owner_id, status, created_at, updated_at)"
            + " VALUES ('SCC-P01-SUM', ?, 'ACTIVE', NOW(3), NOW(3))", OWNER);
        subsystemId = jdbc.queryForObject(
            "SELECT id FROM subsystem WHERE name = 'SCC-P01-SUM'", Long.class);
        jdbc.update("INSERT INTO release_task (subsystem_id, name, creator, purpose,"
                + " plan_finish_date, status, created_at, updated_at)"
                + " VALUES (?, 'P01-SUM-TASK', ?, 'P01', CURDATE(), 'ACTIVE', NOW(3), NOW(3))",
            subsystemId, OWNER);
        long task = jdbc.queryForObject(
            "SELECT id FROM release_task WHERE name = 'P01-SUM-TASK'", Long.class);
        jdbc.update("INSERT INTO deploy_unit (subsystem_id, name, type, owner_id, framework_template,"
                + " branch_mode, status, created_at, updated_at)"
                + " VALUES (?, 'p01-sum-api', 'UNIT', ?, 'spring-boot', 'STANDARD', 'ACTIVE',"
                + " NOW(3), NOW(3))", subsystemId, OWNER);
        unitId = jdbc.queryForObject(
            "SELECT id FROM deploy_unit WHERE name = 'p01-sum-api'", Long.class);
        jdbc.update("INSERT INTO env_de (unit_id, env_kind, de_name, deploy_batch_no, created_at,"
            + " updated_at) VALUES (?, 'PROD', 'p01-sum-prod', 1, NOW(3), NOW(3))", unitId);
        long item = givenItem(task);
        givenSuccessfulProductionDeploy(item);
    }

    @AfterEach
    void clearUserContext() {
        TestUserContext.clear();
    }

    @Test
    void latest_is_the_newest_successful_production_deploy() {
        UnitReleaseSummaryService.Summary summary = summaryService.summary(unitId);

        assertThat(summary.latest()).isNotNull();
        assertThat(summary.latest().digest()).isEqualTo(DIGEST);
        assertThat(summary.latest().publishNo()).isEqualTo(PUBLISH);
        assertThat(summary.latest().executedBy()).isEqualTo(OWNER);
        assertThat(summary.latest().finishedAt()).isAfter(Instant.now().minusSeconds(60));
        assertThat(summary.lock()).as("no lock row was seeded").isNull();
    }

    @Test
    void the_lock_row_is_surfaced_as_is_even_past_its_lease() {
        jdbc.update("INSERT INTO deploy_unit_lock (unit_id, instance_id, acquired_at, expire_at)"
                + " VALUES (?, 4242, UTC_TIMESTAMP(3) - INTERVAL 1 HOUR,"
                + " UTC_TIMESTAMP(3) - INTERVAL 10 MINUTE)", unitId);

        UnitReleaseSummaryService.Summary summary = summaryService.summary(unitId);

        assertThat(summary.lock()).isNotNull();
        assertThat(summary.lock().instanceId()).isEqualTo(4242);
        assertThat(summary.lock().expireAt()).isBefore(Instant.now());
    }

    @Test
    void a_unit_without_production_history_answers_null_sections() {
        jdbc.update("INSERT INTO deploy_unit (subsystem_id, name, type, owner_id, framework_template,"
                + " branch_mode, status, created_at, updated_at)"
                + " VALUES (?, 'p01-sum-bare', 'UNIT', ?, 'spring-boot', 'STANDARD', 'ACTIVE',"
                + " NOW(3), NOW(3))", subsystemId, OWNER);
        long bare = jdbc.queryForObject(
            "SELECT id FROM deploy_unit WHERE name = 'p01-sum-bare'", Long.class);

        UnitReleaseSummaryService.Summary summary = summaryService.summary(bare);

        assertThat(summary.latest()).isNull();
        assertThat(summary.lock()).isNull();
    }

    @Test
    void an_unknown_unit_is_40000() {
        assertThatThrownBy(() -> summaryService.summary(999_999L))
            .isInstanceOf(BizException.class)
            .satisfies(thrown -> assertThat(((BizException) thrown).getError().getCode())
                .isEqualTo(PARAM_INVALID_CODE));
    }

    @Test
    void a_caller_outside_the_unit_scope_is_40009() {
        TestUserContext.scopedWithPerms(OWNER, Set.of(), unitId + 1);

        assertThatThrownBy(() -> summaryService.summary(unitId))
            .isInstanceOf(BizException.class)
            .satisfies(thrown -> assertThat(((BizException) thrown).getError().getCode())
                .isEqualTo(FORBIDDEN_CODE));
    }

    // ------------------------------------------------------------------ fixtures and helpers

    private long givenItem(long task) {
        jdbc.update("INSERT INTO release_item (task_id, crid, type, unit_id, build_plan_id,"
                + " source_branch, version, plan_release_date, status, gate_status, test_status,"
                + " row_version, created_at, updated_at)"
                + " VALUES (?, 'CR-P01-SUM', 'UNIT', ?, 'p01-build', 'release/p01',"
                + " 'V0.0.1-p01', CURDATE(), 'FAILED', 'CHECK_PASS', 'TEST_PASS', 0, NOW(3), NOW(3))",
            task, unitId);
        return jdbc.queryForObject(
            "SELECT id FROM release_item WHERE crid = 'CR-P01-SUM'", Long.class);
    }

    /** One order whose production batch 1 succeeded and batch 2 failed, like the P6-G demo seed. */
    private void givenSuccessfulProductionDeploy(long item) {
        jdbc.update("INSERT INTO release_order (publish_no, item_id, status, created_at, executed_by)"
                + " VALUES (?, ?, 'FAILED', NOW(3), ?)", PUBLISH, item, OWNER);
        long order = jdbc.queryForObject(
            "SELECT id FROM release_order WHERE publish_no = ?", Long.class, PUBLISH);
        jdbc.update("INSERT INTO pipeline_instance (order_id, pipeline_no, exec_type, trigger_type,"
                + " current_node, status, image_digest, created_at, item_id, unit_id, requested_by)"
                + " VALUES (?, ?, 'ONLINE', 'INITIAL', 'PROD_DEPLOY', 'FAILED', ?, NOW(3), ?, ?, ?)",
            order, PIPELINE, DIGEST, item, unitId, OWNER);
        long instance = jdbc.queryForObject(
            "SELECT id FROM pipeline_instance WHERE pipeline_no = ?", Long.class, PIPELINE);
        long de = jdbc.queryForObject(
            "SELECT id FROM env_de WHERE unit_id = ? AND env_kind = 'PROD'", Long.class, unitId);
        jdbc.update("INSERT INTO pipeline_node (instance_id, node_type, seq_no, status)"
            + " VALUES (?, 'PROD_DEPLOY', 3, 'FAILED')", instance);
        jdbc.update("INSERT INTO node_task (instance_id, node_type, task_type, batch_no, target_id,"
                + " status, attempt, finished_at)"
                + " VALUES (?, 'PROD_DEPLOY', 'DEPLOY', 1, ?, 'SUCCESS', 1, NOW(3))", instance, de);
        jdbc.update("INSERT INTO node_task (instance_id, node_type, task_type, batch_no, target_id,"
                + " status, attempt)"
                + " VALUES (?, 'PROD_DEPLOY', 'DEPLOY', 2, ?, 'FAILED', 1)", instance, de);
    }
}
