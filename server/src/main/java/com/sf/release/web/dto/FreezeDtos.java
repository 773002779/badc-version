package com.sf.release.web.dto;

import com.sf.release.application.FreezeView;

import java.time.Instant;
import java.util.List;

/**
 * Request and response bodies of the 封板 endpoints (tech design 4.8).
 *
 * <p>The web layer owns the wire shape; {@link FreezeView} stays HTTP-agnostic. A freeze is sent
 * with both the stable machine words ({@code scope}, {@code status}) and their Chinese labels, so
 * the page renders the banner without keeping its own copy of the vocabulary. {@code active} is the
 * "in force right now" answer and is <em>not</em> derived from {@code status} on the client: an
 * {@code ACTIVE} freeze may start later or have just ended.
 *
 * <p>{@code startAt}/{@code endAt} are ISO-8601 instants (UTC); a missing {@code startAt} means
 * "now". {@code reason} is required on both create and release; the release reason is validated and
 * logged (the table has no column for it, see {@code FreezeService}).
 */
public final class FreezeDtos {

    private FreezeDtos() {
    }

    /** {@code POST /api/v1/freezes}; {@code scope} is {@code GLOBAL} or {@code SUBSYSTEM}. */
    public record CreateFreezeRequest(String scope, Long subsystemId, String reason, String impact,
                                      Instant startAt, Instant endAt) {
    }

    /** {@code POST /api/v1/freezes/{id}/release}. */
    public record ReleaseFreezeRequest(String reason) {
    }

    /** One 封板 as the list and the create/release answers show it. */
    public record FreezeResponse(
        long id,
        String scope,
        String scopeLabel,
        Long subsystemId,
        String reason,
        String impact,
        String initiator,
        Instant startAt,
        Instant endAt,
        String status,
        String statusLabel,
        String releasedBy,
        Instant releasedAt,
        boolean active,
        Instant createdAt) {
    }

    public static FreezeResponse of(FreezeView view) {
        return new FreezeResponse(
            view.id(),
            view.scope(),
            view.scopeLabel(),
            view.subsystemId(),
            view.reason(),
            view.impact(),
            view.initiator(),
            view.startAt(),
            view.endAt(),
            view.status(),
            view.statusLabel(),
            view.releasedBy(),
            view.releasedAt(),
            view.active(),
            view.createdAt());
    }

    public static List<FreezeResponse> of(List<FreezeView> views) {
        return views.stream().map(FreezeDtos::of).toList();
    }
}