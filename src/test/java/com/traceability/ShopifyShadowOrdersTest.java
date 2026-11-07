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
 * Shopify-native returns S3b — exchange shadow orders + reservation from approval (V219). Mirror rows are written by the
 * real ShopifyReturnsStore; the reconcile, committed demand and the shortage rule run on an app_user connection (RLS).
 *
 *   s1  approved return (no FO yet): ONE shadow — prefix, origin, #…-X1, placed_at = approval, name / phone / address
 *       copied (never email), status 'confirmed', a line per exchange line; committed +1 from approval; idempotent
 *   s2  REQUESTED → nothing; approved later → created. No returns access → nothing (today's flow). Original still in
 *       custody (Q2) → nothing
 *   p1  held until Shopify releases it (S3c): not in the pick queue, scan() HELD_FOR_RELEASE, complete() 409
 *   e1  declined → ends; e2 cancelled → ends (committed back each time)
 *   e3  FO cancelled with no replacement → ends 'fo_cancelled'; with a replacement FO → still reserved (re-keyed)
 *   e4  FO CLOSED while unpacked → ends 'shipped_in_shopify'; the MEDIUM alert (while a piece is available) with
 *       traced.shopify-exchange.shipped-alert-enabled on (the default since S3c); off (kill switch) → no alert
 *   n1  an ENDED shadow (status 'confirmed', every line 0) is in no late-to-pack (normal + embedded), no Overview count,
 *       no alert of any type (shipped alert on too), no committed demand
 *   n2  a LIVE shadow is not late to pack (normal + embedded) while the S3c flag is off; with it on it counts
 *   e5  partial: 2 units, Shopify fulfilled 1 → 1 still reserved (per line)
 *   e6  held → released → re-held keeps the reservation
 *   d1  stand-down: a Shopify-native internal:exchange: replacement for the original + variant → no shadow / line 0
 *   x2  a second return on the same original → #…-X2
 *   k1  Short by N: oldest shadow covered first, ONE MEDIUM alert per variant naming the newest; the card says
 *       "kept aside" / "short by N"
 *   g1  GOLDEN: committed demand of every non-shadow order is byte-identical with shadows present
 *   a1  Gap A: a return sync with exchange lines also syncs the order's fulfillment orders → reconciled
 *   b1  Gap B: the import / missing-order path links exchange lines and reconciles
 *   c1  cross-tenant (app_user): tenant B never sees / reconciles tenant A's shadows; same-tenant control
 *   r1  GDPR: the customer subject includes the shadow order
 *   o1  ops backfill: dry run counts, writes nothing; apply creates; a second apply creates nothing
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ShopifyShadowOrdersTest {

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

        /**
         * S3d fixture: Shopify's REST fulfillment of an exchange line on the ORIGINAL (no tracking = a manual "mark as
         * fulfilled") — the evidence S3d needs before a fulfilled exchange line ends its reservation.
         */
        void untrackedFulfillment(String lineGid, int qty) {
            String id = lineGid.substring(lineGid.lastIndexOf('/') + 1);
            jdbc.update("UPDATE orders SET raw = jsonb_set(raw, '{fulfillments}', COALESCE(raw->'fulfillments', '[]'::jsonb) || " +
                "jsonb_build_array(jsonb_build_object('id', ?::text, 'status', 'success', 'location_id', 1, 'service', 'manual', " +
                "'tracking_number', NULL, 'line_items', jsonb_build_array(jsonb_build_object('id', ?::bigint, 'quantity', ?::int, " +
                "'fulfillment_service', 'manual'))))) WHERE id = ?",
                String.valueOf(IDS.incrementAndGet()), Long.parseLong(id), qty, original);
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

    // ── s1 / s2 ─────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    void s1_approvedReturn_oneShadow_copiesCustomer_reservesFromApproval_idempotent() {
        Shop s = new Shop("s1");
        assertThat(s.committed(s.v)).isZero();
        String x = gid("ExchangeLineItem");
        String r = s.ret(gid("Return"), "OPEN", new Object[]{x, s.restV, 1});

        Map<String, Object> o = s.onlyShadow();
        assertThat(o.get("external_id")).isEqualTo("internal:shopify-exchange:" + r);
        assertThat(o).containsEntry("origin", "shopify_exchange").containsEntry("number", "#S3Bs1-X1")
            .containsEntry("customer_name", "Mona Adel").containsEntry("customer_phone", "01012345678")
            .containsEntry("state", "live");
        assertThat(String.valueOf(o.get("status"))).isEqualTo("confirmed");
        assertThat(String.valueOf(o.get("shopify_address"))).contains("12 Nile St");
        assertThat(((java.sql.Timestamp) o.get("placed_at")).toInstant()).isEqualTo(APPROVED);
        assertThat(o.toString()).doesNotContain("@");                       // never an email
        assertThat(s.line(x)).containsEntry("quantity", 1).containsEntry("live_quantity", 1);
        assertThat(s.committed(s.v)).as("reserved from approval, no FO, no courier leg").isEqualTo(1);

        String before = s.shadowOrders().toString();
        assertThat(s.reconcile().created()).isZero();
        assertThat(s.reconcile().created()).isZero();
        assertThat(s.shadowOrders()).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM order_items oi JOIN shopify_exchange_shadows sx ON sx.order_id = oi.order_id " +
            "WHERE sx.tenant_id = ?", Integer.class, s.tenant)).isEqualTo(1);
        assertThat(s.committed(s.v)).isEqualTo(1);
        assertThat(s.shadowOrders().toString().replaceAll("updated_at=[^,]*", ""))
            .isEqualTo(before.replaceAll("updated_at=[^,]*", ""));
    }

    @Test
    void s2_requestedNothing_thenApproved_noAccessNothing_originalInCustodyNothing() {
        Shop s = new Shop("s2");
        String rg = gid("Return"), x = gid("ExchangeLineItem");
        s.ret(rg, "REQUESTED", new Object[]{x, s.restV, 1});
        assertThat(s.shadowOrders()).isEmpty();
        assertThat(s.committed(s.v)).isZero();
        s.ret(rg, "OPEN", new Object[]{x, s.restV, 1});
        assertThat(s.shadowOrders()).hasSize(1);

        Shop n = new Shop("s2n", "not_granted");
        n.ret(gid("Return"), "OPEN", new Object[]{gid("ExchangeLineItem"), n.restV, 1});
        assertThat(n.shadowOrders()).as("no returns access: today's flow, untouched").isEmpty();

        Shop q = new Shop("s2q");
        q.original = q.original("#S3Bq2", false);              // still in Traced custody (Q2)
        q.ret(gid("Return"), "OPEN", new Object[]{gid("ExchangeLineItem"), q.restV, 1});
        assertThat(q.shadowOrders()).as("Q2: the original's own line carries the demand").isEmpty();
    }

    // ── p1 ──────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    void p1_heldUntilReleased_notPackable() {
        Shop s = new Shop("p1");
        s.pieces(s.v, 1);
        s.ret(gid("Return"), "OPEN", new Object[]{gid("ExchangeLineItem"), s.restV, 1});
        UUID shadow = (UUID) s.onlyShadow().get("id");
        UUID worker = jdbc.queryForObject("INSERT INTO users (tenant_id, name, email, password_hash, role) " +
            "VALUES (?, 'W', ?, 'x', 'worker') RETURNING id", UUID.class, s.tenant, "w-" + UUID.randomUUID() + "@t.com");
        String queue = s.as(() -> String.valueOf(fulfill.getQueue()));
        assertThat(queue).doesNotContain(shadow.toString()).doesNotContain("-X1");
        String barcode = jdbc.queryForObject("SELECT barcode FROM pieces WHERE tenant_id = ? LIMIT 1", String.class, s.tenant);
        FulfillService.ScanResult r = s.as(() -> fulfill.scan(shadow, barcode, worker));
        assertThat(r.code()).isEqualTo("HELD_FOR_RELEASE");
        assertThatThrownBy(() -> s.as(() -> fulfill.complete(shadow, worker)))
            .isInstanceOf(ResponseStatusException.class).hasMessageContaining("waiting for release in Shopify");
    }

    // ── end rules ───────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    void e1_declined_ends() {
        Shop s = new Shop("e1");
        String rg = gid("Return"), x = gid("ExchangeLineItem");
        s.ret(rg, "OPEN", new Object[]{x, s.restV, 1});
        assertThat(s.committed(s.v)).isEqualTo(1);
        s.ret(rg, "DECLINED", new Object[]{x, s.restV, 1});
        assertThat(s.onlyShadow()).containsEntry("state", "ended").containsEntry("end_reason", "declined");
        assertThat(s.line(x)).containsEntry("live_quantity", 0).containsEntry("stop_reason", "declined");
        assertThat(s.committed(s.v)).isZero();
    }

    @Test
    void e2_cancelled_ends() {
        Shop s = new Shop("e2");
        String rg = gid("Return"), x = gid("ExchangeLineItem");
        s.ret(rg, "OPEN", new Object[]{x, s.restV, 1});
        s.ret(rg, "CANCELED", new Object[]{x, s.restV, 1});
        assertThat(s.onlyShadow()).containsEntry("state", "ended").containsEntry("end_reason", "cancelled");
        assertThat(s.committed(s.v)).isZero();
    }

    @Test
    void e3_foCancelledNoReplacement_ends_withReplacement_staysReserved() {
        Shop s = new Shop("e3");
        String rg = gid("Return"), x = gid("ExchangeLineItem"), li = gid("LineItem");
        s.ret(rg, "OPEN", new Object[]{x, s.restV, 1, li});
        String fo1 = gid("FulfillmentOrder");
        s.fo(fo1, "ON_HOLD", "AWAITING_PAYMENT", li, 1, 1);
        assertThat(s.committed(s.v)).isEqualTo(1);
        s.fo(fo1, "CANCELLED", null, li, 0, 1);
        assertThat(s.line(x)).containsEntry("live_quantity", 0).containsEntry("stop_reason", "fo_cancelled");
        assertThat(s.onlyShadow()).containsEntry("state", "ended");
        assertThat(s.committed(s.v)).isZero();
        // Shopify replaces the FO (moved location): the same exchange line is reserved again — re-keyed by the line.
        s.fo(gid("FulfillmentOrder"), "OPEN", null, li, 1, 1);
        assertThat(s.line(x)).containsEntry("live_quantity", 1);
        assertThat(s.line(x).get("stop_reason")).isNull();
        assertThat(s.onlyShadow()).containsEntry("state", "live");
        assertThat(s.committed(s.v)).isEqualTo(1);
    }

    @Test
    void e4_foClosedWhileUnpacked_ends_mediumAlertWhileAPieceIsAvailable() {
        Shop s = new Shop("e4");
        s.pieces(s.v, 1);
        String rg = gid("Return"), x = gid("ExchangeLineItem"), li = gid("LineItem");
        s.ret(rg, "OPEN", new Object[]{x, s.restV, 1, li});
        String fo = gid("FulfillmentOrder");
        s.fo(fo, "OPEN", null, li, 1, 1);
        s.untrackedFulfillment(li, 1);                       // S3d: Shopify's fulfillment, no tracking
        s.fo(fo, "CLOSED", null, li, 0, 1);
        // The reservation ends exactly the same with the alert off or on.
        assertThat(s.line(x)).containsEntry("live_quantity", 0).containsEntry("stop_reason", "shipped_in_shopify")
            .containsEntry("shipped_in_shopify", 1);
        assertThat(s.committed(s.v)).isZero();
        exceptions.setShippedInShopifyAlertEnabled(false);
        try {
            assertThat(s.alerts("shopify_exchange_shipped_in_shopify")).as("flag off (kill switch): no alert").isEmpty();
        } finally {
            exceptions.setShippedInShopifyAlertEnabled(true);
        }
        exceptions.setShippedInShopifyAlertEnabled(true);
        try {
        List<Map<String, Object>> a = s.alerts("shopify_exchange_shipped_in_shopify");
        assertThat(a).hasSize(1);
        assertThat(a.get(0)).containsEntry("severity", "MEDIUM").containsEntry("order_id", s.original)
            .containsEntry("shadow_number", "#S3Be4-X1");
        assertThat((String) a.get(0).get("descriptionEn")).startsWith("Exchange shipped in Shopify, piece still available in Traced");
        // no piece on the shelf → nothing "still available" → no alert
        jdbc.update("UPDATE pieces SET status = 'lost' WHERE tenant_id = ?", s.tenant);
        assertThat(s.alerts("shopify_exchange_shipped_in_shopify")).isEmpty();
        } finally {
            exceptions.setShippedInShopifyAlertEnabled(true);
        }
    }

    @Test
    void n1_endedShadow_inNoLateToPack_noOverviewCount_noAlert_noCommitted() {
        Shop s = new Shop("n1");
        s.pieces(s.v, 1);
        var lateBefore = s.as(() -> overview.lateToPack());
        // The embedded app's endpoint (role SHOPIFY_EMBEDDED) delegates to the same lateToPack — called through the bean.
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
            new org.springframework.security.authentication.UsernamePasswordAuthenticationToken("shop", "n/a",
                List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_SHOPIFY_EMBEDDED"))));
        var embeddedBefore = s.as(() -> embedded.lateToPack());
        String trendsBefore = s.as(() -> String.valueOf(overview.trends(null, null)));

        String rg = gid("Return"), x = gid("ExchangeLineItem");
        s.ret(rg, "OPEN", new Object[]{x, s.restV, 1});           // approved 2026-11-04 → older than 48 h
        s.ret(rg, "DECLINED", new Object[]{x, s.restV, 1});
        Map<String, Object> shadow = s.onlyShadow();
        assertThat(shadow).containsEntry("state", "ended");
        assertThat(String.valueOf(shadow.get("status"))).isEqualTo("confirmed");

        assertThat(s.as(() -> overview.lateToPack())).as("late to pack").isEqualTo(lateBefore);
        assertThat(s.as(() -> embedded.lateToPack())).as("late to pack, embedded app").isEqualTo(embeddedBefore);
        assertThat(s.as(() -> String.valueOf(overview.trends(null, null)))).as("Overview counts").isEqualTo(trendsBefore);
        assertThat(s.committed(s.v)).isZero();
        exceptions.setShippedInShopifyAlertEnabled(true);
        try {
            List<Map<String, Object>> all = s.as(() -> exceptions.detectAllOpen());
            String id = shadow.get("id").toString();
            assertThat(all).as("no alert of any type names the ended shadow")
                .noneMatch(a -> a.toString().contains(id) || a.toString().contains("#S3Bn1-X1"));
        } finally {
            exceptions.setShippedInShopifyAlertEnabled(true);
        }

        // Positive control: the same shadow while LIVE does show in late to pack (status 'confirmed' is pre-pack) —
        // with the S3c flag on (off, no shadow is late to pack: n2).
        s.ret(gid("Return"), "OPEN", new Object[]{gid("ExchangeLineItem"), s.restV, 1});
        overview.setShadowsPackable(true);
        try {
            assertThat(s.as(() -> overview.lateToPack()).overdue()).isEqualTo(lateBefore.overdue() + 1);
        } finally {
            overview.setShadowsPackable(true);
        }
    }

    @Test
    void n2_liveShadow_lateToPackOnlyWithS3cFlag() {
        Shop s = new Shop("n2");
        s.pieces(s.v, 1);
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
            new org.springframework.security.authentication.UsernamePasswordAuthenticationToken("shop", "n/a",
                List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_SHOPIFY_EMBEDDED"))));
        var lateBefore = s.as(() -> overview.lateToPack());
        var embeddedBefore = s.as(() -> embedded.lateToPack());

        s.ret(gid("Return"), "OPEN", new Object[]{gid("ExchangeLineItem"), s.restV, 1});   // approved > 48 h ago
        assertThat(s.onlyShadow()).containsEntry("state", "live");
        assertThat(s.committed(s.v)).isEqualTo(1);

        // Flag off (kill switch since S3c): not late to pack anywhere.
        overview.setShadowsPackable(false);
        try {
            assertThat(s.as(() -> overview.lateToPack())).as("late to pack, flag off").isEqualTo(lateBefore);
            assertThat(s.as(() -> embedded.lateToPack())).as("embedded, flag off").isEqualTo(embeddedBefore);
        } finally {
            overview.setShadowsPackable(true);
        }

        // Flag on (S3c): a live shadow is pre-pack work and counts, in both.
        overview.setShadowsPackable(true);
        try {
            var on = s.as(() -> overview.lateToPack());
            assertThat(on.overdue()).isEqualTo(lateBefore.overdue() + 1);
            assertThat(on.over48()).isEqualTo(lateBefore.over48() + 1);
            assertThat(s.as(() -> embedded.lateToPack()).overdue()).isEqualTo(embeddedBefore.overdue() + 1);
        } finally {
            overview.setShadowsPackable(true);
        }
    }

    @Test
    void e5_partial_perLine() {
        Shop s = new Shop("e5");
        String x = gid("ExchangeLineItem"), li = gid("LineItem");
        s.ret(gid("Return"), "OPEN", new Object[]{x, s.restV, 2, li});
        assertThat(s.committed(s.v)).isEqualTo(2);
        s.untrackedFulfillment(li, 1);                       // S3d: Shopify's fulfillment of 1, no tracking
        s.fo(gid("FulfillmentOrder"), "IN_PROGRESS", null, li, 1, 2);
        assertThat(s.line(x)).containsEntry("live_quantity", 1).containsEntry("shipped_in_shopify", 1);
        assertThat(s.onlyShadow()).containsEntry("state", "live");
        assertThat(s.committed(s.v)).isEqualTo(1);
    }

    @Test
    void e6_heldReleasedReheld_keepsTheReservation() {
        Shop s = new Shop("e6");
        String x = gid("ExchangeLineItem"), li = gid("LineItem"), fo = gid("FulfillmentOrder");
        s.ret(gid("Return"), "OPEN", new Object[]{x, s.restV, 1, li});
        for (String st : List.of("ON_HOLD", "OPEN", "ON_HOLD")) {
            s.fo(fo, st, "ON_HOLD".equals(st) ? "AWAITING_PAYMENT" : null, li, 1, 1);
            assertThat(s.committed(s.v)).as(st).isEqualTo(1);
            assertThat(s.onlyShadow()).containsEntry("state", "live");
        }
    }

    @Test
    void d1_nativeReplacementExists_standsDown() {
        Shop s = new Shop("d1");
        native30(s, s.v);
        s.ret(gid("Return"), "OPEN", new Object[]{gid("ExchangeLineItem"), s.restV, 1});
        assertThat(s.shadowOrders()).as("no double demand: the native replacement already reserves it").isEmpty();

        Shop t = new Shop("d1b");
        String x = gid("ExchangeLineItem"), rg = gid("Return");
        t.ret(rg, "OPEN", new Object[]{x, t.restV, 1});
        assertThat(t.committed(t.v)).isEqualTo(1);
        native30(t, t.v);
        t.reconcile();
        assertThat(t.line(x)).containsEntry("live_quantity", 0).containsEntry("stop_reason", "stood_down");
        assertThat(t.committed(t.v)).as("only the native replacement's own line").isEqualTo(1);
    }

    /** A dashboard Bosta type-30 for a Shopify-native exchange (-R reference) whose internal:exchange: order holds 1. */
    private void native30(Shop s, UUID variant) {
        String tn = "77" + IDS.incrementAndGet() % 100_000_000;
        UUID out = jdbc.queryForObject("INSERT INTO orders (tenant_id, store_id, external_id, number, status, payment_method, placed_at, raw) " +
            "VALUES (?, ?, ?, ?, 'new', 'cod', now(), '{}'::jsonb) RETURNING id", UUID.class, s.tenant, s.store, "internal:exchange:" + tn, "EXC-" + tn);
        jdbc.update("INSERT INTO order_items (tenant_id, order_id, variant_id, quantity) VALUES (?, ?, ?, 1)", s.tenant, out, variant);
        jdbc.update("INSERT INTO shipments (tenant_id, order_id, provider, tracking_number, internal_state, shipment_leg) " +
            "VALUES (?, ?, 'bosta', ?, 'created', 'forward')", s.tenant, out, tn);
        jdbc.update("INSERT INTO exchanges (tenant_id, tracking_number, status, raw, matched_order_id, outbound_order_id, " +
            "    outbound_variant_id, inbound_variant_id) VALUES (?, ?, 'matched', jsonb_build_object('businessReference', 'SHOP:#1001-R1'), ?, ?, ?, ?)",
            s.tenant, tn, s.original, out, variant, variant);
    }

    @Test
    void x2_secondReturn_X2() {
        Shop s = new Shop("x2");
        s.ret(gid("Return"), "OPEN", new Object[]{gid("ExchangeLineItem"), s.restV, 1});
        s.ret(gid("Return"), "OPEN", new Object[]{gid("ExchangeLineItem"), s.restW, 1});
        assertThat(s.shadowOrders()).extracting(m -> m.get("number")).containsExactly("#S3Bx2-X1", "#S3Bx2-X2");
    }

    // ── k1 Short by N ───────────────────────────────────────────────────────────────────────────────────────────

    @Test
    void k1_shortByN_oldestFirst_oneAlertPerVariant_cardSaysIt() {
        Shop s = new Shop("k1");
        s.pieces(s.v, 2);
        s.ret(gid("Return"), "OPEN", new Object[]{gid("ExchangeLineItem"), s.restV, 1});
        s.ret(gid("Return"), "OPEN", new Object[]{gid("ExchangeLineItem"), s.restV, 1});
        // X2 approved an hour after X1 (oldest first is by approval time)
        jdbc.update("UPDATE shopify_exchange_shadows SET approved_at = approved_at + interval '1 hour' WHERE x_index = 2 AND tenant_id = ?", s.tenant);
        assertThat(s.committed(s.v)).isEqualTo(2);
        assertThat(s.alerts("shopify_exchange_short")).as("never short while stock covers").isEmpty();

        // one piece goes missing: one piece, two shadows → the newer one (X2) is short by 1
        jdbc.update("UPDATE pieces SET status = 'lost' WHERE id = (SELECT id FROM pieces WHERE tenant_id = ? LIMIT 1)", s.tenant);
        List<Map<String, Object>> a = s.alerts("shopify_exchange_short");
        assertThat(a).hasSize(1);
        assertThat(a.get(0)).containsEntry("severity", "MEDIUM").containsEntry("short_by", 1).containsEntry("variant_id", s.v);
        assertThat((String) a.get(0).get("shadow_numbers")).isEqualTo("#S3Bk1-X2 (1)");
        assertThat((String) a.get(0).get("descriptionEn")).startsWith("Short by 1");

        List<Map<String, Object>> cards = s.as(() -> new TransactionTemplate(appTxm).execute(st ->
            new ShopifyReturnsView(appJdbc).cardsForOrder(s.original)));
        Map<String, String> states = new HashMap<>();
        for (Map<String, Object> c : cards) {
            @SuppressWarnings("unchecked") Map<String, Object> k = (Map<String, Object>) c.get("keptAside");
            states.put((String) k.get("number"), k.get("state") + ":" + k.get("shortBy"));
        }
        assertThat(states).containsEntry("#S3Bk1-X1", "kept_aside:0").containsEntry("#S3Bk1-X2", "short:1");

        s.pieces(s.v, 1);                                                       // stock covers both → clears itself
        assertThat(s.alerts("shopify_exchange_short")).isEmpty();
    }

    // ── g1 golden ───────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    void g1_committedOfNonShadowOrders_byteIdentical() {
        Shop s = new Shop("g1");
        s.pieces(s.w, 3);
        UUID normal = jdbc.queryForObject("INSERT INTO orders (tenant_id, store_id, external_id, number, status, is_self_pickup, " +
            "payment_method, placed_at, raw) VALUES (?, ?, ?, '#N1', 'new', true, 'cod', now(), '{}'::jsonb) RETURNING id",
            UUID.class, s.tenant, s.store, "gid://shopify/Order/" + IDS.incrementAndGet());
        jdbc.update("INSERT INTO order_items (tenant_id, order_id, variant_id, quantity) VALUES (?, ?, ?, 2)", s.tenant, normal, s.w);
        Map<UUID, VariantStockService.VariantStock> before = s.as(() -> new TransactionTemplate(appTxm).execute(st ->
            new VariantStockService(appJdbc).computeAll()));
        s.ret(gid("Return"), "OPEN", new Object[]{gid("ExchangeLineItem"), s.restV, 1});
        Map<UUID, VariantStockService.VariantStock> after = s.as(() -> new TransactionTemplate(appTxm).execute(st ->
            new VariantStockService(appJdbc).computeAll()));
        assertThat(after.get(s.w)).isEqualTo(before.get(s.w));
        assertThat(after.get(s.w).committed()).isEqualTo(2);
        Map<UUID, VariantStockService.VariantStock> rest = new HashMap<>(after);
        rest.remove(s.v);
        assertThat(rest).isEqualTo(before);
        assertThat(after.get(s.v).committed()).isEqualTo(1);
    }

    // ── a1 Gap A / b1 Gap B ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void a1_returnSyncWithExchangeLines_alsoSyncsTheOrdersFulfillmentOrders() {
        Shop s = new Shop("a1");
        String rg = gid("Return"), x = gid("ExchangeLineItem"), li = gid("LineItem"), fo = gid("FulfillmentOrder");
        ObjectNode r = mapper.createObjectNode();
        r.put("id", rg); r.put("status", "OPEN"); r.put("requestApprovedAt", APPROVED.toString());
        r.putObject("order").put("id", s.originalGid);
        r.putObject("returnLineItems").putArray("nodes");
        ObjectNode n = r.putObject("exchangeLineItems").putArray("nodes").addObject();
        n.put("id", x); n.put("variantId", "gid://shopify/ProductVariant/" + s.restV); n.put("quantity", 1); n.put("processedQuantity", 1);
        n.putArray("lineItems").addObject().put("id", li);
        ObjectNode f = mapper.createObjectNode();
        f.put("id", fo); f.put("status", "ON_HOLD"); f.putObject("order").put("id", s.originalGid);
        f.putArray("fulfillmentHolds").addObject().put("reason", "AWAITING_PAYMENT");
        ObjectNode fl = f.putObject("lineItems").putArray("nodes").addObject();
        fl.put("id", gid("FulfillmentOrderLineItem")); fl.put("remainingQuantity", 1); fl.put("totalQuantity", 1);
        fl.putObject("lineItem").put("id", li);
        when(returnsGateway.fetchReturn(any(), any(), eq(rg))).thenReturn(r);
        when(returnsGateway.fetchOrderFulfillmentOrderIds(any(), any(), eq(s.originalGid))).thenReturn(List.of(fo));
        when(returnsGateway.fetchFulfillmentOrder(any(), any(), eq(fo))).thenReturn(f);

        assertThat(returnsSync.syncReturn(s.tenant, s.store, s.domain, rg)).isEqualTo(ShopifyReturnsSync.Outcome.SYNCED);
        verify(returnsGateway).fetchOrderFulfillmentOrderIds(any(), any(), eq(s.originalGid));
        assertThat(jdbc.queryForObject("SELECT status FROM shopify_fulfillment_orders WHERE fo_gid = ?", String.class, fo)).isEqualTo("ON_HOLD");
        assertThat(s.onlyShadow()).containsEntry("state", "live");             // the hook reconciled (no manual call)
        assertThat(s.committed(s.v)).isEqualTo(1);

        // a return with NO exchange lines makes no FO call
        Mockito.clearInvocations(returnsGateway);
        String plain = gid("Return");
        ObjectNode p = r.deepCopy(); p.put("id", plain); ((ObjectNode) p.get("exchangeLineItems")).putArray("nodes");
        when(returnsGateway.fetchReturn(any(), any(), eq(plain))).thenReturn(p);
        returnsSync.syncReturn(s.tenant, s.store, s.domain, plain);
        verify(returnsGateway, never()).fetchOrderFulfillmentOrderIds(any(), any(), any());
    }

    @Test
    void b1_importPath_linksExchangeLines_andReconciles() {
        Shop s = new Shop("b1");
        String x = gid("ExchangeLineItem"), li = gid("LineItem");
        s.ret(gid("Return"), "OPEN", new Object[]{x, s.restV, 1, li});
        // The exchange line arrives on the original through the import (reconcile job's missing-order path), not a webhook.
        ShopifyGateway.Order order = new ShopifyGateway.Order(s.originalGid, "#S3Bb1", null, null, null, "paid", List.of(),
            new BigDecimal("950.00"), List.of(new ShopifyGateway.LineItem(li, 1, "gid://shopify/ProductVariant/" + s.restV, 1)),
            Instant.now(), mapper.createObjectNode().put("updatedAt", Instant.now().toString()));
        s.as(() -> { syncService.ingestMissingOrder(s.store, s.tenant, order); return null; });
        assertThat(jdbc.queryForObject("SELECT shopify_exchange_line_gid FROM order_items WHERE order_id = ? AND external_id = ?",
            String.class, s.original, li)).isEqualTo(x);
        assertThat(jdbc.queryForObject("SELECT quantity FROM order_items WHERE order_id = ? AND external_id = ?",
            Integer.class, s.original, li)).as("frozen original: the exchange line never carries demand there").isZero();
        assertThat(s.committed(s.v)).isEqualTo(1);
    }

    // ── c1 cross-tenant ─────────────────────────────────────────────────────────────────────────────────────────

    @Test
    void c1_crossTenant_appUser_sameTenantControl() {
        Shop a = new Shop("c1a"), b = new Shop("c1b");
        a.ret(gid("Return"), "OPEN", new Object[]{gid("ExchangeLineItem"), a.restV, 1});
        TransactionTemplate tx = new TransactionTemplate(appTxm);
        Integer own = TenantContext.runAs(a.tenant, () -> tx.execute(st ->
            appJdbc.queryForObject("SELECT count(*) FROM shopify_exchange_shadows", Integer.class)));
        Integer cross = TenantContext.runAs(b.tenant, () -> tx.execute(st ->
            appJdbc.queryForObject("SELECT count(*) FROM shopify_exchange_shadows", Integer.class)));
        assertThat(own).isEqualTo(1);
        assertThat(cross).isZero();
        // tenant B reconciling tenant A's original sees nothing and writes nothing
        assertThat(b.as(() -> shadows.reconcileForOrder(a.original)).created()).isZero();
        assertThat(b.committed(a.v)).isZero();
        assertThat(a.committed(a.v)).isEqualTo(1);
        Integer crossLines = TenantContext.runAs(b.tenant, () -> tx.execute(st ->
            appJdbc.queryForObject("SELECT count(*) FROM shopify_exchange_shadow_lines", Integer.class)));
        assertThat(crossLines).isZero();
    }

    // ── r1 GDPR / o1 backfill ───────────────────────────────────────────────────────────────────────────────────

    @Test
    void r1_gdprSubjectIncludesTheShadow() {
        Shop s = new Shop("r1");
        s.ret(gid("Return"), "OPEN", new Object[]{gid("ExchangeLineItem"), s.restV, 1});
        UUID shadow = (UUID) s.onlyShadow().get("id");
        CustomerSubject subject = CustomerSubject.resolve(jdbc, s.tenant, List.of(s.originalGid), null, false);
        assertThat(subject.orderIds()).contains(shadow);
    }

    @Test
    void o1_backfill_dryRunCounts_applyCreates_once() {
        Shop s = new Shop("o1");
        String rg = gid("Return");
        ObjectNode r = mapper.createObjectNode();
        r.put("id", rg); r.put("status", "OPEN"); r.put("requestApprovedAt", APPROVED.toString());
        r.putObject("order").put("id", s.originalGid);
        r.putObject("returnLineItems").putArray("nodes");
        ObjectNode n = r.putObject("exchangeLineItems").putArray("nodes").addObject();
        n.put("id", gid("ExchangeLineItem")); n.put("variantId", "gid://shopify/ProductVariant/" + s.restV); n.put("quantity", 2);
        n.putArray("lineItems");
        s.as(() -> returnsStore.upsertReturn(s.store, s.original, r));     // mirrored before S3b: no reconcile ran

        ShopifyShadowOrders.BackfillCounts dry = s.as(() -> shadows.backfill(true));
        assertThat(dry).isEqualTo(new ShopifyShadowOrders.BackfillCounts(1, 1, 2));
        assertThat(s.shadowOrders()).as("dry run writes nothing").isEmpty();
        ShopifyShadowOrders.BackfillCounts apply = s.as(() -> shadows.backfill(false));
        assertThat(apply).isEqualTo(new ShopifyShadowOrders.BackfillCounts(1, 1, 2));
        assertThat(s.shadowOrders()).hasSize(1);
        int again = s.as(() -> shadows.backfill(false)).shadowsToCreate();
        assertThat(again).isZero();
        assertThat(s.committed(s.v)).isEqualTo(2);
    }
}
