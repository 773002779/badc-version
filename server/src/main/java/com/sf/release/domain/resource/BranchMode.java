package com.sf.release.domain.resource;

/**
 * Branching strategy of a unit. {@code STANDARD} follows one branch per release;
 * {@code COMPATIBLE} supports a compatibility branch next to the main line.
 */
public enum BranchMode {
    STANDARD,
    COMPATIBLE
}