package com.sf.release.domain.freeze;

/**
 * Lifecycle of one {@link FreezeEvent} (tech design 4.2):
 *
 * <ul>
 *   <li>{@link #ACTIVE}: declared. It <em>in force</em> while {@code start_at <= now < end_at}
 *       (half-open, like the window rules); outside that range it is a scheduled or a just-ended
 *       freeze whose daily scan has not run yet.</li>
 *   <li>{@link #RELEASED}: lifted by a person; {@code released_by}/{@code released_at} are set.</li>
 *   <li>{@link #EXPIRED}: {@code end_at} passed and the daily scan closed it.</li>
 * </ul>
 *
 * <p>A freeze is never deleted: the rows are the evidence of why a release needed a second
 * signature.
 */
public enum FreezeStatus {
    ACTIVE,
    RELEASED,
    EXPIRED;

    /** Chinese label for the page; the enum name is the API value. */
    public String label() {
        return switch (this) {
            case ACTIVE -> "生效中";
            case RELEASED -> "已解除";
            case EXPIRED -> "已到期";
        };
    }
}