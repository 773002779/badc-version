package com.sf.release.infra.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Wires the security beans and registers {@link PermissionInterceptor} for every MVC
 * handler except the container error dispatch.
 *
 * <p>This is a plain {@code @Configuration} with {@code @Bean} methods on purpose: a
 * {@code @Configuration} class is not part of a {@code @WebMvcTest} slice, so the
 * Task 2 error-handling slice keeps working without a database-backed auth provider.
 */
@Configuration(proxyBeanMethods = false)
public class SecurityWebConfig {

    @Bean
    public AuthProvider authProvider(JdbcTemplate jdbcTemplate) {
        return new JdbcAuthProvider(jdbcTemplate);
    }

    @Bean
    public PermissionInterceptor permissionInterceptor(AuthProvider authProvider) {
        return new PermissionInterceptor(authProvider);
    }

    @Bean
    public WebMvcConfigurer securityWebMvcConfigurer(PermissionInterceptor permissionInterceptor) {
        return new WebMvcConfigurer() {
            @Override
            public void addInterceptors(InterceptorRegistry registry) {
                registry.addInterceptor(permissionInterceptor)
                    .addPathPatterns("/**")
                    .excludePathPatterns("/error");
            }
        };
    }
}
