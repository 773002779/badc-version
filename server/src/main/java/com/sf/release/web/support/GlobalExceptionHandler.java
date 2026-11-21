package com.sf.release.web.support;

import com.sf.release.common.ApiResponse;
import com.sf.release.common.BizError;
import com.sf.release.common.BizException;
import com.sf.release.common.Masker;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.lang.Nullable;
import org.springframework.validation.FieldError;
import org.springframework.validation.method.MethodValidationException;
import org.springframework.validation.method.MethodValidationResult;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Maps exceptions to the unified {@link ApiResponse} envelope.
 *
 * <p>Business and client input errors keep HTTP 200 with a body code; framework 4xx
 * errors keep their HTTP status; only genuinely unexpected errors become HTTP 500/50000.
 * Client-caused messages are logged masked and without a stack trace.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final int SYSTEM_ERROR = 50000;

    @ExceptionHandler(BizException.class)
    public ResponseEntity<Object> handleBiz(BizException ex) {
        return ResponseEntity.ok()
            .body(ApiResponse.fail(ex.getError(), ex.getMessage(), ex.getPayload()));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Object> handleConstraintViolation(ConstraintViolationException ex) {
        Map<String, String> details = new LinkedHashMap<>();
        for (ConstraintViolation<?> violation : ex.getConstraintViolations()) {
            details.put(violation.getPropertyPath().toString(), violation.getMessage());
        }
        return clientError(details, ex);
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status,
            WebRequest request) {
        Map<String, String> details = new LinkedHashMap<>();
        for (FieldError fieldError : ex.getBindingResult().getFieldErrors()) {
            details.put(fieldError.getField(), fieldError.getDefaultMessage());
        }
        return clientError(details, ex);
    }

    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(
            HandlerMethodValidationException ex, HttpHeaders headers, HttpStatusCode status,
            WebRequest request) {
        return clientError(validationDetails(ex), ex);
    }

    @Override
    protected ResponseEntity<Object> handleMethodValidationException(
            MethodValidationException ex, HttpHeaders headers, HttpStatus status, WebRequest request) {
        return clientError(validationDetails(ex), ex);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(
            HttpMessageNotReadableException ex, HttpHeaders headers, HttpStatusCode status,
            WebRequest request) {
        logClientError(ex);
        return ResponseEntity.ok()
            .body(envelope(BizError.PARAM_INVALID.getCode(), BizError.PARAM_INVALID.getMessage(),
                Map.of("body", "请求体格式错误")));
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception ex, @Nullable Object body, HttpHeaders headers, HttpStatusCode statusCode,
            WebRequest request) {
        if (statusCode.is5xxServerError()) {
            log.error("Unhandled framework error, traceId={}", traceId(), ex);
            return ResponseEntity.status(statusCode).headers(headers)
                .body(envelope(SYSTEM_ERROR, "系统内部错误", null));
        }
        logClientError(ex);
        return ResponseEntity.status(statusCode).headers(headers)
            .body(envelope(statusCode.value() * 100, messageFor(statusCode), null));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Object> handleUnexpected(Exception ex) {
        log.error("Unhandled exception, traceId={}", traceId(), ex);
        return ResponseEntity.internalServerError()
            .body(envelope(SYSTEM_ERROR, "系统内部错误", null));
    }

    private static ResponseEntity<Object> clientError(Map<String, String> details, Exception ex) {
        logClientError(ex);
        Map<String, String> data = details.isEmpty() ? Map.of("parameter", "invalid") : details;
        return ResponseEntity.ok()
            .body(envelope(BizError.PARAM_INVALID.getCode(), BizError.PARAM_INVALID.getMessage(), data));
    }

    private static Map<String, String> validationDetails(MethodValidationResult result) {
        Map<String, String> details = new LinkedHashMap<>();
        for (ParameterValidationResult parameterResult : result.getAllValidationResults()) {
            String field = parameterResult.getMethodParameter().getParameterName();
            if (field == null || field.isBlank()) {
                field = "parameter";
            }
            for (MessageSourceResolvable error : parameterResult.getResolvableErrors()) {
                details.put(field, error.getDefaultMessage());
            }
        }
        return details;
    }

    private static String messageFor(HttpStatusCode status) {
        return switch (status.value()) {
            case 400 -> BizError.PARAM_INVALID.getMessage();
            case 404 -> "资源不存在";
            case 405 -> "请求方法不支持";
            case 406 -> "响应格式不支持";
            case 415 -> "不支持的媒体类型";
            default -> "请求不合法";
        };
    }

    private static void logClientError(Exception ex) {
        log.warn("Client error, traceId={}, type={}, message={}",
            traceId(), ex.getClass().getSimpleName(), Masker.mask(ex.getMessage()));
    }

    private static ApiResponse<Object> envelope(int code, String message, Object data) {
        return new ApiResponse<>(code, message, data, traceId());
    }

    private static String traceId() {
        String traceId = MDC.get("traceId");
        return traceId == null || traceId.isBlank() ? UUID.randomUUID().toString() : traceId;
    }
}