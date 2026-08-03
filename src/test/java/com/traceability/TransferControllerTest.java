package com.traceability;

import com.traceability.identity.model.AccessTokenResponse;
import com.traceability.inventory.*;
import com.traceability.tenancy.TenantContext;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FR-22.6 — TransferController: role gates + {code, message_en, message_ar} error bodies.
 *
 * Business logic (balance enforcement, FIFO, metadata honesty, race guards) is already
 * covered by TransferServiceTest/TransferReconcileTest at the service layer. This class
 * covers what's new at the HTTP boundary: who can call what, and the bilingual error shape
 * for the "command" family (TransferException, reused from FR-22.3 — not a second handler).
 * scan-out/scan-back return ScanOutResult/ScanBackResult directly (200, success/code fields),
 * mirroring FulfillController's /scan — verified here by shape, not re-verified for every
 * business code (already exhaustively tested at the service layer).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TransferControllerTest {

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

    UUID tenantId;
    UUID ownerId;
    UUID variantId;
    UUID destinationLocationId;
    UUID fulfillmentLocationId;
    String ownerToken;
    String workerToken;

    @BeforeAll
    void setup() {
        tenantId = UUID.randomUUID();
        ownerId  = UUID.randomUUID();
        UUID workerId  = UUID.randomUUID();
        UUID storeId   = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        variantId = UUID.randomUUID();
        destinationLocationId = UUID.randomUUID();
        fulfillmentLocationId = UUID.randomUUID();

        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, 'Controller Test Co')", tenantId);
        String ownerEmail = "owner-tc-" + ownerId + "@test.local";
        String workerEmail = "worker-tc-" + workerId + "@test.local";
        jdbc.update(
            "INSERT INTO users (id, tenant_id, name, email, password_hash, role, active) " +
            "VALUES (?, ?, 'Owner', ?, ?, 'owner', true)",
            ownerId, tenantId, ownerEmail, passwordEncoder.encode("pass123"));
        jdbc.update(
            "INSERT INTO users (id, tenant_id, name, email, password_hash, role, active) " +
            "VALUES (?, ?, 'Worker', ?, ?, 'worker', true)",
            workerId, tenantId, workerEmail, passwordEncoder.encode("pass123"));
        jdbc.update(
            "INSERT INTO stores (id, tenant_id, platform, shop_domain, status) " +
            "VALUES (?, ?, 'shopify', 'controller-test.myshopify.com', 'disconnected')",
            storeId, tenantId);
        jdbc.update(
            "INSERT INTO products (id, tenant_id, store_id, external_id, title, status) " +
            "VALUES (?, ?, ?, 'PROD-TC', 'Controller Widget', 'active')",
            productId, tenantId, storeId);
        jdbc.update(
            "INSERT INTO variants (id, tenant_id, product_id, external_id, title, sku) " +
            "VALUES (?, ?, ?, 'VAR-TC', 'Controller Widget Variant', 'TC-001')",
            variantId, tenantId, productId);
        jdbc.update(
            "INSERT INTO locations (id, tenant_id, name, type, is_default, is_fulfillment) " +
            "VALUES (?, ?, 'Main Warehouse', 'warehouse', true, true)",
            fulfillmentLocationId, tenantId);
        jdbc.update(
            "INSERT INTO locations (id, tenant_id, name, type, is_default, is_fulfillment) " +
            "VALUES (?, ?, 'Test Showroom', 'showroom', false, false)",
            destinationLocationId, tenantId);

        ownerToken  = login(ownerEmail);
        workerToken = login(workerEmail);
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

    @BeforeEach
    void setTenantContext() { TenantContext.set(tenantId); }

    @AfterEach
    void clearTenantContext() { TenantContext.clear(); }

    private String base() { return "http://localhost:" + port; }

    private HttpHeaders authJson(String token) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    private String insertAvailablePiece() {
        String id = UlidGenerator.generate();
        jdbc.update(
            "INSERT INTO pieces (id, tenant_id, variant_id, barcode, short_code, status) " +
            "VALUES (?, ?, ?, ?, 'P' || LPAD((abs(hashtext(?)) % 999999 + 1)::text, 6, '0'), 'available'::piece_status)",
            id, tenantId, variantId, "PC-" + id, id);
        return id;
    }

    private UUID createOpenTransfer(String token) {
        ResponseEntity<Map> resp = rest.exchange(
            base() + "/api/v1/transfers", HttpMethod.POST,
            new HttpEntity<>(Map.of("transferType", "showroom",
                "destinationLocationId", destinationLocationId.toString()), authJson(token)),
            Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return UUID.fromString((String) resp.getBody().get("id"));
    }

    // -----------------------------------------------------------------------
    // createTransfer — OWNER/MANAGER only
    // -----------------------------------------------------------------------

    @Test
    void createTransfer_owner_succeeds() {
        ResponseEntity<Map> resp = rest.exchange(
            base() + "/api/v1/transfers", HttpMethod.POST,
            new HttpEntity<>(Map.of("transferType", "dryclean",
                "destinationLocationId", destinationLocationId.toString()), authJson(ownerToken)),
            Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(resp.getBody().get("id")).isNotNull();
    }

    @Test
    void createTransfer_worker_forbidden() {
        ResponseEntity<Map> resp = rest.exchange(
            base() + "/api/v1/transfers", HttpMethod.POST,
            new HttpEntity<>(Map.of("transferType", "dryclean",
                "destinationLocationId", destinationLocationId.toString()), authJson(workerToken)),
            Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // -----------------------------------------------------------------------
    // scan-out — isAuthenticated (mirrors pick permissions); ScanOutResult shape
    // -----------------------------------------------------------------------

    @Test
    void scanOut_worker_allowedAndReturnsScanResultShape() {
        UUID transferId = createOpenTransfer(ownerToken);
        String pieceId = insertAvailablePiece();

        long start = System.nanoTime();
        ResponseEntity<Map> resp = rest.exchange(
            base() + "/api/v1/transfers/" + transferId + "/scan-out", HttpMethod.POST,
            new HttpEntity<>(Map.of("barcode", "PC-" + pieceId), authJson(workerToken)),
            Map.class);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(resp.getStatusCode())
            .as("WORKER is allowed to scan out — mirrors pick permissions")
            .isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().get("success")).isEqualTo(true);
        assertThat(resp.getBody().get("code")).isEqualTo("SCANNED");
        assertThat(resp.getBody().get("pieceId")).isEqualTo(pieceId);
        // Invariant 7 target is <=300ms server-side; this asserts a generous smoke bound
        // (includes HTTP + Testcontainers-local round trip, not just the service call).
        assertThat(elapsedMs).as("scan-out round trip smoke bound").isLessThan(3000);
    }

    @Test
    void scanOut_pieceNotFound_returns200WithRejectedCode() {
        // Mirrors FulfillService.scan(): a rejected scan is still HTTP 200 with a
        // {success:false, code, message} body — not an exception, not a 4xx.
        UUID transferId = createOpenTransfer(ownerToken);

        ResponseEntity<Map> resp = rest.exchange(
            base() + "/api/v1/transfers/" + transferId + "/scan-out", HttpMethod.POST,
            new HttpEntity<>(Map.of("barcode", "PC-DOESNOTEXIST"), authJson(ownerToken)),
            Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().get("success")).isEqualTo(false);
        assertThat(resp.getBody().get("code")).isEqualTo("PIECE_NOT_FOUND");
    }

    // -----------------------------------------------------------------------
    // Reconcile family — OWNER/MANAGER only
    // -----------------------------------------------------------------------

    @Test
    void beginReconcile_worker_forbidden() {
        UUID transferId = createOpenTransfer(ownerToken);
        ResponseEntity<Void> resp = rest.exchange(
            base() + "/api/v1/transfers/" + transferId + "/begin-reconcile", HttpMethod.POST,
            new HttpEntity<>(null, authJson(workerToken)), Void.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void beginReconcileThenScanBackThenCloseFlow_owner_succeeds() {
        UUID transferId = createOpenTransfer(ownerToken);
        String pieceId = insertAvailablePiece();
        rest.exchange(base() + "/api/v1/transfers/" + transferId + "/scan-out", HttpMethod.POST,
            new HttpEntity<>(Map.of("barcode", "PC-" + pieceId), authJson(ownerToken)), Map.class);

        ResponseEntity<Void> beginResp = rest.exchange(
            base() + "/api/v1/transfers/" + transferId + "/begin-reconcile", HttpMethod.POST,
            new HttpEntity<>(null, authJson(ownerToken)), Void.class);
        assertThat(beginResp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        ResponseEntity<Map> scanBackResp = rest.exchange(
            base() + "/api/v1/transfers/" + transferId + "/scan-back", HttpMethod.POST,
            new HttpEntity<>(Map.of("barcode", "PC-" + pieceId, "condition", "good"), authJson(ownerToken)),
            Map.class);
        assertThat(scanBackResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(scanBackResp.getBody().get("success")).isEqualTo(true);
        assertThat(scanBackResp.getBody().get("outcome")).isEqualTo("returned_good");

        ResponseEntity<Void> closeResp = rest.exchange(
            base() + "/api/v1/transfers/" + transferId + "/close", HttpMethod.POST,
            new HttpEntity<>(null, authJson(ownerToken)), Void.class);
        assertThat(closeResp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        String status = jdbc.queryForObject("SELECT status FROM transfers WHERE id = ?", String.class, transferId);
        assertThat(status).isEqualTo("closed");
    }

    @Test
    void classifyShortfall_worker_forbidden() {
        UUID transferId = createOpenTransfer(ownerToken);
        UUID fakeLineId = UUID.randomUUID();
        ResponseEntity<Void> resp = rest.exchange(
            base() + "/api/v1/transfers/" + transferId + "/classify", HttpMethod.POST,
            new HttpEntity<>(Map.of("lineId", fakeLineId.toString(), "sold", 1, "lost", 0,
                "condemnedNotReturned", 0), authJson(workerToken)),
            Void.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void closeTransfer_worker_forbidden() {
        UUID transferId = createOpenTransfer(ownerToken);
        ResponseEntity<Void> resp = rest.exchange(
            base() + "/api/v1/transfers/" + transferId + "/close", HttpMethod.POST,
            new HttpEntity<>(null, authJson(workerToken)), Void.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // -----------------------------------------------------------------------
    // {code, message_en, message_ar} error body (TransferException, reused from FR-22.3)
    // -----------------------------------------------------------------------

    @Test
    void beginReconcile_transferNotFound_returnsBilingualErrorBody() {
        ResponseEntity<Map> resp = rest.exchange(
            base() + "/api/v1/transfers/" + UUID.randomUUID() + "/begin-reconcile", HttpMethod.POST,
            new HttpEntity<>(null, authJson(ownerToken)), Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(resp.getBody().get("code")).isEqualTo("TRANSFER_NOT_FOUND");
        assertThat(resp.getBody().get("message_en")).isEqualTo("Transfer not found");
        assertThat(resp.getBody().get("message_ar")).isEqualTo("لم يتم العثور على عملية النقل");
    }

    @Test
    void closeTransfer_hasOutstandingPieces_returnsBilingualErrorBody() {
        UUID transferId = createOpenTransfer(ownerToken);
        String pieceId = insertAvailablePiece();
        rest.exchange(base() + "/api/v1/transfers/" + transferId + "/scan-out", HttpMethod.POST,
            new HttpEntity<>(Map.of("barcode", "PC-" + pieceId), authJson(ownerToken)), Map.class);
        rest.exchange(base() + "/api/v1/transfers/" + transferId + "/begin-reconcile", HttpMethod.POST,
            new HttpEntity<>(null, authJson(ownerToken)), Void.class);

        ResponseEntity<Map> resp = rest.exchange(
            base() + "/api/v1/transfers/" + transferId + "/close", HttpMethod.POST,
            new HttpEntity<>(null, authJson(ownerToken)), Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(resp.getBody().get("code")).isEqualTo("TRANSFER_HAS_OUTSTANDING_PIECES");
        assertThat((String) resp.getBody().get("message_en")).contains("outstanding");
        assertThat((String) resp.getBody().get("message_ar")).isNotBlank();
    }

    // -----------------------------------------------------------------------
    // GET endpoints — isAuthenticated
    // -----------------------------------------------------------------------

    @Test
    void listOpen_worker_allowed() {
        createOpenTransfer(ownerToken);
        ResponseEntity<java.util.List> resp = rest.exchange(
            base() + "/api/v1/transfers", HttpMethod.GET,
            new HttpEntity<>(authJson(workerToken)), java.util.List.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isNotEmpty();
    }

    @Test
    void getTransfer_worker_allowed() {
        UUID transferId = createOpenTransfer(ownerToken);
        ResponseEntity<Map> resp = rest.exchange(
            base() + "/api/v1/transfers/" + transferId, HttpMethod.GET,
            new HttpEntity<>(authJson(workerToken)), Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().get("id")).isEqualTo(transferId.toString());
    }
}
