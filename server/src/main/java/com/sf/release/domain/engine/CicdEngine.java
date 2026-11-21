package com.sf.release.domain.engine;

import java.util.List;

/**
 * SPI implemented by every CI/CD engine adapter.
 *
 * <p>Phase 1 has exactly one implementation, {@code CODING}. The interface is deliberately
 * independent of any HTTP detail: the orchestrator decides <em>what</em> to run, the
 * adapter owns paths, payload field names and status vocabularies.
 *
 * <p>Failures are reported as {@link EngineException}, never as {@code null} or a silent
 * empty value, so a pipeline task can surface the reason and be marked failed/timed out.
 */
public interface CicdEngine {

    /** Engine type used by {@code EngineRegistry} (for example {@code "CODING"}). */
    String name();

    /** Starts a build job and returns the engine-side identifier plus an optional jump URL. */
    TriggerResult triggerBuild(BuildCmd cmd);

    /** Starts a deploy job for one DE and returns the engine-side identifier. */
    TriggerResult triggerDeploy(DeployCmd cmd);

    /**
     * Queries a build or deploy job started earlier. The kind selects the engine endpoint
     * ({@code build.query} / {@code deploy.query}); it cannot be inferred from the id.
     *
     * <p>A missing image digest is returned as {@code null}: deciding what a missing digest
     * means (for example blocking a production deploy) is the caller's policy, not the
     * adapter's.
     */
    EngineResult query(String externalId, JobKind kind);

    /**
     * Reads the raw log content for a build or deploy job; offset and limit are passed
     * through to the engine.
     */
    String logs(String externalId, JobKind kind, int offset, int limit);

    /** Cancels a running job; an engine that cannot cancel reports the failure as an exception. */
    void cancel(String externalId);

    /**
     * Lists the jobs of one engine project so the user interface can offer the existing build
     * and deploy tasks instead of asking for a raw id. {@code kind} selects the namespace
     * (BUILD or DEPLOY) exactly like {@link #query}.
     *
     * <p>This is an optional capability: an adapter that cannot enumerate jobs reports
     * {@code ENGINE_UNAVAILABLE} rather than returning an empty list, because an empty list
     * means "this project has no jobs" and would be indistinguishable to the caller.
     */
    default List<EngineJob> listJobs(String project, JobKind kind) {
        throw EngineException.unavailable("当前引擎不支持任务列表：" + name());
    }
}