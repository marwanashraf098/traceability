package com.traceability;

import com.traceability.identity.model.AccessTokenResponse;
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
 * FR-24 change (A): ReceivingService.getSession()/requireOpen() must not be able to
 * reach a 'returns'-kind row sharing the same receipts table/id space.
 * deleteSession() was already kind-filtered (see ReceivingSessionDeleteTest);
 * this closes the gap for the read path (getSession) and the write-guard path
 * (requireOpen(), exercised here via addLine — every mutating method routes
 * through it) so neither side of the shared table can cross into the other.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReceivingKindFilterTest {

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
    UUID variantId;
    String ownerToken;

    @BeforeAll
    void setup() {
        tenantId = UUID.randomUUID();
        UUID ownerId   = UUID.randomUUID();
        UUID storeId   = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        variantId = UUID.randomUUID();

        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, 'Kind Filter Test Co')", tenantId);

        String ownerEmail = "owner-rkf-" + ownerId + "@test.local";
        jdbc.update(
            "INSERT INTO users (id, tenant_id, name, email, password_hash, role, active) " +
            "VALUES (?, ?, 'Owner', ?, ?, 'owner', true)",
            ownerId, tenantId, ownerEmail, passwordEncoder.encode("pass123"));

        jdbc.update(
            "INSERT INTO stores (id, tenant_id, platform, shop_domain, status) " +
            "VALUES (?, ?, 'shopify', 'rkf-test.myshopify.com', 'disconnected')",
            storeId, tenantId);
        jdbc.update(
            "INSERT INTO products (id, tenant_id, store_id, external_id, title, status) " +
            "VALUES (?, ?, ?, 'PROD-RKF', 'Kind Filter Widget', 'active')",
            productId, tenantId, storeId);
        jdbc.update(
            "INSERT INTO variants (id, tenant_id, product_id, external_id, title, sku) " +
            "VALUES (?, ?, ?, 'VAR-RKF', 'Kind Filter Variant', 'RKF-001')",
            variantId, tenantId, productId);

        ownerToken = login(ownerEmail);
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

    private HttpHeaders authJson() {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(ownerToken);
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    private UUID createReturnsKindSession() {
        UUID id = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO receipts (id, tenant_id, kind, status, reference) " +
            "VALUES (?, ?, 'returns', 'open', 'RKF-RETURNS')",
            id, tenantId);
        return id;
    }

    // -----------------------------------------------------------------------

    @Test
    void getSession_returnsKindRow_notFound() {
        UUID returnsSessionId = createReturnsKindSession();

        ResponseEntity<String> resp = rest.exchange(
            base() + "/api/v1/receiving/sessions/" + returnsSessionId, HttpMethod.GET,
            new HttpEntity<>(authJson()), String.class);

        assertThat(resp.getStatusCode())
            .as("a 'returns'-kind receipts row must be invisible to ReceivingService.getSession()")
            .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void addLine_returnsKindRow_notFound_noLineInserted() {
        UUID returnsSessionId = createReturnsKindSession();

        ResponseEntity<String> resp = rest.exchange(
            base() + "/api/v1/receiving/sessions/" + returnsSessionId + "/lines", HttpMethod.POST,
            new HttpEntity<>(Map.of("variantId", variantId.toString(), "quantity", 3), authJson()),
            String.class);

        assertThat(resp.getStatusCode())
            .as("requireOpen() must reject a 'returns'-kind session id, not silently add a line to it")
            .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(jdbc.queryForList("SELECT id FROM receipt_lines WHERE receipt_id = ?", returnsSessionId))
            .as("no line may be inserted against a returns-kind session")
            .isEmpty();
    }

    @Test
    void getSession_inboundKindRow_stillWorks() {
        UUID id = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO receipts (id, tenant_id, kind, status, reference) " +
            "VALUES (?, ?, 'inbound', 'open', 'RKF-INBOUND')",
            id, tenantId);

        ResponseEntity<String> resp = rest.exchange(
            base() + "/api/v1/receiving/sessions/" + id, HttpMethod.GET,
            new HttpEntity<>(authJson()), String.class);

        assertThat(resp.getStatusCode())
            .as("kind filter must not regress the normal inbound-session read path")
            .isEqualTo(HttpStatus.OK);
    }
}
