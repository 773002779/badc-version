package com.sf.release.domain.engine;

/**
 * Engine vocabulary exposed to the pipeline. Engine-specific words are mapped to these
 * values by configuration ({@code coding.statusMapping}), so a vendor that says
 * {@code "FINISHED"} instead of {@code "SUCCESS"} only needs a YAML change.
 */
public enum EngineStatus {
    QUEUED,
    RUNNING,
    SUCCESS,
    FAILED,
    CANCELED
}