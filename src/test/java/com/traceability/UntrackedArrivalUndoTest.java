package com.traceability;

import com.traceability.identity.JwtService;
import com.traceability.integrations.shopify.ShopifyGateway;
import com.traceability.inventory.ReturnSessionService;
import com.traceability.tenancy.TenantContext;
import org.jobrunr.scheduling.JobScheduler;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Bug 2 (approved 2026-11-16, BRK-47431-EG) — owner / manager "Undo arrival" from the order drawer for an untracked unit
 * marked in a Scan returns session that has closed. The WHOLE app on app_user + RLS, over HTTP (the way prod runs);
 * fixtures on a separate postgres connection, the marks made through the real ReturnSessionService.
 *
 *   u1  BRK-47431 shape (R2): Raw Denim marked damaged by mistake, Crewneck sellable, session closed → GET lists both
 *       (undoBlocked null); POST undo on the Raw Denim mark → 204: undone_at / undone_by set, audit row
 *       'untracked_arrival_undone', the Crewneck mark and the parcel's 'untracked_units_arrived' stamp kept; no piece,
 *       no Shopify claim
 *   u2  the parcel's last live mark undone → its handled stamp is cleared (back to waiting)
 *   u3  a Receiving line already used the unit (return_source untracked_unit:&lt;id&gt;) → 409, nothing changed; GET says received
 *   u4  its session is still open → 409 (undo it in Scan returns); GET says session_open
 *   u5  a worker → 403 on both endpoints
 *   x1  cross-tenant: tenant B's owner → 404 for A's order / mark, A's mark untouched; B's own order works (control)
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class UntrackedArrivalUndoTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            TestPostgres.newContainer()
                    .withDatabaseName("traceability_test")
                    .withUsername("postgres")
                    .withPassword("postgres");

    static {
        POSTGRES.start();
        try (var c = java.sql.DriverManager.getConnection(POSTGRES.getJdbcUrl(), "postgres", "postgres");
             var st = c.createStatement()) {
            st.execute("CREATE ROLE app_user LOGIN PASSWORD 'testpw'");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",      POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", () -> "app_user");
        r.add("spring.datasource.password", () -> "testpw");
        r.add("spring.flyway.url",          POSTGRES::getJdbcUrl);
        r.add("spring.flyway.user",         POSTGRES::getUsername);
        r.add("spring.flyway.password",     POSTGRES::getPassword);
        r.add("analytics.settlement.refresh-enabled", () -> "false");
        r.add("traced.shopify-returns.backfill.enabled", () -> "false");
        r.add("shopify.webhook-topics-backfill.enabled", () -> "false");
        r.add("traced.catalog-backfill.enabled", () -> "false");
    }

    @Autowired TestRestTemplate rest;
    @Autowired JwtService jwt;
    @Autowired ReturnSessionService sessions;
    @MockBean JobScheduler jobScheduler;
    @MockBean ShopifyGateway shopifyGateway;

    JdbcTemplate pg;
    Shop a, b;

    @BeforeAll
    void setup() {
        pg = new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        a = new Shop("a");
        b = new Shop("b");
    }

    @AfterEach void clear() { TenantContext.clear(); }

    class Shop {
        final UUID tenant = UUID.randomUUID(), store = UUID.randomUUID(), location = UUID.randomUUID();
        final UUID owner, worker, crewneck, rawDenim;
        final String ownerToken, workerToken;
        Shop(String tag) {
            pg.update("INSERT INTO tenants (id, name) VALUES (?, ?)", tenant, "BROEK undo " + tag);
            pg.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status) VALUES (?, ?, 'shopify', ?, 'disconnected')",
                store, tenant, "undo-" + tag + ".myshopify.com");
            pg.update("INSERT INTO locations (id, tenant_id, name, is_fulfillment) VALUES (?, ?, 'Main', true)", location, tenant);
            owner = user("owner");
            worker = user("worker");
            crewneck = variant("Boxy Oversized Crewneck – Burgundy", "BRK-RGC-2627-BRG-M-" + tag);
            rawDenim = variant("Raw Denim Relaxed Fit Jeans", "BROEK-Raw-Denim-RFD-1-" + tag);
            ownerToken = jwt.issueAccessToken(owner, tenant, "owner");
            workerToken = jwt.issueAccessToken(worker, tenant, "worker");
        }
        UUID user(String role) {
            return pg.queryForObject("INSERT INTO users (tenant_id, name, email, password_hash, role, active) VALUES (?, ?, ?, 'x', ?::user_role, true) " +
                "RETURNING id", UUID.class, tenant, role, role + "-" + UUID.randomUUID() + "@test.com", role);
        }
        UUID variant(String title, String sku) {
            UUID p = pg.queryForObject("INSERT INTO products (tenant_id, store_id, external_id, title, status) VALUES (?, ?, ?, ?, 'active') RETURNING id",
                UUID.class, tenant, store, "P-" + UUID.randomUUID(), title);
            return pg.queryForObject("INSERT INTO variants (tenant_id, product_id, external_id, title, sku) VALUES (?, ?, ?, 'M', ?) RETURNING id",
                UUID.class, tenant, p, "V-" + UUID.randomUUID(), sku);
        }
    }

    record Marked(UUID order, UUID leg, UUID session, UUID crewMark, UUID rawMark) {}

    /** BRK-47431: delivered, never packed; return leg 7139234160-like; Crewneck sellable + Raw Denim damaged marked. */
    Marked brk47431(Shop s, String number, String crpTn, boolean closeSession) {
        UUID order = pg.queryForObject("INSERT INTO orders (tenant_id, store_id, external_id, number, status, placed_at) " +
            "VALUES (?, ?, ?, ?, 'new', now()) RETURNING id", UUID.class, s.tenant, s.store, "gid://shopify/Order/" + UUID.randomUUID(), number);
        UUID crew = pg.queryForObject("INSERT INTO order_items (tenant_id, order_id, variant_id, quantity) VALUES (?, ?, ?, 1) RETURNING id",
            UUID.class, s.tenant, order, s.crewneck);
        UUID raw = pg.queryForObject("INSERT INTO order_items (tenant_id, order_id, variant_id, quantity) VALUES (?, ?, ?, 1) RETURNING id",
            UUID.class, s.tenant, order, s.rawDenim);
        UUID leg = pg.queryForObject("INSERT INTO shipments (tenant_id, order_id, provider, tracking_number, internal_state, shipment_leg, raw) " +
            "VALUES (?, ?, 'bosta', ?, 'returned', 'return', '{\"type\":{\"code\":25}}'::jsonb) RETURNING id", UUID.class, s.tenant, order, crpTn);
        UUID session = TenantContext.runAs(s.tenant, () -> sessions.createSession(null, s.worker));
        TenantContext.runAs(s.tenant, () -> sessions.scan(session, crpTn, s.location, s.worker));
        TenantContext.runAs(s.tenant, () -> sessions.unitArrived(session, leg, raw, 1, "damaged", s.worker));
        TenantContext.runAs(s.tenant, () -> sessions.unitArrived(session, leg, crew, 1, "sellable", s.worker));
        if (closeSession) TenantContext.runAs(s.tenant, () -> sessions.close(session, s.worker));
        return new Marked(order, leg, session,
            pg.queryForObject("SELECT id FROM untracked_unit_intakes WHERE order_item_id = ?", UUID.class, crew),
            pg.queryForObject("SELECT id FROM untracked_unit_intakes WHERE order_item_id = ?", UUID.class, raw));
    }

    @Test
    @SuppressWarnings("unchecked")
    void u1_r2Shape_undoTheMistakenDamagedMark_keepTheCrewneck_audited_noStock() {
        Marked m = brk47431(a, "BRK-47431-EG", "7139234160", true);
        ResponseEntity<List> list = get(a.ownerToken, "/api/v1/orders/" + m.order() + "/untracked-arrivals");
        assertThat(list.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((List<Map<String, Object>>) list.getBody()).extracting(r -> r.get("condition") + ":" + r.get("undoBlocked"))
            .containsExactlyInAnyOrder("sellable:null", "damaged:null");

        assertThat(post(a.ownerToken, undoUrl(m.order(), m.rawMark())).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        Map<String, Object> raw = pg.queryForMap("SELECT undone_at, undone_by FROM untracked_unit_intakes WHERE id = ?", m.rawMark());
        assertThat(raw.get("undone_at")).isNotNull();
        assertThat(raw.get("undone_by")).isEqualTo(a.owner);
        assertThat(pg.queryForObject("SELECT undone_at IS NULL FROM untracked_unit_intakes WHERE id = ?", Boolean.class, m.crewMark())).isTrue();
        assertThat(pg.queryForObject("SELECT return_intake_outcome FROM shipments WHERE id = ?", String.class, m.leg()))
            .isEqualTo("untracked_units_arrived");
        assertThat(pg.queryForObject("SELECT metadata->>'condition' FROM audit_log WHERE tenant_id = ? AND action = 'untracked_arrival_undone' " +
            "AND target_id = ?", String.class, a.tenant, m.order().toString())).isEqualTo("damaged");
        assertThat(pg.queryForObject("SELECT count(*) FROM shopify_inventory_adjustments WHERE tenant_id = ?", Integer.class, a.tenant)).isZero();
        assertThat(pg.queryForObject("SELECT count(*) FROM pieces WHERE tenant_id = ?", Integer.class, a.tenant)).isZero();
        assertThat((List<Map<String, Object>>) get(a.ownerToken, "/api/v1/orders/" + m.order() + "/untracked-arrivals").getBody())
            .extracting(r -> r.get("intakeId")).containsExactly(m.crewMark().toString());
    }

    @Test
    void u2_lastLiveMarkUndone_parcelBackToWaiting() {
        Marked m = brk47431(a, "BRK-47432-EG", "7139234161", true);
        assertThat(post(a.ownerToken, undoUrl(m.order(), m.rawMark())).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(post(a.ownerToken, undoUrl(m.order(), m.crewMark())).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(pg.queryForMap("SELECT return_intake_outcome, return_intake_completed_at FROM shipments WHERE id = ?", m.leg()))
            .containsEntry("return_intake_outcome", null).containsEntry("return_intake_completed_at", null);
        assertThat(post(a.ownerToken, undoUrl(m.order(), m.rawMark())).getStatusCode()).as("already undone").isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @SuppressWarnings("unchecked")
    void u3_receivingUsedTheUnit_refused() {
        Marked m = brk47431(a, "BRK-47433-EG", "7139234162", true);
        UUID receipt = pg.queryForObject("INSERT INTO receipts (tenant_id, location_id) VALUES (?, ?) RETURNING id", UUID.class, a.tenant, a.location);
        pg.update("INSERT INTO receipt_lines (tenant_id, receipt_id, variant_id, quantity, return_order_id, return_source) VALUES (?, ?, ?, 1, ?, ?)",
            a.tenant, receipt, a.crewneck, m.order(), "untracked_unit:" + m.crewMark());
        assertThat(post(a.ownerToken, undoUrl(m.order(), m.crewMark())).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(pg.queryForObject("SELECT undone_at IS NULL FROM untracked_unit_intakes WHERE id = ?", Boolean.class, m.crewMark())).isTrue();
        assertThat((List<Map<String, Object>>) get(a.ownerToken, "/api/v1/orders/" + m.order() + "/untracked-arrivals").getBody())
            .filteredOn(r -> m.crewMark().toString().equals(r.get("intakeId"))).singleElement()
            .satisfies(r -> assertThat(r.get("undoBlocked")).isEqualTo("received"));
    }

    @Test
    void u4_sessionStillOpen_refused() {
        Marked m = brk47431(a, "BRK-47434-EG", "7139234163", false);
        assertThat(post(a.ownerToken, undoUrl(m.order(), m.rawMark())).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(pg.queryForObject("SELECT undone_at IS NULL FROM untracked_unit_intakes WHERE id = ?", Boolean.class, m.rawMark())).isTrue();
        TenantContext.runAs(a.tenant, () -> sessions.close(m.session(), a.worker));
    }

    @Test
    void u5_worker_forbidden() {
        Marked m = brk47431(a, "BRK-47435-EG", "7139234164", true);
        assertThat(get(a.workerToken, "/api/v1/orders/" + m.order() + "/untracked-arrivals").getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(post(a.workerToken, undoUrl(m.order(), m.rawMark())).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(pg.queryForObject("SELECT undone_at IS NULL FROM untracked_unit_intakes WHERE id = ?", Boolean.class, m.rawMark())).isTrue();
    }

    @Test
    void x1_crossTenant_404_untouched_ownTenantControl() {
        Marked ma = brk47431(a, "BRK-47436-EG", "7139234165", true);
        Marked mb = brk47431(b, "BRK-47436-EG", "7139234166", true);
        assertThat(get(b.ownerToken, "/api/v1/orders/" + ma.order() + "/untracked-arrivals").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(post(b.ownerToken, undoUrl(ma.order(), ma.rawMark())).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(post(b.ownerToken, undoUrl(mb.order(), ma.rawMark())).getStatusCode()).as("A's mark under B's order").isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(pg.queryForObject("SELECT undone_at IS NULL FROM untracked_unit_intakes WHERE id = ?", Boolean.class, ma.rawMark())).isTrue();
        assertThat(post(b.ownerToken, undoUrl(mb.order(), mb.rawMark())).getStatusCode()).as("control").isEqualTo(HttpStatus.NO_CONTENT);
    }

    static String undoUrl(UUID order, UUID intake) {
        return "/api/v1/orders/" + order + "/untracked-arrivals/" + intake + "/undo";
    }

    @SuppressWarnings("rawtypes")
    ResponseEntity<List> get(String token, String path) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        ResponseEntity<String> raw = rest.exchange(path, HttpMethod.GET, new HttpEntity<>(null, h), String.class);
        List body = null;
        if (raw.getStatusCode().is2xxSuccessful()) {
            try { body = new com.fasterxml.jackson.databind.ObjectMapper().readValue(raw.getBody(), List.class); }
            catch (Exception e) { throw new IllegalStateException(e); }
        }
        return new ResponseEntity<>(body, raw.getStatusCode());
    }

    ResponseEntity<String> post(String token, String path) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        h.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>("{}", h), String.class);
    }
}
