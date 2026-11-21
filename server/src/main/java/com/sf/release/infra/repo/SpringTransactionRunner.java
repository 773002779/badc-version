package com.sf.release.infra.repo;

import com.sf.release.domain.pipeline.port.TransactionRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * {@link TransactionRunner} backed by Spring's {@link TransactionTemplate}, used to make one
 * orchestrator advance (decision, status writes and domain events) atomic (tech design §5.2).
 */
@Component
public class SpringTransactionRunner implements TransactionRunner {

    private final TransactionTemplate transactionTemplate;

    public SpringTransactionRunner(PlatformTransactionManager transactionManager) {
        this.transactionTemplate = new TransactionTemplate(
            Objects.requireNonNull(transactionManager, "transactionManager must not be null"));
    }

    @Override
    public <T> T inTransaction(Supplier<T> action) {
        Objects.requireNonNull(action, "action must not be null");
        return transactionTemplate.execute(status -> action.get());
    }
}