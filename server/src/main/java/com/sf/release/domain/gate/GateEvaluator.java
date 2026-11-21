package com.sf.release.domain.gate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The pure check-gate decision (tech design §7.2, requirements FR-Q-02). It reads the item's check
 * rows plus its waiver and answers one {@link GateDecision}; it never writes, never calls an
 * engine and never looks at the main state machine, so it can be tested exhaustively without a
 * database.
 *
 * <p><strong>The highest severity decides, not the per-check pass flag</strong> — that is the
 * documented口径 "取本次发布单所有检查项的最高等级". A check that reports {@code PASS} but a 高危
 * severity still blocks, because the severity is the fact and the status is the engine's opinion.
 *
 * <p><strong>Three rules that must never bend</strong>
 * <ol>
 *   <li>No checks, or any check still running/unknown, means {@link GateConclusion#RUNNING}:
 *       "not finished" is never "passed" (acceptance criterion of P2).</li>
 *   <li>高危/严重 is never waivable; only 中危 can be lifted, and only by an approved waiver of the
 *       same snapshot.</li>
 *   <li>An invalidated result blocks and is not waivable: the checks must run again.</li>
 * </ol>
 */
public final class GateEvaluator {

    private GateEvaluator() {
    }

    /**
     * Evaluates the check gate of one item.
     *
     * @param checks    the item's check rows (any stage, any order)
     * @param waiver    the item's waiver, or {@code null} when it has none
     * @param now       the instant used for the waiver expiry comparison
     * @param commitSha the snapshot currently being released (may be {@code null})
     * @param digest    the image digest currently being released (may be {@code null})
     */
    public static GateDecision evaluate(List<GateCheck> checks, GateWaiver waiver, Instant now,
                                        String commitSha, String digest) {
        List<GateCheck> rows = checks == null ? List.of() : List.copyOf(checks);
        if (rows.isEmpty()) {
            return new GateDecision(GateConclusion.RUNNING, Severity.NONE, 0, 0, 0, false,
                List.of("尚未开始检查：没有检查项记录"));
        }

        int passed = (int) rows.stream().filter(c -> c.status() == GateCheckStatus.PASS).count();

        List<String> invalid = rows.stream()
            .filter(c -> c.status() == GateCheckStatus.INVALID)
            .map(GateEvaluator::name)
            .toList();
        if (!invalid.isEmpty()) {
            return new GateDecision(GateConclusion.INVALID, maxSeverity(rows), passed, rows.size(),
                0, false,
                List.of("检查结果已失效，需要重新检查：" + String.join("、", invalid)));
        }

        List<GateCheck> unfinished = rows.stream()
            .filter(c -> c.status().pendingResult())
            .toList();
        if (!unfinished.isEmpty()) {
            List<String> reasons = new ArrayList<>();
            reasons.add("检查未完成：" + unfinished.stream().map(GateEvaluator::name)
                .reduce((a, b) -> a + "、" + b).orElse("")
                + "（未完成不会被当作通过）");
            for (GateCheck check : unfinished) {
                if (check.status() == GateCheckStatus.UNKNOWN) {
                    reasons.add(name(check) + "：引擎未返回可判定的结果，稍后自动重试");
                }
            }
            return new GateDecision(GateConclusion.RUNNING, maxSeverity(rows), passed, rows.size(),
                0, false, reasons);
        }

        List<GateCheck> blocking = rows.stream()
            .filter(GateEvaluator::blocks)
            .toList();
        if (blocking.isEmpty()) {
            List<String> reasons = new ArrayList<>();
            reasons.add("全部 " + rows.size() + " 项检查通过");
            Severity max = maxSeverity(rows);
            if (max == Severity.S3) {
                reasons.add("存在低危告警，仅记录不阻断");
            }
            return new GateDecision(GateConclusion.PASS, max, passed, rows.size(), 0, false,
                reasons);
        }

        Severity blockMax = blocking.stream()
            .map(GateCheck::severity)
            .reduce(Severity.NONE, Severity::max);
        boolean waivable = blocking.stream().allMatch(check -> check.severity().waivable());
        String detail = blocking.stream()
            .map(check -> name(check) + " " + check.severity().label()
                + "（" + check.issues() + " 项）")
            .reduce((a, b) -> a + "、" + b)
            .orElse("");

        if (waivable && waiver != null && waiver.countsAt(now, commitSha, digest)) {
            return new GateDecision(GateConclusion.WAIVED, blockMax, passed, rows.size(),
                blocking.size(), true,
                List.of("中危检查项已豁免：" + detail,
                    "豁免单 " + waiver.id() + "（" + waiver.approvedBy() + " 审批，"
                        + waiver.expireAt() + " 到期）"));
        }
        if (waivable) {
            return new GateDecision(GateConclusion.BLOCK, blockMax, passed, rows.size(),
                blocking.size(), true,
                List.of("中危检查项未通过，且没有生效的豁免：" + detail,
                    "可提交豁免申请（高危/严重不可豁免）"));
        }
        return new GateDecision(GateConclusion.BLOCK, blockMax, passed, rows.size(),
            blocking.size(), false,
            List.of("高危/严重检查项未通过，不可豁免：" + detail, "需修复后重新检查"));
    }

    /**
     * A check blocks when its severity blocks and its status is a finished, non-invalid result.
     * A {@code PASS} + 高危 contradiction still blocks (the severity is the fact).
     */
    private static boolean blocks(GateCheck check) {
        return check.severity().blocks() && check.status() != GateCheckStatus.INVALID;
    }

    private static Severity maxSeverity(List<GateCheck> rows) {
        Severity max = Severity.NONE;
        for (GateCheck check : rows) {
            max = Severity.max(max, check.severity());
        }
        return max;
    }

    private static String name(GateCheck check) {
        return Objects.requireNonNull(check.checkType(), "check type is required").label();
    }
}