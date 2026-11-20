package com.sf.release.pipeline;

import com.sf.release.application.ItemAppService;
import com.sf.release.application.ItemAppService.CreateItemCmd;
import com.sf.release.application.PublishAppService;
import com.sf.release.application.PublishAppService.PublishResult;
import com.sf.release.application.RerunAppService;
import com.sf.release.application.RerunAppService.RerunResult;
import com.sf.release.common.BizError;
import com.sf.release.common.BizException;
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

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Re-publishing a failed 变更项 against the real test schema: the commit-based decision
 * (tech design §5.3) is applied through the Task 8 state machine and creates a new pipeline
 * instance under the same 发布单, re-acquiring the unit lock.
 *
 * <p>The git probe is a {@link MockBean} stub: the CODING repository is never contacted.
 */
@SpringBootTest
@ActiveProfiles("test")
class RerunServiceTest {

    private static final String OWNER = "01388007";
    private static final String TESTER = "01388007";
    private static final LocalDate PLAN_DATE = LocalDate.of(2026, 11, 25);
    private static final String BASE = "a".repeat(40);
    private static final String OTHER = "b".repeat(40);
    private static final String BRANCH = "release/202611";
    private static final String MODULE = "outbound-os/quota";
    private static final String OLD_DIGEST = "sha256:t15-old-image";

    private static final int PARAM_INVALID_CODE = 40000;
    private static final int STATE_NOT_ALLOWED_CODE = 40001;
    private static final int UNIT_LOCKED_CODE = 40006;

    @Autowired ItemAppService itemService;
    @Autowired PublishAppService publishService;
    @Autowired RerunAppService rerunService;
    @Autowired UnitLockService unitLockService;
    @Autowired JdbcTemplate jdbc;
    @MockBean GitCommitProbe commitProbe;

    private long subsystemId;
    private long taskId;
    private long unitId;
    private long itemId;

    @BeforeEach
    void resetFixtures() {
        TestUserContext.allUnits(OWNER);
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
        jdbc.update("DELETE FROM app_user WHERE id = ?", OWNER);

        givenUser(OWNER);
        subsystemId = givenSubsystem("SCC-T15");
        taskId = givenTask(subsystemId, "202611-T15");
        unitId = givenUnit(subsystemId, "t15-api");
        givenBinding(unitId, "BUILD", "P-BUILD-T15");
        givenBinding(unitId, "TEST_DEPLOY", "P-TEST-T15");
        givenBinding(unitId, "PROD_DEPLOY", "P-PROD-T15");
        givenDe(unitId, "TEST", "t15-sit", 0);
        givenDe(unitId, "PROD", "t15-prod", 1);
        givenDe(unitId, "PROD", "t15-prod-b", 2);
        itemId = itemService.create(taskId,
            new CreateItemCmd(unitId, BRANCH, "重新发布判定", PLAN_DATE));
    }

    @AfterEach
    void clearUserContext() {
        TestUserContext.clear();
    }

    @Test
    void same_commit_reruns_from_the_failed_node_and_keeps_the_test_pass() {
        PublishResult published = publishService.publish(itemId);
        long oldInstance = instanceId(published.pipelineNo());
        failLastInstance(oldInstance, "TEST_DEPLOY");
        when(commitProbe.headSha(unitId, BRANCH)).thenReturn(BASE);

        RerunResult result = rerunService.rerun(itemId, false);

        assertThat(result.full()).isFalse();
        assertThat(result.revokedTestPass()).isFalse();
        assertThat(result.degraded()).isFalse();
        assertThat(result.pipelineNo()).startsWith("PL-").isNotEqualTo(published.pipelineNo());

        long newInstance = instanceId(result.pipelineNo());
        assertThat(newInstance).isNotEqualTo(oldInstance);
        Map<String, Object> instance = instance(newInstance);
        assertThat(((Number) instance.get("order_id")).longValue())
            .isEqualTo(((Number) instance(oldInstance).get("order_id")).longValue());
        assertThat(instance.get("trigger_type")).isEqualTo("RERUN_FROM_NODE");
        assertThat(instance.get("current_node")).isEqualTo("TEST_DEPLOY");
        assertThat(instance.get("status")).isEqualTo("PENDING");
        assertThat(instance.get("image_digest"))
            .as("the previous image is reused for a same-commit rerun").isEqualTo(OLD_DIGEST);
        assertThat(instance.get("commit_sha")).isEqualTo(BASE);

        assertThat(nodeStatuses(newInstance))
            .containsExactly("BUILD:SKIPPED", "TEST_DEPLOY:PENDING", "VERIFY:PENDING",
                "PROD_DEPLOY:PENDING");

        assertThat(itemStatus()).isEqualTo("TESTING");
        assertThat(lastTestPassBy())
            .as("an unchanged commit keeps the test pass (review focus 4)")
            .isEqualTo(TESTER);
        assertThat(count("release_order")).isEqualTo(1);
        assertThat(count("pipeline_instance")).isEqualTo(2);
        assertThat(lockInstanceId()).isEqualTo(newInstance);
        assertThat(publishNoOf(newInstance)).isEqualTo(published.publishNo());
    }

