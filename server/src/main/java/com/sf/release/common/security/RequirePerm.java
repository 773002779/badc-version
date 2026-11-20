package com.sf.release.common.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares the permission point a handler method requires, for example
 * {@code @RequirePerm("release:publish")}.
 *
 * <p>The annotation lives in {@code common} (not {@code infra}) so controllers in
 * {@code web} can use it without violating the "web must not depend on infra" rule.
 * {@code PermissionInterceptor} lives in {@code infra/security} and reads it by reflection.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RequirePerm {

    /** Exact permission point from the permission catalogue, e.g. {@code gate:test-pass}. */
    String value();
}
