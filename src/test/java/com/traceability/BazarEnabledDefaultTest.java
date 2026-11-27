package com.traceability;

import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V244 — bazar on by default.
 *   d1 a tenant created before V244 with bazar off is switched on by the migration
 *   d2 a tenant created after V244 without naming the column has bazar_enabled = true (column default true)
 *   d3 ops can still switch one tenant off explicitly
 */
@Testcontainers
class BazarEnabledDefaultTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            TestPostgres.newContainer()
                    .withDatabaseName("traceability_test")
                    .withUsername("postgres")
                    .withPassword("postgres");

    @Test
    void v244_bazarOnByDefault() {
        FlywayTestSupport.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion("243"))
                .load()
                .migrate();
        JdbcTemplate jdbc = new JdbcTemplate(
                new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));

        UUID before = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, 'Before V244')", before);
        assertThat(enabled(jdbc, before)).as("V231 default false before V244").isFalse();

        MigrateResult r = FlywayTestSupport.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
        assertThat(r.success).isTrue();
        // V244 ran (later migrations may run too — don't count them).
        assertThat(r.migrations).extracting(m -> m.version).as("V244 applied").contains("244");
        assertThat(jdbc.queryForObject("SELECT column_default FROM information_schema.columns " +
            "WHERE table_name = 'tenants' AND column_name = 'bazar_enabled'", String.class))
            .as("tenants.bazar_enabled defaults to true").isEqualTo("true");

        // d1
        assertThat(enabled(jdbc, before)).as("existing tenant switched on").isTrue();
        // d2
        UUID after = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, 'After V244')", after);
        assertThat(enabled(jdbc, after)).as("new tenant on by default").isTrue();
        assertThat(jdbc.queryForObject(
                "SELECT column_default FROM information_schema.columns " +
                "WHERE table_schema = 'public' AND table_name = 'tenants' AND column_name = 'bazar_enabled'",
                String.class)).isEqualTo("true");
        // d3
        UUID off = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name, bazar_enabled) VALUES (?, 'Off', false)", off);
        assertThat(enabled(jdbc, off)).isFalse();
    }

    private static boolean enabled(JdbcTemplate jdbc, UUID tenant) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT bazar_enabled FROM tenants WHERE id = ?", Boolean.class, tenant));
    }
}
