package com.sf.release.freeze;

import com.sf.release.application.ApprovalService;
import com.sf.release.application.ApprovalStepView;
import com.sf.release.application.FreezeService;
import com.sf.release.application.FreezeView;
import com.sf.release.common.BizError;
import com.sf.release.common.BizException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 生产封板 against the real test schema (tech design 4.2): declaration, the in-flight handling that
 * adds the second signature, the recompute on unfreeze, and the expiry scan.
 *
 * <p>The fixtures build the releases by hand (item + order + admission snapshot + approval steps)
 * because these tests are about the freeze logic, not about how a release reaches
 * {@code PENDING_APPROVAL}; {@code AdmissionServiceTest} covers the submission side.
 */
@SpringBootTest
@ActiveProfiles("test")
class FreezeServiceTest {

    private static final String ACTOR = "fz-lead";

    @Autowired FreezeService freezes;
    @Autowired ApprovalService approvals;
    @Autowired JdbcTemplate jdbc;

    private final AtomicInteger seq = new AtomicInteger();

    private long subsystemA;
    private long subsystemB;
    private long unitA;
    private long unitB;
    private long taskA;
    private long taskB;

    @BeforeEach
    void resetFixtures() {
        jdbc.update("DELETE FROM approval");
        jdbc.update("DELETE FROM admission_snapshot");
        jdbc.update("DELETE FROM freeze_event");
        jdbc.update("DELETE FROM gate_waiver");
        jdbc.update("DELETE FROM gate_check");
        jdbc.update("DELETE FROM pipeline_instance");
        jdbc.update("DELETE FROM release_item");
        jdbc.update("DELETE FROM release_task_member");
        jdbc.update("DELETE FROM release_task");
        jdbc.update("DELETE FROM release_order");
        jdbc.update("DELETE FROM deploy_unit");
        jdbc.update("DELETE FROM subsystem");
        jdbc.update("DELETE FROM window_exception");
        jdbc.update("DELETE FROM window_rule");
        jdbc.update("DELETE FROM release_window");
        jdbc.update("DELETE FROM user_scope");
        jdbc.update("DELETE FROM user_role");
        jdbc.update("DELETE FROM app_user");

        subsystemA = givenSubsystem("FZ-A");
        subsystemB = givenSubsystem("FZ-B");
        unitA = givenUnit(subsystemA, "fz-unit-a");
        unitB = givenUnit(subsystemB, "fz-unit-b");
        taskA = givenTask(subsystemA, "FZ-TASK-A");
        taskB = givenTask(subsystemB, "FZ-TASK-B");
    }

    // ------------------------------------------------------------------ create

    @Test
    void create_validates_the_range() {
        Instant now = Instant.now();

        assertBizError(() -> freezes.create("GLOBAL", null, "大促封板", null, now,
            now.minusSeconds(60), ACTOR), BizError.PARAM_INVALID, "endAt");
        assertBizError(() -> freezes.create("GLOBAL", null, "大促封板", null, now,
            now, ACTOR), BizError.PARAM_INVALID, "endAt");
        assertBizError(() -> freezes.create("GLOBAL", null, "大促封板", null, now.minusSeconds(120),
            now.minusSeconds(60), ACTOR), BizError.PARAM_INVALID, "endAt");
        assertThat(freezeCount()).isZero();
    }

    @Test
    void create_validates_the_scope_subsystem_and_reason() {
        Instant end = Instant.now().plusSeconds(3600);

        assertBizError(() -> freezes.create("PLANET", null, "封板", null, null, end, ACTOR),
            BizError.PARAM_INVALID, "scope");
        assertBizError(() -> freezes.create("SUBSYSTEM", null, "封板", null, null, end, ACTOR),
            BizError.PARAM_INVALID, "subsystemId");
        assertBizError(() -> freezes.create("SUBSYSTEM", 999_999L, "封板", null, null, end, ACTOR),
            BizError.PARAM_INVALID, "subsystemId");
        assertBizError(() -> freezes.create("GLOBAL", null, "   ", null, null, end, ACTOR),
            BizError.PARAM_INVALID, "reason");
        assertBizError(() -> freezes.create("GLOBAL", null, "封板", null, null, end, "  "),
            BizError.PARAM_INVALID, "initiator");
        assertThat(freezeCount()).isZero();
    }

