package com.traceability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.traceability.identity.JwtService;
import com.traceability.identity.model.SignupRequest;
import com.traceability.identity.model.TokenResponse;
import com.traceability.integrations.shopify.ShopifyGateway;
import com.traceability.integrations.shopify.ShopifyGateway.*;
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

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

/**
 * Day 4 integration tests for Shopify connect + import.
 *
 * ShopifyGateway is @MockBean — no real Shopify API calls.
 * Tests run against a real Testcontainers Postgres with all migrations applied.
 * JdbcTemplate connects as postgres (BYPASSRLS) for assertion queries.
 *
 * @TestInstance(PER_CLASS) + static initializer: same pattern as InventoryLedgerTest.
 * Spring's postProcessTestInstance fires before TestcontainersExtension.beforeAll,
 * so the container must be started before the Spring context initializes.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ShopifyImportTest {

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
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtService jwtService;
    @Autowired PasswordEncoder passwordEncoder;
    @MockBean  ShopifyGateway shopifyGateway;

    private String ownerToken;
    private UUID   ownerTenantId;

    private static final String RAW_TOKEN = "shpat_test_token_abc";

    private static final ShopifyGateway.Product PRODUCT_1 = new ShopifyGateway.Product(
            "gid://shopify/Product/111",
            "Widget",
            "active",
            List.of(
                    new ShopifyGateway.Variant("gid://shopify/ProductVariant/10", "WID-RED", "Red", new BigDecimal("9.99")),
                    new ShopifyGateway.Variant("gid://shopify/ProductVariant/11", "WID-BLU", "Blue", new BigDecimal("11.99"))
            ));

    @BeforeAll
    void setupOwner() {
        SignupRequest req = new SignupRequest("Import Co", "imp_user", "import@test.com", "password99");
        ResponseEntity<TokenResponse> resp = rest.postForEntity(
                base() + "/api/v1/auth/signup", req, TokenResponse.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        ownerToken = resp.getBody().accessToken();
        ownerTenantId = UUID.fromString(
                (String) jwtService.verify(ownerToken).getClaim("tenant"));
    }

    @BeforeEach
    void cleanUp() {
        // postgres/BYPASSRLS — deletes all rows across tenants for a clean slate
        jdbc.execute("DELETE FROM order_items");
        jdbc.execute("DELETE FROM orders");
        jdbc.execute("DELETE FROM variants");
        jdbc.execute("DELETE FROM products");
        jdbc.execute("DELETE FROM stores");
    }

    // -------------------------------------------------------------------------
    // (a) Idempotency: two imports produce exactly one row per entity
    // -------------------------------------------------------------------------
    @Test
    void importIdempotency_twoRunsProduceOneRowEach() {
        stubGateway("import-shop.myshopify.com", PRODUCT_1,
                List.of(
                        order("gid://shopify/Order/100", "#1001",
                                List.of(line("gid://shopify/LineItem/1", "gid://shopify/ProductVariant/10"))),
                        order("gid://shopify/Order/101", "#1002",
                                List.of(line("gid://shopify/LineItem/2", "gid://shopify/ProductVariant/11")))
                ));

        connect("import-shop.myshopify.com");
        connect("import-shop.myshopify.com");

        assertThat(countInTenant("products")).isEqualTo(1);
        assertThat(countInTenant("variants")).isEqualTo(2);
        assertThat(countInTenant("orders")).isEqualTo(2);
        assertThat(countInTenant("order_items")).isEqualTo(2);
    }

    // -------------------------------------------------------------------------
    // (b) Unmapped variant: order flagged, matched lines still imported
    // -------------------------------------------------------------------------
    @Test
    void unmappedVariant_flagsOrderButImportsMappedLines() {
        stubGateway("mapped-shop.myshopify.com", PRODUCT_1,
                List.of(order("gid://shopify/Order/200", "#2001", List.of(
                        line("gid://shopify/LineItem/10", "gid://shopify/ProductVariant/10"),    // exists
                        line("gid://shopify/LineItem/11", "gid://shopify/ProductVariant/999")    // missing
                ))));

        connect("mapped-shop.myshopify.com");

        assertThat(countInTenant("orders")).isEqualTo(1);
        assertThat(countInTenant("order_items")).isEqualTo(1);

        Boolean onHold = jdbc.queryForObject(
                "SELECT o.on_hold FROM orders o JOIN stores s ON o.store_id = s.id " +
                "WHERE s.shop_domain = 'mapped-shop.myshopify.com'", Boolean.class);
        assertThat(onHold).isTrue();

        String holdReason = jdbc.queryForObject(
                "SELECT o.hold_reason FROM orders o JOIN stores s ON o.store_id = s.id " +
                "WHERE s.shop_domain = 'mapped-shop.myshopify.com'", String.class);
        assertThat(holdReason).isEqualTo("unmapped_variant_on_import");
    }

    // -------------------------------------------------------------------------
    // (c) Encrypted token: stored value is NOT the raw token
    // -------------------------------------------------------------------------
    @Test
    void encryptedToken_storedValueIsNotPlaintext() {
        stubGateway("enc-shop.myshopify.com", PRODUCT_1, List.of());

        connect("enc-shop.myshopify.com");

        String stored = jdbc.queryForObject(
                "SELECT access_token_encrypted FROM stores WHERE shop_domain = 'enc-shop.myshopify.com'",
                String.class);
        assertThat(stored).isNotNull().isNotEqualTo(RAW_TOKEN);
        assertThat(stored.length()).isGreaterThan(RAW_TOKEN.length());
    }

    // -------------------------------------------------------------------------
    // (d) Non-owner role → 403
    // -------------------------------------------------------------------------
    @Test
    void nonOwnerRole_connectReturns403() throws Exception {
        // Insert a manager user directly (postgres/BYPASSRLS; RLS not blocking DDL-level insert)
        UUID managerId = UUID.randomUUID();
        String managerEmail = "manager-" + managerId + "@test.com";
        String hash = passwordEncoder.encode("managerpass");
        jdbc.update(
                "INSERT INTO users (id, tenant_id, name, email, password_hash, role, active) " +
                "VALUES (?, ?, 'Mgr', ?, ?, 'manager', true)",
                managerId, ownerTenantId, managerEmail, hash);

        // Login as the manager to get a real token
        HttpHeaders loginHeaders = new HttpHeaders();
        loginHeaders.setContentType(MediaType.APPLICATION_JSON);
        Map<String, String> loginBody = Map.of("email", managerEmail, "password", "managerpass");
        ResponseEntity<TokenResponse> loginResp = rest.postForEntity(
                base() + "/api/v1/auth/login",
                new HttpEntity<>(loginBody, loginHeaders),
                TokenResponse.class);
        assertThat(loginResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        String managerToken = loginResp.getBody().accessToken();

        // Attempt to connect a store as manager → must be 403
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(managerToken);
        headers.setContentType(MediaType.APPLICATION_JSON);
        Map<String, String> body = Map.of("shopDomain", "any-shop.myshopify.com", "adminToken", RAW_TOKEN);
        ResponseEntity<String> resp = rest.exchange(
                base() + "/api/v1/shopify/connect",
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ---- helpers ------------------------------------------------------------

    private String base() { return "http://localhost:" + port; }

    @SuppressWarnings("unchecked")
    private void connect(String shopDomain) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(ownerToken);
        headers.setContentType(MediaType.APPLICATION_JSON);
        Map<String, String> body = Map.of("shopDomain", shopDomain, "adminToken", RAW_TOKEN);
        ResponseEntity<Map> resp = rest.exchange(
                base() + "/api/v1/shopify/connect",
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                Map.class);
        assertThat(resp.getStatusCode().is2xxSuccessful())
                .as("connect should succeed (status=%s body=%s)", resp.getStatusCode(), resp.getBody())
                .isTrue();
    }

    private int countInTenant(String table) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE tenant_id = ?", Integer.class, ownerTenantId);
    }

    private void stubGateway(String shopDomain, ShopifyGateway.Product product, List<ShopifyGateway.Order> orders) {
        when(shopifyGateway.validateShop(eq(shopDomain), eq(RAW_TOKEN)))
                .thenReturn("Test Shop");
        when(shopifyGateway.fetchProductsPage(eq(shopDomain), eq(RAW_TOKEN), isNull()))
                .thenReturn(new ShopifyGateway.ProductPage(List.of(product), false, null));
        when(shopifyGateway.fetchOrdersPage(eq(shopDomain), eq(RAW_TOKEN), isNull(), anyString()))
                .thenReturn(new ShopifyGateway.OrderPage(orders, false, null));
    }

    private static ShopifyGateway.Order order(String gid, String name, List<ShopifyGateway.LineItem> lines) {
        ObjectNode addr = new ObjectMapper().createObjectNode()
                .put("address1", "123 Test St").put("city", "Cairo");
        return new ShopifyGateway.Order(
                gid, name, "Test Customer", "+201001234567",
                addr, "pending", List.of(),
                new BigDecimal("100.00"), lines,
                Instant.now(), new ObjectMapper().createObjectNode());
    }

    private static ShopifyGateway.LineItem line(String gid, String variantGid) {
        return new ShopifyGateway.LineItem(gid, 1, variantGid);
    }
}
