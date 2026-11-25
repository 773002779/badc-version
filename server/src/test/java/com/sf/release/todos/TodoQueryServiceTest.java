package com.sf.release.todos;

import com.sf.release.application.TodoQueryService;
import com.sf.release.application.TodoQueryService.TodoBoard;
import com.sf.release.application.TodoQueryService.TodoGroup;
import com.sf.release.application.TodoQueryService.TodoItem;
import com.sf.release.common.BizError;
import com.sf.release.common.BizException;
import com.sf.release.common.security.Permissions;
import com.sf.release.infra.repo.TodoQueryMapper;
import com.sf.release.infra.security.TestUserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The 待办中心 read model against the real test schema (P5-C design section 4): the four group
 * predicates, the first-unsigned-step and self-submission approval rules, the fail-closed unit scope,
 * the limit, the shapes of the three links and the fixed-clock waiting hours.
 *
 * <p>The service is constructed by hand with a frozen {@link Clock} on purpose: only the mapper and
 * the database are shared with the application. Package {@code com.sf.release.todos}, not
 * {@code ..web..}, because the architecture rule forbids the web package from depending on
 * {@code infra}.
 */
@SpringBootTest
@ActiveProfiles("test")
class TodoQueryServiceTest {

    private static final String APPROVE = Permissions.CONTROL_APPROVE;
    private static final String EXECUTE = Permissions.CONTROL_EXECUTE_PROD;

    /** The frozen clock of every waiting-hours assertion. */
    private static final Instant NOW = Instant.parse("2026-11-25T10:00:00Z");

    @Autowired TodoQueryMapper mapper;
    @Autowired JdbcTemplate jdbc;

    private TodoQueryService service;
    private long unitA;
    private long unitB;
    private long subsystemA;
    private long subsystemB;

    @BeforeEach
    void setUp() {
        TodoFixtures.clean(jdbc);
        service = new TodoQueryService(mapper, Clock.fixed(NOW, ZoneOffset.UTC));
        subsystemA = TodoFixtures.givenSubsystem(jdbc, "TODO-SYS-A", TodoFixtures.ME);
        unitA = TodoFixtures.givenUnit(jdbc, subsystemA, "todo-unit-a", TodoFixtures.ME);
        subsystemB = TodoFixtures.givenSubsystem(jdbc, "TODO-SYS-B", TodoFixtures.OTHER);
        unitB = TodoFixtures.givenUnit(jdbc, subsystemB, "todo-unit-b", TodoFixtures.OTHER);
    }

    @AfterEach
    void clearUser() {
        TestUserContext.clear();
    }

    // ------------------------------------------------------------------ 待测试

