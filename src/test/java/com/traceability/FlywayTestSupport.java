package com.traceability;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.flywaydb.database.postgresql.PostgreSQLConfigurationExtension;

/**
 * The ONE way a test builds a Flyway: {@code Flyway.configure()} with the same session-level lock as the app
 * ({@code spring.flyway.postgresql.transactional-lock: false}). A bare {@code Flyway.configure()} keeps Flyway's
 * default transactional lock, under which a CREATE INDEX CONCURRENTLY migration (V175) waits forever.
 * FlywayLockGuardTest fails on any other {@code Flyway.configure()} call in the tests.
 */
public final class FlywayTestSupport {

    static final boolean TRANSACTIONAL_LOCK = false;

    private FlywayTestSupport() {
    }

    public static FluentConfiguration configure() {
        FluentConfiguration cfg = Flyway.configure();
        cfg.getPluginRegister().getPlugin(PostgreSQLConfigurationExtension.class)
            .setTransactionalLock(TRANSACTIONAL_LOCK);
        return cfg;
    }
}
