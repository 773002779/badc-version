package com.sf.release.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * Request body of {@code POST /api/v1/items} (C2).
 *
 * <p><strong>No branch input.</strong> The change item's branch is read-only from the unit's
 * CODING build binding (C21): the service resolves it and the create endpoint never trusts a
 * client value. The optional {@code sourceBranch} field exists only so an older client that
 * still sends one is not rejected; its value is ignored.
 */
public final class ItemDtos {

    private ItemDtos() {
    }

    /** Create form: task, unit, change reason and planned release date are required. */
    public record CreateItemRequest(
        @NotNull(message = "发布任务不能为空")
        Long taskId,

        @NotNull(message = "部署单元不能为空")
        Long unitId,

        @NotBlank(message = "变更原因不能为空")
        @Size(max = 64, message = "变更原因长度不能超过 64")
        String version,

        @NotNull(message = "计划发布时间不能为空")
        LocalDate planReleaseDate,

        /**
         * Ignored on purpose: the branch comes from the CODING binding. Kept in the schema so a
         * client that sends it gets a normal success instead of a 400 on an unknown field.
         */
        @Size(max = 128, message = "分支长度不能超过 128")
        String sourceBranch) {
    }
}