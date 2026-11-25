package com.sf.release.domain.pipeline;

import java.util.Locale;

/**
 * The batch order of the serial production deployment (P6-C design §1/§2).
 *
 * <p><strong>Forward is the default and does not touch the existing semantics.</strong>
 * {@code FORWARD} keeps each production-family task on its DE's {@code deploy_batch_no}, so the
 * handler runs batch 1, then 2, then 3 (C14).
 *
 * <p><strong>Reverse only renumbers a newly created instance.</strong> {@code REVERSE} writes the
 * production task's batch number as {@code maxBatch + 1 - originalBatch} — the same transformation
 * a rollback uses — so the handler's "lowest unfinished batch first" rule run the highest original
 * batch first. It never rewrites an existing instance's rows, never affects the two-node daily
 * pipeline (which has no batches) and is not read by the rollback factory at all; a rollback brings
 * its own reversed order.
 *
 * <p>The Chinese labels are here because {@link CheckType} also owns its display label: the web
 * layer must not import a domain enum, so the application view exposes both the machine name and
 * the label as strings.
 */
public enum BatchOrder {

    FORWARD("顺序"),
    REVERSE("逆序");

    private final String label;

    BatchOrder(String label) {
        this.label = label;
    }

    /** The Chinese operator-facing label, for example {@code 逆序}. */
    public String label() {
        return label;
    }

    /**
     * Resolves a persisted/requested value; an unknown or blank value yields {@code null} so the
     * caller can turn it into a 40000 (request) or the documented default (corrupted row).
     */
    public static BatchOrder parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}