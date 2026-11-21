package com.sf.release.domain.resource;

import java.time.Instant;

/**
 * A concrete deployment instance (DE) of a unit, for example
 * {@code cwsp-quota-api-sit}. {@code deployBatchNo} orders the serial production,
 * disaster-recovery and blue-green deployments (C14); it is never null and defaults
 * to {@code 0} in the database.
 */
public record EnvDe(
    Long id,
    Long unitId,
    EnvKind envKind,
    String envTag,
    String deName,
    String resourceName,
    String codingDeployTarget,
    int deployBatchNo,
    Instant createdAt,
    Instant updatedAt) {

    public EnvDe {
        envTag = trimToNull(envTag);
        deName = trimToNull(deName);
        resourceName = trimToNull(resourceName);
        codingDeployTarget = trimToNull(codingDeployTarget);
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}