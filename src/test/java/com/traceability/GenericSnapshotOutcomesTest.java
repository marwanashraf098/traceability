package com.traceability;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Non-Bosta couriers G2 — section 4 ("outcomes") of scripts/generic_courier_snapshot.sql, through real psql:
 * a Bosta tenant's orders land in delivered / refused / in_transit / wijha (Shopify names another carrier,
 * no Bosta leg); a switched-on tenant's generic orders land in 'wijha' whatever their Shopify carrier class,
 * never delivered / in transit. Orders placed more than 30 days before as_of are left out. (The script runs in
 * BEGIN READ ONLY — a write would fail it.)
 */
@Testcontainers
class GenericSnapshotOutcomesTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("traceability_test")
                    .withUsername("postgres")
                    .withPassword("postgres");

    static final String AS_OF = "2026-10-18T12:00:00Z";
    static final Instant PLACED = Instant.parse("2026-10-10T09:00:00Z");

    JdbcTemplate jdbc;

    @Test
    void outcomesSection_genericOrdersAreWijha_bostaOrdersUnchanged() throws Exception {
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), "postgres", "postgres")
            .locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "postgres", "postgres"));

        UUID bosta = tenant("Bosta-only", false);
        UUID bs = store(bosta, "bosta-only");
        bostaLeg(bosta, order(bosta, bs, "B-1", "bosta", PLACED), "9300000001", "delivered");
        bostaLeg(bosta, order(bosta, bs, "B-2", "bosta", PLACED), "9300000002", "returned");
        bostaLeg(bosta, order(bosta, bs, "B-3", "bosta", PLACED), "9300000003", "with_courier");
        order(bosta, bs, "B-4", "other_known", PLACED);                                       // Wijha, no leg
        bostaLeg(bosta, order(bosta, bs, "B-5", "bosta", Instant.parse("2026-08-01T09:00:00Z")), "9300000005", "delivered");

        UUID gen = tenant("Switched-on", true);
        UUID gs = store(gen, "switched-on");
        bostaLeg(gen, order(gen, gs, "G-1", "bosta", PLACED), "9300000011", "delivered");
        genericLeg(gen, order(gen, gs, "G-2", "other_known", PLACED), "WJ1", "delivered");
        genericLeg(gen, order(gen, gs, "G-3", "unknown", PLACED), "QP2", "with_courier");
        genericLeg(gen, order(gen, gs, "G-4", "unknown", PLACED), "JT3", "created");
        UUID cancelledGeneric = order(gen, gs, "G-5", "unknown", PLACED);                     // generic leg cancelled
        genericLeg(gen, cancelledGeneric, "MY5", "cancelled");

        POSTGRES.copyFileToContainer(MountableFile.forHostPath("scripts/generic_courier_snapshot.sql"), "/tmp/snap.sql");
        ExecResult r = POSTGRES.execInContainer("psql", "-U", "postgres", "-d", "traceability_test",
            "-X", "-q", "-A", "-t", "-v", "as_of=" + AS_OF, "-f", "/tmp/snap.sql");
        assertThat(r.getExitCode()).as(r.getStderr()).isZero();
        String out = r.getStdout();

        assertThat(out).contains(
            "outcomes|Bosta-only|" + bosta + "|delivered|1",
            "outcomes|Bosta-only|" + bosta + "|in_transit|1",
            "outcomes|Bosta-only|" + bosta + "|refused|1",
            "outcomes|Bosta-only|" + bosta + "|wijha|1",
            "outcomes|Switched-on|" + gen + "|delivered|1",
            "outcomes|Switched-on|" + gen + "|wijha|3",
            "outcomes|Switched-on|" + gen + "|not_shipped|1");
        assertThat(out).doesNotContain("outcomes|Switched-on|" + gen + "|in_transit");
    }

    private UUID tenant(String name, boolean generic) {
        UUID t = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name, generic_courier_enabled) VALUES (?, ?, ?)", t, name, generic);
        jdbc.update("INSERT INTO courier_accounts (tenant_id, provider, api_key_encrypted, webhook_secret, status) " +
            "VALUES (?, 'bosta', 'x', ?, 'active')", t, "wh-" + t);
        return t;
    }

    private UUID store(UUID tenant, String handle) {
        UUID s = UUID.randomUUID();
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status) VALUES (?, ?, 'shopify', ?, 'connected')",
            s, tenant, handle + ".myshopify.com");
        return s;
    }

    private UUID order(UUID tenant, UUID store, String number, String carrierClass, Instant placed) {
        UUID product = UUID.randomUUID(), variant = UUID.randomUUID();
        jdbc.update("INSERT INTO products (id, tenant_id, store_id, external_id, title) VALUES (?, ?, ?, ?, 'P')",
            product, tenant, store, "gid://shopify/Product/" + product);
        jdbc.update("INSERT INTO variants (id, tenant_id, product_id, external_id, title) VALUES (?, ?, ?, ?, 'V')",
            variant, tenant, product, "gid://shopify/ProductVariant/" + variant);
        UUID o = jdbc.queryForObject("INSERT INTO orders (tenant_id, store_id, external_id, number, status, placed_at, " +
            "shipping_carrier_class) VALUES (?, ?, ?, ?, 'new', ?, ?) RETURNING id", UUID.class,
            tenant, store, "gid://shopify/Order/" + number + tenant, number, Timestamp.from(placed), carrierClass);
        jdbc.update("INSERT INTO order_items (tenant_id, order_id, variant_id, quantity) VALUES (?, ?, ?, 1)", tenant, o, variant);
        return o;
    }

    private void bostaLeg(UUID tenant, UUID order, String tn, String state) {
        UUID s = jdbc.queryForObject("INSERT INTO shipments (tenant_id, order_id, provider, tracking_number, internal_state) " +
            "VALUES (?, ?, 'bosta', ?, ?::shipment_internal_state) RETURNING id", UUID.class, tenant, order, tn, state);
        jdbc.update("INSERT INTO shipment_status_history (tenant_id, shipment_id, internal_state) VALUES (?, ?, ?::shipment_internal_state)",
            tenant, s, state);
    }

    private void genericLeg(UUID tenant, UUID order, String key, String state) {
        UUID s = jdbc.queryForObject("INSERT INTO shipments (tenant_id, order_id, provider, tracking_number, tracking_key, " +
            "carrier_name, source, internal_state) VALUES (?, ?, 'generic', ?, ?, 'Other', 'shopify_fulfillment', " +
            "?::shipment_internal_state) RETURNING id", UUID.class, tenant, order, key, key, state);
        jdbc.update("INSERT INTO shipment_status_history (tenant_id, shipment_id, internal_state) VALUES (?, ?, ?::shipment_internal_state)",
            tenant, s, state);
    }
}
