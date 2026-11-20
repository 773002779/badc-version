package com.sf.release.infra.lock;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sf.release.domain.pipeline.InstanceStatus;
import com.sf.release.domain.pipeline.port.DistributedLock;
import com.sf.release.domain.pipeline.port.UnitLease;
import com.sf.release.infra.repo.DeployUnitLockMapper;
import com.sf.release.infra.repo.PipelineInstanceMapper;
import com.sf.release.infra.repo.po.DeployUnitLockPO;
import com.sf.release.infra.repo.po.PipelineInstancePO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Mutual exclusion on a 部署单元 (tech design §4.3, §9): {@code lock:unit:{unitId}} holds the
 * Redis lease (4 h) and the {@code deploy_unit_lock} row is the database backstop whose
 * primary key rejects a second holder.
 *
 * <p><strong>Two levels on purpose.</strong> A live DB row always wins: if it exists and its
 * {@code expire_at} is still in the future, the unit is locked even when the distributed key is
 * free (for example after an application restart cleared an in-memory lease). An expired row
 * does <em>not</em> immediately become reclaimable: if it still names a pipeline instance in a
 * non-terminal status ({@code PENDING}/{@code RUNNING}), the lease is renewed instead and
 * {@link #acquire} returns {@code false}. Only a terminal owner, a missing owner instance or
 * the pre-bind sentinel {@code 0} lets a caller reclaim the row — this is what prevents a
 * long-running pipeline from silently losing its unit at the 4-hour mark. When the row is
 * reclaimable, the stale distributed key left by the old holder is dropped before taking it.
 *
 * <p><strong>Renewal.</strong> {@link #renew} extends both levels for the owning instance. The
 * pipeline orchestrator (Task 11) must call it on each advance; Task 14 adds a scheduled
 * renewer so a long build/test/prod run keeps the unit. {@code DeployUnitLockMapper.forceExpire}
 * remains the test/administration seam for a row that must be reclaimed before its lease ends
 * (the owning instance is then treated as terminal/unknown).
 *
 * <p><strong>Reservation.</strong> {@code acquire} runs before the pipeline instance exists, so
 * it stores the sentinel {@code instance_id = 0}. The publishing transaction calls
 * {@link #bindInstance} once the instance has an id. Releasing is ownership-scoped: only the
 * row of the given instance is deleted, and the distributed key is touched only when that row
 * was actually deleted — a caller can never free a new holder's lock on the "lock lost" path.
 */
@Service
public class UnitLockService implements UnitLease {

    private static final Logger log = LoggerFactory.getLogger(UnitLockService.class);

    /** Unit lock lease (tech design §9: {@code leaseTime = 4h}). */
    public static final Duration TTL = Duration.ofHours(4);

    /** Key prefix of the unit lock ({@code lock:unit:{unitId}}). */
    public static final String KEY_PREFIX = "lock:unit:";

    /** Placeholder used while the publishing transaction has not created the instance yet. */
    public static final long UNBOUND_INSTANCE_ID = 0L;

    private static final int ACQUIRE_ATTEMPTS = 2;

    private final DistributedLock distributedLock;
    private final DeployUnitLockMapper lockMapper;
    private final PipelineInstanceMapper instanceMapper;

    public UnitLockService(DistributedLock distributedLock,
                           DeployUnitLockMapper lockMapper,
                           PipelineInstanceMapper instanceMapper) {
        this.distributedLock = distributedLock;
        this.lockMapper = lockMapper;
        this.instanceMapper = instanceMapper;
    }

    /**
     * Tries to take the unit lock for {@code instanceId}, or renews the holder's lease when the
     * unit still belongs to a pipeline that is running.
     *
     * @return {@code true} when this call holds both levels; {@code false} when the unit is
     *     held by somebody else (including a live expired owner, whose lease is renewed here)
     */
    public boolean acquire(long unitId, long instanceId) {
        String key = KEY_PREFIX + unitId;
        Instant now = Instant.now();
        for (int attempt = 1; attempt <= ACQUIRE_ATTEMPTS; attempt++) {
            DeployUnitLockPO existing = lockMapper.selectById(unitId);
            if (isLive(existing, now)) {
                return false;
            }
            if (holdsLiveInstance(existing)) {
                renewOwner(existing);
                return false;
            }
            if (!distributedLock.tryLock(key, TTL)) {
                // The key is held but the DB row is gone or expired. Without an expired row as
                // evidence we must not steal the key (a holder may not have inserted yet).
                DeployUnitLockPO latest = lockMapper.selectById(unitId);
                if (latest == null || isLive(latest, now)) {
                    return false;
                }
                if (holdsLiveInstance(latest)) {
                    renewOwner(latest);
                    return false;
                }
                log.warn("reclaiming stale unit lock key: unitId={}, attempt={}", unitId, attempt);
                distributedLock.unlock(key);
                continue;
            }
            boolean keyHeld = false;
            try {
                DeployUnitLockPO latest = lockMapper.selectById(unitId);
                if (isLive(latest, now)) {
                    return false;
                }
                if (holdsLiveInstance(latest)) {
                    // We now hold the key, so renewing it heals the live owner's lost lease;
                    // keep it (keyHeld = true) instead of releasing it back to the pool.
                    renewOwner(latest);
                    keyHeld = true;
                    return false;
                }
                if (latest != null) {
                    lockMapper.deleteById(unitId);
                }
                lockMapper.insert(DeployUnitLockPO.of(unitId, instanceId, now, now.plus(TTL)));
                keyHeld = true;
                return true;
            } catch (DuplicateKeyException ex) {
                // A concurrent publisher inserted its row between our read and our insert.
                return false;
            } finally {
                if (!keyHeld) {
                    distributedLock.unlock(key);
                }
            }
        }
        return false;
    }

    /** Binds the real pipeline instance id to a lock row reserved by {@link #acquire}. */
    public boolean bindInstance(long unitId, long instanceId) {
        int rows = lockMapper.update(null, Wrappers.<DeployUnitLockPO>lambdaUpdate()
            .eq(DeployUnitLockPO::getUnitId, unitId)
            .set(DeployUnitLockPO::getInstanceId, instanceId));
        return rows == 1;
    }

    /**
     * Extends both levels of the lease for {@code instanceId}. Called by the orchestrator on
     * each advance (Task 11) and by a scheduled renewer later (Task 14), possibly from a
     * different thread than the publisher.
     *
     * @return {@code true} when the row still belongs to {@code instanceId} and both levels
     *     were extended; {@code false} when the lock was lost or the row belongs to somebody else
     */
    @Override
    public boolean renew(long unitId, long instanceId) {
        DeployUnitLockPO row = lockMapper.selectById(unitId);
        if (row == null || row.getInstanceId() == null
            || row.getInstanceId().longValue() != instanceId) {
            return false;
        }
        boolean keyRenewed = distributedLock.renew(KEY_PREFIX + unitId, TTL);
        Instant now = Instant.now();
        int rows = lockMapper.update(null, Wrappers.<DeployUnitLockPO>lambdaUpdate()
            .eq(DeployUnitLockPO::getUnitId, unitId)
            .eq(DeployUnitLockPO::getInstanceId, instanceId)
            .set(DeployUnitLockPO::getExpireAt, now.plus(TTL)));
        if (!keyRenewed) {
            // The DB lease is extended on purpose: a live pipeline must keep the unit blocked
            // even if the distributed key was lost, until the owner reaches a terminal state.
            log.warn("unit lock lease renewed in the DB only: unitId={}, instanceId={}",
                unitId, instanceId);
        }
        return keyRenewed && rows == 1;
    }

    /**
     * Releases the lock only if the row is still owned by {@code instanceId}. The distributed
     * key is released only when the row was actually deleted, so a caller whose lock was
     * already lost cannot free the new holder's key.
     *
     * @return {@code true} when this call removed the row (and the key)
     */
    public boolean release(long unitId, long instanceId) {
        int rows = lockMapper.delete(Wrappers.<DeployUnitLockPO>lambdaQuery()
            .eq(DeployUnitLockPO::getUnitId, unitId)
            .eq(DeployUnitLockPO::getInstanceId, instanceId));
        if (rows != 1) {
            return false;
        }
        distributedLock.unlock(KEY_PREFIX + unitId);
        return true;
    }

    /**
     * The current holder of the unit lock, for {@code UNIT_LOCKED} payloads; {@code null} when
     * the unit is not locked. The instance id is {@code 0} while a publish has not bound its
     * instance yet.
     */
    public LockHolder holder(long unitId) {
        DeployUnitLockPO row = lockMapper.selectById(unitId);
        if (row == null) {
            return null;
        }
        return new LockHolder(row.getInstanceId() == null ? 0L : row.getInstanceId(),
            row.getAcquiredAt());
    }

    private void renewOwner(DeployUnitLockPO row) {
        Long owner = row.getInstanceId();
        if (owner == null) {
            return;
        }
        boolean renewed = renew(row.getUnitId(), owner);
        if (!renewed) {
            log.warn("unit lock owner is still running but its lease could not be renewed:"
                + " unitId={}, instanceId={}", row.getUnitId(), owner);
        }
    }

    /**
     * Whether an expired row still belongs to a pipeline in a non-terminal status. A missing
     * instance (or the pre-bind sentinel) counts as an unknown owner and is reclaimable; an
     * unrecognised status is treated as live, which is the safe direction.
     */
    private boolean holdsLiveInstance(DeployUnitLockPO row) {
        if (row == null || row.getInstanceId() == null || row.getInstanceId() <= 0) {
            return false;
        }
        PipelineInstancePO owner = instanceMapper.selectById(row.getInstanceId());
        if (owner == null) {
            return false;
        }
        String status = owner.getStatus();
        if (status == null || status.isBlank()) {
            return true;
        }
        InstanceStatus parsed;
        try {
            parsed = InstanceStatus.valueOf(status);
        } catch (IllegalArgumentException ex) {
            return true;
        }
        return parsed == InstanceStatus.PENDING || parsed == InstanceStatus.RUNNING;
    }

    private static boolean isLive(DeployUnitLockPO row, Instant now) {
        return row != null && row.getExpireAt() != null && row.getExpireAt().isAfter(now);
    }

    /** Read model of the current holder, used by the 40006 payload. */
    public record LockHolder(long instanceId, Instant acquiredAt) {
    }
}