    @Test
    void test_group_lists_only_a_testing_item_of_a_test_member_whose_newest_instance_is_ready() {
        long task = TodoFixtures.givenTask(jdbc, subsystemA, "todo-test-task", TodoFixtures.OTHER,
            NOW.minusSeconds(24 * 3600));
        TodoFixtures.givenMember(jdbc, task, TodoFixtures.ME, "TEST");
        long hit = readyTestItem(task, unitA, "CR-HIT", NOW.minusSeconds(3 * 3600));

        // The newest instance of this order never reached the test environment; the older ready
        // instance must not put the item back in the queue.
        long notReady = TodoFixtures.givenItem(jdbc, task, unitA, "CR-NOT-READY", "TESTING",
            "TEST_FAIL", NOW.minusSeconds(24 * 3600));
        long notReadyOrder = TodoFixtures.givenOrder(jdbc, notReady, "PO-NOT-READY", "TESTING",
            NOW.minusSeconds(24 * 3600));
        TodoFixtures.givenInstance(jdbc, notReadyOrder, "PL-NOT-READY-1", "RUNNING",
            NOW.minusSeconds(9 * 3600));
        TodoFixtures.givenInstance(jdbc, notReadyOrder, "PL-NOT-READY-2", "RUNNING", null);

        // Already passed: no longer a test todo.
        long passed = TodoFixtures.givenItem(jdbc, task, unitA, "CR-PASSED", "TESTING",
            "TEST_PASS", NOW.minusSeconds(24 * 3600));
        long passedOrder = TodoFixtures.givenOrder(jdbc, passed, "PO-PASSED", "TESTING",
            NOW.minusSeconds(24 * 3600));
        TodoFixtures.givenInstance(jdbc, passedOrder, "PL-PASSED", "RUNNING",
            NOW.minusSeconds(8 * 3600));

        // Member with the wrong task role: a DEV member is not the tester.
        long devTask = TodoFixtures.givenTask(jdbc, subsystemA, "todo-dev-task",
            TodoFixtures.OTHER, NOW.minusSeconds(24 * 3600));
        TodoFixtures.givenMember(jdbc, devTask, TodoFixtures.ME, "DEV");
        readyTestItem(devTask, unitA, "CR-DEV-MEMBER", NOW.minusSeconds(7 * 3600));

        // No membership at all.
        long foreignTask = TodoFixtures.givenTask(jdbc, subsystemA, "todo-foreign-task",
            TodoFixtures.OTHER, NOW.minusSeconds(24 * 3600));
        TodoFixtures.givenMember(jdbc, foreignTask, TodoFixtures.OTHER, "TEST");
        readyTestItem(foreignTask, unitA, "CR-FOREIGN", NOW.minusSeconds(6 * 3600));

        caller(Set.of("TEST"), Set.of());

        TodoGroup group = board().test();

        assertThat(group.count()).isEqualTo(1);
        assertThat(group.items()).hasSize(1);
        TodoItem item = group.items().get(0);
        assertThat(item.objectType()).isEqualTo("ITEM");
        assertThat(item.objectId()).isEqualTo(String.valueOf(hit));
        assertThat(item.label()).isEqualTo("CR-HIT");
        assertThat(item.status()).isEqualTo("TESTING");
        assertThat(item.statusLabel()).isEqualTo("测试中");
        assertThat(item.unitId()).isEqualTo(unitA);
        assertThat(item.waitingSince()).isEqualTo(NOW.minusSeconds(3 * 3600));
        assertThat(item.waitingHours()).isEqualTo(3);
        assertThat(item.link()).isEqualTo("/tasks/" + task + "?item=" + hit);
    }

    @Test
    void test_group_reports_whole_hours_and_clamps_a_future_ready_time_to_zero() {
        long task = TodoFixtures.givenTask(jdbc, subsystemA, "todo-hours-task", TodoFixtures.OTHER,
            NOW.minusSeconds(3600));
        TodoFixtures.givenMember(jdbc, task, TodoFixtures.ME, "TEST");
        long future = readyTestItem(task, unitA, "CR-FUTURE", NOW.plusSeconds(3600));

        caller(Set.of("TEST"), Set.of());

        TodoGroup group = board().test();
        assertThat(group.items()).hasSize(1);
        assertThat(group.items().get(0).objectId()).isEqualTo(String.valueOf(future));
        assertThat(group.items().get(0).waitingHours()).isZero();
    }

    @Test
    void the_limit_keeps_the_count_full_and_orders_the_oldest_wait_first() {
        long task = TodoFixtures.givenTask(jdbc, subsystemA, "todo-limit-task", TodoFixtures.OTHER,
            NOW.minusSeconds(3600));
        TodoFixtures.givenMember(jdbc, task, TodoFixtures.ME, "TEST");
        long oldest = 0;
        for (int index = 1; index <= 6; index++) {
            long item = readyTestItem(task, unitA, "CR-LIMIT-" + index,
                NOW.minusSeconds(index * 3600L));
            if (index == 6) {
                oldest = item;
            }
        }

        caller(Set.of("TEST"), Set.of());

        TodoGroup limited = board(2).test();
        assertThat(limited.count()).isEqualTo(6);
        assertThat(limited.items()).hasSize(2);
        assertThat(limited.items().get(0).objectId()).isEqualTo(String.valueOf(oldest));

        assertThat(board(20).test().items()).hasSize(6);
        assertThat(board(1).test().items()).hasSize(1);
        assertThat(service.board(null).test().items()).as("the default is 5").hasSize(5);
    }

    // ------------------------------------------------------------------ 待审批

