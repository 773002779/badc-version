package com.sf.release.domain.engine;

/**
 * Input of a deploy trigger: the bound deploy task, the target DE and the image built for
 * the same pipeline instance.
 *
 * <p>{@code envKind} is the environment family ({@code TEST}/{@code PROD}/{@code BLUE}/
 * {@code DR}); {@code deName} is the concrete deployment environment. {@code imageDigest}
 * may be {@code null} when the caller has no digest yet; the template then reports the
 * placeholder as unresolved instead of posting an empty image reference.
 *
 * <p>{@code codingDeployTarget} is the DE's explicit CODING-side deployment target
 * ({@code env_de.coding_deploy_target}, PART-05); the handler fills it with the DE name when
 * the row has no mapping, so the engine template always has one non-empty target field.
 */
public record DeployCmd(
    long unitId,
    String taskId,
    String envKind,
    String deName,
    String codingDeployTarget,
    String imageDigest,
    String pipelineNo,
    int attempt) {

    public DeployCmd {
        taskId = trimToNull(taskId);
        envKind = trimToNull(envKind);
        deName = trimToNull(deName);
        codingDeployTarget = trimToNull(codingDeployTarget);
        imageDigest = trimToNull(imageDigest);
        pipelineNo = trimToNull(pipelineNo);
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
