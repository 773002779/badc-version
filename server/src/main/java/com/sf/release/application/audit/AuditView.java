package com.sf.release.application.audit;

import java.time.Instant;

/**
 * One audit row as the read model exposes it (audit design section 4). The application view is
 * HTTP-agnostic; the web DTO maps it to the wire shape.
 *
 * <p>{@code action} is the stored machine code and {@code actionLabel} its Chinese name. A stored
 * code that this build no longer knows degrades to the raw code as the label instead of failing the
 * page (the audit trail is history, not a live enum).
 *
 * <p>{@code before}/{@code after} are the stored JSON texts, verbatim: the page shows them and the
 * CSV export flattens them to one line.
 */
public record AuditView(
    long id,
    String action,
    String actionLabel,
    String actor,
    String objectType,
    String objectId,
    String objectLabel,
    String summary,
    String reason,
    String before,
    String after,
    String traceId,
    Instant createdAt) {
}