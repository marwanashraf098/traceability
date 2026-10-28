package com.traceability.places;

import com.traceability.integrations.shopify.ShopifyGateway;
import com.traceability.integrations.shopify.ShopifyTokenProvider;
import com.traceability.inventory.FulfillService;
import com.traceability.inventory.PieceAdjustService;
import com.traceability.inventory.ReturnService;
import com.traceability.inventory.ReturnSessionService;
import com.traceability.inventory.StockTakeReconciliationService;
import com.traceability.inventory.StockTakeService;
import com.traceability.inventory.TransferService;
import com.traceability.tenancy.TenantContext;
import org.jobrunr.scheduling.JobScheduler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Warehouse places S1 — pieces_bin_guard (V189) clears a piece's place on EVERY departure, whichever code path moves
 * it. Each test seeds a piece with a place (test SQL), runs the REAL path, and asserts bin_id is NULL afterwards.
 * Paths with their own raw current_location_id UPDATE (CLAUDE.md / Step 0 B): transfer send, relocate return,
 * transfer reconcile, restock, return intake, stock-take found; plus a pick scan (→ reserved) and a Lookup adjust
 * (→ lost). Positive controls: a status- and location-preserving update, a hold and a damage adjust keep the place.
 *
 * A piece in a status that holds no place can't be given one by a normal UPDATE (the trigger clears it at once), so
 * those seeds go in with session_replication_role = replica — a stale place the real path must still clear.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PlaceDepartureTriggerTest {

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

    @MockBean JobScheduler         jobScheduler;
    @MockBean ShopifyGateway       shopifyGateway;
    @MockBean ShopifyTokenProvider tokenProvider;

    @Autowired JdbcTemplate                   jdbc;
    @Autowired TransferService                transfers;
    @Autowired ReturnService                  returns;
    @Autowired ReturnSessionService           returnSessions;
    @Autowired StockTakeService               stockTake;
    @Autowired StockTakeReconciliationService reconciliation;
    @Autowired FulfillService                 fulfill;
    @Autowired PieceAdjustService             adjust;

    record T(UUID tenant, UUID user, UUID store, UUID main, UUID other, UUID variant, UUID mainShelf, UUID otherShelf) {}

    final AtomicInteger seq = new AtomicInteger();

    @AfterEach
    void clear() { TenantContext.clear(); }

    // ── departures ─────────────────────────────────────────────────────────────

    @Test
    void d1_transferSend_clears() {
        T t = tenant("d1");
        String p = piece(t, "available", t.main());
        place(p, t.mainShelf());
        UUID transfer = as(t, () -> transfers.createTransfer("other", t.other(), null, "d1", t.user()));
        assertThat(as(t, () -> transfers.scanOut(transfer, "PC-" + p, t.user())).success()).isTrue();
        assertThat(status(p)).isEqualTo("out_on_transfer");
        assertThat(binOf(p)).isNull();
    }

    @Test
    void d2_relocateReturn_clears() {
        T t = tenant("d2");
        String p = piece(t, "available", t.main());
        UUID out = as(t, () -> transfers.createTransfer("other", t.other(), null, "d2", t.user(), "relocate_out"));
        assertThat(as(t, () -> transfers.scanOut(out, "PC-" + p, t.user())).success()).isTrue();
        as(t, () -> { transfers.closeOneWay(out, t.user()); return null; });
        assertThat(status(p)).isEqualTo("transferred_out");
        staleSeed(p, t.otherShelf());

        UUID back = as(t, () -> transfers.createTransfer("other", t.main(), null, "d2 back", t.user(), "relocate_return", t.other()));
        assertThat(as(t, () -> transfers.returnScanOut(back, "PC-" + p, t.user())).success()).isTrue();
        assertThat(status(p)).isEqualTo("out_on_transfer");
        assertThat(binOf(p)).isNull();
    }

    @Test
    void d3_transferReconcile_clears() {
        T t = tenant("d3");
        String p = piece(t, "available", t.main());
        UUID transfer = as(t, () -> transfers.createTransfer("other", t.other(), null, "d3", t.user()));
        as(t, () -> transfers.scanOut(transfer, "PC-" + p, t.user()));
        as(t, () -> { transfers.markSent(transfer, t.user()); transfers.beginReconcile(transfer, t.user()); return null; });
        staleSeed(p, t.otherShelf());

        assertThat(as(t, () -> transfers.reconcileScanBack(transfer, "PC-" + p, "good", t.user())).success()).isTrue();
        assertThat(status(p)).isEqualTo("available");
        assertThat(location(p)).isEqualTo(t.main());
        assertThat(binOf(p)).isNull();
    }

    @Test
    void d4_returnIntake_and_d5_restock_clear() {
        T t = tenant("d4");
        UUID order = deliveredOrder(t);
        String p = piece(t, "delivered", t.main());
        jdbc.update("UPDATE pieces SET current_order_id = ?, last_event_at = now() WHERE id = ?", order, p);   // inside the return window
        allocate(t, order, p);
        staleSeed(p, t.mainShelf());

        UUID session = as(t, () -> returnSessions.createSession(null, t.user()));
        as(t, () -> returnSessions.scan(session, "PC-" + p, t.main(), t.user()));
        assertThat(status(p)).isEqualTo("return_pending_inspection");
        assertThat(binOf(p)).as("return intake").isNull();

        staleSeed(p, t.mainShelf());
        as(t, () -> { returns.restock(p, null, t.user()); return null; });
        assertThat(status(p)).isEqualTo("available");
        assertThat(location(p)).as("restocked into the main warehouse it was already at").isEqualTo(t.main());
        assertThat(binOf(p)).as("restock: arrives with no place").isNull();
    }

    @Test
    void d6_stockTakeFound_clears() {
        T t = tenant("d6");
        String present = piece(t, "available", t.main());
        String lost = piece(t, "lost", t.main());
        staleSeed(lost, t.mainShelf());

        UUID s = as(t, () -> (UUID) stockTake.openSession("all", null, t.main(), null, t.user()).get("sessionId"));
        as(t, () -> stockTake.scan(s, "PC-" + present, "good", t.user()));
        as(t, () -> stockTake.scan(s, "PC-" + lost, "good", t.user()));
        as(t, () -> reconciliation.attestComplete(s, t.user()));
        Map<String, Object> result = as(t, () -> reconciliation.finalizeSession(s, t.user()));
        assertThat(result.get("status")).isEqualTo("finalized");
        assertThat(status(lost)).isEqualTo("available");
        assertThat(binOf(lost)).as("stock-take found: arrives with no place").isNull();
    }

    @Test
    void d7_pickScan_toReserved_clears() {
        T t = tenant("d7");
        String p = piece(t, "available", t.main());
        place(p, t.mainShelf());
        UUID order = order(t, "new");
        jdbc.update("INSERT INTO order_items (id, tenant_id, order_id, variant_id, quantity) VALUES (gen_random_uuid(), ?, ?, ?, 1)",
            t.tenant(), order, t.variant());
        assertThat(as(t, () -> fulfill.scan(order, "PC-" + p, t.user())).success()).isTrue();
        assertThat(status(p)).isEqualTo("reserved");
        assertThat(binOf(p)).isNull();
    }

    @Test
    void d8_adjustLost_clears() {
        T t = tenant("d8");
        String p = piece(t, "available", t.main());
        place(p, t.mainShelf());
        as(t, () -> { adjust.adjustPiece(p, "lost", "cycle_count_missing", null, t.user()); return null; });
        assertThat(status(p)).isEqualTo("lost");
        assertThat(binOf(p)).isNull();
    }

    // ── positive controls ──────────────────────────────────────────────────────

    @Test
    void c1_statusAndLocationPreserved_holdAndDamage_keepThePlace() {
        T t = tenant("c1");
        String p = piece(t, "available", t.main());
        place(p, t.mainShelf());
        jdbc.update("UPDATE pieces SET status = status, current_location_id = current_location_id WHERE id = ?", p);
        assertThat(binOf(p)).as("same status, same location").isEqualTo(t.mainShelf());

        as(t, () -> adjust.hold(p, "quality_check", null, t.user()));
        assertThat(status(p)).isEqualTo("on_hold");
        assertThat(binOf(p)).as("available → on_hold stays on the shelf").isEqualTo(t.mainShelf());

        String q = piece(t, "available", t.main());
        place(q, t.mainShelf());
        as(t, () -> { adjust.adjustPiece(q, "damaged", "damaged_in_storage", null, t.user()); return null; });
        assertThat(status(q)).isEqualTo("damaged");
        assertThat(binOf(q)).as("available → damaged stays where it is").isEqualTo(t.mainShelf());
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private <R> R as(T t, java.util.concurrent.Callable<R> call) {
        return TenantContext.runAs(t.tenant(), call);
    }

    private T tenant(String name) {
        UUID tenant = UUID.randomUUID(), user = UUID.randomUUID(), store = UUID.randomUUID(), product = UUID.randomUUID();
        UUID variant = UUID.randomUUID(), main = UUID.randomUUID(), other = UUID.randomUUID();
        String tag = name + "-" + tenant.toString().substring(0, 6);
        jdbc.update("INSERT INTO tenants (id, name, places_enabled) VALUES (?, ?, true)", tenant, "Departures " + name);
        jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role) VALUES (?, ?, 'Owner', ?, 'h', 'owner')",
            user, tenant, tag + "@departures.test");
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status) VALUES (?, ?, 'shopify', ?, 'disconnected')",
            store, tenant, tag + ".myshopify.com");
        jdbc.update("INSERT INTO products (id, tenant_id, store_id, external_id, title, status) VALUES (?, ?, ?, ?, 'Tee', 'active')",
            product, tenant, store, "P-" + tag);
        jdbc.update("INSERT INTO variants (id, tenant_id, product_id, external_id, title, sku) VALUES (?, ?, ?, ?, 'M', ?)",
            variant, tenant, product, "V-" + tag, "SKU-" + tag);
        jdbc.update("INSERT INTO locations (id, tenant_id, name, type, is_default, is_fulfillment) " +
            "VALUES (?, ?, 'Main Warehouse', 'warehouse', true, true)", main, tenant);
        jdbc.update("INSERT INTO locations (id, tenant_id, name, type) VALUES (?, ?, 'Pop-up Store', 'warehouse')", other, tenant);
        return new T(tenant, user, store, main, other, variant, shelf(tenant, main), shelf(tenant, other));
    }

    private UUID shelf(UUID tenant, UUID location) {
        UUID zone = bin(tenant, location, "zone", null, null, "Z1");
        UUID rack = bin(tenant, location, "rack", zone, "zone", "Z1-R01");
        return bin(tenant, location, "shelf", rack, "rack", "Z1-R01-S1");
    }

    private UUID bin(UUID tenant, UUID location, String kind, UUID parent, String parentKind, String code) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO bins (id, tenant_id, location_id, kind, parent_id, parent_kind, code, scan_code) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            id, tenant, location, kind, parent, parentKind, code, String.format("Q%06d", 100000 + seq.incrementAndGet()));
        return id;
    }

    private String piece(T t, String status, UUID location) {
        int k = seq.incrementAndGet();
        String id = "01DEP" + UUID.randomUUID().toString().replace("-", "").substring(0, 21).toUpperCase();
        jdbc.update("INSERT INTO pieces (id, tenant_id, variant_id, status, barcode, short_code, current_location_id) " +
                "VALUES (?, ?, ?, ?::piece_status, ?, ?, ?)",
            id, t.tenant(), t.variant(), status, "PC-" + id, String.format("P%06d", 800000 + k), location);
        return id;
    }

    /** A placeable piece onto a shelf — the normal write the trigger checks. */
    private void place(String pieceId, UUID binId) {
        jdbc.update("UPDATE pieces SET bin_id = ? WHERE id = ?", binId, pieceId);
        assertThat(binOf(pieceId)).isEqualTo(binId);
    }

    /** A place on a piece whose status holds none — only possible with triggers (and FK checks) off. */
    private void staleSeed(String pieceId, UUID binId) {
        jdbc.execute((ConnectionCallback<Void>) conn -> {
            try (Statement st = conn.createStatement()) {
                st.execute("SET session_replication_role = replica");
                try (PreparedStatement ps = conn.prepareStatement("UPDATE pieces SET bin_id = ? WHERE id = ?")) {
                    ps.setObject(1, binId);
                    ps.setString(2, pieceId);
                    ps.executeUpdate();
                }
            } finally {
                try (Statement st = conn.createStatement()) { st.execute("SET session_replication_role = DEFAULT"); }
            }
            return null;
        });
        assertThat(binOf(pieceId)).as("stale seed in place").isEqualTo(binId);
    }

    private UUID order(T t, String status) {
        return jdbc.queryForObject(
            "INSERT INTO orders (tenant_id, store_id, external_id, number, status, customer_name, customer_phone, " +
            "payment_method, placed_at) VALUES (?, ?, ?, ?, ?::order_status, 'Buyer', '01000000002', 'cod', now()) RETURNING id",
            UUID.class, t.tenant(), t.store(), "EXT-" + UUID.randomUUID(), "#D" + seq.incrementAndGet(), status);
    }

    private UUID deliveredOrder(T t) {
        UUID order = order(t, "delivered");
        jdbc.update("INSERT INTO shipments (id, tenant_id, order_id, tracking_number, internal_state) " +
            "VALUES (gen_random_uuid(), ?, ?, ?, 'delivered')", t.tenant(), order, "91" + (10000000 + seq.incrementAndGet()));
        return order;
    }

    private void allocate(T t, UUID order, String pieceId) {
        UUID item = UUID.randomUUID();
        jdbc.update("INSERT INTO order_items (id, tenant_id, order_id, variant_id, quantity) VALUES (?, ?, ?, ?, 1)",
            item, t.tenant(), order, t.variant());
        jdbc.update("INSERT INTO allocations (id, tenant_id, order_item_id, piece_id, status) VALUES (gen_random_uuid(), ?, ?, ?, 'packed')",
            t.tenant(), item, pieceId);
    }

    private String status(String pieceId) {
        return jdbc.queryForObject("SELECT status::text FROM pieces WHERE id = ?", String.class, pieceId);
    }

    private UUID location(String pieceId) {
        return jdbc.queryForObject("SELECT current_location_id FROM pieces WHERE id = ?", UUID.class, pieceId);
    }

    private UUID binOf(String pieceId) {
        return jdbc.queryForObject("SELECT bin_id FROM pieces WHERE id = ?", UUID.class, pieceId);
    }
}
