package com.sf.release.infra.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sf.release.application.notification.NotificationSender;
import com.sf.release.infra.notification.WeComNotificationSender;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the notification channel adapters (P4-B). The sender does no I/O at construction, so the
 * application boots with the WeCom channel switched off (the seeded {@code notification_setting}
 * row) and no network dependency.
 *
 * <p>{@code release.notification.allow-insecure-http} exists for the same reason as the engine's
 * switch: an internal robot (or a local MockWebServer in the suite) may speak plain HTTP, while a real
 * deployment keeps the {@code https://} requirement.
 */
@Configuration(proxyBeanMethods = false)
public class NotificationConfig {

    @Bean
    public NotificationSender notificationSender(
        ObjectMapper objectMapper,
        @Value("${release.notification.allow-insecure-http:false}") boolean allowInsecureHttp) {
        return new WeComNotificationSender(objectMapper, allowInsecureHttp);
    }
}