    @Test
    void create_makes_the_freeze_active_immediately() {
        Instant start = Instant.now().minusSeconds(60);
        Instant end = Instant.now().plusSeconds(3600);

        FreezeView view = freezes.create("GLOBAL", null, "大促期间禁止上线", "全平台只读", start, end,
            ACTOR);

        assertThat(view.id()).isPositive();
        assertThat(view.status()).isEqualTo("ACTIVE");
        assertThat(view.statusLabel()).isEqualTo("生效中");
        assertThat(view.scopeLabel()).isEqualTo("全局");
        assertThat(view.active()).isTrue();
        assertThat(view.initiator()).isEqualTo(ACTOR);
        assertThat(freezes.active(0, null)).extracting(FreezeView::id).containsExactly(view.id());
    }

    @Test
    void active_covers_global_and_the_matching_subsystem_only() {
        Instant now = Instant.now();
        Instant end = now.plusSeconds(3600);
        long global = freezes.create("GLOBAL", null, "全局封板", null, now.minusSeconds(60), end,
            ACTOR).id();
        long onlyB = freezes.create("SUBSYSTEM", subsystemB, "B 系统封板", null,
            now.minusSeconds(60), end, ACTOR).id();
        long scheduled = freezes.create("SUBSYSTEM", subsystemA, "明天开始", null,
            now.plusSeconds(3600), now.plusSeconds(7200), ACTOR).id();

        assertThat(freezes.active(subsystemA, now)).extracting(FreezeView::id)
            .as("a subsystem freeze of B must not block A; a scheduled freeze is not in force yet")
            .containsExactly(global);
        assertThat(freezes.active(subsystemB, now)).extracting(FreezeView::id)
            .containsExactly(global, onlyB);
        assertThat(freezes.active(0, now)).extracting(FreezeView::id)
            .containsExactly(global, onlyB);
        assertThat(freezes.active(subsystemA, now.plusSeconds(3700))).extracting(FreezeView::id)
            .as("the first two ended, the scheduled one is now in force")
            .containsExactly(scheduled);
        assertThat(freezes.active(subsystemA, now.plusSeconds(7300))).extracting(FreezeView::id)
            .as("after its end it no longer blocks")
            .isEmpty();
    }

    @Test
    void a_negative_subsystem_id_is_a_parameter_error() {
        assertBizError(() -> freezes.active(-1L, Instant.now()), BizError.PARAM_INVALID,
            "subsystemId");
        assertBizError(() -> freezes.list(true, -1L), BizError.PARAM_INVALID, "subsystemId");
    }

    // ------------------------------------------------------------------ in-flight handling

    @Test
    void create_pulls_a_ready_item_back_and_adds_the_second_signature() {
        long itemId = givenItem(taskA, unitA, "READY");
        long orderId = givenOrder(itemId, "PO-FZ-READY");
        long snapshotId = givenSnapshot(orderId, itemId, true);

        freezes.create("GLOBAL", null, "大促封板", null, null, Instant.now().plusSeconds(3600),
            ACTOR);

        assertThat(itemStatus(itemId)).isEqualTo("PENDING_APPROVAL");
        assertThat(orderStatus(orderId)).isEqualTo("PENDING_APPROVAL");
        List<ApprovalStepView> chain = approvals.stepViews(snapshotId);
        assertThat(chain).hasSize(1);
        assertThat(chain.get(0).role()).isEqualTo("DEPT_MANAGER");
        assertThat(chain.get(0).status()).isEqualTo("PENDING");
    }

    @Test
    void create_pulls_a_submitted_item_into_approval() {
        long itemId = givenItem(taskA, unitA, "SUBMITTED");
        long orderId = givenOrder(itemId, "PO-FZ-SUBMITTED");
        long snapshotId = givenSnapshot(orderId, itemId, false);

        freezes.create("GLOBAL", null, "大促封板", null, null, Instant.now().plusSeconds(3600),
            ACTOR);

        assertThat(itemStatus(itemId)).isEqualTo("PENDING_APPROVAL");
        assertThat(orderStatus(orderId)).isEqualTo("PENDING_APPROVAL");
        assertThat(approvals.stepViews(snapshotId)).extracting(ApprovalStepView::role)
            .containsExactly("DEPT_MANAGER");
    }

