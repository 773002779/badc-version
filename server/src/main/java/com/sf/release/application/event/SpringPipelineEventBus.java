package com.sf.release.application.event;

import com.sf.release.domain.pipeline.event.InstanceFinishedEvent;
import com.sf.release.domain.pipeline.event.NodeCompletedEvent;
import com.sf.release.domain.pipeline.event.NodeTaskCompletedEvent;
import com.sf.release.domain.pipeline.event.TestEnvReadyEvent;
import com.sf.release.domain.pipeline.port.PipelineEventBus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * Bridges the domain pipeline events to Spring's {@link ApplicationEventPublisher}. Publishing
 * is synchronous and happens inside the orchestrator's transaction, so an application listener
 * (for example {@link PipelineEventListener}) updates the 变更项 close to the pipeline status
 * change.
 *
 * <p><strong>A listener may not break the pipeline.</strong> Notifications, audits and gate
 * hooks are side effects of an advance, not a step of it: a listener that throws is caught and
 * logged here (tech design §6.4 asks for visibility, not for a lost pipeline). The trade-off is
 * deliberate and documented: an item-state write that fails inside a listener no longer rolls
 * back the pipeline transaction, so the pipeline stays consistent and the item update must be
 * repaired by its own retry/reconcile path.
 */
@Component
public class SpringPipelineEventBus implements PipelineEventBus {

    private static final Logger log = LoggerFactory.getLogger(SpringPipelineEventBus.class);

    private final ApplicationEventPublisher publisher;

    public SpringPipelineEventBus(ApplicationEventPublisher publisher) {
        this.publisher = Objects.requireNonNull(publisher, "publisher must not be null");
    }

    @Override
    public void publish(NodeTaskCompletedEvent event) {
        publishSafely(event);
    }

    @Override
    public void publish(NodeCompletedEvent event) {
        publishSafely(event);
    }

    @Override
    public void publish(InstanceFinishedEvent event) {
        publishSafely(event);
    }

    @Override
    public void publish(TestEnvReadyEvent event) {
        publishSafely(event);
    }

    private void publishSafely(Object event) {
        try {
            publisher.publishEvent(event);
        } catch (RuntimeException ex) {
            log.error("pipeline event listener failed: eventType={}, reason={}",
                event.getClass().getSimpleName(), ex.getMessage(), ex);
        }
    }
}