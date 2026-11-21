package com.sf.release.infra.security;

import java.util.Arrays;
import java.util.Set;

/**
 * Test-only seam to install a {@link CurrentUser} without going through the HTTP
 * interceptor. {@link UserContext#set} stays package-private on purpose, so this helper
 * lives in the same package instead of widening the production API.
 */
public final class TestUserContext {

    private TestUserContext() {
    }

    /** An ADMIN-like caller allowed to touch every deployment unit. */
    public static void allUnits(String userId) {
        UserContext.set(new CurrentUser(userId, Set.of("ADMIN"), Set.of(), Set.of(), true));
    }

    /** A caller restricted to exactly the given deployment unit ids. */
    public static void scoped(String userId, long... unitIds) {
        Set<Long> ids = Arrays.stream(unitIds).boxed().collect(java.util.stream.Collectors.toSet());
        UserContext.set(new CurrentUser(userId, Set.of(), Set.of(), ids, false));
    }

    /** A caller with no unit scope at all, which must be denied every unit. */
    public static void noUnits(String userId) {
        UserContext.set(new CurrentUser(userId, Set.of(), Set.of(), Set.of(), false));
    }

    /** A caller with explicit roles/permissions, unrestricted on units (for service-level tests). */
    public static void as(String userId, Set<String> roles, Set<String> perms) {
        UserContext.set(new CurrentUser(userId, roles, perms, Set.of(), true));
    }

    public static void clear() {
        UserContext.clear();
    }
}