package com.sf.release.infra.job;

import com.sf.release.application.notification.NotificationAudiences;
import com.sf.release.application.notification.NotificationCommand;
import com.sf.release.application.notification.NotificationService;
import com.sf.release.domain.notification.NotificationEventKey;
import com.sf.release.domain.pipeline.port.UnitLease;
import com.sf.release.infra.repo.DeployUnitLockMapper;
import com.sf.release.infra.repo.po.LockedInstanceRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * The 60s 部署单元 lease renewer (P3 fix round 1, review M2): the unit lock is a four-hour
 * {@code lock:unit:*} lease plus the {@code deploy_unit_lock} row, and a pipeline can legitimately
 * stay paused at {@code PROD_DEPLOY} for much longer than that — waiting for an approval, for a
 * freeze to be lifted, or for the next production batch window.
 *
 * <p><strong>What it renews.</strong> Only the lock rows whose owning instance is still
 * {@code RUNNING} or {@code PAUSED}: the row is what blocks another publisher after a restart lost
 * the in-memory/Redis key, so letting it expire while the instance is alive would let a concurrent
 * publish reclaim the unit. A terminal instance is deliberately skipped — its lease is supposed to
 * end, and the orchestrator releases it. The scan is bounded ({@code LIMIT}) so one round is cheap
 * even with a large backlog; the next round starts at the lowest unit id again.
 *
 * <p><strong>An abandoned pause stops being renewed.</strong> Renewing a {@code PAUSED} row
 * unconditionally would hold the unit forever (independent review I3): a rejected release nobody
 * resubmits, or a pause nobody resumes, has no close/force-release API in this phase. The job
 * therefore stops extending a pause older than {@link #maxPaused} (configurable, 7 days by default);
 * its four-hour lease then expires and the next publish may reclaim the unit. A pause recorded
 * before V24 has no {@code paused_at} and keeps the previous unbounded behaviour; the first pause
 * after the migration records the value, so the bound applies from then on. A resumed instance is
 * renewed again from zero.
 *
 * <p><strong>No distributed lock.</strong> Extending a lease is idempotent (the DB row is
 * ownership-scoped and the key renewal is a no-op for a key somebody else holds), so two runners
 * writing the same value are harmless; unlike the engine polling jobs this round needs no leader.
 * A failed renewal is logged, not thrown: the job must keep renewing the other units.
 */
public class UnitLeaseRenewJob {

    private static final Logger log = LoggerFactory.getLogger(UnitLeaseRenewJob.class);

    /** Rows renewed per round; a backlog is worked off over the following rounds. */
    static final int RENEW_LIMIT = 200;

    /** Default bound of an abandoned pause; long enough for a freeze or a rejected release. */
    public static final Duration DEFAULT_MAX_PAUSED = Duration.ofDays(7);

    private final DeployUnitLockMapper lockMapper;
    private final UnitLease unitLease;
    private final NotificationService notifications;
    private final Duration maxPaused;

    /** Test/legacy constructor: no notification subsystem (the lease logic stays unchanged). */
    public UnitLeaseRenewJob(DeployUnitLockMapper lockMapper, UnitLease unitLease) {
        this(lockMapper, unitLease, null, DEFAULT_MAX_PAUSED);
    }

    /**
     * @param maxPaused how long a paused instance keeps its lease renewed; once the pause is older,
     *                  the lease is left to expire so the unit can be reclaimed
     */
    public UnitLeaseRenewJob(DeployUnitLockMapper lockMapper, UnitLease unitLease,
                             Duration maxPaused) {
        this(lockMapper, unitLease, null, maxPaused);
    }

    /**
     * The production constructor: the same renewal rules, plus the {@code LOCK_TIMEOUT_RELEASED}
     * notification when a pause crosses the bound (P4-B notification design section 3/4.4).
     *
     * @param notifications the notification service; {@code null} disables the notification (used by
     *                      the lease tests that construct the job directly)
     */
    public UnitLeaseRenewJob(DeployUnitLockMapper lockMapper, UnitLease unitLease,
                             NotificationService notifications, Duration maxPaused) {
        if (maxPaused == null || maxPaused.isNegative() || maxPaused.isZero()) {
            throw new IllegalArgumentException("maxPaused must be a positive duration");
        }
        this.lockMapper = lockMapper;
        this.unitLease = unitLease;
        this.notifications = notifications;
        this.maxPaused = maxPaused;
    }

    /** Every 60 seconds: well inside the four-hour lease, so one missed round is not fatal. */
    @Scheduled(fixedDelay = 60_000L)
    public void renewLeases() {
        try {
            Instant now = Instant.now();
            Instant cutoff = now.minus(maxPaused);
            List<LockedInstanceRow> owners = lockMapper.selectLiveOwners(RENEW_LIMIT);
            int renewed = 0;
            int lost = 0;
            int abandoned = 0;
            for (LockedInstanceRow owner : owners) {
                if (owner.getUnitId() == null || owner.getInstanceId() == null) {
                    continue;
                }
                if (isAbandonedPause(owner, cutoff)) {
                    abandoned++;
                    notifyAbandoned(owner);
                    continue;
                }
                try {
                    if (unitLease.renew(owner.getUnitId(), owner.getInstanceId())) {
                        renewed++;
                    } else {
                        lost++;
                    }
                } catch (RuntimeException ex) {
                    log.warn("unit lease renew failed: unitId={}, instanceId={}",
                        owner.getUnitId(), owner.getInstanceId(), ex);
                    lost++;
                }
            }
            if (renewed > 0 || lost > 0 || abandoned > 0) {
                log.info("unit lease renew round: renewed={}, lost={}, abandonedPauses={},"
                    + " candidates={}", renewed, lost, abandoned, owners.size());
            }
            if (abandoned > 0) {
                log.warn("{} paused instance(s) outlived the {} pause bound and will lose their"
                        + " unit lease: a release paused this long must be re-published; the unit is"
                        + " reclaimed when the four-hour lease expires", abandoned, maxPaused);
            }
        } catch (RuntimeException ex) {
            log.error("unit lease renew round failed; the next round retries", ex);
        }
    }

    /**
     * A paused instance whose pause is older than the bound. Rows without {@code paused_at} (paused
     * before V24) cannot be judged from that column; the row is kept alive exactly as before, so a
     * legacy pause is not cut short by a missing value (the next pause records it).
     */
    private static boolean isAbandonedPause(LockedInstanceRow owner, Instant cutoff) {
        if (!"PAUSED".equals(owner.getStatus())) {
            return false;
        }
        Instant pausedAt = owner.getPausedAt();
        return pausedAt != null && pausedAt.isBefore(cutoff);
    }

    /**
     * Announces that an abandoned pause will lose its unit lease (design section 3,
     * {@code LOCK_TIMEOUT_RELEASED}). The dedupe key uses the instance id, so the notification is
     * written once even though every following round sees the same abandoned pause again.
     */
    private void notifyAbandoned(LockedInstanceRow owner) {
        if (notifications == null || owner.getUnitId() == null || owner.getInstanceId() == null) {
            return;
        }
        long unitId = owner.getUnitId();
        String limit = maxPaused.toHours() > 0
            ? maxPaused.toHours() + " 小时" : maxPaused.toMinutes() + " 分钟";
        NotificationCommand command = new NotificationCommand(
            NotificationEventKey.LOCK_TIMEOUT_RELEASED,
            String.valueOf(unitId),
            null,
            "锁超时释放",
            "部署单元 #" + unitId + " 的流水线暂停已超过允许上限（" + limit
                + "），租约到期后该单元将被释放；如需继续发布，请重新发起。",
            String.valueOf(owner.getInstanceId()),
            NotificationAudiences.lockTimeout(unitId));
        notifications.notify(command);
    }
}