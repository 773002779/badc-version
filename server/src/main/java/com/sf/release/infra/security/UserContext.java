package com.sf.release.infra.security;

import com.sf.release.common.BizError;
import com.sf.release.common.BizException;

/**
 * Request-scoped holder for the authenticated caller.
 *
 * <p>{@link PermissionInterceptor} sets the context after authentication/authorisation
 * and clears it in {@code afterCompletion}. Access from a thread without a context fails
 * closed with {@link BizError#FORBIDDEN}.
 */
public final class UserContext {

    private static final ThreadLocal<CurrentUser> CURRENT = new ThreadLocal<>();

    private UserContext() {
    }

    /** The current caller; never null. Throws {@code FORBIDDEN} when unauthenticated. */
    public static CurrentUser current() {
        CurrentUser user = CURRENT.get();
        if (user == null) {
            throw new BizException(BizError.FORBIDDEN, "未认证：缺少当前用户上下文");
        }
        return user;
    }

    static void set(CurrentUser user) {
        CURRENT.set(user);
    }

    static void clear() {
        CURRENT.remove();
    }
}