    @Test
    void other_module_change_is_not_a_change_and_keeps_the_test_pass() {
        PublishResult published = publishService.publish(itemId);
        long oldInstance = instanceId(published.pipelineNo());
        failLastInstance(oldInstance, "TEST_DEPLOY");
        when(commitProbe.headSha(unitId, BRANCH)).thenReturn(OTHER);
        when(commitProbe.hasChangesUnder(unitId, BRANCH, MODULE, BASE)).thenReturn(false);

        RerunResult result = rerunService.rerun(itemId, false);

        assertThat(result.full()).isFalse();
        assertThat(result.revokedTestPass()).isFalse();
        assertThat(lastTestPassBy()).isEqualTo(TESTER);
        assertThat(instance(instanceId(result.pipelineNo())).get("trigger_type"))
            .isEqualTo("RERUN_FROM_NODE");
        verify(commitProbe).hasChangesUnder(unitId, BRANCH, MODULE, BASE);
    }

    @Test
    void same_commit_build_failure_rebuilds_but_keeps_the_test_pass() {
        PublishResult published = publishService.publish(itemId);
        long oldInstance = instanceId(published.pipelineNo());
        failLastInstance(oldInstance, "BUILD");
        when(commitProbe.headSha(unitId, BRANCH)).thenReturn(BASE);

        RerunResult result = rerunService.rerun(itemId, false);

        assertThat(result.full())
            .as("a resume from BUILD has no reusable image, so it rebuilds")
            .isTrue();
        assertThat(result.revokedTestPass())
            .as("the commit did not change, so the test pass is kept")
            .isFalse();
        long newInstance = instanceId(result.pipelineNo());
        assertThat(instance(newInstance).get("trigger_type")).isEqualTo("RERUN_FULL");
        assertThat(instance(newInstance).get("current_node")).isEqualTo("BUILD");
        assertThat(itemStatus()).isEqualTo("BUILDING");
        assertThat(lastTestPassBy()).isEqualTo(TESTER);
    }

    @Test
    void changed_commit_requires_confirmation_before_revoking_the_test_pass() {
        PublishResult published = publishService.publish(itemId);
        long oldInstance = instanceId(published.pipelineNo());
        failLastInstance(oldInstance, "TEST_DEPLOY");
        when(commitProbe.headSha(unitId, BRANCH)).thenReturn(OTHER);
        when(commitProbe.hasChangesUnder(anyLong(), anyString(), anyString(), anyString()))
            .thenReturn(true);

        assertThatThrownBy(() -> rerunService.rerun(itemId, false))
            .isInstanceOf(BizException.class)
            .satisfies(thrown -> {
                BizException ex = (BizException) thrown;
                assertThat(ex.getError()).isEqualTo(BizError.PARAM_INVALID);
                assertThat(ex.getError().getCode()).isEqualTo(PARAM_INVALID_CODE);
                assertThat(ex.getMessage()).contains("二次确认");
            });

        assertThat(count("pipeline_instance"))
            .as("without confirmation nothing is created").isEqualTo(1);
        assertThat(itemStatus()).isEqualTo("FAILED");
        assertThat(lastTestPassBy()).isEqualTo(TESTER);
        assertThat(count("deploy_unit_lock"))
            .as("the failed confirmation attempt did not take the unit").isZero();
    }

