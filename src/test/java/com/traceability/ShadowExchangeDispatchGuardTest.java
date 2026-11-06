package com.traceability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.traceability.courier.GenericLegTransitions;
import com.traceability.integrations.bosta.BostaDelivery;
import com.traceability.integrations.bosta.BostaGateway;
import com.traceability.integrations.bosta.BostaIngestionHelper;
import com.traceability.integrations.bosta.BostaWebhookJob;
import com.traceability.integrations.shopify.ShopifyGateway;
import com.traceability.integrations.shopify.ShopifyTokenProvider;
import com.traceability.inventory.*;
import com.traceability.security.EncryptionService;
import com.traceability.tenancy.TenantContext;
import org.jobrunr.scheduling.JobScheduler;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * S3c guard — the Step-5a exchange-dispatch decrement ({@code ShopifyInventoryService.onExchangeReplacementDispatched}
 * → {@code processExchangeDispatch} → {@code ShopifyGateway.pushExchangeDispatch}) makes ZERO decrements for a piece of
 * a Shopify-native exchange SHADOW order ({@code internal:shopify-exchange:<return gid>}, origin
 * {@code shopify_exchange}) and for a piece of a plain Shopify order ({@code gid://shopify/Order/…}), whichever of the
 * four real callers moves the piece out of Traced custody. Shopify already counts a shadow's unit on its own exchange
 * line, so a −1 would take it out twice.
 *
 * "Zero" = {@code pushExchangeDispatch} never called AND no {@code shopify_inventory_adjustments} row with
 * trigger_type 'exchange_dispatch' for the piece — processExchangeDispatch's eligibility query requires
 * {@code external_id LIKE 'internal:exchange:%'} named by an exchanges row / exchange request, so anything else is a
 * silent no-op (no skipped row either).
 *
 * The dispatch entry is proven REACHED in every case, not assumed: {@link ShopifyInventoryService} is a
 * {@code @SpyBean}; each call of {@code onExchangeReplacementDispatched} is verified for the exact piece and the test
 * waits for the real method body (which runs processExchangeDispatch synchronously inside the @Async call) to finish
 * before asserting — no fixed sleep.
 *
 * <pre>
 *   p1 / p2 / p3   PickupSessionService.closeSession          shadow / Shopify order → none; internal exchange → −1 once
 *   b1 / b2 / b3   BostaWebhookJob.applyMappedState loop      (real ingest pipeline, SEND state 21 picked up)
 *                                                             shadow / Shopify order → none; internal exchange → −1 once
 *   g1 / g2 / g3   GenericLegTransitions.deliver, inferred    shadow / Shopify order → none; internal exchange named by
 *                  handover (Shopify 'delivered' on 'created') a portal (G4b) exchange request → −1 once
 *   l1 / l2 / l3   GenericLegTransitions.dispatchLookupReplacement   shadow / Shopify order → none; Lookup replacement
 *                                                             ('internal:exchange:rr:…') → −1 once
 * </pre>
 *
 * Positive controls (p3, b3, g3, l3) make every caller's negative non-vacuous. The Bosta-caller control uses the same
 * SEND delivery shape as the negatives (the piece loop is the shared applyMappedState; Step 5a's normal type-30 path
 * goes through the same loop and is covered by ExchangeDispatchDecrementTest).
 *
 * l1 / l2 are ADVERSARIAL: dispatchLookupReplacement only moves an order that a {@code origin = 'lookup'} exchange
 * request names as its replacement, and LookupExchangeService only ever names an {@code internal:exchange:rr:} order —
 * so the test names the shadow / Shopify order from a Lookup request by SQL, purely to make the caller reach the
 * dispatch entry with such a piece. Even then processExchangeDispatch's prefix check refuses it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ShadowExchangeDispatchGuardTest {

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

    static final String SHOP_DOMAIN = "shadow-dispatch.myshopify.com";
    static final String TRACED_GID  = "gid://shopify/Location/977";
    static final String VARIANT_GID = "gid://shopify/ProductVariant/9501";
    static final String ITEM_GID    = "gid://shopify/InventoryItem/9601";
    static final String RAW_API_KEY = "shadow-dispatch-key";

    @MockBean JobScheduler         jobScheduler;
    @MockBean ShopifyGateway       shopifyGateway;
    @MockBean ShopifyTokenProvider tokenProvider;
    @MockBean BostaGateway         bostaGateway;
    @SpyBean  ShopifyInventoryService shopifyInventory;

    @Autowired JdbcTemplate          jdbc;
    @Autowired ObjectMapper          mapper;
    @Autowired EncryptionService     encryptionService;
    @Autowired PickupSessionService  pickups;
    @Autowired BostaWebhookJob       webhookJob;
    @Autowired BostaIngestionHelper  ingestionHelper;
    @Autowired GenericLegTransitions legs;
    @Autowired PlatformTransactionManager txm;

    UUID tenantId, storeId, variantId, mainLoc, actorId;
    /** The spy itself (the bean is wrapped by the @Async proxy) — stubbing / verification go to it directly. */
    ShopifyInventoryService spy;
    /** Completed when the real onExchangeReplacementDispatched body (processExchangeDispatch) has finished for a piece. */
    final Map<String, CompletableFuture<Void>> dispatched = new ConcurrentHashMap<>();

    enum Kind { SHADOW, SHOPIFY, INTERNAL_EXCHANGE }

    @BeforeAll
    void setup() {
        tenantId = UUID.randomUUID(); storeId = UUID.randomUUID(); actorId = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name, generic_courier_enabled) VALUES (?, 'Shadow Dispatch Tenant', true)", tenantId);
        jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role) VALUES (?, ?, 'Worker', ?, 'h', 'owner')",
            actorId, tenantId, "w-" + actorId + "@shadow.test");
        jdbc.update("INSERT INTO stores (id, tenant_id, shop_domain, status, import_status, access_token_scopes) " +
            "VALUES (?, ?, ?, 'connected', 'idle', 'read_orders,write_inventory,read_products,write_locations,read_locations')",
            storeId, tenantId, SHOP_DOMAIN);
        UUID productId = UUID.randomUUID();
        jdbc.update("INSERT INTO products (id, tenant_id, store_id, external_id, title, status) VALUES (?, ?, ?, 'gid://shopify/Product/95', 'Wool Coat', 'active')",
            productId, tenantId, storeId);
        variantId = UUID.randomUUID();
        jdbc.update("INSERT INTO variants (id, tenant_id, product_id, external_id, title, sku) VALUES (?, ?, ?, ?, 'Grey / M', 'WC-G-M')",
            variantId, tenantId, productId, VARIANT_GID);
        mainLoc = UUID.randomUUID();
        jdbc.update("INSERT INTO locations (id, tenant_id, name, shopify_location_id, shopify_sync_status, is_fulfillment) " +
            "VALUES (?, ?, 'Main Warehouse', ?, 'linked', true)", mainLoc, tenantId, TRACED_GID);
        jdbc.update("INSERT INTO courier_accounts (id, tenant_id, provider, api_key_encrypted, webhook_secret, status) " +
            "VALUES (gen_random_uuid(), ?, 'bosta', ?, 'shadow-dispatch-hash', 'active')", tenantId, encryptionService.encrypt(RAW_API_KEY));
    }

    @BeforeEach
    void stubs() {
        reset(shopifyGateway, tokenProvider);
        when(tokenProvider.getValidToken(any())).thenReturn("shpat-test");
        when(shopifyGateway.resolveInventoryItemId(anyString(), anyString(), anyString())).thenReturn(ITEM_GID);
        spy = AopTestUtils.getUltimateTargetObject(shopifyInventory);
        assertThat(mockingDetails(spy).isSpy()).as("the ShopifyInventoryService bean is a Mockito spy").isTrue();
        dispatched.clear();
        doAnswer(inv -> {
            try {
                return inv.callRealMethod();
            } finally {
                dispatched.computeIfAbsent(inv.getArgument(1), k -> new CompletableFuture<>()).complete(null);
            }
        }).when(spy).onExchangeReplacementDispatched(any(), anyString());
    }

    @AfterEach
    void cleanup() {
        TenantContext.clear();
        UUID t = tenantId;
        jdbc.update("DELETE FROM exception_resolutions WHERE tenant_id = ?", t);
        jdbc.update("DELETE FROM shopify_inventory_adjustments WHERE tenant_id = ?", t);
        jdbc.update("DELETE FROM piece_events WHERE tenant_id = ?", t);
        jdbc.update("DELETE FROM pickup_shipments WHERE tenant_id = ?", t);
        jdbc.update("DELETE FROM pickups WHERE tenant_id = ?", t);
        jdbc.update("UPDATE exchanges SET outbound_order_id = NULL WHERE tenant_id = ?", t);
        jdbc.update("DELETE FROM exchanges WHERE tenant_id = ?", t);
        jdbc.update("DELETE FROM return_request_events WHERE tenant_id = ?", t);
        jdbc.update("DELETE FROM return_requests WHERE tenant_id = ?", t);
        jdbc.update("DELETE FROM shopify_exchange_shadows WHERE tenant_id = ?", t);
        jdbc.update("DELETE FROM shopify_returns WHERE tenant_id = ?", t);
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

    // ── p: PickupSessionService.closeSession ────────────────────────────────────

    @Test
    void p1_pickupClose_shadowOrder_noDecrement() throws Exception {
        Fixture f = bostaLeg(Kind.SHADOW);
        closePickup(f);
        assertNoDecrementAfterDispatch(f.piece());
    }

    @Test
    void p2_pickupClose_shopifyOrder_noDecrement() throws Exception {
        Fixture f = bostaLeg(Kind.SHOPIFY);
        closePickup(f);
        assertNoDecrementAfterDispatch(f.piece());
    }

    @Test
    void p3_pickupClose_internalExchange_positiveControl_decrementsOnce() throws Exception {
        Fixture f = bostaLeg(Kind.INTERNAL_EXCHANGE);
        closePickup(f);
        assertDecrementedOnceAfterDispatch(f.piece());
    }

    // ── b: BostaWebhookJob.applyMappedState piece loop (real ingest pipeline) ──

    @Test
    void b1_bostaPickedUp_shadowOrder_noDecrement() throws Exception {
        Fixture f = bostaLeg(Kind.SHADOW);
        ingestPickedUp(f);
        assertNoDecrementAfterDispatch(f.piece());
    }

    @Test
    void b2_bostaPickedUp_shopifyOrder_noDecrement() throws Exception {
        Fixture f = bostaLeg(Kind.SHOPIFY);
        ingestPickedUp(f);
        assertNoDecrementAfterDispatch(f.piece());
    }

    @Test
    void b3_bostaPickedUp_internalExchange_positiveControl_decrementsOnce() throws Exception {
        Fixture f = bostaLeg(Kind.INTERNAL_EXCHANGE);
        ingestPickedUp(f);
        assertDecrementedOnceAfterDispatch(f.piece());
    }

    // ── g: GenericLegTransitions.deliver — inferred handover ───────────────────

    @Test
    void g1_genericInferredHandover_shadowOrder_noDecrement() throws Exception {
        Fixture f = genericLeg(Kind.SHADOW, false);
        assertThat(deliverFromShopify(f)).isEqualTo(GenericLegTransitions.Delivered.DELIVERED_INFERRED_HANDOVER);
        assertNoDecrementAfterDispatch(f.piece());
    }

    @Test
    void g2_genericInferredHandover_shopifyOrder_noDecrement() throws Exception {
        Fixture f = genericLeg(Kind.SHOPIFY, false);
        assertThat(deliverFromShopify(f)).isEqualTo(GenericLegTransitions.Delivered.DELIVERED_INFERRED_HANDOVER);
        assertNoDecrementAfterDispatch(f.piece());
    }

    @Test
    void g3_genericInferredHandover_portalExchangeReplacement_positiveControl_decrementsOnce() throws Exception {
        Fixture f = genericLeg(Kind.INTERNAL_EXCHANGE, false);
        assertThat(deliverFromShopify(f)).isEqualTo(GenericLegTransitions.Delivered.DELIVERED_INFERRED_HANDOVER);
        assertDecrementedOnceAfterDispatch(f.piece());
    }

    // ── l: GenericLegTransitions.dispatchLookupReplacement ─────────────────────

    @Test
    void l1_lookupDispatch_shadowOrderNamedByALookupRequest_noDecrement() throws Exception {
        Fixture f = genericLeg(Kind.SHADOW, true);
        assertThat(dispatchLookup(f)).containsExactly(f.piece());
        assertNoDecrementAfterDispatch(f.piece());
    }

    @Test
    void l2_lookupDispatch_shopifyOrderNamedByALookupRequest_noDecrement() throws Exception {
        Fixture f = genericLeg(Kind.SHOPIFY, true);
        assertThat(dispatchLookup(f)).containsExactly(f.piece());
        assertNoDecrementAfterDispatch(f.piece());
    }

    @Test
    void l3_lookupDispatch_lookupReplacement_positiveControl_decrementsOnce() throws Exception {
        Fixture f = genericLeg(Kind.INTERNAL_EXCHANGE, true);
        assertThat(dispatchLookup(f)).containsExactly(f.piece());
        assertDecrementedOnceAfterDispatch(f.piece());
    }

    // ── Fixtures ────────────────────────────────────────────────────────────────

    record Fixture(UUID orderId, UUID shipmentId, String tracking, String piece) {}

    private static long rnd() { return ThreadLocalRandom.current().nextLong(1_000_000L, 9_999_999L); }

    /**
     * The order a piece leaves on. SHADOW: a Shopify-native exchange shadow of a delivered Shopify original — the
     * shadow order (V216 identity: origin 'shopify_exchange' ⇔ 'internal:shopify-exchange:' prefix), its mirrored
     * Shopify return and its shopify_exchange_shadows row, as ShopifyShadowOrders writes them. SHOPIFY: a plain Shopify
     * order. INTERNAL_EXCHANGE: an 'internal:exchange:' order ({@code ext} decides the suffix; the caller names it).
     */
    private UUID order(Kind kind, String internalExt, String status) {
        long n = rnd();
        return switch (kind) {
            case SHOPIFY -> jdbc.queryForObject(
                "INSERT INTO orders (tenant_id, store_id, external_id, number, status, placed_at) " +
                "VALUES (?, ?, ?, ?, ?::order_status, now()) RETURNING id",
                UUID.class, tenantId, storeId, "gid://shopify/Order/" + n, "#" + n, status);
            case INTERNAL_EXCHANGE -> jdbc.queryForObject(
                "INSERT INTO orders (tenant_id, store_id, external_id, number, status, placed_at) " +
                "VALUES (?, ?, ?, ?, ?::order_status, now()) RETURNING id",
                UUID.class, tenantId, storeId, internalExt, "EXC-" + n, status);
            case SHADOW -> {
                UUID original = jdbc.queryForObject(
                    "INSERT INTO orders (tenant_id, store_id, external_id, number, status, placed_at) " +
                    "VALUES (?, ?, ?, ?, 'delivered'::order_status, now() - interval '5 days') RETURNING id",
                    UUID.class, tenantId, storeId, "gid://shopify/Order/" + n, "#" + n);
                String returnGid = "gid://shopify/Return/" + n;
                UUID ret = jdbc.queryForObject(
                    "INSERT INTO shopify_returns (tenant_id, store_id, return_gid, order_id, order_gid, name, status, " +
                    "    request_approved_at, raw) VALUES (?, ?, ?, ?, ?, ?, 'OPEN', now(), '{}'::jsonb) RETURNING id",
                    UUID.class, tenantId, storeId, returnGid, original, "gid://shopify/Order/" + n, "#" + n + "-R1");
                UUID shadow = jdbc.queryForObject(
                    "INSERT INTO orders (tenant_id, store_id, external_id, number, status, origin, placed_at) " +
                    "VALUES (?, ?, ?, ?, ?::order_status, 'shopify_exchange', now()) RETURNING id",
                    UUID.class, tenantId, storeId, ShopifyShadowOrders.PREFIX + returnGid, "#" + n + "-X1", status);
                jdbc.update("INSERT INTO shopify_exchange_shadows (tenant_id, store_id, return_id, order_id, original_order_id, " +
                    "    x_index, approved_at) VALUES (?, ?, ?, ?, ?, 1, now())", tenantId, storeId, ret, shadow, original);
                yield shadow;
            }
        };
    }

    /** One order line + one piece at the main warehouse, allocated at {@code pieceStatus} / {@code allocationStatus}. */
    private String allocatedPiece(UUID orderId, String pieceStatus, String allocationStatus) {
        UUID item = UUID.randomUUID();
        jdbc.update("INSERT INTO order_items (id, tenant_id, order_id, variant_id, quantity) VALUES (?, ?, ?, ?, 1)",
            item, tenantId, orderId, variantId);
        String piece = UlidGenerator.generate();
        jdbc.update("INSERT INTO pieces (id, tenant_id, variant_id, barcode, short_code, status, current_location_id, current_order_id) " +
            "VALUES (?, ?, ?, ?, 'P' || LPAD((abs(hashtext(?)) % 999999 + 1)::text, 6, '0'), ?::piece_status, ?, ?)",
            piece, tenantId, variantId, "PC-" + piece, piece, pieceStatus, mainLoc, orderId);
        jdbc.update("INSERT INTO allocations (tenant_id, order_item_id, piece_id, status) VALUES (?, ?, ?, ?::allocation_status)",
            tenantId, item, piece, allocationStatus);
        return piece;
    }

    /** A packed piece on a Bosta forward leg in 'created' (INTERNAL_EXCHANGE: named by an exchanges row — Step 5a). */
    private Fixture bostaLeg(Kind kind) {
        String tracking = String.valueOf(ThreadLocalRandom.current().nextLong(1_000_000_000L, 9_999_999_999L));
        UUID orderId = order(kind, "internal:exchange:" + tracking, "awaiting_pickup");
        UUID shipment = jdbc.queryForObject(
            "INSERT INTO shipments (tenant_id, order_id, provider, tracking_number, internal_state, shipment_leg) " +
            "VALUES (?, ?, 'bosta', ?, 'created'::shipment_internal_state, 'forward') RETURNING id",
            UUID.class, tenantId, orderId, tracking);
        String piece = allocatedPiece(orderId, "packed", "packed");
        if (kind == Kind.INTERNAL_EXCHANGE) {
            jdbc.update("INSERT INTO exchanges (tenant_id, tracking_number, status, outbound_order_id, outbound_variant_id, raw) " +
                "VALUES (?, ?, 'mapped', ?, ?, '{}'::jsonb)", tenantId, tracking, orderId, variantId);
        }
        return new Fixture(orderId, shipment, tracking, piece);
    }

    /**
     * A generic forward leg in 'created'. {@code lookup} false: a packed piece (the inferred-handover shape);
     * INTERNAL_EXCHANGE is named by a portal (G4b) exchange request. {@code lookup} true: a reserved piece on a
     * 'confirmed' order named by an origin 'lookup' exchange request (C2's shape) — for SHADOW / SHOPIFY that naming
     * is adversarial (see the class javadoc).
     */
    private Fixture genericLeg(Kind kind, boolean lookup) {
        String tracking = "GEN" + rnd();
        UUID requestId = UUID.randomUUID();
        UUID orderId = order(kind, "internal:exchange:rr:" + requestId, lookup ? "confirmed" : "awaiting_pickup");
        UUID shipment = jdbc.queryForObject(
            "INSERT INTO shipments (tenant_id, order_id, provider, tracking_number, tracking_key, carrier_name, internal_state, " +
            "    shipment_leg, source) VALUES (?, ?, 'generic', ?, ?, 'Aramex', 'created', 'forward', 'merchant_entered') RETURNING id",
            UUID.class, tenantId, orderId, tracking, tracking);
        String piece = lookup ? allocatedPiece(orderId, "reserved", "active") : allocatedPiece(orderId, "packed", "packed");
        if (lookup || kind == Kind.INTERNAL_EXCHANGE) {
            UUID original = order(Kind.SHOPIFY, null, "delivered");
            jdbc.update("INSERT INTO return_requests (id, tenant_id, order_id, type, status, reference, origin, booking_status, " +
                "    replacement_order_id) VALUES (?, ?, ?, 'exchange', 'approved', ?, ?, 'manual', ?)",
                requestId, tenantId, original, reference(), lookup ? "lookup" : "portal", orderId);
        }
        return new Fixture(orderId, shipment, tracking, piece);
    }

    private String reference() {
        String alphabet = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ";
        StringBuilder sb = new StringBuilder("RR-");
        for (int i = 0; i < 8; i++) sb.append(alphabet.charAt(ThreadLocalRandom.current().nextInt(alphabet.length())));
        return sb.toString();
    }

    // ── Callers ─────────────────────────────────────────────────────────────────

    private void closePickup(Fixture f) {
        UUID pickup = pickups.openSession(tenantId, actorId, LocalDate.now(), null, null);
        assertThat(pickups.scan(tenantId, pickup, actorId, f.tracking()).outcome())
            .isEqualTo(PickupSessionService.ScanOutcome.ACCEPTED);
        pickups.closeSession(tenantId, pickup, actorId);
        assertThat(pieceStatus(f.piece())).isEqualTo("with_courier");
    }

    /** Bosta SEND delivery at state 21 (picked up) for the leg's tracking number, through the real ingest pipeline. */
    private void ingestPickedUp(Fixture f) {
        ObjectNode raw = mapper.createObjectNode();
        raw.put("_id", "send-" + f.tracking());
        raw.put("trackingNumber", f.tracking());
        raw.putObject("type").put("code", 10).put("value", "Send");
        raw.putObject("state").put("code", 21).put("value", "Picked up");
        raw.put("updatedAt", Instant.parse("2026-11-06T10:00:00Z").toString());
        BostaDelivery d = new BostaDelivery(f.tracking(), 21, "SEND", 0, null, null, raw);
        when(bostaGateway.fetchDelivery(anyString(), eq(f.tracking()))).thenReturn(d);
        boolean enqueued = TenantContext.runAs(tenantId,
            () -> ingestionHelper.ingestDelivery(tenantId, RAW_API_KEY, f.tracking(), "bosta_poll"));
        assertThat(enqueued).isTrue();
        Long eventId = jdbc.queryForObject(
            "SELECT id FROM webhook_events WHERE tenant_id = ? AND payload->>'trackingNumber' = ? " +
            "ORDER BY received_at DESC, id DESC LIMIT 1", Long.class, tenantId, f.tracking());
        webhookJob.process(eventId, tenantId);
        TenantContext.clear();
        assertThat(pieceStatus(f.piece())).isEqualTo("with_courier");
    }

    private GenericLegTransitions.Delivered deliverFromShopify(Fixture f) {
        GenericLegTransitions.Delivered out = TenantContext.runAs(tenantId, () -> new TransactionTemplate(txm).execute(s ->
            legs.deliver(tenantId, f.shipmentId(), GenericLegTransitions.SOURCE_SHOPIFY, null)));
        assertThat(pieceStatus(f.piece())).isEqualTo("delivered");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM piece_events WHERE piece_id = ? AND to_status = 'with_courier'",
            Integer.class, f.piece())).as("the inferred handover moved the piece out of custody").isEqualTo(1);
        return out;
    }

    private List<String> dispatchLookup(Fixture f) {
        List<String> left = TenantContext.runAs(tenantId, () -> new TransactionTemplate(txm).execute(s ->
            legs.dispatchLookupReplacement(tenantId, f.shipmentId(), actorId)));
        assertThat(pieceStatus(f.piece())).isEqualTo("with_courier");
        return left;
    }

    // ── Assertions ──────────────────────────────────────────────────────────────

    private String pieceStatus(String piece) {
        return jdbc.queryForObject("SELECT status::text FROM pieces WHERE id = ?", String.class, piece);
    }

    /** The caller reached the Step-5a entry for this piece, exactly once, and its real body finished. */
    private void awaitDispatchEntry(String piece) throws Exception {
        verify(spy, timeout(10_000).times(1)).onExchangeReplacementDispatched(eq(tenantId), eq(piece));
        dispatched.computeIfAbsent(piece, k -> new CompletableFuture<>()).get(10, TimeUnit.SECONDS);
    }

    private int claimRows(String piece) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM shopify_inventory_adjustments " +
            "WHERE trigger_type = 'exchange_dispatch' AND trigger_id = ?", Integer.class, piece);
    }

    private void assertNoDecrementAfterDispatch(String piece) throws Exception {
        awaitDispatchEntry(piece);
        assertThat(claimRows(piece)).as("no exchange_dispatch row (not even skipped) for " + piece).isZero();
        verify(shopifyGateway, never()).pushExchangeDispatch(anyString(), anyString(), anyString(), anyString(),
            anyInt(), anyString(), anyString());
    }

    private void assertDecrementedOnceAfterDispatch(String piece) throws Exception {
        awaitDispatchEntry(piece);
        assertThat(claimRows(piece)).as("one claim row").isEqualTo(1);
        Map<String, Object> r = jdbc.queryForMap("SELECT delta, status, location_id FROM shopify_inventory_adjustments " +
            "WHERE trigger_type = 'exchange_dispatch' AND trigger_id = ?", piece);
        assertThat(r).containsEntry("delta", -1).containsEntry("status", "applied").containsEntry("location_id", mainLoc);
        verify(shopifyGateway, times(1)).pushExchangeDispatch(eq(SHOP_DOMAIN), eq("shpat-test"), eq(ITEM_GID), eq(TRACED_GID),
            eq(-1), eq("traced://piece/" + piece), anyString());
    }
}
