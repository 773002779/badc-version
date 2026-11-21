package com.sf.release.domain.pipeline.port;

/**
 * The short-lived instance advance lock {@code lock:pipe:{instanceId}} (tech design §4.3/§9,
 * lease 30s). {@link #acquire} is non-blocking: a caller that does not get the lock returns
 * immediately, because the current holder's advance covers the same callback.
 */
public interface PipelineLock {

    /** @return {@code true} when the caller now holds the instance lock */
    boolean acquire(long instanceId);

    /** Releases the instance lock; only the holder should call it. */
    void release(long instanceId);
}