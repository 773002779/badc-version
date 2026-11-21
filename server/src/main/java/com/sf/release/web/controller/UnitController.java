package com.sf.release.web.controller;

import com.sf.release.application.UnitAppService;
import com.sf.release.application.UnitAppService.BindingCmd;
import com.sf.release.application.UnitAppService.CreateCmd;
import com.sf.release.application.UnitAppService.EnvDeCmd;
import com.sf.release.application.UnitAppService.EnvDeView;
import com.sf.release.application.UnitAppService.PageQuery;
import com.sf.release.application.UnitAppService.PageResult;
import com.sf.release.application.UnitAppService.UnitView;
import com.sf.release.application.UnitAppService.UpdateCmd;
import com.sf.release.common.ApiResponse;
import com.sf.release.common.security.DataScope;
import com.sf.release.common.security.Permissions;
import com.sf.release.common.security.RequirePerm;
import com.sf.release.web.dto.UnitDtos.AddEnvDeRequest;
import com.sf.release.web.dto.UnitDtos.BindCodingTasksRequest;
import com.sf.release.web.dto.UnitDtos.BindingRequest;
import com.sf.release.web.dto.UnitDtos.CreateUnitRequest;
import com.sf.release.web.dto.UnitDtos.UpdateUnitRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 部署单元管理 endpoints. Every handler is authenticated by {@code PermissionInterceptor};
 * mutations (unit config, CODING task bindings, DE config) additionally require
 * {@code setting:manage}.
 *
 * <p>{@link DataScope} marks the handlers that touch unit-scoped data. Phase 1 enforces
 * the scope explicitly: {@code UnitAppService.page} filters with
 * {@code id IN (scopeUnitIds)} and the unit-scoped methods call
 * {@code ScopeGuard.assertUnitInScope}.
 */
@RestController
@RequestMapping("/units")
public class UnitController {

    private final UnitAppService unitAppService;

    public UnitController(UnitAppService unitAppService) {
        this.unitAppService = unitAppService;
    }

    @DataScope
    @GetMapping
    public ApiResponse<PageResult<UnitView>> page(
            @RequestParam(defaultValue = "1") int pageNo,
            @RequestParam(defaultValue = "10") int pageSize,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Long subsystemId,
            @RequestParam(required = false) String type) {
        return ApiResponse.ok(unitAppService.page(
            new PageQuery(pageNo, pageSize, keyword, subsystemId, type)));
    }

    @DataScope
    @GetMapping("/{id}")
    public ApiResponse<UnitView> get(@PathVariable long id) {
        return ApiResponse.ok(unitAppService.get(id));
    }

    @RequirePerm(Permissions.SETTING_MANAGE)
    @PostMapping
    public ApiResponse<Long> create(@Valid @RequestBody CreateUnitRequest request) {
        return ApiResponse.ok(unitAppService.create(new CreateCmd(
            request.subsystemId(),
            request.name(),
            request.type(),
            request.du(),
            request.ownerId(),
            request.frameworkTemplate(),
            request.branchMode(),
            request.description(),
            request.modulePath(),
            request.deployType(),
            request.imageType(),
            request.imageName())));
    }

    @RequirePerm(Permissions.SETTING_MANAGE)
    @DataScope
    @PutMapping("/{id}")
    public ApiResponse<Void> update(@PathVariable long id,
                                    @Valid @RequestBody UpdateUnitRequest request) {
        unitAppService.update(id, new UpdateCmd(
            request.name(),
            request.type(),
            request.du(),
            request.ownerId(),
            request.frameworkTemplate(),
            request.branchMode(),
            request.status(),
            request.description(),
            request.modulePath(),
            request.deployType(),
            request.imageType(),
            request.imageName()));
        return ApiResponse.ok(null);
    }

    @RequirePerm(Permissions.SETTING_MANAGE)
    @DataScope
    @PutMapping("/{id}/coding-tasks")
    public ApiResponse<Void> bindCodingTasks(@PathVariable long id,
                                             @Valid @RequestBody BindCodingTasksRequest request) {
        List<BindingCmd> bindings = request.bindings().stream()
            .map(UnitController::toCommand)
            .toList();
        unitAppService.bindCodingTasks(id, bindings);
        return ApiResponse.ok(null);
    }

    @DataScope
    @GetMapping("/{id}/env-des")
    public ApiResponse<List<EnvDeView>> listEnvDe(@PathVariable long id) {
        return ApiResponse.ok(unitAppService.listEnvDe(id));
    }

    @RequirePerm(Permissions.SETTING_MANAGE)
    @DataScope
    @PostMapping("/{id}/env-des")
    public ApiResponse<Long> addEnvDe(@PathVariable long id,
                                      @Valid @RequestBody AddEnvDeRequest request) {
        return ApiResponse.ok(unitAppService.addEnvDe(id, new EnvDeCmd(
            request.envKind(),
            request.envTag(),
            request.deName(),
            request.resourceName(),
            request.codingDeployTarget(),
            request.deployBatchNo() == null ? 0 : request.deployBatchNo())));
    }

    private static BindingCmd toCommand(BindingRequest request) {
        return new BindingCmd(request.taskKind(), request.taskId(), request.paramsJson());
    }
}