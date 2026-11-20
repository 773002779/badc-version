package com.sf.release.pipeline;

import com.sf.release.application.ItemAppService;
import com.sf.release.application.ItemAppService.CreateItemCmd;
import com.sf.release.application.PublishAppService;
import com.sf.release.application.PublishAppService.PublishResult;
import com.sf.release.common.BizError;
import com.sf.release.common.BizException;
import com.sf.release.infra.lock.UnitLockService;
import com.sf.release.infra.repo.DeployUnitLockMapper;
import com.sf.release.infra.security.TestUserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

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

/**
 * Publishing an online 变更项 against the real test schema (Flyway migrate, MySQL 8):
 * release order, pre-generated pipeline instance/nodes/tasks and the deployment-unit lock.
 *
 * <p>The publish use case is {@code ScopeGuard → assertPublishable → acquire unit lock →
 * one transaction creating order + instance + nodes + tasks}; the item moves
 * {@code PENDING -PUBLISH-> BUILDING} through the Task 8 state machine.
 *
 * <p>Lock tests use the default in-memory {@code DistributedLock} (no Redis is available on
 * this host); the {@code deploy_unit_lock} row is the DB backstop.
 */
@SpringBootTest
@ActiveProfiles("test")
class PublishServiceTest {

    private static final String OWNER = "01388007";
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
        assertThat(lock.get("expire_at")).isNotNull();
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
            .isIn(BizError.STATE_NOT_ALLOWED, BizError.UNIT_LOCKED);
        assertThat(loser.getError().getCode())
            .isIn(STATE_NOT_ALLOWED_CODE, UNIT_LOCKED_CODE);

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

        assertThatThrownBy(() -> publishService.publish(itemId))
            .isInstanceOf(BizException.class)
            .extracting("error").isEqualTo(BizError.UNIT_LOCKED)
            .satisfies(error -> assertThat(((BizError) error).getCode())
                .isEqualTo(UNIT_LOCKED_CODE));
        assertThat(count("release_order")).isZero();
        assertThat(count("pipeline_instance")).isZero();
        assertThat(itemStatus()).isEqualTo("PENDING");

        // forceExpire is the test seam for a lock row whose expire_at has passed.
        assertThat(lockMapper.forceExpire(unitId)).isEqualTo(1);
        assertThat(unitLockService.acquire(unitId, 1000L))
            .as("an expired row can be reclaimed")
            .isTrue();
        assertThat(jdbc.queryForObject(
            "SELECT instance_id FROM deploy_unit_lock WHERE unit_id = ?", Long.class, unitId))
            .isEqualTo(1000L);

        unitLockService.release(unitId);
        assertThat(publishService.publish(itemId).pipelineNo()).isNotBlank();
        assertThat(count("release_order")).isEqualTo(1);
        assertThat(count("pipeline_instance")).isEqualTo(1);
    }

    @Test
    void release_removes_the_db_row_and_frees_the_lock() {
        assertThat(unitLockService.acquire(unitId, 77L)).isTrue();
        assertThat(count("deploy_unit_lock")).isEqualTo(1);

        unitLockService.release(unitId);

        assertThat(count("deploy_unit_lock")).isZero();
        assertThat(unitLockService.acquire(unitId, 78L))
            .as("the distributed lock must be freed together with the DB row")
            .isTrue();
        assertThat(jdbc.queryForObject(
            "SELECT instance_id FROM deploy_unit_lock WHERE unit_id = ?", Long.class, unitId))
            .isEqualTo(78L);
        unitLockService.release(unitId);
    }

    @Test
    void a_second_publish_is_rejected_while_the_unit_is_held_or_the_item_is_building() {
        publishService.publish(itemId);

        assertThatThrownBy(() -> publishService.publish(itemId))
            .as("the item is BUILDING: the status check rejects the second click")
            .isInstanceOf(BizException.class)
            .extracting("error").isEqualTo(BizError.STATE_NOT_ALLOWED);

        // Even if the lock is gone (for example it expired), the item is BUILDING and the
        // state machine refuses a second PUBLISH.
        unitLockService.release(unitId);
        assertThatThrownBy(() -> publishService.publish(itemId))
            .isInstanceOf(BizException.class)
            .extracting("error").isEqualTo(BizError.STATE_NOT_ALLOWED)
            .satisfies(error -> assertThat(((BizError) error).getCode())
                .isEqualTo(STATE_NOT_ALLOWED_CODE));

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
        return jdbc.queryForObject("SELECT status FROM release_item WHERE id = ?",
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
}