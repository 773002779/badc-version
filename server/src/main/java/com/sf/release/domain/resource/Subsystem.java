package com.sf.release.domain.resource;

import java.time.Instant;

/**
 * A subsystem: the organisational unit that owns code repositories and deployment units.
 *
 * <p>The record is a pure domain value (no framework types). Text fields are normalised on
 * construction: surrounding whitespace is removed and empty strings become {@code null},
 * so the database never stores {@code "  SCC "} or {@code ""}. Required-field validation
 * lives in the application service, which can report {@code PARAM_INVALID} with the right
 * payload; this constructor only guarantees the shape of the value.
 */
public record Subsystem(
    Long id,
    String name,
    String ownerId,
    String agentId,
    SubsystemStatus status,
    String description,
    String gitUrl,
    String codingProject,
    Instant createdAt,
    Instant updatedAt) {

    public Subsystem {
        name = trimToNull(name);
        ownerId = trimToNull(ownerId);
        agentId = trimToNull(agentId);
        description = trimToNull(description);
        gitUrl = trimToNull(gitUrl);
        codingProject = trimToNull(codingProject);
        status = status == null ? SubsystemStatus.ACTIVE : status;
    }

    public boolean isActive() {
        return status == SubsystemStatus.ACTIVE;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}