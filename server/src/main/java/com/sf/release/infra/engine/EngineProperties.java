package com.sf.release.infra.engine;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Security switches for outbound engine calls.
 *
 * <p>CODING calls must use HTTPS. The only escape hatch is
 * {@code release.engine.allow-insecure-http=true}, intended for an internal test tenant or
 * a local MockWebServer; it is {@code false} by default and must never be enabled for a
 * production connection.
 */
@Component
@ConfigurationProperties(prefix = "release.engine")
public class EngineProperties {

    private boolean allowInsecureHttp;

    public boolean isAllowInsecureHttp() {
        return allowInsecureHttp;
    }

    public void setAllowInsecureHttp(boolean allowInsecureHttp) {
        this.allowInsecureHttp = allowInsecureHttp;
    }
}