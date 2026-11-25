package com.sf.release.domain.engine;

/**
 * Input of an artifact publish (upload) trigger of a second-party library (P6-A design §3).
 *
 * <p>The version is the 变更项's version: the platform does not derive or bump it (that needs
 * the artifact repository metadata this slice deliberately does not integrate). {@code taskId}
 * is the engine-side task identifier of the unit's {@code LIB_PUBLISH} binding;
 * {@code pipelineNo}/{@code attempt} provide idempotency context for the engine.
 */
public record PublishCmd(
    String taskId,
    String groupId,
    String artifactId,
    String version,
    String pipelineNo,
    int attempt) {

    public PublishCmd {
        taskId = trimToNull(taskId);
        groupId = trimToNull(groupId);
        artifactId = trimToNull(artifactId);
        version = trimToNull(version);
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