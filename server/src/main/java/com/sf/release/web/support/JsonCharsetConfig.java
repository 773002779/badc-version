package com.sf.release.web.support;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.AbstractJackson2HttpMessageConverter;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Declares the charset of JSON responses explicitly.
 *
 * <p>Spring Boot's Jackson converter answers {@code application/json} without a charset. Browsers
 * and axios decode JSON as UTF-8 by default, but every other client has to guess: PowerShell's
 * {@code Invoke-RestMethod}, for one, falls back to ISO-8859-1 and renders Chinese field values as
 * mojibake even though the bytes on the wire are correct. Adding {@code charset=UTF-8} removes the
 * guess without changing the payload.
 *
 * <p>This is a presentation concern only: no controller sets an encoding and the JSON body is
 * always UTF-8 already.
 */
@Configuration(proxyBeanMethods = false)
public class JsonCharsetConfig {

    @Bean
    public WebMvcConfigurer utf8JsonContentType() {
        return new WebMvcConfigurer() {
            @Override
            public void extendMessageConverters(List<HttpMessageConverter<?>> converters) {
                for (HttpMessageConverter<?> converter : converters) {
                    if (converter instanceof AbstractJackson2HttpMessageConverter jackson) {
                        jackson.setDefaultCharset(StandardCharsets.UTF_8);
                        // Keep the JSON media type: adding a charset must not turn the response
                        // into text/plain for clients that negotiate strictly.
                        jackson.setSupportedMediaTypes(List.of(MediaType.APPLICATION_JSON));
                    }
                }
            }
        };
    }
}