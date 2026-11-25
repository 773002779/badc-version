package com.sf.release.infra.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * The application clock. Waiting times ("已等待 N 小时") must be computed from one injectable source
 * instead of scattered {@code Instant.now()} calls, so a test can freeze time and assert an exact
 * value. The bean is the system clock in production; the 待办 query service takes it as a constructor
 * dependency.
 */
@Configuration(proxyBeanMethods = false)
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemDefaultZone();
    }
}