package com.sf.release.domain.pipeline.port;

import java.time.Duration;

/**
 * Distributed mutual-exclusion primitive used by the unit lock and the instance advance lock
 * (tech design §9). The production implementation is Redis; tests and local development run
 * the in-memory implementation, so everything that depends on this port must boot without a
 * Redis server.
 *
 * <p>{@link #tryLock} is non-blocking: it either takes the key or returns {@code false}.
 * {@code ttl} is the lease of the key; a caller that outlives it may lose the key and must
 * rely on the database backstop where one exists. {@link #unlock} releases the key
 * unconditionally (there is no ownership token), so only the holder should call it.
 */
public interface DistributedLock {

    /**
     * Attempts to take {@code key} for {@code ttl}.
     *
     * @return {@code true} when this call took the key, {@code false} when it is held
     */
    boolean tryLock(String key, Duration ttl);

    /**
     * Extends the lease of a held {@code key} to {@code ttl} from now.
     *
     * <p>Renewal is <strong>not</strong> owner-thread restricted, like {@link #unlock}: a
     * pipeline advances on webhook/scheduler threads, so the caller that renews may not be the
     * thread that acquired the key. The decision to renew at all is the caller's
     * authorisation, backed by the {@code deploy_unit_lock} row where one exists.
     *
     * @return {@code true} when the key existed and its lease was extended
     */
    boolean renew(String key, Duration ttl);

    /** Releases {@code key}. A key that is not held is left untouched. */
    void unlock(String key);
}