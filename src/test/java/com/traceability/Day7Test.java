package com.traceability;

import com.traceability.identity.JwtService;
import com.traceability.identity.model.SignupRequest;
import com.traceability.identity.model.TokenResponse;
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
import org.jobrunr.scheduling.JobScheduler;

import java.math.BigDecimal;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Day 7: read-only Orders list, Order detail, and Catalog endpoints.
 *
 * Tests cover:
 *  (a) Orders list is RLS-scoped (tenant B sees 0 orders from tenant A)
 *  (b) Orders list pagination: correct items and total
 *  (c) Orders list filter by status
 *  (d) Orders list text search (by customer name)
 *  (e) Order detail 404 for wrong tenant (RLS hides it)
 *  (f) Order detail returns items with allocated pieces
 *  (g) Catalog lists products + variants with piece counts
 *  (h) WORKER role → 403 on orders list
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Day7Test {

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
    @MockBean  JobScheduler jobScheduler;

    // Tenant A (main test tenant)
    private String ownerToken;
    private UUID   tenantAId;
    private UUID   storeAId;

    // Tenant B (isolation test)
    private String tokenB;
    private UUID   tenantBId;

    @BeforeAll
    void setup() {
        // Tenant A
        ResponseEntity<TokenResponse> ra = rest.postForEntity(
                base() + "/api/v1/auth/signup",
                new SignupRequest("TenantA", "owner_a", "ownera@day7.test", "password99", true),
                TokenResponse.class);
        assertThat(ra.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        ownerToken = ra.getBody().accessToken();
        tenantAId  = UUID.fromString((String) jwtService.verify(ownerToken).getClaim("tenant"));

        // Tenant B
        ResponseEntity<TokenResponse> rb = rest.postForEntity(
                base() + "/api/v1/auth/signup",
                new SignupRequest("TenantB", "owner_b", "ownerb@day7.test", "password99", true),
                TokenResponse.class);
        assertThat(rb.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        tokenB    = rb.getBody().accessToken();
        tenantBId = UUID.fromString((String) jwtService.verify(tokenB).getClaim("tenant"));

        // Seed a store for tenant A
        storeAId = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO stores (id, tenant_id, platform, shop_domain, status) VALUES (?, ?, 'shopify', 'a.myshopify.com', 'connected')",
            storeAId, tenantAId);
    }

    @BeforeEach
    void cleanOrders() {
        // Delete in FK order; postgres BYPASSRLS so no GUC needed for cleanup
        jdbc.update("DELETE FROM allocations WHERE tenant_id = ? OR tenant_id = ?", tenantAId, tenantBId);
        jdbc.update("DELETE FROM shipments WHERE tenant_id = ? OR tenant_id = ?", tenantAId, tenantBId);
        jdbc.update("DELETE FROM order_items WHERE tenant_id = ? OR tenant_id = ?", tenantAId, tenantBId);
        jdbc.update("DELETE FROM orders WHERE tenant_id = ? OR tenant_id = ?", tenantAId, tenantBId);
        jdbc.update("DELETE FROM pieces WHERE tenant_id = ? OR tenant_id = ?", tenantAId, tenantBId);
        jdbc.update("DELETE FROM variants WHERE tenant_id = ? OR tenant_id = ?", tenantAId, tenantBId);
        jdbc.update("DELETE FROM products WHERE tenant_id = ? OR tenant_id = ?", tenantAId, tenantBId);
    }

    // ── (a) RLS scoping ───────────────────────────────────────────────────────

    @Test
    void ordersList_rlsScoped_tenantBSeesZeroOrdersFromTenantA() {
        insertOrder("A-ORD-1", "Alice", "new");

        // Tenant A sees 1
        assertThat(getOrdersTotal(ownerToken)).isEqualTo(1);
        // Tenant B sees 0 — RLS blocks it
        assertThat(getOrdersTotal(tokenB)).isEqualTo(0);
    }

    // ── (b) Pagination ────────────────────────────────────────────────────────

    @Test
    void ordersList_pagination_correctTotalAndItems() {
        insertOrder("ORD-1", "Alice", "new");
        insertOrder("ORD-2", "Bob",   "new");
        insertOrder("ORD-3", "Carol", "confirmed");

        // First page of size 2
        Map<?, ?> body = getOrders(ownerToken, "?page=0&size=2");
        assertThat(body.get("total")).isEqualTo(3);
        assertThat((List<?>) body.get("items")).hasSize(2);

        // Second page
        Map<?, ?> page2 = getOrders(ownerToken, "?page=1&size=2");
        assertThat((List<?>) page2.get("items")).hasSize(1);
    }

    // ── (c) Status filter ─────────────────────────────────────────────────────

    @Test
    void ordersList_filterByStatus_returnsOnlyMatchingOrders() {
        insertOrder("ORD-F1", "Dan",  "new");
        insertOrder("ORD-F2", "Eve",  "confirmed");
        insertOrder("ORD-F3", "Fred", "confirmed");

        Map<?, ?> body = getOrders(ownerToken, "?status=confirmed");
        assertThat(body.get("total")).isEqualTo(2);
        List<?> items = (List<?>) body.get("items");
        items.forEach(i -> assertThat(((Map<?, ?>) i).get("status")).isEqualTo("confirmed"));
    }

    // ── (d) Text search ───────────────────────────────────────────────────────

    @Test
    void ordersList_searchByCustomerName_returnsMatchingOrders() {
        insertOrder("SRCH-1", "Zara Smith",  "new");
        insertOrder("SRCH-2", "John Doe",    "new");
        insertOrder("SRCH-3", "Zara Johnson","new");

        Map<?, ?> body = getOrders(ownerToken, "?q=Zara");
        assertThat(body.get("total")).isEqualTo(2);
    }

    // ── (e) Order detail cross-tenant 404 ────────────────────────────────────

    @Test
    void orderDetail_crossTenantRequest_returns404() {
        UUID ordId = insertOrder("CROSS-1", "Victim", "new");

        // Tenant B tries to fetch tenant A's order — RLS hides it → 404
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(tokenB);
        ResponseEntity<String> resp = rest.exchange(
            base() + "/api/v1/orders/" + ordId,
            HttpMethod.GET, new HttpEntity<>(headers), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ── (f) Order detail with items + allocated pieces ────────────────────────

    @Test
    void orderDetail_returnsItemsWithAllocatedPieces() {
        // Create product + variant
        UUID productId = insertProduct("Gadget");
        UUID variantId = insertVariant(productId, "Blue", "GAD-BLU");

        // Create order + item
        UUID orderId = insertOrder("DETAIL-1", "Greg", "picking");
        UUID itemId  = insertOrderItem(orderId, variantId, 1);

        // Create piece + allocation
        String pieceId = "01TEST" + UUID.randomUUID().toString().replace("-","").substring(0,14);
        jdbc.update(
            "INSERT INTO pieces (id, tenant_id, variant_id, barcode, short_code, status) VALUES (?, ?, ?, ?, 'P' || LPAD((abs(hashtext(?)) % 999999 + 1)::text, 6, '0'), 'reserved'::piece_status)",
            pieceId, tenantAId, variantId, "PC-" + pieceId, pieceId);
        UUID allocId = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO allocations (id, tenant_id, order_item_id, piece_id, status) VALUES (?, ?, ?, ?, 'active'::allocation_status)",
            allocId, tenantAId, itemId, pieceId);

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(ownerToken);
        ResponseEntity<Map> resp = rest.exchange(
            base() + "/api/v1/orders/" + orderId,
            HttpMethod.GET, new HttpEntity<>(headers), Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> body = resp.getBody();
        assertThat(body.get("number")).isEqualTo("DETAIL-1");
        assertThat(body.get("status")).isEqualTo("picking");

        List<?> items = (List<?>) body.get("items");
        assertThat(items).hasSize(1);

        Map<?, ?> item = (Map<?, ?>) items.get(0);
        assertThat(item.get("sku")).isEqualTo("GAD-BLU");
        assertThat(item.get("quantity")).isEqualTo(1);

        List<?> pieces = (List<?>) item.get("allocatedPieces");
        assertThat(pieces).hasSize(1);
        assertThat(((Map<?, ?>) pieces.get(0)).get("barcode")).isEqualTo("PC-" + pieceId);
    }

    // ── (g) Catalog with piece counts ────────────────────────────────────────

    @Test
    void catalog_returnsProductsWithVariantPieceCounts() {
        UUID productId  = insertProduct("Widget");
        UUID variantId  = insertVariant(productId, "Red", "WID-RED");

        // Insert 3 available + 1 packed piece
        insertPiece(variantId, "available");
        insertPiece(variantId, "available");
        insertPiece(variantId, "available");
        insertPiece(variantId, "packed");

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(ownerToken);
        ResponseEntity<Map> resp = rest.exchange(
            base() + "/api/v1/catalog",
            HttpMethod.GET, new HttpEntity<>(headers), Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<?> products = (List<?>) resp.getBody().get("products");
        assertThat(products).hasSizeGreaterThanOrEqualTo(1);

        Map<?, ?> product = (Map<?, ?>) products.stream()
            .filter(p -> "Widget".equals(((Map<?, ?>) p).get("title")))
            .findFirst().orElseThrow();
        List<?> variants = (List<?>) product.get("variants");
        assertThat(variants).hasSize(1);

        Map<?, ?> counts = (Map<?, ?>) ((Map<?, ?>) variants.get(0)).get("pieceCounts");
        assertThat(counts.get("available")).isEqualTo(3);
        assertThat(counts.get("packed")).isEqualTo(1);
        assertThat(counts.get("total")).isEqualTo(4);
    }

    // ── (h) WORKER role → 403 ────────────────────────────────────────────────

    @Test
    void ordersList_workerRole_returns403() {
        UUID workerId = UUID.randomUUID();
        String workerEmail = "worker-" + workerId + "@day7.test";
        jdbc.update(
            "INSERT INTO users (id, tenant_id, name, email, password_hash, role, active) VALUES (?, ?, 'Worker', ?, ?, 'worker', true)",
            workerId, tenantAId, workerEmail, passwordEncoder.encode("workerpass"));

        HttpHeaders loginH = new HttpHeaders();
        loginH.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<TokenResponse> login = rest.postForEntity(
            base() + "/api/v1/auth/login",
            new HttpEntity<>(Map.of("email", workerEmail, "password", "workerpass"), loginH),
            TokenResponse.class);
        assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);
        String workerToken = login.getBody().accessToken();

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(workerToken);
        ResponseEntity<String> resp = rest.exchange(
            base() + "/api/v1/orders", HttpMethod.GET,
            new HttpEntity<>(headers), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private String base() { return "http://localhost:" + port; }

    private UUID insertOrder(String number, String customerName, String status) {
        UUID id = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO orders (id, tenant_id, store_id, external_id, number, customer_name, status) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?::order_status)",
            id, tenantAId, storeAId, "ext-" + number, number, customerName, status);
        return id;
    }

    private UUID insertProduct(String title) {
        UUID id = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO products (id, tenant_id, store_id, external_id, title) VALUES (?, ?, ?, ?, ?)",
            id, tenantAId, storeAId, "ext-" + title + UUID.randomUUID(), title);
        return id;
    }

    private UUID insertVariant(UUID productId, String title, String sku) {
        UUID id = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO variants (id, tenant_id, product_id, external_id, title, sku, price) VALUES (?, ?, ?, ?, ?, ?, ?)",
            id, tenantAId, productId, "ext-var-" + sku, title, sku, new BigDecimal("9.99"));
        return id;
    }

    private UUID insertOrderItem(UUID orderId, UUID variantId, int qty) {
        UUID id = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO order_items (id, tenant_id, order_id, variant_id, quantity) VALUES (?, ?, ?, ?, ?)",
            id, tenantAId, orderId, variantId, qty);
        return id;
    }

    private void insertPiece(UUID variantId, String status) {
        String id = "PC" + UUID.randomUUID().toString().replace("-", "").substring(0, 18);
        jdbc.update(
            "INSERT INTO pieces (id, tenant_id, variant_id, barcode, short_code, status) VALUES (?, ?, ?, ?, 'P' || LPAD((abs(hashtext(?)) % 999999 + 1)::text, 6, '0'), ?::piece_status)",
            id, tenantAId, variantId, "PC-" + id, id, status);
    }

    private long getOrdersTotal(String token) {
        Map<?, ?> body = getOrders(token, "");
        Object total = body.get("total");
        return total instanceof Number n ? n.longValue() : 0L;
    }

    @SuppressWarnings("unchecked")
    private Map<?, ?> getOrders(String token, String query) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        ResponseEntity<Map> resp = rest.exchange(
            base() + "/api/v1/orders" + query,
            HttpMethod.GET, new HttpEntity<>(headers), Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        return resp.getBody();
    }
}
