package com.sf.release.domain.pipeline;

import java.time.Instant;
import java.util.Locale;
import java.util.Objects;

/**
 * A 发布单 (release order): the business object of one published 变更项. It is created once
 * and reused by reruns, so its {@code publishNo} stays stable while each execution gets a new
 * {@link PipelineInstance} (tech design §5.3, J16).
 *
 * <p>The order owns the admission fields of tech design §4.2 ({@code oaTicketNo},
 * {@code plannedReleaseTime}, {@code windowResult}, {@code deadlineAt}). Task 9 writes only
 * the identity and the initial lifecycle marker; the admission/gate tasks fill the rest.
 *
 * <p>{@code status} is a lifecycle marker. The tech design does not fix its vocabulary yet,
 * so Task 9 creates orders as {@link #STATUS_INIT} and later tasks may define the transitions.
 */
public record ReleaseOrder(
    Long id,
    String publishNo,
    Long itemId,
    String oaTicketNo,
    Instant plannedReleaseTime,
    String windowResult,
    Instant deadlineAt,
    String status,
    int rowVersion,
    Instant createdAt) {

    /** Initial lifecycle marker of a freshly published order. */
    public static final String STATUS_INIT = "INIT";

    public ReleaseOrder {
        publishNo = trimToNull(publishNo);
        oaTicketNo = trimToNull(oaTicketNo);
        windowResult = trimToNull(windowResult);
        status = trimToNull(status) == null
            ? STATUS_INIT : status.trim().toUpperCase(Locale.ROOT);
        Objects.requireNonNull(itemId, "itemId must not be null");
        rowVersion = Math.max(rowVersion, 0);
    }

    /** A copy carrying the database id assigned by the insert. */
    public ReleaseOrder withId(Long newId) {
        return new ReleaseOrder(newId, publishNo, itemId, oaTicketNo, plannedReleaseTime,
            windowResult, deadlineAt, status, rowVersion, createdAt);
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}