package com.sf.release.infra.security;

import com.sf.release.common.BizError;
import com.sf.release.common.BizException;

import java.util.Map;

/**
 * Imperative data-scope guard. Phase 1 has no unit-scoped tables yet, so services call
 * this method before reading or mutating deployment-unit data; Task 5 adds the MyBatis
 * interceptor that injects {@code unit_id IN (...)} for {@code @DataScope} queries.
 *
 * <p><strong>Fail-closed:</strong> only a caller with {@link CurrentUser#allUnits()} may
 * access an arbitrary unit. Otherwise the unit must be present in the configured
 * whitelist; an empty whitelist denies every unit. Rejections use
 * {@link BizError#FORBIDDEN} (40009).
 */
public final class ScopeGuard {

    private ScopeGuard() {
    }

    public static void assertUnitInScope(long unitId) {
        CurrentUser user = UserContext.current();
        if (user.allUnits()) {
            return;
        }
        if (!user.scopeUnitIds().contains(unitId)) {
            throw new BizException(BizError.FORBIDDEN, "无权访问该部署单元", Map.of("unitId", unitId));
        }
    }
}
