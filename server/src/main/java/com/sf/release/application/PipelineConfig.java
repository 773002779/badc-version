package com.sf.release.application;

import com.sf.release.domain.engine.CicdEngine;
import com.sf.release.domain.pipeline.NodeHandler;
import com.sf.release.domain.pipeline.NodeHandlerRegistry;
import com.sf.release.domain.pipeline.NodeTaskCompletionService;
import com.sf.release.domain.pipeline.PipelineOrchestrator;
import com.sf.release.domain.pipeline.RerunPlanner;
import com.sf.release.domain.pipeline.handler.BuildNodeHandler;
import com.sf.release.domain.pipeline.handler.TestDeployNodeHandler;
import com.sf.release.domain.pipeline.port.GitCommitProbe;
import com.sf.release.domain.pipeline.port.PipelineEventBus;
import com.sf.release.domain.pipeline.port.PipelineLock;
import com.sf.release.domain.pipeline.port.PipelineRepository;
import com.sf.release.domain.pipeline.port.TransactionRunner;
import com.sf.release.domain.pipeline.port.UnitLease;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the plain-domain orchestrator to its ports. The domain classes carry no Spring
 * annotation (ArchUnit keeps the domain framework-free), so their beans are declared here.
 *
 * <p>{@link NodeHandler}s are collected from the context: Task 12/13 handlers are declared as
 * beans here (Task 14 carry-over), and an empty registry is valid at startup — the failure is
 * raised loudly when a node is entered without a handler, not at boot.
 */
@Configuration
public class PipelineConfig {

    @Bean
    public BuildNodeHandler buildNodeHandler(CicdEngine engine,
                                             PipelineRepository repository,
                                             TransactionRunner transactionRunner,
                                             NodeTaskCompletionService completionService) {
        return new BuildNodeHandler(engine, repository, transactionRunner, completionService);
    }

    @Bean
    public TestDeployNodeHandler testDeployNodeHandler(CicdEngine engine,
                                                       PipelineRepository repository,
                                                       TransactionRunner transactionRunner,
                                                       NodeTaskCompletionService completionService,
                                                       PipelineEventBus eventBus) {
        return new TestDeployNodeHandler(engine, repository, transactionRunner, completionService,
            eventBus);
    }

    @Bean
    public NodeHandlerRegistry nodeHandlerRegistry(ObjectProvider<NodeHandler> handlers) {
        NodeHandlerRegistry registry = new NodeHandlerRegistry();
        handlers.orderedStream().forEach(registry::register);
        return registry;
    }

    @Bean
    public NodeTaskCompletionService nodeTaskCompletionService(PipelineRepository repository,
                                                              PipelineEventBus eventBus) {
        return new NodeTaskCompletionService(repository, eventBus);
    }

    @Bean
    public PipelineOrchestrator pipelineOrchestrator(PipelineRepository repository,
                                                     NodeTaskCompletionService completionService,
                                                     PipelineLock pipelineLock,
                                                     UnitLease unitLease,
                                                     TransactionRunner transactionRunner,
                                                     NodeHandlerRegistry handlerRegistry,
                                                     PipelineEventBus eventBus) {
        return new PipelineOrchestrator(repository, completionService, pipelineLock, unitLease,
            transactionRunner, handlerRegistry, eventBus);
    }

    /**
     * The rerun scope decision (Task 15). The planner is plain domain: its git probe is the
     * infrastructure adapter and its last-instance lookup is the pipeline repository.
     */
    @Bean
    public RerunPlanner rerunPlanner(GitCommitProbe commitProbe, PipelineRepository repository) {
        return new RerunPlanner(commitProbe, repository::latestInstanceOf);
    }
}