    @Test
    void a_subsystem_freeze_leaves_the_other_system_alone() {
        long itemA = givenItem(taskA, unitA, "SUBMITTED");
        long orderA = givenOrder(itemA, "PO-FZ-SYS-A");
        long snapshotA = givenSnapshot(orderA, itemA, false);
        long itemB = givenItem(taskB, unitB, "SUBMITTED");
        long orderB = givenOrder(itemB, "PO-FZ-SYS-B");
        long snapshotB = givenSnapshot(orderB, itemB, false);

        freezes.create("SUBSYSTEM", subsystemB, "只封 B 系统", null, null,
            Instant.now().plusSeconds(3600), ACTOR);

        assertThat(itemStatus(itemA)).as("A is not affected").isEqualTo("SUBMITTED");
        assertThat(approvals.stepViews(snapshotA)).isEmpty();
        assertThat(itemStatus(itemB)).isEqualTo("PENDING_APPROVAL");
        assertThat(approvals.stepViews(snapshotB)).extracting(ApprovalStepView::role)
            .containsExactly("DEPT_MANAGER");
    }

    @Test
    void a_scheduled_freeze_raises_the_requirement_at_declaration_time() {
        // Deliberate: declaring the freeze is the governance act, and adding a signature early is
        // fail-closed (it can only add an approval, never remove one). The in-force range still
        // governs the admission step and the pre-execution review.
        long itemId = givenItem(taskA, unitA, "READY");
        long orderId = givenOrder(itemId, "PO-FZ-LATER");
        long snapshotId = givenSnapshot(orderId, itemId, true);
        Instant now = Instant.now();

        freezes.create("GLOBAL", null, "下周大促", null, now.plusSeconds(3600),
            now.plusSeconds(7200), ACTOR);

        assertThat(itemStatus(itemId)).isEqualTo("PENDING_APPROVAL");
        assertThat(approvals.stepViews(snapshotId)).extracting(ApprovalStepView::role)
            .containsExactly("DEPT_MANAGER");
    }

    @Test
    void an_in_flight_release_without_a_snapshot_is_left_untouched() {
        long itemId = givenItem(taskA, unitA, "READY");
        long orderId = givenOrder(itemId, "PO-FZ-NO-SNAPSHOT");

        freezes.create("GLOBAL", null, "大促封板", null, null, Instant.now().plusSeconds(3600),
            ACTOR);

        assertThat(itemStatus(itemId))
            .as("nothing can be recomputed without a snapshot, so the item is not pulled back")
            .isEqualTo("READY");
        assertThat(orderStatus(orderId)).isEqualTo("INIT");
    }

    // ------------------------------------------------------------------ release / unfreeze

    @Test
    void unfreeze_of_an_in_window_release_drops_both_pending_roles_and_finishes_the_chain() {
        long itemId = givenItem(taskA, unitA, "PENDING_APPROVAL");
        long orderId = givenOrderWithStatus(itemId, "PO-FZ-IN", "PENDING_APPROVAL");
        long snapshotId = givenSnapshot(orderId, itemId, true);
        givenApproval(orderId, snapshotId, 1, "OPS_LEAD", "PENDING");
        givenApproval(orderId, snapshotId, 2, "DEPT_MANAGER", "PENDING");
        long freezeId = freezes.create("GLOBAL", null, "大促封板", null, null,
            Instant.now().plusSeconds(3600), ACTOR).id();

        FreezeView released = freezes.release(freezeId, ACTOR, "大促结束");

        assertThat(released.status()).isEqualTo("RELEASED");
        assertThat(released.statusLabel()).isEqualTo("已解除");
        assertThat(released.releasedBy()).isEqualTo(ACTOR);
        assertThat(released.releasedAt()).isNotNull();
        assertThat(released.active()).isFalse();
        assertThat(approvals.stepViews(snapshotId)).isEmpty();
        assertThat(itemStatus(itemId)).isEqualTo("READY");
        assertThat(orderStatus(orderId)).isEqualTo("READY");
    }

