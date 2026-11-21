package com.sf.release.domain.engine;

/**
 * Input of a deploy trigger: the bound deploy task, the target DE and the image built for
 * the same pipeline instance.
 *
 * <p>{@code envKind} is the environment family ({@code TEST}/{@code PROD}/{@code BLUE}/
 * {@code DR}); {@code deName} is the concrete deployment environment. {@code imageDigest}
 * may be {@code null} when the caller has no digest yet; the template then reports the
 * placeholder as unresolved instead of posting an empty image reference.
 */
public record DeployCmd(
    long unitId,
    String taskId,
    String envKind,
    String deName,
    String imageDigest,
    String pipelineNo,
    int attempt) {

    public DeployCmd {
        taskId = trimToNull(taskId);
        envKind = trimToNull(envKind);
        deName = trimToNull(deName);
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