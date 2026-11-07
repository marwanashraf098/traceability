package com.traceability;

import com.traceability.identity.JwtService;
import com.traceability.integrations.shopify.ShopifyGateway;
import com.traceability.inventory.ExceptionService;
import com.traceability.inventory.ShipmentLinkService;
import com.traceability.inventory.ShippedOutsideTraced;
import com.traceability.inventory.UlidGenerator;
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
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Clock;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * "Shipped outside Traced" (approved 2026-11-07): pieces a pick scan reserved / packed on an order that then left
 * without Traced's pack / pickup flow. ShippedOutsideTraced.STUCK_PIECES_SQL is the one rule; alerts are read through an
 * app_user ExceptionService (RLS on).
 *
 *   a1  alert: reserved piece, Shopify fulfilled 10 days ago, leg with the courier (history moved) → MEDIUM, one per order
 *   a2  no alert: fulfilled 3 days ago; leg only 'created'; leg returned; not fulfilled in Shopify; cancelled order
 *   m1  action (owner): reserved → packed → with_courier, allocation packed, order with_courier, events, audit, alert
 *       gone, drawer offer present before / absent after, no Shopify call, no inventory claim; a second call → 409
 *   m2  action (manager) on a delivered leg: packed and awaiting_pickup pieces → with_courier → delivered, order delivered
 *   w1  worker → 403, nothing moved
 *   x1  cross-tenant: tenant B's owner → 404 on A's order, nothing moved; app_user under B sees none of A's stuck pieces;
 *       same-tenant control: A's owner moves them
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ShippedOutsideTracedTest {

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

    static final String TYPE = "shipped_outside_traced";

    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;
    @Autowired JdbcTemplate jdbc;   // postgres — fixtures only
    @Autowired JwtService jwt;
    @MockBean  JobScheduler jobScheduler;
    @MockBean  ShopifyGateway shopifyGateway;

    ExceptionService appExceptions;
    JdbcTemplate appJdbc;
    TransactionTemplate appTx;
    final AtomicLong seq = new AtomicLong(5_550_000_000L);

    record T(UUID id, UUID store, UUID location, UUID variant, String owner, String manager, String worker, UUID workerId) {}

    @BeforeAll
    void appUser() {
        TenantAwareDataSource ds = new TenantAwareDataSource(
            new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "app_user", "testpw"));
        appJdbc = new JdbcTemplate(ds);
        appTx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        ShipmentLinkService link = new ShipmentLinkService(appJdbc, null, null, null, null, null, null, null, null);
        appExceptions = new ExceptionService(appJdbc, Clock.systemUTC(), link);
    }

    @AfterEach
    void clear() { TenantContext.clear(); }

    // ── a1 / a2: the alert ────────────────────────────────────────────────────

    @Test
    void a1_heldPieceOnAnOrderTheCourierHas_alertOncePerOrder() {
        T t = tenant("a1");
        UUID o = order(t, "#A1", 10, false);
        piece(t, o, "reserved", "active");
        piece(t, o, "reserved", "active");
        leg(t, o, "with_courier");
        assertThat(alerts(t)).singleElement().satisfies(a -> assertThat(a)
            .containsEntry("order_id", o).containsEntry("severity", "MEDIUM").containsEntry("pieces", 2)
            .containsEntry("subject_key", TYPE + ":" + o).containsEntry("actionUrl", "/orders/" + o));
    }

    @Test
    void a2_notYet_orNotShipped_orComingBack_orUnfulfilled_orCancelled_noAlert() {
        T t = tenant("a2");
        UUID recent = order(t, "#A2a", 3, false);
        piece(t, recent, "reserved", "active");
        leg(t, recent, "with_courier");
        UUID prePickup = order(t, "#A2b", 10, false);
        piece(t, prePickup, "reserved", "active");
        legOnly(t, prePickup, "created", List.of("created"));
        UUID returned = order(t, "#A2c", 10, false);
        piece(t, returned, "reserved", "active");
        leg(t, returned, "returned");
        UUID unfulfilled = order(t, "#A2d", 10, false, false);
        piece(t, unfulfilled, "reserved", "active");
        leg(t, unfulfilled, "with_courier");
        UUID cancelled = order(t, "#A2e", 10, true);
        piece(t, cancelled, "reserved", "active");
        leg(t, cancelled, "with_courier");
        assertThat(alerts(t)).isEmpty();
        // The manager action applies to the recent one (the 7 days only time the alert), never to the others.
        for (UUID o : List.of(prePickup, returned, unfulfilled, cancelled)) {
            assertThat(post(t.owner(), o).getStatusCode().value()).as(o.toString()).isEqualTo(409);
        }
    }

    // ── m1 / m2: the action ───────────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void m1_owner_movesHeldPiecesOutOfCustody_auditedNoShopify() {
        T t = tenant("m1");
        UUID o = order(t, "#M1", 10, false);
        String p = piece(t, o, "reserved", "active");
        UUID leg = leg(t, o, "with_courier");
        assertThat((Map<String, Object>) detail(t.owner(), o).get("shippedOutsideTraced"))
            .containsEntry("pieces", 1).containsEntry("target", "with_courier");

        ResponseEntity<Map> r = post(t.owner(), o);
        assertThat(r.getStatusCode().value()).isEqualTo(200);
        assertThat(r.getBody()).containsEntry("pieces", 1).containsEntry("target", "with_courier");
        assertThat(pieceStatus(p)).isEqualTo("with_courier");
        assertThat(jdbc.queryForObject("SELECT status::text FROM allocations WHERE piece_id = ?", String.class, p)).isEqualTo("packed");
        assertThat(jdbc.queryForObject("SELECT status::text FROM orders WHERE id = ?", String.class, o)).isEqualTo("with_courier");
        assertThat(jdbc.queryForList(
            "SELECT event_type || ':' || from_status || '>' || to_status FROM piece_events WHERE piece_id = ? " +
            "AND metadata->>'event' = 'shipped_outside_traced' AND order_id = ? AND shipment_id = ? ORDER BY occurred_at, id",
            String.class, p, o, leg))
            .containsExactly("pack:reserved>packed", "handed_to_courier:packed>with_courier");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = ? AND target_id = ?",
            Integer.class, t.id(), TYPE, o.toString())).isEqualTo(1);
        assertThat(alerts(t)).isEmpty();
        assertThat(detail(t.owner(), o)).doesNotContainKey("shippedOutsideTraced");
        verifyNoInteractions(shopifyGateway);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM shopify_inventory_adjustments WHERE tenant_id = ?",
            Integer.class, t.id())).as("no Shopify stock claim of any kind").isZero();
        assertThat(post(t.owner(), o).getStatusCode().value()).as("nothing left to move").isEqualTo(409);
    }

    @Test
    void m2_manager_deliveredLeg_packedAndAwaitingPickup_toDelivered() {
        T t = tenant("m2");
        UUID o = order(t, "#M2", 10, false);
        String packed = piece(t, o, "packed", "packed");
        String waiting = piece(t, o, "awaiting_pickup", "packed");
        leg(t, o, "delivered");
        ResponseEntity<Map> r = post(t.manager(), o);
        assertThat(r.getStatusCode().value()).isEqualTo(200);
        assertThat(r.getBody()).containsEntry("pieces", 2).containsEntry("target", "delivered");
        assertThat(pieceStatus(packed)).isEqualTo("delivered");
        assertThat(pieceStatus(waiting)).isEqualTo("delivered");
        assertThat(jdbc.queryForObject("SELECT status::text FROM orders WHERE id = ?", String.class, o)).isEqualTo("delivered");
        assertThat(jdbc.queryForObject("SELECT current_order_id FROM pieces WHERE id = ?", UUID.class, packed)).isEqualTo(o);
    }

    @Test
    void w1_worker_refused_nothingMoved() {
        T t = tenant("w1");
        UUID o = order(t, "#W1", 10, false);
        String p = piece(t, o, "reserved", "active");
        leg(t, o, "with_courier");
        assertThat(post(t.worker(), o).getStatusCode().value()).isEqualTo(403);
        assertThat(pieceStatus(p)).isEqualTo("reserved");
        assertThat(alerts(t)).hasSize(1);
    }

    // ── x1: cross-tenant ──────────────────────────────────────────────────────

    @Test
    void x1_otherTenant_404_appUserSeesNoneOfIt_sameTenantControlMoves() {
        T a = tenant("x1a"), b = tenant("x1b");
        UUID o = order(a, "#X1", 10, false);
        String p = piece(a, o, "reserved", "active");
        leg(a, o, "with_courier");

        assertThat(post(b.owner(), o).getStatusCode().value()).isEqualTo(404);
        assertThat(pieceStatus(p)).isEqualTo("reserved");
        assertThat(alerts(b)).isEmpty();
        List<Map<String, Object>> crossed = TenantContext.runAs(b.id(), () -> appTx.execute(s ->
            ShippedOutsideTraced.stuckPieces(appJdbc, a.id(), null, 0)));
        assertThat(crossed).as("app_user under B: none of A's rows").isEmpty();
        List<Map<String, Object>> own = TenantContext.runAs(a.id(), () -> appTx.execute(s ->
            ShippedOutsideTraced.stuckPieces(appJdbc, a.id(), null, 0)));
        assertThat(own).as("positive control").hasSize(1);

        assertThat(post(a.owner(), o).getStatusCode().value()).isEqualTo(200);
        assertThat(pieceStatus(p)).isEqualTo("with_courier");
    }

    // ── fixtures ──────────────────────────────────────────────────────────────

    private List<Map<String, Object>> alerts(T t) {
        return TenantContext.runAs(t.id(), () -> appTx.execute(s -> appExceptions.detectAllOpen()))
            .stream().filter(e -> TYPE.equals(e.get("type"))).toList();
    }

    private T tenant(String tag) {
        UUID id = UUID.randomUUID(), store = UUID.randomUUID(), location = UUID.randomUUID();
        String slug = "so-" + tag + "-" + id.toString().substring(0, 6);
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?)", id, "SO " + tag);
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status, import_status, access_token_scopes) " +
            "VALUES (?, ?, 'shopify', ?, 'disconnected', 'idle', 'read_orders')", store, id, slug + ".myshopify.com");
        jdbc.update("INSERT INTO locations (id, tenant_id, name, is_fulfillment) VALUES (?, ?, 'Main', true)", location, id);
        UUID product = jdbc.queryForObject("INSERT INTO products (tenant_id, store_id, external_id, title, status) " +
            "VALUES (?, ?, ?, 'Bucket Hat', 'active') RETURNING id", UUID.class, id, store, "gid://shopify/Product/" + slug);
        UUID variant = jdbc.queryForObject("INSERT INTO variants (tenant_id, product_id, external_id, sku, title, price) " +
            "VALUES (?, ?, ?, ?, 'One size', 300) RETURNING id", UUID.class, id, product, "gid://shopify/ProductVariant/" + slug, slug);
        String owner = token(id, "owner"), manager = token(id, "manager");
        UUID workerId = user(id, "worker");
        return new T(id, store, location, variant, owner, manager, jwt.issueAccessToken(workerId, id, "worker"), workerId);
    }

    private UUID user(UUID tenant, String role) {
        return jdbc.queryForObject("INSERT INTO users (tenant_id, name, email, password_hash, role, active) " +
            "VALUES (?, ?, ?, 'x', ?::user_role, true) RETURNING id", UUID.class, tenant, role, role + "-" + UUID.randomUUID() + "@t.com", role);
    }

    private String token(UUID tenant, String role) {
        return jwt.issueAccessToken(user(tenant, role), tenant, role);
    }

    private UUID order(T t, String number, int fulfilledDaysAgo, boolean cancelled) {
        return order(t, number, fulfilledDaysAgo, cancelled, true);
    }

    /** A Shopify order placed 12 days ago; its raw carries one successful fulfillment {@code fulfilledDaysAgo} ago. */
    private UUID order(T t, String number, int fulfilledDaysAgo, boolean cancelled, boolean fulfilled) {
        String at = java.time.OffsetDateTime.now().minusDays(fulfilledDaysAgo).withNano(0).toString();
        String raw = fulfilled
            ? "{\"fulfillments\":[{\"id\":1,\"status\":\"success\",\"created_at\":\"" + at + "\"}]}"
            : "{\"fulfillments\":[]}";
        return jdbc.queryForObject(
            "INSERT INTO orders (tenant_id, store_id, external_id, number, status, payment_method, placed_at, raw) " +
            "VALUES (?, ?, ?, ?, ?::order_status, 'cod', now() - interval '12 days', ?::jsonb) RETURNING id",
            UUID.class, t.id(), t.store(), "gid://shopify/Order/" + UUID.randomUUID(), number,
            cancelled ? "cancelled" : "new", raw);
    }

    private String piece(T t, UUID order, String status, String allocation) {
        String id = UlidGenerator.generate();
        jdbc.update("INSERT INTO pieces (id, tenant_id, variant_id, barcode, short_code, status, current_order_id, current_location_id, " +
            "    last_event_at, last_user_id) VALUES (?, ?, ?, ?, 'P' || LPAD((abs(hashtext(?)) % 999999 + 1)::text, 6, '0'), " +
            "    ?::piece_status, ?, ?, now(), ?)",
            id, t.id(), t.variant(), "PC-" + id, id, status, order, t.location(), t.workerId());
        UUID item = jdbc.queryForObject("INSERT INTO order_items (tenant_id, order_id, variant_id, quantity) VALUES (?, ?, ?, 1) RETURNING id",
            UUID.class, t.id(), order, t.variant());
        jdbc.update("INSERT INTO allocations (tenant_id, order_item_id, piece_id, status) VALUES (?, ?, ?, ?::allocation_status)",
            t.id(), item, id, allocation);
        return id;
    }

    /** A Bosta forward leg in {@code state}; history created → with_courier (→ state). */
    private UUID leg(T t, UUID order, String state) {
        List<String> history = new ArrayList<>(List.of("created", "with_courier"));
        if (!"with_courier".equals(state) && !"created".equals(state)) history.add(state);
        return legOnly(t, order, state, history);
    }

    private UUID legOnly(T t, UUID order, String state, List<String> history) {
        UUID leg = jdbc.queryForObject(
            "INSERT INTO shipments (tenant_id, order_id, provider, tracking_number, internal_state, shipment_leg, created_at) " +
            "VALUES (?, ?, 'bosta', ?, ?::shipment_internal_state, 'forward', now() - interval '11 days') RETURNING id",
            UUID.class, t.id(), order, String.valueOf(seq.incrementAndGet()), state);
        int h = history.size();
        for (String s : history) {
            jdbc.update("INSERT INTO shipment_status_history (tenant_id, shipment_id, internal_state, occurred_at) " +
                "VALUES (?, ?, ?::shipment_internal_state, now() - make_interval(days => ?))", t.id(), leg, s, h--);
        }
        return leg;
    }

    private String pieceStatus(String id) {
        return jdbc.queryForObject("SELECT status::text FROM pieces WHERE id = ?", String.class, id);
    }

    @SuppressWarnings("rawtypes")
    private ResponseEntity<Map> post(String token, UUID order) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        h.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange("http://localhost:" + port + "/api/v1/orders/" + order + "/shipped-outside-traced",
            HttpMethod.POST, new HttpEntity<>("{}", h), Map.class);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private Map<String, Object> detail(String token, UUID order) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        ResponseEntity<Map> r = rest.exchange("http://localhost:" + port + "/api/v1/orders/" + order, HttpMethod.GET,
            new HttpEntity<>(h), Map.class);
        assertThat(r.getStatusCode().value()).isEqualTo(200);
        return r.getBody();
    }
}
