package com.sf.release.admission;

import com.sf.release.application.AdmissionService;
import com.sf.release.application.AdmissionView;
import com.sf.release.application.ApprovalService;
import com.sf.release.application.ApprovalStepView;
import com.sf.release.application.FreezeService;
import com.sf.release.application.ItemTransitions;
import com.sf.release.application.SubmitProdService;
import com.sf.release.application.WindowService;
import com.sf.release.common.BizError;
import com.sf.release.common.BizException;
import com.sf.release.common.security.Permissions;
import com.sf.release.domain.task.ItemEvent;
import com.sf.release.infra.security.TestUserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * 提交生产 / 重提 / 准入视图 against the real test schema (tech design 4.5/4.8): the three chains
 * (免审, single signature, double signature), the refusal codes, the snapshot evidence, and the
 * snapshot-scoped resubmission.
 *
 * <p>The item is built by hand (task + unit + item + order + instance + one passing check): these
 * tests are about the submission, not about the pipeline that produces a {@code GATE_PENDING} item.
 * The window fixtures are chosen so the current clock is irrelevant: an {@code enabled = false}
 * GLOBAL window is "always inside", and no window row at all is "outside".
 */
@SpringBootTest
@ActiveProfiles("test")
class AdmissionServiceTest {

    private static final String USER = "ad-dev";
    private static final String PUBLISH_NO = "PO-AD-1";
    private static final String OA = "OA-AD-0001";
    private static final String IMAGE = "sha256:ad-digest";

    @Autowired SubmitProdService submitProdService;
    @Autowired AdmissionService admissionService;
    @Autowired ApprovalService approvals;
    @Autowired FreezeService freezes;
    @Autowired WindowService windows;
    @Autowired ItemTransitions transitions;
    @Autowired JdbcTemplate jdbc;

    private long subsystemId;
    private long unitId;
    private long taskId;
    private long itemId;
    private long orderId;

    @BeforeEach
    void givenAnItemReadyForProduction() {
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

        TestUserContext.as(USER, Set.of("DEV"), Set.of(Permissions.CONTROL_SUBMIT_PROD,
            Permissions.CONTROL_FREEZE, Permissions.CONTROL_UNFREEZE));

        subsystemId = givenSubsystem("AD-SYS");
        unitId = givenUnit(subsystemId, "ad-unit");
        taskId = givenTask(subsystemId, "AD-TASK");
        itemId = givenItem("GATE_PENDING", "TEST_PASS", "ad-tester");
        orderId = givenOrder("INIT");
        givenInstance(IMAGE);
        givenCheck("SONAR", "IMAGE", "PASS", "S3", 0);
    }

    @AfterEach
    void clearUser() {
        TestUserContext.clear();
    }

    // ------------------------------------------------------------------ the three chains

    @Test
    void inside_the_window_the_release_is_ready_without_any_approval() {
        givenWindowConstraintOff();
        Instant planned = planned();

        AdmissionView view = submitProdService.submit(PUBLISH_NO, planned, OA);

        assertThat(view.itemStatus()).isEqualTo("READY");
        assertThat(view.orderStatus()).isEqualTo("READY");
        assertThat(view.chain()).isEmpty();
        assertThat(view.snapshot()).isNotNull();
        assertThat(view.snapshot().inWindow()).isTrue();
        assertThat(view.snapshot().windowId()).isNotNull();
        assertThat(view.snapshot().frozen()).isFalse();
        assertThat(view.nextAction()).contains("无需审批");
        assertThat(itemStatus()).isEqualTo("READY");
        assertThat(orderStatus()).isEqualTo("READY");
        assertThat(windowResult()).isEqualTo("IN");
        assertThat(orderOa()).isEqualTo(OA);
        assertThat(orderPlanned().toInstant()).isCloseTo(planned, within(1, ChronoUnit.SECONDS));
        assertThat(orderDeadline().toInstant())
            .isCloseTo(planned.plusSeconds(2 * 3600), within(1, ChronoUnit.SECONDS));
    }

