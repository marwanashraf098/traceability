package com.traceability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.traceability.integrations.shopify.ShopifyGateway;
import com.traceability.integrations.shopify.ShopifyReturnsGateway;
import com.traceability.integrations.shopify.ShopifyReturnsStore;
import com.traceability.integrations.shopify.ShopifyReturnsSync;
import com.traceability.integrations.shopify.ShopifySyncService;
import com.traceability.integrations.shopify.ShopifyTokenProvider;
import com.traceability.inventory.ExceptionService;
import com.traceability.inventory.FulfillService;
import com.traceability.inventory.ShopifyShadowOrders;
import com.traceability.inventory.VariantStockService;
import com.traceability.privacy.CustomerSubject;
import com.traceability.returncases.ShopifyReturnsView;
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
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Shopify-native returns S3c — Pick &amp; Pack for exchange SHADOW orders (approved Step 0, 2026-11-07). The held rule is
 * ONE SQL definition ({@link ShopifyShadowOrders#heldSql}): unprocessed, or its fulfillment order ON_HOLD / SCHEDULED →
 * HELD (Waiting for release, never in the queue, scan / complete / waybill refuse HELD_FOR_RELEASE); OPEN / IN_PROGRESS →
 * RELEASED — a normal pickable order (needs a courier leg like any order), packed without any backward status write.
 *
 *   q1  unprocessed: Waiting for release lists it (exchangeFor, items with product title, shortBy); not in the queue even
 *       with a created courier leg; scan HELD_FOR_RELEASE (EN + AR); complete 409; waybill HELD_FOR_RELEASE; the
 *       order card says heldForRelease
 *   q2  FO ON_HOLD → still held; FO OPEN → released: in the queue (exchange badge, exchangeFor, shortBy 0), out of
 *       Waiting; waybill OPEN; scan + complete pack it (status 'packed', forward write only); Shopify then fulfills the
 *       FO → the packed line keeps its quantity and no "shipped in Shopify" alert
 *   q3  released with no courier leg: not in the queue, counted by awaiting-waybill (like any order)
 *   q4  released → held again (FO back ON_HOLD) → back in Waiting, scan HELD_FOR_RELEASE
 *   q5  ended (declined): in neither list; scan NOT_PICKABLE; complete 409
 *   q6  Short by N in both modes: the queue row and the order card (waybill mode reads the same card) say shortBy 1
 *   q7  the shadow line carries its product title (order_items.raw, from the variant)
 *   q8  cross-tenant on app_user + RLS: tenant B's context sees none of A's waiting shadows and reads A's held shadow as
 *       NONE; A's own context (same-tenant control) sees and reads it
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ShadowPickPackTest {

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
    }

    @Autowired JdbcTemplate jdbc;                     // postgres — fixtures only
    @Autowired ObjectMapper mapper;
    @Autowired ShopifyReturnsStore returnsStore;
    @Autowired ShopifyReturnsSync returnsSync;
    @Autowired ShopifySyncService syncService;
    @Autowired FulfillService fulfill;
    @Autowired com.traceability.inventory.WaybillResolver waybills;
    @Autowired ExceptionService exceptions;
    @Autowired com.traceability.overview.OverviewService overview;
    @Autowired com.traceability.embedded.EmbeddedController embedded;
    @MockBean ShopifyReturnsGateway returnsGateway;
    @MockBean ShopifyGateway shopifyGateway;
    @MockBean ShopifyTokenProvider tokenProvider;

    JdbcTemplate appJdbc;
    DataSourceTransactionManager appTxm;
    ShopifyShadowOrders shadows;

    static final AtomicLong IDS = new AtomicLong(81_000_000_000_000L);
    static final Instant APPROVED = Instant.parse("2026-11-04T09:30:00Z");

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
    void clear() {
        TenantContext.clear();
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
    }

    // ── fixture ─────────────────────────────────────────────────────────────────────────────────────────────────

    /** A tenant + connected store with returns access, a main warehouse, variants V and W, and a delivered original. */
    class Shop {
        final UUID tenant = UUID.randomUUID(), store = UUID.randomUUID(), location = UUID.randomUUID();
        final String domain = "s3b-" + tenant.toString().substring(0, 8) + ".myshopify.com";
        final long restV = IDS.incrementAndGet(), restW = IDS.incrementAndGet();
        final UUID v, w;
        UUID original;
        String originalGid;

        Shop(String tag) { this(tag, "granted"); }

        Shop(String tag, String access) {
            jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?)", tenant, "S3b " + tag);
            jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status, connection_type, returns_access) " +
                "VALUES (?, ?, 'shopify', ?, 'connected', 'oauth', ?)", store, tenant, domain, access);
            jdbc.update("INSERT INTO locations (id, tenant_id, name, type, is_default, is_fulfillment) " +
                "VALUES (?, ?, 'Main Warehouse', 'warehouse', true, true)", location, tenant);
            UUID product = jdbc.queryForObject("INSERT INTO products (tenant_id, store_id, external_id, title, status) " +
                "VALUES (?, ?, ?, 'LINEN SHIRT', 'active') RETURNING id", UUID.class, tenant, store, "gid://shopify/Product/" + IDS.incrementAndGet());
            v = variant(product, restV, "M");
            w = variant(product, restW, "L");
            original = original("#S3B" + tag, true);
        }

        UUID variant(UUID product, long rest, String title) {
            return jdbc.queryForObject("INSERT INTO variants (tenant_id, product_id, external_id, sku, title) VALUES (?, ?, ?, ?, ?) RETURNING id",
                UUID.class, tenant, product, "gid://shopify/ProductVariant/" + rest, "S3B-" + rest, title);
        }

        /** A Shopify order; delivered = its forward leg moved past 'created' (frozen — out of Traced custody). */
        UUID original(String number, boolean delivered) {
            originalGid = "gid://shopify/Order/" + IDS.incrementAndGet();
            UUID o = jdbc.queryForObject(
                "INSERT INTO orders (tenant_id, store_id, external_id, number, status, payment_method, placed_at, raw, " +
                "    customer_name, customer_phone, shopify_address) " +
                "VALUES (?, ?, ?, ?, ?::order_status, 'cod', now() - interval '5 days', '{}'::jsonb, 'Mona Adel', '01012345678', " +
                "    '{\"city\": \"Cairo\", \"address1\": \"12 Nile St\"}'::jsonb) RETURNING id",
                UUID.class, tenant, store, originalGid, number, delivered ? "delivered" : "new");
            jdbc.update("INSERT INTO order_items (tenant_id, order_id, variant_id, quantity, external_id) VALUES (?, ?, ?, 1, ?)",
                tenant, o, v, "gid://shopify/LineItem/" + IDS.incrementAndGet());
            if (delivered) {
                UUID s = jdbc.queryForObject("INSERT INTO shipments (tenant_id, order_id, provider, tracking_number, internal_state, shipment_leg) " +
                    "VALUES (?, ?, 'bosta', ?, 'delivered', 'forward') RETURNING id", UUID.class, tenant, o, "9" + IDS.incrementAndGet());
                jdbc.update("INSERT INTO shipment_status_history (tenant_id, shipment_id, internal_state) VALUES (?, ?, 'delivered')", tenant, s);
            }
            return o;
        }

        void pieces(UUID variant, int n) {
            for (int i = 0; i < n; i++) {
                long k = IDS.incrementAndGet();
                jdbc.update("INSERT INTO pieces (id, tenant_id, variant_id, barcode, short_code, status, current_location_id) " +
                    "VALUES (?, ?, ?, ?, ?, 'available', ?)", String.format("01S3B%021d", k), tenant, variant, "S3B-" + k,
                    String.format("S%07d", k % 10_000_000), location);
            }
        }

        <T> T as(java.util.concurrent.Callable<T> body) { return TenantContext.runAs(tenant, body); }

        /** Upserts a Return through the real store, then reconciles (app_user). Returns the return gid. */
        String ret(String returnGid, String status, Object[]... xlines) {
            ObjectNode r = mapper.createObjectNode();
            r.put("id", returnGid);
            r.put("name", "#R" + IDS.get() % 1000);
            r.put("status", status);
            r.put("createdAt", APPROVED.minusSeconds(3600).toString());
            if ("REQUESTED".equals(status)) r.putNull("requestApprovedAt"); else r.put("requestApprovedAt", APPROVED.toString());
            r.putObject("order").put("id", originalGid);
            r.putObject("returnLineItems").putArray("nodes");
            ArrayNode xs = r.putObject("exchangeLineItems").putArray("nodes");
            for (Object[] x : xlines) {
                ObjectNode n = xs.addObject();
                n.put("id", (String) x[0]);
                n.put("variantId", "gid://shopify/ProductVariant/" + x[1]);
                n.put("quantity", (Integer) x[2]);
                n.put("processedQuantity", x.length > 3 ? (Integer) x[2] : 0);
                ArrayNode li = n.putArray("lineItems");
                if (x.length > 3) li.addObject().put("id", (String) x[3]);
            }
            as(() -> returnsStore.upsertReturn(store, original, r));
            reconcile();
            return returnGid;
        }

        /** Upserts a FulfillmentOrder holding one exchange order line, then reconciles. */
        void fo(String foGid, String status, String hold, String lineGid, int remaining, int total) {
            ObjectNode fo = mapper.createObjectNode();
            fo.put("id", foGid);
            fo.put("status", status);
            fo.putObject("order").put("id", originalGid);
            ArrayNode holds = fo.putArray("fulfillmentHolds");
            if (hold != null) holds.addObject().put("reason", hold);
            ArrayNode nodes = fo.putObject("lineItems").putArray("nodes");
            ObjectNode n = nodes.addObject();
            n.put("id", "gid://shopify/FulfillmentOrderLineItem/" + IDS.incrementAndGet());
            n.put("remainingQuantity", remaining);
            n.put("totalQuantity", total);
            n.putObject("lineItem").put("id", lineGid).putObject("variant").put("id", "gid://shopify/ProductVariant/" + restV);
            as(() -> returnsStore.upsertFulfillmentOrder(store, original, fo));
            reconcile();
        }

        ShopifyShadowOrders.Outcome reconcile() { return as(() -> shadows.reconcileForOrder(original)); }

        long committed(UUID variant) {
            return as(() -> new TransactionTemplate(appTxm).execute(s ->
                new VariantStockService(appJdbc).forVariant(new VariantStockService(appJdbc).computeAll(), variant).committed()));
        }

        List<Map<String, Object>> shadowOrders() {
            return jdbc.queryForList("SELECT o.*, s.state, s.end_reason, s.x_index FROM orders o " +
                "JOIN shopify_exchange_shadows s ON s.order_id = o.id WHERE o.tenant_id = ? ORDER BY s.x_index", tenant);
        }

        Map<String, Object> onlyShadow() {
            List<Map<String, Object>> l = shadowOrders();
            assertThat(l).hasSize(1);
            return l.get(0);
        }

        Map<String, Object> line(String exchangeLineGid) {
            return jdbc.queryForMap("SELECT sl.* FROM shopify_exchange_shadow_lines sl JOIN shopify_exchange_lines x " +
                "ON x.id = sl.exchange_line_id WHERE x.line_gid = ? AND sl.tenant_id = ?", exchangeLineGid, tenant);
        }

        List<Map<String, Object>> alerts(String type) {
            return as(() -> exceptions.detectAllOpen().stream().filter(a -> type.equals(a.get("type"))).toList());
        }
    }

    static String gid(String kind) { return "gid://shopify/" + kind + "/" + IDS.incrementAndGet(); }

    // ── S3c helpers ─────────────────────────────────────────────────────────────────────────────────────────────

    UUID actor(Shop s) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role) VALUES (?, ?, 'Packer', ?, 'h', 'owner')",
            id, s.tenant, "p-" + id + "@s3c.test");
        return id;
    }

    /** A created Bosta forward leg on the order; returns its tracking number. */
    String leg(Shop s, UUID orderId) {
        String tn = "9" + String.format("%09d", IDS.incrementAndGet() % 1_000_000_000L);
        jdbc.update("INSERT INTO shipments (tenant_id, order_id, provider, tracking_number, internal_state, shipment_leg) " +
            "VALUES (?, ?, 'bosta', ?, 'created', 'forward')", s.tenant, orderId, tn);
        return tn;
    }

    String availablePiece(Shop s, UUID variant) {
        return jdbc.queryForObject("SELECT barcode FROM pieces WHERE tenant_id = ? AND variant_id = ? AND status = 'available' " +
            "ORDER BY id LIMIT 1", String.class, s.tenant, variant);
    }

    UUID shadowId(Shop s) { return (UUID) s.onlyShadow().get("id"); }

    List<UUID> queueIds(Shop s) { return s.as(() -> fulfill.getQueue().stream().map(r -> (UUID) r.get("id")).toList()); }

    Map<String, Object> queueRow(Shop s, UUID id) {
        return s.as(() -> fulfill.getQueue().stream().filter(r -> id.equals(r.get("id"))).findFirst().orElseThrow());
    }

    List<Map<String, Object>> waiting(Shop s) { return s.as(() -> fulfill.getWaitingForRelease()); }

    // ── q1 ───────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    void q1_unprocessed_isHeld_waitingNotQueue_scanCompleteWaybillRefuse() {
        Shop s = new Shop("q1");
        s.pieces(s.v, 1);
        UUID packer = actor(s);
        s.ret(gid("Return"), "OPEN", new Object[]{gid("ExchangeLineItem"), s.restV, 1});
        UUID id = shadowId(s);
        String tn = leg(s, id);

        List<Map<String, Object>> w = waiting(s);
        assertThat(w).hasSize(1);
        assertThat(w.get(0)).containsEntry("id", id).containsEntry("number", "#S3Bq1-X1").containsEntry("shortBy", 0);
        assertThat(w.get(0).get("exchangeFor").toString()).contains("#S3Bq1").contains(s.original.toString());
        assertThat(w.get(0).get("items").toString()).contains("LINEN SHIRT").contains("M");
        assertThat(queueIds(s)).as("held: never in the queue, even with a courier leg").doesNotContain(id);

        var r = s.as(() -> fulfill.scan(id, availablePiece(s, s.v), packer));
        assertThat(r.code()).isEqualTo("HELD_FOR_RELEASE");
        assertThat(r.toString()).contains("waiting for release in Shopify").contains("شوبيفاي");
        assertThatThrownBy(() -> s.as(() -> fulfill.complete(id, packer)))
            .isInstanceOf(ResponseStatusException.class).hasMessageContaining("409");
        assertThat(s.as(() -> waybills.resolve(tn, packer)).code())
            .isEqualTo(com.traceability.inventory.WaybillResolver.Code.HELD_FOR_RELEASE);
        assertThat(s.as(() -> fulfill.getOrder(id))).containsEntry("heldForRelease", true).containsEntry("is_exchange", true);
        assertThat(s.as(() -> fulfill.getOrder(s.original))).doesNotContainKeys("heldForRelease", "exchangeFor", "shortBy");
    }

    // ── q2 ───────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    void q2_onHoldThenReleased_packsLikeAnyOrder_thenShopifyFulfillsWithoutAlert() {
        Shop s = new Shop("q2");
        s.pieces(s.v, 2);
        UUID packer = actor(s);
        String x = gid("ExchangeLineItem"), li = gid("LineItem"), fo = gid("FulfillmentOrder");
        s.ret(gid("Return"), "OPEN", new Object[]{x, s.restV, 1, li});
        UUID id = shadowId(s);
        String tn = leg(s, id);

        s.fo(fo, "ON_HOLD", "AWAITING_RETURN_ITEMS", li, 1, 1);
        assertThat(waiting(s)).extracting(m -> m.get("id")).containsExactly(id);
        assertThat(queueIds(s)).doesNotContain(id);

        s.fo(fo, "OPEN", null, li, 1, 1);
        assertThat(waiting(s)).isEmpty();
        assertThat(queueIds(s)).contains(id);
        Map<String, Object> row = queueRow(s, id);
        assertThat(row).containsEntry("is_exchange", true).containsEntry("shortBy", 0);
        assertThat(row.get("exchangeFor").toString()).contains("#S3Bq2");
        assertThat(s.as(() -> waybills.resolve(tn, packer)).code()).isEqualTo(com.traceability.inventory.WaybillResolver.Code.OPEN);

        var r = s.as(() -> fulfill.scan(id, availablePiece(s, s.v), packer));
        assertThat(r.success()).as(r.toString()).isTrue();
        assertThat(s.as(() -> fulfill.complete(id, packer))).isEqualTo(1);
        assertThat(String.valueOf(jdbc.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, id))).isEqualTo("packed");

        // Shopify fulfills the exchange (the merchant's courier app): the packed line stays, no alert.
        s.fo(fo, "CLOSED", null, li, 0, 1);
        assertThat(jdbc.queryForObject("SELECT quantity FROM order_items WHERE order_id = ?", Integer.class, id)).isEqualTo(1);
        assertThat(s.alerts("shopify_exchange_shipped_in_shopify")).isEmpty();
    }

    // ── q3 / q4 / q5 ─────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    void q3_releasedWithoutCourierLeg_awaitsWaybillLikeAnyOrder() {
        Shop s = new Shop("q3");
        s.pieces(s.v, 1);
        String li = gid("LineItem");
        int before = s.as(() -> fulfill.getAwaitingWaybillCount());
        s.ret(gid("Return"), "OPEN", new Object[]{gid("ExchangeLineItem"), s.restV, 1, li});
        s.fo(gid("FulfillmentOrder"), "OPEN", null, li, 1, 1);
        UUID id = shadowId(s);
        assertThat(queueIds(s)).doesNotContain(id);
        assertThat(waiting(s)).isEmpty();
        assertThat(s.as(() -> fulfill.getAwaitingWaybillCount())).isEqualTo(before + 1);
        leg(s, id);
        assertThat(queueIds(s)).contains(id);
        assertThat(s.as(() -> fulfill.getAwaitingWaybillCount())).isEqualTo(before);
    }

    @Test
    void q4_releasedThenHeldAgain_backToWaiting() {
        Shop s = new Shop("q4");
        s.pieces(s.v, 1);
        UUID packer = actor(s);
        String li = gid("LineItem"), fo = gid("FulfillmentOrder");
        s.ret(gid("Return"), "OPEN", new Object[]{gid("ExchangeLineItem"), s.restV, 1, li});
        UUID id = shadowId(s);
        leg(s, id);
        s.fo(fo, "OPEN", null, li, 1, 1);
        assertThat(queueIds(s)).contains(id);
        s.fo(fo, "ON_HOLD", "HIGH_RISK_OF_FRAUD", li, 1, 1);
        assertThat(queueIds(s)).doesNotContain(id);
        assertThat(waiting(s)).extracting(m -> m.get("id")).containsExactly(id);
        assertThat(s.as(() -> fulfill.scan(id, availablePiece(s, s.v), packer)).code()).isEqualTo("HELD_FOR_RELEASE");
        assertThat(s.committed(s.v)).as("re-held keeps the reservation").isEqualTo(1);
    }

    @Test
    void q5_ended_inNeitherList_notPickable() {
        Shop s = new Shop("q5");
        s.pieces(s.v, 1);
        UUID packer = actor(s);
        String rg = gid("Return"), x = gid("ExchangeLineItem");
        s.ret(rg, "OPEN", new Object[]{x, s.restV, 1});
        s.ret(rg, "DECLINED", new Object[]{x, s.restV, 1});
        UUID id = shadowId(s);
        leg(s, id);
        assertThat(queueIds(s)).doesNotContain(id);
        assertThat(waiting(s)).isEmpty();
        assertThat(s.as(() -> fulfill.scan(id, availablePiece(s, s.v), packer)).code()).isEqualTo("NOT_PICKABLE");
        assertThatThrownBy(() -> s.as(() -> fulfill.complete(id, packer))).isInstanceOf(ResponseStatusException.class);
    }

    // ── q6 / q7 ──────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    void q6_shortByN_inQueueRow_andOrderCard() {
        Shop s = new Shop("q6");                                       // no pieces of V at all
        String li = gid("LineItem");
        s.ret(gid("Return"), "OPEN", new Object[]{gid("ExchangeLineItem"), s.restV, 1, li});
        UUID id = shadowId(s);
        assertThat(waiting(s).get(0)).containsEntry("shortBy", 1);
        leg(s, id);
        s.fo(gid("FulfillmentOrder"), "OPEN", null, li, 1, 1);
        assertThat(queueRow(s, id)).containsEntry("shortBy", 1);
        assertThat(s.as(() -> fulfill.getOrder(id))).containsEntry("shortBy", 1).containsEntry("heldForRelease", false);
    }

    @Test
    void q7_shadowLine_carriesProductTitle() {
        Shop s = new Shop("q7");
        s.ret(gid("Return"), "OPEN", new Object[]{gid("ExchangeLineItem"), s.restV, 1});
        UUID id = shadowId(s);
        assertThat(jdbc.queryForObject("SELECT raw->>'title' FROM order_items WHERE order_id = ?", String.class, id))
            .isEqualTo("LINEN SHIRT");
        assertThat(jdbc.queryForObject("SELECT raw->>'name' FROM order_items WHERE order_id = ?", String.class, id))
            .isEqualTo("LINEN SHIRT - M");
        // A shadow made before titles (S3b) gets them on the next reconcile.
        jdbc.update("UPDATE order_items SET raw = NULL WHERE order_id = ?", id);
        s.reconcile();
        assertThat(jdbc.queryForObject("SELECT raw->>'title' FROM order_items WHERE order_id = ?", String.class, id))
            .isEqualTo("LINEN SHIRT");
    }

    // ── q8 ───────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    void q8_crossTenant_appUser_sameTenantControl() {
        Shop a = new Shop("q8a"), b = new Shop("q8b");
        a.ret(gid("Return"), "OPEN", new Object[]{gid("ExchangeLineItem"), a.restV, 1});
        UUID id = shadowId(a);
        FulfillService appFulfill = new FulfillService(appJdbc, null, null, 30);
        TransactionTemplate tx = new TransactionTemplate(appTxm);
        List<Map<String, Object>> waitA = a.as(() -> tx.execute(st -> appFulfill.getWaitingForRelease()));
        List<Map<String, Object>> waitB = b.as(() -> tx.execute(st -> appFulfill.getWaitingForRelease()));
        assertThat(waitA).as("same tenant: A's waiting list").extracting(m -> m.get("id")).containsExactly(id);
        assertThat(waitB).as("tenant B sees nothing of A").isEmpty();
        String heldCount = "SELECT COUNT(*) FROM warehouse_orders o WHERE " + ShopifyShadowOrders.heldSql("o");
        Integer own = a.as(() -> tx.execute(st -> appJdbc.queryForObject(heldCount, Integer.class)));
        Integer cross = b.as(() -> tx.execute(st -> appJdbc.queryForObject(heldCount, Integer.class)));
        assertThat(own).as("same tenant").isEqualTo(1);
        assertThat(cross).as("tenant B sees none of A's held shadows").isZero();
        ShopifyShadowOrders.PickState psA = a.as(() -> tx.execute(st -> ShopifyShadowOrders.pickState(appJdbc, a.tenant, id)));
        ShopifyShadowOrders.PickState psB = b.as(() -> tx.execute(st -> ShopifyShadowOrders.pickState(appJdbc, b.tenant, id)));
        assertThat(psA).isEqualTo(ShopifyShadowOrders.PickState.HELD);
        assertThat(psB).isEqualTo(ShopifyShadowOrders.PickState.NONE);
    }
}
