package com.sf.release.support;

import com.sf.release.infra.lock.InMemoryDistributedLock;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Makes the test schema deterministic: every test context starts from an empty schema.
 *
 * <p>A clean also drops the in-memory lock leases of every cached test context. Without that,
 * a context created earlier can still hold {@code lock:unit:<id>} while the wipe resets
 * {@code deploy_unit}'s AUTO_INCREMENT, so a later test reuses an id whose lease is still held
 * and publish fails with 40006 although no lock row exists. The leases guard a database that no
 * longer exists, so dropping them is the correct semantics, not a workaround.
 */
@Configuration
@Profile("test")
public class FlywayTestConfig {

    @Bean
    FlywayMigrationStrategy cleanThenMigrate() {
        return flyway -> {
            flyway.clean();
            InMemoryDistributedLock.clearAllLeases();
            flyway.migrate();
        };
    }
}