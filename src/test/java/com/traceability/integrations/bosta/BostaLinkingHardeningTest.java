package com.traceability.integrations.bosta;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.traceability.ApiException;
import com.traceability.inventory.ShipmentLinkService;
import com.traceability.security.EncryptionService;
import com.traceability.tenancy.TenantAwareDataSource;
import com.traceability.tenancy.TenantContext;
import org.jobrunr.scheduling.JobScheduler;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Bosta linking hardening (2026-09-30, Step 1 of the late-booking investigation).
 *
 * Step 0 found late SEND links already work on the delivery side (matchByBusinessReference
 * ignores bosta_link_status). This class locks that in and covers the three risks found:
 *
 *   h1 — late SEND for a not_created order links on arrival and clears the flag (lock-in).
 *   h2 — second SEND for an order that already has an active forward leg: no second leg,
 *        NO_MATCH row, and process() completes without throwing (no job retry).
 *   h3 — AMBIGUOUS_MULTI row (two stores, same order number): reconcile must never
 *        manualLink it to one of the two orders.
 *   h4 — reconcile / manualLink never links a type-25 (or 30, or type-less) row as a forward leg.
 *   h5 — a type-20 (RETURN TO ORIGIN) row for a not_created order still links as a forward
 *        leg via reconcile (type 20 = a SEND Bosta re-labelled on its way back).
 *   h6 — cross-tenant: the same bare numeric reference in another tenant never links;
 *        same-tenant positive control links.
 *
 * Fixtures are shaped like production: BRK-xxxxx-EG order names, bare numeric tracking
 * numbers, a bare numeric Bosta reference against a '#'-prefixed Shopify name.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BostaLinkingHardeningTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("traceability_test")
                    .withUsername("postgres")
                    .withPassword("postgres");

    static { POSTGRES.start(); }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",           POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username",      POSTGRES::getUsername);
        r.add("spring.datasource.password",      POSTGRES::getPassword);
        r.add("spring.flyway.url",               POSTGRES::getJdbcUrl);
        r.add("spring.flyway.user",              POSTGRES::getUsername);
        r.add("spring.flyway.password",          POSTGRES::getPassword);
        r.add("bosta.reconcile.max-attempts",    () -> "3");
    }

    private static final int MAX_ATTEMPTS = 3;

    @Autowired JdbcTemplate           jdbc;
    @Autowired ObjectMapper           mapper;
    @Autowired EncryptionService      encryptionService;
    @Autowired BostaWebhookJob        webhookJob;
    @Autowired BostaOrderReconcileJob reconcileJob;
    @Autowired ShipmentLinkService    shipmentLinkService;
    @MockBean  BostaGateway           bostaGateway;
    @MockBean  JobScheduler           jobScheduler;

    private JdbcTemplate        appUserJdbc;
    private TransactionTemplate appUserTx;

    private UUID tenantA, storeA1, storeA2;
    private UUID tenantB, storeB;

    @BeforeAll
    void setup() {
        DriverManagerDataSource rawDs =
                new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "app_user", "testpw");
        TenantAwareDataSource appUserDs = new TenantAwareDataSource(rawDs);
        appUserJdbc = new JdbcTemplate(appUserDs);
        appUserTx   = new TransactionTemplate(new DataSourceTransactionManager(appUserDs));

        tenantA = UUID.randomUUID();
        storeA1 = UUID.randomUUID();
        storeA2 = UUID.randomUUID();
        tenantB = UUID.randomUUID();
        storeB  = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, 'HardeningTenantA')", tenantA);
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, 'HardeningTenantB')", tenantB);
        insertStore(storeA1, tenantA, "hardening-a1.myshopify.com");
        insertStore(storeA2, tenantA, "hardening-a2.myshopify.com");
        insertStore(storeB,  tenantB, "hardening-b.myshopify.com");
        insertCourierAccount(tenantA, "hardening-secret-a");
        insertCourierAccount(tenantB, "hardening-secret-b");
    }

    @BeforeEach
    void cleanup() {
        for (UUID t : List.of(tenantA, tenantB)) {
            jdbc.update("DELETE FROM shipment_status_history   WHERE tenant_id = ?", t);
            jdbc.update("DELETE FROM unlinked_bosta_deliveries WHERE tenant_id = ?", t);
            jdbc.update("DELETE FROM shipments                 WHERE tenant_id = ?", t);
            jdbc.update("DELETE FROM orders                    WHERE tenant_id = ?", t);
            jdbc.update("DELETE FROM webhook_events            WHERE tenant_id = ?", t);
        }
    }

    // ── h1: late SEND for a not_created order links on arrival ─────────────────────────────

    @Test
    void h1_lateSend_notCreatedOrder_linksOnArrival_andClearsFlag() {
        UUID orderId = insertOrder(tenantA, storeA1, "BRK-44815-EG");
        flagNotCreated(orderId);

        long wid = processSend(tenantA, "4152295425", 24, "BRK-44815-EG", "2026-09-30T09:00:00.000Z");

        Map<String, Object> ship = jdbc.queryForMap(
            "SELECT order_id, shipment_leg, internal_state::text AS st FROM shipments " +
            "WHERE tracking_number = '4152295425'");
        assertThat(ship.get("order_id")).isEqualTo(orderId);
        assertThat(ship.get("shipment_leg")).isEqualTo("forward");
        assertThat(ship.get("st")).isEqualTo("with_courier");

        Map<String, Object> o = jdbc.queryForMap(
            "SELECT bosta_link_status, bosta_link_attempts FROM orders WHERE id = ?", orderId);
        assertThat(o.get("bosta_link_status")).as("flag cleared on link").isNull();
        assertThat(o.get("bosta_link_attempts")).isEqualTo(0);

        assertThat(unresolvedCount(tenantA, "4152295425")).isZero();
        assertThat(eventStatus(wid)).isEqualTo("processed");
    }

    // ── h2: second SEND for an order that already has an active forward leg ────────────────

    @Test
    void h2_secondSend_orderWithActiveForwardLeg_noDoubleLink_noNoMatchThrow() {
        UUID orderId = insertOrder(tenantA, storeA1, "BRK-44816-EG");
        insertForwardShipment(tenantA, orderId, "757830510", "with_courier");

        long[] wid = new long[1];
        assertThatCode(() ->
            wid[0] = processSend(tenantA, "3670960121", 10, "BRK-44816-EG", "2026-09-30T09:05:00.000Z"))
            .as("process() must complete — a throw means JobRunr retries the event")
            .doesNotThrowAnyException();

        Integer legs = jdbc.queryForObject(
            "SELECT COUNT(*) FROM shipments WHERE order_id = ? AND shipment_leg = 'forward'",
            Integer.class, orderId);
        assertThat(legs).as("no second forward leg").isEqualTo(1);
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM shipments WHERE tracking_number = '3670960121'", Integer.class))
            .isZero();

        Map<String, Object> row = jdbc.queryForMap(
            "SELECT match_reason, resolved FROM unlinked_bosta_deliveries " +
            "WHERE tenant_id = ? AND tracking_number = '3670960121'", tenantA);
        assertThat(row.get("match_reason")).isEqualTo("NO_MATCH");
        assertThat(row.get("resolved")).isEqualTo(false);
        assertThat(eventStatus(wid[0])).isEqualTo("processed");
    }

    // ── h3: AMBIGUOUS_MULTI row is never linked by reconcile ───────────────────────────────

    @Test
    void h3_ambiguousMultiRow_reconcileNeverLinksEitherOrder() {
        UUID o1 = insertOrder(tenantA, storeA1, "BRK-44820-EG");
        UUID o2 = insertOrder(tenantA, storeA2, "BRK-44820-EG");
        flagNotCreated(o1);
        flagNotCreated(o2);

        processSend(tenantA, "9869548113", 10, "BRK-44820-EG", "2026-09-30T09:10:00.000Z");

        Map<String, Object> row = jdbc.queryForMap(
            "SELECT match_reason, resolved FROM unlinked_bosta_deliveries " +
            "WHERE tenant_id = ? AND tracking_number = '9869548113'", tenantA);
        assertThat(row.get("match_reason")).isEqualTo("AMBIGUOUS_MULTI");

        // Record the flag-clear behaviour (reported, not asserted as a rule).
        List<String> flags = jdbc.queryForList(
            "SELECT coalesce(bosta_link_status, 'NULL') FROM orders WHERE id IN (?, ?) ORDER BY id",
            String.class, o1, o2);
        System.out.println("h3 flags after first arrival: " + flags);

        for (int tick = 0; tick <= MAX_ATTEMPTS + 1; tick++) {
            expireCooldown(tenantA);
            reconcileJob.reconcileAll();
        }

        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM shipments WHERE order_id IN (?, ?)", Integer.class, o1, o2))
            .as("neither order linked").isZero();
        Map<String, Object> after = jdbc.queryForMap(
            "SELECT match_reason, resolved FROM unlinked_bosta_deliveries " +
            "WHERE tenant_id = ? AND tracking_number = '9869548113'", tenantA);
        assertThat(after.get("match_reason")).isEqualTo("AMBIGUOUS_MULTI");
        assertThat(after.get("resolved")).isEqualTo(false);
    }

    /**
     * The row was recorded as a plain NO_MATCH (no order yet); two stores then ingest orders
     * with that same number. The row's reason says nothing about ambiguity, so only the
     * "reference matches exactly one order" rule keeps reconcile from picking one.
     */
    @Test
    void h3b_noMatchRow_laterTwoOrdersSameNumber_reconcileNeverLinks() {
        String tn = "1221548202";
        record(tn, raw(tn, 10, 10, "Send", "BRK-44824-EG"));
        assertThat(jdbc.queryForObject(
            "SELECT match_reason FROM unlinked_bosta_deliveries WHERE tenant_id = ? AND tracking_number = ?",
            String.class, tenantA, tn)).isEqualTo("NO_MATCH");

        UUID o1 = insertOrder(tenantA, storeA1, "BRK-44824-EG");
        UUID o2 = insertOrder(tenantA, storeA2, "BRK-44824-EG");

        for (int tick = 0; tick <= MAX_ATTEMPTS + 1; tick++) {
            expireCooldown(tenantA);
            reconcileJob.reconcileAll();
        }

        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM shipments WHERE order_id IN (?, ?)", Integer.class, o1, o2))
            .as("neither order linked").isZero();
        assertThat(unresolvedCount(tenantA, tn)).isEqualTo(1);
    }

    /**
     * The reference matched nothing when the delivery arrived (order not ingested yet) and
     * Bosta had no receiver phone, so the phone+COD fallback recorded COD_ONLY_AMBIGUOUS.
     * That reason describes the fallback, not the reference — once the order is ingested
     * with a matching number, reconcile must link it.
     */
    @Test
    void h3c_codOnlyRow_orderIngestedLater_reconcileLinks() {
        String tn = "5141584932";
        ObjectNode r = raw(tn, 10, 10, "Send", "BRK-44825-EG");
        r.remove("receiver");
        when(bostaGateway.fetchDelivery(anyString(), eq(tn)))
            .thenReturn(BostaDelivery.fromRaw(tn, r));
        Long wid = jdbc.queryForObject(
            "INSERT INTO webhook_events (source, tenant_id, topic, payload, status, received_at) " +
            "VALUES ('bosta', ?, 'delivery_update', ?::jsonb, 'pending', now()) RETURNING id",
            Long.class, tenantA,
            String.format("{\"trackingNumber\":\"%s\",\"state\":10,\"type\":\"SEND\"," +
                "\"updatedAt\":\"2026-09-30T09:30:00.000Z\"}", tn));
        webhookJob.process(wid, tenantA);

        assertThat(jdbc.queryForObject(
            "SELECT match_reason FROM unlinked_bosta_deliveries WHERE tenant_id = ? AND tracking_number = ?",
            String.class, tenantA, tn)).isEqualTo("COD_ONLY_AMBIGUOUS");

        UUID orderId = insertOrder(tenantA, storeA1, "BRK-44825-EG");
        reconcileJob.reconcileAll();

        Map<String, Object> ship = jdbc.queryForMap(
            "SELECT order_id, shipment_leg FROM shipments WHERE tracking_number = ?", tn);
        assertThat(ship.get("order_id")).isEqualTo(orderId);
        assertThat(ship.get("shipment_leg")).isEqualTo("forward");
        assertThat(unresolvedCount(tenantA, tn)).isZero();
    }

    // ── h4: reconcile / manualLink never create a forward leg for type 25 / 30 / no type ───

    @Test
    void h4_crpRow_notLinkedAsForward_byReconcileOrManualLink() {
        UUID orderId = insertOrder(tenantA, storeA1, "BRK-44821-EG");
        long crpRowId = insertUnlinkedRow(tenantA, "9371680874", "BRK-44821-EG", 46,
            "CUSTOMER RETURN PICKUP", raw("9371680874", 46, 25, "Customer Return Pickup", "BRK-44821-EG"));

        for (int tick = 0; tick <= MAX_ATTEMPTS; tick++) {
            expireCooldown(tenantA);
            reconcileJob.reconcileAll();
        }
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM shipments WHERE tracking_number = '9371680874'", Integer.class))
            .as("reconcile must not link a CRP row").isZero();
        assertThat(unresolvedCount(tenantA, "9371680874")).isEqualTo(1);
        // The CRP row is not a reconcile candidate at all: the order goes through its normal
        // attempts up to max-attempts — rather than reconcile picking the row every tick, having
        // manualLink refuse it, and never advancing the counter. (V139, 2026-10-05: the job no
        // longer flags 'not_created' at max-attempts; the order just leaves the candidate set.)
        assertThat(jdbc.queryForObject(
            "SELECT bosta_link_attempts FROM orders WHERE id = ?", Integer.class, orderId))
            .as("CRP row is never a reconcile candidate").isEqualTo(MAX_ATTEMPTS);
        assertThat(jdbc.queryForObject(
            "SELECT bosta_link_status FROM orders WHERE id = ?", String.class, orderId))
            .as("V139: never flagged 'not_created'").isNull();

        assertManualLinkRefused(crpRowId, orderId, "9371680874");
    }

    @Test
    void h4b_exchangeAndTypelessRows_refusedByManualLink() {
        UUID orderId = insertOrder(tenantA, storeA1, "BRK-44823-EG");
        long exchRowId = insertUnlinkedRow(tenantA, "8227227652", "BRK-44823-EG", 10,
            "EXCHANGE", raw("8227227652", 10, 30, "Exchange", "BRK-44823-EG"));
        long typelessRowId = insertUnlinkedRow(tenantA, "1974819079", "BRK-44823-EG", 10,
            "SEND", null);

        assertManualLinkRefused(exchRowId, orderId, "8227227652");
        assertManualLinkRefused(typelessRowId, orderId, "1974819079");
    }

    // ── h5: type-20 (RETURN TO ORIGIN) row links as a forward leg via reconcile ───────────

    @Test
    void h5_returnToOriginRow_notCreatedOrder_linksAsForwardViaReconcile() {
        UUID orderId = insertOrder(tenantA, storeA1, "BRK-44822-EG");
        flagNotCreated(orderId);

        // The delivery was seen while no order matched it (e.g. before the order was
        // ingested): SEND@10 → SEND@24 → RETURN TO ORIGIN@24, same tracking number.
        // The first arrival clears the not_created flag; later arrivals only refresh the row.
        String tn = "2017084040";
        record(tn, raw(tn, 10, 10, "Send", "BRK-44822-EG"));
        record(tn, raw(tn, 24, 10, "Send", "BRK-44822-EG"));
        record(tn, raw(tn, 24, 20, "Return to Origin", "BRK-44822-EG"));

        Map<String, Object> row = jdbc.queryForMap(
            "SELECT raw->'type'->>'code' AS code, bosta_order_type FROM unlinked_bosta_deliveries " +
            "WHERE tenant_id = ? AND tracking_number = ? AND resolved = false", tenantA, tn);
        assertThat(row.get("code")).isEqualTo("20");
        assertThat(jdbc.queryForObject(
            "SELECT bosta_link_status FROM orders WHERE id = ?", String.class, orderId)).isNull();

        reconcileJob.reconcileAll();

        Map<String, Object> ship = jdbc.queryForMap(
            "SELECT order_id, shipment_leg FROM shipments WHERE tracking_number = ?", tn);
        assertThat(ship.get("order_id")).isEqualTo(orderId);
        assertThat(ship.get("shipment_leg")).isEqualTo("forward");
        assertThat(unresolvedCount(tenantA, tn)).isZero();
    }

    // ── h6: cross-tenant isolation with a same-tenant positive control ─────────────────────

    @Test
    void h6_crossTenant_sameReference_noLink_sameTenantControlLinks() {
        UUID orderA = insertOrder(tenantA, storeA1, "#385329349470");

        // Tenant B has no such order: its delivery with the same bare numeric reference
        // must not touch tenant A's order.
        processSend(tenantB, "180348723", 24, "385329349470", "2026-09-30T09:20:00.000Z");
        for (int tick = 0; tick <= MAX_ATTEMPTS; tick++) {
            expireCooldown(tenantA);
            expireCooldown(tenantB);
            reconcileJob.reconcileAll();
        }
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM shipments WHERE order_id = ?", Integer.class, orderA))
            .as("tenant B delivery never links to tenant A order").isZero();
        assertThat(unresolvedCount(tenantB, "180348723")).isEqualTo(1);

        // Under RLS, tenant A cannot even see tenant B's unlinked row.
        Integer visibleToA = TenantContext.runAs(tenantA, () -> appUserTx.execute(s ->
            appUserJdbc.queryForObject(
                "SELECT COUNT(*) FROM unlinked_bosta_deliveries WHERE tracking_number = '180348723'",
                Integer.class)));
        assertThat(visibleToA).isZero();

        // Positive control: the same reference shape in tenant A links to tenant A's order.
        processSend(tenantA, "4818277658", 24, "385329349470", "2026-09-30T09:25:00.000Z");
        Map<String, Object> ship = jdbc.queryForMap(
            "SELECT order_id, tenant_id FROM shipments WHERE tracking_number = '4818277658'");
        assertThat(ship.get("order_id")).isEqualTo(orderA);
        assertThat(ship.get("tenant_id")).isEqualTo(tenantA);
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────────────

    private void assertManualLinkRefused(long unlinkedId, UUID orderId, String tracking) {
        Throwable t = catchThrowable(() -> TenantContext.runAs(tenantA, () ->
            shipmentLinkService.manualLink(unlinkedId, orderId, null)));
        assertThat(t).as("manualLink must refuse row %s", tracking).isInstanceOf(ApiException.class);
        ApiException ex = (ApiException) t;
        assertThat(ex.httpStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(ex.errorCode()).isEqualTo("UNLINKED_DELIVERY_TYPE_NOT_LINKABLE");
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM shipments WHERE tracking_number = ?", Integer.class, tracking))
            .as("no partial write").isZero();
        assertThat(unresolvedCount(tenantA, tracking)).as("row stays open").isEqualTo(1);
    }

    private ObjectNode raw(String tracking, int state, int typeCode, String typeValue, String ref) {
        ObjectNode n = mapper.createObjectNode();
        n.put("_id", "bosta-" + tracking);
        n.put("trackingNumber", tracking);
        n.putObject("state").put("code", state);
        n.putObject("type").put("code", typeCode).put("value", typeValue);
        n.put("businessReference", ref);
        n.put("cod", 850);
        n.put("updatedAt", "2026-09-30T08:00:00.000Z");
        ObjectNode receiver = n.putObject("receiver");
        receiver.put("fullName", "Test Receiver");
        receiver.put("phone", "+201001234567");
        return n;
    }

    /** Runs the real webhook pipeline for a SEND delivery; returns the webhook_events id. */
    private long processSend(UUID tenant, String tracking, int state, String ref, String updatedAt) {
        ObjectNode r = raw(tracking, state, 10, "Send", ref);
        r.put("updatedAt", updatedAt);
        when(bostaGateway.fetchDelivery(anyString(), eq(tracking)))
            .thenReturn(BostaDelivery.fromRaw(tracking, r));
        Long wid = jdbc.queryForObject(
            "INSERT INTO webhook_events (source, tenant_id, topic, payload, status, received_at) " +
            "VALUES ('bosta', ?, 'delivery_update', ?::jsonb, 'pending', now()) RETURNING id",
            Long.class, tenant,
            String.format("{\"trackingNumber\":\"%s\",\"state\":%d,\"type\":\"SEND\",\"updatedAt\":\"%s\"}",
                tracking, state, updatedAt));
        webhookJob.process(wid, tenant);
        return wid;
    }

    /** recordUnlinked() exactly as the pipeline calls it on a NO_MATCH (package-private access). */
    private void record(String tracking, JsonNode rawNode) {
        BostaDelivery d = BostaDelivery.fromRaw(tracking, rawNode);
        Long wid = jdbc.queryForObject(
            "INSERT INTO webhook_events (source, tenant_id, topic, payload, status, received_at) " +
            "VALUES ('bosta', ?, 'delivery_update', ?::jsonb, 'pending', now()) RETURNING id",
            Long.class, tenantA, String.format("{\"trackingNumber\":\"%s\"}", tracking));
        TenantContext.runAs(tenantA, () ->
            webhookJob.recordUnlinked(tenantA, tracking, d, wid, "NO_MATCH"));
    }

    private long insertUnlinkedRow(UUID tenant, String tracking, String ref, int state,
                                   String orderType, JsonNode rawNode) {
        return jdbc.queryForObject(
            "INSERT INTO unlinked_bosta_deliveries " +
            "  (tenant_id, tracking_number, business_reference, bosta_state_code, " +
            "   bosta_order_type, match_reason, resolved, raw) " +
            "VALUES (?, ?, ?, ?, ?, 'NO_MATCH', false, ?::jsonb) RETURNING id",
            Long.class, tenant, tracking, ref, state, orderType,
            rawNode != null ? rawNode.toString() : null);
    }

    private UUID insertOrder(UUID tenant, UUID store, String number) {
        return jdbc.queryForObject(
            "INSERT INTO orders (tenant_id, store_id, external_id, number, status, placed_at) " +
            "VALUES (?, ?, ?, ?, 'new'::order_status, now()) RETURNING id",
            UUID.class, tenant, store, "gid://shopify/Order/" + UUID.randomUUID(), number);
    }

    private void flagNotCreated(UUID orderId) {
        jdbc.update(
            "UPDATE orders SET bosta_link_status = 'not_created', bosta_link_attempts = ?, " +
            "  bosta_link_last_check = now() - interval '1 hour' WHERE id = ?",
            MAX_ATTEMPTS, orderId);
    }

    private void insertForwardShipment(UUID tenant, UUID orderId, String tracking, String state) {
        jdbc.update(
            "INSERT INTO shipments (tenant_id, order_id, provider, tracking_number, internal_state) " +
            "VALUES (?, ?, 'bosta', ?, ?::shipment_internal_state)",
            tenant, orderId, tracking, state);
    }

    /** Stands in for the 4-minute per-order cooldown between 5-minute reconcile ticks. */
    private void expireCooldown(UUID tenant) {
        jdbc.update(
            "UPDATE orders SET bosta_link_last_check = now() - interval '5 minutes' " +
            "WHERE tenant_id = ? AND bosta_link_last_check IS NOT NULL", tenant);
    }

    private int unresolvedCount(UUID tenant, String tracking) {
        Integer n = jdbc.queryForObject(
            "SELECT COUNT(*) FROM unlinked_bosta_deliveries " +
            "WHERE tenant_id = ? AND tracking_number = ? AND resolved = false",
            Integer.class, tenant, tracking);
        return n != null ? n : 0;
    }

    private String eventStatus(long wid) {
        return jdbc.queryForObject("SELECT status FROM webhook_events WHERE id = ?", String.class, wid);
    }

    private void insertStore(UUID id, UUID tenant, String domain) {
        jdbc.update(
            "INSERT INTO stores (id, tenant_id, platform, shop_domain, status) " +
            "VALUES (?, ?, 'shopify', ?, 'disconnected')", id, tenant, domain);
    }

    private void insertCourierAccount(UUID tenant, String secret) {
        jdbc.update(
            "INSERT INTO courier_accounts (tenant_id, provider, api_key_encrypted, webhook_secret, status) " +
            "VALUES (?, 'bosta', ?, ?, 'active')",
            tenant, encryptionService.encrypt("key-" + secret), secret);
    }
}
