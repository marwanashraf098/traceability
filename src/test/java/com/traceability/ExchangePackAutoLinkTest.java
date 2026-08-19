package com.traceability;

import com.traceability.identity.JwtService;
import com.traceability.identity.model.SignupRequest;
import com.traceability.identity.model.TokenResponse;
import com.traceability.inventory.UlidGenerator;
import org.jobrunr.scheduling.JobScheduler;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FR-EXCHANGE Phase 5 — pack-time auto-link. complete() on an exchange's outbound order
 * now auto-creates+links the forward shipment against exchanges.tracking_number, reusing
 * ShipmentLinkService.linkByAwbScan() completely unchanged — no operator AWB-verification
 * scan needed, because an exchange has exactly one possible AWB (already known at pack
 * time), unlike a normal order where the scan exists to catch a label mixed up between
 * multiple candidate orders at the station.
 *
 * Exercises the real HTTP endpoints (not FulfillService directly) because the new
 * orchestration lives in FulfillController.complete(), composed from
 * ExchangeService.trackingNumberForPackedOutboundOrder() + the untouched
 * ShipmentLinkService.linkByAwbScan().
 *
 * Matrix:
 *   e1 — complete() on a mapped exchange order auto-links: shipments row created,
 *        forward leg, tracking_number == exchanges.tracking_number, order → awaiting_pickup
 *   e2 — forward-only: exactly one shipments row for the tracking after complete(), and
 *        re-posting /link with the same tracking stays idempotent — never a second row
 *   e3 — a NON-exchange order's complete() does NOT auto-link — order stays 'packed',
 *        no shipment created, proving the auto-link is gated on exchange origin and not
 *        fired unconditionally for every completed order
 *   e4 — a self-pickup exchange's complete() routes to 'self_pickup_pending' (unchanged
 *        self-pickup behavior) and never reaches the auto-link call at all
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ExchangePackAutoLinkTest {

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

    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;
    @Autowired JdbcTemplate     jdbc;
    @Autowired JwtService       jwtService;
    @MockBean  JobScheduler     jobScheduler;

    UUID tenantId;
    UUID storeId;
    UUID outboundVariantId;
    String ownerToken;

    private String base() { return "http://localhost:" + port; }

    @BeforeAll
    void setupOwner() {
        SignupRequest req = new SignupRequest("Exchange AutoLink Co", "excal_owner", "excal@test.com", "password99", true);
        ResponseEntity<TokenResponse> resp = rest.postForEntity(
                base() + "/api/v1/auth/signup", req, TokenResponse.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        ownerToken = resp.getBody().accessToken();
        tenantId = UUID.fromString((String) jwtService.verify(ownerToken).getClaim("tenant"));

        storeId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        outboundVariantId = UUID.randomUUID();
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status) " +
                    "VALUES (?, ?, 'shopify', 'excal.myshopify.com', 'disconnected')", storeId, tenantId);
        jdbc.update("INSERT INTO products (id, tenant_id, store_id, external_id, title) " +
                    "VALUES (?, ?, ?, 'P-EXCAL', 'AutoLink Product')", productId, tenantId, storeId);
        jdbc.update("INSERT INTO variants (id, tenant_id, product_id, external_id, title, sku) " +
                    "VALUES (?, ?, ?, 'V-EXCAL', 'AutoLink Variant', 'EXCALSKU')",
                    outboundVariantId, tenantId, productId);
    }

    @BeforeEach
    void cleanUp() {
        jdbc.execute("DELETE FROM shipment_status_history");
        jdbc.execute("DELETE FROM piece_events");
        jdbc.execute("DELETE FROM allocations");
        jdbc.execute("UPDATE pieces SET current_order_id = NULL");
        jdbc.execute("DELETE FROM shipments");
        jdbc.execute("DELETE FROM exchanges");
        jdbc.execute("DELETE FROM order_items");
        jdbc.execute("DELETE FROM orders");
        jdbc.execute("DELETE FROM pieces");
        jdbc.execute("DELETE FROM unlinked_bosta_deliveries");
    }

    // ---- fixtures ------------------------------------------------------------

    private String createPiece() {
        String id = UlidGenerator.generate();
        jdbc.update(
            "INSERT INTO pieces (id, tenant_id, variant_id, barcode, short_code, status) " +
            "VALUES (?, ?, ?, ?, 'P' || LPAD((abs(hashtext(?)) % 999999 + 1)::text, 6, '0'), 'available')",
            id, tenantId, outboundVariantId, "PC-" + id, id);
        return id;
    }

    /**
     * Mirrors the shape ExchangeService.map() produces (order + order_item + a 'mapped'
     * exchanges row referencing it) without going through the full ingest/mapping HTTP
     * pipeline — that pipeline is already covered by ExchangeMappingTest; this test is
     * only about what happens at complete() given a mapped exchange order.
     */
    private UUID seedMappedExchangeOrder(String tracking, boolean selfPickup) {
        UUID orderId = jdbc.queryForObject(
            "INSERT INTO orders (tenant_id, store_id, external_id, number, status, is_self_pickup, placed_at, raw) " +
            "VALUES (?, ?, ?, ?, 'new'::order_status, ?, now(), '{}'::jsonb) RETURNING id",
            UUID.class, tenantId, storeId, "internal:exchange:" + tracking, "EXC-" + tracking, selfPickup);
        jdbc.update(
            "INSERT INTO order_items (tenant_id, order_id, variant_id, quantity) VALUES (?, ?, ?, 1)",
            tenantId, orderId, outboundVariantId);
        jdbc.update(
            "INSERT INTO exchanges (tenant_id, tracking_number, status, outbound_order_id, " +
            "  outbound_variant_id, raw) " +
            "VALUES (?, ?, 'mapped', ?, ?, '{\"type\":{\"code\":30}}'::jsonb)",
            tenantId, tracking, orderId, outboundVariantId);
        return orderId;
    }

    private HttpHeaders authHeaders() {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(ownerToken);
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    private void scanPiece(UUID orderId, String pieceId) {
        ResponseEntity<Map> resp = rest.exchange(
            base() + "/api/v1/fulfill/" + orderId + "/scan", HttpMethod.POST,
            new HttpEntity<>(Map.of("barcode", "PC-" + pieceId), authHeaders()), Map.class);
        assertThat(resp.getStatusCode().is2xxSuccessful())
            .as("scan response: %s", resp.getBody()).isTrue();
    }

    private ResponseEntity<Map> completeOrder(UUID orderId) {
        return rest.exchange(
            base() + "/api/v1/fulfill/" + orderId + "/complete", HttpMethod.POST,
            new HttpEntity<>(authHeaders()), Map.class);
    }

    // ── e1 ───────────────────────────────────────────────────────────────────

    @Test
    void e1_complete_autoLinksExchangeOrder_toExchangesTrackingNumber() {
        String tracking = String.valueOf(System.nanoTime());
        UUID orderId = seedMappedExchangeOrder(tracking, false);
        scanPiece(orderId, createPiece());

        ResponseEntity<Map> resp = completeOrder(orderId);
        assertThat(resp.getStatusCode().is2xxSuccessful())
            .as("complete response: %s", resp.getBody()).isTrue();

        Map<String, Object> shipment = jdbc.queryForMap(
            "SELECT tracking_number, shipment_leg, internal_state::text AS internal_state " +
            "FROM shipments WHERE order_id = ?", orderId);
        assertThat(shipment.get("tracking_number")).isEqualTo(tracking);
        assertThat(shipment.get("shipment_leg")).isEqualTo("forward");

        String orderStatus = jdbc.queryForObject(
            "SELECT status::text FROM orders WHERE id = ?", String.class, orderId);
        assertThat(orderStatus).isEqualTo("awaiting_pickup");
    }

    // ── e2 ───────────────────────────────────────────────────────────────────

    @Test
    void e2_forwardOnly_exactlyOneShipmentRow_neverASecond() {
        String tracking = String.valueOf(System.nanoTime());
        UUID orderId = seedMappedExchangeOrder(tracking, false);
        scanPiece(orderId, createPiece());

        completeOrder(orderId);

        Integer count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM shipments WHERE tracking_number = ?", Integer.class, tracking);
        assertThat(count).isEqualTo(1);

        // Re-posting /link with the SAME tracking number for the SAME order must stay
        // idempotent (linkByAwbScan's own existing-forward-shipment branch — unmodified
        // by this pass), never a second INSERT for this globally-unique tracking number.
        ResponseEntity<Map> relink = rest.exchange(
            base() + "/api/v1/fulfill/" + orderId + "/link", HttpMethod.POST,
            new HttpEntity<>(Map.of("trackingNumber", tracking), authHeaders()), Map.class);
        assertThat(relink.getStatusCode().is2xxSuccessful())
            .as("relink response: %s", relink.getBody()).isTrue();

        count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM shipments WHERE tracking_number = ?", Integer.class, tracking);
        assertThat(count).isEqualTo(1);
    }

    // ── e3 ───────────────────────────────────────────────────────────────────

    @Test
    void e3_nonExchangeOrder_completeDoesNotAutoLink_stillRequiresManualScan() {
        UUID orderId = jdbc.queryForObject(
            "INSERT INTO orders (tenant_id, store_id, external_id, number, status, placed_at) " +
            "VALUES (?, ?, 'EXT-E3-' || ?, 'ORD-E3', 'new'::order_status, now()) RETURNING id",
            UUID.class, tenantId, storeId, System.nanoTime());
        jdbc.update(
            "INSERT INTO order_items (tenant_id, order_id, variant_id, quantity) VALUES (?, ?, ?, 1)",
            tenantId, orderId, outboundVariantId);
        scanPiece(orderId, createPiece());

        ResponseEntity<Map> resp = completeOrder(orderId);
        assertThat(resp.getStatusCode().is2xxSuccessful())
            .as("complete response: %s", resp.getBody()).isTrue();

        Integer shipmentCount = jdbc.queryForObject(
            "SELECT COUNT(*) FROM shipments WHERE order_id = ?", Integer.class, orderId);
        assertThat(shipmentCount).isEqualTo(0);

        String orderStatus = jdbc.queryForObject(
            "SELECT status::text FROM orders WHERE id = ?", String.class, orderId);
        assertThat(orderStatus).isEqualTo("packed");
    }

    // ── e4 ───────────────────────────────────────────────────────────────────

    @Test
    void e4_selfPickupExchange_completeRoutesToSelfPickupPending_noAutoLink() {
        String tracking = String.valueOf(System.nanoTime());
        UUID orderId = seedMappedExchangeOrder(tracking, true);
        scanPiece(orderId, createPiece());

        ResponseEntity<Map> resp = completeOrder(orderId);
        assertThat(resp.getStatusCode().is2xxSuccessful())
            .as("complete response: %s", resp.getBody()).isTrue();

        Integer shipmentCount = jdbc.queryForObject(
            "SELECT COUNT(*) FROM shipments WHERE order_id = ?", Integer.class, orderId);
        assertThat(shipmentCount).isEqualTo(0);

        String orderStatus = jdbc.queryForObject(
            "SELECT status::text FROM orders WHERE id = ?", String.class, orderId);
        assertThat(orderStatus).isEqualTo("self_pickup_pending");
    }
}
