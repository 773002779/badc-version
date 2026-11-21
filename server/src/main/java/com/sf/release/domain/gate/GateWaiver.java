package com.sf.release.domain.gate;

import java.time.Instant;
import java.util.Objects;

/**
 * The 豁免单 of one 变更项 (requirements FR-Q-03): one approved waiver covers the item's
 * medium-severity findings <em>for one snapshot</em> (commit + image digest).
 *
 * <p><strong>When it counts.</strong> {@link #countsAt(Instant, String, String)} is the single
 * definition used by the evaluator: approved, not expired, and the snapshot it was granted for is
 * still the current one. A changed commit or image therefore retires the waiver without anybody
 * having to cancel it — the same rule as the design's {@code waiverValid} (§7.2).
 *
 * <p><strong>No self-approval.</strong> The requester and the approver must differ (C19/C20
 * spirit); {@link #approvedBy(String, Instant)} rejects a waiver approved by its own requester.
 * The permission check lives in the application service because it needs the caller identity.
 */
public record GateWaiver(
    Long id,
    long itemId,
    Long orderId,
    GateWaiverStatus status,
    String reason,
    String impact,
    String requestedBy,
    Instant requestedAt,
    String approvedBy,
    Instant approvedAt,
    String decideReason,
    Instant expireAt,
    String commitSha,
    String imageDigest,
    Instant createdAt,
    Instant updatedAt) {

    /** Longest accepted text field, matching the DDL of {@code gate_waiver}. */
    public static final int MAX_TEXT = 512;

    public GateWaiver {
        status = status == null ? GateWaiverStatus.PENDING : status;
        reason = trimToNull(reason);
        impact = trimToNull(impact);
        requestedBy = trimToNull(requestedBy);
        approvedBy = trimToNull(approvedBy);
        decideReason = trimToNull(decideReason);
        commitSha = trimToNull(commitSha);
        imageDigest = trimToNull(imageDigest);
    }

    /**
     * True when this waiver lifts the item's medium findings right now: it is approved, not
     * expired, and the snapshot it covers is still the current one.
     */
    public boolean countsAt(Instant now, String currentCommit, String currentDigest) {
        return status == GateWaiverStatus.APPROVED
            && expireAt != null
            && now != null
            && expireAt.isAfter(now)
            && Objects.equals(commitSha, trimToNull(currentCommit))
            && Objects.equals(imageDigest, trimToNull(currentDigest));
    }

    /** True when the deadline passed while the waiver was still approved. */
    public boolean expiredAt(Instant now) {
        return status == GateWaiverStatus.APPROVED && expireAt != null && now != null
            && !expireAt.isAfter(now);
    }

    /**
     * The waiver with a new status and approver. Approving one's own request is rejected here,
     * next to the data, so no caller can bypass the rule by skipping a service check.
     *
     * @throws IllegalArgumentException when {@code approver} equals {@link #requestedBy}
     */
    public GateWaiver decide(GateWaiverStatus next, String approver, String note, Instant now) {
        Objects.requireNonNull(next, "next status must not be null");
        if (next == GateWaiverStatus.APPROVED && requestedBy != null
                && requestedBy.equals(trimToNull(approver))) {
            throw new IllegalArgumentException("豁免不能自审：" + requestedBy);
        }
        return new GateWaiver(id, itemId, orderId, next, reason, impact, requestedBy,
            requestedAt, trimToNull(approver), now, trimToNull(note), expireAt, commitSha,
            imageDigest, createdAt, now);
    }

    /** The waiver with an expiry change (only a pending or approved waiver can be re-dated). */
    public GateWaiver withExpiry(Instant nextExpire, Instant now) {
        return new GateWaiver(id, itemId, orderId, status, reason, impact, requestedBy,
            requestedAt, approvedBy, approvedAt, decideReason, nextExpire, commitSha,
            imageDigest, createdAt, now);
    }

    /** The waiver expired at {@code now}. */
    public GateWaiver expired(Instant now) {
        return new GateWaiver(id, itemId, orderId, GateWaiverStatus.EXPIRED, reason, impact,
            requestedBy, requestedAt, approvedBy, approvedAt, decideReason, expireAt, commitSha,
            imageDigest, createdAt, now);
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}