package com.sf.release.application.event;

import com.sf.release.domain.pipeline.event.InstanceFinishedEvent;
import com.sf.release.domain.pipeline.event.NodeCompletedEvent;
import com.sf.release.domain.pipeline.event.NodeTaskCompletedEvent;
import com.sf.release.domain.pipeline.port.PipelineEventBus;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * Bridges the domain pipeline events to Spring's {@link ApplicationEventPublisher}. Publishing
 * is synchronous and happens inside the orchestrator's transaction, so an application listener
 * (for example {@link PipelineEventListener}) updates the 变更项 in the same commit as the
 * pipeline status change.
 */
@Component
public class SpringPipelineEventBus implements PipelineEventBus {

    private final ApplicationEventPublisher publisher;

    public SpringPipelineEventBus(ApplicationEventPublisher publisher) {
        this.publisher = Objects.requireNonNull(publisher, "publisher must not be null");
    }

    @Override
    public void publish(NodeTaskCompletedEvent event) {
        publisher.publishEvent(event);
    }

    @Override
    public void publish(NodeCompletedEvent event) {
        publisher.publishEvent(event);
    }

    @Override
    public void publish(InstanceFinishedEvent event) {
        publisher.publishEvent(event);
    }
}