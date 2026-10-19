package com.traceability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.traceability.fulfillment.OrderController;
import com.traceability.identity.JwtService;
import com.traceability.integrations.bosta.*;
import com.traceability.integrations.bosta.BostaDeletionService.Confirmation;
import com.traceability.inventory.ExceptionService;
import com.traceability.security.EncryptionService;
import com.traceability.tenancy.TenantAwareDataSource;
import com.traceability.tenancy.TenantContext;
import org.jobrunr.scheduling.JobScheduler;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Deleted-in-Bosta slice 4 — confirm a deletion safely (BostaDeletionService) and what follows.
 *
 *   dl1  one 400 → suspected only, state unchanged, no confirmation
 *   dl2  two 400s ≥ 30 min apart + the key's positive control → closed: 'cancelled', provider_deleted_at,
 *        reason, NO history row, no piece writes; the order's stranded unlinked delivery gets matcher_version NULL
 *   dl3  two 400s but no successful lookup for that key in 30 min → not confirmed
 *   dl4  404 / 401 / 5xx / timeout on the second look → never an observation, never a confirmation; 401 pauses
 *   dl5  a suspect Bosta returns again → suspicion cleared, result ingested
 *   dl6  after closing: the order shows "Awaiting booking" (no shipment) and a NEW waybill opens, packs and
 *        completes in a waybill pack session (no history → hasEverShippedPastCreated stays false)
 *   dl7  Shopify fulfillment still carrying the dead tracking number → never re-linked, no Bosta call, no alert
 *   dl8  pieces awaiting_pickup → state kept, waybill_deleted_in_bosta HIGH; with_courier → CRITICAL
 *   dl9  return leg held by an open request → kept, provider_deleted_at only; with no request → closed
 *   dl10 cancelled_live_shipment: a suspicion alone doesn't clear it, the confirmation does
 *   dl11 generic leg untouched (never observed, never confirmed)
 *   dl12 the print path's re-check is an observation only — never confirms, even when it would qualify
 *   dl13 waybill mode: the dead waybill is refused "deleted in Bosta"; one that dies while open is set aside
 *        on the next piece scan, its pieces back on the shelf
 *   dl14 the dead waybill can't be linked again by scan (409)
 *   dl15 RLS: app_user with another tenant's context can't observe / clear / confirm this tenant's legs
 *
 * Production-shaped: bare numeric 10-digit tracking numbers (PackFixtures), packed pieces, real states.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BostaDeletionTest {

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
    }

    @LocalServerPort int port;
    @Autowired TestRestTemplate             rest;
    @Autowired JdbcTemplate                 jdbc;
    @Autowired JwtService                   jwt;
    @Autowired EncryptionService            encryption;
    @Autowired ObjectMapper                 mapper;
    @Autowired BostaDeletionService         deletion;
    @Autowired ExceptionService             exceptions;
    @Autowired BostaFulfillmentLinkService  fulfillmentLink;
    @Autowired org.springframework.transaction.PlatformTransactionManager txm;
    @Autowired com.traceability.inventory.FulfillService fulfillService;
    private OrderController orders;   // built like OrderShippingCarrierTest (no Spring Security proxy)
    @Autowired com.traceability.inventory.ShipmentLinkService shipmentLink;
    @MockBean  BostaGateway                 gateway;
    @MockBean  JobScheduler                 jobScheduler;

    static final String KEY = "bosta-key-test";

    @BeforeEach
    void resetBosta() {
        reset(gateway);
        orders = new OrderController(jdbc, mapper, txm, new com.traceability.fulfillment.OrderNotesService(jdbc), fulfillService);
    }

    /** A tenant with an active Bosta account (key = KEY), waybill pack mode, a packer and one variant. */
    final class Shop {
        final PackFixtures f;
        final UUID packer, variant;
        Shop(String name) {
            f = new PackFixtures(jdbc, name);
            jdbc.update("UPDATE tenants SET pick_pack_mode = 'waybill_scan' WHERE id = ?", f.tenant);
            jdbc.update("INSERT INTO courier_accounts (tenant_id, provider, api_key_encrypted, webhook_secret, status) " +
                        "VALUES (?, 'bosta', ?, ?, 'active')", f.tenant, encryption.encrypt(KEY), "wh-" + f.tenant);
            packer = f.user("Mona", "worker");
            variant = f.variant("Linen shirt", "LS-" + name, "https://cdn.shopify.com/s/files/shirt.jpg");
        }
        /** An order with one unit, its pieces on the shelf, a 'created' Bosta forward leg. */
        Order order(String number) {
            UUID id = f.order(number, 1);
            f.item(id, variant, 1);
            String tn = f.forward(id);
            String piece = f.piece(variant, packer);
            return new Order(id, number, tn, shipment(tn), piece);
        }
        void keyOk(int minutesAgo) {
            jdbc.update("UPDATE courier_accounts SET lookup_ok_at = now() - (? * INTERVAL '1 minute') WHERE tenant_id = ?",
                minutesAgo, f.tenant);
        }
    }

    record Order(UUID id, String number, String tracking, UUID shipment, String piece) {}

    // ── the confirmation rule ─────────────────────────────────────────────────

    @Test
    void dl1_oneNotFound_suspectedOnly() {
        Shop s = new Shop("DL1");
        Order o = s.order("#4101");
        s.keyOk(1);
        Confirmation c = as(s, () -> { deletion.observeNotFound(s.f.tenant, o.shipment()); return deletion.tryConfirm(s.f.tenant, o.shipment()); });
        assertThat(c).isEqualTo(Confirmation.NOT_READY);
        Map<String, Object> row = leg(o.shipment());
        assertThat(row.get("internal_state")).isEqualTo("created");
        assertThat(row.get("provider_not_found_at")).isNotNull();
        assertThat(row.get("not_found_count")).isEqualTo(1);
        assertThat(row.get("provider_deleted_at")).isNull();
    }

    @Test
    void dl2_twoNotFound30MinApart_withPositiveControl_closed_noHistory_unlinkedRetried() {
        Shop s = new Shop("DL2");
        Order o = s.order("#4102");
        suspect(o.shipment(), 31);
        s.keyOk(2);
        long unlinked = unlinked(s, "#4102", "9" + o.tracking().substring(1));
        long otherUnlinked = unlinked(s, "#9999", "8" + o.tracking().substring(1));
        when(gateway.fetchDelivery(eq(KEY), eq(o.tracking()))).thenThrow(new DeliveryNotFoundException(o.tracking()));

        int looked = as(s, () -> deletion.recheckSuspects(s.f.tenant, KEY));

        assertThat(looked).isEqualTo(1);
        Map<String, Object> row = leg(o.shipment());
        assertThat(row.get("internal_state")).isEqualTo("cancelled");
        assertThat(row.get("provider_deleted_at")).isNotNull();
        assertThat(row.get("provider_deleted_reason")).isEqualTo("deleted_in_bosta");
        assertThat(row.get("not_found_count")).isEqualTo(2);
        assertThat(count("SELECT COUNT(*) FROM shipment_status_history WHERE shipment_id = ?", o.shipment())).isZero();
        assertThat(count("SELECT COUNT(*) FROM piece_events WHERE piece_id = ? AND event_type <> 'received'", s.f.pieceId(o.piece()))).isZero();
        assertThat(jdbc.queryForObject("SELECT matcher_version FROM unlinked_bosta_deliveries WHERE id = ?", String.class, unlinked)).isNull();
        assertThat(jdbc.queryForObject("SELECT matcher_version FROM unlinked_bosta_deliveries WHERE id = ?", String.class, otherUnlinked))
            .isEqualTo("v-test");
    }

    @Test
    void dl3_noSuccessfulLookupForTheKeyIn30Min_notConfirmed() {
        Shop s = new Shop("DL3");
        Order o = s.order("#4103");
        suspect(o.shipment(), 31);
        s.keyOk(31);                                       // last success too old — the key isn't proven working
        when(gateway.fetchDelivery(eq(KEY), eq(o.tracking()))).thenThrow(new DeliveryNotFoundException(o.tracking()));

        as(s, () -> deletion.recheckSuspects(s.f.tenant, KEY));

        Map<String, Object> row = leg(o.shipment());
        assertThat(row.get("not_found_count")).as("still observed").isEqualTo(2);
        assertThat(row.get("internal_state")).isEqualTo("created");
        assertThat(row.get("provider_deleted_at")).isNull();
        assertThat(as(s, () -> deletion.tryConfirm(s.f.tenant, o.shipment()))).isEqualTo(Confirmation.NO_POSITIVE_CONTROL);
    }

    @Test
    void dl4_404_401_5xx_timeout_neverConfirm_401Pauses() {
        Shop s = new Shop("DL4");
        Order o = s.order("#4104");
        RuntimeException[] inconclusive = {
            null,                                                                   // 404 → null delivery
            new BostaTransientException("Bosta 5xx fetching delivery " + o.tracking(), null),
            new BostaTransientException("Network error fetching delivery " + o.tracking(), null),
            new BostaAuthException("Bosta API error (401 UNAUTHORIZED): no", 401, null),
        };
        for (RuntimeException e : inconclusive) {
            suspect(o.shipment(), 31);
            s.keyOk(2);
            reset(gateway);
            if (e == null) when(gateway.fetchDelivery(eq(KEY), eq(o.tracking()))).thenReturn(null);
            else when(gateway.fetchDelivery(eq(KEY), eq(o.tracking()))).thenThrow(e);
            as(s, () -> deletion.recheckSuspects(s.f.tenant, KEY));
            Map<String, Object> row = leg(o.shipment());
            assertThat(row.get("not_found_count")).as(String.valueOf(e)).isEqualTo(1);
            assertThat(row.get("internal_state")).as(String.valueOf(e)).isEqualTo("created");
            assertThat(row.get("provider_deleted_at")).as(String.valueOf(e)).isNull();
        }
        // The 401 paused confirmation: even a second 400 now (key looked fine 2 min ago) doesn't confirm.
        assertThat(jdbc.queryForObject("SELECT lookup_auth_failed_at FROM courier_accounts WHERE tenant_id = ?",
            java.sql.Timestamp.class, s.f.tenant)).isNotNull();
        suspect(o.shipment(), 31);
        jdbc.update("UPDATE shipments SET not_found_count = 2, last_not_found_at = now() WHERE id = ?", o.shipment());
        s.keyOk(1);
        assertThat(as(s, () -> deletion.tryConfirm(s.f.tenant, o.shipment()))).isEqualTo(Confirmation.AUTH_PAUSED);
        assertThat(leg(o.shipment()).get("provider_deleted_at")).isNull();
    }

    @Test
    void dl5_suspectReappears_suspicionCleared() {
        Shop s = new Shop("DL5");
        Order o = s.order("#4105");
        suspect(o.shipment(), 31);
        when(gateway.fetchDelivery(eq(KEY), eq(o.tracking())))
            .thenReturn(new BostaDelivery(o.tracking(), 10, "SEND", 0, "#4105", null, mapper.createObjectNode()));

        as(s, () -> deletion.recheckSuspects(s.f.tenant, KEY));

        Map<String, Object> row = leg(o.shipment());
        assertThat(row.get("provider_not_found_at")).isNull();
        assertThat(row.get("last_not_found_at")).isNull();
        assertThat(row.get("not_found_count")).isEqualTo(0);
        assertThat(row.get("provider_deleted_at")).isNull();
        assertThat(jdbc.queryForObject("SELECT lookup_ok_at FROM courier_accounts WHERE tenant_id = ?",
            java.sql.Timestamp.class, s.f.tenant)).as("a found delivery is the key's positive control").isNotNull();
    }

    // ── after closing ─────────────────────────────────────────────────────────

    @Test
    void dl6_afterClosing_awaitingBooking_thenANewWaybillPacksAndCompletes() {
        Shop s = new Shop("DL6");
        Order o = s.order("#4106");
        UUID twin = s.f.order("#4116", 1);                  // same order, never had a shipment
        s.f.item(twin, s.variant, 1);
        closeConfirmed(s, o);

        var detail = TenantContext.runAs(s.f.tenant, () -> orders.detail(o.id()));
        var twinDetail = TenantContext.runAs(s.f.tenant, () -> orders.detail(twin));
        assertThat(detail.shippingBadge().state()).isEqualTo("awaiting_booking");
        assertThat(detail.derivedStatus()).as("a provider-deleted leg is no shipment").isEqualTo(twinDetail.derivedStatus());
        var listed = TenantContext.runAs(s.f.tenant, () -> orders.list(null, null, null, null, 0, 100).items())
            .stream().filter(r -> r.id().equals(o.id().toString())).findFirst().orElseThrow();
        assertThat(listed.shippingBadge().state()).isEqualTo("awaiting_booking");
        assertThat(listed.derivedStatus()).isEqualTo(twinDetail.derivedStatus());

        // Bosta's new delivery for the order is linked (auto-match / Shopify / manual all insert this leg).
        String fresh = s.f.forward(o.id());
        String token = jwt.issueAccessToken(s.packer, s.f.tenant, "worker");
        UUID session = UUID.fromString((String) pack(token, HttpMethod.POST, "", null).getBody().get("id"));
        Map<String, Object> opened = pack(token, HttpMethod.POST, "/" + session + "/waybill", Map.of("code", fresh)).getBody();
        assertThat(opened.get("result")).isEqualTo("opened");
        Map<String, Object> done = pack(token, HttpMethod.POST, "/" + session + "/orders/" + o.id() + "/scan",
            Map.of("code", o.piece())).getBody();
        assertThat(done.get("status")).as(String.valueOf(done)).isEqualTo("completed");
        assertThat(jdbc.queryForObject("SELECT status::text FROM orders WHERE id = ?", String.class, o.id())).isEqualTo("awaiting_pickup");
        assertThat(count("SELECT COUNT(*) FROM shipment_status_history h JOIN shipments sh ON sh.id = h.shipment_id " +
            "WHERE sh.order_id = ? AND h.internal_state <> 'created'", o.id())).as("hasEverShippedPastCreated stays false").isZero();
    }

    @Test
    void dl7_shopifyFulfillmentWithTheDeadTracking_neverRelinked_noAlert() {
        Shop s = new Shop("DL7");
        Order o = s.order("#4107");
        closeConfirmed(s, o);
        jdbc.update("INSERT INTO order_fulfillment_tracking (tenant_id, order_id, tracking_number, carrier_raw, carrier_class, fulfillment_status) " +
                    "VALUES (?, ?, ?, 'Bosta', 'bosta', 'success')", s.f.tenant, o.id(), o.tracking());

        BostaFulfillmentLinkService.Result r = fulfillmentLink.attempt(s.f.tenant, o.id(), o.tracking(), false);

        assertThat(r.verdict()).isEqualTo(BostaFulfillmentLinkService.Verdict.SKIP);
        assertThat(r.reason()).contains("deleted in Bosta");
        verify(gateway, never()).fetchDelivery(anyString(), anyString());
        assertThat(jdbc.queryForObject("SELECT link_status FROM order_fulfillment_tracking WHERE order_id = ?", String.class, o.id()))
            .isEqualTo("skipped");
        assertThat(leg(o.shipment()).get("internal_state")).isEqualTo("cancelled");
        assertThat(alerts(s, "fulfillment_link_problem")).isEmpty();
    }

    @Test
    void dl8_piecesAwaitingPickup_stateKept_alertHigh_withCourierCritical() {
        Shop s = new Shop("DL8");
        Order o = s.order("#4108");
        String pieceId = s.f.pieceId(o.piece());
        jdbc.update("UPDATE pieces SET status = 'awaiting_pickup' WHERE id = ?", pieceId);
        jdbc.update("INSERT INTO allocations (piece_id, order_item_id, tenant_id, status) " +
                    "SELECT ?, oi.id, oi.tenant_id, 'packed' FROM order_items oi WHERE oi.order_id = ?", pieceId, o.id());
        assertThat(confirmNow(s, o)).isEqualTo(Confirmation.KEPT_PIECES_OUT);
        Map<String, Object> row = leg(o.shipment());
        assertThat(row.get("internal_state")).isEqualTo("created");
        assertThat(row.get("provider_deleted_at")).isNotNull();
        List<Map<String, Object>> a = alerts(s, "waybill_deleted_in_bosta");
        assertThat(a).hasSize(1);
        assertThat(a.get(0).get("severity")).isEqualTo("HIGH");
        assertThat((String) a.get(0).get("pieces")).contains("awaiting_pickup");

        jdbc.update("UPDATE pieces SET status = 'with_courier' WHERE id = ?", pieceId);
        assertThat(alerts(s, "waybill_deleted_in_bosta").get(0).get("severity")).isEqualTo("CRITICAL");
    }

    @Test
    void dl9_returnLeg_openRequestKeepsIt_noRequestClosesIt() {
        Shop s = new Shop("DL9");
        Order o = s.order("#4109");
        String heldTn = s.f.leg(o.id(), "return", "created", null);
        UUID held = shipment(heldTn);
        jdbc.update("INSERT INTO return_requests (tenant_id, order_id, status, reference, return_shipment_id) " +
                    "VALUES (?, ?, 'pickup_booked', 'RR-DL9A', ?)", s.f.tenant, o.id(), held);
        Order o2 = s.order("#4119");
        UUID free = shipment(s.f.leg(o2.id(), "return", "created", null));

        assertThat(confirmNow(s, held)).isEqualTo(Confirmation.KEPT_RETURN_HELD);
        assertThat(leg(held).get("internal_state")).isEqualTo("created");
        assertThat(leg(held).get("provider_deleted_at")).isNotNull();

        assertThat(confirmNow(s, free)).isEqualTo(Confirmation.CLOSED);
        assertThat(leg(free).get("internal_state")).isEqualTo("cancelled");
    }

    @Test
    void dl10_cancelledLiveShipment_suspicionDoesntClear_confirmationDoes() {
        Shop s = new Shop("DL10");
        Order o = s.order("#4110");
        jdbc.update("UPDATE orders SET status = 'cancelled', cancel_requested_at = now() WHERE id = ?", o.id());
        assertThat(alerts(s, "cancelled_live_shipment")).hasSize(1);
        suspect(o.shipment(), 31);
        assertThat(alerts(s, "cancelled_live_shipment")).as("suspected is not confirmed").hasSize(1);
        assertThat(confirmNow(s, o)).isEqualTo(Confirmation.CLOSED);
        assertThat(alerts(s, "cancelled_live_shipment")).isEmpty();
    }

    @Test
    void dl11_genericLeg_untouched() {
        Shop s = new Shop("DL11");
        UUID order = s.f.order("#4111", 1);
        UUID generic = jdbc.queryForObject(
            "INSERT INTO shipments (tenant_id, order_id, provider, tracking_number, tracking_key, carrier_name, source, internal_state) " +
            "VALUES (?, ?, 'generic', 'WJ7730012', 'WJ7730012', 'Wijha', 'merchant_entered', 'created') RETURNING id",
            UUID.class, s.f.tenant, order);
        s.keyOk(1);
        Confirmation c = as(s, () -> { deletion.observeNotFound(s.f.tenant, generic); deletion.observeNotFound(s.f.tenant, generic);
            return deletion.tryConfirm(s.f.tenant, generic); });
        assertThat(c).isEqualTo(Confirmation.NOT_BOSTA);
        Map<String, Object> row = leg(generic);
        assertThat(row.get("provider_not_found_at")).isNull();
        assertThat(row.get("not_found_count")).isEqualTo(0);
        assertThat(row.get("internal_state")).isEqualTo("created");
    }

    @Test
    void dl12_printRecheck_isAnObservationOnly_neverConfirms() {
        Shop s = new Shop("DL12");
        Order o = s.order("#4112");
        suspect(o.shipment(), 31);
        s.keyOk(1);
        when(gateway.fetchDelivery(eq(KEY), eq(o.tracking()))).thenThrow(new DeliveryNotFoundException(o.tracking()));

        deletion.recheckFromPrint(s.f.tenant, KEY, List.of(o.tracking()));      // no tenant context, like the print

        Map<String, Object> row = leg(o.shipment());
        assertThat(row.get("not_found_count")).isEqualTo(2);
        assertThat(row.get("internal_state")).isEqualTo("created");
        assertThat(row.get("provider_deleted_at")).as("qualifies, yet never confirmed by the print path").isNull();
    }

    // ── waybill pack mode ─────────────────────────────────────────────────────

    @Test
    void dl13_waybillMode_deadWaybillRefused_andOneThatDiesWhileOpenIsSetAside() {
        Shop s = new Shop("DL13");
        String token = jwt.issueAccessToken(s.packer, s.f.tenant, "worker");
        UUID session = UUID.fromString((String) pack(token, HttpMethod.POST, "", null).getBody().get("id"));

        Order dead = s.order("#4113");
        closeConfirmed(s, dead);
        Map<String, Object> refused = pack(token, HttpMethod.POST, "/" + session + "/waybill", Map.of("code", dead.tracking())).getBody();
        assertThat(refused.get("result")).isEqualTo("rejected");
        assertThat(refused.get("code")).isEqualTo("NOT_PACKABLE");
        assertThat(refused.get("subReason")).isEqualTo("DELETED_IN_BOSTA");
        assertThat((String) refused.get("messageEn")).contains("deleted in Bosta");

        UUID variant2 = s.f.variant("Wide trousers", "WT-DL13", "https://cdn.shopify.com/s/files/trousers.jpg");
        UUID live = s.f.order("#4123", 1);
        s.f.item(live, variant2, 2);
        String tn = s.f.forward(live);
        String p1 = s.f.piece(variant2, s.packer), p2 = s.f.piece(variant2, s.packer);
        assertThat(pack(token, HttpMethod.POST, "/" + session + "/waybill", Map.of("code", tn)).getBody().get("result")).isEqualTo("opened");
        assertThat(pack(token, HttpMethod.POST, "/" + session + "/orders/" + live + "/scan", Map.of("code", p1)).getBody().get("status"))
            .isEqualTo("scanned");
        // Confirmed deleted while the packer works on it (kept: a piece is reserved, none out yet → closed).
        jdbc.update("UPDATE shipments SET provider_deleted_at = now(), provider_deleted_reason = 'deleted_in_bosta' WHERE tracking_number = ?", tn);

        Map<String, Object> r = pack(token, HttpMethod.POST, "/" + session + "/orders/" + live + "/scan", Map.of("code", p2)).getBody();
        assertThat(r.get("status")).isEqualTo("rejected");
        assertThat(((Map<?, ?>) r.get("scan")).get("code")).isEqualTo("WAYBILL_DELETED_IN_BOSTA");
        assertThat(r.get("order")).as("the order is no longer open").isNull();
        assertThat(jdbc.queryForObject("SELECT reason FROM pack_session_orders WHERE order_id = ? AND outcome = 'set_aside'",
            String.class, live)).isEqualTo("deleted_in_bosta");
        assertThat(jdbc.queryForList("SELECT status::text FROM pieces WHERE short_code IN (?, ?) AND tenant_id = ?",
            String.class, p1, p2, s.f.tenant))
            .containsOnly("available");
    }

    @Test
    void dl14_deadWaybillCantBeLinkedAgainByScan() {
        Shop s = new Shop("DL14");
        Order o = s.order("#4114");
        closeConfirmed(s, o);
        // Packed and waiting for its AWB: without the guard, the swapped-label check hands back the dead leg
        // as "already this order's" and the link completes on it.
        String pieceId = s.f.pieceId(o.piece());
        jdbc.update("UPDATE pieces SET status = 'packed' WHERE id = ?", pieceId);
        jdbc.update("INSERT INTO allocations (piece_id, order_item_id, tenant_id, status) " +
                    "SELECT ?, oi.id, oi.tenant_id, 'packed' FROM order_items oi WHERE oi.order_id = ?", pieceId, o.id());
        jdbc.update("UPDATE orders SET status = 'packed' WHERE id = ?", o.id());
        var e = org.junit.jupiter.api.Assertions.assertThrows(org.springframework.web.server.ResponseStatusException.class,
            () -> TenantContext.runAs(s.f.tenant, () -> shipmentLink.linkByAwbScan(o.id(), o.tracking(), s.packer)));
        assertThat(e.getStatusCode().value()).isEqualTo(409);
        assertThat(e.getReason()).contains("deleted in Bosta");
        assertThat(count("SELECT COUNT(*) FROM shipments WHERE order_id = ?", o.id())).isEqualTo(1);
        assertThat(leg(o.shipment()).get("internal_state")).isEqualTo("cancelled");
        assertThat(jdbc.queryForObject("SELECT status::text FROM pieces WHERE id = ?", String.class, pieceId)).isEqualTo("packed");
    }

    @Test
    void dl15_rls_appUserWithAnotherTenantsContext_cantTouchThisTenantsLeg() {
        Shop a = new Shop("DL15A");
        Shop b = new Shop("DL15B");
        Order o = a.order("#4115");
        suspect(o.shipment(), 31);
        a.keyOk(1);
        DataSource appDs = new TenantAwareDataSource(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "app_user", "testpw"));
        JdbcTemplate appJdbc = new JdbcTemplate(appDs);
        BostaDeletionService asAppUser = new BostaDeletionService(appJdbc, new DataSourceTransactionManager(appDs),
            gateway, null, 20);

        Confirmation c = TenantContext.runAs(b.f.tenant, () -> {
            asAppUser.observeNotFound(a.f.tenant, o.shipment());
            asAppUser.clearSuspicion(a.f.tenant, o.shipment());
            return asAppUser.tryConfirm(a.f.tenant, o.shipment());
        });
        assertThat(c).isEqualTo(Confirmation.NOT_BOSTA);              // RLS: the row isn't visible at all
        Map<String, Object> row = leg(o.shipment());
        assertThat(row.get("not_found_count")).isEqualTo(1);
        assertThat(row.get("provider_not_found_at")).isNotNull();
        assertThat(row.get("provider_deleted_at")).isNull();

        // Its own tenant, as app_user: the same calls work (the columns are writable under RLS).
        jdbc.update("UPDATE shipments SET not_found_count = 2, last_not_found_at = now() WHERE id = ?", o.shipment());
        assertThat(TenantContext.runAs(a.f.tenant, () -> asAppUser.tryConfirm(a.f.tenant, o.shipment())))
            .isEqualTo(Confirmation.CLOSED);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private <T> T as(Shop s, java.util.concurrent.Callable<T> c) { return TenantContext.runAs(s.f.tenant, c); }

    private UUID shipment(String tn) {
        return jdbc.queryForObject("SELECT id FROM shipments WHERE tracking_number = ?", UUID.class, tn);
    }

    private Map<String, Object> leg(UUID shipment) {
        return jdbc.queryForMap("SELECT internal_state::text AS internal_state, provider_not_found_at, last_not_found_at, " +
            "not_found_count, provider_deleted_at, provider_deleted_reason FROM shipments WHERE id = ?", shipment);
    }

    /** One 400 seen {@code minutesAgo} minutes ago, not looked at since. */
    private void suspect(UUID shipment, int minutesAgo) {
        jdbc.update("UPDATE shipments SET provider_not_found_at = now() - (? * INTERVAL '1 minute'), " +
            "last_not_found_at = now() - (? * INTERVAL '1 minute'), not_found_count = 1, " +
            "last_polled_at = now() - (? * INTERVAL '1 minute'), provider_deleted_at = NULL, provider_deleted_reason = NULL " +
            "WHERE id = ?", minutesAgo, minutesAgo, minutesAgo, shipment);
    }

    /** Ready to confirm now: two 400s 31 min apart, the key proven a minute ago. */
    private Confirmation confirmNow(Shop s, UUID shipment) {
        suspect(shipment, 31);
        jdbc.update("UPDATE shipments SET not_found_count = 2, last_not_found_at = now() WHERE id = ?", shipment);
        s.keyOk(1);
        return as(s, () -> deletion.tryConfirm(s.f.tenant, shipment));
    }

    private Confirmation confirmNow(Shop s, Order o) { return confirmNow(s, o.shipment()); }

    private void closeConfirmed(Shop s, Order o) {
        assertThat(confirmNow(s, o)).isEqualTo(Confirmation.CLOSED);
    }

    private long unlinked(Shop s, String reference, String tracking) {
        return jdbc.queryForObject(
            "INSERT INTO unlinked_bosta_deliveries (tenant_id, tracking_number, business_reference, bosta_state_code, " +
            "bosta_order_type, matcher_version) VALUES (?, ?, ?, 10, 'SEND', 'v-test') RETURNING id",
            Long.class, s.f.tenant, tracking, reference);
    }

    private List<Map<String, Object>> alerts(Shop s, String type) {
        return TenantContext.runAs(s.f.tenant, () -> exceptions.detectAllOpen()).stream()
            .filter(e -> type.equals(e.get("type"))).toList();
    }

    private int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    @SuppressWarnings("rawtypes")
    private ResponseEntity<Map> pack(String token, HttpMethod method, String path, Object body) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        h.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange("http://localhost:" + port + "/api/v1/pack-sessions" + path, method, new HttpEntity<>(body, h), Map.class);
    }
}
