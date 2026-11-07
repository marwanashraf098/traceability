package com.traceability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.traceability.integrations.bosta.BostaDelivery;
import com.traceability.integrations.bosta.BostaFulfillmentLinkService;
import com.traceability.integrations.bosta.BostaGateway;
import com.traceability.integrations.bosta.BostaIngestionHelper;
import com.traceability.integrations.bosta.BostaStateMapper;
import com.traceability.integrations.bosta.BostaWebhookJob;
import com.traceability.integrations.shopify.ShopifyGateway;
import com.traceability.integrations.shopify.ShopifyReturnsGateway;
import com.traceability.integrations.shopify.ShopifyReturnsStore;
import com.traceability.integrations.shopify.ShopifyTokenProvider;
import com.traceability.inventory.*;
import com.traceability.security.EncryptionService;
import com.traceability.tenancy.TenantAwareDataSource;
import com.traceability.tenancy.TenantContext;
import org.jobrunr.scheduling.JobScheduler;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Shopify-native returns S3d — a Bosta courier leg booked for a Shopify-native exchange attaches to the exchange's
 * SHADOW order (the Return's businessReference "…:&lt;original&gt;-R1" names it), never to the original and never as a
 * new internal:exchange: replacement.
 *
 * <pre>
 *   b1   type 30 through the real ingest pipeline, reference names a live shadow → no internal:exchange: order;
 *        exchanges.outbound_order_id = the shadow; the shadow has the forward leg; status not needs_mapping; no
 *        exchange_needs_mapping alert
 *   b2   control: the same type 30 with no shadow → the old path (internal:exchange: order, its forward leg)
 *   b3   SEND with the "-R1" reference (tryMatchDelivery step 0b) → the shadow's forward leg; the original untouched
 *   b3b  CRP (type 25) with the same reference → still the ORIGINAL's return leg
 *   b4   fulfillment-link job (no Bosta forward leg on the original): the fetched delivery names a live shadow →
 *        'skipped', no conflict, no fulfillment_link_problem; and (f1) the exchange AWB already on the shadow →
 *        LINKED 'linked via Shopify exchange #…-X1'
 *   f1   the full tracked exchange: type 30 → shadow leg; Shopify fulfills WITH that tracking (FO CLOSED) → still live
 *        and released; pack (scan / complete) → pickup close → pieces with_courier; the shadow is no longer committed
 *        or in the queue; ZERO exchange_dispatch claims (the Step-5a entry reached and refused)
 * </pre>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ShadowCourierAttachTest {

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
        r.add("traced.shopify-returns.backfill.enabled", () -> "false");
        r.add("shopify.webhook-topics-backfill.enabled", () -> "false");
        r.add(ShopifyShadowOrders.S3C_FLAG, () -> "true");
    }

    @MockBean JobScheduler          jobScheduler;
    @MockBean ShopifyGateway        shopifyGateway;
    @MockBean ShopifyTokenProvider  tokenProvider;
    @MockBean ShopifyReturnsGateway returnsGateway;
    @MockBean BostaGateway          bostaGateway;
    @SpyBean  ShopifyInventoryService shopifyInventory;

    @Autowired JdbcTemplate          jdbc;
    @Autowired ObjectMapper          mapper;
    @Autowired EncryptionService     encryption;
    @Autowired ShopifyReturnsStore   returnsStore;
    @Autowired BostaWebhookJob       webhookJob;
    @Autowired BostaIngestionHelper  ingestionHelper;
    @Autowired BostaStateMapper      stateMapper;
    @Autowired ShipmentLinkService   links;
    @Autowired BostaFulfillmentLinkService fulfillmentLinks;
    @Autowired FulfillService        fulfill;
    @Autowired PickupSessionService  pickups;
    @Autowired ExceptionService      exceptions;

    JdbcTemplate appJdbc;
    DataSourceTransactionManager appTxm;
    ShopifyShadowOrders shadows;
    ShopifyInventoryService spy;
    final Map<String, CompletableFuture<Void>> dispatched = new ConcurrentHashMap<>();

    static final AtomicLong IDS = new AtomicLong(84_000_000_000_000L);
    static final Instant APPROVED = Instant.parse("2026-11-04T09:30:00Z");
    static final String RAW_KEY = "s3d-bosta-key";

    @BeforeAll
    void appUser() {
        DataSource ds = new TenantAwareDataSource(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "app_user", "testpw"));
        appJdbc = new JdbcTemplate(ds);
        appTxm = new DataSourceTransactionManager(ds);
        shadows = new ShopifyShadowOrders(appJdbc, appTxm, mapper);
    }

    @BeforeEach
    void stubs() {
        reset(shopifyGateway, tokenProvider, returnsGateway, bostaGateway);
        when(tokenProvider.getValidToken(any())).thenReturn("tok");
        spy = AopTestUtils.getUltimateTargetObject(shopifyInventory);
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
    void clear() { TenantContext.clear(); }

    static String gid(String kind) { return "gid://shopify/" + kind + "/" + IDS.incrementAndGet(); }
    static String tn() { return String.valueOf(5_000_000_000L + IDS.incrementAndGet() % 4_000_000_000L); }

    // ── fixture ─────────────────────────────────────────────────────────────────────────────────────────────────

    class Shop {
        final UUID tenant = UUID.randomUUID(), store = UUID.randomUUID(), location = UUID.randomUUID(), actor = UUID.randomUUID();
        final long restV = IDS.incrementAndGet(), restW = IDS.incrementAndGet();
        final UUID v, w, original, originalItem;
        final String originalGid, number, ref;

        /** {@code legFrozen}: the original left custody on a delivered Bosta leg; false: a delivered piece, no leg. */
        Shop(String tag, boolean legFrozen) {
            number = "#S3D" + tag;
            ref = "S3D" + tag + ":S3D" + tag + "-R1";              // the Bosta plugin's "<order>:<return name>"
            jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?)", tenant, "S3d " + tag);
            jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role) VALUES (?, ?, 'Packer', ?, 'h', 'owner')",
                actor, tenant, "p-" + actor + "@s3d.test");
            jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status, connection_type, returns_access) " +
                "VALUES (?, ?, 'shopify', ?, 'connected', 'oauth', 'granted')", store, tenant, "s3d-" + tag.toLowerCase() + "-" + tenant.toString().substring(0, 6) + ".myshopify.com");
            jdbc.update("INSERT INTO locations (id, tenant_id, name, type, is_default, is_fulfillment) " +
                "VALUES (?, ?, 'Main Warehouse', 'warehouse', true, true)", location, tenant);
            jdbc.update("INSERT INTO courier_accounts (tenant_id, provider, api_key_encrypted, webhook_secret, status) " +
                "VALUES (?, 'bosta', ?, ?, 'active')", tenant, encryption.encrypt(RAW_KEY), "wh-" + tenant);
            UUID product = jdbc.queryForObject("INSERT INTO products (tenant_id, store_id, external_id, title, status) " +
                "VALUES (?, ?, ?, 'LINEN SHIRT', 'active') RETURNING id", UUID.class, tenant, store, gid("Product"));
            v = variant(product, restV, "M");
            w = variant(product, restW, "L");
            originalGid = gid("Order");
            original = jdbc.queryForObject(
                "INSERT INTO orders (tenant_id, store_id, external_id, number, status, payment_method, placed_at, raw, " +
                "    customer_name, customer_phone) VALUES (?, ?, ?, ?, 'delivered', 'cod', now() - interval '5 days', '{}'::jsonb, " +
                "    'Mona Adel', '01012345678') RETURNING id", UUID.class, tenant, store, originalGid, number);
            originalItem = jdbc.queryForObject("INSERT INTO order_items (tenant_id, order_id, variant_id, quantity, external_id) " +
                "VALUES (?, ?, ?, 1, ?) RETURNING id", UUID.class, tenant, original, w, gid("LineItem"));
            if (legFrozen) {
                UUID s = jdbc.queryForObject("INSERT INTO shipments (tenant_id, order_id, provider, tracking_number, internal_state, shipment_leg) " +
                    "VALUES (?, ?, 'bosta', ?, 'delivered', 'forward') RETURNING id", UUID.class, tenant, original, tn());
                jdbc.update("INSERT INTO shipment_status_history (tenant_id, shipment_id, internal_state) VALUES (?, ?, 'delivered')", tenant, s);
            } else {
                String piece = UlidGenerator.generate();
                jdbc.update("INSERT INTO pieces (id, tenant_id, variant_id, barcode, short_code, status, current_order_id) " +
                    "VALUES (?, ?, ?, ?, ?, 'delivered', ?)", piece, tenant, w, "PC-" + piece,
                    String.format("F%07d", IDS.incrementAndGet() % 10_000_000), original);
                jdbc.update("INSERT INTO allocations (tenant_id, order_item_id, piece_id, status) VALUES (?, ?, ?, 'packed')",
                    tenant, originalItem, piece);
            }
        }

        UUID variant(UUID product, long rest, String title) {
            return jdbc.queryForObject("INSERT INTO variants (tenant_id, product_id, external_id, sku, title) VALUES (?, ?, ?, ?, ?) RETURNING id",
                UUID.class, tenant, product, "gid://shopify/ProductVariant/" + rest, "S3D-" + rest, title);
        }

        /** An available piece at the main warehouse; returns {id, barcode}. */
        String[] piece(UUID variant) {
            long k = IDS.incrementAndGet();
            String id = String.format("01S3D%021d", k);
            jdbc.update("INSERT INTO pieces (id, tenant_id, variant_id, barcode, short_code, status, current_location_id) " +
                "VALUES (?, ?, ?, ?, ?, 'available', ?)", id, tenant, variant, "S3D-" + k,
                String.format("E%07d", k % 10_000_000), location);
            return new String[]{id, "S3D-" + k};
        }

        <T> T as(java.util.concurrent.Callable<T> body) { return TenantContext.runAs(tenant, body); }

        /** OPEN Return "S3D<tag>-R1" with one exchange line of V (processed onto lineGid when not null). */
        void ret(String lineGid) {
            ObjectNode r = mapper.createObjectNode();
            r.put("id", gid("Return"));
            r.put("name", "#" + ref.substring(ref.indexOf(':') + 1));
            r.put("status", "OPEN");
            r.put("createdAt", APPROVED.minusSeconds(3600).toString());
            r.put("requestApprovedAt", APPROVED.toString());
            r.putObject("order").put("id", originalGid);
            r.putObject("returnLineItems").putArray("nodes");
            ObjectNode n = r.putObject("exchangeLineItems").putArray("nodes").addObject();
            n.put("id", gid("ExchangeLineItem"));
            n.put("variantId", "gid://shopify/ProductVariant/" + restV);
            n.put("quantity", 1);
            n.put("processedQuantity", lineGid != null ? 1 : 0);
            ArrayNode li = n.putArray("lineItems");
            if (lineGid != null) li.addObject().put("id", lineGid);
            as(() -> returnsStore.upsertReturn(store, original, r));
            reconcile();
        }

        void fo(String foGid, String status, String lineGid, int remaining) {
            ObjectNode fo = mapper.createObjectNode();
            fo.put("id", foGid);
            fo.put("status", status);
            fo.putObject("order").put("id", originalGid);
            fo.putArray("fulfillmentHolds");
            ObjectNode n = fo.putObject("lineItems").putArray("nodes").addObject();
            n.put("id", "gid://shopify/FulfillmentOrderLineItem/" + IDS.incrementAndGet());
            n.put("remainingQuantity", remaining);
            n.put("totalQuantity", 1);
            n.putObject("lineItem").put("id", lineGid).putObject("variant").put("id", "gid://shopify/ProductVariant/" + restV);
            as(() -> returnsStore.upsertFulfillmentOrder(store, original, fo));
            reconcile();
        }

        void reconcile() { as(() -> shadows.reconcileForOrder(original)); }

        UUID shadow() {
            return jdbc.queryForObject("SELECT order_id FROM shopify_exchange_shadows WHERE tenant_id = ? AND original_order_id = ?",
                UUID.class, tenant, original);
        }

        String shadowState() {
            return jdbc.queryForObject("SELECT state FROM shopify_exchange_shadows WHERE tenant_id = ?", String.class, tenant);
        }

        List<Map<String, Object>> alerts(String type) {
            return as(() -> exceptions.detectAllOpen().stream().filter(a -> type.equals(a.get("type"))).toList());
        }

        List<UUID> queue() { return as(() -> fulfill.getQueue().stream().map(r -> (UUID) r.get("id")).toList()); }

        long committed(UUID variant) {
            return as(() -> new TransactionTemplate(appTxm).execute(s ->
                new VariantStockService(appJdbc).forVariant(new VariantStockService(appJdbc).computeAll(), variant).committed()));
        }

        List<Map<String, Object>> shipments(String tracking) {
            return jdbc.queryForList("SELECT order_id, shipment_leg::text AS leg, internal_state::text AS state FROM shipments " +
                "WHERE tenant_id = ? AND tracking_number = ?", tenant, tracking);
        }

        int internalExchangeOrders() {
            return jdbc.queryForObject("SELECT COUNT(*) FROM orders WHERE tenant_id = ? AND external_id LIKE 'internal:exchange:%'",
                Integer.class, tenant);
        }
    }

    /** A Bosta delivery (type code / value) with a businessReference; 30 carries one item on each leg. */
    BostaDelivery delivery(String tracking, int typeCode, String typeValue, int state, String reference) {
        ObjectNode raw = mapper.createObjectNode();
        raw.put("_id", "d-" + tracking);
        raw.put("trackingNumber", tracking);
        raw.putObject("type").put("code", typeCode).put("value", typeValue);
        raw.putObject("state").put("code", state).put("value", "Created");
        raw.put("businessReference", reference);
        raw.putObject("receiver").put("firstName", "Mona").put("lastName", "Adel").put("phone", "01012345678");
        if (typeCode == 30) {
            raw.putObject("specs").putObject("packageDetails").put("itemsCount", 1).put("description", "Linen Shirt M");
            raw.putObject("returnSpecs").putObject("packageDetails").put("itemsCount", 1).put("description", "Linen Shirt L");
        }
        raw.put("updatedAt", Instant.parse("2026-11-06T10:00:00Z").plusSeconds(IDS.incrementAndGet() % 1000).toString());
        return BostaDelivery.fromRaw(tracking, raw);
    }

    /** The real ingest pipeline: discovery enqueue + BostaWebhookJob.process. */
    void ingest(Shop s, BostaDelivery d) {
        when(bostaGateway.fetchDelivery(anyString(), eq(d.trackingNumber()))).thenReturn(d);
        boolean enqueued = TenantContext.runAs(s.tenant,
            () -> ingestionHelper.ingestDelivery(s.tenant, RAW_KEY, d.trackingNumber(), "bosta_poll"));
        assertThat(enqueued).isTrue();
        Long eventId = jdbc.queryForObject("SELECT id FROM webhook_events WHERE tenant_id = ? AND payload->>'trackingNumber' = ? " +
            "ORDER BY received_at DESC, id DESC LIMIT 1", Long.class, s.tenant, d.trackingNumber());
        webhookJob.process(eventId, s.tenant);
        TenantContext.clear();
    }

    Map<String, Object> exchangeRow(Shop s, String tracking) {
        return jdbc.queryForMap("SELECT status, outbound_order_id, outbound_variant_id FROM exchanges WHERE tenant_id = ? AND tracking_number = ?",
            s.tenant, tracking);
    }

    // ── b1 / b2 ──────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    void b1_type30_namingLiveShadow_attachesToShadow_noInternalExchangeOrder() {
        Shop s = new Shop("B1", true);
        s.ret(null);
        UUID shadow = s.shadow();
        String awb = tn();
        ingest(s, delivery(awb, 30, "Exchange", 10, s.ref));

        assertThat(s.internalExchangeOrders()).as("no internal:exchange: order").isZero();
        Map<String, Object> ex = exchangeRow(s, awb);
        assertThat(ex.get("outbound_order_id")).isEqualTo(shadow);
        assertThat(ex.get("outbound_variant_id")).isEqualTo(s.v);
        assertThat(ex.get("status")).isNotEqualTo("needs_mapping");
        assertThat(s.shipments(awb)).singleElement().satisfies(r -> {
            assertThat(r.get("order_id")).isEqualTo(shadow);
            assertThat(r.get("leg")).isEqualTo("forward");
        });
        assertThat(s.alerts("exchange_needs_mapping")).isEmpty();
        assertThat(s.shadowState()).isEqualTo("live");
    }

    @Test
    void b2_type30_withoutShadow_oldPath_internalExchangeOrder() {
        Shop s = new Shop("B2", true);                       // no Shopify Return at all
        String awb = tn();
        ingest(s, delivery(awb, 30, "Exchange", 10, s.ref));

        UUID exc = jdbc.queryForObject("SELECT id FROM orders WHERE tenant_id = ? AND external_id = ?", UUID.class,
            s.tenant, "internal:exchange:" + awb);
        assertThat(exchangeRow(s, awb).get("outbound_order_id")).isEqualTo(exc);
        assertThat(s.shipments(awb)).singleElement().satisfies(r -> assertThat(r.get("order_id")).isEqualTo(exc));
    }

    // ── b3 / b3b ─────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    void b3_send_withReturnReference_linksToShadow_originalUntouched() {
        Shop s = new Shop("B3", true);
        s.ret(null);
        UUID shadow = s.shadow();
        int originalLegs = jdbc.queryForObject("SELECT COUNT(*) FROM shipments WHERE order_id = ?", Integer.class, s.original);
        String awb = tn();
        BostaDelivery d = delivery(awb, 10, "Send", 10, s.ref);
        ShipmentLinkService.LinkResult r = s.as(() -> links.tryMatchDelivery(s.tenant, awb, d, stateMapper.map(10, "SEND")));

        assertThat(r.orderId()).isEqualTo(shadow);
        assertThat(s.shipments(awb)).singleElement().satisfies(m -> {
            assertThat(m.get("order_id")).isEqualTo(shadow);
            assertThat(m.get("leg")).isEqualTo("forward");
        });
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM shipments WHERE order_id = ?", Integer.class, s.original))
            .isEqualTo(originalLegs);
    }

    @Test
    void b3b_crp_withSameReference_staysTheOriginalsReturnLeg() {
        Shop s = new Shop("B3B", true);
        s.ret(null);
        UUID shadow = s.shadow();
        String awb = tn();
        BostaDelivery d = delivery(awb, 25, "Customer Return Pickup", 22, s.ref);
        ShipmentLinkService.LinkResult r = s.as(() ->
            links.tryMatchDelivery(s.tenant, awb, d, stateMapper.map(22, "CUSTOMER RETURN PICKUP")));

        assertThat(r.orderId()).isEqualTo(s.original);
        assertThat(s.shipments(awb)).singleElement().satisfies(m -> {
            assertThat(m.get("order_id")).isEqualTo(s.original);
            assertThat(m.get("leg")).isEqualTo("return");
        });
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM shipments WHERE order_id = ?", Integer.class, shadow)).isZero();
    }

    // ── b4 ───────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    void b4_fulfillmentLink_deliveryNamesLiveShadow_skippedNoConflict() {
        Shop s = new Shop("B4", false);                     // no Bosta forward leg on the original
        s.ret(null);
        assertThat(s.shadowState()).isEqualTo("live");
        String awb = tn();
        jdbc.update("INSERT INTO order_fulfillment_tracking (tenant_id, order_id, tracking_number, carrier_raw, carrier_class, " +
            "shopify_fulfillment_id, fulfillment_status) VALUES (?, ?, ?, 'Bosta', 'bosta', ?, 'success')", s.tenant, s.original, awb,
            String.valueOf(IDS.incrementAndGet()));
        when(bostaGateway.fetchDelivery(anyString(), eq(awb))).thenReturn(delivery(awb, 10, "Send", 10, s.ref));

        BostaFulfillmentLinkService.Result r = fulfillmentLinks.attempt(s.tenant, s.original, awb, false);
        assertThat(r.verdict()).isEqualTo(BostaFulfillmentLinkService.Verdict.SKIP);
        assertThat(r.reason()).contains("Shopify exchange");
        assertThat(jdbc.queryForObject("SELECT link_status FROM order_fulfillment_tracking WHERE tenant_id = ? AND tracking_number = ?",
            String.class, s.tenant, awb)).isEqualTo("skipped");
        assertThat(s.shipments(awb)).isEmpty();
        assertThat(s.alerts("fulfillment_link_problem")).isEmpty();
    }

    // ── f1: the full tracked Bosta exchange ──────────────────────────────────────────────────────────────────────

    @Test
    void f1_trackedBostaExchange_packPickupCustody_endsIt_zeroDispatchClaims() throws Exception {
        Shop s = new Shop("F1", true);
        String[] piece = s.piece(s.v);
        String pieceId = piece[0], barcode = piece[1];
        String li = gid("LineItem"), fo = gid("FulfillmentOrder");
        s.ret(li);
        s.fo(fo, "OPEN", li, 1);
        UUID shadow = s.shadow();
        assertThat(s.committed(s.v)).isEqualTo(1);

        // the courier app books the exchange in Bosta (type 30) …
        String awb = tn();
        ingest(s, delivery(awb, 30, "Exchange", 10, s.ref));
        assertThat(s.internalExchangeOrders()).isZero();
        assertThat(s.shipments(awb)).singleElement().satisfies(r -> assertThat(r.get("order_id")).isEqualTo(shadow));

        // … and fulfills the exchange line in Shopify WITH that tracking number: the FO closes, the shadow stays live.
        ObjectNode raw = mapper.createObjectNode();
        ObjectNode f = raw.putArray("fulfillments").addObject();
        String fulfillmentId = String.valueOf(IDS.incrementAndGet());
        f.put("id", Long.parseLong(fulfillmentId));
        f.put("status", "success");
        f.put("location_id", 7001L);
        f.put("service", "manual");
        f.put("tracking_company", "Bosta");
        f.put("tracking_number", awb);
        f.putArray("tracking_numbers").add(awb);
        ObjectNode line = f.putArray("line_items").addObject();
        line.put("id", Long.parseLong(li.substring(li.lastIndexOf('/') + 1)));
        line.put("quantity", 1);
        line.put("fulfillment_service", "manual");
        jdbc.update("UPDATE orders SET raw = ?::jsonb WHERE id = ?", raw.toString(), s.original);
        s.fo(fo, "CLOSED", li, 0);
        assertThat(s.shadowState()).isEqualTo("live");
        assertThat(s.alerts("shopify_exchange_shipped_in_shopify")).isEmpty();
        assertThat(s.queue()).as("released, with its courier leg").contains(shadow);

        // b4 (positive): the original's Bosta fulfillment carrying the exchange AWB is that exchange, not a conflict
        jdbc.update("INSERT INTO order_fulfillment_tracking (tenant_id, order_id, tracking_number, carrier_raw, carrier_class, " +
            "shopify_fulfillment_id, fulfillment_status) VALUES (?, ?, ?, 'Bosta', 'bosta', ?, 'success')", s.tenant, s.original, awb,
            fulfillmentId);
        BostaFulfillmentLinkService.Result link = fulfillmentLinks.attempt(s.tenant, s.original, awb, false);
        assertThat(link.verdict()).isEqualTo(BostaFulfillmentLinkService.Verdict.LINKED);
        assertThat(link.reason()).isEqualTo("linked via Shopify exchange #S3DF1-X1");
        assertThat(s.alerts("fulfillment_link_problem")).isEmpty();
        assertThat(s.shipments(awb)).as("no second shipment").hasSize(1);

        // pack
        var scan = s.as(() -> fulfill.scan(shadow, barcode, s.actor));
        assertThat(scan.success()).as(scan.toString()).isTrue();
        assertThat(s.as(() -> fulfill.complete(shadow, s.actor))).isEqualTo(1);

        // pickup scan → custody
        UUID pickup = pickups.openSession(s.tenant, s.actor, LocalDate.now(), null, null);
        assertThat(pickups.scan(s.tenant, pickup, s.actor, awb).outcome()).isEqualTo(PickupSessionService.ScanOutcome.ACCEPTED);
        pickups.closeSession(s.tenant, pickup, s.actor);
        assertThat(jdbc.queryForObject("SELECT status::text FROM pieces WHERE id = ?", String.class, pieceId)).isEqualTo("with_courier");

        assertThat(s.committed(s.v)).as("no longer committed").isZero();
        assertThat(s.queue()).doesNotContain(shadow);

        // the four-caller guard still holds: the Step-5a entry was reached for the piece and wrote nothing
        verify(spy, timeout(10_000).times(1)).onExchangeReplacementDispatched(eq(s.tenant), eq(pieceId));
        dispatched.computeIfAbsent(pieceId, k -> new CompletableFuture<>()).get(10, TimeUnit.SECONDS);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM shopify_inventory_adjustments WHERE tenant_id = ? " +
            "AND trigger_type = 'exchange_dispatch'", Integer.class, s.tenant)).isZero();
        verify(shopifyGateway, never()).pushExchangeDispatch(anyString(), anyString(), anyString(), anyString(),
            anyInt(), anyString(), anyString());
    }
}
