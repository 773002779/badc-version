package com.sf.release.domain.resource;

import java.time.Instant;

/**
 * A reference from a unit to a CODING task (C23). The natural key is
 * {@code (unitId, taskKind)}: a unit has at most one task per kind. {@code paramsJson}
 * carries the optional environment/variable template and {@code enabled} allows a
 * configured task to be switched off without deleting it.
 */
public record CodingTaskBinding(
    Long id,
    Long unitId,
    TaskKind taskKind,
    String taskId,
    String paramsJson,
    boolean enabled,
    Instant createdAt,
    Instant updatedAt) {

    public CodingTaskBinding {
        taskId = trimToNull(taskId);
        paramsJson = trimToNull(paramsJson);
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}