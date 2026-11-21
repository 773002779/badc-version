package com.sf.release.domain.resource;

/**
 * Scope of a CODING connection. A {@code GLOBAL} connection is the tenant-wide default;
 * a {@code SUBSYSTEM} connection overrides it for one subsystem (for example when a
 * subsystem lives in a different CODING team or project).
 *
 * <p>The database stores a GLOBAL row with the sentinel {@code subsystem_id = 0} because
 * the column participates in {@code UNIQUE (scope, subsystem_id)} and MySQL treats NULLs
 * as distinct inside a unique key.
 */
public enum CodingScope {

    GLOBAL,
    SUBSYSTEM
}
