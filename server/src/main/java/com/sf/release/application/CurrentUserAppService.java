package com.sf.release.application;

import com.sf.release.infra.security.CurrentUser;
import com.sf.release.infra.security.UserContext;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * The "who am I" read model: the identity, roles, permission points and data-scope flags of the
 * caller of the current request.
 *
 * <p><strong>Why the page needs it.</strong> Requirements FR-G-05 and the P2 plan forbid a hidden
 * action: a button the caller may not use must be <em>disabled with a reason</em>, not missing. Without
 * a permission read the page can only guess, so it would hide the button (the user never learns why
 * they cannot pass the test) or show it enabled and surface a 40009 after the click.
 *
 * <p><strong>Read-only and safe.</strong> It exposes exactly what the request interceptor already
 * resolved for the caller — no other user's data, no scope of somebody else. The permission list is
 * the effective one after the role mapping, which is what a UI decision must use.
 */
@Service
public class CurrentUserAppService {

    /** {@code GET /api/v1/me} body. */
    public record MeView(String userId, List<String> roles, List<String> perms, boolean allUnits,
                         List<Long> unitIds) {

        public MeView {
            roles = roles == null ? List.of() : List.copyOf(roles);
            perms = perms == null ? List.of() : List.copyOf(perms);
            unitIds = unitIds == null ? List.of() : List.copyOf(unitIds);
        }

        public boolean can(String perm) {
            return perms.contains(perm);
        }
    }

    /**
     * The current caller.
     *
     * @throws com.sf.release.common.BizException 40009 when the request has no authenticated user
     */
    public MeView me() {
        CurrentUser user = UserContext.current();
        return new MeView(user.userId(), new ArrayList<>(user.roles()),
            new ArrayList<>(user.perms()), user.allUnits(), new ArrayList<>(user.scopeUnitIds()));
    }
}