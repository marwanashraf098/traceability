package com.traceability;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.traceability.courier.GenericDeliveryService;
import com.traceability.courier.GenericTrackingKey;
import com.traceability.fulfillment.OrderController;
import com.traceability.identity.JwtService;
import com.traceability.integrations.bosta.BostaGateway;
import com.traceability.integrations.bosta.BostaV2Client;
import com.traceability.integrations.shopify.FulfillmentTrackingCapture;
import com.traceability.integrations.shopify.ShopifyGateway;
import com.traceability.inventory.*;
import com.traceability.portal.PortalService;
import com.traceability.portal.ReturnPickupBookingService;
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
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDate;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Non-Bosta couriers G3 — out at Pickups, delivered, returned parcels. A switched-on tenant with a Bosta account
 * (the Femine case) shipping with Aramex / Mylerz next to Bosta.
 *
 *   p*  Pickups: generic waybills scan in through the shared resolver; one courier per session (a parcel for
 *       another courier is refused, naming the session's); close → with_courier, handed_over_at, custody lock,
 *       history source pickup_scan, pieces with_courier; the manifest names the carrier
 *   d*  delivered from Shopify's fulfillment shipment_status: from with_courier; from awaiting pickup (inferred
 *       handover + LOW alert); held when not packed; other statuses move nothing; never regresses; a tenant with
 *       the switch off ignores shipment_status entirely
 *   m*  Mark delivered: bulk, only with_courier legs, one audit row per delivered order, idempotent, roles
 *   s*  order status "Delivered · Aramex", the "With <carrier>" list filter, the timeline's source
 *   r*  returned parcels: a generic waybill scan → leg returned, pieces expected, grading + restock unchanged;
 *       piece-first scan; an untracked generic parcel gets unit rows + Return To Receive
 *   q*  portal: a delivered generic order is never eligible; no type 25 / 30 booking is reachable
 *   a*  a cancelled order with a generic parcel out: the neutral generic alert, never "cancel the AWB in Bosta"
 * (Bosta isolation: GenericG3IsolationGoldenTest.)
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GenericCourierG3Test {

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
    @Autowired TestRestTemplate      rest;
    @Autowired JdbcTemplate          jdbc;
    @Autowired JwtService            jwt;
    @Autowired ObjectMapper          mapper;
    @Autowired PickupSessionService  pickups;
    @Autowired ReturnSessionService  returns;
    @Autowired ExceptionService      exceptions;
    @Autowired FulfillmentTrackingCapture capture;
    @Autowired GenericDeliveryService delivery;
    @Autowired PortalService         portal;
    @Autowired ReturnPickupBookingService booking;
    @MockBean  BostaGateway          bostaGateway;
    @MockBean  BostaV2Client         bostaV2;
    @MockBean  ShopifyGateway        shopifyGateway;
    @MockBean  JobScheduler          jobScheduler;

    @AfterEach void clear() { TenantContext.clear(); }

    // ── fixtures ─────────────────────────────────────────────────────────────────────────────────

    /** A tenant: Bosta account, the switch on (unless off), a Main warehouse, owner / manager / worker, one variant. */
    final class Shop {
        final UUID tenant, store, location, owner, manager, worker, variant;
        final String slug;
        int seq = 0;
        Shop(String name, boolean on) {
            tenant = UUID.randomUUID(); store = UUID.randomUUID(); location = UUID.randomUUID();
            slug = "g3-" + name.toLowerCase() + "-" + tenant.toString().substring(0, 6);
            jdbc.update("INSERT INTO tenants (id, name, generic_courier_enabled, portal_slug, portal_enabled) VALUES (?, ?, ?, ?, true)",
                tenant, "G3 " + name, on, slug);
            jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status) VALUES (?, ?, 'shopify', ?, 'disconnected')",
                store, tenant, slug + ".myshopify.com");
            jdbc.update("INSERT INTO locations (id, tenant_id, name, is_fulfillment) VALUES (?, ?, 'Main', true)", location, tenant);
            jdbc.update("INSERT INTO courier_accounts (tenant_id, provider, api_key_encrypted, webhook_secret, status) " +
                "VALUES (?, 'bosta', 'x', ?, 'active')", tenant, "wh-" + tenant);
            owner = user("Owner", "owner"); manager = user("Mgr", "manager"); worker = user("Fatma", "worker");
            UUID product = jdbc.queryForObject("INSERT INTO products (tenant_id, store_id, external_id, title, status) " +
                "VALUES (?, ?, ?, 'Linen Dress', 'active') RETURNING id", UUID.class, tenant, store, "gid://shopify/Product/" + tenant);
            variant = jdbc.queryForObject("INSERT INTO variants (tenant_id, product_id, external_id, sku, title) " +
                "VALUES (?, ?, ?, 'LD-M', 'M') RETURNING id", UUID.class, tenant, product, "gid://shopify/ProductVariant/" + tenant);
        }
        UUID user(String name, String role) {
            return jdbc.queryForObject("INSERT INTO users (tenant_id, name, email, password_hash, role, active) " +
                "VALUES (?, ?, ?, 'x', ?::user_role, true) RETURNING id", UUID.class, tenant, name,
                name.toLowerCase() + "-" + UUID.randomUUID() + "@test.com", role);
        }
        Ord order(String status) {
            int k = ++seq;
            String number = "#" + (5000 + k);
            String ext = "gid://shopify/Order/" + tenant.toString().substring(0, 8) + k;
            UUID id = jdbc.queryForObject(
                "INSERT INTO orders (tenant_id, store_id, external_id, number, status, customer_name, customer_phone, cod_amount, " +
                "    payment_method, placed_at) VALUES (?, ?, ?, ?, ?::order_status, 'Mona Adel', ?, 900, 'cod', now() - interval '3 days') " +
                "RETURNING id", UUID.class, tenant, store, ext, number, status, "0101222" + (5000 + k));
            UUID item = jdbc.queryForObject("INSERT INTO order_items (tenant_id, order_id, variant_id, quantity) VALUES (?, ?, ?, 1) RETURNING id",
                UUID.class, tenant, id, variant);
            return new Ord(id, item, ext, number, "0101222" + (5000 + k));
        }
        /** A generic forward leg as the G1 writer stores it, in {@code state}. */
        UUID generic(Ord o, String tn, String carrier, String state) {
            return jdbc.queryForObject("INSERT INTO shipments (tenant_id, order_id, provider, tracking_number, tracking_key, " +
                "internal_state, source, shopify_fulfillment_id, carrier_name) VALUES (?, ?, 'generic', ?, ?, ?::shipment_internal_state, " +
                "'shopify_fulfillment', ?, ?) RETURNING id", UUID.class, tenant, o.id(), tn, GenericTrackingKey.of(tn), state,
                "F-" + o.id(), carrier);
        }
        UUID bosta(Ord o, String tn, String state) {
            return jdbc.queryForObject("INSERT INTO shipments (tenant_id, order_id, provider, tracking_number, internal_state) " +
                "VALUES (?, ?, 'bosta', ?, ?::shipment_internal_state) RETURNING id", UUID.class, tenant, o.id(), tn, state);
        }
        /** A tracked piece on the order's line, allocation packed, in {@code status}; its id. */
        String piece(Ord o, String status, String alloc) {
            String id = UlidGenerator.generate();
            jdbc.update("INSERT INTO pieces (id, tenant_id, variant_id, barcode, short_code, status, current_order_id, " +
                "current_location_id, last_event_at) VALUES (?, ?, ?, ?, ?, ?::piece_status, ?, ?, now())",
                id, tenant, variant, "PC-" + id, "G" + String.format("%06d", Math.floorMod(id.hashCode(), 999_999) + 1),
                status, o.id(), location);
            jdbc.update("INSERT INTO allocations (tenant_id, order_item_id, piece_id, status) VALUES (?, ?, ?, ?::allocation_status)",
                tenant, o.item(), id, alloc);
            return id;
        }
        /** Shopify orders/updated: one fulfillment of the order with its tracking number and shipment_status. */
        void shopify(Ord o, String carrier, String tn, String shipmentStatus) {
            ObjectMapper m = new ObjectMapper();
            com.fasterxml.jackson.databind.node.ObjectNode f = m.createObjectNode();
            f.put("id", "F-" + o.id());
            f.put("status", "success");
            f.put("tracking_company", carrier);
            f.put("tracking_number", tn);
            f.put("created_at", "2026-10-18T10:00:00+03:00");
            if (shipmentStatus != null) f.put("shipment_status", shipmentStatus);
            com.fasterxml.jackson.databind.node.ObjectNode payload = m.createObjectNode();
            payload.putArray("fulfillments").add(f);
            TenantContext.runAs(tenant, () -> capture.capture(store, o.ext(), payload));
        }
        String token(String role) {
            UUID u = switch (role) { case "owner" -> owner; case "manager" -> manager; default -> worker; };
            return jwt.issueAccessToken(u, tenant, role);
        }
        <T> T as(java.util.function.Supplier<T> s) { return TenantContext.runAs(tenant, s::get); }
    }

    record Ord(UUID id, UUID item, String ext, String number, String phone) {}

    private String state(UUID shipment) {
        return jdbc.queryForObject("SELECT internal_state::text FROM shipments WHERE id = ?", String.class, shipment);
    }

    private List<String> history(UUID shipment) {
        return jdbc.queryForList("SELECT internal_state || ':' || coalesce(source, '-') FROM shipment_status_history " +
            "WHERE shipment_id = ? ORDER BY occurred_at, id", String.class, shipment);
    }

    private String pieceStatus(String piece) {
        return jdbc.queryForObject("SELECT status::text FROM pieces WHERE id = ?", String.class, piece);
    }

    private List<Map<String, Object>> alerts(Shop s, String type) {
        return s.as(() -> exceptions.detectAllOpen()).stream().filter(e -> type.equals(e.get("type"))).toList();
    }

    private ResponseEntity<String> http(Shop s, String role, HttpMethod method, String path, Object body) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(s.token(role));
        h.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange("http://localhost:" + port + path, method, new HttpEntity<>(body, h), String.class);
    }

    // ── p: Pickups ───────────────────────────────────────────────────────────────────────────────

    @Test
    void p1_genericSession_oneCarrierPerSession_closeHandsOver_manifestNamesCarrier() {
        Shop s = new Shop("p1", true);
        Ord a = s.order("packed"), b = s.order("packed"), my = s.order("packed"), bo = s.order("packed");
        UUID legA = s.generic(a, "34127840151", "Aramex", "created");
        UUID legB = s.generic(b, "34127840162", "aramex ", "created");     // same carrier, other spelling
        s.generic(my, "MY-778899", "Mylerz", "created");
        s.bosta(bo, "8123456789", "created");
        String pa = s.piece(a, "packed", "packed"), pb = s.piece(b, "packed", "packed");
        s.piece(my, "packed", "packed"); s.piece(bo, "packed", "packed");

        UUID pickup = pickups.openSession(s.tenant, s.worker, LocalDate.now(), "10:00 to 13:00", null);
        PickupSessionService.ScanResult r1 = pickups.scan(s.tenant, pickup, s.worker, "34127840151");
        assertThat(r1.outcome()).isEqualTo(PickupSessionService.ScanOutcome.ACCEPTED);
        assertThat(r1.entry().trackingNumber()).isEqualTo("34127840151");
        assertThat(r1.entry().carrierName()).isEqualTo("Aramex");
        assertThat(pickups.scan(s.tenant, pickup, s.worker, "34127840162").outcome())
            .isEqualTo(PickupSessionService.ScanOutcome.ACCEPTED);

        PickupSessionService.ScanResult mylerz = pickups.scan(s.tenant, pickup, s.worker, "MY-778899");
        assertThat(mylerz.outcome()).isEqualTo(PickupSessionService.ScanOutcome.WRONG_CARRIER);
        assertThat(mylerz.carrier()).isEqualTo("Aramex");
        PickupSessionService.ScanResult bostaScan = pickups.scan(s.tenant, pickup, s.worker, "8123456789");
        assertThat(bostaScan.outcome()).isEqualTo(PickupSessionService.ScanOutcome.WRONG_CARRIER);
        assertThat(bostaScan.carrier()).isEqualTo("Aramex");

        // HTTP: the refusal names the session's courier
        ResponseEntity<String> http = http(s, "worker", HttpMethod.POST, "/api/v1/pickup-sessions/" + pickup + "/scans",
            Map.of("trackingNumber", "MY-778899"));
        assertThat(http.getBody()).contains("\"outcome\":\"WRONG_CARRIER\"").contains("\"carrier\":\"Aramex\"");

        PickupSessionService.SessionDetail before = pickups.getSession(s.tenant, pickup);
        assertThat(before.scannedCount()).isEqualTo(2);
        assertThat(before.carrier()).isEqualTo("Aramex");

        PickupSessionService.CloseResult closed = pickups.closeSession(s.tenant, pickup, s.worker);
        assertThat(closed.shipmentsClosed()).isEqualTo(2);
        assertThat(closed.pieceExceptions()).isEmpty();
        for (UUID leg : List.of(legA, legB)) {
            assertThat(state(leg)).isEqualTo("with_courier");
            assertThat(history(leg)).containsExactly("with_courier:pickup_scan");
            assertThat(jdbc.queryForObject("SELECT custody_locked_by_scan AND handed_over_at IS NOT NULL FROM shipments WHERE id = ?",
                Boolean.class, leg)).isTrue();
        }
        assertThat(pieceStatus(pa)).isEqualTo("with_courier");
        assertThat(pieceStatus(pb)).isEqualTo("with_courier");

        PickupSessionService.ManifestData manifest = pickups.getManifestData(s.tenant, pickup);
        assertThat(manifest.carrier()).isEqualTo("Aramex");
        assertThat(manifest.shipments()).extracting(PickupSessionService.ScanEntry::carrierName).containsOnly("Aramex", "aramex");
        assertThat(http(s, "worker", HttpMethod.GET, "/api/v1/pickup-sessions/" + pickup + "/manifest", null).getBody())
            .contains("\"carrier\":\"Aramex\"");
    }

    @Test
    void p2_bostaSession_refusesAGenericParcel_namingBosta_andKeepsNoCarrierKey() {
        Shop s = new Shop("p2", true);
        Ord bo = s.order("packed"), a = s.order("packed");
        UUID bostaLeg = s.bosta(bo, "8123450001", "created");
        s.generic(a, "34127840999", "Aramex", "created");
        s.piece(bo, "packed", "packed"); s.piece(a, "packed", "packed");

        UUID pickup = pickups.openSession(s.tenant, s.worker, LocalDate.now(), "10:00 to 13:00", null);
        assertThat(pickups.scan(s.tenant, pickup, s.worker, "D-07-8123450001").outcome())
            .isEqualTo(PickupSessionService.ScanOutcome.ACCEPTED);
        PickupSessionService.ScanResult r = pickups.scan(s.tenant, pickup, s.worker, "34127840999");
        assertThat(r.outcome()).isEqualTo(PickupSessionService.ScanOutcome.WRONG_CARRIER);
        assertThat(r.carrier()).isEqualTo("Bosta");

        assertThat(http(s, "worker", HttpMethod.GET, "/api/v1/pickup-sessions/" + pickup, null).getBody())
            .doesNotContain("\"carrier\"").doesNotContain("carrierName");
        pickups.closeSession(s.tenant, pickup, s.worker);
        assertThat(history(bostaLeg)).containsExactly("with_courier:-");
        assertThat(jdbc.queryForObject("SELECT handed_over_at FROM shipments WHERE id = ?", Object.class, bostaLeg))
            .as("Bosta legs never get handed_over_at").isNull();
    }

    // ── d: delivered from Shopify ────────────────────────────────────────────────────────────────

    @Test
    void d1_shopifyDelivered_fromWithCourier_deliversThroughTheLedger() {
        Shop s = new Shop("d1", true);
        Ord o = s.order("packed");
        UUID leg = s.generic(o, "34100000001", "Aramex", "with_courier");
        String p = s.piece(o, "with_courier", "packed");

        s.shopify(o, "Aramex", "34100000001", "delivered");

        assertThat(state(leg)).isEqualTo("delivered");
        assertThat(jdbc.queryForObject("SELECT delivered_at IS NOT NULL FROM shipments WHERE id = ?", Boolean.class, leg)).isTrue();
        assertThat(history(leg)).containsExactly("delivered:shopify");
        assertThat(pieceStatus(p)).isEqualTo("delivered");
        assertThat(jdbc.queryForObject("SELECT metadata->>'source' FROM piece_events WHERE piece_id = ? AND to_status = 'delivered'",
            String.class, p)).isEqualTo("shopify");
        assertThat(alerts(s, "generic_delivered_without_pickup")).isEmpty();
    }

    @Test
    void d2_shopifyDelivered_neverScannedOut_packed_inferredHandover_plusLowAlert() {
        Shop s = new Shop("d2", true);
        Ord o = s.order("packed");
        UUID leg = s.generic(o, "34100000002", "Aramex", "created");
        String p = s.piece(o, "packed", "packed");

        s.shopify(o, "Aramex", "34100000002", "delivered");

        assertThat(state(leg)).isEqualTo("delivered");
        assertThat(history(leg)).containsExactly("with_courier:inferred_handover", "delivered:shopify");
        assertThat(jdbc.queryForObject("SELECT handed_over_at IS NOT NULL AND NOT custody_locked_by_scan FROM shipments WHERE id = ?",
            Boolean.class, leg)).isTrue();
        assertThat(pieceStatus(p)).isEqualTo("delivered");
        assertThat(jdbc.queryForList("SELECT from_status::text || '>' || to_status::text FROM piece_events WHERE piece_id = ? " +
            "AND from_status IS NOT NULL ORDER BY occurred_at, id", String.class, p))
            .containsExactly("packed>with_courier", "with_courier>delivered");
        List<Map<String, Object>> a = alerts(s, "generic_delivered_without_pickup");
        assertThat(a).hasSize(1);
        assertThat(a.get(0)).containsEntry("severity", "LOW").containsEntry("reason", "inferred");
        assertThat((String) a.get(0).get("descriptionEn")).contains("without a pickup scan").contains("Aramex");
        assertThat((String) a.get(0).get("descriptionAr")).isNotBlank();
    }

    @Test
    void d3_shopifyDelivered_notPackedInTraced_held_alertSaysSo() {
        Shop s = new Shop("d3", true);
        Ord o = s.order("picking");
        UUID leg = s.generic(o, "34100000003", "Aramex", "created");
        String p = s.piece(o, "reserved", "active");

        s.shopify(o, "Aramex", "34100000003", "delivered");

        assertThat(state(leg)).isEqualTo("created");
        assertThat(history(leg)).isEmpty();
        assertThat(pieceStatus(p)).isEqualTo("reserved");
        List<Map<String, Object>> a = alerts(s, "generic_delivered_without_pickup");
        assertThat(a).hasSize(1);
        assertThat(a.get(0)).containsEntry("reason", "not_packed");
    }

    @Test
    void d4_otherShopifyStatuses_moveNothing_andShowOnTheOrder() {
        Shop s = new Shop("d4", true);
        Ord o = s.order("packed");
        UUID leg = s.generic(o, "34100000004", "Aramex", "with_courier");
        String p = s.piece(o, "with_courier", "packed");
        for (String st : List.of("confirmed", "in_transit", "out_for_delivery", "attempted_delivery", "failure")) {
            s.shopify(o, "Aramex", "34100000004", st);
            assertThat(state(leg)).as(st).isEqualTo("with_courier");
            assertThat(pieceStatus(p)).as(st).isEqualTo("with_courier");
        }
        assertThat(history(leg)).isEmpty();
        String detail = http(s, "owner", HttpMethod.GET, "/api/v1/orders/" + o.id(), null).getBody();
        assertThat(detail).contains("\"shopifyShipmentStatus\":\"failure\"").contains("\"carrierName\":\"Aramex\"");
    }

    @Test
    void d5_afterDelivered_aLaterShopifyStatusIsIgnored_monotonic() {
        Shop s = new Shop("d5", true);
        Ord o = s.order("packed");
        UUID leg = s.generic(o, "34100000005", "Aramex", "with_courier");
        String p = s.piece(o, "with_courier", "packed");
        s.shopify(o, "Aramex", "34100000005", "delivered");
        s.shopify(o, "Aramex", "34100000005", "in_transit");
        s.shopify(o, "Aramex", "34100000005", "failure");
        s.shopify(o, "Aramex", "34100000005", "delivered");     // a replay

        assertThat(state(leg)).isEqualTo("delivered");
        assertThat(history(leg)).containsExactly("delivered:shopify");
        assertThat(pieceStatus(p)).isEqualTo("delivered");
    }

    @Test
    void d6_switchOff_shipmentStatusIgnoredEntirely() {
        Shop s = new Shop("d6", false);
        Ord o = s.order("packed");
        UUID leg = s.generic(o, "34100000006", "Aramex", "with_courier");   // left from when it was on
        String p = s.piece(o, "with_courier", "packed");

        s.shopify(o, "Aramex", "34100000006", "delivered");

        assertThat(state(leg)).isEqualTo("with_courier");
        assertThat(history(leg)).isEmpty();
        assertThat(pieceStatus(p)).isEqualTo("with_courier");
        assertThat(jdbc.queryForObject("SELECT shipment_status FROM order_fulfillment_tracking WHERE order_id = ?",
            String.class, o.id())).as("not even stored").isNull();
    }

    // ── m: Mark delivered ────────────────────────────────────────────────────────────────────────

    @Test
    void m1_markDelivered_bulk_onlyWithCourier_auditPerOrder_idempotent_roles() {
        Shop s = new Shop("m1", true);
        Ord a = s.order("packed"), b = s.order("packed"), c = s.order("packed"), bo = s.order("packed");
        UUID legA = s.generic(a, "34100000011", "Aramex", "with_courier");
        UUID legB = s.generic(b, "34100000012", "Aramex", "with_courier");
        UUID legC = s.generic(c, "34100000013", "Aramex", "created");
        UUID legBo = s.bosta(bo, "8123450011", "with_courier");
        String pa = s.piece(a, "with_courier", "packed");

        ResponseEntity<String> worker = http(s, "worker", HttpMethod.POST, "/api/v1/generic-shipments/mark-delivered",
            Map.of("orderIds", List.of(a.id())));
        assertThat(worker.getStatusCode().value()).isEqualTo(403);

        ResponseEntity<String> r = http(s, "manager", HttpMethod.POST, "/api/v1/generic-shipments/mark-delivered",
            Map.of("orderIds", List.of(a.id(), b.id(), c.id(), bo.id())));
        assertThat(r.getStatusCode().value()).isEqualTo(200);
        assertThat(r.getBody()).contains("\"orderNumber\":\"" + a.number() + "\",\"outcome\":\"DELIVERED\"")
            .contains("\"orderNumber\":\"" + c.number() + "\",\"outcome\":\"NOT_WITH_COURIER\"")
            .contains("\"outcome\":\"NO_GENERIC_SHIPMENT\"");
        assertThat(state(legA)).isEqualTo("delivered");
        assertThat(state(legB)).isEqualTo("delivered");
        assertThat(state(legC)).isEqualTo("created");
        assertThat(state(legBo)).as("a Bosta leg is never marked").isEqualTo("with_courier");
        assertThat(history(legA)).containsExactly("delivered:manual");
        assertThat(pieceStatus(pa)).isEqualTo("delivered");
        assertThat(jdbc.queryForObject("SELECT actor_user_id FROM piece_events WHERE piece_id = ? AND to_status = 'delivered'",
            UUID.class, pa)).isEqualTo(s.manager);
        String audit = "SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = '" + GenericDeliveryService.AUDIT_MARK_DELIVERED + "'";
        assertThat(jdbc.queryForObject(audit, Integer.class, s.tenant)).isEqualTo(2);

        List<GenericDeliveryService.MarkResult> again = s.as(() -> delivery.markDelivered(List.of(a.id(), b.id()), s.owner));
        assertThat(again).extracting(GenericDeliveryService.MarkResult::outcome).containsOnly("ALREADY_DELIVERED");
        assertThat(jdbc.queryForObject(audit, Integer.class, s.tenant)).as("idempotent: no new audit row").isEqualTo(2);
        assertThat(history(legA)).containsExactly("delivered:manual");
    }

    // ── s: order status, list filter, timeline ───────────────────────────────────────────────────

    @Test
    void s1_deliveredWithCarrier_listFilter_carriersEndpoint_timelineSource() throws Exception {
        Shop s = new Shop("s1", true);
        Ord a = s.order("packed"), b = s.order("packed"), m = s.order("packed");
        UUID legA = s.generic(a, "34100000021", "Aramex", "created");
        s.generic(b, "34100000022", "Aramex", "with_courier");
        s.generic(m, "MY-1002", "Mylerz", "with_courier");
        s.piece(a, "packed", "packed");
        UUID pickup = pickups.openSession(s.tenant, s.worker, LocalDate.now(), "10:00 to 13:00", null);
        pickups.scan(s.tenant, pickup, s.worker, "34100000021");
        pickups.closeSession(s.tenant, pickup, s.worker);
        s.as(() -> delivery.markDelivered(List.of(a.id()), s.owner));

        JsonNode list = mapper.readTree(http(s, "owner", HttpMethod.GET, "/api/v1/orders?size=50", null).getBody());
        JsonNode rowA = null;
        for (JsonNode n : list.path("items")) if (a.number().equals(n.path("number").asText())) rowA = n;
        assertThat(rowA.path("derivedStatus").path("primaryKey").asText()).isEqualTo("status.delivered_with_carrier");
        assertThat(rowA.path("shippingBadge").path("carrier").asText()).isEqualTo("Aramex");

        JsonNode filtered = mapper.readTree(http(s, "owner", HttpMethod.GET,
            "/api/v1/orders?size=50&deliveryState=with_courier&carrier=ARAMEX", null).getBody());
        assertThat(filtered.path("total").asInt()).isEqualTo(1);
        assertThat(filtered.path("items").get(0).path("number").asText()).isEqualTo(b.number());

        assertThat(http(s, "manager", HttpMethod.GET, "/api/v1/generic-shipments/carriers", null).getBody())
            .contains("{\"carrier\":\"Aramex\",\"withCourier\":1}").contains("{\"carrier\":\"Mylerz\",\"withCourier\":1}");

        JsonNode timeline = mapper.readTree(http(s, "owner", HttpMethod.GET, "/api/v1/orders/" + a.id() + "/timeline", null).getBody());
        List<String> sources = new ArrayList<>();
        for (JsonNode n : timeline) if (n.has("source")) sources.add(n.path("eventKey").asText() + "/" + n.path("source").asText());
        assertThat(sources).containsExactly("shipment_state_with_courier/pickup_scan", "shipment_state_delivered/manual");
        assertThat(history(legA)).containsExactly("with_courier:pickup_scan", "delivered:manual");
    }

    // ── r: returned parcels ──────────────────────────────────────────────────────────────────────

    @Test
    void r1_genericWaybillScan_legReturned_piecesExpected_gradeAndRestock() {
        Shop s = new Shop("r1", true);
        Ord o = s.order("packed");
        UUID leg = s.generic(o, "34100000031", "Aramex", "with_courier");
        String p = s.piece(o, "with_courier", "packed");
        UUID rs = s.as(() -> returns.createSession(null, s.worker));

        Map<String, Object> awb = s.as(() -> returns.scan(rs, "34100000031", s.location, s.worker));
        assertThat(awb).containsEntry("scanType", "awb").containsEntry("awb", "34100000031").containsEntry("carrier", "Aramex");
        assertThat(state(leg)).isEqualTo("returned");
        assertThat(history(leg)).containsExactly("returned:return_scan");
        assertThat(jdbc.queryForObject("SELECT returned_at IS NOT NULL FROM shipments WHERE id = ?", Boolean.class, leg)).isTrue();

        Map<String, Object> item = s.as(() -> returns.scan(rs, "PC-" + p, s.location, s.worker));
        assertThat(item.get("unexpected")).isEqualTo(false);
        assertThat(item.get("on_scanned_awb")).isEqualTo(true);
        assertThat(pieceStatus(p)).isEqualTo("return_pending_inspection");
        assertThat(alerts(s, "unexpected_return")).isEmpty();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> parcels = (List<Map<String, Object>>) s.as(() -> returns.getSession(rs)).get("parcels");
        assertThat(parcels).hasSize(1);
        assertThat(parcels.get(0)).containsEntry("carrier", "Aramex").containsEntry("returnedToSender", true)
            .containsEntry("bosta", null);

        s.as(() -> returns.disposition(rs, p, "restock", null, s.location, s.worker, "worker"));
        assertThat(pieceStatus(p)).isEqualTo("available");
        long deadline = System.currentTimeMillis() + 5000;
        Integer claims = 0;
        while (System.currentTimeMillis() < deadline && claims == 0) {
            claims = jdbc.queryForObject("SELECT count(*) FROM shopify_inventory_adjustments WHERE tenant_id = ? " +
                "AND trigger_type = 'return_inspection' AND trigger_id LIKE ?", Integer.class, s.tenant, p + "%");
        }
        assertThat(claims).as("the existing restock path records its Shopify +1 claim").isEqualTo(1);
        assertThat(s.as(() -> returns.close(rs, s.worker))).containsEntry("restockedCount", 1);
    }

    @Test
    void r2_pieceFirstScan_deliveredGenericLeg_expected_legReturned() {
        Shop s = new Shop("r2", true);
        Ord o = s.order("packed");
        jdbc.update("UPDATE orders SET placed_at = now() - interval '90 days' WHERE id = ?", o.id());   // out of window
        UUID leg = s.generic(o, "34100000032", "Aramex", "delivered");
        String p = s.piece(o, "delivered", "packed");
        UUID rs = s.as(() -> returns.createSession(null, s.worker));

        Map<String, Object> item = s.as(() -> returns.scan(rs, "PC-" + p, s.location, s.worker));
        assertThat(item.get("unexpected")).isEqualTo(false);
        assertThat(pieceStatus(p)).isEqualTo("return_pending_inspection");
        assertThat(state(leg)).isEqualTo("returned");
        assertThat(history(leg)).containsExactly("returned:return_scan");
        assertThat(jdbc.queryForObject("SELECT metadata->>'return_kind' FROM piece_events WHERE piece_id = ? AND event_type = 'return_received'",
            String.class, p)).isEqualTo("rto");
    }

    @Test
    void r3_untrackedGenericParcel_unitRows_markArrived_returnToReceive() {
        Shop s = new Shop("r3", true);
        Ord o = s.order("packed");
        jdbc.update("UPDATE order_items SET quantity = 2 WHERE id = ?", o.item());
        UUID leg = s.generic(o, "MY-55001", "Mylerz", "delivered");
        UUID rs = s.as(() -> returns.createSession(null, s.worker));

        s.as(() -> returns.scan(rs, "my55001", s.location, s.worker));
        assertThat(state(leg)).isEqualTo("returned");
        @SuppressWarnings("unchecked")
        Map<String, Object> card = ((List<Map<String, Object>>) s.as(() -> returns.getSession(rs)).get("parcels")).get(0);
        assertThat(card).containsEntry("carrier", "Mylerz").containsEntry("returnedToSender", true)
            .containsEntry("canMarkReceived", true);
        assertThat((List<?>) card.get("untrackedUnits")).hasSize(2);

        s.as(() -> { returns.unitArrived(rs, leg, o.item(), 1, "sellable", s.worker); return null; });
        assertThat(alerts(s, "return_to_receive")).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT return_intake_outcome FROM shipments WHERE id = ?", String.class, leg))
            .isEqualTo("untracked_units_arrived");
    }

    // ── q: portal ────────────────────────────────────────────────────────────────────────────────

    @Test
    void q1_deliveredGenericOrder_neverPortalEligible_bostaControlIs() {
        Shop s = new Shop("q1", true);
        Ord g = s.order("delivered"), bo = s.order("delivered");
        UUID gl = s.generic(g, "34100000041", "Aramex", "with_courier");
        s.piece(g, "with_courier", "packed");
        s.as(() -> delivery.markDelivered(List.of(g.id()), s.owner));
        assertThat(state(gl)).isEqualTo("delivered");
        assertThat(jdbc.queryForObject("SELECT delivered_at IS NOT NULL FROM shipments WHERE id = ?", Boolean.class, gl)).isTrue();
        UUID bl = s.bosta(bo, "8123450041", "delivered");
        jdbc.update("UPDATE shipments SET delivered_at = now() - interval '2 days' WHERE id = ?", bl);
        s.piece(bo, "delivered", "packed");

        assertThat(portal.lookup(s.slug, g.number().substring(1), g.phone()).orElseThrow().outcome().toString())
            .isNotEqualTo("SUCCESS");
        assertThat(portal.lookup(s.slug, bo.number().substring(1), bo.phone()).orElseThrow().outcome().toString())
            .as("control: the Bosta order next to it is eligible").isEqualTo("SUCCESS");
    }

    @Test
    void q2_noBostaBookingReachable_forAGenericOrder() {
        Shop s = new Shop("q2", true);
        Ord g = s.order("delivered");
        s.generic(g, "34100000042", "Aramex", "delivered");
        UUID request = jdbc.queryForObject(
            "INSERT INTO return_requests (tenant_id, order_id, type, status, reference, pickup_address_source) " +
            "VALUES (?, ?, 'exchange', 'approved', ?, 'order') RETURNING id", UUID.class, s.tenant, g.id(), "RR-G3Q2");

        assertThatThrownBy(() -> s.as(() -> { booking.bookNow(request); return null; }))
            .isInstanceOf(ResponseStatusException.class).hasMessageContaining("another courier");
        booking.book(request, s.tenant);
        assertThat(jdbc.queryForObject("SELECT booking_status FROM return_requests WHERE id = ?", String.class, request))
            .as("nothing claimed").isNull();
        verify(bostaV2, never()).createReturnPickup(any(), any());
        verify(bostaV2, never()).createExchange(any(), any());
    }

    // ── an: analytics ────────────────────────────────────────────────────────────────────────────

    @Test
    void an1_genericDeliveredAndReturned_neverInBostaDeliveryFigures() throws Exception {
        Shop s = new Shop("an1", true);
        Ord bo = s.order("packed"), gd = s.order("packed"), gr = s.order("packed");
        UUID bl = s.bosta(bo, "8123450061", "delivered");
        jdbc.update("UPDATE shipments SET delivered_at = now() - interval '1 day' WHERE id = ?", bl);
        jdbc.update("INSERT INTO shipment_status_history (tenant_id, shipment_id, internal_state, occurred_at) " +
            "VALUES (?, ?, 'with_courier', now() - interval '2 days'), (?, ?, 'delivered', now() - interval '1 day')",
            s.tenant, bl, s.tenant, bl);
        s.generic(gd, "34100000061", "Aramex", "with_courier");
        s.piece(gd, "with_courier", "packed");
        s.as(() -> delivery.markDelivered(List.of(gd.id()), s.owner));
        UUID grl = s.generic(gr, "34100000062", "Aramex", "with_courier");
        String pr = s.piece(gr, "with_courier", "packed");
        UUID rs = s.as(() -> returns.createSession(null, s.worker));
        s.as(() -> returns.scan(rs, "PC-" + pr, s.location, s.worker));
        assertThat(state(grl)).isEqualTo("returned");

        JsonNode summary = mapper.readTree(http(s, "owner", HttpMethod.GET, "/api/v1/analytics/delivery/summary?period=30d", null).getBody());
        assertThat(summary.path("current").path("delivered").asInt()).as("Bosta delivered only").isEqualTo(1);
        assertThat(summary.path("current").path("failed").asInt()).as("a generic return is not a Bosta failure").isZero();
        assertThat(summary.path("current").path("handedOrders").asInt()).as("Bosta handovers only").isEqualTo(1);

        JsonNode orders = mapper.readTree(http(s, "owner", HttpMethod.GET, "/api/v1/analytics/orders?size=200&period=30d", null).getBody());
        Map<String, String> status = new HashMap<>();
        for (JsonNode o : orders.path("orders")) status.put(o.path("name").asText(), o.path("deliveryStatus").path("key").asText());
        assertThat(status.get(bo.number())).isEqualTo("delivered");
        assertThat(status.get(gd.number())).as("a generic delivered order is never a Bosta delivery").isNotEqualTo("delivered");
        assertThat(status.get(gr.number())).as("a generic returned order is never a Bosta refusal").isNotEqualTo("refused");
    }

    // ── a: alerts ────────────────────────────────────────────────────────────────────────────────

    @Test
    void a1_cancelledOrder_genericParcelOut_neutralAlert_notTheBostaOne() {
        Shop s = new Shop("a1", true);
        Ord o = s.order("cancelled");
        s.generic(o, "34100000051", "Aramex", "with_courier");
        Ord bo = s.order("cancelled");
        s.bosta(bo, "8123450051", "with_courier");

        List<Map<String, Object>> g = alerts(s, "generic_cancelled_live_shipment");
        assertThat(g).hasSize(1);
        assertThat(g.get(0)).containsEntry("severity", "HIGH").containsEntry("order_number", o.number());
        assertThat((String) g.get(0).get("descriptionEn")).contains("Aramex").doesNotContain("Bosta");
        assertThat(alerts(s, "cancelled_live_shipment")).extracting(e -> e.get("order_number")).containsExactly(bo.number());
    }
}
