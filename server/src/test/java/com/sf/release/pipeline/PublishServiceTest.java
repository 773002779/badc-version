package com.sf.release.pipeline;

import com.sf.release.application.ItemAppService;
import com.sf.release.application.ItemAppService.CreateItemCmd;
import com.sf.release.application.PublishAppService;
import com.sf.release.application.PublishAppService.PublishResult;
import com.sf.release.common.BizError;
import com.sf.release.common.BizException;
import com.sf.release.domain.engine.EngineException;
import com.sf.release.domain.pipeline.port.GitCommitProbe;
import com.sf.release.domain.pipeline.port.PipelineRepository;
import com.sf.release.infra.lock.UnitLockService;
import com.sf.release.infra.repo.DeployUnitLockMapper;
import com.sf.release.infra.security.TestUserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Publishing an online 变更项 against the real test schema (Flyway migrate, MySQL 8):
 * release order, pre-generated pipeline instance/nodes/tasks and the deployment-unit lock.
 *
 * <p>The publish use case is {@code ScopeGuard → assertPublishable → acquire unit lock →
 * one transaction creating order + instance + nodes + tasks}; the item moves
 * {@code PENDING -PUBLISH-> BUILDING} through the Task 8 state machine.
 *
 * <p>Lock tests use the default in-memory {@code DistributedLock} (no Redis is available on
 * this host); the {@code deploy_unit_lock} row is the DB backstop. The dedicated context property
 * keeps this class from reusing the rerun tests' cached context: their fixtures recreate the same
 * unit ids under a cleaned schema, and the in-memory lock map is not part of the DB reset.
 */
@SpringBootTest(properties = "release.publish-probe=fresh-context")
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension.class)
class PublishServiceTest {

    private static final String OWNER = "01388007";
    private static final String BRANCH = "release/202611";
    private static final String BASE_COMMIT = "c".repeat(40);
    private static final LocalDate PLAN_DATE = LocalDate.of(2026, 11, 25);
    private static final int PARAM_INVALID_CODE = 40000;
    private static final int STATE_NOT_ALLOWED_CODE = 40001;
    private static final int UNIT_LOCKED_CODE = 40006;
    private static final int UNIT_NOT_CONFIGURED_CODE = 40011;

    @Autowired ItemAppService itemService;
    @Autowired PublishAppService publishService;
    @Autowired UnitLockService unitLockService;
    @Autowired DeployUnitLockMapper lockMapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired PipelineRepository repository;
    @MockBean GitCommitProbe commitProbe;

