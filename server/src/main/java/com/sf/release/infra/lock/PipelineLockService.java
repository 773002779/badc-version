package com.sf.release.infra.lock;

import com.sf.release.domain.pipeline.port.DistributedLock;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * Instance-level short lock used by the pipeline orchestrator before advancing a node
 * (tech design §4.3/§9: {@code lock:pipe:{instanceId}}, {@code leaseTime = 30s}). The critical
 * section is a single advance step, so a caller that cannot take the lock simply returns: the
 * other caller is already advancing the same instance, and polling will pick up the result.
 */
@Service
public class PipelineLockService {

    /** Instance lock lease (tech design §9: 30 s). */
    public static final Duration TTL = Duration.ofSeconds(30);

    /** Key prefix of the instance lock ({@code lock:pipe:{instanceId}}). */
    public static final String KEY_PREFIX = "lock:pipe:";

    private final DistributedLock distributedLock;

    public PipelineLockService(DistributedLock distributedLock) {
        this.distributedLock = distributedLock;
    }

    /** @return {@code true} when the caller may advance the instance now */
    public boolean acquire(long instanceId) {
        return distributedLock.tryLock(KEY_PREFIX + instanceId, TTL);
    }

    public void release(long instanceId) {
        distributedLock.unlock(KEY_PREFIX + instanceId);
    }
}