    @Test
    void approval_group_lists_the_first_pending_step_for_a_role_holder_who_may_approve() {
        long order = approvalOrder(unitA, "PO-APPROVE-1", TodoFixtures.OTHER, NOW.minusSeconds(2 * 3600),
            1, "OPS_LEAD", "PENDING");

        caller(Set.of("OPS_LEAD"), Set.of(APPROVE));

        TodoGroup group = board().approval();

        assertThat(group.count()).isEqualTo(1);
        assertThat(group.items()).hasSize(1);
        TodoItem item = group.items().get(0);
        assertThat(item.objectType()).isEqualTo("ORDER");
        assertThat(item.objectId()).isEqualTo(String.valueOf(order));
        assertThat(item.label()).isEqualTo("PO-APPROVE-1");
        assertThat(item.status()).isEqualTo("PENDING_APPROVAL");
        assertThat(item.statusLabel()).isEqualTo("待审批");
        assertThat(item.unitId()).isEqualTo(unitA);
        assertThat(item.waitingSince()).isEqualTo(NOW.minusSeconds(2 * 3600));
        assertThat(item.waitingHours()).isEqualTo(2);
        assertThat(item.link()).isEqualTo("/releases/PO-APPROVE-1");
    }

    @Test
    void approval_group_ignores_a_role_mismatch_and_a_caller_without_control_approve() {
        approvalOrder(unitA, "PO-ROLE", TodoFixtures.OTHER, NOW.minusSeconds(3600),
            1, "OPS_LEAD", "PENDING");

        caller(Set.of("DEPT_MANAGER"), Set.of(APPROVE));
        assertThat(board().approval().count()).isZero();

        caller(Set.of("OPS_LEAD"), Set.of());
        assertThat(board().approval().count()).isZero();
    }

    @Test
    void approval_group_hides_a_release_the_caller_submitted() {
        approvalOrder(unitA, "PO-SELF", TodoFixtures.ME, NOW.minusSeconds(3600),
            1, "OPS_LEAD", "PENDING");

        caller(Set.of("OPS_LEAD"), Set.of(APPROVE));

        assertThat(board().approval().count()).isZero();
        assertThat(board().approval().items()).isEmpty();
    }

    @Test
    void approval_group_only_shows_the_lowest_pending_step_of_the_latest_snapshot() {
        // Step 1 is already signed: the second signer now sees the order.
        long secondStep = approvalOrder(unitA, "PO-SECOND", TodoFixtures.OTHER,
            NOW.minusSeconds(3600), 1, "OPS_LEAD", "APPROVED");
        long snapshot = latestSnapshot(secondStep);
        TodoFixtures.givenApproval(jdbc, secondStep, snapshot, 2, "DEPT_MANAGER", "PENDING");

        // Step 1 is still unsigned: the second signer must not be prompted yet.
        long waitingFirst = approvalOrder(unitA, "PO-FIRST", TodoFixtures.OTHER,
            NOW.minusSeconds(1800), 1, "OPS_LEAD", "PENDING");
        long waitingSnapshot = latestSnapshot(waitingFirst);
        TodoFixtures.givenApproval(jdbc, waitingFirst, waitingSnapshot, 2, "DEPT_MANAGER",
            "PENDING");

        caller(Set.of("DEPT_MANAGER"), Set.of(APPROVE));

        TodoGroup group = board().approval();
        assertThat(group.count()).isEqualTo(1);
        assertThat(group.items()).extracting(TodoItem::objectId)
            .containsExactly(String.valueOf(secondStep));
    }

    @Test
    void approval_group_reads_the_latest_snapshot_not_an_older_generation() {
        long order = approvalOrder(unitA, "PO-GENERATIONS", TodoFixtures.OTHER,
            NOW.minusSeconds(3 * 3600), 1, "OPS_LEAD", "PENDING");
        // A resubmission produced a newer snapshot whose first pending step is a DEPT_MANAGER
        // step; the OPS_LEAD step of the old generation is history.
        long item = jdbc.queryForObject("SELECT item_id FROM release_order WHERE id = ?",
            Long.class, order);
        long latest = TodoFixtures.givenSnapshot(jdbc, order, item, TodoFixtures.OTHER,
            NOW.minusSeconds(3600));
        TodoFixtures.givenApproval(jdbc, order, latest, 1, "DEPT_MANAGER", "PENDING");

        caller(Set.of("OPS_LEAD"), Set.of(APPROVE));
        assertThat(board().approval().count()).isZero();

        caller(Set.of("DEPT_MANAGER"), Set.of(APPROVE));
        assertThat(board().approval().count()).isEqualTo(1);
    }

