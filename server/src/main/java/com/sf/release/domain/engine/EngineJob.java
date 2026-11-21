package com.sf.release.domain.engine;

/**
 * One selectable engine job (a build or deploy task of a project), as returned by
 * {@link CicdEngine#listJobs}.
 *
 * <p>The {@code taskId} is what a unit binding stores; the {@code name} is what the operator
 * sees. {@code type} keeps the engine's own vocabulary word when the response reports one
 * (for example {@code BUILD} / {@code DEPLOY}) and is {@code null} otherwise, so the picker
 * can group or filter without the platform inventing a value.
 */
public record EngineJob(String taskId, String name, String type) {

    public EngineJob {
        taskId = trimToNull(taskId);
        name = trimToNull(name);
        type = trimToNull(type);
        if (taskId == null) {
            throw new IllegalArgumentException("engine job id is required");
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