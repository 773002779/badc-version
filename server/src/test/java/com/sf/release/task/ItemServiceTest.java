package com.sf.release.task;

import com.sf.release.application.ItemAppService;
import com.sf.release.application.ItemAppService.CreateItemCmd;
import com.sf.release.application.ItemAppService.ItemView;
import com.sf.release.application.TaskAppService;
import com.sf.release.application.TaskAppService.ProgressView;
import com.sf.release.common.BizError;
import com.sf.release.common.BizException;
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
 * 变更项 creation and state transitions against the real test schema (Flyway migrate,
 * MyBatis-Plus, MySQL 8).
 *
 * <p>Creation snapshots the unit's CODING build task and module path, rejects unknown
 * task/unit, and enforces the {@code (task_id, unit_id, source_branch)} unique key.
 * Transitions go through the state machine and are persisted with a {@code row_version}
 * CAS; two concurrent updates must not overwrite each other.
 */
@SpringBootTest
@ActiveProfiles("test")
class ItemServiceTest {

    private static final String OWNER = "01388007";
    private static final LocalDate PLAN_DATE = LocalDate.of(2026, 11, 25);
    private static final int PARAM_INVALID_CODE = 40000;
    private static final int STATE_NOT_ALLOWED_CODE = 40001;
    private static final int DUPLICATE_NAME_CODE = 40010;
    private static final int UNIT_NOT_CONFIGURED_CODE = 40011;

    @Autowired ItemAppService itemService;
    @Autowired TaskAppService taskService;
    @Autowired JdbcTemplate jdbc;

    private long subsystemId;
    private long taskId;
    private long unitId;
    private long libId;

    @BeforeEach
    void resetFixtures() {
        jdbc.update("DELETE FROM release_item");
        jdbc.update("DELETE FROM release_task_member");
        jdbc.update("DELETE FROM release_task");
        jdbc.update("DELETE FROM coding_task_binding");
        jdbc.update("DELETE FROM deploy_unit");
        jdbc.update("DELETE FROM subsystem");
        jdbc.update("DELETE FROM app_user WHERE id = ?", OWNER);
        givenUser(OWNER, "ACTIVE");
        subsystemId = givenSubsystem("SCC-QUOTA");
        taskId = givenTask(subsystemId, "202611-V6.10.12");
        unitId = givenUnit(subsystemId, "quota-api", "UNIT", "outbound-os/quota");
        libId = givenUnit(subsystemId, "quota-sdk", "LIB", null);
        givenBuildBinding(unitId, "P-BUILD-QUOTA");
        givenBuildBinding(libId, "P-BUILD-SDK");
    }

    @AfterEach
    void cleanItems() {
        jdbc.update("DELETE FROM release_item");
    }

    @Test
    void create_snapshots_unit_and_build_task_and_starts_pending() {
        long id = itemService.create(taskId,
            new CreateItemCmd(unitId, "  release/202611  ", "  配额优化  ", PLAN_DATE));

        assertThat(id).isPositive();
        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM release_item WHERE id = ?", id);
        assertThat(((Number) row.get("task_id")).longValue()).isEqualTo(taskId);
        assertThat(String.valueOf(row.get("crid"))).startsWith("CR-").hasSizeLessThanOrEqualTo(32);
        assertThat(row.get("type")).isEqualTo("UNIT");
        assertThat(((Number) row.get("unit_id")).longValue()).isEqualTo(unitId);
        // Snapshot from the unit's BUILD binding, not from the request.
        assertThat(row.get("build_plan_id")).isEqualTo("P-BUILD-QUOTA");
        assertThat(row.get("source_branch")).isEqualTo("release/202611");
        assertThat(row.get("module_path")).isEqualTo("outbound-os/quota");
        assertThat(row.get("version")).isEqualTo("配额优化");
        assertThat(String.valueOf(row.get("plan_release_date"))).isEqualTo("2026-11-25");
        assertThat(row.get("status")).isEqualTo("PENDING");
        assertThat(row.get("gate_status")).isEqualTo("TEST_PENDING");
        assertThat(row.get("base_commit")).isNull();
        assertThat(((Number) row.get("row_version")).intValue()).isZero();
        assertThat(row.get("created_at")).isNotNull();
        assertThat(row.get("updated_at")).isNotNull();

        // A second-party library keeps its type and has no module path of its own.
        long libItem = itemService.create(taskId,
            new CreateItemCmd(libId, "release/202611", "SDK 升级", PLAN_DATE));
        assertThat(itemService.view(libItem).type()).isEqualTo("LIB");
        assertThat(itemService.view(libItem).buildPlanId()).isEqualTo("P-BUILD-SDK");
        assertThat(itemService.view(libItem).modulePath()).isNull();
    }

