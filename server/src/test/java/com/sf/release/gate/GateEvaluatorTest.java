package com.sf.release.gate;

import com.sf.release.domain.gate.GateCheck;
import com.sf.release.domain.gate.GateCheckStatus;
import com.sf.release.domain.gate.GateConclusion;
import com.sf.release.domain.gate.GateDecision;
import com.sf.release.domain.gate.GateEvaluator;
import com.sf.release.domain.gate.GateWaiver;
import com.sf.release.domain.gate.GateWaiverStatus;
import com.sf.release.domain.gate.Severity;
import com.sf.release.domain.pipeline.CheckType;
import com.sf.release.domain.task.GateStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The pure gate decision (requirements FR-Q-02, tech design §7.2). No Spring, no database: the
 * evaluator reads check rows and a waiver and answers one conclusion, so every rule of the口径 can
 * be pinned here.
 *
 * <p>The three rules that must never bend are covered explicitly: "not finished" is never "passed";
 * 高危/严重 is never waivable; an invalidated result blocks.
 */
class GateEvaluatorTest {

    private static final Instant NOW = Instant.parse("2026-11-21T10:00:00Z");
    private static final String COMMIT = "a".repeat(40);
    private static final String DIGEST = "sha256:gate";

    @Test
    void no_check_at_all_is_running_never_a_pass() {
        GateDecision decision = GateEvaluator.evaluate(List.of(), null, NOW, COMMIT, DIGEST);

        assertThat(decision.conclusion()).isEqualTo(GateConclusion.RUNNING);
        assertThat(decision.admits()).isFalse();
        assertThat(decision.gateStatus()).isEqualTo(GateStatus.CHECK_RUNNING);
        assertThat(decision.reasons()).isNotEmpty();
    }

    @Test
    void one_unfinished_check_keeps_the_gate_running_even_when_the_others_pass() {
        GateDecision decision = GateEvaluator.evaluate(List.of(
            pass(CheckType.SONAR, Severity.NONE),
            running(CheckType.AUTO_TEST)), null, NOW, COMMIT, DIGEST);

        assertThat(decision.conclusion()).isEqualTo(GateConclusion.RUNNING);
        assertThat(decision.passed()).isEqualTo(1);
        assertThat(decision.total()).isEqualTo(2);
        assertThat(decision.reasons()).anyMatch(reason -> reason.contains("自动化测试"));
    }

    @Test
    void an_unreachable_engine_is_not_finished_either() {
        GateDecision decision = GateEvaluator.evaluate(List.of(
            check(CheckType.SONAR, GateCheckStatus.UNKNOWN, Severity.NONE, 0)), null, NOW,
            COMMIT, DIGEST);

        assertThat(decision.conclusion()).isEqualTo(GateConclusion.RUNNING);
        assertThat(decision.admits()).isFalse();
        assertThat(decision.reasons()).anyMatch(reason -> reason.contains("引擎未返回"));
    }

    @Test
    void every_check_passed_is_a_pass_and_low_severity_only_warns() {
        GateDecision decision = GateEvaluator.evaluate(List.of(
            pass(CheckType.SONAR, Severity.S3),
            pass(CheckType.AUTO_TEST, Severity.NONE)), null, NOW, COMMIT, DIGEST);

        assertThat(decision.conclusion()).isEqualTo(GateConclusion.PASS);
        assertThat(decision.admits()).isTrue();
        assertThat(decision.gateStatus()).isEqualTo(GateStatus.CHECK_PASS);
        assertThat(decision.maxSeverity()).isEqualTo(Severity.S3);
        assertThat(decision.reasons()).anyMatch(reason -> reason.contains("低危"));
    }

    @Test
    void a_medium_finding_blocks_but_is_waivable() {
        GateDecision decision = GateEvaluator.evaluate(List.of(
            check(CheckType.SONAR, GateCheckStatus.FAIL, Severity.S2, 3),
            pass(CheckType.AUTO_TEST, Severity.NONE)), null, NOW, COMMIT, DIGEST);

        assertThat(decision.conclusion()).isEqualTo(GateConclusion.BLOCK);
        assertThat(decision.waivable()).isTrue();
        assertThat(decision.blocking()).isEqualTo(1);
        assertThat(decision.gateStatus()).isEqualTo(GateStatus.CHECK_FAIL);
        assertThat(decision.reasons()).anyMatch(reason -> reason.contains("可提交豁免申请"));
    }

    @Test
    void an_approved_unexpired_waiver_of_the_same_snapshot_lifts_a_medium_finding() {
        GateDecision decision = GateEvaluator.evaluate(List.of(
            check(CheckType.SONAR, GateCheckStatus.FAIL, Severity.S2, 3)),
            approvedWaiver(COMMIT, DIGEST, NOW.plusSeconds(3600)), NOW, COMMIT, DIGEST);

        assertThat(decision.conclusion()).isEqualTo(GateConclusion.WAIVED);
        assertThat(decision.admits()).isTrue();
        assertThat(decision.gateStatus()).isEqualTo(GateStatus.CHECK_WAIVED);
        assertThat(decision.reasons()).anyMatch(reason -> reason.contains("已豁免"));
    }

