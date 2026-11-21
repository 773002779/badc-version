package com.sf.release.common.security;

import java.util.Set;

/**
 * Platform role names. These strings are the keys of the configurable
 * {@code role_permission} mapping and must not be changed.
 */
public final class Roles {

    public static final String DEV = "DEV";
    public static final String TEST = "TEST";
    public static final String OPS = "OPS";
    public static final String OPS_LEAD = "OPS_LEAD";
    public static final String DEPT_MANAGER = "DEPT_MANAGER";
    public static final String PRODUCT = "PRODUCT";
    public static final String OWNER = "OWNER";
    public static final String ADMIN = "ADMIN";

    public static final Set<String> ALL = Set.of(
        DEV, TEST, OPS, OPS_LEAD, DEPT_MANAGER, PRODUCT, OWNER, ADMIN);

    private Roles() {
    }
}
