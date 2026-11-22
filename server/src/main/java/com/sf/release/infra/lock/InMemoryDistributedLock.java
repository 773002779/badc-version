package com.sf.release.infra.lock;

import com.sf.release.domain.pipeline.port.DistributedLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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

    /**
     * Every instance ever created in this JVM. A test context is cached across test classes while
     * another context's Flyway clean can wipe the shared schema, which leaves leases that no
     * database row backs any more. {@link #clearAllLeases()} lets the test schema reset drop them.
     * The registry is empty in a running application because only one instance is created.
     */
    private static final Set<InMemoryDistributedLock> INSTANCES = ConcurrentHashMap.newKeySet();

    private final Map<String, Instant> leases = new ConcurrentHashMap<>();

    public InMemoryDistributedLock() {
        INSTANCES.add(this);
    }

    /**
     * Drops every lease of every instance in this JVM. Test aid only: a lease is meaningless once
     * the database it guards was wiped.
     */
    public static void clearAllLeases() {
        INSTANCES.forEach(lock -> lock.leases.clear());
    }

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
    public boolean renew(String key, Duration ttl) {
        Objects.requireNonNull(key, "key must not be null");
        requirePositive(ttl);
        Instant now = Instant.now();
        Instant expiresAt = now.plus(ttl);
        boolean[] renewed = {false};
        leases.computeIfPresent(key, (ignored, current) -> {
            if (!current.isAfter(now)) {
                return current;
            }
            renewed[0] = true;
            return expiresAt;
        });
        return renewed[0];
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