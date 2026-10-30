package com.traceability;

import org.testcontainers.containers.PostgreSQLContainer;

import java.util.Map;

/**
 * The ONE way a test builds its Postgres container: {@code postgres:16-alpine} with the data directory on tmpfs
 * and durability switched off ({@code fsync}, {@code synchronous_commit}, {@code full_page_writes}). Nothing in the
 * suite tests crash durability, so this only removes disk waits. Still one container per test class — callers chain
 * {@code withDatabaseName / withUsername / withPassword} exactly as before.
 *
 * <p>Not used by ExchangeBackfillTest (left as it was, by rule).
 */
public final class TestPostgres {

    static final String IMAGE = "postgres:16-alpine";
    static final String DATA_DIR = "/var/lib/postgresql/data";

    private TestPostgres() {
    }

    public static PostgreSQLContainer<?> newContainer() {
        return new PostgreSQLContainer<>(IMAGE)
            .withTmpFs(Map.of(DATA_DIR, "rw"))
            .withCommand("postgres",
                "-c", "fsync=off",
                "-c", "synchronous_commit=off",
                "-c", "full_page_writes=off");
    }
}
