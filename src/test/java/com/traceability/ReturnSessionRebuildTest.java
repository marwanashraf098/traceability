package com.traceability;

import com.traceability.identity.model.AccessTokenResponse;
import com.traceability.inventory.ShopifyInventoryService;
import com.traceability.inventory.UlidGenerator;
import com.traceability.tenancy.TenantContext;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * FR-24: end-to-end endpoint tests for the session-based returns rebuild.
 * ReturnSessionTest.java covers the deeper piece-state-machine cases (legal/
 * illegal-state scan, restock/damage disposition, the old untouched gated reprint)
 * via direct ReturnSessionService calls. This file covers the controller/endpoint
 * layer: one-open-session claim, close-blocked/close-summary bodies, abandon
 * (change B — soft-delete, no revert), cross-tenant isolation, pagination, the
 * load-bearing "unassigned pending" query (every branch), and the new
 * any-status reprint surface.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReturnSessionRebuildTest {

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
    @Autowired PasswordEncoder  passwordEncoder;

    @MockBean ShopifyInventoryService shopifyInventory;

    UUID tenantId, otherTenantId;
    UUID actorId, variantId, storeId, locationId;
    String ownerToken, otherTenantOwnerToken;

    @BeforeAll
    void setup() {
        tenantId      = UUID.randomUUID();
        otherTenantId = UUID.randomUUID();
        actorId       = UUID.randomUUID();
        UUID otherOwnerId = UUID.randomUUID();
        storeId    = UUID.randomUUID();
        locationId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        variantId = UUID.randomUUID();

        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, 'RSR-Tenant')", tenantId);
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, 'RSR-Other')", otherTenantId);

        String ownerEmail = "owner-rsr-" + actorId + "@test.local";
        String otherOwnerEmail = "owner-rsr-b-" + otherOwnerId + "@test.local";
        jdbc.update(
            "INSERT INTO users (id, tenant_id, name, email, password_hash, role, active) " +
            "VALUES (?, ?, 'Owner', ?, ?, 'owner', true)",
            actorId, tenantId, ownerEmail, passwordEncoder.encode("pass123"));
        jdbc.update(
            "INSERT INTO users (id, tenant_id, name, email, password_hash, role, active) " +
            "VALUES (?, ?, 'Owner B', ?, ?, 'owner', true)",
            otherOwnerId, otherTenantId, otherOwnerEmail, passwordEncoder.encode("pass123"));

        jdbc.update(
            "INSERT INTO stores (id, tenant_id, platform, shop_domain, status) " +
            "VALUES (?, ?, 'shopify', 'rsr-test.myshopify.com', 'disconnected')", storeId, tenantId);
        jdbc.update(
            "INSERT INTO products (id, tenant_id, store_id, external_id, title, status) " +
            "VALUES (?, ?, ?, 'PROD-RSR', 'Rebuild Widget', 'active')", productId, tenantId, storeId);
        jdbc.update(
            "INSERT INTO variants (id, tenant_id, product_id, external_id, title, sku) " +
            "VALUES (?, ?, ?, 'VAR-RSR', 'Rebuild Variant', 'RSR-001')", variantId, tenantId, productId);
        jdbc.update(
            "INSERT INTO locations (id, tenant_id, name, type, is_default, is_fulfillment) " +
            "VALUES (?, ?, 'RSR Warehouse', 'warehouse', true, true)", locationId, tenantId);

        ownerToken = login(ownerEmail);
        otherTenantOwnerToken = login(otherOwnerEmail);
    }

    private String login(String email) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<AccessTokenResponse> resp = rest.postForEntity(
            base() + "/api/v1/auth/login",
            new HttpEntity<>(Map.of("email", email, "password", "pass123"), headers),
            AccessTokenResponse.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        return resp.getBody().accessToken();
    }

    @BeforeEach void setCtx()   {
        TenantContext.set(tenantId);
        when(shopifyInventory.onReturnInspectionAvailable(any(), any(), any()))
            .thenReturn(CompletableFuture.completedFuture(null));
    }

    @AfterEach void clearCtx() {
        jdbc.update("DELETE FROM return_session_items WHERE tenant_id IN (?, ?)", tenantId, otherTenantId);
        jdbc.update("DELETE FROM return_session_shipments WHERE tenant_id IN (?, ?)", tenantId, otherTenantId);
        jdbc.update("DELETE FROM return_sessions WHERE tenant_id IN (?, ?)", tenantId, otherTenantId);
        jdbc.update("DELETE FROM piece_events WHERE tenant_id IN (?, ?)", tenantId, otherTenantId);
        jdbc.update("DELETE FROM allocations WHERE tenant_id IN (?, ?)", tenantId, otherTenantId);
        jdbc.update("DELETE FROM pieces WHERE tenant_id IN (?, ?)", tenantId, otherTenantId);
        jdbc.update("DELETE FROM shipments WHERE tenant_id IN (?, ?)", tenantId, otherTenantId);
        jdbc.update("DELETE FROM order_items WHERE tenant_id IN (?, ?)", tenantId, otherTenantId);
        jdbc.update("DELETE FROM orders WHERE tenant_id IN (?, ?)", tenantId, otherTenantId);
        TenantContext.clear();
    }

    private String base() { return "http://localhost:" + port; }

    private HttpHeaders authJson(String token) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    @SuppressWarnings("unchecked")
    private ResponseEntity<Map> post(String path, Object body, String token) {
        return rest.exchange(base() + path, HttpMethod.POST,
            new HttpEntity<>(body, authJson(token)), Map.class);
    }

    @SuppressWarnings("unchecked")
    private ResponseEntity<Map> get(String path, String token) {
        return rest.exchange(base() + path, HttpMethod.GET,
            new HttpEntity<>(authJson(token)), Map.class);
    }

    private UUID openSession(String token) {
        ResponseEntity<Map> resp = post("/api/v1/returns/sessions", Map.of(), token);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return UUID.fromString((String) resp.getBody().get("sessionId"));
    }

    // ── One-open-session claim ────────────────────────────────────────────────

    @Test
    void createSession_whenOneAlreadyOpen_returns409WithExistingSessionDetails() {
        UUID first = openSession(ownerToken);

        ResponseEntity<Map> resp = post("/api/v1/returns/sessions", Map.of(), ownerToken);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(resp.getBody().get("code")).isEqualTo("SESSION_ALREADY_OPEN");
        Map<String, Object> details = (Map<String, Object>) resp.getBody().get("details");
        assertThat(details.get("sessionId")).isEqualTo(first.toString());
    }

    @Test
    void createSession_afterFirstCloses_succeedsAgain() {
        UUID sessionId = openSession(ownerToken);
        ResponseEntity<Map> closeResp = post("/api/v1/returns/sessions/" + sessionId + "/close", Map.of(), ownerToken);
        assertThat(closeResp.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<Map> resp = post("/api/v1/returns/sessions", Map.of(), ownerToken);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    // ── Scan: legal / illegal / foreign / AWB / adopt ─────────────────────────

    @Test
    void scan_legalPiece_transitionsAndCreatesPendingItem() {
        UUID sessionId = openSession(ownerToken);
        String piece = createPiece(tenantId, "return_in_transit", null);

        ResponseEntity<Map> resp = post(
            "/api/v1/returns/sessions/" + sessionId + "/scan",
            Map.of("scan", "PC-" + piece), ownerToken);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().get("disposition")).isEqualTo("pending");
        assertThat(resp.getBody().get("unexpected")).isEqualTo(false);
        assertThat(pieceStatus(piece)).isEqualTo("return_pending_inspection");
    }

    @Test
    void scan_illegalStatusPiece_noTransition_unexpectedTrue() {
        UUID sessionId = openSession(ownerToken);
        String piece = createPiece(tenantId, "available", null);

        ResponseEntity<Map> resp = post(
            "/api/v1/returns/sessions/" + sessionId + "/scan",
            Map.of("scan", "PC-" + piece), ownerToken);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().get("unexpected")).isEqualTo(true);
        assertThat(resp.getBody().get("disposition")).isEqualTo("pending");
        assertThat(pieceStatus(piece))
            .as("illegal-state piece must not transition")
            .isEqualTo("available");
    }

    @Test
    void scan_foreignBarcode_returns422_noRowWritten() {
        UUID sessionId = openSession(ownerToken);

        ResponseEntity<Map> resp = post(
            "/api/v1/returns/sessions/" + sessionId + "/scan",
            Map.of("scan", "NOT-A-REAL-BARCODE-XYZ"), ownerToken);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        Integer itemCount = jdbc.queryForObject(
            "SELECT COUNT(*) FROM return_session_items WHERE session_id = ?", Integer.class, sessionId);
        assertThat(itemCount).isZero();
    }

    @Test
    void scan_awb_returnsExpectedPiecesTransientList_notPersistedAsItem() {
        UUID sessionId = openSession(ownerToken);
        UUID orderId = createOrder();
        createShipment(orderId, "9200001", "returning");
        String piece = createPiece(tenantId, "return_in_transit", orderId);
        createAlloc(orderId, piece);

        ResponseEntity<Map> resp = post(
            "/api/v1/returns/sessions/" + sessionId + "/scan",
            Map.of("scan", "9200001"), ownerToken);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().get("scanType")).isEqualTo("awb");
        List<Map<String, Object>> expected = (List<Map<String, Object>>) resp.getBody().get("expectedPieces");
        assertThat(expected).hasSize(1);
        assertThat(expected.get(0).get("id")).isEqualTo(piece);

        // Not persisted as a real item — only a shipment audit row.
        Integer itemCount = jdbc.queryForObject(
            "SELECT COUNT(*) FROM return_session_items WHERE session_id = ?", Integer.class, sessionId);
        assertThat(itemCount).isZero();
        Integer shipmentRows = jdbc.queryForObject(
            "SELECT COUNT(*) FROM return_session_shipments WHERE session_id = ? AND awb = '9200001'",
            Integer.class, sessionId);
        assertThat(shipmentRows).isEqualTo(1);
    }

    @Test
    void scan_pieceAlreadyPendingInspection_adopts_noSecondTransition_writesReturnReceivedAgain() {
        UUID orderId = createOrder();
        String piece = createPiece(tenantId, "return_pending_inspection", orderId);
        createAlloc(orderId, piece);
        // Simulate Bosta state-46 having already moved it here, pre-session.
        jdbc.update(
            "INSERT INTO piece_events (tenant_id, piece_id, event_type, from_status, to_status) " +
            "VALUES (?, ?, 'return_received', 'return_pending_inspection'::piece_status, " +
            "        'return_pending_inspection'::piece_status)",
            tenantId, piece);

        UUID sessionId = openSession(ownerToken);
        ResponseEntity<Map> resp = post(
            "/api/v1/returns/sessions/" + sessionId + "/scan",
            Map.of("scan", "PC-" + piece), ownerToken);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(pieceStatus(piece)).isEqualTo("return_pending_inspection");

        // Two return_received events now: the pre-existing webhook one + this session's adopt.
        Integer eventCount = jdbc.queryForObject(
            "SELECT COUNT(*) FROM piece_events WHERE piece_id = ? AND event_type = 'return_received'",
            Integer.class, piece);
        assertThat(eventCount).isEqualTo(2);
        String meta = jdbc.queryForObject(
            "SELECT metadata::text FROM piece_events WHERE piece_id = ? AND event_type = 'return_received' " +
            "ORDER BY created_at DESC LIMIT 1", String.class, piece);
        assertThat(meta).contains(sessionId.toString());
    }

    // ── Disposition ───────────────────────────────────────────────────────────

    @Test
    void disposition_damaged_missingReason_returns400() {
        UUID sessionId = openSession(ownerToken);
        String piece = createPiece(tenantId, "return_in_transit", null);
        post("/api/v1/returns/sessions/" + sessionId + "/scan", Map.of("scan", "PC-" + piece), ownerToken);

        ResponseEntity<Map> resp = post(
            "/api/v1/returns/sessions/" + sessionId + "/items/" + piece + "/disposition",
            Map.of("disposition", "damaged"), ownerToken);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void disposition_damaged_neverCallsShopify() {
        UUID sessionId = openSession(ownerToken);
        String piece = createPiece(tenantId, "return_in_transit", null);
        post("/api/v1/returns/sessions/" + sessionId + "/scan", Map.of("scan", "PC-" + piece), ownerToken);

        ResponseEntity<Map> resp = post(
            "/api/v1/returns/sessions/" + sessionId + "/items/" + piece + "/disposition",
            Map.of("disposition", "damaged", "reason", "torn seam"), ownerToken);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(pieceStatus(piece)).isEqualTo("damaged");
        // FR-17 v2 invariant: damaged-at-inspection never touches Shopify (never sellable there).
        verify(shopifyInventory, never()).onReturnInspectionAvailable(any(), eq(piece), any());
    }

    @Test
    void disposition_restock_callsShopifyIncrementOnly() {
        UUID sessionId = openSession(ownerToken);
        String piece = createPiece(tenantId, "return_in_transit", null);
        post("/api/v1/returns/sessions/" + sessionId + "/scan", Map.of("scan", "PC-" + piece), ownerToken);

        ResponseEntity<Map> resp = post(
            "/api/v1/returns/sessions/" + sessionId + "/items/" + piece + "/disposition",
            Map.of("disposition", "restock"), ownerToken);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(pieceStatus(piece)).isEqualTo("available");
        verify(shopifyInventory, times(1)).onReturnInspectionAvailable(eq(tenantId), eq(piece), any());
    }

    @Test
    void disposition_illegalStateItem_restockRejected_mismatchAlwaysAvailable() {
        UUID sessionId = openSession(ownerToken);
        String piece = createPiece(tenantId, "available", null);
        post("/api/v1/returns/sessions/" + sessionId + "/scan", Map.of("scan", "PC-" + piece), ownerToken);

        ResponseEntity<Map> restockResp = post(
            "/api/v1/returns/sessions/" + sessionId + "/items/" + piece + "/disposition",
            Map.of("disposition", "restock"), ownerToken);
        assertThat(restockResp.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        ResponseEntity<Map> mismatchResp = post(
            "/api/v1/returns/sessions/" + sessionId + "/items/" + piece + "/disposition",
            Map.of("disposition", "mismatch"), ownerToken);
        assertThat(mismatchResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(mismatchResp.getBody().get("disposition")).isEqualTo("mismatch");
    }

    // ── Close ─────────────────────────────────────────────────────────────────

    @Test
    void close_blockedWithPendingItems_returns409WithBlockingItemsList() {
        UUID sessionId = openSession(ownerToken);
        String piece = createPiece(tenantId, "return_in_transit", null);
        post("/api/v1/returns/sessions/" + sessionId + "/scan", Map.of("scan", "PC-" + piece), ownerToken);

        ResponseEntity<Map> resp = post("/api/v1/returns/sessions/" + sessionId + "/close", Map.of(), ownerToken);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(resp.getBody().get("code")).isEqualTo("SESSION_CLOSE_BLOCKED");
        Map<String, Object> details = (Map<String, Object>) resp.getBody().get("details");
        List<Map<String, Object>> blocking = (List<Map<String, Object>>) details.get("blockingItems");
        assertThat(blocking).hasSize(1);
        assertThat(blocking.get(0).get("pieceId")).isEqualTo(piece);
    }

    @Test
    void close_afterAllDispositioned_succeeds_withCorrectCounts() {
        UUID sessionId = openSession(ownerToken);
        String restocked = createPiece(tenantId, "return_in_transit", null);
        String damaged    = createPiece(tenantId, "return_in_transit", null);
        post("/api/v1/returns/sessions/" + sessionId + "/scan", Map.of("scan", "PC-" + restocked), ownerToken);
        post("/api/v1/returns/sessions/" + sessionId + "/scan", Map.of("scan", "PC-" + damaged), ownerToken);
        post("/api/v1/returns/sessions/" + sessionId + "/items/" + restocked + "/disposition",
            Map.of("disposition", "restock"), ownerToken);
        post("/api/v1/returns/sessions/" + sessionId + "/items/" + damaged + "/disposition",
            Map.of("disposition", "damaged", "reason", "cracked"), ownerToken);

        ResponseEntity<Map> resp = post("/api/v1/returns/sessions/" + sessionId + "/close", Map.of(), ownerToken);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().get("restockedCount")).isEqualTo(1);
        assertThat(resp.getBody().get("damagedCount")).isEqualTo(1);
        assertThat(resp.getBody().get("pieceCount")).isEqualTo(2);
        String status = jdbc.queryForObject(
            "SELECT status FROM return_sessions WHERE id = ?", String.class, sessionId);
        assertThat(status).isEqualTo("closed");
    }

    // ── Abandon (change B: soft-delete, no revert) ────────────────────────────

    @Test
    void abandon_softDeletes_doesNotRevertPendingPieces() {
        UUID sessionId = openSession(ownerToken);
        String piece = createPiece(tenantId, "return_in_transit", null);
        post("/api/v1/returns/sessions/" + sessionId + "/scan", Map.of("scan", "PC-" + piece), ownerToken);
        assertThat(pieceStatus(piece)).isEqualTo("return_pending_inspection");

        ResponseEntity<Void> resp = rest.exchange(
            base() + "/api/v1/returns/sessions/" + sessionId, HttpMethod.DELETE,
            new HttpEntity<>(authJson(ownerToken)), Void.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        String status = jdbc.queryForObject(
            "SELECT status FROM return_sessions WHERE id = ?", String.class, sessionId);
        assertThat(status).isEqualTo("abandoned");
        assertThat(pieceStatus(piece))
            .as("change B: abandon does not revert — piece stays at return_pending_inspection")
            .isEqualTo("return_pending_inspection");
        // Session + item rows are kept, not hard-deleted.
        Integer itemCount = jdbc.queryForObject(
            "SELECT COUNT(*) FROM return_session_items WHERE session_id = ?", Integer.class, sessionId);
        assertThat(itemCount).isEqualTo(1);

        // One-open-session index must now allow a new session.
        ResponseEntity<Map> next = post("/api/v1/returns/sessions", Map.of(), ownerToken);
        assertThat(next.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    // ── Cross-tenant isolation ────────────────────────────────────────────────

    @Test
    void crossTenant_getSession_notFound() {
        UUID sessionId = openSession(ownerToken);

        ResponseEntity<Map> resp = get("/api/v1/returns/sessions/" + sessionId, otherTenantOwnerToken);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void crossTenant_scan_notFound() {
        UUID sessionId = openSession(ownerToken);

        ResponseEntity<Map> resp = post(
            "/api/v1/returns/sessions/" + sessionId + "/scan",
            Map.of("scan", "irrelevant"), otherTenantOwnerToken);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ── Sessions list pagination ──────────────────────────────────────────────

    @Test
    void listSessions_paginated_correctTotal() {
        UUID s1 = openSession(ownerToken);
        post("/api/v1/returns/sessions/" + s1 + "/close", Map.of(), ownerToken);
        UUID s2 = openSession(ownerToken);
        post("/api/v1/returns/sessions/" + s2 + "/close", Map.of(), ownerToken);
        UUID s3 = openSession(ownerToken);
        post("/api/v1/returns/sessions/" + s3 + "/close", Map.of(), ownerToken);

        ResponseEntity<Map> resp = get("/api/v1/returns/sessions?page=0&size=2", ownerToken);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().get("total")).isEqualTo(3);
        List<Map<String, Object>> items = (List<Map<String, Object>>) resp.getBody().get("items");
        assertThat(items).hasSize(2);
    }

    // ── Unassigned pending — load-bearing query, every branch ────────────────

    @Test
    void analytics_unassignedPending_everyBranch() {
        // (a) baseline: no session item at all → included.
        String baseline = createPiece(tenantId, "return_pending_inspection", null);

        // (b) item in an OPEN session, pending → excluded (still being worked).
        String stillOpen = createPiece(tenantId, "return_pending_inspection", null);
        UUID openSessionId = openSession(ownerToken);
        insertSessionItem(openSessionId, stillOpen, "pending");

        // (c) item in a CLOSED session, resolved (restocked) → excluded.
        // Use a distinct piece whose actual status is 'available' (post-restock) so it
        // wouldn't appear anyway by status — this branch is about a piece that is STILL
        // at return_pending_inspection with a resolved item (the realistic mismatch case,
        // see (e)); modeled directly here for the "resolved excludes" branch in isolation.
        String resolvedElsewhere = createPiece(tenantId, "return_pending_inspection", null);
        UUID closedSessionId = closedSession();
        insertSessionItem(closedSessionId, resolvedElsewhere, "restocked");

        // (d) item in an ABANDONED session, still pending → INCLUDED (change B: no revert,
        // abandoned pieces resurface as unassigned).
        String abandonedPending = createPiece(tenantId, "return_pending_inspection", null);
        UUID abandonedSessionId = abandonedSession();
        insertSessionItem(abandonedSessionId, abandonedPending, "pending");

        // (e) item in a CLOSED session, disposition='mismatch' → excluded, even though the
        // piece is physically still at return_pending_inspection forever (mismatch never
        // transitions it).
        String mismatched = createPiece(tenantId, "return_pending_inspection", null);
        insertSessionItem(closedSessionId, mismatched, "mismatch");

        ResponseEntity<Map> resp = get("/api/v1/returns/analytics", ownerToken);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<String, Object>> unassigned = (List<Map<String, Object>>) resp.getBody().get("unassignedPending");
        List<String> ids = unassigned.stream().map(m -> (String) m.get("pieceId")).toList();

        assertThat(ids).as("(a) baseline, no item at all").contains(baseline);
        assertThat(ids).as("(b) pending item in an OPEN session").doesNotContain(stillOpen);
        assertThat(ids).as("(c) resolved item in a closed session").doesNotContain(resolvedElsewhere);
        assertThat(ids).as("(d) pending item in an ABANDONED session — resurfaces").contains(abandonedPending);
        assertThat(ids).as("(e) mismatch-resolved item, piece still physically pending").doesNotContain(mismatched);
    }

    // ── Reprint: new any-status surface ────────────────────────────────────────

    @Test
    void reprintLabel_newEndpoint_worksOnAvailablePiece() {
        UUID sessionId = openSession(ownerToken);
        String piece = createPiece(tenantId, "available", null);

        ResponseEntity<byte[]> resp = rest.exchange(
            base() + "/api/v1/returns/sessions/" + sessionId + "/pieces/" + piece + "/reprint-label",
            HttpMethod.POST, new HttpEntity<>(authJson(ownerToken)), byte[].class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isNotEmpty();
        Integer reprintEvents = jdbc.queryForObject(
            "SELECT COUNT(*) FROM piece_events WHERE piece_id = ? AND event_type = 'label_reprinted'",
            Integer.class, piece);
        assertThat(reprintEvents).isEqualTo(1);
    }

    // ── Fixtures ──────────────────────────────────────────────────────────────

    private UUID createOrder() {
        return jdbc.queryForObject(
            "INSERT INTO orders (tenant_id, store_id, external_id, number, status, " +
            "    customer_name, customer_phone, payment_method, placed_at) " +
            "VALUES (?, ?, ?, 'ORD-RSR', 'returning'::order_status, 'Buyer', '01000000003', 'cod', now()) " +
            "RETURNING id",
            UUID.class, tenantId, storeId, "EXT-RSR-" + UUID.randomUUID());
    }

    private void createShipment(UUID orderId, String trackingNumber, String state) {
        jdbc.update(
            "INSERT INTO shipments (id, tenant_id, order_id, tracking_number, internal_state) " +
            "VALUES (gen_random_uuid(), ?, ?, ?, ?::shipment_internal_state)",
            tenantId, orderId, trackingNumber, state);
    }

    private String createPiece(UUID tid, String status, UUID orderId) {
        String id = UlidGenerator.generate();
        jdbc.update(
            "INSERT INTO pieces " +
            "(id, tenant_id, variant_id, barcode, short_code, status, current_order_id, last_event_at) " +
            "VALUES (?, ?, ?, ?, 'P' || LPAD((abs(hashtext(?)) % 999999 + 1)::text, 6, '0'), ?::piece_status, ?, now())",
            id, tid, variantId, "PC-" + id, id, status, orderId);
        return id;
    }

    private void createAlloc(UUID orderId, String pieceId) {
        UUID itemId = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO order_items (id, tenant_id, order_id, variant_id, quantity) " +
            "VALUES (?, ?, ?, ?, 1)", itemId, tenantId, orderId, variantId);
        jdbc.update(
            "INSERT INTO allocations (id, tenant_id, order_item_id, piece_id, status) " +
            "VALUES (gen_random_uuid(), ?, ?, ?, 'packed')",
            tenantId, itemId, pieceId);
    }

    private String pieceStatus(String pieceId) {
        return jdbc.queryForObject("SELECT status::text FROM pieces WHERE id = ?", String.class, pieceId);
    }

    private UUID closedSession() {
        UUID id = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO return_sessions (id, tenant_id, status, opened_by, closed_by, closed_at) " +
            "VALUES (?, ?, 'closed', ?, ?, now())", id, tenantId, actorId, actorId);
        return id;
    }

    private UUID abandonedSession() {
        UUID id = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO return_sessions (id, tenant_id, status, opened_by, closed_by, closed_at) " +
            "VALUES (?, ?, 'abandoned', ?, ?, now())", id, tenantId, actorId, actorId);
        return id;
    }

    private void insertSessionItem(UUID sessionId, String pieceId, String disposition) {
        jdbc.update(
            "INSERT INTO return_session_items " +
            "(id, tenant_id, session_id, piece_id, scanned_by, scan_source, disposition, disposition_at) " +
            "VALUES (gen_random_uuid(), ?, ?, ?, ?, 'barcode', ?, " +
            "        CASE WHEN ? = 'pending' THEN NULL ELSE now() END)",
            tenantId, sessionId, pieceId, actorId, disposition, disposition);
    }
}
