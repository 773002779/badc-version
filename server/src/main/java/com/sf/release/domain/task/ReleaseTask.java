package com.sf.release.domain.task;

import java.time.Instant;
import java.time.LocalDate;

/**
 * A release task: the organising unit of one 上线需求, named after the release train
 * (for example {@code 202609-V6.10.12}).
 *
 * <p>Pure domain value: text fields are trimmed on construction and blank values become
 * {@code null}. The task belongs to exactly one subsystem; its members are a separate
 * aggregate part ({@link TaskMember}) written in the same transaction.
 */
public record ReleaseTask(
    Long id,
    Long subsystemId,
    String name,
    String creator,
    String purpose,
    LocalDate planFinishDate,
    TaskStatus status,
    Instant createdAt,
    Instant updatedAt) {

    public ReleaseTask {
        name = trimToNull(name);
        creator = trimToNull(creator);
        purpose = trimToNull(purpose);
        status = status == null ? TaskStatus.ACTIVE : status;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}