    @Test
    void outside_the_window_the_release_needs_the_operations_lead() {
        // No release_window row at all: "not configured" is outside the window (design 4.1).
        Instant planned = planned();

        AdmissionView view = submitProdService.submit(PUBLISH_NO, planned, OA);

        assertThat(view.itemStatus()).isEqualTo("PENDING_APPROVAL");
        assertThat(view.orderStatus()).isEqualTo("PENDING_APPROVAL");
        assertThat(view.snapshot().inWindow()).isFalse();
        assertThat(view.snapshot().windowId()).isNull();
        assertThat(view.chain()).extracting(ApprovalStepView::role).containsExactly("OPS_LEAD");
        assertThat(view.chain().get(0).status()).isEqualTo("PENDING");
        assertThat(view.chain().get(0).stepNo()).isEqualTo(1);
        assertThat(windowResult()).isEqualTo("OUT");
        assertThat(itemStatus()).isEqualTo("PENDING_APPROVAL");
    }

    @Test
    void during_a_freeze_the_release_needs_both_signatures() {
        long freezeId = freezes.create("GLOBAL", null, "大促封板", null, null,
            Instant.now().plusSeconds(3600), "ad-manager").id();

        AdmissionView view = submitProdService.submit(PUBLISH_NO, planned(), OA);

        assertThat(view.itemStatus()).isEqualTo("PENDING_APPROVAL");
        assertThat(view.snapshot().frozen()).isTrue();
        assertThat(view.snapshot().freezeIds()).containsExactly(freezeId);
        assertThat(view.chain()).extracting(ApprovalStepView::role)
            .containsExactly("OPS_LEAD", "DEPT_MANAGER");
        assertThat(view.chain()).extracting(ApprovalStepView::status)
            .containsExactly("PENDING", "PENDING");
        assertThat(view.admitted()).isTrue();
        assertThat(view.steps()).hasSize(6);
    }

    @Test
    void a_freeze_declared_for_later_still_governs_a_release_planned_inside_it() {
        Instant planned = Instant.now().plusSeconds(7200);
        freezes.create("GLOBAL", null, "下周大促", null, Instant.now().plusSeconds(3600),
            Instant.now().plusSeconds(3 * 3600), "ad-manager");

        AdmissionView view = submitProdService.submit(PUBLISH_NO, planned, OA);

        assertThat(view.admitted()).isTrue();
        assertThat(view.itemStatus()).isEqualTo("PENDING_APPROVAL");
        assertThat(view.snapshot().frozen())
            .as("a declared freeze governs a release planned inside it (fail-closed)")
            .isTrue();
        assertThat(view.chain()).extracting(ApprovalStepView::role)
            .containsExactly("OPS_LEAD", "DEPT_MANAGER");
    }

    @Test
    void an_approved_waiver_lifts_the_check_gate_and_the_snapshot_records_it() {
        givenWindowConstraintOff();
        givenCheck("SONAR", "IMAGE", "FAIL", "S2", 2);
        long waiverId = givenApprovedWaiver();

        AdmissionView view = submitProdService.submit(PUBLISH_NO, planned(), OA);

        assertThat(view.itemStatus()).isEqualTo("READY");
        assertThat(view.snapshot().checkConclusion()).isEqualTo("WAIVED");
        assertThat(view.snapshot().waiverIds()).containsExactly(waiverId);
        assertThat(jdbc.queryForObject(
            "SELECT waiver_ids FROM admission_snapshot WHERE id = ?", String.class,
            view.snapshot().id())).isEqualTo("[" + waiverId + "]");
        assertThat(view.snapshot().checkConclusion()).isEqualTo("WAIVED");
        assertThat(view.chain()).isEmpty();
    }