    @Test
    void confirmed_full_rerun_revokes_the_test_pass_and_starts_from_build() {
        PublishResult published = publishService.publish(itemId);
        long oldInstance = instanceId(published.pipelineNo());
        failLastInstance(oldInstance, "TEST_DEPLOY");
        when(commitProbe.headSha(unitId, BRANCH)).thenReturn(OTHER);
        when(commitProbe.hasChangesUnder(unitId, BRANCH, MODULE, BASE)).thenReturn(true);

        RerunResult result = rerunService.rerun(itemId, true);

        assertThat(result.full()).isTrue();
        assertThat(result.revokedTestPass()).isTrue();
        assertThat(result.degraded()).isFalse();

        long newInstance = instanceId(result.pipelineNo());
        Map<String, Object> instance = instance(newInstance);
        assertThat(instance.get("trigger_type")).isEqualTo("RERUN_FULL");
        assertThat(instance.get("current_node")).isEqualTo("BUILD");
        assertThat(instance.get("image_digest")).isNull();
        assertThat(instance.get("commit_sha")).isNull();
        assertThat(nodeStatuses(newInstance))
            .containsExactly("BUILD:PENDING", "TEST_DEPLOY:PENDING", "VERIFY:PENDING",
                "PROD_DEPLOY:PENDING");

        assertThat(itemStatus()).isEqualTo("BUILDING");
        assertThat(lastTestPassBy()).isNull();
        assertThat(jdbc.queryForObject(
            "SELECT last_test_pass_at FROM release_item WHERE id = ?", Instant.class, itemId))
            .isNull();
        assertThat(count("release_order")).isEqualTo(1);
        assertThat(count("pipeline_instance")).isEqualTo(2);
        assertThat(lockInstanceId()).isEqualTo(newInstance);
        assertThat(publishNoOf(newInstance)).isEqualTo(published.publishNo());
        assertThat(baseCommit())
            .as("a full rebuild advances the baseline to the observed head")
            .isEqualTo(OTHER);
    }

    @Test
    void rerun_without_a_test_pass_requires_no_confirmation_and_reports_no_revocation() {
        PublishResult published = publishService.publish(itemId);
        long oldInstance = instanceId(published.pipelineNo());
        failLastInstance(oldInstance, "BUILD");
        jdbc.update("UPDATE release_item SET last_test_pass_by = NULL,"
            + " last_test_pass_at = NULL WHERE id = ?", itemId);
        when(commitProbe.headSha(unitId, BRANCH)).thenReturn(BASE);

        RerunResult result = rerunService.rerun(itemId, false);

        assertThat(result.full()).isTrue();
        assertThat(result.revokedTestPass())
            .as("there was no test pass to revoke").isFalse();
        assertThat(lastTestPassBy()).isNull();
        assertThat(jdbc.queryForObject(
            "SELECT last_test_pass_at FROM release_item WHERE id = ?", Instant.class, itemId))
            .isNull();
        assertThat(itemStatus()).isEqualTo("BUILDING");
    }

    @Test
    void changed_commit_without_a_test_pass_does_not_require_confirmation() {
        PublishResult published = publishService.publish(itemId);
        long oldInstance = instanceId(published.pipelineNo());
        failLastInstance(oldInstance, "TEST_DEPLOY");
        jdbc.update("UPDATE release_item SET last_test_pass_by = NULL,"
            + " last_test_pass_at = NULL WHERE id = ?", itemId);
        when(commitProbe.headSha(unitId, BRANCH)).thenReturn(OTHER);
        when(commitProbe.hasChangesUnder(unitId, BRANCH, MODULE, BASE)).thenReturn(true);

        RerunResult result = rerunService.rerun(itemId, false);

        assertThat(result.full()).isTrue();
        assertThat(result.revokedTestPass()).isFalse();
        assertThat(instance(instanceId(result.pipelineNo())).get("trigger_type"))
            .isEqualTo("RERUN_FULL");
        assertThat(baseCommit()).isEqualTo(OTHER);
    }

    @Test
    void production_resume_skips_batches_that_already_succeeded() {
        PublishResult published = publishService.publish(itemId);
        long oldInstance = instanceId(published.pipelineNo());
        failProductionBatches(oldInstance, "SUCCESS", "FAILED");
        when(commitProbe.headSha(unitId, BRANCH)).thenReturn(BASE);

        RerunResult result = rerunService.rerun(itemId, false);

        assertThat(result.full()).isFalse();
        assertThat(result.revokedTestPass()).isFalse();
        long newInstance = instanceId(result.pipelineNo());
        assertThat(instance(newInstance).get("current_node")).isEqualTo("PROD_DEPLOY");
        assertThat(instance(newInstance).get("trigger_type")).isEqualTo("RERUN_FROM_NODE");
        assertThat(productionBatches(newInstance))
            .as("batch 1 succeeded and must not be redeployed")
            .containsExactly("1:SKIPPED", "2:PENDING");
    }

