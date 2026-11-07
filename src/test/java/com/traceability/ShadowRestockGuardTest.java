package com.traceability;

import com.traceability.integrations.shopify.ShopifyGateway;
import com.traceability.integrations.shopify.ShopifyTokenProvider;
import com.traceability.inventory.PieceAdjustService;
import com.traceability.inventory.ReturnService;
import com.traceability.inventory.ShopifyShadowOrders;
import com.traceability.tenancy.TenantContext;
import org.jobrunr.scheduling.JobScheduler;
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

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * S3c restock guard (problem 7): Shopify records a returned exchange replacement's restock on the ORIGINAL order (the
 * exchange line lives there), while Traced's piece last belonged to the SHADOW order ('internal:shopify-exchange:').
 * {@link ShopifyShadowOrders#restockOrderId} keys every restock guard by the original, so a unit Shopify already
 * restocked at Main is never +1'd again by Traced.
 *
 *   r1  return inspection AVAILABLE of a replacement returned from the shadow, Shopify already restocked 1 unit of the
 *       variant on the ORIGINAL at Main (RESTOCKED disposition) → claim 'skipped_shopify_restocked', no Shopify call
 *   r2  control: the same with no Shopify restock → the +1 is sent once
 *   r3  Back to good of a replacement damaged at return inspection (damaged_restore_increment) — same rule: skipped;
 *       control without a Shopify restock → the +1 is sent once
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ShadowRestockGuardTest {

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
    @Autowired PieceAdjustService adjust;
    @MockBean JobScheduler jobScheduler;
    @MockBean ShopifyGateway shopifyGateway;
    @MockBean ShopifyTokenProvider tokenProvider;

    record T(UUID tenant, UUID store, UUID main, long mainNum, UUID user, UUID variant, long shopifyVariantId) {
        String variantGid() { return "gid://shopify/ProductVariant/" + shopifyVariantId; }
        String mainGid() { return "gid://shopify/Location/" + mainNum; }
    }
    record X(UUID original, UUID shadow, UUID shadowItem) {}

    private final AtomicInteger seq = new AtomicInteger();

    @BeforeEach
    void reset() {
        Mockito.reset(shopifyGateway, tokenProvider, jobScheduler);
        when(tokenProvider.getValidToken(any())).thenReturn("tok");
    }

    @AfterEach
    void clear() { TenantContext.clear(); }

    // ── return inspection ────────────────────────────────────────────────────────────────────────────────────

    @Test
    void r1_replacementRestockedOnOriginalInShopify_inspectionPlusOneSkipped() throws Exception {
        T t = tenant("r1", false);
        X x = nativeExchange(t);
        restockedOnOriginal(t, x.original(), 1);
        assertThat(restockedUnits(t, x.shadow())).as("Shopify records nothing on the shadow").isZero();
        assertThat(restockedUnits(t, x.original())).isEqualTo(1);
        String piece = returnedReplacement(t, x);

        TenantContext.runAs(t.tenant(), () -> returns.restock(piece, null, t.user()));

        awaitCount(t, "return_inspection", "status = 'skipped_shopify_restocked'", 1);
        assertThat(jdbc.queryForObject("SELECT source_order_id FROM shopify_inventory_adjustments WHERE tenant_id = ? " +
            "AND trigger_type = 'return_inspection'", UUID.class, t.tenant())).as("counted on the original").isEqualTo(x.original());
        verify(shopifyGateway, never()).adjustInventoryQuantities(any(), any(), any(), any(), anyInt(), any(), any());
    }

    @Test
    void r2_control_noShopifyRestock_inspectionPlusOneSentOnce() throws Exception {
        T t = tenant("r2", false);
        X x = nativeExchange(t);
        String piece = returnedReplacement(t, x);

        TenantContext.runAs(t.tenant(), () -> returns.restock(piece, null, t.user()));

        awaitCount(t, "return_inspection", "status = 'applied'", 1);
        assertThat(count(t, "return_inspection", "status = 'skipped_shopify_restocked'")).isZero();
        verify(shopifyGateway, times(1)).adjustInventoryQuantities(any(), any(), any(), any(), eq(1), eq("restock"), any());
    }

    // ── Back to good ─────────────────────────────────────────────────────────────────────────────────────────

    @Test
    void r3_backToGood_replacementRestockedOnOriginal_skipped_controlSent() throws Exception {
        T t = tenant("r3", true);
        X x = nativeExchange(t);
        restockedOnOriginal(t, x.original(), 1);
        String piece = returnedDamagedReplacement(t, x);

        as(t, () -> adjust.restore(piece, "repaired", null, t.user()));

        Map<String, Object> c = claim(t, "damaged_restore_increment", piece);
        assertThat(c).containsEntry("status", "skipped_shopify_restocked").containsEntry("skip_reason", "shopify_restocked")
            .containsEntry("source_order_id", x.original());
        Thread.sleep(300);
        verify(shopifyGateway, never()).pushPieceIncrement(any(), any(), any(), any(), anyInt(), any(), any(), any());
        verify(shopifyGateway, never()).adjustInventoryQuantities(any(), any(), any(), any(), anyInt(), any(), any());

        // Control: another native exchange of the same tenant, nothing restocked in Shopify → +1 sent once.
        X y = nativeExchange(t);
        String other = returnedDamagedReplacement(t, y);
        as(t, () -> adjust.restore(other, "repaired", null, t.user()));
        awaitCount(t, "damaged_restore_increment", "status = 'applied'", 1);
        verify(shopifyGateway, times(1)).pushPieceIncrement(any(), any(), any(), any(), eq(1), any(), any(), any());
    }

    // ── fixtures ────────────────────────────────────────────────────────────────────────────────────────────

    private Long restockedUnits(T t, UUID order) {
        return jdbc.queryForObject("SELECT shopify_restocked_units(o.raw, o.tenant_id, o.id, ?, ?) FROM orders o WHERE o.id = ?",
            Long.class, t.variantGid(), t.mainGid(), order);
    }

    private int count(T t, String triggerType, String predicate) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM shopify_inventory_adjustments WHERE tenant_id = ? " +
            "AND trigger_type = ? AND " + predicate, Integer.class, t.tenant(), triggerType);
    }

    private void awaitCount(T t, String triggerType, String predicate, int n) throws Exception {
        long deadline = System.currentTimeMillis() + 10_000;
        while (count(t, triggerType, predicate) < n) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("never reached " + n + " × " + triggerType + " " + predicate + "; rows: " +
                    jdbc.queryForList("SELECT trigger_type, status, skip_reason, error FROM shopify_inventory_adjustments " +
                        "WHERE tenant_id = ?", t.tenant()));
            }
            Thread.sleep(25);
        }
        Thread.sleep(150);
    }

    private Map<String, Object> claim(T t, String triggerType, String piece) {
        return jdbc.queryForMap("SELECT trigger_id, status, skip_reason, delta, source_order_id " +
            "FROM shopify_inventory_adjustments WHERE tenant_id = ? AND trigger_type = ? " +
            "AND split_part(trigger_id, ':', 1) = ?", t.tenant(), triggerType, piece);
    }

    private void as(T t, Runnable r) {
        TenantContext.runAs(t.tenant(), () -> { r.run(); return null; });
    }

    /**
     * A Shopify-native exchange as S3b stores it: the original (delivered) order, a Shopify Return on it with one
     * exchange line of the variant, and the shadow order ('internal:shopify-exchange:') with its V219 rows.
     */
    private X nativeExchange(T t) {
        int k = seq.incrementAndGet();
        String orderGid = "gid://shopify/Order/SRG" + k;
        UUID original = jdbc.queryForObject(
            "INSERT INTO orders (tenant_id, store_id, external_id, number, status, payment_method, placed_at, raw) " +
            "VALUES (?, ?, ?, ?, 'delivered'::order_status, 'cod', now() - interval '10 days', '{\"id\": 1, \"refunds\": []}'::jsonb) " +
            "RETURNING id", UUID.class, t.tenant(), t.store(), orderGid, "#SRG" + k);
        jdbc.update("INSERT INTO order_items (tenant_id, order_id, variant_id, quantity) VALUES (?, ?, ?, 1)",
            t.tenant(), original, t.variant());
        String returnGid = "gid://shopify/Return/" + (77_000_000_000L + k);
        UUID ret = jdbc.queryForObject(
            "INSERT INTO shopify_returns (tenant_id, store_id, return_gid, order_id, order_gid, name, status, raw) " +
            "VALUES (?, ?, ?, ?, ?, ?, 'CLOSED', '{}'::jsonb) RETURNING id",
            UUID.class, t.tenant(), t.store(), returnGid, original, orderGid, "#SRG" + k + "-R1");
        UUID xl = jdbc.queryForObject(
            "INSERT INTO shopify_exchange_lines (tenant_id, return_id, line_gid, variant_gid, variant_id, quantity, " +
            "processed_quantity, order_line_gids, raw) VALUES (?, ?, ?, ?, ?, 1, 1, ARRAY[?]::text[], '{}'::jsonb) RETURNING id",
            UUID.class, t.tenant(), ret, "gid://shopify/ExchangeLineItem/" + k, t.variantGid(), t.variant(),
            "gid://shopify/LineItem/" + (56_000_000_000L + k));
        UUID shadow = jdbc.queryForObject(
            "INSERT INTO orders (tenant_id, store_id, external_id, number, status, payment_method, placed_at, raw, origin) " +
            "VALUES (?, ?, ?, ?, 'delivered'::order_status, 'cod', now() - interval '5 days', '{}'::jsonb, 'shopify_exchange') " +
            "RETURNING id", UUID.class, t.tenant(), t.store(), ShopifyShadowOrders.PREFIX + returnGid, "#SRG" + k + "-X1");
        UUID item = jdbc.queryForObject(
            "INSERT INTO order_items (tenant_id, order_id, variant_id, quantity) VALUES (?, ?, ?, 1) RETURNING id",
            UUID.class, t.tenant(), shadow, t.variant());
        UUID row = jdbc.queryForObject(
            "INSERT INTO shopify_exchange_shadows (tenant_id, store_id, return_id, order_id, original_order_id, x_index, " +
            "approved_at) VALUES (?, ?, ?, ?, ?, 1, now() - interval '5 days') RETURNING id",
            UUID.class, t.tenant(), t.store(), ret, shadow, original);
        jdbc.update("INSERT INTO shopify_exchange_shadow_lines (tenant_id, shadow_id, exchange_line_id, order_item_id, " +
            "variant_id, quantity, live_quantity) VALUES (?, ?, ?, ?, ?, 1, 1)", t.tenant(), row, xl, item, t.variant());
        return new X(original, shadow, item);
    }

    /** The customer returned the replacement in Shopify: a second Return on the ORIGINAL, RESTOCKED at Main. */
    private void restockedOnOriginal(T t, UUID original, int qty) {
        int k = seq.incrementAndGet();
        String orderGid = jdbc.queryForObject("SELECT external_id FROM orders WHERE id = ?", String.class, original);
        UUID ret = jdbc.queryForObject(
            "INSERT INTO shopify_returns (tenant_id, store_id, return_gid, order_id, order_gid, name, status, raw) " +
            "VALUES (?, ?, ?, ?, ?, ?, 'CLOSED', '{}'::jsonb) RETURNING id",
            UUID.class, t.tenant(), t.store(), "gid://shopify/Return/" + (78_000_000_000L + k), original, orderGid, "#RR" + k + "-R2");
        String fli = "gid://shopify/FulfillmentLineItem/" + (79_000_000_000L + k);
        jdbc.update(
            "INSERT INTO shopify_return_lines (tenant_id, return_id, line_gid, fulfillment_line_gid, variant_gid, quantity, " +
            "processed_quantity, raw) VALUES (?, ?, ?, ?, ?, ?, ?, '{}'::jsonb)",
            t.tenant(), ret, "gid://shopify/ReturnLineItem/" + (79_000_000_000L + k), fli, t.variantGid(), qty, qty);
        jdbc.update(
            "INSERT INTO shopify_rfo_dispositions (tenant_id, return_id, disposition_gid, rfo_gid, rfo_line_gid, " +
            "fulfillment_line_gid, type, quantity, location_gid, raw) VALUES (?, ?, ?, ?, ?, ?, 'RESTOCKED', ?, ?, '{}'::jsonb)",
            t.tenant(), ret, "gid://shopify/ReverseFulfillmentOrderDisposition/" + k,
            "gid://shopify/ReverseFulfillmentOrder/" + k, "gid://shopify/ReverseFulfillmentOrderLineItem/" + k,
            fli, qty, t.mainGid());
    }

    /** The replacement piece, delivered for the SHADOW order and now back awaiting inspection (its order = the shadow). */
    private String returnedReplacement(T t, X x) {
        String piece = piece(t, "return_pending_inspection");
        jdbc.update("INSERT INTO allocations (tenant_id, order_item_id, piece_id, status) VALUES (?, ?, ?, 'packed'::allocation_status)",
            t.tenant(), x.shadowItem(), piece);
        jdbc.update("UPDATE pieces SET current_order_id = ? WHERE id = ?", x.shadow(), piece);
        jdbc.update("INSERT INTO piece_events (tenant_id, piece_id, event_type, from_status, to_status, order_id, occurred_at) " +
            "VALUES (?, ?, 'courier_update', 'with_courier', 'delivered', ?, now() - interval '30 minutes')", t.tenant(), piece, x.shadow());
        jdbc.update("INSERT INTO piece_events (tenant_id, piece_id, event_type, from_status, to_status, order_id, occurred_at) " +
            "VALUES (?, ?, 'return_received', 'delivered', 'return_pending_inspection', ?, now() - interval '10 minutes')",
            t.tenant(), piece, x.shadow());
        return piece;
    }

    /** As {@link #returnedReplacement}, then marked damaged at return inspection (allocation kept, as in production). */
    private String returnedDamagedReplacement(T t, X x) {
        String piece = returnedReplacement(t, x);
        as(t, () -> returns.markDamaged(piece, "damaged_in_transit", t.user()));
        return piece;
    }

    private String piece(T t, String status) {
        int k = seq.incrementAndGet();
        String id = String.format("01SHADOWRG%016d", k);
        jdbc.update("INSERT INTO pieces (id, tenant_id, variant_id, status, barcode, short_code, current_location_id, created_at) " +
            "VALUES (?, ?, ?, ?::piece_status, ?, ?, ?, now() - interval '1 day')",
            id, t.tenant(), t.variant(), status, "SRG-" + k, String.format("G%07d", k), t.main());
        return id;
    }

    private T tenant(String name, boolean seeded) {
        UUID tenant = UUID.randomUUID(), store = UUID.randomUUID(), location = UUID.randomUUID(), user = UUID.randomUUID();
        UUID product = UUID.randomUUID(), variant = UUID.randomUUID();
        long shopifyVariantId = 7_500_000_000L + seq.incrementAndGet();
        long mainNum = 83_000_000L + seq.incrementAndGet();
        String shop = name + "-" + tenant.toString().substring(0, 6) + ".myshopify.com";
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?)", tenant, "Tenant " + name);
        jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role) VALUES (?, ?, 'Owner', ?, 'h', 'owner')",
            user, tenant, name + "-" + tenant.toString().substring(0, 6) + "@test.local");
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status, import_status, access_token_scopes, last_sync_at) " +
            "VALUES (?, ?, 'shopify', ?, 'connected', 'completed', ?, now())", store, tenant, shop, SCOPES);
        jdbc.update("INSERT INTO locations (id, tenant_id, name, type, is_default, is_fulfillment, shopify_location_id, shopify_sync_status) " +
            "VALUES (?, ?, 'Main Warehouse', 'warehouse', true, true, ?, 'linked')", location, tenant, "gid://shopify/Location/" + mainNum);
        jdbc.update("INSERT INTO products (id, tenant_id, store_id, external_id, title, status) VALUES (?, ?, ?, ?, 'P', 'active')",
            product, tenant, store, "gid://shopify/Product/" + shop);
        jdbc.update("INSERT INTO variants (id, tenant_id, product_id, external_id, title, sku, shopify_inventory_item_id) " +
            "VALUES (?, ?, ?, ?, 'V', ?, ?)", variant, tenant, product, "gid://shopify/ProductVariant/" + shopifyVariantId,
            "SKU-" + name, "gid://shopify/InventoryItem/" + shopifyVariantId);
        if (seeded) {
            jdbc.update("INSERT INTO shopify_inventory_adjustments (tenant_id, batch_id, variant_id, location_id, delta, " +
                "trigger_type, trigger_id, status, created_at) VALUES (?, ?, ?, ?, 1, 'initial_seed', ?, 'applied', " +
                "now() - interval '1 hour')", tenant, UUID.randomUUID(), variant, location, "fixture-seed:" + UUID.randomUUID());
        }
        return new T(tenant, store, location, mainNum, user, variant, shopifyVariantId);
    }
}
