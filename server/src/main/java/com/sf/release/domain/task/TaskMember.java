package com.sf.release.domain.task;

import java.time.Instant;

/**
 * A member of a release task with the role they play there. A user appears at most once
 * per task ({@code (task_id, user_id)} is unique), so re-adding the same person changes the
 * role of the existing row instead of duplicating it.
 */
public record TaskMember(
    Long id,
    Long taskId,
    String userId,
    MemberRole role,
    Instant createdAt,
    Instant updatedAt) {

    public TaskMember {
        userId = trimToNull(userId);
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}