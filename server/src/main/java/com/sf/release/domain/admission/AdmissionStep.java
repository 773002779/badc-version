package com.sf.release.domain.admission;

/**
 * One step of the 生产准入 judgement (tech design 4.5). The six steps are evaluated in the fixed
 * order of {@link Key}, and each answers three things: whether it passed, why, and what the
 * operator should do next. The page needs all three — requirements FR-G-05 forbid a disabled
 * button without a reason.
 *
 * <p>{@code nextAction} is {@code null} for a step that passed and needs nothing; a step may pass
 * and still carry an action (the window step passes when the planned time is outside the window,
 * but the release then needs an {@code OPS_LEAD} signature). {@link AdmissionDecision#admitted()}
 * is therefore about the step outcomes only, never about the required signatures.
 */
public record AdmissionStep(Key key, boolean passed, String reason, String nextAction) {

    /** The fixed evaluation order of tech design 4.5 (1..6); the names are the API value. */
    public enum Key {
        /** 检查门禁: {@code GateService.decisionFor}, INVALID or an unwaivable BLOCK refuses. */
        GATE,
        /** 测试门禁: {@code release_item.test_status == TEST_PASS}. */
        TEST,
        /** OA 工单号: non-empty and well formed (design 4.3). */
        OA_TICKET,
        /** 发布窗口: inside needs nothing, outside adds {@code OPS_LEAD}. */
        WINDOW,
        /** 封板: a freeze that governs the release (declared, not ended, starting no later than the
         * later of now and the planned time) requires the two signatures of design 4.4,
         * {@code OPS_LEAD} and {@code DEPT_MANAGER} — inside the window too. */
        FREEZE,
        /** 计划发布时间: after now and at most 30 days ahead. */
        PLANNED_TIME;

        /** Chinese label for the page; the enum name is the API value. */
        public String label() {
            return switch (this) {
                case GATE -> "检查门禁";
                case TEST -> "测试门禁";
                case OA_TICKET -> "OA 工单号";
                case WINDOW -> "发布窗口";
                case FREEZE -> "封板";
                case PLANNED_TIME -> "计划发布时间";
            };
        }
    }
}