package com.sf.release.infra.job;

import com.sf.release.domain.pipeline.port.UnitLease;
import com.sf.release.infra.repo.DeployUnitLockMapper;
import com.sf.release.infra.repo.po.LockedInstanceRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

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
 * <p><strong>No distributed lock.</strong> Extending a lease is idempotent (the DB row is
 * ownership-scoped and the key renewal is a no-op for a key somebody else holds), so two runners
 * writing the same value are harmless; unlike the engine polling jobs this round needs no leader.
 * A failed renewal is logged, not thrown: the job must keep renewing the other units.
 */
public class UnitLeaseRenewJob {

    private static final Logger log = LoggerFactory.getLogger(UnitLeaseRenewJob.class);

    /** Rows renewed per round; a backlog is worked off over the following rounds. */
    static final int RENEW_LIMIT = 200;

    private final DeployUnitLockMapper lockMapper;
    private final UnitLease unitLease;

    public UnitLeaseRenewJob(DeployUnitLockMapper lockMapper, UnitLease unitLease) {
        this.lockMapper = lockMapper;
        this.unitLease = unitLease;
    }

    /** Every 60 seconds: well inside the four-hour lease, so one missed round is not fatal. */
    @Scheduled(fixedDelay = 60_000L)
    public void renewLeases() {
        try {
            List<LockedInstanceRow> owners = lockMapper.selectLiveOwners(RENEW_LIMIT);
            int renewed = 0;
            int lost = 0;
            for (LockedInstanceRow owner : owners) {
                if (owner.getUnitId() == null || owner.getInstanceId() == null) {
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
            if (renewed > 0 || lost > 0) {
                log.info("unit lease renew round: renewed={}, lost={}, candidates={}", renewed,
                    lost, owners.size());
            }
        } catch (RuntimeException ex) {
            log.error("unit lease renew round failed; the next round retries", ex);
        }
    }
}