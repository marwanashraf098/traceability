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
 * S1b (V212) — restock double-count protection counts Shopify RETURN restocks (RESTOCKED dispositions of the returns
 * mirror), not only refund restock lines, for every merchant. Step 0 (dev store, 2026-11-05): one unit returned with
 * refund + restock in one step carries BOTH records while on_hand rose once → GREATEST per order + variant at Main.
 *
 *   d1  disposition-only restock at Main → Traced's inspection +1 skipped (revert the function to refunds only → RED)
 *   d2  refund-only restock at Main → skipped, unchanged; a disposition at ANOTHER location doesn't block the +1
 *   d3  both records for one unit, two units back → exactly one skip, one +1 (SUM instead of GREATEST → RED)
 *   d4  a disposition arriving AFTER Traced's +1 → restocked_twice (HIGH), never a decrement
 *   d5  Receiving a returned unit (line linked to its return) Shopify already restocked → nothing sent, claim
 *       'skipped_shopify_restocked', the line records it, and the piece's arrival counts as Shopify-counted
 *   d6  Receiving: linked unit skipped, unlinked units of the same variant still +N
 *   d7  Receiving with no Shopify restock → full +N (unchanged); a later disposition → restocked_twice
 *   d8  cross-tenant: another tenant's disposition never blocks; the same tenant's does (positive control); another
 *       tenant's return can't be linked
 *   g1  golden: with no disposition, shopify_restocked_units equals the V201 refund function for every payload shape,
 *       and an order with no Shopify restock still gets Traced's +1
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ShopifyRestockedUnitsTest {

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
    @Autowired ReceivingService receiving;
    @Autowired ExceptionService exceptions;
    @MockBean ShopifyGateway shopifyGateway;
    @MockBean ShopifyTokenProvider tokenProvider;

    record T(UUID tenant, UUID store, UUID main, long mainNum, UUID user, UUID variant, long shopifyVariantId) {
        String variantGid() { return "gid://shopify/ProductVariant/" + shopifyVariantId; }
        String mainGid() { return "gid://shopify/Location/" + mainNum; }
    }

    private final AtomicInteger seq = new AtomicInteger();

    @BeforeEach
    void reset() {
        Mockito.reset(shopifyGateway, tokenProvider);
        when(tokenProvider.getValidToken(any())).thenReturn("tok");
    }

    @AfterEach
    void clear() { TenantContext.clear(); }

    // ── return inspection ────────────────────────────────────────────────────────────────────────────────────

    @Test
    void d1_dispositionOnlyRestockAtMain_tracedPlusOneSkipped() throws Exception {
        T t = tenant("d1", null);
        UUID order = order(t, "{\"id\": 1, \"refunds\": []}");
        ShopifyReturnFixtures.restockedReturn(jdbc, t.tenant(), t.store(), order, t.variantGid(), 1, t.mainGid());
        String piece = returnedPiece(t, order);

        TenantContext.runAs(t.tenant(), () -> returns.restock(piece, null, t.user()));

        awaitCount(t, "return_inspection", "status = 'skipped_shopify_restocked'", 1);
        assertThat(jdbc.queryForObject("SELECT error FROM shopify_inventory_adjustments WHERE tenant_id = ? " +
            "AND trigger_type = 'return_inspection'", String.class, t.tenant())).contains("refund or a return");
        verify(shopifyGateway, never()).adjustInventoryQuantities(any(), any(), any(), any(), anyInt(), any(), any());
    }

    @Test
    void d2_refundOnlyStillSkipped_dispositionElsewhereDoesNotBlock() throws Exception {
        T t = tenant("d2", null);
        UUID refunded = order(t, refundRaw(t, 1, String.valueOf(t.mainNum())));
        String a = returnedPiece(t, refunded);
        TenantContext.runAs(t.tenant(), () -> returns.restock(a, null, t.user()));
        awaitCount(t, "return_inspection", "status = 'skipped_shopify_restocked'", 1);

        UUID elsewhere = order(t, "{\"id\": 2, \"refunds\": []}");
        ShopifyReturnFixtures.restockedReturn(jdbc, t.tenant(), t.store(), elsewhere, t.variantGid(), 1,
            "gid://shopify/Location/424242");
        String b = returnedPiece(t, elsewhere);
        TenantContext.runAs(t.tenant(), () -> returns.restock(b, null, t.user()));
        awaitCount(t, "return_inspection", "status = 'applied'", 1);
        verify(shopifyGateway, times(1)).adjustInventoryQuantities(any(), any(), any(), any(), eq(1), eq("restock"), any());
    }

    @Test
    void d3_bothRecordsForOneUnit_exactlyOneSkip() throws Exception {
        T t = tenant("d3", null);
        // Shopify's #1007 shape: one unit, a refund RETURN line AND a RESTOCKED disposition. Two units come back.
        UUID order = order(t, refundRaw(t, 1, String.valueOf(t.mainNum())));
        ShopifyReturnFixtures.restockedReturn(jdbc, t.tenant(), t.store(), order, t.variantGid(), 1, t.mainGid());
        assertThat(restockedUnits(t, order)).isEqualTo(1);

        String first = returnedPiece(t, order), second = returnedPiece(t, order);
        TenantContext.runAs(t.tenant(), () -> returns.restock(first, null, t.user()));
        awaitCount(t, "return_inspection", "status = 'skipped_shopify_restocked'", 1);
        TenantContext.runAs(t.tenant(), () -> returns.restock(second, null, t.user()));
        awaitCount(t, "return_inspection", "status = 'applied'", 1);

        assertThat(count(t, "return_inspection", "status = 'skipped_shopify_restocked'")).isEqualTo(1);
        verify(shopifyGateway, times(1)).adjustInventoryQuantities(any(), any(), any(), any(), eq(1), eq("restock"), any());
    }

    @Test
    void d4_dispositionAfterTracedPlusOne_restockedTwice_noDecrement() throws Exception {
        T t = tenant("d4", null);
        UUID order = order(t, "{\"id\": 1, \"refunds\": []}");
        String piece = returnedPiece(t, order);
        TenantContext.runAs(t.tenant(), () -> returns.restock(piece, null, t.user()));
        awaitCount(t, "return_inspection", "status = 'applied'", 1);
        assertThat(restockedTwice(t)).as("nothing restocked in Shopify yet").isEmpty();

        ShopifyReturnFixtures.restockedReturn(jdbc, t.tenant(), t.store(), order, t.variantGid(), 1, t.mainGid());

        List<Map<String, Object>> alerts = restockedTwice(t);
        assertThat(alerts).hasSize(1);
        assertThat(alerts.get(0)).containsEntry("severity", "HIGH").containsEntry("qty", 1L);
        assertThat((String) alerts.get(0).get("descriptionEn")).contains("refund or return restocked them again");
        // Only Traced's own +1 was ever written — the late restock is an alert, never a decrement.
        verify(shopifyGateway, times(1)).adjustInventoryQuantities(any(), any(), any(), any(), anyInt(), any(), any());
    }

    // ── Receiving ────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    void d5_receivingAReturnedUnitShopifyRestocked_nothingSent_arrivalCounted() throws Exception {
        T t = tenant("d5", null);
        UUID order = order(t, "{\"id\": 1, \"refunds\": []}");
        ShopifyReturnFixtures.restockedReturn(jdbc, t.tenant(), t.store(), order, t.variantGid(), 1, t.mainGid());
        String key = "shipment:" + returnLeg(t, order);

        Map<String, Object> preview = TenantContext.runAs(t.tenant(), () -> receiving.returnSourcePreview(key));
        @SuppressWarnings("unchecked") List<Map<String, Object>> items = (List<Map<String, Object>>) preview.get("items");
        assertThat(items).singleElement().satisfies(i -> assertThat(i).containsEntry("alreadyInShopify", 1L));

        UUID session = TenantContext.runAs(t.tenant(), () -> {
            UUID s = receiving.createSession(t.user(), t.main(), "RET", null, null);
            receiving.addLine(s, t.variant(), 1, key);
            receiving.finalize(s, t.user());
            return s;
        });

        Map<String, Object> claim = receivingClaim(t, session);
        assertThat(claim).containsEntry("status", "skipped_shopify_restocked").containsEntry("delta", 1);
        assertThat(jdbc.queryForObject("SELECT shopify_restocked_units FROM receipt_lines WHERE receipt_id = ?",
            Integer.class, session)).isEqualTo(1);
        Thread.sleep(300);
        verify(shopifyGateway, never()).adjustInventoryQuantities(any(), any(), any(), any(), anyInt(), any(), any());

        String piece = jdbc.queryForObject("SELECT id FROM pieces WHERE receipt_id = ?", String.class, session);
        PieceShopifyRules.Verdict arrival = TenantContext.runAs(t.tenant(), () ->
            PieceShopifyRules.arrival(jdbc, t.tenant(), piece, new PieceShopifyRules.Setup(t.main(), null, null)));
        assertThat(arrival.write()).as("Shopify counted it through its own restock").isTrue();
        assertThat(restockedTwice(t)).isEmpty();
    }

    @Test
    void d6_receivingLinkedUnitSkipped_unlinkedUnitsStillSent() throws Exception {
        T t = tenant("d6", null);
        UUID order = order(t, refundRaw(t, 1, String.valueOf(t.mainNum())));
        String key = "shipment:" + returnLeg(t, order);

        UUID session = TenantContext.runAs(t.tenant(), () -> {
            UUID s = receiving.createSession(t.user(), t.main(), "MIX", null, null);
            receiving.addLine(s, t.variant(), 1, key);
            receiving.addLine(s, t.variant(), 2);
            receiving.finalize(s, t.user());
            return s;
        });

        awaitReceivingStatus(t, session, "applied");
        assertThat(receivingClaim(t, session)).containsEntry("delta", 2);
        verify(shopifyGateway, times(1)).adjustInventoryQuantities(any(), any(), any(), any(), eq(2), eq("received"), any());
        assertThat(jdbc.queryForList("SELECT shopify_restocked_units FROM receipt_lines WHERE receipt_id = ? " +
            "ORDER BY return_source NULLS LAST", Integer.class, session)).containsExactly(1, 0);
    }

    @Test
    void d7_receivingWithNoShopifyRestock_fullPlusN_laterDispositionRestockedTwice() throws Exception {
        T t = tenant("d7", null);
        UUID order = order(t, "{\"id\": 1, \"refunds\": []}");
        String key = "shipment:" + returnLeg(t, order);

        UUID session = TenantContext.runAs(t.tenant(), () -> {
            UUID s = receiving.createSession(t.user(), t.main(), "RET", null, null);
            receiving.addLine(s, t.variant(), 1, key);
            receiving.finalize(s, t.user());
            return s;
        });
        awaitReceivingStatus(t, session, "applied");
        verify(shopifyGateway, times(1)).adjustInventoryQuantities(any(), any(), any(), any(), eq(1), eq("received"), any());
        assertThat(restockedTwice(t)).isEmpty();

        ShopifyReturnFixtures.restockedReturn(jdbc, t.tenant(), t.store(), order, t.variantGid(), 1, t.mainGid());
        assertThat(restockedTwice(t)).singleElement().satisfies(a -> assertThat(a).containsEntry("qty", 1L));

        // The guard counts the received unit: a further inspection restock of the same order is not skipped by it.
        String piece = returnedPiece(t, order);
        TenantContext.runAs(t.tenant(), () -> returns.restock(piece, null, t.user()));
        awaitCount(t, "return_inspection", "status = 'applied'", 1);
    }

    // ── cross-tenant / golden ────────────────────────────────────────────────────────────────────────────────

    @Test
    void d8_crossTenant_otherTenantsDispositionNeverBlocks_sameTenantDoes() throws Exception {
        long sharedVariant = 7_300_000_000L + seq.incrementAndGet();
        T a = tenant("d8a", sharedVariant), b = tenant("d8b", sharedVariant);
        UUID orderA = order(a, "{\"id\": 1, \"refunds\": []}");
        UUID orderB = order(b, "{\"id\": 1, \"refunds\": []}");
        // Tenant B's return restocked the same Shopify variant at B's Main.
        ShopifyReturnFixtures.restockedReturn(jdbc, b.tenant(), b.store(), orderB, b.variantGid(), 1, b.mainGid());

        assertThat(restockedUnits(a, orderA)).isZero();
        String pa = returnedPiece(a, orderA);
        TenantContext.runAs(a.tenant(), () -> returns.restock(pa, null, a.user()));
        awaitCount(a, "return_inspection", "status = 'applied'", 1);

        // Positive control: B's own restock is blocked by B's disposition.
        String pb = returnedPiece(b, orderB);
        TenantContext.runAs(b.tenant(), () -> returns.restock(pb, null, b.user()));
        awaitCount(b, "return_inspection", "status = 'skipped_shopify_restocked'", 1);

        // B's return leg can't be linked from A.
        String keyB = "shipment:" + returnLeg(b, orderB);
        assertThatThrownBy(() -> TenantContext.runAs(a.tenant(), () -> receiving.returnSourcePreview(keyB)))
            .isInstanceOf(ResponseStatusException.class).hasMessageContaining("404");
        assertThat(TenantContext.runAs(b.tenant(), () -> receiving.returnSourcePreview(keyB))).containsKey("items");
    }

    @Test
    void g1_golden_noDisposition_sameAsRefundFunction_andPlusOneSent() throws Exception {
        T t = tenant("g1", null);
        List<String> raws = List.of(
            "{\"id\": 1, \"refunds\": []}",
            "{\"id\": 1}",
            "{\"refunds\": \"broken\"}",
            refundRaw(t, 1, null),
            refundRaw(t, 2, String.valueOf(t.mainNum())),
            refundRaw(t, 3, "424242"),
            "{\"refunds\": [{\"refund_line_items\": [{\"quantity\": \"x\", \"restock_type\": \"return\", " +
                "\"line_item\": {\"variant_id\": " + t.shopifyVariantId() + "}}]}]}",
            "{\"refunds\": [{\"refund_line_items\": [{\"quantity\": 1, \"restock_type\": \"no_restock\", " +
                "\"line_item\": {\"variant_id\": " + t.shopifyVariantId() + "}}]}]}");
        for (String raw : raws) {
            UUID order = order(t, raw);
            Long combined = restockedUnits(t, order);
            Long v201 = jdbc.queryForObject("SELECT shopify_refund_restocked_units(raw, ?, ?) FROM orders WHERE id = ?",
                Long.class, t.variantGid(), t.mainGid(), order);
            assertThat(combined).as(raw).isEqualTo(v201);
        }
        UUID plain = order(t, "{\"id\": 9, \"refunds\": []}");
        String piece = returnedPiece(t, plain);
        TenantContext.runAs(t.tenant(), () -> returns.restock(piece, null, t.user()));
        awaitCount(t, "return_inspection", "status = 'applied'", 1);
        verify(shopifyGateway, times(1)).adjustInventoryQuantities(any(), any(), any(), any(), eq(1), eq("restock"), any());
    }

    /** Step 5's read-only ops script: lists exactly the order + variant Traced +1'd that a later return restocked. */
    @Test
    void s1_candidatesScript_listsTheDoubleCountOnly() throws Exception {
        T t = tenant("s1", null);
        UUID doubled = order(t, "{\"id\": 1, \"refunds\": []}");
        String piece = returnedPiece(t, doubled);
        TenantContext.runAs(t.tenant(), () -> returns.restock(piece, null, t.user()));
        awaitCount(t, "return_inspection", "status = 'applied'", 1);
        ShopifyReturnFixtures.restockedReturn(jdbc, t.tenant(), t.store(), doubled, t.variantGid(), 1, t.mainGid());
        // Control: a +1 on an order with no Shopify restock is not a candidate.
        UUID clean = order(t, "{\"id\": 2, \"refunds\": []}");
        String other = returnedPiece(t, clean);
        TenantContext.runAs(t.tenant(), () -> returns.restock(other, null, t.user()));
        awaitCount(t, "return_inspection", "status = 'applied'", 2);

        String script = java.nio.file.Files.readString(java.nio.file.Path.of(
            "scripts/ops/2026-11-05-s1b-restock-double-count-candidates.sql"));
        String query = script.substring(script.indexOf("WITH main AS"), script.indexOf("ROLLBACK;")).trim();
        query = query.substring(0, query.lastIndexOf(';'));
        List<Map<String, Object>> rows = jdbc.queryForList(query).stream()
            .filter(r -> t.variant().equals(r.get("variant_id"))).toList();
        assertThat(rows).singleElement().satisfies(r -> assertThat(r)
            .containsEntry("order_id", doubled).containsEntry("plus_ones", 1L).containsEntry("double_counted", 1L));
    }

    // ── fixtures ────────────────────────────────────────────────────────────────────────────────────────────

    private Long restockedUnits(T t, UUID order) {
        return jdbc.queryForObject("SELECT shopify_restocked_units(o.raw, o.tenant_id, o.id, ?, ?) FROM orders o WHERE o.id = ?",
            Long.class, t.variantGid(), t.mainGid(), order);
    }

    private List<Map<String, Object>> restockedTwice(T t) {
        return TenantContext.runAs(t.tenant(), () -> exceptions.detectAllOpen()).stream()
            .filter(e -> "restocked_twice".equals(e.get("type"))).toList();
    }

    private int count(T t, String triggerType, String predicate) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM shopify_inventory_adjustments WHERE tenant_id = ? " +
            "AND trigger_type = ? AND " + predicate, Integer.class, t.tenant(), triggerType);
    }

    private void awaitCount(T t, String triggerType, String predicate, int n) throws Exception {
        long deadline = System.currentTimeMillis() + 10_000;
        while (count(t, triggerType, predicate) < n) {
            if (System.currentTimeMillis() > deadline) throw new AssertionError("never reached " + n + " × " + predicate);
            Thread.sleep(25);
        }
        Thread.sleep(150);
    }

    private Map<String, Object> receivingClaim(T t, UUID session) {
        return jdbc.queryForMap("SELECT status, delta FROM shopify_inventory_adjustments WHERE tenant_id = ? " +
            "AND trigger_type = 'receiving_session' AND trigger_id = ?", t.tenant(), session.toString());
    }

    private void awaitReceivingStatus(T t, UUID session, String status) throws Exception {
        long deadline = System.currentTimeMillis() + 10_000;
        while (jdbc.queryForObject("SELECT COUNT(*) FROM shopify_inventory_adjustments WHERE tenant_id = ? " +
                "AND trigger_type = 'receiving_session' AND trigger_id = ? AND status = ?", Integer.class,
                t.tenant(), session.toString(), status) < 1) {
            if (System.currentTimeMillis() > deadline) throw new AssertionError("receiving claim never " + status);
            Thread.sleep(25);
        }
        Thread.sleep(150);
    }

    private static String refundRaw(T t, int qty, String locationId) {
        return "{\"id\": 1, \"refunds\": [{\"id\": 9, \"refund_line_items\": [{\"quantity\": " + qty +
            ", \"restock_type\": \"return\"" + (locationId == null ? "" : ", \"location_id\": " + locationId) +
            ", \"line_item\": {\"variant_id\": " + t.shopifyVariantId() + "}}]}]}";
    }

    private UUID order(T t, String raw) {
        int k = seq.incrementAndGet();
        UUID order = jdbc.queryForObject(
            "INSERT INTO orders (tenant_id, store_id, external_id, number, status, payment_method, placed_at, raw) " +
            "VALUES (?, ?, ?, ?, 'new'::order_status, 'cod', now(), ?::jsonb) RETURNING id",
            UUID.class, t.tenant(), t.store(), "gid://shopify/Order/SR" + k, "#SR" + k, raw);
        jdbc.update("INSERT INTO order_items (tenant_id, order_id, variant_id, quantity) VALUES (?, ?, ?, 2)",
            t.tenant(), order, t.variant());
        return order;
    }

    /** A returned-to-sender / return leg of the order — what a leg-level Return To Receive alert names. */
    private UUID returnLeg(T t, UUID order) {
        return jdbc.queryForObject(
            "INSERT INTO shipments (tenant_id, order_id, provider, tracking_number, internal_state, shipment_leg) " +
            "VALUES (?, ?, 'bosta', ?, 'returned', 'return') RETURNING id",
            UUID.class, t.tenant(), order, String.valueOf(5_100_000_000L + seq.incrementAndGet()));
    }

    private String returnedPiece(T t, UUID order) {
        int k = seq.incrementAndGet();
        String id = String.format("01RESTOCKUNIT%013d", k);
        jdbc.update("INSERT INTO pieces (id, tenant_id, variant_id, status, barcode, short_code, current_location_id, current_order_id) " +
            "VALUES (?, ?, ?, 'return_pending_inspection'::piece_status, ?, ?, ?, ?)",
            id, t.tenant(), t.variant(), "SU-" + k, String.format("U%07d", k), t.main(), order);
        return id;
    }

    private T tenant(String name, Long shopifyVariant) {
        UUID tenant = UUID.randomUUID(), store = UUID.randomUUID(), location = UUID.randomUUID(), user = UUID.randomUUID();
        UUID product = UUID.randomUUID(), variant = UUID.randomUUID();
        long shopifyVariantId = shopifyVariant != null ? shopifyVariant : 7_200_000_000L + seq.incrementAndGet();
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
