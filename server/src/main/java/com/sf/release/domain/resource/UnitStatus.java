package com.sf.release.domain.resource;

/**
 * Lifecycle of a deployment unit. {@code INACTIVE} units stay for history and are hidden
 * from the selectable list of new releases.
 */
public enum UnitStatus {
    ACTIVE,
    INACTIVE
}