    @Test
    void an_in_window_frozen_release_needs_only_the_department_manager() {
        givenWindowConstraintOff();
        freezes.create("GLOBAL", null, "大促封板", null, null,
            Instant.now().plusSeconds(3600), "ad-manager");

        AdmissionView view = submitProdService.submit(PUBLISH_NO, planned(), OA);

        assertThat(view.itemStatus()).isEqualTo("PENDING_APPROVAL");
        assertThat(view.chain()).extracting(ApprovalStepView::role).containsExactly("DEPT_MANAGER");
    }

    // ------------------------------------------------------------------ refusals

    @Test
    void a_blocked_gate_is_40002_with_its_reasons() {
        givenCheck("SONAR", "IMAGE", "FAIL", "S1", 2);

        assertBizError(() -> submitProdService.submit(PUBLISH_NO, planned(), OA),
            BizError.GATE_BLOCKED, ex -> {
                assertThat(reasons(ex)).isNotEmpty();
                assertThat(failedSteps(ex)).contains("GATE");
                assertThat(payload(ex).get("nextAction")).isNotNull();
                List<?> steps = (List<?>) payload(ex).get("steps");
                assertThat(steps).as("the six-step evidence travels in the refusal payload")
                    .hasSize(6);
                Map<?, ?> gate = (Map<?, ?>) steps.get(0);
                assertThat(gate.get("key")).isEqualTo("GATE");
                assertThat(gate.get("label")).isEqualTo("检查门禁");
                assertThat(gate.get("passed")).isEqualTo(false);
                assertThat((String) gate.get("reason")).isNotEmpty();
            });
        assertThat(itemStatus()).as("a refused submission writes nothing").isEqualTo("GATE_PENDING");
        assertThat(snapshotCount()).isZero();
        assertThat(orderStatus()).isEqualTo("INIT");
    }

    @Test
    void a_test_that_did_not_pass_is_40002() {
        jdbc.update("UPDATE release_item SET test_status = 'TEST_FAIL' WHERE id = ?", itemId);

        assertBizError(() -> submitProdService.submit(PUBLISH_NO, planned(), OA),
            BizError.GATE_BLOCKED, ex ->
                assertThat(failedSteps(ex)).contains("TEST"));
        assertThat(snapshotCount()).isZero();
    }

    @Test
    void a_malformed_oa_ticket_is_40000() {
        assertBizError(() -> submitProdService.submit(PUBLISH_NO, planned(), "bad"),
            BizError.PARAM_INVALID, ex ->
                assertThat(failedSteps(ex)).contains("OA_TICKET"));
        assertBizError(() -> submitProdService.submit(PUBLISH_NO, planned(), "  "),
            BizError.PARAM_INVALID, ex ->
                assertThat(failedSteps(ex)).contains("OA_TICKET"));
        assertThat(snapshotCount()).isZero();
    }

    @Test
    void a_planned_time_in_the_past_or_beyond_thirty_days_is_40000() {
        Instant now = Instant.now();

        assertBizError(() -> submitProdService.submit(PUBLISH_NO, now.minusSeconds(60), OA),
            BizError.PARAM_INVALID, ex ->
                assertThat(failedSteps(ex)).contains("PLANNED_TIME"));
        assertBizError(() -> submitProdService.submit(PUBLISH_NO, now.plusSeconds(31 * 86_400), OA),
            BizError.PARAM_INVALID, ex ->
                assertThat(failedSteps(ex)).contains("PLANNED_TIME"));
        assertBizError(() -> submitProdService.submit(PUBLISH_NO, null, OA),
            BizError.PARAM_INVALID, ex ->
                assertThat(failedSteps(ex))
                    .contains("PLANNED_TIME", "WINDOW"));
        assertThat(snapshotCount()).isZero();
    }

    @Test
    void a_second_submit_is_40001() {
        submitProdService.submit(PUBLISH_NO, planned(), OA);

        assertBizError(() -> submitProdService.submit(PUBLISH_NO, planned(), OA),
            BizError.STATE_NOT_ALLOWED, "status");
        assertThat(snapshotCount()).as("a repeat submit writes no second snapshot").isEqualTo(1);
    }

