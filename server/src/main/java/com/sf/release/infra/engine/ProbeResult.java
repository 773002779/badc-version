package com.sf.release.infra.engine;

/**
 * Outcome of a connection probe. {@code FAILED} is a business result, not an exception:
 * the caller (settings screen) shows the message to the operator.
 *
 * <p>The job counts belong to a successful list call ({@code job.list}); a failed probe leaves
 * them at zero because nothing was enumerated. They are reported separately from the message so
 * the page can render "构建 3 · 部署 2" without parsing text.
 */
public record ProbeResult(Status status, String message, int buildJobs, int deployJobs) {

    public enum Status {
        OK,
        FAILED
    }

    public ProbeResult {
        status = status == null ? Status.FAILED : status;
        buildJobs = Math.max(0, buildJobs);
        deployJobs = Math.max(0, deployJobs);
    }

    public static ProbeResult ok(String message) {
        return ok(message, 0, 0);
    }

    public static ProbeResult ok(String message, int buildJobs, int deployJobs) {
        return new ProbeResult(Status.OK, message, buildJobs, deployJobs);
    }

    public static ProbeResult failed(String message) {
        return new ProbeResult(Status.FAILED, message, 0, 0);
    }

    public boolean isOk() {
        return status == Status.OK;
    }
}