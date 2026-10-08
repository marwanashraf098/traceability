package com.traceability.inventory;

import com.traceability.integrations.shopify.ShopifyGateway;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * scripts/ops/2026-10-08-restock-null-location-repair.sql, run through real psql in the database
 * container, on a fixture shaped like prod's buggy state (restocked events with a NULL location,
 * pieces with no current_location_id):
 *
 *   L (main warehouse linked to Shopify 2 days ago, seed right after):
 *     p1 restocked 5 days ago, available        → Group A, seed shortfall, no +1
 *     p2 restocked 1 day ago, available, order without refunds   → Group A, +1 queued
 *     p3 restocked 1 day ago, available, order whose refund restocked it → Group A, skipped_shopify_restocked
 *     p4 restocked 1 day ago, since delivered   → Group B (untouched), +1 queued
 *   U (main warehouse not linked): p5 available → Group A, no Shopify anything
 *
 *   rr1 dry run (default) → exit 0, nothing changed
 *   rr2 -v commit=yes      → Group A located with location_corrected events, Group B untouched,
 *                            the +1 / skip claims written, the +1 due for the increment retry job
 *   rr3 commit again       → no new event, no new claim (idempotent)
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RestockRepairScriptTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("traceability_test")
                    .withUsername("postgres")
                    .withPassword("postgres");

    static { POSTGRES.start(); }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",      POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        r.add("spring.flyway.url",          POSTGRES::getJdbcUrl);
        r.add("spring.flyway.user",         POSTGRES::getUsername);
        r.add("spring.flyway.password",     POSTGRES::getPassword);
    }

    static final String SCRIPT = "2026-10-08-restock-null-location-repair.sql";

    @Autowired JdbcTemplate jdbc;
    @MockBean ShopifyGateway shopifyGateway;

    UUID linked, unlinked, linkedMain, unlinkedMain, linkedVariant, unlinkedVariant, store;
    UUID orderNoRefund, orderRefunded;
    long shopifyVariantId = 8_100_000_001L;
    int seq;

    @BeforeAll
    void fixture() {
        linked = tenant("L");
        linkedMain = location(linked, true);
        jdbc.update("INSERT INTO shopify_inventory_adjustments (tenant_id, batch_id, variant_id, location_id, delta, " +
            "trigger_type, trigger_id, payload, status, created_at) VALUES (?, ?, ?, ?, 3, 'initial_seed', 'seed', '{}'::jsonb, " +
            "'applied', now() - interval '2 days' + interval '15 seconds')",
            linked, UUID.randomUUID(), linkedVariant, linkedMain);
        orderNoRefund = order(linked, "{\"id\": 1, \"refunds\": []}");
        orderRefunded = order(linked, "{\"id\": 2, \"refunds\": [{\"refund_line_items\": [{\"quantity\": 1, " +
            "\"restock_type\": \"return\", \"line_item\": {\"variant_id\": " + shopifyVariantId + "}}]}]}");

        buggyRestock(linked, linkedVariant, "p1", null, "5 days", "available");
        buggyRestock(linked, linkedVariant, "p2", orderNoRefund, "1 day", "available");
        buggyRestock(linked, linkedVariant, "p3", orderRefunded, "1 day", "available");
        buggyRestock(linked, linkedVariant, "p4", orderNoRefund, "1 day", "delivered");

        unlinked = tenant("U");
        unlinkedMain = location(unlinked, false);
        buggyRestock(unlinked, unlinkedVariant, "p5", null, "1 day", "available");
    }

    @Test @Order(1)
    void rr1_dryRun_changesNothing() throws Exception {
        long events = count("SELECT COUNT(*) FROM piece_events"), claims = count("SELECT COUNT(*) FROM shopify_inventory_adjustments");

        ExecResult r = psql(false);

        assertThat(r.getExitCode()).as(r.getStderr()).isZero();
        assertThat(r.getStdout()).contains("dry run — ROLLED BACK").contains("skip_shopify_restocked").contains("push");
        assertThat(count("SELECT COUNT(*) FROM pieces WHERE current_location_id IS NULL AND id LIKE '01REPAIR%'")).isEqualTo(5);
        assertThat(count("SELECT COUNT(*) FROM piece_events")).isEqualTo(events);
        assertThat(count("SELECT COUNT(*) FROM shopify_inventory_adjustments")).isEqualTo(claims);
    }

    @Test @Order(2)
    void rr2_commit_locatesGroupA_queuesPlusOnes_leavesGroupB() throws Exception {
        ExecResult r = psql(true);
        assertThat(r.getExitCode()).as(r.getStderr()).isZero();

        assertThat(location("p1")).isEqualTo(linkedMain);
        assertThat(location("p2")).isEqualTo(linkedMain);
        assertThat(location("p3")).isEqualTo(linkedMain);
        assertThat(location("p5")).isEqualTo(unlinkedMain);
        assertThat(location("p4")).as("Group B is never touched").isNull();
        assertThat(count("SELECT COUNT(*) FROM piece_events WHERE event_type = 'location_corrected'")).isEqualTo(4);
        assertThat(count("SELECT COUNT(*) FROM piece_events WHERE event_type = 'location_corrected' AND from_status <> to_status")).isZero();

        Map<String, String> claims = new java.util.HashMap<>();
        jdbc.query("SELECT split_part(trigger_id, ':', 1) AS piece, status FROM shopify_inventory_adjustments " +
            "WHERE trigger_type = 'return_inspection'", rs -> { claims.put(rs.getString(1), rs.getString(2)); });
        assertThat(claims).containsOnly(
            Map.entry(piece("p2"), "failed"),                       // queued +1
            Map.entry(piece("p3"), "skipped_shopify_restocked"),    // the refund restocked it already
            Map.entry(piece("p4"), "failed"));                      // queued +1 (restocked after link, sold since)

        // The queued +1s are exactly what the increment retry job picks up next.
        assertThat(jdbc.queryForList("SELECT split_part(sia.trigger_id, ':', 1) FROM shopify_inventory_adjustments sia " +
            "WHERE sia.tenant_id = ? AND " + IncrementRecoveryRules.DUE_SQL, String.class, linked))
            .containsExactlyInAnyOrder(piece("p2"), piece("p4"));
        assertThat(jdbc.queryForObject("SELECT source_order_id FROM shopify_inventory_adjustments " +
            "WHERE trigger_id LIKE ? || ':%'", UUID.class, piece("p2"))).isEqualTo(orderNoRefund);
    }

    @Test @Order(3)
    void rr3_commitAgain_isIdempotent() throws Exception {
        long events = count("SELECT COUNT(*) FROM piece_events"), claims = count("SELECT COUNT(*) FROM shopify_inventory_adjustments");
        ExecResult r = psql(true);
        assertThat(r.getExitCode()).as(r.getStderr()).isZero();
        assertThat(count("SELECT COUNT(*) FROM piece_events")).isEqualTo(events);
        assertThat(count("SELECT COUNT(*) FROM shopify_inventory_adjustments")).isEqualTo(claims);
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    private ExecResult psql(boolean commit) throws Exception {
        POSTGRES.copyFileToContainer(MountableFile.forHostPath("scripts/ops/" + SCRIPT), "/tmp/" + SCRIPT);
        List<String> cmd = new ArrayList<>(List.of("psql", "-U", "postgres", "-d", "traceability_test", "-v", "ON_ERROR_STOP=1"));
        if (commit) cmd.addAll(List.of("-v", "commit=yes"));
        cmd.addAll(List.of("-f", "/tmp/" + SCRIPT));
        return POSTGRES.execInContainer(cmd.toArray(String[]::new));
    }

    private long count(String sql) { return jdbc.queryForObject(sql, Long.class); }

    private static String piece(String name) { return "01REPAIR" + name.toUpperCase() + "0000000000000000"; }

    private UUID location(String name) {
        return jdbc.queryForObject("SELECT current_location_id FROM pieces WHERE id = ?", UUID.class, piece(name));
    }

    /** The prod bug, replayed: return_received (with the order) then 'restocked' with a NULL location. */
    private void buggyRestock(UUID tenant, UUID variant, String name, UUID order, String ago, String statusNow) {
        String id = piece(name);
        jdbc.update("INSERT INTO pieces (id, tenant_id, variant_id, status, barcode, short_code, current_location_id) " +
            "VALUES (?, ?, ?, ?::piece_status, ?, ?, NULL)", id, tenant, variant, statusNow, "RP-" + name, "RP" + (++seq));
        jdbc.update("INSERT INTO piece_events (tenant_id, piece_id, event_type, order_id, from_status, to_status, occurred_at) " +
            "VALUES (?, ?, 'return_received', ?, 'delivered', 'return_pending_inspection', now() - ?::interval - interval '1 hour')",
            tenant, id, order, ago);
        jdbc.update("INSERT INTO piece_events (tenant_id, piece_id, event_type, location_id, from_status, to_status, occurred_at) " +
            "VALUES (?, ?, 'restocked', NULL, 'return_pending_inspection', 'available', now() - ?::interval)", tenant, id, ago);
        if (!"available".equals(statusNow)) {
            jdbc.update("INSERT INTO piece_events (tenant_id, piece_id, event_type, from_status, to_status, occurred_at) " +
                "VALUES (?, ?, 'courier_update', 'available', ?::piece_status, now() - interval '1 hour')", tenant, id, statusNow);
        }
    }

    private UUID tenant(String name) {
        UUID tenant = UUID.randomUUID(), product = UUID.randomUUID(), variant = UUID.randomUUID();
        store = UUID.randomUUID();
        String shop = "repair-" + name.toLowerCase() + "-" + tenant.toString().substring(0, 6) + ".myshopify.com";
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?)", tenant, "Repair " + name);
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status, import_status) " +
            "VALUES (?, ?, 'shopify', ?, 'connected', 'completed')", store, tenant, shop);
        jdbc.update("INSERT INTO products (id, tenant_id, store_id, external_id, title, status) VALUES (?, ?, ?, ?, 'P', 'active')",
            product, tenant, store, "gid://shopify/Product/" + shop);
        long vid = "L".equals(name) ? shopifyVariantId : shopifyVariantId + 1;
        jdbc.update("INSERT INTO variants (id, tenant_id, product_id, external_id, title, sku) VALUES (?, ?, ?, ?, 'V', ?)",
            variant, tenant, product, "gid://shopify/ProductVariant/" + vid, "SKU-" + name);
        if ("L".equals(name)) linkedVariant = variant; else unlinkedVariant = variant;
        return tenant;
    }

    private UUID location(UUID tenant, boolean linkedToShopify) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO locations (id, tenant_id, name, is_fulfillment, shopify_location_id, shopify_sync_status, shopify_synced_at) " +
            "VALUES (?, ?, 'Main Warehouse', true, ?, ?, now() - interval '2 days')", id, tenant,
            linkedToShopify ? "gid://shopify/Location/1" : null, linkedToShopify ? "linked" : "unsynced");
        return id;
    }

    private UUID order(UUID tenant, String raw) {
        return jdbc.queryForObject(
            "INSERT INTO orders (tenant_id, store_id, external_id, number, status, payment_method, placed_at, raw) " +
            "VALUES (?, ?, ?, ?, 'new'::order_status, 'cod', now(), ?::jsonb) RETURNING id",
            UUID.class, tenant, store, "gid://shopify/Order/RP" + (++seq), "#RP" + seq, raw);
    }
}
