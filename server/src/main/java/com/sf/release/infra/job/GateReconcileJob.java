package com.sf.release.infra.job;

import com.sf.release.application.GateCheckService;
import com.sf.release.application.GateService;
import com.sf.release.application.GateWaiverService;
import com.sf.release.domain.pipeline.port.DistributedLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * The gate half of the 30s polling backstop (tech design §7.1/§7.3): polls the checks the engine
 * still owes us, re-evaluates the affected gates, and expires waivers once a day.
 *
 * <p><strong>Why a separate job.</strong> The pipeline reconcile ({@link ReconcileJob}) plays
 * advances and task completions back; the gate reconcile owns asynchronously reported check
 * results and waiver expiry. Keeping them apart keeps each lock's scope honest — the gate job holds
 * {@link #LOCK_KEY}, never the pipeline lock — and lets the check interval be tuned independently
 * (a 30s check poll is right; a 30s waiver expiry scan is not).
 *
 * <p><strong>A failed poll is not a failed check.</strong> An unreachable engine leaves the check
 * {@code UNKNOWN}; the gate then reads "not finished" and the next round retries. The job never
 * turns an outage into a verdict, and it never reschedules a finished check.
 */
public class GateReconcileJob {

    /** Distributed key of the single-runner guard. */
    public static final String LOCK_KEY = "lock:job:gate-reconcile";

    /** Lease of the poll lock; a crashed round blocks another runner for at most this long. */
    public static final Duration LOCK_TTL = Duration.ofMinutes(2);

    /** Upper bound of checks/waivers handled per round. */
    static final int SCAN_LIMIT = 200;

    private static final Logger log = LoggerFactory.getLogger(GateReconcileJob.class);

    private final DistributedLock lock;
    private final GateCheckService checkService;
    private final GateService gateService;
    private final GateWaiverService waiverService;

    public GateReconcileJob(DistributedLock lock, GateCheckService checkService,
                            GateService gateService, GateWaiverService waiverService) {
        this.lock = lock;
        this.checkService = checkService;
        this.gateService = gateService;
        this.waiverService = waiverService;
    }

    /**
     * Polls due checks and re-evaluates every item whose result changed. Re-evaluation is what
     * advances the pipeline: a finished check can complete the {@code VERIFY} node, and a blocking
     * one fails it.
     */
    @Scheduled(fixedDelay = 30_000)
    public void pollChecks() {
        if (!lock.tryLock(LOCK_KEY, LOCK_TTL)) {
            log.debug("gate reconcile round skipped: {} is held by another runner", LOCK_KEY);
            return;
        }
        try {
            List<Long> changed = checkService.pollDue(Instant.now(), SCAN_LIMIT);
            for (Long itemId : changed) {
                try {
                    gateService.evaluate(itemId);
                } catch (RuntimeException ex) {
                    log.error("gate evaluation failed after a check result: itemId={}", itemId, ex);
                }
            }
            if (!changed.isEmpty()) {
                log.info("gate reconcile round finished: changedItems={}", changed.size());
            }
        } catch (RuntimeException ex) {
            log.error("gate reconcile round failed; the next round retries", ex);
        } finally {
            lock.unlock(LOCK_KEY);
        }
    }

    /**
     * Expires approved waivers whose validity passed (requirements §4.6: 豁免到期 → 视为
     * {@code CHECK_INVALID}). Once a day at 00:05: an expiry is a calendar event, not something a
     * 30s loop needs to watch.
     */
    @Scheduled(cron = "0 5 0 * * *")
    public void expireWaivers() {
        if (!lock.tryLock(LOCK_KEY, LOCK_TTL)) {
            log.debug("waiver expiry skipped: {} is held by another runner", LOCK_KEY);
            return;
        }
        try {
            int expired = waiverService.expireDue(Instant.now(), SCAN_LIMIT);
            if (expired > 0) {
                log.info("waiver expiry scan finished: expired={}", expired);
            }
        } catch (RuntimeException ex) {
            log.error("waiver expiry scan failed; tomorrow's run retries", ex);
        } finally {
            lock.unlock(LOCK_KEY);
        }
    }
}