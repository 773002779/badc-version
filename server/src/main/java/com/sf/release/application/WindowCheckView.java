package com.sf.release.application;

/**
 * The answer of {@code POST /api/v1/windows/check} (and of every admission window evaluation).
 *
 * <p>The two flags keep the two "allowed anyway" cases apart, exactly as {@code WindowCheck}
 * documents them: {@code configured = false} means no window exists and {@code inWindow} is false
 * (an unconfigured platform still needs approval); {@code configured = true, enabled = false} means
 * the constraint was switched off and {@code inWindow} is true without any span. When
 * {@code enabled} is true, {@code span} is the span containing the evaluated instant (null when
 * outside) and {@code next} the following span (null when none opens within seven days).
 *
 * <p>{@code windowId}, {@code timezone}, {@code scope} and {@code inherited} describe <em>which</em>
 * window decided: the page can say "系统覆盖的窗口（Asia/Shanghai）" instead of implying the
 * subsystem has rules of its own. All four are null/false when nothing is configured.
 */
public record WindowCheckView(boolean inWindow, WindowSpanView span, WindowSpanView next,
                              boolean configured, Long windowId, boolean enabled, String timezone,
                              String scope, boolean inherited) {
}