    @Test
    void an_item_that_did_not_pass_the_gate_cannot_be_submitted() {
        jdbc.update("UPDATE release_item SET status = 'TESTING' WHERE id = ?", itemId);

        assertBizError(() -> submitProdService.submit(PUBLISH_NO, planned(), OA),
            BizError.STATE_NOT_ALLOWED, "status");
    }

    @Test
    void an_unknown_publish_number_is_40000() {
        assertBizError(() -> submitProdService.submit("PO-NOPE", planned(), OA),
            BizError.PARAM_INVALID, "publishNo");
        assertBizError(() -> submitProdService.admission("PO-NOPE"),
            BizError.PARAM_INVALID, "publishNo");
    }

    @Test
    void submitting_requires_the_permission() {
        TestUserContext.as(USER, Set.of("TEST"), Set.of(Permissions.GATE_TEST_PASS));

        assertBizError(() -> submitProdService.submit(PUBLISH_NO, planned(), OA),
            BizError.FORBIDDEN, "perm");
    }

    @Test
    void submitting_requires_the_unit_in_scope() {
        // The permission is granted, the unit is not: the fail-closed scope guard must refuse.
        TestUserContext.scopedWithPerms(USER, Set.of(Permissions.CONTROL_SUBMIT_PROD));

        assertBizError(() -> submitProdService.submit(PUBLISH_NO, planned(), OA),
            BizError.FORBIDDEN, "unitId");
        assertBizError(() -> submitProdService.admission(PUBLISH_NO), BizError.FORBIDDEN, "unitId");
    }

    // ------------------------------------------------------------------ snapshot evidence

    @Test
    void the_snapshot_carries_the_whole_admission_evidence() {
        long freezeId = freezes.create("GLOBAL", null, "大促封板", null, null,
            Instant.now().plusSeconds(3600), "ad-manager").id();

        AdmissionView view = submitProdService.submit(PUBLISH_NO, planned(), OA);

        Map<String, Object> row = jdbc.queryForMap(
            "SELECT order_id, item_id, planned_release_time, freeze_ids,"
                + " max_severity, check_conclusion, waiver_ids, test_conclusion, test_pass_by,"
                + " oa_ticket_no, image_digest, chain_json FROM admission_snapshot WHERE id = ?",
            view.snapshot().id());
        assertThat(((Number) row.get("order_id")).longValue()).isEqualTo(orderId);
        assertThat(((Number) row.get("item_id")).longValue()).isEqualTo(itemId);
        assertThat(row.get("planned_release_time")).isNotNull();
        assertThat(jdbc.queryForObject(
            "SELECT in_window + 0 FROM admission_snapshot WHERE id = ?", Integer.class,
            view.snapshot().id())).as("outside the window").isZero();
        assertThat(jdbc.queryForObject(
            "SELECT frozen + 0 FROM admission_snapshot WHERE id = ?", Integer.class,
            view.snapshot().id())).isEqualTo(1);
        assertThat(row.get("freeze_ids")).isEqualTo("[" + freezeId + "]");
        assertThat(row.get("max_severity")).isEqualTo("S3");
        assertThat(row.get("check_conclusion")).isEqualTo("PASS");
        assertThat(row.get("waiver_ids")).isEqualTo("[]");
        assertThat(row.get("test_conclusion")).isEqualTo("TEST_PASS");
        assertThat(row.get("test_pass_by")).isEqualTo("ad-tester");
        assertThat(row.get("oa_ticket_no")).isEqualTo(OA);
        assertThat(row.get("image_digest")).isEqualTo(IMAGE);
        assertThat(row.get("chain_json")).isEqualTo("[\"OPS_LEAD\",\"DEPT_MANAGER\"]");
        assertThat(view.snapshot().freezeIds()).containsExactly(freezeId);
        assertThat(view.snapshot().chain()).containsExactly("OPS_LEAD", "DEPT_MANAGER");
        assertThat(approvalCount(view.snapshot().id())).isEqualTo(2);
    }

