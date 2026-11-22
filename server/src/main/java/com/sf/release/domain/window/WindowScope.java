package com.sf.release.domain.window;

/**
 * The scope of a {@link ReleaseWindow}: the platform-wide default, or the override of one
 * subsystem (系统). The names are part of the API contract.
 */
public enum WindowScope {
    GLOBAL,
    SUBSYSTEM
}