package com.traceability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.traceability.integrations.shopify.ShopifyGateway;
import com.traceability.integrations.shopify.ShopifyReturnsGateway;
import com.traceability.integrations.shopify.ShopifyReturnsStore;
import com.traceability.integrations.shopify.ShopifyTokenProvider;
import com.traceability.inventory.ExceptionService;
import com.traceability.inventory.FulfillService;
import com.traceability.inventory.LookupService;
import com.traceability.inventory.ShopifyShadowOrders;
import com.traceability.tenancy.TenantAwareDataSource;
import com.traceability.tenancy.TenantContext;
import org.junit.jupiter.api.*;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Shopify-native returns S3d — HOW Shopify fulfilled an exchange line decides whether the shadow's reservation ends
 * ({@code ShopifyShadowOrders.plan} + {@code evidence}, read from the ORIGINAL order's stored REST fulfillments), plus
 * Lookup nesting and the cross-tenant behaviour of the two "which shadow" rules.
 *
 * <pre>
 *   c1  FO CLOSED + a manual fulfillment WITH tracking → live + RELEASED (queue once it has a courier leg, not Waiting
 *       for release), no "shipped in Shopify" alert (flag on)
 *   c2  FO CLOSED + a fulfillment with NO tracking → ended 'shipped_in_shopify' + the alert
 *   c3  FO CLOSED, no stored fulfillment yet → still live; the fulfillment arrives (raw + reconcile): tracked → stays,
 *       untracked → ends
 *   c4  #1009: processed exchange, no FO Traced can see, return CLOSED → live / held; a readable untracked fulfillment
 *       of every unit ends it; a tracked one keeps it (released)
 *   c5  a fulfillment service (3PL) with tracking ends it — fulfillment-level service or line-level fulfillment_service
 *   l1  Lookup: the original lists its shadows (shopifyExchanges); the shadow's number opens the ORIGINAL with
 *       matchedExchange; a plain order has no shopifyExchanges key
 *   x1  app_user + RLS: shadowForReturnReference / shadowForLineItems under tenant B never return tenant A's shadow
 *       (same Return name in both tenants); same-tenant positive control
 * </pre>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ShadowFulfillmentEvidenceTest {

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

    @Autowired JdbcTemplate jdbc;                     // postgres — fixtures only
    @Autowired ObjectMapper mapper;
    @Autowired ShopifyReturnsStore returnsStore;
    @Autowired FulfillService fulfill;
    @Autowired ExceptionService exceptions;
    @Autowired LookupService lookup;
    @MockBean ShopifyReturnsGateway returnsGateway;
    @MockBean ShopifyGateway shopifyGateway;
    @MockBean ShopifyTokenProvider tokenProvider;

    JdbcTemplate appJdbc;
    DataSourceTransactionManager appTxm;
    ShopifyShadowOrders shadows;

    static final AtomicLong IDS = new AtomicLong(83_000_000_000_000L);
    static final Instant APPROVED = Instant.parse("2026-11-04T09:30:00Z");
    static final String ALERT = "shopify_exchange_shipped_in_shopify";

    @BeforeAll
    void appUser() {
        DataSource ds = new TenantAwareDataSource(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "app_user", "testpw"));
        appJdbc = new JdbcTemplate(ds);
        appTxm = new DataSourceTransactionManager(ds);
        shadows = new ShopifyShadowOrders(appJdbc, appTxm, mapper);
    }

    @BeforeEach
    void stubs() {
        Mockito.reset(returnsGateway, tokenProvider);
        when(tokenProvider.getValidToken(any())).thenReturn("tok");
    }

    @AfterEach
    void clear() { TenantContext.clear(); }

    static String gid(String kind) { return "gid://shopify/" + kind + "/" + IDS.incrementAndGet(); }
    static String num(String gid) { return gid.substring(gid.lastIndexOf('/') + 1); }

    // ── fixture ─────────────────────────────────────────────────────────────────────────────────────────────────

    /** A tenant + connected store with returns access, a main warehouse, variants V / W, and a delivered original. */
    class Shop {
        final UUID tenant = UUID.randomUUID(), store = UUID.randomUUID(), location = UUID.randomUUID();
        final String domain = "s3d-" + tenant.toString().substring(0, 8) + ".myshopify.com";
        final long restV = IDS.incrementAndGet(), restW = IDS.incrementAndGet();
        final UUID v, w;
        final UUID original;
        final String originalGid, number;

        Shop(String number) {
            this.number = number;
            jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?)", tenant, "S3d " + number);
            jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status, connection_type, returns_access) " +
                "VALUES (?, ?, 'shopify', ?, 'connected', 'oauth', 'granted')", store, tenant, domain);
            jdbc.update("INSERT INTO locations (id, tenant_id, name, type, is_default, is_fulfillment) " +
                "VALUES (?, ?, 'Main Warehouse', 'warehouse', true, true)", location, tenant);
            UUID product = jdbc.queryForObject("INSERT INTO products (tenant_id, store_id, external_id, title, status) " +
                "VALUES (?, ?, ?, 'LINEN SHIRT', 'active') RETURNING id", UUID.class, tenant, store, "gid://shopify/Product/" + IDS.incrementAndGet());
            v = variant(product, restV, "M");
            w = variant(product, restW, "L");
            originalGid = "gid://shopify/Order/" + IDS.incrementAndGet();
            original = jdbc.queryForObject(
                "INSERT INTO orders (tenant_id, store_id, external_id, number, status, payment_method, placed_at, raw, " +
                "    customer_name, customer_phone) " +
                "VALUES (?, ?, ?, ?, 'delivered', 'cod', now() - interval '5 days', '{}'::jsonb, 'Mona Adel', '01012345678') RETURNING id",
                UUID.class, tenant, store, originalGid, number);
            jdbc.update("INSERT INTO order_items (tenant_id, order_id, variant_id, quantity, external_id) VALUES (?, ?, ?, 1, ?)",
                tenant, original, v, "gid://shopify/LineItem/" + IDS.incrementAndGet());
            UUID s = jdbc.queryForObject("INSERT INTO shipments (tenant_id, order_id, provider, tracking_number, internal_state, shipment_leg) " +
                "VALUES (?, ?, 'bosta', ?, 'delivered', 'forward') RETURNING id", UUID.class, tenant, original, "9" + IDS.incrementAndGet());
            jdbc.update("INSERT INTO shipment_status_history (tenant_id, shipment_id, internal_state) VALUES (?, ?, 'delivered')", tenant, s);
        }

        UUID variant(UUID product, long rest, String title) {
            return jdbc.queryForObject("INSERT INTO variants (tenant_id, product_id, external_id, sku, title) VALUES (?, ?, ?, ?, ?) RETURNING id",
                UUID.class, tenant, product, "gid://shopify/ProductVariant/" + rest, "S3D-" + rest, title);
        }

        void pieces(UUID variant, int n) {
            for (int i = 0; i < n; i++) {
                long k = IDS.incrementAndGet();
                jdbc.update("INSERT INTO pieces (id, tenant_id, variant_id, barcode, short_code, status, current_location_id) " +
                    "VALUES (?, ?, ?, ?, ?, 'available', ?)", String.format("01S3D%021d", k), tenant, variant, "S3D-" + k,
                    String.format("D%07d", k % 10_000_000), location);
            }
        }

        <T> T as(java.util.concurrent.Callable<T> body) { return TenantContext.runAs(tenant, body); }

        /** Upserts a Return (name given) with ONE exchange line of V (processed onto {@code lineGid} when not null). */
        void ret(String returnGid, String name, String status, String xGid, int qty, String lineGid) {
            ObjectNode r = mapper.createObjectNode();
            r.put("id", returnGid);
            r.put("name", name);
            r.put("status", status);
            r.put("createdAt", APPROVED.minusSeconds(3600).toString());
            r.put("requestApprovedAt", APPROVED.toString());
            r.putObject("order").put("id", originalGid);
            r.putObject("returnLineItems").putArray("nodes");
            ArrayNode xs = r.putObject("exchangeLineItems").putArray("nodes");
            ObjectNode n = xs.addObject();
            n.put("id", xGid);
            n.put("variantId", "gid://shopify/ProductVariant/" + restV);
            n.put("quantity", qty);
            n.put("processedQuantity", lineGid != null ? qty : 0);
            ArrayNode li = n.putArray("lineItems");
            if (lineGid != null) li.addObject().put("id", lineGid);
            as(() -> returnsStore.upsertReturn(store, original, r));
            reconcile();
        }

        void fo(String foGid, String status, String lineGid, int remaining, int total) {
            ObjectNode fo = mapper.createObjectNode();
            fo.put("id", foGid);
            fo.put("status", status);
            fo.putObject("order").put("id", originalGid);
            fo.putArray("fulfillmentHolds");
            ObjectNode n = fo.putObject("lineItems").putArray("nodes").addObject();
            n.put("id", "gid://shopify/FulfillmentOrderLineItem/" + IDS.incrementAndGet());
            n.put("remainingQuantity", remaining);
            n.put("totalQuantity", total);
            n.putObject("lineItem").put("id", lineGid).putObject("variant").put("id", "gid://shopify/ProductVariant/" + restV);
            as(() -> returnsStore.upsertFulfillmentOrder(store, original, fo));
            reconcile();
        }

        /** Stores the original's REST fulfillments (what orders/updated writes into orders.raw). */
        void storeFulfillments(ObjectNode... fs) {
            ObjectNode raw = mapper.createObjectNode();
            ArrayNode arr = raw.putArray("fulfillments");
            for (ObjectNode f : fs) arr.add(f);
            jdbc.update("UPDATE orders SET raw = ?::jsonb WHERE id = ?", raw.toString(), original);
        }

        ShopifyShadowOrders.Outcome reconcile() { return as(() -> shadows.reconcileForOrder(original)); }

        Map<String, Object> shadow() {
            List<Map<String, Object>> l = jdbc.queryForList(
                "SELECT s.order_id, s.state, s.end_reason, o.number FROM shopify_exchange_shadows s " +
                "JOIN orders o ON o.id = s.order_id WHERE s.tenant_id = ? AND s.original_order_id = ?", tenant, original);
            assertThat(l).hasSize(1);
            return l.get(0);
        }

        UUID shadowId() { return (UUID) shadow().get("order_id"); }

        Map<String, Object> line() {
            return jdbc.queryForMap("SELECT live_quantity, shipped_in_shopify, stop_reason FROM shopify_exchange_shadow_lines " +
                "WHERE tenant_id = ?", tenant);
        }

        void leg(UUID orderId) {
            jdbc.update("INSERT INTO shipments (tenant_id, order_id, provider, tracking_number, internal_state, shipment_leg) " +
                "VALUES (?, ?, 'bosta', ?, 'created', 'forward')", tenant, orderId,
                "8" + String.format("%09d", IDS.incrementAndGet() % 1_000_000_000L));
        }

        List<UUID> queue() { return as(() -> fulfill.getQueue().stream().map(r -> (UUID) r.get("id")).toList()); }
        List<Object> waiting() { return as(() -> fulfill.getWaitingForRelease().stream().map(r -> r.get("id")).toList()); }
        ShopifyShadowOrders.PickState pickState() {
            UUID id = shadowId();
            return as(() -> new TransactionTemplate(appTxm).execute(st -> ShopifyShadowOrders.pickState(appJdbc, tenant, id)));
        }
        List<Map<String, Object>> alerts() {
            return as(() -> exceptions.detectAllOpen().stream().filter(a -> ALERT.equals(a.get("type"))).toList());
        }
    }

    /** A REST fulfillment of the original covering {@code qty} units of {@code lineGid}. */
    ObjectNode fulfillment(String lineGid, int qty, String tracking, String service, String lineService) {
        ObjectNode f = mapper.createObjectNode();
        f.put("id", IDS.incrementAndGet());
        f.put("status", "success");
        f.put("location_id", 7_001L);
        f.put("service", service);
        f.put("tracking_company", "Bosta");
        if (tracking != null) {
            f.put("tracking_number", tracking);
            f.putArray("tracking_numbers").add(tracking);
        } else {
            f.putNull("tracking_number");
            f.putArray("tracking_numbers");
        }
        ObjectNode li = f.putArray("line_items").addObject();
        li.put("id", Long.parseLong(num(lineGid)));
        li.put("quantity", qty);
        li.put("fulfillment_service", lineService);
        return f;
    }

    /** OPEN return, processed onto a fresh line gid; its FO already CLOSED (Shopify fulfilled it). */
    String closedExchange(Shop s) {
        String li = gid("LineItem");
        s.ret(gid("Return"), s.number + "-R1", "OPEN", gid("ExchangeLineItem"), 1, li);
        return li;
    }

    // ── c1 / c2 ──────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    void c1_trackedFulfillment_staysLiveAndReleased_inQueueWithLeg_noAlert() {
        Shop s = new Shop("#S3Dc1");
        s.pieces(s.v, 2);
        String li = closedExchange(s);
        s.storeFulfillments(fulfillment(li, 1, "4471234567", "manual", "manual"));
        s.fo(gid("FulfillmentOrder"), "CLOSED", li, 0, 1);

        assertThat(s.shadow()).containsEntry("state", "live");
        assertThat(s.line()).containsEntry("live_quantity", 1).containsEntry("shipped_in_shopify", 1)
            .containsEntry("stop_reason", null);
        assertThat(s.pickState()).as("tracked = released").isEqualTo(ShopifyShadowOrders.PickState.PACKABLE);
        UUID id = s.shadowId();
        assertThat(s.waiting()).doesNotContain(id);
        assertThat(s.queue()).as("no courier leg yet").doesNotContain(id);
        s.leg(id);
        assertThat(s.queue()).contains(id);
        assertThat(s.waiting()).doesNotContain(id);
        assertThat(s.alerts()).isEmpty();
    }

    @Test
    void c2_untrackedFulfillment_endsShippedInShopify_withAlert() {
        Shop s = new Shop("#S3Dc2");
        s.pieces(s.v, 2);
        String li = closedExchange(s);
        s.storeFulfillments(fulfillment(li, 1, null, "manual", "manual"));
        s.fo(gid("FulfillmentOrder"), "CLOSED", li, 0, 1);

        assertThat(s.shadow()).containsEntry("state", "ended");
        assertThat(s.line()).containsEntry("live_quantity", 0).containsEntry("shipped_in_shopify", 1)
            .containsEntry("stop_reason", "shipped_in_shopify");
        UUID id = s.shadowId();
        s.leg(id);
        assertThat(s.queue()).doesNotContain(id);
        assertThat(s.alerts()).hasSize(1);
        assertThat(s.alerts().get(0)).containsEntry("order_id", s.original).containsEntry("shadow_order_id", id);
    }

    // ── c3 ───────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    void c3a_foClosedWithoutStoredFulfillment_staysLive_thenTrackedArrives_staysLive() {
        Shop s = new Shop("#S3Dc3a");
        s.pieces(s.v, 1);
        String li = closedExchange(s);
        s.fo(gid("FulfillmentOrder"), "CLOSED", li, 0, 1);
        assertThat(s.shadow()).as("unknown never ends").containsEntry("state", "live");
        assertThat(s.line()).containsEntry("live_quantity", 1).containsEntry("stop_reason", null);
        assertThat(s.alerts()).isEmpty();

        s.storeFulfillments(fulfillment(li, 1, "4479990001", "manual", "manual"));
        s.reconcile();
        assertThat(s.shadow()).containsEntry("state", "live");
        assertThat(s.line()).containsEntry("live_quantity", 1).containsEntry("stop_reason", null);
        assertThat(s.alerts()).isEmpty();
    }

    @Test
    void c3b_foClosedWithoutStoredFulfillment_staysLive_thenUntrackedArrives_ends() {
        Shop s = new Shop("#S3Dc3b");
        s.pieces(s.v, 1);
        String li = closedExchange(s);
        s.fo(gid("FulfillmentOrder"), "CLOSED", li, 0, 1);
        assertThat(s.shadow()).containsEntry("state", "live");

        s.storeFulfillments(fulfillment(li, 1, null, "manual", "manual"));
        s.reconcile();
        assertThat(s.shadow()).containsEntry("state", "ended");
        assertThat(s.line()).containsEntry("live_quantity", 0).containsEntry("stop_reason", "shipped_in_shopify");
        assertThat(s.alerts()).hasSize(1);
    }

    @Test
    void c3c_nonRestFulfillmentShape_isUnknown_neverEnds() {
        Shop s = new Shop("#S3Dc3c");
        String li = closedExchange(s);
        ObjectNode graphqlShape = fulfillment(li, 1, null, "manual", "manual");
        graphqlShape.remove("location_id");                    // not the REST shape → unreadable
        s.storeFulfillments(graphqlShape);
        s.fo(gid("FulfillmentOrder"), "CLOSED", li, 0, 1);
        assertThat(s.shadow()).containsEntry("state", "live");
    }

    // ── c4 (#1009) ───────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    void c4a_processedNoVisibleFo_returnClosed_staysLiveHeld() {
        Shop s = new Shop("#S3Dc4a");
        s.pieces(s.v, 1);
        String rg = gid("Return"), x = gid("ExchangeLineItem"), li = gid("LineItem");
        s.ret(rg, "#S3Dc4a-R1", "OPEN", x, 1, li);
        s.ret(rg, "#S3Dc4a-R1", "CLOSED", x, 1, li);
        assertThat(s.shadow()).containsEntry("state", "live");
        assertThat(s.line()).containsEntry("live_quantity", 1).containsEntry("stop_reason", null);
        assertThat(s.pickState()).isEqualTo(ShopifyShadowOrders.PickState.HELD);
        assertThat(s.waiting()).contains(s.shadowId());
    }

    @Test
    void c4b_processedNoVisibleFo_returnClosed_readableUntrackedFulfillmentOfEveryUnit_ends() {
        Shop s = new Shop("#S3Dc4b");
        String rg = gid("Return"), x = gid("ExchangeLineItem"), li = gid("LineItem");
        s.ret(rg, "#S3Dc4b-R1", "OPEN", x, 1, li);
        assertThat(s.shadow()).containsEntry("state", "live");        // (a reconcile never creates an ended shadow)
        s.storeFulfillments(fulfillment(li, 1, null, "manual", "manual"));
        s.ret(rg, "#S3Dc4b-R1", "CLOSED", x, 1, li);
        assertThat(s.shadow()).containsEntry("state", "ended");
        assertThat(s.line()).containsEntry("stop_reason", "shipped_in_shopify");
    }

    @Test
    void c4c_processedNoVisibleFo_returnClosed_trackedFulfillment_staysLiveReleased() {
        Shop s = new Shop("#S3Dc4c");
        String rg = gid("Return"), x = gid("ExchangeLineItem"), li = gid("LineItem");
        s.ret(rg, "#S3Dc4c-R1", "OPEN", x, 1, li);
        s.storeFulfillments(fulfillment(li, 1, "4471112223", "manual", "manual"));
        s.ret(rg, "#S3Dc4c-R1", "CLOSED", x, 1, li);
        assertThat(s.shadow()).containsEntry("state", "live");
        assertThat(s.pickState()).isEqualTo(ShopifyShadowOrders.PickState.PACKABLE);
    }

    // ── c5 (3PL) ─────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    void c5a_fulfillmentService_withTracking_ends() {
        Shop s = new Shop("#S3Dc5a");
        String li = closedExchange(s);
        s.storeFulfillments(fulfillment(li, 1, "3PL-77001", "shipwire", "manual"));
        s.fo(gid("FulfillmentOrder"), "CLOSED", li, 0, 1);
        assertThat(s.shadow()).containsEntry("state", "ended");
        assertThat(s.line()).containsEntry("stop_reason", "shipped_in_shopify");
    }

    @Test
    void c5b_lineFulfillmentService_withTracking_ends() {
        Shop s = new Shop("#S3Dc5b");
        String li = closedExchange(s);
        s.storeFulfillments(fulfillment(li, 1, "3PL-77002", "manual", "amazon_marketplace_web"));
        s.fo(gid("FulfillmentOrder"), "CLOSED", li, 0, 1);
        assertThat(s.shadow()).containsEntry("state", "ended");
        assertThat(s.line()).containsEntry("stop_reason", "shipped_in_shopify");
    }

    // ── l1 ───────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    void l1_lookup_nestsShadows_shadowNumberOpensOriginal_plainOrderHasNoKey() {
        Shop s = new Shop("#S3Dl1");
        s.ret(gid("Return"), "#S3Dl1-R1", "OPEN", gid("ExchangeLineItem"), 1, null);
        UUID shadowId = s.shadowId();

        Map<String, Object> r = s.as(() -> lookup.lookupOrder("#S3Dl1"));
        assertThat(r).containsEntry("orderId", s.original.toString()).doesNotContainKey("matchedExchange");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> xs = (List<Map<String, Object>>) r.get("shopifyExchanges");
        assertThat(xs).hasSize(1);
        assertThat(xs.get(0)).containsEntry("orderId", shadowId.toString()).containsEntry("orderNumber", "#S3Dl1-X1")
            .containsEntry("status", "confirmed").containsEntry("state", "live");

        Map<String, Object> viaShadow = s.as(() -> lookup.lookupOrder("S3Dl1-X1"));
        assertThat(viaShadow).containsEntry("orderId", s.original.toString()).containsEntry("orderNumber", "#S3Dl1")
            .containsEntry("matchedExchange", "#S3Dl1-X1").containsKey("shopifyExchanges");

        UUID plain = jdbc.queryForObject("INSERT INTO orders (tenant_id, store_id, external_id, number, status, placed_at) " +
            "VALUES (?, ?, ?, '#S3Dl1-plain', 'new', now()) RETURNING id", UUID.class, s.tenant, s.store, gid("Order"));
        Map<String, Object> p = s.as(() -> lookup.lookupOrder("#S3Dl1-plain"));
        assertThat(p).containsEntry("orderId", plain.toString()).doesNotContainKeys("shopifyExchanges", "matchedExchange");
    }

    // ── x1 ───────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    void x1_crossTenant_appUser_shadowRules_neverReturnAnotherTenantsShadow() {
        Shop a = new Shop("#S3DX1"), b = new Shop("#S3DX1");             // same number, same Return name
        String liA = gid("LineItem"), liB = gid("LineItem");
        a.ret(gid("Return"), "#S3DX1-R1", "OPEN", gid("ExchangeLineItem"), 1, liA);
        b.ret(gid("Return"), "#S3DX1-R1", "OPEN", gid("ExchangeLineItem"), 1, liB);
        UUID shadowA = a.shadowId(), shadowB = b.shadowId();
        TransactionTemplate tx = new TransactionTemplate(appTxm);
        String ref = "S3DX1:#S3DX1-R1";

        UUID ownA = a.as(() -> tx.execute(st -> ShopifyShadowOrders.shadowForReturnReference(appJdbc, a.tenant, ref)));
        UUID ownB = b.as(() -> tx.execute(st -> ShopifyShadowOrders.shadowForReturnReference(appJdbc, b.tenant, ref)));
        UUID bAskingForA = b.as(() -> tx.execute(st -> ShopifyShadowOrders.shadowForReturnReference(appJdbc, a.tenant, ref)));
        assertThat(ownA).as("same tenant control").isEqualTo(shadowA);
        assertThat(ownB).as("tenant B gets its own shadow, never A's").isEqualTo(shadowB);
        assertThat(bAskingForA).as("B's context passing A's tenant id sees nothing (RLS)").isNull();

        UUID lineA = a.as(() -> tx.execute(st -> ShopifyShadowOrders.shadowForLineItems(appJdbc, a.tenant, a.original, List.of(liA))));
        UUID lineBForA = b.as(() -> tx.execute(st -> ShopifyShadowOrders.shadowForLineItems(appJdbc, a.tenant, a.original, List.of(liA))));
        UUID lineBOwnIdA = b.as(() -> tx.execute(st -> ShopifyShadowOrders.shadowForLineItems(appJdbc, b.tenant, a.original, List.of(liA))));
        UUID lineB = b.as(() -> tx.execute(st -> ShopifyShadowOrders.shadowForLineItems(appJdbc, b.tenant, b.original, List.of(liB))));
        assertThat(lineA).as("same tenant control").isEqualTo(shadowA);
        assertThat(lineBForA).isNull();
        assertThat(lineBOwnIdA).isNull();
        assertThat(lineB).isEqualTo(shadowB);
    }
}
