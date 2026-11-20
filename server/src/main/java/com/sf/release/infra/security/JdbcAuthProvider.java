package com.sf.release.infra.security;

import com.sf.release.common.security.Roles;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Local-database {@link AuthProvider}: only {@code ACTIVE} users exist, roles come from
 * {@code user_role}, permission points from the configurable {@code role_permission}
 * mapping, and unit scope from {@code user_scope}.
 *
 * <p>{@code allUnits} is resolved from the {@code ADMIN} role (the bootstrap marker) or
 * from an explicit {@code scope_type = 'ALL'} row; ordinary users get only their
 * {@code UNIT} rows, and no rows means no unit access.
 */
public class JdbcAuthProvider implements AuthProvider {

    private static final String ACTIVE = "ACTIVE";

    private final JdbcTemplate jdbc;

    public JdbcAuthProvider(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<CurrentUser> authenticate(String userId) {
        if (userId == null || userId.isBlank()) {
            return Optional.empty();
        }
        List<String> active = jdbc.queryForList(
            "SELECT id FROM app_user WHERE id = ? AND status = ?", String.class, userId, ACTIVE);
        if (active.isEmpty()) {
            return Optional.empty();
        }
        Set<String> roles = new LinkedHashSet<>(jdbc.queryForList(
            "SELECT role FROM user_role WHERE user_id = ?", String.class, userId));
        Set<String> perms = new LinkedHashSet<>(jdbc.queryForList(
            "SELECT DISTINCT rp.perm FROM user_role ur"
                + " JOIN role_permission rp ON rp.role = ur.role"
                + " WHERE ur.user_id = ?", String.class, userId));
        boolean allUnits = roles.contains(Roles.ADMIN) || hasAllScopeRow(userId);
        Set<Long> scopeUnitIds = new LinkedHashSet<>(jdbc.queryForList(
            "SELECT scope_id FROM user_scope WHERE user_id = ? AND scope_type = 'UNIT'",
            Long.class, userId));
        return Optional.of(new CurrentUser(userId, roles, perms, scopeUnitIds, allUnits));
    }

    private boolean hasAllScopeRow(String userId) {
        return !jdbc.queryForList(
            "SELECT scope_id FROM user_scope WHERE user_id = ? AND scope_type = 'ALL'",
            Long.class, userId).isEmpty();
    }
}
