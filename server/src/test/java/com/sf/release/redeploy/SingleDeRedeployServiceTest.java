package com.sf.release.redeploy;

import com.sf.release.application.ItemAppService;
import com.sf.release.application.ItemAppService.CreateItemCmd;
import com.sf.release.application.PublishAppService;
import com.sf.release.application.PublishAppService.PublishResult;
import com.sf.release.application.SingleDeRedeployService;
import com.sf.release.application.SingleDeRedeployService.RedeployResult;
import com.sf.release.common.BizError;
import com.sf.release.common.BizException;
import com.sf.release.common.security.Permissions;
import com.sf.release.domain.pipeline.port.GitCommitProbe;
import com.sf.release.infra.lock.UnitLockService;
import com.sf.release.infra.security.TestUserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * P6-G design §4.1/§4.2: the single-DE redeploy against the real test schema.
 *
 * <p>Pinned here: a failed production DE creates a {@code RERUN_FROM_NODE} instance at
 * {@code PROD_DEPLOY} that retries the failed DE's <em>whole batch</em> (PENDING) and pre-marks
 * every other batch and earlier node {@code SKIPPED}, reusing the previous image and commit; the
 * item moves to {@code PROD_DEPLOYING}, unfinished checks are {@code INVALID}, the unit lock is
 * bound to the new instance and one {@code RERUN} audit row is written. The rejections: a
 * successful/non-deploy/historical-instance task, a done item, a live lock, a missing permission or
 * unit scope.
 *
 * <p>The fixture mirrors {@code RerunServiceTest}: publish, then {@code jdbc} turns the production
 * node into the failure under test. {@code release.pipeline.auto-start} is off in the test profile,
 * so nothing touches the CI engine.
 */
@SpringBootTest
@ActiveProfiles("test")
class SingleDeRedeployServiceTest {

    private static final String OWNER = "p6g-rd-owner";
    private static final LocalDate PLAN_DATE = LocalDate.of(2026, 11, 26);
    private static final String BASE = "a".repeat(40);
    private static final String BRANCH = "release/202611";
    private static final String MODULE = "outbound-os/quota";
    private static final String OLD_DIGEST = "sha256:p6g-old-image";

    private static final int PARAM_INVALID_CODE = 40000;
    private static final int STATE_NOT_ALLOWED_CODE = 40001;
    private static final int UNIT_LOCKED_CODE = 40006;
    private static final int FORBIDDEN_CODE = 40009;

    @Autowired ItemAppService itemService;
    @Autowired PublishAppService publishService;
    @Autowired SingleDeRedeployService redeployService;
    @Autowired UnitLockService unitLockService;
    @Autowired JdbcTemplate jdbc;
    @MockBean GitCommitProbe commitProbe;

    private long subsystemId;
    private long taskId;
    private long unitId;
    private long itemId;
    private long prodDeId;
    private long batch2DeBId;
    private long batch2DeCId;
    private long batch3DeId;

    @BeforeEach
    void resetFixtures() {
        TestUserContext.as(OWNER, Set.of("ADMIN"), Set.of(Permissions.CONTROL_EXECUTE_PROD));
        jdbc.update("DELETE FROM node_task");
        jdbc.update("DELETE FROM pipeline_node");
        jdbc.update("DELETE FROM pipeline_instance");
        jdbc.update("DELETE FROM release_order");
        jdbc.update("DELETE FROM deploy_unit_lock");
        jdbc.update("DELETE FROM gate_check");
        jdbc.update("DELETE FROM audit_log");
        jdbc.update("DELETE FROM release_item");
        jdbc.update("DELETE FROM release_task_member");
        jdbc.update("DELETE FROM release_task");
        jdbc.update("DELETE FROM env_de");
        jdbc.update("DELETE FROM coding_task_binding");
        jdbc.update("DELETE FROM deploy_unit");
        jdbc.update("DELETE FROM subsystem");
        jdbc.update("DELETE FROM app_user WHERE id = ?", OWNER);

        givenUser(OWNER);
        subsystemId = givenSubsystem("SCC-P6G-RD");
        taskId = givenTask(subsystemId, "202611-P6G-RD");
        unitId = givenUnit(subsystemId, "p6g-api");
        givenBinding(unitId, "BUILD", "P-BUILD-P6G");
        givenBinding(unitId, "TEST_DEPLOY", "P-TEST-P6G");
        givenBinding(unitId, "PROD_DEPLOY", "P-PROD-P6G");
        givenDe(unitId, "TEST", "p6g-sit", 0);
        prodDeId = givenDe(unitId, "PROD", "p6g-prod", 1);
        batch2DeBId = givenDe(unitId, "PROD", "p6g-prod-b", 2);
        batch2DeCId = givenDe(unitId, "PROD", "p6g-prod-c", 2);
        batch3DeId = givenDe(unitId, "PROD", "p6g-prod-d", 3);
        itemId = itemService.create(taskId,
            new CreateItemCmd(unitId, BRANCH, "单 DE 重部署", PLAN_DATE));
    }

