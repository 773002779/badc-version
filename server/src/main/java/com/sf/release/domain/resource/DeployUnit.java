package com.sf.release.domain.resource;

import java.time.Instant;

/**
 * A deployment unit or second-party library: the smallest resource that can be built and
 * deployed independently.
 *
 * <p>The record is a pure domain value (no framework types). Text fields are trimmed on
 * construction and blank values become {@code null}. {@code modulePath} is the module
 * inside a shared code repository and is used by commit comparison (Task 15); it is
 * nullable because most units occupy the whole repository. Defaults mirror the DDL:
 * {@code type = UNIT}, {@code branchMode = STANDARD}, {@code status = ACTIVE}.
 *
 * <p>{@code module} and {@code tag} are <strong>labels only</strong>: they exist for the
 * unit list filter and for display, never for a build or deploy decision. They are
 * deliberately distinct from {@code modulePath}, which the commit comparison reads.
 */
public record DeployUnit(
    Long id,
    Long subsystemId,
    String name,
    UnitType type,
    String du,
    String ownerId,
    String frameworkTemplate,
    BranchMode branchMode,
    UnitStatus status,
    String description,
    String modulePath,
    String deployType,
    String imageType,
    String imageName,
    String module,
    String tag,
    Instant createdAt,
    Instant updatedAt) {

    public DeployUnit {
        name = trimToNull(name);
        du = trimToNull(du);
        ownerId = trimToNull(ownerId);
        frameworkTemplate = trimToNull(frameworkTemplate);
        description = trimToNull(description);
        modulePath = trimToNull(modulePath);
        deployType = trimToNull(deployType);
        imageType = trimToNull(imageType);
        imageName = trimToNull(imageName);
        module = trimToNull(module);
        tag = trimToNull(tag);
        type = type == null ? UnitType.UNIT : type;
        branchMode = branchMode == null ? BranchMode.STANDARD : branchMode;
        status = status == null ? UnitStatus.ACTIVE : status;
    }

    public boolean isActive() {
        return status == UnitStatus.ACTIVE;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}