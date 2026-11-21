package com.sf.release.task;

import com.sf.release.application.TaskAppService;
import com.sf.release.application.TaskAppService.CreateTaskCmd;
import com.sf.release.application.TaskAppService.MemberCmd;
import com.sf.release.application.TaskAppService.ProgressView;
import com.sf.release.common.BizError;
import com.sf.release.common.BizException;
import com.sf.release.common.security.Roles;
import com.sf.release.domain.task.ItemStatus;
import com.sf.release.domain.task.MemberRole;
import com.sf.release.domain.task.TaskProgress;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Release tasks and their members against the real test schema (Flyway migrate,
 * MyBatis-Plus, MySQL 8).
 *
 * <p>Task 7 has no release items yet: a task is created with its subsystem, creator and
 * plan finish date, members are upserted by {@code (task_id, user_id)}, and the progress
 * rules are exercised through {@link TaskProgress}, which Task 8 will feed with real item
 * statuses.
 */
@SpringBootTest
@ActiveProfiles("test")
class TaskServiceTest {

    private static final String CREATOR = "01388007";
    private static final String PRODUCT = "01380484";
    private static final String INACTIVE = "01399999";
    private static final int PARAM_INVALID_CODE = 40000;
    private static final LocalDate PLAN_FINISH = LocalDate.of(2026, 10, 12);

    @Autowired TaskAppService taskService;
    @Autowired JdbcTemplate jdbc;

    private long subsystemId;

    @BeforeEach
    void resetFixtures() {
        jdbc.update("DELETE FROM release_item");
        jdbc.update("DELETE FROM release_task_member");
        jdbc.update("DELETE FROM release_task");
        jdbc.update("DELETE FROM subsystem");
        jdbc.update("DELETE FROM app_user WHERE id IN (?, ?, ?)", CREATOR, PRODUCT, INACTIVE);
        givenUser(CREATOR, "ACTIVE");
        givenUser(PRODUCT, "ACTIVE");
        givenUser(INACTIVE, "INACTIVE");
        subsystemId = givenSubsystem("SCC-QUOTA");
    }

    @Test
    void create_trims_name_and_stores_creator_and_plan_finish_date() {
        long id = taskService.create(new CreateTaskCmd(
            "  202609-V6.10.12  ", subsystemId, CREATOR, PLAN_FINISH, "  新功能  "));

        assertThat(id).isPositive();
        Map<String, Object> row = jdbc.queryForMap(
            "SELECT name, subsystem_id, creator, purpose, plan_finish_date, status, created_at"
                + " FROM release_task WHERE id = ?", id);
        assertThat(row.get("name")).isEqualTo("202609-V6.10.12");
        assertThat(((Number) row.get("subsystem_id")).longValue()).isEqualTo(subsystemId);
        assertThat(row.get("creator")).isEqualTo(CREATOR);
        assertThat(row.get("purpose")).isEqualTo("新功能");
        // DATE column: the driver renders it as yyyy-MM-dd.
        assertThat(String.valueOf(row.get("plan_finish_date"))).isEqualTo("2026-10-12");
        assertThat(row.get("status")).isEqualTo("ACTIVE");
        assertThat(row.get("created_at")).isNotNull();
    }

    @Test
    void create_rejects_unknown_subsystem_and_invalid_input() {
        assertParamInvalid(() -> taskService.create(
            new CreateTaskCmd("202609-V6.10.12", 999_999L, CREATOR, PLAN_FINISH, null)));

        assertParamInvalid(() -> taskService.create(
            new CreateTaskCmd("   ", subsystemId, CREATOR, PLAN_FINISH, null)));

        assertParamInvalid(() -> taskService.create(
            new CreateTaskCmd("202609-V6.10.12", subsystemId, INACTIVE, PLAN_FINISH, null)));

        assertThat(taskCount()).isZero();

        taskService.create(new CreateTaskCmd("202609-V6.10.12", subsystemId, CREATOR,
            PLAN_FINISH, null));
        assertThatThrownBy(() -> taskService.create(new CreateTaskCmd(
            "202609-V6.10.12", subsystemId, CREATOR, PLAN_FINISH, null)))
            .as("a task name identifies the release train inside a subsystem")
            .isInstanceOf(BizException.class)
            .extracting("error").isEqualTo(BizError.DUPLICATE_NAME);
    }

    @Test
    void progress_is_zero_for_a_task_without_items() {
        long id = taskService.create(new CreateTaskCmd(
            "202609-V6.10.12", subsystemId, CREATOR, PLAN_FINISH, "新功能"));

        ProgressView progress = taskService.progress(id);
        assertThat(progress.done()).isZero();
        assertThat(progress.total()).isZero();
        assertThat(progress.rate()).isZero();
        assertThat(progress.hasBlocking()).isFalse();
    }

    @Test
    void adding_the_same_member_twice_updates_the_role_and_keeps_one_row() {
        long id = taskService.create(cmd());

        taskService.addMembers(id, List.of(new MemberCmd(CREATOR, "DEV")));
        assertThat(memberRole(id, CREATOR)).isEqualTo("DEV");

        // Same (task_id, user_id): the second add is an idempotent overlay, not a duplicate.
        taskService.addMembers(id, List.of(new MemberCmd(CREATOR, "PRODUCT")));
        assertThat(memberCount(id)).isEqualTo(1);
        assertThat(memberRole(id, CREATOR)).isEqualTo("PRODUCT");

        // A different user is a separate row, and the role is parsed case-insensitively.
        taskService.addMembers(id, List.of(new MemberCmd(PRODUCT, "test")));
        assertThat(memberCount(id)).isEqualTo(2);
        assertThat(memberRole(id, PRODUCT)).isEqualTo("TEST");
    }

