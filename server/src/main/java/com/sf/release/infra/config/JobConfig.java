package com.sf.release.infra.config;

import com.sf.release.domain.pipeline.PipelineOrchestrator;
import com.sf.release.domain.pipeline.port.DistributedLock;
import com.sf.release.domain.pipeline.port.ItemFailureRepairer;
import com.sf.release.domain.pipeline.port.PipelineRepository;
import com.sf.release.infra.engine.EngineRegistry;
import com.sf.release.infra.job.ReconcileJob;
import com.sf.release.infra.repo.NodeTaskMapper;
import com.sf.release.infra.repo.PipelineInstanceMapper;
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
                                     PipelineInstanceMapper instanceMapper,
                                     ItemFailureRepairer failureRepairer,
                                     @Value("${release.engine.type:CODING}") String engineType) {
        return new ReconcileJob(lock, engines, engineType, repository, orchestrator, taskMapper,
            instanceMapper, failureRepairer);
    }
}