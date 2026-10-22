package com.traceability;

import com.traceability.courier.GenericLegTransitions;
import com.traceability.identity.JwtService;
import com.traceability.integrations.bosta.BostaV2Client;
import com.traceability.integrations.shopify.ShopifyGateway;
import com.traceability.integrations.shopify.ShopifyTokenProvider;
import com.traceability.inventory.*;
import com.traceability.portal.PortalService;
import com.traceability.portal.ReturnPickupBookingJob;
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

import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Non-Bosta couriers G4b — portal EXCHANGES for orders shipped with another courier (V180), end to end, plus the
 * G4a follow-ups:
 *   x1  portal exchange → approve → replacement order → outbound + return waybill → pack → Pickups close →
 *       Shopify −1 exactly once → Mark delivered → old item back by its return waybill → exchanged
 *   x2  the same with no return waybill — the old item is matched by its piece
 *   x3  inferred handover (Shopify-style 'delivered' with no pickup scan) still decrements exactly once
 *   r*  refusals: same outbound / return key, out of stock at approval, switch off
 *   m1  mixed tenant: a Bosta exchange books a type 30; a generic one goes manual
 *   s1  5a: a stray internal order nobody names never decrements
 *   c1  cases: manual requests in the 'to do' bucket until booked
 *   f*  follow-ups: drawer Arrived (untracked) moves the generic return leg to returned, undo back; the
 *       courier-returns list names the carrier
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GenericCourierG4bTest {

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
    @Autowired EncryptionService encryption;
    @Autowired PortalService portal;
    @Autowired FulfillService fulfill;
    @Autowired PickupSessionService pickups;
    @Autowired ReturnSessionService returns;
    @Autowired GenericLegTransitions legs;
    @MockBean  ShopifyGateway shopifyGateway;
    @MockBean  ShopifyTokenProvider tokenProvider;
    @MockBean  JobScheduler jobScheduler;
    @MockBean  BostaV2Client bostaV2;

    static final AtomicInteger SEQ = new AtomicInteger();

    record T(UUID id, String slug, UUID store, UUID location, UUID product, UUID m, UUID l, UUID owner, UUID worker,
             String ownerToken, String workerToken) {}
    record O(UUID id, String number, UUID forward, String piece, String phone) {}

    @BeforeAll
    void districts() {
        jdbc.update("INSERT INTO bosta_districts (district_id, city_id, city_name, city_name_ar, zone_id, zone_name, zone_name_ar, " +
                    "district_name, district_name_ar, pickup_available, dropoff_available) VALUES " +
                    "(?, ?, 'Cairo', 'القاهرة', 'z1', 'Nasr City', 'مدينة نصر', 'Nasr City - 7th District', 'مدينة نصر - الحي السابع', true, true) " +
                    "ON CONFLICT DO NOTHING", NASR, CAIRO);
    }

    @BeforeEach
    void stubs() {
        reset(shopifyGateway, tokenProvider, bostaV2);
        clearInvocations(jobScheduler);
        when(tokenProvider.getValidToken(any())).thenReturn("shpat-g4b");
        when(shopifyGateway.resolveInventoryItemId(anyString(), anyString(), anyString())).thenReturn("gid://shopify/InventoryItem/9");
    }

    @AfterEach void clear() { TenantContext.clear(); }

    // ── x: end to end ────────────────────────────────────────────────────────────────────────────

    @Test
    void x1_exchangeEndToEnd_withReturnWaybill() throws Exception {
        T t = tenant(true);
        O o = deliveredGeneric(t, "#8001", "Aramex", "AR-8001");
        String spare = stockPiece(t, t.l());
        UUID rr = portalExchange(t, o);
        assertThat(row(rr)).containsEntry("status", "requested").containsEntry("type", "exchange");

        assertThat(post(t.ownerToken(), "/api/v1/return-requests/" + rr + "/approve", null).getStatusCode().value()).isEqualTo(204);
        Map<String, Object> r = row(rr);
        assertThat(r).containsEntry("status", "approved").containsEntry("booking_status", "manual")
            .containsEntry("bosta_tracking_number", null);
        UUID replacement = (UUID) r.get("replacement_order_id");
        Map<String, Object> rep = jdbc.queryForMap("SELECT external_id, number, status::text AS status, customer_name, " +
            "customer_phone, shopify_address->>'address1' AS address1 FROM orders WHERE id = ?", replacement);
        assertThat(rep).containsEntry("external_id", "internal:exchange:rr:" + rr).containsEntry("status", "new")
            .containsEntry("customer_name", "Youssef Adel").containsEntry("customer_phone", o.phone())
            .containsEntry("address1", "5 Tahrir St");
        assertThat((String) rep.get("number")).startsWith("EXC-RR-");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM exchanges WHERE tenant_id = ?", Integer.class, t.id())).isZero();
        assertThat(bookingJobs()).isEmpty();
        verifyNoInteractions(bostaV2);
        assertThat(queueNumbers(t)).as("not pickable before its outbound waybill").doesNotContain((String) rep.get("number"));

        ResponseEntity<Object> booked = post(t.ownerToken(), "/api/v1/return-requests/" + rr + "/manual-courier",
            Map.of("carrier", "Aramex", "outboundTrackingNumber", "AR-OUT-8001", "trackingNumber", "AR-RET-8001"));
        assertThat(booked.getStatusCode().value()).isEqualTo(200);
        assertThat(String.valueOf(booked.getBody())).contains("LEGS_CREATED");
        assertThat(row(rr)).containsEntry("status", "pickup_booked").containsEntry("link_source", "merchant_entered");
        assertThat(jdbc.queryForMap("SELECT provider::text AS provider, source, internal_state::text AS state, tracking_key " +
            "FROM shipments WHERE order_id = ? AND shipment_leg = 'forward'", replacement))
            .containsEntry("provider", "generic").containsEntry("source", "merchant_entered")
            .containsEntry("state", "created").containsEntry("tracking_key", "AROUT8001");
        assertThat(queueNumbers(t)).as("pickable once its outbound waybill exists").contains((String) rep.get("number"));
        Map<?, ?> drawer = (Map<?, ?>) get(t.ownerToken(), "/api/v1/return-requests/" + rr).getBody();
        assertThat(((Map<?, ?>) drawer.get("exchange")).get("trackingNumber")).isEqualTo("AR-OUT-8001");
        assertThat(((Map<?, ?>) ((Map<?, ?>) drawer.get("otherCourier")).get("replacement")).get("outboundTrackingNumber"))
            .isEqualTo("AR-OUT-8001");

        packAndHandOver(t, replacement, spare, "AR-OUT-8001");
        awaitDispatch(spare, 1);
        assertDecrementedOnce(t, spare);

        post(t.ownerToken(), "/api/v1/generic-shipments/mark-delivered", Map.of("orderIds", List.of(replacement)));
        assertThat(jdbc.queryForObject("SELECT internal_state::text FROM shipments WHERE order_id = ? AND shipment_leg = 'forward'",
            String.class, replacement)).isEqualTo("delivered");
        settle();
        assertDecrementedOnce(t, spare);

        // the old item comes back on its own return waybill
        TenantContext.set(t.id());
        UUID session = returns.createSession(null, t.worker());
        assertThat(returns.scan(session, "ar-ret-8001", t.location(), t.worker())).containsEntry("awb", "AR-RET-8001");
        returns.scan(session, o.piece(), t.location(), t.worker());
        assertThat(forwardState(o)).as("the customer's exchange — the original stays delivered").isEqualTo("delivered");
        returns.disposition(session, pieceId(o.piece()), "restock", null, t.location(), t.worker(), "worker");
        returns.close(session, t.worker());
        TenantContext.clear();
        assertThat(row(rr)).containsEntry("status", "exchanged");
        assertThat(jdbc.queryForObject("SELECT internal_state::text FROM shipments WHERE id = ?", String.class,
            row(rr).get("return_shipment_id"))).isEqualTo("returned");
        assertDecrementedOnce(t, spare);
    }

    @Test
    void x2_exchangeWithoutReturnWaybill_oldItemMatchedByPiece() throws Exception {
        T t = tenant(true);
        O o = deliveredGeneric(t, "#8002", "Aramex", "AR-8002");
        String spare = stockPiece(t, t.l());
        UUID rr = approvedExchange(t, o);
        UUID replacement = (UUID) row(rr).get("replacement_order_id");
        assertThat(post(t.ownerToken(), "/api/v1/return-requests/" + rr + "/manual-courier",
            Map.of("outboundTrackingNumber", "AR-OUT-8002")).getStatusCode().value()).isEqualTo(200);
        assertThat(row(rr)).containsEntry("status", "pickup_booked").containsEntry("return_shipment_id", null);

        packAndHandOver(t, replacement, spare, "AR-OUT-8002");
        awaitDispatch(spare, 1);
        post(t.ownerToken(), "/api/v1/generic-shipments/mark-delivered", Map.of("orderIds", List.of(replacement)));

        TenantContext.set(t.id());
        UUID session = returns.createSession(null, t.worker());
        returns.scan(session, o.piece(), t.location(), t.worker());
        assertThat(jdbc.queryForObject("SELECT metadata->>'return_kind' FROM piece_events WHERE piece_id = ? " +
            "AND event_type = 'return_received'", String.class, pieceId(o.piece()))).isEqualTo("request_return");
        assertThat(forwardState(o)).isEqualTo("delivered");
        returns.disposition(session, pieceId(o.piece()), "restock", null, t.location(), t.worker(), "worker");
        returns.close(session, t.worker());
        TenantContext.clear();
        assertThat(row(rr)).containsEntry("status", "exchanged");
        settle();
        assertDecrementedOnce(t, spare);
    }

    @Test
    void x3_inferredHandover_replacementDecrementsExactlyOnce() throws Exception {
        T t = tenant(true);
        O o = deliveredGeneric(t, "#8003", "Aramex", "AR-8003");
        String spare = stockPiece(t, t.l());
        UUID rr = approvedExchange(t, o);
        UUID replacement = (UUID) row(rr).get("replacement_order_id");
        post(t.ownerToken(), "/api/v1/return-requests/" + rr + "/manual-courier", Map.of("outboundTrackingNumber", "AR-OUT-8003"));
        TenantContext.set(t.id());
        assertThat(fulfill.scan(replacement, spare, t.worker()).success()).isTrue();
        fulfill.complete(replacement, t.worker());
        UUID leg = jdbc.queryForObject("SELECT id FROM shipments WHERE order_id = ? AND shipment_leg = 'forward'", UUID.class, replacement);
        // the replacement never went through Pickups: 'delivered' arrives with no handover scan
        assertThat(legs.deliver(t.id(), leg, GenericLegTransitions.SOURCE_SHOPIFY, null))
            .isEqualTo(GenericLegTransitions.Delivered.DELIVERED_INFERRED_HANDOVER);
        assertThat(legs.deliver(t.id(), leg, GenericLegTransitions.SOURCE_SHOPIFY, null))
            .isEqualTo(GenericLegTransitions.Delivered.ALREADY_DELIVERED);
        TenantContext.clear();
        awaitDispatch(spare, 1);
        settle();
        assertDecrementedOnce(t, spare);
    }

    // ── r: refusals ──────────────────────────────────────────────────────────────────────────────

    @Test
    void r1_sameOutboundAndReturnKey_refused_inOneCallAndAcrossCalls() {
        T t = tenant(true);
        O o = deliveredGeneric(t, "#8011", "Aramex", "AR-8011");
        stockPiece(t, t.l());
        UUID rr = approvedExchange(t, o);
        ResponseEntity<Object> same = post(t.ownerToken(), "/api/v1/return-requests/" + rr + "/manual-courier",
            Map.of("outboundTrackingNumber", "AR-X-1", "trackingNumber", "ar x 1"));
        assertThat(same.getStatusCode().value()).isEqualTo(409);
        assertThat(String.valueOf(same.getBody())).contains("SAME_AS_OUTBOUND");
        assertThat(row(rr)).containsEntry("status", "approved");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM shipments WHERE tenant_id = ? AND provider = 'generic' " +
            "AND tracking_key = 'ARX1'", Integer.class, t.id())).as("all or nothing").isZero();

        assertThat(post(t.ownerToken(), "/api/v1/return-requests/" + rr + "/manual-courier",
            Map.of("outboundTrackingNumber", "AR-X-1")).getStatusCode().value()).isEqualTo(200);
        ResponseEntity<Object> later = post(t.ownerToken(), "/api/v1/return-requests/" + rr + "/manual-courier",
            Map.of("trackingNumber", "ARX1"));
        assertThat(later.getStatusCode().value()).isEqualTo(409);
        assertThat(String.valueOf(later.getBody())).contains("SAME_AS_OUTBOUND");
        assertThat(post(t.ownerToken(), "/api/v1/return-requests/" + rr + "/manual-courier",
            Map.of("trackingNumber", "AR-RET-X")).getStatusCode().value()).isEqualTo(200);
        assertThat(post(t.ownerToken(), "/api/v1/return-requests/" + rr + "/manual-courier",
            Map.of("outboundTrackingNumber", "AR-X-2")).getStatusCode().value()).as("outbound once").isEqualTo(409);
    }

    @Test
    void r2_outOfStockAtApproval_refused_switchOff_refused() {
        T t = tenant(true);
        O o = deliveredGeneric(t, "#8012", "Aramex", "AR-8012");
        String spare = stockPiece(t, t.l());
        UUID rr = portalExchange(t, o);
        jdbc.update("UPDATE pieces SET status = 'damaged' WHERE short_code = ?", spare);
        ResponseEntity<Object> oos = post(t.ownerToken(), "/api/v1/return-requests/" + rr + "/approve", null);
        assertThat(oos.getStatusCode().value()).isEqualTo(409);
        assertThat(row(rr)).containsEntry("status", "requested").containsEntry("replacement_order_id", null);

        stockPiece(t, t.l());
        jdbc.update("UPDATE tenants SET portal_generic_returns_enabled = false WHERE id = ?", t.id());
        ResponseEntity<Object> off = post(t.ownerToken(), "/api/v1/return-requests/" + rr + "/approve", null);
        assertThat(off.getStatusCode().value()).isEqualTo(409);
        assertThat(String.valueOf(off.getBody())).contains("OTHER_COURIER_RETURNS_OFF");
        assertThat(bookingJobs()).isEmpty();
    }

    // ── m: mixed tenant ──────────────────────────────────────────────────────────────────────────

    @Test
    void m1_mixedTenant_bostaExchangeBooksType30_genericGoesManual() {
        T t = tenant(true);
        O bosta = bostaOrder(t, "#8021");
        O generic = deliveredGeneric(t, "#8022", "Aramex", "AR-8022");
        stockPiece(t, t.l());
        stockPiece(t, t.l());
        UUID b = directExchange(t, bosta);
        UUID g = directExchange(t, generic);
        assertThat(post(t.ownerToken(), "/api/v1/return-requests/" + b + "/approve", null).getStatusCode().value()).isEqualTo(204);
        assertThat(bookingJobs()).containsExactly(b);
        assertThat(row(b)).containsEntry("booking_status", null).containsEntry("replacement_order_id", null);
        assertThat(post(t.ownerToken(), "/api/v1/return-requests/" + g + "/approve", null).getStatusCode().value()).isEqualTo(204);
        assertThat(bookingJobs()).isEmpty();
        assertThat(row(g)).containsEntry("booking_status", "manual");
        assertThat(row(g).get("replacement_order_id")).isNotNull();
    }

    // ── s: 5a never fires for an internal order no one names ─────────────────────────────────────

    @Test
    void s1_strayInternalOrder_neverDecrements() throws Exception {
        T t = tenant(true);
        String spare = stockPiece(t, t.l());
        UUID stray = jdbc.queryForObject("INSERT INTO orders (tenant_id, store_id, external_id, number, placed_at) " +
            "VALUES (?, ?, ?, 'EXC-STRAY', now()) RETURNING id", UUID.class, t.id(), t.store(),
            "internal:exchange:rr:" + UUID.randomUUID());
        jdbc.update("INSERT INTO order_items (tenant_id, order_id, variant_id, quantity) VALUES (?, ?, ?, 1)", t.id(), stray, t.l());
        TenantContext.set(t.id());
        legs.createReplacementForwardLeg(t.id(), stray, "Aramex", "AR-STRAY-1");
        TenantContext.clear();
        packAndHandOver(t, stray, spare, "AR-STRAY-1");
        assertThat(jdbc.queryForObject("SELECT status::text FROM pieces WHERE short_code = ?", String.class, spare)).isEqualTo("with_courier");
        settle();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM shopify_inventory_adjustments WHERE trigger_type = 'exchange_dispatch' " +
            "AND trigger_id = ?", Integer.class, pieceId(spare))).isZero();
        verify(shopifyGateway, never()).pushExchangeDispatch(anyString(), anyString(), anyString(), anyString(), anyInt(), anyString(), anyString());
    }

    // ── c: cases buckets ─────────────────────────────────────────────────────────────────────────

    @Test
    void c1_manualRequests_inTheToDoBucket_untilBooked() {
        T t = tenant(true);
        O ex = deliveredGeneric(t, "#8031", "Aramex", "AR-8031");
        O refund = deliveredGeneric(t, "#8032", "Aramex", "AR-8032");
        stockPiece(t, t.l());
        UUID x = approvedExchange(t, ex);
        UUID r = directRefund(t, refund);
        assertThat(post(t.ownerToken(), "/api/v1/return-requests/" + r + "/approve", null).getStatusCode().value()).isEqualTo(204);
        assertThat(caseStep(t, x)).isEqualTo("to_do|book_with_courier");
        assertThat(caseStep(t, r)).isEqualTo("to_do|book_with_courier");

        post(t.ownerToken(), "/api/v1/return-requests/" + x + "/manual-courier", Map.of("outboundTrackingNumber", "AR-OUT-8031"));
        post(t.ownerToken(), "/api/v1/return-requests/" + r + "/manual-courier", Map.of("carrier", "Aramex"));
        assertThat(caseStep(t, x)).isEqualTo("in_progress|on_the_way");
        assertThat(caseStep(t, r)).isEqualTo("in_progress|awaiting_items");
        String counts = String.valueOf(get(t.ownerToken(), "/api/v1/returns-exchanges/counts").getBody());
        assertThat(counts).contains("in_progress=2");
    }

    // ── f: G4a follow-ups ────────────────────────────────────────────────────────────────────────

    @Test
    void f1_drawerArrivedOutsideASession_movesGenericReturnLeg_undoMovesItBack() {
        T t = tenant(true);
        O o = deliveredGeneric(t, "#8041", "Aramex", "AR-8041");
        UUID untrackedLine = jdbc.queryForObject("INSERT INTO order_items (tenant_id, order_id, variant_id, quantity) " +
            "VALUES (?, ?, ?, 1) RETURNING id", UUID.class, t.id(), o.id(), t.m());
        UUID rr = jdbc.queryForObject("INSERT INTO return_requests (tenant_id, order_id, status, reference, decided_at, booking_status, " +
            "    manual_carrier_name) VALUES (?, ?, 'approved', ?, now(), 'manual', 'Aramex') RETURNING id",
            UUID.class, t.id(), o.id(), ref());
        UUID item = jdbc.queryForObject("INSERT INTO return_request_items (tenant_id, request_id, order_item_id, unit_no, variant_id, " +
            "    reason_code) VALUES (?, ?, ?, 1, ?, 'wrong_size') RETURNING id", UUID.class, t.id(), rr, untrackedLine, t.m());
        assertThat(post(t.ownerToken(), "/api/v1/return-requests/" + rr + "/manual-courier",
            Map.of("trackingNumber", "AR-RET-8041")).getStatusCode().value()).isEqualTo(200);
        UUID leg = (UUID) row(rr).get("return_shipment_id");

        assertThat(post(t.ownerToken(), "/api/v1/return-requests/" + rr + "/items/" + item + "/arrived",
            Map.of("condition", "sellable")).getStatusCode().value()).isEqualTo(204);
        assertThat(jdbc.queryForMap("SELECT internal_state::text AS state, return_intake_completed_at IS NOT NULL AS intake " +
            "FROM shipments WHERE id = ?", leg)).containsEntry("state", "returned").containsEntry("intake", true);

        assertThat(post(t.ownerToken(), "/api/v1/return-requests/" + rr + "/items/" + item + "/arrived/undo", null)
            .getStatusCode().value()).isEqualTo(204);
        assertThat(jdbc.queryForMap("SELECT internal_state::text AS state, return_intake_completed_at IS NOT NULL AS intake, " +
            "(SELECT string_agg(h.internal_state || ':' || h.source, ',' ORDER BY h.occurred_at, h.id) FROM shipment_status_history h " +
            " WHERE h.shipment_id = s.id) AS history FROM shipments s WHERE s.id = ?", leg))
            .containsEntry("state", "created").containsEntry("intake", false)
            .containsEntry("history", "returned:return_scan,created:return_scan");
    }

    @Test
    void f2_courierReturnsList_namesTheCarrier_onlyForGenericLegs() {
        T t = tenant(true);
        O o = deliveredGeneric(t, "#8051", "Aramex", "AR-8051");
        UUID rr = directRefund(t, o);
        post(t.ownerToken(), "/api/v1/return-requests/" + rr + "/approve", null);
        post(t.ownerToken(), "/api/v1/return-requests/" + rr + "/manual-courier", Map.of("trackingNumber", "AR-RET-8051"));
        O bosta = bostaOrder(t, "#8052");
        jdbc.update("INSERT INTO shipments (tenant_id, order_id, provider, tracking_number, internal_state, shipment_leg, raw) " +
            "VALUES (?, ?, 'bosta', ?, 'created', 'return', '{\"type\":{\"code\":25}}'::jsonb)", t.id(), bosta.id(),
            String.valueOf(4_200_000_000L + SEQ.incrementAndGet()));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> list = (List<Map<String, Object>>) get(t.ownerToken(), "/api/v1/refunds").getBody();
        Map<String, Object> generic = list.stream().filter(m -> "AR-RET-8051".equals(m.get("tracking_number"))).findFirst().orElseThrow();
        assertThat(generic).containsEntry("carrier", "Aramex");
        Map<String, Object> b = list.stream().filter(m -> "#8052".equals(m.get("order_number"))).findFirst().orElseThrow();
        assertThat(b).doesNotContainKey("carrier");
    }

    // ── fixtures ─────────────────────────────────────────────────────────────────────────────────

    private T tenant(boolean portalGenericOn) {
        int n = SEQ.incrementAndGet();
        UUID id = UUID.randomUUID(), store = UUID.randomUUID(), location = UUID.randomUUID();
        String slug = "g4b-" + n + "-" + id.toString().substring(0, 6);
        jdbc.update("INSERT INTO tenants (id, name, portal_slug, portal_enabled, portal_pickup_booking, portal_pickup_booking_since, " +
            "    portal_exchanges_enabled, portal_exchanges_since, generic_courier_enabled, portal_generic_returns_enabled, " +
            "    portal_generic_returns_since) " +
            "VALUES (?, ?, ?, true, true, now() - interval '1 day', true, now() - interval '1 day', true, ?, now())",
            id, "G4b " + n, slug, portalGenericOn);
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status, import_status, access_token_scopes) " +
            "VALUES (?, ?, 'shopify', ?, 'connected', 'idle', 'read_orders,write_inventory,read_products,write_locations,read_locations')",
            store, id, slug + ".myshopify.com");
        jdbc.update("INSERT INTO locations (id, tenant_id, name, is_fulfillment, shopify_location_id, shopify_sync_status) " +
            "VALUES (?, ?, 'Main', true, ?, 'linked')", location, id, "gid://shopify/Location/" + (5000 + n));
        jdbc.update("INSERT INTO courier_accounts (tenant_id, provider, api_key_encrypted, webhook_secret, status, " +
            "    return_business_location_id, return_business_location_name) VALUES (?, 'bosta', ?, ?, 'active', 'loc', 'Maadi')",
            id, encryption.encrypt("key-" + slug), "wh-" + id);
        UUID owner = user(id, "owner"), worker = user(id, "worker");
        UUID product = jdbc.queryForObject("INSERT INTO products (tenant_id, store_id, external_id, title, status) " +
            "VALUES (?, ?, ?, 'Linen Shirt', 'active') RETURNING id", UUID.class, id, store, "gid://shopify/Product/" + slug);
        UUID m = jdbc.queryForObject("INSERT INTO variants (tenant_id, product_id, external_id, sku, title) " +
            "VALUES (?, ?, ?, 'SH-M', 'Sand / M') RETURNING id", UUID.class, id, product, "gid://shopify/ProductVariant/" + slug + "-m");
        UUID l = jdbc.queryForObject("INSERT INTO variants (tenant_id, product_id, external_id, sku, title) " +
            "VALUES (?, ?, ?, 'SH-L', 'Sand / L') RETURNING id", UUID.class, id, product, "gid://shopify/ProductVariant/" + slug + "-l");
        return new T(id, slug, store, location, product, m, l, owner, worker,
            jwt.issueAccessToken(owner, id, "owner"), jwt.issueAccessToken(worker, id, "worker"));
    }

    private UUID user(UUID tenant, String role) {
        return jdbc.queryForObject("INSERT INTO users (tenant_id, name, email, password_hash, role, active) " +
            "VALUES (?, ?, ?, 'x', ?::user_role, true) RETURNING id",
            UUID.class, tenant, role, role + "-" + UUID.randomUUID() + "@test.com", role);
    }

    private static String phone(String number) { return "0103333" + number.substring(1); }

    private UUID order(T t, String number, String status) {
        return jdbc.queryForObject(
            "INSERT INTO orders (tenant_id, store_id, external_id, number, status, customer_name, customer_phone, cod_amount, " +
            "    payment_method, placed_at, shopify_address) " +
            "VALUES (?, ?, ?, ?, ?::order_status, 'Youssef Adel', ?, 900, 'cod', now() - interval '8 days', " +
            "    '{\"address1\": \"5 Tahrir St\", \"city\": \"Cairo\"}'::jsonb) RETURNING id",
            UUID.class, t.id(), t.store(), "gid://shopify/Order/" + t.id() + number, number, status, phone(number));
    }

    /** A generic order with one piece of size M, handed over (pickup scan) and then marked delivered by the owner. */
    private O deliveredGeneric(T t, String number, String carrier, String tracking) {
        UUID order = order(t, number, "with_courier");
        String piece = allocated(t, order, t.m(), "with_courier");
        UUID leg = jdbc.queryForObject(
            "INSERT INTO shipments (tenant_id, order_id, provider, tracking_number, tracking_key, carrier_name, internal_state, " +
            "    shipment_leg, source, handed_over_at, created_at) " +
            "VALUES (?, ?, 'generic', ?, ?, ?, 'with_courier', 'forward', 'shopify_fulfillment', now() - interval '6 days', " +
            "    now() - interval '7 days') RETURNING id",
            UUID.class, t.id(), order, tracking, tracking.replaceAll("[^A-Za-z0-9]", "").toUpperCase(), carrier);
        jdbc.update("INSERT INTO shipment_status_history (tenant_id, shipment_id, internal_state, occurred_at, source) " +
            "VALUES (?, ?, 'with_courier', now() - interval '6 days', 'pickup_scan')", t.id(), leg);
        post(t.ownerToken(), "/api/v1/generic-shipments/mark-delivered", Map.of("orderIds", List.of(order)));
        return new O(order, number, leg, piece, phone(number));
    }

    private O bostaOrder(T t, String number) {
        UUID order = order(t, number, "delivered");
        String piece = allocated(t, order, t.m(), "delivered");
        String raw = "{\"type\":{\"code\":10,\"value\":\"Send\"},\"dropOffAddress\":{\"firstLine\":\"12 Street\"," +
            "\"city\":{\"_id\":\"" + CAIRO + "\",\"name\":\"Cairo\"},\"district\":{\"_id\":\"" + NASR + "\",\"name\":\"Nasr City\"}}," +
            "\"receiver\":{\"firstName\":\"Youssef\",\"lastName\":\"Adel\",\"phone\":\"+20103333\"}}";
        UUID leg = jdbc.queryForObject("INSERT INTO shipments (tenant_id, order_id, provider, tracking_number, internal_state, " +
            "    shipment_leg, raw, delivered_at) VALUES (?, ?, 'bosta', ?, 'delivered', 'forward', ?::jsonb, now() - interval '2 days') RETURNING id",
            UUID.class, t.id(), order, String.valueOf(4_100_000_000L + SEQ.incrementAndGet()), raw);
        return new O(order, number, leg, piece, phone(number));
    }

    private String allocated(T t, UUID order, UUID variant, String status) {
        UUID item = jdbc.queryForObject("INSERT INTO order_items (tenant_id, order_id, variant_id, quantity) VALUES (?, ?, ?, 1) RETURNING id",
            UUID.class, t.id(), order, variant);
        String code = piece(t, variant, status, order);
        jdbc.update("INSERT INTO allocations (tenant_id, order_item_id, piece_id, status) VALUES (?, ?, ?, 'packed'::allocation_status)",
            t.id(), item, pieceId(code));
        return code;
    }

    private String piece(T t, UUID variant, String status, UUID order) {
        String id = UlidGenerator.generate();
        String code = "P" + String.format("%06d", Math.floorMod(id.hashCode(), 999_999) + 1);
        jdbc.update("INSERT INTO pieces (id, tenant_id, variant_id, barcode, short_code, status, current_order_id, current_location_id, " +
            "    last_event_at, last_user_id) VALUES (?, ?, ?, ?, ?, ?::piece_status, ?, ?, now(), ?)",
            id, t.id(), variant, "PC-" + id, code, status, order, order == null ? t.location() : null, t.worker());
        jdbc.update("INSERT INTO piece_events (tenant_id, piece_id, event_type, actor_user_id, from_status, to_status) " +
            "VALUES (?, ?, 'received', ?, NULL, 'available'::piece_status)", t.id(), id, t.worker());
        return code;
    }

    private String stockPiece(T t, UUID variant) {
        return piece(t, variant, "available", null);
    }

    /** The customer asks for size L instead of M on the portal. */
    private UUID portalExchange(T t, O o) {
        String token = (String) portal.lookup(t.slug(), o.number().substring(1), o.phone()).orElseThrow().body().get("token");
        PortalService.SubmitResult r = portal.submit(t.slug(), token, new PortalService.SubmitRequest(
            List.of(new PortalService.SubmitLine(t.m(), 1, "wrong_size")), null, null, null, "exchange", t.l(), false)).orElseThrow();
        assertThat(r.outcome()).as(String.valueOf(r.body())).isEqualTo(PortalService.SubmitOutcome.CREATED);
        return jdbc.queryForObject("SELECT id FROM return_requests WHERE order_id = ? ORDER BY created_at DESC LIMIT 1", UUID.class, o.id());
    }

    private UUID approvedExchange(T t, O o) {
        UUID rr = portalExchange(t, o);
        assertThat(post(t.ownerToken(), "/api/v1/return-requests/" + rr + "/approve", null).getStatusCode().value()).isEqualTo(204);
        clearInvocations(jobScheduler);
        return rr;
    }

    private UUID directExchange(T t, O o) {
        UUID rr = jdbc.queryForObject("INSERT INTO return_requests (tenant_id, order_id, type, status, reference, pickup_city_id, " +
            "    pickup_city_name, pickup_district_id, pickup_district_name) " +
            "VALUES (?, ?, 'exchange', 'requested', ?, ?, 'Cairo', ?, 'Nasr City - 7th District') RETURNING id",
            UUID.class, t.id(), o.id(), ref(), CAIRO, NASR);
        jdbc.update("INSERT INTO return_request_items (tenant_id, request_id, piece_id, variant_id, reason_code, replacement_variant_id) " +
            "VALUES (?, ?, ?, ?, 'wrong_size', ?)", t.id(), rr, pieceId(o.piece()), t.m(), t.l());
        return rr;
    }

    private UUID directRefund(T t, O o) {
        UUID rr = jdbc.queryForObject("INSERT INTO return_requests (tenant_id, order_id, status, reference) " +
            "VALUES (?, ?, 'requested', ?) RETURNING id", UUID.class, t.id(), o.id(), ref());
        jdbc.update("INSERT INTO return_request_items (tenant_id, request_id, piece_id, variant_id, reason_code) VALUES (?, ?, ?, ?, 'wrong_size')",
            t.id(), rr, pieceId(o.piece()), t.m());
        return rr;
    }

    /** Pack the replacement (piece scan + complete), then a Pickups session scans its waybill and closes. */
    private void packAndHandOver(T t, UUID order, String piece, String waybill) {
        TenantContext.set(t.id());
        assertThat(fulfill.scan(order, piece, t.worker()).success()).isTrue();
        fulfill.complete(order, t.worker());
        UUID pickup = pickups.openSession(t.id(), t.worker(), LocalDate.now(), null, null);
        assertThat(pickups.scan(t.id(), pickup, t.worker(), waybill).outcome()).isEqualTo(PickupSessionService.ScanOutcome.ACCEPTED);
        pickups.closeSession(t.id(), pickup, t.worker());
        TenantContext.clear();
    }

    private List<String> queueNumbers(T t) {
        @SuppressWarnings("unchecked")
        Object body = get(t.ownerToken(), "/api/v1/fulfill/queue").getBody();
        return List.of(String.valueOf(body).split("[,{}\\[\\]]")).stream().map(String::trim)
            .filter(s -> s.startsWith("number=")).map(s -> s.substring("number=".length())).toList();
    }

    private String caseStep(T t, UUID requestId) {
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) get(t.ownerToken(), "/api/v1/returns-exchanges").getBody();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) body.get("items");
        Map<String, Object> c = items.stream().filter(i -> requestId.toString().equals(i.get("id"))).findFirst().orElseThrow();
        return c.get("stage") + "|" + c.get("nextStep");
    }

    private void awaitDispatch(String piece, int n) throws InterruptedException {
        for (int i = 0; i < 200; i++) {
            Integer done = jdbc.queryForObject("SELECT COUNT(*) FROM shopify_inventory_adjustments WHERE trigger_type = 'exchange_dispatch' " +
                "AND trigger_id = ? AND status <> 'pending'", Integer.class, pieceId(piece));
            if (done != null && done >= n) return;
            Thread.sleep(50);
        }
        throw new AssertionError("timed out waiting for the exchange_dispatch claim of " + piece);
    }

    private void settle() {
        try { Thread.sleep(400); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private void assertDecrementedOnce(T t, String piece) {
        String id = pieceId(piece);
        assertThat(jdbc.queryForList("SELECT delta || ':' || status FROM shopify_inventory_adjustments " +
            "WHERE trigger_type = 'exchange_dispatch' AND trigger_id = ?", String.class, id)).containsExactly("-1:applied");
        verify(shopifyGateway, times(1)).pushExchangeDispatch(anyString(), anyString(), anyString(), anyString(),
            eq(-1), eq("traced://piece/" + id), anyString());
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

    private String forwardState(O o) {
        return jdbc.queryForObject("SELECT internal_state::text FROM shipments WHERE id = ?", String.class, o.forward());
    }

    private Map<String, Object> row(UUID rr) {
        return jdbc.queryForMap("SELECT status::text AS status, type, booking_status, manual_carrier_name, return_shipment_id, " +
            "link_source, bosta_tracking_number, replacement_order_id FROM return_requests WHERE id = ?", rr);
    }

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

    private ResponseEntity<Object> exchange(String token, HttpMethod method, String path, Object body) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        h.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange("http://localhost:" + port + path, method, new HttpEntity<>(body, h), Object.class);
    }
}
