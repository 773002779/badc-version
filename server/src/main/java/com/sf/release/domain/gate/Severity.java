package com.sf.release.domain.gate;

import java.util.Locale;

/**
 * Highest defect level one check reported (requirements FR-Q-02). The gate takes the highest
 * severity of the whole 发布单, not a per-check pass/fail, so this enum is the decision input.
 *
 * <p><strong>Disposition</strong>
 * <ul>
 *   <li>{@link #S0} / {@link #S1} (严重 / 高危): blocks and <em>cannot</em> be waived — the only
 *       way out is to fix the finding and rerun the check;</li>
 *   <li>{@link #S2} (中危): blocks, but an approved waiver of the same snapshot lifts it;</li>
 *   <li>{@link #S3} (低危): warns only and never blocks;</li>
 *   <li>{@link #NONE}: no finding.</li>
 * </ul>
 *
 * <p>{@link #rank()} is monotonic (NONE &lt; S3 &lt; S2 &lt; S1 &lt; S0) so {@code max} is the
 * natural comparator and a new level can be inserted without renumbering the persisted names.
 */
public enum Severity {
    NONE(0, "无问题"),
    S3(1, "低危"),
    S2(2, "中危"),
    S1(3, "高危"),
    S0(4, "严重");

    private final int rank;
    private final String label;

    Severity(int rank, String label) {
        this.rank = rank;
        this.label = label;
    }

    public int rank() {
        return rank;
    }

    /** Chinese label used by the API and the page; the enum name is the persisted value. */
    public String label() {
        return label;
    }

    /** True when this level blocks the check gate (中危 and above). */
    public boolean blocks() {
        return rank >= S2.rank;
    }

    /**
     * True when an approved waiver can lift this level. Only 中危 is waivable: 高危 and 严重 must
     * be fixed, and 低危 never blocked in the first place.
     */
    public boolean waivable() {
        return this == S2;
    }

    /** The higher of two severities; {@code null} is treated as {@link #NONE}. */
    public static Severity max(Severity left, Severity right) {
        Severity a = left == null ? NONE : left;
        Severity b = right == null ? NONE : right;
        return a.rank >= b.rank ? a : b;
    }

    /**
     * Parses a severity word from configuration or an engine response (case-insensitive).
     * An unrecognised word yields {@code null} so the caller can decide whether that is a
     * configuration error; the adapter maps vendor words to these names first.
     */
    public static Severity parse(String word) {
        if (word == null || word.isBlank()) {
            return null;
        }
        try {
            return valueOf(word.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}