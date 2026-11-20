package com.sf.release.infra.lock;

import com.sf.release.domain.pipeline.port.DistributedLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Process-local {@link DistributedLock}: the default implementation used by tests and local
 * development (the platform ruling keeps the application bootable without Redis).
 *
 * <p>Each key holds its expiry instant. {@code tryLock} is atomic per key through
 * {@link ConcurrentHashMap#compute}, and an expired lease may be taken again. Because the
 * state lives in one JVM it is <strong>not</strong> a real distributed lock — a deployment
 * with several nodes must enable {@link RedisDistributedLock}. The DB backstop of the unit
 * lock still applies in every profile.
 */
@Component
@ConditionalOnProperty(name = "release.redis.enabled", havingValue = "false",
    matchIfMissing = true)
public class InMemoryDistributedLock implements DistributedLock {

    private final Map<String, Instant> leases = new ConcurrentHashMap<>();

    @Override
    public boolean tryLock(String key, Duration ttl) {
        Objects.requireNonNull(key, "key must not be null");
        requirePositive(ttl);
        Instant now = Instant.now();
        Instant expiresAt = now.plus(ttl);
        boolean[] acquired = {false};
        leases.compute(key, (ignored, current) -> {
            if (current == null || !current.isAfter(now)) {
                acquired[0] = true;
                return expiresAt;
            }
            return current;
        });
        return acquired[0];
    }

    @Override
    public void unlock(String key) {
        Objects.requireNonNull(key, "key must not be null");
        leases.remove(key);
    }

    private static void requirePositive(Duration ttl) {
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("ttl must be a positive duration");
        }
    }
}