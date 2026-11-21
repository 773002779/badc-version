package com.sf.release.domain.gate;

import com.sf.release.domain.pipeline.CheckType;

import java.time.Instant;

/**
 * One check result of one 变更项 (tech design §7.1). It is the platform's own record: the engine
 * id, the scanned object, the highest severity and the human summary all live here, so the gate
 * decision never has to call the engine again.
 *
 * <p><strong>One row per check.</strong> The table's unique key is
 * {@code (item_id, check_kind, stage)}, so a second report for the same check overwrites this row
 * — the callback is idempotent and the page never shows two Sonar results for one item.
 *
 * <p>{@code scannedObject} is what was scanned (commit, image digest or DE name). A rerun that
 * rebuilds the image invalidates the previous result; comparing the scanned object is how the
 * gate notices, instead of trusting an "invalid" flag somebody had to set.
 */
public record GateCheck(
    Long id,
    long itemId,
    Long orderId,
    Long instanceId,
    CheckType checkType,
    GateCheckStatus status,
    Severity severity,
    int issues,
    String scannedObject,
    String externalId,
    String reportUrl,
    String summary,
    int attempt,
    Instant startedAt,
    Instant finishedAt,
    Instant deadlineAt) {

    public GateCheck {
        status = status == null ? GateCheckStatus.PENDING : status;
        severity = severity == null ? Severity.NONE : severity;
        issues = Math.max(issues, 0);
        attempt = Math.max(attempt, 0);
        scannedObject = trimToNull(scannedObject);
        externalId = trimToNull(externalId);
        reportUrl = trimToNull(reportUrl);
        summary = trimToNull(summary);
    }

    /** The stage this check belongs to, derived from its type. */
    public CheckType.CheckStage stage() {
        return checkType.stage();
    }

    /** True when the result can no longer apply to the current image/commit. */
    public boolean staleFor(String currentObject) {
        return scannedObject != null && !scannedObject.equals(trimToNull(currentObject));
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}