package com.sf.release.infra.job;

import com.sf.release.application.reminder.ApprovalReminderService;
import com.sf.release.application.reminder.ApprovalReminderService.ScanResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Clock;

/**
 * The P6-E approval-SLA scan (design §1): every ten minutes it asks
 * {@link ApprovalReminderService#scan(java.time.Instant, int)} to remind the steps past 12 hours and
 * escalate the ones past 24 hours.
 *
 * <p><strong>Why a separate bean.</strong> It has its own switch
 * ({@code release.approval.reminder-enabled}, default true) so the test profile does not race a
 * background thread against the shared schema — the tests invoke the service (or this job) with a
 * fixed clock. It is wired in {@code JobConfig} like the freeze expiry and notification dispatch
 * jobs.
 *
 * <p><strong>No distributed lock.</strong> Two runners would read the same due rows, but the
 * notification unique key collapses them to one row per recipient/channel/level; the scan is an
 * idempotent read plus unique-key inserts, not a state transition.
 *
 * <p><strong>The clock is injected</strong> so the "已等待 N 小时" figure and the two thresholds come
 * from one testable source instead of a scattered {@code Instant.now()}.
 */
public class ApprovalReminderJob {

    private static final Logger log = LoggerFactory.getLogger(ApprovalReminderJob.class);

    /** Rows read per round; a backlog is worked off over the following rounds. */
    static final int SCAN_LIMIT = 200;

    private final ApprovalReminderService reminders;
    private final Clock clock;

    public ApprovalReminderJob(ApprovalReminderService reminders, Clock clock) {
        this.reminders = reminders;
        this.clock = clock;
    }

    /** Every ten minutes: well inside the hour-scale thresholds the SLA measures. */
    @Scheduled(fixedDelay = 600_000L)
    public void scanApprovals() {
        try {
            ScanResult result = reminders.scan(clock.instant(), SCAN_LIMIT);
            if (result.reminded() > 0 || result.escalated() > 0) {
                log.info("approval reminder scan finished: due={}, reminded={}, escalated={}",
                    result.scanned(), result.reminded(), result.escalated());
            }
        } catch (RuntimeException ex) {
            log.error("approval reminder scan failed; the next round retries", ex);
        }
    }
}
