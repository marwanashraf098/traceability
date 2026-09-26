package com.traceability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.traceability.integrations.bosta.BostaDelivery;
import com.traceability.integrations.bosta.BostaGateway;
import com.traceability.integrations.bosta.BostaIngestionHelper;
import com.traceability.integrations.bosta.BostaWebhookJob;
import com.traceability.integrations.shopify.ShopifyException;
import com.traceability.integrations.shopify.ShopifyGateway;
import com.traceability.integrations.shopify.ShopifyTokenProvider;
import com.traceability.integrations.shopify.StoreRepository;
import com.traceability.inventory.*;
import com.traceability.security.EncryptionService;
import com.traceability.tenancy.TenantAwareDataSource;
import com.traceability.tenancy.TenantContext;
import org.jobrunr.scheduling.JobScheduler;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.lang.reflect.Constructor;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Step 5a — the fourth named decrement: pushExchangeDispatch, −1 per replacement piece of an
 * INTERNAL exchange order ('internal:exchange:%' + its exchanges row), exactly once, when the
 * piece first leaves Traced custody (packed / awaiting_pickup → with_courier or delivered),
 * from either writer: PickupSessionService.closeSession or BostaWebhookJob's piece loop
 * (type-30 forward leg through ExchangeStateInterpreter, via the real ingest pipeline).
 *
 * E1–E5 exactly once; N1–N6 never fires; R1–R2 reversal; F1 failure + re-push; G1 the
 * positive-only guard; X1 cross-tenant on a real app_user connection.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ExchangeDispatchDecrementTest {

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

    static final String SHOP_DOMAIN  = "exchange-dispatch.myshopify.com";
    static final String TRACED_GID   = "gid://shopify/Location/777";
    static final String VARIANT_GID  = "gid://shopify/ProductVariant/5501";
    static final String ITEM_GID     = "gid://shopify/InventoryItem/6601";
    static final String RAW_API_KEY  = "exchange-dispatch-key";

    @MockBean JobScheduler         jobScheduler;
    @MockBean ShopifyGateway       shopifyGateway;
    @MockBean ShopifyTokenProvider tokenProvider;
    @MockBean BostaGateway         bostaGateway;

    @Autowired JdbcTemplate            jdbc;
    @Autowired ObjectMapper            mapper;
    @Autowired EncryptionService       encryptionService;
    @Autowired ShopifyInventoryService shopifyInventory;
    @Autowired PickupSessionService    pickups;
    @Autowired BostaWebhookJob         webhookJob;
    @Autowired BostaIngestionHelper    ingestionHelper;
    @Autowired FulfillService          fulfill;
    @Autowired InventoryLedger         ledger;
    @Autowired ReturnSessionService    returnSessions;
    @Autowired ExceptionService        exceptions;
    @Autowired PlatformTransactionManager txm;

    UUID tenantId, storeId, variantId, fulfillmentLoc, showroomLoc, actorId;
    UUID tenantB, storeB, variantB, locB;

    @BeforeAll
    void setup() {
        tenantId = UUID.randomUUID(); storeId = UUID.randomUUID(); actorId = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, 'Exchange Dispatch Tenant')", tenantId);
        jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role) VALUES (?, ?, 'Worker', ?, 'h', 'owner')",
            actorId, tenantId, "w-" + actorId + "@dispatch.test");
        jdbc.update("INSERT INTO stores (id, tenant_id, shop_domain, status, import_status, access_token_scopes) " +
            "VALUES (?, ?, ?, 'connected', 'idle', 'read_orders,write_inventory,read_products,write_locations,read_locations')",
            storeId, tenantId, SHOP_DOMAIN);
        UUID productId = UUID.randomUUID();
        jdbc.update("INSERT INTO products (id, tenant_id, store_id, external_id, title, status) VALUES (?, ?, ?, 'gid://shopify/Product/55', 'Linen Shirt', 'active')",
            productId, tenantId, storeId);
        variantId = UUID.randomUUID();
        jdbc.update("INSERT INTO variants (id, tenant_id, product_id, external_id, title, sku) VALUES (?, ?, ?, ?, 'White / M', 'LIN-W-M')",
            variantId, tenantId, productId, VARIANT_GID);
        fulfillmentLoc = UUID.randomUUID();
        jdbc.update("INSERT INTO locations (id, tenant_id, name, shopify_location_id, shopify_sync_status, is_fulfillment) " +
            "VALUES (?, ?, 'Main Warehouse', ?, 'linked', true)", fulfillmentLoc, tenantId, TRACED_GID);
        showroomLoc = UUID.randomUUID();
        jdbc.update("INSERT INTO locations (id, tenant_id, name, shopify_location_id, shopify_sync_status, is_fulfillment) " +
            "VALUES (?, ?, 'Showroom', 'gid://shopify/Location/778', 'linked', false)", showroomLoc, tenantId);
        jdbc.update("INSERT INTO courier_accounts (id, tenant_id, provider, api_key_encrypted, webhook_secret, status) " +
            "VALUES (gen_random_uuid(), ?, 'bosta', ?, 'dispatch-hash', 'active')", tenantId, encryptionService.encrypt(RAW_API_KEY));

        tenantB = UUID.randomUUID(); storeB = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, 'Exchange Dispatch B')", tenantB);
        jdbc.update("INSERT INTO stores (id, tenant_id, shop_domain, status, import_status, access_token_scopes) " +
            "VALUES (?, ?, 'dispatch-b.myshopify.com', 'connected', 'idle', 'read_orders,write_inventory')", storeB, tenantB);
        UUID productB = UUID.randomUUID();
        jdbc.update("INSERT INTO products (id, tenant_id, store_id, external_id, title, status) VALUES (?, ?, ?, 'gid://shopify/Product/66', 'Tote', 'active')",
            productB, tenantB, storeB);
        variantB = UUID.randomUUID();
        jdbc.update("INSERT INTO variants (id, tenant_id, product_id, external_id, title, sku) VALUES (?, ?, ?, 'gid://shopify/ProductVariant/6601', 'Black', 'TOTE')",
            variantB, tenantB, productB);
        locB = UUID.randomUUID();
        jdbc.update("INSERT INTO locations (id, tenant_id, name, shopify_location_id, shopify_sync_status, is_fulfillment) " +
            "VALUES (?, ?, 'B Warehouse', 'gid://shopify/Location/779', 'linked', true)", locB, tenantB);
    }

    @BeforeEach
    void stubs() {
        reset(shopifyGateway, tokenProvider);
        when(tokenProvider.getValidToken(any())).thenReturn("shpat-test");
        when(shopifyGateway.resolveInventoryItemId(anyString(), anyString(), anyString())).thenReturn(ITEM_GID);
    }

    @AfterEach
    void cleanup() {
        TenantContext.clear();
        for (UUID t : List.of(tenantId, tenantB)) {
            jdbc.update("DELETE FROM exception_resolutions WHERE tenant_id = ?", t);
            jdbc.update("DELETE FROM shopify_inventory_adjustments WHERE tenant_id = ?", t);
            jdbc.update("DELETE FROM piece_events WHERE tenant_id = ?", t);
            jdbc.update("DELETE FROM return_session_items WHERE tenant_id = ?", t);
            jdbc.update("DELETE FROM return_session_shipments WHERE tenant_id = ?", t);
            jdbc.update("DELETE FROM return_sessions WHERE tenant_id = ?", t);
            jdbc.update("DELETE FROM pickup_shipments WHERE tenant_id = ?", t);
            jdbc.update("DELETE FROM pickups WHERE tenant_id = ?", t);
            jdbc.update("UPDATE exchanges SET outbound_order_id = NULL WHERE tenant_id = ?", t);
            jdbc.update("DELETE FROM exchanges WHERE tenant_id = ?", t);
            jdbc.update("UPDATE pieces SET current_order_id = NULL WHERE tenant_id = ?", t);
            jdbc.update("DELETE FROM allocations WHERE tenant_id = ?", t);
            jdbc.update("DELETE FROM pieces WHERE tenant_id = ?", t);
            jdbc.update("DELETE FROM shipment_status_history WHERE tenant_id = ?", t);
            jdbc.update("DELETE FROM shipments WHERE tenant_id = ?", t);
            jdbc.update("DELETE FROM webhook_events WHERE tenant_id = ?", t);
            jdbc.update("DELETE FROM unlinked_bosta_deliveries WHERE tenant_id = ?", t);
            jdbc.update("DELETE FROM order_items WHERE tenant_id = ?", t);
            jdbc.update("DELETE FROM orders WHERE tenant_id = ?", t);
        }
    }

    // ── E: exactly once ─────────────────────────────────────────────────────────

    @Test
    void e1_pickupCloseThenWebhook_oneDecrement() throws Exception {
        Fixture f = exchangeOrder("packed", fulfillmentLoc);
        closePickup(f);
        assertThat(pieceStatus(f.piece())).isEqualTo("with_courier");
        awaitRows(f.piece(), 1);

        ingestExchange(f, "picked_up", 21);          // piece already with_courier → no-op branch
        ingestExchange(f, "exchanged_returned", 46); // with_courier → delivered: not a departure
        assertThat(pieceStatus(f.piece())).isEqualTo("delivered");

        settle();
        assertDecrementedOnce(f.piece());
    }

    @Test
    void e2_webhookThenPickup_oneDecrement() throws Exception {
        Fixture f = exchangeOrder("awaiting_pickup", fulfillmentLoc);
        ingestExchange(f, "picked_up", 21);
        assertThat(pieceStatus(f.piece())).isEqualTo("with_courier");
        awaitRows(f.piece(), 1);

        // The pickup writer can't move it again (not packed) — and if both writers ever fired
        // for the same piece, the per-piece claim would still allow one call only.
        UUID pickup = pickups.openSession(tenantId, actorId, LocalDate.now(), null, null);
        assertThat(pickups.scan(tenantId, pickup, actorId, f.tracking()).outcome())
            .isEqualTo(PickupSessionService.ScanOutcome.NOT_PACKED);
        shopifyInventory.onExchangeReplacementDispatched(tenantId, f.piece()).get(5, TimeUnit.SECONDS);

        settle();
        assertDecrementedOnce(f.piece());
    }

    @Test
    void e3_duplicateWebhooks_oneDecrement() throws Exception {
        Fixture f = exchangeOrder("packed", fulfillmentLoc);
        long event = ingestExchange(f, "picked_up", 21);
        webhookJob.process(event, tenantId);         // exact redelivery → duplicate
        TenantContext.set(tenantId);
        ingestExchange(f, "picked_up", 22);          // same state again, later updatedAt → no-op branch
        ingestExchange(f, "out_for_exchange", 23);   // no piece move

        settle();
        assertDecrementedOnce(f.piece());
    }

    @Test
    void e4_straightPackedToDelivered_boostaSkippedPickedUp_oneDecrement() throws Exception {
        Fixture f = exchangeOrder("packed", fulfillmentLoc);
        ingestExchange(f, "exchanged_returned", 46);
        assertThat(pieceStatus(f.piece())).isEqualTo("delivered");

        awaitRows(f.piece(), 1);
        settle();
        assertDecrementedOnce(f.piece());
    }

    @Test
    void e5_concurrentTriggersForOnePiece_claimAllowsOneCall() throws Exception {
        Fixture f = exchangeOrder("packed", fulfillmentLoc);
        jdbc.update("UPDATE pieces SET status = 'with_courier' WHERE id = ?", f.piece());
        var a = shopifyInventory.onExchangeReplacementDispatched(tenantId, f.piece());
        var b = shopifyInventory.onExchangeReplacementDispatched(tenantId, f.piece());
        a.get(5, TimeUnit.SECONDS);
        b.get(5, TimeUnit.SECONDS);
        settle();
        assertDecrementedOnce(f.piece());
    }

    // ── N: never fires ──────────────────────────────────────────────────────────

    @Test
    void n1_normalShopifyOrder_noDecrement() {
        Fixture f = order("gid://shopify/Order/" + ThreadLocalRandom.current().nextLong(1_000_000, 9_999_999), false, "packed", fulfillmentLoc);
        closePickup(f);
        assertThat(pieceStatus(f.piece())).isEqualTo("with_courier");
        settle();
        assertNoDecrement(f.piece());
    }

    @Test
    void n2_demoOrder_noDecrement_evenWithAnExchangesRow() {
        Fixture f = order("DEMO-ORDER-X" + ThreadLocalRandom.current().nextInt(1000), true, "packed", fulfillmentLoc);
        closePickup(f);
        settle();
        assertNoDecrement(f.piece());
    }

    @Test
    void n3_onPack_noDecrement() {
        Fixture f = exchangeOrder(null, fulfillmentLoc);   // order + shipment 'created', piece available, no allocation
        TenantContext.set(tenantId);
        assertThat(fulfill.scan(f.orderId(), "PC-" + f.piece(), actorId).success()).isTrue();
        fulfill.complete(f.orderId(), actorId);
        TenantContext.clear();
        assertThat(pieceStatus(f.piece())).isIn("packed", "awaiting_pickup");
        settle();
        assertNoDecrement(f.piece());
    }

    @Test
    void n4_cancelOrUnpick_packedToAvailable_noDecrement() {
        Fixture f = exchangeOrder("packed", fulfillmentLoc);
        TenantContext.set(tenantId);
        ledger.transition(f.piece(), PieceStatus.PACKED, PieceStatus.AVAILABLE, "unpicked", actorId,
            new TransitionContext(f.orderId(), null, fulfillmentLoc, f.orderId(), null));
        TenantContext.clear();
        jdbc.update("UPDATE allocations SET status = 'released' WHERE piece_id = ?", f.piece());
        settle();
        assertNoDecrement(f.piece());
    }

    @Test
    void n5_noOpTransitions_noDecrement() throws Exception {
        Fixture f = exchangeOrder("packed", fulfillmentLoc);
        jdbc.update("UPDATE pieces SET status = 'with_courier' WHERE id = ?", f.piece());   // already out
        ingestExchange(f, "picked_up", 21);          // current == target → skipped
        settle();
        assertNoDecrement(f.piece());
    }

    @Test
    void n6_pieceNotAtTheFulfillmentLocation_noDecrement() {
        Fixture f = exchangeOrder("packed", showroomLoc);
        closePickup(f);
        assertThat(pieceStatus(f.piece())).isEqualTo("with_courier");
        settle();
        assertNoDecrement(f.piece());
    }

    // ── R: reversal ─────────────────────────────────────────────────────────────

    @Test
    void r1_leavesThenComesBackAndIsRestocked_netZero() throws Exception {
        Fixture f = exchangeOrder("packed", fulfillmentLoc);
        closePickup(f);
        awaitRows(f.piece(), 1);

        TenantContext.set(tenantId);
        UUID s = returnSessions.createSession(null, actorId);
        returnSessions.scan(s, "PC-" + f.piece(), fulfillmentLoc, actorId);
        returnSessions.disposition(s, f.piece(), "restock", null, fulfillmentLoc, actorId);
        TenantContext.clear();
        awaitTrigger("return_inspection", f.piece());

        assertThat(sumAppliedDeltas(f.piece())).as("-1 on departure, +1 on restock").isZero();
        verify(shopifyGateway, times(1)).pushExchangeDispatch(anyString(), anyString(), anyString(), anyString(), eq(-1), anyString(), anyString());
        verify(shopifyGateway, times(1)).adjustInventoryQuantities(anyString(), anyString(), anyString(), anyString(), eq(1), anyString(), anyString());
    }

    @Test
    void r2_leavesThenComesBackDamaged_minusOneOnly() throws Exception {
        Fixture f = exchangeOrder("packed", fulfillmentLoc);
        closePickup(f);
        awaitRows(f.piece(), 1);

        TenantContext.set(tenantId);
        UUID s = returnSessions.createSession(null, actorId);
        returnSessions.scan(s, "PC-" + f.piece(), fulfillmentLoc, actorId);
        returnSessions.disposition(s, f.piece(), "damaged", "Torn seam", fulfillmentLoc, actorId);
        TenantContext.clear();
        settle();

        assertThat(sumAppliedDeltas(f.piece())).isEqualTo(-1);
        verify(shopifyGateway, never()).adjustInventoryQuantities(anyString(), anyString(), anyString(), anyString(), anyInt(), anyString(), anyString());
    }

    // ── F: failure → exception → re-push ───────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void f1_shopifyError_failed_exceptionShown_repushSucceedsWithTheSameIdempotencyKey() throws Exception {
        doThrow(new ShopifyException("Exchange dispatch failed: inventory item not stocked"))
            .when(shopifyGateway).pushExchangeDispatch(anyString(), anyString(), anyString(), anyString(), anyInt(), anyString(), anyString());
        Fixture f = exchangeOrder("packed", fulfillmentLoc);
        closePickup(f);
        awaitRows(f.piece(), 1);
        assertThat(row(f.piece()).get("status")).isEqualTo("failed");

        TenantContext.set(tenantId);
        List<Map<String, Object>> items = (List<Map<String, Object>>) exceptions
            .listExceptions("void_hold_sync_failed", null, 0, 50).get("items");
        assertThat(items).hasSize(1);
        assertThat(items.get(0).get("trigger_type")).isEqualTo("exchange_dispatch");
        assertThat(items.get(0).get("severity")).isEqualTo("CRITICAL");
        assertThat((String) items.get(0).get("descriptionEn")).contains("exchange replacement").contains("PC-" + f.piece());
        assertThat((String) items.get(0).get("descriptionAr")).contains("خصم قطعة الاستبدال");

        doNothing().when(shopifyGateway).pushExchangeDispatch(anyString(), anyString(), anyString(), anyString(), anyInt(), anyString(), anyString());
        shopifyInventory.repushFailedVoidOrHold("exchange_dispatch", f.piece());
        assertThat(row(f.piece()).get("status")).isEqualTo("applied");
        assertThat((List<?>) exceptions.listExceptions("void_hold_sync_failed", null, 0, 50).get("items")).isEmpty();
        TenantContext.clear();

        ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
        verify(shopifyGateway, times(2)).pushExchangeDispatch(eq(SHOP_DOMAIN), eq("shpat-test"), eq(ITEM_GID), eq(TRACED_GID),
            eq(-1), eq("traced://piece/" + f.piece()), keys.capture());
        assertThat(keys.getAllValues().get(0)).isEqualTo(keys.getAllValues().get(1));
    }

    // ── G: guards ───────────────────────────────────────────────────────────────

    @Test
    void g1_adjustInventoryQuantitiesStillRejectsNegative_andPushExchangeDispatchRejectsNonNegative() throws Exception {
        Class<?> impl = Class.forName("com.traceability.integrations.shopify.ShopifyHttpGateway");
        Constructor<?> c = impl.getDeclaredConstructor(org.springframework.web.client.RestClient.Builder.class,
            ObjectMapper.class, String.class, String.class, String.class);
        c.setAccessible(true);
        ShopifyGateway real = (ShopifyGateway) c.newInstance(org.springframework.web.client.RestClient.builder(),
            mapper, "2026-04", "id", "secret");

        assertThatThrownBy(() -> real.adjustInventoryQuantities("x.myshopify.com", "t", ITEM_GID, TRACED_GID, -1, "r", "k"))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("positive delta");
        assertThatThrownBy(() -> real.pushExchangeDispatch("x.myshopify.com", "t", ITEM_GID, TRACED_GID, 0, "u", "k"))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("negative delta");
        assertThatThrownBy(() -> real.pushExchangeDispatch("x.myshopify.com", "t", ITEM_GID, TRACED_GID, 1, "u", "k"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    // ── X: cross-tenant on a real app_user connection ─────────────────────────

    @Test
    void x1_otherTenantsContextCannotDecrementOrSee_sameTenantControlSucceeds() throws Exception {
        Fixture f = exchangeOrder("packed", fulfillmentLoc);
        jdbc.update("UPDATE pieces SET status = 'with_courier' WHERE id = ?", f.piece());

        DataSource appDs = new TenantAwareDataSource(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "app_user", "testpw"));
        JdbcTemplate appJdbc = new JdbcTemplate(appDs);
        DataSourceTransactionManager appTxm = new DataSourceTransactionManager(appDs);
        ShopifyInventoryService appUserService = new ShopifyInventoryService(appJdbc, appTxm, shopifyGateway, tokenProvider,
            mapper, new StoreRepository(appJdbc, appTxm));

        // Tenant B's context naming tenant A's piece: RLS hides the piece → nothing claimed, no call.
        appUserService.onExchangeReplacementDispatched(tenantB, f.piece()).get(5, TimeUnit.SECONDS);
        assertNoDecrement(f.piece());

        // Same-tenant positive control on the same app_user connection.
        appUserService.onExchangeReplacementDispatched(tenantId, f.piece()).get(5, TimeUnit.SECONDS);
        assertDecrementedOnce(f.piece());

        Integer visibleToB = TenantContext.runAs(tenantB, () -> new org.springframework.transaction.support.TransactionTemplate(appTxm)
            .execute(s -> appJdbc.queryForObject("SELECT COUNT(*) FROM shopify_inventory_adjustments WHERE trigger_type = 'exchange_dispatch'", Integer.class)));
        assertThat(visibleToB).as("tenant B sees none of A's rows").isZero();
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────

    record Fixture(UUID orderId, String tracking, String piece) {}

    private Fixture exchangeOrder(String pieceStatus, UUID location) {
        return order(null, true, pieceStatus, location);
    }

    /**
     * An order (internal exchange order when externalId is null) with one order line, a forward
     * shipment in 'created', and one piece — allocated at {@code pieceStatus} (packed /
     * awaiting_pickup), or merely available when null. withExchangeRow links an exchanges row.
     */
    private Fixture order(String externalId, boolean withExchangeRow, String pieceStatus, UUID location) {
        String tracking = String.valueOf(ThreadLocalRandom.current().nextLong(1_000_000_000L, 9_999_999_999L));
        String ext = externalId != null ? externalId : "internal:exchange:" + tracking;
        String orderStatus = pieceStatus == null ? "new" : "awaiting_pickup";
        UUID orderId = jdbc.queryForObject(
            "INSERT INTO orders (tenant_id, store_id, external_id, number, status, placed_at) " +
            "VALUES (?, ?, ?, ?, ?::order_status, now()) RETURNING id",
            UUID.class, tenantId, storeId, ext, "EXC-" + tracking, orderStatus);
        UUID item = UUID.randomUUID();
        jdbc.update("INSERT INTO order_items (id, tenant_id, order_id, variant_id, quantity) VALUES (?, ?, ?, ?, 1)",
            item, tenantId, orderId, variantId);
        jdbc.update("INSERT INTO shipments (tenant_id, order_id, provider, tracking_number, internal_state, shipment_leg) " +
            "VALUES (?, ?, 'bosta', ?, 'created'::shipment_internal_state, 'forward')", tenantId, orderId, tracking);
        String piece = UlidGenerator.generate();
        jdbc.update("INSERT INTO pieces (id, tenant_id, variant_id, barcode, short_code, status, current_location_id, current_order_id) " +
            "VALUES (?, ?, ?, ?, 'P' || LPAD((abs(hashtext(?)) % 999999 + 1)::text, 6, '0'), ?::piece_status, ?, ?)",
            piece, tenantId, variantId, "PC-" + piece, piece, pieceStatus == null ? "available" : pieceStatus, location,
            pieceStatus == null ? null : orderId);
        if (pieceStatus != null) {
            jdbc.update("INSERT INTO allocations (tenant_id, order_item_id, piece_id, status) VALUES (?, ?, ?, 'packed')",
                tenantId, item, piece);
        }
        if (withExchangeRow) {
            jdbc.update("INSERT INTO exchanges (tenant_id, tracking_number, status, outbound_order_id, outbound_variant_id, raw) " +
                "VALUES (?, ?, 'mapped', ?, ?, '{}'::jsonb)", tenantId, tracking, orderId, variantId);
        }
        return new Fixture(orderId, tracking, piece);
    }

    private void closePickup(Fixture f) {
        UUID pickup = pickups.openSession(tenantId, actorId, LocalDate.now(), null, null);
        assertThat(pickups.scan(tenantId, pickup, actorId, f.tracking()).outcome())
            .isEqualTo(PickupSessionService.ScanOutcome.ACCEPTED);
        pickups.closeSession(tenantId, pickup, actorId);
    }

    private int eventSeq = 0;

    /** A type-30 delivery whose latest completed timeline value is {@code value}, through the real ingest pipeline. */
    private long ingestExchange(Fixture f, String value, int stateCode) {
        ObjectNode raw = mapper.createObjectNode();
        raw.put("_id", "exc-" + f.tracking());
        raw.put("trackingNumber", f.tracking());
        raw.putObject("type").put("code", 30).put("value", "Exchange");
        raw.putObject("state").put("code", stateCode).put("value", value);
        raw.putObject("specs").putObject("packageDetails").put("itemsCount", 1).put("description", "Linen Shirt White M");
        raw.putObject("returnSpecs").putObject("packageDetails").put("itemsCount", 1).put("description", "Linen Shirt White L");
        Instant at = Instant.parse("2026-09-26T10:00:00Z").plusSeconds(60L * (++eventSeq));
        ArrayNode timeline = raw.putArray("timeline");
        timeline.addObject().put("value", value).put("done", true).put("date", at.toString());
        raw.put("updatedAt", at.toString());
        BostaDelivery d = new BostaDelivery(f.tracking(), stateCode, "EXCHANGE", 0, null, null, raw);
        when(bostaGateway.fetchDelivery(anyString(), eq(f.tracking()))).thenReturn(d);
        boolean enqueued = TenantContext.runAs(tenantId,
            () -> ingestionHelper.ingestDelivery(tenantId, RAW_API_KEY, f.tracking(), "bosta_poll"));
        assertThat(enqueued).isTrue();
        Long eventId = jdbc.queryForObject(
            "SELECT id FROM webhook_events WHERE tenant_id = ? AND payload->>'trackingNumber' = ? " +
            "ORDER BY received_at DESC, id DESC LIMIT 1", Long.class, tenantId, f.tracking());
        webhookJob.process(eventId, tenantId);
        TenantContext.clear();
        return eventId;
    }

    private String pieceStatus(String piece) {
        return jdbc.queryForObject("SELECT status::text FROM pieces WHERE id = ?", String.class, piece);
    }

    private Map<String, Object> row(String piece) {
        return jdbc.queryForMap("SELECT delta, status, location_id FROM shopify_inventory_adjustments " +
            "WHERE trigger_type = 'exchange_dispatch' AND trigger_id = ?", piece);
    }

    private void awaitRows(String piece, int n) throws InterruptedException {
        awaitTriggerCount("exchange_dispatch", piece, n);
    }

    private void awaitTrigger(String triggerType, String piece) throws InterruptedException {
        awaitTriggerCount(triggerType, piece, 1);
    }

    private void awaitTriggerCount(String triggerType, String piece, int n) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            Integer done = jdbc.queryForObject("SELECT COUNT(*) FROM shopify_inventory_adjustments " +
                "WHERE trigger_type = ? AND trigger_id = ? AND status <> 'pending'", Integer.class, triggerType, piece);
            if (done != null && done >= n) return;
            Thread.sleep(50);
        }
        throw new AssertionError("timed out waiting for " + triggerType + " on " + piece);
    }

    /** Lets any stray async trigger finish before asserting that nothing more happened. */
    private void settle() {
        try { Thread.sleep(400); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private void assertDecrementedOnce(String piece) {
        Integer rows = jdbc.queryForObject("SELECT COUNT(*) FROM shopify_inventory_adjustments " +
            "WHERE trigger_type = 'exchange_dispatch' AND trigger_id = ?", Integer.class, piece);
        assertThat(rows).as("one claim row").isEqualTo(1);
        Map<String, Object> r = row(piece);
        assertThat(r.get("delta")).isEqualTo(-1);
        assertThat(r.get("status")).isEqualTo("applied");
        assertThat(r.get("location_id")).isEqualTo(fulfillmentLoc);
        verify(shopifyGateway, times(1)).pushExchangeDispatch(eq(SHOP_DOMAIN), eq("shpat-test"), eq(ITEM_GID), eq(TRACED_GID),
            eq(-1), eq("traced://piece/" + piece), anyString());
    }

    private void assertNoDecrement(String piece) {
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM shopify_inventory_adjustments WHERE trigger_type = 'exchange_dispatch' " +
            "AND trigger_id = ?", Integer.class, piece)).isZero();
        verify(shopifyGateway, never()).pushExchangeDispatch(anyString(), anyString(), anyString(), anyString(), anyInt(), anyString(), anyString());
    }

    private int sumAppliedDeltas(String piece) {
        return jdbc.queryForObject("SELECT COALESCE(SUM(delta), 0) FROM shopify_inventory_adjustments " +
            "WHERE trigger_id = ? AND status = 'applied' AND trigger_type IN ('exchange_dispatch', 'return_inspection')",
            Integer.class, piece);
    }
}
