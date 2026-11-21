package com.sf.release.domain.gate;

/**
 * Lifecycle of one {@link GateCheck} row.
 *
 * <p><strong>Only a terminal non-invalid status may be read as a result.</strong>
 * {@link #RUNNING}, {@link #PENDING} and {@link #UNKNOWN} all mean "we do not know yet". An
 * engine outage is recorded as {@link #UNKNOWN} on purpose: treating it as {@link #PASS} would
 * admit a release nobody checked, and treating it as {@link #FAIL} would block a release for a
 * reason the customer cannot fix (the reconcile loop retries it instead).
 */
public enum GateCheckStatus {
    /** Created, not started yet. */
    PENDING,
    /** Started; the engine has an external id and a deadline. */
    RUNNING,
    /** The check finished and its highest severity does not block (NONE / S3). */
    PASS,
    /** The check finished with a blocking severity. */
    FAIL,
    /** The check did not finish before its deadline; it does not pass. */
    TIMEOUT,
    /** The result no longer applies (commit / image changed, test deployment rerun). */
    INVALID,
    /** The engine could not be reached or answered with an unmapped state; retried later. */
    UNKNOWN;

    /** A result that will not change on its own. */
    public boolean terminal() {
        return this == PASS || this == FAIL || this == TIMEOUT || this == INVALID;
    }

    /** A finished result that stops the release. */
    public boolean blocking() {
        return this == FAIL || this == TIMEOUT || this == INVALID;
    }

    /** A result the page must show as "not finished yet". */
    public boolean pendingResult() {
        return this == PENDING || this == RUNNING || this == UNKNOWN;
    }
}