    // ------------------------------------------------------------------ 待执行

    @Test
    void execute_group_hits_a_ready_order_of_an_ops_member() {
        long task = TodoFixtures.givenTask(jdbc, subsystemA, "todo-exec-task", TodoFixtures.OTHER,
            NOW.minusSeconds(3600));
        TodoFixtures.givenMember(jdbc, task, TodoFixtures.ME, "OPS");
        long order = readyOrder(task, unitA, "PO-EXEC-1", NOW.minusSeconds(4 * 3600));

        caller(Set.of("OPS"), Set.of());

        TodoGroup group = board().execute();
        assertThat(group.count()).isEqualTo(1);
        TodoItem item = group.items().get(0);
        assertThat(item.objectType()).isEqualTo("ORDER");
        assertThat(item.objectId()).isEqualTo(String.valueOf(order));
        assertThat(item.label()).isEqualTo("PO-EXEC-1");
        assertThat(item.status()).isEqualTo("READY");
        assertThat(item.statusLabel()).isEqualTo("待执行");
        assertThat(item.unitId()).isEqualTo(unitA);
        assertThat(item.waitingHours()).isEqualTo(4);
        assertThat(item.link()).isEqualTo("/releases/PO-EXEC-1");
    }

    @Test
    void execute_group_ignores_a_non_ops_member_without_the_execute_permission() {
        long task = TodoFixtures.givenTask(jdbc, subsystemA, "todo-exec-dev-task",
            TodoFixtures.OTHER, NOW.minusSeconds(3600));
        TodoFixtures.givenMember(jdbc, task, TodoFixtures.ME, "DEV");
        readyOrder(task, unitA, "PO-EXEC-DEV", NOW.minusSeconds(3600));

        caller(Set.of("DEV"), Set.of());

        assertThat(board().execute().count()).isZero();
    }

    @Test
    void execute_group_reaches_a_permission_holder_who_is_not_a_task_member() {
        long task = TodoFixtures.givenTask(jdbc, subsystemA, "todo-exec-admin-task",
            TodoFixtures.OTHER, NOW.minusSeconds(3600));
        readyOrder(task, unitA, "PO-EXEC-ADMIN", NOW.minusSeconds(3600));

        caller(Set.of("ADMIN"), Set.of(EXECUTE));

        TodoGroup group = board().execute();
        assertThat(group.count()).isEqualTo(1);
        assertThat(group.items().get(0).link()).isEqualTo("/releases/PO-EXEC-ADMIN");
    }

    // ------------------------------------------------------------------ 我发起的

    @Test
    void mine_group_lists_a_task_i_created_with_a_blocking_item_and_counts_the_items() {
        long task = TodoFixtures.givenTask(jdbc, subsystemA, "todo-mine-task", TodoFixtures.ME,
            NOW.minusSeconds(5 * 3600));
        TodoFixtures.givenItem(jdbc, task, unitA, "CR-MINE-TESTING", "TESTING", "TEST_FAIL",
            NOW.minusSeconds(5 * 3600));
        TodoFixtures.givenItem(jdbc, task, unitA, "CR-MINE-READY", "READY", "TEST_PASS",
            NOW.minusSeconds(5 * 3600));
        TodoFixtures.givenItem(jdbc, task, unitA, "CR-MINE-DONE", "DONE", "TEST_PASS",
            NOW.minusSeconds(5 * 3600));

        caller(Set.of(), Set.of());

        TodoGroup group = board().mine();
        assertThat(group.count()).isEqualTo(2);
        assertThat(group.items()).hasSize(1);
        TodoItem item = group.items().get(0);
        assertThat(item.objectType()).isEqualTo("TASK");
        assertThat(item.objectId()).isEqualTo(String.valueOf(task));
        assertThat(item.label()).isEqualTo("todo-mine-task");
        assertThat(item.status()).isEqualTo("ACTIVE");
        assertThat(item.statusLabel()).isEqualTo("进行中");
        assertThat(item.unitId()).isEqualTo(unitA);
        assertThat(item.waitingSince()).isEqualTo(NOW.minusSeconds(5 * 3600));
        assertThat(item.waitingHours()).isEqualTo(5);
        assertThat(item.link()).isEqualTo("/tasks/" + task);
    }

