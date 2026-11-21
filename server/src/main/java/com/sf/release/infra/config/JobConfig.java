package com.sf.release.infra.config;

import com.sf.release.application.GateCheckService;
import com.sf.release.application.GateService;
import com.sf.release.application.GateWaiverService;
import com.sf.release.domain.pipeline.PipelineOrchestrator;
import com.sf.release.domain.pipeline.port.DistributedLock;
import com.sf.release.domain.pipeline.port.ItemFailureRepairer;
import com.sf.release.domain.pipeline.port.PipelineRepository;
import com.sf.release.infra.engine.EngineRegistry;
import com.sf.release.infra.job.GateReconcileJob;
import com.sf.release.infra.job.ReconcileJob;
import com.sf.release.infra.repo.NodeTaskMapper;
import com.sf.release.infra.repo.PipelineInstanceMapper;
import com.sf.release.infra.repo.PipelineNodeMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Declares the scheduled jobs. {@link ReconcileJob} is conditional on
 * {@code release.reconcile.enabled} (default {@code true}); the test profile disables it because
 * the suite shares one schema and intends to invoke {@link ReconcileJob#run()} deterministically
 * instead of racing a background thread.
 */
@Configuration(proxyBeanMethods = false)
public class JobConfig {

    @Bean
    @ConditionalOnProperty(name = "release.reconcile.enabled", havingValue = "true",
        matchIfMissing = true)
    public ReconcileJob reconcileJob(DistributedLock lock,
                                     EngineRegistry engines,
                                     PipelineRepository repository,
                                     PipelineOrchestrator orchestrator,
                                     NodeTaskMapper taskMapper,
                                     PipelineNodeMapper nodeMapper,
                                     PipelineInstanceMapper instanceMapper,
                                     ItemFailureRepairer failureRepairer,
                                     @Value("${release.engine.type:CODING}") String engineType) {
        return new ReconcileJob(lock, engines, engineType, repository, orchestrator, taskMapper,
            nodeMapper, instanceMapper, failureRepairer);
    }

    /**
     * The gate half of the polling backstop: check polling (30s) and waiver expiry (daily). It has
     * its own switch because it holds a different lock and can be tuned separately; the test profile
     * turns it off so the shared schema is not mutated by a background thread.
     */
    @Bean
    @ConditionalOnProperty(name = "release.gate.reconcile-enabled", havingValue = "true",
        matchIfMissing = true)
    public GateReconcileJob gateReconcileJob(DistributedLock lock,
                                             GateCheckService checkService,
                                             GateService gateService,
                                             GateWaiverService waiverService) {
        return new GateReconcileJob(lock, checkService, gateService, waiverService);
    }
}