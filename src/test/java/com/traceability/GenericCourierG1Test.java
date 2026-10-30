package com.traceability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.traceability.fulfillment.OrderController;
import com.traceability.fulfillment.OrderNotesService;
import com.traceability.fulfillment.OrderShippingBadge;
import com.traceability.fulfillment.OrderStatusDeriver;
import com.traceability.integrations.bosta.BostaDelivery;
import com.traceability.integrations.bosta.BostaFulfillmentLinkService;
import com.traceability.integrations.bosta.BostaGateway;
import com.traceability.integrations.bosta.BostaStateMapper;
import com.traceability.integrations.shopify.FulfillmentTrackingCapture;
import com.traceability.inventory.ExceptionService;
import com.traceability.inventory.FulfillService;
import com.traceability.inventory.PackPrintBatchStore;
import com.traceability.inventory.ShipmentLinkService;
import com.traceability.inventory.VariantStockService;
import com.traceability.security.EncryptionService;
import com.traceability.tenancy.TenantContext;
import org.jobrunr.scheduling.JobScheduler;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Non-Bosta couriers, G1 (V168 / V169) — generic shipment legs.
 *
 *   iso*   Bosta isolation: a Bosta tenant behaves identically with the flag off and on
 *   jumi1  Bosta tenant + "Other" → no generic row even with the flag on
 *   gen*   non-Bosta tenant: "Mylerz" / "Other" create a generic leg (pickable, badge, committed stock);
 *          flag off → nothing; with_courier reads "With {carrier}"
 *   uniq*  per-tenant generic keys, idempotent replay, Bosta keeps global uniqueness, V132 trigger scoped
 *   bw*    Bosta wins: created → generic cancelled + Bosta linked; packed → no link, HIGH exception
 *   can*   cancelled fulfillment; multi1 multiple parcels; pre1 pre-connect order
 *   stuck1 / print1  stuck alerts and print batches ignore generic legs
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GenericCourierG1Test {

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
        r.add("bosta.fulfillment-link.max-backoff-ms", () -> "0");
    }

    @Autowired JdbcTemplate                jdbc;
    @Autowired ObjectMapper                mapper;
    @Autowired PlatformTransactionManager  txm;
    @Autowired FulfillmentTrackingCapture  capture;
    @Autowired FulfillService              fulfillService;
    @Autowired VariantStockService         stock;
    @Autowired ExceptionService            exceptions;
    @Autowired ShipmentLinkService         linkService;
    @Autowired BostaFulfillmentLinkService fulfillmentLink;
    @Autowired BostaStateMapper            stateMapper;
    @Autowired PackPrintBatchStore         printBatches;
    @Autowired EncryptionService           encryptionService;
    @MockBean  BostaGateway                bostaGateway;
    @MockBean  JobScheduler                jobScheduler;

    private OrderController controller;
    private TransactionTemplate tx;

    record Shop(UUID tenantId, UUID storeId, UUID variantId) {}

    @BeforeAll
    void setup() {
        controller = new OrderController(jdbc, mapper, txm, new OrderNotesService(jdbc), fulfillService);
        tx = new TransactionTemplate(txm);
    }

    @BeforeEach void resetMocks() { reset(bostaGateway, jobScheduler); }
    @AfterEach  void clear() { TenantContext.clear(); }

    // ── iso: Bosta isolation ────────────────────────────────────────────────────────────────────

    @Test
    void iso1_bostaTenant_flagOffAndOn_identical_noGenericRow() {
        Shop off = shop("iso-off", true, false);
        Shop on  = shop("iso-on", true, true);
        Map<String, Object> snapOff = bostaScenario(off);
        Map<String, Object> snapOn  = bostaScenario(on);

        assertThat(genericCount(off)).isZero();
        assertThat(genericCount(on)).isZero();
        assertThat(snapOn).as("flag on must not change anything for a Bosta tenant").isEqualTo(snapOff);

        // Sanity: the snapshot really reads the Bosta behaviour.
        assertThat(snapOff.get("pickable")).isEqualTo(List.of("ISO-4"));
        assertThat(snapOff.get("stuck")).isEqualTo(1);
        assertThat(snapOff.get("committed")).isEqualTo(1L);
        @SuppressWarnings("unchecked") Map<String, String> badges = (Map<String, String>) snapOff.get("badges");
        assertThat(badges.get("ISO-2")).isEqualTo("awaiting_booking|null");
        assertThat(badges.get("ISO-4")).isEqualTo("linked|Bosta");
        // shipment_status is stored only for a switched-on tenant.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM order_fulfillment_tracking WHERE tenant_id = ? " +
            "AND shipment_status IS NOT NULL", Integer.class, off.tenantId())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM order_fulfillment_tracking WHERE tenant_id = ? " +
            "AND shipment_status IS NOT NULL", Integer.class, on.tenantId())).isEqualTo(3);
    }

    /** "Bosta", "Other" and blank fulfillments + one Bosta forward leg; returns everything a merchant sees. */
    private Map<String, Object> bostaScenario(Shop s) {
        UUID o1 = order(s, "ISO-1", 0), o2 = order(s, "ISO-2", 0), o3 = order(s, "ISO-3", 0), o4 = order(s, "ISO-4", 0);
        for (UUID o : List.of(o1, o2, o3, o4)) item(s, o);
        captureAs(s, o1, payload(f(11, "success", "Bosta", "4810010001", null, "2026-10-10T10:00:00Z")));
        captureAs(s, o2, payload(f(12, "success", "Other", "5810010002", null, "2026-10-10T10:00:00Z")));
        captureAs(s, o3, payload(f(13, "success", null, "6810010003", null, "2026-10-10T10:00:00Z")));
        jdbc.update("INSERT INTO shipments (tenant_id, order_id, provider, tracking_number, internal_state, created_at) " +
            "VALUES (?, ?, 'bosta', ?, 'created', now() - interval '20 days')", s.tenantId(), o4,
            String.valueOf(7_000_000_000L + new Random().nextInt(900_000_000)));

        Map<String, Object> snap = new LinkedHashMap<>();
        snap.put("badges", badges(s));
        snap.put("status", statuses(s));
        snap.put("pickable", pickable(s));
        snap.put("committed", TenantContext.runAs(s.tenantId(), () -> stock.computeAll())
            .getOrDefault(s.variantId(), new VariantStockService.VariantStock(0, 0, 0)).committed());
        snap.put("stuck", alerts(s, "stuck_shipment").size());
        return snap;
    }

    @Test
    void jumi1_bostaTenant_other_flagOn_noGenericRow() {
        Shop s = shop("jumi", true, true);
        UUID o = order(s, "J-1", 0);
        captureAs(s, o, payload(f(21, "success", "Other", "3810020001", null, "2026-10-10T10:00:00Z")));
        assertThat(genericCount(s)).isZero();
        assertThat(pickable(s)).isEmpty();
        assertThat(badges(s).get("J-1")).isEqualTo("awaiting_booking|null");
    }

    // ── gen: non-Bosta tenant ───────────────────────────────────────────────────────────────────

    @Test
    void gen1_mylerz_createsGenericLeg_pickable_badge_committed() {
        Shop s = shop("gen1", false, true);
        UUID o = order(s, "G-1", 0);
        item(s, o);
        captureAs(s, o, payload(f(31, "success", "Mylerz", "MZ 55-443", "https://mylerz.net/track/MZ-55443", "2026-10-10T10:00:00Z")));

        Map<String, Object> row = jdbc.queryForMap("SELECT provider::text AS provider, internal_state::text AS st, source, " +
            "shopify_fulfillment_id, carrier_name, tracking_url, tracking_number, tracking_key, shipment_leg FROM shipments WHERE order_id = ?", o);
        assertThat(row.get("provider")).isEqualTo("generic");
        assertThat(row.get("st")).isEqualTo("created");
        assertThat(row.get("source")).isEqualTo("shopify_fulfillment");
        assertThat(row.get("shopify_fulfillment_id")).isEqualTo("31");
        assertThat(row.get("carrier_name")).isEqualTo("Mylerz");
        assertThat(row.get("tracking_url")).isEqualTo("https://mylerz.net/track/MZ-55443");
        assertThat(row.get("tracking_number")).isEqualTo("MZ55-443");
        assertThat(row.get("tracking_key")).isEqualTo("MZ55443");
        assertThat(row.get("shipment_leg")).isEqualTo("forward");

        assertThat(pickable(s)).containsExactly("G-1");
        assertThat(badges(s).get("G-1")).isEqualTo("generic_carrier|Mylerz");
        assertThat(detailBadge(s, o)).isEqualTo(new OrderShippingBadge(OrderShippingBadge.GENERIC_CARRIER, "Mylerz", null));
        assertThat(statuses(s).get("G-1")).isEqualTo("status.label_created");
        assertThat(TenantContext.runAs(s.tenantId(), () -> stock.computeAll()).get(s.variantId()).committed())
            .as("committed stock now counts the generic-shipment order").isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT carrier_class FROM order_fulfillment_tracking WHERE order_id = ?",
            String.class, o)).isEqualTo("other_known");
    }

    @Test
    void gen2_other_noBostaAccount_creates_flagOff_nothing() {
        Shop on = shop("gen2-on", false, true);
        UUID o = order(on, "G2-1", 0);
        captureAs(on, o, payload(f(41, "success", "Other", "QP-1001", null, "2026-10-10T10:00:00Z")));
        assertThat(genericCount(on)).isEqualTo(1);
        assertThat(badges(on).get("G2-1")).isEqualTo("generic_carrier|Other");

        Shop off = shop("gen2-off", false, false);
        UUID o2 = order(off, "G2-2", 0);
        captureAs(off, o2, payload(f(42, "success", "Mylerz", "MZ-1", null, "2026-10-10T10:00:00Z")));
        captureAs(off, o2, payload(f(42, "success", "Other", "QP-2", null, "2026-10-10T10:00:00Z")));
        assertThat(genericCount(off)).isZero();
        assertThat(pickable(off)).isEmpty();
    }

    @Test
    void gen3_withCourier_readsWithCarrier_neverInTransit() {
        Shop s = shop("gen3", false, true);
        UUID o = order(s, "G3-1", 0);
        captureAs(s, o, payload(f(51, "success", "Mylerz", "MZ-777", null, "2026-10-10T10:00:00Z")));
        UUID sh = jdbc.queryForObject("SELECT id FROM shipments WHERE order_id = ?", UUID.class, o);
        jdbc.update("UPDATE shipments SET internal_state = 'with_courier' WHERE id = ?", sh);
        jdbc.update("INSERT INTO shipment_status_history (tenant_id, shipment_id, internal_state) VALUES (?, ?, 'with_courier')",
            s.tenantId(), sh);
        assertThat(statuses(s).get("G3-1")).isEqualTo(OrderStatusDeriver.WITH_CARRIER_KEY);
        assertThat(TenantContext.runAs(s.tenantId(), () -> controller.detail(o).derivedStatus().primaryKey()))
            .isEqualTo(OrderStatusDeriver.WITH_CARRIER_KEY);
        assertThat(OrderStatusDeriver.deriveLegStatus("with_courier", "generic").primaryKey()).isEqualTo("status.with_carrier");
        assertThat(OrderStatusDeriver.deriveLegStatus("with_courier", "bosta").primaryKey()).isEqualTo("status.in_transit");
    }

    // ── uniqueness ──────────────────────────────────────────────────────────────────────────────

    @Test
    void uniq1_twoTenantsSameNumber_bothInsert_sameTenantIdempotent_bostaStillGlobal() {
        Shop a = shop("uq-a", false, true), b = shop("uq-b", false, true);
        UUID oa = order(a, "U-1", 0), ob = order(b, "U-1", 0);
        ObjectNode p = payload(f(61, "success", "J&T Express", "JT0012345", null, "2026-10-10T10:00:00Z"));
        captureAs(a, oa, p);
        captureAs(b, ob, p);
        assertThat(genericCount(a)).isEqualTo(1);
        assertThat(genericCount(b)).isEqualTo(1);

        // Replay → idempotent.
        captureAs(a, oa, p);
        assertThat(genericCount(a)).isEqualTo(1);
        // Same key on another order of the same tenant ("jt-0012345" → JT0012345) → refused, never guessed.
        UUID oa2 = order(a, "U-2", 0);
        captureAs(a, oa2, payload(f(62, "success", "J&T Express", "jt-0012345", null, "2026-10-10T10:00:00Z")));
        assertThat(genericCount(a)).isEqualTo(1);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO shipments (tenant_id, order_id, provider, tracking_number, tracking_key) " +
            "VALUES (?, ?, 'generic', 'X', 'JT0012345')", a.tenantId(), oa2)).isInstanceOf(DuplicateKeyException.class);

        // Bosta keeps global tracking_number uniqueness across tenants (bare numeric fixture).
        jdbc.update("INSERT INTO shipments (tenant_id, order_id, provider, tracking_number) VALUES (?, ?, 'bosta', '9432163099')",
            a.tenantId(), oa2);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO shipments (tenant_id, order_id, provider, tracking_number, shipment_leg) " +
            "VALUES (?, ?, 'bosta', '9432163099', 'return')", b.tenantId(), ob)).isInstanceOf(DuplicateKeyException.class);
        // A generic row may carry the same raw number as a Bosta row.
        UUID ob2 = order(b, "U-3", 0);
        jdbc.update("INSERT INTO shipments (tenant_id, order_id, provider, tracking_number, tracking_key) " +
            "VALUES (?, ?, 'generic', '9432163099', '9432163099')", b.tenantId(), ob2);
        // tracking_key: generic only, canonical only.
        assertThatThrownBy(() -> jdbc.update("INSERT INTO shipments (tenant_id, order_id, provider, tracking_number, tracking_key) " +
            "VALUES (?, ?, 'bosta', '9432163100', 'X1')", a.tenantId(), order(a, "U-4", 0)))
            .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO shipments (tenant_id, order_id, provider, tracking_number, tracking_key) " +
            "VALUES (?, ?, 'generic', 'wj-1', 'wj-1')", a.tenantId(), order(a, "U-5", 0)))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void uniq2_v132ReservedRange_bostaOnly() {
        Shop s = shop("uq-777", false, true);
        UUID o = order(s, "R-1", 0);
        captureAs(s, o, payload(f(71, "success", "Mylerz", "7771234567890", null, "2026-10-10T10:00:00Z")));
        assertThat(genericCount(s)).as("a real carrier's 777… number is fine on a generic row").isEqualTo(1);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO shipments (tenant_id, order_id, provider, tracking_number, shipment_leg) " +
            "VALUES (?, ?, 'bosta', '7771234567891', 'return')", s.tenantId(), o))
            .as("Bosta rows still refuse the reserved range").isInstanceOf(DataIntegrityViolationException.class);
    }

    // ── Bosta wins ──────────────────────────────────────────────────────────────────────────────

    @Test
    void bw1_genericCreated_cancelled_andBostaLinked() {
        Shop s = shop("bw1", true, true);   // Femine-like: Bosta account + another known carrier
        UUID o = order(s, "BW-1", 0);
        captureAs(s, o, payload(f(81, "success", "Wijha", "WJ-81", null, "2026-10-10T10:00:00Z")));
        UUID generic = jdbc.queryForObject("SELECT id FROM shipments WHERE order_id = ? AND provider = 'generic'", UUID.class, o);
        bostaTracking(s, o, "9811000001");
        bostaDelivery(s, "9811000001", "BW-1");

        BostaFulfillmentLinkService.Result r = fulfillmentLink.attempt(s.tenantId(), o, "9811000001", false);

        assertThat(r.verdict()).isEqualTo(BostaFulfillmentLinkService.Verdict.LINKED);
        assertThat(jdbc.queryForObject("SELECT internal_state::text FROM shipments WHERE id = ?", String.class, generic))
            .isEqualTo("cancelled");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM shipment_status_history WHERE shipment_id = ? " +
            "AND internal_state = 'cancelled'", Integer.class, generic)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM shipments WHERE order_id = ? AND provider = 'bosta' " +
            "AND tracking_number = '9811000001' AND shipment_leg = 'forward'", Integer.class, o)).isEqualTo(1);
        assertThat(badges(s).get("BW-1")).isEqualTo("linked|Bosta");
    }

    @Test
    void bw2_genericPastCreated_noLink_highException_neverGuesses() {
        Shop s = shop("bw2", true, true);
        UUID o = order(s, "BW-2", 0);
        captureAs(s, o, payload(f(91, "success", "Wijha", "WJ-91", null, "2026-10-10T10:00:00Z")));
        jdbc.update("UPDATE orders SET status = 'packed' WHERE id = ?", o);   // something is packed
        bostaTracking(s, o, "9811000002");
        bostaDelivery(s, "9811000002", "BW-2");

        // Fulfillment-link path: conflict, no Bosta call, fulfillment_link_problem (HIGH).
        BostaFulfillmentLinkService.Result r = fulfillmentLink.attempt(s.tenantId(), o, "9811000002", false);
        assertThat(r.verdict()).isEqualTo(BostaFulfillmentLinkService.Verdict.SKIP);
        assertThat(r.reason()).contains("already shipped with Wijha");
        verify(bostaGateway, never()).fetchDelivery(anyString(), anyString());
        assertThat(alerts(s, "fulfillment_link_problem")).singleElement()
            .satisfies(e -> assertThat(e.get("severity")).isEqualTo("HIGH"));

        // Webhook / discovery path: unlinked with GENERIC_CONFLICT; nothing written.
        BostaDelivery d = BostaDelivery.fromRaw("9811000002", raw("9811000002", "BW-2"));
        ShipmentLinkService.LinkResult lr = TenantContext.runAs(s.tenantId(), () -> tx.execute(x ->
            linkService.linkDeliveryToOrder(s.tenantId(), o, "9811000002", d, stateMapper.map(24, "SEND"))));
        assertThat(lr.orderId()).isNull();
        assertThat(lr.unlinkedReason()).isEqualTo(ShipmentLinkService.REASON_GENERIC_CONFLICT);
        assertThat(jdbc.queryForObject("SELECT internal_state::text FROM shipments WHERE order_id = ? AND provider = 'generic'",
            String.class, o)).isEqualTo("created");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM shipments WHERE order_id = ? AND provider = 'bosta'",
            Integer.class, o)).isZero();

        Long unlinked = jdbc.queryForObject("INSERT INTO unlinked_bosta_deliveries (tenant_id, tracking_number, " +
            "business_reference, bosta_state_code, bosta_order_type, match_reason, raw) " +
            "VALUES (?, '9811000002', 'BW-2', 24, 'SEND', 'GENERIC_CONFLICT', ?::jsonb) RETURNING id", Long.class,
            s.tenantId(), raw("9811000002", "BW-2").toString());
        assertThat(alerts(s, "generic_bosta_conflict")).singleElement()
            .satisfies(e -> assertThat(e.get("severity")).isEqualTo("HIGH"));
        assertThat(alerts(s, "unmatched_delivery")).as("not double-reported as unmatched").isEmpty();

        // Manual link refuses too.
        assertThatThrownBy(() -> TenantContext.runAs(s.tenantId(), () -> linkService.manualLink(unlinked, o, null)))
            .isInstanceOf(ResponseStatusException.class).hasMessageContaining("another carrier");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM shipments WHERE order_id = ? AND provider = 'bosta'",
            Integer.class, o)).isZero();
    }

    // ── cancellation, multiple parcels, pre-connect ─────────────────────────────────────────────

    @Test
    void can1_cancelledFulfillment_cancelsCreatedGeneric() {
        Shop s = shop("can1", false, true);
        UUID o = order(s, "C-1", 0);
        captureAs(s, o, payload(f(101, "success", "Mylerz", "MZ-101", null, "2026-10-10T10:00:00Z")));
        captureAs(s, o, payload(f(101, "cancelled", "Mylerz", "MZ-101", null, "2026-10-10T10:00:00Z")));
        assertThat(jdbc.queryForObject("SELECT internal_state::text FROM shipments WHERE order_id = ?", String.class, o))
            .isEqualTo("cancelled");
        assertThat(alerts(s, "generic_fulfillment_cancelled")).isEmpty();
        assertThat(pickable(s)).isEmpty();
    }

    @Test
    void can2_cancelledFulfillment_afterPacking_keepsShipment_raisesException() {
        Shop s = shop("can2", false, true);
        UUID o = order(s, "C-2", 0);
        captureAs(s, o, payload(f(102, "success", "Mylerz", "MZ-102", null, "2026-10-10T10:00:00Z")));
        jdbc.update("UPDATE orders SET status = 'packed' WHERE id = ?", o);
        captureAs(s, o, payload(f(102, "cancelled", "Mylerz", "MZ-102", null, "2026-10-10T10:00:00Z")));
        assertThat(jdbc.queryForObject("SELECT internal_state::text FROM shipments WHERE order_id = ?", String.class, o))
            .isEqualTo("created");
        assertThat(alerts(s, "generic_fulfillment_cancelled")).singleElement()
            .satisfies(e -> assertThat(e.get("severity")).isEqualTo("MEDIUM"));
    }

    @Test
    void multi1_extraParcel_recordsNothing_raisesMultipleParcels() {
        Shop s = shop("multi", false, true);
        UUID o = order(s, "M-1", 0);
        captureAs(s, o, payload(f(111, "success", "Mylerz", "MZ-111", null, "2026-10-10T10:00:00Z")));
        captureAs(s, o, payload(f(111, "success", "Mylerz", "MZ-111", null, "2026-10-10T10:00:00Z"),
                                f(112, "success", "Mylerz", "MZ-112", null, "2026-10-11T10:00:00Z")));
        assertThat(genericCount(s)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT shopify_fulfillment_id FROM shipments WHERE order_id = ?", String.class, o))
            .isEqualTo("111");
        assertThat(alerts(s, "generic_multiple_parcels")).singleElement()
            .satisfies(e -> assertThat(e.get("severity")).isEqualTo("MEDIUM"));
    }

    @Test
    void pre1_preConnectOrder_noGenericRow() {
        Shop s = shop("pre1", false, true);
        UUID o = order(s, "P-1", 0);
        jdbc.update("UPDATE orders SET placed_at = ? WHERE id = ?", Timestamp.from(Instant.parse("2026-09-01T00:00:00Z")), o);
        captureAs(s, o, payload(f(121, "success", "Mylerz", "MZ-121", null, "2026-10-10T10:00:00Z")));
        assertThat(genericCount(s)).isZero();
    }

    // ── stuck alerts, print batches ─────────────────────────────────────────────────────────────

    @Test
    void stuck1_genericNeverStuck_print1_neverInBatch() {
        Shop s = shop("stuck", false, true);
        UUID o = order(s, "S-1", 0);
        captureAs(s, o, payload(f(131, "success", "Mylerz", "MZ-131", null, "2026-10-10T10:00:00Z")));
        jdbc.update("UPDATE shipments SET created_at = now() - interval '30 days' WHERE order_id = ?", o);
        assertThat(alerts(s, "stuck_shipment")).isEmpty();
        assertThat(pickable(s)).containsExactly("S-1");
        assertThat(TenantContext.runAs(s.tenantId(), () -> printBatches.candidates("all", "oldest"))).isEmpty();
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────────────────

    private Shop shop(String tag, boolean bosta, boolean genericOn) {
        UUID tenantId = UUID.randomUUID(), storeId = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name, generic_courier_enabled) VALUES (?, ?, ?)", tenantId, "G1-" + tag, genericOn);
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status, orders_ingest_from) " +
            "VALUES (?, ?, 'shopify', ?, 'connected', ?)", storeId, tenantId,
            "g1-" + tag + "-" + tenantId.toString().substring(0, 8) + ".myshopify.com",
            Timestamp.from(Instant.parse("2026-09-29T10:00:00Z")));
        if (bosta) {
            jdbc.update("INSERT INTO courier_accounts (tenant_id, provider, api_key_encrypted, webhook_secret, status) " +
                "VALUES (?, 'bosta', ?, ?, 'active')", tenantId, encryptionService.encrypt("key-" + tenantId), "wh-" + tenantId);
        }
        UUID productId = UUID.randomUUID(), variantId = UUID.randomUUID();
        jdbc.update("INSERT INTO products (id, tenant_id, store_id, external_id, title, status) VALUES (?, ?, ?, ?, 'P', 'active')",
            productId, tenantId, storeId, "gid://shopify/Product/" + Math.abs(productId.getMostSignificantBits()));
        jdbc.update("INSERT INTO variants (id, tenant_id, product_id, external_id, title, sku) VALUES (?, ?, ?, ?, 'V', ?)",
            variantId, tenantId, productId, "gid://shopify/ProductVariant/" + Math.abs(variantId.getMostSignificantBits()), "SKU-" + tag);
        return new Shop(tenantId, storeId, variantId);
    }

    private UUID order(Shop s, String number, int daysAgo) {
        Instant placed = Instant.now().minus(Duration.ofDays(daysAgo)).minus(Duration.ofHours(1));
        return jdbc.queryForObject("INSERT INTO orders (tenant_id, store_id, external_id, number, status, placed_at) " +
            "VALUES (?, ?, ?, ?, 'new'::order_status, ?) RETURNING id", UUID.class,
            s.tenantId(), s.storeId(), "gid://shopify/Order/" + Math.abs(UUID.randomUUID().getMostSignificantBits()),
            number, Timestamp.from(placed));
    }

    private void item(Shop s, UUID order) {
        jdbc.update("INSERT INTO order_items (id, tenant_id, order_id, variant_id, quantity) VALUES (?, ?, ?, ?, 1)",
            UUID.randomUUID(), s.tenantId(), order, s.variantId());
    }

    /** As the orders/updated webhook does: the raw order is upserted first, then the capture runs. */
    private void captureAs(Shop s, UUID order, ObjectNode payload) {
        String gid = jdbc.queryForObject("SELECT external_id FROM orders WHERE id = ?", String.class, order);
        jdbc.update("UPDATE orders SET raw = ?::jsonb WHERE id = ?", payload.toString(), order);
        TenantContext.runAs(s.tenantId(), () -> tx.execute(x -> capture.capture(s.storeId(), gid, payload)));
    }

    private ObjectNode f(long id, String status, String company, String tn, String url, String createdAt) {
        ObjectNode n = mapper.createObjectNode();
        n.put("id", id);
        n.put("status", status);
        n.put("created_at", createdAt);
        n.put("shipment_status", "confirmed");
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

    private void bostaTracking(Shop s, UUID order, String tn) {
        jdbc.update("INSERT INTO order_fulfillment_tracking (tenant_id, order_id, tracking_number, carrier_raw, " +
            "carrier_class, shopify_fulfillment_id, fulfillment_status) VALUES (?, ?, ?, 'Bosta', 'bosta', '999', 'success')",
            s.tenantId(), order, tn);
    }

    private void bostaDelivery(Shop s, String tn, String ref) {
        when(bostaGateway.fetchDelivery(eq("key-" + s.tenantId()), eq(tn)))
            .thenReturn(BostaDelivery.fromRaw(tn, raw(tn, ref)));
    }

    private ObjectNode raw(String tn, String ref) {
        ObjectNode raw = mapper.createObjectNode();
        raw.put("_id", "bosta-" + tn);
        raw.put("trackingNumber", tn);
        raw.put("businessReference", ref);
        raw.put("createdAt", "2026-10-10T10:00:00.000Z");
        raw.put("updatedAt", "2026-10-10T11:00:00.000Z");
        raw.putObject("type").put("code", 10).put("value", "Send");
        raw.putObject("state").put("code", 24).put("value", "x");
        return raw;
    }

    private int genericCount(Shop s) {
        return jdbc.queryForObject("SELECT count(*) FROM shipments WHERE tenant_id = ? AND provider = 'generic'",
            Integer.class, s.tenantId());
    }

    /** order number → "state|carrier" from the list endpoint (null badge → "none"). */
    private Map<String, String> badges(Shop s) {
        Map<String, String> m = new TreeMap<>();
        TenantContext.runAs(s.tenantId(), () -> controller.list(null, null, null, null, 0, 100).items())
            .forEach(o -> m.put(o.number(), o.shippingBadge() == null ? "none"
                : o.shippingBadge().state() + "|" + o.shippingBadge().carrier()));
        return m;
    }

    private Map<String, String> statuses(Shop s) {
        Map<String, String> m = new TreeMap<>();
        TenantContext.runAs(s.tenantId(), () -> controller.list(null, null, null, null, 0, 100).items())
            .forEach(o -> m.put(o.number(), o.derivedStatus().primaryKey()));
        return m;
    }

    private OrderShippingBadge detailBadge(Shop s, UUID order) {
        return TenantContext.runAs(s.tenantId(), () -> controller.detail(order).shippingBadge());
    }

    private List<String> pickable(Shop s) {
        return TenantContext.runAs(s.tenantId(), () -> fulfillService.getQueue()).stream()
            .map(r -> (String) r.get("number")).sorted().toList();
    }

    private List<Map<String, Object>> alerts(Shop s, String type) {
        return TenantContext.runAs(s.tenantId(), () -> exceptions.detectAllOpen()).stream()
            .filter(e -> type.equals(e.get("type"))).toList();
    }
}