    @Test
    void the_draft_check_answers_the_six_steps_without_writing_anything() {
        AdmissionView clean = submitProdService.check(PUBLISH_NO, planned(), OA);

        assertThat(clean.admitted()).isTrue();
        assertThat(clean.steps()).extracting(step -> step.key())
            .containsExactly("GATE", "TEST", "OA_TICKET", "WINDOW", "FREEZE", "PLANNED_TIME");
        assertThat(clean.snapshot()).as("a draft check writes no snapshot").isNull();
        assertThat(clean.chain()).isEmpty();
        assertThat(snapshotCount()).isZero();

        AdmissionView bad = submitProdService.check(PUBLISH_NO, planned(), "bad");

        assertThat(bad.admitted()).isFalse();
        assertThat(bad.steps()).filteredOn(step -> !step.passed())
            .extracting(step -> step.key()).containsExactly("OA_TICKET");
        assertThat(bad.steps().get(2).reason()).contains("OA 工单号格式不合法");
        assertThat(bad.steps().get(2).nextAction()).isNotNull();
        assertThat(bad.steps().get(2).label()).isEqualTo("OA 工单号");
        assertThat(itemStatus()).isEqualTo("GATE_PENDING");
    }

    @Test
    void the_draft_check_is_scope_guarded() {
        TestUserContext.scoped(USER);

        assertBizError(() -> submitProdService.check(PUBLISH_NO, planned(), OA),
            BizError.FORBIDDEN, "unitId");
    }

    @Test
    void the_draft_evaluation_answers_the_submit_dialog_without_writing_anything() {
        com.sf.release.domain.admission.AdmissionDecision plain =
            admissionService.evaluate(orderId, planned(), OA);
        assertThat(plain.admitted()).isTrue();
        assertThat(plain.requiredRoles()).as("no window row means outside the window")
            .extracting(role -> role.name()).containsExactly("OPS_LEAD");

        freezes.create("GLOBAL", null, "大促封板", null, null, Instant.now().plusSeconds(3600),
            "ad-manager");
        com.sf.release.domain.admission.AdmissionDecision frozen =
            admissionService.evaluate(orderId, planned(), OA);

        assertThat(frozen.admitted()).isTrue();
        assertThat(frozen.requiredRoles()).extracting(role -> role.name())
            .containsExactly("OPS_LEAD", "DEPT_MANAGER");
        assertThat(frozen.nextAction()).contains("会签");
        assertThat(snapshotCount()).as("a draft writes no snapshot").isZero();
    }

    @Test
    void the_admission_read_returns_the_latest_snapshot_chain_and_statuses() {
        givenWindowConstraintOff();
        AdmissionView submitted = submitProdService.submit(PUBLISH_NO, planned(), OA);

        AdmissionView view = submitProdService.admission(PUBLISH_NO);

        assertThat(view.snapshot()).isNotNull();
        assertThat(view.snapshot().id()).isEqualTo(submitted.snapshot().id());
        assertThat(view.itemStatus()).isEqualTo("READY");
        assertThat(view.orderStatus()).isEqualTo("READY");
        assertThat(view.chain()).isEmpty();
        assertThat(view.review()).as("the pre-execution review belongs to the execution task").isNull();
        assertThat(view.admitted())
            .as("the stored snapshot holds the outcome, not the six step sentences")
            .isNull();
        assertThat(view.steps()).isEmpty();
        assertThat(view.nextAction()).isNotBlank();
    }

    @Test
    void the_admission_read_of_a_never_submitted_release_has_no_snapshot() {
        AdmissionView view = submitProdService.admission(PUBLISH_NO);

        assertThat(view.snapshot()).isNull();
        assertThat(view.chain()).isEmpty();
        assertThat(view.itemStatus()).isEqualTo("GATE_PENDING");
        assertThat(view.nextAction()).contains("尚未提交生产");
    }

