package com.sf.release.web.support;

import com.sf.release.common.ApiResponse;
import com.sf.release.common.BizError;
import com.sf.release.common.BizException;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Valid;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Set;

/**
 * Test-only controller that exercises the global error handling paths.
 */
@RestController
@RequestMapping("/dummy")
public class DummyController {

    @GetMapping("/state")
    public ApiResponse<Void> state() {
        throw new BizException(BizError.STATE_NOT_ALLOWED, "状态不允许该操作",
            Map.of("currentStatus", "DONE"));
    }

    @GetMapping("/boom")
    public ApiResponse<Void> boom() {
        throw new IllegalStateException("boom");
    }

    @PostMapping("/validate")
    public ApiResponse<String> validate(@Valid @RequestBody DummyRequest request) {
        return ApiResponse.ok(request.name());
    }

    @GetMapping("/param")
    public ApiResponse<String> param(@RequestParam @NotBlank(message = "name required") String name) {
        return ApiResponse.ok(name);
    }

    @GetMapping("/constraint")
    public ApiResponse<String> constraint(@RequestParam String name) {
        Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
        Set<ConstraintViolation<ConstraintProbe>> violations =
            validator.validateValue(ConstraintProbe.class, "name", name);
        if (!violations.isEmpty()) {
            throw new ConstraintViolationException(violations);
        }
        return ApiResponse.ok(name);
    }

    @GetMapping("/read-error")
    public ApiResponse<Void> readError() {
        throw new HttpMessageNotReadableException("Malformed body: {\"password\":\"super-secret\"}");
    }

    public record DummyRequest(@NotBlank(message = "name must not be blank") String name) {
    }

    public static class ConstraintProbe {
        @NotBlank(message = "name required")
        private String name;
    }
}