    @Test
    void unfreeze_of_an_out_of_window_release_keeps_the_operations_signature() {
        long itemId = givenItem(taskA, unitA, "PENDING_APPROVAL");
        long orderId = givenOrderWithStatus(itemId, "PO-FZ-OUT", "PENDING_APPROVAL");
        long snapshotId = givenSnapshot(orderId, itemId, false);
        givenApproval(orderId, snapshotId, 1, "OPS_LEAD", "PENDING");
        givenApproval(orderId, snapshotId, 2, "DEPT_MANAGER", "PENDING");
        long freezeId = freezes.create("GLOBAL", null, "大促封板", null, null,
            Instant.now().plusSeconds(3600), ACTOR).id();

        freezes.release(freezeId, ACTOR, "封板解除");

        assertThat(approvals.stepViews(snapshotId)).extracting(ApprovalStepView::role)
            .containsExactly("OPS_LEAD");
        assertThat(itemStatus(itemId)).isEqualTo("PENDING_APPROVAL");
        assertThat(orderStatus(orderId)).isEqualTo("PENDING_APPROVAL");
    }

    @Test
    void unfreeze_never_deletes_an_approved_step() {
        long itemId = givenItem(taskA, unitA, "PENDING_APPROVAL");
        long orderId = givenOrderWithStatus(itemId, "PO-FZ-APPROVED", "PENDING_APPROVAL");
        long snapshotId = givenSnapshot(orderId, itemId, true);
        givenDecidedApproval(orderId, snapshotId, 1, "OPS_LEAD", "APPROVED", "fz-lead");
        givenApproval(orderId, snapshotId, 2, "DEPT_MANAGER", "PENDING");
        long freezeId = freezes.create("GLOBAL", null, "大促封板", null, null,
            Instant.now().plusSeconds(3600), ACTOR).id();

        freezes.release(freezeId, ACTOR, "封板解除");

        List<ApprovalStepView> chain = approvals.stepViews(snapshotId);
        assertThat(chain).hasSize(1);
        assertThat(chain.get(0).role()).isEqualTo("OPS_LEAD");
        assertThat(chain.get(0).status()).isEqualTo("APPROVED");
        assertThat(chain.get(0).decidedBy()).isEqualTo("fz-lead");
        assertThat(itemStatus(itemId))
            .as("no pending step left and one approval: the chain is complete")
            .isEqualTo("READY");
    }

    @Test
    void another_active_freeze_keeps_the_second_signature() {
        long itemId = givenItem(taskA, unitA, "PENDING_APPROVAL");
        long orderId = givenOrderWithStatus(itemId, "PO-FZ-TWO", "PENDING_APPROVAL");
        long snapshotId = givenSnapshot(orderId, itemId, true);
        givenApproval(orderId, snapshotId, 1, "OPS_LEAD", "PENDING");
        givenApproval(orderId, snapshotId, 2, "DEPT_MANAGER", "PENDING");
        Instant end = Instant.now().plusSeconds(3600);
        long first = freezes.create("GLOBAL", null, "第一个封板", null, null, end, ACTOR).id();
        freezes.create("SUBSYSTEM", subsystemA, "第二个封板", null, null, end, ACTOR);

        freezes.release(first, ACTOR, "只解除第一个");

        assertThat(approvals.stepViews(snapshotId)).extracting(ApprovalStepView::role)
            .as("the subsystem freeze is still in force, so DEPT_MANAGER stays")
            .containsExactly("DEPT_MANAGER");
        assertThat(itemStatus(itemId)).isEqualTo("PENDING_APPROVAL");
    }

    @Test
    void releasing_twice_is_a_state_error() {
        long freezeId = freezes.create("GLOBAL", null, "大促封板", null, null,
            Instant.now().plusSeconds(3600), ACTOR).id();
        freezes.release(freezeId, ACTOR, "解除");

        assertBizError(() -> freezes.release(freezeId, ACTOR, "再解除一次"),
            BizError.STATE_NOT_ALLOWED, "status");
    }

