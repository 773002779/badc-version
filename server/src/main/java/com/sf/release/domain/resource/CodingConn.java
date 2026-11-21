package com.sf.release.domain.resource;

import java.time.Instant;

/**
 * A CODING tenant connection (base URL, team, project and an encrypted credential).
 *
 * <p>The natural key is {@code (scope, subsystemId)}: one GLOBAL default plus at most one
 * override per subsystem. The entity only ever carries the <strong>encrypted</strong>
 * credential; the plaintext token exists in memory only while a save encrypts it or a
 * connection test decrypts it, and it is never part of this value.
 */
public record CodingConn(
    Long id,
    CodingScope scope,
    long subsystemId,
    String baseUrl,
    String team,
    String project,
    String credentialCipher,
    Instant createdAt,
    Instant updatedAt) {

    /** Sentinel used by GLOBAL rows so the unique key never sees a NULL. */
    public static final long GLOBAL_SUBSYSTEM_ID = 0L;

    public CodingConn {
        baseUrl = trimToNull(baseUrl);
        team = trimToNull(team);
        project = trimToNull(project);
        credentialCipher = trimToNull(credentialCipher);
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
