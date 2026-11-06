package com.traceability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.traceability.identity.model.AccessTokenResponse;
import com.traceability.inventory.UlidGenerator;
import com.traceability.returncases.ReturnCaseService;
import com.traceability.returncases.ShopifyReturnsView;
import com.traceability.tenancy.TenantAwareDataSource;
import com.traceability.tenancy.TenantContext;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.password.PasswordEncoder;
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

/**
 * Shopify-native returns S2 — READ-ONLY display of the S1 mirror: the order drawer's "From Shopify" card, the order
 * line "Exchange item for #…-R1", the Returns list's Shopify rows (source filter, source counts, Shopify state,
 * In Traced), the Duplicate return flag (Traced request listed first, never merged), cross-tenant on app_user, and
 * the golden (an order / tenant with no Shopify returns answers exactly as before).
 *
 *   c1  order card: coming back (reason + notes), going out, on hold · Awaiting payment, Restocked in Shopify ×N,
 *       the exchange order line labelled, an unrelated FO ignored; order without returns has no card
 *   c2  the state follows the exchange FO (released → shipped) and the return (closed)
 *   l1  list: Shopify row (source, state, lines, summary, orderId), source filter, source counts, closed → done
 *   d1  duplicate: request + Shopify return on one order → both flagged, Shopify row right after the request, paging
 *       one row at a time keeps that order; a cancelled Shopify return / a rejected request → no duplicate
 *   t1  In Traced from existing intake evidence: expected → awaiting inspection → graded sellable / damaged
 *   x1  app_user cross-tenant (+ same-tenant positive control): view, list, counts
 *   g1  golden: order without Shopify returns (detail byte-identical before / after another order gets one), tenant
 *       without Shopify returns (list + counts byte-identical before / after another tenant gets one, no S2 keys)
 *   r1  roles: worker 403 on list and detail (unchanged)
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ShopifyReturnsViewTest {

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
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired ReturnCaseService cases;
    @Autowired ObjectMapper mapper;

    record Tenant(UUID id, UUID store, UUID product, UUID black, UUID white, UUID location, UUID owner) {}
    record Order(UUID id, UUID item, String number) {}

    private static final AtomicLong GIDS = new AtomicLong(77_000_000_000L);

    Tenant a, b;
    String ownerA, ownerB, workerA;
    DataSource appDs;
    TransactionTemplate appUserTx;

    @BeforeAll
    void setup() {
        a = tenant("Nour Studio");
        b = tenant("Other Store");
        ownerA = login(a.owner());
        ownerB = login(b.owner());
        workerA = login(user(a.id(), "worker"));
        appDs = new TenantAwareDataSource(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "app_user", "testpw"));
        appUserTx = new TransactionTemplate(new DataSourceTransactionManager(appDs));
    }

    @AfterEach
    void cleanup() {
        TenantContext.clear();
        for (Tenant t : List.of(a, b)) {
            jdbc.update("DELETE FROM shopify_rfo_dispositions WHERE tenant_id = ?", t.id());
            jdbc.update("DELETE FROM shopify_exchange_lines WHERE tenant_id = ?", t.id());
            jdbc.update("DELETE FROM shopify_return_lines WHERE tenant_id = ?", t.id());
            jdbc.update("DELETE FROM shopify_returns WHERE tenant_id = ?", t.id());
            jdbc.update("DELETE FROM shopify_fulfillment_orders WHERE tenant_id = ?", t.id());
            jdbc.update("DELETE FROM return_request_events WHERE tenant_id = ?", t.id());
            jdbc.update("DELETE FROM return_request_items WHERE tenant_id = ?", t.id());
            jdbc.update("DELETE FROM return_requests WHERE tenant_id = ?", t.id());
            jdbc.update("DELETE FROM piece_events WHERE tenant_id = ?", t.id());
            jdbc.update("DELETE FROM allocations WHERE tenant_id = ?", t.id());
            jdbc.update("DELETE FROM pieces WHERE tenant_id = ?", t.id());
            jdbc.update("DELETE FROM order_items WHERE tenant_id = ?", t.id());
            jdbc.update("DELETE FROM orders WHERE tenant_id = ?", t.id());
        }
    }

    // ── c1 / c2: the order card ───────────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void c1_orderCard_comingBack_goingOut_onHold_restocked_exchangeLine() {
        Order o = order(a, "#1006");
        Order plain = order(a, "#1007");
        UUID ret = shopifyReturn(a, o, "#1006-R1", "OPEN");
        String fli = returnLine(a, ret, a.black(), 1, "size-too-large", "Too big on the shoulders", "Please swap");
        String orderLine = "gid://shopify/LineItem/" + GIDS.incrementAndGet();
        String exLine = exchangeLine(a, ret, a.white(), 1, orderLine);
        // the exchange order line Shopify added (stored at 0: added after the order left Traced)
        UUID exItem = UUID.randomUUID();
        jdbc.update("INSERT INTO order_items (id, tenant_id, order_id, variant_id, quantity, raw, shopify_exchange_line_gid) " +
            "VALUES (?, ?, ?, ?, 0, '{\"current_quantity\": 1}'::jsonb, ?)", exItem, a.id(), o.id(), a.white(), exLine);
        fo(a, o, "ON_HOLD", new String[]{"AWAITING_PAYMENT"}, orderLine);
        fo(a, o, "CLOSED", new String[]{}, "gid://shopify/LineItem/original-" + GIDS.incrementAndGet());   // the original FO
        disposition(a, ret, fli, "RESTOCKED", 1);

        Map<String, Object> detail = get("/api/v1/orders/" + o.id(), ownerA).getBody();
        List<Map<String, Object>> cards = (List<Map<String, Object>>) detail.get("shopifyReturns");
        assertThat(cards).hasSize(1);
        Map<String, Object> c = cards.get(0);
        assertThat(c).containsEntry("name", "#1006-R1").containsEntry("state", "on_hold").containsEntry("kind", "exchange")
            .containsEntry("restockedInShopify", 1).containsEntry("duplicate", false);
        assertThat((List<String>) c.get("holdReasons")).containsExactly("AWAITING_PAYMENT");
        Map<String, Object> back = ((List<Map<String, Object>>) c.get("comingBack")).get(0);
        assertThat(back).containsEntry("productTitle", "Henley").containsEntry("variantTitle", "Black / XL")
            .containsEntry("quantity", 1).containsEntry("reason", "size-too-large")
            .containsEntry("reasonNote", "Too big on the shoulders").containsEntry("customerNote", "Please swap");
        Map<String, Object> out = ((List<Map<String, Object>>) c.get("goingOut")).get(0);
        assertThat(out).containsEntry("productTitle", "Henley").containsEntry("variantTitle", "White / L").containsEntry("quantity", 1);
        assertThat((Map<String, Object>) c.get("inTraced")).containsEntry("state", "expected");

        Map<String, Object> exRow = ((List<Map<String, Object>>) detail.get("items")).stream()
            .filter(i -> exItem.toString().equals(i.get("id"))).findFirst().orElseThrow();
        assertThat(exRow).containsEntry("exchangeFor", "#1006-R1").containsEntry("addedAfterShipping", true);
        Map<String, Object> origRow = ((List<Map<String, Object>>) detail.get("items")).stream()
            .filter(i -> o.item().toString().equals(i.get("id"))).findFirst().orElseThrow();
        assertThat(origRow).doesNotContainKey("exchangeFor");

        Map<String, Object> none = get("/api/v1/orders/" + plain.id(), ownerA).getBody();
        assertThat(none).doesNotContainKey("shopifyReturns");
    }

    @Test
    @SuppressWarnings("unchecked")
    void c2_stateFollowsExchangeFulfillmentOrderAndReturn() {
        Order o = order(a, "#2001");
        UUID ret = shopifyReturn(a, o, "#2001-R1", "OPEN");
        returnLine(a, ret, a.black(), 1, null, null, null);
        String orderLine = "gid://shopify/LineItem/" + GIDS.incrementAndGet();
        exchangeLine(a, ret, a.white(), 1, orderLine);
        UUID foId = fo(a, o, "OPEN", new String[]{}, orderLine);
        assertThat(card(o).get("state")).isEqualTo("released");
        jdbc.update("UPDATE shopify_fulfillment_orders SET status = 'CLOSED' WHERE id = ?", foId);
        assertThat(card(o).get("state")).isEqualTo("shipped");
        jdbc.update("UPDATE shopify_returns SET status = 'CLOSED', closed_at = now() WHERE id = ?", ret);
        assertThat(card(o).get("state")).isEqualTo("closed");

        // a plain return (no exchange line): open → requested chip as Shopify says
        Order o2 = order(a, "#2002");
        UUID r2 = shopifyReturn(a, o2, "#2002-R1", "REQUESTED");
        returnLine(a, r2, a.black(), 1, null, null, null);
        Map<String, Object> c2 = card(o2);
        assertThat(c2).containsEntry("state", "requested").containsEntry("kind", "return");
        assertThat((List<Object>) c2.get("goingOut")).isEmpty();

        // an OPEN return whose order has a held FO that is NOT its exchange's (the original's): still open
        Order o3 = order(a, "#2003");
        UUID r3 = shopifyReturn(a, o3, "#2003-R1", "OPEN");
        returnLine(a, r3, a.black(), 1, null, null, null);
        fo(a, o3, "ON_HOLD", new String[]{"OTHER"}, "gid://shopify/LineItem/original-" + GIDS.incrementAndGet());
        assertThat(card(o3)).containsEntry("state", "open");
    }

    // ── l1: the Returns list ───────────────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void l1_listShopifyRows_sourceFilter_counts() {
        Order o = order(a, "#3001");
        UUID ret = shopifyReturn(a, o, "#3001-R1", "OPEN");
        returnLine(a, ret, a.black(), 1, null, null, null);
        String orderLine = "gid://shopify/LineItem/" + GIDS.incrementAndGet();
        exchangeLine(a, ret, a.white(), 1, orderLine);
        fo(a, o, "ON_HOLD", new String[]{"AWAITING_PAYMENT"}, orderLine);
        Order closedO = order(a, "#3002");
        UUID closed = shopifyReturn(a, closedO, "#3002-R1", "CLOSED");
        returnLine(a, closed, a.black(), 1, null, null, null);
        Order portalO = order(a, "#3003");
        UUID req = request(a, portalO, "requested");

        List<Map<String, Object>> all = list(a, null);
        Map<String, Object> s = byId(all, ret);
        assertThat(s).containsEntry("caseType", "S").containsEntry("source", "shopify").containsEntry("kind", "exchange")
            .containsEntry("reference", "#3001-R1").containsEntry("orderNumber", "#3001")
            .containsEntry("nextStep", "shopify_open").containsEntry("stage", "in_progress")
            .containsEntry("itemsSummary", "Henley Black / XL → Henley White / L")
            .doesNotContainKey("duplicate");
        Map<String, Object> block = (Map<String, Object>) s.get("shopify");
        assertThat(block).containsEntry("state", "on_hold").containsEntry("orderId", o.id().toString());
        assertThat(((Map<String, Object>) s.get("target"))).containsEntry("orderId", o.id().toString());
        assertThat(byId(all, closed)).containsEntry("stage", "done").containsEntry("tone", "done_good")
            .containsEntry("kind", "refund");
        assertThat(((Map<String, Object>) byId(all, closed).get("shopify")).get("state")).isEqualTo("closed");

        assertThat(ids(list(a, "shopify"))).containsExactlyInAnyOrder(ret.toString(), closed.toString());
        assertThat(ids(list(a, "traced"))).containsExactly(req.toString());

        Map<String, Object> counts = asTenant(a, () -> cases.counts(null, null, null));
        assertThat((Map<String, Object>) counts.get("sources")).containsEntry("traced", 1).containsEntry("shopify", 2);
        assertThat((Map<String, Object>) counts.get("stages")).containsEntry("all", 3).containsEntry("in_progress", 1);
        Map<String, Object> shopifyOnly = asTenant(a, () -> cases.counts(null, null, "shopify"));
        assertThat((Map<String, Object>) shopifyOnly.get("stages")).containsEntry("all", 2).containsEntry("to_do", 0);
        // search by the return's name
        assertThat(ids(asTenantList(a, new ReturnCaseService.Filter(null, null, null, "#3001-R1")))).containsExactly(ret.toString());
        // type filter: a Shopify return with exchange lines is an exchange
        assertThat(ids(asTenantList(a, new ReturnCaseService.Filter(null, "exchange", null, null)))).containsExactly(ret.toString());
        // over HTTP, with the source param
        ResponseEntity<Map> http = get("/api/v1/returns-exchanges?source=shopify", ownerA);
        assertThat((List<Object>) http.getBody().get("items")).hasSize(2);
        assertThat(get("/api/v1/returns-exchanges/counts?source=bogus", ownerA).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // ── d1: duplicate return ────────────────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void d1_duplicate_bothFlagged_tracedFirst_paging() {
        Order o = order(a, "#4001");
        UUID req = request(a, o, "approved");
        jdbc.update("UPDATE return_requests SET created_at = now() - interval '3 days' WHERE id = ?", req);
        // the Shopify return is NEWER than the request — on its own it would sort first
        UUID ret = shopifyReturn(a, o, "#4001-R1", "OPEN");
        returnLine(a, ret, a.black(), 1, null, null, null);
        Order other = order(a, "#4002");
        UUID otherReq = request(a, other, "approved");
        jdbc.update("UPDATE return_requests SET created_at = now() - interval '1 day' WHERE id = ?", otherReq);

        List<Map<String, Object>> all = list(a, null);
        List<String> order = ids(all);
        assertThat(byId(all, req)).containsEntry("duplicate", true);
        assertThat(byId(all, ret)).containsEntry("duplicate", true);
        assertThat(byId(all, otherReq)).doesNotContainKey("duplicate");
        assertThat(order.indexOf(ret.toString())).isEqualTo(order.indexOf(req.toString()) + 1);
        Map<String, Object> block = (Map<String, Object>) byId(all, ret).get("shopify");
        assertThat(block).containsEntry("duplicate", true);
        assertThat((List<String>) block.get("duplicateOf")).hasSize(1);
        // the Shopify row takes the request's stage
        assertThat(byId(all, ret).get("stage")).isEqualTo(byId(all, req).get("stage"));

        // paging one row at a time returns the same order (cursor carries the Shopify row's sub key)
        List<String> paged = new ArrayList<>();
        String cursor = null;
        do {
            String c = cursor;
            Map<String, Object> page = asTenant(a, () -> cases.list(new ReturnCaseService.Filter(null, null, null, null), c, 1));
            paged.addAll(ids((List<Map<String, Object>>) page.get("items")));
            cursor = (String) page.get("nextCursor");
        } while (cursor != null);
        assertThat(paged).containsExactlyElementsOf(order);

        // the order card says so too
        assertThat(card(o)).containsEntry("duplicate", true);

        // a cancelled Shopify return is no duplicate; a rejected request neither
        jdbc.update("UPDATE shopify_returns SET status = 'CANCELED' WHERE id = ?", ret);
        assertThat(list(a, null)).noneMatch(i -> Boolean.TRUE.equals(i.get("duplicate")));
        jdbc.update("UPDATE shopify_returns SET status = 'OPEN' WHERE id = ?", ret);
        jdbc.update("UPDATE return_requests SET status = 'rejected' WHERE id = ?", req);
        assertThat(list(a, null)).noneMatch(i -> Boolean.TRUE.equals(i.get("duplicate")));
        assertThat(card(o)).containsEntry("duplicate", false);
    }

    // ── t1: In Traced ──────────────────────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void t1_inTraced_fromExistingIntakeEvidence() {
        Order o = order(a, "#5001");
        UUID ret = shopifyReturn(a, o, "#5001-R1", "OPEN");
        returnLine(a, ret, a.black(), 1, null, null, null);
        assertThat((Map<String, Object>) card(o).get("inTraced")).containsEntry("state", "expected");

        String piece = returnedPiece(a, o, "return_pending_inspection");
        assertThat((Map<String, Object>) card(o).get("inTraced")).containsEntry("state", "awaiting_inspection")
            .containsEntry("received", 1).containsEntry("pending", 1);

        grade(a, piece, "restocked", "available");
        assertThat((Map<String, Object>) card(o).get("inTraced")).containsEntry("state", "sellable").containsEntry("sellable", 1);

        // a second order: graded damaged
        Order o2 = order(a, "#5002");
        UUID r2 = shopifyReturn(a, o2, "#5002-R1", "CLOSED");
        returnLine(a, r2, a.black(), 1, null, null, null);
        String p2 = returnedPiece(a, o2, "return_pending_inspection");
        grade(a, p2, "damaged", "damaged");
        assertThat((Map<String, Object>) card(o2).get("inTraced")).containsEntry("state", "damaged");

        // the list row carries the same block
        Map<String, Object> row = byId(list(a, "shopify"), ret);
        assertThat((Map<String, Object>) ((Map<String, Object>) row.get("shopify")).get("inTraced")).containsEntry("state", "sellable");

        // a cancelled return with nothing back: none
        Order o3 = order(a, "#5003");
        UUID r3 = shopifyReturn(a, o3, "#5003-R1", "CANCELED");
        returnLine(a, r3, a.black(), 1, null, null, null);
        assertThat((Map<String, Object>) card(o3).get("inTraced")).containsEntry("state", "none");
    }

    // ── x1: cross-tenant on app_user ──────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void x1_appUser_crossTenant_withPositiveControl() {
        Order theirs = order(b, "#9001");
        UUID ret = shopifyReturn(b, theirs, "#9001-R1", "OPEN");
        returnLine(b, ret, b.black(), 1, null, null, null);
        String orderLine = "gid://shopify/LineItem/" + GIDS.incrementAndGet();
        String ex = exchangeLine(b, ret, b.white(), 1, orderLine);
        jdbc.update("INSERT INTO order_items (tenant_id, order_id, variant_id, quantity, raw, shopify_exchange_line_gid) " +
            "VALUES (?, ?, ?, 0, '{\"current_quantity\": 1}'::jsonb, ?)", b.id(), theirs.id(), b.white(), ex);
        Order mine = order(a, "#9002");

        JdbcTemplate app = new JdbcTemplate(appDs);
        ShopifyReturnsView appView = new ShopifyReturnsView(app);
        ReturnCaseService appCases = new ReturnCaseService(app);

        // tenant A sees nothing of B's return
        List<Map<String, Object>> aCards = asApp(a, () -> appView.cardsForOrder(theirs.id()));
        Map<UUID, Map<String, Object>> aRows = asApp(a, () -> appView.forReturns(List.of(ret)));
        Map<UUID, String> aNames = asApp(a, () -> appView.exchangeLineReturnNames(theirs.id()));
        assertThat(aCards).isEmpty();
        assertThat(aRows).isEmpty();
        assertThat(aNames).isEmpty();
        Map<String, Object> aList = asApp(a, () -> appCases.list(new ReturnCaseService.Filter(null, null, null, null, "shopify"), null, 50));
        assertThat((List<Object>) aList.get("items")).isEmpty();
        Map<String, Object> aCounts = asApp(a, () -> appCases.counts(null, null, null));
        assertThat(aCounts).doesNotContainKey("sources");
        // ... and A's own order works on the same connection
        List<Map<String, Object>> aMine = asApp(a, () -> appView.cardsForOrder(mine.id()));
        assertThat(aMine).isEmpty();
        // no context at all: nothing
        Integer noContext = appUserTx.execute(s -> app.queryForObject("SELECT COUNT(*) FROM shopify_returns", Integer.class));
        assertThat(noContext).isZero();

        // positive control: B sees its own return through the same code and connection
        List<Map<String, Object>> bCards = asApp(b, () -> appView.cardsForOrder(theirs.id()));
        assertThat(bCards).hasSize(1);
        assertThat(bCards.get(0)).containsEntry("name", "#9001-R1");
        Map<UUID, String> bNames = asApp(b, () -> appView.exchangeLineReturnNames(theirs.id()));
        assertThat(bNames).hasSize(1);
        Map<String, Object> bList = asApp(b, () -> appCases.list(new ReturnCaseService.Filter(null, null, null, null, "shopify"), null, 50));
        assertThat(ids((List<Map<String, Object>>) bList.get("items"))).containsExactly(ret.toString());

        // over HTTP: A's owner can't open B's order (404), B's owner sees the card
        assertThat(get("/api/v1/orders/" + theirs.id(), ownerA).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat((List<Object>) get("/api/v1/orders/" + theirs.id(), ownerB).getBody().get("shopifyReturns")).hasSize(1);
    }

    // ── g1: golden ────────────────────────────────────────────────────────────────

    @Test
    void g1_golden_noShopifyReturns_byteIdentical() {
        Order x = order(a, "#6001");
        Order y = order(a, "#6002");
        request(b, order(b, "#6003"), "approved");
        request(b, order(b, "#6004"), "requested");

        String detailBefore = raw("/api/v1/orders/" + x.id(), ownerA);
        String bListBefore = raw("/api/v1/returns-exchanges?limit=1", ownerB);
        String bCountsBefore = raw("/api/v1/returns-exchanges/counts", ownerB);

        // another order of A gets a Shopify exchange (and its order line); tenant A gets Shopify returns
        UUID ret = shopifyReturn(a, y, "#6002-R1", "OPEN");
        returnLine(a, ret, a.black(), 1, null, null, null);
        String orderLine = "gid://shopify/LineItem/" + GIDS.incrementAndGet();
        String ex = exchangeLine(a, ret, a.white(), 1, orderLine);
        jdbc.update("INSERT INTO order_items (tenant_id, order_id, variant_id, quantity, raw, shopify_exchange_line_gid) " +
            "VALUES (?, ?, ?, 0, '{\"current_quantity\": 1}'::jsonb, ?)", a.id(), y.id(), a.white(), ex);
        fo(a, y, "ON_HOLD", new String[]{"AWAITING_PAYMENT"}, orderLine);

        String detailAfter = raw("/api/v1/orders/" + x.id(), ownerA);
        String bListAfter = raw("/api/v1/returns-exchanges?limit=1", ownerB);
        String bCountsAfter = raw("/api/v1/returns-exchanges/counts", ownerB);

        assertThat(detailAfter).isEqualTo(detailBefore);
        assertThat(bListAfter).isEqualTo(bListBefore);
        assertThat(bCountsAfter).isEqualTo(bCountsBefore);
        for (String key : List.of("shopifyReturns", "exchangeFor")) assertThat(detailAfter).doesNotContain("\"" + key + "\"");
        for (String key : List.of("\"shopify\"", "\"duplicate\"", "\"orderId\"")) assertThat(bListAfter).doesNotContain(key);
        assertThat(bCountsAfter).doesNotContain("\"sources\"");
        // the 3-part cursor of the pre-S2 format: rank|us|case_key
        String cursor = (String) get("/api/v1/returns-exchanges?limit=1", ownerB).getBody().get("nextCursor");
        assertThat(new String(Base64.getUrlDecoder().decode(cursor)).split("\\|")).hasSize(3);
    }

    // ── r1: roles ──────────────────────────────────────────────────────────────────

    @Test
    void r1_roles_unchanged() {
        Order o = order(a, "#7001");
        shopifyReturn(a, o, "#7001-R1", "OPEN");
        assertThat(get("/api/v1/returns-exchanges", workerA).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(get("/api/v1/orders/" + o.id(), workerA).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(get("/api/v1/returns-exchanges?source=shopify", ownerA).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // ── helpers ───────────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private Map<String, Object> card(Order o) {
        List<Map<String, Object>> cards = (List<Map<String, Object>>) get("/api/v1/orders/" + o.id(), ownerA).getBody().get("shopifyReturns");
        assertThat(cards).hasSize(1);
        return cards.get(0);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> list(Tenant t, String source) {
        return asTenantList(t, new ReturnCaseService.Filter(null, null, null, null, source));
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> asTenantList(Tenant t, ReturnCaseService.Filter f) {
        return (List<Map<String, Object>>) asTenant(t, () -> cases.list(f, null, 50)).get("items");
    }

    /** On the real app_user connection, inside one transaction, with the tenant's context. */
    private <T> T asApp(Tenant t, java.util.function.Supplier<T> body) {
        return TenantContext.runAs(t.id(), () -> appUserTx.execute(s -> body.get()));
    }

    private <T> T asTenant(Tenant t, java.util.function.Supplier<T> body) {
        return TenantContext.runAs(t.id(), body::get);
    }

    private static Map<String, Object> byId(List<Map<String, Object>> items, UUID id) {
        return items.stream().filter(i -> id.toString().equals(i.get("id"))).findFirst().orElseThrow();
    }

    private static List<String> ids(List<Map<String, Object>> items) {
        return items.stream().map(i -> (String) i.get("id")).toList();
    }

    private Tenant tenant(String name) {
        UUID id = UUID.randomUUID(), store = UUID.randomUUID(), owner = UUID.randomUUID(), location = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?)", id, name);
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status) VALUES (?, ?, 'shopify', ?, 'disconnected')",
            store, id, "s-" + id + ".myshopify.com");
        jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role, active) VALUES (?, ?, 'Owner', ?, ?, 'owner', true)",
            owner, id, "owner-" + owner + "@s2.test", passwordEncoder.encode("pass123"));
        jdbc.update("INSERT INTO locations (id, tenant_id, name, is_fulfillment) VALUES (?, ?, 'Main', true)", location, id);
        UUID product = UUID.randomUUID();
        jdbc.update("INSERT INTO products (id, tenant_id, store_id, external_id, title, status) VALUES (?, ?, ?, ?, 'Henley', 'active')",
            product, id, store, "gid://shopify/Product/" + GIDS.incrementAndGet());
        return new Tenant(id, store, product, variant(id, product, "Black / XL"), variant(id, product, "White / L"), location, owner);
    }

    private UUID variant(UUID tenant, UUID product, String title) {
        UUID v = UUID.randomUUID();
        jdbc.update("INSERT INTO variants (id, tenant_id, product_id, external_id, title, sku) VALUES (?, ?, ?, ?, ?, ?)",
            v, tenant, product, "gid://shopify/ProductVariant/" + GIDS.incrementAndGet(), title, "SKU-" + v.toString().substring(0, 6));
        return v;
    }

    private UUID user(UUID tenant, String role) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role, active) VALUES (?, ?, ?, ?, ?, ?::user_role, true)",
            id, tenant, role, role + "-" + id + "@s2.test", passwordEncoder.encode("pass123"), role);
        return id;
    }

    private Order order(Tenant t, String number) {
        UUID id = UUID.randomUUID(), item = UUID.randomUUID();
        jdbc.update("INSERT INTO orders (id, tenant_id, store_id, external_id, number, status, payment_method, placed_at, customer_name) " +
            "VALUES (?, ?, ?, ?, ?, 'delivered'::order_status, 'cod'::order_payment_method, now(), 'Ahmed M.')",
            id, t.id(), t.store(), "gid://shopify/Order/" + GIDS.incrementAndGet(), number);
        jdbc.update("INSERT INTO order_items (id, tenant_id, order_id, variant_id, quantity) VALUES (?, ?, ?, ?, 1)",
            item, t.id(), id, t.black());
        return new Order(id, item, number);
    }

    private UUID request(Tenant t, Order o, String status) {
        return jdbc.queryForObject(
            "INSERT INTO return_requests (tenant_id, order_id, type, status, reference, decided_at) " +
            "VALUES (?, ?, 'refund', ?::return_request_status, ?, CASE WHEN ?::text <> 'requested' THEN now() END) RETURNING id",
            UUID.class, t.id(), o.id(), status, reference(), status);
    }

    private static String reference() {
        String alphabet = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ";
        StringBuilder sb = new StringBuilder("RR-");
        java.util.concurrent.ThreadLocalRandom r = java.util.concurrent.ThreadLocalRandom.current();
        for (int i = 0; i < 6; i++) sb.append(alphabet.charAt(r.nextInt(alphabet.length())));
        return sb.toString();
    }

    private UUID shopifyReturn(Tenant t, Order o, String name, String status) {
        String orderGid = jdbc.queryForObject("SELECT external_id FROM orders WHERE id = ?", String.class, o.id());
        return jdbc.queryForObject(
            "INSERT INTO shopify_returns (tenant_id, store_id, return_gid, order_id, order_gid, name, status, total_quantity, " +
            "    shopify_created_at, raw) VALUES (?, ?, ?, ?, ?, ?, ?, 1, now(), '{}'::jsonb) RETURNING id",
            UUID.class, t.id(), t.store(), "gid://shopify/Return/" + GIDS.incrementAndGet(), o.id(), orderGid, name, status);
    }

    /** @return the line's fulfillment line GID */
    private String returnLine(Tenant t, UUID ret, UUID variant, int qty, String reason, String reasonNote, String customerNote) {
        String fli = "gid://shopify/FulfillmentLineItem/" + GIDS.incrementAndGet();
        String variantGid = jdbc.queryForObject("SELECT external_id FROM variants WHERE id = ?", String.class, variant);
        jdbc.update(
            "INSERT INTO shopify_return_lines (tenant_id, return_id, line_gid, fulfillment_line_gid, variant_gid, variant_id, " +
            "    quantity, processed_quantity, reason, reason_note, customer_note, raw) VALUES (?, ?, ?, ?, ?, ?, ?, 0, ?, ?, ?, '{}'::jsonb)",
            t.id(), ret, "gid://shopify/ReturnLineItem/" + GIDS.incrementAndGet(), fli, variantGid, variant, qty,
            reason, reasonNote, customerNote);
        return fli;
    }

    /** @return the exchange line's GID */
    private String exchangeLine(Tenant t, UUID ret, UUID variant, int qty, String orderLineGid) {
        String gid = "gid://shopify/ExchangeLineItem/" + GIDS.incrementAndGet();
        String variantGid = jdbc.queryForObject("SELECT external_id FROM variants WHERE id = ?", String.class, variant);
        jdbc.update(
            "INSERT INTO shopify_exchange_lines (tenant_id, return_id, line_gid, variant_gid, variant_id, quantity, order_line_gids, raw) " +
            "VALUES (?, ?, ?, ?, ?, ?, ARRAY[?]::text[], '{}'::jsonb)",
            t.id(), ret, gid, variantGid, variant, qty, orderLineGid);
        return gid;
    }

    private UUID fo(Tenant t, Order o, String status, String[] holds, String lineItemGid) {
        String orderGid = jdbc.queryForObject("SELECT external_id FROM orders WHERE id = ?", String.class, o.id());
        String lines = "[{\"foLineGid\":\"gid://shopify/FulfillmentOrderLineItem/" + GIDS.incrementAndGet() + "\",\"lineItemGid\":\"" +
            lineItemGid + "\",\"variantGid\":null,\"remainingQuantity\":1,\"totalQuantity\":1}]";
        return jdbc.queryForObject(
            "INSERT INTO shopify_fulfillment_orders (tenant_id, store_id, fo_gid, order_id, order_gid, status, hold_reasons, " +
            "    line_items, shopify_created_at, raw) VALUES (?, ?, ?, ?, ?, ?, ?::text[], ?::jsonb, now(), '{}'::jsonb) RETURNING id",
            UUID.class, t.id(), t.store(), "gid://shopify/FulfillmentOrder/" + GIDS.incrementAndGet(), o.id(), orderGid, status,
            "{" + String.join(",", holds) + "}", lines);
    }

    private void disposition(Tenant t, UUID ret, String fli, String type, int qty) {
        long k = GIDS.incrementAndGet();
        jdbc.update(
            "INSERT INTO shopify_rfo_dispositions (tenant_id, return_id, disposition_gid, rfo_gid, rfo_line_gid, " +
            "    fulfillment_line_gid, type, quantity, raw) VALUES (?, ?, ?, ?, ?, ?, ?, ?, '{}'::jsonb)",
            t.id(), ret, "gid://shopify/ReverseFulfillmentOrderDisposition/" + k, "gid://shopify/ReverseFulfillmentOrder/" + k,
            "gid://shopify/ReverseFulfillmentOrderLineItem/" + k, fli, type, qty);
    }

    /** A piece of the order's line that came back (released allocation + return_received event for the order). */
    private String returnedPiece(Tenant t, Order o, String status) {
        String id = UlidGenerator.generate();
        jdbc.update("INSERT INTO pieces (id, tenant_id, variant_id, barcode, short_code, status, current_location_id) " +
            "VALUES (?, ?, ?, ?, 'P' || LPAD((abs(hashtext(?)) % 999999 + 1)::text, 6, '0'), ?::piece_status, ?)",
            id, t.id(), t.black(), "PC-" + id, id, status, t.location());
        jdbc.update("INSERT INTO allocations (tenant_id, order_item_id, piece_id, status) VALUES (?, ?, ?, 'released')",
            t.id(), o.item(), id);
        jdbc.update("INSERT INTO piece_events (tenant_id, piece_id, event_type, order_id, from_status, to_status, occurred_at) " +
            "VALUES (?, ?, 'return_received', ?, 'delivered', 'return_pending_inspection', now() - interval '1 minute')",
            t.id(), id, o.id());
        return id;
    }

    private void grade(Tenant t, String piece, String event, String to) {
        jdbc.update("UPDATE pieces SET status = ?::piece_status WHERE id = ?", to, piece);
        jdbc.update("INSERT INTO piece_events (tenant_id, piece_id, event_type, from_status, to_status) " +
            "VALUES (?, ?, ?, 'return_pending_inspection', ?::piece_status)", t.id(), piece, event, to);
    }

    private String base() { return "http://localhost:" + port; }

    private String login(UUID userId) {
        String email = jdbc.queryForObject("SELECT email FROM users WHERE id = ?", String.class, userId);
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<AccessTokenResponse> resp = rest.postForEntity(base() + "/api/v1/auth/login",
            new HttpEntity<>(Map.of("email", email, "password", "pass123"), h), AccessTokenResponse.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        return resp.getBody().accessToken();
    }

    @SuppressWarnings("rawtypes")
    private ResponseEntity<Map> get(String path, String token) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        return rest.exchange(base() + path, HttpMethod.GET, new HttpEntity<>(h), Map.class);
    }

    private String raw(String path, String token) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        ResponseEntity<String> r = rest.exchange(base() + path, HttpMethod.GET, new HttpEntity<>(h), String.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        return r.getBody();
    }
}
