package com.traceability;

import com.traceability.demo.DemoSeeder;
import com.traceability.identity.JwtService;
import com.traceability.integrations.shopify.ShopifyGateway;
import com.traceability.integrations.shopify.ShopifyTokenProvider;
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
 * POST /api/v1/demo/reseed — owner-authed manual reseed trigger.
 *
 * Real HTTP + real Spring Security filter chain (RANDOM_PORT, not MockMvc) so
 * @PreAuthorize("hasRole('OWNER')") is genuinely exercised, not assumed.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DemoAdminControllerTest {

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
        r.add("shopify.api-version",        () -> "2024-10");
        r.add("shopify.client-id",          () -> "test-client-id");
        r.add("shopify.client-secret",      () -> "test-client-secret");
        r.add("shopify.scopes",             () -> "read_products");
        r.add("shopify.webhook-base-url",   () -> "https://test.example.com");
        r.add("bosta.api-base-url",         () -> "https://app.bosta.co");
    }

    @MockBean JobScheduler         jobScheduler;
    @MockBean ShopifyGateway       shopifyGateway;
    @MockBean ShopifyTokenProvider tokenProvider;

    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;
    @Autowired JdbcTemplate     jdbc;
    @Autowired JwtService       jwtService;
    @Autowired DemoSeeder       demoSeeder;

    private String base() { return "http://localhost:" + port; }

    private HttpHeaders authHeaders(UUID userId, UUID tenantId, String role) {
        String token = jwtService.issueAccessToken(userId, tenantId, role);
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return headers;
    }

    private ResponseEntity<Map> postReseed(HttpHeaders headers) {
        return rest.exchange(base() + "/api/v1/demo/reseed", HttpMethod.POST,
                new HttpEntity<>(headers), Map.class);
    }

    // -----------------------------------------------------------------------
    // (a) demo owner calls it -> 200, reseeded:true, demo tenant back to golden counts.
    // -----------------------------------------------------------------------
    @Test
    void demoOwner_reseedsDemoTenant_returnsGoldenCounts() {
        demoSeeder.ensureBootstrapped();
        UUID ownerId = demoSeeder.resolveOwnerId();

        // Corrupt the demo tenant's data first, so a real reseed is observable.
        jdbc.update("DELETE FROM products WHERE tenant_id = ?", DemoSeeder.DEMO_TENANT_ID);
        Long productsBefore = jdbc.queryForObject(
                "SELECT COUNT(*) FROM products WHERE tenant_id = ?", Long.class, DemoSeeder.DEMO_TENANT_ID);
        assertThat(productsBefore).isZero();

        ResponseEntity<Map> resp = postReseed(authHeaders(ownerId, DemoSeeder.DEMO_TENANT_ID, "owner"));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).containsEntry("reseeded", true);
        assertThat(resp.getBody()).containsEntry("tenantId", DemoSeeder.DEMO_TENANT_ID.toString());

        Long productsAfter = jdbc.queryForObject(
                "SELECT COUNT(*) FROM products WHERE tenant_id = ?", Long.class, DemoSeeder.DEMO_TENANT_ID);
        assertThat(productsAfter).as("golden catalog restored").isEqualTo(6L);
        Long piecesAfter = jdbc.queryForObject(
                "SELECT COUNT(*) FROM pieces WHERE tenant_id = ?", Long.class, DemoSeeder.DEMO_TENANT_ID);
        assertThat(piecesAfter).as("golden piece count restored").isEqualTo(108L);
    }

    // -----------------------------------------------------------------------
    // (b) CRITICAL SAFETY: a REAL (non-demo) owner calling this endpoint reseeds
    //     the DEMO tenant, NEVER their own — resolveAndAssertDemoTenant() resolves
    //     purely via is_demo=true, ignoring the caller's own TenantContext/tenant.
    // -----------------------------------------------------------------------
    @Test
    void realOwner_callingReseed_neverTouchesTheirOwnTenant_onlyReseedsDemoTenant() {
        demoSeeder.ensureBootstrapped();

        // A genuine real tenant with its own data.
        UUID realTenantId = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, 'Real Control Co')", realTenantId);
        UUID realOwnerId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO users (id, tenant_id, name, email, password_hash, role) " +
                "VALUES (?, ?, 'Real Owner', 'real-owner@control.invalid', 'x', 'owner')",
                realOwnerId, realTenantId);
        UUID realProductId = UUID.randomUUID();
        UUID realStoreId = UUID.randomUUID();
        jdbc.update("INSERT INTO stores (id, tenant_id, shop_domain, status) VALUES (?, ?, ?, 'connected')",
                realStoreId, realTenantId, "real-control.myshopify.com");
        jdbc.update("INSERT INTO products (id, tenant_id, store_id, external_id, title) VALUES (?,?,?,?,?)",
                realProductId, realTenantId, realStoreId, "real-ext-id", "Real Product — must survive untouched");

        // Call the endpoint AS the real owner.
        ResponseEntity<Map> resp = postReseed(authHeaders(realOwnerId, realTenantId, "owner"));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        // The response names the DEMO tenant, not the caller's own tenant.
        assertThat(resp.getBody()).containsEntry("tenantId", DemoSeeder.DEMO_TENANT_ID.toString());

        // The real tenant's own data is completely untouched.
        Long realProductCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM products WHERE tenant_id = ?", Long.class, realTenantId);
        assertThat(realProductCount).as("real tenant's own data must survive untouched").isEqualTo(1L);
        String stillThere = jdbc.queryForObject(
                "SELECT title FROM products WHERE id = ?", String.class, realProductId);
        assertThat(stillThere).isEqualTo("Real Product — must survive untouched");

        // The DEMO tenant (not the real one) is the one that got reseeded to golden counts.
        Long demoProductCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM products WHERE tenant_id = ?", Long.class, DemoSeeder.DEMO_TENANT_ID);
        assertThat(demoProductCount).isEqualTo(6L);
    }

    // -----------------------------------------------------------------------
    // (c) role gate: a worker token is rejected (403), no reseed happens.
    // -----------------------------------------------------------------------
    @Test
    void workerToken_isRejected_403_noReseed() {
        demoSeeder.ensureBootstrapped();
        UUID workerId = UUID.randomUUID();

        ResponseEntity<Map> resp = postReseed(authHeaders(workerId, DemoSeeder.DEMO_TENANT_ID, "worker"));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // -----------------------------------------------------------------------
    // (d) no token at all: 401.
    // -----------------------------------------------------------------------
    @Test
    void noToken_isRejected_401() {
        ResponseEntity<Map> resp = rest.postForEntity(base() + "/api/v1/demo/reseed", null, Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
