package com.traceability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.traceability.identity.CustomUserDetails;
import com.traceability.integrations.bosta.BostaDelivery;
import com.traceability.integrations.bosta.BostaStateMapper;
import com.traceability.integrations.shopify.ShopifyGateway;
import com.traceability.integrations.shopify.ShopifyReturnsGateway;
import com.traceability.integrations.shopify.ShopifyReturnsStore;
import com.traceability.integrations.shopify.ShopifyTokenProvider;
import com.traceability.inventory.ExceptionService;
import com.traceability.inventory.FulfillService;
import com.traceability.inventory.ShipmentLinkService;
import com.traceability.inventory.ShopifyExchangeController;
import com.traceability.inventory.ShopifyShadowOrders;
import com.traceability.inventory.VariantStockService;
import com.traceability.tenancy.TenantAwareDataSource;
import com.traceability.tenancy.TenantContext;
import org.jobrunr.scheduling.JobScheduler;
import org.junit.jupiter.api.*;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Shopify-native returns S3d review (629736f6) — the safety net around exchange shadows.
 *
 * <pre>
 *   t1   a Bosta type 20 (RETURN TO ORIGIN) with "…:#&lt;order&gt;-R1" while a live shadow exists → never the shadow's
 *        forward leg (the shadow has no shipment); it goes today's path (the original)
 *   t1b  control: a SEND (10) with the same reference IS the shadow's forward leg
 *   w1   #1009 shape (processed, no visible FO, return CLOSED) approved 8 days ago → one shopify_exchange_still_waiting
 *        (MEDIUM, the ORIGINAL's order_id, the shadow number)
 *   w2   the same approved 2 days ago → none
 *   w3   FO ON_HOLD (explained), 8 days → none
 *   w4   unprocessed OPEN return (explained), 8 days → none
 *   w5   FO CLOSED with no stored fulfillment, 8 days → HELD, in Waiting for release with unexplained=true, the alert;
 *        then a tracked fulfillment + reconcile → released, the alert gone
 *   r1   owner releases (controller bean, OWNER): ended manual_release, lines 0 / stop manual_release, order_items 0,
 *        committed 0, one audit row, alert gone, not waiting; a new OPEN FO + reconcile → stays ended (sticky)
 *   r2   manager allowed; worker → AccessDeniedException
 *   r3   an active allocation on the shadow → 409
 *   r4   already ended → 409; unknown id → 404
 *   r5   app_user + RLS: tenant B's context can't release A's shadow (NOT_FOUND); A's own context can
 * </pre>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ShadowSafetyNetTest {

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
    @Autowired ShipmentLinkService links;
    @Autowired BostaStateMapper stateMapper;
    @Autowired ShopifyExchangeController controller;
    @MockBean JobScheduler jobScheduler;
    @MockBean ShopifyReturnsGateway returnsGateway;
    @MockBean ShopifyGateway shopifyGateway;
    @MockBean ShopifyTokenProvider tokenProvider;

    JdbcTemplate appJdbc;
    DataSourceTransactionManager appTxm;
    ShopifyShadowOrders shadows;

    static final AtomicLong IDS = new AtomicLong(86_000_000_000_000L);
    static final Instant APPROVED = Instant.parse("2026-11-04T09:30:00Z");
    static final String WAITING = "shopify_exchange_still_waiting";

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
        SecurityContextHolder.clearContext();
    }

    static String gid(String kind) { return "gid://shopify/" + kind + "/" + IDS.incrementAndGet(); }
    static String num(String gid) { return gid.substring(gid.lastIndexOf('/') + 1); }
    static String tn() { return String.valueOf(5_000_000_000L + IDS.incrementAndGet() % 4_000_000_000L); }

    // ── fixture ─────────────────────────────────────────────────────────────────────────────────────────────────

    /** A tenant + connected store (returns access), users, a main warehouse, variant V, and a delivered original. */
    class Shop {
        final UUID tenant = UUID.randomUUID(), store = UUID.randomUUID(), location = UUID.randomUUID();
        final UUID owner = UUID.randomUUID(), manager = UUID.randomUUID(), worker = UUID.randomUUID();
        final String domain = "s3dsn-" + tenant.toString().substring(0, 8) + ".myshopify.com";
        final long restV = IDS.incrementAndGet();
        final UUID v, original;
        final String originalGid, number;

        Shop(String tag) {
            this.number = "#S3D" + tag;
            jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?)", tenant, "S3d " + tag);
            for (Object[] u : new Object[][]{{owner, "owner"}, {manager, "manager"}, {worker, "worker"}}) {
                jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role) VALUES (?, ?, ?, ?, 'h', ?::user_role)",
                    u[0], tenant, "U " + u[1], u[1] + "-" + u[0] + "@s3dsn.test", u[1]);
            }
            jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status, connection_type, returns_access) " +
                "VALUES (?, ?, 'shopify', ?, 'connected', 'oauth', 'granted')", store, tenant, domain);
            jdbc.update("INSERT INTO locations (id, tenant_id, name, type, is_default, is_fulfillment) " +
                "VALUES (?, ?, 'Main Warehouse', 'warehouse', true, true)", location, tenant);
            UUID product = jdbc.queryForObject("INSERT INTO products (tenant_id, store_id, external_id, title, status) " +
                "VALUES (?, ?, ?, 'LINEN SHIRT', 'active') RETURNING id", UUID.class, tenant, store, gid("Product"));
            v = jdbc.queryForObject("INSERT INTO variants (tenant_id, product_id, external_id, sku, title) VALUES (?, ?, ?, ?, 'M') RETURNING id",
                UUID.class, tenant, product, "gid://shopify/ProductVariant/" + restV, "S3DSN-" + restV);
            originalGid = gid("Order");
            original = jdbc.queryForObject(
                "INSERT INTO orders (tenant_id, store_id, external_id, number, status, payment_method, placed_at, raw, " +
                "    customer_name, customer_phone) " +
                "VALUES (?, ?, ?, ?, 'delivered', 'cod', now() - interval '20 days', '{}'::jsonb, 'Mona Adel', '01012345678') RETURNING id",
                UUID.class, tenant, store, originalGid, number);
            jdbc.update("INSERT INTO order_items (tenant_id, order_id, variant_id, quantity, external_id) VALUES (?, ?, ?, 1, ?)",
                tenant, original, v, gid("LineItem"));
            UUID s = jdbc.queryForObject("INSERT INTO shipments (tenant_id, order_id, provider, tracking_number, internal_state, shipment_leg) " +
                "VALUES (?, ?, 'bosta', ?, 'delivered', 'forward') RETURNING id", UUID.class, tenant, original, tn());
            jdbc.update("INSERT INTO shipment_status_history (tenant_id, shipment_id, internal_state) VALUES (?, ?, 'delivered')", tenant, s);
        }

        /** An available piece of V at the main warehouse; returns its id. */
        String piece(String status) {
            long k = IDS.incrementAndGet();
            String id = String.format("01S3N%021d", k);
            jdbc.update("INSERT INTO pieces (id, tenant_id, variant_id, barcode, short_code, status, current_location_id) " +
                "VALUES (?, ?, ?, ?, ?, ?::piece_status, ?)", id, tenant, v, "S3N-" + k,
                String.format("N%07d", k % 10_000_000), status, location);
            return id;
        }

        <T> T as(java.util.concurrent.Callable<T> body) { return TenantContext.runAs(tenant, body); }

        /** Upserts the Return "#S3D<tag>-R1" with ONE exchange line of V (processed onto {@code lineGid} when not null). */
        void ret(String returnGid, String status, String xGid, String lineGid) {
            ObjectNode r = mapper.createObjectNode();
            r.put("id", returnGid);
            r.put("name", number + "-R1");
            r.put("status", status);
            r.put("createdAt", APPROVED.minusSeconds(3600).toString());
            r.put("requestApprovedAt", APPROVED.toString());
            r.putObject("order").put("id", originalGid);
            r.putObject("returnLineItems").putArray("nodes");
            ObjectNode n = r.putObject("exchangeLineItems").putArray("nodes").addObject();
            n.put("id", xGid);
            n.put("variantId", "gid://shopify/ProductVariant/" + restV);
            n.put("quantity", 1);
            n.put("processedQuantity", lineGid != null ? 1 : 0);
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

        void storeFulfillments(ObjectNode... fs) {
            ObjectNode raw = mapper.createObjectNode();
            ArrayNode arr = raw.putArray("fulfillments");
            for (ObjectNode f : fs) arr.add(f);
            jdbc.update("UPDATE orders SET raw = ?::jsonb WHERE id = ?", raw.toString(), original);
        }

        ShopifyShadowOrders.Outcome reconcile() { return as(() -> shadows.reconcileForOrder(original)); }

        /** Back-dates the shadow's approval (the alert's 7-day clock). */
        void approvedDaysAgo(int days) {
            assertThat(jdbc.update("UPDATE shopify_exchange_shadows SET approved_at = now() - make_interval(days => ?) " +
                "WHERE tenant_id = ? AND original_order_id = ?", days, tenant, original)).isEqualTo(1);
        }

        Map<String, Object> shadow() {
            List<Map<String, Object>> l = jdbc.queryForList(
                "SELECT s.order_id, s.state, s.end_reason, s.ended_at, o.number FROM shopify_exchange_shadows s " +
                "JOIN orders o ON o.id = s.order_id WHERE s.tenant_id = ? AND s.original_order_id = ?", tenant, original);
            assertThat(l).hasSize(1);
            return l.get(0);
        }

        UUID shadowId() { return (UUID) shadow().get("order_id"); }

        Map<String, Object> line() {
            return jdbc.queryForMap("SELECT live_quantity, shipped_in_shopify, stop_reason, stopped_at FROM shopify_exchange_shadow_lines " +
                "WHERE tenant_id = ?", tenant);
        }

        int shadowItemQuantity() {
            return jdbc.queryForObject("SELECT quantity FROM order_items WHERE tenant_id = ? AND order_id = ?",
                Integer.class, tenant, shadowId());
        }

        UUID shadowItem() {
            return jdbc.queryForObject("SELECT id FROM order_items WHERE tenant_id = ? AND order_id = ?", UUID.class, tenant, shadowId());
        }

        List<Map<String, Object>> waitingRows() { return as(() -> fulfill.getWaitingForRelease()); }
        List<Object> waiting() { return waitingRows().stream().map(r -> r.get("id")).toList(); }

        ShopifyShadowOrders.PickState pickState() {
            UUID id = shadowId();
            return as(() -> new TransactionTemplate(appTxm).execute(st -> ShopifyShadowOrders.pickState(appJdbc, tenant, id)));
        }

        List<Map<String, Object>> alerts() {
            return as(() -> exceptions.detectAllOpen().stream().filter(a -> WAITING.equals(a.get("type"))).toList());
        }

        long committed() {
            return as(() -> new TransactionTemplate(appTxm).execute(s ->
                new VariantStockService(appJdbc).forVariant(new VariantStockService(appJdbc).computeAll(), v).committed()));
        }

        int audits() {
            return jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE tenant_id = ? AND action = 'shopify_exchange_release'",
                Integer.class, tenant);
        }

        CustomUserDetails principal(UUID user, String role) {
            CustomUserDetails p = new CustomUserDetails(user, tenant, role, null);
            SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(p, "n/a", p.getAuthorities()));
            return p;
        }

        Map<String, Object> releaseAs(UUID user, String role, UUID orderId) {
            CustomUserDetails p = principal(user, role);
            return as(() -> controller.release(orderId, p));
        }

        /** #1009 shape: return processed onto a line, no FO Traced can see, then CLOSED. Returns the line gid. */
        String unexplained1009() {
            String rg = gid("Return"), x = gid("ExchangeLineItem"), li = gid("LineItem");
            ret(rg, "OPEN", x, li);
            ret(rg, "CLOSED", x, li);
            return li;
        }
    }

    ObjectNode fulfillment(String lineGid, int qty, String tracking) {
        ObjectNode f = mapper.createObjectNode();
        f.put("id", IDS.incrementAndGet());
        f.put("status", "success");
        f.put("location_id", 7_001L);
        f.put("service", "manual");
        f.put("tracking_company", "Bosta");
        f.put("tracking_number", tracking);
        f.putArray("tracking_numbers").add(tracking);
        ObjectNode li = f.putArray("line_items").addObject();
        li.put("id", Long.parseLong(num(lineGid)));
        li.put("quantity", qty);
        li.put("fulfillment_service", "manual");
        return f;
    }

    BostaDelivery delivery(String tracking, int typeCode, String typeValue, int state, String reference) {
        ObjectNode raw = mapper.createObjectNode();
        raw.put("_id", "d-" + tracking);
        raw.put("trackingNumber", tracking);
        raw.putObject("type").put("code", typeCode).put("value", typeValue);
        raw.putObject("state").put("code", state).put("value", "Route Assigned");
        raw.put("businessReference", reference);
        raw.putObject("receiver").put("firstName", "Mona").put("lastName", "Adel").put("phone", "01012345678");
        raw.put("updatedAt", Instant.parse("2026-11-06T10:00:00Z").plusSeconds(IDS.incrementAndGet() % 1000).toString());
        return BostaDelivery.fromRaw(tracking, raw);
    }

    List<Map<String, Object>> shipmentsOf(UUID tenant, UUID orderId) {
        return jdbc.queryForList("SELECT tracking_number, shipment_leg::text AS leg FROM shipments WHERE tenant_id = ? AND order_id = ?",
            tenant, orderId);
    }

    // ── t1: only a SEND is the shadow's courier leg ──────────────────────────────────────────────────────────────

    @Test
    void t1_type20ReturnToOrigin_withReturnReference_isNeverTheShadowsLeg() {
        Shop s = new Shop("T1");
        s.ret(gid("Return"), "OPEN", gid("ExchangeLineItem"), null);
        UUID shadow = s.shadowId();
        assertThat(s.shadow()).containsEntry("state", "live");
        int originalLegs = shipmentsOf(s.tenant, s.original).size();
        String awb = tn();
        BostaDelivery d = delivery(awb, 20, "Return to Origin", 20, "S3DT1:#S3DT1-R1");

        ShipmentLinkService.LinkResult r = s.as(() -> links.tryMatchDelivery(s.tenant, awb, d, stateMapper.map(20, "RETURN TO ORIGIN")));

        assertThat(r.orderId()).as("never the shadow").isNotEqualTo(shadow);
        assertThat(shipmentsOf(s.tenant, shadow)).as("the shadow has no forward leg").isEmpty();
        // Where it went — today's path: the reference resolves to the ORIGINAL, which already has its (delivered) Bosta
        // forward leg, so the delivery is left unlinked as ACTIVE_FORWARD_LEG; no shipment row anywhere.
        assertThat(r.orderId()).isNull();
        assertThat(r.unlinkedReason()).isEqualTo("ACTIVE_FORWARD_LEG");
        assertThat(jdbc.queryForList("SELECT order_id FROM shipments WHERE tenant_id = ? AND tracking_number = ?", s.tenant, awb))
            .isEmpty();
        assertThat(shipmentsOf(s.tenant, s.original)).hasSize(originalLegs);
    }

    @Test
    void t1b_send_withSameReference_isTheShadowsForwardLeg() {
        Shop s = new Shop("T1B");
        s.ret(gid("Return"), "OPEN", gid("ExchangeLineItem"), null);
        UUID shadow = s.shadowId();
        String awb = tn();
        BostaDelivery d = delivery(awb, 10, "Send", 10, "S3DT1B:#S3DT1B-R1");

        ShipmentLinkService.LinkResult r = s.as(() -> links.tryMatchDelivery(s.tenant, awb, d, stateMapper.map(10, "SEND")));

        assertThat(r.orderId()).isEqualTo(shadow);
        assertThat(shipmentsOf(s.tenant, shadow)).singleElement().satisfies(m -> {
            assertThat(m.get("tracking_number")).isEqualTo(awb);
            assertThat(m.get("leg")).isEqualTo("forward");
        });
    }

    // ── w: shopify_exchange_still_waiting ────────────────────────────────────────────────────────────────────────

    @Test
    void w1_1009_approved8DaysAgo_oneMediumAlert_onTheOriginal() {
        Shop s = new Shop("W1");
        s.unexplained1009();
        assertThat(s.shadow()).containsEntry("state", "live");
        // Pinned to the detector's clock (the database's now()), not the fixture's fixed APPROVED date —
        // otherwise this check fails on every run more than 7 days after 2026-11-04.
        s.approvedDaysAgo(6);
        assertThat(s.alerts()).as("not before 7 days").isEmpty();
        s.approvedDaysAgo(8);

        List<Map<String, Object>> a = s.alerts();
        assertThat(a).hasSize(1);
        assertThat(a.get(0))
            .containsEntry("severity", "MEDIUM")
            .containsEntry("order_id", s.original)
            .containsEntry("shadow_number", "#S3DW1-X1")
            .containsEntry("subject_key", WAITING + ":" + s.shadowId());
    }

    @Test
    void w2_1009_approved2DaysAgo_noAlert() {
        Shop s = new Shop("W2");
        s.unexplained1009();
        s.approvedDaysAgo(2);
        assertThat(s.shadow()).containsEntry("state", "live");
        assertThat(s.alerts()).isEmpty();
    }

    @Test
    void w3_foOnHold_8Days_explained_noAlert() {
        Shop s = new Shop("W3");
        String li = gid("LineItem");
        s.ret(gid("Return"), "OPEN", gid("ExchangeLineItem"), li);
        s.fo(gid("FulfillmentOrder"), "ON_HOLD", li, 1, 1);
        s.approvedDaysAgo(8);
        assertThat(s.shadow()).containsEntry("state", "live");
        assertThat(s.pickState()).isEqualTo(ShopifyShadowOrders.PickState.HELD);
        assertThat(s.alerts()).isEmpty();
        assertThat(s.waitingRows()).singleElement().satisfies(r -> assertThat(r.get("unexplained")).isEqualTo(false));
    }

    @Test
    void w4_unprocessedOpenReturn_8Days_explained_noAlert() {
        Shop s = new Shop("W4");
        s.ret(gid("Return"), "OPEN", gid("ExchangeLineItem"), null);
        s.approvedDaysAgo(8);
        assertThat(s.shadow()).containsEntry("state", "live");
        assertThat(s.pickState()).isEqualTo(ShopifyShadowOrders.PickState.HELD);
        assertThat(s.alerts()).isEmpty();
    }

    @Test
    void w5_foClosedNoStoredFulfillment_heldUnexplained_alert_thenTrackedFulfillment_released_alertGone() {
        Shop s = new Shop("W5");
        String li = gid("LineItem");
        s.ret(gid("Return"), "OPEN", gid("ExchangeLineItem"), li);
        s.fo(gid("FulfillmentOrder"), "CLOSED", li, 0, 1);
        s.approvedDaysAgo(8);

        assertThat(s.shadow()).containsEntry("state", "live");
        assertThat(s.pickState()).as("unexplained stays waiting").isEqualTo(ShopifyShadowOrders.PickState.HELD);
        assertThat(s.line()).containsEntry("live_quantity", 1).containsEntry("shipped_in_shopify", 0)
            .containsEntry("stop_reason", null);
        UUID id = s.shadowId();
        assertThat(s.waitingRows()).singleElement().satisfies(r -> {
            assertThat(r.get("id")).isEqualTo(id);
            assertThat(r.get("unexplained")).isEqualTo(true);
        });
        assertThat(s.alerts()).hasSize(1);
        assertThat(s.alerts().get(0)).containsEntry("order_id", s.original);

        s.storeFulfillments(fulfillment(li, 1, "4475550001"));
        s.reconcile();
        assertThat(s.shadow()).containsEntry("state", "live");
        assertThat(s.line()).containsEntry("live_quantity", 1).containsEntry("shipped_in_shopify", 1);
        assertThat(s.pickState()).as("tracked = released").isEqualTo(ShopifyShadowOrders.PickState.PACKABLE);
        assertThat(s.waiting()).doesNotContain(id);
        assertThat(s.alerts()).isEmpty();
    }

    // ── r: Release reservation ───────────────────────────────────────────────────────────────────────────────────

    @Test
    void r1_ownerRelease_endsManualRelease_audited_alertGone_sticky() {
        Shop s = new Shop("R1");
        String li = s.unexplained1009();
        s.approvedDaysAgo(8);
        UUID id = s.shadowId();
        assertThat(s.alerts()).hasSize(1);
        assertThat(s.waiting()).contains(id);
        assertThat(s.committed()).isEqualTo(1);

        Map<String, Object> out = s.releaseAs(s.owner, "owner", id);
        assertThat(out).containsEntry("orderId", id.toString()).containsEntry("number", "#S3DR1-X1")
            .containsEntry("state", "ended").containsEntry("endReason", "manual_release");

        assertThat(s.shadow()).containsEntry("state", "ended").containsEntry("end_reason", "manual_release");
        assertThat(s.shadow().get("ended_at")).isNotNull();
        assertThat(s.line()).containsEntry("live_quantity", 0).containsEntry("stop_reason", "manual_release");
        assertThat(s.line().get("stopped_at")).isNotNull();
        assertThat(s.shadowItemQuantity()).isZero();
        assertThat(s.committed()).isZero();
        assertThat(s.audits()).isEqualTo(1);
        Map<String, Object> audit = jdbc.queryForMap("SELECT actor_user_id, target_type, target_id, metadata::text AS m FROM audit_log " +
            "WHERE tenant_id = ? AND action = 'shopify_exchange_release'", s.tenant);
        assertThat(audit).containsEntry("actor_user_id", s.owner).containsEntry("target_type", "order")
            .containsEntry("target_id", id.toString());
        assertThat((String) audit.get("m")).contains("manual_release").contains("#S3DR1-X1");
        assertThat(s.alerts()).isEmpty();
        assertThat(s.waiting()).doesNotContain(id);
        assertThat(s.pickState()).isEqualTo(ShopifyShadowOrders.PickState.ENDED);

        // Sticky: Shopify's mirror changes (a new OPEN FO for the line) — the reconcile never revives it.
        s.fo(gid("FulfillmentOrder"), "OPEN", li, 1, 1);
        s.reconcile();
        assertThat(s.shadow()).as("sticky").containsEntry("state", "ended").containsEntry("end_reason", "manual_release");
        assertThat(s.line()).containsEntry("live_quantity", 0).containsEntry("stop_reason", "manual_release");
        assertThat(s.shadowItemQuantity()).isZero();
        assertThat(s.committed()).isZero();
    }

    @Test
    void r2_managerAllowed_workerDenied() {
        Shop s = new Shop("R2");
        s.unexplained1009();
        UUID id = s.shadowId();

        CustomUserDetails w = s.principal(s.worker, "worker");
        assertThatThrownBy(() -> s.as(() -> controller.release(id, w))).isInstanceOf(AccessDeniedException.class);
        assertThat(s.shadow()).containsEntry("state", "live");
        assertThat(s.audits()).isZero();

        assertThat(s.releaseAs(s.manager, "manager", id)).containsEntry("endReason", "manual_release");
        assertThat(s.shadow()).containsEntry("state", "ended").containsEntry("end_reason", "manual_release");
        assertThat(jdbc.queryForObject("SELECT actor_user_id FROM audit_log WHERE tenant_id = ? AND action = 'shopify_exchange_release'",
            UUID.class, s.tenant)).isEqualTo(s.manager);
    }

    @Test
    void r3_activeAllocation_409() {
        Shop s = new Shop("R3");
        s.unexplained1009();
        UUID id = s.shadowId();
        String piece = s.piece("reserved");
        jdbc.update("INSERT INTO allocations (tenant_id, order_item_id, piece_id, status) VALUES (?, ?, ?, 'active')",
            s.tenant, s.shadowItem(), piece);

        assertThatThrownBy(() -> s.releaseAs(s.owner, "owner", id))
            .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
        assertThat(s.shadow()).containsEntry("state", "live");
        assertThat(s.line()).containsEntry("live_quantity", 1);
        assertThat(s.shadowItemQuantity()).isEqualTo(1);
        assertThat(s.audits()).isZero();
    }

    @Test
    void r4_alreadyEnded_409_unknown_404() {
        Shop s = new Shop("R4");
        s.unexplained1009();
        UUID id = s.shadowId();
        s.releaseAs(s.owner, "owner", id);
        assertThatThrownBy(() -> s.releaseAs(s.owner, "owner", id))
            .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));

        // an ended-by-Shopify shadow (declined) is refused too
        Shop d = new Shop("R4D");
        String rg = gid("Return"), x = gid("ExchangeLineItem");
        d.ret(rg, "OPEN", x, null);
        d.ret(rg, "DECLINED", x, null);
        assertThat(d.shadow()).containsEntry("state", "ended").containsEntry("end_reason", "declined");
        UUID did = d.shadowId();
        assertThatThrownBy(() -> d.releaseAs(d.owner, "owner", did))
            .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
        assertThat(d.shadow()).containsEntry("end_reason", "declined");

        assertThatThrownBy(() -> s.releaseAs(s.owner, "owner", UUID.randomUUID()))
            .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
        assertThat(s.audits()).isEqualTo(1);
        assertThat(d.audits()).isZero();
    }

    @Test
    void r5_crossTenant_appUser_otherTenantCannotRelease_ownTenantCan() {
        Shop a = new Shop("R5A"), b = new Shop("R5B");
        a.unexplained1009();
        UUID shadowA = a.shadowId();

        assertThatThrownBy(() -> b.as(() -> shadows.releaseManually(shadowA)))
            .isInstanceOfSatisfying(ShopifyShadowOrders.ReleaseRefused.class, e -> assertThat(e.code).isEqualTo("NOT_FOUND"));
        assertThat(a.shadow()).containsEntry("state", "live");
        assertThat(a.line()).containsEntry("live_quantity", 1);

        assertThat(a.as(() -> shadows.releaseManually(shadowA))).isEqualTo("#S3DR5A-X1");
        assertThat(a.shadow()).containsEntry("state", "ended").containsEntry("end_reason", "manual_release");
        assertThat(a.shadowItemQuantity()).isZero();
    }
}
