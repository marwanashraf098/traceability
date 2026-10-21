package com.traceability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.traceability.courier.GenericLegTransitions;
import com.traceability.identity.JwtService;
import com.traceability.integrations.bosta.BostaV2Client;
import com.traceability.integrations.shopify.ShopifyGateway;
import com.traceability.inventory.ReturnSessionService;
import com.traceability.inventory.UlidGenerator;
import com.traceability.portal.PortalService;
import com.traceability.portal.ReturnPickupBookingJob;
import com.traceability.portal.ReturnPickupBookingService;
import com.traceability.security.EncryptionService;
import com.traceability.tenancy.TenantContext;
import org.jobrunr.jobs.lambdas.IocJobLambda;
import org.jobrunr.scheduling.JobScheduler;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Non-Bosta couriers G4a — returns-portal refunds for orders shipped with another courier (V176).
 *
 *   e*  eligibility: the switch, delivered only (Mark delivered / Shopify), inside the window, the per-order flag
 *   a*  approval: booking 'manual', nothing enqueued, every Bosta booking path skips it, auto-approve, exchanges
 *   m*  POST /return-requests/{id}/manual-courier: with / without tracking, refusals, roles, events
 *   s*  Scan returns: return waybill → the request's parcel; attributed piece keeps the forward leg delivered;
 *       close → the generic return leg 'returned'; a parcel returned to sender (no request) still behaves as G3
 *   x*  mixed tenant, settings switch, portal config hint
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GenericCourierG4aTest {

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
        r.add("analytics.settlement.refresh-enabled", () -> "false");
    }

    static final String CAIRO = "FceDyHXwpSYYF9zGW", NASR = "Iy7-lFD0BE0";

    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;
    @Autowired JwtService jwt;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired EncryptionService encryption;
    @Autowired PortalService portal;
    @Autowired ReturnPickupBookingService booking;
    @Autowired ReturnSessionService returns;
    @Autowired GenericLegTransitions legs;
    @MockBean  ShopifyGateway shopifyGateway;
    @MockBean  JobScheduler jobScheduler;
    @MockBean  BostaV2Client bostaV2;

    static final AtomicInteger SEQ = new AtomicInteger();

    record T(UUID id, String slug, UUID store, UUID location, UUID variant, UUID owner, UUID manager, UUID worker,
             String ownerToken, String managerToken, String workerToken) {}
    record O(UUID id, String number, UUID forward, List<String> pieces, String phone) {}

    @BeforeAll
    void districts() {
        jdbc.update("INSERT INTO bosta_districts (district_id, city_id, city_name, city_name_ar, zone_id, zone_name, zone_name_ar, " +
                    "district_name, district_name_ar, pickup_available, dropoff_available) VALUES " +
                    "(?, ?, 'Cairo', 'القاهرة', 'z1', 'Nasr City', 'مدينة نصر', 'Nasr City - 7th District', 'مدينة نصر - الحي السابع', true, true) " +
                    "ON CONFLICT DO NOTHING", NASR, CAIRO);
    }

    @BeforeEach void reset() { clearInvocations(jobScheduler); clearInvocations(bostaV2); }
    @AfterEach  void clear() { TenantContext.clear(); }

    // ── e: eligibility ───────────────────────────────────────────────────────────────────────────

    @Test
    void e1_notEligibleUntilDelivered_thenEligibleAfterMarkDelivered_withPerOrderFlag_noPickupArea() throws Exception {
        T t = tenant(true, true);
        O o = genericOrder(t, "#6001", "with_courier", "Aramex", "AR-6001");
        assertThat(lookup(t, o).outcome()).as("with the courier — not delivered yet").isEqualTo(PortalService.Outcome.NOT_FOUND);

        Object marked = post(t.ownerToken(), "/api/v1/generic-shipments/mark-delivered", Map.of("orderIds", List.of(o.id()))).getBody();
        assertThat(String.valueOf(marked)).contains("DELIVERED");

        PortalService.LookupResult r = lookup(t, o);
        assertThat(r.outcome()).isEqualTo(PortalService.Outcome.SUCCESS);
        assertThat(r.body()).containsEntry("otherCourier", Map.of("carrierName", "Aramex"));
        assertThat(r.body().get("pickup")).as("no Bosta pickup area for another courier's order").isNull();
        assertThat(r.body().get("orderNumber")).isEqualTo("#6001");
    }

    @Test
    void e2_shopifyDelivered_eligible_outOfWindow_not_switchOff_not() {
        T t = tenant(true, true);
        O o = genericOrder(t, "#6002", "with_courier", "Mylerz", "MY6002");
        TenantContext.set(t.id());
        assertThat(legs.deliver(t.id(), o.forward(), GenericLegTransitions.SOURCE_SHOPIFY, null))
            .isEqualTo(GenericLegTransitions.Delivered.DELIVERED);
        TenantContext.clear();
        assertThat(lookup(t, o).outcome()).isEqualTo(PortalService.Outcome.SUCCESS);

        jdbc.update("UPDATE tenants SET portal_generic_returns_enabled = false WHERE id = ?", t.id());
        assertThat(lookup(t, o).outcome()).as("switch off — out of the portal exactly as G3").isEqualTo(PortalService.Outcome.NOT_FOUND);
        jdbc.update("UPDATE tenants SET portal_generic_returns_enabled = true WHERE id = ?", t.id());

        jdbc.update("UPDATE shipments SET delivered_at = now() - interval '40 days' WHERE id = ?", o.forward());
        assertThat(lookup(t, o).outcome()).as("outside the 30-day window").isEqualTo(PortalService.Outcome.NOT_FOUND);
    }

    @Test
    void e3_handedOverButNeverDelivered_neverEligible_evenWithHandedOverAt() {
        T t = tenant(true, true);
        O o = genericOrder(t, "#6003", "with_courier", "Aramex", "AR6003");
        jdbc.update("UPDATE shipments SET handed_over_at = now() - interval '10 days' WHERE id = ?", o.forward());
        assertThat(lookup(t, o).outcome()).isEqualTo(PortalService.Outcome.NOT_FOUND);
    }

    @Test
    void e4_submitRefund_works_exchangeAndCustomAddressRefused() {
        T t = tenant(true, true);
        O o = deliveredGeneric(t, "#6004", "Aramex", "AR6004");
        String token = (String) lookup(t, o).body().get("token");

        Optional<PortalService.SubmitResult> exchange = portal.submit(t.slug(), token, new PortalService.SubmitRequest(
            List.of(new PortalService.SubmitLine(t.variant(), 1, "wrong_size")), null, null, null, "exchange", t.variant(), false));
        assertThat(exchange.orElseThrow().outcome()).isEqualTo(PortalService.SubmitOutcome.INVALID);

        Optional<PortalService.SubmitResult> custom = portal.submit(t.slug(), token, new PortalService.SubmitRequest(
            List.of(new PortalService.SubmitLine(t.variant(), 1, "wrong_size")), null, null, null, null, null, null, "custom",
            new PortalService.CustomAddress(CAIRO, NASR, "7 New Street, Heliopolis", null, null, null, null)));
        assertThat(custom.orElseThrow().outcome()).isEqualTo(PortalService.SubmitOutcome.INVALID);

        PortalService.SubmitResult ok = portal.submit(t.slug(), token, new PortalService.SubmitRequest(
            List.of(new PortalService.SubmitLine(t.variant(), 2, "wrong_size")), null, null)).orElseThrow();
        assertThat(ok.outcome()).isEqualTo(PortalService.SubmitOutcome.CREATED);
        assertThat(ok.body()).containsEntry("status", "requested").doesNotContainKey("_bookRequestId");
        assertThat(jdbc.queryForObject("SELECT booking_status FROM return_requests WHERE order_id = ?", String.class, o.id())).isNull();
    }

    // ── a: approval → 'manual' ───────────────────────────────────────────────────────────────────

    @Test
    void a1_approve_manual_nothingEnqueued_everyBostaPathSkipsIt_overTime() throws Exception {
        T t = tenant(true, true);
        O o = deliveredGeneric(t, "#6011", "Aramex", "AR6011");
        UUID rr = submit(t, o, 1);

        assertThat(post(t.ownerToken(), "/api/v1/return-requests/" + rr + "/approve", null).getStatusCode().value()).isEqualTo(204);
        assertThat(row(rr)).containsEntry("status", "approved").containsEntry("booking_status", "manual")
            .containsEntry("manual_carrier_name", "Aramex");
        assertThat(bookingJobs()).as("nothing enqueued on approve").isEmpty();

        // the sweeper — run it with the approval well past the orphan age, twice
        jdbc.update("UPDATE return_requests SET decided_at = now() - interval '2 hours' WHERE id = ?", rr);
        ReturnPickupBookingService.SweepResult s1 = booking.sweepTenant(t.id());
        ReturnPickupBookingService.SweepResult s2 = booking.sweepTenant(t.id());
        assertThat(s1.enqueued()).isZero();
        assertThat(s2.enqueued()).isZero();
        assertThat(bookingJobs()).as("the sweeper never enqueues a manual request").isEmpty();

        // the job itself (backstop) posts nothing and claims nothing
        booking.book(rr, t.id());
        verifyNoInteractions(bostaV2);
        assertThat(row(rr)).containsEntry("booking_status", "manual");

        for (String path : List.of("booking/retry", "booking/not-booked", "booking/book-now")) {
            assertThat(post(t.ownerToken(), "/api/v1/return-requests/" + rr + "/" + path, null).getStatusCode().value())
                .as(path).isEqualTo(409);
        }
        assertThat(post(t.ownerToken(), "/api/v1/return-requests/" + rr + "/booking/confirm",
            Map.of("trackingNumber", "1234567890")).getStatusCode().value()).isEqualTo(409);
        assertThat(row(rr)).containsEntry("booking_status", "manual").containsEntry("status", "approved");

        // pickup_booking_problem never sees it; the hand-booked link candidate rule never offers it
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM return_requests rr WHERE rr.id = ? AND " +
            com.traceability.returncases.ReturnCaseRules.BOOKING_PROBLEM_SQL, Integer.class, rr)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM return_requests rr WHERE rr.id = ? AND " +
            com.traceability.portal.ReturnRequestLifecycle.linkCandidateSql("rr.tenant_id", "rr.order_id", "now()"),
            Integer.class, rr)).isZero();

        // the drawer: otherCourier with the carrier and where to collect; a list chip carrier
        Map<?, ?> detail = (Map<?, ?>) get(t.ownerToken(), "/api/v1/return-requests/" + rr).getBody();
        assertThat(detail.get("bookingStatus")).isEqualTo("manual");
        Map<?, ?> other = (Map<?, ?>) detail.get("otherCourier");
        assertThat(other.get("carrierName")).isEqualTo("Aramex");
        assertThat(other.get("forwardCarrierName")).isEqualTo("Aramex");
        assertThat(((Map<?, ?>) other.get("pickup")).get("name")).isEqualTo("Youssef Adel");
        String list = String.valueOf(get(t.ownerToken(), "/api/v1/return-requests").getBody());
        assertThat(list).contains("manualCarrierName=Aramex");
    }

    @Test
    void a2_autoApprove_genericManual_noJob() {
        T t = tenant(true, true);
        jdbc.update("UPDATE tenants SET portal_auto_approve = true WHERE id = ?", t.id());
        O o = deliveredGeneric(t, "#6012", "Aramex", "AR6012");
        UUID rr = submit(t, o, 1);
        assertThat(row(rr)).containsEntry("status", "approved").containsEntry("booking_status", "manual");
        assertThat(bookingJobs()).isEmpty();
    }

    @Test
    void a3_genericExchangeRequest_approveRefused_withClearMessage() throws Exception {
        T t = tenant(true, true);
        O o = deliveredGeneric(t, "#6013", "Aramex", "AR6013");
        UUID rr = jdbc.queryForObject("INSERT INTO return_requests (tenant_id, order_id, type, status, reference) " +
            "VALUES (?, ?, 'exchange', 'requested', ?) RETURNING id", UUID.class, t.id(), o.id(), ref());
        jdbc.update("INSERT INTO return_request_items (tenant_id, request_id, piece_id, variant_id, reason_code, replacement_variant_id) " +
            "VALUES (?, ?, ?, ?, 'wrong_size', ?)", t.id(), rr, pieceId(o.pieces().get(0)), t.variant(), t.variant());
        ResponseEntity<Object> r = post(t.ownerToken(), "/api/v1/return-requests/" + rr + "/approve", null);
        assertThat(r.getStatusCode().value()).isEqualTo(409);
        assertThat(String.valueOf(r.getBody())).contains("EXCHANGE_OTHER_COURIER").contains("coming soon");
        assertThat(row(rr)).containsEntry("status", "requested");
        assertThat(bookingJobs()).isEmpty();
    }

    // ── m: manual-courier endpoint ───────────────────────────────────────────────────────────────

    @Test
    void m1_withoutTracking_carrierRecorded_staysApproved_thenWithTracking_legCreatedAndLinked() throws Exception {
        T t = tenant(true, true);
        O o = deliveredGeneric(t, "#6021", "Aramex", "AR6021");
        UUID rr = approved(t, o);

        ResponseEntity<Object> noTn = post(t.managerToken(), "/api/v1/return-requests/" + rr + "/manual-courier",
            Map.of("carrier", "Mylerz"));
        assertThat(noTn.getStatusCode().value()).isEqualTo(200);
        assertThat(row(rr)).containsEntry("status", "approved").containsEntry("manual_carrier_name", "Mylerz")
            .containsEntry("return_shipment_id", null);
        assertThat(events(rr)).contains("courier_chosen");

        ResponseEntity<Object> withTn = post(t.ownerToken(), "/api/v1/return-requests/" + rr + "/manual-courier",
            Map.of("carrier", "Mylerz", "trackingNumber", " MYR-77 001 "));
        assertThat(withTn.getStatusCode().value()).isEqualTo(200);
        Map<String, Object> r = row(rr);
        assertThat(r).containsEntry("status", "pickup_booked").containsEntry("link_source", "merchant_entered")
            .containsEntry("bosta_tracking_number", null).containsEntry("booking_status", "manual");
        Map<String, Object> leg = jdbc.queryForMap("SELECT provider::text AS provider, shipment_leg, source, tracking_number, " +
            "tracking_key, carrier_name, internal_state::text AS state FROM shipments WHERE id = ?", r.get("return_shipment_id"));
        assertThat(leg).containsEntry("provider", "generic").containsEntry("shipment_leg", "return")
            .containsEntry("source", "portal_request").containsEntry("tracking_number", "MYR-77 001")
            .containsEntry("tracking_key", "MYR77001").containsEntry("carrier_name", "Mylerz").containsEntry("state", "created");
        assertThat(events(rr)).contains("leg_linked", "pickup_booked");

        // set once: a second call → 409
        assertThat(post(t.ownerToken(), "/api/v1/return-requests/" + rr + "/manual-courier",
            Map.of("trackingNumber", "OTHER1")).getStatusCode().value()).isEqualTo(409);
    }

    @Test
    void m2_sameKeyAsForward_refused_requestContinuesWithNoLeg_takenKey_refused_roles_bosta() throws Exception {
        T t = tenant(true, true);
        O o = deliveredGeneric(t, "#6022", "Aramex", "AR-6022");
        UUID rr = approved(t, o);

        ResponseEntity<Object> same = post(t.ownerToken(), "/api/v1/return-requests/" + rr + "/manual-courier",
            Map.of("trackingNumber", "ar 6022"));
        assertThat(same.getStatusCode().value()).isEqualTo(409);
        assertThat(String.valueOf(same.getBody())).contains("SAME_AS_ORDER_WAYBILL");
        assertThat(row(rr)).containsEntry("status", "approved").containsEntry("return_shipment_id", null);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM shipments WHERE order_id = ? AND shipment_leg = 'return'",
            Integer.class, o.id())).isZero();

        O other = deliveredGeneric(t, "#6023", "Aramex", "AR-6023");
        ResponseEntity<Object> taken = post(t.ownerToken(), "/api/v1/return-requests/" + rr + "/manual-courier",
            Map.of("trackingNumber", "AR6023"));
        assertThat(taken.getStatusCode().value()).isEqualTo(409);
        assertThat(String.valueOf(taken.getBody())).contains("TRACKING_TAKEN");
        assertThat(other.id()).isNotNull();

        assertThat(post(t.workerToken(), "/api/v1/return-requests/" + rr + "/manual-courier",
            Map.of("carrier", "Aramex")).getStatusCode().value()).as("worker").isEqualTo(403);

        // a Bosta request is never 'manual'
        O bosta = bostaOrder(t, "#6024");
        UUID brr = approvedDirect(t, bosta, null);
        ResponseEntity<Object> notManual = post(t.ownerToken(), "/api/v1/return-requests/" + brr + "/manual-courier",
            Map.of("carrier", "Aramex", "trackingNumber", "ZZ1"));
        assertThat(notManual.getStatusCode().value()).isEqualTo(409);
        assertThat(String.valueOf(notManual.getBody())).contains("NOT_MANUAL");
    }

    // ── s: Scan returns ──────────────────────────────────────────────────────────────────────────

    @Test
    void s1_returnWaybill_opensRequestParcel_attributedPiece_forwardStaysDelivered_closeReturnsTheLeg() throws Exception {
        T t = tenant(true, true);
        O o = deliveredGeneric(t, "#6031", "Aramex", "AR6031");
        UUID rr = approved(t, o);
        post(t.ownerToken(), "/api/v1/return-requests/" + rr + "/manual-courier", Map.of("trackingNumber", "RET-6031"));
        UUID returnLeg = (UUID) row(rr).get("return_shipment_id");

        TenantContext.set(t.id());
        UUID session = returns.createSession(null, t.worker());
        Map<String, Object> awb = returns.scan(session, "ret6031", t.location(), t.worker());
        assertThat(awb).containsEntry("scanType", "awb").containsEntry("awb", "RET-6031").containsEntry("carrier", "Aramex");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> parcels = (List<Map<String, Object>>) returns.getSession(session).get("parcels");
        assertThat(parcels).anySatisfy(p -> {
            assertThat(p.get("shipmentId")).isEqualTo(returnLeg.toString());
            assertThat(p.get("requestReference")).isNotNull();
        });

        Map<String, Object> piece = returns.scan(session, o.pieces().get(0), t.location(), t.worker());
        assertThat(String.valueOf(piece)).doesNotContain("unexpected=true");
        assertThat(state(o.forward())).as("the customer's return — the forward leg stays delivered").isEqualTo("delivered");
        assertThat(jdbc.queryForObject("SELECT metadata->>'return_kind' FROM piece_events WHERE piece_id = ? " +
            "AND event_type = 'return_received'", String.class, pieceId(o.pieces().get(0)))).isEqualTo("request_return");
        assertThat(jdbc.queryForObject("SELECT item_status FROM return_request_items WHERE request_id = ?", String.class, rr))
            .isEqualTo("arrived");

        returns.disposition(session, pieceId(o.pieces().get(0)), "restock", null, t.location(), t.worker(), "worker");
        returns.close(session, t.worker());
        TenantContext.clear();
        Map<String, Object> leg = jdbc.queryForMap("SELECT internal_state::text AS state, return_intake_completed_at IS NOT NULL AS intake, " +
            "returned_at IS NOT NULL AS returned, (SELECT string_agg(h.internal_state || ':' || coalesce(h.source, '-'), ',') " +
            "FROM shipment_status_history h WHERE h.shipment_id = s.id) AS history FROM shipments s WHERE s.id = ?", returnLeg);
        assertThat(leg).containsEntry("state", "returned").containsEntry("intake", true).containsEntry("returned", true)
            .containsEntry("history", "returned:return_scan");
        assertThat(state(o.forward())).isEqualTo("delivered");
        assertThat(row(rr).get("status")).as("the one item is done — nothing left to arrive").isIn("received", "refund_pending");
    }

    @Test
    void s2_noTracking_pieceScanOnly_originalWaybill_forwardStaysDelivered() {
        T t = tenant(true, true);
        O o = deliveredGeneric(t, "#6032", "Aramex", "AR6032");
        UUID rr = approved(t, o);
        TenantContext.set(t.id());
        UUID session = returns.createSession(null, t.worker());
        returns.scan(session, "AR6032", t.location(), t.worker());
        assertThat(state(o.forward())).as("original waybill while a request is open — not returned to sender").isEqualTo("delivered");
        returns.scan(session, o.pieces().get(0), t.location(), t.worker());
        assertThat(state(o.forward())).isEqualTo("delivered");
        assertThat(jdbc.queryForObject("SELECT item_status FROM return_request_items WHERE request_id = ?", String.class, rr))
            .isEqualTo("arrived");
        returns.disposition(session, pieceId(o.pieces().get(0)), "restock", null, t.location(), t.worker(), "worker");
        returns.close(session, t.worker());
    }

    @Test
    void s3_noRequest_returnedToSender_stillG3() {
        T t = tenant(true, true);
        O delivered = deliveredGeneric(t, "#6033", "Aramex", "AR6033");
        O moving = genericOrder(t, "#6034", "with_courier", "Aramex", "AR6034");
        TenantContext.set(t.id());
        UUID session = returns.createSession(null, t.worker());
        returns.scan(session, moving.pieces().get(0), t.location(), t.worker());
        assertThat(state(moving.forward())).isEqualTo("returned");
        returns.scan(session, "AR6033", t.location(), t.worker());
        assertThat(state(delivered.forward())).as("no request: G3's returned-to-sender rule").isEqualTo("returned");
    }

    // ── x: mixed tenant, settings, portal config ────────────────────────────────────────────────

    @Test
    void x1_mixedTenant_bostaAutoBooks_genericGoesManual() {
        T t = tenant(true, true);
        jdbc.update("UPDATE tenants SET portal_auto_approve = true WHERE id = ?", t.id());
        O bosta = bostaOrder(t, "#6041");
        O generic = deliveredGeneric(t, "#6042", "Aramex", "AR6042");

        UUID b = submitBosta(t, bosta);
        assertThat(row(b)).containsEntry("status", "approved").containsEntry("booking_status", null);
        assertThat(bookingJobs()).containsExactly(b);

        UUID g = submit(t, generic, 1);
        assertThat(row(g)).containsEntry("status", "approved").containsEntry("booking_status", "manual");
        assertThat(bookingJobs()).isEmpty();

        PortalService.LookupResult bl = lookup(t, bosta);
        assertThat(bl.body()).doesNotContainKey("otherCourier");
        assertThat(bl.body().get("pickup")).isNotNull();
    }

    @Test
    void x2_settingsSwitch_ownerOnly_requiresGenericCourier_audited_configHint() throws Exception {
        T t = tenant(true, false);
        String path = "/api/v1/tenant/portal-settings/other-couriers";
        assertThat(get(t.managerToken(), path).getStatusCode().value()).isEqualTo(200);
        assertThat(put(t.managerToken(), path, Map.of("enabled", true)).getStatusCode().value()).isEqualTo(403);
        assertThat(portal.config(t.slug()).orElseThrow()).doesNotContainKey("otherCouriers");

        ResponseEntity<Object> on = put(t.ownerToken(), path, Map.of("enabled", true));
        assertThat(on.getStatusCode().value()).isEqualTo(200);
        assertThat(String.valueOf(on.getBody())).contains("enabled=true");
        assertThat(jdbc.queryForObject("SELECT portal_generic_returns_since IS NOT NULL FROM tenants WHERE id = ?", Boolean.class, t.id())).isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE tenant_id = ? AND action = 'portal_generic_returns_switch'",
            Integer.class, t.id())).isEqualTo(1);
        assertThat(portal.config(t.slug()).orElseThrow()).as("no generic shipment yet — no hint").doesNotContainKey("otherCouriers");
        genericOrder(t, "#6051", "with_courier", "Aramex", "AR6051");
        assertThat(portal.config(t.slug()).orElseThrow()).containsEntry("otherCouriers", true);

        T off = tenant(false, false);
        ResponseEntity<Object> refused = put(off.ownerToken(), path, Map.of("enabled", true));
        assertThat(refused.getStatusCode().value()).isEqualTo(409);
        assertThat(String.valueOf(refused.getBody())).contains("OTHER_COURIERS_OFF");
    }

    // ── fixtures ─────────────────────────────────────────────────────────────────────────────────

    private T tenant(boolean genericOn, boolean portalGenericOn) {
        int n = SEQ.incrementAndGet();
        UUID id = UUID.randomUUID(), store = UUID.randomUUID(), location = UUID.randomUUID();
        String slug = "g4a-" + n + "-" + id.toString().substring(0, 6);
        jdbc.update("INSERT INTO tenants (id, name, portal_slug, portal_enabled, portal_pickup_booking, portal_pickup_booking_since, " +
            "    generic_courier_enabled, portal_generic_returns_enabled, portal_generic_returns_since) " +
            "VALUES (?, ?, ?, true, true, now() - interval '1 day', ?, ?, CASE WHEN ? THEN now() END)",
            id, "G4a " + n, slug, genericOn, portalGenericOn, portalGenericOn);
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status) VALUES (?, ?, 'shopify', ?, 'disconnected')",
            store, id, slug + ".myshopify.com");
        jdbc.update("INSERT INTO locations (id, tenant_id, name, is_fulfillment) VALUES (?, ?, 'Main', true)", location, id);
        jdbc.update("INSERT INTO courier_accounts (tenant_id, provider, api_key_encrypted, webhook_secret, status, " +
            "    return_business_location_id, return_business_location_name) VALUES (?, 'bosta', ?, ?, 'active', 'loc', 'Maadi')",
            id, encryption.encrypt("key-" + slug), "wh-" + id);
        UUID owner = user(id, "owner"), manager = user(id, "manager"), worker = user(id, "worker");
        UUID product = jdbc.queryForObject("INSERT INTO products (tenant_id, store_id, external_id, title, status) " +
            "VALUES (?, ?, ?, 'Linen Shirt', 'active') RETURNING id", UUID.class, id, store, "gid://shopify/Product/" + slug);
        UUID variant = jdbc.queryForObject("INSERT INTO variants (tenant_id, product_id, external_id, sku, title) " +
            "VALUES (?, ?, ?, 'SH-M', 'Sand / M') RETURNING id", UUID.class, id, product, "gid://shopify/ProductVariant/" + slug);
        return new T(id, slug, store, location, variant, owner, manager, worker,
            jwt.issueAccessToken(owner, id, "owner"), jwt.issueAccessToken(manager, id, "manager"),
            jwt.issueAccessToken(worker, id, "worker"));
    }

    private UUID user(UUID tenant, String role) {
        return jdbc.queryForObject("INSERT INTO users (tenant_id, name, email, password_hash, role, active) " +
            "VALUES (?, ?, ?, 'x', ?::user_role, true) RETURNING id",
            UUID.class, tenant, role, role + "-" + UUID.randomUUID() + "@test.com", role);
    }

    private UUID order(T t, String number, String status) {
        return jdbc.queryForObject(
            "INSERT INTO orders (tenant_id, store_id, external_id, number, status, customer_name, customer_phone, cod_amount, " +
            "    payment_method, placed_at, shopify_address) " +
            "VALUES (?, ?, ?, ?, ?::order_status, 'Youssef Adel', ?, 900, 'cod', now() - interval '8 days', " +
            "    '{\"address1\": \"5 Tahrir St\", \"city\": \"Cairo\"}'::jsonb) RETURNING id",
            UUID.class, t.id(), t.store(), "gid://shopify/Order/" + t.id() + number, number, status, phone(number));
    }

    private static String phone(String number) { return "0102222" + number.substring(1); }

    /** A generic forward leg in {@code state} (pieces with the courier, or delivered when 'delivered'). */
    private O genericOrder(T t, String number, String state, String carrier, String tracking) {
        boolean delivered = "delivered".equals(state);
        UUID order = order(t, number, delivered ? "delivered" : "with_courier");
        List<String> pieces = pieces(t, order, 2, delivered ? "delivered" : "with_courier");
        UUID leg = jdbc.queryForObject(
            "INSERT INTO shipments (tenant_id, order_id, provider, tracking_number, tracking_key, carrier_name, internal_state, " +
            "    shipment_leg, source, handed_over_at, delivered_at, created_at) " +
            "VALUES (?, ?, 'generic', ?, ?, ?, ?::shipment_internal_state, 'forward', 'shopify_fulfillment', now() - interval '6 days', " +
            "    CASE WHEN ? THEN now() - interval '2 days' END, now() - interval '7 days') RETURNING id",
            UUID.class, t.id(), order, tracking, tracking.replaceAll("[^A-Za-z0-9]", "").toUpperCase(), carrier, state, delivered);
        jdbc.update("INSERT INTO shipment_status_history (tenant_id, shipment_id, internal_state, occurred_at, source) " +
            "VALUES (?, ?, 'with_courier', now() - interval '6 days', 'pickup_scan')", t.id(), leg);
        return new O(order, number, leg, pieces, phone(number));
    }

    /** Delivered through "Mark delivered" (the owner's action — the real path). */
    private O deliveredGeneric(T t, String number, String carrier, String tracking) {
        O o = genericOrder(t, number, "with_courier", carrier, tracking);
        post(t.ownerToken(), "/api/v1/generic-shipments/mark-delivered", Map.of("orderIds", List.of(o.id())));
        assertThat(state(o.forward())).isEqualTo("delivered");
        return o;
    }

    private O bostaOrder(T t, String number) {
        UUID order = order(t, number, "delivered");
        List<String> pieces = pieces(t, order, 1, "delivered");
        String raw = "{\"type\":{\"code\":10,\"value\":\"Send\"},\"dropOffAddress\":{\"firstLine\":\"12 Street\"," +
            "\"city\":{\"_id\":\"" + CAIRO + "\",\"name\":\"Cairo\"},\"district\":{\"_id\":\"" + NASR + "\",\"name\":\"Nasr City\"}}}";
        UUID leg = jdbc.queryForObject("INSERT INTO shipments (tenant_id, order_id, provider, tracking_number, internal_state, " +
            "    shipment_leg, raw, delivered_at) VALUES (?, ?, 'bosta', ?, 'delivered', 'forward', ?::jsonb, now() - interval '2 days') RETURNING id",
            UUID.class, t.id(), order, String.valueOf(4_100_000_000L + SEQ.incrementAndGet()), raw);
        return new O(order, number, leg, pieces, phone(number));
    }

    private List<String> pieces(T t, UUID order, int n, String status) {
        List<String> codes = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            UUID item = jdbc.queryForObject("INSERT INTO order_items (tenant_id, order_id, variant_id, quantity) VALUES (?, ?, ?, 1) RETURNING id",
                UUID.class, t.id(), order, t.variant());
            String id = UlidGenerator.generate();
            String code = "P" + String.format("%06d", Math.floorMod(id.hashCode(), 999_999) + 1);
            jdbc.update("INSERT INTO pieces (id, tenant_id, variant_id, barcode, short_code, status, current_order_id, last_event_at, last_user_id) " +
                "VALUES (?, ?, ?, ?, ?, ?::piece_status, ?, now(), ?)", id, t.id(), t.variant(), "PC-" + id, code, status, order, t.worker());
            jdbc.update("INSERT INTO piece_events (tenant_id, piece_id, event_type, actor_user_id, from_status, to_status) " +
                "VALUES (?, ?, 'received', ?, NULL, 'available'::piece_status)", t.id(), id, t.worker());
            jdbc.update("INSERT INTO allocations (tenant_id, order_item_id, piece_id, status) VALUES (?, ?, ?, 'packed'::allocation_status)",
                t.id(), item, id);
            codes.add(code);
        }
        return codes;
    }

    private PortalService.LookupResult lookup(T t, O o) {
        return portal.lookup(t.slug(), o.number().substring(1), o.phone()).orElseThrow();
    }

    private UUID submit(T t, O o, int qty) {
        String token = (String) lookup(t, o).body().get("token");
        PortalService.SubmitResult r = portal.submit(t.slug(), token, new PortalService.SubmitRequest(
            List.of(new PortalService.SubmitLine(t.variant(), qty, "wrong_size")), null, null)).orElseThrow();
        assertThat(r.outcome()).isEqualTo(PortalService.SubmitOutcome.CREATED);
        return jdbc.queryForObject("SELECT id FROM return_requests WHERE order_id = ? ORDER BY created_at DESC LIMIT 1", UUID.class, o.id());
    }

    private UUID submitBosta(T t, O o) {
        String token = (String) lookup(t, o).body().get("token");
        PortalService.SubmitResult r = portal.submit(t.slug(), token, new PortalService.SubmitRequest(
            List.of(new PortalService.SubmitLine(t.variant(), 1, "wrong_size")), null, null, NASR)).orElseThrow();
        assertThat(r.outcome()).isEqualTo(PortalService.SubmitOutcome.CREATED);
        return jdbc.queryForObject("SELECT id FROM return_requests WHERE order_id = ?", UUID.class, o.id());
    }

    private UUID approved(T t, O o) {
        UUID rr = submit(t, o, 1);
        assertThat(post(t.ownerToken(), "/api/v1/return-requests/" + rr + "/approve", null).getStatusCode().value()).isEqualTo(204);
        clearInvocations(jobScheduler);
        return rr;
    }

    private UUID approvedDirect(T t, O o, String bookingStatus) {
        UUID rr = jdbc.queryForObject("INSERT INTO return_requests (tenant_id, order_id, status, reference, decided_at, booking_status) " +
            "VALUES (?, ?, 'approved', ?, now(), ?) RETURNING id", UUID.class, t.id(), o.id(), ref(), bookingStatus);
        jdbc.update("INSERT INTO return_request_items (tenant_id, request_id, piece_id, variant_id, reason_code) VALUES (?, ?, ?, ?, 'wrong_size')",
            t.id(), rr, pieceId(o.pieces().get(0)), t.variant());
        return rr;
    }

    private static String ref() {
        String a = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ";
        StringBuilder b = new StringBuilder("RR-");
        Random r = new Random();
        for (int i = 0; i < 6; i++) b.append(a.charAt(r.nextInt(a.length())));
        return b.toString();
    }

    private String pieceId(String code) {
        return jdbc.queryForObject("SELECT id FROM pieces WHERE short_code = ?", String.class, code);
    }

    private String state(UUID shipment) {
        return jdbc.queryForObject("SELECT internal_state::text FROM shipments WHERE id = ?", String.class, shipment);
    }

    private Map<String, Object> row(UUID rr) {
        return jdbc.queryForMap("SELECT status::text AS status, booking_status, manual_carrier_name, return_shipment_id, " +
            "link_source, bosta_tracking_number FROM return_requests WHERE id = ?", rr);
    }

    private List<String> events(UUID rr) {
        return jdbc.queryForList("SELECT event_type FROM return_request_events WHERE request_id = ? ORDER BY occurred_at", String.class, rr);
    }

    /** Booking jobs enqueued since the last call (the request each would book); clears the mock. */
    @SuppressWarnings("unchecked")
    private List<UUID> bookingJobs() {
        ArgumentCaptor<IocJobLambda> captor = ArgumentCaptor.forClass(IocJobLambda.class);
        verify(jobScheduler, atLeast(0)).enqueue(captor.capture());
        List<UUID> ids = new ArrayList<>();
        for (IocJobLambda<ReturnPickupBookingJob> l : captor.getAllValues()) {
            ReturnPickupBookingJob job = mock(ReturnPickupBookingJob.class);
            try { l.accept(job); } catch (Exception e) { continue; }
            ArgumentCaptor<UUID> req = ArgumentCaptor.forClass(UUID.class);
            verify(job, atLeast(0)).book(req.capture(), any());
            ids.addAll(req.getAllValues());
        }
        clearInvocations(jobScheduler);
        return ids;
    }

    private ResponseEntity<Object> get(String token, String path) {
        return exchange(token, HttpMethod.GET, path, null);
    }

    private ResponseEntity<Object> post(String token, String path, Object body) {
        return exchange(token, HttpMethod.POST, path, body);
    }

    private ResponseEntity<Object> put(String token, String path, Object body) {
        return exchange(token, HttpMethod.PUT, path, body);
    }

    private ResponseEntity<Object> exchange(String token, HttpMethod method, String path, Object body) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        h.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange("http://localhost:" + port + path, method, new HttpEntity<>(body, h), Object.class);
    }
}
