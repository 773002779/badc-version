package com.sf.release.infra.lock;

import com.sf.release.domain.pipeline.port.DistributedLock;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Redis-backed {@link DistributedLock}, wired only when
 * {@code release.redis.enabled = true} (tech design §9). It is the production implementation
 * of the unit lock ({@code lock:unit:{unitId}}, lease 4 h) and of the instance advance lock
 * ({@code lock:pipe:{instanceId}}, lease 30 s).
 *
 * <p><strong>No owner-thread restriction.</strong> Unlock and renewal are unconditional by
 * contract: a pipeline is released from a webhook/scheduler thread and advanced from a
 * different thread than the publisher. Unlock therefore uses {@link RLock#forceUnlock()}
 * (Redisson releases the key regardless of the current owner) and renewal uses
 * {@code RKeys.expire} on the lock key. The authorization for doing so is the
 * {@code deploy_unit_lock} row evidence held by {@code UnitLockService}, not the lock
 * implementation.
 *
 * <p>The lease is explicit ({@code ttl}) and renewed by the orchestrator, so Redisson's
 * watchdog renewal is not used.
 */
@Component
@ConditionalOnProperty(name = "release.redis.enabled", havingValue = "true")
public class RedisDistributedLock implements DistributedLock {

    private final RedissonClient redisson;

    public RedisDistributedLock(RedissonClient redisson) {
        this.redisson = redisson;
    }

    @Override
    public boolean tryLock(String key, Duration ttl) {
        Objects.requireNonNull(key, "key must not be null");
        requirePositive(ttl);
        RLock lock = redisson.getLock(key);
        try {
            return lock.tryLock(0L, ttl.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    @Override
    public boolean renew(String key, Duration ttl) {
        Objects.requireNonNull(key, "key must not be null");
        requirePositive(ttl);
        return redisson.getKeys().expire(key, ttl.toMillis(), TimeUnit.MILLISECONDS);
    }

    @Override
    public void unlock(String key) {
        Objects.requireNonNull(key, "key must not be null");
        redisson.getLock(key).forceUnlock();
    }

    private static void requirePositive(Duration ttl) {
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("ttl must be a positive duration");
        }
    }
}