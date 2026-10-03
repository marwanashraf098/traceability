package com.traceability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.traceability.integrations.bosta.*;
import com.traceability.integrations.shopify.ShopifyWebhookProcessorJob;
import com.traceability.inventory.ExceptionService;
import com.traceability.security.EncryptionService;
import com.traceability.tenancy.TenantContext;
import org.jobrunr.jobs.lambdas.JobLambda;
import org.jobrunr.scheduling.JobScheduler;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Linking a Bosta delivery from the order's own Shopify fulfillment (V131, 2026-10-03).
 *
 *   fl1  type 10 (SEND) links — through the discovery pipeline: shipment at Bosta's state, status
 *        history, not-traced tag, reconcile flag cleared; order stays 'new', no pieces/allocations
 *        (the late-discovery outcome of BRK-44871)
 *   fl2  type 20 (RETURN TO ORIGIN) links
 *   fl3  reference pointing at another order → refused, conflict + exception, no shipment
 *   fl4  null reference AND null Shopify id → conflict + exception, no shipment
 *   fl5  Shopify id = this order (no reference) → links
 *   fl6  order already has a different active forward leg → no-op, no Bosta call
 *   fl7  tracking number already a shipment on another order → dedicated conflict, no link
 *   fl8  a repeated attempt (duplicate event) → one link, one event
 *   fl9  concurrent attempts → one link
 *   fl10 404, then FOUND on the retry → links; 404 for 24 h → gave_up + exception
 *   fl11 429 → retry at Bosta's retry-after, no attempt counted
 *   fl12 trigger: orders/updated with a Bosta fulfillment enqueues ONE job (deterministic id) that
 *        links; a duplicate orders/updated enqueues the same id; Wijha / "Other" / cancelled
 *        fulfillments never enqueue and never fetch
 *   fl13 cross-tenant: A's tracking number fetched only with A's key, never linked to B's order;
 *        same-tenant positive control for B
 *   fl14 a reference that resolves only through the part after ':' ("blncoeg:#515960") links to
 *        THIS order — the pipeline uses the order hint, not its own reference matching
 *   fl15 a customer return pickup (type 25) on a Shopify fulfillment is never linked as a forward leg
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BostaFulfillmentLinkTest {

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
        // fl11: a 429 is now retried within the run (hotfix c7514f2) — no real waiting in tests.
        r.add("bosta.fulfillment-link.max-backoff-ms", () -> "0");
    }

    static final String CREATED = "Thu Oct 01 2026 15:20:00 GMT+0000 (Coordinated Universal Time)";

    @Autowired JdbcTemplate                jdbc;
    @Autowired ObjectMapper                mapper;
    @Autowired EncryptionService           encryptionService;
    @Autowired BostaFulfillmentLinkService linkService;
    @Autowired ShopifyWebhookProcessorJob  processorJob;
    @Autowired ExceptionService            exceptionService;
    @MockBean  BostaGateway                bostaGateway;
    @MockBean  JobScheduler                jobScheduler;

    record Shop(UUID tenantId, UUID storeId, String domain) {}

    private Shop a, b;

    @BeforeEach
    void setUp() {
        reset(bostaGateway, jobScheduler);
        a = shop("BROEK-fl");
        b = shop("Other-fl");
    }

    // ── fl1 / fl2 ─────────────────────────────────────────────────────────────

    @Test
    void fl1_type10_links_likeALateDiscoveryLink() {
        UUID order = order(a, "BRK-44841-EG", 18912387137815L);
        jdbc.update("UPDATE orders SET bosta_link_status = 'not_created', bosta_link_attempts = 10 WHERE id = ?", order);
        tracking(a, order, "9432163061");
        delivery(a, "9432163061", 10, "Send", 24, "BRK-44841-EG", "18912387137815");

        BostaFulfillmentLinkService.Result r = linkService.attempt(a.tenantId(), order, "9432163061", false);

        assertThat(r.verdict()).isEqualTo(BostaFulfillmentLinkService.Verdict.LINKED);
        Map<String, Object> ship = jdbc.queryForMap(
            "SELECT internal_state::text AS st, provider_state, shipment_leg FROM shipments WHERE tracking_number = '9432163061'");
        assertThat(ship.get("st")).isEqualTo("with_courier");
        assertThat(ship.get("provider_state")).isEqualTo(24);
        assertThat(ship.get("shipment_leg")).isEqualTo("forward");
        assertThat(count("SELECT COUNT(*) FROM shipment_status_history h JOIN shipments s ON s.id = h.shipment_id " +
            "WHERE s.tracking_number = '9432163061'")).isEqualTo(1);
        Map<String, Object> o = jdbc.queryForMap(
            "SELECT status::text AS st, bosta_link_status, bosta_link_attempts, not_traced_at FROM orders WHERE id = ?", order);
        assertThat(o.get("st")).as("never picked in Traced → stays 'new'").isEqualTo("new");
        assertThat(o.get("bosta_link_status")).isNull();
        assertThat(o.get("bosta_link_attempts")).isEqualTo(0);
        assertThat(o.get("not_traced_at")).as("shipped without Traced custody").isNotNull();
        assertThat(count("SELECT COUNT(*) FROM allocations al JOIN order_items oi ON oi.id = al.order_item_id " +
            "WHERE oi.order_id = '" + order + "'")).isZero();
        assertThat(count("SELECT COUNT(*) FROM piece_events WHERE order_id = '" + order + "'")).isZero();
        assertThat(linkStatus(a, order, "9432163061")).isEqualTo("linked");
        assertThat(count("SELECT COUNT(*) FROM webhook_events WHERE source::text = 'shopify_fulfillment' " +
            "AND payload->>'trackingNumber' = '9432163061' AND status::text = 'processed'")).isEqualTo(1);
    }

    @Test
    void fl2_type20_returnToOrigin_links() {
        UUID order = order(a, "BRK-44826-EG", 18912000000826L);
        tracking(a, order, "8641091084");
        delivery(a, "8641091084", 20, "Return to Origin", 30, "BRK-44826-EG", "18912000000826");

        assertThat(linkService.attempt(a.tenantId(), order, "8641091084", false).verdict())
            .isEqualTo(BostaFulfillmentLinkService.Verdict.LINKED);
        assertThat(count("SELECT COUNT(*) FROM shipments WHERE tracking_number = '8641091084' " +
            "AND order_id = '" + order + "' AND shipment_leg = 'forward'")).isEqualTo(1);
    }

    // ── fl3 / fl4 / fl5 ───────────────────────────────────────────────────────

    @Test
    void fl3_referencePointsAtAnotherOrder_refused_withException() {
        UUID order = order(a, "BRK-44827-EG", 18912000000827L);
        order(a, "BRK-44833-EG", 18912000000833L);
        tracking(a, order, "8653144427");
        delivery(a, "8653144427", 10, "Send", 24, "BRK-44833-EG", null);

        BostaFulfillmentLinkService.Result r = linkService.attempt(a.tenantId(), order, "8653144427", false);

        assertThat(r.verdict()).isEqualTo(BostaFulfillmentLinkService.Verdict.SKIP);
        assertThat(r.reason()).contains("BRK-44833-EG");
        assertThat(count("SELECT COUNT(*) FROM shipments WHERE tracking_number = '8653144427'")).isZero();
        assertThat(linkStatus(a, order, "8653144427")).isEqualTo("conflict");
        assertThat(problems(a)).singleElement().satisfies(e -> assertThat(e.get("tracking_number")).isEqualTo("8653144427"));
    }

    @Test
    void fl4_nullReferenceAndNullShopifyId_exception_noLink() {
        UUID order = order(a, "BRK-44836-EG", 18912000000836L);
        tracking(a, order, "9849943191");
        delivery(a, "9849943191", 10, "Send", 24, null, null);

        BostaFulfillmentLinkService.Result r = linkService.attempt(a.tenantId(), order, "9849943191", false);

        assertThat(r.reason()).contains("no reference and no Shopify order id");
        assertThat(count("SELECT COUNT(*) FROM shipments WHERE tracking_number = '9849943191'")).isZero();
        assertThat(problems(a)).hasSize(1);
    }

    @Test
    void fl5_shopifyIdMatchesThisOrder_withoutReference_links() {
        UUID order = order(a, "BRK-44837-EG", 18912000000837L);
        tracking(a, order, "239941658");   // bare 9 digits
        delivery(a, "239941658", 10, "Send", 45, null, "18912000000837");

        assertThat(linkService.attempt(a.tenantId(), order, "239941658", false).verdict())
            .isEqualTo(BostaFulfillmentLinkService.Verdict.LINKED);
    }

    // ── fl6 / fl7 ─────────────────────────────────────────────────────────────

    @Test
    void fl6_existingForwardLeg_isANoOp_noBostaCall() {
        UUID order = order(a, "BRK-44839-EG", 18912000000839L);
        shipment(a, order, "5903442100");
        tracking(a, order, "5903442156");

        BostaFulfillmentLinkService.Result r = linkService.attempt(a.tenantId(), order, "5903442156", false);

        assertThat(r.reason()).contains("active forward leg");
        verify(bostaGateway, never()).fetchDelivery(anyString(), anyString());
        assertThat(linkStatus(a, order, "5903442156")).isEqualTo("skipped");
        assertThat(problems(a)).isEmpty();
    }

    @Test
    void fl7_trackingAlreadyAShipmentOnAnotherOrder_dedicatedConflict() {
        UUID order = order(a, "BRK-44841-EG", 18912000000841L);
        UUID other = order(a, "BRK-44842-EG", 18912000000842L);
        shipment(a, other, "2251220237");
        tracking(a, order, "2251220237");

        BostaFulfillmentLinkService.Result r = linkService.attempt(a.tenantId(), order, "2251220237", false);

        assertThat(r.reason()).isEqualTo("tracking number is already a shipment on order BRK-44842-EG");
        assertThat(count("SELECT COUNT(*) FROM shipments WHERE tracking_number = '2251220237' " +
            "AND order_id = '" + other + "'")).as("never moved").isEqualTo(1);
        assertThat(linkStatus(a, order, "2251220237")).isEqualTo("conflict");
        assertThat(problems(a)).hasSize(1);
    }

    // ── fl8 / fl9 ─────────────────────────────────────────────────────────────

    @Test
    void fl8_repeatedAttempt_oneLink_oneEvent() {
        UUID order = order(a, "BRK-44843-EG", 18912000000843L);
        tracking(a, order, "9573447153");
        delivery(a, "9573447153", 10, "Send", 24, "BRK-44843-EG", null);

        linkService.attempt(a.tenantId(), order, "9573447153", false);
        BostaFulfillmentLinkService.Result second = linkService.attempt(a.tenantId(), order, "9573447153", false);

        assertThat(second.reason()).isEqualTo("already linked");
        assertThat(count("SELECT COUNT(*) FROM shipments WHERE tracking_number = '9573447153'")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM webhook_events WHERE source::text = 'shopify_fulfillment' " +
            "AND payload->>'trackingNumber' = '9573447153'")).isEqualTo(1);
    }

    @Test
    void fl9_concurrentAttempts_oneLink() throws Exception {
        UUID order = order(a, "BRK-44844-EG", 18912000000844L);
        tracking(a, order, "1282197865");
        delivery(a, "1282197865", 10, "Send", 24, "BRK-44844-EG", null);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<BostaFulfillmentLinkService.Result>> fs = List.of(
            pool.submit(() -> { go.await(); return linkService.attempt(a.tenantId(), order, "1282197865", false); }),
            pool.submit(() -> { go.await(); return linkService.attempt(a.tenantId(), order, "1282197865", false); }));
        go.countDown();
        for (var f : fs) f.get();
        pool.shutdown();

        assertThat(count("SELECT COUNT(*) FROM shipments WHERE tracking_number = '1282197865'")).isEqualTo(1);
        // Whichever attempt lost the race finds the shipment on its next run.
        linkService.attempt(a.tenantId(), order, "1282197865", false);
        assertThat(linkStatus(a, order, "1282197865")).isEqualTo("linked");
    }

    // ── fl10 / fl11 ───────────────────────────────────────────────────────────

    @Test
    void fl10_notFoundThenFound_links_andNotFoundFor24h_givesUp() {
        UUID order = order(a, "BRK-44849-EG", 18912000000849L);
        tracking(a, order, "1228636146");
        when(bostaGateway.fetchDelivery(eq("key-" + a.tenantId()), eq("1228636146")))
            .thenReturn(null)
            .thenReturn(BostaDelivery.fromRaw("1228636146", raw("1228636146", 10, "Send", 24, "BRK-44849-EG", null)));

        linkService.attempt(a.tenantId(), order, "1228636146", false);
        assertThat(linkStatus(a, order, "1228636146")).isEqualTo("retry");
        assertThat(problems(a)).as("no exception while retrying").isEmpty();

        jdbc.update("UPDATE order_fulfillment_tracking SET link_next_retry_at = now() - INTERVAL '1 minute' " +
            "WHERE tracking_number = '1228636146'");
        linkService.retryDue();
        assertThat(linkStatus(a, order, "1228636146")).isEqualTo("linked");

        // A number Bosta never finds: after 24 h → gave_up + exception.
        UUID order2 = order(a, "BRK-44851-EG", 18912000000851L);
        tracking(a, order2, "209760032");
        when(bostaGateway.fetchDelivery(anyString(), eq("209760032")))
            .thenThrow(new DeliveryNotFoundException("209760032"));
        linkService.attempt(a.tenantId(), order2, "209760032", false);
        jdbc.update("UPDATE order_fulfillment_tracking SET link_first_failed_at = now() - INTERVAL '25 hours', " +
            "link_next_retry_at = now() - INTERVAL '1 minute' WHERE tracking_number = '209760032'");
        linkService.retryDue();
        assertThat(linkStatus(a, order2, "209760032")).isEqualTo("gave_up");
        assertThat(problems(a)).singleElement().satisfies(e -> assertThat(e.get("tracking_number")).isEqualTo("209760032"));
    }

    @Test
    void fl11_rateLimited_retriesAtRetryAfter_noAttemptCounted() {
        UUID order = order(a, "BRK-44855-EG", 18912000000855L);
        tracking(a, order, "563601351");
        when(bostaGateway.fetchDelivery(anyString(), eq("563601351"))).thenThrow(new BostaRateLimitException(90));

        linkService.attempt(a.tenantId(), order, "563601351", false);

        Map<String, Object> row = jdbc.queryForMap(
            "SELECT link_status, link_attempts, link_first_failed_at, " +
            "  link_next_retry_at BETWEEN now() + INTERVAL '80 seconds' AND now() + INTERVAL '100 seconds' AS at_retry_after " +
            "FROM order_fulfillment_tracking WHERE tracking_number = '563601351'");
        assertThat(row.get("link_status")).isEqualTo("retry");
        assertThat(row.get("link_attempts")).isEqualTo(0);
        assertThat(row.get("link_first_failed_at")).isNull();
        assertThat(row.get("at_retry_after")).isEqualTo(true);
    }

    // ── fl12 ──────────────────────────────────────────────────────────────────

    @Test
    void fl12_trigger_bostaFulfillmentEnqueuesOneJob_othersNever() throws Exception {
        String gid = "gid://shopify/Order/18914721661207";
        UUID order = order(a, "BRK-44898-EG", 18914721661207L);
        delivery(a, "9214743303", 10, "Send", 24, "BRK-44898-EG", "18914721661207");
        ArrayNode f = mapper.createArrayNode();
        f.add(fulfillment(1L, "success", "Bosta", "9214743303", "https://bosta.co/tracking-shipments?shipment-number=9214743303"));
        f.add(fulfillment(2L, "success", "Wijha", "WJ-12345", null));
        f.add(fulfillment(3L, "success", "Other", "8958142126", null));
        f.add(fulfillment(4L, "cancelled", "Bosta", "5498300341", null));

        orderUpdated(a, gid, "BRK-44898-EG", f);
        orderUpdated(a, gid, "BRK-44898-EG", f);   // duplicate event

        ArgumentCaptor<UUID> ids = ArgumentCaptor.forClass(UUID.class);
        ArgumentCaptor<JobLambda> jobs = ArgumentCaptor.forClass(JobLambda.class);
        verify(jobScheduler, times(2)).enqueue(ids.capture(), jobs.capture());
        UUID expected = BostaFulfillmentLinkService.jobId(a.tenantId(), order, "9214743303");
        assertThat(ids.getAllValues()).containsOnly(expected);

        jobs.getValue().run();   // what JobRunr runs (once — the id is the same)
        assertThat(linkStatus(a, order, "9214743303")).isEqualTo("linked");
        verify(bostaGateway, never()).fetchDelivery(anyString(), eq("WJ-12345"));
        verify(bostaGateway, never()).fetchDelivery(anyString(), eq("8958142126"));
        verify(bostaGateway, never()).fetchDelivery(anyString(), eq("5498300341"));

        // Once linked, a later orders/updated enqueues nothing.
        orderUpdated(a, gid, "BRK-44898-EG", f);
        verify(jobScheduler, times(2)).enqueue(any(UUID.class), any(JobLambda.class));
    }

    // ── fl13 ──────────────────────────────────────────────────────────────────

    @Test
    void fl13_crossTenant_ownKeyOnly_neverLinksToOtherTenantsOrder() {
        UUID orderA = order(a, "BRK-44866-EG", 18912000000866L);
        UUID orderB = order(b, "BRK-44866-EG", 18912000000866L);   // same number in another tenant
        tracking(a, orderA, "8948149267");
        delivery(a, "8948149267", 10, "Send", 24, "BRK-44866-EG", null);

        assertThat(linkService.attempt(a.tenantId(), orderA, "8948149267", false).verdict())
            .isEqualTo(BostaFulfillmentLinkService.Verdict.LINKED);
        verify(bostaGateway, never()).fetchDelivery(eq("key-" + b.tenantId()), anyString());
        assertThat(count("SELECT COUNT(*) FROM shipments WHERE order_id = '" + orderB + "'")).isZero();

        // B attempting A's order id: not its order → nothing fetched, nothing linked.
        assertThat(linkService.attempt(b.tenantId(), orderA, "8948149267", false).reason()).isEqualTo("order not found");

        // Same-tenant positive control for B.
        tracking(b, orderB, "5498300341");
        delivery(b, "5498300341", 10, "Send", 24, "BRK-44866-EG", null);
        assertThat(linkService.attempt(b.tenantId(), orderB, "5498300341", false).verdict())
            .isEqualTo(BostaFulfillmentLinkService.Verdict.LINKED);
        verify(bostaGateway, never()).fetchDelivery(eq("key-" + a.tenantId()), eq("5498300341"));
    }

    // ── fl14 / fl15 ───────────────────────────────────────────────────────────

    @Test
    void fl14_colonReference_linksThroughTheOrderHint() {
        UUID order = order(a, "#515960", 18916407738699L);
        tracking(a, order, "6735013499");
        delivery(a, "6735013499", 10, "Send", 24, "blncoeg:#515960", null);

        assertThat(linkService.attempt(a.tenantId(), order, "6735013499", false).verdict())
            .isEqualTo(BostaFulfillmentLinkService.Verdict.LINKED);
        assertThat(count("SELECT COUNT(*) FROM shipments WHERE tracking_number = '6735013499' " +
            "AND order_id = '" + order + "'")).isEqualTo(1);
    }

    @Test
    void fl15_customerReturnPickup_neverLinkedAsForward() {
        UUID order = order(a, "BRK-44880-EG", 18912000000880L);
        tracking(a, order, "1002219850");
        delivery(a, "1002219850", 25, "Customer Return Pickup", 10, "BRK-44880-EG", null);

        BostaFulfillmentLinkService.Result r = linkService.attempt(a.tenantId(), order, "1002219850", false);

        assertThat(r.reason()).isEqualTo("not a forward delivery (Bosta type 25)");
        assertThat(count("SELECT COUNT(*) FROM shipments WHERE tracking_number = '1002219850'")).isZero();
        assertThat(linkStatus(a, order, "1002219850")).isEqualTo("conflict");
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private Shop shop(String name) {
        UUID tenantId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        String domain = "fl-" + tenantId.toString().substring(0, 8) + ".myshopify.com";
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?)", tenantId, name);
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status, orders_ingest_from) " +
            "VALUES (?, ?, 'shopify', ?, 'connected', ?)", storeId, tenantId, domain,
            Timestamp.from(Instant.parse("2026-09-29T10:51:17Z")));
        jdbc.update("INSERT INTO courier_accounts (tenant_id, provider, api_key_encrypted, webhook_secret, status) " +
            "VALUES (?, 'bosta', ?, 'h', 'active')", tenantId, encryptionService.encrypt("key-" + tenantId));
        return new Shop(tenantId, storeId, domain);
    }

    private UUID order(Shop s, String number, long shopifyId) {
        return jdbc.queryForObject(
            "INSERT INTO orders (tenant_id, store_id, external_id, number, status, placed_at) " +
            "VALUES (?, ?, ?, ?, 'new'::order_status, ?) RETURNING id", UUID.class,
            s.tenantId(), s.storeId(), "gid://shopify/Order/" + shopifyId, number,
            Timestamp.from(Instant.parse("2026-09-30T12:00:00Z")));
    }

    private void tracking(Shop s, UUID order, String tn) {
        jdbc.update("INSERT INTO order_fulfillment_tracking (tenant_id, order_id, tracking_number, carrier_raw, " +
            "carrier_class, shopify_fulfillment_id, fulfillment_status) VALUES (?, ?, ?, 'Bosta', 'bosta', '1', 'success')",
            s.tenantId(), order, tn);
    }

    private void shipment(Shop s, UUID order, String tn) {
        jdbc.update("INSERT INTO shipments (tenant_id, order_id, provider, tracking_number, internal_state) " +
            "VALUES (?, ?, 'bosta', ?, 'with_courier'::shipment_internal_state)", s.tenantId(), order, tn);
    }

    private ObjectNode raw(String tn, int typeCode, String typeValue, int state, String ref, String shopifyId) {
        ObjectNode raw = mapper.createObjectNode();
        raw.put("_id", "bosta-" + tn);
        raw.put("trackingNumber", tn);
        if (ref != null) raw.put("businessReference", ref); else raw.putNull("businessReference");
        if (shopifyId != null) raw.putObject("shopifyInfo").put("orderId", shopifyId);
        raw.put("createdAt", CREATED);
        raw.put("updatedAt", "2026-10-01T18:30:00.000Z");
        raw.put("creationSrc", "SHOPIFY");
        raw.putObject("type").put("code", typeCode).put("value", typeValue);
        raw.putObject("state").put("code", state).put("value", "x");
        return raw;
    }

    private void delivery(Shop s, String tn, int typeCode, String typeValue, int state, String ref, String shopifyId) {
        when(bostaGateway.fetchDelivery(eq("key-" + s.tenantId()), eq(tn)))
            .thenReturn(BostaDelivery.fromRaw(tn, raw(tn, typeCode, typeValue, state, ref, shopifyId)));
    }

    private ObjectNode fulfillment(long id, String status, String company, String tn, String url) {
        ObjectNode f = mapper.createObjectNode();
        f.put("id", id);
        f.put("status", status);
        f.put("service", "manual");
        f.put("tracking_company", company);
        f.put("tracking_number", tn);
        f.putArray("tracking_numbers").add(tn);
        if (url != null) f.put("tracking_url", url);
        return f;
    }

    private void orderUpdated(Shop s, String gid, String name, ArrayNode fulfillments) {
        ObjectNode p = mapper.createObjectNode();
        p.put("id", Long.parseLong(gid.substring(gid.lastIndexOf('/') + 1)));
        p.put("admin_graphql_api_id", gid);
        p.put("name", name);
        p.put("created_at", "2026-10-01T05:53:53+03:00");
        p.put("financial_status", "pending");
        p.put("current_total_price", "450.00");
        p.putArray("line_items");
        p.set("fulfillments", fulfillments);
        UUID eventId = jdbc.queryForObject(
            "INSERT INTO shopify_webhook_events (tenant_id, topic, shop_domain, webhook_id, payload_raw) " +
            "VALUES (?, 'orders/updated', ?, ?, ?::jsonb) RETURNING id",
            UUID.class, s.tenantId(), s.domain(), UUID.randomUUID().toString(), p.toString());
        processorJob.process(eventId, s.tenantId());
    }

    private String linkStatus(Shop s, UUID order, String tn) {
        return jdbc.queryForObject("SELECT link_status FROM order_fulfillment_tracking " +
            "WHERE tenant_id = ? AND order_id = ? AND tracking_number = ?", String.class, s.tenantId(), order, tn);
    }

    private List<Map<String, Object>> problems(Shop s) {
        return TenantContext.runAs(s.tenantId(), () -> exceptionService.detectAllOpen()).stream()
            .filter(e -> "fulfillment_link_problem".equals(e.get("type"))).toList();
    }

    private int count(String sql) {
        return jdbc.queryForObject(sql, Integer.class);
    }
}