    @Test
    void create_rejects_unknown_task_unknown_unit_missing_unit_and_bad_input() {
        assertParamInvalid(() -> itemService.create(999_999L,
            new CreateItemCmd(unitId, "release/202611", "配额优化", PLAN_DATE)), "unknown task");
        assertParamInvalid(() -> itemService.create(taskId,
            new CreateItemCmd(null, "release/202611", "配额优化", PLAN_DATE)), "missing unit");
        assertParamInvalid(() -> itemService.create(taskId,
            new CreateItemCmd(999_999L, "release/202611", "配额优化", PLAN_DATE)), "unknown unit");
        assertParamInvalid(() -> itemService.create(taskId,
            new CreateItemCmd(unitId, "   ", "配额优化", PLAN_DATE)), "missing branch");
        assertParamInvalid(() -> itemService.create(taskId,
            new CreateItemCmd(unitId, "release/202611", "   ", PLAN_DATE)), "missing version");
        assertParamInvalid(() -> itemService.create(taskId,
            new CreateItemCmd(unitId, "release/202611", "配额优化", null)), "missing date");
        assertParamInvalid(() -> itemService.create(taskId, null), "missing command");

        assertThat(itemCount()).isZero();
    }

    @Test
    void duplicate_task_unit_branch_is_40010_but_another_branch_is_allowed() {
        itemService.create(taskId, new CreateItemCmd(unitId, "release/202611", "配额优化",
            PLAN_DATE));

        assertThatThrownBy(() -> itemService.create(taskId,
            new CreateItemCmd(unitId, " release/202611 ", "再提一次", PLAN_DATE)))
            .as("the unique key is (task_id, unit_id, source_branch); the branch is trimmed")
            .isInstanceOf(BizException.class)
            .extracting("error").isEqualTo(BizError.DUPLICATE_NAME)
            .satisfies(error -> assertThat(((BizError) error).getCode())
                .isEqualTo(DUPLICATE_NAME_CODE));

        assertThat(itemService.create(taskId,
            new CreateItemCmd(unitId, "release/202612", "另一分支", PLAN_DATE))).isPositive();
        assertThat(itemCount()).isEqualTo(2);
    }

    @Test
    void unit_without_a_build_binding_is_not_configured() {
        long bareUnit = givenUnit(subsystemId, "bare-api", "UNIT", null);

        assertThatThrownBy(() -> itemService.create(taskId,
            new CreateItemCmd(bareUnit, "release/202611", "配额优化", PLAN_DATE)))
            .as("a change item needs the CODING build task of its unit")
            .isInstanceOf(BizException.class)
            .extracting("error").isEqualTo(BizError.UNIT_NOT_CONFIGURED)
            .satisfies(error -> assertThat(((BizError) error).getCode())
                .isEqualTo(UNIT_NOT_CONFIGURED_CODE));
        assertThat(itemCount()).isZero();
    }

    @Test
    void transit_persists_the_next_status_with_row_version_and_rejects_stale_events() {
        long id = itemService.create(taskId,
            new CreateItemCmd(unitId, "release/202611", "配额优化", PLAN_DATE));

        ItemView afterPublish = itemService.transit(id, "publish");
        assertThat(afterPublish.status()).isEqualTo("BUILDING");
        assertThat(afterPublish.rowVersion()).isEqualTo(1);
        assertThat(jdbc.queryForMap("SELECT status, row_version FROM release_item WHERE id = ?",
            id)).containsEntry("status", "BUILDING").containsEntry("row_version", 1);

        // The event is applied to the reloaded item: PUBLISH is illegal from BUILDING now.
        assertThatThrownBy(() -> itemService.transit(id, "PUBLISH"))
            .isInstanceOf(BizException.class)
            .extracting("error").isEqualTo(BizError.STATE_NOT_ALLOWED)
            .satisfies(error -> assertThat(((BizError) error).getCode())
                .isEqualTo(STATE_NOT_ALLOWED_CODE));
        assertThat(jdbc.queryForObject("SELECT status FROM release_item WHERE id = ?",
            String.class, id)).isEqualTo("BUILDING");
        assertThat(jdbc.queryForObject("SELECT row_version FROM release_item WHERE id = ?",
            Integer.class, id)).isEqualTo(1);

        assertParamInvalid(() -> itemService.transit(id, "NOT_AN_EVENT"), "unknown event");
        assertParamInvalid(() -> itemService.transit(id, " "), "blank event");
        assertParamInvalid(() -> itemService.transit(999_999L, "PUBLISH"), "unknown item");
    }

