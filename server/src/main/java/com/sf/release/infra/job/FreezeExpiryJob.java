package com.sf.release.infra.job;

import com.sf.release.application.FreezeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Instant;

/**
 * The daily 封板 expiry scan (tech design 4.2: "到期自动失效由每日扫描处理"). It flips the
 * {@code ACTIVE} freezes whose {@code end_at} passed to {@code EXPIRED}; a lift by a person
 * ({@code RELEASED}) is never touched.
 *
 * <p><strong>Why a separate bean.</strong> It has its own switch
 * ({@code release.freeze.expiry-enabled}) so the test profile does not race a background thread
 * against the shared schema — the tests invoke {@link FreezeService#expireDue(Instant)} themselves,
 * exactly like the gate and pipeline reconcile jobs.
 *
 * <p><strong>No distributed lock.</strong> The scan is an idempotent status flip
 * ({@code UPDATE ... WHERE status = 'ACTIVE' AND end_at <= now}); two runners would write the same
 * value, and the row count of the second one is zero. The gate job needs its lock because polling an
 * engine twice is not idempotent, this one does not.
 */
public class FreezeExpiryJob {

    private static final Logger log = LoggerFactory.getLogger(FreezeExpiryJob.class);

    private final FreezeService freezeService;

    public FreezeExpiryJob(FreezeService freezeService) {
        this.freezeService = freezeService;
    }

    /** Once a day at 00:20: an expiry is a calendar event, not something a short loop watches. */
    @Scheduled(cron = "0 20 0 * * *")
    public void expireFreezes() {
        try {
            int expired = freezeService.expireDue(Instant.now());
            if (expired > 0) {
                log.info("freeze expiry scan finished: expired={}", expired);
            }
        } catch (RuntimeException ex) {
            log.error("freeze expiry scan failed; tomorrow's run retries", ex);
        }
    }
}