package com.sf.release.infra.security;

import java.util.Set;

/**
 * The authenticated caller of the current request: identity, platform roles, resolved
 * permission points and the deployment units they may touch.
 *
 * <p>{@code scopeUnitIds} is the explicit unit whitelist. An empty set means "no unit
 * scope configured" and is treated as unrestricted until Task 5 introduces the
 * unit-scoped tables; a non-empty set restricts the caller to exactly those units.
 */
public record CurrentUser(String userId, Set<String> roles, Set<String> perms, Set<Long> scopeUnitIds) {

    public CurrentUser {
        roles = roles == null ? Set.of() : Set.copyOf(roles);
        perms = perms == null ? Set.of() : Set.copyOf(perms);
        scopeUnitIds = scopeUnitIds == null ? Set.of() : Set.copyOf(scopeUnitIds);
    }

    public boolean hasRole(String role) {
        return roles.contains(role);
    }

    public boolean hasPerm(String perm) {
        return perms.contains(perm);
    }
}
