package com.sf.release.common.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a handler that is reachable without the platform identity header.
 *
 * <p>{@code PermissionInterceptor} skips user resolution, the {@code @RequirePerm} check
 * and {@code UserContext} population for such handlers. Consequently a {@code @Public}
 * endpoint <strong>must authenticate itself</strong>: the planned CODING webhook will
 * verify its HMAC signature in Task 14. Do not use this annotation for anything a normal
 * platform user could also call; calling {@code UserContext.current()} inside a
 * {@code @Public} handler fails closed with {@code FORBIDDEN}.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Public {
}