    // ------------------------------------------------------------------ resubmission

    @Test
    void resubmit_keeps_the_old_chain_and_starts_a_new_snapshot() {
        AdmissionView first = submitProdService.submit(PUBLISH_NO, planned(), OA);
        long firstSnapshot = first.snapshot().id();
        rejectSubmittedRelease(firstSnapshot);
        String newOa = "OA-AD-0002";
        Instant newPlanned = planned().plusSeconds(600);

        AdmissionView second = submitProdService.resubmit(PUBLISH_NO, newPlanned, newOa);

        assertThat(second.snapshot().id()).isGreaterThan(firstSnapshot);
        assertThat(second.snapshot().oaTicketNo()).isEqualTo(newOa);
        assertThat(second.snapshot().plannedReleaseTime())
            .isCloseTo(newPlanned, within(1, ChronoUnit.SECONDS));
        assertThat(second.chain()).extracting(ApprovalStepView::role).containsExactly("OPS_LEAD");
        assertThat(second.chain().get(0).stepNo()).isEqualTo(1);
        assertThat(second.chain().get(0).status()).isEqualTo("PENDING");
        assertThat(second.itemStatus()).isEqualTo("PENDING_APPROVAL");
        assertThat(snapshotCount()).isEqualTo(2);
        assertThat(approvals.steps(firstSnapshot)).as("the rejected chain is history")
            .extracting(step -> step.status().name()).containsExactly("REJECTED");
        assertThat(approvals.stepViews(second.snapshot().id())).hasSize(1);
        assertThat(orderOa()).isEqualTo(newOa);
        assertThat(orderPlanned().toInstant())
            .isCloseTo(newPlanned, within(1, ChronoUnit.SECONDS));
    }

    @Test
    void resubmit_reuses_the_previous_oa_ticket_and_planned_time_when_omitted() {
        AdmissionView first = submitProdService.submit(PUBLISH_NO, planned(), OA);
        rejectSubmittedRelease(first.snapshot().id());
        String oaBefore = orderOa();
        Timestamp plannedBefore = orderPlanned();

        AdmissionView second = submitProdService.resubmit(PUBLISH_NO, null, null);

        assertThat(second.snapshot().id()).isGreaterThan(first.snapshot().id());
        assertThat(second.snapshot().oaTicketNo()).isEqualTo(oaBefore);
        assertThat(orderOa()).isEqualTo(oaBefore);
        assertThat(orderPlanned()).isEqualTo(plannedBefore);
    }

    @Test
    void submitting_a_rejected_item_points_at_the_resubmit_action() {
        AdmissionView first = submitProdService.submit(PUBLISH_NO, planned(), OA);
        rejectSubmittedRelease(first.snapshot().id());

        assertBizError(() -> submitProdService.submit(PUBLISH_NO, planned(), OA),
            BizError.STATE_NOT_ALLOWED, ex ->
                assertThat(ex.getMessage()).contains("重新提交"));
        assertThat(snapshotCount()).isEqualTo(1);
    }

    @Test
    void resubmit_requires_a_rejected_item() {
        assertBizError(() -> submitProdService.resubmit(PUBLISH_NO, planned(), OA),
            BizError.STATE_NOT_ALLOWED, "status");
        assertThat(snapshotCount()).isZero();
    }

    @Test
    void resubmit_reruns_the_admission_judgement_and_writes_nothing_when_it_fails() {
        AdmissionView first = submitProdService.submit(PUBLISH_NO, planned(), OA);
        rejectSubmittedRelease(first.snapshot().id());
        jdbc.update("UPDATE release_item SET test_status = 'TEST_REVOKED' WHERE id = ?", itemId);

        assertBizError(() -> submitProdService.resubmit(PUBLISH_NO, planned(), OA),
            BizError.GATE_BLOCKED, ex ->
                assertThat(failedSteps(ex)).contains("TEST"));
        assertThat(snapshotCount()).isEqualTo(1);
        assertThat(itemStatus()).isEqualTo("REJECTED");
    }

