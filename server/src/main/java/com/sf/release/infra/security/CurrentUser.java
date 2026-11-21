package com.sf.release.infra.security;

import java.util.Set;

/**
 * The authenticated caller of the current request: identity, platform roles, resolved
 * permission points, the deployment units they may touch and whether they are
 * unrestricted on units.
 *
 * <p><strong>Data scope is fail-closed:</strong> an empty {@code scopeUnitIds} means the
 * caller may touch no deployment unit. {@code allUnits} is the explicit unrestricted
 * marker, resolved by {@link JdbcAuthProvider} from the {@code ADMIN} role or from a
 * {@code user_scope} row of type {@code ALL}. Adding unit scope rows only ever narrows
 * access, never widens it.
 */
public record CurrentUser(String userId, Set<String> roles, Set<String> perms,
                          Set<Long> scopeUnitIds, boolean allUnits) {

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
