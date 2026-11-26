package com.sf.release.domain.engine;

import com.sf.release.domain.pipeline.CheckType;

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
     * Uploads the artifact of a second-party library to the artifact repository (P6-A design
     * §3). Like {@link #startCheck}, this is an <em>optional</em> capability: an adapter that
     * cannot publish artifacts reports {@code ENGINE_UNAVAILABLE}, and the pipeline task is
     * failed with that reason instead of being left running forever.
     *
     * <p>The platform only records the task and its result; it does not own the artifact
     * repository, so the coordinates and the version come from the unit and the 变更项.
     */
    default TriggerResult startPublish(PublishCmd cmd) {
        throw EngineException.unavailable("当前引擎不支持制品入库：" + name());
    }

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

    /**
     * The PART-05 variant of {@link #listJobs(String, JobKind)}: the call is made on behalf of
     * one deployment unit, so an adapter that resolves per-subsystem connections answers the
     * unit's subsystem override. The default simply falls back to the global form.
     */
    default List<EngineJob> listJobs(long unitId, String project, JobKind kind) {
        return listJobs(project, kind);
    }

    /**
     * Starts one gate check (Sonar, component vulnerability, …) and returns the engine-side id
     * used to poll it. Like {@link #listJobs}, this is an <em>optional</em> capability: an adapter
     * that cannot run checks reports {@code ENGINE_UNAVAILABLE}, and the platform records the
     * check as "not finished" instead of "passed".
     *
     * <p>The seventh {@code CheckType} values are the catalogue; the engine is never asked which
     * checks exist. The trigger carries the object to scan (commit/image for an image-level check,
     * a deployed DE for an environment-level one).
     */
    default TriggerResult startCheck(GateCheckCmd cmd) {
        throw EngineException.unavailable("当前引擎不支持质量检查：" + name());
    }

    /**
     * Polls one gate check started by {@link #startCheck}. A response that cannot be judged is
     * reported as {@link GateCheckOutcome#UNKNOWN}; it must never be silently read as success.
     */
    default GateCheckReport queryCheck(String externalId, CheckType checkType) {
        throw EngineException.unavailable("当前引擎不支持质量检查：" + name());
    }
}