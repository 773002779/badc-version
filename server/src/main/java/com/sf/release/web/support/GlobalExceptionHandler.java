package com.sf.release.web.support;

import com.sf.release.common.ApiResponse;
import com.sf.release.common.BizError;
import com.sf.release.common.BizException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Maps exceptions to the unified {@link ApiResponse} envelope.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BizException.class)
    public ResponseEntity<ApiResponse<Object>> handleBiz(BizException ex) {
        return ResponseEntity.ok(ApiResponse.fail(ex.getError(), ex.getMessage(), ex.getPayload()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Object>> handleValidation(MethodArgumentNotValidException ex) {
        Map<String, String> details = new LinkedHashMap<>();
        for (FieldError fieldError : ex.getBindingResult().getFieldErrors()) {
            details.put(fieldError.getField(), fieldError.getDefaultMessage());
        }
        return ResponseEntity.ok(
            ApiResponse.fail(BizError.PARAM_INVALID, BizError.PARAM_INVALID.getMessage(), details));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Object>> handleUnexpected(Exception ex) {
        String traceId = MDC.get("traceId");
        if (traceId == null || traceId.isBlank()) {
            traceId = UUID.randomUUID().toString();
        }
        log.error("Unhandled exception, traceId={}", traceId, ex);
        return ResponseEntity.internalServerError()
            .body(new ApiResponse<>(50000, "系统内部错误", null, traceId));
    }
}