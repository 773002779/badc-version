package com.sf.release.domain.pipeline.port;

import com.sf.release.domain.pipeline.event.InstanceFinishedEvent;
import com.sf.release.domain.pipeline.event.NodeCompletedEvent;
import com.sf.release.domain.pipeline.event.NodeTaskCompletedEvent;
import com.sf.release.domain.pipeline.event.TestEnvReadyEvent;

/**
 * Publishes the pipeline domain events. The implementation bridges them to the Spring
 * {@code ApplicationEventPublisher} ({@code application/event}), so the domain stays free of
 * Spring types.
 */
public interface PipelineEventBus {

    void publish(NodeTaskCompletedEvent event);

    void publish(NodeCompletedEvent event);

    void publish(InstanceFinishedEvent event);

    void publish(TestEnvReadyEvent event);
}