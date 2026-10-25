package com.traceability;

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
 * V186 grandfathering backfill (onboarding gate). Migrates to V185, seeds every tenant shape, then
 * migrates to latest:
 *   m1 a tenant with a store in 'connected' / 'needs_reauth' / 'error' (anything but 'disconnected') gets
 *      shopify_connected_at AND setup_completed_at — never gated, not even on the address;
 *   m2 a tenant whose only store is 'disconnected' (previously connected) and a tenant with no store get
 *      neither — they are gated; a tenant with one disconnected and one connected store counts as connected;
 *   m3 no location gets an address (the columns are new and empty).
 */
@Testcontainers
class OnboardingGateMigrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("traceability_test")
                    .withUsername("postgres")
                    .withPassword("postgres");

    @Test
    void backfill() {
        FlywayTestSupport.configure()
            .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .locations("classpath:db/migration").target("185").load().migrate();
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));

        UUID connected   = tenant(jdbc, "connected", "connected");
        UUID reauth      = tenant(jdbc, "reauth", "needs_reauth");
        UUID error       = tenant(jdbc, "error", "error");
        UUID disconnected = tenant(jdbc, "disconnected", "disconnected");
        UUID none        = tenant(jdbc, "none", null);
        UUID mixed       = tenant(jdbc, "mixed", "disconnected");
        store(jdbc, mixed, "mixed-2", "connected");

        MigrateResult r = FlywayTestSupport.configure()
            .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .locations("classpath:db/migration").load().migrate();
        assertThat(r.success).isTrue();

        for (UUID t : new UUID[] { connected, reauth, error, mixed }) {
            assertThat(stamped(jdbc, t, "shopify_connected_at")).as("connected %s", t).isTrue();
            assertThat(stamped(jdbc, t, "setup_completed_at")).as("completed %s", t).isTrue();
        }
        for (UUID t : new UUID[] { disconnected, none }) {
            assertThat(stamped(jdbc, t, "shopify_connected_at")).as("gated %s", t).isFalse();
            assertThat(stamped(jdbc, t, "setup_completed_at")).as("gated %s", t).isFalse();
        }
        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM locations WHERE address_province_code IS NOT NULL OR address_street IS NOT NULL",
            Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM locations", Integer.class)).isEqualTo(6);
    }

    private static boolean stamped(JdbcTemplate jdbc, UUID tenant, String column) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
            "SELECT " + column + " IS NOT NULL FROM tenants WHERE id = ?", Boolean.class, tenant));
    }

    private static UUID tenant(JdbcTemplate jdbc, String tag, String storeStatus) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?)", id, "Gate " + tag);
        jdbc.update("INSERT INTO locations (tenant_id, name, is_default, is_fulfillment) VALUES (?, 'Main Warehouse', true, true)", id);
        if (storeStatus != null) store(jdbc, id, tag, storeStatus);
        return id;
    }

    private static void store(JdbcTemplate jdbc, UUID tenant, String tag, String status) {
        jdbc.update("INSERT INTO stores (tenant_id, platform, shop_domain, status) VALUES (?, 'shopify', ?, ?::store_status)",
            tenant, "gate-" + tag + "-" + UUID.randomUUID() + ".myshopify.com", status);
    }
}
