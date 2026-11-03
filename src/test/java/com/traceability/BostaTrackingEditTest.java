package com.traceability;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.traceability.integrations.bosta.*;
import com.traceability.integrations.shopify.FulfillmentTrackingCapture;
import com.traceability.inventory.ExceptionService;
import com.traceability.inventory.FulfillService;
import com.traceability.inventory.OrderLineFreeze;
import com.traceability.inventory.WaybillResolver;
import com.traceability.security.EncryptionService;
import com.traceability.tenancy.TenantContext;
import org.jobrunr.scheduling.JobScheduler;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Shopify tracking-number edits, S3 — Bosta legs (V202). Shopify payloads go through the real capture, links
 * through the real BostaFulfillmentLinkService → BostaWebhookJob pipeline; Bosta is mocked (fetch / v2 search).
 * Realistic bare numeric Bosta AWBs.
 *
 *   b1  anchored edit, unpacked: the old leg is replaced (cancelled, NO history row, superseded_*), the new delivery
 *       linked; the order picks + completes; the new waybill opens in waybill mode, the old one says "replaced"
 *   b2  packed → replaced + MEDIUM relabel
 *   b3  frozen (old leg picked up) → refused HIGH, no Bosta call for the new number, nothing replaced
 *   b4  in an open pickup session → refused HIGH
 *   b5  strict cancel-and-refulfill → replaced (cancel_refulfill)
 *   b6  D2, unanchored leg (linked by discovery): old delivery still live → refused MEDIUM "Shopify shows a different
 *       waybill", retried; once Bosta shows the old delivery Canceled (49) → replaced, the alert gone
 *   b6c D2 cadence: every 30 min for 2 h, then every 6 h, gave_up at 48 h — the MEDIUM alert stays (never the HIGH link
 *       problem while the old leg is live) until the old delivery ends
 *   b6d D2 re-checks wait for Bosta headroom (lowest priority): no call, back in 5 min
 *   b7  Bosta 404 on the new number → old leg untouched, 'retry' (as before V202)
 *   b8  golden: the prod 'skipped' shape (a second Bosta parcel, the leg's number still live on Shopify) stays
 *       'skipped', no Bosta call, no decision — byte-identical to the pre-V202 outcome
 *   b9  the new waybill's unlinked_bosta_deliveries row is resolved when it links
 *   f1  FENCE, webhook: Bosta's webhook for the OLD waybill after the switch → raw / state only, no history row, no
 *       piece move, no error; HIGH superseded_waybill_moving; Bosta 49 on it → alert gone, watch over
 *   f2  FENCE, status poll: the poll's per-leg check of the replaced leg → same
 *   p1  every shipments writer that sets a state is safe on a replaced leg (the V202 CHECK is a hard backstop): a
 *       pickup scan of the OLD waybill is refused (LEG_ENDED); a replaced leg already in a session (the race with the
 *       D4 check) is left out at close — the close succeeds, the new leg goes with the courier
 *   f3  the watch: lowest priority (after the cycle's normal fetches, never on a rate-limited cycle), ends at 14 days,
 *       on a terminal state, on not-found; BostaDeletionService never touches a replaced leg
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BostaTrackingEditTest {

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
    @Autowired BostaFulfillmentLinkService fulfillmentLink;
    @Autowired BostaWebhookJob             webhookJob;
    @Autowired BostaStatusPollJob          poll;
    @Autowired BostaDeletionService        deletion;
    @Autowired BostaStateMapper            stateMapper;
    @Autowired FulfillService              fulfill;
    @Autowired WaybillResolver             resolver;
    @Autowired ExceptionService            exceptions;
    @Autowired EncryptionService           encryption;
    @Autowired com.traceability.inventory.PickupSessionService pickups;
    @Autowired BostaFulfillmentCatchUpService catchUp;
    @MockBean  BostaGateway                bostaGateway;
    @MockBean  BostaV2Client               bostaV2;
    @MockBean  JobScheduler                jobScheduler;

    private TransactionTemplate tx;
    private final Map<String, JsonNode> bosta = new ConcurrentHashMap<>();   // tracking number → Bosta's current raw

    @BeforeAll void setup() { tx = new TransactionTemplate(txm); }

    @BeforeEach
    void stub() {
        reset(bostaGateway, bostaV2, jobScheduler);
        when(bostaGateway.fetchDelivery(anyString(), anyString())).thenAnswer(inv -> {
            JsonNode raw = bosta.get((String) inv.getArgument(1));
            if (raw == null) throw new DeliveryNotFoundException("not found");
            return BostaDelivery.fromRaw(inv.getArgument(1), raw);
        });
        when(bostaV2.searchDeliveriesPage(anyString(), anyInt(), anyInt(), anyString())).thenReturn(List.of());
    }

    @AfterEach void clear() { TenantContext.clear(); }

    final class Shop {
        final PackFixtures f;
        final UUID packer, variant;
        Shop(String name) {
            f = new PackFixtures(jdbc, name);
            jdbc.update("UPDATE stores SET status = 'connected' WHERE id = ?", f.store);
            jdbc.update("INSERT INTO courier_accounts (tenant_id, provider, api_key_encrypted, webhook_secret, status) " +
                "VALUES (?, 'bosta', ?, ?, 'active')", f.tenant, encryption.encrypt("key-" + f.tenant), "wh-" + f.tenant);
            packer = f.user("Mona", "worker");
            variant = f.variant("Dress", "DR-" + name, null);
        }
        UUID order(String number) {
            UUID id = f.orderWith(number, "new", 0, "gid://shopify/Order/" + Math.abs(UUID.randomUUID().getMostSignificantBits()));
            f.item(id, variant, 1);
            return id;
        }
        String number(UUID o) { return jdbc.queryForObject("SELECT number FROM orders WHERE id = ?", String.class, o); }
    }

    // ── b: the switch ────────────────────────────────────────────────────────────────────────────

    @Test
    void b1_anchoredEdit_unpacked_replaced_noHistoryRow_ordersPacksOnTheNewWaybill() {
        Shop s = new Shop("b1");
        UUID o = s.order("#17243");
        UUID old = linkedLeg(s, o, 7329122353521L, "853795364", "2026-10-30T21:00:00+02:00");
        deliveryInBosta(s, o, "8384882226", 10);
        capture(s, o, payload(f(7329122353521L, "success", "2026-11-01T22:03:26+02:00", "8384882226")));

        assertThat(link(s, o, "8384882226").verdict()).isEqualTo(BostaFulfillmentLinkService.Verdict.LINKED);

        Map<String, Object> oldRow = shipment(old);
        UUID now = liveLeg(o);
        assertThat(oldRow.get("internal_state")).isEqualTo("cancelled");
        assertThat(oldRow.get("superseded_reason")).isEqualTo("shopify_tracking_edit");
        assertThat(oldRow.get("superseded_by_shipment_id")).isEqualTo(now);
        assertThat(historyPastCreated(old)).as("no history row past 'created' on the replaced leg").isZero();
        assertThat(jdbc.queryForObject("SELECT tracking_number FROM shipments WHERE id = ?", String.class, now)).isEqualTo("8384882226");
        assertThat(changes(s)).containsExactly("edit|853795364→8384882226|applied||{}");
        assertThat(alerts(s)).isEmpty();

        TenantContext.set(s.f.tenant);
        assertThat(OrderLineFreeze.isFrozen(jdbc, o, s.f.tenant)).isFalse();
        FulfillService.ScanResult scan = fulfill.scan(o, s.f.piece(s.variant, s.packer), s.packer);
        assertThat(scan.success()).as(scan.code()).isTrue();
        assertThat(fulfill.complete(o, s.packer)).isEqualTo(1);

        UUID o2 = s.order("#17244");
        linkedLeg(s, o2, 7329122353522L, "853795365", "2026-10-30T21:00:00+02:00");
        deliveryInBosta(s, o2, "8384882227", 10);
        capture(s, o2, payload(f(7329122353522L, "success", "2026-11-01T22:03:26+02:00", "8384882227")));
        link(s, o2, "8384882227");
        jdbc.update("UPDATE tenants SET pick_pack_mode = 'waybill_scan' WHERE id = ?", s.f.tenant);
        TenantContext.set(s.f.tenant);
        assertThat(resolver.resolve("8384882227", s.packer).code()).isEqualTo(WaybillResolver.Code.OPEN);
        WaybillResolver.Resolution oldScan = resolver.resolve("853795365", s.packer);
        assertThat(oldScan.detail()).isEqualTo(WaybillResolver.NotPackable.REPLACED_IN_SHOPIFY.name());
        assertThat(oldScan.messageEn()).contains("8384882227");
    }

    @Test
    void b2_packed_replaced_relabelAlert() {
        Shop s = new Shop("b2");
        UUID o = s.order("#17300");
        linkedLeg(s, o, 7329122353530L, "4491317452", "2026-10-31T12:00:00+02:00");
        pack(s, o);
        deliveryInBosta(s, o, "2036246050", 10);
        capture(s, o, payload(f(7329122353530L, "success", "2026-10-31T18:49:41+02:00", "2036246050")));
        assertThat(link(s, o, "2036246050").verdict()).isEqualTo(BostaFulfillmentLinkService.Verdict.LINKED);
        assertThat(changes(s)).containsExactly("edit|4491317452→2036246050|applied||{packed}");
        assertThat(alerts(s)).containsExactly("tracking_changed_relabel|MEDIUM|#17300");
    }

    @Test
    void b3_frozen_refusedHigh_noBostaCallForTheNewNumber() {
        Shop s = new Shop("b3");
        UUID o = s.order("#17301");
        UUID old = linkedLeg(s, o, 7329122353531L, "9730639058", "2026-10-31T12:00:00+02:00");
        jdbc.update("INSERT INTO shipment_status_history (tenant_id, shipment_id, internal_state, provider_state) " +
            "VALUES (?, ?, 'with_courier', 21)", s.f.tenant, old);
        deliveryInBosta(s, o, "9730639099", 10);
        capture(s, o, payload(f(7329122353531L, "success", "2026-10-31T18:00:00+02:00", "9730639099")));
        assertThat(link(s, o, "9730639099").verdict()).isEqualTo(BostaFulfillmentLinkService.Verdict.SKIP);
        verify(bostaGateway, never()).fetchDelivery(anyString(), eq("9730639099"));
        assertThat(liveLeg(o)).isEqualTo(old);
        assertThat(changes(s)).containsExactly("edit|9730639058→9730639099|refused|frozen|{}");
        assertThat(alerts(s)).containsExactly("tracking_changed_after_custody|HIGH|#17301");
        assertThat(linkStatus(o, "9730639099")).isEqualTo("skipped");
    }

    @Test
    void b4_openPickupSession_refusedHigh() {
        Shop s = new Shop("b4");
        UUID o = s.order("#17302");
        UUID old = linkedLeg(s, o, 7329122353532L, "5512340987", "2026-10-31T12:00:00+02:00");
        UUID pickup = jdbc.queryForObject("INSERT INTO pickups (tenant_id, session_status) VALUES (?, 'open') RETURNING id",
            UUID.class, s.f.tenant);
        jdbc.update("INSERT INTO pickup_shipments (pickup_id, shipment_id, tenant_id) VALUES (?, ?, ?)", pickup, old, s.f.tenant);
        deliveryInBosta(s, o, "5512340999", 10);
        capture(s, o, payload(f(7329122353532L, "success", "2026-10-31T18:00:00+02:00", "5512340999")));
        link(s, o, "5512340999");
        assertThat(liveLeg(o)).isEqualTo(old);
        assertThat(changes(s)).containsExactly("edit|5512340987→5512340999|refused|pickup_session|{}");
        assertThat(alerts(s)).containsExactly("tracking_changed_after_custody|HIGH|#17302");
    }

    @Test
    void b5_strictCancelAndRefulfill_replaced() {
        Shop s = new Shop("b5");
        UUID o = s.order("#17303");
        UUID old = linkedLeg(s, o, 7329122353533L, "6941206863", "2026-10-29T12:00:00+02:00");
        deliveryInBosta(s, o, "4266118553", 10);
        capture(s, o, payload(f(7329122353533L, "cancelled", "2026-10-30T10:00:00+02:00", "6941206863"),
                              f(7331107176833L, "success", "2026-10-30T10:05:00+02:00", "4266118553")));
        assertThat(link(s, o, "4266118553").verdict()).isEqualTo(BostaFulfillmentLinkService.Verdict.LINKED);
        assertThat(shipment(old).get("superseded_at")).isNotNull();
        assertThat(changes(s)).containsExactly("cancel_refulfill|6941206863→4266118553|applied||{}");
    }

    @Test
    void b6_d2_unanchored_oldStillLive_refusedMedium_thenCanceledInBosta_replaced() {
        Shop s = new Shop("b6");
        UUID o = s.order("#46639");
        // Linked by discovery / a pack scan: no fulfillment carried the old number.
        UUID old = bostaLeg(s, o, "4491317460");
        deliveryInBosta(s, o, "4491317460", 10);       // the old delivery is still live in Bosta
        deliveryInBosta(s, o, "2036246060", 10);
        capture(s, o, payload(f(7317931000087L, "success", "2026-10-31T18:49:41+02:00", "2036246060")));

        assertThat(link(s, o, "2036246060").verdict()).isEqualTo(BostaFulfillmentLinkService.Verdict.SKIP);
        assertThat(liveLeg(o)).isEqualTo(old);
        assertThat(changes(s)).containsExactly("edit|4491317460→2036246060|refused|old_delivery_live|{}");
        assertThat(alerts(s)).containsExactly("tracking_change_needs_review|MEDIUM|#46639");
        assertThat(alertText(s)).contains("Shopify shows a different waybill");
        assertThat(linkStatus(o, "2036246060")).isEqualTo("retry");

        ((ObjectNode) bosta.get("4491317460").path("state")).put("code", 49);   // the merchant cancelled it in Bosta
        assertThat(link(s, o, "2036246060").verdict()).isEqualTo(BostaFulfillmentLinkService.Verdict.LINKED);
        assertThat(shipment(old).get("superseded_at")).isNotNull();
        assertThat(historyPastCreated(old)).isZero();
        assertThat(alerts(s)).isEmpty();
    }

    @Test
    void b6c_d2_cadence_30min_then6h_gaveUpAt48h_alertStays_untilTheOldDeliveryEnds() {
        Shop s = new Shop("b6c");
        UUID o = s.order("#46700");
        UUID old = bostaLeg(s, o, "4491317470");
        deliveryInBosta(s, o, "4491317470", 21);       // the old delivery is live (with the courier) in Bosta
        deliveryInBosta(s, o, "2036246070", 10);
        capture(s, o, payload(f(7317931000187L, "success", "2026-10-31T18:49:41+02:00", "2036246070")));

        link(s, o, "2036246070");
        assertThat(linkStatus(o, "2036246070")).isEqualTo("retry");
        assertThat(minutesToNextRetry(o, "2036246070")).as("first 2 h: every 30 min").isBetween(29L, 31L);

        jdbc.update("UPDATE shipment_tracking_changes SET created_at = now() - INTERVAL '3 hours' WHERE shipment_id = ?", old);
        link(s, o, "2036246070");
        assertThat(minutesToNextRetry(o, "2036246070")).as("after 2 h: every 6 h").isBetween(359L, 361L);

        jdbc.update("UPDATE shipment_tracking_changes SET created_at = now() - INTERVAL '49 hours' WHERE shipment_id = ?", old);
        link(s, o, "2036246070");
        assertThat(linkStatus(o, "2036246070")).as("48 h: gave up").isEqualTo("gave_up");
        assertThat(alerts(s)).as("the MEDIUM alert stays").containsExactly("tracking_change_needs_review|MEDIUM|#46700");
        assertThat(alertTypes(s)).as("never the HIGH link problem while the old leg is live").doesNotContain("fulfillment_link_problem");

        jdbc.update("UPDATE shipments SET internal_state = 'cancelled' WHERE id = ?", old);   // Bosta: the old delivery ended
        assertThat(alerts(s)).as("the old delivery ended").isEmpty();
    }

    @Test
    void b6d_d2_recheck_waitsForBostaHeadroom_noCall() {
        Shop s = new Shop("b6d");
        UUID o = s.order("#46701");
        bostaLeg(s, o, "4491317480");
        deliveryInBosta(s, o, "4491317480", 21);
        deliveryInBosta(s, o, "2036246080", 10);
        capture(s, o, payload(f(7317931000188L, "success", "2026-10-31T18:49:41+02:00", "2036246080")));
        link(s, o, "2036246080");   // first look: refused, retry
        clearInvocations(bostaGateway);
        fulfillmentLink.setD2Headroom(key -> false);
        try {
            assertThat(link(s, o, "2036246080").reason()).isEqualTo("waiting for Bosta headroom");
        } finally {
            fulfillmentLink.setD2Headroom(key -> true);
        }
        verify(bostaGateway, never()).fetchDelivery(anyString(), anyString());
        assertThat(minutesToNextRetry(o, "2036246080")).isBetween(4L, 6L);
    }

    @Test
    void b7_bosta404OnTheNewNumber_oldLegUntouched_retry() {
        Shop s = new Shop("b7");
        UUID o = s.order("#17304");
        UUID old = linkedLeg(s, o, 7329122353534L, "1345710666", "2026-10-31T12:00:00+02:00");
        capture(s, o, payload(f(7329122353534L, "success", "2026-10-31T18:00:00+02:00", "1345710667")));
        assertThat(link(s, o, "1345710667").reason()).isEqualTo("not found in Bosta");
        assertThat(liveLeg(o)).isEqualTo(old);
        assertThat(shipment(old).get("superseded_at")).isNull();
        assertThat(linkStatus(o, "1345710667")).isEqualTo("retry");
    }

    @Test
    void b8_golden_secondBostaParcel_staysSkipped_noCall_noDecision() {
        Shop s = new Shop("b8");
        UUID o = s.order("BRK-46000-EG");
        UUID old = linkedLeg(s, o, 7400000000100L, "4779965049", "2026-10-31T12:00:00+02:00");
        deliveryInBosta(s, o, "4779965050", 10);
        capture(s, o, payload(f(7400000000100L, "success", "2026-10-31T12:00:00+02:00", "4779965049"),
                              f(7400000000101L, "success", "2026-10-31T13:00:00+02:00", "4779965050")));
        BostaFulfillmentLinkService.Result r = link(s, o, "4779965050");
        assertThat(r.verdict()).isEqualTo(BostaFulfillmentLinkService.Verdict.SKIP);
        assertThat(r.reason()).isEqualTo("order already has an active forward leg (4779965049)");
        assertThat(linkStatus(o, "4779965050")).isEqualTo("skipped");
        assertThat(jdbc.queryForObject("SELECT link_reason FROM order_fulfillment_tracking WHERE order_id = ? AND tracking_number = ?",
            String.class, o, "4779965050")).isEqualTo("order already has an active forward leg (4779965049)");
        verify(bostaGateway, never()).fetchDelivery(anyString(), eq("4779965050"));
        assertThat(liveLeg(o)).isEqualTo(old);
        assertThat(changes(s)).isEmpty();
    }

    @Test
    void b9_newWaybillsUnlinkedRow_resolvedOnLink() {
        Shop s = new Shop("b9");
        UUID o = s.order("#17305");
        linkedLeg(s, o, 7329122353535L, "6100000001", "2026-10-31T12:00:00+02:00");
        deliveryInBosta(s, o, "6100000002", 10);
        jdbc.update("INSERT INTO unlinked_bosta_deliveries (tenant_id, tracking_number, business_reference, bosta_state_code, " +
            "bosta_order_type, match_reason, raw) VALUES (?, '6100000002', '#17305', 10, 'SEND', 'NO_MATCH', ?::jsonb)",
            s.f.tenant, bosta.get("6100000002").toString());
        capture(s, o, payload(f(7329122353535L, "success", "2026-10-31T18:00:00+02:00", "6100000002")));
        link(s, o, "6100000002");
        assertThat(jdbc.queryForObject("SELECT resolved FROM unlinked_bosta_deliveries WHERE tracking_number = '6100000002'",
            Boolean.class)).isTrue();
    }

    // ── f: the fence ─────────────────────────────────────────────────────────────────────────────

    @Test
    void f1_fence_oldWaybillWebhookAfterTheSwitch_storedOnly_highAlert_endsOnCancel() {
        Shop s = new Shop("f1");
        UUID o = s.order("#17400");
        UUID old = switched(s, o, 7329122353540L, "7200000001", "7200000002");
        pack(s, o);
        String piecesBefore = pieces(o);

        ((ObjectNode) bosta.get("7200000001").path("state")).put("code", 21);   // Bosta: the old delivery was picked up
        webhook(s, "7200000001", 21);

        assertThat(historyPastCreated(old)).as("no history row").isZero();
        assertThat(pieces(o)).as("no piece moved").isEqualTo(piecesBefore);
        assertThat(shipment(old).get("internal_state")).isEqualTo("cancelled");
        assertThat(shipment(old).get("superseded_last_state")).isEqualTo(stateMapper.map(21, "SEND").shipmentInternalState());
        assertThat(alerts(s)).contains("superseded_waybill_moving|HIGH|#17400");

        ((ObjectNode) bosta.get("7200000001").path("state")).put("code", 49);   // the merchant cancelled it in Bosta
        webhook(s, "7200000001", 49);
        assertThat(alerts(s)).doesNotContain("superseded_waybill_moving|HIGH|#17400");
        assertThat(watched(old)).isFalse();
    }

    @Test
    void f2_fence_statusPollOfTheReplacedLeg_storedOnly_highAlert() {
        Shop s = new Shop("f2");
        UUID o = s.order("#17401");
        UUID old = switched(s, o, 7329122353541L, "7200000011", "7200000012");
        pack(s, o);
        String piecesBefore = pieces(o);
        ((ObjectNode) bosta.get("7200000011").path("state")).put("code", 21);
        jdbc.update("UPDATE shipments SET last_polled_at = now() - INTERVAL '5 hours' WHERE id = ?", old);
        // Only this tenant polls in this test.
        jdbc.update("UPDATE courier_accounts SET status = 'disconnected' WHERE tenant_id <> ?", s.f.tenant);
        clearInvocations(bostaGateway);   // the switch itself fetched it; count only the poll's call

        poll.pollAll();
        processPending(s);

        verify(bostaGateway, atLeastOnce()).fetchDelivery(anyString(), eq("7200000011"));   // the poll, then verify-by-fetch
        assertThat(historyPastCreated(old)).isZero();
        assertThat(pieces(o)).isEqualTo(piecesBefore);
        assertThat(shipment(old).get("superseded_last_state")).isEqualTo(stateMapper.map(21, "SEND").shipmentInternalState());
        assertThat(alerts(s)).contains("superseded_waybill_moving|HIGH|#17401");
    }

    @Test
    void f3_watch_limits_andDeletionServiceNeverTouchesAReplacedLeg() {
        Shop s = new Shop("f3");
        UUID o = s.order("#17402");
        UUID old = switched(s, o, 7329122353542L, "7200000021", "7200000022");
        assertThat(watched(old)).isTrue();
        jdbc.update("UPDATE shipments SET superseded_at = now() - INTERVAL '15 days' WHERE id = ?", old);
        assertThat(watched(old)).as("14 days after the switch").isFalse();
        jdbc.update("UPDATE shipments SET superseded_at = now(), superseded_last_state = 'terminated' WHERE id = ?", old);
        assertThat(watched(old)).as("terminal in Bosta").isFalse();
        jdbc.update("UPDATE shipments SET superseded_last_state = NULL, provider_not_found_at = LOCALTIMESTAMP WHERE id = ?", old);
        assertThat(watched(old)).as("not found in Bosta").isFalse();

        jdbc.update("UPDATE shipments SET provider_not_found_at = NULL, not_found_count = 0 WHERE id = ?", old);
        TenantContext.runAs(s.f.tenant, () -> deletion.observeNotFound(s.f.tenant, old));
        assertThat(shipment(old).get("not_found_count")).isEqualTo(0);
        jdbc.update("UPDATE shipments SET not_found_count = 5 WHERE id = ?", old);
        assertThat(TenantContext.runAs(s.f.tenant, () -> deletion.tryConfirm(s.f.tenant, old)))
            .isEqualTo(BostaDeletionService.Confirmation.NOT_BOSTA);
        assertThat(shipment(old).get("provider_deleted_at")).isNull();

        // Lowest priority: a rate limit on the cycle's normal safety-net fetch → the replaced leg isn't fetched.
        jdbc.update("UPDATE shipments SET not_found_count = 0, last_polled_at = now() - INTERVAL '5 hours' WHERE id = ?", old);
        UUID busy = s.order("#17403");
        UUID normal = bostaLeg(s, busy, "7200000099");
        jdbc.update("UPDATE shipments SET internal_state = 'with_courier', last_polled_at = now() - INTERVAL '5 hours' WHERE id = ?", normal);
        jdbc.update("UPDATE courier_accounts SET status = 'disconnected' WHERE tenant_id <> ?", s.f.tenant);
        reset(bostaGateway);
        when(bostaGateway.fetchDelivery(anyString(), eq("7200000099"))).thenThrow(new BostaRateLimitException(30));
        poll.pollAll();
        verify(bostaGateway, never()).fetchDelivery(anyString(), eq("7200000021"));
    }

    @Test
    void p1_pickup_oldWaybillRefused_replacedLegInASession_leftOutAtClose_noConstraintError() {
        Shop s = new Shop("p1");
        UUID o = s.order("#17500");
        pack(s, o);
        UUID old = switched(s, o, 7329122353550L, "7300000001", "7300000002");
        UUID now = liveLeg(o);
        UUID session = pickups.openSession(s.f.tenant, s.packer, java.time.LocalDate.now(), "10:00 to 13:00", null);

        assertThat(pickups.scan(s.f.tenant, session, s.packer, "7300000001").outcome())
            .isEqualTo(com.traceability.inventory.PickupSessionService.ScanOutcome.LEG_ENDED);
        assertThat(pickups.scan(s.f.tenant, session, s.packer, "7300000002").outcome())
            .isEqualTo(com.traceability.inventory.PickupSessionService.ScanOutcome.ACCEPTED);
        // The race: the old leg was scanned in before the switch.
        jdbc.update("INSERT INTO pickup_shipments (pickup_id, shipment_id, tenant_id) VALUES (?, ?, ?)", session, old, s.f.tenant);

        com.traceability.inventory.PickupSessionService.CloseResult close = pickups.closeSession(s.f.tenant, session, s.packer);

        assertThat(close.shipmentsClosed()).isEqualTo(1);
        assertThat(close.leftOut()).extracting(com.traceability.inventory.PickupSessionService.LeftOut::trackingNumber)
            .containsExactly("7300000001");
        assertThat(shipment(old).get("internal_state")).as("the replaced leg is never handed over").isEqualTo("cancelled");
        assertThat(historyPastCreated(old)).isZero();
        assertThat(jdbc.queryForObject("SELECT internal_state::text FROM shipments WHERE id = ?", String.class, now))
            .isEqualTo("with_courier");
    }

    // ── S4 (a): the order timeline ──────────────────────────────────────────────────────────────

    @Test
    void t1_timeline_waybillChangedInShopify_andNotFollowed_withItsReason() {
        Shop s = new Shop("t1");
        com.traceability.fulfillment.OrderController controller = new com.traceability.fulfillment.OrderController(
            jdbc, mapper, txm, new com.traceability.fulfillment.OrderNotesService(jdbc), fulfill);
        UUID o = s.order("#17600");
        switched(s, o, 7329122353560L, "7400000001", "7400000002");
        UUID frozen = s.order("#17601");
        UUID leg = linkedLeg(s, frozen, 7329122353561L, "7400000011", "2026-10-31T12:00:00+02:00");
        jdbc.update("INSERT INTO shipment_status_history (tenant_id, shipment_id, internal_state, provider_state) " +
            "VALUES (?, ?, 'with_courier', 21)", s.f.tenant, leg);
        deliveryInBosta(s, frozen, "7400000012", 10);
        capture(s, frozen, payload(f(7329122353561L, "success", "2026-10-31T18:00:00+02:00", "7400000012")));
        link(s, frozen, "7400000012");

        List<com.traceability.fulfillment.OrderController.TimelineItem> followed =
            TenantContext.runAs(s.f.tenant, () -> controller.timeline(o));
        assertThat(followed).filteredOn(i -> i.eventKey().startsWith("waybill_change"))
            .singleElement().satisfies(i -> {
                assertThat(i.eventKey()).isEqualTo("waybill_changed_in_shopify");
                assertThat(i.detail()).isEqualTo("7400000001 → 7400000002");
                assertThat(i.reasonKey()).isNull();
            });
        List<com.traceability.fulfillment.OrderController.TimelineItem> refused =
            TenantContext.runAs(s.f.tenant, () -> controller.timeline(frozen));
        assertThat(refused).filteredOn(i -> i.eventKey().startsWith("waybill_change"))
            .singleElement().satisfies(i -> {
                assertThat(i.eventKey()).isEqualTo("waybill_change_not_followed");
                assertThat(i.kind()).isEqualTo("fail");
                assertThat(i.reasonKey()).isEqualTo("frozen");
                assertThat(i.detail()).isEqualTo("7400000011 → 7400000012");
            });
    }

    // ── S4 (b): the per-order catch-up dry run ──────────────────────────────────────────────────

    @Test
    void c1_catchUp_oneOrder_dryRun_wouldReplaceTheDeletedLeg_writesNothing_otherOrderUntouched() {
        Shop s = new Shop("c1");
        // The BRK-46639-EG shape: packed, old leg linked by a pack scan (no fulfillment row), confirmed deleted in
        // Bosta with its state kept; Shopify's number was 'skipped' before S3.
        UUID o = s.order("BRK-46639-EG");
        pack(s, o);
        UUID old = bostaLeg(s, o, "4491317452");
        jdbc.update("UPDATE shipments SET provider_deleted_at = now(), provider_deleted_reason = 'deleted_in_bosta' WHERE id = ?", old);
        capture(s, o, payload(f(7317931000087L, "success", "2026-10-31T18:49:41+02:00", "2036246050")));
        jdbc.update("UPDATE order_fulfillment_tracking SET link_status = 'skipped', " +
            "link_reason = 'order already has an active forward leg (4491317452)' WHERE order_id = ?", o);
        deliveryInBosta(s, o, "2036246050", 10);
        UUID other = s.order("BRK-46640-EG");
        bostaLeg(s, other, "4491317453");
        capture(s, other, payload(f(7317931000088L, "success", "2026-10-31T18:49:41+02:00", "2036246051")));
        clearInvocations(bostaGateway);
        String before = shipmentsAndRows(s);

        List<BostaFulfillmentCatchUpService.Row> rows = catchUp.run(s.f.tenant, false, "BRK-46639-EG");

        assertThat(rows).singleElement().satisfies(r -> {
            assertThat(r.orderNumber()).isEqualTo("BRK-46639-EG");
            assertThat(r.verdict()).isEqualTo("WOULD_LINK");
            assertThat(r.reason()).isEqualTo("replaces 4491317452");
        });
        assertThat(shipmentsAndRows(s)).as("a dry run writes nothing").isEqualTo(before);
        assertThat(changes(s)).isEmpty();
        verify(bostaGateway, never()).fetchDelivery(anyString(), eq("2036246051"));
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────────────────

    private String shipmentsAndRows(Shop s) {
        return String.join("\n", jdbc.queryForList("SELECT x::text FROM shipments x WHERE tenant_id = ? UNION ALL " +
            "SELECT t::text FROM order_fulfillment_tracking t WHERE tenant_id = ? ORDER BY 1", String.class, s.f.tenant, s.f.tenant));
    }

    /** A Bosta leg linked from a Shopify fulfillment (capture + the real link job). */
    private UUID linkedLeg(Shop s, UUID o, long fid, String tn, String updatedAt) {
        deliveryInBosta(s, o, tn, 10);
        capture(s, o, payload(f(fid, "success", updatedAt, tn)));
        assertThat(link(s, o, tn).verdict()).isEqualTo(BostaFulfillmentLinkService.Verdict.LINKED);
        return liveLeg(o);
    }

    /** linkedLeg, then the same fulfillment edited to {@code newTn} and linked: returns the replaced leg. */
    private UUID switched(Shop s, UUID o, long fid, String oldTn, String newTn) {
        UUID old = linkedLeg(s, o, fid, oldTn, "2026-10-31T12:00:00+02:00");
        deliveryInBosta(s, o, newTn, 10);
        capture(s, o, payload(f(fid, "success", "2026-10-31T18:00:00+02:00", newTn)));
        assertThat(link(s, o, newTn).verdict()).isEqualTo(BostaFulfillmentLinkService.Verdict.LINKED);
        assertThat(shipment(old).get("superseded_at")).isNotNull();
        return old;
    }

    /** A Bosta leg linked some other way (discovery / pack scan) — no fulfillment row for its number. */
    private UUID bostaLeg(Shop s, UUID o, String tn) {
        return jdbc.queryForObject("INSERT INTO shipments (tenant_id, order_id, provider, tracking_number, internal_state) " +
            "VALUES (?, ?, 'bosta', ?, 'created') RETURNING id", UUID.class, s.f.tenant, o, tn);
    }

    private void deliveryInBosta(Shop s, UUID o, String tn, int state) {
        ObjectNode raw = mapper.createObjectNode();
        raw.put("_id", "bosta-" + tn);
        raw.put("trackingNumber", tn);
        raw.put("businessReference", s.number(o));
        raw.put("createdAt", "2026-10-30T10:00:00.000Z");
        raw.put("updatedAt", Instant.now().toString());
        raw.putObject("type").put("code", 10).put("value", "Send");
        raw.putObject("state").put("code", state).put("value", "x");
        bosta.put(tn, raw);
    }

    private BostaFulfillmentLinkService.Result link(Shop s, UUID o, String tn) {
        return fulfillmentLink.attempt(s.f.tenant, o, tn, false);
    }

    private void capture(Shop s, UUID o, ObjectNode payload) {
        jdbc.update("UPDATE orders SET raw = ?::jsonb WHERE id = ?", payload.toString(), o);
        String gid = jdbc.queryForObject("SELECT external_id FROM orders WHERE id = ?", String.class, o);
        TenantContext.runAs(s.f.tenant, () -> tx.execute(x -> capture.capture(s.f.store, gid, payload)));
    }

    private ObjectNode f(long id, String status, String updatedAt, String... numbers) {
        ObjectNode n = mapper.createObjectNode();
        n.put("id", id);
        n.put("status", status);
        n.put("created_at", "2026-10-29T09:00:00+02:00");
        n.put("updated_at", updatedAt);
        n.put("tracking_company", "Bosta");
        if (numbers.length > 0) n.put("tracking_number", numbers[0]);
        ArrayNode arr = n.putArray("tracking_numbers");
        for (String x : numbers) arr.add(x);
        return n;
    }

    private ObjectNode payload(ObjectNode... fulfillments) {
        ObjectNode p = mapper.createObjectNode();
        ArrayNode arr = p.putArray("fulfillments");
        for (ObjectNode n : fulfillments) arr.add(n);
        return p;
    }

    /** A real Bosta webhook for {@code tn}: a 'bosta' webhook_events row, processed (verify-by-fetch). */
    private void webhook(Shop s, String tn, int state) {
        ObjectNode payload = mapper.createObjectNode();
        payload.put("trackingNumber", tn);
        payload.put("state", state);
        payload.put("type", "SEND");
        payload.put("updatedAt", Instant.now().toString() + "-" + UUID.randomUUID());
        Long id = jdbc.queryForObject("INSERT INTO webhook_events (source, tenant_id, topic, payload, status, received_at) " +
            "VALUES ('bosta'::webhook_source, ?, 'delivery_update', ?::jsonb, 'pending', now()) RETURNING id",
            Long.class, s.f.tenant, payload.toString());
        webhookJob.process(id, s.f.tenant);
        assertThat(jdbc.queryForObject("SELECT status::text FROM webhook_events WHERE id = ?", String.class, id))
            .as("the event is processed, not failed").isEqualTo("processed");
    }

    /** The JobRunr side of the ingest (mocked here): run every pending event of the tenant. */
    private void processPending(Shop s) {
        for (Long id : jdbc.queryForList("SELECT id FROM webhook_events WHERE tenant_id = ? AND status = 'pending' ORDER BY id",
                Long.class, s.f.tenant)) {
            webhookJob.process(id, s.f.tenant);
            assertThat(jdbc.queryForObject("SELECT status::text FROM webhook_events WHERE id = ?", String.class, id))
                .as("the polled event is processed, not failed").isEqualTo("processed");
        }
    }

    private void pack(Shop s, UUID o) {
        TenantContext.set(s.f.tenant);
        assertThat(fulfill.scan(o, s.f.piece(s.variant, s.packer), s.packer).success()).isTrue();
        fulfill.complete(o, s.packer);
        TenantContext.clear();
    }

    private UUID liveLeg(UUID o) {
        return jdbc.queryForObject("SELECT id FROM shipments WHERE order_id = ? AND shipment_leg = 'forward' " +
            "AND internal_state NOT IN ('cancelled', 'terminated')", UUID.class, o);
    }

    private Map<String, Object> shipment(UUID id) {
        return jdbc.queryForMap("SELECT internal_state::text AS internal_state, superseded_at, superseded_reason, " +
            "superseded_by_shipment_id, superseded_last_state, not_found_count, provider_deleted_at FROM shipments WHERE id = ?", id);
    }

    private int historyPastCreated(UUID shipment) {
        return jdbc.queryForObject("SELECT count(*) FROM shipment_status_history WHERE shipment_id = ? AND internal_state <> 'created'",
            Integer.class, shipment);
    }

    private String pieces(UUID o) {
        return String.join(",", jdbc.queryForList("SELECT p.id || ':' || p.status FROM pieces p JOIN allocations a ON a.piece_id = p.id " +
            "JOIN order_items oi ON oi.id = a.order_item_id WHERE oi.order_id = ? ORDER BY p.id", String.class, o));
    }

    private boolean watched(UUID id) {
        return jdbc.queryForObject("SELECT " + BostaSupersede.watchedSql("s") + " FROM shipments s WHERE s.id = ?", Boolean.class, id);
    }

    private String linkStatus(UUID o, String tn) {
        return jdbc.queryForObject("SELECT link_status FROM order_fulfillment_tracking WHERE order_id = ? AND tracking_number = ?",
            String.class, o, tn);
    }

    private List<String> changes(Shop s) {
        return jdbc.queryForList("SELECT kind || '|' || old_tracking_number || '→' || coalesce(new_tracking_number, 'null') || '|' || " +
            "outcome || '|' || coalesce(reason, '') || '|' || relabel::text FROM shipment_tracking_changes WHERE tenant_id = ? " +
            "ORDER BY created_at, id", String.class, s.f.tenant);
    }

    private List<String> alerts(Shop s) {
        return TenantContext.runAs(s.f.tenant, () -> exceptions.detectAllOpen()).stream()
            .filter(e -> ((String) e.get("type")).startsWith("tracking_change") || "superseded_waybill_moving".equals(e.get("type")))
            .map(e -> e.get("type") + "|" + e.get("severity") + "|" + e.get("order_number")).toList();
    }

    private long minutesToNextRetry(UUID o, String tn) {
        return jdbc.queryForObject("SELECT round(extract(epoch FROM link_next_retry_at - now()) / 60)::bigint " +
            "FROM order_fulfillment_tracking WHERE order_id = ? AND tracking_number = ?", Long.class, o, tn);
    }

    private List<String> alertTypes(Shop s) {
        return TenantContext.runAs(s.f.tenant, () -> exceptions.detectAllOpen()).stream().map(e -> (String) e.get("type")).toList();
    }

    private String alertText(Shop s) {
        return TenantContext.runAs(s.f.tenant, () -> exceptions.detectAllOpen()).stream()
            .filter(e -> ((String) e.get("type")).startsWith("tracking_change"))
            .map(e -> (String) e.get("descriptionEn")).findFirst().orElse("");
    }
}
