package com.traceability.inventory;

import com.traceability.TestPostgres;
import com.traceability.integrations.shopify.ShopifyGateway;
import com.traceability.integrations.shopify.ShopifyTokenProvider;
import com.traceability.tenancy.TenantContext;
import org.junit.jupiter.api.*;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * The return-restock gate counts only Shopify refund restocks AT THE TRACED MAIN location (V201, approved 2026-11-02).
 * Before, any refund restock of the order + variant blocked Traced's +1 at Main — even one Shopify put back into
 * another location, which left Main one unit short (prod: 3 Nuvira restocks, #3134 / #3140 / #3149).
 *
 *   g1  refund restocked at ANOTHER location → Traced's restock sends +1 at Main (was: skipped_shopify_restocked)
 *   g2  refund restocked AT MAIN → skipped_shopify_restocked, nothing sent (unchanged)
 *   g3  a restock line with no location_id still counts (pre-location payloads — unchanged)
 *   g4  restocked_twice: a later refund restock at another location is not a double count at Main
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RestockGateMainLocationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            TestPostgres.newContainer()
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

    static final String SCOPES = "read_orders,write_inventory,read_products,write_locations,read_locations";

    @Autowired JdbcTemplate jdbc;
    @Autowired ReturnService returns;
    @Autowired ExceptionService exceptions;
    @MockBean ShopifyGateway shopifyGateway;
    @MockBean ShopifyTokenProvider tokenProvider;

    record T(UUID tenant, UUID store, UUID main, long mainNum, UUID user, UUID variant, long shopifyVariantId) {}

    private final AtomicInteger seq = new AtomicInteger();

    @BeforeEach
    void reset() {
        Mockito.reset(shopifyGateway, tokenProvider);
        when(tokenProvider.getValidToken(any())).thenReturn("tok");
    }

    @AfterEach
    void clear() { TenantContext.clear(); }

    @Test
    void g1_refundRestockedAtAnotherLocation_doesNotBlockTracedsPlusOneAtMain() throws Exception {
        T t = tenant("g1");
        UUID order = order(t, refundRaw(t, 1, "424242"));
        String piece = returnedPiece(t, order);

        TenantContext.runAs(t.tenant(), () -> returns.restock(piece, null, t.user()));

        awaitCount(t, "status = 'applied'", 1);
        assertThat(count(t, "status = 'skipped_shopify_restocked'")).isZero();
        verify(shopifyGateway, times(1)).adjustInventoryQuantities(any(), any(), any(), any(), eq(1), eq("restock"), any());
    }

    @Test
    void g2_refundRestockedAtMain_stillSkipped() throws Exception {
        T t = tenant("g2");
        UUID order = order(t, refundRaw(t, 1, String.valueOf(t.mainNum())));
        String piece = returnedPiece(t, order);

        TenantContext.runAs(t.tenant(), () -> returns.restock(piece, null, t.user()));

        awaitCount(t, "status = 'skipped_shopify_restocked'", 1);
        verify(shopifyGateway, never()).adjustInventoryQuantities(any(), any(), any(), any(), anyInt(), any(), any());
    }

    @Test
    void g3_restockLineWithNoLocation_stillCounts() throws Exception {
        T t = tenant("g3");
        UUID order = order(t, refundRaw(t, 1, null));
        String piece = returnedPiece(t, order);

        TenantContext.runAs(t.tenant(), () -> returns.restock(piece, null, t.user()));

        awaitCount(t, "status = 'skipped_shopify_restocked'", 1);
        verify(shopifyGateway, never()).adjustInventoryQuantities(any(), any(), any(), any(), anyInt(), any(), any());
    }

    @Test
    void g4_restockedTwice_ignoresARestockAtAnotherLocation() throws Exception {
        T t = tenant("g4");
        UUID order = order(t, "{\"id\": 1, \"refunds\": []}");
        String piece = returnedPiece(t, order);
        TenantContext.runAs(t.tenant(), () -> returns.restock(piece, null, t.user()));
        awaitCount(t, "status = 'applied'", 1);

        jdbc.update("UPDATE orders SET raw = ?::jsonb WHERE id = ?", refundRaw(t, 1, "424242"), order);
        assertThat(restockedTwice(t)).as("restocked elsewhere — Main counted once").isEmpty();

        jdbc.update("UPDATE orders SET raw = ?::jsonb WHERE id = ?", refundRaw(t, 1, String.valueOf(t.mainNum())), order);
        assertThat(restockedTwice(t)).as("positive control: restocked at Main too").hasSize(1);
    }

    // ── fixtures ────────────────────────────────────────────────────────────────────────────────────────────────

    private List<Map<String, Object>> restockedTwice(T t) {
        return TenantContext.runAs(t.tenant(), () -> exceptions.detectAllOpen()).stream()
            .filter(e -> "restocked_twice".equals(e.get("type"))).toList();
    }

    private int count(T t, String predicate) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM shopify_inventory_adjustments WHERE tenant_id = ? " +
            "AND trigger_type = 'return_inspection' AND " + predicate, Integer.class, t.tenant());
    }

    private void awaitCount(T t, String predicate, int n) throws Exception {
        long deadline = System.currentTimeMillis() + 10_000;
        while (count(t, predicate) < n) {
            if (System.currentTimeMillis() > deadline) throw new AssertionError("never reached " + n + " × " + predicate);
            Thread.sleep(25);
        }
        Thread.sleep(150);   // let any stray second call land before the caller verifies
    }

    /** A REST orders/* payload whose one refund restocked {@code qty} unit(s) of the variant at {@code locationId}. */
    private static String refundRaw(T t, int qty, String locationId) {
        return "{\"id\": 1, \"refunds\": [{\"id\": 9, \"refund_line_items\": [{\"quantity\": " + qty +
            ", \"restock_type\": \"return\"" + (locationId == null ? "" : ", \"location_id\": " + locationId) +
            ", \"line_item\": {\"variant_id\": " + t.shopifyVariantId() + "}}]}]}";
    }

    private UUID order(T t, String raw) {
        int k = seq.incrementAndGet();
        return jdbc.queryForObject(
            "INSERT INTO orders (tenant_id, store_id, external_id, number, status, payment_method, placed_at, raw) " +
            "VALUES (?, ?, ?, ?, 'new'::order_status, 'cod', now(), ?::jsonb) RETURNING id",
            UUID.class, t.tenant(), t.store(), "gid://shopify/Order/RG" + k, "#RG" + k, raw);
    }

    private String returnedPiece(T t, UUID order) {
        int k = seq.incrementAndGet();
        String id = String.format("01RESTOCKGATE%013d", k);
        jdbc.update("INSERT INTO pieces (id, tenant_id, variant_id, status, barcode, short_code, current_location_id, current_order_id) " +
            "VALUES (?, ?, ?, 'return_pending_inspection'::piece_status, ?, ?, ?, ?)",
            id, t.tenant(), t.variant(), "RG-" + k, String.format("G%07d", k), t.main(), order);
        return id;
    }

    private T tenant(String name) {
        UUID tenant = UUID.randomUUID(), store = UUID.randomUUID(), location = UUID.randomUUID(), user = UUID.randomUUID();
        UUID product = UUID.randomUUID(), variant = UUID.randomUUID();
        long shopifyVariantId = 7_200_000_000L + seq.incrementAndGet();
        long mainNum = 81_000_000L + seq.incrementAndGet();
        String shop = name + "-" + tenant.toString().substring(0, 6) + ".myshopify.com";
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?)", tenant, "Tenant " + name);
        jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role) VALUES (?, ?, 'Owner', ?, 'h', 'owner')",
            user, tenant, name + "-" + tenant.toString().substring(0, 6) + "@test.local");
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status, import_status, access_token_scopes) " +
            "VALUES (?, ?, 'shopify', ?, 'connected', 'completed', ?)", store, tenant, shop, SCOPES);
        jdbc.update("INSERT INTO locations (id, tenant_id, name, shopify_location_id, shopify_sync_status, is_fulfillment) " +
            "VALUES (?, ?, 'Main Warehouse', ?, 'linked', true)", location, tenant, "gid://shopify/Location/" + mainNum);
        jdbc.update("INSERT INTO products (id, tenant_id, store_id, external_id, title, status) VALUES (?, ?, ?, ?, 'P', 'active')",
            product, tenant, store, "gid://shopify/Product/" + shop);
        jdbc.update("INSERT INTO variants (id, tenant_id, product_id, external_id, title, sku, shopify_inventory_item_id) " +
            "VALUES (?, ?, ?, ?, 'V', ?, ?)", variant, tenant, product, "gid://shopify/ProductVariant/" + shopifyVariantId,
            "SKU-" + name, "gid://shopify/InventoryItem/" + shopifyVariantId);
        return new T(tenant, store, location, mainNum, user, variant, shopifyVariantId);
    }
}
