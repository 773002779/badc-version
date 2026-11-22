package com.sf.release.domain.approval;

import java.time.Instant;
import java.util.Objects;

/**
 * One step of an {@link ApprovalChain}: a 1-based sequence number, the role that must sign, the
 * status, and — once decided — who signed, when and with which note.
 *
 * <p>The decision fields are {@code null} while the step is {@link ApprovalStatus#PENDING}; a
 * rejected step always carries the mandatory reason in {@link #comment()}. A canceled step was
 * never decided, so it keeps them {@code null} too.
 */
public record ApprovalStep(int stepNo, ApprovalRole role, ApprovalStatus status, String decidedBy,
                           Instant decidedAt, String comment) {

    public ApprovalStep {
        if (stepNo < 1) {
            throw new IllegalArgumentException("stepNo must be >= 1: " + stepNo);
        }
        Objects.requireNonNull(role, "role must not be null");
        Objects.requireNonNull(status, "status must not be null");
    }

    /** A brand-new step waiting for its signature. */
    public static ApprovalStep pending(int stepNo, ApprovalRole role) {
        return new ApprovalStep(stepNo, role, ApprovalStatus.PENDING, null, null, null);
    }

    public boolean pending() {
        return status == ApprovalStatus.PENDING;
    }

    /** The same step with a new status and decision fields; used by {@link ApprovalChain}. */
    public ApprovalStep decided(ApprovalStatus newStatus, String actor, Instant at, String note) {
        Objects.requireNonNull(newStatus, "newStatus must not be null");
        return new ApprovalStep(stepNo, role, newStatus, actor, at, note);
    }
}