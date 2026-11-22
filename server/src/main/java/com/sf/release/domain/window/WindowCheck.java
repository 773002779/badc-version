package com.sf.release.domain.window;

/**
 * The answer of one window evaluation, with the two flags that keep "no window configured" and
 * "the window is switched off" apart (see {@link ReleaseWindow}).
 *
 * <ul>
 *   <li>{@code configured = false} — no window exists; {@code inWindow} is false as well, because
 *       an unconfigured platform still requires approval;</li>
 *   <li>{@code configured = true, enabled = false} — the constraint is switched off, so
 *       {@code inWindow} is always true and {@code span}/{@code next} are empty;</li>
 *   <li>{@code configured = true, enabled = true} — the rules decided; {@code span} is the span
 *       containing the evaluated instant (null when outside) and {@code next} the following span
 *       (null when none opens within the search horizon).</li>
 * </ul>
 *
 * <p>The canonical shape names the four fields of the requirements; {@code enabled} is the extra
 * flag that lets a page say <em>why</em> an instant is inside. The four-argument constructor keeps
 * older callers source compatible and treats "configured" as "enabled".
 */
public record WindowCheck(boolean inWindow, WindowSpan span, WindowSpan next, boolean configured,
                          boolean enabled) {

    public WindowCheck(boolean inWindow, WindowSpan span, WindowSpan next, boolean configured) {
        this(inWindow, span, next, configured, configured);
    }

    /** Nothing configured: outside the window, never exempt. */
    public static WindowCheck notConfigured() {
        return new WindowCheck(false, null, null, false, false);
    }

    /** A configured window whose constraint is switched off: always inside, no spans. */
    public static WindowCheck constraintOff() {
        return new WindowCheck(true, null, null, true, false);
    }
}