    @AfterEach
    void clearUserContext() {
        TestUserContext.clear();
    }

    @Test
    void redeploy_retries_the_whole_target_batch_and_skips_everything_else() {
        PublishResult published = publishService.publish(itemId);
        long oldInstance = instanceId(published.pipelineNo());
        failProduction(oldInstance);
        long failedTask = taskOf(oldInstance, 2, batch2DeBId);
        givenRunningCheck(oldInstance);

        RedeployResult result = redeployService.redeploy(published.publishNo(), failedTask);

        assertThat(result.pipelineNo()).startsWith("PL-").isNotEqualTo(published.pipelineNo());
        assertThat(result.fromNode()).isEqualTo("PROD_DEPLOY");
        assertThat(result.batchNo()).isEqualTo(2);
        assertThat(result.targetDeIds())
            .as("the failed DE's whole batch is retried together")
            .containsExactlyInAnyOrder(batch2DeBId, batch2DeCId);
        assertThat(result.targetDeName()).isEqualTo("p6g-prod-b");

        long newInstance = instanceId(result.pipelineNo());
        Map<String, Object> instance = instance(newInstance);
        assertThat(instance.get("trigger_type")).isEqualTo("RERUN_FROM_NODE");
        assertThat(instance.get("current_node")).isEqualTo("PROD_DEPLOY");
        assertThat(instance.get("status")).isEqualTo("PENDING");
        assertThat(instance.get("image_digest"))
            .as("the previous image is reused; a redeploy never rebuilds")
            .isEqualTo(OLD_DIGEST);
        assertThat(instance.get("commit_sha")).isEqualTo(BASE);
        assertThat(publishNoOf(newInstance)).isEqualTo(published.publishNo());

        assertThat(nodeStatuses(newInstance))
            .containsExactly("BUILD:SKIPPED", "TEST_DEPLOY:SKIPPED", "VERIFY:SKIPPED",
                "PROD_DEPLOY:PENDING");
        assertThat(productionTaskRows(newInstance)).containsExactly(
            "1:" + prodDeId + ":SKIPPED",
            "2:" + batch2DeBId + ":PENDING",
            "2:" + batch2DeCId + ":PENDING",
            "3:" + batch3DeId + ":SKIPPED");

        assertThat(itemStatus()).isEqualTo("PROD_DEPLOYING");
        assertThat(count("release_order")).isEqualTo(1);
        assertThat(count("pipeline_instance")).isEqualTo(2);
        assertThat(lockInstanceId()).isEqualTo(newInstance);
        assertThat(taskStatus(failedTask))
            .as("history is read-only: the failed row itself is never touched")
            .isEqualTo("FAILED");
    }

