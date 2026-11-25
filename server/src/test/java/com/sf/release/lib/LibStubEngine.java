package com.sf.release.lib;

import com.sf.release.domain.engine.BuildCmd;
import com.sf.release.domain.engine.CicdEngine;
import com.sf.release.domain.engine.DeployCmd;
import com.sf.release.domain.engine.EngineResult;
import com.sf.release.domain.engine.GateCheckCmd;
import com.sf.release.domain.engine.GateCheckReport;
import com.sf.release.domain.engine.JobKind;
import com.sf.release.domain.engine.PublishCmd;
import com.sf.release.domain.engine.TriggerResult;
import com.sf.release.domain.pipeline.CheckType;

import java.util.ArrayList;
import java.util.List;

/**
 * Recording {@link CicdEngine} stub of the library tests: it answers the three triggers a LIB
 * pipeline uses (build, check, publish) and records every command, so a test can prove "exactly
 * one publish" and "exactly three checks". An injected failure is thrown from the matching
 * trigger to exercise the handler's persisted-failure path.
 */
final class LibStubEngine implements CicdEngine {

    final List<BuildCmd> buildCalls = new ArrayList<>();
    final List<GateCheckCmd> checkCalls = new ArrayList<>();
    final List<PublishCmd> publishCalls = new ArrayList<>();

    /** When set, the next {@code triggerBuild} throws it (and the call is recorded). */
    RuntimeException buildFailure;
    /** When set, the next {@code startCheck} throws it (and the call is recorded). */
    RuntimeException checkFailure;
    /** When set, the next {@code startPublish} throws it (and the call is recorded). */
    RuntimeException publishFailure;

    /** Commit reported by a build trigger; {@code null} means "the engine reported none". */
    String reportedCommit;

    @Override
    public String name() {
        return "LIB-STUB";
    }

    @Override
    public TriggerResult triggerBuild(BuildCmd cmd) {
        buildCalls.add(cmd);
        if (buildFailure != null) {
            throw buildFailure;
        }
        int seq = buildCalls.size();
        return new TriggerResult("build-" + seq, "https://engine.example/build/" + seq,
            reportedCommit);
    }

    @Override
    public TriggerResult triggerDeploy(DeployCmd cmd) {
        throw new UnsupportedOperationException("a LIB pipeline has no deploy");
    }

    @Override
    public TriggerResult startPublish(PublishCmd cmd) {
        publishCalls.add(cmd);
        if (publishFailure != null) {
            throw publishFailure;
        }
        return new TriggerResult("publish-" + publishCalls.size(),
            "https://engine.example/publish/" + publishCalls.size());
    }

    @Override
    public TriggerResult startCheck(GateCheckCmd cmd) {
        checkCalls.add(cmd);
        if (checkFailure != null) {
            throw checkFailure;
        }
        return new TriggerResult("check-" + checkCalls.size(),
            "https://engine.example/check/" + checkCalls.size());
    }

    @Override
    public GateCheckReport queryCheck(String externalId, CheckType checkType) {
        throw new UnsupportedOperationException("checks are finished by the test, not polled");
    }

    @Override
    public EngineResult query(String externalId, JobKind kind) {
        throw new UnsupportedOperationException("the library test does not poll jobs");
    }

    @Override
    public String logs(String externalId, JobKind kind, int offset, int limit) {
        return "";
    }

    @Override
    public void cancel(String externalId) {
        // No running engine job in the stub.
    }

    List<CheckType> checkedTypes() {
        return checkCalls.stream().map(GateCheckCmd::checkType).toList();
    }
}