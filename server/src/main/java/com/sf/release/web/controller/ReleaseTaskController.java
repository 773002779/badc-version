package com.sf.release.web.controller;

import com.sf.release.application.SingleDeRedeployService;
import com.sf.release.application.TaskLogService;
import com.sf.release.common.ApiResponse;
import com.sf.release.common.security.DataScope;
import com.sf.release.common.security.Permissions;
import com.sf.release.common.security.RequirePerm;
import com.sf.release.web.dto.RedeployDtos;
import com.sf.release.web.dto.RedeployDtos.RedeployResponse;
import com.sf.release.web.dto.RedeployDtos.TaskLogResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * P6-G task actions on the 发布内容 page (design §3):
 *
 * <ul>
 *   <li>{@code POST /api/v1/releases/{publishNo}/tasks/{taskId}/redeploy} — re-executes the failed
 *       production DE row. Requires {@code control:execute-prod}; the service additionally
 *       validates the task/release eligibility, the unit scope and the unit lock.</li>
 *   <li>{@code GET /api/v1/releases/{publishNo}/tasks/{taskId}/logs?offset=&limit=} — proxies one
 *       page of the task's engine log. Any authenticated caller inside the unit's data scope may
 *       read; the service enforces the latest-instance and job-kind rules.</li>
 * </ul>
 *
 * <p>Both handlers return the unified envelope; an unknown publish number/task is 40000, an
 * ineligible task is 40001, a scope violation is 40009 and an engine failure keeps its own
 * 50001/50002 code. No page is added and no state is written by the log endpoint.
 */
@RestController
@RequestMapping("/api/v1/releases")
public class ReleaseTaskController {

    private final SingleDeRedeployService singleDeRedeployService;
    private final TaskLogService taskLogService;

    public ReleaseTaskController(SingleDeRedeployService singleDeRedeployService,
                                 TaskLogService taskLogService) {
        this.singleDeRedeployService = singleDeRedeployService;
        this.taskLogService = taskLogService;
    }

    @RequirePerm(Permissions.CONTROL_EXECUTE_PROD)
    @PostMapping("/{publishNo}/tasks/{taskId}/redeploy")
    public ApiResponse<RedeployResponse> redeploy(@PathVariable String publishNo,
                                                  @PathVariable long taskId) {
        return ApiResponse.ok(RedeployDtos.of(
            singleDeRedeployService.redeploy(publishNo, taskId)));
    }

    @DataScope
    @GetMapping("/{publishNo}/tasks/{taskId}/logs")
    public ApiResponse<TaskLogResponse> logs(
            @PathVariable String publishNo,
            @PathVariable long taskId,
            @RequestParam(name = "offset", defaultValue = "0") int offset,
            @RequestParam(name = "limit", defaultValue = "8000") int limit) {
        return ApiResponse.ok(RedeployDtos.of(
            taskLogService.logs(publishNo, taskId, offset, limit)));
    }
}