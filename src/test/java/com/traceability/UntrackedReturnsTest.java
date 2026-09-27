package com.traceability;

import com.traceability.identity.model.AccessTokenResponse;
import com.traceability.integrations.shopify.ShopifyGateway;
import com.traceability.inventory.*;
import com.traceability.portal.*;
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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/**
 * Step 6a — portal returns / exchanges for UNTRACKED order lines (an order_items row with no
 * allocation of any status): lookup and submit bind order-line UNITS instead of pieces; the
 * per-item "Arrived" action (drawer + return session) takes an untracked item straight to done;
 * the canonical scan-evidence rule's untracked-arrival clause; no stock, no Shopify.
 *
 * L lookup · S submit · A Arrived (guards, exception, undo, leg-level refusal) · F lifecycle ·
 * E canonical rule · X cross-tenant on a real app_user connection.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class UntrackedReturnsTest {

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
    @Autowired TestRestTemplate rest;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired PortalService portal;
    @Autowired PortalTokenService tokens;
    @Autowired ReturnRequestService requests;
    @Autowired ReturnSessionService sessions;
    @Autowired ExceptionService exceptions;
    @Autowired InventoryLedger ledger;
    @Autowired ReturnService returnService;
    @Autowired ShipmentLinkService shipmentLinkService;
    @Autowired PlatformTransactionManager txm;
    @MockBean JobScheduler jobScheduler;
    @MockBean ShopifyGateway shopifyGateway;

    record Tenant(UUID id, UUID store, UUID location, UUID owner, UUID worker, String slug,
                  UUID shirtM, UUID shirtL, UUID scarf) {}

    Tenant a, b;
    String ownerA, workerA;

    @BeforeAll
    void setup() {
        a = tenant("Nour 6a", "nour-6a");
        b = tenant("Other 6a", "other-6a");
        ownerA = login(a.owner());
        workerA = login(a.worker());
    }

    @AfterEach
    void cleanup() {
        TenantContext.clear();
        for (Tenant t : List.of(a, b)) {
            jdbc.update("DELETE FROM exception_resolutions WHERE tenant_id = ?", t.id());
            jdbc.update("DELETE FROM return_refunds WHERE tenant_id = ?", t.id());
            jdbc.update("DELETE FROM return_session_items WHERE tenant_id = ?", t.id());
            jdbc.update("DELETE FROM return_session_shipments WHERE tenant_id = ?", t.id());
            jdbc.update("DELETE FROM return_sessions WHERE tenant_id = ?", t.id());
            jdbc.update("UPDATE exchanges SET outbound_order_id = NULL, matched_order_id = NULL, return_request_id = NULL WHERE tenant_id = ?", t.id());
            jdbc.update("DELETE FROM exchanges WHERE tenant_id = ?", t.id());
            jdbc.update("DELETE FROM portal_lookup_attempts WHERE tenant_id = ?", t.id());
            jdbc.update("DELETE FROM return_request_items WHERE tenant_id = ?", t.id());
            jdbc.update("DELETE FROM return_requests WHERE tenant_id = ?", t.id());
            jdbc.update("DELETE FROM piece_events WHERE tenant_id = ?", t.id());
            jdbc.update("UPDATE pieces SET current_order_id = NULL WHERE tenant_id = ?", t.id());
            jdbc.update("DELETE FROM allocations WHERE tenant_id = ?", t.id());
            jdbc.update("DELETE FROM pieces WHERE tenant_id = ?", t.id());
            jdbc.update("DELETE FROM shipment_status_history WHERE tenant_id = ?", t.id());
            jdbc.update("DELETE FROM shipments WHERE tenant_id = ?", t.id());
            jdbc.update("DELETE FROM order_items WHERE tenant_id = ?", t.id());
            jdbc.update("DELETE FROM orders WHERE tenant_id = ?", t.id());
            jdbc.update("UPDATE tenants SET portal_exchanges_enabled = false, portal_auto_approve = false WHERE id = ?", t.id());
        }
        clearInvocations(shopifyGateway);
    }

    // ── L: lookup ─────────────────────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void l1_untrackedLinesOffered_trackedUnchanged_currentQuantityCap_partialIsTracked_requestedReduces() {
        Order o = order(a, "#6001", "01060000001");
        String piece = trackedPiece(a, o, a.scarf());                       // a tracked line
        UUID shirt = untrackedLine(a, o, a.shirtM(), 3, 2);                // untracked: qty 3, current_quantity 2
        UUID partial = untrackedLine(a, o, a.shirtL(), 2, null);           // partially allocated → tracked
        allocate(a, partial, trackedPieceOf(a, a.shirtL()));

        List<Map<String, Object>> lines = lookupLines(a, "6001", "01060000001");
        Map<String, Object> tracked = lines.stream().filter(l -> a.scarf().toString().equals(l.get("variantId"))).findFirst().orElseThrow();
        assertThat(tracked.keySet()).containsExactlyInAnyOrder("variantId", "productTitle", "variantTitle", "imageUrl",
            "deliveredQuantity", "returnableQuantity", "nonReturnable");
        Map<String, Object> untracked = lines.stream().filter(l -> shirt.toString().equals(l.get("orderItemId"))).findFirst().orElseThrow();
        assertThat(untracked).containsEntry("tracked", false).containsEntry("variantId", a.shirtM().toString())
            .containsEntry("productTitle", "Linen Shirt").containsEntry("variantTitle", "White / M")
            .containsEntry("returnableQuantity", 2);
        assertThat(lines).noneMatch(l -> partial.toString().equals(l.get("orderItemId")));

        submit(a, o, List.of(new PortalService.SubmitLine(null, 1, "wrong_size", shirt)), null);
        Map<String, Object> again = lookupLines(a, "6001", "01060000001").stream()
            .filter(l -> shirt.toString().equals(l.get("orderItemId"))).findFirst().orElseThrow();
        assertThat(again.get("returnableQuantity")).isEqualTo(1);
        assertThat(piece).isNotNull();
    }

    @Test
    @SuppressWarnings("unchecked")
    void l2_exchangeOptionsForAnUntrackedLine() {
        jdbc.update("UPDATE tenants SET portal_exchanges_enabled = true WHERE id = ?", a.id());
        stock(a, a.shirtL(), 2);
        Order o = order(a, "#6002", "01060000002");
        UUID shirt = untrackedLine(a, o, a.shirtM(), 1, null);
        Map<String, Object> line = lookupLines(a, "6002", "01060000002").stream()
            .filter(l -> shirt.toString().equals(l.get("orderItemId"))).findFirst().orElseThrow();
        List<Map<String, Object>> options = (List<Map<String, Object>>) line.get("exchangeOptions");
        assertThat(options).extracting(m -> m.get("variantId")).containsExactly(a.shirtL().toString());
        assertThat(options.get(0).get("inStock")).isEqualTo(true);
    }

    // ── S: submit ─────────────────────────────────────────────────────────────

    @Test
    void s1_untrackedUnitsBound_mixedRequest_exchangeWithUntrackedLine() {
        Order o = order(a, "#6101", "01060000101");
        String piece = trackedPiece(a, o, a.scarf());
        UUID shirt = untrackedLine(a, o, a.shirtM(), 3, null);
        UUID r = submit(a, o, List.of(new PortalService.SubmitLine(null, 2, "wrong_size", shirt),
            new PortalService.SubmitLine(a.scarf(), 1, "damaged")), null);
        assertThat(jdbc.queryForList("SELECT unit_no::int FROM return_request_items WHERE request_id = ? AND order_item_id = ? ORDER BY unit_no",
            Integer.class, r, shirt)).containsExactly(1, 2);
        assertThat(jdbc.queryForObject("SELECT variant_id FROM return_request_items WHERE request_id = ? AND order_item_id = ? LIMIT 1",
            UUID.class, r, shirt)).isEqualTo(a.shirtM());
        assertThat(jdbc.queryForObject("SELECT piece_id FROM return_request_items WHERE request_id = ? AND order_item_id IS NULL",
            String.class, r)).isEqualTo(piece);
        UUID r2 = submit(a, o, List.of(new PortalService.SubmitLine(null, 1, "other", shirt)), null);
        assertThat(jdbc.queryForObject("SELECT unit_no::int FROM return_request_items WHERE request_id = ?", Integer.class, r2)).isEqualTo(3);
        assertThat(outcome(a, o, List.of(new PortalService.SubmitLine(null, 1, "other", shirt)), null))
            .as("no unit left").isEqualTo(PortalService.SubmitOutcome.INVALID);

        jdbc.update("UPDATE tenants SET portal_exchanges_enabled = true WHERE id = ?", a.id());
        stock(a, a.shirtL(), 1);
        Order o2 = order(a, "#6102", "01060000102");
        UUID line2 = untrackedLine(a, o2, a.shirtM(), 1, null);
        UUID ex = submit(a, o2, List.of(new PortalService.SubmitLine(null, 1, "wrong_size", line2)), a.shirtL());
        assertThat(jdbc.queryForMap("SELECT type, (SELECT replacement_variant_id FROM return_request_items WHERE request_id = rr.id) AS repl " +
            "FROM return_requests rr WHERE id = ?", ex)).containsEntry("type", "exchange").containsEntry("repl", a.shirtL());
    }

    @Test
    void s2_raceForTheLastUnit_blocksOnTheUnitIndex_then409() throws Exception {
        Order o = order(a, "#6201", "01060000201");
        UUID shirt = untrackedLine(a, o, a.shirtM(), 1, null);
        String token = tokens.issue(a.id(), o.id());
        PortalService.SubmitRequest req = new PortalService.SubmitRequest(
            List.of(new PortalService.SubmitLine(null, 1, "wrong_size", shirt)), null, null);
        // A competing submission bound unit 1 but hasn't committed (READ COMMITTED: ours can't
        // see it, binds the same unit, then blocks on return_request_items_one_active_per_unit).
        try (java.sql.Connection competitor = java.sql.DriverManager.getConnection(POSTGRES.getJdbcUrl(), "postgres", "postgres")) {
            competitor.setAutoCommit(false);
            UUID reqId = UUID.randomUUID();
            try (var st = competitor.prepareStatement(
                    "INSERT INTO return_requests (id, tenant_id, order_id, reference) VALUES (?, ?, ?, 'RR-RACE6A')")) {
                st.setObject(1, reqId); st.setObject(2, a.id()); st.setObject(3, o.id()); st.executeUpdate();
            }
            try (var st = competitor.prepareStatement(
                    "INSERT INTO return_request_items (tenant_id, request_id, variant_id, reason_code, order_item_id, unit_no) " +
                    "VALUES (?, ?, ?, 'damaged', ?, 1)")) {
                st.setObject(1, a.id()); st.setObject(2, reqId); st.setObject(3, a.shirtM()); st.setObject(4, shirt); st.executeUpdate();
            }
            ExecutorService pool = Executors.newSingleThreadExecutor();
            Future<PortalService.SubmitOutcome> ours = pool.submit(() -> portal.submit(a.slug(), token, req).orElseThrow().outcome());
            Thread.sleep(1500);
            assertThat(ours.isDone()).as("blocked on the unit index while the competitor is open").isFalse();
            competitor.commit();
            assertThat(ours.get(20, TimeUnit.SECONDS)).isEqualTo(PortalService.SubmitOutcome.CONFLICT);
            pool.shutdown();
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM return_request_items WHERE order_item_id = ?", Integer.class, shirt)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM return_requests WHERE order_id = ?", Integer.class, o.id()))
            .as("the losing request rolled back entirely").isEqualTo(1);
    }

    // ── A: the Arrived action ─────────────────────────────────────────────────

    @Test
    void a1_drawer_sellable_exception_refundPending_refund_refunded_undoRefusedAfterRefund() {
        Order o = order(a, "#6301", "01060000301");
        UUID shirt = untrackedLine(a, o, a.shirtM(), 1, null);
        UUID r = approve(a, submit(a, o, List.of(new PortalService.SubmitLine(null, 1, "wrong_size", shirt)), null));
        UUID item = itemOf(r, shirt);

        assertThat(post("/api/v1/return-requests/" + r + "/items/" + item + "/arrived", Map.of("condition", "sellable"), ownerA)
            .getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(jdbc.queryForMap("SELECT item_status, active, arrived_condition, arrived_by, done_at IS NOT NULL AS done " +
            "FROM return_request_items WHERE id = ?", item))
            .containsEntry("item_status", "done").containsEntry("active", false).containsEntry("arrived_condition", "sellable")
            .containsEntry("arrived_by", a.owner()).containsEntry("done", true);
        assertThat(status(r)).isEqualTo("refund_pending");
        assertThat(events(r)).contains("item_arrived_untracked", "received", "refund_pending");
        Map<String, Object> ex = problems(a, "request_item_to_receive").get(0);
        assertThat((String) ex.get("descriptionEn")).contains("Linen Shirt / White / M").contains("#6301")
            .contains(ref(r)).contains("next Receiving session");
        assertThat((String) ex.get("descriptionAr")).contains(ref(r));
        assertThat(ex.get("actionUrl")).isEqualTo("/receiving");

        TenantContext.set(a.id());
        requests.recordRefund(r, "cash", new BigDecimal("450"), LocalDate.now(), null, null, a.owner());
        assertStatus(409, () -> requests.undoItemArrived(r, item, a.owner()));
        requests.markRefunded(r, a.owner());
        TenantContext.clear();
        assertThat(status(r)).isEqualTo("refunded");
        verifyNoInteractions(shopifyGateway);
    }

    @Test
    void a2_guards_trackedItem_notAwaiting_notOpen_badCondition_workerForbiddenOnDrawer() {
        Order o = order(a, "#6401", "01060000401");
        String piece = trackedPiece(a, o, a.scarf());
        UUID shirt = untrackedLine(a, o, a.shirtM(), 1, null);
        UUID r = submit(a, o, List.of(new PortalService.SubmitLine(null, 1, "wrong_size", shirt),
            new PortalService.SubmitLine(a.scarf(), 1, "damaged")), null);
        UUID untracked = itemOf(r, shirt);
        UUID tracked = jdbc.queryForObject("SELECT id FROM return_request_items WHERE request_id = ? AND piece_id = ?", UUID.class, r, piece);

        TenantContext.set(a.id());
        assertStatus(409, () -> requests.itemArrived(r, untracked, "sellable", a.owner()));   // still 'requested'
        TenantContext.clear();
        approve(a, r);
        TenantContext.set(a.id());
        assertStatus(409, () -> requests.itemArrived(r, tracked, "sellable", a.owner()));     // tracked → scan it
        assertStatus(400, () -> requests.itemArrived(r, untracked, "broken", a.owner()));
        requests.itemArrived(r, untracked, "damaged", a.owner());
        assertStatus(409, () -> requests.itemArrived(r, untracked, "sellable", a.owner()));   // no longer awaiting
        TenantContext.clear();
        assertThat(post("/api/v1/return-requests/" + r + "/items/" + untracked + "/arrived/undo", null, workerA).getStatusCode())
            .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(post("/api/v1/return-requests/" + r + "/items/" + untracked + "/arrived", Map.of("condition", "damaged"), workerA)
            .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void a3_session_needsOpenSessionAndScannedAwb_workerAllowed_damagedNoException_undoRestores_legMarkReceivedRefuses() {
        Order o = order(a, "#6501", "01060000501");
        UUID shirt = untrackedLine(a, o, a.shirtM(), 1, null);
        UUID r = approve(a, submit(a, o, List.of(new PortalService.SubmitLine(null, 1, "wrong_size", shirt)), null));
        UUID item = itemOf(r, shirt);
        Leg leg = returnLeg(a, o, r, "7300000001");

        UUID s = openSession(a);
        assertThat(post("/api/v1/returns/sessions/" + s + "/request-items/" + item + "/arrived", Map.of("condition", "damaged"), workerA)
            .getStatusCode()).as("AWB not scanned yet").isEqualTo(HttpStatus.CONFLICT);
        scanAwb(a, s, "7300000001");
        assertThat(post("/api/v1/returns/sessions/" + s + "/parcels/" + leg.id() + "/mark-received", null, workerA)
            .getStatusCode()).as("request-linked leg: per-item Arrived only").isEqualTo(HttpStatus.CONFLICT);
        assertThat(post("/api/v1/returns/sessions/" + s + "/request-items/" + item + "/arrived", Map.of("condition", "damaged"), workerA)
            .getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(status(r)).isEqualTo("refund_pending");
        assertThat(problems(a, "request_item_to_receive")).as("damaged → no exception").isEmpty();

        assertThat(post("/api/v1/returns/sessions/" + s + "/request-items/" + item + "/arrived/undo", null, workerA)
            .getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(jdbc.queryForMap("SELECT item_status, active, arrived_at, arrived_condition FROM return_request_items WHERE id = ?", item))
            .containsEntry("item_status", "awaiting").containsEntry("active", true)
            .containsEntry("arrived_at", null).containsEntry("arrived_condition", null);
        assertThat(status(r)).isEqualTo("pickup_booked");
        assertThat(events(r)).contains("item_arrival_undone");

        post("/api/v1/returns/sessions/" + s + "/request-items/" + item + "/arrived", Map.of("condition", "sellable"), workerA);
        assertThat(problems(a, "request_item_to_receive")).hasSize(1);
        post("/api/v1/returns/sessions/" + s + "/request-items/" + item + "/arrived/undo", null, workerA);
        assertThat(problems(a, "request_item_to_receive")).as("undo removes the exception").isEmpty();

        jdbc.update("UPDATE return_sessions SET status = 'closed' WHERE id = ?", s);
        assertThat(post("/api/v1/returns/sessions/" + s + "/request-items/" + item + "/arrived", Map.of("condition", "damaged"), workerA)
            .getStatusCode()).as("session closed").isEqualTo(HttpStatus.CONFLICT);
        verifyNoInteractions(shopifyGateway);
    }

    // ── F: lifecycle ──────────────────────────────────────────────────────────

    @Test
    void f1_exchangeWithUntrackedOldItem_arrivedViaExchangeAwb_exchanged_exchangesRowReturnReceived() {
        Order o = order(a, "#6601", "01060000601");
        UUID shirt = untrackedLine(a, o, a.shirtM(), 1, null);
        jdbc.update("UPDATE tenants SET portal_exchanges_enabled = true WHERE id = ?", a.id());
        stock(a, a.shirtL(), 1);
        UUID r = submit(a, o, List.of(new PortalService.SubmitLine(null, 1, "wrong_size", shirt)), a.shirtL());
        jdbc.update("UPDATE return_requests SET status = 'pickup_booked', booking_status = 'booked', bosta_tracking_number = '7400000001', " +
            "decided_at = now() WHERE id = ?", r);
        jdbc.update("INSERT INTO exchanges (tenant_id, tracking_number, status, return_request_id, matched_order_id, match_method, raw) " +
            "VALUES (?, '7400000001', 'matched', ?, ?, 'reference', '{}'::jsonb)", a.id(), r, o.id());
        UUID internal = jdbc.queryForObject("INSERT INTO orders (tenant_id, store_id, external_id, number, placed_at) " +
            "VALUES (?, ?, 'internal:exchange:7400000001', 'EXC-7400000001', now()) RETURNING id", UUID.class, a.id(), a.store());
        jdbc.update("INSERT INTO shipments (tenant_id, order_id, provider, tracking_number, internal_state, shipment_leg) " +
            "VALUES (?, ?, 'bosta', '7400000001', 'delivered'::shipment_internal_state, 'forward')", a.id(), internal);
        jdbc.update("UPDATE exchanges SET outbound_order_id = ? WHERE tracking_number = '7400000001'", internal);

        UUID s = openSession(a);
        scanAwb(a, s, "7400000001");
        TenantContext.set(a.id());
        sessions.requestItemArrived(s, itemOf(r, shirt), "sellable", a.worker());
        TenantContext.clear();
        assertThat(status(r)).isEqualTo("exchanged");
        assertThat(jdbc.queryForObject("SELECT status FROM exchanges WHERE tracking_number = '7400000001'", String.class))
            .isEqualTo("return_received");
        verifyNoInteractions(shopifyGateway);
    }

    @Test
    void f2_mixedRequest_needsBothPaths() {
        Order o = order(a, "#6701", "01060000701");
        String piece = trackedPiece(a, o, a.scarf());
        UUID shirt = untrackedLine(a, o, a.shirtM(), 1, null);
        UUID r = approve(a, submit(a, o, List.of(new PortalService.SubmitLine(null, 1, "wrong_size", shirt),
            new PortalService.SubmitLine(a.scarf(), 1, "damaged")), null));

        TenantContext.set(a.id());
        requests.itemArrived(r, itemOf(r, shirt), "damaged", a.owner());
        TenantContext.clear();
        assertThat(status(r)).as("the tracked piece is still awaited").isEqualTo("approved");

        UUID s = openSession(a);
        TenantContext.set(a.id());
        sessions.scan(s, "PC-" + piece, a.location(), a.owner());
        assertThat(status(r)).isEqualTo("received");
        sessions.disposition(s, piece, "restock", null, a.location(), a.owner());
        TenantContext.clear();
        assertThat(status(r)).isEqualTo("refund_pending");
    }

    // ── E: canonical scan-evidence rule ─────────────────────────────────────

    @Test
    void e1_requestLinkedLeg_getsEvidenceFromAnUntrackedArrival_stampedOnClose() {
        Order o = order(a, "#6801", "01060000801");
        UUID shirt = untrackedLine(a, o, a.shirtM(), 1, null);
        UUID r = approve(a, submit(a, o, List.of(new PortalService.SubmitLine(null, 1, "wrong_size", shirt)), null));
        Leg leg = returnLeg(a, o, r, "7500000001");
        jdbc.update("UPDATE shipments SET internal_state = 'returned' WHERE id = ?", leg.id());
        assertThat(awaitingScan(leg.id())).as("before: waiting to be scanned").isTrue();

        UUID s = openSession(a);
        scanAwb(a, s, "7500000001");
        TenantContext.set(a.id());
        sessions.requestItemArrived(s, itemOf(r, shirt), "sellable", a.worker());
        assertThat(awaitingScan(leg.id())).as("the untracked arrival is evidence").isFalse();
        sessions.close(s, a.owner());
        TenantContext.clear();
        assertThat(jdbc.queryForObject("SELECT return_intake_completed_at IS NOT NULL FROM shipments WHERE id = ?", Boolean.class, leg.id()))
            .as("close stamps the leg on the untracked arrival").isTrue();
    }

    // ── X: cross-tenant on a real app_user connection ─────────────────────────

    @Test
    void x1_bothArrivedEndpointsAndUndo_crossTenant404_sameTenantControl() {
        DataSource ds = new TenantAwareDataSource(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "app_user", "testpw"));
        JdbcTemplate appJdbc = new JdbcTemplate(ds);
        TransactionTemplate appTx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        ReturnRequestService appRequests = new ReturnRequestService(appJdbc);
        ReturnSessionService appSessions = new ReturnSessionService(appJdbc, ledger, returnService, shipmentLinkService, Clock.systemUTC());

        Order mineO = order(a, "#6901", "01060000901");
        UUID mineLine = untrackedLine(a, mineO, a.shirtM(), 2, null);
        UUID mine = approve(a, submit(a, mineO, List.of(new PortalService.SubmitLine(null, 2, "wrong_size", mineLine)), null));
        returnLeg(a, mineO, mine, "7600000001");
        Order theirsO = order(b, "#6901", "01060000901");
        UUID theirsLine = untrackedLine(b, theirsO, b.shirtM(), 2, null);
        UUID theirs = approve(b, submit(b, theirsO, List.of(new PortalService.SubmitLine(null, 2, "wrong_size", theirsLine)), null));
        returnLeg(b, theirsO, theirs, "7600000002");
        List<UUID> mineItems = jdbc.queryForList("SELECT id FROM return_request_items WHERE request_id = ? ORDER BY unit_no", UUID.class, mine);
        List<UUID> theirsItems = jdbc.queryForList("SELECT id FROM return_request_items WHERE request_id = ? ORDER BY unit_no", UUID.class, theirs);
        UUID s = openSession(a);
        scanAwb(a, s, "7600000001");

        assertStatus(404, () -> TenantContext.runAs(a.id(), () -> appTx.execute(x -> { appRequests.itemArrived(theirs, theirsItems.get(0), "sellable", a.owner()); return null; })));
        assertStatus(404, () -> TenantContext.runAs(a.id(), () -> appTx.execute(x -> { appSessions.requestItemArrived(s, theirsItems.get(1), "sellable", a.owner()); return null; })));
        jdbc.update("UPDATE return_request_items SET item_status = 'done', active = false, arrived_at = now(), done_at = now(), " +
            "arrived_condition = 'damaged' WHERE id = ?", theirsItems.get(0));
        assertStatus(404, () -> TenantContext.runAs(a.id(), () -> appTx.execute(x -> { appRequests.undoItemArrived(theirs, theirsItems.get(0), a.owner()); return null; })));
        assertStatus(404, () -> TenantContext.runAs(a.id(), () -> appTx.execute(x -> { appSessions.undoRequestItemArrived(s, theirsItems.get(0), a.owner()); return null; })));
        assertThat(jdbc.queryForObject("SELECT item_status FROM return_request_items WHERE id = ?", String.class, theirsItems.get(1))).isEqualTo("awaiting");
        assertThat(jdbc.queryForObject("SELECT item_status FROM return_request_items WHERE id = ?", String.class, theirsItems.get(0))).isEqualTo("done");

        TenantContext.runAs(a.id(), () -> appTx.execute(x -> { appRequests.itemArrived(mine, mineItems.get(0), "sellable", a.owner()); return null; }));
        TenantContext.runAs(a.id(), () -> appTx.execute(x -> { appSessions.requestItemArrived(s, mineItems.get(1), "damaged", a.owner()); return null; }));
        assertThat(status(mine)).isEqualTo("refund_pending");
        TenantContext.runAs(a.id(), () -> appTx.execute(x -> { appRequests.undoItemArrived(mine, mineItems.get(0), a.owner()); return null; }));
        TenantContext.runAs(a.id(), () -> appTx.execute(x -> { appSessions.undoRequestItemArrived(s, mineItems.get(1), a.owner()); return null; }));
        assertThat(jdbc.queryForList("SELECT item_status FROM return_request_items WHERE request_id = ?", String.class, mine))
            .containsOnly("awaiting");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    record Order(UUID id, String number) {}
    record Leg(UUID id, String tracking) {}

    private Tenant tenant(String name, String slug) {
        UUID id = UUID.randomUUID(), store = UUID.randomUUID(), owner = UUID.randomUUID(), worker = UUID.randomUUID(),
            location = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name, portal_slug, portal_enabled) VALUES (?, ?, ?, true)", id, name, slug);
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status) VALUES (?, ?, 'shopify', ?, 'disconnected')",
            store, id, slug + ".myshopify.com");
        jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role, active) VALUES (?, ?, 'Owner', ?, ?, 'owner', true)",
            owner, id, "owner-" + owner + "@test.local", passwordEncoder.encode("pass123"));
        jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role, active) VALUES (?, ?, 'Worker', ?, ?, 'worker', true)",
            worker, id, "worker-" + worker + "@test.local", passwordEncoder.encode("pass123"));
        jdbc.update("INSERT INTO locations (id, tenant_id, name, is_fulfillment) VALUES (?, ?, 'Main', true)", location, id);
        UUID shirt = UUID.randomUUID();
        jdbc.update("INSERT INTO products (id, tenant_id, store_id, external_id, title, status) VALUES (?, ?, ?, ?, 'Linen Shirt', 'active')",
            shirt, id, store, "P-shirt-" + slug);
        UUID scarfP = UUID.randomUUID();
        jdbc.update("INSERT INTO products (id, tenant_id, store_id, external_id, title, status) VALUES (?, ?, ?, ?, 'Wool Scarf', 'active')",
            scarfP, id, store, "P-scarf-" + slug);
        return new Tenant(id, store, location, owner, worker, slug,
            variant(id, shirt, "White / M"), variant(id, shirt, "White / L"), variant(id, scarfP, "Grey"));
    }

    private UUID variant(UUID tenant, UUID product, String title) {
        UUID v = UUID.randomUUID();
        jdbc.update("INSERT INTO variants (id, tenant_id, product_id, external_id, title, sku) VALUES (?, ?, ?, ?, ?, ?)",
            v, tenant, product, "V-" + v, title, "SKU-" + v.toString().substring(0, 6));
        return v;
    }

    /** A delivered order (forward leg delivered yesterday) with no lines yet. */
    private Order order(Tenant t, String number, String phone) {
        UUID order = jdbc.queryForObject(
            "INSERT INTO orders (tenant_id, store_id, external_id, number, status, payment_method, placed_at, customer_name, customer_phone, pii_source) " +
            "VALUES (?, ?, ?, ?, 'delivered'::order_status, 'cod'::order_payment_method, now(), 'Mona', ?, 'bosta') RETURNING id",
            UUID.class, t.id(), t.store(), "gid://shopify/Order/" + UUID.randomUUID(), number, phone);
        jdbc.update("INSERT INTO shipments (tenant_id, order_id, provider, tracking_number, internal_state, shipment_leg, delivered_at) " +
            "VALUES (?, ?, 'bosta', ?, 'delivered'::shipment_internal_state, 'forward', now() - interval '1 day')",
            t.id(), order, String.valueOf(ThreadLocalRandom.current().nextLong(1_000_000_000L, 1_999_999_999L)));
        return new Order(order, number);
    }

    /** An untracked order line (no allocation); {@code currentQuantity} goes into a REST-shaped raw when set. */
    private UUID untrackedLine(Tenant t, Order o, UUID variant, int quantity, Integer currentQuantity) {
        UUID id = UUID.randomUUID();
        String raw = currentQuantity == null ? null : "{\"admin_graphql_api_id\":\"gid://shopify/LineItem/1\",\"current_quantity\":" + currentQuantity + "}";
        jdbc.update("INSERT INTO order_items (id, tenant_id, order_id, variant_id, quantity, raw) VALUES (?, ?, ?, ?, ?, ?::jsonb)",
            id, t.id(), o.id(), variant, quantity, raw);
        return id;
    }

    /** A tracked line: one delivered piece of {@code variant} with a packed allocation. */
    private String trackedPiece(Tenant t, Order o, UUID variant) {
        String piece = trackedPieceOf(t, variant);
        jdbc.update("UPDATE pieces SET status = 'delivered', current_order_id = ? WHERE id = ?", o.id(), piece);
        UUID item = UUID.randomUUID();
        jdbc.update("INSERT INTO order_items (id, tenant_id, order_id, variant_id, quantity) VALUES (?, ?, ?, ?, 1)", item, t.id(), o.id(), variant);
        allocate(t, item, piece);
        return piece;
    }

    private String trackedPieceOf(Tenant t, UUID variant) {
        String piece = UlidGenerator.generate();
        jdbc.update("INSERT INTO pieces (id, tenant_id, variant_id, barcode, short_code, status, last_event_at) " +
            "VALUES (?, ?, ?, ?, 'P' || LPAD((abs(hashtext(?)) % 999999 + 1)::text, 6, '0'), 'delivered'::piece_status, now())",
            piece, t.id(), variant, "PC-" + piece, piece);
        return piece;
    }

    private void allocate(Tenant t, UUID orderItem, String piece) {
        jdbc.update("INSERT INTO allocations (tenant_id, order_item_id, piece_id, status) VALUES (?, ?, ?, 'packed')", t.id(), orderItem, piece);
    }

    private void stock(Tenant t, UUID variant, int n) {
        for (int i = 0; i < n; i++) {
            String p = UlidGenerator.generate();
            jdbc.update("INSERT INTO pieces (id, tenant_id, variant_id, barcode, short_code, status, current_location_id) " +
                "VALUES (?, ?, ?, ?, 'P' || LPAD((abs(hashtext(?)) % 999999 + 1)::text, 6, '0'), 'available'::piece_status, ?)",
                p, t.id(), variant, "PC-" + p, p, t.location());
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> lookupLines(Tenant t, String number, String phone) {
        PortalService.LookupResult res = portal.lookup(t.slug(), number, phone).orElseThrow();
        assertThat(res.outcome()).isEqualTo(PortalService.Outcome.SUCCESS);
        return (List<Map<String, Object>>) res.body().get("lines");
    }

    private PortalService.SubmitOutcome outcome(Tenant t, Order o, List<PortalService.SubmitLine> lines, UUID replacement) {
        PortalService.SubmitRequest req = replacement == null ? new PortalService.SubmitRequest(lines, null, null)
            : new PortalService.SubmitRequest(lines, null, null, null, "exchange", replacement, true);
        return portal.submit(t.slug(), tokens.issue(t.id(), o.id()), req).orElseThrow().outcome();
    }

    /** Submits and returns the new request's id (the newest request of the order). */
    private UUID submit(Tenant t, Order o, List<PortalService.SubmitLine> lines, UUID replacement) {
        assertThat(outcome(t, o, lines, replacement)).isEqualTo(PortalService.SubmitOutcome.CREATED);
        return jdbc.queryForObject("SELECT id FROM return_requests WHERE order_id = ? ORDER BY created_at DESC, id DESC LIMIT 1",
            UUID.class, o.id());
    }

    private UUID approve(Tenant t, UUID request) {
        TenantContext.runAs(t.id(), () -> requests.approve(request, t.owner()));
        return request;
    }

    private UUID itemOf(UUID request, UUID orderItem) {
        return jdbc.queryForObject("SELECT id FROM return_request_items WHERE request_id = ? AND order_item_id = ? ORDER BY unit_no LIMIT 1",
            UUID.class, request, orderItem);
    }

    /** A courier-return leg for the order, linked to the request (booked by Traced → pickup_booked). */
    private Leg returnLeg(Tenant t, Order o, UUID request, String tracking) {
        UUID id = jdbc.queryForObject("INSERT INTO shipments (tenant_id, order_id, provider, tracking_number, internal_state, shipment_leg, created_at) " +
            "VALUES (?, ?, 'bosta', ?, 'with_courier'::shipment_internal_state, 'return', now() - interval '1 hour') RETURNING id",
            UUID.class, t.id(), o.id(), tracking);
        jdbc.update("UPDATE return_requests SET return_shipment_id = ?, status = 'pickup_booked', link_source = 'traced_booking' WHERE id = ?",
            id, request);
        return new Leg(id, tracking);
    }

    private UUID openSession(Tenant t) {
        return TenantContext.runAs(t.id(), () -> sessions.createSession(null, t.owner()));
    }

    private void scanAwb(Tenant t, UUID session, String awb) {
        TenantContext.runAs(t.id(), () -> sessions.scan(session, awb, t.location(), t.owner()));
    }

    private boolean awaitingScan(UUID shipmentId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
            "SELECT " + ShipmentLinkService.RETURN_LEG_AWAITING_SCAN_SQL + " FROM shipments s WHERE s.id = ?", Boolean.class, shipmentId));
    }

    private String status(UUID request) {
        return jdbc.queryForObject("SELECT status::text FROM return_requests WHERE id = ?", String.class, request);
    }

    private String ref(UUID request) {
        return jdbc.queryForObject("SELECT reference FROM return_requests WHERE id = ?", String.class, request);
    }

    private List<String> events(UUID request) {
        return jdbc.queryForList("SELECT event_type FROM return_request_events WHERE request_id = ? ORDER BY occurred_at, id", String.class, request);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> problems(Tenant t, String type) {
        return TenantContext.runAs(t.id(), () -> new TransactionTemplate(txm).execute(x ->
            (List<Map<String, Object>>) exceptions.listExceptions(type, null, 0, 100).get("items")));
    }

    private void assertStatus(int code, Runnable body) {
        assertThatThrownBy(body::run).isInstanceOfSatisfying(ResponseStatusException.class,
            e -> assertThat(e.getStatusCode().value()).isEqualTo(code));
    }

    private String login(UUID userId) {
        String email = jdbc.queryForObject("SELECT email FROM users WHERE id = ?", String.class, userId);
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<AccessTokenResponse> resp = rest.postForEntity("http://localhost:" + port + "/api/v1/auth/login",
            new HttpEntity<>(Map.of("email", email, "password", "pass123"), h), AccessTokenResponse.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        return resp.getBody().accessToken();
    }

    private ResponseEntity<Map> post(String path, Object body, String token) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        h.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange("http://localhost:" + port + path, HttpMethod.POST, new HttpEntity<>(body, h), Map.class);
    }
}
