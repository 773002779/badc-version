package com.sf.release.web.controller;

import com.sf.release.application.ReleaseControlService;
import com.sf.release.common.ApiResponse;
import com.sf.release.common.security.Permissions;
import com.sf.release.common.security.RequirePerm;
import com.sf.release.web.dto.ControlDtos;
import com.sf.release.web.dto.ControlDtos.ArchiveResponse;
import com.sf.release.web.dto.ControlDtos.ControlResponse;
import com.sf.release.web.dto.ControlDtos.ReasonRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 停止 / 退出 / 关闭 / 归档 endpoints (P5-B design §4):
 *
 * <ul>
 *   <li>{@code POST /api/v1/releases/{publishNo}/stop} — ends a production release; requires
 *       {@code control:execute-prod} plus the unit data scope. The batches already online are kept;</li>
 *   <li>{@code POST /api/v1/releases/{publishNo}/exit} — ends any non-terminal release pipeline.
 *       The permission is {@code release:publish} <em>or</em> {@code control:execute-prod}, so no
 *       single {@link RequirePerm} can express it: the interceptor still authenticates and the
 *       service owns the OR;</li>
 *   <li>{@code POST /api/v1/items/{itemId}/close} — closes a non-terminal 变更项, terminating its
 *       active instances, voiding the pending approvals and invalidating the unfinished checks;</li>
 *   <li>{@code POST /api/v1/tasks/{taskId}/archive} — turns the 发布任务 read-only and cascades the
 *       close over every unfinished 变更项; release records and the rollback entry stay.</li>
 * </ul>
 *
 * <p>Every body is {@code {reason}}. The answers echo the resulting statuses: the three release
 * actions answer {@code {status, itemStatus, terminatedPipelineNo}}, the archive answers
 * {@code {taskStatus, closedItems, terminatedInstances}} so the page can say how much it closed.
 *
 * <p>The controller is a thin translation layer and imports no domain or infrastructure enum; the
 * data-scope guard runs inside {@link ReleaseControlService} (40009) because the object is resolved
 * there.
 */
@RestController
@RequestMapping("/api/v1")
public class ReleaseControlController {

    private final ReleaseControlService releaseControlService;

    public ReleaseControlController(ReleaseControlService releaseControlService) {
        this.releaseControlService = releaseControlService;
    }

    /** 停止发布: production flow only; the already deployed batches are kept. */
    @RequirePerm(Permissions.CONTROL_EXECUTE_PROD)
    @PostMapping("/releases/{publishNo}/stop")
    public ApiResponse<ControlResponse> stop(@PathVariable String publishNo,
                                             @Valid @RequestBody ReasonRequest request) {
        return ApiResponse.ok(ControlDtos.of(
            releaseControlService.stop(publishNo, request.reason())));
    }

    /** 退出流水线: any non-terminal release; no single permission (see the class Javadoc). */
    @PostMapping("/releases/{publishNo}/exit")
    public ApiResponse<ControlResponse> exit(@PathVariable String publishNo,
                                             @Valid @RequestBody ReasonRequest request) {
        return ApiResponse.ok(ControlDtos.of(
            releaseControlService.exit(publishNo, request.reason())));
    }

    /** 关闭变更项: any non-terminal item; it can never be published again. */
    @RequirePerm(Permissions.RELEASE_PUBLISH)
    @PostMapping("/items/{itemId}/close")
    public ApiResponse<ControlResponse> close(@PathVariable long itemId,
                                              @Valid @RequestBody ReasonRequest request) {
        return ApiResponse.ok(ControlDtos.of(
            releaseControlService.close(itemId, request.reason())));
    }

    /** 归档任务: the task becomes read-only; the cascade counts are returned for the page prompt. */
    @RequirePerm(Permissions.TASK_CREATE)
    @PostMapping("/tasks/{taskId}/archive")
    public ApiResponse<ArchiveResponse> archive(@PathVariable long taskId,
                                                @Valid @RequestBody ReasonRequest request) {
        return ApiResponse.ok(ControlDtos.of(
            releaseControlService.archive(taskId, request.reason())));
    }
}