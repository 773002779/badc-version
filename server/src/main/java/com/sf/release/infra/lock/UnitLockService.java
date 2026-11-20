package com.sf.release.infra.lock;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sf.release.domain.pipeline.port.DistributedLock;
import com.sf.release.infra.repo.DeployUnitLockMapper;
import com.sf.release.infra.repo.po.DeployUnitLockPO;
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
 * {@code expire_at} is still in the future, the unit is locked even when the Redis key is free
 * (for example after an application restart cleared an in-memory lease). Conversely, when the
 * DB row is expired, the lease is stale and may be reclaimed; {@link #acquire} drops the
 * distributed key that the old holder left behind before taking it again. This is what makes
 * {@code DeployUnitLockMapper.forceExpire} work: moving {@code expire_at} into the past is the
 * test/administration way of releasing a hung holder without waiting 4 hours.
 *
 * <p><strong>Reservation.</strong> {@code acquire} runs before the pipeline instance exists,
 * so it stores the sentinel {@code instance_id = 0}. The publishing transaction calls
 * {@link #bindInstance} once the instance has an id, so a committed lock row always names its
 * real instance.
 *
 * <p>Releasing deletes the DB row first and then the distributed key. An acquirer that sees
 * no row while the key is still held returns {@code false} instead of stealing it, so a
 * release can never free another holder's key; the loser simply retries.
 */
@Service
public class UnitLockService {

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

    public UnitLockService(DistributedLock distributedLock, DeployUnitLockMapper lockMapper) {
        this.distributedLock = distributedLock;
        this.lockMapper = lockMapper;
    }

    /**
     * Tries to take the unit lock for {@code instanceId}.
     *
     * @return {@code true} when this call holds both levels; {@code false} when the unit is
     *     held by somebody else (or by a concurrent caller that won the race)
     */
    public boolean acquire(long unitId, long instanceId) {
        String key = KEY_PREFIX + unitId;
        Instant now = Instant.now();
        for (int attempt = 1; attempt <= ACQUIRE_ATTEMPTS; attempt++) {
            DeployUnitLockPO existing = lockMapper.selectById(unitId);
            if (isLive(existing, now)) {
                return false;
            }
            if (!distributedLock.tryLock(key, TTL)) {
                // The key is held but the DB row is gone or expired. Without an expired row as
                // evidence we must not steal the key (a holder may not have inserted yet).
                DeployUnitLockPO latest = lockMapper.selectById(unitId);
                if (latest == null || isLive(latest, now)) {
                    return false;
                }
                log.warn("reclaiming stale unit lock key: unitId={}, attempt={}", unitId, attempt);
                distributedLock.unlock(key);
                continue;
            }
            DeployUnitLockPO latest = lockMapper.selectById(unitId);
            if (isLive(latest, now)) {
                distributedLock.unlock(key);
                return false;
            }
            if (latest != null) {
                lockMapper.deleteById(unitId);
            }
            try {
                lockMapper.insert(DeployUnitLockPO.of(unitId, instanceId, now, now.plus(TTL)));
                return true;
            } catch (DuplicateKeyException ex) {
                // A concurrent publisher inserted its row between our read and our insert.
                distributedLock.unlock(key);
                return false;
            }
        }
        return false;
    }

    /**
     * Binds the real pipeline instance id to a lock row reserved by {@link #acquire}.
     *
     * @return {@code true} when the row still existed; {@code false} when the row was removed
     *     (for example force-expired) between the acquire and the instance creation
     */
    public boolean bindInstance(long unitId, long instanceId) {
        int rows = lockMapper.update(null, Wrappers.<DeployUnitLockPO>lambdaUpdate()
            .eq(DeployUnitLockPO::getUnitId, unitId)
            .set(DeployUnitLockPO::getInstanceId, instanceId));
        return rows == 1;
    }

    /** Releases both levels. Safe to call when the unit is not locked. */
    public void release(long unitId) {
        Objects.requireNonNull(unitId, "unitId");
        lockMapper.deleteById(unitId);
        distributedLock.unlock(KEY_PREFIX + unitId);
    }

    private static boolean isLive(DeployUnitLockPO row, Instant now) {
        return row != null && row.getExpireAt() != null && row.getExpireAt().isAfter(now);
    }
}