    @Test
    void mine_group_ignores_a_finished_task_and_a_task_someone_else_created() {
        long finished = TodoFixtures.givenTask(jdbc, subsystemA, "todo-mine-finished",
            TodoFixtures.ME, NOW.minusSeconds(3600));
        TodoFixtures.givenItem(jdbc, finished, unitA, "CR-MINE-DONE-ONLY", "DONE", "TEST_PASS",
            NOW.minusSeconds(3600));
        TodoFixtures.givenItem(jdbc, finished, unitA, "CR-MINE-CLOSED", "CLOSED", "TEST_PASS",
            NOW.minusSeconds(3600));

        long foreign = TodoFixtures.givenTask(jdbc, subsystemA, "todo-mine-foreign",
            TodoFixtures.OTHER, NOW.minusSeconds(3600));
        TodoFixtures.givenItem(jdbc, foreign, unitA, "CR-FOREIGN-TASK", "TESTING", "TEST_FAIL",
            NOW.minusSeconds(3600));

        caller(Set.of(), Set.of());

        assertThat(board().mine().count()).isZero();
        assertThat(board().mine().items()).isEmpty();
    }

    // ------------------------------------------------------------------ 数据范围与空库

    @Test
    void every_group_is_empty_outside_the_callers_unit_scope() {
        // One matching row per group, all in unitB.
        long testTask = TodoFixtures.givenTask(jdbc, subsystemB, "todo-scope-test-task",
            TodoFixtures.OTHER, NOW.minusSeconds(3600));
        TodoFixtures.givenMember(jdbc, testTask, TodoFixtures.ME, "TEST");
        readyTestItem(testTask, unitB, "CR-SCOPE-TEST", NOW.minusSeconds(3600));

        long execTask = TodoFixtures.givenTask(jdbc, subsystemB, "todo-scope-exec-task",
            TodoFixtures.OTHER, NOW.minusSeconds(3600));
        TodoFixtures.givenMember(jdbc, execTask, TodoFixtures.ME, "OPS");
        readyOrder(execTask, unitB, "PO-SCOPE-EXEC", NOW.minusSeconds(3600));

        approvalOrder(unitB, "PO-SCOPE-APPROVE", TodoFixtures.OTHER, NOW.minusSeconds(3600),
            1, "OPS_LEAD", "PENDING");

        long mineTask = TodoFixtures.givenTask(jdbc, subsystemB, "todo-scope-mine",
            TodoFixtures.ME, NOW.minusSeconds(3600));
        TodoFixtures.givenItem(jdbc, mineTask, unitB, "CR-SCOPE-MINE", "TESTING", "TEST_FAIL",
            NOW.minusSeconds(3600));

        TestUserContext.scopedWithPerms(TodoFixtures.ME, Set.of(APPROVE, EXECUTE), unitA);

        TodoBoard board = board();
        assertThat(board.test().count()).isZero();
        assertThat(board.approval().count()).isZero();
        assertThat(board.execute().count()).isZero();
        assertThat(board.mine().count()).isZero();
        assertThat(board.test().items()).isEmpty();
        assertThat(board.approval().items()).isEmpty();
        assertThat(board.execute().items()).isEmpty();
        assertThat(board.mine().items()).isEmpty();
    }

    @Test
    void a_caller_with_no_unit_scope_reads_nothing() {
        long task = TodoFixtures.givenTask(jdbc, subsystemA, "todo-noscope-task", TodoFixtures.ME,
            NOW.minusSeconds(3600));
        TodoFixtures.givenMember(jdbc, task, TodoFixtures.ME, "TEST");
        readyTestItem(task, unitA, "CR-NOSCOPE", NOW.minusSeconds(3600));

        TestUserContext.noUnits(TodoFixtures.ME);

        TodoBoard board = board();
        assertThat(board.test().count()).isZero();
        assertThat(board.mine().count()).isZero();
    }