    @Test
    void member_role_must_be_one_of_the_task_roles() {
        long id = taskService.create(cmd());

        assertParamInvalid(() -> taskService.addMembers(
            id, List.of(new MemberCmd(CREATOR, "DEVOPS"))));
        assertThat(memberCount(id)).isZero();

        assertParamInvalid(() -> taskService.addMembers(
            id, List.of(new MemberCmd(INACTIVE, "DEV"))));
        assertParamInvalid(() -> taskService.addMembers(id, List.of()));
        assertParamInvalid(() -> taskService.addMembers(id, null));
        assertThat(memberCount(id)).isZero();

        // C22 addresses freeze notifications by role, so the member roles are real
        // platform role names, not ad-hoc strings.
        Set<String> memberRoles = Arrays.stream(MemberRole.values())
            .map(Enum::name)
            .collect(Collectors.toSet());
        assertThat(Roles.ALL).containsAll(memberRoles);
        assertThat(memberRoles).contains("PRODUCT");
    }

    @Test
    void productsOf_returns_only_product_members() {
        long id = taskService.create(cmd());
        taskService.addMembers(id, List.of(
            new MemberCmd(CREATOR, "DEV"),
            new MemberCmd(PRODUCT, "PRODUCT")));

        assertThat(taskService.productsOf(id)).containsExactly(PRODUCT);
        assertThat(taskService.productsOf(id)).doesNotContain(CREATOR);
        assertThat(taskService.productsOf(taskService.create(cmd("202609-V6.10.13"))))
            .as("a task without a product member has no freeze-notification recipient")
            .isEmpty();
    }

    @Test
    void unknown_task_id_is_param_invalid_for_reads_and_member_writes() {
        assertParamInvalid(() -> taskService.progress(999_999L));
        assertParamInvalid(() -> taskService.productsOf(999_999L));
        assertParamInvalid(() -> taskService.addMembers(
            999_999L, List.of(new MemberCmd(CREATOR, "DEV"))));
    }

    @Test
    void blocking_is_defined_over_item_status() {
        TaskProgress empty = TaskProgress.of(List.of());
        assertThat(empty.done()).isZero();
        assertThat(empty.total()).isZero();
        assertThat(empty.rate()).isZero();
        assertThat(empty.hasBlocking()).isFalse();

        TaskProgress mixed = TaskProgress.of(List.of(
            ItemStatus.DONE, ItemStatus.PROD_DEPLOYING, ItemStatus.PENDING, ItemStatus.FAILED));
        assertThat(mixed.done()).isEqualTo(1);
        assertThat(mixed.total()).isEqualTo(4);
        assertThat(mixed.rate()).isEqualTo(0.25);
        assertThat(mixed.hasBlocking()).isTrue();

        TaskProgress doneAndClosedOut = TaskProgress.of(List.of(
            ItemStatus.DONE, ItemStatus.CLOSED, ItemStatus.ROLLED_BACK));
        assertThat(doneAndClosedOut.hasBlocking()).isFalse();

        // FR-T-04 / J9: the rate is done/total and only DONE counts as done.
        Set<ItemStatus> blocking = Arrays.stream(ItemStatus.values())
            .filter(ItemStatus::isBlocking)
            .collect(Collectors.toSet());
        assertThat(blocking).containsExactlyInAnyOrder(
            ItemStatus.PENDING, ItemStatus.BUILDING, ItemStatus.TESTING, ItemStatus.GATE_PENDING,
            ItemStatus.SUBMITTED, ItemStatus.PENDING_APPROVAL, ItemStatus.READY,
            ItemStatus.PROD_DEPLOYING);
        assertThat(Arrays.stream(ItemStatus.values()).filter(ItemStatus::isDone))
            .containsExactly(ItemStatus.DONE);
    }

    private CreateTaskCmd cmd() {
        return cmd("202609-V6.10.12");
    }

    private CreateTaskCmd cmd(String name) {
        return new CreateTaskCmd(name, subsystemId, CREATOR, PLAN_FINISH, "新功能");
    }

    private static void assertParamInvalid(Runnable call) {
        assertThatThrownBy(call::run)
            .isInstanceOf(BizException.class)
            .extracting("error").isEqualTo(BizError.PARAM_INVALID)
            .satisfies(error -> assertThat(((BizError) error).getCode())
                .isEqualTo(PARAM_INVALID_CODE));
    }

    private int taskCount() {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM release_task", Integer.class);
        return count == null ? 0 : count;
    }

    private int memberCount(long taskId) {
        Integer count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM release_task_member WHERE task_id = ?", Integer.class, taskId);
        return count == null ? 0 : count;
    }

    private String memberRole(long taskId, String userId) {
        return jdbc.queryForObject(
            "SELECT role FROM release_task_member WHERE task_id = ? AND user_id = ?",
            String.class, taskId, userId);
    }

    private long givenSubsystem(String name) {
        jdbc.update("INSERT INTO subsystem (name, owner_id, status, created_at, updated_at)"
            + " VALUES (?, ?, 'ACTIVE', NOW(3), NOW(3))", name, CREATOR);
        Long id = jdbc.queryForObject("SELECT id FROM subsystem WHERE name = ?", Long.class, name);
        return id == null ? 0L : id;
    }

    private void givenUser(String userId, String status) {
        jdbc.update("INSERT INTO app_user (id, name, status, created_at) VALUES (?, ?, ?, NOW(3))",
            userId, "用户" + userId, status);
    }
}