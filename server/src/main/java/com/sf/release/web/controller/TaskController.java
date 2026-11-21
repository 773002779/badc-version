package com.sf.release.web.controller;

import com.sf.release.application.TaskAppService;
import com.sf.release.application.TaskAppService.CreateTaskCmd;
import com.sf.release.application.TaskAppService.MemberCmd;
import com.sf.release.application.TaskAppService.ProgressView;
import com.sf.release.common.ApiResponse;
import com.sf.release.common.security.Permissions;
import com.sf.release.common.security.RequirePerm;
import com.sf.release.web.dto.TaskDtos.AddMembersRequest;
import com.sf.release.web.dto.TaskDtos.CreateTaskRequest;
import com.sf.release.web.dto.TaskDtos.MemberRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 发布任务与成员 endpoints. Every handler is authenticated by {@code PermissionInterceptor};
 * mutations (task creation, member add/update) additionally require {@code task:create}.
 * Reads (progress, product recipients) need authentication only.
 */
@RestController
@RequestMapping("/tasks")
public class TaskController {

    private final TaskAppService taskAppService;

    public TaskController(TaskAppService taskAppService) {
        this.taskAppService = taskAppService;
    }

    @RequirePerm(Permissions.TASK_CREATE)
    @PostMapping
    public ApiResponse<Long> create(@Valid @RequestBody CreateTaskRequest request) {
        return ApiResponse.ok(taskAppService.create(new CreateTaskCmd(
            request.name(),
            request.subsystemId(),
            null,
            request.planFinishDate(),
            request.purpose())));
    }

    @RequirePerm(Permissions.TASK_CREATE)
    @PostMapping("/{id}/members")
    public ApiResponse<Void> addMembers(@PathVariable long id,
                                        @Valid @RequestBody AddMembersRequest request) {
        taskAppService.addMembers(id, request.members().stream()
            .map(TaskController::toCommand)
            .toList());
        return ApiResponse.ok(null);
    }

    @GetMapping("/{id}/progress")
    public ApiResponse<ProgressView> progress(@PathVariable long id) {
        return ApiResponse.ok(taskAppService.progress(id));
    }

    @GetMapping("/{id}/products")
    public ApiResponse<List<String>> products(@PathVariable long id) {
        return ApiResponse.ok(taskAppService.productsOf(id));
    }

    private static MemberCmd toCommand(MemberRequest request) {
        return new MemberCmd(request.userId(), request.role());
    }
}