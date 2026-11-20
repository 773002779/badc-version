package com.sf.release.domain.pipeline.event;

import java.time.Instant;
import java.util.Objects;

/**
 * The test environment of an online pipeline is ready: every TEST DE of the {@code TEST_DEPLOY}
 * node deployed successfully, so testers may start validating the release.
 *
 * <p>Published at most once per instance, <em>after</em> the completion transaction of the last
 * successful deploy has committed (the handler hook runs post-commit and opens no transaction of
 * its own around the publication). A partially successful node never publishes this event: a
 * failed DE keeps its own failed row and the orchestrator fails the instance, while the
 * already-successful DE rows stay {@code SUCCESS} (tech design §5.2 rule 3).
 *
 * <p>{@code publishNo} is the business number of the release order
 * ({@code release_order.publish_no}), stable across reruns; {@code instanceId} identifies the
 * execution that produced the ready environment. The recipient resolution and the notification
 * channels are application-layer concerns of later tasks.
 */
public record TestEnvReadyEvent(String publishNo, long instanceId, Instant readyAt) {

    public TestEnvReadyEvent {
        Objects.requireNonNull(publishNo, "publishNo must not be null");
        Objects.requireNonNull(readyAt, "readyAt must not be null");
    }
}