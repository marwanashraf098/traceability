package com.traceability;

import com.traceability.inventory.ReturnSessionService;
import com.traceability.inventory.UlidGenerator;
import com.traceability.portal.PortalService;
import com.traceability.portal.ReturnRequestService;
import com.traceability.tenancy.TenantContext;
import org.jobrunr.scheduling.JobScheduler;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Returns Step 4d-1 — the reservation bug found in the 4d diagnosis: before 4d-1 only a
 * rejection ever set return_request_items.active = false, so an approved request kept its
 * pieces reserved forever. Once such a piece came back, was restocked and sold to someone
 * else, the new customer could not return it (the portal lookup showed it as not returnable
 * and a submission tripped return_request_items_one_active_per_piece).
 *
 * Uses only APIs that existed before 4d-1 (portal lookup / submit, approve, return session
 * scan / restock), so it runs unchanged against the pre-4d-1 code — where it fails.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReturnReservationReleaseTest {

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

    private static final String SLUG = "reserve-4d1";

    @Autowired JdbcTemplate         jdbc;
    @Autowired PortalService        portal;
    @Autowired ReturnRequestService requests;
    @Autowired ReturnSessionService sessions;

    @MockBean JobScheduler jobScheduler;

    UUID tenantId, storeId, variantId, locationId, ownerId;

    @BeforeAll
    void setup() {
        tenantId   = UUID.randomUUID();
        storeId    = UUID.randomUUID();
        variantId  = UUID.randomUUID();
        locationId = UUID.randomUUID();
        ownerId    = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name, portal_slug, portal_enabled) VALUES (?, 'Reserve Tenant', ?, true)",
            tenantId, SLUG);
        jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role) " +
                    "VALUES (?, ?, 'Owner', 'owner@reserve.test', 'h', 'owner')", ownerId, tenantId);
        jdbc.update("INSERT INTO locations (id, tenant_id, name) VALUES (?, ?, 'Main')", locationId, tenantId);
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status) " +
                    "VALUES (?, ?, 'shopify', 'reserve.myshopify.com', 'disconnected')", storeId, tenantId);
        jdbc.update("INSERT INTO products (id, tenant_id, store_id, external_id, title, status) " +
                    "VALUES (?, ?, ?, 'P-RS', 'Linen Shirt', 'active')", productId, tenantId, storeId);
        jdbc.update("INSERT INTO variants (id, tenant_id, product_id, external_id, title, sku) " +
                    "VALUES (?, ?, ?, 'V-RS', 'Sand M', 'LIN-SAND-M')", variantId, tenantId, productId);
    }

    @AfterEach
    void cleanup() {
        TenantContext.clear();
        jdbc.update("DELETE FROM portal_lookup_attempts WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM piece_events WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM return_session_items WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM return_session_shipments WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM return_sessions WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM return_request_items WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM return_requests WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM shopify_inventory_adjustments WHERE tenant_id = ?", tenantId);
        jdbc.update("UPDATE pieces SET current_order_id = NULL WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM allocations WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM pieces WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM shipments WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM order_items WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM orders WHERE tenant_id = ?", tenantId);
    }

    @Test
    void restockedPieceFromAnApprovedRequest_isReturnableOnANewOrderByANewCustomer() {
        // Customer 1 returns the piece through the portal; the merchant approves.
        String piece = UlidGenerator.generate();
        UUID order1 = deliveredOrder("#5101", "01011110001");
        insertPiece(piece, order1);
        String token1 = lookupToken("5101", "01011110001", 1);
        PortalService.SubmitResult r1 = portal.submit(SLUG, token1, new PortalService.SubmitRequest(
            List.of(new PortalService.SubmitLine(variantId, 1, "wrong_size")), null, null)).orElseThrow();
        assertThat(r1.outcome()).isEqualTo(PortalService.SubmitOutcome.CREATED);
        UUID request1 = jdbc.queryForObject("SELECT id FROM return_requests WHERE tenant_id = ?", UUID.class, tenantId);
        TenantContext.set(tenantId);
        requests.approve(request1, ownerId);

        // It comes back and is restocked.
        UUID session = sessions.createSession(null, ownerId);
        sessions.scan(session, "PC-" + piece, locationId, ownerId);
        sessions.disposition(session, piece, "restock", null, locationId, ownerId);
        sessions.close(session, ownerId);
        TenantContext.clear();
        assertThat(jdbc.queryForObject("SELECT status::text FROM pieces WHERE id = ?", String.class, piece))
            .isEqualTo("available");

        // The same piece is sold and delivered to customer 2.
        UUID order2 = deliveredOrder("#5202", "01022220002");
        jdbc.update("UPDATE pieces SET status = 'delivered'::piece_status, current_order_id = ?, last_event_at = now() " +
                    "WHERE id = ?", order2, piece);
        allocate(order2, piece);

        String token2 = lookupToken("5202", "01022220002", 1);
        PortalService.SubmitResult r2 = portal.submit(SLUG, token2, new PortalService.SubmitRequest(
            List.of(new PortalService.SubmitLine(variantId, 1, "changed_mind")), null, null)).orElseThrow();
        assertThat(r2.outcome()).as("the new customer's return is accepted").isEqualTo(PortalService.SubmitOutcome.CREATED);
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM return_request_items i JOIN return_requests rr ON rr.id = i.request_id " +
            "WHERE rr.order_id = ? AND i.piece_id = ? AND i.active", Integer.class, order2, piece)).isEqualTo(1);
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────

    /** Looks the order up in the portal, asserts the first line's returnable quantity, returns the token. */
    @SuppressWarnings("unchecked")
    private String lookupToken(String number, String phone, int expectedReturnable) {
        PortalService.LookupResult res = portal.lookup(SLUG, number, phone).orElseThrow();
        assertThat(res.outcome()).isEqualTo(PortalService.Outcome.SUCCESS);
        List<Map<String, Object>> lines = (List<Map<String, Object>>) res.body().get("lines");
        assertThat(lines.get(0).get("returnableQuantity"))
            .as("the portal lookup shows the piece as returnable").isEqualTo(expectedReturnable);
        return (String) res.body().get("token");
    }

    private UUID deliveredOrder(String number, String phone) {
        UUID order = jdbc.queryForObject(
            "INSERT INTO orders (tenant_id, store_id, external_id, number, status, payment_method, placed_at, " +
            "    customer_name, customer_phone, pii_source) " +
            "VALUES (?, ?, ?, ?, 'delivered'::order_status, 'cod'::order_payment_method, now(), 'Customer', ?, 'bosta') RETURNING id",
            UUID.class, tenantId, storeId, "gid://shopify/Order/" + UUID.randomUUID(), number, phone);
        jdbc.update("INSERT INTO shipments (tenant_id, order_id, provider, tracking_number, internal_state, shipment_leg, delivered_at) " +
                    "VALUES (?, ?, 'bosta', ?, 'delivered'::shipment_internal_state, 'forward', now())",
                    tenantId, order, String.valueOf(ThreadLocalRandom.current().nextLong(1_000_000_000L, 9_999_999_999L)));
        return order;
    }

    private void insertPiece(String id, UUID order) {
        jdbc.update("INSERT INTO pieces (id, tenant_id, variant_id, barcode, short_code, status, current_order_id, last_event_at) " +
                    "VALUES (?, ?, ?, ?, 'P' || LPAD((abs(hashtext(?)) % 999999 + 1)::text, 6, '0'), 'delivered'::piece_status, ?, now())",
                    id, tenantId, variantId, "PC-" + id, id, order);
        allocate(order, id);
    }

    private void allocate(UUID order, String piece) {
        UUID item = UUID.randomUUID();
        jdbc.update("INSERT INTO order_items (id, tenant_id, order_id, variant_id, quantity) VALUES (?, ?, ?, ?, 1)",
                    item, tenantId, order, variantId);
        jdbc.update("INSERT INTO allocations (tenant_id, order_item_id, piece_id, status) VALUES (?, ?, ?, 'packed')",
                    tenantId, item, piece);
    }
}