    @Test
    void two_concurrent_transitions_leave_one_winner_and_a_consistent_row() throws Exception {
        long id = itemService.create(taskId,
            new CreateItemCmd(unitId, "release/202611", "配额优化", PLAN_DATE));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<ItemView>> futures = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                return itemService.transit(id, "PUBLISH");
            }));
        }
        start.countDown();

        List<ItemView> succeeded = new ArrayList<>();
        List<Throwable> failed = new ArrayList<>();
        for (Future<ItemView> future : futures) {
            try {
                succeeded.add(future.get(20, TimeUnit.SECONDS));
            } catch (ExecutionException ex) {
                failed.add(ex.getCause());
            }
        }
        pool.shutdownNow();

        // The loser retries on the row_version CAS, reloads BUILDING and is rejected by the
        // state machine instead of writing BUILDING twice or clobbering the winner's row.
        assertThat(succeeded).hasSize(1);
        assertThat(succeeded.get(0).status()).isEqualTo("BUILDING");
        assertThat(failed).hasSize(1);
        assertThat(failed.get(0)).isInstanceOf(BizException.class);
        assertThat(((BizException) failed.get(0)).getError())
            .isEqualTo(BizError.STATE_NOT_ALLOWED);

        assertThat(jdbc.queryForMap("SELECT status, row_version FROM release_item WHERE id = ?",
            id)).containsEntry("status", "BUILDING").containsEntry("row_version", 1);
    }

    @Test
    void task_progress_counts_real_item_statuses() {
        itemService.create(taskId, new CreateItemCmd(unitId, "release/202611", "一", PLAN_DATE));
        long second = itemService.create(taskId,
            new CreateItemCmd(unitId, "release/202612", "二", PLAN_DATE));

        ProgressView progress = taskService.progress(taskId);
        assertThat(progress.done()).isZero();
        assertThat(progress.total()).isEqualTo(2);
        assertThat(progress.hasBlocking()).isTrue();

        jdbc.update("UPDATE release_item SET status = 'DONE' WHERE id = ?", second);
        ProgressView halfDone = taskService.progress(taskId);
        assertThat(halfDone.done()).isEqualTo(1);
        assertThat(halfDone.total()).isEqualTo(2);
        assertThat(halfDone.rate()).isEqualTo(0.5d);
        assertThat(halfDone.hasBlocking())
            .as("the PENDING item is still blocking")
            .isTrue();
    }

    private void assertParamInvalid(Runnable call, String scenario) {
        assertThatThrownBy(call::run)
            .as(scenario)
            .isInstanceOf(BizException.class)
            .extracting("error").isEqualTo(BizError.PARAM_INVALID)
            .satisfies(error -> assertThat(((BizError) error).getCode())
                .isEqualTo(PARAM_INVALID_CODE));
    }

    private int itemCount() {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM release_item", Integer.class);
        return count == null ? 0 : count;
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

    private long givenUnit(long subsystem, String name, String type, String modulePath) {
        jdbc.update("INSERT INTO deploy_unit (subsystem_id, name, type, owner_id, framework_template,"
                + " branch_mode, status, module_path, created_at, updated_at)"
                + " VALUES (?, ?, ?, ?, 'spring-boot', 'STANDARD', 'ACTIVE', ?, NOW(3), NOW(3))",
            subsystem, name, type, OWNER, modulePath);
        Long id = jdbc.queryForObject(
            "SELECT id FROM deploy_unit WHERE subsystem_id = ? AND name = ?", Long.class,
            subsystem, name);
        return id == null ? 0L : id;
    }

    private void givenBuildBinding(long unit, String taskId) {
        jdbc.update("INSERT INTO coding_task_binding (unit_id, task_kind, task_id, enabled,"
            + " created_at, updated_at) VALUES (?, 'BUILD', ?, 1, NOW(3), NOW(3))", unit, taskId);
    }

    private void givenUser(String userId, String status) {
        jdbc.update("INSERT INTO app_user (id, name, status, created_at) VALUES (?, ?, ?, NOW(3))",
            userId, "用户" + userId, status);
    }
}