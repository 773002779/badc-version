package com.sf.release.domain.resource;

/**
 * Lifecycle of a subsystem. {@code INACTIVE} subsystems stay in the list for history but
 * are no longer selectable for new releases; the stored value is the enum name, matching
 * the {@code app_user.status} vocabulary.
 */
public enum SubsystemStatus {
    ACTIVE,
    INACTIVE
}