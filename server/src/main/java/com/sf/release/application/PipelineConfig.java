package com.sf.release.application;

import com.sf.release.domain.pipeline.NodeHandler;
import com.sf.release.domain.pipeline.NodeHandlerRegistry;
import com.sf.release.domain.pipeline.NodeTaskCompletionService;
import com.sf.release.domain.pipeline.PipelineOrchestrator;
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
 * <p>{@link NodeHandler}s are collected from the context: Task 12/13 register them from
 * configuration, and an empty registry is valid at startup — the failure is raised loudly when
 * a node is entered without a handler, not at boot.
 */
@Configuration
public class PipelineConfig {

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
}