    @Test
    void redeploy_invalidates_only_the_unfinished_checks() {
        PublishResult published = publishService.publish(itemId);
        long oldInstance = instanceId(published.pipelineNo());
        failProduction(oldInstance);
        long failedTask = taskOf(oldInstance, 2, batch2DeBId);
        long runningCheck = givenCheck(oldInstance, "SONAR", "RUNNING");
        long passedCheck = givenCheck(oldInstance, "CODE_LINT", "PASS");

        redeployService.redeploy(published.publishNo(), failedTask);

        assertThat(jdbc.queryForObject(
            "SELECT status FROM gate_check WHERE id = ?", String.class, runningCheck))
            .isEqualTo("INVALID");
        assertThat(jdbc.queryForObject(
            "SELECT status FROM gate_check WHERE id = ?", String.class, passedCheck))
            .as("a finished verdict stays as evidence")
            .isEqualTo("PASS");
        assertThat(jdbc.queryForObject(
            "SELECT gate_status FROM release_item WHERE id = ?", String.class, itemId))
            .isEqualTo("CHECK_INVALID");
    }

    @Test
    void redeploy_writes_one_rerun_audit_row() {
        PublishResult published = publishService.publish(itemId);
        long oldInstance = instanceId(published.pipelineNo());
        failProduction(oldInstance);
        long failedTask = taskOf(oldInstance, 2, batch2DeBId);

        redeployService.redeploy(published.publishNo(), failedTask);

        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT actor, action, object_type, object_id, summary, scope_unit_id"
                + " FROM audit_log WHERE action = 'RERUN'");
        assertThat(rows).hasSize(1);
        Map<String, Object> row = rows.get(0);
        assertThat(row.get("actor")).isEqualTo(OWNER);
        assertThat(row.get("object_type")).isEqualTo("ORDER");
        assertThat(row.get("object_id")).isEqualTo(published.publishNo());
        assertThat(String.valueOf(row.get("summary"))).contains("单 DE 重部署");
        assertThat(((Number) row.get("scope_unit_id")).longValue()).isEqualTo(unitId);
    }

    @Test
    void redeploy_rejects_a_successful_task_with_40001() {
        PublishResult published = publishService.publish(itemId);
        long oldInstance = instanceId(published.pipelineNo());
        failProduction(oldInstance);
        long failedTask = taskOf(oldInstance, 2, batch2DeBId);
        jdbc.update("UPDATE node_task SET status = 'SUCCESS' WHERE id = ?", failedTask);

        assertThatThrownBy(() -> redeployService.redeploy(published.publishNo(), failedTask))
            .isInstanceOf(BizException.class)
            .satisfies(thrown -> assertThat(((BizException) thrown).getError().getCode())
                .isEqualTo(STATE_NOT_ALLOWED_CODE));
        assertThat(count("pipeline_instance")).isEqualTo(1);
    }

    @Test
    void redeploy_rejects_a_non_deploy_task_with_40001() {
        PublishResult published = publishService.publish(itemId);
        long oldInstance = instanceId(published.pipelineNo());
        failProduction(oldInstance);
        Long buildTask = jdbc.queryForObject(
            "SELECT id FROM node_task WHERE instance_id = ? AND task_type = 'CODE_CHECK'",
            Long.class, oldInstance);

        assertThatThrownBy(() -> redeployService.redeploy(published.publishNo(), buildTask))
            .isInstanceOf(BizException.class)
            .satisfies(thrown -> assertThat(((BizException) thrown).getError().getCode())
                .isEqualTo(STATE_NOT_ALLOWED_CODE));
    }

    @Test
    void redeploy_rejects_a_historical_instance_task_with_40001() {
        PublishResult published = publishService.publish(itemId);
        long oldInstance = instanceId(published.pipelineNo());
        failProduction(oldInstance);
        long failedTask = taskOf(oldInstance, 2, batch2DeBId);
        long orderId = jdbc.queryForObject(
            "SELECT order_id FROM pipeline_instance WHERE id = ?", Long.class, oldInstance);
        // A newer instance of the same 发布单 makes the failed task historical evidence.
        jdbc.update("INSERT INTO pipeline_instance (order_id, pipeline_no, exec_type,"
                + " trigger_type, current_node, status, image_digest, commit_sha, created_at)"
                + " VALUES (?, 'PL-P6G-NEWER', 'ONLINE', 'RERUN_FROM_NODE', 'PROD_DEPLOY',"
                + " 'PENDING', ?, ?, NOW(3))",
            orderId, OLD_DIGEST, BASE);

        assertThatThrownBy(() -> redeployService.redeploy(published.publishNo(), failedTask))
            .isInstanceOf(BizException.class)
            .hasMessageContaining("历史实例")
            .satisfies(thrown -> assertThat(((BizException) thrown).getError().getCode())
                .isEqualTo(STATE_NOT_ALLOWED_CODE));
    }

    @Test
    void redeploy_rejects_a_done_item_with_40001() {
        PublishResult published = publishService.publish(itemId);
        long oldInstance = instanceId(published.pipelineNo());
        failProduction(oldInstance);
        long failedTask = taskOf(oldInstance, 2, batch2DeBId);
        jdbc.update("UPDATE release_item SET status = 'DONE' WHERE id = ?", itemId);

        assertThatThrownBy(() -> redeployService.redeploy(published.publishNo(), failedTask))
            .isInstanceOf(BizException.class)
            .satisfies(thrown -> assertThat(((BizException) thrown).getError().getCode())
                .isEqualTo(STATE_NOT_ALLOWED_CODE));
    }

    @Test
    void redeploy_rejects_a_live_unit_lock_with_40006() {
        PublishResult published = publishService.publish(itemId);
        long oldInstance = instanceId(published.pipelineNo());
        failProductionWithoutReleasingTheLock(oldInstance);
        long failedTask = taskOf(oldInstance, 2, batch2DeBId);

        assertThatThrownBy(() -> redeployService.redeploy(published.publishNo(), failedTask))
            .isInstanceOf(BizException.class)
            .satisfies(thrown -> assertThat(((BizException) thrown).getError().getCode())
                .isEqualTo(UNIT_LOCKED_CODE));
        assertThat(count("pipeline_instance")).isEqualTo(1);
    }

    @Test
    void redeploy_requires_the_execute_prod_permission_40009() {
        PublishResult published = publishService.publish(itemId);
        long oldInstance = instanceId(published.pipelineNo());
        failProduction(oldInstance);
        long failedTask = taskOf(oldInstance, 2, batch2DeBId);
        TestUserContext.scopedWithPerms(OWNER, Set.of(), unitId);

        assertThatThrownBy(() -> redeployService.redeploy(published.publishNo(), failedTask))
            .isInstanceOf(BizException.class)
            .satisfies(thrown -> assertThat(((BizException) thrown).getError().getCode())
                .isEqualTo(FORBIDDEN_CODE));
    }

    @Test
    void redeploy_requires_the_unit_scope_40009() {
        PublishResult published = publishService.publish(itemId);
        long oldInstance = instanceId(published.pipelineNo());
        failProduction(oldInstance);
        long failedTask = taskOf(oldInstance, 2, batch2DeBId);
        TestUserContext.scopedWithPerms(OWNER, Set.of("control:execute-prod"), unitId + 1);

        assertThatThrownBy(() -> redeployService.redeploy(published.publishNo(), failedTask))
            .isInstanceOf(BizException.class)
            .satisfies(thrown -> assertThat(((BizException) thrown).getError().getCode())
                .isEqualTo(FORBIDDEN_CODE));
    }

    @Test
    void redeploy_rejects_an_unknown_publish_or_task_with_40000() {
        PublishResult published = publishService.publish(itemId);
        long oldInstance = instanceId(published.pipelineNo());
        failProduction(oldInstance);
        long failedTask = taskOf(oldInstance, 2, batch2DeBId);

        assertThatThrownBy(() -> redeployService.redeploy("PUB-NOT-EXIST", failedTask))
            .isInstanceOf(BizException.class)
            .satisfies(thrown -> assertThat(((BizException) thrown).getError().getCode())
                .isEqualTo(PARAM_INVALID_CODE));
        assertThatThrownBy(() -> redeployService.redeploy(published.publishNo(), 999_999L))
            .isInstanceOf(BizException.class)
            .satisfies(thrown -> assertThat(((BizException) thrown).getError().getCode())
                .isEqualTo(PARAM_INVALID_CODE));
    }

    // ------------------------------------------------------------------ fixtures and helpers

    private static final String[] NODE_ORDER =
        {"BUILD", "TEST_DEPLOY", "VERIFY", "PROD_DEPLOY"};

    /**
     * Turns the initial instance into a failed production deployment: earlier nodes succeeded,
     * {@code PROD_DEPLOY} failed, batch 1 succeeded, batch 2 failed, batch 3 never ran. The item,
     * the order and the instance carry a reusable image/commit, and the unit lock is released as a
     * terminal pipeline would have left it.
     */
    private void failProduction(long instanceId) {
        failProductionWithoutReleasingTheLock(instanceId);
        assertThat(unitLockService.release(unitId, instanceId)).isTrue();
    }

    /** The same failure, but the initial publish still holds the unit lock. */
    private void failProductionWithoutReleasingTheLock(long instanceId) {
        for (String node : NODE_ORDER) {
            String status = "PROD_DEPLOY".equals(node) ? "FAILED" : "SUCCESS";
            jdbc.update("UPDATE pipeline_node SET status = ? WHERE instance_id = ?"
                + " AND node_type = ?", status, instanceId, node);
        }
        jdbc.update("UPDATE pipeline_instance SET status = 'FAILED', image_digest = ?,"
            + " commit_sha = ? WHERE id = ?", OLD_DIGEST, BASE, instanceId);
        jdbc.update("UPDATE release_item SET status = 'FAILED', base_commit = ? WHERE id = ?",
            BASE, itemId);
        jdbc.update("UPDATE release_order SET status = 'FAILED'");
        jdbc.update("UPDATE node_task SET status = 'SUCCESS' WHERE instance_id = ?"
            + " AND node_type = 'PROD_DEPLOY' AND batch_no = 1", instanceId);
        jdbc.update("UPDATE node_task SET status = 'FAILED' WHERE instance_id = ?"
            + " AND node_type = 'PROD_DEPLOY' AND batch_no = 2", instanceId);
        jdbc.update("UPDATE node_task SET status = 'SKIPPED' WHERE instance_id = ?"
            + " AND node_type = 'PROD_DEPLOY' AND batch_no = 3", instanceId);
    }

    private long taskOf(long instanceId, int batchNo, long targetId) {
        Long id = jdbc.queryForObject(
            "SELECT id FROM node_task WHERE instance_id = ? AND node_type = 'PROD_DEPLOY'"
                + " AND batch_no = ? AND target_id = ?", Long.class, instanceId, batchNo, targetId);
        return id == null ? 0L : id;
    }

    private String taskStatus(long taskId) {
        return jdbc.queryForObject("SELECT status FROM node_task WHERE id = ?", String.class,
            taskId);
    }

    private long givenCheck(long instanceId, String kind, String status) {
        jdbc.update("INSERT INTO gate_check (item_id, order_id, instance_id, check_kind, stage,"
                + " status, severity, issues, attempt, created_at, updated_at)"
                + " VALUES (?, (SELECT order_id FROM pipeline_instance WHERE id = ?), ?, ?,"
                + " 'ENV', ?, 'S2', 0, 0, NOW(3), NOW(3))",
            itemId, instanceId, instanceId, kind, status);
        Long id = jdbc.queryForObject(
            "SELECT id FROM gate_check WHERE item_id = ? AND check_kind = ?", Long.class,
            itemId, kind);
        return id == null ? 0L : id;
    }

    private long givenRunningCheck(long instanceId) {
        return givenCheck(instanceId, "SONAR", "RUNNING");
    }

    private int count(String table) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
        return count == null ? 0 : count;
    }

    private Map<String, Object> instance(long instanceId) {
        return jdbc.queryForMap("SELECT * FROM pipeline_instance WHERE id = ?", instanceId);
    }

    private List<String> nodeStatuses(long instanceId) {
        return jdbc.queryForList(
            "SELECT CONCAT(node_type, ':', status) FROM pipeline_node WHERE instance_id = ?"
                + " ORDER BY seq_no", String.class, instanceId);
    }

    /** One {@code batch:target:status} line per production deploy task, ordered by batch/target. */
    private List<String> productionTaskRows(long instanceId) {
        return jdbc.queryForList(
            "SELECT CONCAT(batch_no, ':', target_id, ':', status) FROM node_task"
                + " WHERE instance_id = ? AND node_type = 'PROD_DEPLOY'"
                + " ORDER BY batch_no, target_id", String.class, instanceId);
    }

    private String itemStatus() {
        return jdbc.queryForObject("SELECT status FROM release_item WHERE id = ?",
            String.class, itemId);
    }

    private String publishNoOf(long instanceId) {
        Long orderId = jdbc.queryForObject(
            "SELECT order_id FROM pipeline_instance WHERE id = ?", Long.class, instanceId);
        return jdbc.queryForObject("SELECT publish_no FROM release_order WHERE id = ?",
            String.class, orderId);
    }

    private long instanceId(String pipelineNo) {
        Long id = jdbc.queryForObject(
            "SELECT id FROM pipeline_instance WHERE pipeline_no = ?", Long.class, pipelineNo);
        return id == null ? 0L : id;
    }

    private long lockInstanceId() {
        List<Long> ids = jdbc.queryForList(
            "SELECT instance_id FROM deploy_unit_lock WHERE unit_id = ?", Long.class, unitId);
        return ids.isEmpty() ? -1L : ids.get(0);
    }

    private void givenUser(String userId) {
        jdbc.update("INSERT INTO app_user (id, name, status, created_at) VALUES (?, ?, 'ACTIVE', NOW(3))",
            userId, "用户" + userId);
    }

    private long givenSubsystem(String name) {
        jdbc.update("INSERT INTO subsystem (name, owner_id, status, created_at, updated_at)"
            + " VALUES (?, ?, 'ACTIVE', NOW(3), NOW(3))", name, OWNER);
        Long id = jdbc.queryForObject("SELECT id FROM subsystem WHERE name = ?", Long.class, name);
        return id == null ? 0L : id;
    }

    private long givenTask(long subsystem, String name) {
        jdbc.update("INSERT INTO release_task (subsystem_id, name, creator, purpose,"
                + " plan_finish_date, status, created_at, updated_at)"
                + " VALUES (?, ?, ?, '新功能', ?, 'ACTIVE', NOW(3), NOW(3))",
            subsystem, name, OWNER, PLAN_DATE);
        Long id = jdbc.queryForObject(
            "SELECT id FROM release_task WHERE subsystem_id = ? AND name = ?", Long.class,
            subsystem, name);
        return id == null ? 0L : id;
    }

    private long givenUnit(long subsystem, String name) {
        jdbc.update("INSERT INTO deploy_unit (subsystem_id, name, type, owner_id, framework_template,"
                + " branch_mode, status, module_path, created_at, updated_at)"
                + " VALUES (?, ?, 'UNIT', ?, 'spring-boot', 'STANDARD', 'ACTIVE', ?,"
                + " NOW(3), NOW(3))",
            subsystem, name, OWNER, MODULE);
        Long id = jdbc.queryForObject(
            "SELECT id FROM deploy_unit WHERE subsystem_id = ? AND name = ?", Long.class,
            subsystem, name);
        return id == null ? 0L : id;
    }

    private void givenBinding(long unit, String kind, String taskId) {
        jdbc.update("INSERT INTO coding_task_binding (unit_id, task_kind, task_id, enabled,"
                + " created_at, updated_at) VALUES (?, ?, ?, 1, NOW(3), NOW(3))",
            unit, kind, taskId);
    }

    private long givenDe(long unit, String envKind, String name, int batchNo) {
        jdbc.update("INSERT INTO env_de (unit_id, env_kind, de_name, deploy_batch_no,"
                + " created_at, updated_at) VALUES (?, ?, ?, ?, NOW(3), NOW(3))",
            unit, envKind, name, batchNo);
        Long id = jdbc.queryForObject(
            "SELECT id FROM env_de WHERE unit_id = ? AND de_name = ?", Long.class, unit, name);
        return id == null ? 0L : id;
    }
}
