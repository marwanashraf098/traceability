package com.traceability;

import com.traceability.identity.JwtService;
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

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Deleted-in-Bosta slice 2 — alerts that only clear themselves.
 *
 *   co1 — each of the four condition-only types carries its clearsWhen code; stuck_shipment and
 *         cancelled_but_delivered carry none
 *   co2 — POST /exceptions/resolve on each of the four → 409 CONDITION_ONLY, no exception_resolutions row,
 *         the alert is still listed
 *   co3 — POST /exceptions/resolve on stuck_shipment still works (204, row written, alert gone)
 *   co3b — cancelled_but_delivered keeps Resolve (review 2026-10-19): 204, row written, and the alert is
 *          gone (its detector respects resolutions)
 *   co4 — cancelled_live_shipment clears once Traced confirms the delivery was deleted in Bosta
 *         (provider_deleted_at, slice 4), as its clearsWhen text says
 *   co5 — exchange_unmapped_state: each of the six states ExchangeStateInterpreter handles raises nothing;
 *         an unknown state still does (one shared set, KNOWN_FORWARD_LEG_STATES)
 *
 * Production-shaped fixtures: bare numeric tracking numbers, real enum values.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ConditionOnlyAlertsTest {

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

    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;
    @Autowired JdbcTemplate     jdbc;
    @Autowired JwtService       jwt;
    @MockBean  JobScheduler     jobScheduler;

    static final Map<String, String> EXPECTED = Map.of(
        "cancelled_live_shipment", "BOSTA_LEG_ENDED",
        "guided_unpack",           "ORDER_UNPACKED",
        "exchange_needs_mapping",  "EXCHANGE_MAPPED",
        "exchange_unmapped_state", "BOSTA_STATE_RECOGNISED");

    UUID tenantId, storeId, managerId, liveShipmentId, stuckShipmentId;
    String token;

    @BeforeAll
    void seed() {
        tenantId  = UUID.randomUUID();
        storeId   = UUID.randomUUID();
        managerId = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, 'ConditionOnlyTenant')", tenantId);
        jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role) " +
                    "VALUES (?, ?, 'Manager', 'manager@condition-only.test', '$2a$x', 'manager')", managerId, tenantId);
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status) " +
                    "VALUES (?, ?, 'shopify', 'condition-only.myshopify.com', 'disconnected')", storeId, tenantId);
        token = jwt.issueAccessToken(managerId, tenantId, "manager");

        // cancelled_live_shipment: cancelled order, its Bosta delivery still 'created'.
        liveShipmentId = forwardShipment(order("cancelled", "#CO1001"), "6979330981", "created", null);
        // cancelled_but_delivered: cancelled order, delivery delivered.
        forwardShipment(order("cancelled", "#CO1002"), "6979330982", "delivered", null);
        // guided_unpack: cancel requested after packing.
        UUID packed = order("packed", "#CO1003");
        jdbc.update("UPDATE orders SET cancel_requested_at = now() WHERE id = ?", packed);
        // exchange_needs_mapping: a dashboard exchange waiting for its sizes.
        jdbc.update("INSERT INTO exchanges (tenant_id, tracking_number, status, raw) " +
                    "VALUES (?, '6979330983', 'needs_mapping', '{}'::jsonb)", tenantId);
        // exchange_unmapped_state: the exchange's forward leg reported a state the interpreter doesn't know.
        UUID outbound = order("awaiting_pickup", "#CO1004");
        forwardShipment(outbound, "6979330984", "created", "{\"state\":{\"value\":\"Rescheduled\"}}");
        jdbc.update("INSERT INTO exchanges (tenant_id, tracking_number, status, raw, outbound_order_id) " +
                    "VALUES (?, '6979330984', 'matched', '{}'::jsonb, ?)", tenantId, outbound);
        // stuck_shipment: no courier update for 10 days.
        stuckShipmentId = forwardShipment(order("with_courier", "#CO1005"), "6979330985", "with_courier", null);
        jdbc.update("UPDATE shipments SET last_synced_at = now() - interval '10 days' WHERE id = ?", stuckShipmentId);
    }

    @Test @Order(1)
    void co1_conditionOnlyAlertsCarryClearsWhen_othersDoNot() {
        List<Map<String, Object>> items = items();
        for (var e : EXPECTED.entrySet()) {
            Map<String, Object> item = only(items, e.getKey());
            assertThat(item.get("clearsWhen")).as(e.getKey()).isEqualTo(e.getValue());
        }
        assertThat(only(items, "stuck_shipment")).doesNotContainKey("clearsWhen");
        assertThat(only(items, "cancelled_but_delivered")).doesNotContainKey("clearsWhen");
    }

    @Test @Order(2)
    void co2_resolveOnConditionOnly_is409_noRowWritten() {
        List<Map<String, Object>> items = items();
        for (String type : EXPECTED.keySet()) {
            String key = (String) only(items, type).get("subject_key");
            ResponseEntity<String> r = resolve(type, key);
            assertThat(r.getStatusCode().value()).as(type).isEqualTo(409);
            assertThat(r.getBody()).as(type).contains("CONDITION_ONLY");
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM exception_resolutions WHERE tenant_id = ?",
            Integer.class, tenantId)).isZero();
        List<Map<String, Object>> after = items();
        for (String type : EXPECTED.keySet()) assertThat(only(after, type)).as(type).isNotNull();
    }

    @Test @Order(3)
    void co3_resolveOnStuckShipment_stillWorks() {
        String key = (String) only(items(), "stuck_shipment").get("subject_key");
        assertThat(resolve("stuck_shipment", key).getStatusCode().value()).isEqualTo(204);
        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM exception_resolutions WHERE tenant_id = ? AND exception_type = 'stuck_shipment'",
            Integer.class, tenantId)).isEqualTo(1);
        assertThat(items().stream().filter(i -> "stuck_shipment".equals(i.get("type")))).isEmpty();
    }

    @Test @Order(3)
    void co3b_resolveOnCancelledButDelivered_hidesIt() {
        String key = (String) only(items(), "cancelled_but_delivered").get("subject_key");
        assertThat(resolve("cancelled_but_delivered", key).getStatusCode().value()).isEqualTo(204);
        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM exception_resolutions WHERE tenant_id = ? AND exception_type = 'cancelled_but_delivered'",
            Integer.class, tenantId)).isEqualTo(1);
        assertThat(items().stream().filter(i -> "cancelled_but_delivered".equals(i.get("type")))).isEmpty();
    }

    @Test @Order(5)
    void co5_exchangeUnmappedState_handledStatesRaiseNothing_unknownStillDoes() {
        assertThat(com.traceability.integrations.bosta.ExchangeStateInterpreter.KNOWN_FORWARD_LEG_STATES)
            .containsExactlyInAnyOrder("new", "picked_up", "in_transit", "out_for_exchange", "exchanged_returned",
                "out_for_return");
        // The fixture's exchange (state "Rescheduled") raises it; move its leg through every handled state.
        UUID leg = jdbc.queryForObject("SELECT id FROM shipments WHERE tracking_number = '6979330984'", UUID.class);
        for (String state : com.traceability.integrations.bosta.ExchangeStateInterpreter.KNOWN_FORWARD_LEG_STATES) {
            String shown = state.equals("out_for_exchange") ? "Out_For_Exchange " : state;   // Bosta casing / spacing
            jdbc.update("UPDATE shipments SET raw = jsonb_build_object('state', jsonb_build_object('value', ?::text)) " +
                        "WHERE id = ?", shown, leg);
            assertThat(items().stream().filter(i -> "exchange_unmapped_state".equals(i.get("type"))))
                .as(state).isEmpty();
        }
        jdbc.update("UPDATE shipments SET raw = '{\"state\":{\"value\":\"Rescheduled\"}}'::jsonb WHERE id = ?", leg);
        assertThat(only(items(), "exchange_unmapped_state").get("clearsWhen")).isEqualTo("BOSTA_STATE_RECOGNISED");
    }

    @Test @Order(6)
    void co4_cancelledLiveShipment_clearsWhenConfirmedDeletedInBosta() {
        assertThat(only(items(), "cancelled_live_shipment")).isNotNull();
        jdbc.update("UPDATE shipments SET provider_deleted_at = now(), provider_deleted_reason = 'deleted_in_bosta' " +
                    "WHERE id = ?", liveShipmentId);
        assertThat(items().stream().filter(i -> "cancelled_live_shipment".equals(i.get("type")))).isEmpty();
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private UUID order(String status, String number) {
        return jdbc.queryForObject(
            "INSERT INTO orders (tenant_id, store_id, external_id, number, status, payment_method, placed_at) " +
            "VALUES (?, ?, ?, ?, ?::order_status, 'cod', now()) RETURNING id",
            UUID.class, tenantId, storeId, "CO-" + UUID.randomUUID(), number, status);
    }

    private UUID forwardShipment(UUID orderId, String tracking, String state, String raw) {
        return jdbc.queryForObject(
            "INSERT INTO shipments (tenant_id, order_id, provider, tracking_number, internal_state, shipment_leg, raw) " +
            "VALUES (?, ?, 'bosta', ?, ?::shipment_internal_state, 'forward', ?::jsonb) RETURNING id",
            UUID.class, tenantId, orderId, tracking, state, raw);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> items() {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        ResponseEntity<Map> r = rest.exchange("http://localhost:" + port + "/api/v1/exceptions?size=200",
            HttpMethod.GET, new HttpEntity<>(h), Map.class);
        assertThat(r.getStatusCode().value()).isEqualTo(200);
        return (List<Map<String, Object>>) r.getBody().get("items");
    }

    private static Map<String, Object> only(List<Map<String, Object>> items, String type) {
        List<Map<String, Object>> of = items.stream().filter(i -> type.equals(i.get("type"))).toList();
        assertThat(of).as(type).hasSize(1);
        return of.get(0);
    }

    private ResponseEntity<String> resolve(String type, String key) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        h.setContentType(MediaType.APPLICATION_JSON);
        Map<String, String> body = Map.of("exceptionType", type, "subjectKey", key, "note", "checked");
        return rest.exchange("http://localhost:" + port + "/api/v1/exceptions/resolve", HttpMethod.POST,
            new HttpEntity<>(body, h), String.class);
    }
}
