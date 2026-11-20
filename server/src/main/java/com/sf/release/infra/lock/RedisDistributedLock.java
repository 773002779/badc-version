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
 * <p>The lease is explicit ({@code ttl}), so Redisson's watchdog renewal is not used: a
 * caller that outlives the lease may lose the key, which is why the unit lock also keeps the
 * {@code deploy_unit_lock} database row as a backstop.
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
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("ttl must be a positive duration");
        }
        RLock lock = redisson.getLock(key);
        try {
            return lock.tryLock(0L, ttl.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    @Override
    public void unlock(String key) {
        Objects.requireNonNull(key, "key must not be null");
        RLock lock = redisson.getLock(key);
        if (lock.isHeldByCurrentThread()) {
            lock.unlock();
        }
    }
}