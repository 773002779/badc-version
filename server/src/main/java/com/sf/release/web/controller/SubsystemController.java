package com.sf.release.web.controller;

import com.sf.release.application.SubsystemAppService;
import com.sf.release.application.SubsystemAppService.CreateCmd;
import com.sf.release.application.SubsystemAppService.PageQuery;
import com.sf.release.application.SubsystemAppService.PageResult;
import com.sf.release.application.SubsystemAppService.SubsystemView;
import com.sf.release.application.SubsystemAppService.UpdateCmd;
import com.sf.release.common.ApiResponse;
import com.sf.release.common.security.Permissions;
import com.sf.release.common.security.RequirePerm;
import com.sf.release.web.dto.SubsystemDtos.CreateSubsystemRequest;
import com.sf.release.web.dto.SubsystemDtos.UpdateSubsystemRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 系统管理 endpoints. Every handler is authenticated by {@code PermissionInterceptor};
 * mutations additionally require the {@code setting:manage} permission point, because
 * resource administration is an ADMIN responsibility in phase 1. Read endpoints are open
 * to any authenticated employee.
 */
@RestController
@RequestMapping("/subsystems")
public class SubsystemController {

    private final SubsystemAppService subsystemAppService;

    public SubsystemController(SubsystemAppService subsystemAppService) {
        this.subsystemAppService = subsystemAppService;
    }

    @GetMapping
    public ApiResponse<PageResult<SubsystemView>> page(
            @RequestParam(defaultValue = "1") int pageNo,
            @RequestParam(defaultValue = "10") int pageSize,
            @RequestParam(required = false) String keyword) {
        return ApiResponse.ok(subsystemAppService.page(new PageQuery(pageNo, pageSize, keyword)));
    }

    @GetMapping("/{id}")
    public ApiResponse<SubsystemView> get(@PathVariable long id) {
        return ApiResponse.ok(subsystemAppService.get(id));
    }

    @RequirePerm(Permissions.SETTING_MANAGE)
    @PostMapping
    public ApiResponse<Long> create(@Valid @RequestBody CreateSubsystemRequest request) {
        return ApiResponse.ok(subsystemAppService.create(new CreateCmd(
            request.name(),
            request.ownerId(),
            request.agentId(),
            request.description(),
            request.gitUrl(),
            request.codingProject())));
    }

    @RequirePerm(Permissions.SETTING_MANAGE)
    @PutMapping("/{id}")
    public ApiResponse<Void> update(@PathVariable long id,
                                    @Valid @RequestBody UpdateSubsystemRequest request) {
        subsystemAppService.update(id, new UpdateCmd(
            request.name(),
            request.ownerId(),
            request.agentId(),
            request.status(),
            request.description(),
            request.gitUrl(),
            request.codingProject()));
        return ApiResponse.ok(null);
    }
}