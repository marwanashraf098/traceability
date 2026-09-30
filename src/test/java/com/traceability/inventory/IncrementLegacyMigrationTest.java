package com.traceability.inventory;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V121 (failed-increment recovery, Part D-e) — the deploy-time backlog. Migrates to V120, seeds
 * claims in every relevant state, then applies V121: exactly the FAILED increment claims
 * (receiving_session, return_inspection, hold_exit) become legacy; applied / pending claims and
 * failed non-increment claims (void_correction, initial_seed) do not.
 */
@Testcontainers
class IncrementLegacyMigrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("traceability_test")
                    .withUsername("postgres")
                    .withPassword("postgres");

    @Test
    void v121_marksOnlyFailedIncrementClaimsLegacy() {
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .locations("classpath:db/migration").target("120").load().migrate();
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "postgres", "postgres"));

        UUID tenant = UUID.randomUUID(), store = UUID.randomUUID(), product = UUID.randomUUID(),
             variant = UUID.randomUUID(), location = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, 'Legacy')", tenant);
        jdbc.update("INSERT INTO stores (id, tenant_id, shop_domain, status) VALUES (?, ?, 'legacy.myshopify.com', 'connected')", store, tenant);
        jdbc.update("INSERT INTO products (id, tenant_id, store_id, external_id, title, status) VALUES (?, ?, ?, 'gid://shopify/Product/l', 'P', 'active')",
            product, tenant, store);
        jdbc.update("INSERT INTO variants (id, tenant_id, product_id, external_id, title) VALUES (?, ?, ?, 'gid://shopify/ProductVariant/l', 'V')",
            variant, tenant, product);
        jdbc.update("INSERT INTO locations (id, tenant_id, name, is_fulfillment) VALUES (?, ?, 'Main', true)", location, tenant);

        String[][] claims = {
            {"receiving_session", "failed"}, {"return_inspection", "failed"}, {"hold_exit", "failed"},
            {"receiving_session", "applied"}, {"receiving_session", "pending"},
            {"void_correction", "failed"}, {"initial_seed", "failed"},
        };
        int n = 0;
        for (String[] c : claims) {
            jdbc.update("INSERT INTO shopify_inventory_adjustments " +
                "(tenant_id, batch_id, variant_id, location_id, delta, trigger_type, trigger_id, status) " +
                "VALUES (?, gen_random_uuid(), ?, ?, ?, ?, ?, ?)",
                tenant, variant, location, "void_correction".equals(c[0]) ? -1 : 2, c[0], c[0] + "-" + c[1] + "-" + (n++), c[1]);
        }

        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .locations("classpath:db/migration").load().migrate();

        Map<String, Boolean> legacy = jdbc.queryForList(
                "SELECT trigger_type || '/' || status AS k, legacy FROM shopify_inventory_adjustments WHERE tenant_id = ?", tenant)
            .stream().collect(Collectors.toMap(r -> (String) r.get("k"), r -> (Boolean) r.get("legacy")));
        assertThat(legacy).containsExactlyInAnyOrderEntriesOf(Map.of(
            "receiving_session/failed", true, "return_inspection/failed", true, "hold_exit/failed", true,
            "receiving_session/applied", false, "receiving_session/pending", false,
            "void_correction/failed", false, "initial_seed/failed", false));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM shopify_inventory_adjustments WHERE tenant_id = ? " +
            "AND (failure_class IS NOT NULL OR attempt_count <> 0 OR next_attempt_at IS NOT NULL)", Integer.class, tenant))
            .as("legacy rows carry no retry schedule").isZero();
    }
}
