package com.sf.release.application;

/**
 * One step of the 生产准入 judgement as the web layer sees it (design 4.5): the machine key, its
 * Chinese label, whether it passed, why, and what to do next. The page needs all five to replace a
 * greyed-out submit button with a reason (requirements FR-G-05).
 *
 * <p>{@code nextAction} is {@code null} for a step that passed and needs nothing; the window step
 * passes when the planned time is outside the window but still carries "提交后由 OPS_LEAD 审批", so a
 * passed step is not necessarily a step without an action.
 */
public record AdmissionStepView(String key, String label, boolean passed, String reason,
                                String nextAction) {
}