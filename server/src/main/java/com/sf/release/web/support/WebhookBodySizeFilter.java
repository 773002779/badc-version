package com.sf.release.web.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sf.release.common.ApiResponse;
import com.sf.release.common.BizError;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Bound on the unauthenticated webhook body (Task 14 review, important 4).
 *
 * <p>The webhook endpoint is {@code @Public}: identity resolution is skipped, so an attacker
 * can send an arbitrarily large body before any HMAC check happens. This filter runs in front
 * of the MVC handler and rejects a body above {@value #MAX_BODY_BYTES} bytes with HTTP 413
 * <em>before the body is read</em>:
 * <ul>
 *   <li>a declared {@code Content-Length} above the limit is rejected without touching the
 *       stream;</li>
 *   <li>a body without a usable {@code Content-Length} (chunked) is wrapped in a limiting
 *       stream that aborts with 413 as soon as the limit is crossed, so the request is not
 *       buffered past the cap.</li>
 * </ul>
 *
 * <p>Only {@value #WEBHOOK_PATH} is filtered; other endpoints keep their own limits. The 413
 * body uses the unified envelope and is written without invoking the controller, so no HMAC
 * computation or database write happens for an oversized request.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class WebhookBodySizeFilter extends OncePerRequestFilter {

    /** Hard cap of a webhook body: CODING status callbacks are small. */
    public static final int MAX_BODY_BYTES = 64 * 1024;

    /** Path of the public webhook. */
    public static final String WEBHOOK_PATH = "/api/v1/hooks/coding";

    private static final Logger log = LoggerFactory.getLogger(WebhookBodySizeFilter.class);

    private final ObjectMapper json;

    public WebhookBodySizeFilter(ObjectMapper json) {
        this.json = json;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (!WEBHOOK_PATH.equals(request.getRequestURI())) {
            chain.doFilter(request, response);
            return;
        }
        long declared = request.getContentLengthLong();
        if (declared > MAX_BODY_BYTES) {
            log.warn("webhook body rejected before authentication: contentLength={} > {}",
                declared, MAX_BODY_BYTES);
            reject(response);
            return;
        }
        if (declared >= 0) {
            chain.doFilter(request, response);
            return;
        }
        try {
            chain.doFilter(new LimitedBodyRequest(request), response);
        } catch (BodyTooLargeException ex) {
            log.warn("webhook chunked body rejected after {} bytes", MAX_BODY_BYTES);
            reject(response);
        }
    }

    private void reject(HttpServletResponse response) throws IOException {
        if (response.isCommitted()) {
            log.warn("webhook body rejected but the response was already committed");
            return;
        }
        response.resetBuffer();
        response.setStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        json.writeValue(response.getWriter(), ApiResponse.fail(BizError.PARAM_INVALID,
            "请求体超过 " + MAX_BODY_BYTES + " 字节限制", Map.of("maxBytes", MAX_BODY_BYTES)));
    }

    /** Wraps the request so its stream stops the read at {@link #MAX_BODY_BYTES}. */
    private static final class LimitedBodyRequest extends HttpServletRequestWrapper {

        private LimitedBodyRequest(HttpServletRequest request) {
            super(request);
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            return new LimitedServletInputStream(super.getInputStream(), MAX_BODY_BYTES);
        }
    }

    /** A stream that throws {@link BodyTooLargeException} once the cap is crossed. */
    private static final class LimitedServletInputStream extends ServletInputStream {

        private final ServletInputStream delegate;
        private final int limit;
        private int count;

        private LimitedServletInputStream(ServletInputStream delegate, int limit) {
            this.delegate = delegate;
            this.limit = limit;
        }

        @Override
        public int read() throws IOException {
            int value = delegate.read();
            if (value >= 0) {
                counted(1);
            }
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int read = delegate.read(buffer, offset, length);
            if (read > 0) {
                counted(read);
            }
            return read;
        }

        private void counted(int bytes) {
            count += bytes;
            if (count > limit) {
                throw new BodyTooLargeException();
            }
        }

        @Override
        public boolean isFinished() {
            return delegate.isFinished();
        }

        @Override
        public boolean isReady() {
            return delegate.isReady();
        }

        @Override
        public void setReadListener(ReadListener listener) {
            delegate.setReadListener(listener);
        }
    }

    /** Internal marker; converted to 413 by the filter, never reaches the MVC layer. */
    private static final class BodyTooLargeException extends RuntimeException {
    }
}