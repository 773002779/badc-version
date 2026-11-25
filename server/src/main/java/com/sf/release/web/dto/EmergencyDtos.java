package com.sf.release.web.dto;

import com.sf.release.application.EmergencyReleaseService;

/**
 * Response body of {@code POST /api/v1/releases/{publishNo}/emergency-release} (P6-D design §3).
 *
 * <p>The web layer owns the wire shape while the use case stays HTTP-agnostic: every status travels
 * as its machine string, so this class imports no domain or infrastructure enum (the web package may
 * only depend on {@code application} and {@code common}).
 *
 * <p>{@code emergency} is always {@code true}: the answer only exists for a successful release. The
 * request body is the shared {@link ControlDtos.ReasonRequest} ({@code {reason}}, 1..200 characters).
 */
public final class EmergencyDtos {

    private EmergencyDtos() {
    }

    /** {@code {status, itemStatus, emergency:true}} of a successful emergency release. */
    public record EmergencyResponse(String status, String itemStatus, boolean emergency) {
    }

    public static EmergencyResponse of(EmergencyReleaseService.EmergencyResult result) {
        return new EmergencyResponse(result.status(), result.itemStatus(), result.emergency());
    }
}