    private long subsystemId;
    private long taskId;
    private long unitId;
    private long itemId;
    private long testDe1;
    private long testDe2;
    private long prodDe;
    private long drDe;
    private long blueDe;

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
        subsystemId = givenSubsystem("SCC-QUOTA");
        taskId = givenTask(subsystemId, "202611-V6.10.12");
        unitId = givenUnit(subsystemId, "quota-api");
        givenBinding(unitId, "BUILD", "P-BUILD-QUOTA");
        givenBinding(unitId, "TEST_DEPLOY", "P-TEST-QUOTA");
        givenBinding(unitId, "PROD_DEPLOY", "P-PROD-QUOTA");
        testDe1 = givenDe(unitId, "TEST", "quota-sit", 0);
        testDe2 = givenDe(unitId, "TEST", "quota-uat", 0);
        prodDe = givenDe(unitId, "PROD", "quota-prod-a", 1);
        blueDe = givenDe(unitId, "BLUE", "quota-blue", 1);
        drDe = givenDe(unitId, "DR", "quota-dr", 2);
        itemId = itemService.create(taskId,
            new CreateItemCmd(unitId, "release/202611", "配额优化", PLAN_DATE));
    }

    @AfterEach
    void clearUserContext() {
        TestUserContext.clear();
    }

    @Test
    void publish_creates_one_order_one_instance_and_moves_the_item_to_building() {
        PublishResult result = publishService.publish(itemId);

        assertThat(result.publishNo()).startsWith("PO-").hasSizeLessThanOrEqualTo(32);
        assertThat(result.pipelineNo()).startsWith("PL-").hasSizeLessThanOrEqualTo(32);
        assertThat(count("release_order")).isEqualTo(1);
        assertThat(count("pipeline_instance")).isEqualTo(1);

        Map<String, Object> order = jdbc.queryForMap(
            "SELECT * FROM release_order WHERE publish_no = ?", result.publishNo());
        assertThat(((Number) order.get("item_id")).longValue()).isEqualTo(itemId);
        assertThat(order.get("status")).isEqualTo("INIT");
        assertThat(((Number) order.get("row_version")).intValue()).isZero();
        assertThat(order.get("created_at")).isNotNull();
        assertThat(order.get("planned_release_time")).isNull();

        long instanceId = instanceId(result.pipelineNo());
        Map<String, Object> instance = jdbc.queryForMap(
            "SELECT * FROM pipeline_instance WHERE id = ?", instanceId);
        assertThat(((Number) instance.get("order_id")).longValue())
            .isEqualTo(((Number) order.get("id")).longValue());
        assertThat(instance.get("exec_type")).isEqualTo("ONLINE");
        assertThat(instance.get("trigger_type")).isEqualTo("INITIAL");
        assertThat(instance.get("current_node")).isEqualTo("BUILD");
        assertThat(instance.get("status")).isEqualTo("PENDING");
        assertThat(instance.get("image_digest")).isNull();
        assertThat(instance.get("commit_sha")).isNull();
        assertThat(instance.get("start_at")).isNull();
        assertThat(instance.get("end_at")).isNull();
        assertThat(instance.get("created_at")).isNotNull();

        assertThat(itemStatus()).isEqualTo("BUILDING");
        assertThat(jdbc.queryForObject("SELECT row_version FROM release_item WHERE id = ?",
            Integer.class, itemId)).isEqualTo(1);

        Map<String, Object> lock = jdbc.queryForMap(
            "SELECT * FROM deploy_unit_lock WHERE unit_id = ?", unitId);
        assertThat(((Number) lock.get("instance_id")).longValue()).isEqualTo(instanceId);
        assertThat(lock.get("acquired_at")).isNotNull();
        Instant expireAt = lockExpireAt();
        assertThat(Duration.between(Instant.now(), expireAt))
            .as("the unit lease is four hours from the publish")
            .isBetween(Duration.ofHours(3).plusMinutes(55), Duration.ofHours(4).plusMinutes(5));
    }

    @Test
    void publish_records_the_probed_branch_head_as_the_rerun_baseline() {
        when(commitProbe.headSha(unitId, BRANCH)).thenReturn("  " + BASE_COMMIT + "  ");

        publishService.publish(itemId);

        assertThat(baseCommit())
            .as("the baseline is the trimmed observed HEAD, not an instance commit")
            .isEqualTo(BASE_COMMIT);
        verify(commitProbe).headSha(unitId, BRANCH);
    }

    @Test
    void probe_failure_keeps_base_commit_null_and_does_not_block_publish(
            CapturedOutput output) {
        when(commitProbe.headSha(unitId, BRANCH))
            .thenThrow(EngineException.unavailable("git probe unavailable"));

        PublishResult result = publishService.publish(itemId);

        assertThat(result.pipelineNo()).isNotBlank();
        assertThat(itemStatus()).isEqualTo("BUILDING");
        assertThat(baseCommit()).isNull();
        assertThat(output.getAll())
            .as("the degradation must be visible in the log, not silent")
            .contains("base_commit stays NULL");
    }

    @Test
    void blank_head_keeps_base_commit_null_and_does_not_block_publish(CapturedOutput output) {
        when(commitProbe.headSha(unitId, BRANCH)).thenReturn("   ");

        publishService.publish(itemId);

        assertThat(itemStatus()).isEqualTo("BUILDING");
        assertThat(baseCommit()).isNull();
        assertThat(output.getAll()).contains("base_commit stays NULL");
    }

    @Test
    void sha256_sized_head_is_stored_whole() {
        String sha256 = "d".repeat(64);
        when(commitProbe.headSha(unitId, BRANCH)).thenReturn(sha256);

        publishService.publish(itemId);

        assertThat(baseCommit())
            .as("a 64-char head fits the VARCHAR(64) baseline without truncation")
            .isEqualTo(sha256);
    }

    @Test
    void over_long_head_degrades_to_null_instead_of_being_truncated(CapturedOutput output) {
        when(commitProbe.headSha(unitId, BRANCH)).thenReturn("e".repeat(65));

        publishService.publish(itemId);

        assertThat(itemStatus()).isEqualTo("BUILDING");
        assertThat(baseCommit()).as("a commit id is never truncated").isNull();
        assertThat(output.getAll())
            .contains("longer than 64 chars")
            .contains("base_commit stays NULL");
    }

    @Test
    void instance_has_four_nodes_in_order_and_expected_tasks() {
        PublishResult result = publishService.publish(itemId);
        long instanceId = instanceId(result.pipelineNo());

        List<Map<String, Object>> nodes = jdbc.queryForList(
            "SELECT node_type, seq_no, status FROM pipeline_node"
                + " WHERE instance_id = ? ORDER BY seq_no", instanceId);
        assertThat(nodes).extracting(row -> row.get("node_type"))
            .containsExactly("BUILD", "TEST_DEPLOY", "VERIFY", "PROD_DEPLOY");
        assertThat(nodes).extracting(row -> ((Number) row.get("seq_no")).intValue())
            .containsExactly(0, 1, 2, 3);
        assertThat(nodes).allSatisfy(row -> assertThat(row.get("status")).isEqualTo("PENDING"));

        // BUILD: the three fixed tasks, inserted in execution order.
        assertThat(tasks(jdbc.queryForList(
            "SELECT task_type, status, attempt, external_id, started_at FROM node_task"
                + " WHERE instance_id = ? AND node_type = 'BUILD' ORDER BY id", instanceId)))
            .extracting(row -> row.get("task_type"))
            .containsExactly("CODE_CHECK", "PKG_BUILD", "IMG_BUILD");
        assertThat(tasks(jdbc.queryForList(
            "SELECT status, attempt, external_id, started_at FROM node_task"
                + " WHERE instance_id = ? AND node_type = 'BUILD'", instanceId)))
            .allSatisfy(row -> {
                assertThat(row.get("status")).isEqualTo("PENDING");
                assertThat(((Number) row.get("attempt")).intValue()).isZero();
                assertThat(row.get("external_id")).isNull();
                assertThat(row.get("started_at")).isNull();
            });

        // TEST_DEPLOY: one DEPLOY task per TEST DE, no production batch number.
        List<Map<String, Object>> testTasks = tasks(jdbc.queryForList(
            "SELECT task_type, target_id, batch_no FROM node_task"
                + " WHERE instance_id = ? AND node_type = 'TEST_DEPLOY' ORDER BY target_id",
            instanceId));
        assertThat(testTasks).hasSize(2);
        assertThat(testTasks).allSatisfy(row -> {
            assertThat(row.get("task_type")).isEqualTo("DEPLOY");
            assertThat(((Number) row.get("batch_no")).intValue()).isZero();
        });
        assertThat(testTasks).extracting(row -> ((Number) row.get("target_id")).longValue())
            .containsExactly(testDe1, testDe2);

        // VERIFY: one CHECK task per plan check type (there is no catalogue table yet).
        List<Map<String, Object>> verifyTasks = tasks(jdbc.queryForList(
            "SELECT task_type, target_id FROM node_task"
                + " WHERE instance_id = ? AND node_type = 'VERIFY' ORDER BY target_id",
            instanceId));
        assertThat(verifyTasks).hasSize(7);
        assertThat(verifyTasks).allSatisfy(row ->
            assertThat(row.get("task_type")).isEqualTo("CHECK"));
        assertThat(verifyTasks).extracting(row -> ((Number) row.get("target_id")).longValue())
            .containsExactly(1L, 2L, 3L, 4L, 5L, 6L, 7L);

        // PROD_DEPLOY: one DEPLOY per PROD/DR/BLUE DE carrying the deploy batch number,
        // serialized by batch (C14).
        List<Map<String, Object>> prodTasks = tasks(jdbc.queryForList(
            "SELECT task_type, target_id, batch_no FROM node_task"
                + " WHERE instance_id = ? AND node_type = 'PROD_DEPLOY'"
                + " ORDER BY batch_no, target_id", instanceId));
        assertThat(prodTasks).hasSize(3);
        assertThat(prodTasks).allSatisfy(row ->
            assertThat(row.get("task_type")).isEqualTo("DEPLOY"));
        assertThat(prodTasks)
            .extracting(row -> ((Number) row.get("target_id")).longValue() + ":"
                + ((Number) row.get("batch_no")).intValue())
            .containsExactly(prodDe + ":1", blueDe + ":1", drDe + ":2");

        assertThat(count("node_task")).isEqualTo(15);
    }

    @Test
    void concurrent_double_publish_creates_exactly_one_order_and_instance() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<PublishResult>> futures = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            futures.add(pool.submit(() -> {
                TestUserContext.allUnits(OWNER);
                try {
                    go.await();
                    return publishService.publish(itemId);
                } finally {
                    TestUserContext.clear();
                }
            }));
        }
        go.countDown();

        List<PublishResult> succeeded = new ArrayList<>();
        List<Throwable> failed = new ArrayList<>();
        for (Future<PublishResult> future : futures) {
            try {
                succeeded.add(future.get(20, TimeUnit.SECONDS));
            } catch (ExecutionException ex) {
                failed.add(ex.getCause());
            }
        }
        pool.shutdownNow();

        assertThat(succeeded).hasSize(1);
        assertThat(failed).hasSize(1);
        assertThat(failed.get(0)).isInstanceOf(BizException.class);
        BizException loser = (BizException) failed.get(0);
        assertThat(loser.getError())
            .as("the loser always loses on the unit lock, never on a post-commit status read")
            .isEqualTo(BizError.UNIT_LOCKED);
        assertThat(loser.getError().getCode()).isEqualTo(UNIT_LOCKED_CODE);
        assertThat(loser.getPayload()).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> loserPayload = (Map<String, Object>) loser.getPayload();
        assertThat(loserPayload).containsEntry("unitId", unitId);

        assertThat(count("release_order")).isEqualTo(1);
        assertThat(count("pipeline_instance")).isEqualTo(1);
        assertThat(count("pipeline_node")).isEqualTo(4);
        assertThat(count("node_task")).isEqualTo(15);
        assertThat(count("deploy_unit_lock")).isEqualTo(1);
        assertThat(itemStatus()).isEqualTo("BUILDING");
        assertThat(jdbc.queryForObject("SELECT row_version FROM release_item WHERE id = ?",
            Integer.class, itemId)).isEqualTo(1);
    }

    @Test
    void publish_requires_a_test_de_and_a_production_family_de() {
        long noTestDe = givenUnit(subsystemId, "quota-api-prod-only");
        givenBinding(noTestDe, "BUILD", "P-BUILD-ONLY");
        givenBinding(noTestDe, "TEST_DEPLOY", "P-TEST-ONLY");
        givenBinding(noTestDe, "PROD_DEPLOY", "P-PROD-ONLY");
        givenDe(noTestDe, "PROD", "prod-only-prod", 1);
        long prodOnlyItem = itemService.create(taskId,
            new CreateItemCmd(noTestDe, "release/202611", "缺测试环境", PLAN_DATE));

        assertThatThrownBy(() -> publishService.publish(prodOnlyItem))
            .as("a unit without a TEST DE cannot test-deploy")
            .isInstanceOf(BizException.class)
            .isInstanceOfSatisfying(BizException.class, ex -> {
                assertThat(ex.getError()).isEqualTo(BizError.UNIT_NOT_CONFIGURED);
                assertThat(ex.getError().getCode()).isEqualTo(UNIT_NOT_CONFIGURED_CODE);
                @SuppressWarnings("unchecked")
                Map<String, Object> payload = (Map<String, Object>) ex.getPayload();
                assertThat(payload)
                    .containsEntry("unitId", noTestDe)
                    .containsEntry("missing", List.of("TEST_DE"));
            });

        long noProdDe = givenUnit(subsystemId, "quota-api-test-only");
        givenBinding(noProdDe, "BUILD", "P-BUILD-TESTONLY");
        givenBinding(noProdDe, "TEST_DEPLOY", "P-TEST-TESTONLY");
        givenBinding(noProdDe, "PROD_DEPLOY", "P-PROD-TESTONLY");
        givenDe(noProdDe, "TEST", "test-only-sit", 0);
        long testOnlyItem = itemService.create(taskId,
            new CreateItemCmd(noProdDe, "release/202611", "缺生产环境", PLAN_DATE));

        assertThatThrownBy(() -> publishService.publish(testOnlyItem))
            .as("a unit without a PROD/DR/BLUE DE cannot production-deploy")
            .isInstanceOf(BizException.class)
            .isInstanceOfSatisfying(BizException.class, ex -> {
                assertThat(ex.getError()).isEqualTo(BizError.UNIT_NOT_CONFIGURED);
                @SuppressWarnings("unchecked")
                Map<String, Object> payload = (Map<String, Object>) ex.getPayload();
                assertThat(payload).containsEntry("missing", List.of("PROD_DE"));
            });

        assertThat(count("release_order"))
            .as("a misconfigured unit creates no order and takes no lock")
            .isZero();
        assertThat(count("pipeline_instance")).isZero();
        assertThat(count("deploy_unit_lock")).isZero();
        assertThat(itemStatus(prodOnlyItem)).isEqualTo("PENDING");
        assertThat(itemStatus(testOnlyItem)).isEqualTo("PENDING");

        // Adding the missing production-family DE makes the unit publishable.
        givenDe(noProdDe, "DR", "test-only-dr", 1);
        assertThat(publishService.publish(testOnlyItem).pipelineNo()).isNotBlank();
        assertThat(count("release_order")).isEqualTo(1);
    }

    @Test
    void base_commit_probe_runs_outside_the_publish_transaction_and_before_the_unit_lock() {
        // The unit is already held by another instance: the publish must fail on the lock, but
        // the probe has already run. Before the hoist the probe lived inside the transaction,
        // which the lock check prevents from ever starting.
        assertThat(unitLockService.acquire(unitId, 999L)).isTrue();

        assertThatThrownBy(() -> publishService.publish(itemId))
            .isInstanceOf(BizException.class)
            .extracting("error").isEqualTo(BizError.UNIT_LOCKED);

        verify(commitProbe).headSha(unitId, BRANCH);
        assertThat(count("release_order")).isZero();
    }

    @Test
    void bind_instance_is_scoped_to_the_unbound_sentinel() {
        assertThat(unitLockService.acquire(unitId, UnitLockService.UNBOUND_INSTANCE_ID)).isTrue();

        assertThat(unitLockService.bindInstance(unitId, 101L)).isTrue();
        assertThat(unitLockService.bindInstance(unitId, 202L))
            .as("a stale publisher must not overwrite an already bound lock row")
            .isFalse();
        assertThat(lockInstanceId()).isEqualTo(101L);
    }

    @Test
    void a_64_char_commit_sha_is_stored_in_full_and_a_longer_one_is_a_business_error() {
        PublishResult published = publishService.publish(itemId);
        long instanceId = instanceId(published.pipelineNo());
        String sha256 = "f".repeat(64);

        assertThat(repository.recordCommit(instanceId, sha256)).isTrue();
        assertThat(instanceCommitSha(instanceId))
            .as("a SHA-256 commit is not cut at 40 characters (V14)")
            .isEqualTo(sha256);

        assertThatThrownBy(() -> repository.recordCommit(instanceId, sha256 + "0"))
            .as("a value wider than the column fails loudly instead of being truncated")
            .isInstanceOf(BizException.class)
            .extracting("error").isEqualTo(BizError.PARAM_INVALID);
        assertThat(instanceCommitSha(instanceId))
            .as("the rejected write left the stored commit untouched")
            .isEqualTo(sha256);
    }

    @Test
    void incomplete_bindings_is_40011_and_creates_no_order_or_instance() {
        long bareUnit = givenUnit(subsystemId, "quota-api-bare");
        givenBinding(bareUnit, "BUILD", "P-BUILD-BARE");
        long bareItem = itemService.create(taskId,
            new CreateItemCmd(bareUnit, "release/202611", "缺绑定", PLAN_DATE));

        assertThatThrownBy(() -> publishService.publish(bareItem))
            .as("TEST_DEPLOY/PROD_DEPLOY bindings are missing")
            .isInstanceOf(BizException.class)
            .extracting("error").isEqualTo(BizError.UNIT_NOT_CONFIGURED)
            .satisfies(error -> assertThat(((BizError) error).getCode())
                .isEqualTo(UNIT_NOT_CONFIGURED_CODE));

        assertThat(count("release_order")).isZero();
        assertThat(count("pipeline_instance")).isZero();
        assertThat(count("pipeline_node")).isZero();
        assertThat(count("node_task")).isZero();
        assertThat(count("deploy_unit_lock")).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM release_item WHERE id = ?",
            String.class, bareItem)).isEqualTo("PENDING");
    }

    @Test
    void held_lock_rejects_second_publish_with_40006_and_force_expire_reacquires() {
        assertThat(unitLockService.acquire(unitId, 999L)).isTrue();
        assertThat(unitLockService.acquire(unitId, 1000L))
            .as("the same unit cannot be acquired twice")
            .isFalse();

        Throwable thrown = catchThrowable(() -> publishService.publish(itemId));
        assertThat(thrown).isInstanceOf(BizException.class);
        BizException locked = (BizException) thrown;
        assertThat(locked.getError()).isEqualTo(BizError.UNIT_LOCKED);
        assertThat(locked.getError().getCode()).isEqualTo(UNIT_LOCKED_CODE);
        assertThat(locked.getPayload()).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) locked.getPayload();
        assertThat(payload).containsEntry("unitId", unitId).containsEntry("instanceId", 999L);
        assertThat(payload.get("acquiredAt")).isNotNull();
        assertThat(count("release_order")).isZero();
        assertThat(count("pipeline_instance")).isZero();
        assertThat(itemStatus()).isEqualTo("PENDING");

        // forceExpire is the test seam for a lock row whose expire_at has passed; the owner
        // instance is unknown (no pipeline_instance row 999), so it is reclaimable.
        assertThat(lockMapper.forceExpire(unitId)).isEqualTo(1);
        assertThat(unitLockService.acquire(unitId, 1000L))
            .as("an expired row with an unknown owner can be reclaimed")
            .isTrue();
        assertThat(lockInstanceId()).isEqualTo(1000L);

        assertThat(unitLockService.release(unitId, 1000L)).isTrue();
        assertThat(publishService.publish(itemId).pipelineNo()).isNotBlank();
        assertThat(count("release_order")).isEqualTo(1);
        assertThat(count("pipeline_instance")).isEqualTo(1);
    }

    @Test
    void release_removes_only_the_owners_db_row_and_free_lock() {
        assertThat(unitLockService.acquire(unitId, 77L)).isTrue();
        assertThat(count("deploy_unit_lock")).isEqualTo(1);

        assertThat(unitLockService.release(unitId, 78L))
            .as("a non-owner must not delete the holder's row")
            .isFalse();
        assertThat(count("deploy_unit_lock")).isEqualTo(1);
        assertThat(unitLockService.acquire(unitId, 99L)).isFalse();

        assertThat(unitLockService.release(unitId, 77L)).isTrue();
        assertThat(count("deploy_unit_lock")).isZero();
        assertThat(unitLockService.acquire(unitId, 78L))
            .as("the distributed lock must be freed together with the DB row")
            .isTrue();
        assertThat(lockInstanceId()).isEqualTo(78L);
        assertThat(unitLockService.release(unitId, 78L)).isTrue();
    }

    @Test
    void renew_extends_the_lease_only_for_the_owner() {
        long liveInstance = givenInstance("PL-T9-RENEW", "RUNNING");
        assertThat(unitLockService.acquire(unitId, liveInstance)).isTrue();
        Instant before = lockExpireAt();

        assertThat(unitLockService.renew(unitId, liveInstance)).isTrue();
        Instant after = lockExpireAt();
        assertThat(after).isAfter(before);
        assertThat(Duration.between(Instant.now(), after))
            .isBetween(Duration.ofHours(3).plusMinutes(55), Duration.ofHours(4).plusMinutes(5));

        assertThat(unitLockService.renew(unitId, liveInstance + 1))
            .as("a different instance cannot renew the lease")
            .isFalse();
        assertThat(lockInstanceId()).isEqualTo(liveInstance);
    }

    @Test
    void expired_lock_of_a_live_instance_is_renewed_not_reclaimed() {
        long liveInstance = givenInstance("PL-T9-LIVE", "RUNNING");
        assertThat(unitLockService.acquire(unitId, liveInstance)).isTrue();
        assertThat(lockMapper.forceExpire(unitId)).isEqualTo(1);
        Instant expired = lockExpireAt();

        assertThat(unitLockService.acquire(unitId, 1000L))
            .as("a running pipeline keeps the unit: acquire renews instead of stealing")
            .isFalse();
        Instant renewed = lockExpireAt();
        assertThat(renewed).isAfter(expired);
        assertThat(Duration.between(Instant.now(), renewed))
            .isBetween(Duration.ofHours(3).plusMinutes(55), Duration.ofHours(4).plusMinutes(5));
        assertThat(lockInstanceId()).isEqualTo(liveInstance);
        assertThat(count("release_order")).isZero();
    }

    @Test
    void expired_lock_of_a_terminal_instance_is_reclaimed() {
        long doneInstance = givenInstance("PL-T9-DONE", "DONE");
        assertThat(unitLockService.acquire(unitId, doneInstance)).isTrue();
        assertThat(lockMapper.forceExpire(unitId)).isEqualTo(1);

        assertThat(unitLockService.acquire(unitId, 1000L))
            .as("a terminal owner cannot keep the unit")
            .isTrue();
        assertThat(lockInstanceId()).isEqualTo(1000L);
    }

    @Test
    void a_second_publish_is_rejected_while_the_unit_is_held_or_the_item_is_building() {
        publishService.publish(itemId);

        assertThatThrownBy(() -> publishService.publish(itemId))
            .as("the first publish still holds the unit lock")
            .isInstanceOf(BizException.class)
            .extracting("error").isEqualTo(BizError.UNIT_LOCKED)
            .satisfies(error -> assertThat(((BizError) error).getCode())
                .isEqualTo(UNIT_LOCKED_CODE));

        long holder = lockInstanceId();
        assertThat(holder).isPositive();
        assertThat(unitLockService.release(unitId, holder)).isTrue();

        // With the lock gone, the state machine still refuses a second PUBLISH from BUILDING.
        assertThatThrownBy(() -> publishService.publish(itemId))
            .isInstanceOf(BizException.class)
            .extracting("error").isEqualTo(BizError.STATE_NOT_ALLOWED)
            .satisfies(error -> assertThat(((BizError) error).getCode())
                .isEqualTo(STATE_NOT_ALLOWED_CODE));
        assertThat(count("deploy_unit_lock"))
            .as("the failed publish releases its own reservation")
            .isZero();

        assertThat(count("release_order")).isEqualTo(1);
        assertThat(count("pipeline_instance")).isEqualTo(1);
        assertThat(itemStatus()).isEqualTo("BUILDING");
    }

    @Test
    void publish_rejects_an_unknown_item_and_a_library_item() {
        assertThatThrownBy(() -> publishService.publish(999_999L))
            .isInstanceOf(BizException.class)
            .extracting("error").isEqualTo(BizError.PARAM_INVALID)
            .satisfies(error -> assertThat(((BizError) error).getCode())
                .isEqualTo(PARAM_INVALID_CODE));

        long libUnit = givenUnit(subsystemId, "quota-sdk", "LIB");
        givenBinding(libUnit, "BUILD", "P-BUILD-SDK");
        long libItem = itemService.create(taskId,
            new CreateItemCmd(libUnit, "release/202611", "SDK 升级", PLAN_DATE));

        assertThatThrownBy(() -> publishService.publish(libItem))
            .as("the LIB pipeline is out of phase-1 scope")
            .isInstanceOf(BizException.class)
            .extracting("error").isEqualTo(BizError.STATE_NOT_ALLOWED)
            .satisfies(error -> assertThat(((BizError) error).getCode())
                .isEqualTo(STATE_NOT_ALLOWED_CODE));
        assertThat(count("release_order")).isZero();
        assertThat(count("pipeline_instance")).isZero();
    }

    // ------------------------------------------------------------------ fixtures and helpers

    private int count(String table) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
        return count == null ? 0 : count;
    }

    private String itemStatus() {
        return itemStatus(itemId);
    }

    private String itemStatus(long id) {
        return jdbc.queryForObject("SELECT status FROM release_item WHERE id = ?",
            String.class, id);
    }

    private String instanceCommitSha(long id) {
        return jdbc.queryForObject("SELECT commit_sha FROM pipeline_instance WHERE id = ?",
            String.class, id);
    }

    private String baseCommit() {
        return jdbc.queryForObject("SELECT base_commit FROM release_item WHERE id = ?",
            String.class, itemId);
    }

    private long instanceId(String pipelineNo) {
        Long id = jdbc.queryForObject(
            "SELECT id FROM pipeline_instance WHERE pipeline_no = ?", Long.class, pipelineNo);
        return id == null ? 0L : id;
    }

    /** JDBC 1.4 returns {@code List<Map<String,Object>>} typed as {@code List<Map>}. */
    private static List<Map<String, Object>> tasks(List<?> rows) {
        List<Map<String, Object>> typed = new ArrayList<>();
        for (Object row : rows) {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) row;
            typed.add(map);
        }
        return typed;
    }

    private long lockInstanceId() {
        Long id = jdbc.queryForObject(
            "SELECT instance_id FROM deploy_unit_lock WHERE unit_id = ?", Long.class, unitId);
        return id == null ? -1L : id;
    }

    private Instant lockExpireAt() {
        Timestamp expireAt = jdbc.queryForObject(
            "SELECT expire_at FROM deploy_unit_lock WHERE unit_id = ?", Timestamp.class, unitId);
        return expireAt == null ? null : expireAt.toInstant();
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
        return givenUnit(subsystem, name, "UNIT");
    }

    private long givenUnit(long subsystem, String name, String type) {
        jdbc.update("INSERT INTO deploy_unit (subsystem_id, name, type, owner_id, framework_template,"
                + " branch_mode, status, module_path, created_at, updated_at)"
                + " VALUES (?, ?, ?, ?, 'spring-boot', 'STANDARD', 'ACTIVE', 'outbound-os/quota',"
                + " NOW(3), NOW(3))",
            subsystem, name, type, OWNER);
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

    /** Inserts a bare pipeline instance row so a lock row can name a live/terminal owner. */
    private long givenInstance(String pipelineNo, String status) {
        jdbc.update("INSERT INTO pipeline_instance (order_id, pipeline_no, exec_type, trigger_type,"
                + " current_node, status, created_at)"
                + " VALUES (0, ?, 'ONLINE', 'INITIAL', 'BUILD', ?, NOW(3))",
            pipelineNo, status);
        Long id = jdbc.queryForObject(
            "SELECT id FROM pipeline_instance WHERE pipeline_no = ?", Long.class, pipelineNo);
        return id == null ? 0L : id;
    }
}