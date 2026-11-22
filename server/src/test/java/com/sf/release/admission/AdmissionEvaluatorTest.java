package com.sf.release.admission;

import com.sf.release.domain.admission.AdmissionDecision;
import com.sf.release.domain.admission.AdmissionEvaluator;
import com.sf.release.domain.admission.AdmissionFacts;
import com.sf.release.domain.admission.AdmissionStep;
import com.sf.release.domain.approval.ApprovalRole;
import com.sf.release.domain.gate.GateConclusion;
import com.sf.release.domain.task.TestStatus;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The pure 准入 judgement (tech design 4.5): the six steps, their order, their reasons and the chain
 * they imply. No Spring, no database — every case here is a fact combination.
 */
class AdmissionEvaluatorTest {

    private static final Instant NOW = Instant.parse("2026-11-22T02:00:00Z");
    private static final String OA = "OA-2026-0001";

    @Test
    void an_in_window_release_with_a_passing_gate_needs_no_approval() {
        AdmissionDecision decision = AdmissionEvaluator.evaluate(
            facts(GateConclusion.PASS, TestStatus.TEST_PASS, OA, NOW.plusSeconds(3600), true, false));

        assertThat(decision.admitted()).isTrue();
        assertThat(decision.requiredRoles()).isEmpty();
        assertThat(decision.reasons()).isEmpty();
        assertThat(decision.nextAction()).isEqualTo("无需审批，提交后即可执行生产");
        assertThat(decision.steps()).extracting(AdmissionStep::key).containsExactly(
            AdmissionStep.Key.GATE, AdmissionStep.Key.TEST, AdmissionStep.Key.OA_TICKET,
            AdmissionStep.Key.WINDOW, AdmissionStep.Key.FREEZE, AdmissionStep.Key.PLANNED_TIME);
    }

    @Test
    void outside_the_window_adds_the_operations_lead() {
        AdmissionDecision decision = AdmissionEvaluator.evaluate(
            facts(GateConclusion.PASS, TestStatus.TEST_PASS, OA, NOW.plusSeconds(3600), false,
                false));

        assertThat(decision.admitted()).isTrue();
        assertThat(decision.requiredRoles()).containsExactly(ApprovalRole.OPS_LEAD);
        assertThat(decision.nextAction()).contains("运维组长（OPS_LEAD）");
        assertThat(windowStep(decision).passed())
            .as("being outside the window is a requirement, not a failure")
            .isTrue();
        assertThat(windowStep(decision).nextAction()).contains("OPS_LEAD");
    }

    @Test
    void a_freeze_outside_the_window_also_needs_both_signatures() {
        AdmissionDecision decision = AdmissionEvaluator.evaluate(
            facts(GateConclusion.PASS, TestStatus.TEST_PASS, OA, NOW.plusSeconds(3600), false, true));

        assertThat(decision.admitted()).isTrue();
        assertThat(decision.requiredRoles()).containsExactly(ApprovalRole.OPS_LEAD,
            ApprovalRole.DEPT_MANAGER);
        assertThat(decision.nextAction())
            .contains("运维组长（OPS_LEAD）").contains("部门经理（DEPT_MANAGER）");
    }

    @Test
    void an_in_window_frozen_release_needs_both_signatures() {
        AdmissionDecision decision = AdmissionEvaluator.evaluate(
            facts(GateConclusion.PASS, TestStatus.TEST_PASS, OA, NOW.plusSeconds(3600), true, true));

        assertThat(decision.requiredRoles()).containsExactly(ApprovalRole.OPS_LEAD,
            ApprovalRole.DEPT_MANAGER);
        assertThat(freezeStep(decision).nextAction())
            .contains("OPS_LEAD").contains("DEPT_MANAGER");
    }

    @Test
    void a_waived_gate_is_admitted_but_an_invalid_or_unwaivable_block_is_not() {
        assertThat(AdmissionEvaluator.evaluate(facts(GateConclusion.WAIVED, TestStatus.TEST_PASS,
            OA, NOW.plusSeconds(3600), true, false)).admitted()).isTrue();

        AdmissionDecision invalid = AdmissionEvaluator.evaluate(facts(GateConclusion.INVALID,
            TestStatus.TEST_PASS, OA, NOW.plusSeconds(3600), true, false));
        assertThat(invalid.admitted()).isFalse();
        assertThat(invalid.blockedByGateOrTest()).isTrue();
        assertThat(invalid.steps().get(0).reason()).contains("检查门禁未通过");
        assertThat(invalid.steps().get(0).nextAction()).contains("重新执行");

        AdmissionDecision block = AdmissionEvaluator.evaluate(new AdmissionFacts(OA,
            NOW.plusSeconds(3600), NOW, GateConclusion.BLOCK, false, List.of("高危检查项未通过"),
            TestStatus.TEST_PASS, true, false));
        assertThat(block.admitted()).isFalse();
        assertThat(block.steps().get(0).reason()).contains("高危检查项未通过");
        assertThat(block.steps().get(0).nextAction()).contains("不可豁免");
    }

    @Test
    void a_waivable_block_says_the_waiver_is_an_option() {
        AdmissionDecision decision = AdmissionEvaluator.evaluate(new AdmissionFacts(OA,
            NOW.plusSeconds(3600), NOW, GateConclusion.BLOCK, true, List.of("中危检查项未通过"),
            TestStatus.TEST_PASS, true, false));

        assertThat(decision.steps().get(0).nextAction()).contains("豁免");
    }

