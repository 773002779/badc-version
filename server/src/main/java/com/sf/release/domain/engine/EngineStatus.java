package com.sf.release.domain.engine;

/**
 * Engine vocabulary exposed to the pipeline. Engine-specific words are mapped to these
 * values by configuration ({@code coding.statusMapping}), so a vendor that says
 * {@code "FINISHED"} instead of {@code "SUCCESS"} only needs a YAML change.
 *
 * <p>{@link #UNKNOWN} is a deliberate non-failure result: a vendor word that is not in the
 * mapping yet must not be reported as {@code ENGINE_UNAVAILABLE}, because the reconcile loop
 * turns repeated call failures into a TIMEOUT that would falsely kill a live task. The raw
 * word stays in {@link EngineResult#message} and the adapter logs it at WARN.
 */
public enum EngineStatus {
    QUEUED,
    RUNNING,
    SUCCESS,
    FAILED,
    CANCELED,
    UNKNOWN
}