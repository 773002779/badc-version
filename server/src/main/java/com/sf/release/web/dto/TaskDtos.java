package com.sf.release.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.List;

/**
 * Request bodies of {@code /tasks}. Responses reuse the application view records so the web
 * layer never depends on domain entities.
 *
 * <p>The create form carries no creator: the application service records the authenticated
 * caller, so a request cannot attribute a task to somebody else.
 */
public final class TaskDtos {

    private TaskDtos() {
    }

    /** Create form: subsystem and name are required, purpose and plan finish date optional. */
    public record CreateTaskRequest(
        @NotBlank(message = "任务名称不能为空")
        @Size(max = 128, message = "任务名称长度不能超过 128")
        String name,

        @NotNull(message = "所属子系统不能为空")
        Long subsystemId,

        LocalDate planFinishDate,

        @Size(max = 512, message = "目的长度不能超过 512")
        String purpose) {
    }

    /** One member of the task; {@code role} is DEV / TEST / OPS / PRODUCT / OWNER. */
    public record MemberRequest(
        @NotBlank(message = "成员不能为空")
        @Size(max = 32, message = "成员工号长度不能超过 32")
        String userId,

        @NotBlank(message = "成员角色不能为空")
        @Size(max = 16, message = "成员角色长度不能超过 16")
        String role) {
    }

    /** Add-or-update request: adding an existing member overwrites that member's role. */
    public record AddMembersRequest(
        @NotEmpty(message = "成员不能为空")
        @Valid
        List<MemberRequest> members) {
    }
}