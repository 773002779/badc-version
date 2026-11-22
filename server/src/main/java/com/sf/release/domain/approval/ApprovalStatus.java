package com.sf.release.domain.approval;

/**
 * Lifecycle of one approval step (tech design 4.4). The chain is a sequential 会签:
 *
 * <ul>
 *   <li>{@link #PENDING} — waiting; only the lowest pending step may be decided;</li>
 *   <li>{@link #APPROVED} — signed by somebody holding the role;</li>
 *   <li>{@link #REJECTED} — signed off with a mandatory reason; it terminates the chain;</li>
 *   <li>{@link #CANCELED} — a later step that became meaningless because an earlier one was
 *       rejected. Kept instead of deleted so the history shows who was still expected to sign.</li>
 * </ul>
 */
public enum ApprovalStatus {
    PENDING,
    APPROVED,
    REJECTED,
    CANCELED
}