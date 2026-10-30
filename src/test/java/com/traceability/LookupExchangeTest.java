package com.traceability;

import com.traceability.identity.JwtService;
import com.traceability.integrations.bosta.BostaV2Client;
import com.traceability.integrations.shopify.ShopifyGateway;
import com.traceability.integrations.shopify.ShopifyTokenProvider;
import com.traceability.inventory.*;
import com.traceability.security.EncryptionService;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * C2 Lookup Exchange (V193, approved 2026-10-28) — an owner / manager sends an available piece as an exchange
 * replacement from Lookup, on a store that ships with its own courier and has no Bosta account:
 *   le1  create without tracking — merchant request (origin lookup, approved, manual), replacement order 'confirmed',
 *        piece reserved; never pickable (queue, scan NOT_PICKABLE, complete 409); nothing sent to Shopify
 *   le2  tracking added later from the drawer → leg with_courier (lookup_dispatch), piece reserved → packed →
 *        with_courier, Shopify −1 exactly once; a repeat is refused and changes nothing; Pickups / pick refuse it
 *   le3  tracking in the dialog → handed over at once, −1 once; a return waybill equal to the outbound one rolls
 *        the whole exchange back
 *   le4  cancel before it leaves → piece available, order cancelled, request closed exchange_failed, no Shopify;
 *        cancel after it left → 409 REPLACEMENT_LEFT
 *   le5  "Release from order" refuses a piece reserved to a Lookup exchange
 *   le6  gate: no generic courier / an active Bosta account → 409, Lookup shows no Exchange; the portal's
 *        other-couriers switch is NOT needed
 *   le7  refusals: not at the main warehouse, untagged, not available, a portal pre-connect order (404 / ORDER_NOT_FOUND)
 *   le8  untracked unit coming back: listed one row per unit; Arrived → exchanged; outbound tracking still accepted
 *        after 'exchanged' (Lookup only) — a portal exchange in 'exchanged' is refused as before
 *   le9  order search leaves internal orders out; the drawer's lookupExchange block
 *   le10 Orders' Cancel order refuses a Lookup exchange order (names the request); a normal order still cancels
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LookupExchangeTest {

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
        r.add("analytics.settlement.refresh-enabled", () -> "false");
    }

    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;
    @Autowired JwtService jwt;
    @Autowired JdbcTemplate jdbc;
    @Autowired EncryptionService encryption;
    @Autowired FulfillService fulfill;
    @Autowired PickupSessionService pickups;
    @MockBean  ShopifyGateway shopifyGateway;
    @MockBean  ShopifyTokenProvider tokenProvider;
    @MockBean  JobScheduler jobScheduler;
    @MockBean  BostaV2Client bostaV2;

    static final AtomicInteger SEQ = new AtomicInteger();

    record T(UUID id, UUID store, UUID location, UUID other, UUID m, UUID l, UUID owner, UUID worker,
             String ownerToken, String workerToken) {}
    record O(UUID id, String number, String piece) {}

    @BeforeEach
    void stubs() {
        reset(shopifyGateway, tokenProvider, bostaV2);
        when(tokenProvider.getValidToken(any())).thenReturn("shpat-c2");
        when(shopifyGateway.resolveInventoryItemId(anyString(), anyString(), anyString())).thenReturn("gid://shopify/InventoryItem/9");
    }

    @AfterEach void clear() { TenantContext.clear(); }

    // ── le1 ──────────────────────────────────────────────────────────────────────────────────────

    @Test
    void le1_createWithoutTracking_reservedAndNeverPickable() throws Exception {
        T t = tenant();
        O o = delivered(t, "#9101");
        String spare = stock(t, t.l(), t.location());

        ResponseEntity<Object> r = create(t, spare, body(o, pieceId(o.piece()), null));
        assertThat(r.getStatusCode().value()).as(String.valueOf(r.getBody())).isEqualTo(201);
        Map<?, ?> created = (Map<?, ?>) r.getBody();
        assertThat((String) created.get("reference")).startsWith("RR-");
        assertThat((String) created.get("replacementOrderNumber")).startsWith("EXC-RR-");
        assertThat(created.get("dispatched")).isEqualTo(false);
        UUID rr = UUID.fromString((String) created.get("requestId"));

        Map<String, Object> row = row(rr);
        assertThat(row).containsEntry("origin", "lookup").containsEntry("type", "exchange")
            .containsEntry("status", "approved").containsEntry("booking_status", "manual")
            .containsEntry("manual_carrier_name", "QP Express");
        assertThat(jdbc.queryForMap("SELECT piece_id, reason_code, replacement_variant_id, item_status FROM return_request_items " +
            "WHERE request_id = ?", rr)).containsEntry("piece_id", pieceId(o.piece())).containsEntry("reason_code", "other")
            .containsEntry("replacement_variant_id", t.l()).containsEntry("item_status", "awaiting");

        UUID replacement = (UUID) row.get("replacement_order_id");
        assertThat(jdbc.queryForMap("SELECT external_id, status::text AS status FROM orders WHERE id = ?", replacement))
            .containsEntry("external_id", "internal:exchange:rr:" + rr).containsEntry("status", "confirmed");
        assertThat(pieceStatus(spare)).isEqualTo("reserved");
        assertThat(jdbc.queryForObject("SELECT a.status::text FROM allocations a JOIN order_items oi ON oi.id = a.order_item_id " +
            "WHERE oi.order_id = ? AND a.piece_id = ?", String.class, replacement, pieceId(spare))).isEqualTo("active");

        assertThat(queueText(t)).doesNotContain((String) created.get("replacementOrderNumber"));
        TenantContext.set(t.id());
        FulfillService.ScanResult scan = fulfill.scan(replacement, spare, t.worker());
        assertThat(scan.success()).isFalse();
        assertThat(scan.code()).isEqualTo("NOT_PICKABLE");
        assertThatThrownBy(() -> fulfill.complete(replacement, t.worker()))
            .isInstanceOf(ResponseStatusException.class).hasMessageContaining("Lookup");
        TenantContext.clear();

        settle();
        assertThat(claims(spare)).isEmpty();
        verify(shopifyGateway, never()).pushExchangeDispatch(any(), any(), any(), any(), anyInt(), any(), any());
        assertThat(eventTypes(rr)).containsExactly("requested", "approved");
    }

    // ── le2 ──────────────────────────────────────────────────────────────────────────────────────

    @Test
    void le2_trackingLaterFromDrawer_handsOverAndDecrementsOnce() throws Exception {
        T t = tenant();
        O o = delivered(t, "#9102");
        String spare = stock(t, t.l(), t.location());
        UUID rr = createdId(create(t, spare, body(o, pieceId(o.piece()), null)));
        UUID replacement = (UUID) row(rr).get("replacement_order_id");

        Map<?, ?> drawer = drawer(t, rr);
        Map<?, ?> lookup = lookupBlock(drawer);
        assertThat(lookup.get("pieceStatus")).isEqualTo("reserved");
        assertThat(lookup.get("left")).isEqualTo(false);
        assertThat(lookup.get("canCancel")).isEqualTo(true);

        ResponseEntity<Object> booked = post(t.ownerToken(), "/api/v1/return-requests/" + rr + "/manual-courier",
            Map.of("outboundTrackingNumber", "QP-OUT-9102"));
        assertThat(booked.getStatusCode().value()).as(String.valueOf(booked.getBody())).isEqualTo(200);
        assertThat(String.valueOf(booked.getBody())).contains("OUTBOUND_CREATED");

        Map<String, Object> leg = jdbc.queryForMap("SELECT id, internal_state::text AS state, handed_over_at FROM shipments " +
            "WHERE order_id = ? AND shipment_leg = 'forward'", replacement);
        assertThat(leg.get("state")).isEqualTo("with_courier");
        assertThat(leg.get("handed_over_at")).isNotNull();
        assertThat(jdbc.queryForList("SELECT internal_state::text || ':' || source FROM shipment_status_history " +
            "WHERE shipment_id = ?", String.class, leg.get("id"))).containsExactly("with_courier:lookup_dispatch");
        assertThat(pieceStatus(spare)).isEqualTo("with_courier");
        assertThat(jdbc.queryForList("SELECT event_type || ':' || from_status || '>' || to_status FROM piece_events " +
            "WHERE piece_id = ? AND event_type <> 'received' ORDER BY occurred_at, id", String.class, pieceId(spare)))
            .containsExactly("scan:available>reserved", "pack:reserved>packed", "handed_to_courier:packed>with_courier");
        assertThat(jdbc.queryForObject("SELECT status::text FROM orders WHERE id = ?", String.class, replacement)).isEqualTo("with_courier");
        assertThat(row(rr)).containsEntry("status", "pickup_booked");
        assertThat(eventTypes(rr)).contains("replacement_booked", "pickup_booked", "replacement_dispatched");

        awaitDispatch(spare);
        assertDecrementedOnce(spare);

        // a repeat changes nothing — the leg exists and the piece already left
        ResponseEntity<Object> again = post(t.ownerToken(), "/api/v1/return-requests/" + rr + "/manual-courier",
            Map.of("outboundTrackingNumber", "QP-OUT-9102-B"));
        assertThat(again.getStatusCode().value()).isEqualTo(409);
        assertThat(String.valueOf(again.getBody())).contains("OUTBOUND_ALREADY_SET");
        TenantContext.set(t.id());
        assertThat(fulfill.scan(replacement, spare, t.worker()).code()).isEqualTo("ALREADY_SHIPPED");
        UUID pickup = pickups.openSession(t.id(), t.worker(), LocalDate.now(), null, null);
        assertThat(pickups.scan(t.id(), pickup, t.worker(), "QP-OUT-9102").outcome())
            .isNotEqualTo(PickupSessionService.ScanOutcome.ACCEPTED);
        TenantContext.clear();
        settle();
        assertDecrementedOnce(spare);

        Map<?, ?> after = lookupBlock(drawer(t, rr));
        assertThat(after.get("left")).isEqualTo(true);
        assertThat(after.get("canCancel")).isEqualTo(false);
    }

    // ── le3 ──────────────────────────────────────────────────────────────────────────────────────

    @Test
    void le3_trackingInDialog_dispatchedAtOnce_andSameAsOutboundRollsBack() throws Exception {
        T t = tenant();
        O o = delivered(t, "#9103");
        String spare = stock(t, t.l(), t.location());

        Map<String, Object> bad = body(o, pieceId(o.piece()), null);
        bad.put("outboundTrackingNumber", "OUT-9103");
        bad.put("returnTrackingNumber", "out 9103");
        ResponseEntity<Object> refused = create(t, spare, bad);
        assertThat(refused.getStatusCode().value()).isEqualTo(409);
        assertThat(String.valueOf(refused.getBody())).contains("SAME_AS_OUTBOUND");
        assertThat(pieceStatus(spare)).as("all or nothing").isEqualTo("available");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM return_requests WHERE order_id = ?", Integer.class, o.id())).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM orders WHERE tenant_id = ? AND external_id LIKE 'internal:exchange:%'",
            Integer.class, t.id())).isZero();

        Map<String, Object> ok = body(o, pieceId(o.piece()), "wrong_size");
        ok.put("carrier", "Bosta-free Express");
        ok.put("outboundTrackingNumber", "OUT-9103");
        ok.put("returnTrackingNumber", "QP-RET-9103");
        ResponseEntity<Object> r = create(t, spare, ok);
        assertThat(r.getStatusCode().value()).as(String.valueOf(r.getBody())).isEqualTo(201);
        assertThat(((Map<?, ?>) r.getBody()).get("dispatched")).isEqualTo(true);
        UUID rr = createdId(r);
        assertThat(row(rr)).containsEntry("status", "pickup_booked").containsEntry("manual_carrier_name", "Bosta-free Express");
        assertThat(row(rr).get("return_shipment_id")).isNotNull();
        assertThat(jdbc.queryForObject("SELECT reason_code FROM return_request_items WHERE request_id = ?", String.class, rr))
            .isEqualTo("wrong_size");
        assertThat(pieceStatus(spare)).isEqualTo("with_courier");
        awaitDispatch(spare);
        assertDecrementedOnce(spare);
    }

    // ── le4 ──────────────────────────────────────────────────────────────────────────────────────

    @Test
    void le4_cancelBeforeItLeaves_andRefusedAfter() throws Exception {
        T t = tenant();
        O o = delivered(t, "#9104");
        String spare = stock(t, t.l(), t.location());
        UUID rr = createdId(create(t, spare, body(o, pieceId(o.piece()), null)));
        UUID replacement = (UUID) row(rr).get("replacement_order_id");

        assertThat(post(t.workerToken(), "/api/v1/return-requests/" + rr + "/cancel-exchange", null).getStatusCode().value())
            .isEqualTo(403);
        assertThat(post(t.ownerToken(), "/api/v1/return-requests/" + rr + "/cancel-exchange", null).getStatusCode().value())
            .isEqualTo(204);
        assertThat(pieceStatus(spare)).isEqualTo("available");
        assertThat(jdbc.queryForObject("SELECT a.status::text FROM allocations a WHERE a.piece_id = ?", String.class, pieceId(spare)))
            .isEqualTo("released");
        assertThat(jdbc.queryForObject("SELECT status::text FROM orders WHERE id = ?", String.class, replacement)).isEqualTo("cancelled");
        assertThat(row(rr)).containsEntry("status", "closed");
        assertThat(jdbc.queryForObject("SELECT close_reason FROM return_requests WHERE id = ?", String.class, rr))
            .isEqualTo("exchange_failed");
        assertThat(jdbc.queryForObject("SELECT active FROM return_request_items WHERE request_id = ?", Boolean.class, rr)).isFalse();
        assertThat(eventTypes(rr)).contains("exchange_cancelled", "closed");
        assertThat(post(t.ownerToken(), "/api/v1/return-requests/" + rr + "/cancel-exchange", null).getStatusCode().value())
            .isEqualTo(409);
        // the original's piece is free for another exchange again
        assertThat(createdId(create(t, spare, body(o, pieceId(o.piece()), null)))).isNotNull();

        O o2 = delivered(t, "#9105");
        String spare2 = stock(t, t.l(), t.location());
        Map<String, Object> withTracking = body(o2, pieceId(o2.piece()), null);
        withTracking.put("outboundTrackingNumber", "OUT-9105");
        UUID left = createdId(create(t, spare2, withTracking));
        awaitDispatch(spare2);
        ResponseEntity<Object> late = post(t.ownerToken(), "/api/v1/return-requests/" + left + "/cancel-exchange", null);
        assertThat(late.getStatusCode().value()).isEqualTo(409);
        assertThat(String.valueOf(late.getBody())).contains("REPLACEMENT_LEFT");
        assertThat(pieceStatus(spare2)).isEqualTo("with_courier");
        assertDecrementedOnce(spare2);
        verify(shopifyGateway, never()).pushPieceIncrement(any(), any(), any(), any(), anyInt(), any(), any(), any());
        verify(shopifyGateway, never()).adjustInventoryQuantities(any(), any(), any(), any(), anyInt(), any(), any());
    }

    // ── le5 ──────────────────────────────────────────────────────────────────────────────────────

    @Test
    void le5_releaseFromOrder_refusesALookupExchangePiece() {
        T t = tenant();
        O o = delivered(t, "#9106");
        String spare = stock(t, t.l(), t.location());
        UUID rr = createdId(create(t, spare, body(o, pieceId(o.piece()), null)));
        ResponseEntity<Object> r = post(t.ownerToken(), "/api/v1/pieces/" + pieceId(spare) + "/release-for-adjust", null);
        assertThat(r.getStatusCode().value()).isEqualTo(409);
        assertThat(String.valueOf(r.getBody())).contains("RESERVED_TO_LOOKUP_EXCHANGE")
            .contains(jdbc.queryForObject("SELECT reference FROM return_requests WHERE id = ?", String.class, rr));
        assertThat(pieceStatus(spare)).isEqualTo("reserved");

        @SuppressWarnings("unchecked")
        Map<String, Object> lookup = (Map<String, Object>) get(t.ownerToken(), "/api/v1/lookup?q=" + spare).getBody();
        assertThat(lookup).containsKey("lookupExchange").doesNotContainKey("canExchange");
    }

    // ── le6 ──────────────────────────────────────────────────────────────────────────────────────

    @Test
    void le6_gate_genericOnAndNoBostaAccount_portalSwitchNotNeeded() {
        T t = tenant();
        O o = delivered(t, "#9107");
        String spare = stock(t, t.l(), t.location());
        assertThat(jdbc.queryForObject("SELECT portal_generic_returns_enabled FROM tenants WHERE id = ?", Boolean.class, t.id()))
            .isFalse();
        assertThat(lookupOf(t.ownerToken(), spare)).containsEntry("canExchange", true);
        assertThat(lookupOf(t.workerToken(), spare)).doesNotContainKey("canExchange");
        assertThat(post(t.workerToken(), "/api/v1/pieces/" + pieceId(spare) + "/exchange", body(o, pieceId(o.piece()), null))
            .getStatusCode().value()).isEqualTo(403);

        jdbc.update("INSERT INTO courier_accounts (tenant_id, provider, api_key_encrypted, webhook_secret, status) " +
            "VALUES (?, 'bosta', ?, ?, 'active')", t.id(), encryption.encrypt("k"), "wh-" + t.id());
        assertThat(lookupOf(t.ownerToken(), spare)).doesNotContainKey("canExchange");
        ResponseEntity<Object> r = create(t, spare, body(o, pieceId(o.piece()), null));
        assertThat(r.getStatusCode().value()).isEqualTo(409);
        assertThat(String.valueOf(r.getBody())).contains("LOOKUP_EXCHANGE_UNAVAILABLE");
        assertThat(get(t.ownerToken(), "/api/v1/lookup-exchange/orders?q=9107").getStatusCode().value()).isEqualTo(409);

        T off = tenant();
        jdbc.update("UPDATE tenants SET generic_courier_enabled = false WHERE id = ?", off.id());
        String p = stock(off, off.l(), off.location());
        assertThat(lookupOf(off.ownerToken(), p)).doesNotContainKey("canExchange");
        assertThat(create(off, p, body(delivered(off, "#9108"), null, null)).getStatusCode().value()).isEqualTo(409);
        assertThat(pieceStatus(spare)).isEqualTo("available");
    }

    // ── le7 ──────────────────────────────────────────────────────────────────────────────────────

    @Test
    void le7_refusals() {
        T t = tenant();
        O o = delivered(t, "#9109");

        ResponseEntity<Object> elsewhere = create(t, stock(t, t.l(), t.other()), body(o, pieceId(o.piece()), null));
        assertThat(elsewhere.getStatusCode().value()).isEqualTo(409);
        assertThat(String.valueOf(elsewhere.getBody())).contains("NOT_AT_MAIN_WAREHOUSE")
            .contains("Move this piece to your main warehouse first (Transfers), then start the exchange.");

        String untaggedId = pieceId(stock(t, t.l(), t.location()));
        jdbc.update("UPDATE pieces SET short_code = NULL WHERE id = ?", untaggedId);
        ResponseEntity<Object> u = post(t.ownerToken(), "/api/v1/pieces/" + untaggedId + "/exchange", body(o, pieceId(o.piece()), null));
        assertThat(u.getStatusCode().value()).isEqualTo(409);
        assertThat(String.valueOf(u.getBody())).contains("PIECE_UNTAGGED");

        String damaged = stock(t, t.l(), t.location());
        jdbc.update("UPDATE pieces SET status = 'damaged' WHERE id = ?", pieceId(damaged));
        assertThat(String.valueOf(create(t, damaged, body(o, pieceId(o.piece()), null)).getBody())).contains("PIECE_NOT_AVAILABLE");

        // a piece of the order that isn't delivered can't come back
        String spare = stock(t, t.l(), t.location());
        assertThat(String.valueOf(create(t, spare, body(o, pieceId(spare), null)).getBody())).contains("RETURNING_NOT_ELIGIBLE");

        // a portal pre-connect order is the same as a missing one
        UUID portalOrder = jdbc.queryForObject("INSERT INTO orders (tenant_id, store_id, external_id, number, status, origin, " +
            "    shopify_order_gid, customer_name, customer_phone, placed_at) " +
            "VALUES (?, ?, ?, '#9110', 'delivered', 'portal_pre_connect', ?, 'Portal Person', '0109999990', now()) RETURNING id",
            UUID.class, t.id(), t.store(), "internal:portal:" + UUID.randomUUID(), "gid://shopify/Order/p" + t.id());
        assertThat(get(t.ownerToken(), "/api/v1/lookup-exchange/orders/" + portalOrder + "/returnables").getStatusCode().value())
            .isEqualTo(404);
        assertThat(get(t.ownerToken(), "/api/v1/lookup-exchange/orders/" + UUID.randomUUID() + "/returnables").getStatusCode().value())
            .isEqualTo(404);
        Map<String, Object> toPortal = body(o, null, null);
        toPortal.put("orderId", portalOrder.toString());
        assertThat(String.valueOf(create(t, spare, toPortal).getBody())).contains("ORDER_NOT_FOUND");
        assertThat(pieceStatus(spare)).isEqualTo("available");
    }

    // ── le8 ──────────────────────────────────────────────────────────────────────────────────────

    @Test
    void le8_untrackedUnit_arrived_thenLateTracking_lookupOnly() throws Exception {
        T t = tenant();
        UUID order = order(t, "#9111", "delivered");
        UUID line = jdbc.queryForObject("INSERT INTO order_items (tenant_id, order_id, variant_id, quantity) VALUES (?, ?, ?, 2) RETURNING id",
            UUID.class, t.id(), order, t.m());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rets = (List<Map<String, Object>>) get(t.ownerToken(),
            "/api/v1/lookup-exchange/orders/" + order + "/returnables").getBody();
        assertThat(rets).extracting(m -> m.get("kind") + ":" + m.get("unitNo")).containsExactly("unit:1", "unit:2");

        String spare = stock(t, t.l(), t.location());
        Map<String, Object> b = new HashMap<>();
        b.put("orderId", order.toString());
        b.put("returningOrderItemId", line.toString());
        b.put("returningUnitNo", 2);
        UUID rr = createdId(create(t, spare, b));
        assertThat(jdbc.queryForMap("SELECT order_item_id, unit_no FROM return_request_items WHERE request_id = ?", rr))
            .containsEntry("order_item_id", line).containsEntry("unit_no", 2);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> left = (List<Map<String, Object>>) get(t.ownerToken(),
            "/api/v1/lookup-exchange/orders/" + order + "/returnables").getBody();
        assertThat(left).extracting(m -> m.get("unitNo")).containsExactly(1);

        UUID item = jdbc.queryForObject("SELECT id FROM return_request_items WHERE request_id = ?", UUID.class, rr);
        assertThat(post(t.ownerToken(), "/api/v1/return-requests/" + rr + "/items/" + item + "/arrived",
            Map.of("condition", "sellable")).getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(row(rr)).containsEntry("status", "exchanged");
        assertThat(pieceStatus(spare)).as("the replacement is still waiting for its waybill").isEqualTo("reserved");

        // a return waybill alone is still refused on 'exchanged'
        assertThat(post(t.ownerToken(), "/api/v1/return-requests/" + rr + "/manual-courier",
            Map.of("trackingNumber", "QP-RET-9111")).getStatusCode().value()).isEqualTo(409);
        ResponseEntity<Object> late = post(t.ownerToken(), "/api/v1/return-requests/" + rr + "/manual-courier",
            Map.of("outboundTrackingNumber", "QP-OUT-9111"));
        assertThat(late.getStatusCode().value()).as(String.valueOf(late.getBody())).isEqualTo(200);
        assertThat(row(rr)).containsEntry("status", "exchanged");
        assertThat(pieceStatus(spare)).isEqualTo("with_courier");
        awaitDispatch(spare);
        assertDecrementedOnce(spare);

        // portal behaviour unchanged: a portal exchange already 'exchanged' still can't get an outbound waybill
        UUID portalRr = jdbc.queryForObject("INSERT INTO return_requests (tenant_id, order_id, type, status, reference, booking_status) " +
            "VALUES (?, ?, 'exchange', 'exchanged', ?, 'manual') RETURNING id", UUID.class, t.id(), order,
            com.traceability.portal.ReturnReferences.newReference(jdbc, t.id()));
        ResponseEntity<Object> portalLate = post(t.ownerToken(), "/api/v1/return-requests/" + portalRr + "/manual-courier",
            Map.of("outboundTrackingNumber", "QP-OUT-PORTAL"));
        assertThat(portalLate.getStatusCode().value()).isEqualTo(409);
        assertThat(String.valueOf(portalLate.getBody())).contains("ALREADY_BOOKED");
    }

    // ── le9 ──────────────────────────────────────────────────────────────────────────────────────

    @Test
    void le9_search_leavesInternalOrdersOut() {
        T t = tenant();
        O o = delivered(t, "#9112");
        String spare = stock(t, t.l(), t.location());
        createdId(create(t, spare, body(o, pieceId(o.piece()), null)));
        // the internal replacement order copies the customer's name — it must never be offered as an original
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> byName = (List<Map<String, Object>>) get(t.ownerToken(),
            "/api/v1/lookup-exchange/orders?q=Mona").getBody();
        assertThat(byName).extracting(m -> String.valueOf(m.get("number"))).noneMatch(n -> n.startsWith("EXC-"));
        // #9112's only piece is in a live request now — nothing left to bring back
        assertThat(byName).extracting(m -> m.get("number")).doesNotContain("#9112");

        O o2 = delivered(t, "#9113");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> byNumber = (List<Map<String, Object>>) get(t.ownerToken(),
            "/api/v1/lookup-exchange/orders?q=9113").getBody();
        assertThat(byNumber).hasSize(1);
        assertThat(byNumber.get(0)).containsEntry("number", "#9113").containsEntry("carrierName", "QP Express")
            .containsEntry("customerName", "Mona Adel");
        assertThat(byNumber.get(0).get("deliveredAt")).isNotNull();
        assertThat(o2).isNotNull();
    }

    // ── le10 ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void le10_ordersCancelOrder_refusedOnALookupExchangeOrder_normalOrderStillCancels() {
        T t = tenant();
        O o = delivered(t, "#9114");
        String spare = stock(t, t.l(), t.location());
        UUID rr = createdId(create(t, spare, body(o, pieceId(o.piece()), null)));
        UUID replacement = (UUID) row(rr).get("replacement_order_id");
        String reference = jdbc.queryForObject("SELECT reference FROM return_requests WHERE id = ?", String.class, rr);

        ResponseEntity<Object> refused = post(t.ownerToken(), "/api/v1/fulfill/" + replacement + "/cancel", null);
        assertThat(refused.getStatusCode().value()).isEqualTo(409);
        assertThat(String.valueOf(refused.getBody())).contains("LOOKUP_EXCHANGE_ORDER").contains(reference)
            .contains("Cancel exchange").contains("إلغاء الاستبدال");
        assertThat(pieceStatus(spare)).isEqualTo("reserved");
        assertThat(jdbc.queryForObject("SELECT status::text FROM orders WHERE id = ?", String.class, replacement)).isEqualTo("confirmed");
        assertThat(row(rr)).containsEntry("status", "approved");

        // control: a normal order with a reserved piece still cancels and frees it
        UUID normal = order(t, "#9115", "new");
        UUID item = jdbc.queryForObject("INSERT INTO order_items (tenant_id, order_id, variant_id, quantity) VALUES (?, ?, ?, 1) RETURNING id",
            UUID.class, t.id(), normal, t.m());
        String held = stock(t, t.m(), t.location());
        jdbc.update("UPDATE pieces SET status = 'reserved', current_order_id = ? WHERE id = ?", normal, pieceId(held));
        jdbc.update("INSERT INTO allocations (tenant_id, order_item_id, piece_id, status) VALUES (?, ?, ?, 'active'::allocation_status)",
            t.id(), item, pieceId(held));
        ResponseEntity<Object> ok = post(t.ownerToken(), "/api/v1/fulfill/" + normal + "/cancel", null);
        assertThat(ok.getStatusCode().value()).as(String.valueOf(ok.getBody())).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT status::text FROM orders WHERE id = ?", String.class, normal)).isEqualTo("cancelled");
        assertThat(pieceStatus(held)).isEqualTo("available");

        // once the exchange is cancelled its own way, the order is cancelled and Orders answers as for any cancelled order
        assertThat(post(t.ownerToken(), "/api/v1/return-requests/" + rr + "/cancel-exchange", null).getStatusCode().value())
            .isEqualTo(204);
        ResponseEntity<Object> after = post(t.ownerToken(), "/api/v1/fulfill/" + replacement + "/cancel", null);
        assertThat(after.getStatusCode().value()).isEqualTo(409);
        assertThat(String.valueOf(after.getBody())).doesNotContain("LOOKUP_EXCHANGE_ORDER");
    }

    // ── fixtures ─────────────────────────────────────────────────────────────────────────────────

    /** A store that ships with its own courier (generic on), no Bosta account, portal other-couriers switch OFF. */
    private T tenant() {
        int n = SEQ.incrementAndGet();
        UUID id = UUID.randomUUID(), store = UUID.randomUUID(), location = UUID.randomUUID(), other = UUID.randomUUID();
        String slug = "c2-" + n + "-" + id.toString().substring(0, 6);
        jdbc.update("INSERT INTO tenants (id, name, portal_slug, generic_courier_enabled, generic_default_carrier) " +
            "VALUES (?, ?, ?, true, 'QP Express')", id, "C2 " + n, slug);
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status, import_status, access_token_scopes) " +
            "VALUES (?, ?, 'shopify', ?, 'connected', 'idle', 'read_orders,write_inventory,read_products,write_locations,read_locations')",
            store, id, slug + ".myshopify.com");
        jdbc.update("INSERT INTO locations (id, tenant_id, name, is_fulfillment, shopify_location_id, shopify_sync_status) " +
            "VALUES (?, ?, 'Main', true, ?, 'linked')", location, id, "gid://shopify/Location/" + (7000 + n));
        jdbc.update("INSERT INTO locations (id, tenant_id, name, is_fulfillment) VALUES (?, ?, 'Showroom', false)", other, id);
        UUID owner = user(id, "owner"), worker = user(id, "worker");
        UUID product = jdbc.queryForObject("INSERT INTO products (tenant_id, store_id, external_id, title, status) " +
            "VALUES (?, ?, ?, 'Linen Shirt', 'active') RETURNING id", UUID.class, id, store, "gid://shopify/Product/" + slug);
        UUID m = jdbc.queryForObject("INSERT INTO variants (tenant_id, product_id, external_id, sku, title) " +
            "VALUES (?, ?, ?, 'LS-M', 'White / M') RETURNING id", UUID.class, id, product, "gid://shopify/ProductVariant/" + slug + "-m");
        UUID l = jdbc.queryForObject("INSERT INTO variants (tenant_id, product_id, external_id, sku, title) " +
            "VALUES (?, ?, ?, 'LS-L', 'White / L') RETURNING id", UUID.class, id, product, "gid://shopify/ProductVariant/" + slug + "-l");
        return new T(id, store, location, other, m, l, owner, worker,
            jwt.issueAccessToken(owner, id, "owner"), jwt.issueAccessToken(worker, id, "worker"));
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
            "VALUES (?, ?, ?, ?, ?::order_status, 'Mona Adel', ?, 900, 'cod', now() - interval '8 days', " +
            "    '{\"address1\": \"5 Tahrir St\", \"city\": \"Cairo\"}'::jsonb) RETURNING id",
            UUID.class, t.id(), t.store(), "gid://shopify/Order/" + t.id() + number, number, status, "0101234" + number.substring(1));
    }

    /** A delivered generic order with one piece of size M. */
    private O delivered(T t, String number) {
        UUID order = order(t, number, "delivered");
        UUID item = jdbc.queryForObject("INSERT INTO order_items (tenant_id, order_id, variant_id, quantity) VALUES (?, ?, ?, 1) RETURNING id",
            UUID.class, t.id(), order, t.m());
        String code = piece(t, t.m(), "delivered", order, null);
        jdbc.update("INSERT INTO allocations (tenant_id, order_item_id, piece_id, status) VALUES (?, ?, ?, 'packed'::allocation_status)",
            t.id(), item, pieceId(code));
        jdbc.update("INSERT INTO shipments (tenant_id, order_id, provider, tracking_number, tracking_key, carrier_name, internal_state, " +
            "    shipment_leg, source, handed_over_at, delivered_at, created_at) " +
            "VALUES (?, ?, 'generic', ?, ?, 'QP Express', 'delivered', 'forward', 'shopify_fulfillment', now() - interval '6 days', " +
            "    now() - interval '4 days', now() - interval '7 days')",
            t.id(), order, "QP-" + number.substring(1), "QP" + number.substring(1));
        return new O(order, number, code);
    }

    private String piece(T t, UUID variant, String status, UUID order, UUID location) {
        String id = UlidGenerator.generate();
        String code = "P" + String.format("%06d", Math.floorMod(id.hashCode(), 999_999) + 1);
        jdbc.update("INSERT INTO pieces (id, tenant_id, variant_id, barcode, short_code, status, current_order_id, current_location_id, " +
            "    last_event_at, last_user_id) VALUES (?, ?, ?, ?, ?, ?::piece_status, ?, ?, now(), ?)",
            id, t.id(), variant, "PC-" + id, code, status, order, location, t.worker());
        jdbc.update("INSERT INTO piece_events (tenant_id, piece_id, event_type, actor_user_id, from_status, to_status) " +
            "VALUES (?, ?, 'received', ?, NULL, 'available'::piece_status)", t.id(), id, t.worker());
        return code;
    }

    private String stock(T t, UUID variant, UUID location) {
        return piece(t, variant, "available", null, location);
    }

    private Map<String, Object> body(O o, String returningPieceId, String reason) {
        Map<String, Object> b = new HashMap<>();
        b.put("orderId", o.id().toString());
        if (returningPieceId != null) b.put("returningPieceId", returningPieceId);
        if (reason != null) b.put("reasonCode", reason);
        return b;
    }

    private ResponseEntity<Object> create(T t, String pieceCode, Map<String, Object> body) {
        return post(t.ownerToken(), "/api/v1/pieces/" + pieceId(pieceCode) + "/exchange", body);
    }

    private UUID createdId(ResponseEntity<Object> r) {
        assertThat(r.getStatusCode().value()).as(String.valueOf(r.getBody())).isEqualTo(201);
        return UUID.fromString((String) ((Map<?, ?>) r.getBody()).get("requestId"));
    }

    private Map<?, ?> drawer(T t, UUID rr) {
        return (Map<?, ?>) get(t.ownerToken(), "/api/v1/return-requests/" + rr).getBody();
    }

    private static Map<?, ?> lookupBlock(Map<?, ?> drawer) {
        return (Map<?, ?>) ((Map<?, ?>) ((Map<?, ?>) drawer.get("otherCourier")).get("replacement")).get("lookupExchange");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> lookupOf(String token, String code) {
        return (Map<String, Object>) get(token, "/api/v1/lookup?q=" + code).getBody();
    }

    private String queueText(T t) {
        return String.valueOf(get(t.ownerToken(), "/api/v1/fulfill/queue").getBody());
    }

    private String pieceId(String code) {
        return jdbc.queryForObject("SELECT id FROM pieces WHERE short_code = ?", String.class, code);
    }

    private String pieceStatus(String code) {
        return jdbc.queryForObject("SELECT status::text FROM pieces WHERE short_code = ?", String.class, code);
    }

    private List<String> claims(String code) {
        return jdbc.queryForList("SELECT trigger_type FROM shopify_inventory_adjustments WHERE trigger_id LIKE ?",
            String.class, pieceId(code) + "%");
    }

    private List<String> eventTypes(UUID rr) {
        return jdbc.queryForList("SELECT event_type FROM return_request_events WHERE request_id = ? ORDER BY occurred_at, id",
            String.class, rr);
    }

    private Map<String, Object> row(UUID rr) {
        return jdbc.queryForMap("SELECT status::text AS status, type, origin, booking_status, manual_carrier_name, " +
            "return_shipment_id, replacement_order_id FROM return_requests WHERE id = ?", rr);
    }

    private void awaitDispatch(String piece) throws InterruptedException {
        for (int i = 0; i < 200; i++) {
            Integer done = jdbc.queryForObject("SELECT COUNT(*) FROM shopify_inventory_adjustments WHERE trigger_type = 'exchange_dispatch' " +
                "AND trigger_id = ? AND status <> 'pending'", Integer.class, pieceId(piece));
            if (done != null && done >= 1) return;
            Thread.sleep(50);
        }
        throw new AssertionError("timed out waiting for the exchange_dispatch claim of " + piece);
    }

    private void settle() {
        try { Thread.sleep(400); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private void assertDecrementedOnce(String piece) {
        String id = pieceId(piece);
        assertThat(jdbc.queryForList("SELECT delta || ':' || status FROM shopify_inventory_adjustments " +
            "WHERE trigger_type = 'exchange_dispatch' AND trigger_id = ?", String.class, id)).containsExactly("-1:applied");
        verify(shopifyGateway, times(1)).pushExchangeDispatch(anyString(), anyString(), anyString(), anyString(),
            eq(-1), eq("traced://piece/" + id), anyString());
    }

    private ResponseEntity<Object> get(String token, String path) {
        return exchange(token, HttpMethod.GET, path, null);
    }

    private ResponseEntity<Object> post(String token, String path, Object body) {
        return exchange(token, HttpMethod.POST, path, body);
    }

    private ResponseEntity<Object> exchange(String token, HttpMethod method, String path, Object body) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        h.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange("http://localhost:" + port + path, method, new HttpEntity<>(body, h), Object.class);
    }
}
