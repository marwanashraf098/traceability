package com.traceability;

import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Flexible labeling for everyone (V193, 2026-10-29) — existing tenants. Migrate to V192, add a tenant with the switch
 * off and one already on, migrate to latest: both are on, and a tenant inserted afterwards without naming the column
 * is on too (the new default). The V176 default was false; nothing else changes on the row.
 */
@Testcontainers
class FlexibleLabelingRolloutMigrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("traceability")
                    .withUsername("postgres")
                    .withPassword("postgres");

    @Test
    void existingTenantsTurnedOn_newDefaultTrue() throws Exception {
        FlywayTestSupport.configure()
            .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .locations("classpath:db/migration")
            .target("192")
            .load()
            .migrate();

        UUID off = UUID.randomUUID(), on = UUID.randomUUID(), later = UUID.randomUUID();
        try (Connection c = conn()) {
            c.createStatement().execute("INSERT INTO tenants (id, name) VALUES ('" + off + "', 'Was off')");
            c.createStatement().execute("INSERT INTO tenants (id, name, flexible_labeling_enabled) VALUES ('" + on + "', 'Was on', true)");
            assertThat(flag(c, off)).as("V176 default").isFalse();
        }

        FlywayTestSupport.configure()
            .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .locations("classpath:db/migration")
            .load()
            .migrate();

        try (Connection c = conn()) {
            assertThat(flag(c, off)).isTrue();
            assertThat(flag(c, on)).isTrue();
            assertThat(scalar(c, "SELECT name FROM tenants WHERE id = '" + off + "'")).isEqualTo("Was off");
            c.createStatement().execute("INSERT INTO tenants (id, name) VALUES ('" + later + "', 'New')");
            assertThat(flag(c, later)).as("new default").isTrue();
            assertThat(scalar(c, "SELECT COUNT(*) FROM tenants WHERE NOT flexible_labeling_enabled")).isEqualTo("0");
        }
    }

    private static Connection conn() throws Exception {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static boolean flag(Connection c, UUID tenant) throws Exception {
        try (ResultSet rs = c.createStatement().executeQuery(
                "SELECT flexible_labeling_enabled FROM tenants WHERE id = '" + tenant + "'")) {
            rs.next();
            return rs.getBoolean(1);
        }
    }

    private static String scalar(Connection c, String sql) throws Exception {
        try (ResultSet rs = c.createStatement().executeQuery(sql)) {
            rs.next();
            return rs.getString(1);
        }
    }
}
