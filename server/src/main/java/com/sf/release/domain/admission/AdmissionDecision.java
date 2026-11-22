package com.sf.release.domain.admission;

import com.sf.release.domain.approval.ApprovalRole;

import java.util.List;
import java.util.stream.Collectors;

/**
 * The outcome of one 生产准入 judgement (tech design 4.5): the six steps plus the approval chain the
 * snapshot requires.
 *
 * <p>{@link #admitted()} is derived from the steps, never stored, so a decision cannot claim to be
 * admitted while a step failed. {@link #requiredRoles()} is the chain the submission must build:
 * empty means 免审 ({@code NO_APPROVAL} → {@code READY}), otherwise the steps are signed in order.
 *
 * <p>{@link #blockedByGateOrTest()} is the hint the submit use case needs to pick 40002 (a gate or
 * test finding) instead of 40000 (a bad OA ticket or planned time) — the requirement fixes both
 * codes, and the caller must not guess from the message text.
 */
public record AdmissionDecision(List<ApprovalRole> requiredRoles, List<AdmissionStep> steps) {

    public AdmissionDecision {
        requiredRoles = requiredRoles == null ? List.of() : List.copyOf(requiredRoles);
        steps = steps == null ? List.of() : List.copyOf(steps);
    }

    /** True when every one of the six steps passed. */
    public boolean admitted() {
        return steps.stream().allMatch(AdmissionStep::passed);
    }

    /** The reasons of the failed steps, in evaluation order (empty when admitted). */
    public List<String> reasons() {
        return failedSteps().stream().map(AdmissionStep::reason).toList();
    }

    public List<AdmissionStep> failedSteps() {
        return steps.stream().filter(step -> !step.passed()).toList();
    }

    /** True when the gate or the test step is the reason for a refusal (40002, not 40000). */
    public boolean blockedByGateOrTest() {
        return failedSteps().stream().anyMatch(step -> step.key() == AdmissionStep.Key.GATE
            || step.key() == AdmissionStep.Key.TEST);
    }

    /**
     * What the operator should do next: the first failed step's action, or — when admitted — the
     * signature route ("提交后按顺序会签：…") or "无需审批".
     */
    public String nextAction() {
        for (AdmissionStep step : steps) {
            if (!step.passed()) {
                return step.nextAction() == null ? step.reason() : step.nextAction();
            }
        }
        if (requiredRoles.isEmpty()) {
            return "无需审批，提交后即可执行生产";
        }
        return "提交后按顺序会签："
            + requiredRoles.stream().map(AdmissionDecision::labelOf).collect(Collectors.joining(" → "));
    }

    /** Chinese label of a signature role, for the page and for the "next step" sentence. */
    public static String labelOf(ApprovalRole role) {
        return role == null ? "" : role.label() + "（" + role.name() + "）";
    }

    /** Chinese label of a persisted role name; an unknown word is returned unchanged. */
    public static String roleLabel(String role) {
        if (role == null) {
            return "";
        }
        try {
            return labelOf(ApprovalRole.valueOf(role));
        } catch (IllegalArgumentException ex) {
            return role;
        }
    }
}