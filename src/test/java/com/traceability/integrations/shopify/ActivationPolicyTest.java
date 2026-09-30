package com.traceability.integrations.shopify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.traceability.inventory.ShopifyCatalogActivationService;
import com.traceability.inventory.ShopifyInventoryService;
import com.traceability.tenancy.TenantContext;
import org.junit.jupiter.api.*;
import org.mockito.InOrder;
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

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Activation policy + stored item ids (activation perf, Part A).
 *
 *   ap1 — connect import: only ACTIVE products' variants are sent to activation; the draft and
 *         archived variants are never sent (batch or single); the import stores every variant's
 *         inventory item id, so activation makes no resolve call at all.
 *   ap2 — lazy guard: an increment on a never-activated DRAFT variant activates it at the Traced
 *         location first, then adjusts — in that order, same idempotency key as the catalog
 *         activation.
 *   ap3 — lazy guard: activation fails → no adjust call, the claim row is 'failed'.
 *   ap4 — item id stored → an increment makes no resolve call.
 *   ap5 — item id missing → resolved exactly once and written back; the next increment for the
 *         same variant reads it from the column.
 *   ap6 — the products webhook stores the REST payload's inventory_item_id as the GID.
 *   ap7 — seed: a DRAFT variant with Traced stock (never activated at connect) is activated at the
 *         Traced location right before its seed adjust, in that order.
 *   ap8 — seed: that activation fails → no adjust, the initial_seed row is 'failed'.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ActivationPolicyTest {

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

    private static final ObjectMapper M = new ObjectMapper();
    private static final String SCOPES = "read_orders,write_inventory,read_products,write_locations,read_locations";

    @Autowired JdbcTemplate jdbc;
    @Autowired ShopifyImportJob importJob;
    @Autowired ShopifyInventoryService inventoryService;
    @Autowired ShopifyWebhookProcessorJob webhookProcessor;
    @Autowired com.traceability.inventory.ShopifyInventoryReconcileService seedService;
    @MockBean ShopifyGateway shopifyGateway;
    @MockBean ShopifyTokenProvider tokenProvider;
    @MockBean ShopifyLocationGateway shopifyLocations;

    record Store(UUID tenant, UUID id, String shop, UUID location, String tracedGid) {}

    @BeforeEach
    void reset() {
        Mockito.reset(shopifyGateway, tokenProvider);
        when(tokenProvider.getValidToken(any())).thenReturn("tok");
        when(shopifyGateway.fetchOrdersPage(anyString(), anyString(), any(), anyString()))
            .thenReturn(new ShopifyGateway.OrderPage(List.of(), false, null));
        when(shopifyGateway.activateInventoryItems(anyString(), anyString(), anyString(), anyList())).thenAnswer(inv -> {
            List<ShopifyGateway.ActivationRequest> reqs = inv.getArgument(3);
            return reqs.stream().map(r -> new ShopifyGateway.ActivationResult(r.inventoryItemGid(), null)).toList();
        });
    }

    @AfterEach
    void clear() { TenantContext.clear(); }

    @Test
    void ap1_connect_activatesOnlyActiveProductsVariants_andStoresItemIds() {
        Store s = store("ap1");
        String p = "gid://shopify/Product/" + s.shop(), v = "gid://shopify/ProductVariant/" + s.shop();
        when(shopifyGateway.fetchProductsPage(eq(s.shop()), anyString(), any())).thenReturn(new ShopifyGateway.ProductPage(List.of(
            new ShopifyGateway.Product(p + "-a", "Active", "active", null, List.of(
                variant(v + "-a1", "item-a1"), variant(v + "-a2", "item-a2"))),
            new ShopifyGateway.Product(p + "-d", "Draft", "draft", null, List.of(variant(v + "-d1", "item-d1"))),
            new ShopifyGateway.Product(p + "-x", "Archived", "archived", null, List.of(variant(v + "-x1", "item-x1")))),
            false, null));

        importJob.run(s.id(), s.tenant());

        assertThat(activatedItems(s)).containsExactlyInAnyOrder("item-a1", "item-a2");
        verify(shopifyGateway, never()).activateInventoryItem(anyString(), anyString(), anyString(), anyString(), anyString());
        verify(shopifyGateway, never()).activateInventoryItems(anyString(), anyString(),
            argThat(loc -> !s.tracedGid().equals(loc)), anyList());
        verify(shopifyGateway, never()).resolveInventoryItemIds(anyString(), anyString(), anyList());
        verify(shopifyGateway, never()).resolveInventoryItemId(anyString(), anyString(), anyString());
        assertThat(jdbc.queryForList(
            "SELECT shopify_inventory_item_id FROM variants WHERE tenant_id = ? ORDER BY external_id", String.class, s.tenant()))
            .as("the import stores every variant's item id, every status")
            .containsExactly("item-a1", "item-a2", "item-d1", "item-x1");
    }

    @Test
    void ap2_lazyGuard_draftVariant_activateThenAdjust_inOrder() throws Exception {
        Store s = store("ap2");
        UUID variant = variantRow(s, "draft", "ap2-v", "gid://shopify/InventoryItem/ap2");
        UUID session = UUID.randomUUID();

        inventoryService.onReceivingSessionClose(s.tenant(), session, s.location(), Map.of(variant, 2)).get(5, TimeUnit.SECONDS);

        InOrder order = inOrder(shopifyGateway);
        order.verify(shopifyGateway).activateInventoryItem(eq(s.shop()), eq("tok"), eq("gid://shopify/InventoryItem/ap2"),
            eq(s.tracedGid()), eq(ShopifyCatalogActivationService.activationKey(s.tenant(), variant, s.tracedGid())));
        order.verify(shopifyGateway).adjustInventoryQuantities(eq(s.shop()), eq("tok"), eq("gid://shopify/InventoryItem/ap2"),
            eq(s.tracedGid()), eq(2), eq("received"), anyString());
        assertThat(claimStatus(session, variant)).isEqualTo("applied");
    }

    @Test
    void ap3_lazyGuard_activationFails_noAdjust_claimFailed() throws Exception {
        Store s = store("ap3");
        UUID variant = variantRow(s, "archived", "ap3-v", "gid://shopify/InventoryItem/ap3");
        doThrow(new ShopifyException("inventoryActivate failed: Inventory item does not exist"))
            .when(shopifyGateway).activateInventoryItem(anyString(), anyString(), anyString(), anyString(), anyString());
        UUID session = UUID.randomUUID();

        inventoryService.onReceivingSessionClose(s.tenant(), session, s.location(), Map.of(variant, 1)).get(5, TimeUnit.SECONDS);

        verify(shopifyGateway, never()).adjustInventoryQuantities(any(), any(), any(), any(), anyInt(), any(), any());
        assertThat(claimStatus(session, variant)).isEqualTo("failed");
        assertThat(jdbc.queryForObject("SELECT error FROM shopify_inventory_adjustments WHERE trigger_id = ? AND variant_id = ?",
            String.class, session.toString(), variant)).contains("Activation").contains("adjust not sent");
    }

    @Test
    void ap4_storedItemId_incrementMakesNoResolveCall() throws Exception {
        Store s = store("ap4");
        UUID variant = variantRow(s, "active", "ap4-v", "gid://shopify/InventoryItem/ap4");

        inventoryService.onReceivingSessionClose(s.tenant(), UUID.randomUUID(), s.location(), Map.of(variant, 1)).get(5, TimeUnit.SECONDS);

        verify(shopifyGateway, never()).resolveInventoryItemId(anyString(), anyString(), anyString());
        verify(shopifyGateway, never()).resolveInventoryItemIds(anyString(), anyString(), anyList());
        verify(shopifyGateway).adjustInventoryQuantities(eq(s.shop()), eq("tok"), eq("gid://shopify/InventoryItem/ap4"),
            eq(s.tracedGid()), eq(1), eq("received"), anyString());
    }

    @Test
    void ap5_missingItemId_resolvedOnce_writtenBack() throws Exception {
        Store s = store("ap5");
        UUID variant = variantRow(s, "active", "ap5-v", null);
        String variantGid = "gid://shopify/ProductVariant/" + s.shop() + "-ap5-v";
        when(shopifyGateway.resolveInventoryItemId(eq(s.shop()), eq("tok"), eq(variantGid))).thenReturn("gid://shopify/InventoryItem/ap5");

        inventoryService.onReceivingSessionClose(s.tenant(), UUID.randomUUID(), s.location(), Map.of(variant, 1)).get(5, TimeUnit.SECONDS);
        assertThat(jdbc.queryForObject("SELECT shopify_inventory_item_id FROM variants WHERE id = ?", String.class, variant))
            .isEqualTo("gid://shopify/InventoryItem/ap5");
        inventoryService.onReceivingSessionClose(s.tenant(), UUID.randomUUID(), s.location(), Map.of(variant, 4)).get(5, TimeUnit.SECONDS);

        verify(shopifyGateway, times(1)).resolveInventoryItemId(anyString(), anyString(), anyString());
        verify(shopifyGateway, times(2)).adjustInventoryQuantities(eq(s.shop()), eq("tok"),
            eq("gid://shopify/InventoryItem/ap5"), eq(s.tracedGid()), anyInt(), eq("received"), anyString());
    }

    @Test
    void ap6_productsWebhook_storesInventoryItemGid() {
        Store s = store("ap6");
        ObjectNode payload = M.createObjectNode().put("id", 777).put("admin_graphql_api_id", "gid://shopify/Product/777")
            .put("title", "Webhook").put("status", "draft");
        payload.putArray("variants").addObject().put("id", 778).put("admin_graphql_api_id", "gid://shopify/ProductVariant/778")
            .put("sku", "W").put("title", "V").put("price", "1.00").put("inventory_item_id", 4242L);
        UUID event = jdbc.queryForObject(
            "INSERT INTO shopify_webhook_events (tenant_id, topic, shop_domain, webhook_id, payload_raw) " +
            "VALUES (?, 'products/create', ?, ?, ?::jsonb) RETURNING id", UUID.class,
            s.tenant(), s.shop(), "wh-" + UUID.randomUUID(), payload.toString());

        webhookProcessor.process(event, s.tenant());

        assertThat(jdbc.queryForObject("SELECT shopify_inventory_item_id FROM variants WHERE tenant_id = ? AND external_id = ?",
            String.class, s.tenant(), "gid://shopify/ProductVariant/778")).isEqualTo("gid://shopify/InventoryItem/4242");
    }

    @Test
    void ap7_seed_draftCandidate_activatedBeforeItsAdjust() {
        Store s = store("ap7");
        UUID variant = variantRow(s, "draft", "ap7-v", "gid://shopify/InventoryItem/ap7");
        piece(s, variant, "ap7-1"); piece(s, variant, "ap7-2");

        TenantContext.set(s.tenant());
        var result = seedService.apply(null);
        TenantContext.clear();

        assertThat(result.seeded()).isEqualTo(1);
        InOrder order = inOrder(shopifyGateway);
        order.verify(shopifyGateway).activateInventoryItem(eq(s.shop()), eq("tok"), eq("gid://shopify/InventoryItem/ap7"),
            eq(s.tracedGid()), eq(ShopifyCatalogActivationService.activationKey(s.tenant(), variant, s.tracedGid())));
        order.verify(shopifyGateway).adjustInventoryQuantities(eq(s.shop()), eq("tok"), eq("gid://shopify/InventoryItem/ap7"),
            eq(s.tracedGid()), eq(2), anyString(), anyString());
    }

    @Test
    void ap8_seed_activationFails_noAdjust_rowFailed() {
        Store s = store("ap8");
        UUID variant = variantRow(s, "archived", "ap8-v", "gid://shopify/InventoryItem/ap8");
        piece(s, variant, "ap8-1");
        doThrow(new ShopifyException("inventoryActivate failed: Inventory item does not exist"))
            .when(shopifyGateway).activateInventoryItem(anyString(), anyString(), anyString(), anyString(), anyString());

        TenantContext.set(s.tenant());
        var result = seedService.apply(null);
        TenantContext.clear();

        assertThat(result.seeded()).isZero();
        assertThat(result.failed()).isEqualTo(1);
        verify(shopifyGateway, never()).adjustInventoryQuantities(any(), any(), any(), any(), anyInt(), any(), any());
        assertThat(jdbc.queryForObject("SELECT status || '|' || error FROM shopify_inventory_adjustments " +
            "WHERE trigger_type = 'initial_seed' AND variant_id = ?", String.class, variant))
            .startsWith("failed|").contains("adjust not sent");
    }

    private void piece(Store s, UUID variant, String key) {
        String id = String.format("01AP%022d", Math.abs((long) (s.shop() + key).hashCode()));
        jdbc.update("INSERT INTO pieces (id, tenant_id, variant_id, status, barcode, short_code, current_location_id) " +
            "VALUES (?, ?, ?, 'available'::piece_status, ?, ?, ?)",
            id, s.tenant(), variant, "AP-" + id, "A" + id.substring(id.length() - 6), s.location());
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static ShopifyGateway.Variant variant(String gid, String itemGid) {
        return new ShopifyGateway.Variant(gid, "S-" + gid.hashCode(), "T", new BigDecimal("10.00"), itemGid);
    }

    private Store store(String name) {
        UUID tenant = UUID.randomUUID(), store = UUID.randomUUID(), location = UUID.randomUUID();
        String shop = name + "-" + tenant.toString().substring(0, 6) + ".myshopify.com";
        String traced = "gid://shopify/Location/" + shop;
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?)", tenant, "Tenant " + name);
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status, import_status, access_token_scopes, catalog_backfilled_at) " +
                    "VALUES (?, ?, 'shopify', ?, 'connected', 'completed', ?, now())", store, tenant, shop, SCOPES);
        jdbc.update("INSERT INTO locations (id, tenant_id, name, shopify_location_id, shopify_sync_status, is_fulfillment) " +
                    "VALUES (?, ?, 'Main Warehouse', ?, 'linked', true)", location, tenant, traced);
        return new Store(tenant, store, shop, location, traced);
    }

    private UUID variantRow(Store s, String productStatus, String key, String itemGid) {
        UUID product = UUID.randomUUID(), variant = UUID.randomUUID();
        jdbc.update("INSERT INTO products (id, tenant_id, store_id, external_id, title, status) VALUES (?, ?, ?, ?, 'P', ?)",
            product, s.tenant(), s.id(), "gid://shopify/Product/" + s.shop() + "-" + key, productStatus);
        jdbc.update("INSERT INTO variants (id, tenant_id, product_id, external_id, title, shopify_inventory_item_id) " +
                    "VALUES (?, ?, ?, ?, 'V', ?)",
            variant, s.tenant(), product, "gid://shopify/ProductVariant/" + s.shop() + "-" + key, itemGid);
        return variant;
    }

    private String claimStatus(UUID session, UUID variant) {
        return jdbc.queryForObject("SELECT status FROM shopify_inventory_adjustments " +
            "WHERE trigger_type = 'receiving_session' AND trigger_id = ? AND variant_id = ?",
            String.class, session.toString(), variant);
    }

    @SuppressWarnings("unchecked")
    private List<String> activatedItems(Store s) {
        return Mockito.mockingDetails(shopifyGateway).getInvocations().stream()
            .filter(i -> i.getMethod().getName().equals("activateInventoryItems") && s.shop().equals(i.getArgument(0)))
            .flatMap(i -> ((List<ShopifyGateway.ActivationRequest>) i.getArgument(3)).stream())
            .map(ShopifyGateway.ActivationRequest::inventoryItemGid)
            .toList();
    }
}
