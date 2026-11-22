package com.sf.release.domain.admission;

import com.sf.release.domain.approval.ApprovalRole;
import com.sf.release.domain.gate.GateConclusion;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * The pure 生产准入 judgement (tech design 4.5), in the fixed order of
 * {@link AdmissionStep.Key}:
 *
 * <ol>
 *   <li><strong>检查门禁</strong> — {@code INVALID} and a non-waivable {@code BLOCK} refuse; a
 *       {@code WAIVED} conclusion is admitted (the waiver already lifted the 中危 findings) and so
 *       is {@code PASS}. {@code RUNNING} refuses as well: "not finished" is never a pass, and the
 *       submit use case only accepts {@code GATE_PENDING} items anyway.</li>
 *   <li><strong>测试门禁</strong> — only {@code TEST_PASS} admits (FR-Q-03: a waiver never lifts
 *       the test gate).</li>
 *   <li><strong>OA 工单号</strong> — required and matching {@link #OA_TICKET} (design 4.3).</li>
 *   <li><strong>发布窗口</strong> — inside needs nothing; outside adds {@code OPS_LEAD}.</li>
 *   <li><strong>封板</strong> — in force <em>at submission time</em> adds {@code DEPT_MANAGER} on
 *       top. The design evaluates the freeze at submission, not at the planned instant: the
 *       signature answers "is it acceptable to release while production is frozen", which is a
 *       question of the moment the release is handed over.</li>
 *   <li><strong>计划发布时间</strong> — after now, at most {@link #MAX_HORIZON} ahead.</li>
 * </ol>
 *
 * <p>The judgement is a pure function of {@link AdmissionFacts}: no clock, no database, no state
 * machine. The application layer resolves the facts, and the submit use case is what actually
 * writes the snapshot, the order fields and the chain.
 */
public final class AdmissionEvaluator {

    /**
     * The OA ticket format of design 4.3: 6-64 characters, starting with a letter or a digit, the
     * rest letters, digits, {@code _} or {@code -}.
     */
    public static final Pattern OA_TICKET = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9_-]{5,63}$");

    /** How far ahead a release may be planned; prevents "submit a time that never runs". */
    public static final Duration MAX_HORIZON = Duration.ofDays(30);

    private AdmissionEvaluator() {
    }

    /** Evaluates the six steps and the required chain of one submission. */
    public static AdmissionDecision evaluate(AdmissionFacts facts) {
        Objects.requireNonNull(facts, "facts must not be null");
        List<AdmissionStep> steps = new ArrayList<>(6);
        steps.add(gateStep(facts));
        steps.add(testStep(facts));
        steps.add(oaStep(facts.oaTicketNo()));
        steps.add(windowStep(facts));
        steps.add(freezeStep(facts));
        steps.add(plannedTimeStep(facts));
        return new AdmissionDecision(requiredRoles(facts), steps);
    }

    /**
     * The chain the snapshot requires: {@code OPS_LEAD} when the planned time is outside the
     * window, {@code DEPT_MANAGER} when a freeze is in force at submission, in that signature
     * order. An unknown planned time cannot be inside a window, so no {@code OPS_LEAD} is added
     * until the operator fills it in.
     */
    private static List<ApprovalRole> requiredRoles(AdmissionFacts facts) {
        List<ApprovalRole> roles = new ArrayList<>(2);
        if (facts.plannedAt() != null && !facts.inWindow()) {
            roles.add(ApprovalRole.OPS_LEAD);
        }
        if (facts.frozen()) {
            roles.add(ApprovalRole.DEPT_MANAGER);
        }
        return List.copyOf(roles);
    }

    private static AdmissionStep gateStep(AdmissionFacts facts) {
        GateConclusion conclusion = facts.gateConclusion();
        if (conclusion.admits()) {
            return new AdmissionStep(AdmissionStep.Key.GATE, true,
                "检查门禁通过：" + conclusion.label(), null);
        }
        String detail = facts.gateReasons().isEmpty()
            ? conclusion.label() : String.join("；", facts.gateReasons());
        return new AdmissionStep(AdmissionStep.Key.GATE, false,
            "检查门禁未通过：" + detail, gateNextAction(conclusion, facts.waivable()));
    }

    private static String gateNextAction(GateConclusion conclusion, boolean waivable) {
        return switch (conclusion) {
            case INVALID -> "重新执行已失效的检查项后再提交生产";
            case BLOCK -> waivable
                ? "修复检查项，或提交豁免申请（仅中危可豁免）后重试"
                : "修复检查项后重跑检查（高危/严重不可豁免）";
            default -> "等待检查完成后重试";
        };
    }

    private static AdmissionStep testStep(AdmissionFacts facts) {
        if (facts.testStatus().passed()) {
            return new AdmissionStep(AdmissionStep.Key.TEST, true,
                "测试门禁通过：" + facts.testStatus().label(), null);
        }
        return new AdmissionStep(AdmissionStep.Key.TEST, false,
            "测试门禁未通过（当前：" + facts.testStatus().label() + "）",
            "由测试人员标记测试通过后再提交生产");
    }

    private static AdmissionStep oaStep(String raw) {
        String oa = raw == null ? "" : raw.trim();
        if (oa.isEmpty()) {
            return new AdmissionStep(AdmissionStep.Key.OA_TICKET, false,
                "OA 工单号不能为空", "填写 OA 工单号");
        }
        if (!OA_TICKET.matcher(oa).matches()) {
            return new AdmissionStep(AdmissionStep.Key.OA_TICKET, false,
                "OA 工单号格式不合法：" + oa
                    + "（6-64 位字母、数字、下划线或中划线，且以字母或数字开头）",
                "填写符合格式的 OA 工单号");
        }
        return new AdmissionStep(AdmissionStep.Key.OA_TICKET, true, "OA 工单号已填写：" + oa, null);
    }

    private static AdmissionStep windowStep(AdmissionFacts facts) {
        if (facts.plannedAt() == null) {
            return new AdmissionStep(AdmissionStep.Key.WINDOW, false,
                "计划发布时间未填写，无法判定发布窗口", "填写计划发布时间");
        }
        if (facts.inWindow()) {
            return new AdmissionStep(AdmissionStep.Key.WINDOW, true,
                "计划时间在发布窗口内，无需运维组长审批", null);
        }
        return new AdmissionStep(AdmissionStep.Key.WINDOW, true,
            "计划时间不在发布窗口内，需要运维组长（OPS_LEAD）审批", "提交后由 OPS_LEAD 审批");
    }

    private static AdmissionStep freezeStep(AdmissionFacts facts) {
        if (facts.frozen()) {
            return new AdmissionStep(AdmissionStep.Key.FREEZE, true,
                "封板生效中，需要部门经理（DEPT_MANAGER）二次审批", "提交后由 DEPT_MANAGER 审批");
        }
        return new AdmissionStep(AdmissionStep.Key.FREEZE, true, "当前没有生效封板", null);
    }

    private static AdmissionStep plannedTimeStep(AdmissionFacts facts) {
        Instant planned = facts.plannedAt();
        if (planned == null) {
            return new AdmissionStep(AdmissionStep.Key.PLANNED_TIME, false,
                "计划发布时间不能为空", "填写计划发布时间");
        }
        if (!planned.isAfter(facts.now())) {
            return new AdmissionStep(AdmissionStep.Key.PLANNED_TIME, false,
                "计划发布时间必须晚于当前时间", "填写晚于当前时间的计划发布时间");
        }
        if (planned.isAfter(facts.now().plus(MAX_HORIZON))) {
            return new AdmissionStep(AdmissionStep.Key.PLANNED_TIME, false,
                "计划发布时间不能超过当前时间 " + MAX_HORIZON.toDays() + " 天",
                "填写不超过 " + MAX_HORIZON.toDays() + " 天的计划发布时间");
        }
        return new AdmissionStep(AdmissionStep.Key.PLANNED_TIME, true, "计划发布时间在允许范围内", null);
    }
}