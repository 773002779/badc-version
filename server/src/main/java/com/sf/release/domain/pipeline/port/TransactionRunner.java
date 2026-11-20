package com.sf.release.domain.pipeline.port;

import java.util.function.Supplier;

/**
 * Transaction boundary used by the orchestrator: the completion decision, the status writes
 * and the domain events of one advance must commit or roll back together (tech design §5.2).
 * The implementation lives in {@code infra} ({@code TransactionTemplate}).
 */
public interface TransactionRunner {

    /** Runs {@code action} in one transaction and returns its result. */
    <T> T inTransaction(Supplier<T> action);

    /** Convenience overload for an action without a result. */
    default void inTransaction(Runnable action) {
        inTransaction(() -> {
            action.run();
            return null;
        });
    }
}