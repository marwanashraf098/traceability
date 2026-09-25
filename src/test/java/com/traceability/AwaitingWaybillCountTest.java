package com.traceability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.traceability.identity.JwtService;
import com.traceability.inventory.FulfillService;
import com.traceability.notifications.EmailGateway;
import com.traceability.tenancy.TenantContext;
import org.jobrunr.scheduling.JobScheduler;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.DefaultResponseErrorHandler;
import org.springframework.web.client.RestTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.http.HttpClient;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * GET /api/v1/fulfill/queue/awaiting-waybill-count — open orders held out of the Pick &amp;
 * Pack queue ONLY because no Bosta waybill (forward shipment) exists yet.
 *
 * Counts: open status (new/ready_to_pick/self_pickup_pending), on_hold=false, inside the
 * queue's lookback, not self-pickup, NO forward shipment at all.
 * Never counts: an order with a 'created' shipment (it's in the queue), an order whose
 * shipment exists but has moved on (excluded for a different reason), on-hold, self-pickup,
 * cancelled, or older than the lookback.
 *
 * The equivalence test pins the contract with PICKABLE_ORDERS_FILTER (untouched): an order
 * counted here is absent from getQueue(), and linking a 'created' shipment moves it from
 * this count into the queue — i.e. the shipment is the ONLY thing keeping it out.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AwaitingWaybillCountTest {

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
    @Autowired JdbcTemplate   jdbc;
    @Autowired FulfillService fulfillSvc;
    @Autowired JwtService     jwtSvc;
    @MockBean  JobScheduler   jobScheduler;
    @MockBean  EmailGateway   emailGateway;

    UUID tenantA, storeA, ownerA;
    UUID tenantB, storeB, ownerB;
    final ObjectMapper json = new ObjectMapper();
    RestTemplate rest;

    @BeforeAll
    void setup() {
        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        rest = new RestTemplate(new JdkClientHttpRequestFactory(client));
        rest.setErrorHandler(new DefaultResponseErrorHandler() {
            @Override public boolean hasError(ClientHttpResponse r) { return false; }
        });

        tenantA = UUID.randomUUID(); storeA = UUID.randomUUID(); ownerA = UUID.randomUUID();
        tenantB = UUID.randomUUID(); storeB = UUID.randomUUID(); ownerB = UUID.randomUUID();
        for (Object[] t : new Object[][] {
                {tenantA, storeA, ownerA, "awb-count-a"}, {tenantB, storeB, ownerB, "awb-count-b"}}) {
            jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?)", t[0], t[3]);
            jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status) " +
                        "VALUES (?, ?, 'shopify', ?, 'connected')", t[1], t[0], t[3] + ".myshopify.com");
            jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role) " +
                        "VALUES (?, ?, 'Owner', ?, 'hash', 'owner')", t[2], t[0], "owner@" + t[3] + ".test");
        }
    }

    @AfterEach
    void cleanOrders() {
        TenantContext.clear();
        jdbc.update("DELETE FROM shipments WHERE tenant_id IN (?, ?)", tenantA, tenantB);
        jdbc.update("DELETE FROM orders    WHERE tenant_id IN (?, ?)", tenantA, tenantB);
    }

    @AfterAll
    void teardown() {
        jdbc.update("DELETE FROM users   WHERE tenant_id IN (?, ?)", tenantA, tenantB);
        jdbc.update("DELETE FROM stores  WHERE tenant_id IN (?, ?)", tenantA, tenantB);
        jdbc.update("DELETE FROM tenants WHERE id IN (?, ?)", tenantA, tenantB);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private UUID order(UUID tenant, UUID store, String ext, String status,
                       boolean onHold, boolean selfPickup, String placedAgo) {
        return jdbc.queryForObject(
            "INSERT INTO orders (tenant_id, store_id, external_id, number, status, " +
            "    payment_method, placed_at, on_hold, is_self_pickup) " +
            "VALUES (?, ?, ?, ?, ?::order_status, 'cod', now() - ?::interval, ?, ?) RETURNING id",
            UUID.class, tenant, store, ext, "#" + ext, status, placedAgo, onHold, selfPickup);
    }

    private UUID openNoShipment(UUID tenant, UUID store, String ext) {
        return order(tenant, store, ext, "new", false, false, "1 day");
    }

    private void forwardShipment(UUID tenant, UUID orderId, String tracking, String state) {
        jdbc.update(
            "INSERT INTO shipments (tenant_id, order_id, provider, tracking_number, internal_state, shipment_leg) " +
            "VALUES (?, ?, 'bosta', ?, ?::shipment_internal_state, 'forward')",
            tenant, orderId, tracking, state);
    }

    private int countViaHttp(UUID user, UUID tenant) throws Exception {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(jwtSvc.issueAccessToken(user, tenant, "owner"));
        ResponseEntity<String> r = rest.exchange(
                "http://localhost:" + port + "/api/v1/fulfill/queue/awaiting-waybill-count",
                HttpMethod.GET, new HttpEntity<>(h), String.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        return json.readTree(r.getBody()).get("count").asInt();
    }

    // ── tests ────────────────────────────────────────────────────────────────

    @Test
    void countsOnlyOpenOrdersWithNoShipment_andIsTenantScoped() throws Exception {
        // Tenant A — two genuinely waiting-for-waybill orders (positive control) ...
        openNoShipment(tenantA, storeA, "A-NEW");
        order(tenantA, storeA, "A-RTP", "ready_to_pick", false, false, "2 days");
        // ... and one of every look-alike that must NOT be counted.
        forwardShipment(tenantA, openNoShipment(tenantA, storeA, "A-CREATED"), "900000000001", "created");
        forwardShipment(tenantA, openNoShipment(tenantA, storeA, "A-MOVED"),   "900000000002", "with_courier");
        order(tenantA, storeA, "A-HOLD",   "new",                 true,  false, "1 day");
        order(tenantA, storeA, "A-SELF",   "self_pickup_pending", false, true,  "1 day");
        order(tenantA, storeA, "A-CANCEL", "cancelled",           false, false, "1 day");
        order(tenantA, storeA, "A-OLD",    "new",                 false, false, "40 days");

        // Tenant B — three waiting orders of its own; none may leak into A's count.
        openNoShipment(tenantB, storeB, "B-1");
        openNoShipment(tenantB, storeB, "B-2");
        openNoShipment(tenantB, storeB, "B-3");

        assertThat(countViaHttp(ownerA, tenantA)).isEqualTo(2);
        assertThat(countViaHttp(ownerB, tenantB)).isEqualTo(3);
    }

    @Test
    void emptyTenant_countsZero() throws Exception {
        assertThat(countViaHttp(ownerA, tenantA)).isZero();
    }

    @Test
    void countedOrder_isAbsentFromQueueOnlyBecauseItLacksAShipment() {
        UUID orderId = openNoShipment(tenantA, storeA, "A-EQUIV");
        TenantContext.set(tenantA);

        assertThat(fulfillSvc.getAwaitingWaybillCount()).isEqualTo(1);
        assertThat(fulfillSvc.getQueue()).noneMatch(o -> orderId.equals(o.get("id")));

        // The waybill arrives — nothing else about the order changes.
        forwardShipment(tenantA, orderId, "900000000003", "created");

        assertThat(fulfillSvc.getAwaitingWaybillCount()).isZero();
        assertThat(fulfillSvc.getQueue()).anyMatch(o -> orderId.equals(o.get("id")));
    }
}
