package com.sf.release.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Request bodies of {@code /units}. Responses reuse the application view records so the
 * web layer never depends on domain entities.
 */
public final class UnitDtos {

    private UnitDtos() {
    }

    /** Create form: subsystem, name and owner are required, the rest is optional detail. */
    public record CreateUnitRequest(
        @NotNull(message = "所属系统不能为空")
        Long subsystemId,

        @NotBlank(message = "名称不能为空")
        @Size(max = 128, message = "名称长度不能超过 128")
        String name,

        @Size(max = 16, message = "类型长度不能超过 16")
        String type,

        @Size(max = 128, message = "DU 长度不能超过 128")
        String du,

        @NotBlank(message = "负责人不能为空")
        @Size(max = 32, message = "负责人长度不能超过 32")
        String ownerId,

        @Size(max = 64, message = "框架模板长度不能超过 64")
        String frameworkTemplate,

        @Size(max = 16, message = "分支模式长度不能超过 16")
        String branchMode,

        @Size(max = 512, message = "描述长度不能超过 512")
        String description,

        @Size(max = 255, message = "模块路径长度不能超过 255")
        String modulePath,

        @Size(max = 32, message = "部署类型长度不能超过 32")
        String deployType,

        @Size(max = 32, message = "镜像类型长度不能超过 32")
        String imageType,

        @Size(max = 255, message = "镜像名称长度不能超过 255")
        String imageName,

        @Size(max = 64, message = "模块长度不能超过 64")
        String module,

        @Size(max = 64, message = "标签长度不能超过 64")
        String tag,

        /*
         * Artifact coordinates (P6-A): required for a LIB unit and ignored by a UNIT. The
         * type-conditional requirement and the coordinate format live in the application
         * service, because a bean-validation annotation cannot see the requested unit type.
         */
        @Size(max = 128, message = "制品 groupId 长度不能超过 128")
        String artifactGroupId,

        @Size(max = 128, message = "制品 artifactId 长度不能超过 128")
        String artifactArtifactId) {
    }

    /** Edit form: the same detail fields plus the lifecycle status. */
    public record UpdateUnitRequest(
        @NotBlank(message = "名称不能为空")
        @Size(max = 128, message = "名称长度不能超过 128")
        String name,

        @Size(max = 16, message = "类型长度不能超过 16")
        String type,

        @Size(max = 128, message = "DU 长度不能超过 128")
        String du,

        @NotBlank(message = "负责人不能为空")
        @Size(max = 32, message = "负责人长度不能超过 32")
        String ownerId,

        @Size(max = 64, message = "框架模板长度不能超过 64")
        String frameworkTemplate,

        @Size(max = 16, message = "分支模式长度不能超过 16")
        String branchMode,

        @Size(max = 16, message = "状态长度不能超过 16")
        String status,

        @Size(max = 512, message = "描述长度不能超过 512")
        String description,

        @Size(max = 255, message = "模块路径长度不能超过 255")
        String modulePath,

        @Size(max = 32, message = "部署类型长度不能超过 32")
        String deployType,

        @Size(max = 32, message = "镜像类型长度不能超过 32")
        String imageType,

        @Size(max = 255, message = "镜像名称长度不能超过 255")
        String imageName,

        @Size(max = 64, message = "模块长度不能超过 64")
        String module,

        @Size(max = 64, message = "标签长度不能超过 64")
        String tag,

        @Size(max = 128, message = "制品 groupId 长度不能超过 128")
        String artifactGroupId,

        @Size(max = 128, message = "制品 artifactId 长度不能超过 128")
        String artifactArtifactId) {
    }

    /** One CODING task reference. */
    public record BindingRequest(
        @NotBlank(message = "任务类型不能为空")
        @Size(max = 16, message = "任务类型长度不能超过 16")
        String taskKind,

        @NotBlank(message = "CODING 任务 ID 不能为空")
        @Size(max = 64, message = "CODING 任务 ID 长度不能超过 64")
        String taskId,

        String paramsJson) {
    }

    /** Bind/rebind request: accumulates over the unit's existing task kinds. */
    public record BindCodingTasksRequest(
        @NotNull(message = "绑定列表不能为空")
        @Valid
        List<BindingRequest> bindings) {
    }

    /** DE create form; a missing batch number means {@code 0}. */
    public record AddEnvDeRequest(
        @NotBlank(message = "环境类型不能为空")
        @Size(max = 16, message = "环境类型长度不能超过 16")
        String envKind,

        @Size(max = 64, message = "环境标签长度不能超过 64")
        String envTag,

        @NotBlank(message = "DE 名称不能为空")
        @Size(max = 128, message = "DE 名称长度不能超过 128")
        String deName,

        @Size(max = 128, message = "资源名长度不能超过 128")
        String resourceName,

        @Size(max = 128, message = "CODING 部署目标长度不能超过 128")
        String codingDeployTarget,

        @Min(value = 0, message = "部署批次号不能为负数")
        Integer deployBatchNo) {
    }
    // -------------------------------------------------------- PART-01 release summary

    /** Maps the summary read model of the unit list's three new columns (PART-01 design §2). */
    public static UnitReleaseSummaryResponse ofReleaseSummary(
            com.sf.release.application.UnitReleaseSummaryService.Summary view) {
        return new UnitReleaseSummaryResponse(
            view.unitId(),
            view.latest() == null ? null : new LatestReleaseView(
                view.latest().digest(),
                view.latest().publishNo(),
                view.latest().finishedAt(),
                view.latest().executedBy()),
            view.lock() == null ? null : new UnitLockView(
                view.lock().instanceId(),
                view.lock().acquiredAt(),
                view.lock().expireAt()));
    }

    /**
     * The unit list's summary columns (PART-01 design §1): the newest successful production deploy
     * (「最近发布版本 / 时间」) and the current lock row (「占用锁」). Both sections are null when
     * the unit never deployed to production / is not locked — the page renders {@code —}.
     */
    public record UnitReleaseSummaryResponse(
        long unitId,
        LatestReleaseView latest,
        UnitLockView lock) {
    }

    /** The newest successful production deploy; the same definition the rollback drawer shows. */
    public record LatestReleaseView(
        String digest,
        String publishNo,
        java.time.Instant finishedAt,
        String executedBy) {
    }

    /** The current lock row; a row past its lease stays visible until the reclaim job runs. */
    public record UnitLockView(
        long instanceId,
        java.time.Instant acquiredAt,
        java.time.Instant expireAt) {
    }
}
