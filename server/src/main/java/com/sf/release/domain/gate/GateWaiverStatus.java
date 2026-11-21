package com.sf.release.domain.gate;

/** Lifecycle of one 豁免单 (requirements FR-Q-03). */
public enum GateWaiverStatus {
    /** Requested; it does NOT count towards the gate yet. */
    PENDING,
    /** Approved by a security reviewer other than the requester; it counts while unexpired. */
    APPROVED,
    /** Rejected; the requester may file a new one. */
    REJECTED,
    /** Passed {@code expire_at}; the gate falls back to blocked and records an invalidation. */
    EXPIRED,
    /** Superseded (a new snapshot, or the requester withdrew it). */
    CANCELED
}