    // ------------------------------------------------------------------ fixtures

    private Instant planned() {
        return Instant.now().plusSeconds(3600);
    }

    private void givenWindowConstraintOff() {
        windows.save(new WindowService.SaveCommand("GLOBAL", null, false, null, List.of(),
            List.of()));
    }

    private long givenSubsystem(String name) {
        jdbc.update("INSERT INTO subsystem (name, owner_id, status, created_at, updated_at)"
            + " VALUES (?, ?, 'ACTIVE', NOW(3), NOW(3))", name, USER);
        Long id = jdbc.queryForObject("SELECT id FROM subsystem WHERE name = ?", Long.class, name);
        return id == null ? 0L : id;
    }

    private long givenUnit(long subsystemId, String name) {
        jdbc.update("INSERT INTO deploy_unit (subsystem_id, name, type, owner_id, status,"
                + " created_at, updated_at) VALUES (?, ?, 'UNIT', ?, 'ACTIVE', NOW(3), NOW(3))",
            subsystemId, name, USER);
        Long id = jdbc.queryForObject("SELECT id FROM deploy_unit WHERE name = ?", Long.class, name);
        return id == null ? 0L : id;
    }

    private long givenTask(long subsystemId, String name) {
        jdbc.update("INSERT INTO release_task (subsystem_id, name, creator, status, created_at,"
                + " updated_at) VALUES (?, ?, ?, 'ACTIVE', NOW(3), NOW(3))", subsystemId, name,
            USER);
        Long id = jdbc.queryForObject("SELECT id FROM release_task WHERE name = ?", Long.class,
            name);
        return id == null ? 0L : id;
    }

    private long givenItem(String status, String testStatus, String testPassBy) {
        jdbc.update("INSERT INTO release_item (task_id, crid, type, unit_id, build_plan_id,"
                + " source_branch, version, plan_release_date, status, gate_status, test_status,"
                + " last_test_pass_by, last_test_pass_at, row_version, created_at, updated_at)"
                + " VALUES (?, 'CR-AD-1', 'UNIT', ?, 'P-BUILD', 'release/ad', 'v1', '2026-11-25',"
                + " ?, 'CHECK_PASS', ?, ?, NOW(3), 0, NOW(3), NOW(3))",
            taskId, unitId, status, testStatus, testPassBy);
        Long id = jdbc.queryForObject("SELECT id FROM release_item WHERE crid = 'CR-AD-1'",
            Long.class);
        return id == null ? 0L : id;
    }

    private long givenOrder(String status) {
        jdbc.update("INSERT INTO release_order (publish_no, item_id, status, row_version,"
            + " created_at) VALUES (?, ?, ?, 0, NOW(3))", PUBLISH_NO, itemId, status);
        Long id = jdbc.queryForObject("SELECT id FROM release_order WHERE publish_no = ?",
            Long.class, PUBLISH_NO);
        return id == null ? 0L : id;
    }

    private void givenInstance(String digest) {
        jdbc.update("INSERT INTO pipeline_instance (order_id, pipeline_no, exec_type, trigger_type,"
                + " current_node, status, image_digest, commit_sha, start_at, created_at)"
                + " VALUES (?, 'PL-AD-1', 'ONLINE', 'INITIAL', 'VERIFY', 'RUNNING', ?,"
                + " '0123456789abcdef0123456789abcdef01234567', NOW(3), NOW(3))",
            orderId, digest);
    }

    private void givenCheck(String kind, String stage, String status, String severity, int issues) {
        jdbc.update("DELETE FROM gate_check WHERE item_id = ?", itemId);
        jdbc.update("INSERT INTO gate_check (item_id, order_id, check_kind, stage, status, severity,"
                + " issues, attempt, created_at, updated_at)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, 1, NOW(3), NOW(3))",
            itemId, orderId, kind, stage, status, severity, issues);
    }

