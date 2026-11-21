package com.sf.release.web.controller;

import com.sf.release.application.SubsystemAppService.PageResult;
import com.sf.release.application.TaskAppService;
import com.sf.release.application.TaskAppService.CreateTaskCmd;
import com.sf.release.application.TaskAppService.ItemView;
import com.sf.release.application.TaskAppService.MemberCmd;
import com.sf.release.application.TaskAppService.ProgressView;
import com.sf.release.application.TaskAppService.TaskDetailView;
import com.sf.release.application.TaskAppService.TaskQuery;
import com.sf.release.application.TaskAppService.TaskView;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 发布任务与成员 endpoints. Every handler is authenticated by {@code PermissionInterceptor};
 * mutations (task creation, member add/update) additionally require {@code task:create}.
 * Reads (task list, task detail, item list, progress, product recipients) need authentication
 * only.
 *
 * <p>The three read endpoints of the P1 task page are:
 *
 * <ul>
 *   <li>{@code GET /tasks} — paged task cards, newest first, with the optional
 *       {@code subsystemId}, {@code status} and {@code keyword} filters;</li>
 *   <li>{@code GET /tasks/{id}} — the task, its members and its item count;</li>
 *   <li>{@code GET /tasks/{id}/items} — the task's 变更项 with the deployment unit name.</li>
 * </ul>
 *
 * <p>An unknown task id answers with the business code 40000 and {@code {taskId}} in the
 * payload.
 */
@RestController
@RequestMapping("/tasks")
public class TaskController {

    private final TaskAppService taskAppService;

    public TaskController(TaskAppService taskAppService) {
        this.taskAppService = taskAppService;
    }

    @GetMapping
    public ApiResponse<PageResult<TaskView>> page(
            @RequestParam(defaultValue = "1") int pageNo,
            @RequestParam(defaultValue = "10") int pageSize,
            @RequestParam(required = false) Long subsystemId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword) {
        return ApiResponse.ok(taskAppService.page(
            new TaskQuery(pageNo, pageSize, subsystemId, status, keyword)));
    }

    @GetMapping("/{id}")
    public ApiResponse<TaskDetailView> detail(@PathVariable long id) {
        return ApiResponse.ok(taskAppService.detail(id));
    }

    @GetMapping("/{id}/items")
    public ApiResponse<List<ItemView>> items(@PathVariable long id) {
        return ApiResponse.ok(taskAppService.items(id));
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