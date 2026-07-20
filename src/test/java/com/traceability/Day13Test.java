package com.traceability;

import com.traceability.inventory.ExceptionService;
import com.traceability.inventory.UlidGenerator;
import com.traceability.tenancy.TenantContext;
import org.jobrunr.scheduling.JobScheduler;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Day 13 integration tests: Exceptions center (FR-15.3).
 *
 * (a) lost piece    → CRITICAL; resolve removes it; audit record written
 * (b) never_received → HIGH; surfaces past window; ack removes it
 * (c) unmatched_delivery → MEDIUM; surfaces; natural resolve (resolved=true) removes it
 * (d) blocked_customer → LOW; surfaces; ack removes it
 * (e) stuck_shipment → HIGH; surfaces; ack removes it;
 *     new Bosta sync after ack (last_synced_at > resolved_at) → reappears
 * (f) unexpected_return → HIGH; surfaces; ack removes it
 * (g) delivery_limbo (provider_state=103) → HIGH; surfaces; ack removes it
 * (h) ndr_failed (provider_state=47, exceptionCode=26) → CRITICAL severity (critical NDR code)
 * (i) severity ordering: CRITICAL before HIGH before MEDIUM before LOW
 * (j) age ordering within same severity: oldest first
 * (k) tenant isolation: tenant B exceptions invisible to tenant A context
 * (l) resolve writes audit record with resolver name, timestamp, note
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Day13Test {

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

    @Autowired ExceptionService excSvc;
    @Autowired JdbcTemplate     jdbc;
    @MockBean  JobScheduler     jobScheduler;

    UUID tenantId, actorId, storeId, variantId;

    @BeforeAll
    void setupFixture() {
        tenantId  = UUID.randomUUID();
        actorId   = UUID.randomUUID();
        storeId   = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        variantId = UUID.randomUUID();

        jdbc.update("INSERT INTO tenants (id, name, stuck_shipment_days) VALUES (?, 'D13Tenant', 5)",
                    tenantId);
        jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role) " +
                    "VALUES (?, ?, 'Ops', 'ops@d13.local', 'h', 'manager')", actorId, tenantId);
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status) " +
                    "VALUES (?, ?, 'shopify', 'd13.myshopify.com', 'disconnected')", storeId, tenantId);
        jdbc.update("INSERT INTO products (id, tenant_id, store_id, external_id, title) " +
                    "VALUES (?, ?, ?, 'P-D13', 'Widget')", productId, tenantId, storeId);
        jdbc.update("INSERT INTO variants (id, tenant_id, product_id, external_id, title, sku) " +
                    "VALUES (?, ?, ?, 'V-D13', 'Blue', 'WGT-BLU')", variantId, tenantId, productId);
    }

    @BeforeEach void ctx()    { TenantContext.set(tenantId); }
    @AfterEach  void clear()  {
        TenantContext.clear();
        // Clean up in FK order (most-dependent first)
        jdbc.update("DELETE FROM exception_resolutions WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM piece_events WHERE tenant_id = ?", tenantId);
        jdbc.update("UPDATE pieces SET current_order_id = NULL WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM allocations WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM pieces WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM unlinked_bosta_deliveries WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM shipments WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM order_items WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM orders WHERE tenant_id = ?", tenantId);
    }

    // ── (a) Lost piece ────────────────────────────────────────────────────────

    @Test
    void a_lostPiece_surfacesAsCritical_resolveRemovesIt_auditWritten() {
        UUID orderId = order("lost");
        String piece = piece("lost", orderId);
        shipment(orderId, "AWB-LOST-A", "lost");

        List<Map<String, Object>> items = exceptionsOfType("lost");
        assertThat(items).hasSize(1);
        assertThat(items.get(0)).containsEntry("severity", "CRITICAL");
        assertThat(items.get(0)).containsEntry("barcode", "PC-" + piece);

        // Resolve it
        excSvc.resolve("lost", "lost:piece:" + piece, actorId, "written off");

        items = exceptionsOfType("lost");
        assertThat(items).isEmpty();

        // Audit record written
        int count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM exception_resolutions " +
            "WHERE tenant_id = ? AND exception_type = 'lost' AND subject_key = ?",
            Integer.class, tenantId, "lost:piece:" + piece);
        assertThat(count).isEqualTo(1);
    }

    // ── (b) Never-received ────────────────────────────────────────────────────

    @Test
    void b_neverReceived_surfacesPastWindow_ackRemovesIt() {
        UUID orderId = order("returned");
        UUID shipId  = shipmentId(orderId, "AWB-NR-B", "returned");
        jdbc.update("UPDATE shipments SET returned_at = now() - interval '5 days' WHERE id = ?", shipId);
        String piece = piece("return_pending_inspection", orderId);
        allocation(orderId, piece);

        assertThat(exceptionsOfType("never_received")).hasSize(1);

        excSvc.resolve("never_received", "never_received:piece:" + piece, actorId, "investigated");

        assertThat(exceptionsOfType("never_received")).isEmpty();
    }

    // ── (c) Unmatched delivery ────────────────────────────────────────────────

    @Test
    void c_unmatchedDelivery_surfaces_naturalResolveClearsIt() {
        long unlinkedId = jdbc.queryForObject(
            "INSERT INTO unlinked_bosta_deliveries " +
            "(tenant_id, tracking_number, bosta_state_code, bosta_order_type) " +
            "VALUES (?, 'AWB-UNLINK-C', 45, 'SEND') RETURNING id",
            Long.class, tenantId);

        assertThat(exceptionsOfType("unmatched_delivery")).hasSize(1);

        // Natural resolution — operator linked it
        jdbc.update("UPDATE unlinked_bosta_deliveries SET resolved = true WHERE id = ?", unlinkedId);

        assertThat(exceptionsOfType("unmatched_delivery")).isEmpty();
    }

    // ── (d) Blocked customer ─────────────────────────────────────────────────

    @Test
    void d_blockedCustomer_surfaces_ackRemovesIt() {
        UUID orderId = jdbc.queryForObject(
            "INSERT INTO orders (tenant_id, store_id, external_id, number, status, " +
            "    customer_name, payment_method, on_hold, hold_reason, placed_at) " +
            "VALUES (?, ?, 'EXT-HOLD-D', '#D13-HOLD', 'new'::order_status, " +
            "    'Ahmed', 'cod', true, 'address unverified', now()) RETURNING id",
            UUID.class, tenantId, storeId);

        assertThat(exceptionsOfType("blocked_customer")).hasSize(1);

        excSvc.resolve("blocked_customer", "blocked:" + orderId, actorId, "reviewed");

        assertThat(exceptionsOfType("blocked_customer")).isEmpty();
    }

    // ── (e) Stuck shipment + recurrence ──────────────────────────────────────

    @Test
    void e_stuckShipment_surfaces_ack_thenSyncAfterAck_reappears() {
        UUID orderId = order("with_courier");
        UUID shipId  = shipmentId(orderId, "AWB-STUCK-E", "with_courier");
        // Simulate 10 days with no courier update
        jdbc.update(
            "UPDATE shipments SET last_synced_at = now() - interval '10 days' WHERE id = ?",
            shipId);

        assertThat(exceptionsOfType("stuck_shipment")).hasSize(1);

        // Ack it — resolved_at = now (after last_synced_at 10 days ago → suppressed)
        excSvc.resolve("stuck_shipment", "stuck:shipment:" + shipId, actorId, "chasing courier");

        assertThat(exceptionsOfType("stuck_shipment")).isEmpty();

        // Bosta syncs the shipment AFTER the ack: last_synced_at = ack + 1s
        // The suppression check (er.resolved_at > last_synced_at) now fails → reappears.
        // We simulate by backdating resolved_at so last_synced_at is newer.
        jdbc.update(
            "UPDATE exception_resolutions SET resolved_at = now() - interval '1 hour' " +
            "WHERE tenant_id = ? AND exception_type = 'stuck_shipment'", tenantId);
        // Set last_synced_at to 9 days ago (after the backdated ack, still outside stuck window)
        jdbc.update(
            "UPDATE shipments SET last_synced_at = now() - interval '9 days' WHERE id = ?",
            shipId);

        // resolved_at (1h ago) < last_synced_at (9 days ago)? No — 1h ago IS AFTER 9 days ago.
        // So we need resolved_at to be BEFORE last_synced_at.
        // Backdate resolved_at to 10 days ago, last_synced_at to 9 days ago:
        jdbc.update(
            "UPDATE exception_resolutions SET resolved_at = now() - interval '10 days' " +
            "WHERE tenant_id = ? AND exception_type = 'stuck_shipment'", tenantId);

        // Now: last_synced_at (9 days ago) > resolved_at (10 days ago) → ack invalid → reappears
        assertThat(exceptionsOfType("stuck_shipment")).hasSize(1);
    }

    // ── (f) Unexpected return ─────────────────────────────────────────────────

    @Test
    void f_unexpectedReturn_surfaces_ackRemovesIt() {
        UUID orderId = order("with_courier");
        String piece = piece("return_pending_inspection", orderId);
        // Write a return_received event from with_courier (unexpected)
        jdbc.update(
            "INSERT INTO piece_events " +
            "(tenant_id, piece_id, event_type, from_status, to_status) " +
            "VALUES (?, ?, 'return_received', 'with_courier'::piece_status, " +
            "        'return_pending_inspection'::piece_status)",
            tenantId, piece);

        assertThat(exceptionsOfType("unexpected_return")).hasSize(1);

        excSvc.resolve("unexpected_return", "unexpected_return:" + piece, actorId, null);

        assertThat(exceptionsOfType("unexpected_return")).isEmpty();
    }

    // ── (g) Delivery limbo ────────────────────────────────────────────────────

    @Test
    void g_deliveryLimbo_providerState103_surfaces_ackRemovesIt() {
        UUID orderId = order("with_courier");
        UUID shipId  = shipmentId(orderId, "AWB-LIMBO-G", "exception");
        jdbc.update(
            "UPDATE shipments SET provider_state = 103, number_of_attempts = 3 WHERE id = ?",
            shipId);

        List<Map<String, Object>> items = exceptionsOfType("delivery_limbo");
        assertThat(items).hasSize(1);
        assertThat(items.get(0)).containsEntry("number_of_attempts", 3);

        excSvc.resolve("delivery_limbo", "delivery_limbo:shipment:" + shipId, actorId, "contacting customer");

        assertThat(exceptionsOfType("delivery_limbo")).isEmpty();
    }

    // ── (h) NDR failed ────────────────────────────────────────────────────────

    @Test
    void h_ndrFailed_criticalCode26_surfacesAsCritical_withDescription() {
        UUID orderId = order("with_courier");
        UUID shipId  = shipmentId(orderId, "AWB-NDR-H", "exception");
        jdbc.update(
            "UPDATE shipments SET provider_state = 47, internal_state = 'exception', " +
            "    raw = '{\"exceptionCode\": 26}'::jsonb WHERE id = ?", shipId);

        List<Map<String, Object>> items = exceptionsOfType("ndr_failed");
        assertThat(items).hasSize(1);
        assertThat(items.get(0)).containsEntry("severity", "CRITICAL");
        assertThat(items.get(0).get("ndr_code")).isNotNull();
        assertThat(items.get(0).get("ndr_description").toString())
            .contains("damaged");

        // Normal NDR code (not critical) → MEDIUM
        UUID orderId2 = order("with_courier");
        UUID shipId2  = shipmentId(orderId2, "AWB-NDR-H2", "exception");
        jdbc.update(
            "UPDATE shipments SET provider_state = 47, internal_state = 'exception', " +
            "    raw = '{\"exceptionCode\": 1}'::jsonb WHERE id = ?", shipId2);

        List<Map<String, Object>> normalNdr = exceptionsOfType("ndr_failed").stream()
            .filter(e -> shipId2.toString().equals(
                e.get("shipment_id") != null ? e.get("shipment_id").toString() : null))
            .toList();
        assertThat(normalNdr).hasSize(1);
        assertThat(normalNdr.get(0)).containsEntry("severity", "MEDIUM");
    }

    // ── (i) Severity ordering ─────────────────────────────────────────────────

    @Test
    void i_severityOrdering_criticalBeforeHighBeforeMediumBeforeLow() {
        // CRITICAL: lost piece
        UUID o1 = order("lost");
        piece("lost", o1);

        // HIGH: stuck shipment
        UUID o2 = order("with_courier");
        UUID s2 = shipmentId(o2, "AWB-SORT-HIGH", "with_courier");
        jdbc.update("UPDATE shipments SET last_synced_at = now()-interval '10 days' WHERE id=?", s2);

        // MEDIUM: unmatched delivery
        jdbc.update(
            "INSERT INTO unlinked_bosta_deliveries " +
            "(tenant_id, tracking_number, bosta_state_code, bosta_order_type) " +
            "VALUES (?, 'AWB-SORT-MED', 45, 'SEND')", tenantId);

        // LOW: blocked order
        jdbc.update(
            "INSERT INTO orders (tenant_id, store_id, external_id, number, status, " +
            "    payment_method, on_hold, placed_at) " +
            "VALUES (?, ?, 'EXT-SORT-LOW', '#SORT-LOW', 'new'::order_status, " +
            "    'cod', true, now())", tenantId, storeId);

        List<Map<String, Object>> all = exceptions();
        List<String> severities = all.stream()
            .map(e -> (String) e.get("severity"))
            .toList();

        // CRITICAL must come before HIGH, HIGH before MEDIUM, MEDIUM before LOW
        int ciCrit  = firstIdx(severities, "CRITICAL");
        int ciHigh  = firstIdx(severities, "HIGH");
        int ciMed   = firstIdx(severities, "MEDIUM");
        int ciLow   = firstIdx(severities, "LOW");

        assertThat(ciCrit).isLessThan(ciHigh);
        assertThat(ciHigh).isLessThan(ciMed);
        assertThat(ciMed).isLessThan(ciLow);
    }

    // ── (j) Age ordering within same severity ─────────────────────────────────

    @Test
    void j_ageOrdering_oldestFirstWithinSameSeverity() {
        // Two HIGH exceptions: unexpected returns with different event timestamps
        UUID o1 = order("with_courier");
        UUID o2 = order("with_courier");
        String p1 = piece("return_pending_inspection", o1);
        String p2 = piece("return_pending_inspection", o2);

        // p1 occurred 2 days ago (older)
        jdbc.update(
            "INSERT INTO piece_events (tenant_id, piece_id, event_type, from_status, to_status, occurred_at) " +
            "VALUES (?, ?, 'return_received', 'with_courier'::piece_status, " +
            "        'return_pending_inspection'::piece_status, now() - interval '2 days')",
            tenantId, p1);
        // p2 occurred 1 day ago (newer)
        jdbc.update(
            "INSERT INTO piece_events (tenant_id, piece_id, event_type, from_status, to_status, occurred_at) " +
            "VALUES (?, ?, 'return_received', 'with_courier'::piece_status, " +
            "        'return_pending_inspection'::piece_status, now() - interval '1 day')",
            tenantId, p2);

        List<Map<String, Object>> unexp = exceptionsOfType("unexpected_return");
        assertThat(unexp).hasSize(2);
        // p1 (older) must come first
        assertThat(unexp.get(0).get("piece_id").toString()).isEqualTo(p1);
        assertThat(unexp.get(1).get("piece_id").toString()).isEqualTo(p2);
    }

    // ── (k) Tenant isolation ─────────────────────────────────────────────────

    @Test
    void k_tenantIsolation_otherTenantExceptionsInvisible() {
        UUID other = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, 'OtherD13')", other);
        UUID otherStore = UUID.randomUUID();
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status) " +
                    "VALUES (?, ?, 'shopify', 'other-d13.myshopify.com', 'disconnected')", otherStore, other);

        // Create a lost piece for the OTHER tenant (bypass RLS — we're using postgres)
        String otherId = UlidGenerator.generate();
        UUID otherProd = UUID.randomUUID();
        UUID otherVar  = UUID.randomUUID();
        jdbc.update("INSERT INTO products (id,tenant_id,store_id,external_id,title) VALUES (?,?,?,'OP','OtherProd')", otherProd, other, otherStore);
        jdbc.update("INSERT INTO variants (id,tenant_id,product_id,external_id,title,sku) VALUES (?,?,?,'OV','OtherVar','OV-SKU')", otherVar, other, otherProd);
        jdbc.update(
            "INSERT INTO pieces (id,tenant_id,variant_id,barcode,short_code,status) " +
            "VALUES (?,?,?,?,'P' || LPAD((abs(hashtext(?)) % 999999 + 1)::text, 6, '0'),'lost'::piece_status)",
            otherId, other, otherVar, "PC-" + otherId, otherId);

        // TenantContext is set to our tenant — should see zero lost exceptions from other tenant
        assertThat(exceptionsOfType("lost")).isEmpty();

        jdbc.update("DELETE FROM pieces WHERE id = ?", otherId);
        jdbc.update("DELETE FROM variants WHERE id = ?", otherVar);
        jdbc.update("DELETE FROM products WHERE id = ?", otherProd);
        jdbc.update("DELETE FROM stores WHERE id = ?", otherStore);
        jdbc.update("DELETE FROM tenants WHERE id = ?", other);
    }

    // ── (l) Resolve audit record ──────────────────────────────────────────────

    @Test
    void l_resolve_writesAuditRecord_withResolverTimestampNote() {
        UUID orderId = order("lost");
        String piece = piece("lost", orderId);
        String subjectKey = "lost:piece:" + piece;

        excSvc.resolve("lost", subjectKey, actorId, "Courier confirmed lost — claim filed");

        Map<String, Object> record = jdbc.queryForMap(
            "SELECT er.exception_type, er.subject_key, er.note, " +
            "       er.resolved_by::text AS resolved_by, " +
            "       er.resolved_at " +
            "FROM exception_resolutions er " +
            "WHERE er.tenant_id = ? AND er.subject_key = ?",
            tenantId, subjectKey);

        assertThat(record.get("exception_type")).isEqualTo("lost");
        assertThat(record.get("subject_key")).isEqualTo(subjectKey);
        assertThat(record.get("resolved_by")).isEqualTo(actorId.toString());
        assertThat(record.get("note")).isEqualTo("Courier confirmed lost — claim filed");
        assertThat(record.get("resolved_at")).isNotNull();
    }

    // ── DB helpers ────────────────────────────────────────────────────────────

    private UUID order(String status) {
        return jdbc.queryForObject(
            "INSERT INTO orders (tenant_id, store_id, external_id, number, status, " +
            "    payment_method, placed_at) " +
            "VALUES (?, ?, ?, '#D13', ?::order_status, 'cod', now()) RETURNING id",
            UUID.class, tenantId, storeId, "EXT-D13-" + UUID.randomUUID(), status);
    }

    private String piece(String status, UUID orderId) {
        String id = UlidGenerator.generate();
        jdbc.update(
            "INSERT INTO pieces (id, tenant_id, variant_id, barcode, short_code, status, current_order_id) " +
            "VALUES (?, ?, ?, ?, 'P' || LPAD((abs(hashtext(?)) % 999999 + 1)::text, 6, '0'), ?::piece_status, ?)",
            id, tenantId, variantId, "PC-" + id, id, status, orderId);
        return id;
    }

    private void allocation(UUID orderId, String pieceId) {
        UUID itemId = UUID.randomUUID();
        jdbc.update("INSERT INTO order_items (id,tenant_id,order_id,variant_id,quantity) VALUES (?,?,?,?,1)",
                    itemId, tenantId, orderId, variantId);
        jdbc.update("INSERT INTO allocations (id,tenant_id,order_item_id,piece_id,status) VALUES (gen_random_uuid(),?,?,?,'packed')",
                    tenantId, itemId, pieceId);
    }

    private void shipment(UUID orderId, String tracking, String state) {
        jdbc.update(
            "INSERT INTO shipments (id,tenant_id,order_id,tracking_number,internal_state) " +
            "VALUES (gen_random_uuid(),?,?,?,?::shipment_internal_state)",
            tenantId, orderId, tracking, state);
    }

    private UUID shipmentId(UUID orderId, String tracking, String state) {
        return jdbc.queryForObject(
            "INSERT INTO shipments (id,tenant_id,order_id,tracking_number,internal_state) " +
            "VALUES (gen_random_uuid(),?,?,?,?::shipment_internal_state) RETURNING id",
            UUID.class, tenantId, orderId, tracking, state);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> exceptions() {
        Map<String, Object> result = excSvc.listExceptions(null, null, 0, 200);
        return (List<Map<String, Object>>) result.get("items");
    }

    private List<Map<String, Object>> exceptionsOfType(String type) {
        return exceptions().stream()
            .filter(e -> type.equals(e.get("type")))
            .collect(java.util.stream.Collectors.toList());
    }

    private static int firstIdx(List<String> list, String value) {
        int i = list.indexOf(value);
        return i >= 0 ? i : Integer.MAX_VALUE;
    }
}
