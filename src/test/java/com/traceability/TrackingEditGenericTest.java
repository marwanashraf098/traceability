package com.traceability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.traceability.integrations.bosta.BostaFulfillmentLinkService;
import com.traceability.integrations.bosta.BostaGateway;
import com.traceability.integrations.shopify.FulfillmentTrackingCapture;
import com.traceability.inventory.ExceptionService;
import com.traceability.inventory.FulfillService;
import com.traceability.inventory.WaybillResolver;
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

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Shopify tracking-number edits, S1 (tracking rows) + S2 (generic legs follow the edit) — V200. Payloads go through
 * the real FulfillmentTrackingCapture, as orders/updated does.
 *
 *   t*  tracking rows: an edit marks the old number removed (never deleted); a stale payload never revives it; a
 *       number that comes back is live again; a cancelled fulfillment's rows aren't "removed"; golden — unedited
 *       payloads (one parcel, an appended second parcel, a cancel with no refulfill) replayed write identical rows
 *       and record no change; the false generic_multiple_parcels after an edit is gone, a real second parcel still alerts
 *   g*  generic legs: edit unpacked → in place, silently; packed → applied + MEDIUM relabel (clears when it leaves
 *       custody); frozen → refused HIGH (clears when Shopify shows the leg's number again); open pickup session →
 *       refused HIGH; packed cancel-and-refulfill (strict) → in place onto the new fulfillment; unpacked
 *       cancel-and-refulfill → unchanged pre-V200 behaviour (cancel + new leg); looser shapes → refused MEDIUM;
 *       new number on another order → refused MEDIUM; cosmetic → text only; blanked → recorded only; out-of-order
 *       payloads never revert; a carrier change to Bosta and a merchant-entered leg are never followed
 *   rls the edit run as app_user under RLS: the decision row is written, another tenant sees nothing, app_user
 *       can't UPDATE / DELETE the record
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TrackingEditGenericTest {

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
    }

    @Autowired JdbcTemplate                jdbc;
    @Autowired ObjectMapper                mapper;
    @Autowired PlatformTransactionManager  txm;
    @Autowired FulfillmentTrackingCapture  capture;
    @Autowired BostaFulfillmentLinkService linkService;
    @Autowired FulfillService              fulfill;
    @Autowired WaybillResolver             resolver;
    @Autowired ExceptionService            exceptions;
    @MockBean  BostaGateway                bostaGateway;
    @MockBean  JobScheduler                jobScheduler;

    private TransactionTemplate tx;

    @BeforeAll void setup() { tx = new TransactionTemplate(txm); }
    @AfterEach void clear() { TenantContext.clear(); }

    final class Shop {
        final PackFixtures f;
        final UUID packer, variant;
        Shop(String name) {
            f = new PackFixtures(jdbc, name);
            jdbc.update("UPDATE tenants SET generic_courier_enabled = true, pick_pack_mode = 'waybill_scan' WHERE id = ?", f.tenant);
            packer = f.user("Mona", "worker");
            variant = f.variant("Abaya", "AB-" + name, null);
        }
        UUID order(String number) {
            UUID id = f.orderWith(number, "new", 0, "gid://shopify/Order/" + Math.abs(UUID.randomUUID().getMostSignificantBits()));
            f.item(id, variant, 1);
            return id;
        }
    }

    // ── t: tracking rows ─────────────────────────────────────────────────────────────────────────

    @Test
    void t1_edit_marksOldRemoved_keepsIt_newRowLive() {
        Shop s = new Shop("t1");
        UUID o = s.order("#37108");
        captureAs(s, o, payload(f(7306270474458L, "success", "Aramex", "2026-10-26T10:00:00+03:00", "ARV3626150")));
        captureAs(s, o, payload(f(7306270474458L, "success", "Aramex", "2026-10-26T22:41:14+03:00", "ARV3626181")));
        assertThat(rows(o)).containsExactlyInAnyOrder("ARV3626150|removed|success", "ARV3626181|live|success");
    }

    @Test
    void t2_stalePayload_neverRevivesOrRewrites() {
        Shop s = new Shop("t2");
        UUID o = s.order("#37171");
        captureAs(s, o, payload(f(7306270474459L, "success", "Aramex", "2026-10-26T10:00:00+03:00", "ARV3626151")));
        captureAs(s, o, payload(f(7306270474459L, "success", "Aramex", "2026-10-26T22:00:00+03:00", "ARV3626185")));
        String before = String.join(";", rowsFull(o));
        captureAs(s, o, payload(f(7306270474459L, "success", "Aramex", "2026-10-26T10:00:00+03:00", "ARV3626151")));
        assertThat(rows(o)).containsExactlyInAnyOrder("ARV3626151|removed|success", "ARV3626185|live|success");
        assertThat(String.join(";", rowsFull(o)).replaceAll("\\|seen=[^;]*", ""))
            .isEqualTo(before.replaceAll("\\|seen=[^;]*", ""));
    }

    @Test
    void t3_numberComesBack_liveAgain() {
        Shop s = new Shop("t3");
        UUID o = s.order("#37192");
        captureAs(s, o, payload(f(7306270474460L, "success", "Wijha", "2026-10-26T10:00:00+03:00", "WJ-12345")));
        captureAs(s, o, payload(f(7306270474460L, "success", "Wijha", "2026-10-26T11:00:00+03:00", "WJ-99999")));
        captureAs(s, o, payload(f(7306270474460L, "success", "Wijha", "2026-10-26T12:00:00+03:00", "WJ-12345")));
        assertThat(rows(o)).containsExactlyInAnyOrder("WJ-12345|live|success", "WJ-99999|removed|success");
        assertThat(leg(o).get("tracking_number")).isEqualTo("WJ-12345");
    }

    @Test
    void t4_cancelledFulfillment_rowsCancelled_notRemoved() {
        Shop s = new Shop("t4");
        UUID o = s.order("#37200");
        captureAs(s, o, payload(f(7306270474461L, "success", "Aramex", "2026-10-26T10:00:00+03:00", "ARV3626152")));
        captureAs(s, o, payload(f(7306270474461L, "cancelled", "Aramex", "2026-10-26T11:00:00+03:00", "ARV3626152")));
        assertThat(rows(o)).containsExactly("ARV3626152|live|cancelled");
    }

    @Test
    void t5_golden_uneditedPayloads_identicalRows_noChangeRecorded() {
        Shop s = new Shop("t5");
        UUID one = s.order("#37233"), two = s.order("#37344"), gone = s.order("#37470");
        ObjectNode p1 = payload(f(7306270474462L, "success", "Aramex", "2026-10-26T10:00:00+03:00", "ARV3626153"));
        // Maron shape: a number appended to the same fulfillment — the first number stays.
        ObjectNode p2a = payload(f(7306270474463L, "success", "Aramex", "2026-10-26T10:00:00+03:00", "ARV3626154"));
        ObjectNode p2b = payload(f(7306270474463L, "success", "Aramex", "2026-10-26T11:00:00+03:00", "ARV3626154", "ARV3626155"));
        ObjectNode p3a = payload(f(7306270474464L, "success", "Wijha", "2026-10-26T10:00:00+03:00", "WJ-40211"));
        ObjectNode p3b = payload(f(7306270474464L, "cancelled", "Wijha", "2026-10-26T11:00:00+03:00", "WJ-40211"));
        captureAs(s, one, p1); captureAs(s, two, p2a); captureAs(s, two, p2b); captureAs(s, gone, p3a); captureAs(s, gone, p3b);
        String shipmentsBefore = shipmentsText(s), rowsBefore = String.join(";", rowsFull(one) ) + rowsFull(two) + rowsFull(gone);
        for (int i = 0; i < 3; i++) { captureAs(s, one, p1); captureAs(s, two, p2b); captureAs(s, gone, p3b); }

        assertThat(shipmentsText(s)).as("shipments unchanged by replays").isEqualTo(shipmentsBefore);
        assertThat((String.join(";", rowsFull(one)) + rowsFull(two) + rowsFull(gone)).replaceAll("\\|seen=[^;,\\]]*", ""))
            .isEqualTo(rowsBefore.replaceAll("\\|seen=[^;,\\]]*", ""));
        assertThat(rows(two)).containsExactlyInAnyOrder("ARV3626154|live|success", "ARV3626155|live|success");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM order_fulfillment_tracking WHERE tenant_id = ? AND removed_at IS NOT NULL",
            Integer.class, s.f.tenant)).isZero();
        assertThat(changes(s)).isEmpty();
        assertThat(leg(two).get("tracking_number")).isEqualTo("ARV3626154");
    }

    @Test
    void t6_editNoLongerRaisesMultipleParcels_realSecondParcelStillDoes() {
        Shop s = new Shop("t6");
        UUID edited = s.order("#37493"), split = s.order("#37494");
        captureAs(s, edited, payload(f(7306270474465L, "success", "Aramex", "2026-10-26T10:00:00+03:00", "ARV3626156")));
        captureAs(s, edited, payload(f(7306270474465L, "success", "Aramex", "2026-10-26T11:00:00+03:00", "ARV3626310")));
        captureAs(s, split, payload(f(7306270474466L, "success", "Aramex", "2026-10-26T10:00:00+03:00", "ARV3626157")));
        captureAs(s, split, payload(f(7306270474466L, "success", "Aramex", "2026-10-26T10:00:00+03:00", "ARV3626157"),
                                    f(7306270474467L, "success", "Aramex", "2026-10-26T12:00:00+03:00", "ARV3626293")));
        assertThat(alertOrders(s, "generic_multiple_parcels")).containsExactly("#37494");
    }

    // ── g: generic legs ──────────────────────────────────────────────────────────────────────────

    @Test
    void g1_editUnpacked_inPlace_silently_newWaybillOpens() {
        Shop s = new Shop("g1");
        UUID o = s.order("#37543");
        captureAs(s, o, payload(f(7317931000087L, "success", "Aramex", "2026-10-31T12:00:00+02:00", "ARV3626277")));
        UUID legId = (UUID) leg(o).get("id");
        captureAs(s, o, payload(f(7317931000087L, "success", "Aramex", "2026-10-31T18:49:41+02:00", "arv 3626537")));

        Map<String, Object> l = leg(o);
        assertThat(l.get("id")).as("same leg, in place").isEqualTo(legId);
        assertThat(l.get("tracking_number")).isEqualTo("arv3626537");
        assertThat(l.get("tracking_key")).isEqualTo("ARV3626537");
        assertThat(l.get("tracking_changed_at")).isNotNull();
        assertThat(changes(s)).containsExactly("edit|ARV3626277→arv3626537|applied||{}");
        assertThat(trackingAlerts(s)).isEmpty();
        TenantContext.set(s.f.tenant);
        assertThat(resolver.resolve("ARV3626537", s.packer).code()).isEqualTo(WaybillResolver.Code.OPEN);
        assertThat(resolver.resolve("ARV3626277", s.packer).orderId()).as("the old number no longer opens it").isNull();
    }

    @Test
    void g2_editPacked_applied_relabelAlert_clearsOnceItLeaves() {
        Shop s = new Shop("g2");
        UUID o = s.order("#37563");
        captureAs(s, o, payload(f(7317931000088L, "success", "Wijha", "2026-10-31T12:00:00+02:00", "WJ-55555")));
        pack(s, o);
        captureAs(s, o, payload(f(7317931000088L, "success", "Wijha", "2026-10-31T13:00:00+02:00", "WJ-66666")));

        assertThat(leg(o).get("tracking_number")).isEqualTo("WJ-66666");
        assertThat(changes(s)).containsExactly("edit|WJ-55555→WJ-66666|applied||{packed}");
        assertThat(trackingAlerts(s)).containsExactly("tracking_changed_relabel|MEDIUM|#37563");
        // The pickup scan hands it over: it left custody — the relabel alert is gone.
        history((UUID) leg(o).get("id"), "with_courier");
        assertThat(trackingAlerts(s)).isEmpty();
    }

    @Test
    void g3_frozen_refusedHigh_legKept_clearsWhenShopifyRevertsToItsNumber() {
        Shop s = new Shop("g3");
        UUID o = s.order("#37616");
        captureAs(s, o, payload(f(7317931000089L, "success", "Aramex", "2026-10-31T12:00:00+02:00", "ARV3626413")));
        UUID legId = (UUID) leg(o).get("id");
        jdbc.update("UPDATE shipments SET internal_state = 'with_courier' WHERE id = ?", legId);
        history(legId, "with_courier");
        captureAs(s, o, payload(f(7317931000089L, "success", "Aramex", "2026-10-31T14:00:00+02:00", "ARV3626551")));

        assertThat(leg(o).get("tracking_number")).as("left custody: kept").isEqualTo("ARV3626413");
        assertThat(changes(s)).containsExactly("edit|ARV3626413→ARV3626551|refused|frozen|{}");
        assertThat(trackingAlerts(s)).containsExactly("tracking_changed_after_custody|HIGH|#37616");
        captureAs(s, o, payload(f(7317931000089L, "success", "Aramex", "2026-10-31T16:00:00+02:00", "ARV3626413")));
        assertThat(trackingAlerts(s)).as("Shopify shows the leg's own number again").isEmpty();
    }

    @Test
    void g4_openPickupSession_refusedHigh() {
        Shop s = new Shop("g4");
        UUID o = s.order("#37717");
        captureAs(s, o, payload(f(7317931000090L, "success", "Wijha", "2026-10-31T12:00:00+02:00", "WJ-70001")));
        UUID legId = (UUID) leg(o).get("id");
        UUID pickup = jdbc.queryForObject("INSERT INTO pickups (tenant_id, session_status) VALUES (?, 'open') RETURNING id",
            UUID.class, s.f.tenant);
        jdbc.update("INSERT INTO pickup_shipments (pickup_id, shipment_id, tenant_id) VALUES (?, ?, ?)", pickup, legId, s.f.tenant);
        captureAs(s, o, payload(f(7317931000090L, "success", "Wijha", "2026-10-31T13:00:00+02:00", "WJ-70002")));

        assertThat(leg(o).get("tracking_number")).isEqualTo("WJ-70001");
        assertThat(changes(s)).containsExactly("edit|WJ-70001→WJ-70002|refused|pickup_session|{}");
        assertThat(trackingAlerts(s)).containsExactly("tracking_changed_after_custody|HIGH|#37717");
    }

    @Test
    void g5_packedCancelAndRefulfill_strict_inPlaceOntoNewFulfillment() {
        Shop s = new Shop("g5");
        UUID o = s.order("#37761");
        captureAs(s, o, payload(f(7329122353521L, "success", "Aramex", "2026-10-30T21:00:00+02:00", "ARV3626450")));
        UUID legId = (UUID) leg(o).get("id");
        pack(s, o);
        captureAs(s, o, payload(f(7329122353521L, "cancelled", "Aramex", "2026-10-30T21:13:00+02:00", "ARV3626450"),
                                f(7331107176817L, "success", "Aramex", "2026-11-01T22:03:26+02:00", "ARV3626451")));

        Map<String, Object> l = leg(o);
        assertThat(l.get("id")).isEqualTo(legId);
        assertThat(l.get("tracking_number")).isEqualTo("ARV3626451");
        assertThat(l.get("shopify_fulfillment_id")).isEqualTo("7331107176817");
        assertThat(changes(s)).containsExactly("cancel_refulfill|ARV3626450→ARV3626451|applied||{packed}");
        assertThat(alertOrders(s, "generic_fulfillment_cancelled")).as("its fulfillment is live again").isEmpty();
        assertThat(trackingAlerts(s)).containsExactly("tracking_changed_relabel|MEDIUM|#37761");
    }

    @Test
    void g5b_unpackedCancelAndRefulfill_preV200Behaviour_cancelThenNewLeg() {
        Shop s = new Shop("g5b");
        UUID o = s.order("#37762");
        captureAs(s, o, payload(f(7329122353522L, "success", "Aramex", "2026-10-30T21:00:00+02:00", "ARV3626460")));
        UUID old = (UUID) leg(o).get("id");
        captureAs(s, o, payload(f(7329122353522L, "cancelled", "Aramex", "2026-10-30T21:13:00+02:00", "ARV3626460"),
                                f(7331107176818L, "success", "Aramex", "2026-11-01T22:03:26+02:00", "ARV3626461")));
        assertThat(jdbc.queryForObject("SELECT internal_state::text FROM shipments WHERE id = ?", String.class, old)).isEqualTo("cancelled");
        assertThat(leg(o).get("tracking_number")).isEqualTo("ARV3626461");
        assertThat(leg(o).get("id")).isNotEqualTo(old);
        assertThat(changes(s)).isEmpty();
    }

    @Test
    void g6_looserShapes_refusedMedium_neverGuesses() {
        Shop s = new Shop("g6");
        UUID twoNumbers = s.order("#37800"), twoFulfillments = s.order("#37801");
        captureAs(s, twoNumbers, payload(f(7400000000001L, "success", "Aramex", "2026-10-31T10:00:00+02:00", "ARV3627001")));
        captureAs(s, twoNumbers, payload(f(7400000000001L, "success", "Aramex", "2026-10-31T11:00:00+02:00", "ARV3627002", "ARV3627003")));
        captureAs(s, twoFulfillments, payload(f(7400000000002L, "success", "Aramex", "2026-10-31T10:00:00+02:00", "ARV3627011")));
        pack(s, twoFulfillments);
        captureAs(s, twoFulfillments, payload(f(7400000000002L, "cancelled", "Aramex", "2026-10-31T11:00:00+02:00", "ARV3627011"),
            f(7400000000003L, "success", "Aramex", "2026-10-31T12:00:00+02:00", "ARV3627012"),
            f(7400000000004L, "success", "Aramex", "2026-10-31T12:05:00+02:00", "ARV3627013")));

        assertThat(leg(twoNumbers).get("tracking_number")).isEqualTo("ARV3627001");
        assertThat(leg(twoFulfillments).get("tracking_number")).isEqualTo("ARV3627011");
        assertThat(changes(s)).containsExactlyInAnyOrder(
            "edit|ARV3627001→ARV3627002, ARV3627003|refused|ambiguous|{}",
            "cancel_refulfill|ARV3627011→ARV3627012, ARV3627013|refused|ambiguous|{}");
        assertThat(trackingAlerts(s)).containsExactlyInAnyOrder(
            "tracking_change_needs_review|MEDIUM|#37800", "tracking_change_needs_review|MEDIUM|#37801");
    }

    @Test
    void g7_newNumberIsAnotherOrdersWaybill_refusedKeyTaken() {
        Shop s = new Shop("g7");
        UUID mine = s.order("#37900"), theirs = s.order("#37901");
        captureAs(s, theirs, payload(f(7400000000010L, "success", "Wijha", "2026-10-31T10:00:00+02:00", "WJ-80002")));
        captureAs(s, mine, payload(f(7400000000011L, "success", "Wijha", "2026-10-31T10:00:00+02:00", "WJ-80001")));
        captureAs(s, mine, payload(f(7400000000011L, "success", "Wijha", "2026-10-31T11:00:00+02:00", "wj 80002")));
        assertThat(leg(mine).get("tracking_number")).isEqualTo("WJ-80001");
        assertThat(changes(s)).containsExactly("edit|WJ-80001→wj80002|refused|key_taken|{}");
        assertThat(trackingAlerts(s)).containsExactly("tracking_change_needs_review|MEDIUM|#37900");
    }

    @Test
    void g8_cosmetic_textOnly_noAlert() {
        Shop s = new Shop("g8");
        UUID o = s.order("#38000");
        captureAs(s, o, payload(f(7400000000020L, "success", "Wijha", "2026-10-31T10:00:00+02:00", "WJ-12345")));
        captureAs(s, o, payload(f(7400000000020L, "success", "Wijha", "2026-10-31T11:00:00+02:00", "WJ12345")));
        assertThat(leg(o).get("tracking_number")).isEqualTo("WJ12345");
        assertThat(changes(s)).containsExactly("cosmetic|WJ-12345→WJ12345|applied||{}");
        assertThat(trackingAlerts(s)).isEmpty();
    }

    @Test
    void g9_blanked_recordedOnly_noAlert_legKept() {
        Shop s = new Shop("g9");
        UUID o = s.order("#38001");
        captureAs(s, o, payload(f(7400000000021L, "success", "Aramex", "2026-10-31T10:00:00+02:00", "ARV3628001")));
        captureAs(s, o, payload(f(7400000000021L, "success", "Aramex", "2026-10-31T11:00:00+02:00")));
        assertThat(leg(o).get("tracking_number")).isEqualTo("ARV3628001");
        assertThat(rows(o)).containsExactly("ARV3628001|removed|success");
        assertThat(changes(s)).containsExactly("blanked|ARV3628001→null|recorded||{}");
        assertThat(trackingAlerts(s)).isEmpty();
    }

    @Test
    void g10_outOfOrderPayloads_neverRevert() {
        Shop s = new Shop("g10");
        UUID o = s.order("#38002");
        ObjectNode a = payload(f(7400000000022L, "success", "Aramex", "2026-10-31T10:00:00+02:00", "ARV3628010"));
        ObjectNode b = payload(f(7400000000022L, "success", "Aramex", "2026-10-31T11:00:00+02:00", "ARV3628011"));
        captureAs(s, o, a);
        captureAs(s, o, b);
        captureAs(s, o, a);   // the older payload arrives late
        assertThat(leg(o).get("tracking_number")).isEqualTo("ARV3628011");
        assertThat(rows(o)).containsExactlyInAnyOrder("ARV3628010|removed|success", "ARV3628011|live|success");
        assertThat(changes(s)).containsExactly("edit|ARV3628010→ARV3628011|applied||{}");
    }

    @Test
    void g11_carrierChangeToBosta_andMerchantEnteredLeg_neverFollowed() {
        Shop s = new Shop("g11");
        UUID toBosta = s.order("#38003"), entered = s.order("#38004");
        captureAs(s, toBosta, payload(f(7400000000030L, "success", "Wijha", "2026-10-31T10:00:00+02:00", "WJ-90001")));
        captureAs(s, toBosta, payload(f(7400000000030L, "success", "Bosta", "2026-10-31T11:00:00+02:00", "4266118553")));
        assertThat(leg(toBosta).get("tracking_number")).isEqualTo("WJ-90001");

        jdbc.update("INSERT INTO shipments (tenant_id, order_id, provider, tracking_number, tracking_key, internal_state, source, " +
            "shopify_fulfillment_id, carrier_name) VALUES (?, ?, 'generic', 'WJ-90010', 'WJ90010', 'created', 'merchant_entered', " +
            "'7400000000031', 'Wijha')", s.f.tenant, entered);
        captureAs(s, entered, payload(f(7400000000031L, "success", "Wijha", "2026-10-31T11:00:00+02:00", "WJ-90011")));
        assertThat(leg(entered).get("tracking_number")).isEqualTo("WJ-90010");
        assertThat(changes(s)).isEmpty();
    }

    // ── rls ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void rls_editAsAppUser_recorded_otherTenantSeesNothing_noUpdateOrDelete() {
        Shop s = new Shop("rls"), other = new Shop("rls-other");
        UUID o = s.order("#38100");
        captureAs(s, o, payload(f(7400000000040L, "success", "Aramex", "2026-10-31T10:00:00+02:00", "ARV3629001")));

        var appDs = new com.traceability.tenancy.TenantAwareDataSource(
            new org.springframework.jdbc.datasource.DriverManagerDataSource(POSTGRES.getJdbcUrl(), "app_user", "testpw"));
        JdbcTemplate app = new JdbcTemplate(appDs);
        TransactionTemplate appTx = new TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(appDs));
        FulfillmentTrackingCapture appCapture = new FulfillmentTrackingCapture(app, linkService);
        ObjectNode edit = payload(f(7400000000040L, "success", "Aramex", "2026-10-31T11:00:00+02:00", "ARV3629002"));
        jdbc.update("UPDATE orders SET raw = ?::jsonb WHERE id = ?", edit.toString(), o);
        String gid = jdbc.queryForObject("SELECT external_id FROM orders WHERE id = ?", String.class, o);
        TenantContext.runAs(s.f.tenant, () -> appTx.execute(x -> appCapture.capture(s.f.store, gid, edit)));

        assertThat(leg(o).get("tracking_number")).isEqualTo("ARV3629002");
        java.util.concurrent.Callable<Integer> count = () -> appTx.execute(x ->
            app.queryForObject("SELECT count(*) FROM shipment_tracking_changes", Integer.class));
        Integer own = TenantContext.runAs(s.f.tenant, count);
        Integer theirs = TenantContext.runAs(other.f.tenant, count);
        assertThat(own).as("same-tenant positive control (app_user)").isEqualTo(1);
        assertThat(theirs).as("another tenant sees nothing").isZero();
        assertThatThrownBy(() -> TenantContext.runAs(s.f.tenant, () -> appTx.execute(x ->
            app.update("UPDATE shipment_tracking_changes SET outcome = 'refused'")))).rootCause().hasMessageContaining("permission denied");
        assertThatThrownBy(() -> TenantContext.runAs(s.f.tenant, () -> appTx.execute(x ->
            app.update("DELETE FROM shipment_tracking_changes")))).rootCause().hasMessageContaining("permission denied");
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────────────────

    /** As orders/updated does: the raw order is upserted first, then the capture runs. */
    private void captureAs(Shop s, UUID o, ObjectNode payload) {
        jdbc.update("UPDATE orders SET raw = ?::jsonb WHERE id = ?", payload.toString(), o);
        String gid = jdbc.queryForObject("SELECT external_id FROM orders WHERE id = ?", String.class, o);
        TenantContext.runAs(s.f.tenant, () -> tx.execute(x -> capture.capture(s.f.store, gid, payload)));
    }

    private ObjectNode f(long id, String status, String company, String updatedAt, String... numbers) {
        ObjectNode n = mapper.createObjectNode();
        n.put("id", id);
        n.put("status", status);
        n.put("created_at", "2026-10-26T09:00:00+03:00");
        n.put("updated_at", updatedAt);
        n.put("shipment_status", "confirmed");
        n.put("tracking_company", company);
        if (numbers.length > 0) n.put("tracking_number", numbers[0]); else n.putNull("tracking_number");
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

    /** "number|live/removed|status" per tracking row. */
    private List<String> rows(UUID o) {
        return jdbc.queryForList("SELECT tracking_number || '|' || CASE WHEN removed_at IS NULL THEN 'live' ELSE 'removed' END " +
            "|| '|' || coalesce(fulfillment_status, '') FROM order_fulfillment_tracking WHERE order_id = ? ORDER BY tracking_number",
            String.class, o);
    }

    private List<String> rowsFull(UUID o) {
        return jdbc.queryForList("SELECT tracking_number || '|' || coalesce(carrier_raw,'') || '|' || carrier_class || '|' || " +
            "coalesce(shopify_fulfillment_id,'') || '|' || coalesce(fulfillment_status,'') || '|' || coalesce(shipment_status,'') || " +
            "'|' || coalesce(removed_at::text,'') || '|' || coalesce(fulfillment_updated_at::text,'') || '|seen=' || last_seen_at " +
            "FROM order_fulfillment_tracking WHERE order_id = ? ORDER BY tracking_number", String.class, o);
    }

    private Map<String, Object> leg(UUID o) {
        return jdbc.queryForMap("SELECT id, tracking_number, tracking_key, shopify_fulfillment_id, tracking_changed_at FROM shipments " +
            "WHERE order_id = ? AND shipment_leg = 'forward' AND internal_state NOT IN ('cancelled', 'terminated') " +
            "ORDER BY created_at DESC, id DESC LIMIT 1", o);
    }

    private String shipmentsText(Shop s) {
        return String.join("\n", jdbc.queryForList("SELECT sh::text FROM shipments sh WHERE tenant_id = ? ORDER BY id::text",
            String.class, s.f.tenant)).replaceAll("\\d{4}-\\d\\d-\\d\\d \\d\\d:\\d\\d:\\d\\d[.\\d]*\\+\\d\\d", "<ts>");
    }

    /** "kind|old→new|outcome|reason|relabel" per decision. */
    private List<String> changes(Shop s) {
        return jdbc.queryForList("SELECT kind || '|' || old_tracking_number || '→' || coalesce(new_tracking_number, 'null') || '|' || " +
            "outcome || '|' || coalesce(reason, '') || '|' || relabel::text FROM shipment_tracking_changes WHERE tenant_id = ? " +
            "ORDER BY created_at, id", String.class, s.f.tenant);
    }

    private List<String> trackingAlerts(Shop s) {
        return TenantContext.runAs(s.f.tenant, () -> exceptions.detectAllOpen()).stream()
            .filter(e -> ((String) e.get("type")).startsWith("tracking_change"))
            .map(e -> e.get("type") + "|" + e.get("severity") + "|" + e.get("order_number")).toList();
    }

    private List<String> alertOrders(Shop s, String type) {
        return TenantContext.runAs(s.f.tenant, () -> exceptions.detectAllOpen()).stream()
            .filter(e -> type.equals(e.get("type"))).map(e -> (String) e.get("order_number")).toList();
    }

    /** Picked and packed through the real queue flow. */
    private void pack(Shop s, UUID o) {
        jdbc.update("UPDATE tenants SET pick_pack_mode = 'order_queue' WHERE id = ?", s.f.tenant);
        TenantContext.set(s.f.tenant);
        assertThat(fulfill.scan(o, s.f.piece(s.variant, s.packer), s.packer).success()).isTrue();
        fulfill.complete(o, s.packer);
        TenantContext.clear();
        jdbc.update("UPDATE tenants SET pick_pack_mode = 'waybill_scan' WHERE id = ?", s.f.tenant);
    }

    private void history(UUID shipment, String state) {
        jdbc.update("INSERT INTO shipment_status_history (tenant_id, shipment_id, internal_state, source) " +
            "SELECT tenant_id, id, ?::shipment_internal_state, 'pickup_scan' FROM shipments WHERE id = ?", state, shipment);
    }
}
