package com.sf.release.domain.engine;

import com.sf.release.domain.pipeline.CheckType;

/**
 * Input of one gate-check trigger. The engine needs the object to scan, which differs per stage:
 * an image-level check scans the built image/commit, an environment-level check scans a deployed
 * DE. All of it is already known when the check starts, so the platform never asks the engine
 * "which checks are there" — the seven {@link CheckType} values are the catalogue (phase 1).
 *
 * <p>{@code taskId} is the bound CODING job the check belongs to: the unit's {@code BUILD}
 * binding for an image-level check and its {@code TEST_DEPLOY} binding for an environment-level
 * check. It may be {@code null} (an unconfigured unit then fails with a configuration error
 * instead of sending a half-empty request).
 */
public record GateCheckCmd(
    long unitId,
    String taskId,
    CheckType checkType,
    String commitSha,
    String imageDigest,
    String deName,
    String pipelineNo,
    int attempt) {

    public GateCheckCmd {
        taskId = trimToNull(taskId);
        commitSha = trimToNull(commitSha);
        imageDigest = trimToNull(imageDigest);
        deName = trimToNull(deName);
        pipelineNo = trimToNull(pipelineNo);
        if (checkType == null) {
            throw new IllegalArgumentException("check type is required");
        }
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}