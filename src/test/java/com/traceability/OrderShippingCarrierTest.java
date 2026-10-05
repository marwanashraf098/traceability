package com.traceability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.traceability.embedded.EmbeddedController;
import com.traceability.fulfillment.OrderCarrier;
import com.traceability.fulfillment.OrderController;
import com.traceability.fulfillment.OrderController.FunnelCounts;
import com.traceability.fulfillment.OrderNotesService;
import com.traceability.fulfillment.OrderShippingBadge;
import com.traceability.integrations.bosta.BostaGateway;
import com.traceability.integrations.bosta.BostaOrderReconcileJob;
import com.traceability.integrations.shopify.FulfillmentTrackingCapture;
import com.traceability.inventory.ShipmentLinkService;
import com.traceability.overview.OverviewService;
import com.traceability.security.EncryptionService;
import com.traceability.tenancy.TenantAwareDataSource;
import com.traceability.tenancy.TenantContext;
import org.jobrunr.scheduling.JobScheduler;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V139 (2026-10-05) — the order's shipping carrier (orders.shipping_carrier_class / _name) and the
 * read-time shipping badge (OrderShippingBadge) that replaced the reconcile job's 'not_created' flag.
 *
 *   c1 carrier from a Wijha fulfillment → other_known "Wijha"; a Bosta fulfillment added → bosta (Bosta wins);
 *      the Bosta fulfillment cancelled → back to other_known; all cancelled → NULL
 *   c2 a live Bosta forward shipment wins over a Wijha fulfillment; linking recomputes the carrier
 *   c3 Jumi's "Other" carrier → unknown → Bosta-eligible (awaiting booking, still a reconcile candidate)
 *   c4 V139 backfill from order_fulfillment_tracking AND orders.raw fulfillments (cancelled ignored)
 *   c5 reconcile skips other_known orders and never sets 'not_created'
 *   b* one badge state per test through list() AND detail()
 *   f1 funnel / embedded funnel / late-to-pack exclude shipped-elsewhere; shippedElsewhere count
 *   i1 cross-tenant isolation (app_user): recompute, the carrier function and the badge see one tenant
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OrderShippingCarrierTest {

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
        r.add("bosta.reconcile.max-attempts", () -> "3");
    }

    @Autowired JdbcTemplate               jdbc;
    @Autowired ObjectMapper               mapper;
    @Autowired PlatformTransactionManager txm;
    @Autowired FulfillmentTrackingCapture capture;
    @Autowired BostaOrderReconcileJob     reconcileJob;
    @Autowired ShipmentLinkService        shipmentLinkService;
    @Autowired EncryptionService          encryptionService;
    @Autowired com.traceability.inventory.FulfillService fulfillService;
    @MockBean  BostaGateway               bostaGateway;
    @MockBean  JobScheduler               jobScheduler;

    private OrderController controller;
    private TransactionTemplate tx;
    private JdbcTemplate appUserJdbc;
    private PlatformTransactionManager appUserTxm;
    private TransactionTemplate appUserTx;

    @BeforeAll
    void setup() {
        controller = new OrderController(jdbc, mapper, txm, new OrderNotesService(jdbc), fulfillService);
        tx = new TransactionTemplate(txm);
        TenantAwareDataSource appDs = new TenantAwareDataSource(
            new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "app_user", "testpw"));
        appUserJdbc = new JdbcTemplate(appDs);
        appUserTxm  = new DataSourceTransactionManager(appDs);
        appUserTx   = new TransactionTemplate(appUserTxm);
    }

    @AfterEach void clear() { TenantContext.clear(); }

    // ── c1 ───────────────────────────────────────────────────────────────────

    @Test
    void c1_wijha_thenBostaWins_cancelFlipsBack_allCancelledNull() {
        UUID[] t = tenant("c1", true);
        String gid = "gid://shopify/Order/7390010001";
        UUID order = order(t, gid, "C1-1", "new", 0);

        captureAs(t, gid, payload(f(1, "success", "Wijha", "WJ-1001", null)));
        assertCarrier(order, "other_known", "Wijha");

        captureAs(t, gid, payload(f(1, "success", "Wijha", "WJ-1001", null), f(2, "success", "Bosta", "4810010001", null)));
        assertCarrier(order, "bosta", "Bosta");

        captureAs(t, gid, payload(f(1, "success", "Wijha", "WJ-1001", null), f(2, "cancelled", "Bosta", "4810010001", null)));
        assertCarrier(order, "other_known", "Wijha");

        captureAs(t, gid, payload(f(1, "cancelled", "Wijha", "WJ-1001", null), f(2, "cancelled", "Bosta", "4810010001", null)));
        assertCarrier(order, null, null);
    }

    // ── c2 ───────────────────────────────────────────────────────────────────

    @Test
    void c2_liveBostaShipmentWins_andLinkingRecomputes() {
        UUID[] t = tenant("c2", true);
        String gid = "gid://shopify/Order/7390020001";
        UUID order = order(t, gid, "#C2-1", "new", 0);
        captureAs(t, gid, payload(f(1, "success", "Wijha", "WJ-2001", null)));
        assertCarrier(order, "other_known", "Wijha");

        // Link a Bosta delivery to the order through the normal link path (manualLink → createOrFindShipment).
        Long unlinkedId = jdbc.queryForObject(
            "INSERT INTO unlinked_bosta_deliveries (tenant_id, tracking_number, business_reference, bosta_state_code, " +
            "  bosta_order_type, match_reason, resolved, raw) " +
            "VALUES (?, '4820020001', '#C2-1', 10, 'SEND', 'NO_MATCH', false, '{\"type\":{\"code\":10,\"value\":\"Send\"}}'::jsonb) RETURNING id",
            Long.class, t[0]);
        TenantContext.runAs(t[0], () -> shipmentLinkService.manualLink(unlinkedId, order, null));
        assertCarrier(order, "bosta", "Bosta");

        // The shipment dies → the Wijha fulfillment decides again.
        jdbc.update("UPDATE shipments SET internal_state = 'cancelled' WHERE order_id = ?", order);
        TenantContext.runAs(t[0], () -> tx.execute(s -> { OrderCarrier.recompute(jdbc, t[0], order); return null; }));
        assertCarrier(order, "other_known", "Wijha");
    }

    // ── c3 ───────────────────────────────────────────────────────────────────

    @Test
    void c3_jumiOther_staysUnknown_andBostaEligible() {
        UUID[] t = tenant("c3", true);
        String gid = "gid://shopify/Order/7390030001";
        UUID order = order(t, gid, "C3-1", "new", 0);
        captureAs(t, gid, payload(f(1, "success", "Other", "9630030001", null)));
        assertCarrier(order, "unknown", "Other");

        OrderShippingBadge b = detailBadge(t, order);
        assertThat(b.state()).as("an 'Other' fulfillment never reads as shipped elsewhere").isNotEqualTo("shipped_elsewhere");

        jdbc.update("UPDATE orders SET bosta_link_attempts = 0, bosta_link_last_check = NULL WHERE id = ?", order);
        reconcileJob.reconcileAll();
        assertThat(jdbc.queryForObject("SELECT bosta_link_attempts FROM orders WHERE id = ?", Integer.class, order))
            .as("still a reconcile candidate").isEqualTo(1);
    }

    // ── c4 ───────────────────────────────────────────────────────────────────

    @Test
    void c4_backfill_fromTrackingRows_andRawFulfillments() throws Exception {
        UUID[] t = tenant("c4", true);
        // (a) pre-V129 order: Wijha only in orders.raw (no tracking row)
        UUID rawWijha = order(t, "gid://shopify/Order/7390040001", "C4-1", "new", 0);
        setRaw(rawWijha, payload(f(1, "success", "Wijha", "WJ-4001", null)));
        // (b) tracking row only, Bosta class
        UUID tracked = order(t, "gid://shopify/Order/7390040002", "C4-2", "new", 0);
        jdbc.update("INSERT INTO order_fulfillment_tracking (tenant_id, order_id, tracking_number, carrier_raw, carrier_class, fulfillment_status) " +
            "VALUES (?, ?, '4840040002', 'Bosta', 'bosta', 'success')", t[0], tracked);
        // (c) raw: cancelled Wijha + live Bosta (by URL only) → bosta
        UUID rawMixed = order(t, "gid://shopify/Order/7390040003", "C4-3", "new", 0);
        setRaw(rawMixed, payload(f(1, "cancelled", "Wijha", "WJ-4003", null),
                                 f(2, "success", "Other", "4840040003", "https://bosta.co/tracking-shipments?shipment-number=4840040003")));
        // (d) raw: only a cancelled Wijha → NULL
        UUID rawCancelled = order(t, "gid://shopify/Order/7390040004", "C4-4", "new", 0);
        setRaw(rawCancelled, payload(f(1, "cancelled", "Wijha", "WJ-4004", null)));
        // (e) nothing at all → NULL, untouched
        UUID none = order(t, "gid://shopify/Order/7390040005", "C4-5", "new", 0);

        jdbc.update("UPDATE orders SET shipping_carrier_class = NULL, shipping_carrier_name = NULL WHERE tenant_id = ?", t[0]);
        jdbc.execute(backfillSql());

        assertCarrier(rawWijha, "other_known", "Wijha");
        assertCarrier(tracked, "bosta", "Bosta");
        assertCarrier(rawMixed, "bosta", "Other");
        assertCarrier(rawCancelled, null, null);
        assertCarrier(none, null, null);
    }

    // ── c5 ───────────────────────────────────────────────────────────────────

    @Test
    void c5_reconcile_skipsOtherKnown_neverFlags() {
        UUID[] t = tenant("c5", true);
        UUID wijha = order(t, "gid://shopify/Order/7390050001", "C5-1", "new", 0);
        setCarrier(wijha, "other_known", "Wijha");
        UUID plain = order(t, "gid://shopify/Order/7390050002", "C5-2", "new", 0);
        jdbc.update("UPDATE orders SET bosta_link_attempts = 2 WHERE id = ?", plain);

        reconcileJob.reconcileAll();

        Map<String, Object> w = jdbc.queryForMap("SELECT bosta_link_attempts, bosta_link_last_check, bosta_link_status FROM orders WHERE id = ?", wijha);
        assertThat(w.get("bosta_link_attempts")).as("other_known never checked").isEqualTo(0);
        assertThat(w.get("bosta_link_last_check")).isNull();
        Map<String, Object> p = jdbc.queryForMap("SELECT bosta_link_attempts, bosta_link_status FROM orders WHERE id = ?", plain);
        assertThat(p.get("bosta_link_attempts")).isEqualTo(3);
        assertThat(p.get("bosta_link_status")).as("never 'not_created'").isNull();
    }

    // ── badge states, list + detail ──────────────────────────────────────────

    @Test
    void b1_awaitingBooking() {
        UUID[] t = tenant("b1", true);
        UUID o = order(t, "gid://shopify/Order/7390060001", "B1-1", "new", 0);
        assertBadge(t, o, "awaiting_booking", null, null);
    }

    @Test
    void b2_notBookedOverdue_afterThreeDays() {
        UUID[] t = tenant("b2", true);
        UUID young = order(t, "gid://shopify/Order/7390070001", "B2-1", "new", 2);
        UUID old   = order(t, "gid://shopify/Order/7390070002", "B2-2", "confirmed", 4);
        assertBadge(t, young, "awaiting_booking", null, null);
        assertBadge(t, old, "not_booked_overdue", null, 4);
    }

    @Test
    void b3_shippedElsewhere_withCarrierName() {
        UUID[] t = tenant("b3", true);
        UUID o = order(t, "gid://shopify/Order/7390080001", "B3-1", "new", 5);
        setCarrier(o, "other_known", "Wijha");
        assertBadge(t, o, "shipped_elsewhere", "Wijha", null);
    }

    @Test
    void b4_bostaTrackingNotLinked_conflictOrGaveUp_orUnlinkedPastGrace() {
        UUID[] t = tenant("b4", true);
        UUID conflict = order(t, "gid://shopify/Order/7390090001", "B4-1", "new", 0);
        tracking(t, conflict, "4890090001", "conflict", 0);
        UUID gaveUp = order(t, "gid://shopify/Order/7390090002", "B4-2", "new", 0);
        tracking(t, gaveUp, "4890090002", "gave_up", 0);
        UUID stale = order(t, "gid://shopify/Order/7390090003", "B4-3", "new", 0);
        tracking(t, stale, "4890090003", "retry", 90);
        UUID fresh = order(t, "gid://shopify/Order/7390090004", "B4-4", "new", 0);
        tracking(t, fresh, "4890090004", null, 20);
        UUID cancelledF = order(t, "gid://shopify/Order/7390090005", "B4-5", "new", 0);
        tracking(t, cancelledF, "4890090005", "gave_up", 0);
        jdbc.update("UPDATE order_fulfillment_tracking SET fulfillment_status = 'cancelled' WHERE tracking_number = '4890090005'");

        assertBadge(t, conflict, "bosta_tracking_not_linked", "Bosta", null);
        assertBadge(t, gaveUp, "bosta_tracking_not_linked", "Bosta", null);
        assertBadge(t, stale, "bosta_tracking_not_linked", "Bosta", null);
        assertBadge(t, fresh, "awaiting_booking", null, null);
        assertBadge(t, cancelledF, "awaiting_booking", null, null);
    }

    @Test
    void b5_linked_liveForwardShipment() {
        UUID[] t = tenant("b5", true);
        UUID o = order(t, "gid://shopify/Order/7390100001", "B5-1", "new", 5);
        setCarrier(o, "other_known", "Wijha");     // a live Bosta shipment decides regardless
        shipment(t, o, "4800100001", "created");
        assertBadge(t, o, "linked", "Bosta", null);
    }

    @Test
    void b6_cancelled() {
        UUID[] t = tenant("b6", true);
        UUID o = order(t, "gid://shopify/Order/7390110001", "B6-1", "cancelled", 6);
        assertBadge(t, o, "cancelled", null, null);
    }

    @Test
    void b7_noBadge_selfPickup_noBostaTenant_deliveredWithoutShipment() {
        UUID[] t = tenant("b7", true);
        UUID pickup = order(t, "gid://shopify/Order/7390120001", "B7-1", "new", 5);
        jdbc.update("UPDATE orders SET is_self_pickup = true WHERE id = ?", pickup);
        UUID delivered = order(t, "gid://shopify/Order/7390120002", "B7-2", "delivered", 5);
        assertBadge(t, pickup, null, null, null);
        assertBadge(t, delivered, null, null, null);

        UUID[] noBosta = tenant("b7n", false);
        UUID o = order(noBosta, "gid://shopify/Order/7390120003", "B7-3", "new", 5);
        assertBadge(noBosta, o, null, null, null);
    }

    // ── funnel / overview ────────────────────────────────────────────────────

    @Test
    void f1_funnelAndLateToPack_excludeShippedElsewhere() {
        UUID[] t = tenant("f1", true);
        UUID wijhaToday = order(t, "gid://shopify/Order/7390130001", "F1-1", "new", 0);
        setCarrier(wijhaToday, "other_known", "Wijha");
        order(t, "gid://shopify/Order/7390130002", "F1-2", "new", 0);              // plain New
        UUID unknownToday = order(t, "gid://shopify/Order/7390130003", "F1-3", "new", 0);
        setCarrier(unknownToday, "unknown", "Other");                               // Jumi-style → New
        UUID wijhaOld = order(t, "gid://shopify/Order/7390130004", "F1-4", "new", 2);
        setCarrier(wijhaOld, "other_known", "Wijha");
        order(t, "gid://shopify/Order/7390130005", "F1-5", "new", 2);              // plain late

        TenantContext.set(t[0]);
        FunnelCounts f = tx.execute(s -> controller.funnel());
        assertThat(f.newCount()).as("Wijha not in New").isEqualTo(2);
        assertThat(f.shippedElsewhere()).isEqualTo(1);

        EmbeddedController.FunnelCounts ef = new EmbeddedController(jdbc, txm, null, null).ordersFunnel();
        assertThat(ef.newCount()).isEqualTo(2);
        assertThat(ef.shippedElsewhere()).isEqualTo(1);

        OverviewService.LateToPack late = new OverviewService(jdbc, Clock.systemUTC()).lateToPack();
        assertThat(late.overdue()).as("Wijha not late to pack").isEqualTo(1);
        assertThat(late.over48()).isEqualTo(1);
    }

    // ── isolation ────────────────────────────────────────────────────────────

    @Test
    void i1_crossTenantIsolation_appUser() {
        UUID[] a = tenant("i1a", true);
        UUID[] b = tenant("i1b", true);
        UUID orderB = order(b, "gid://shopify/Order/7390140001", "I1-B", "new", 0);
        jdbc.update("INSERT INTO order_fulfillment_tracking (tenant_id, order_id, tracking_number, carrier_raw, carrier_class, fulfillment_status, link_status) " +
            "VALUES (?, ?, 'WJ-14001', 'Wijha', 'other_known', 'success', NULL)", b[0], orderB);
        UUID orderA = order(a, "gid://shopify/Order/7390140002", "I1-A", "new", 0);
        tracking(a, orderA, "4800140002", "conflict", 0);

        // Tenant A cannot recompute or read tenant B's carrier.
        TenantContext.runAs(a[0], () -> appUserTx.execute(s -> {
            OrderCarrier.recompute(appUserJdbc, a[0], orderB);
            OrderCarrier.recompute(appUserJdbc, b[0], orderB);   // wrong tenant id for the GUC → RLS hides it
            assertThat(appUserJdbc.queryForList("SELECT * FROM shipping_carrier_of(?)", orderB)).isEmpty();
            return null;
        }));
        assertCarrier(orderB, null, null);

        // Tenant B recomputes its own.
        TenantContext.runAs(b[0], () -> appUserTx.execute(s -> { OrderCarrier.recompute(appUserJdbc, b[0], orderB); return null; }));
        assertCarrier(orderB, "other_known", "Wijha");

        // app_user list under B sees only B's order, with B's badge; A's conflict never shows.
        OrderController appCtl = new OrderController(appUserJdbc, mapper, appUserTxm, new OrderNotesService(appUserJdbc), fulfillService);
        List<OrderController.OrderSummary> items = TenantContext.runAs(b[0], () -> appCtl.list(null, null, null, null, 0, 100).items());
        assertThat(items).extracting(OrderController.OrderSummary::id).containsExactly(orderB.toString());
        assertThat(items.get(0).shippingBadge().state()).isEqualTo("shipped_elsewhere");
        List<OrderController.OrderSummary> itemsA = TenantContext.runAs(a[0], () -> appCtl.list(null, null, null, null, 0, 100).items());
        assertThat(itemsA).extracting(OrderController.OrderSummary::id).containsExactly(orderA.toString());
        assertThat(itemsA.get(0).shippingBadge().state()).isEqualTo("bosta_tracking_not_linked");
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private UUID[] tenant(String tag, boolean bosta) {
        UUID tenantId = UUID.randomUUID(), storeId = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?)", tenantId, "OSC-" + tag);
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status) VALUES (?, ?, 'shopify', ?, 'disconnected')",
            storeId, tenantId, "osc-" + tag + "-" + tenantId.toString().substring(0, 8) + ".myshopify.com");
        if (bosta) {
            jdbc.update("INSERT INTO courier_accounts (tenant_id, provider, api_key_encrypted, webhook_secret, status) " +
                "VALUES (?, 'bosta', ?, ?, 'active')", tenantId, encryptionService.encrypt("k-" + tag), "wh-" + tenantId);
        }
        return new UUID[]{tenantId, storeId};
    }

    private UUID order(UUID[] t, String gid, String number, String status, int daysAgo) {
        // placed_at from the JVM's clock (the badge and late-to-pack compare against it), an hour
        // past whole days so a Docker clock a little ahead of the Mac can't shift the day count.
        java.time.Instant placed = daysAgo == 0 ? java.time.Instant.now()
            : java.time.Instant.now().minus(java.time.Duration.ofDays(daysAgo).plusHours(1));
        return jdbc.queryForObject("INSERT INTO orders (tenant_id, store_id, external_id, number, status, placed_at) " +
            "VALUES (?, ?, ?, ?, ?::order_status, ?) RETURNING id",
            UUID.class, t[0], t[1], gid, number, status, java.sql.Timestamp.from(placed));
    }

    private void shipment(UUID[] t, UUID order, String tn, String state) {
        jdbc.update("INSERT INTO shipments (tenant_id, order_id, provider, tracking_number, internal_state, shipment_leg) " +
            "VALUES (?, ?, 'bosta', ?, ?::shipment_internal_state, 'forward')", t[0], order, tn, state);
    }

    private void tracking(UUID[] t, UUID order, String tn, String linkStatus, int minutesAgo) {
        jdbc.update("INSERT INTO order_fulfillment_tracking (tenant_id, order_id, tracking_number, carrier_raw, carrier_class, " +
            "  fulfillment_status, link_status, first_seen_at) " +
            "VALUES (?, ?, ?, 'Bosta', 'bosta', 'success', ?, now() - (? * INTERVAL '1 minute'))",
            t[0], order, tn, linkStatus, minutesAgo);
    }

    private void setCarrier(UUID order, String cls, String name) {
        jdbc.update("UPDATE orders SET shipping_carrier_class = ?, shipping_carrier_name = ? WHERE id = ?", cls, name, order);
    }

    private void setRaw(UUID order, ObjectNode raw) {
        jdbc.update("UPDATE orders SET raw = ?::jsonb WHERE id = ?", raw.toString(), order);
    }

    /** As the orders/updated webhook does: the raw order is upserted first, then the capture runs. */
    private void captureAs(UUID[] t, String gid, ObjectNode payload) {
        jdbc.update("UPDATE orders SET raw = ?::jsonb WHERE tenant_id = ? AND external_id = ?", payload.toString(), t[0], gid);
        TenantContext.runAs(t[0], () -> tx.execute(s -> capture.capture(t[1], gid, payload)));
    }

    private ObjectNode f(long id, String status, String company, String tn, String url) {
        ObjectNode n = mapper.createObjectNode();
        n.put("id", id);
        n.put("status", status);
        if (company != null) n.put("tracking_company", company);
        n.put("tracking_number", tn);
        n.putArray("tracking_numbers").add(tn);
        if (url != null) { n.put("tracking_url", url); n.putArray("tracking_urls").add(url); }
        return n;
    }

    private ObjectNode payload(ObjectNode... fulfillments) {
        ObjectNode p = mapper.createObjectNode();
        ArrayNode arr = p.putArray("fulfillments");
        for (ObjectNode n : fulfillments) arr.add(n);
        return p;
    }

    private void assertCarrier(UUID order, String cls, String name) {
        Map<String, Object> r = jdbc.queryForMap("SELECT shipping_carrier_class, shipping_carrier_name FROM orders WHERE id = ?", order);
        assertThat(r.get("shipping_carrier_class")).as("carrier class").isEqualTo(cls);
        assertThat(r.get("shipping_carrier_name")).as("carrier name").isEqualTo(name);
    }

    private OrderShippingBadge detailBadge(UUID[] t, UUID order) {
        return TenantContext.runAs(t[0], () -> controller.detail(order).shippingBadge());
    }

    private void assertBadge(UUID[] t, UUID order, String state, String carrier, Integer days) {
        OrderShippingBadge detail = detailBadge(t, order);
        OrderShippingBadge listed = TenantContext.runAs(t[0], () -> controller.list(null, null, null, null, 0, 100).items())
            .stream().filter(s -> s.id().equals(order.toString())).findFirst().orElseThrow().shippingBadge();
        if (state == null) {
            assertThat(detail).as("detail: no badge").isNull();
            assertThat(listed).as("list: no badge").isNull();
            return;
        }
        OrderShippingBadge expected = new OrderShippingBadge(state, carrier, days);
        assertThat(detail).as("detail badge").isEqualTo(expected);
        assertThat(listed).as("list badge").isEqualTo(expected);
    }

    private String backfillSql() throws Exception {
        String sql = new String(new ClassPathResource("db/migration/V139__order_shipping_carrier.sql")
            .getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        return sql.substring(sql.indexOf("UPDATE orders o"));
    }
}