    @Test
    void release_requires_a_reason_and_an_existing_freeze() {
        long freezeId = freezes.create("GLOBAL", null, "大促封板", null, null,
            Instant.now().plusSeconds(3600), ACTOR).id();

        assertBizError(() -> freezes.release(freezeId, ACTOR, "  "), BizError.PARAM_INVALID,
            "reason");
        assertBizError(() -> freezes.release(999_999L, ACTOR, "解除"), BizError.PARAM_INVALID,
            "id");
        assertThat(freezeStatus(freezeId)).isEqualTo("ACTIVE");
    }

    // ------------------------------------------------------------------ expiry

    @Test
    void expire_due_flips_only_the_active_rows_whose_end_passed() {
        Instant now = Instant.now();
        long expired = givenFreeze("ACTIVE", now.minusSeconds(7200), now.minusSeconds(3600));
        long inForce = givenFreeze("ACTIVE", now.minusSeconds(60), now.plusSeconds(3600));
        long released = givenFreeze("RELEASED", now.minusSeconds(7200), now.plusSeconds(3600));

        assertThat(freezes.expireDue(now)).isEqualTo(1);

        assertThat(freezeStatus(expired)).isEqualTo("EXPIRED");
        assertThat(freezeStatus(inForce)).isEqualTo("ACTIVE");
        assertThat(freezeStatus(released)).as("a person's lift is kept").isEqualTo("RELEASED");
        assertThat(freezes.expireDue(now)).as("the scan is idempotent").isZero();
    }

    @Test
    void the_history_list_returns_the_released_freeze_after_it_left_the_active_list() {
        long freezeId = freezes.create("GLOBAL", null, "大促封板", null, null,
            Instant.now().plusSeconds(3600), ACTOR).id();
        freezes.release(freezeId, ACTOR, "解除");

        assertThat(freezes.list(true, null)).isEmpty();
        assertThat(freezes.list(false, null)).extracting(FreezeView::id).containsExactly(freezeId);
        assertThat(freezes.list(false, subsystemA)).extracting(FreezeView::id)
            .as("a GLOBAL freeze belongs to every system's list")
            .containsExactly(freezeId);
    }

    // ------------------------------------------------------------------ fixtures

    private long givenSubsystem(String name) {
        jdbc.update("INSERT INTO subsystem (name, owner_id, status, created_at, updated_at)"
            + " VALUES (?, ?, 'ACTIVE', NOW(3), NOW(3))", name, ACTOR);
        Long id = jdbc.queryForObject("SELECT id FROM subsystem WHERE name = ?", Long.class, name);
        return id == null ? 0L : id;
    }

    private long givenUnit(long subsystemId, String name) {
        jdbc.update("INSERT INTO deploy_unit (subsystem_id, name, type, owner_id, status,"
                + " created_at, updated_at) VALUES (?, ?, 'UNIT', ?, 'ACTIVE', NOW(3), NOW(3))",
            subsystemId, name, ACTOR);
        Long id = jdbc.queryForObject("SELECT id FROM deploy_unit WHERE name = ?", Long.class, name);
        return id == null ? 0L : id;
    }

    private long givenTask(long subsystemId, String name) {
        jdbc.update("INSERT INTO release_task (subsystem_id, name, creator, status, created_at,"
                + " updated_at) VALUES (?, ?, ?, 'ACTIVE', NOW(3), NOW(3))",
            subsystemId, name, ACTOR);
        Long id = jdbc.queryForObject("SELECT id FROM release_task WHERE name = ?", Long.class,
            name);
        return id == null ? 0L : id;
    }

    private long givenItem(long taskId, long unitId, String status) {
        int n = seq.incrementAndGet();
        String crid = "CR-FZ-" + n;
        jdbc.update("INSERT INTO release_item (task_id, crid, type, unit_id, build_plan_id,"
                + " source_branch, version, plan_release_date, status, gate_status, test_status,"
                + " row_version, created_at, updated_at)"
                + " VALUES (?, ?, 'UNIT', ?, 'P-BUILD', ?, 'v1', '2026-11-25', ?, 'CHECK_PASS',"
                + " 'TEST_PASS', 0, NOW(3), NOW(3))",
            taskId, crid, unitId, "release/fz-" + n, status);
        Long id = jdbc.queryForObject("SELECT id FROM release_item WHERE crid = ?", Long.class,
            crid);
        return id == null ? 0L : id;
    }

