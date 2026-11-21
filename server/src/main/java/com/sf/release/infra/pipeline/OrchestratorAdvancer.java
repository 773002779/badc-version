package com.sf.release.infra.pipeline;

import com.sf.release.domain.pipeline.PipelineOrchestrator;
import com.sf.release.domain.pipeline.port.PipelineAdvancer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * {@link PipelineAdvancer} backed by the {@link PipelineOrchestrator}.
 *
 * <p>The orchestrator is resolved lazily through {@link ObjectProvider}: the gate services are
 * dependencies of the {@code VERIFY} node handler, which the handler registry — and therefore the
 * orchestrator — is built from, so eager injection would be a construction cycle. Nothing is
 * resolved at startup; the first {@link #advance(long)} call looks the bean up.
 */
@Component
public class OrchestratorAdvancer implements PipelineAdvancer {

    private static final Logger log = LoggerFactory.getLogger(OrchestratorAdvancer.class);

    private final ObjectProvider<PipelineOrchestrator> orchestrator;

    public OrchestratorAdvancer(ObjectProvider<PipelineOrchestrator> orchestrator) {
        this.orchestrator = orchestrator;
    }

    @Override
    public void advance(long instanceId) {
        if (instanceId <= 0) {
            return;
        }
        PipelineOrchestrator target = orchestrator.getIfAvailable();
        if (target == null) {
            log.debug("no orchestrator available, advance skipped: instanceId={}", instanceId);
            return;
        }
        target.advance(instanceId);
    }
}