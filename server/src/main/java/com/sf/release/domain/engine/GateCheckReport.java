package com.sf.release.domain.engine;

import com.sf.release.domain.gate.Severity;

/**
 * The result of polling one gate check.
 *
 * <p>{@code severity} is {@code null} when the engine reported no level (a still-running check, or
 * a response without the field); the platform then keeps the previous level instead of inventing
 * {@code NONE}, because {@code NONE} would read as "no findings".
 */
public record GateCheckReport(
    GateCheckOutcome outcome,
    Severity severity,
    int issues,
    String summary,
    String reportUrl) {

    public GateCheckReport {
        outcome = outcome == null ? GateCheckOutcome.UNKNOWN : outcome;
        issues = Math.max(issues, 0);
        summary = trimToNull(summary);
        reportUrl = trimToNull(reportUrl);
    }

    /** A running check with no further detail. */
    public static GateCheckReport running() {
        return new GateCheckReport(GateCheckOutcome.RUNNING, null, 0, null, null);
    }

    /** The response could not be judged; the platform retries instead of guessing. */
    public static GateCheckReport unknown(String summary) {
        return new GateCheckReport(GateCheckOutcome.UNKNOWN, null, 0, summary, null);
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}