package com.sf.release.infra.job;

import com.sf.release.application.notification.NotificationDispatchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * The 15-second WeCom delivery loop (design section 5). It has its own switch
 * ({@code release.notification.dispatch-enabled}) so the test profile does not race a background
 * thread against the shared schema — the tests call
 * {@link NotificationDispatchService#dispatchDue(int)} explicitly, like the other job tests.
 *
 * <p><strong>No distributed lock.</strong> A single instance takes the pending rows in id order; the
 * design accepts at-least-once delivery when more than one runner is deployed (section 9). A failed
 * round is logged and the next round retries the rows that are still {@code PENDING}.
 */
public class NotificationDispatchJob {

    private static final Logger log = LoggerFactory.getLogger(NotificationDispatchJob.class);

    /** The design's batch of one round. */
    static final int BATCH = NotificationDispatchService.DEFAULT_BATCH;

    private final NotificationDispatchService dispatchService;

    public NotificationDispatchJob(NotificationDispatchService dispatchService) {
        this.dispatchService = dispatchService;
    }

    @Scheduled(fixedDelay = 15_000L)
    public void dispatchPending() {
        try {
            int finished = dispatchService.dispatchDue(BATCH);
            if (finished > 0) {
                log.info("notification dispatch round finished: rows={}", finished);
            }
        } catch (RuntimeException ex) {
            log.error("notification dispatch round failed; the next round retries", ex);
        }
    }
}