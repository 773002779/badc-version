package com.sf.release.domain.window;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * One contiguous stretch of time inside a {@link ReleaseWindow}: {@code [start, end)}.
 *
 * <p>Both bounds are local date-times in the window's zone, because the window itself is written
 * in local time. The application layer converts an {@code Instant} into the window zone before
 * asking, and the web layer renders the span as-is.
 */
public record WindowSpan(LocalDateTime start, LocalDateTime end) {

    public WindowSpan {
        Objects.requireNonNull(start, "start must not be null");
        Objects.requireNonNull(end, "end must not be null");
        if (end.isBefore(start)) {
            throw new IllegalArgumentException("end must not be before start: " + start + ".." + end);
        }
    }
}