    @Test
    void full_rerun_keeps_all_production_batches_pending() {
        PublishResult published = publishService.publish(itemId);
        long oldInstance = instanceId(published.pipelineNo());
        failProductionBatches(oldInstance, "SUCCESS", "FAILED");
        when(commitProbe.headSha(unitId, BRANCH)).thenReturn(OTHER);
        when(commitProbe.hasChangesUnder(unitId, BRANCH, MODULE, BASE)).thenReturn(true);

        RerunResult result = rerunService.rerun(itemId, true);

        assertThat(result.full()).isTrue();
        long newInstance = instanceId(result.pipelineNo());
        assertThat(instance(newInstance).get("current_node")).isEqualTo("BUILD");
        assertThat(productionBatches(newInstance))
            .as("a full rebuild deploys every batch again")
            .containsExactly("1:PENDING", "2:PENDING");
        assertThat(baseCommit()).isEqualTo(OTHER);
    }

    @Test
    void missing_module_path_degrades_to_a_whole_repo_full_rerun() {
        PublishResult published = publishService.publish(itemId);
        long oldInstance = instanceId(published.pipelineNo());
        failLastInstance(oldInstance, "TEST_DEPLOY");
        jdbc.update("UPDATE release_item SET module_path = NULL WHERE id = ?", itemId);
        when(commitProbe.headSha(unitId, BRANCH)).thenReturn(OTHER);

        RerunResult result = rerunService.rerun(itemId, true);

        assertThat(result.full()).isTrue();
        assertThat(result.degraded())
            .as("the whole-repository comparison is flagged to the caller").isTrue();
        assertThat(instance(instanceId(result.pipelineNo())).get("trigger_type"))
            .isEqualTo("RERUN_FULL");
        verify(commitProbe, never()).hasChangesUnder(anyLong(), anyString(), anyString(),
            anyString());
    }

    @Test
    void rerun_is_rejected_from_done_or_testing_with_40001() {
        publishService.publish(itemId);
        jdbc.update("UPDATE release_item SET status = 'TESTING' WHERE id = ?", itemId);
        assertThatThrownBy(() -> rerunService.rerun(itemId, false))
            .as("an in-flight item cannot be republished")
            .isInstanceOf(BizException.class)
            .extracting("error").isEqualTo(BizError.STATE_NOT_ALLOWED)
            .satisfies(error -> assertThat(((BizError) error).getCode())
                .isEqualTo(STATE_NOT_ALLOWED_CODE));

        jdbc.update("UPDATE release_item SET status = 'DONE' WHERE id = ?", itemId);
        assertThatThrownBy(() -> rerunService.rerun(itemId, false))
            .isInstanceOf(BizException.class)
            .extracting("error").isEqualTo(BizError.STATE_NOT_ALLOWED)
            .satisfies(error -> assertThat(((BizError) error).getCode())
                .isEqualTo(STATE_NOT_ALLOWED_CODE));

        assertThat(count("pipeline_instance")).isEqualTo(1);
    }

    @Test
    void rerun_rejects_an_unknown_item_with_40000() {
        assertThatThrownBy(() -> rerunService.rerun(999_999L, false))
            .isInstanceOf(BizException.class)
            .extracting("error").isEqualTo(BizError.PARAM_INVALID)
            .satisfies(error -> assertThat(((BizError) error).getCode())
                .isEqualTo(PARAM_INVALID_CODE));
    }

    @Test
    void rerun_reacquires_the_unit_lock_and_rejects_a_live_holder_with_40006() {
        publishService.publish(itemId);
        // The initial publish still holds the unit lock: a rerun must not create a second
        // pipeline over the same deployment unit, even though the item is now failed.
        jdbc.update("UPDATE release_item SET status = 'FAILED', base_commit = ? WHERE id = ?",
            BASE, itemId);
        jdbc.update("UPDATE pipeline_instance SET image_digest = ?, commit_sha = ?",
            OLD_DIGEST, BASE);
        when(commitProbe.headSha(unitId, BRANCH)).thenReturn(BASE);

        assertThatThrownBy(() -> rerunService.rerun(itemId, false))
            .isInstanceOf(BizException.class)
            .extracting("error").isEqualTo(BizError.UNIT_LOCKED)
            .satisfies(error -> assertThat(((BizError) error).getCode())
                .isEqualTo(UNIT_LOCKED_CODE));

        assertThat(count("pipeline_instance")).isEqualTo(1);
    }

