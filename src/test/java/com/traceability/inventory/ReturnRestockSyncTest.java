package com.traceability.inventory;

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
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Returns restock → Shopify (2026-10-08 diagnosis, Issue 3), through the real
 * ReturnService.restock path (the one ReturnSessionService.disposition calls):
 *
 *   rs1  no location given → the piece lands at the main warehouse, +1 claimed and sent
 *   rs2  the same piece restocked on two returns → two applied +1 claims (per-restock key)
 *   rs3  Shopify refund restocked 1 of the order's 2 units → first Traced restock skipped
 *        (skipped_shopify_restocked, nothing sent), second pushed
 *   rs4  a Shopify refund restock arriving AFTER Traced's +1 → HIGH restocked_twice, no Shopify write
 *   rs5  tenant with no main warehouse → restock refused, piece unchanged
 *   rs6  restock into a non-main location → recorded skipped_not_fulfillment_location row, nothing sent
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReturnRestockSyncTest {

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

    static final String SCOPES = "read_orders,write_inventory,read_products,write_locations,read_locations";

    @Autowired JdbcTemplate jdbc;
    @Autowired ReturnService returns;
    @Autowired ExceptionService exceptions;
    @MockBean ShopifyGateway shopifyGateway;
    @MockBean ShopifyTokenProvider tokenProvider;

    record T(UUID tenant, UUID store, UUID main, UUID user, UUID variant, long shopifyVariantId) {}

    private final AtomicInteger seq = new AtomicInteger();

    @BeforeEach
    void reset() {
        Mockito.reset(shopifyGateway, tokenProvider);
        when(tokenProvider.getValidToken(any())).thenReturn("tok");
    }

    @AfterEach
    void clear() { TenantContext.clear(); }

    // ── rs1: null location → main warehouse + the +1 ─────────────────────────────

    @Test
    void rs1_nullLocation_defaultsToMainWarehouse_andPushesPlusOne() throws Exception {
        T t = tenant("rs1", true);
        String piece = returnedPiece(t, null, null);   // as in prod: no location on the piece

        TenantContext.runAs(t.tenant(), () -> returns.restock(piece, null, t.user()));

        assertThat(jdbc.queryForObject("SELECT current_location_id FROM pieces WHERE id = ?", UUID.class, piece))
            .as("never a NULL location — the main warehouse").isEqualTo(t.main());
        awaitCount(t, "status = 'applied'", 1);
        String triggerId = jdbc.queryForObject("SELECT trigger_id FROM shopify_inventory_adjustments " +
            "WHERE tenant_id = ? AND trigger_type = 'return_inspection'", String.class, t.tenant());
        assertThat(triggerId).startsWith(piece + ":");
        verify(shopifyGateway, times(1)).adjustInventoryQuantities(any(), any(), any(), any(), eq(1), eq("restock"), any());
    }

    // ── rs2: two returns of the same piece → two +1 ──────────────────────────────

    @Test
    void rs2_samePieceRestockedTwice_twoAppliedClaims() throws Exception {
        T t = tenant("rs2", true);
        String piece = returnedPiece(t, null, t.main());

        TenantContext.runAs(t.tenant(), () -> returns.restock(piece, null, t.user()));
        awaitCount(t, "status = 'applied'", 1);

        // The piece is sold and comes back again (fixture shortcut for the forward + return trip).
        jdbc.update("UPDATE pieces SET status = 'return_pending_inspection'::piece_status WHERE id = ?", piece);
        TenantContext.runAs(t.tenant(), () -> returns.restock(piece, null, t.user()));
        awaitCount(t, "status = 'applied'", 2);

        verify(shopifyGateway, times(2)).adjustInventoryQuantities(any(), any(), any(), any(), eq(1), eq("restock"), any());
    }

    // ── rs3: quantity-matched double-count guard ─────────────────────────────────

    @Test
    void rs3_shopifyRefundRestockedOneOfTwoUnits_firstSkipped_secondPushed() throws Exception {
        T t = tenant("rs3", true);
        UUID order = order(t, refundRaw(t, 1));
        String first = returnedPiece(t, order, t.main());
        String second = returnedPiece(t, order, t.main());

        TenantContext.runAs(t.tenant(), () -> returns.restock(first, null, t.user()));
        awaitCount(t, "status = 'skipped_shopify_restocked'", 1);
        verify(shopifyGateway, never()).adjustInventoryQuantities(any(), any(), any(), any(), anyInt(), any(), any());

        TenantContext.runAs(t.tenant(), () -> returns.restock(second, null, t.user()));
        awaitCount(t, "status = 'applied'", 1);
        verify(shopifyGateway, times(1)).adjustInventoryQuantities(any(), any(), any(), any(), eq(1), eq("restock"), any());

        List<String> byPiece = jdbc.queryForList("SELECT split_part(trigger_id, ':', 1) || '=' || status " +
            "FROM shopify_inventory_adjustments WHERE tenant_id = ? AND trigger_type = 'return_inspection' " +
            "ORDER BY created_at, id", String.class, t.tenant());
        assertThat(byPiece).containsExactlyInAnyOrder(first + "=skipped_shopify_restocked", second + "=applied");
    }

    // ── rs4: refund restock after Traced's +1 → HIGH exception, no write ─────────

    @Test
    void rs4_refundRestockAfterTracedPush_raisesRestockedTwice_noShopifyWrite() throws Exception {
        T t = tenant("rs4", true);
        UUID order = order(t, "{\"id\": 1, \"refunds\": []}");
        String piece = returnedPiece(t, order, t.main());

        TenantContext.runAs(t.tenant(), () -> returns.restock(piece, null, t.user()));
        awaitCount(t, "status = 'applied'", 1);
        assertThat(restockedTwice(t)).isEmpty();
        clearInvocations(shopifyGateway);

        // orders/updated: the merchant refunds the same unit in Shopify WITH restock.
        jdbc.update("UPDATE orders SET raw = ?::jsonb WHERE id = ?", refundRaw(t, 1), order);

        List<Map<String, Object>> rows = restockedTwice(t);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("severity")).isEqualTo("HIGH");
        assertThat(((Number) rows.get(0).get("qty")).intValue()).isEqualTo(1);
        assertThat((String) rows.get(0).get("descriptionEn")).startsWith("Restocked twice");
        verifyNoInteractions(shopifyGateway);
    }

    // ── rs5: no main warehouse → loud failure, piece untouched ───────────────────

    @Test
    void rs5_noMainWarehouse_restockRefused_pieceUnchanged() {
        T t = tenant("rs5", false);
        String piece = returnedPiece(t, null, null);

        assertThatThrownBy(() -> TenantContext.runAs(t.tenant(), () -> returns.restock(piece, null, t.user())))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("NO_MAIN_WAREHOUSE");

        assertThat(jdbc.queryForObject("SELECT status::text FROM pieces WHERE id = ?", String.class, piece))
            .isEqualTo("return_pending_inspection");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM piece_events WHERE piece_id = ?", Integer.class, piece)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM shopify_inventory_adjustments WHERE tenant_id = ?",
            Integer.class, t.tenant())).isZero();
    }

    // ── rs6: non-main location → a recorded skip, not a silent one ───────────────

    @Test
    void rs6_restockIntoNonMainLocation_recordedSkip() throws Exception {
        T t = tenant("rs6", true);
        UUID showroom = UUID.randomUUID();
        jdbc.update("INSERT INTO locations (id, tenant_id, name, is_fulfillment) VALUES (?, ?, 'Showroom', false)",
            showroom, t.tenant());
        String piece = returnedPiece(t, null, t.main());

        TenantContext.runAs(t.tenant(), () -> returns.restock(piece, showroom, t.user()));

        awaitCount(t, "status = 'skipped_not_fulfillment_location' AND location_id = '" + showroom + "'", 1);
        verify(shopifyGateway, never()).adjustInventoryQuantities(any(), any(), any(), any(), anyInt(), any(), any());
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    private List<Map<String, Object>> restockedTwice(T t) {
        return TenantContext.runAs(t.tenant(), () -> exceptions.detectAllOpen()).stream()
            .filter(e -> "restocked_twice".equals(e.get("type"))).toList();
    }

    private void awaitCount(T t, String predicate, int n) throws Exception {
        long deadline = System.currentTimeMillis() + 10_000;
        while (jdbc.queryForObject("SELECT COUNT(*) FROM shopify_inventory_adjustments WHERE tenant_id = ? " +
                "AND trigger_type = 'return_inspection' AND " + predicate, Integer.class, t.tenant()) < n) {
            if (System.currentTimeMillis() > deadline) throw new AssertionError("never reached " + n + " × " + predicate);
            Thread.sleep(25);
        }
        Thread.sleep(150);   // let any stray second call land before the caller verifies
    }

    /** A REST orders/* payload whose one refund restocked {@code qty} unit(s) of the tenant's variant. */
    private static String refundRaw(T t, int qty) {
        return "{\"id\": 1, \"refunds\": [{\"id\": 9, \"refund_line_items\": [{\"quantity\": " + qty +
            ", \"restock_type\": \"return\", \"line_item\": {\"variant_id\": " + t.shopifyVariantId() + "}}]}]}";
    }

    private UUID order(T t, String raw) {
        int k = seq.incrementAndGet();
        return jdbc.queryForObject(
            "INSERT INTO orders (tenant_id, store_id, external_id, number, status, payment_method, placed_at, raw) " +
            "VALUES (?, ?, ?, ?, 'new'::order_status, 'cod', now(), ?::jsonb) RETURNING id",
            UUID.class, t.tenant(), t.store(), "gid://shopify/Order/RS" + k, "#RS" + k, raw);
    }

    private String returnedPiece(T t, UUID order, UUID location) {
        int k = seq.incrementAndGet();
        String id = String.format("01RESTOCKSYNC%013d", k);
        jdbc.update("INSERT INTO pieces (id, tenant_id, variant_id, status, barcode, short_code, current_location_id, current_order_id) " +
            "VALUES (?, ?, ?, 'return_pending_inspection'::piece_status, ?, ?, ?, ?)",
            id, t.tenant(), t.variant(), "RS-" + k, String.format("R%07d", k), location, order);
        return id;
    }

    private T tenant(String name, boolean withMain) {
        UUID tenant = UUID.randomUUID(), store = UUID.randomUUID(), location = UUID.randomUUID(), user = UUID.randomUUID();
        UUID product = UUID.randomUUID(), variant = UUID.randomUUID();
        long shopifyVariantId = 7_000_000_000L + seq.incrementAndGet();
        String shop = name + "-" + tenant.toString().substring(0, 6) + ".myshopify.com";
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?)", tenant, "Tenant " + name);
        jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role) VALUES (?, ?, 'Owner', ?, 'h', 'owner')",
            user, tenant, name + "-" + tenant.toString().substring(0, 6) + "@test.local");
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status, import_status, access_token_scopes) " +
            "VALUES (?, ?, 'shopify', ?, 'connected', 'completed', ?)", store, tenant, shop, SCOPES);
        jdbc.update("INSERT INTO locations (id, tenant_id, name, shopify_location_id, shopify_sync_status, is_fulfillment) " +
            "VALUES (?, ?, ?, ?, 'linked', ?)", location, tenant, withMain ? "Main Warehouse" : "Side room",
            "gid://shopify/Location/" + shop, withMain);
        jdbc.update("INSERT INTO products (id, tenant_id, store_id, external_id, title, status) VALUES (?, ?, ?, ?, 'P', 'active')",
            product, tenant, store, "gid://shopify/Product/" + shop);
        jdbc.update("INSERT INTO variants (id, tenant_id, product_id, external_id, title, sku, shopify_inventory_item_id) " +
            "VALUES (?, ?, ?, ?, 'V', ?, ?)", variant, tenant, product, "gid://shopify/ProductVariant/" + shopifyVariantId,
            "SKU-" + name, "gid://shopify/InventoryItem/" + shopifyVariantId);
        return new T(tenant, store, withMain ? location : null, user, variant, shopifyVariantId);
    }
}
