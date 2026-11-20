package com.sf.release.common.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a handler whose data access must stay inside the caller's data scope.
 *
 * <p>Phase 1 enforces the contract imperatively: the handler calls
 * {@code ScopeGuard.assertUnitInScope(unitId)} before touching unit-scoped data. The
 * SQL-rewriting part of the design ({@code unit_id IN (...)}) is intentionally deferred
 * to Task 5: no table has a {@code unit_id} column yet, so a MyBatis interceptor would be
 * neither verifiable nor testable today. Task 5 will consume this marker.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface DataScope {
}
