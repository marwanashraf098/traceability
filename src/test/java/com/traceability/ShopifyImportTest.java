package com.traceability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.traceability.identity.JwtService;
import com.traceability.identity.model.SignupRequest;
import com.traceability.identity.model.TokenResponse;
import com.traceability.integrations.shopify.ShopifyGateway;
import com.traceability.integrations.shopify.ShopifyImportJob;
import com.traceability.integrations.shopify.ShopifyLocationGateway;
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
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Day 5 integration tests for Shopify connect + background import job.
 *
 * ShopifyGateway and JobScheduler are @MockBean — no real Shopify calls,
 * no real job scheduling. Import jobs are invoked directly as Java methods.
 *
 * @TestInstance(PER_CLASS) + static initializer: same pattern as InventoryLedgerTest.
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
    @Autowired ShopifyImportJob importJob;
    @MockBean  ShopifyGateway shopifyGateway;
    @MockBean  JobScheduler   jobScheduler;   // prevents real job scheduling
    @MockBean  ShopifyLocationGateway shopifyLocations;

    private String ownerToken;
    private UUID   ownerTenantId;

    private static final String RAW_TOKEN = "shpat_test_token_abc";

    private static final ShopifyGateway.Product PRODUCT_1 = new ShopifyGateway.Product(
            "gid://shopify/Product/111",
            "Widget",
            "active",
            "https://cdn.shopify.com/s/files/1/0000/0001/products/widget.jpg",
            List.of(
                    new ShopifyGateway.Variant("gid://shopify/ProductVariant/10", "WID-RED", "Red",  new BigDecimal("9.99")),
                    new ShopifyGateway.Variant("gid://shopify/ProductVariant/11", "WID-BLU", "Blue", new BigDecimal("11.99"))
            ));

    @BeforeAll
    void setupOwner() {
        SignupRequest req = new SignupRequest("Import Co", "imp_user", "import@test.com", "password99", true);
        ResponseEntity<TokenResponse> resp = rest.postForEntity(
                base() + "/api/v1/auth/signup", req, TokenResponse.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        ownerToken    = resp.getBody().accessToken();
        ownerTenantId = UUID.fromString(
                (String) jwtService.verify(ownerToken).getClaim("tenant"));
    }

    @BeforeEach
    void cleanUp() {
        jdbc.execute("DELETE FROM shopify_inventory_adjustments");
        jdbc.execute("DELETE FROM piece_events");
        jdbc.execute("DELETE FROM allocations");
        jdbc.execute("DELETE FROM pieces");
        jdbc.execute("DELETE FROM order_items");
        jdbc.execute("DELETE FROM orders");
        jdbc.execute("DELETE FROM variants");
        jdbc.execute("DELETE FROM products");
        jdbc.execute("DELETE FROM stores");
    }

    // -------------------------------------------------------------------------
    // (a) Idempotency: two import job runs produce exactly one row per entity
    // -------------------------------------------------------------------------
    @Test
    void importIdempotency_twoJobRunsProduceOneRowEach() {
        stubGateway("import-shop.myshopify.com", PRODUCT_1,
                List.of(
                        order("gid://shopify/Order/100", "#1001",
                                List.of(line("gid://shopify/LineItem/1", "gid://shopify/ProductVariant/10"))),
                        order("gid://shopify/Order/101", "#1002",
                                List.of(line("gid://shopify/LineItem/2", "gid://shopify/ProductVariant/11")))
                ));

        UUID storeId = connect("import-shop.myshopify.com");
        importJob.run(storeId, ownerTenantId);   // first run
        importJob.run(storeId, ownerTenantId);   // second run — must be idempotent

        assertThat(countInTenant("products")).isEqualTo(1);
        assertThat(countInTenant("variants")).isEqualTo(2);
        assertThat(countInTenant("orders")).isEqualTo(2);
        assertThat(countInTenant("order_items")).isEqualTo(2);
    }

    // -------------------------------------------------------------------------
    // (a2) FR-17 v2 go-live is now automatic on connect (Parts A+B+C, no manual
    //      approval gate). Confirm end-to-end across TWO full job runs:
    //        - exactly one Shopify location ever created (Part A claim-before-call)
    //        - the on_hand seed writes exactly once, not twice, once a variant has
    //          real stock (Part C's live-recompute skip-nonzero guard)
    //        - every write (activate, seed) targets ONLY the Traced GID, never any
    //          other locationId
    // -------------------------------------------------------------------------
    @Test
    void connectFlowIdempotency_oneLocationEverCreated_onHandSeededOnceNotTwice() throws Exception {
        String shopDomain = "golive-shop.myshopify.com";
        String tracedGid  = "gid://shopify/Location/golive";

        stubGateway(shopDomain, PRODUCT_1, List.of());

        when(shopifyLocations.findByName(eq(shopDomain), eq(RAW_TOKEN), anyString()))
                .thenReturn(Optional.empty());
        when(shopifyLocations.create(eq(shopDomain), eq(RAW_TOKEN), any()))
                .thenReturn(new ShopifyLocationGateway.LocationResult(tracedGid, "Traced Main Warehouse"));
        when(shopifyGateway.resolveInventoryItemId(eq(shopDomain), eq(RAW_TOKEN), eq("gid://shopify/ProductVariant/10")))
                .thenReturn("gid://shopify/InventoryItem/red");
        when(shopifyGateway.resolveInventoryItemId(eq(shopDomain), eq(RAW_TOKEN), eq("gid://shopify/ProductVariant/11")))
                .thenReturn("gid://shopify/InventoryItem/blue");
        // apply() runs on EVERY importJob.run() call, including run 1 (before any pieces
        // exist) — so the FIRST invocation of fetchAvailableQuantities happens during run 1
        // (everything on_hand=0, reads as empty regardless). The SECOND invocation is run 2
        // (after pieces are inserted, still empty on Shopify's side -> triggers the seed).
        // The THIRD invocation (run 3) must see the now-seeded value -> skip, never double-add.
        when(shopifyGateway.fetchAvailableQuantities(eq(shopDomain), eq(RAW_TOKEN), eq(tracedGid), anyList()))
                .thenReturn(List.of())
                .thenReturn(List.of())
                .thenReturn(List.of(new ShopifyGateway.InventoryLevel("gid://shopify/InventoryItem/red", 3)));

        UUID storeId = connect(shopDomain);

        // Run 1: imports the catalog (variants don't exist before this), provisions +
        // links the Traced location, activates the (still-zero-stock) catalog. No pieces
        // exist yet, so Part C computes on_hand=0 for both variants -> ACTION_NOOP, nothing
        // seeded — there is nothing to double-seed FROM yet.
        importJob.run(storeId, ownerTenantId);

        UUID variantRedId = jdbc.queryForObject(
                "SELECT id FROM variants WHERE tenant_id = ? AND external_id = ?",
                UUID.class, ownerTenantId, "gid://shopify/ProductVariant/10");
        UUID fulfillmentLocationId = jdbc.queryForObject(
                "SELECT id FROM locations WHERE tenant_id = ? AND is_fulfillment = true",
                UUID.class, ownerTenantId);

        // Now give the Red variant real stock at the fulfillment location.
        for (int i = 0; i < 3; i++) {
            String pieceId = UlidGenerator.generate();
            jdbc.update(
                    "INSERT INTO pieces (id, tenant_id, variant_id, status, barcode, short_code, current_location_id) " +
                    "VALUES (?, ?, ?, 'available'::piece_status, ?, " +
                    "    'P' || LPAD((abs(hashtext(?)) % 999999 + 1)::text, 6, '0'), ?)",
                    pieceId, ownerTenantId, variantRedId, "PC-" + pieceId, pieceId, fulfillmentLocationId);
        }

        // Run 2 ("reconnect"): Part A/B no-op (already linked/active); Part C now sees
        // on_hand=3 for Red, Shopify shows 0 (first fetchAvailableQuantities answer) -> seeds +3.
        importJob.run(storeId, ownerTenantId);

        // Run 3 ("reconnect again"): Part C recomputes live and sees Shopify's SECOND stubbed
        // answer (already 3) -> must skip, never double-add to 6.
        importJob.run(storeId, ownerTenantId);

        verify(shopifyLocations, times(1)).create(any(), any(), any());
        verify(shopifyGateway, times(1)).adjustInventoryQuantities(
                eq(shopDomain), eq(RAW_TOKEN), eq("gid://shopify/InventoryItem/red"), eq(tracedGid), eq(3), anyString(), any());
        // The blue variant never had stock -> never seeded, in any run.
        verify(shopifyGateway, never()).adjustInventoryQuantities(
                any(), any(), eq("gid://shopify/InventoryItem/blue"), any(), anyInt(), any(), any());
        // No write of any kind (activate, adjust) ever targets a locationId other than the Traced GID.
        verify(shopifyGateway, never()).activateInventoryItem(any(), any(), any(), argThat(loc -> !tracedGid.equals(loc)), any());
        verify(shopifyGateway, never()).adjustInventoryQuantities(any(), any(), any(), argThat(loc -> !tracedGid.equals(loc)), anyInt(), any(), any());

        String locationStatus = jdbc.queryForObject(
                "SELECT shopify_sync_status FROM locations WHERE id = ?", String.class, fulfillmentLocationId);
        assertThat(locationStatus).isEqualTo("linked");
    }

    // -------------------------------------------------------------------------
    // (b) Unmapped variant: order flagged, matched lines still imported
    // -------------------------------------------------------------------------
    @Test
    void unmappedVariant_flagsOrderButImportsMappedLines() {
        stubGateway("mapped-shop.myshopify.com", PRODUCT_1,
                List.of(order("gid://shopify/Order/200", "#2001", List.of(
                        line("gid://shopify/LineItem/10", "gid://shopify/ProductVariant/10"),
                        line("gid://shopify/LineItem/11", "gid://shopify/ProductVariant/999")
                ))));

        UUID storeId = connect("mapped-shop.myshopify.com");
        importJob.run(storeId, ownerTenantId);

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
    void nonOwnerRole_connectReturns403() {
        UUID managerId    = UUID.randomUUID();
        String managerEmail = "manager-" + managerId + "@test.com";
        String hash = passwordEncoder.encode("managerpass");
        jdbc.update(
                "INSERT INTO users (id, tenant_id, name, email, password_hash, role, active) " +
                "VALUES (?, ?, 'Mgr', ?, ?, 'manager', true)",
                managerId, ownerTenantId, managerEmail, hash);

        HttpHeaders loginHeaders = new HttpHeaders();
        loginHeaders.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<TokenResponse> loginResp = rest.postForEntity(
                base() + "/api/v1/auth/login",
                new HttpEntity<>(Map.of("email", managerEmail, "password", "managerpass"), loginHeaders),
                TokenResponse.class);
        assertThat(loginResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        String managerToken = loginResp.getBody().accessToken();

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(managerToken);
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> resp = rest.exchange(
                base() + "/api/v1/shopify/connect",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("shopDomain", "any.myshopify.com", "adminToken", RAW_TOKEN), headers),
                String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // -------------------------------------------------------------------------
    // (e) Job failure: import_status set to 'failed' with error summary
    // -------------------------------------------------------------------------
    @Test
    void jobFailure_setsImportStatusFailed() {
        when(shopifyGateway.validateShop(eq("fail-shop.myshopify.com"), eq(RAW_TOKEN)))
            .thenReturn("Fail Shop");
        when(shopifyGateway.fetchProductsPage(eq("fail-shop.myshopify.com"), eq(RAW_TOKEN), isNull()))
            .thenThrow(new com.traceability.integrations.shopify.ShopifyException("API down"));

        UUID storeId = connect("fail-shop.myshopify.com");
        importJob.run(storeId, ownerTenantId);  // catches exception internally

        String status = jdbc.queryForObject(
            "SELECT import_status FROM stores WHERE id = ?", String.class, storeId);
        assertThat(status).isEqualTo("failed");

        String summary = jdbc.queryForObject(
            "SELECT import_summary FROM stores WHERE id = ?", String.class, storeId);
        assertThat(summary).contains("error");
    }

    // -------------------------------------------------------------------------
    // (f) GET status endpoint returns correct import_status after job completes
    // -------------------------------------------------------------------------
    @Test
    void statusEndpoint_returnsCompletedAfterJobRun() {
        stubGateway("status-shop.myshopify.com", PRODUCT_1, List.of());

        UUID storeId = connect("status-shop.myshopify.com");

        // Before job: status = pending
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(ownerToken);
        ResponseEntity<Map> before = rest.exchange(
            base() + "/api/v1/shopify/stores/" + storeId + "/status",
            HttpMethod.GET, new HttpEntity<>(headers), Map.class);
        assertThat(before.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(before.getBody().get("importStatus")).isEqualTo("pending");

        // Run the job
        importJob.run(storeId, ownerTenantId);

        // After job: status = completed
        ResponseEntity<Map> after = rest.exchange(
            base() + "/api/v1/shopify/stores/" + storeId + "/status",
            HttpMethod.GET, new HttpEntity<>(headers), Map.class);
        assertThat(after.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(after.getBody().get("importStatus")).isEqualTo("completed");
    }

    // ---- helpers ------------------------------------------------------------

    private String base() { return "http://localhost:" + port; }

    @SuppressWarnings("unchecked")
    private UUID connect(String shopDomain) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(ownerToken);
        headers.setContentType(MediaType.APPLICATION_JSON);
        Map<String, String> body = Map.of("shopDomain", shopDomain, "adminToken", RAW_TOKEN);
        ResponseEntity<Map> resp = rest.exchange(
                base() + "/api/v1/shopify/connect",
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                Map.class);
        assertThat(resp.getStatusCode())
                .as("connect should return 202 (status=%s body=%s)", resp.getStatusCode(), resp.getBody())
                .isEqualTo(HttpStatus.ACCEPTED);
        return UUID.fromString((String) resp.getBody().get("storeId"));
    }

    private int countInTenant(String table) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE tenant_id = ?", Integer.class, ownerTenantId);
    }

    private void stubGateway(String shopDomain, ShopifyGateway.Product product,
                              List<ShopifyGateway.Order> orders) {
        when(shopifyGateway.validateShop(eq(shopDomain), eq(RAW_TOKEN))).thenReturn("Test Shop");
        when(shopifyGateway.fetchProductsPage(eq(shopDomain), eq(RAW_TOKEN), isNull()))
                .thenReturn(new ShopifyGateway.ProductPage(List.of(product), false, null));
        when(shopifyGateway.fetchOrdersPage(eq(shopDomain), eq(RAW_TOKEN), isNull(), anyString()))
                .thenReturn(new ShopifyGateway.OrderPage(orders, false, null));
    }

    private static ShopifyGateway.Order order(String gid, String name, List<ShopifyGateway.LineItem> lines) {
        ObjectNode addr = new ObjectMapper().createObjectNode()
                .put("address1", "123 Test St").put("city", "Cairo");
        // FR-18: stubGateway() is called before connect(), which sets orders_ingest_from = now().
        // +60s ensures placed_at is always after the cutoff regardless of execution timing.
        return new ShopifyGateway.Order(
                gid, name, "Test Customer", "+201001234567",
                addr, "pending", List.of(),
                new BigDecimal("100.00"), lines,
                Instant.now().plusSeconds(60), new ObjectMapper().createObjectNode());
    }

    private static ShopifyGateway.LineItem line(String gid, String variantGid) {
        return new ShopifyGateway.LineItem(gid, 1, variantGid);
    }
}
