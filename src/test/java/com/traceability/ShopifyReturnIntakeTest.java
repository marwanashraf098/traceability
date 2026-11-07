package com.traceability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.traceability.integrations.shopify.ShopifyGateway;
import com.traceability.integrations.shopify.ShopifyReturnsGateway;
import com.traceability.integrations.shopify.ShopifyTokenProvider;
import com.traceability.inventory.ExceptionService;
import com.traceability.inventory.ReturnSessionService;
import com.traceability.inventory.ShopifyReturnIntake;
import com.traceability.inventory.ShopifyShadowOrders;
import com.traceability.returncases.ShopifyReturnsView;
import com.traceability.tenancy.TenantAwareDataSource;
import com.traceability.tenancy.TenantContext;
import org.jobrunr.scheduling.JobScheduler;
import org.junit.jupiter.api.*;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Shopify-native returns S4 (R4) — a returned item of a Shopify return comes back through normal Scan returns intake.
 * Every scan goes through the real {@link ReturnSessionService#scan}; pieces are DELIVERED 60 days ago (outside the
 * 30-day customer window), so only a Shopify return can make them expected.
 *
 * <pre>
 *   a1  expected back: OPEN return line of the piece's variant → accepted (return_pending_inspection, not unexpected,
 *       return_kind customer_return_shopify), linked to the line; controls → mismatch (stays delivered, unexpected):
 *       no Shopify return, another variant, a REQUESTED / DECLINED / CANCELED return
 *   a2  with_courier piece: a Shopify return line → not unexpected and no unexpected_return alert; control → both
 *   p1  partial: a line of 2 → first scan "Received 1 of 2" (partial), second fills it, a third piece → mismatch
 *   m1  two returns on one order: the oldest line first, then the next return's
 *   d1  duplicate: a Traced request item takes the scan first (request_return, item arrived) and the Shopify line is
 *       covered (intake carries the request item; card received); another unit of the variant → mismatch
 *   g1  S2 card: expected → awaiting inspection → graded sellable; a damaged one → graded damaged
 *   s1  restock: Shopify already restocked the unit at Main → Traced's +1 skipped_shopify_restocked, nothing sent;
 *       control (not restocked in Shopify) → +1 sent once
 *   l1  LOW shopify_return_open_after_intake after 7 days (not at 2); clears when Shopify processes the unit; and when
 *       it closes the return
 *   l2  MEDIUM shopify_return_ended_after_intake when Shopify declines / cancels after arrival; the piece is not moved;
 *       a manager resolves it
 *   x7  replacement returned again: the piece came back for the SHADOW order → resolves to the original's return line
 *   x1  app_user + RLS: tenant B's context neither sees nor links A's piece (expects false, no row), A's own does;
 *       B's view has no A card
 *   g0  golden: a store without returns access (mirror rows present) → mismatch, no intake row; an in-window return
 *       with no Shopify return → accepted exactly as before (customer_after_delivery), no intake row
 * </pre>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ShopifyReturnIntakeTest {

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
        r.add("traced.shopify-returns.backfill.enabled", () -> "false");
        r.add("shopify.webhook-topics-backfill.enabled", () -> "false");
    }

    @Autowired JdbcTemplate jdbc;               // postgres — fixtures only
    @Autowired ObjectMapper mapper;
    @Autowired ReturnSessionService sessions;
    @Autowired ExceptionService exceptions;
    @MockBean JobScheduler jobScheduler;
    @MockBean ShopifyGateway shopifyGateway;
    @MockBean ShopifyTokenProvider tokenProvider;
    @MockBean ShopifyReturnsGateway returnsGateway;

    JdbcTemplate appJdbc;
    TransactionTemplate appTx;

    static final AtomicLong IDS = new AtomicLong(91_000_000_000L);
    static final String OPEN_ALERT = "shopify_return_open_after_intake";
    static final String ENDED_ALERT = "shopify_return_ended_after_intake";

    @BeforeAll
    void appUser() {
        DataSource ds = new TenantAwareDataSource(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "app_user", "testpw"));
        appJdbc = new JdbcTemplate(ds);
        appTx = new TransactionTemplate(new DataSourceTransactionManager(ds));
    }

    @BeforeEach
    void stubs() {
        Mockito.reset(shopifyGateway, tokenProvider, jobScheduler);
        when(tokenProvider.getValidToken(any())).thenReturn("tok");
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    // ── fixture ─────────────────────────────────────────────────────────────────────────────────────────────────

    /** A tenant, a connected store (returns access by default), main warehouse linked to Shopify, variants V and W. */
    class Shop {
        final UUID tenant = UUID.randomUUID(), store = UUID.randomUUID(), main = UUID.randomUUID(), user = UUID.randomUUID();
        final long mainNum = IDS.incrementAndGet();
        final UUID product;
        final UUID v, w;
        final long vNum = IDS.incrementAndGet(), wNum = IDS.incrementAndGet();
        UUID session;

        Shop(String tag, String returnsAccess) {
            jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?)", tenant, "S4 " + tag);
            jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role) VALUES (?, ?, 'Owner', ?, 'h', 'owner')",
                user, tenant, "s4-" + tag + "-" + tenant.toString().substring(0, 6) + "@test.local");
            jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status, connection_type, returns_access, " +
                "access_token_scopes, import_status, last_sync_at) VALUES (?, ?, 'shopify', ?, 'connected', 'oauth', ?, " +
                "'read_orders,write_inventory,read_products,write_locations,read_locations', 'completed', now())",
                store, tenant, "s4-" + tag + "-" + tenant.toString().substring(0, 6) + ".myshopify.com", returnsAccess);
            jdbc.update("INSERT INTO locations (id, tenant_id, name, type, is_default, is_fulfillment, shopify_location_id, " +
                "shopify_sync_status) VALUES (?, ?, 'Main Warehouse', 'warehouse', true, true, ?, 'linked')", main, tenant, mainGid());
            product = jdbc.queryForObject("INSERT INTO products (tenant_id, store_id, external_id, title, status) " +
                "VALUES (?, ?, ?, 'HENLEY', 'active') RETURNING id", UUID.class, tenant, store, "gid://shopify/Product/" + IDS.incrementAndGet());
            v = variant(vNum, "Black / M");
            w = variant(wNum, "White / M");
            session = as(() -> sessions.createSession("S4", user));
        }

        Shop(String tag) { this(tag, "granted"); }

        private UUID variant(long n, String title) {
            return jdbc.queryForObject("INSERT INTO variants (tenant_id, product_id, external_id, title, sku, shopify_inventory_item_id) " +
                "VALUES (?, ?, ?, ?, ?, ?) RETURNING id", UUID.class, tenant, product, vgid(n), title, "S4-" + n,
                "gid://shopify/InventoryItem/" + n);
        }

        String vgid(long n) { return "gid://shopify/ProductVariant/" + n; }
        String mainGid() { return "gid://shopify/Location/" + mainNum; }

        <T> T as(java.util.concurrent.Callable<T> body) { return TenantContext.runAs(tenant, body); }

        /** A delivered order: one line of {@code qty} units of the variant, a delivered Bosta forward leg. */
        UUID order(String number, UUID variant, int qty) {
            UUID o = jdbc.queryForObject(
                "INSERT INTO orders (tenant_id, store_id, external_id, number, status, payment_method, placed_at, raw) " +
                "VALUES (?, ?, ?, ?, 'delivered', 'cod', now() - interval '70 days', '{\"refunds\": []}'::jsonb) RETURNING id",
                UUID.class, tenant, store, "gid://shopify/Order/" + IDS.incrementAndGet(), number);
            jdbc.update("INSERT INTO order_items (tenant_id, order_id, variant_id, quantity, external_id) VALUES (?, ?, ?, ?, ?)",
                tenant, o, variant, qty, "gid://shopify/LineItem/" + IDS.incrementAndGet());
            UUID s = jdbc.queryForObject("INSERT INTO shipments (tenant_id, order_id, provider, tracking_number, internal_state, shipment_leg) " +
                "VALUES (?, ?, 'bosta', ?, 'delivered', 'forward') RETURNING id", UUID.class, tenant, o,
                String.valueOf(4_000_000_000L + IDS.incrementAndGet() % 1_000_000_000L));
            jdbc.update("INSERT INTO shipment_status_history (tenant_id, shipment_id, internal_state) VALUES (?, ?, 'delivered')", tenant, s);
            return o;
        }

        /** A piece of the order's line, in {@code status}, its last event 60 days ago (outside the 30-day window). */
        String piece(UUID order, String status) {
            long k = IDS.incrementAndGet();
            String id = String.format("01S4IN%020d", k);
            UUID item = jdbc.queryForObject("SELECT id FROM order_items WHERE order_id = ? LIMIT 1", UUID.class, order);
            UUID variant = jdbc.queryForObject("SELECT variant_id FROM order_items WHERE id = ?", UUID.class, item);
            jdbc.update("INSERT INTO pieces (id, tenant_id, variant_id, barcode, short_code, status, current_location_id, " +
                "current_order_id, last_event_at) VALUES (?, ?, ?, ?, ?, ?::piece_status, ?, ?, now() - interval '60 days')",
                id, tenant, variant, "S4-" + k, String.format("Q%07d", k % 10_000_000), status, main, order);
            jdbc.update("INSERT INTO allocations (tenant_id, order_item_id, piece_id, status) VALUES (?, ?, ?, 'packed'::allocation_status)",
                tenant, item, id);
            jdbc.update("INSERT INTO piece_events (tenant_id, piece_id, event_type, from_status, to_status, order_id, occurred_at) " +
                "VALUES (?, ?, 'courier_update', 'with_courier', ?::piece_status, ?, now() - interval '60 days')", tenant, id, status, order);
            return id;
        }

        String barcode(String piece) { return jdbc.queryForObject("SELECT barcode FROM pieces WHERE id = ?", String.class, piece); }

        /** A Shopify Return on the order ({@code ageHours} old) and its id. */
        UUID ret(UUID order, String name, String status, int ageHours) {
            String orderGid = jdbc.queryForObject("SELECT external_id FROM orders WHERE id = ?", String.class, order);
            return jdbc.queryForObject(
                "INSERT INTO shopify_returns (tenant_id, store_id, return_gid, order_id, order_gid, name, status, raw, shopify_created_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, '{}'::jsonb, now() - make_interval(hours => ?)) RETURNING id",
                UUID.class, tenant, store, "gid://shopify/Return/" + IDS.incrementAndGet(), order, orderGid, name, status, ageHours);
        }

        /** A return line of the variant ({@code qty} units); returns its fulfillment line GID. */
        String line(UUID ret, UUID variant, int qty) {
            String fli = "gid://shopify/FulfillmentLineItem/" + IDS.incrementAndGet();
            jdbc.update("INSERT INTO shopify_return_lines (tenant_id, return_id, line_gid, fulfillment_line_gid, variant_gid, variant_id, " +
                "quantity, raw) VALUES (?, ?, ?, ?, (SELECT external_id FROM variants WHERE id = ?), ?, ?, '{}'::jsonb)",
                tenant, ret, "gid://shopify/ReturnLineItem/" + IDS.incrementAndGet(), fli, variant, variant, qty);
            return fli;
        }

        Map<String, Object> scan(String piece) {
            return as(() -> sessions.scan(session, barcode(piece), main, user));
        }

        String status(String piece) { return jdbc.queryForObject("SELECT status::text FROM pieces WHERE id = ?", String.class, piece); }

        boolean unexpected(String piece) {
            return jdbc.queryForObject("SELECT unexpected FROM return_session_items WHERE piece_id = ? AND session_id = ?",
                Boolean.class, piece, session);
        }

        String returnKind(String piece) {
            return jdbc.queryForObject("SELECT metadata->>'return_kind' FROM piece_events WHERE piece_id = ? " +
                "AND event_type = 'return_received' ORDER BY occurred_at DESC LIMIT 1", String.class, piece);
        }

        List<Map<String, Object>> intakes(String piece) {
            return jdbc.queryForList("SELECT return_id, return_line_id, order_id, piece_order_id, request_item_id, session_id " +
                "FROM shopify_return_intakes WHERE piece_id = ?", piece);
        }

        UUID intakeReturn(String piece) {
            List<Map<String, Object>> l = intakes(piece);
            assertThat(l).hasSize(1);
            return (UUID) l.get(0).get("return_id");
        }

        /** The S2 card's "In Traced" for a return, read on app_user under this tenant (RLS). */
        @SuppressWarnings("unchecked")
        Map<String, Object> inTraced(UUID ret) {
            return as(() -> appTx.execute(st -> (Map<String, Object>)
                new ShopifyReturnsView(appJdbc).forReturns(List.of(ret)).get(ret).get("inTraced")));
        }

        List<Map<String, Object>> alerts(String type) {
            return as(() -> exceptions.detectAllOpen().stream().filter(a -> type.equals(a.get("type"))).toList());
        }

        void disposition(String piece, String what) {
            as(() -> sessions.disposition(session, piece, what, "damaged".equals(what) ? "torn" : null, main, user, "owner"));
        }

        int claims(String predicate) {
            return jdbc.queryForObject("SELECT COUNT(*) FROM shopify_inventory_adjustments WHERE tenant_id = ? " +
                "AND trigger_type = 'return_inspection' AND " + predicate, Integer.class, tenant);
        }

        void awaitClaims(String predicate) throws Exception {
            long deadline = System.currentTimeMillis() + 10_000;
            while (claims(predicate) < 1) {
                if (System.currentTimeMillis() > deadline) {
                    throw new AssertionError("no claim " + predicate + ": " + jdbc.queryForList(
                        "SELECT trigger_type, status, skip_reason FROM shopify_inventory_adjustments WHERE tenant_id = ?", tenant));
                }
                Thread.sleep(25);
            }
            Thread.sleep(150);
        }
    }

    static String reference() {
        String alphabet = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ";
        StringBuilder sb = new StringBuilder("RR-");
        java.util.concurrent.ThreadLocalRandom r = java.util.concurrent.ThreadLocalRandom.current();
        for (int i = 0; i < 6; i++) sb.append(alphabet.charAt(r.nextInt(alphabet.length())));
        return sb.toString();
    }

    // ── a1 / a2: expected back vs mismatch ───────────────────────────────────────────────────────────────────────

    @Test
    void a1_openShopifyReturnLine_acceptsTheScan_controlsAreMismatches() {
        Shop s = new Shop("a1");
        UUID o = s.order("#A1001", s.v, 1);
        UUID r = s.ret(o, "#A1001-R1", "OPEN", 2);
        s.line(r, s.v, 1);
        String p = s.piece(o, "delivered");

        s.scan(p);

        assertThat(s.status(p)).isEqualTo("return_pending_inspection");
        assertThat(s.unexpected(p)).isFalse();
        assertThat(s.returnKind(p)).isEqualTo("customer_return_shopify");
        assertThat(s.intakes(p)).singleElement().satisfies(i -> {
            assertThat(i).containsEntry("return_id", r).containsEntry("order_id", o).containsEntry("piece_order_id", o)
                .containsEntry("session_id", s.session);
            assertThat(i.get("request_item_id")).isNull();
        });

        // Controls: the same piece shape with no Shopify return / another variant / a return not (or no longer) approved.
        UUID none = s.order("#A1002", s.v, 1);
        String pn = s.piece(none, "delivered");
        UUID other = s.order("#A1003", s.v, 1);
        s.line(s.ret(other, "#A1003-R1", "OPEN", 2), s.w, 1);
        String po = s.piece(other, "delivered");
        List<String> deadPieces = new ArrayList<>();
        for (String st : List.of("REQUESTED", "DECLINED", "CANCELED")) {
            UUID d = s.order("#A1-" + st, s.v, 1);
            s.line(s.ret(d, "#A1-" + st + "-R1", st, 2), s.v, 1);
            deadPieces.add(s.piece(d, "delivered"));
        }
        List<String> controls = new ArrayList<>(List.of(pn, po));
        controls.addAll(deadPieces);
        for (String c : controls) {
            s.scan(c);
            assertThat(s.status(c)).as("mismatch %s", c).isEqualTo("delivered");
            assertThat(s.unexpected(c)).isTrue();
            assertThat(s.intakes(c)).isEmpty();
        }
    }

    @Test
    void a2_withCourierPiece_shopifyReturn_notUnexpected_noAlert_controlUnexpectedWithAlert() {
        Shop s = new Shop("a2");
        UUID o = s.order("#A2001", s.v, 1);
        s.line(s.ret(o, "#A2001-R1", "OPEN", 2), s.v, 1);
        String p = s.piece(o, "with_courier");
        UUID c = s.order("#A2002", s.v, 1);
        String pc = s.piece(c, "with_courier");

        s.scan(p);
        s.scan(pc);

        assertThat(s.unexpected(p)).isFalse();
        assertThat(s.intakes(p)).hasSize(1);
        assertThat(s.unexpected(pc)).isTrue();
        List<Object> alerted = s.alerts("unexpected_return").stream().map(a -> a.get("piece_id")).toList();
        assertThat(alerted).contains(pc).doesNotContain(p);
    }

    // ── p1 / m1: partial, two returns ───────────────────────────────────────────────────────────────────────────

    @Test
    void p1_partial_lineOfTwo_receivedOneOfTwo_thenFull_thirdIsMismatch() {
        Shop s = new Shop("p1");
        UUID o = s.order("#P1001", s.v, 3);
        UUID r = s.ret(o, "#P1001-R1", "OPEN", 2);
        s.line(r, s.v, 2);
        String p1 = s.piece(o, "delivered"), p2 = s.piece(o, "delivered"), p3 = s.piece(o, "delivered");

        s.scan(p1);
        s.disposition(p1, "restock");
        assertThat(s.inTraced(r)).containsEntry("state", "partial").containsEntry("received", 1).containsEntry("units", 2);

        s.scan(p2);
        assertThat(s.intakeReturn(p2)).isEqualTo(r);
        assertThat(s.inTraced(r)).containsEntry("state", "awaiting_inspection").containsEntry("received", 2);

        s.scan(p3);
        assertThat(s.status(p3)).isEqualTo("delivered");
        assertThat(s.unexpected(p3)).isTrue();
        assertThat(s.intakes(p3)).isEmpty();
    }

    @Test
    void m1_twoReturnsOnOneOrder_oldestLineFirst_thenTheNext() {
        Shop s = new Shop("m1");
        UUID o = s.order("#M1001", s.v, 2);
        UUID newer = s.ret(o, "#M1001-R2", "OPEN", 1);
        UUID older = s.ret(o, "#M1001-R1", "OPEN", 30);
        s.line(newer, s.v, 1);
        s.line(older, s.v, 1);
        String p1 = s.piece(o, "delivered"), p2 = s.piece(o, "delivered");

        s.scan(p1);
        assertThat(s.intakeReturn(p1)).isEqualTo(older);
        assertThat(s.inTraced(older)).containsEntry("received", 1);
        assertThat(s.inTraced(newer)).containsEntry("state", "expected").containsEntry("received", 0);

        s.scan(p2);
        assertThat(s.intakeReturn(p2)).isEqualTo(newer);
        assertThat(s.inTraced(newer)).containsEntry("received", 1);
        assertThat(s.inTraced(older)).containsEntry("received", 1);
    }

    // ── d1: duplicate ───────────────────────────────────────────────────────────────────────────────────────────

    @Test
    void d1_duplicate_tracedRequestTakesTheScan_shopifyLineCovered_secondUnitMismatch() {
        Shop s = new Shop("d1");
        UUID o = s.order("#D1001", s.v, 2);
        UUID r = s.ret(o, "#D1001-R1", "OPEN", 2);
        s.line(r, s.v, 1);
        String p1 = s.piece(o, "delivered"), p2 = s.piece(o, "delivered");
        UUID req = jdbc.queryForObject(
            "INSERT INTO return_requests (tenant_id, order_id, type, status, reference, decided_at) " +
            "VALUES (?, ?, 'refund', 'approved', ?, now()) RETURNING id", UUID.class, s.tenant, o, reference());
        UUID item = jdbc.queryForObject(
            "INSERT INTO return_request_items (tenant_id, request_id, piece_id, variant_id, reason_code, item_status, active) " +
            "VALUES (?, ?, ?, ?, 'wrong_size', 'awaiting', true) RETURNING id", UUID.class, s.tenant, req, p1, s.v);

        s.scan(p1);

        assertThat(s.returnKind(p1)).isEqualTo("request_return");
        assertThat(jdbc.queryForObject("SELECT item_status FROM return_request_items WHERE id = ?", String.class, item))
            .isEqualTo("arrived");
        assertThat(s.intakes(p1)).singleElement().satisfies(i ->
            assertThat(i).containsEntry("return_id", r).containsEntry("request_item_id", item));
        assertThat(s.inTraced(r)).containsEntry("state", "awaiting_inspection").containsEntry("received", 1);

        s.scan(p2);
        assertThat(s.status(p2)).as("the Shopify line is covered").isEqualTo("delivered");
        assertThat(s.unexpected(p2)).isTrue();
    }

    // ── g1: the S2 card ─────────────────────────────────────────────────────────────────────────────────────────

    @Test
    void g1_card_expected_awaitingInspection_gradedSellable_gradedDamaged() {
        Shop s = new Shop("g1");
        UUID o = s.order("#G1001", s.v, 1);
        UUID r = s.ret(o, "#G1001-R1", "OPEN", 2);
        s.line(r, s.v, 1);
        String p = s.piece(o, "delivered");
        assertThat(s.inTraced(r)).containsEntry("state", "expected");
        s.scan(p);
        assertThat(s.inTraced(r)).containsEntry("state", "awaiting_inspection").containsEntry("pending", 1);
        s.disposition(p, "restock");
        assertThat(s.inTraced(r)).containsEntry("state", "sellable").containsEntry("sellable", 1);

        UUID o2 = s.order("#G1002", s.v, 1);
        UUID r2 = s.ret(o2, "#G1002-R1", "OPEN", 2);
        s.line(r2, s.v, 1);
        String p2 = s.piece(o2, "delivered");
        s.scan(p2);
        s.disposition(p2, "damaged");
        assertThat(s.inTraced(r2)).containsEntry("state", "damaged").containsEntry("damaged", 1);
    }

    // ── s1: restock guard ───────────────────────────────────────────────────────────────────────────────────────

    @Test
    void s1_shopifyAlreadyRestocked_plusOneSkipped_controlSentOnce() throws Exception {
        Shop s = new Shop("s1");
        UUID o = s.order("#S1001", s.v, 1);
        UUID r = s.ret(o, "#S1001-R1", "OPEN", 2);
        String fli = s.line(r, s.v, 1);
        jdbc.update("UPDATE shopify_return_lines SET processed_quantity = 1 WHERE return_id = ?", r);
        jdbc.update(
            "INSERT INTO shopify_rfo_dispositions (tenant_id, return_id, disposition_gid, rfo_gid, rfo_line_gid, " +
            "fulfillment_line_gid, type, quantity, location_gid, raw) VALUES (?, ?, ?, ?, ?, ?, 'RESTOCKED', 1, ?, '{}'::jsonb)",
            s.tenant, r, "gid://shopify/ReverseFulfillmentOrderDisposition/" + IDS.incrementAndGet(),
            "gid://shopify/ReverseFulfillmentOrder/" + IDS.incrementAndGet(),
            "gid://shopify/ReverseFulfillmentOrderLineItem/" + IDS.incrementAndGet(), fli, s.mainGid());
        String p = s.piece(o, "delivered");

        s.scan(p);
        s.disposition(p, "restock");

        s.awaitClaims("status = 'skipped_shopify_restocked'");
        verify(shopifyGateway, never()).adjustInventoryQuantities(any(), any(), any(), any(), anyInt(), any(), any());

        // Control: same shape, Shopify restocked nothing → Traced's +1 goes out once.
        UUID c = s.order("#S1002", s.v, 1);
        s.line(s.ret(c, "#S1002-R1", "OPEN", 2), s.v, 1);
        String pc = s.piece(c, "delivered");
        s.scan(pc);
        s.disposition(pc, "restock");
        s.awaitClaims("status = 'applied'");
        verify(shopifyGateway, times(1)).adjustInventoryQuantities(any(), any(), any(), any(), eq(1), eq("restock"), any());
    }

    // ── l1 / l2: alerts ─────────────────────────────────────────────────────────────────────────────────────────

    @Test
    void l1_receivedButStillOpenInShopify_lowAfter7Days_clearsWhenProcessed_andWhenClosed() {
        Shop s = new Shop("l1");
        UUID o = s.order("#L1001", s.v, 1);
        UUID r = s.ret(o, "#L1001-R1", "OPEN", 2);
        s.line(r, s.v, 1);
        String p = s.piece(o, "delivered");
        s.scan(p);

        jdbc.update("UPDATE shopify_return_intakes SET received_at = now() - interval '2 days' WHERE piece_id = ?", p);
        assertThat(s.alerts(OPEN_ALERT)).isEmpty();

        jdbc.update("UPDATE shopify_return_intakes SET received_at = now() - interval '8 days' WHERE piece_id = ?", p);
        assertThat(s.alerts(OPEN_ALERT)).singleElement().satisfies(a -> assertThat(a)
            .containsEntry("severity", "LOW").containsEntry("order_id", o).containsEntry("return_name", "#L1001-R1")
            .containsEntry("subject_key", OPEN_ALERT + ":" + r));

        jdbc.update("UPDATE shopify_return_lines SET processed_quantity = 1 WHERE return_id = ?", r);
        assertThat(s.alerts(OPEN_ALERT)).as("processed in Shopify").isEmpty();

        jdbc.update("UPDATE shopify_return_lines SET processed_quantity = 0 WHERE return_id = ?", r);
        assertThat(s.alerts(OPEN_ALERT)).hasSize(1);
        jdbc.update("UPDATE shopify_returns SET status = 'CLOSED' WHERE id = ?", r);
        assertThat(s.alerts(OPEN_ALERT)).as("closed in Shopify").isEmpty();
    }

    @Test
    void l2_declinedOrCancelledAfterArrival_medium_pieceNotMoved_managerResolves() {
        Shop s = new Shop("l2");
        UUID o = s.order("#L2001", s.v, 1);
        UUID r = s.ret(o, "#L2001-R1", "OPEN", 2);
        s.line(r, s.v, 1);
        String p = s.piece(o, "delivered");
        s.scan(p);
        assertThat(s.alerts(ENDED_ALERT)).isEmpty();

        jdbc.update("UPDATE shopify_returns SET status = 'DECLINED' WHERE id = ?", r);
        assertThat(s.alerts(ENDED_ALERT)).singleElement().satisfies(a -> {
            assertThat(a).containsEntry("severity", "MEDIUM").containsEntry("order_id", o).containsEntry("return_status", "DECLINED");
            assertThat((String) a.get("descriptionEn")).contains("declined").contains(s.barcode(p));
        });
        assertThat(s.status(p)).as("never auto-moved").isEqualTo("return_pending_inspection");
        assertThat(s.intakes(p)).hasSize(1);

        s.as(() -> { exceptions.resolve(ENDED_ALERT, ENDED_ALERT + ":" + r, s.user, "kept it"); return null; });
        assertThat(s.alerts(ENDED_ALERT)).isEmpty();

        // CANCELED likewise.
        UUID o2 = s.order("#L2002", s.v, 1);
        UUID r2 = s.ret(o2, "#L2002-R1", "OPEN", 2);
        s.line(r2, s.v, 1);
        String p2 = s.piece(o2, "delivered");
        s.scan(p2);
        jdbc.update("UPDATE shopify_returns SET status = 'CANCELED', cancelled_seen_at = now() WHERE id = ?", r2);
        assertThat(s.alerts(ENDED_ALERT)).singleElement().satisfies(a -> assertThat((String) a.get("descriptionEn")).contains("cancelled"));
        assertThat(s.alerts(OPEN_ALERT)).isEmpty();
    }

    // ── x7: the replacement comes back ──────────────────────────────────────────────────────────────────────────

    @Test
    void x7_replacementReturnedAgain_resolvesToTheOriginalsReturnLine_viaTheShadow() {
        Shop s = new Shop("x7");
        UUID original = s.order("#X7001", s.w, 1);
        UUID exchange = s.ret(original, "#X7001-R1", "CLOSED", 72);
        UUID xl = jdbc.queryForObject(
            "INSERT INTO shopify_exchange_lines (tenant_id, return_id, line_gid, variant_gid, variant_id, quantity, processed_quantity, " +
            "order_line_gids, raw) VALUES (?, ?, ?, ?, ?, 1, 1, ARRAY[?]::text[], '{}'::jsonb) RETURNING id",
            UUID.class, s.tenant, exchange, "gid://shopify/ExchangeLineItem/" + IDS.incrementAndGet(), s.vgid(s.vNum), s.v,
            "gid://shopify/LineItem/" + IDS.incrementAndGet());
        UUID shadow = jdbc.queryForObject(
            "INSERT INTO orders (tenant_id, store_id, external_id, number, status, payment_method, placed_at, raw, origin) " +
            "VALUES (?, ?, ?, '#X7001-X1', 'delivered', 'cod', now() - interval '65 days', '{}'::jsonb, 'shopify_exchange') RETURNING id",
            UUID.class, s.tenant, s.store, ShopifyShadowOrders.PREFIX + "gid://shopify/Return/" + IDS.incrementAndGet());
        UUID shadowItem = jdbc.queryForObject("INSERT INTO order_items (tenant_id, order_id, variant_id, quantity) VALUES (?, ?, ?, 1) RETURNING id",
            UUID.class, s.tenant, shadow, s.v);
        UUID row = jdbc.queryForObject(
            "INSERT INTO shopify_exchange_shadows (tenant_id, store_id, return_id, order_id, original_order_id, x_index, approved_at) " +
            "VALUES (?, ?, ?, ?, ?, 1, now() - interval '66 days') RETURNING id", UUID.class, s.tenant, s.store, exchange, shadow, original);
        jdbc.update("INSERT INTO shopify_exchange_shadow_lines (tenant_id, shadow_id, exchange_line_id, order_item_id, variant_id, " +
            "quantity, live_quantity) VALUES (?, ?, ?, ?, ?, 1, 0)", s.tenant, row, xl, shadowItem, s.v);
        // The replacement (V), delivered for the shadow; now the customer returns it in Shopify — on the ORIGINAL order.
        String piece = s.piece(shadow, "delivered");
        UUID again = s.ret(original, "#X7001-R2", "OPEN", 2);
        s.line(again, s.v, 1);

        s.scan(piece);

        assertThat(s.status(piece)).isEqualTo("return_pending_inspection");
        assertThat(s.returnKind(piece)).isEqualTo("customer_return_shopify");
        assertThat(s.intakes(piece)).singleElement().satisfies(i -> assertThat(i)
            .containsEntry("return_id", again).containsEntry("order_id", original).containsEntry("piece_order_id", shadow));
        assertThat(s.inTraced(again)).containsEntry("state", "awaiting_inspection").containsEntry("received", 1);
        s.disposition(piece, "restock");
        assertThat(s.inTraced(again)).containsEntry("state", "sellable");
    }

    // ── x1: cross-tenant ────────────────────────────────────────────────────────────────────────────────────────

    @Test
    void x1_appUser_otherTenantNeitherSeesNorLinks_ownTenantDoes() {
        Shop a = new Shop("x1a");
        Shop b = new Shop("x1b");
        UUID o = a.order("#XA001", a.v, 1);
        UUID r = a.ret(o, "#XA001-R1", "OPEN", 2);
        a.line(r, a.v, 1);
        String p = a.piece(o, "delivered");

        Boolean bSees = b.as(() -> appTx.execute(st -> ShopifyReturnIntake.expects(appJdbc, b.tenant, p, o, a.v)));
        ShopifyReturnIntake.Linked bLinked =
            b.as(() -> appTx.execute(st -> ShopifyReturnIntake.link(appJdbc, b.tenant, p, o, a.v, null, null, null, null)));
        Map<UUID, Map<String, Object>> bView = b.as(() -> appTx.execute(st -> new ShopifyReturnsView(appJdbc).forReturns(List.of(r))));
        assertThat(bSees).isFalse();
        assertThat(bLinked).isNull();
        assertThat(a.intakes(p)).isEmpty();
        assertThat(bView).isEmpty();

        // Same-tenant positive control on app_user.
        Boolean aSees = a.as(() -> appTx.execute(st -> ShopifyReturnIntake.expects(appJdbc, a.tenant, p, o, a.v)));
        ShopifyReturnIntake.Linked aLinked =
            a.as(() -> appTx.execute(st -> ShopifyReturnIntake.link(appJdbc, a.tenant, p, o, a.v, null, null, null, null)));
        assertThat(aSees).isTrue();
        assertThat(aLinked).isNotNull();
        assertThat(a.intakes(p)).hasSize(1);
        assertThat(a.inTraced(r)).containsEntry("received", 1);
        // app_user can't rewrite or remove an intake.
        assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> a.as(() -> appTx.execute(st ->
            appJdbc.update("DELETE FROM shopify_return_intakes WHERE piece_id = ?", p))))).isNotNull();
    }

    // ── g0: golden ──────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    void g0_noReturnsAccess_unchanged_noShopifyReturn_inWindow_unchanged() {
        Shop s = new Shop("g0", "not_granted");
        UUID o = s.order("#G0001", s.v, 1);
        s.line(s.ret(o, "#G0001-R1", "OPEN", 2), s.v, 1);
        String p = s.piece(o, "delivered");
        s.scan(p);
        assertThat(s.status(p)).isEqualTo("delivered");
        assertThat(s.unexpected(p)).isTrue();
        assertThat(s.intakes(p)).isEmpty();

        // In the window, no Shopify return: accepted exactly as before.
        Shop t = new Shop("g0b");
        UUID o2 = t.order("#G0002", t.v, 1);
        String p2 = t.piece(o2, "delivered");
        jdbc.update("UPDATE pieces SET last_event_at = now() - interval '2 days' WHERE id = ?", p2);
        t.scan(p2);
        assertThat(t.status(p2)).isEqualTo("return_pending_inspection");
        assertThat(t.returnKind(p2)).isEqualTo("customer_after_delivery");
        assertThat(t.unexpected(p2)).isFalse();
        assertThat(t.intakes(p2)).isEmpty();
        assertThat(t.alerts(OPEN_ALERT)).isEmpty();
        assertThat(t.alerts(ENDED_ALERT)).isEmpty();
    }

    // ── w1: a Shopify return stops expecting pieces after the tenant's return window ───────────────────────────────

    @Test
    void w1_openOrClosedReturn_expectsOnlyWithinTheTenantsReturnWindow_fromTheReturnsCreation() {
        // A tenant with a 10-day window: an OPEN / CLOSED return 9 days old expects; 11 days old doesn't.
        Shop s = new Shop("w1");
        jdbc.update("UPDATE tenants SET customer_return_window_days = 10 WHERE id = ?", s.tenant);
        List<String> inside = new ArrayList<>(), outside = new ArrayList<>();
        for (String st : List.of("OPEN", "CLOSED")) {
            UUID a = s.order("#W1-" + st + "-IN", s.v, 1);
            s.line(s.ret(a, "#W1-" + st + "-IN-R1", st, 9 * 24), s.v, 1);
            inside.add(s.piece(a, "delivered"));
            UUID b = s.order("#W1-" + st + "-OUT", s.v, 1);
            s.line(s.ret(b, "#W1-" + st + "-OUT-R1", st, 11 * 24), s.v, 1);
            outside.add(s.piece(b, "delivered"));
        }
        // The default window (30 days): 29 days old expects; 31 days old doesn't.
        Shop d = new Shop("w1d");
        UUID c = d.order("#W1D-IN", d.v, 1);
        d.line(d.ret(c, "#W1D-IN-R1", "CLOSED", 29 * 24), d.v, 1);
        String dIn = d.piece(c, "delivered");
        UUID e = d.order("#W1D-OUT", d.v, 1);
        d.line(d.ret(e, "#W1D-OUT-R1", "CLOSED", 31 * 24), d.v, 1);
        String dOut = d.piece(e, "delivered");

        for (String p : inside) {
            s.scan(p);
            assertThat(s.status(p)).as("inside %s", p).isEqualTo("return_pending_inspection");
            assertThat(s.returnKind(p)).isEqualTo("customer_return_shopify");
            assertThat(s.intakes(p)).hasSize(1);
        }
        for (String p : outside) {
            s.scan(p);
            assertThat(s.status(p)).as("outside %s", p).isEqualTo("delivered");
            assertThat(s.unexpected(p)).isTrue();
            assertThat(s.intakes(p)).isEmpty();
        }
        d.scan(dIn);
        assertThat(d.status(dIn)).isEqualTo("return_pending_inspection");
        assertThat(d.intakes(dIn)).hasSize(1);
        d.scan(dOut);
        assertThat(d.status(dOut)).isEqualTo("delivered");
        assertThat(d.unexpected(dOut)).isTrue();
        assertThat(d.intakes(dOut)).isEmpty();
    }
}
