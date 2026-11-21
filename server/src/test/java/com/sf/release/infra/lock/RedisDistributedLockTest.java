package com.sf.release.infra.lock;

import org.junit.jupiter.api.Test;
import org.redisson.api.RKeys;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Contract tests for the Redis lock, driven through mocked Redisson objects because this host
 * has no Redis server. They pin the two properties the in-memory suite cannot prove:
 * unlock is <strong>not</strong> limited to the acquiring thread (release happens on a
 * webhook/scheduler thread), and renewal extends the key lease.
 */
class RedisDistributedLockTest {

    private static final String KEY = "lock:unit:7";

    @Test
    void unlock_force_unlocks_the_key_even_when_another_thread_acquired_it() {
        RedissonClient redisson = mock(RedissonClient.class);
        RLock lock = mock(RLock.class);
        when(redisson.getLock(KEY)).thenReturn(lock);

        new RedisDistributedLock(redisson).unlock(KEY);

        verify(lock).forceUnlock();
        verify(lock, never()).unlock();
        verify(lock, never()).isHeldByCurrentThread();
    }

    @Test
    void renew_extends_the_redis_key_lease() {
        RedissonClient redisson = mock(RedissonClient.class);
        RKeys keys = mock(RKeys.class);
        when(redisson.getKeys()).thenReturn(keys);
        when(keys.expire(KEY, 7_200_000L, TimeUnit.MILLISECONDS)).thenReturn(true);

        assertThat(new RedisDistributedLock(redisson).renew(KEY, Duration.ofHours(2))).isTrue();

        verify(keys).expire(KEY, 7_200_000L, TimeUnit.MILLISECONDS);
    }

    @Test
    void try_lock_uses_the_lease_and_does_not_block() throws InterruptedException {
        RedissonClient redisson = mock(RedissonClient.class);
        RLock lock = mock(RLock.class);
        when(redisson.getLock(KEY)).thenReturn(lock);
        when(lock.tryLock(0L, 4_000L, TimeUnit.MILLISECONDS)).thenReturn(true);

        assertThat(new RedisDistributedLock(redisson).tryLock(KEY, Duration.ofSeconds(4)))
            .isTrue();

        verify(lock).tryLock(0L, 4_000L, TimeUnit.MILLISECONDS);
    }
}