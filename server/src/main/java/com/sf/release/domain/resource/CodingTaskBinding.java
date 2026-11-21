package com.sf.release.domain.resource;

import java.time.Instant;

/**
 * A reference from a unit to a CODING task (C23). The natural key is
 * {@code (unitId, taskKind)}: a unit has at most one task per kind. {@code paramsJson}
 * carries the optional environment/variable template and {@code enabled} allows a
 * configured task to be switched off without deleting it.
 *
 * <p><strong>The BUILD binding owns the read-only branch (C21).</strong> Because the platform
 * does not manage branches, the branch a change item uses is the {@code branch} field of the
 * BUILD binding's {@code paramsJson} template (for example
 * {@code {"branch":"release/202611"}}). {@code ItemAppService.createFromBinding} reads it from
 * there and refuses to create an item when the binding configures none; a client never supplies
 * the branch.
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