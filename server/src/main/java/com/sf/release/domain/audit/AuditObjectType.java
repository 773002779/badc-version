package com.sf.release.domain.audit;

import java.util.Locale;

/**
 * The kind of object an audit row is about (audit design section 2).
 *
 * <p>The stored value is the enum name; the Chinese label is only for display, so the web layer
 * never keeps its own copy of the vocabulary. {@link #parse(String)} is the lenient seam used by the
 * query service: an unknown value answers {@code null} and the caller turns that into 40000.
 *
 * <p>{@code TASK}, {@code ROLLBACK} and {@code SETTING} extend the design's example list
 * ({@code ORDER / ITEM / UNIT / SUBSYSTEM / FREEZE / WAIVER / SETTING}): a task and a rollback are
 * real objects of this platform, and every platform-level configuration (CODING connection,
 * notification block, release window) is recorded as {@code SETTING} with a scope-qualified id.
 */
public enum AuditObjectType {

    ORDER("发布单"),
    ITEM("变更项"),
    UNIT("部署单元"),
    SUBSYSTEM("系统"),
    TASK("发布任务"),
    FREEZE("生产封板"),
    WAIVER("检查豁免"),
    ROLLBACK("生产回滚"),
    SETTING("平台设置");

    private final String label;

    AuditObjectType(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /**
     * The object type named by {@code raw}, or {@code null} when the value is blank or unknown.
     * Matching is case-insensitive so {@code order} is the same type as {@code ORDER}.
     */
    public static AuditObjectType parse(String raw) {
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