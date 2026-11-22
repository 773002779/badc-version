package com.sf.release.domain.freeze;

import java.util.Locale;

/**
 * The scope of one {@link FreezeEvent} (tech design 4.2): {@link #GLOBAL} freezes production for
 * every system, {@link #SUBSYSTEM} for one system only. The names are part of the API contract.
 */
public enum FreezeScope {
    GLOBAL,
    SUBSYSTEM;

    /** Chinese label for the page; the enum name is the API value. */
    public String label() {
        return switch (this) {
            case GLOBAL -> "全局";
            case SUBSYSTEM -> "指定系统";
        };
    }

    /**
     * Parses the API word, case-insensitively.
     *
     * @return the scope, or {@code null} when the word is blank or unknown (the application
     *     service turns that into a 40000 naming the field)
     */
    public static FreezeScope parse(String raw) {
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