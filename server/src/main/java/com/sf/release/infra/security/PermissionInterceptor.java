package com.sf.release.infra.security;

import com.sf.release.common.BizError;
import com.sf.release.common.BizException;
import com.sf.release.common.security.Permissions;
import com.sf.release.common.security.Public;
import com.sf.release.common.security.RequirePerm;
import com.sf.release.common.security.Roles;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.lang.Nullable;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Map;
import java.util.Set;

/**
 * Authenticates every MVC request from the temporary {@code X-User-Id} header and
 * enforces {@link RequirePerm}, except handlers marked {@link Public} which skip identity
 * resolution entirely and must authenticate themselves (HMAC webhook, Task 14).
 *
 * <p>Failures throw {@link BizException} with {@link BizError#FORBIDDEN} (40009) and are
 * rendered by the existing {@code GlobalExceptionHandler}; the interceptor does not build
 * its own response. The user context is populated only after authentication and
 * authorisation succeed, so a rejected request never leaves a thread-local behind;
 * {@link #afterCompletion} clears it for every request that passed.
 */
public class PermissionInterceptor implements HandlerInterceptor {

    public static final String USER_HEADER = "X-User-Id";

    /**
     * C20: operations and operations leads may never initiate a code version release,
     * even when the (configurable) role mapping grants the permission.
     */
    private static final Set<String> CODE_RELEASE_BLOCKED_ROLES = Set.of(Roles.OPS, Roles.OPS_LEAD);

    private final AuthProvider authProvider;

    public PermissionInterceptor(AuthProvider authProvider) {
        this.authProvider = authProvider;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod handlerMethod)) {
            // Static resources, 404s and preflight requests have no protected handler method.
            return true;
        }
        if (handlerMethod.getMethodAnnotation(Public.class) != null) {
            // Machine-to-machine endpoints authenticate themselves (e.g. the CODING
            // webhook verifies its HMAC signature in Task 14); no user context is set.
            return true;
        }

        CurrentUser user = authenticate(request);
        RequirePerm requirePerm = handlerMethod.getMethodAnnotation(RequirePerm.class);
        if (requirePerm != null) {
            assertPermitted(user, requirePerm.value());
        }
        UserContext.set(user);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, @Nullable Exception ex) {
        UserContext.clear();
    }

    private CurrentUser authenticate(HttpServletRequest request) {
        String userId = request.getHeader(USER_HEADER);
        if (userId == null || userId.isBlank()) {
            throw new BizException(BizError.FORBIDDEN, "缺少 " + USER_HEADER + " 请求头");
        }
        return authProvider.authenticate(userId.trim())
            .orElseThrow(() -> new BizException(BizError.FORBIDDEN, "用户不存在或已停用"));
    }

    private static void assertPermitted(CurrentUser user, String perm) {
        if (Permissions.RELEASE_PUBLISH.equals(perm)
                && user.roles().stream().anyMatch(CODE_RELEASE_BLOCKED_ROLES::contains)) {
            throw new BizException(BizError.FORBIDDEN,
                "运维角色不得发起代码版本发布", Map.of("perm", perm));
        }
        if (!user.hasPerm(perm)) {
            throw new BizException(BizError.FORBIDDEN, "缺少权限点：" + perm, Map.of("perm", perm));
        }
    }
}
