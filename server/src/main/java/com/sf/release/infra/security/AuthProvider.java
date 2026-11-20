package com.sf.release.infra.security;

import java.util.Optional;

/**
 * Authentication seam. Phase 1 resolves the temporary {@code X-User-Id} header against
 * the local {@code app_user} table; a future SSO provider can replace the bean without
 * changing {@link PermissionInterceptor} or the annotations.
 */
public interface AuthProvider {

    /**
     * Resolves an identity to its roles, permission points and data scope.
     *
     * @return the caller, or empty when the user is unknown or not {@code ACTIVE}
     */
    Optional<CurrentUser> authenticate(String userId);
}
