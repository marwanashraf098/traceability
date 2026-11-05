package com.traceability;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Arrays;
import java.util.Comparator;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Returns portal fees F1 — {@code tenants.portal_free_variant_swaps} (Marawan, 2026-11-05): TRUE for every tenant that
 * exists when the migration runs (a same-product exchange keeps ignoring the price difference — today's behaviour),
 * FALSE for every tenant created afterwards.
 *
 * Migrates to the version just before the customer-fees migration (found by name, so a renumber at rebase can't
 * break it), inserts a tenant, migrates the rest, then inserts another.
 */
@Testcontainers
class FreeVariantSwapsMigrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            TestPostgres.newContainer()
                    .withDatabaseName("traceability_test")
                    .withUsername("postgres")
                    .withPassword("postgres");

    private Flyway flyway(String target) {
        var c = FlywayTestSupport.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration");
        return (target == null ? c : c.target(target)).load();
    }

    @Test
    void m1_existingTenantsTrue_newTenantsFalse() throws Exception {
        MigrationInfo[] all = flyway(null).info().all();
        MigrationInfo fees = Arrays.stream(all)
                .filter(m -> m.getScript().contains("__portal_customer_fees"))
                .findFirst().orElseThrow();
        MigrationInfo before = Arrays.stream(all)
                .filter(m -> m.getVersion() != null && m.getVersion().compareTo(fees.getVersion()) < 0)
                .max(Comparator.comparing(MigrationInfo::getVersion)).orElseThrow();

        assertThat(flyway(before.getVersion().getVersion()).migrate().success).isTrue();
        UUID existing = UUID.randomUUID();
        try (Connection c = DriverManager.getConnection(POSTGRES.getJdbcUrl(), "postgres", "postgres")) {
            exec(c, "INSERT INTO tenants (id, name) VALUES (?, 'Existing before F1')", existing);
        }

        assertThat(flyway(null).migrate().success).isTrue();
        UUID created = UUID.randomUUID();
        try (Connection c = DriverManager.getConnection(POSTGRES.getJdbcUrl(), "postgres", "postgres")) {
            exec(c, "INSERT INTO tenants (id, name) VALUES (?, 'Signed up after F1')", created);
            assertThat(swaps(c, existing)).as("a tenant that existed at migration time keeps today's behaviour").isTrue();
            assertThat(swaps(c, created)).as("a tenant created afterwards uses the price difference").isFalse();
        }
    }

    private static void exec(Connection c, String sql, Object... args) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
            ps.executeUpdate();
        }
    }

    private static boolean swaps(Connection c, UUID tenant) throws Exception {
        try (PreparedStatement ps = c.prepareStatement("SELECT portal_free_variant_swaps FROM tenants WHERE id = ?")) {
            ps.setObject(1, tenant);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getBoolean(1);
            }
        }
    }
}