    @Test
    void a_running_check_is_never_a_pass() {
        AdmissionDecision decision = AdmissionEvaluator.evaluate(facts(GateConclusion.RUNNING,
            TestStatus.TEST_PASS, OA, NOW.plusSeconds(3600), true, false));

        assertThat(decision.admitted()).isFalse();
        assertThat(decision.steps().get(0).reason()).contains("检查门禁未通过");
    }

    @Test
    void the_test_gate_requires_test_pass_and_a_waiver_never_lifts_it() {
        AdmissionDecision decision = AdmissionEvaluator.evaluate(facts(GateConclusion.WAIVED,
            TestStatus.TEST_FAIL, OA, NOW.plusSeconds(3600), true, false));

        assertThat(decision.admitted()).isFalse();
        assertThat(decision.blockedByGateOrTest()).isTrue();
        assertThat(decision.failedSteps()).extracting(AdmissionStep::key)
            .containsExactly(AdmissionStep.Key.TEST);
        assertThat(decision.steps().get(1).reason()).contains("测试不通过");
    }

    @Test
    void the_oa_ticket_format_is_checked_and_missing_is_not_the_same_as_malformed() {
        assertThat(oaStep(null).passed()).isFalse();
        assertThat(oaStep("  ").reason()).contains("不能为空");
        assertThat(oaStep("abc").passed()).as("too short").isFalse();
        assertThat(oaStep("-OA-123456").passed()).as("must start with a letter or digit").isFalse();
        assertThat(oaStep("OA 123456").passed()).isFalse();
        assertThat(oaStep("OA_2026-11").passed()).as("underscore and dash are allowed").isTrue();
        assertThat(oaStep("A".repeat(65)).passed()).as("64 is the maximum").isFalse();
        assertThat(oaStep("A".repeat(64)).passed()).isTrue();
        assertThat(AdmissionEvaluator.evaluate(facts(GateConclusion.PASS, TestStatus.TEST_PASS,
            "bad", NOW.plusSeconds(3600), true, false)).blockedByGateOrTest())
            .as("an OA problem is 40000, not 40002")
            .isFalse();
    }

    @Test
    void the_planned_time_must_be_after_now_and_at_most_thirty_days_ahead() {
        assertThat(AdmissionEvaluator.evaluate(facts(GateConclusion.PASS, TestStatus.TEST_PASS, OA,
            NOW, true, false)).admitted()).as("now is not after now").isFalse();
        assertThat(AdmissionEvaluator.evaluate(facts(GateConclusion.PASS, TestStatus.TEST_PASS, OA,
            NOW.minusSeconds(60), true, false)).steps().get(5).reason()).contains("必须晚于当前时间");
        assertThat(AdmissionEvaluator.evaluate(facts(GateConclusion.PASS, TestStatus.TEST_PASS, OA,
            NOW.plus(Duration.ofDays(30)), true, false)).admitted()).isTrue();
        assertThat(AdmissionEvaluator.evaluate(facts(GateConclusion.PASS, TestStatus.TEST_PASS, OA,
            NOW.plus(Duration.ofDays(31)), true, false)).admitted()).isFalse();
    }

    @Test
    void an_unknown_planned_time_fails_the_window_and_the_time_step() {
        AdmissionDecision decision = AdmissionEvaluator.evaluate(
            facts(GateConclusion.PASS, TestStatus.TEST_PASS, OA, null, false, false));

        assertThat(decision.admitted()).isFalse();
        assertThat(decision.failedSteps()).extracting(AdmissionStep::key)
            .containsExactly(AdmissionStep.Key.WINDOW, AdmissionStep.Key.PLANNED_TIME);
        assertThat(decision.requiredRoles()).as("no planned time means no window decision")
            .isEmpty();
    }

    @Test
    void the_next_action_is_the_first_failed_step_action() {
        AdmissionDecision decision = AdmissionEvaluator.evaluate(new AdmissionFacts("bad", NOW,
            NOW, GateConclusion.BLOCK, false, List.of("高危检查项未通过"), TestStatus.TEST_PENDING,
            true, false));

        assertThat(decision.reasons()).hasSize(4);
        assertThat(decision.nextAction()).contains("不可豁免");
    }

    // ------------------------------------------------------------------ helpers

    private static AdmissionFacts facts(GateConclusion conclusion, TestStatus test, String oa,
                                        Instant planned, boolean inWindow, boolean frozen) {
        return new AdmissionFacts(oa, planned, NOW, conclusion, false, List.of("检查原因"), test,
            inWindow, frozen);
    }

    private static AdmissionStep oaStep(String oa) {
        AdmissionDecision decision = AdmissionEvaluator.evaluate(
            facts(GateConclusion.PASS, TestStatus.TEST_PASS, oa, NOW.plusSeconds(3600), true, false));
        return decision.steps().stream()
            .filter(step -> step.key() == AdmissionStep.Key.OA_TICKET)
            .findFirst()
            .orElseThrow();
    }

    private static AdmissionStep windowStep(AdmissionDecision decision) {
        return decision.steps().stream()
            .filter(step -> step.key() == AdmissionStep.Key.WINDOW)
            .findFirst()
            .orElseThrow();
    }

    private static AdmissionStep freezeStep(AdmissionDecision decision) {
        return decision.steps().stream()
            .filter(step -> step.key() == AdmissionStep.Key.FREEZE)
            .findFirst()
            .orElseThrow();
    }
}