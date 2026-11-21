package com.sf.release.common;

import org.slf4j.MDC;

import java.util.UUID;

/**
 * Unified API envelope: {@code {code, message, data, traceId}}.
 *
 * <p>{@code code == 0} means success; any other value is a {@link BizError} (40000-40099)
 * or a system error (50000+). Every instance carries the request trace id.
 */
public record ApiResponse<T>(int code, String message, T data, String traceId) {

    public static final int OK = 0;

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(OK, "success", data, currentTraceId());
    }

    public static ApiResponse<Object> fail(BizError error, String message, Object payload) {
        String resolved = (message == null || message.isBlank()) ? error.getMessage() : message;
        return new ApiResponse<>(error.getCode(), resolved, payload, currentTraceId());
    }

    /**
     * Trace id placed in the MDC by {@code TraceIdFilter}. The fallback keeps the
     * field non-null for responses built outside a served request.
     */
    private static String currentTraceId() {
        String traceId = MDC.get("traceId");
        return traceId == null ? UUID.randomUUID().toString() : traceId;
    }
}