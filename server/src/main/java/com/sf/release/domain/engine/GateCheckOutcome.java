package com.sf.release.domain.engine;

/**
 * What one polled check reports. This is the engine's own vocabulary, deliberately separate from
 * the platform's {@code GateCheckStatus}: the adapter maps words to outcomes, the platform decides
 * what an outcome means for the gate.
 *
 * <p>{@link #UNKNOWN} is not a failure: it means "this response cannot be judged" (missing status
 * field, unmapped word). The platform keeps such a check open and retries, because reading it as
 * either pass or fail would be a lie.
 */
public enum GateCheckOutcome {
    RUNNING,
    SUCCESS,
    FAILED,
    UNKNOWN
}