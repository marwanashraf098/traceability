package com.traceability;

import com.traceability.inventory.ReturnSessionAutoCloseJob;
import com.traceability.inventory.ReturnSessionService;
import com.traceability.inventory.ShopifyInventoryService;
import com.traceability.inventory.UlidGenerator;
import com.traceability.tenancy.TenantContext;
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

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * F — ReturnSessionAutoCloseJob: closes open return sessions idle > 12h with NOTHING pending
 * (empty sessions included) through the normal close() path, actor = system (closed_by NULL).
 * Sessions with a pending item, sessions idle < 12h, and abandoned sessions are never touched.
 * One tenant per case — at most one open session per tenant (V73).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
                properties = "org.jobrunr.background-job-server.enabled=true")
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReturnSessionAutoCloseTest {

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

    @Autowired ReturnSessionAutoCloseJob job;
    @Autowired ReturnSessionService      sessionSvc;
    @Autowired JdbcTemplate              jdbc;

    @MockBean ShopifyInventoryService shopifyInventory;

    record Tenant(UUID id, UUID actor, UUID store, UUID variant, UUID location) {}

    @BeforeEach
    void stubs() {
        when(shopifyInventory.onReturnInspectionAvailable(any(), any(), any()))
            .thenReturn(CompletableFuture.completedFuture(null));
    }

    @AfterEach
    void clearCtx() { TenantContext.clear(); }

    // ── cases ───────────────────────────────────────────────────────────────

    @Test
    void f1_emptySession_idle13h_closes_asSystem() {
        Tenant t = newTenant();
        UUID s = openSession(t);
        backdate(s, 13);

        job.run();

        assertThat(status(s)).isEqualTo("closed");
        assertThat(jdbc.queryForObject("SELECT closed_by FROM return_sessions WHERE id = ?", UUID.class, s))
            .as("actor = system (NULL)").isNull();
    }

    @Test
    void f2_allDispositionedSession_idle13h_closes_andStampsIntakeOnScannedReturnLeg() {
        Tenant t = newTenant();
        UUID order = deliveredOrder(t);
        String piece = deliveredPiece(t, order);
        String crp = returnedCrpLeg(t, order);

        UUID s = openSession(t);
        TenantContext.runAs(t.id(), () -> {
            sessionSvc.scan(s, "PC-" + piece, t.location(), t.actor());
            sessionSvc.disposition(s, piece, "restock", null, t.location(), t.actor());
        });
        backdate(s, 13);
        assertThat(intake(crp)).isNull();

        job.run();

        assertThat(status(s)).isEqualTo("closed");
        assertThat(intake(crp)).as("close() path ran → intake stamped on the scanned return leg").isNotNull();
    }

    @Test
    void f3_sessionWithPendingItem_idle13h_staysOpen() {
        Tenant t = newTenant();
        UUID order = deliveredOrder(t);
        String piece = deliveredPiece(t, order);
        String crp = returnedCrpLeg(t, order);

        UUID s = openSession(t);
        TenantContext.runAs(t.id(), () -> sessionSvc.scan(s, "PC-" + piece, t.location(), t.actor()));
        backdate(s, 13);

        job.run();

        assertThat(status(s)).as("pending item → never auto-closed").isEqualTo("open");
        assertThat(intake(crp)).isNull();
        assertThat(jdbc.queryForObject("SELECT status::text FROM pieces WHERE id = ?", String.class, piece))
            .isEqualTo("return_pending_inspection");
    }

    @Test
    void f4_session_idle11h_staysOpen() {
        Tenant t = newTenant();
        UUID s = openSession(t);
        backdate(s, 11);

        job.run();

        assertThat(status(s)).isEqualTo("open");
    }

    @Test
    void f4b_recentDispositionCountsAsActivity() {
        Tenant t = newTenant();
        UUID order = deliveredOrder(t);
        String piece = deliveredPiece(t, order);
        returnedCrpLeg(t, order);
        UUID s = openSession(t);
        TenantContext.runAs(t.id(), () -> {
            sessionSvc.scan(s, "PC-" + piece, t.location(), t.actor());
            sessionSvc.disposition(s, piece, "restock", null, t.location(), t.actor());
        });
        // opened + scanned 13h ago, but dispositioned 2h ago → idle only 2h.
        jdbc.update("UPDATE return_sessions SET opened_at = now() - interval '13 hours' WHERE id = ?", s);
        jdbc.update("UPDATE return_session_items SET scanned_at = now() - interval '13 hours', " +
                    "disposition_at = now() - interval '2 hours' WHERE session_id = ?", s);

        job.run();

        assertThat(status(s)).isEqualTo("open");
    }

    @Test
    void f5_abandonedSession_untouched() {
        Tenant t = newTenant();
        UUID s = openSession(t);
        TenantContext.runAs(t.id(), () -> sessionSvc.abandon(s, t.actor()));
        backdate(s, 13);
        Object closedAtBefore = jdbc.queryForObject("SELECT closed_at FROM return_sessions WHERE id = ?", Object.class, s);

        job.run();

        assertThat(status(s)).isEqualTo("abandoned");
        assertThat(jdbc.queryForObject("SELECT closed_at FROM return_sessions WHERE id = ?", Object.class, s))
            .isEqualTo(closedAtBefore);
    }

    @Test
    void f6_perTenant_noCrossTenantEffect() {
        Tenant a = newTenant();
        Tenant b = newTenant();
        UUID sa = openSession(a);
        UUID sb = openSession(b);
        backdate(sa, 13);
        backdate(sb, 13);

        job.processTenant(a.id());

        assertThat(status(sa)).as("tenant A's eligible session closed").isEqualTo("closed");
        assertThat(status(sb)).as("tenant B untouched by tenant A's pass").isEqualTo("open");
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private Tenant newTenant() {
        UUID id = UUID.randomUUID(), actor = UUID.randomUUID(), store = UUID.randomUUID(),
             product = UUID.randomUUID(), variant = UUID.randomUUID(), location = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, 'AutoClose')", id);
        jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role) " +
                    "VALUES (?, ?, 'Worker', ?, 'h', 'owner')", actor, id, "ac-" + actor + "@test.local");
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status) " +
                    "VALUES (?, ?, 'shopify', ?, 'disconnected')", store, id, "ac-" + id + ".myshopify.com");
        jdbc.update("INSERT INTO products (id, tenant_id, store_id, external_id, title, status) " +
                    "VALUES (?, ?, ?, 'P-AC', 'Tee', 'active')", product, id, store);
        jdbc.update("INSERT INTO variants (id, tenant_id, product_id, external_id, title, sku) " +
                    "VALUES (?, ?, ?, 'V-AC', 'White M', 'TEE-W-M')", variant, id, product);
        jdbc.update("INSERT INTO locations (id, tenant_id, name) VALUES (?, ?, 'Returns Bay')", location, id);
        return new Tenant(id, actor, store, variant, location);
    }

    private UUID openSession(Tenant t) {
        return TenantContext.runAs(t.id(), () -> sessionSvc.createSession(null, t.actor()));
    }

    /** Push every activity timestamp of the session back by {@code hours}. */
    private void backdate(UUID session, int hours) {
        jdbc.update("UPDATE return_sessions SET opened_at = now() - (interval '1 hour' * ?) WHERE id = ?", hours, session);
        jdbc.update("UPDATE return_session_items SET scanned_at = now() - (interval '1 hour' * ?), " +
                    "disposition_at = CASE WHEN disposition_at IS NULL THEN NULL " +
                    "                      ELSE now() - (interval '1 hour' * ?) END " +
                    "WHERE session_id = ?", hours, hours, session);
        jdbc.update("UPDATE return_session_shipments SET linked_at = now() - (interval '1 hour' * ?) " +
                    "WHERE session_id = ?", hours, session);
    }

    private UUID deliveredOrder(Tenant t) {
        return jdbc.queryForObject(
            "INSERT INTO orders (tenant_id, store_id, external_id, number, status, payment_method, placed_at) " +
            "VALUES (?, ?, ?, ?, 'delivered'::order_status, 'cod'::order_payment_method, now()) RETURNING id",
            UUID.class, t.id(), t.store(), "gid://shopify/Order/" + UUID.randomUUID(),
            "#" + ThreadLocalRandom.current().nextInt(10_000, 99_999));
    }

    /** Delivered 60 days ago — outside the 30-day window, so only the CRP leg makes it scannable. */
    private String deliveredPiece(Tenant t, UUID order) {
        String id = UlidGenerator.generate();
        jdbc.update(
            "INSERT INTO pieces (id, tenant_id, variant_id, barcode, short_code, status, current_order_id, last_event_at) " +
            "VALUES (?, ?, ?, ?, 'P' || LPAD((abs(hashtext(?)) % 999999 + 1)::text, 6, '0'), " +
            "        'delivered'::piece_status, ?, now() - interval '60 days')",
            id, t.id(), t.variant(), "PC-" + id, id, order);
        UUID item = UUID.randomUUID();
        jdbc.update("INSERT INTO order_items (id, tenant_id, order_id, variant_id, quantity) VALUES (?, ?, ?, ?, 1)",
            item, t.id(), order, t.variant());
        jdbc.update("INSERT INTO allocations (tenant_id, order_item_id, piece_id, status) VALUES (?, ?, ?, 'packed')",
            t.id(), item, id);
        return id;
    }

    private String returnedCrpLeg(Tenant t, UUID order) {
        String tracking = String.valueOf(ThreadLocalRandom.current().nextLong(1_000_000_000L, 9_999_999_999L));
        jdbc.update(
            "INSERT INTO shipments (tenant_id, order_id, provider, tracking_number, internal_state, shipment_leg, " +
            "    created_at, returned_at, raw) " +
            "VALUES (?, ?, 'bosta', ?, 'returned'::shipment_internal_state, 'return', now() - interval '3 days', " +
            "    now() - interval '1 day', '{\"type\":{\"code\":25,\"value\":\"Customer Return Pickup\"}}'::jsonb)",
            t.id(), order, tracking);
        return tracking;
    }

    private String status(UUID session) {
        return jdbc.queryForObject("SELECT status FROM return_sessions WHERE id = ?", String.class, session);
    }

    private Object intake(String tracking) {
        return jdbc.queryForObject("SELECT return_intake_completed_at FROM shipments WHERE tracking_number = ?",
            Object.class, tracking);
    }
}
