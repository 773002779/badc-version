package com.sf.release.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request bodies of {@code /subsystems}. Responses reuse
 * {@link com.sf.release.application.SubsystemAppService.SubsystemView} so the web layer
 * never depends on domain entities.
 */
public final class SubsystemDtos {

    private SubsystemDtos() {
    }

    /** Create form: name and owner are required, the rest is optional detail. */
    public record CreateSubsystemRequest(
        @NotBlank(message = "名称不能为空")
        @Size(max = 128, message = "名称长度不能超过 128")
        String name,

        @NotBlank(message = "负责人不能为空")
        @Size(max = 32, message = "负责人长度不能超过 32")
        String ownerId,

        @Size(max = 32, message = "工具授权代理长度不能超过 32")
        String agentId,

        @Size(max = 512, message = "描述长度不能超过 512")
        String description,

        @Size(max = 512, message = "Git 仓库地址长度不能超过 512")
        String gitUrl,

        @Size(max = 128, message = "CODING 项目长度不能超过 128")
        String codingProject) {
    }

    /** Edit form: the same fields plus the lifecycle status. */
    public record UpdateSubsystemRequest(
        @NotBlank(message = "名称不能为空")
        @Size(max = 128, message = "名称长度不能超过 128")
        String name,

        @NotBlank(message = "负责人不能为空")
        @Size(max = 32, message = "负责人长度不能超过 32")
        String ownerId,

        @Size(max = 32, message = "工具授权代理长度不能超过 32")
        String agentId,

        @Size(max = 16, message = "状态长度不能超过 16")
        String status,

        @Size(max = 512, message = "描述长度不能超过 512")
        String description,

        @Size(max = 512, message = "Git 仓库地址长度不能超过 512")
        String gitUrl,

        @Size(max = 128, message = "CODING 项目长度不能超过 128")
        String codingProject) {
    }
}