    @Test
    void an_empty_database_returns_four_empty_groups() {
        caller(Set.of("ADMIN"), Set.of(APPROVE, EXECUTE));

        TodoBoard board = board();
        assertThat(board.test().count()).isZero();
        assertThat(board.approval().count()).isZero();
        assertThat(board.execute().count()).isZero();
        assertThat(board.mine().count()).isZero();
    }

    // ------------------------------------------------------------------ limit validation

    @Test
    void a_limit_outside_one_to_twenty_is_40000() {
        caller(Set.of("ADMIN"), Set.of());

        assertThatThrownBy(() -> service.board(0))
            .isInstanceOfSatisfying(BizException.class, ex -> {
                assertThat(ex.getError()).isEqualTo(BizError.PARAM_INVALID);
                assertThat((Map<String, Object>) ex.getPayload()).containsKey("limit");
            });
        assertThatThrownBy(() -> service.board(21))
            .isInstanceOfSatisfying(BizException.class,
                ex -> assertThat(ex.getError()).isEqualTo(BizError.PARAM_INVALID));
        assertThatThrownBy(() -> service.board(-1))
            .isInstanceOfSatisfying(BizException.class,
                ex -> assertThat(ex.getError()).isEqualTo(BizError.PARAM_INVALID));
    }

    // ------------------------------------------------------------------ fixtures

    private TodoBoard board() {
        return service.board(null);
    }

    private TodoBoard board(int limit) {
        return service.board(limit);
    }

    private static void caller(Set<String> roles, Set<String> perms) {
        TestUserContext.as(TodoFixtures.ME, roles, perms);
    }

    /** A TESTING item of {@code task} with an order whose newest instance became ready at {@code readyAt}. */
    private long readyTestItem(long task, long unitId, String crid, Instant readyAt) {
        long item = TodoFixtures.givenItem(jdbc, task, unitId, crid, "TESTING", "TEST_FAIL",
            readyAt.minusSeconds(3600));
        long order = TodoFixtures.givenOrder(jdbc, item, "PO-" + crid, "TESTING",
            readyAt.minusSeconds(3600));
        TodoFixtures.givenInstance(jdbc, order, "PL-" + crid, "RUNNING", readyAt);
        return item;
    }

    /** A READY order of {@code task}, created at {@code createdAt}. */
    private long readyOrder(long task, long unitId, String publishNo, Instant createdAt) {
        long item = TodoFixtures.givenItem(jdbc, task, unitId, "CR-" + publishNo, "READY",
            "TEST_PASS", createdAt);
        return TodoFixtures.givenOrder(jdbc, item, publishNo, "READY", createdAt);
    }

    /** A PENDING_APPROVAL order with one snapshot and one approval step. */
    private long approvalOrder(long unitId, String publishNo, String submittedBy,
                               Instant snapshotAt, int stepNo, String role, String status) {
        long task = TodoFixtures.givenTask(jdbc, subsystemA, "todo-approval-" + publishNo,
            TodoFixtures.OTHER, snapshotAt);
        long item = TodoFixtures.givenItem(jdbc, task, unitId, "CR-" + publishNo,
            "PENDING_APPROVAL", "TEST_PASS", snapshotAt);
        long order = TodoFixtures.givenOrder(jdbc, item, publishNo, "PENDING_APPROVAL",
            snapshotAt.minusSeconds(3600));
        long snapshot = TodoFixtures.givenSnapshot(jdbc, order, item, submittedBy, snapshotAt);
        TodoFixtures.givenApproval(jdbc, order, snapshot, stepNo, role, status);
        return order;
    }

    private long latestSnapshot(long orderId) {
        Long id = jdbc.queryForObject("SELECT MAX(id) FROM admission_snapshot WHERE order_id = ?",
            Long.class, orderId);
        return id == null ? 0L : id;
    }
}