package com.traceability;

import com.traceability.identity.JwtService;
import com.traceability.inventory.ReturnSessionService;
import com.traceability.tenancy.TenantContext;
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
 * Worker permission-guard fixes — revert-to-confirm proofs for the endpoints locked from
 * isAuthenticated() to hasAnyRole('OWNER','MANAGER').
 *
 * Phase A (three endpoints):
 *   1. ReturnSessionController.disposition()
 *   2. ShopifyInventoryController.exportCsv()
 *   3. FulfillController.cancelOrder()
 *
 * Worker experience fix, backend pass (six endpoints — this REVERSES two deliberate prior
 * decisions; stock-take scan and transfer scan-out were intentionally opened to Worker,
 * Marawan has reclassified both as owner/manager-only):
 *   4. TransferController.scanOut()
 *   5. TransferController.listOpen()
 *   6. TransferController.getTransfer()
 *   7. StockTakeController.getSession()
 *   8. StockTakeController.scan()
 *   9. StockTakeController.unscan()
 *
 * Each gets a worker JWT -> 403 (fails on the old isAuthenticated() guard, where it was
 * allowed) and an owner JWT -> success positive control on the SAME resource afterward,
 * proving the worker call had zero effect and the endpoint still works for the allowed role.
 *
 * Pickups (PickupSessionController) are deliberately NOT covered here — pickups stay
 * Worker-callable (docs/blueprint.md:350). Receiving is NOT covered — it was already fully
 * OWNER/MANAGER-locked before either pass; nothing to prove there.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class WorkerPermissionGuardTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("traceability_test")
                    .withUsername("postgres")
                    .withPassword("postgres");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",      POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        r.add("spring.flyway.url",          POSTGRES::getJdbcUrl);
        r.add("spring.flyway.user",         POSTGRES::getUsername);
        r.add("spring.flyway.password",     POSTGRES::getPassword);
    }

    @LocalServerPort int port;
    @Autowired TestRestTemplate       rest;
    @Autowired JdbcTemplate           jdbc;
    @Autowired JwtService             jwt;
    @Autowired ReturnSessionService   sessionSvc;
    @MockBean  JobScheduler           jobScheduler;

    private String base() { return "http://localhost:" + port; }

    UUID tenantId, ownerId, workerId, storeId, locationId, variantId;
    String ownerToken, workerToken;

    @BeforeEach
    void setupFixture() {
        tenantId   = UUID.randomUUID();
        ownerId    = UUID.randomUUID();
        workerId   = UUID.randomUUID();
        storeId    = UUID.randomUUID();
        locationId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        variantId  = UUID.randomUUID();

        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, 'WpgTenant')", tenantId);
        jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role) " +
                    "VALUES (?, ?, 'Owner', ?, 'x', 'owner')", ownerId, tenantId, "owner+" + ownerId + "@wpg.test");
        jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role) " +
                    "VALUES (?, ?, 'Worker', ?, 'x', 'worker')", workerId, tenantId, "worker+" + workerId + "@wpg.test");
        jdbc.update("INSERT INTO locations (id, tenant_id, name) VALUES (?, ?, 'WpgLoc')",
                    locationId, tenantId);
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status) " +
                    "VALUES (?, ?, 'shopify', ?, 'disconnected')", storeId, tenantId, "wpg-" + storeId + ".myshopify.com");
        jdbc.update("INSERT INTO products (id, tenant_id, store_id, external_id, title, status) " +
                    "VALUES (?, ?, ?, 'P-WPG', 'Wpg Product', 'active')", productId, tenantId, storeId);
        jdbc.update("INSERT INTO variants (id, tenant_id, product_id, external_id, title, sku) " +
                    "VALUES (?, ?, ?, 'V-WPG', 'Default', 'WPG-SKU')", variantId, tenantId, productId);

        ownerToken  = jwt.issueAccessToken(ownerId, tenantId, "owner");
        workerToken = jwt.issueAccessToken(workerId, tenantId, "worker");
    }

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM piece_events WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM stock_take_scans WHERE tenant_id = ?", tenantId);
        // transfer_pieces/transfer_lines FK-reference pieces/transfers — must go before both.
        jdbc.update("DELETE FROM transfer_pieces WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM transfer_lines WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM return_session_items WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM return_session_shipments WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM return_sessions WHERE tenant_id = ?", tenantId);
        jdbc.update("UPDATE pieces SET current_order_id = NULL WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM allocations WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM pieces WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM order_items WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM shipments WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM orders WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM transfers WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM stock_take_sessions WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM variants WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM products WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM stores WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM locations WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM users WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM tenants WHERE id = ?", tenantId);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 1. Returns disposition — worker 403, then owner succeeds on the same item
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    void disposition_workerForbidden_ownerSucceeds() {
        UUID orderId = createOrder("returning");
        String pieceId = createPiece("return_in_transit", orderId);

        TenantContext.set(tenantId);
        UUID sessionId;
        try {
            sessionId = sessionSvc.createSession(null, ownerId);
            Map<String, Object> scanResult = sessionSvc.scan(sessionId, "PC-" + pieceId, locationId, ownerId);
            assertThat(scanResult.get("disposition")).isEqualTo("pending");
        } finally {
            TenantContext.clear();
        }
        assertThat(pieceStatus(pieceId)).isEqualTo("return_pending_inspection");

        Map<String, Object> body = Map.of("disposition", "restock", "locationId", locationId.toString());
        String url = base() + "/api/v1/returns/sessions/" + sessionId + "/items/" + pieceId + "/disposition";

        ResponseEntity<String> workerResp = rest.exchange(
            url, HttpMethod.POST, new HttpEntity<>(body, bearerHeaders(workerToken)), String.class);
        assertThat(workerResp.getStatusCode().value())
            .as("worker must be rejected (403) — disposition is Manager-tier per blueprint.md")
            .isEqualTo(403);
        assertThat(pieceStatus(pieceId))
            .as("worker's rejected call must have zero effect")
            .isEqualTo("return_pending_inspection");

        ResponseEntity<Map> ownerResp = rest.exchange(
            url, HttpMethod.POST, new HttpEntity<>(body, bearerHeaders(ownerToken)), Map.class);
        assertThat(ownerResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(ownerResp.getBody().get("disposition")).isEqualTo("restocked");
        assertThat(pieceStatus(pieceId)).isEqualTo("available");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 2. CSV export — worker 403, owner succeeds
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    void exportCsv_workerForbidden_ownerSucceeds() {
        String url = base() + "/api/v1/shopify-inventory/adjustments/export.csv";

        ResponseEntity<String> workerResp = rest.exchange(
            url, HttpMethod.GET, new HttpEntity<>(bearerHeaders(workerToken)), String.class);
        assertThat(workerResp.getStatusCode().value())
            .as("worker must be rejected (403) — exports are Owner/Manager-tier per blueprint.md")
            .isEqualTo(403);

        ResponseEntity<String> ownerResp = rest.exchange(
            url, HttpMethod.GET, new HttpEntity<>(bearerHeaders(ownerToken)), String.class);
        assertThat(ownerResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(ownerResp.getBody()).startsWith("id,batch_id,trigger_type");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 3. Order cancel — worker 403, then owner succeeds on the same order
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    void cancelOrder_workerForbidden_ownerSucceeds() {
        UUID orderId = createOrder("new");
        String url = base() + "/api/v1/fulfill/" + orderId + "/cancel";

        ResponseEntity<String> workerResp = rest.exchange(
            url, HttpMethod.POST, new HttpEntity<>(bearerHeaders(workerToken)), String.class);
        assertThat(workerResp.getStatusCode().value())
            .as("worker must be rejected (403) — order cancel is Owner/Manager-tier per blueprint.md")
            .isEqualTo(403);
        assertThat(orderStatus(orderId))
            .as("worker's rejected call must have zero effect")
            .isEqualTo("new");

        ResponseEntity<Map> ownerResp = rest.exchange(
            url, HttpMethod.POST, new HttpEntity<>(bearerHeaders(ownerToken)), Map.class);
        assertThat(ownerResp.getStatusCode().value()).isIn(200, 202);
        assertThat(orderStatus(orderId)).isEqualTo("cancelled");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 4. Transfer scan-out — worker 403, owner succeeds
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    void transferScanOut_workerForbidden_ownerSucceeds() {
        UUID transferId = createTransfer();
        String pieceId = createPiece("available", null);
        Map<String, Object> body = Map.of("barcode", "PC-" + pieceId);
        String url = base() + "/api/v1/transfers/" + transferId + "/scan-out";

        ResponseEntity<String> workerResp = rest.exchange(
            url, HttpMethod.POST, new HttpEntity<>(body, bearerHeaders(workerToken)), String.class);
        assertThat(workerResp.getStatusCode().value())
            .as("worker must be rejected (403) — transfers are Owner/Manager-tier per blueprint.md")
            .isEqualTo(403);

        ResponseEntity<Map> ownerResp = rest.exchange(
            url, HttpMethod.POST, new HttpEntity<>(body, bearerHeaders(ownerToken)), Map.class);
        assertThat(ownerResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(ownerResp.getBody().get("success")).isEqualTo(true);
        assertThat(ownerResp.getBody().get("pieceId")).isEqualTo(pieceId);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 5. Transfer list — worker 403, owner succeeds
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    void transferList_workerForbidden_ownerSucceeds() {
        createTransfer();
        String url = base() + "/api/v1/transfers";

        ResponseEntity<String> workerResp = rest.exchange(
            url, HttpMethod.GET, new HttpEntity<>(bearerHeaders(workerToken)), String.class);
        assertThat(workerResp.getStatusCode().value())
            .as("worker must be rejected (403) — transfers are Owner/Manager-tier per blueprint.md")
            .isEqualTo(403);

        ResponseEntity<java.util.List> ownerResp = rest.exchange(
            url, HttpMethod.GET, new HttpEntity<>(bearerHeaders(ownerToken)), java.util.List.class);
        assertThat(ownerResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(ownerResp.getBody()).isNotEmpty();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 6. Transfer detail — worker 403, owner succeeds
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    void transferDetail_workerForbidden_ownerSucceeds() {
        UUID transferId = createTransfer();
        String url = base() + "/api/v1/transfers/" + transferId;

        ResponseEntity<String> workerResp = rest.exchange(
            url, HttpMethod.GET, new HttpEntity<>(bearerHeaders(workerToken)), String.class);
        assertThat(workerResp.getStatusCode().value())
            .as("worker must be rejected (403) — transfers are Owner/Manager-tier per blueprint.md")
            .isEqualTo(403);

        ResponseEntity<Map> ownerResp = rest.exchange(
            url, HttpMethod.GET, new HttpEntity<>(bearerHeaders(ownerToken)), Map.class);
        assertThat(ownerResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(ownerResp.getBody().get("id")).isEqualTo(transferId.toString());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 7. Stock-take session detail — worker 403, owner succeeds
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    void stockTakeSessionDetail_workerForbidden_ownerSucceeds() {
        UUID sessionId = createStockTakeSession();
        String url = base() + "/api/v1/stock-takes/sessions/" + sessionId;

        ResponseEntity<String> workerResp = rest.exchange(
            url, HttpMethod.GET, new HttpEntity<>(bearerHeaders(workerToken)), String.class);
        assertThat(workerResp.getStatusCode().value())
            .as("worker must be rejected (403) — stock take is Owner/Manager-tier per blueprint.md")
            .isEqualTo(403);

        ResponseEntity<Map> ownerResp = rest.exchange(
            url, HttpMethod.GET, new HttpEntity<>(bearerHeaders(ownerToken)), Map.class);
        assertThat(ownerResp.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 8. Stock-take scan — worker 403, owner succeeds
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    void stockTakeScan_workerForbidden_ownerSucceeds() {
        UUID sessionId = createStockTakeSession();
        String pieceId = createPiece("available", null);
        Map<String, Object> body = Map.of("barcode", "PC-" + pieceId, "condition", "good");
        String url = base() + "/api/v1/stock-takes/sessions/" + sessionId + "/scan";

        ResponseEntity<String> workerResp = rest.exchange(
            url, HttpMethod.POST, new HttpEntity<>(body, bearerHeaders(workerToken)), String.class);
        assertThat(workerResp.getStatusCode().value())
            .as("worker must be rejected (403) — stock take is Owner/Manager-tier per blueprint.md")
            .isEqualTo(403);

        ResponseEntity<Map> ownerResp = rest.exchange(
            url, HttpMethod.POST, new HttpEntity<>(body, bearerHeaders(ownerToken)), Map.class);
        assertThat(ownerResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(ownerResp.getBody().get("pieceId")).isEqualTo(pieceId);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 9. Stock-take unscan — worker 403, owner succeeds
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    void stockTakeUnscan_workerForbidden_ownerSucceeds() {
        UUID sessionId = createStockTakeSession();
        String pieceId = createPiece("available", null);
        String url = base() + "/api/v1/stock-takes/sessions/" + sessionId + "/scan/" + pieceId;

        ResponseEntity<String> workerResp = rest.exchange(
            url, HttpMethod.DELETE, new HttpEntity<>(bearerHeaders(workerToken)), String.class);
        assertThat(workerResp.getStatusCode().value())
            .as("worker must be rejected (403) — stock take is Owner/Manager-tier per blueprint.md")
            .isEqualTo(403);

        ResponseEntity<Void> ownerResp = rest.exchange(
            url, HttpMethod.DELETE, new HttpEntity<>(bearerHeaders(ownerToken)), Void.class);
        assertThat(ownerResp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static HttpHeaders bearerHeaders(String token) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    private UUID createOrder(String status) {
        return jdbc.queryForObject(
            "INSERT INTO orders (tenant_id, store_id, external_id, number, status, " +
            "    customer_name, customer_phone, payment_method, placed_at) " +
            "VALUES (?, ?, ?, 'ORD-WPG', ?::order_status, 'Buyer', '01000000003', 'cod', now()) " +
            "RETURNING id",
            UUID.class, tenantId, storeId, "EXT-WPG-" + UUID.randomUUID(), status);
    }

    private String createPiece(String status, UUID orderId) {
        String id = com.traceability.inventory.UlidGenerator.generate();
        jdbc.update(
            "INSERT INTO pieces " +
            "(id, tenant_id, variant_id, barcode, short_code, status, current_order_id, last_event_at) " +
            "VALUES (?, ?, ?, ?, 'P' || LPAD((abs(hashtext(?)) % 999999 + 1)::text, 6, '0'), ?::piece_status, ?, now())",
            id, tenantId, variantId, "PC-" + id, id, status, orderId);
        return id;
    }

    private String pieceStatus(String pieceId) {
        return jdbc.queryForObject(
            "SELECT status::text FROM pieces WHERE id = ?", String.class, pieceId);
    }

    private String orderStatus(UUID orderId) {
        return jdbc.queryForObject(
            "SELECT status::text FROM orders WHERE id = ?", String.class, orderId);
    }

    private UUID createTransfer() {
        return jdbc.queryForObject(
            "INSERT INTO transfers (tenant_id, transfer_type, destination_location_id, created_by) " +
            "VALUES (?, 'showroom', ?, ?) RETURNING id",
            UUID.class, tenantId, locationId, ownerId);
    }

    private UUID createStockTakeSession() {
        return jdbc.queryForObject(
            "INSERT INTO stock_take_sessions (tenant_id, scope_type, location_id, opened_by) " +
            "VALUES (?, 'all', ?, ?) RETURNING id",
            UUID.class, tenantId, locationId, ownerId);
    }
}