    @Test
    void a_waiver_does_not_count_after_its_expiry() {
        GateDecision decision = GateEvaluator.evaluate(List.of(
            check(CheckType.SONAR, GateCheckStatus.FAIL, Severity.S2, 3)),
            approvedWaiver(COMMIT, DIGEST, NOW.minusSeconds(60)), NOW, COMMIT, DIGEST);

        assertThat(decision.conclusion()).isEqualTo(GateConclusion.BLOCK);
        assertThat(decision.waivable()).isTrue();
    }

    @Test
    void a_waiver_of_another_image_does_not_count() {
        GateDecision decision = GateEvaluator.evaluate(List.of(
            check(CheckType.SONAR, GateCheckStatus.FAIL, Severity.S2, 3)),
            approvedWaiver(COMMIT, "sha256:other", NOW.plusSeconds(3600)), NOW, COMMIT, DIGEST);

        assertThat(decision.conclusion()).isEqualTo(GateConclusion.BLOCK);
    }

    @Test
    void a_pending_waiver_is_not_a_decision() {
        GateWaiver pending = new GateWaiver(9L, 1L, 2L, GateWaiverStatus.PENDING, "why", null,
            "dev", NOW, null, null, null, NOW.plusSeconds(3600), COMMIT, DIGEST, NOW, NOW);
        GateDecision decision = GateEvaluator.evaluate(List.of(
            check(CheckType.SONAR, GateCheckStatus.FAIL, Severity.S2, 3)), pending, NOW, COMMIT,
            DIGEST);

        assertThat(decision.conclusion()).isEqualTo(GateConclusion.BLOCK);
    }

    @Test
    void high_severity_blocks_and_is_never_waivable() {
        GateDecision decision = GateEvaluator.evaluate(List.of(
            check(CheckType.SONAR, GateCheckStatus.FAIL, Severity.S1, 1)),
            approvedWaiver(COMMIT, DIGEST, NOW.plusSeconds(3600)), NOW, COMMIT, DIGEST);

        assertThat(decision.conclusion()).isEqualTo(GateConclusion.BLOCK);
        assertThat(decision.waivable()).isFalse();
        assertThat(decision.reasons()).anyMatch(reason -> reason.contains("不可豁免"));
    }

    @Test
    void the_severity_is_the_fact_even_when_the_engine_says_pass() {
        // A check that reports PASS but a 高危 severity must still block: the level is the finding.
        GateDecision decision = GateEvaluator.evaluate(List.of(
            check(CheckType.SONAR, GateCheckStatus.PASS, Severity.S1, 1)), null, NOW, COMMIT,
            DIGEST);

        assertThat(decision.conclusion()).isEqualTo(GateConclusion.BLOCK);
        assertThat(decision.waivable()).isFalse();
    }

    @Test
    void an_invalidated_result_blocks_and_is_not_waivable() {
        GateDecision decision = GateEvaluator.evaluate(List.of(
            check(CheckType.SONAR, GateCheckStatus.INVALID, Severity.NONE, 0)),
            approvedWaiver(COMMIT, DIGEST, NOW.plusSeconds(3600)), NOW, COMMIT, DIGEST);

        assertThat(decision.conclusion()).isEqualTo(GateConclusion.INVALID);
        assertThat(decision.gateStatus()).isEqualTo(GateStatus.CHECK_INVALID);
        assertThat(decision.waivable()).isFalse();
    }

    @Test
    void the_highest_severity_of_the_whole_item_decides() {
        // One image-level 低危 and one environment-level 中危: the item is blocked by the higher
        // level, exactly the "取所有检查项最高等级" rule.
        GateDecision decision = GateEvaluator.evaluate(List.of(
            pass(CheckType.SONAR, Severity.S3),
            check(CheckType.AUTO_TEST, GateCheckStatus.FAIL, Severity.S2, 2)), null, NOW, COMMIT,
            DIGEST);

        assertThat(decision.conclusion()).isEqualTo(GateConclusion.BLOCK);
        assertThat(decision.maxSeverity()).isEqualTo(Severity.S2);
        assertThat(decision.summary()).contains("中危");
    }

    // ------------------------------------------------------------------ fixtures

    private static GateCheck pass(CheckType type, Severity severity) {
        return check(type, GateCheckStatus.PASS, severity, 0);
    }

    private static GateCheck running(CheckType type) {
        return check(type, GateCheckStatus.RUNNING, Severity.NONE, 0);
    }

    private static GateCheck check(CheckType type, GateCheckStatus status, Severity severity,
                                   int issues) {
        return new GateCheck(1L, 7L, 8L, 9L, type, status, severity, issues, DIGEST, "ext-1",
            "https://coding.example/report/1", null, 1, NOW.minusSeconds(600), null,
            NOW.plusSeconds(600));
    }

    private static GateWaiver approvedWaiver(String commit, String digest, Instant expireAt) {
        return new GateWaiver(9L, 7L, 8L, GateWaiverStatus.APPROVED, "medium accepted", null,
            "dev-1", NOW.minusSeconds(3600), "sec-1", NOW.minusSeconds(1800), null, expireAt,
            commit, digest, NOW.minusSeconds(3600), NOW.minusSeconds(1800));
    }
}