package com.sf.release.domain.engine;

/**
 * Result of a successful trigger: the engine-side identifier used for later
 * {@code query}/{@code logs}/{@code cancel} calls, the URL a user can open to watch the job
 * and, when the engine reports it, the commit the job actually builds.
 *
 * <p>{@code externalUrl} is {@code null} when the engine response (or the endpoint
 * configuration) does not provide one. {@code commitSha} is {@code null} when the trigger
 * response has no commit (the engine then builds the branch head); callers must not fabricate
 * a value from a baseline commit.
 */
public record TriggerResult(String externalId, String externalUrl, String commitSha) {

    public TriggerResult {
        externalId = trimToNull(externalId);
        externalUrl = trimToNull(externalUrl);
        commitSha = trimToNull(commitSha);
    }

    /** A trigger result without a commit (deploy jobs, or a build response with no commit). */
    public TriggerResult(String externalId, String externalUrl) {
        this(externalId, externalUrl, null);
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}