    // ------------------------------------------------------------------ fixtures and helpers

    private static final String[] NODE_ORDER =
        {"BUILD", "TEST_DEPLOY", "VERIFY", "PROD_DEPLOY"};

    /**
     * Turns the initial instance into a failed one: nodes before {@code failedNode} succeeded,
     * the failed node is FAILED and the rest are SKIPPED. The item is set to FAILED with a
     * baseline commit and a test pass, and the unit lock is released as a terminal pipeline
     * would have left it.
     */
    private void failLastInstance(long instanceId, String failedNode) {
        int failedIndex = List.of(NODE_ORDER).indexOf(failedNode);
        for (int i = 0; i < NODE_ORDER.length; i++) {
            String status = i < failedIndex ? "SUCCESS" : i == failedIndex ? "FAILED" : "SKIPPED";
            jdbc.update("UPDATE pipeline_node SET status = ? WHERE instance_id = ?"
                + " AND node_type = ?", status, instanceId, NODE_ORDER[i]);
        }
        jdbc.update("UPDATE pipeline_instance SET status = 'FAILED', image_digest = ?,"
            + " commit_sha = ? WHERE id = ?", OLD_DIGEST, BASE, instanceId);
        jdbc.update("UPDATE release_item SET status = 'FAILED', base_commit = ?,"
            + " last_test_pass_by = ?, last_test_pass_at = NOW(3) WHERE id = ?",
            BASE, TESTER, itemId);
        assertThat(unitLockService.release(unitId, instanceId)).isTrue();
    }

    /**
     * Turns the initial instance into a failed production deployment: earlier nodes succeeded,
     * {@code PROD_DEPLOY} failed, batch 1 has the first given status and batch 2 the second.
     */
    private void failProductionBatches(long instanceId, String batch1Status,
                                       String batch2Status) {
        jdbc.update("UPDATE pipeline_node SET status = 'SUCCESS' WHERE instance_id = ?"
            + " AND node_type IN ('BUILD', 'TEST_DEPLOY', 'VERIFY')", instanceId);
        jdbc.update("UPDATE pipeline_node SET status = 'FAILED' WHERE instance_id = ?"
            + " AND node_type = 'PROD_DEPLOY'", instanceId);
        jdbc.update("UPDATE pipeline_instance SET status = 'FAILED', image_digest = ?,"
            + " commit_sha = ? WHERE id = ?", OLD_DIGEST, BASE, instanceId);
        jdbc.update("UPDATE release_item SET status = 'FAILED', base_commit = ?,"
            + " last_test_pass_by = ?, last_test_pass_at = NOW(3) WHERE id = ?",
            BASE, TESTER, itemId);
        jdbc.update("UPDATE node_task SET status = ? WHERE instance_id = ?"
            + " AND node_type = 'PROD_DEPLOY' AND batch_no = 1", batch1Status, instanceId);
        jdbc.update("UPDATE node_task SET status = ? WHERE instance_id = ?"
            + " AND node_type = 'PROD_DEPLOY' AND batch_no = 2", batch2Status, instanceId);
        assertThat(unitLockService.release(unitId, instanceId)).isTrue();
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

    private String itemStatus() {
        return jdbc.queryForObject("SELECT status FROM release_item WHERE id = ?",
            String.class, itemId);
    }

    private String baseCommit() {
        return jdbc.queryForObject("SELECT base_commit FROM release_item WHERE id = ?",
            String.class, itemId);
    }

    /** One {@code batch:status} line per production deploy task, ordered by batch. */
    private List<String> productionBatches(long instanceId) {
        return jdbc.queryForList(
            "SELECT CONCAT(batch_no, ':', status) FROM node_task WHERE instance_id = ?"
                + " AND node_type = 'PROD_DEPLOY' ORDER BY batch_no, target_id",
            String.class, instanceId);
    }

    private String lastTestPassBy() {
        return jdbc.queryForObject("SELECT last_test_pass_by FROM release_item WHERE id = ?",
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

    private void givenDe(long unit, String envKind, String name, int batchNo) {
        jdbc.update("INSERT INTO env_de (unit_id, env_kind, de_name, deploy_batch_no,"
                + " created_at, updated_at) VALUES (?, ?, ?, ?, NOW(3), NOW(3))",
            unit, envKind, name, batchNo);
    }
}
