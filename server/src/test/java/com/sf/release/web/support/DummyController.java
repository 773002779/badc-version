package com.sf.release.web.support;

import com.sf.release.common.ApiResponse;
import com.sf.release.common.BizError;
import com.sf.release.common.BizException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

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

    public record DummyRequest(@NotBlank(message = "name must not be blank") String name) {
    }
}