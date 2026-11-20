package com.sf.release.domain.engine;

/**
 * Result of a successful trigger: the engine-side identifier used for later
 * {@code query}/{@code logs}/{@code cancel} calls, plus the URL a user can open to watch
 * the job. {@code externalUrl} is {@code null} when the engine response (or the endpoint
 * configuration) does not provide one.
 */
public record TriggerResult(String externalId, String externalUrl) {

    public TriggerResult {
        externalId = trimToNull(externalId);
        externalUrl = trimToNull(externalUrl);
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}