package com.sf.release.web.controller;

import com.sf.release.application.LibAppService;
import com.sf.release.application.LibAppService.DependentsView;
import com.sf.release.common.ApiResponse;
import com.sf.release.common.security.DataScope;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Second-party library endpoints (P6-A design §4).
 *
 * <p>{@code GET /api/v1/libs/{unitId}/dependents} returns the documented module-path heuristic
 * dependents of a library: {@code {libUnitId, artifactId, source, dependents:[...]}}. The caller
 * must be authenticated (the {@code PermissionInterceptor}) and the library must be inside the
 * caller's data scope (enforced through {@link LibAppService}); a non-LIB unit is 40001.
 *
 * <p>The web layer passes enum values as strings only: enums stay in the application/domain
 * layers (ArchUnit rule of the slice).
 */
@RestController
@RequestMapping("/api/v1/libs")
public class LibController {

    private final LibAppService libAppService;

    public LibController(LibAppService libAppService) {
        this.libAppService = libAppService;
    }

    /** The deployment units that depend on the library through {@code module_path}. */
    @DataScope
    @GetMapping("/{unitId}/dependents")
    public ApiResponse<DependentsView> dependents(@PathVariable long unitId) {
        return ApiResponse.ok(libAppService.dependents(unitId));
    }
}