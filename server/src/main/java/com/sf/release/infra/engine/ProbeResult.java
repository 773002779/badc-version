package com.sf.release.infra.engine;

/**
 * Outcome of a connection probe. {@code FAILED} is a business result, not an exception:
 * the caller (settings screen) shows the message to the operator.
 */
public record ProbeResult(Status status, String message) {

    public enum Status {
        OK,
        FAILED
    }

    public ProbeResult {
        status = status == null ? Status.FAILED : status;
    }

    public static ProbeResult ok(String message) {
        return new ProbeResult(Status.OK, message);
    }

    public static ProbeResult failed(String message) {
        return new ProbeResult(Status.FAILED, message);
    }

    public boolean isOk() {
        return status == Status.OK;
    }
}
