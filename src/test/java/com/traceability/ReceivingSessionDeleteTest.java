package com.traceability;

import com.traceability.identity.model.AccessTokenResponse;
import com.traceability.inventory.UlidGenerator;
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
 * DELETE /api/v1/receiving/sessions/{sessionId} — new feature: delete an OPEN
 * receiving session (+ its lines). HARD BOUNDARY: a finalized session (has
 * generated pieces — real inventory/custody) must never be deletable, enforced
 * at the backend. OWNER/MANAGER only.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReceivingSessionDeleteTest {

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

    UUID tenantAId, tenantBId;
    UUID variantId;
    UUID locationId;
    String ownerToken;
    String workerToken;
    String otherTenantOwnerToken;

    @BeforeAll
    void setup() {
        tenantAId = UUID.randomUUID();
        tenantBId = UUID.randomUUID();
        UUID ownerId    = UUID.randomUUID();
        UUID workerId   = UUID.randomUUID();
        UUID otherOwnerId = UUID.randomUUID();
        UUID storeId    = UUID.randomUUID();
        UUID productId  = UUID.randomUUID();
        variantId  = UUID.randomUUID();
        locationId = UUID.randomUUID();

        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, 'Delete Test Co A')", tenantAId);
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, 'Delete Test Co B')", tenantBId);

        String ownerEmail = "owner-rsd-" + ownerId + "@test.local";
        String workerEmail = "worker-rsd-" + workerId + "@test.local";
        String otherOwnerEmail = "owner-rsd-b-" + otherOwnerId + "@test.local";
        jdbc.update(
            "INSERT INTO users (id, tenant_id, name, email, password_hash, role, active) " +
            "VALUES (?, ?, 'Owner', ?, ?, 'owner', true)",
            ownerId, tenantAId, ownerEmail, passwordEncoder.encode("pass123"));
        jdbc.update(
            "INSERT INTO users (id, tenant_id, name, email, password_hash, role, active) " +
            "VALUES (?, ?, 'Worker', ?, ?, 'worker', true)",
            workerId, tenantAId, workerEmail, passwordEncoder.encode("pass123"));
        jdbc.update(
            "INSERT INTO users (id, tenant_id, name, email, password_hash, role, active) " +
            "VALUES (?, ?, 'Owner B', ?, ?, 'owner', true)",
            otherOwnerId, tenantBId, otherOwnerEmail, passwordEncoder.encode("pass123"));

        jdbc.update(
            "INSERT INTO stores (id, tenant_id, platform, shop_domain, status) " +
            "VALUES (?, ?, 'shopify', 'rsd-test.myshopify.com', 'disconnected')",
            storeId, tenantAId);
        jdbc.update(
            "INSERT INTO products (id, tenant_id, store_id, external_id, title, status) " +
            "VALUES (?, ?, ?, 'PROD-RSD', 'Delete Test Widget', 'active')",
            productId, tenantAId, storeId);
        jdbc.update(
            "INSERT INTO variants (id, tenant_id, product_id, external_id, title, sku) " +
            "VALUES (?, ?, ?, 'VAR-RSD', 'Delete Test Variant', 'RSD-001')",
            variantId, tenantAId, productId);
        jdbc.update(
            "INSERT INTO locations (id, tenant_id, name, type, is_default, is_fulfillment) " +
            "VALUES (?, ?, 'RSD Warehouse', 'warehouse', true, true)",
            locationId, tenantAId);

        ownerToken  = login(ownerEmail);
        workerToken = login(workerEmail);
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

    @BeforeEach
    void setTenantContext() { TenantContext.set(tenantAId); }

    @AfterEach
    void clearTenantContext() { TenantContext.clear(); }

    private String base() { return "http://localhost:" + port; }

    private HttpHeaders authJson(String token) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    private UUID createOpenSession(UUID tenantId) {
        UUID id = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO receipts (id, tenant_id, kind, status, reference, location_id) " +
            "VALUES (?, ?, 'inbound', 'open', 'RSD-REF', ?)",
            id, tenantId, locationId);
        return id;
    }

    private void addLine(UUID sessionId, UUID tenantId, int qty) {
        jdbc.update(
            "INSERT INTO receipt_lines (id, tenant_id, receipt_id, variant_id, quantity) " +
            "VALUES (gen_random_uuid(), ?, ?, ?, ?)",
            tenantId, sessionId, variantId, qty);
    }

    private ResponseEntity<Void> delete(UUID sessionId, String token) {
        return rest.exchange(
            base() + "/api/v1/receiving/sessions/" + sessionId, HttpMethod.DELETE,
            new HttpEntity<>(authJson(token)), Void.class);
    }

    // -----------------------------------------------------------------------

    @Test
    void deleteSession_openEmpty_owner_succeeds() {
        UUID sessionId = createOpenSession(tenantAId);

        ResponseEntity<Void> resp = delete(sessionId, ownerToken);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(jdbc.queryForList("SELECT id FROM receipts WHERE id = ?", sessionId)).isEmpty();
    }

    @Test
    void deleteSession_openWithLines_manager_succeedsAndRemovesLinesTooNoOrphans() {
        UUID managerId = UUID.randomUUID();
        String managerEmail = "manager-rsd-" + managerId + "@test.local";
        jdbc.update(
            "INSERT INTO users (id, tenant_id, name, email, password_hash, role, active) " +
            "VALUES (?, ?, 'Manager', ?, ?, 'manager', true)",
            managerId, tenantAId, managerEmail, passwordEncoder.encode("pass123"));
        String managerToken = login(managerEmail);

        UUID sessionId = createOpenSession(tenantAId);
        addLine(sessionId, tenantAId, 3);
        addLine(sessionId, tenantAId, 5);

        ResponseEntity<Void> resp = delete(sessionId, managerToken);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(jdbc.queryForList("SELECT id FROM receipts WHERE id = ?", sessionId))
            .as("session row must be gone").isEmpty();
        assertThat(jdbc.queryForList("SELECT id FROM receipt_lines WHERE receipt_id = ?", sessionId))
            .as("no orphaned receipt_lines rows may remain").isEmpty();
    }

    @Test
    void deleteSession_finalized_rejected_sessionAndPiecesIntact() {
        UUID sessionId = createOpenSession(tenantAId);
        addLine(sessionId, tenantAId, 2);
        String pieceId = UlidGenerator.generate();
        jdbc.update(
            "INSERT INTO pieces (id, tenant_id, variant_id, receipt_id, barcode, short_code, status) " +
            "VALUES (?, ?, ?, ?, ?, 'P' || LPAD((abs(hashtext(?)) % 999999 + 1)::text, 6, '0'), " +
            "        'available'::piece_status)",
            pieceId, tenantAId, variantId, sessionId, "PC-" + pieceId, pieceId);
        jdbc.update("UPDATE receipts SET status = 'finalized', finalized_at = now() WHERE id = ?", sessionId);

        ResponseEntity<Void> resp = delete(sessionId, ownerToken);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(jdbc.queryForList("SELECT id FROM receipts WHERE id = ?", sessionId))
            .as("finalized session must never be deleted").hasSize(1);
        assertThat(jdbc.queryForList("SELECT id FROM pieces WHERE receipt_id = ?", sessionId))
            .as("generated pieces must be untouched").hasSize(1);
    }

    @Test
    void deleteSession_returnsKindSession_rejectedNotFound() {
        UUID returnsSessionId = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO receipts (id, tenant_id, kind, status, reference) " +
            "VALUES (?, ?, 'returns', 'open', 'RSD-RETURNS')",
            returnsSessionId, tenantAId);

        ResponseEntity<Void> resp = delete(returnsSessionId, ownerToken);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(jdbc.queryForList("SELECT id FROM receipts WHERE id = ?", returnsSessionId))
            .as("returns session must survive a call to the receiving delete endpoint")
            .hasSize(1);
    }

    @Test
    void deleteSession_crossTenant_rejectedNotFound() {
        UUID sessionId = createOpenSession(tenantAId);

        ResponseEntity<Void> resp = delete(sessionId, otherTenantOwnerToken);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(jdbc.queryForList("SELECT id FROM receipts WHERE id = ?", sessionId))
            .as("tenant B must not be able to delete tenant A's session").hasSize(1);
    }

    @Test
    void deleteSession_worker_forbidden() {
        UUID sessionId = createOpenSession(tenantAId);

        ResponseEntity<Void> resp = delete(sessionId, workerToken);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(jdbc.queryForList("SELECT id FROM receipts WHERE id = ?", sessionId)).hasSize(1);
    }

    @Test
    void deleteSession_unknownId_notFound() {
        ResponseEntity<Void> resp = delete(UUID.randomUUID(), ownerToken);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