    /**
     * An approved, unexpired waiver for the current commit and image: the 中危 finding is lifted, the
     * gate concludes {@code WAIVED} and the admission must record that waiver in the snapshot.
     */
    private long givenApprovedWaiver() {
        jdbc.update("INSERT INTO gate_waiver (item_id, order_id, status, reason, requested_by,"
                + " requested_at, approved_by, approved_at, expire_at, commit_sha, image_digest,"
                + " created_at, updated_at)"
                + " VALUES (?, ?, 'APPROVED', '5 个失败都是已知用例', 'ad-dev', NOW(3), 'ad-reviewer',"
                + " NOW(3), DATE_ADD(NOW(3), INTERVAL 3 DAY),"
                + " '0123456789abcdef0123456789abcdef01234567', ?, NOW(3), NOW(3))",
            itemId, orderId, IMAGE);
        Long id = jdbc.queryForObject("SELECT MAX(id) FROM gate_waiver", Long.class);
        assertThat(id).isNotNull();
        return id;
    }

    /** Rejects the submitted release the way the approval endpoints will (Task 3). */
    private void rejectSubmittedRelease(long snapshotId) {
        approvals.decide(snapshotId, 1, false, "ad-lead", "窗口外风险过大，驳回");
        assertThat(transitions.apply(itemId, ItemEvent.REJECT))
            .isEqualTo(ItemTransitions.Outcome.APPLIED);
        jdbc.update("UPDATE release_order SET status = 'REJECTED', row_version = row_version + 1"
            + " WHERE id = ?", orderId);
    }

    private long snapshotCount() {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM admission_snapshot WHERE order_id = ?",
            Long.class, orderId);
        return count == null ? 0L : count;
    }

    private long approvalCount(long snapshotId) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM approval WHERE snapshot_id = ?",
            Long.class, snapshotId);
        return count == null ? 0L : count;
    }

    private String itemStatus() {
        return jdbc.queryForObject("SELECT status FROM release_item WHERE id = ?", String.class,
            itemId);
    }

    private String orderStatus() {
        return jdbc.queryForObject("SELECT status FROM release_order WHERE id = ?", String.class,
            orderId);
    }

    private String windowResult() {
        return jdbc.queryForObject("SELECT window_result FROM release_order WHERE id = ?",
            String.class, orderId);
    }

    private String orderOa() {
        return jdbc.queryForObject("SELECT oa_ticket_no FROM release_order WHERE id = ?",
            String.class, orderId);
    }

    private Timestamp orderPlanned() {
        return jdbc.queryForObject("SELECT planned_release_time FROM release_order WHERE id = ?",
            Timestamp.class, orderId);
    }

    private Timestamp orderDeadline() {
        return jdbc.queryForObject("SELECT deadline_at FROM release_order WHERE id = ?",
            Timestamp.class, orderId);
    }

    private static Map<String, Object> payload(BizException ex) {
        assertThat(ex.getPayload()).isInstanceOf(Map.class);
        return (Map<String, Object>) ex.getPayload();
    }

    /** The failed admission step keys of a refusal payload. */
    private static List<String> failedSteps(BizException ex) {
        return stringsOf(payload(ex).get("failedSteps"));
    }

    /** The reasons of a refusal payload. */
    private static List<String> reasons(BizException ex) {
        return stringsOf(payload(ex).get("reasons"));
    }

    @SuppressWarnings("unchecked")
    private static List<String> stringsOf(Object value) {
        return value == null ? List.of() : (List<String>) value;
    }

    private static void assertBizError(org.assertj.core.api.ThrowableAssert.ThrowingCallable call,
                                       BizError error,
                                       java.util.function.Consumer<BizException> extra) {
        assertThatThrownBy(call).isInstanceOfSatisfying(BizException.class, ex -> {
            assertThat(ex.getError()).isEqualTo(error);
            extra.accept(ex);
        });
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