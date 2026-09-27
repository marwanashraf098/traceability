package com.traceability;

import com.traceability.integrations.shopify.ShopifyGateway;
import com.traceability.inventory.*;
import com.traceability.portal.*;
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
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Step 6b (D) — a request-linked courier-return leg whose request has NO item still awaited after
 * a per-item "Arrived" action is intake-complete: return_intake_completed_at + outcome
 * 'request_items_arrived' + the acting user, WITHOUT the leg-level return_to_receive exception.
 * An undo that puts an item back to awaiting clears it. Exchanges & Refunds (listCrpReturns)
 * then shows the leg as received ('resolved'), not "needs inspection".
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class UntrackedLegIntakeTest {

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

    @Autowired JdbcTemplate jdbc;
    @Autowired PortalService portal;
    @Autowired PortalTokenService tokens;
    @Autowired ReturnRequestService requests;
    @Autowired ReturnSessionService sessions;
    @Autowired ShipmentLinkService shipmentLinkService;
    @Autowired ExceptionService exceptions;
    @Autowired PlatformTransactionManager txm;
    @MockBean JobScheduler jobScheduler;
    @MockBean ShopifyGateway shopifyGateway;

    UUID tenantId, storeId, location, owner, worker, shirt, scarf;
    static final String SLUG = "leg-intake-6b";

    @BeforeAll
    void setup() {
        tenantId = UUID.randomUUID(); storeId = UUID.randomUUID(); location = UUID.randomUUID();
        owner = UUID.randomUUID(); worker = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name, portal_slug, portal_enabled) VALUES (?, 'Leg intake', ?, true)", tenantId, SLUG);
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status) VALUES (?, ?, 'shopify', 'leg6b.myshopify.com', 'disconnected')",
            storeId, tenantId);
        jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role, active) VALUES (?, ?, 'Owner', ?, 'h', 'owner', true)",
            owner, tenantId, "o-" + owner + "@t.local");
        jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role, active) VALUES (?, ?, 'Worker', ?, 'h', 'worker', true)",
            worker, tenantId, "w-" + worker + "@t.local");
        jdbc.update("INSERT INTO locations (id, tenant_id, name, is_fulfillment) VALUES (?, ?, 'Main', true)", location, tenantId);
        UUID p = UUID.randomUUID();
        jdbc.update("INSERT INTO products (id, tenant_id, store_id, external_id, title, status) VALUES (?, ?, ?, 'P-6b', 'Linen Shirt', 'active')",
            p, tenantId, storeId);
        shirt = UUID.randomUUID();
        jdbc.update("INSERT INTO variants (id, tenant_id, product_id, external_id, title, sku) VALUES (?, ?, ?, 'V-6b-1', 'White / M', 'S6B1')",
            shirt, tenantId, p);
        scarf = UUID.randomUUID();
        jdbc.update("INSERT INTO variants (id, tenant_id, product_id, external_id, title, sku) VALUES (?, ?, ?, 'V-6b-2', 'White / L', 'S6B2')",
            scarf, tenantId, p);
    }

    @AfterEach
    void cleanup() {
        TenantContext.clear();
        jdbc.update("DELETE FROM exception_resolutions WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM return_session_items WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM return_session_shipments WHERE tenant_id = ?", tenantId);
        jdbc.update("UPDATE shipments SET return_intake_session_id = NULL WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM return_sessions WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM return_request_items WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM return_requests WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM portal_lookup_attempts WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM shipments WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM order_items WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM orders WHERE tenant_id = ?", tenantId);
    }

    @Test
    void d1_drawerArrivalOfTheLastAwaitedItem_stampsTheLeg_noLegException_undoClears_listShowsReceived() {
        Fixture f = fixture("#6b01", 2);
        List<UUID> items = items(f.request());

        TenantContext.set(tenantId);
        requests.itemArrived(f.request(), items.get(0), "sellable", owner);
        assertThat(leg(f.leg()).get("return_intake_completed_at")).as("one item still awaited → no stamp").isNull();
        requests.itemArrived(f.request(), items.get(1), "damaged", owner);
        TenantContext.clear();

        Map<String, Object> leg = leg(f.leg());
        assertThat(leg.get("return_intake_outcome")).isEqualTo("request_items_arrived");
        assertThat(leg.get("return_intake_by")).isEqualTo(owner);
        assertThat(leg.get("return_intake_completed_at")).isNotNull();
        assertThat(problems("return_to_receive")).as("no leg-level exception").isEmpty();
        assertThat(problems("request_item_to_receive")).as("the item-level one covers stock").hasSize(1);
        assertThat(crpRow(f.leg()).get("inspection_state")).isEqualTo("resolved");

        TenantContext.set(tenantId);
        requests.undoItemArrived(f.request(), items.get(1), owner);
        TenantContext.clear();
        leg = leg(f.leg());
        assertThat(leg.get("return_intake_completed_at")).isNull();
        assertThat(leg.get("return_intake_outcome")).isNull();
        assertThat(leg.get("return_intake_by")).isNull();
        assertThat(crpRow(f.leg()).get("inspection_state")).isNotEqualTo("resolved");
        verifyNoInteractions(shopifyGateway);
    }

    @Test
    void d2_sessionArrival_stampsWithTheSession_andTheActingUser() {
        Fixture f = fixture("#6b02", 1);
        UUID s = TenantContext.runAs(tenantId, () -> sessions.createSession(null, owner));
        TenantContext.runAs(tenantId, () -> sessions.scan(s, f.tracking(), location, owner));
        TenantContext.set(tenantId);
        sessions.requestItemArrived(s, items(f.request()).get(0), "sellable", worker);
        TenantContext.clear();
        Map<String, Object> leg = leg(f.leg());
        assertThat(leg.get("return_intake_outcome")).isEqualTo("request_items_arrived");
        assertThat(leg.get("return_intake_by")).isEqualTo(worker);
        assertThat(leg.get("return_intake_session_id")).isEqualTo(s);
        assertThat(problems("return_to_receive")).isEmpty();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    record Fixture(UUID order, UUID request, UUID leg, String tracking) {}

    /** An untracked delivered order, an approved request for {@code units} units, and its linked courier-return leg. */
    private Fixture fixture(String number, int units) {
        String phone = "0106" + String.format("%07d", ThreadLocalRandom.current().nextInt(9_999_999));
        UUID order = jdbc.queryForObject(
            "INSERT INTO orders (tenant_id, store_id, external_id, number, status, payment_method, placed_at, customer_name, customer_phone, pii_source) " +
            "VALUES (?, ?, ?, ?, 'delivered'::order_status, 'cod'::order_payment_method, now(), 'Mona', ?, 'bosta') RETURNING id",
            UUID.class, tenantId, storeId, "gid://shopify/Order/" + UUID.randomUUID(), number, phone);
        jdbc.update("INSERT INTO shipments (tenant_id, order_id, provider, tracking_number, internal_state, shipment_leg, delivered_at) " +
            "VALUES (?, ?, 'bosta', ?, 'delivered'::shipment_internal_state, 'forward', now() - interval '1 day')",
            tenantId, order, String.valueOf(ThreadLocalRandom.current().nextLong(1_000_000_000L, 1_999_999_999L)));
        UUID line = UUID.randomUUID();
        jdbc.update("INSERT INTO order_items (id, tenant_id, order_id, variant_id, quantity) VALUES (?, ?, ?, ?, ?)",
            line, tenantId, order, shirt, units);
        PortalService.SubmitResult res = portal.submit(SLUG, tokens.issue(tenantId, order), new PortalService.SubmitRequest(
            List.of(new PortalService.SubmitLine(null, units, "wrong_size", line)), null, null)).orElseThrow();
        assertThat(res.outcome()).isEqualTo(PortalService.SubmitOutcome.CREATED);
        UUID request = jdbc.queryForObject("SELECT id FROM return_requests WHERE order_id = ?", UUID.class, order);
        TenantContext.runAs(tenantId, () -> requests.approve(request, owner));
        String tracking = String.valueOf(ThreadLocalRandom.current().nextLong(7_000_000_000L, 7_999_999_999L));
        UUID leg = jdbc.queryForObject("INSERT INTO shipments (tenant_id, order_id, provider, tracking_number, internal_state, shipment_leg, created_at) " +
            "VALUES (?, ?, 'bosta', ?, 'returned'::shipment_internal_state, 'return', now() - interval '1 hour') RETURNING id",
            UUID.class, tenantId, order, tracking);
        jdbc.update("UPDATE return_requests SET return_shipment_id = ?, status = 'pickup_booked', link_source = 'traced_booking' WHERE id = ?",
            leg, request);
        return new Fixture(order, request, leg, tracking);
    }

    private List<UUID> items(UUID request) {
        return jdbc.queryForList("SELECT id FROM return_request_items WHERE request_id = ? ORDER BY unit_no", UUID.class, request);
    }

    private Map<String, Object> leg(UUID id) {
        return jdbc.queryForMap("SELECT return_intake_completed_at, return_intake_outcome, return_intake_by, return_intake_session_id " +
            "FROM shipments WHERE id = ?", id);
    }

    private Map<String, Object> crpRow(UUID legId) {
        List<Map<String, Object>> rows = TenantContext.runAs(tenantId,
            () -> new TransactionTemplate(txm).execute(x -> shipmentLinkService.listCrpReturns(0, 100)));
        return rows.stream().filter(r -> legId.toString().equals(r.get("id"))).findFirst().orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> problems(String type) {
        return TenantContext.runAs(tenantId, () -> new TransactionTemplate(txm).execute(x ->
            (List<Map<String, Object>>) exceptions.listExceptions(type, null, 0, 100).get("items")));
    }
}
