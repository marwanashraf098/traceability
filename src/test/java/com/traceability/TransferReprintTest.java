package com.traceability;

import com.traceability.identity.model.AccessTokenResponse;
import com.traceability.inventory.*;
import com.traceability.tenancy.TenantContext;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
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

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FR-22.5 — TransferController.reprintOutstandingLabels (POST .../reprint-outstanding).
 *
 * No status-gated returns endpoint is reused here on purpose (that one is hard-gated to
 * return_pending_inspection/damaged and would reject an out_on_transfer piece) — this is a
 * dedicated transfer-scoped endpoint over transfer_pieces.outcome IS NULL.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TransferReprintTest {

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
    @Autowired TransferService  transferSvc;
    @Autowired PasswordEncoder  passwordEncoder;

    UUID tenantId;
    UUID ownerId;
    UUID variantId;
    UUID destinationLocationId;
    UUID fulfillmentLocationId;
    String ownerToken;

    @BeforeAll
    void setup() {
        tenantId = UUID.randomUUID();
        ownerId  = UUID.randomUUID();
        UUID storeId   = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        variantId = UUID.randomUUID();
        destinationLocationId  = UUID.randomUUID();
        fulfillmentLocationId  = UUID.randomUUID();

        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, 'Reprint Test Co')", tenantId);
        String ownerEmail = "owner-tr-" + ownerId + "@test.local";
        jdbc.update(
            "INSERT INTO users (id, tenant_id, name, email, password_hash, role, active) " +
            "VALUES (?, ?, 'Owner', ?, ?, 'owner', true)",
            ownerId, tenantId, ownerEmail, passwordEncoder.encode("pass123"));
        jdbc.update(
            "INSERT INTO stores (id, tenant_id, platform, shop_domain, status) " +
            "VALUES (?, ?, 'shopify', 'reprint-test.myshopify.com', 'disconnected')",
            storeId, tenantId);
        jdbc.update(
            "INSERT INTO products (id, tenant_id, store_id, external_id, title, status) " +
            "VALUES (?, ?, ?, 'PROD-TRP', 'Reprint Widget', 'active')",
            productId, tenantId, storeId);
        jdbc.update(
            "INSERT INTO variants (id, tenant_id, product_id, external_id, title, sku) " +
            "VALUES (?, ?, ?, 'VAR-TRP', 'Reprint Widget Variant', 'TRP-001')",
            variantId, tenantId, productId);
        jdbc.update(
            "INSERT INTO locations (id, tenant_id, name, type, is_default, is_fulfillment) " +
            "VALUES (?, ?, 'Main Warehouse', 'warehouse', true, true)",
            fulfillmentLocationId, tenantId);
        jdbc.update(
            "INSERT INTO locations (id, tenant_id, name, type, is_default, is_fulfillment) " +
            "VALUES (?, ?, 'Test Showroom', 'showroom', false, false)",
            destinationLocationId, tenantId);

        HttpHeaders loginHeaders = new HttpHeaders();
        loginHeaders.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<AccessTokenResponse> loginResp = rest.postForEntity(
            base() + "/api/v1/auth/login",
            new HttpEntity<>(Map.of("email", ownerEmail, "password", "pass123"), loginHeaders),
            AccessTokenResponse.class);
        assertThat(loginResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        ownerToken = loginResp.getBody().accessToken();
    }

    @BeforeEach
    void setTenantContext() { TenantContext.set(tenantId); }

    @AfterEach
    void clearTenantContext() { TenantContext.clear(); }

    private String base() { return "http://localhost:" + port; }

    private String insertAvailablePiece() {
        String id = UlidGenerator.generate();
        jdbc.update(
            "INSERT INTO pieces (id, tenant_id, variant_id, barcode, short_code, status) " +
            "VALUES (?, ?, ?, ?, 'P' || LPAD((abs(hashtext(?)) % 999999 + 1)::text, 6, '0'), 'available'::piece_status)",
            id, tenantId, variantId, "PC-" + id, id);
        return id;
    }

    private UUID openTransferWithOutstanding(int n) {
        UUID transferId = transferSvc.createTransfer("showroom", destinationLocationId, null, "test", ownerId);
        for (int i = 0; i < n; i++) {
            String pieceId = insertAvailablePiece();
            transferSvc.scanOut(transferId, "PC-" + pieceId, ownerId);
        }
        return transferId;
    }

    private HttpHeaders authHeaders(String token) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        return h;
    }

    // -----------------------------------------------------------------------

    @Test
    void reprintOutstanding_returnsMergedPdfAndWritesOneReprintEventPerPiece() {
        UUID transferId = openTransferWithOutstanding(3);

        ResponseEntity<byte[]> resp = rest.exchange(
            base() + "/api/v1/transfers/" + transferId + "/reprint-outstanding",
            HttpMethod.POST, new HttpEntity<>(authHeaders(ownerToken)), byte[].class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PDF);

        try (PDDocument doc = Loader.loadPDF(resp.getBody())) {
            assertThat(doc.getNumberOfPages()).as("one page per outstanding piece").isEqualTo(3);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        List<String> pieceIds = jdbc.queryForList(
            "SELECT piece_id FROM transfer_pieces WHERE transfer_id = ?", String.class, transferId);
        for (String pieceId : pieceIds) {
            Integer reprintEvents = jdbc.queryForObject(
                "SELECT COUNT(*) FROM piece_events WHERE piece_id = ? AND event_type = 'label_reprinted'",
                Integer.class, pieceId);
            assertThat(reprintEvents).as("piece %s has one label_reprinted event", pieceId).isEqualTo(1);
            // recordLabelReprinted() writes from_status = to_status = current — status unchanged.
            String status = jdbc.queryForObject("SELECT status FROM pieces WHERE id = ?", String.class, pieceId);
            assertThat(status).isEqualTo("out_on_transfer");
        }
    }

    @Test
    void reprintOutstanding_excludesResolvedPieces() {
        UUID transferId = openTransferWithOutstanding(2);
        String resolvedPieceId = jdbc.queryForObject(
            "SELECT piece_id FROM transfer_pieces WHERE transfer_id = ? ORDER BY created_at ASC LIMIT 1",
            String.class, transferId);
        transferSvc.beginReconcile(transferId, ownerId);
        transferSvc.reconcileScanBack(transferId, "PC-" + resolvedPieceId, "good", ownerId);

        ResponseEntity<byte[]> resp = rest.exchange(
            base() + "/api/v1/transfers/" + transferId + "/reprint-outstanding",
            HttpMethod.POST, new HttpEntity<>(authHeaders(ownerToken)), byte[].class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        try (PDDocument doc = Loader.loadPDF(resp.getBody())) {
            assertThat(doc.getNumberOfPages()).as("only the 1 still-outstanding piece").isEqualTo(1);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        Integer resolvedPieceReprintEvents = jdbc.queryForObject(
            "SELECT COUNT(*) FROM piece_events WHERE piece_id = ? AND event_type = 'label_reprinted'",
            Integer.class, resolvedPieceId);
        assertThat(resolvedPieceReprintEvents).as("resolved piece must not be reprinted").isEqualTo(0);
    }

    @Test
    void reprintOutstanding_noOutstandingPieces_returns422() {
        UUID transferId = openTransferWithOutstanding(1);
        String pieceId = jdbc.queryForObject(
            "SELECT piece_id FROM transfer_pieces WHERE transfer_id = ?", String.class, transferId);
        transferSvc.beginReconcile(transferId, ownerId);
        transferSvc.reconcileScanBack(transferId, "PC-" + pieceId, "good", ownerId);

        ResponseEntity<byte[]> resp = rest.exchange(
            base() + "/api/v1/transfers/" + transferId + "/reprint-outstanding",
            HttpMethod.POST, new HttpEntity<>(authHeaders(ownerToken)), byte[].class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    void reprintOutstanding_transferNotFound_returns404() {
        ResponseEntity<byte[]> resp = rest.exchange(
            base() + "/api/v1/transfers/" + UUID.randomUUID() + "/reprint-outstanding",
            HttpMethod.POST, new HttpEntity<>(authHeaders(ownerToken)), byte[].class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void reprintOutstanding_workerRole_returns403() {
        UUID transferId = openTransferWithOutstanding(1);

        UUID workerId = UUID.randomUUID();
        String workerEmail = "worker-tr-" + workerId + "@test.local";
        jdbc.update(
            "INSERT INTO users (id, tenant_id, name, email, password_hash, role, active) " +
            "VALUES (?, ?, 'Worker', ?, ?, 'worker', true)",
            workerId, tenantId, workerEmail, passwordEncoder.encode("pass123"));

        HttpHeaders loginHeaders = new HttpHeaders();
        loginHeaders.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<AccessTokenResponse> loginResp = rest.postForEntity(
            base() + "/api/v1/auth/login",
            new HttpEntity<>(Map.of("email", workerEmail, "password", "pass123"), loginHeaders),
            AccessTokenResponse.class);
        String workerToken = loginResp.getBody().accessToken();

        ResponseEntity<byte[]> resp = rest.exchange(
            base() + "/api/v1/transfers/" + transferId + "/reprint-outstanding",
            HttpMethod.POST, new HttpEntity<>(authHeaders(workerToken)), byte[].class);

        assertThat(resp.getStatusCode())
            .as("WORKER role must receive 403 on reprint-outstanding")
            .isEqualTo(HttpStatus.FORBIDDEN);
    }
}
