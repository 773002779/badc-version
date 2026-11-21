package com.sf.release.domain.engine;

/**
 * Snapshot of a job as reported by the engine.
 *
 * <p>{@code imageDigest} is {@code null} when the engine response has no digest (not every
 * query carries one). {@code message} keeps the raw engine status word for diagnostics.
 */
public record EngineResult(EngineStatus status, String imageDigest, String message) {

    public EngineResult {
        if (status == null) {
            throw new IllegalArgumentException("engine status is required");
        }
        imageDigest = trimToNull(imageDigest);
        message = trimToNull(message);
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}