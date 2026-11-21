package com.sf.release.domain.engine;

/**
 * Input of a build trigger: the bound CODING task plus the source coordinates the pipeline
 * already knows.
 *
 * <p>{@code taskId} is the engine-side task identifier from {@code coding_task_binding};
 * {@code pipelineNo}/{@code attempt} provide idempotency context for the engine.
 */
public record BuildCmd(
    long unitId,
    String taskId,
    String branch,
    String commitSha,
    String pipelineNo,
    int attempt) {

    public BuildCmd {
        taskId = trimToNull(taskId);
        branch = trimToNull(branch);
        commitSha = trimToNull(commitSha);
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