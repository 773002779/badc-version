package com.sf.release.domain.resource;

/**
 * Kind of deployable resource. {@code UNIT} is a deployable service, {@code LIB} is a
 * second-party library that shares the unit shape but has no deployment or environment
 * configuration (FR-R-03).
 */
public enum UnitType {
    UNIT,
    LIB
}