    private long givenOrder(long itemId, String publishNo) {
        return givenOrderWithStatus(itemId, publishNo, "INIT");
    }

    private long givenOrderWithStatus(long itemId, String publishNo, String status) {
        jdbc.update("INSERT INTO release_order (publish_no, item_id, planned_release_time,"
                + " window_result, deadline_at, status, row_version, created_at)"
                + " VALUES (?, ?, NOW(3), 'OUT', NOW(3), ?, 0, NOW(3))",
            publishNo, itemId, status);
        Long id = jdbc.queryForObject("SELECT id FROM release_order WHERE publish_no = ?",
            Long.class, publishNo);
        return id == null ? 0L : id;
    }

    private long givenSnapshot(long orderId, long itemId, boolean inWindow) {
        jdbc.update("INSERT INTO admission_snapshot (order_id, item_id, planned_release_time,"
                + " in_window, window_id, frozen, freeze_ids, max_severity, check_conclusion,"
                + " waiver_ids, test_conclusion, test_pass_by, oa_ticket_no, image_digest,"
                + " chain_json, created_at)"
                + " VALUES (?, ?, NOW(3), ?, NULL, 0, '[]', 'S3', 'PASS', '[]', 'TEST_PASS',"
                + " ?, 'OA-FZ-1', 'sha256:fz', '[]', NOW(3))",
            orderId, itemId, inWindow, ACTOR);
        Long id = jdbc.queryForObject("SELECT id FROM admission_snapshot WHERE order_id = ?",
            Long.class, orderId);
        return id == null ? 0L : id;
    }

    private void givenApproval(long orderId, long snapshotId, int stepNo, String role,
                               String status) {
        jdbc.update("INSERT INTO approval (order_id, snapshot_id, step_no, role, status,"
            + " created_at) VALUES (?, ?, ?, ?, ?, NOW(3))", orderId, snapshotId, stepNo, role,
            status);
    }

    private void givenDecidedApproval(long orderId, long snapshotId, int stepNo, String role,
                                       String status, String decidedBy) {
        jdbc.update("INSERT INTO approval (order_id, snapshot_id, step_no, role, status,"
                + " decided_by, decided_at, comment, created_at)"
                + " VALUES (?, ?, ?, ?, ?, ?, NOW(3), '同意', NOW(3))",
            orderId, snapshotId, stepNo, role, status, decidedBy);
    }

    private long givenFreeze(String status, Instant startAt, Instant endAt) {
        jdbc.update("INSERT INTO freeze_event (scope, subsystem_id, reason, impact, initiator_id,"
                + " start_at, end_at, status, released_by, released_at, created_at)"
                + " VALUES ('GLOBAL', NULL, '历史封板', NULL, ?, ?, ?, ?, NULL, NULL, NOW(3))",
            ACTOR, Timestamp.from(startAt), Timestamp.from(endAt), status);
        Long id = jdbc.queryForObject("SELECT MAX(id) FROM freeze_event", Long.class);
        return id == null ? 0L : id;
    }

    private long freezeCount() {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM freeze_event", Long.class);
        return count == null ? 0L : count;
    }

    private String freezeStatus(long id) {
        return jdbc.queryForObject("SELECT status FROM freeze_event WHERE id = ?", String.class, id);
    }

    private String itemStatus(long id) {
        return jdbc.queryForObject("SELECT status FROM release_item WHERE id = ?", String.class, id);
    }

    private String orderStatus(long id) {
        return jdbc.queryForObject("SELECT status FROM release_order WHERE id = ?", String.class,
            id);
    }

    private static void assertBizError(org.assertj.core.api.ThrowableAssert.ThrowingCallable call,
                                       BizError error, String field) {
        assertThatThrownBy(call).isInstanceOfSatisfying(BizException.class, ex -> {
            assertThat(ex.getError()).isEqualTo(error);
            assertThat(ex.getPayload()).isInstanceOf(Map.class);
            assertThat((Map<String, Object>) ex.getPayload()).containsKey(field);
        });
    }
}