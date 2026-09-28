package com.traceability;

import com.traceability.identity.model.AccessTokenResponse;
import com.traceability.inventory.ExceptionService;
import com.traceability.inventory.UlidGenerator;
import com.traceability.returncases.ReturnCaseService;
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
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Returns & exchanges Step 1 — the case list and counts (ReturnCaseService) built on
 * ReturnCaseRules, the same rules the alert detectors use.
 *
 * Covers: the table-driven source × state → stage / next step / tone / overdue mapping (incl. a
 * resolved alert clearing the flag but not the stage, a lost leg, a redacted customer); de-dup
 * (a request's leg and exchange row appear once, as the request); tiles and the tile filter;
 * stage / type filters and each search field; keyset paging with equal timestamps; agreement
 * with every related detector; cross-tenant on a real app_user connection; roles over HTTP.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReturnCasesTest {

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
    @Autowired ReturnCaseService cases;
    @Autowired ExceptionService exceptions;

    record Tenant(UUID id, UUID store, UUID product, UUID whiteM, UUID whiteL, UUID location, UUID owner) {}
    record Order(UUID id, UUID item, String number) {}

    Tenant a, b;
    String ownerA, managerA, workerA;
    ReturnCaseService appUserCases;
    TransactionTemplate appUserTx;

    @BeforeAll
    void setup() {
        a = tenant("Nour Studio");
        b = tenant("Other Store");
        ownerA = login(a.owner());
        managerA = login(user(a.id(), "manager"));
        workerA = login(user(a.id(), "worker"));
        DataSource ds = new TenantAwareDataSource(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "app_user", "testpw"));
        appUserCases = new ReturnCaseService(new JdbcTemplate(ds));
        appUserTx = new TransactionTemplate(new DataSourceTransactionManager(ds));
    }

    @AfterEach
    void cleanup() {
        TenantContext.clear();
        for (Tenant t : List.of(a, b)) {
            jdbc.update("DELETE FROM exception_resolutions WHERE tenant_id = ?", t.id());
            jdbc.update("DELETE FROM return_request_events WHERE tenant_id = ?", t.id());
            jdbc.update("UPDATE exchanges SET return_request_id = NULL WHERE tenant_id = ?", t.id());
            jdbc.update("DELETE FROM exchanges WHERE tenant_id = ?", t.id());
            jdbc.update("DELETE FROM return_request_items WHERE tenant_id = ?", t.id());
            jdbc.update("UPDATE return_requests SET return_shipment_id = NULL WHERE tenant_id = ?", t.id());
            jdbc.update("DELETE FROM return_requests WHERE tenant_id = ?", t.id());
            jdbc.update("DELETE FROM shipment_status_history WHERE tenant_id = ?", t.id());
            jdbc.update("DELETE FROM shipments WHERE tenant_id = ?", t.id());
            jdbc.update("UPDATE pieces SET current_order_id = NULL WHERE tenant_id = ?", t.id());
            jdbc.update("DELETE FROM allocations WHERE tenant_id = ?", t.id());
            jdbc.update("DELETE FROM pieces WHERE tenant_id = ?", t.id());
            jdbc.update("DELETE FROM order_items WHERE tenant_id = ?", t.id());
            jdbc.update("DELETE FROM orders WHERE tenant_id = ?", t.id());
            jdbc.update("UPDATE tenants SET portal_pickup_booking = true, refund_pending_window_days = 5, " +
                        "return_arrival_window_days = 10, return_unscanned_window_days = 3 WHERE id = ?", t.id());
        }
    }

    // ── Mapping ──────────────────────────────────────────────────────────────────

    /** expected {caseLabel → [nextStep, stage, tone, overdue?]} */
    @Test
    void mapping_everySourceAndState() {
        Map<String, UUID> ids = new LinkedHashMap<>();
        Map<String, String[]> want = new LinkedHashMap<>();

        // A — portal requests
        ids.put("A requested", request(order("#A1"), "refund", "requested", null, "awaiting"));
        want.put("A requested", new String[]{"approve", "to_do", "action", null});
        stock(a.whiteL(), 1);
        ids.put("A exchange requested, in stock", request(order("#A2"), "exchange", "requested", null, "awaiting"));
        want.put("A exchange requested, in stock", new String[]{"approve", "to_do", "action", null});
        UUID soldOutVariant = variant(a, "White / S");
        UUID soldOut = request(order("#A3"), "exchange", "requested", null, "awaiting");
        jdbc.update("UPDATE return_request_items SET replacement_variant_id = ? WHERE request_id = ?", soldOutVariant, soldOut);
        ids.put("A exchange requested, sold out", soldOut);
        want.put("A exchange requested, sold out", new String[]{"choose_replacement", "to_do", "action", null});
        ids.put("A approved, booking pending", request(order("#A4"), "refund", "approved", "pending", "awaiting"));
        want.put("A approved, booking pending", new String[]{"booking", "in_progress", "moving", null});
        jdbc.update("UPDATE tenants SET portal_pickup_booking = false WHERE id = ?", a.id());
        // (booking off is read live — this request is evaluated in the same query below)
        UUID manual = request(order("#A5"), "refund", "approved", null, "awaiting");
        ids.put("A approved, booking off", manual);
        want.put("A approved, booking off", new String[]{"awaiting_items", "in_progress", "moving", null});
        ids.put("A booked", request(order("#A6"), "refund", "pickup_booked", "booked", "awaiting"));
        want.put("A booked", new String[]{"on_the_way", "in_progress", "moving", null});
        UUID overdueItems = request(order("#A7"), "refund", "pickup_booked", "booked", "awaiting");
        jdbc.update("UPDATE return_requests SET decided_at = now() - interval '12 days', booking_attempted_at = now() - interval '12 days' WHERE id = ?", overdueItems);
        ids.put("A items overdue", overdueItems);
        want.put("A items overdue", new String[]{"on_the_way", "in_progress", "problem", "12"});
        for (String bs : List.of("failed", "failed_ambiguous")) {
            ids.put("A booking " + bs, request(order("#A8" + bs), "refund", "approved", bs, "awaiting"));
            want.put("A booking " + bs, new String[]{"booking_problem", "to_do", "problem", null});
        }
        ids.put("A booking needs_review", request(order("#A9"), "refund", "pickup_booked", "needs_review", "awaiting"));
        want.put("A booking needs_review", new String[]{"booking_problem", "to_do", "problem", null});
        UUID resolvedBooking = request(order("#A10"), "refund", "approved", "failed", "awaiting");
        resolve("pickup_booking_problem", bookingKey(resolvedBooking));
        ids.put("A booking failed, resolved", resolvedBooking);
        want.put("A booking failed, resolved", new String[]{"booking_problem", "to_do", "action", null});
        ids.put("A partly arrived", request(order("#A11"), "refund", "pickup_booked", "booked", "arrived"));
        want.put("A partly arrived", new String[]{"inspect", "to_do", "action", null});
        ids.put("A received", request(order("#A12"), "refund", "received", "booked", "arrived"));
        want.put("A received", new String[]{"inspect", "to_do", "action", null});
        ids.put("A refund pending", request(order("#A13"), "refund", "refund_pending", "booked", "done"));
        want.put("A refund pending", new String[]{"record_refund", "to_do", "action", null});
        UUID overdueRefund = request(order("#A14"), "refund", "refund_pending", "booked", "done");
        jdbc.update("UPDATE return_requests SET refund_pending_at = now() - interval '7 days' WHERE id = ?", overdueRefund);
        ids.put("A refund overdue", overdueRefund);
        want.put("A refund overdue", new String[]{"record_refund", "to_do", "problem", "7"});
        UUID resolvedRefund = request(order("#A15"), "refund", "refund_pending", "booked", "done");
        jdbc.update("UPDATE return_requests SET refund_pending_at = now() - interval '7 days' WHERE id = ?", resolvedRefund);
        resolve("refund_pending_overdue", "refund_pending_overdue:" + resolvedRefund);
        ids.put("A refund overdue, resolved", resolvedRefund);
        want.put("A refund overdue, resolved", new String[]{"record_refund", "to_do", "action", null});
        UUID toReceive = request(order("#A16"), "exchange", "exchanged", "booked", "done");
        jdbc.update("UPDATE return_request_items SET arrived_at = now(), arrived_condition = 'sellable' WHERE request_id = ?", toReceive);
        ids.put("A exchanged, item to add in Receiving", toReceive);
        want.put("A exchanged, item to add in Receiving", new String[]{"add_in_receiving", "to_do", "action", null});
        ids.put("A refunded", request(order("#A17"), "refund", "refunded", "booked", "done"));
        want.put("A refunded", new String[]{"refunded", "done", "done_good", null});
        ids.put("A exchanged", request(order("#A18"), "exchange", "exchanged", "booked", "done"));
        want.put("A exchanged", new String[]{"exchanged", "done", "done_good", null});
        ids.put("A rejected", request(order("#A19"), "refund", "rejected", null, "not_coming"));
        want.put("A rejected", new String[]{"rejected", "done", "done_closed", null});
        ids.put("A closed", request(order("#A20"), "refund", "closed", null, "not_coming"));
        want.put("A closed", new String[]{"closed", "done", "done_closed", null});

        // B — dashboard exchanges
        Map<String, String[]> bWant = Map.of(
            "needs_mapping", new String[]{"choose_replacement", "to_do", "action", null},
            "unmatched", new String[]{"link_order", "to_do", "action", null},
            "needs_confirmation", new String[]{"link_order", "to_do", "action", null},
            "mapped", new String[]{"on_the_way", "in_progress", "moving", null},
            "matched", new String[]{"on_the_way", "in_progress", "moving", null},
            "return_received", new String[]{"exchanged", "done", "done_good", null},
            "bare_return", new String[]{"returned_no_exchange", "done", "done_closed", null},
            "dismissed", new String[]{"dismissed", "done", "done_closed", null},
            "cancelled", new String[]{"cancelled", "done", "done_closed", null});
        bWant.forEach((status, w) -> {
            ids.put("B " + status, exchange(tracking(), status, null, null));
            want.put("B " + status, w);
        });

        // C — courier returns no request holds
        ids.put("C with courier", leg(order("#C1"), "with_courier", null, null, 0));
        want.put("C with courier", new String[]{"on_the_way", "in_progress", "moving", null});
        ids.put("C returned, not scanned", leg(order("#C2"), "returned", null, null, 1));
        want.put("C returned, not scanned", new String[]{"scan", "to_do", "action", null});
        ids.put("C returned, unscanned too long", leg(order("#C3"), "returned", null, null, 5));
        want.put("C returned, unscanned too long", new String[]{"scan", "to_do", "problem", "5"});
        UUID resolvedUnscanned = leg(order("#C4"), "returned", null, null, 5);
        resolve("return_leg_unscanned", "return_leg_unscanned:shipment:" + resolvedUnscanned);
        ids.put("C unscanned, resolved", resolvedUnscanned);
        want.put("C unscanned, resolved", new String[]{"scan", "to_do", "action", null});
        ids.put("C scanned, resolved", leg(order("#C5"), "returned", "now()", null, 1));
        want.put("C scanned, resolved", new String[]{"received", "done", "done_good", null});
        Order pending = order("#C6");
        piece(pending, "return_pending_inspection");
        ids.put("C scanned, piece to inspect", leg(pending, "returned", "now()", null, 1));
        want.put("C scanned, piece to inspect", new String[]{"inspect", "to_do", "action", null});
        ids.put("C received untracked, to add", leg(order("#C7"), "returned", "now()", "received_untracked", 1));
        want.put("C received untracked, to add", new String[]{"add_in_receiving", "to_do", "action", null});
        UUID untrackedDone = leg(order("#C8"), "returned", "now() - interval '1 hour'", "received_untracked", 1);
        resolve("return_to_receive", untrackedDone.toString());
        ids.put("C received untracked, done", untrackedDone);
        want.put("C received untracked, done", new String[]{"received", "done", "done_good", null});
        ids.put("C lost", leg(order("#C9"), "lost", null, null, 0));
        want.put("C lost", new String[]{"lost", "done", "problem", null});
        ids.put("C terminated", leg(order("#C10"), "terminated", null, null, 0));
        want.put("C terminated", new String[]{"cancelled", "done", "done_closed", null});
        Order ambiguousOrder = order("#C11");
        request(ambiguousOrder, "refund", "approved", null, "awaiting", "now() - interval '3 days'");
        request(ambiguousOrder, "refund", "approved", null, "awaiting", "now() - interval '3 days'");
        UUID ambiguous = leg(ambiguousOrder, "with_courier", null, null, 0);
        jdbc.update("UPDATE shipments SET raw = jsonb_build_object('createdAt', to_char(now() AT TIME ZONE 'UTC', " +
                    "'YYYY-MM-DD\"T\"HH24:MI:SS\"Z\"')) WHERE id = ?", ambiguous);
        ids.put("C ambiguous", ambiguous);
        want.put("C ambiguous", new String[]{"link_request", "to_do", "action", null});

        Map<String, Map<String, Object>> byId = allCases(a).stream()
            .collect(Collectors.toMap(c -> (String) c.get("id"), c -> c, (x, y) -> x));
        want.forEach((label, w) -> {
            Map<String, Object> c = byId.get(ids.get(label).toString());
            assertThat(c).as(label + " is a case").isNotNull();
            assertThat(c.get("nextStep")).as(label + " next step").isEqualTo(w[0]);
            assertThat(c.get("stage")).as(label + " stage").isEqualTo(w[1]);
            assertThat(c.get("tone")).as(label + " tone").isEqualTo(w[2]);
            if (w[3] == null) assertThat(c.get("overdueDays")).as(label + " overdue").isNull();
            else assertThat(((Number) c.get("overdueDays")).intValue()).as(label + " overdue").isGreaterThanOrEqualTo(Integer.parseInt(w[3]));
        });
    }

    @Test
    @SuppressWarnings("unchecked")
    void rowContent_sourceOrderCustomerItems_andRedactedCustomer() {
        Order o = order("#R1");
        UUID refund = request(o, "refund", "requested", null, "awaiting");
        jdbc.update("INSERT INTO return_request_items (tenant_id, request_id, order_item_id, unit_no, variant_id, reason_code) " +
                    "VALUES (?, ?, ?, 2, ?, 'wrong_size')", a.id(), refund, o.item(), a.whiteM());
        stock(a.whiteL(), 1);
        UUID exch = request(order("#R2"), "exchange", "requested", null, "awaiting");
        UUID bRow = exchange(tracking(), "unmatched", null, null);
        UUID mapped = exchange(tracking(), "matched", null, order("#R3").id());
        jdbc.update("UPDATE exchanges SET inbound_variant_id = ?, outbound_variant_id = ? WHERE id = ?", a.whiteM(), a.whiteL(), mapped);
        UUID legId = leg(order("#R4"), "returned", null, null, 1);
        Order redacted = order("#R5");
        jdbc.update("UPDATE orders SET pii_redacted_at = now(), customer_name = NULL WHERE id = ?", redacted.id());
        UUID red = request(redacted, "refund", "requested", null, "awaiting");

        Map<String, Map<String, Object>> byId = allCases(a).stream().collect(Collectors.toMap(c -> (String) c.get("id"), c -> c));
        Map<String, Object> r = byId.get(refund.toString());
        assertThat(r).containsEntry("caseType", "A").containsEntry("source", "returns_page").containsEntry("kind", "refund")
            .containsEntry("orderNumber", "#R1").containsEntry("customerName", "Mona Customer")
            .containsEntry("itemsSummary", "Linen Shirt White / M × 2");
        assertThat((String) r.get("reference")).startsWith("RR-");
        assertThat((Map<String, Object>) r.get("target")).containsEntry("requestId", refund.toString());
        assertThat(byId.get(exch.toString())).containsEntry("kind", "exchange")
            .containsEntry("itemsSummary", "Linen Shirt White / M → White / L");
        Map<String, Object> b1 = byId.get(bRow.toString());
        assertThat(b1).containsEntry("caseType", "B").containsEntry("source", "bosta").containsEntry("orderNumber", null)
            .containsEntry("customerName", "Laila Receiver").containsEntry("itemsSummary", "Shirt M → Shirt L")
            .containsEntry("itemsSummaryAr", "قميص M ← Shirt L");
        assertThat(byId.get(mapped.toString())).as("labelling guard: matched_order_id").containsEntry("orderNumber", "#R3")
            .containsEntry("itemsSummary", "Linen Shirt White / M → White / L");
        Map<String, Object> c = byId.get(legId.toString());
        assertThat(c).containsEntry("caseType", "C").containsEntry("source", "bosta").containsEntry("orderNumber", "#R4")
            .containsEntry("itemsSummary", "Linen Shirt White / M · not scanned yet")
            .containsEntry("itemsSummaryAr", "Linen Shirt White / M · لم تُمسح بعد").containsEntry("notScanned", true);
        assertThat(byId.get(red.toString())).as("redacted → null name, order still found")
            .containsEntry("customerName", null).containsEntry("orderNumber", "#R5");
    }

    // ── De-dup ───────────────────────────────────────────────────────────────────

    @Test
    void dedup_requestWithLegAndExchangeRow_appearsOnce() {
        Order o = order("#D1");
        UUID req = request(o, "exchange", "pickup_booked", "booked", "awaiting");
        String tn = tracking();
        UUID legId = leg(o, "with_courier", null, null, 0, tn);
        jdbc.update("UPDATE return_requests SET return_shipment_id = ?, bosta_tracking_number = ? WHERE id = ?", legId, tn, req);
        UUID exRow = exchange(tracking(), "matched", req, o.id());
        // A leg held only by the tracking number the request booked.
        Order o2 = order("#D2");
        UUID req2 = request(o2, "refund", "pickup_booked", "booked", "awaiting");
        String tn2 = tracking();
        jdbc.update("UPDATE return_requests SET bosta_tracking_number = ? WHERE id = ?", tn2, req2);
        UUID leg2 = leg(o2, "with_courier", null, null, 0, tn2);

        List<Map<String, Object>> all = allCases(a);
        Set<String> seen = all.stream().map(c -> (String) c.get("id")).collect(Collectors.toSet());
        assertThat(all).hasSize(2);
        assertThat(seen).containsExactlyInAnyOrder(req.toString(), req2.toString());
        assertThat(seen).doesNotContain(legId.toString(), exRow.toString(), leg2.toString());
        // The absorbed tracking numbers still find the request.
        assertThat(ids(list(new ReturnCaseService.Filter(null, null, null, tn)))).containsExactly(req.toString());
        assertThat(ids(list(new ReturnCaseService.Filter(null, null, null, tn2)))).containsExactly(req2.toString());
    }

    // ── Tiles ────────────────────────────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void tiles_countsMatchDefinitions_andTileFilterReturnsExactlyThose() {
        UUID approve1 = request(order("#T1"), "refund", "requested", null, "awaiting");
        stock(a.whiteL(), 1);
        UUID approve2 = request(order("#T2"), "exchange", "requested", null, "awaiting");
        UUID soldOut = request(order("#T3"), "exchange", "requested", null, "awaiting");
        jdbc.update("UPDATE return_request_items SET replacement_variant_id = ? WHERE request_id = ?", variant(a, "Black / XL"), soldOut);
        UUID mapping = exchange(tracking(), "needs_mapping", null, null);
        UUID link1 = exchange(tracking(), "unmatched", null, null);
        UUID link2 = exchange(tracking(), "needs_confirmation", null, null);
        UUID refund = request(order("#T4"), "refund", "refund_pending", "booked", "done");
        UUID booking = request(order("#T5"), "refund", "approved", "failed", "awaiting");
        request(order("#T6"), "refund", "refunded", "booked", "done");
        leg(order("#T7"), "with_courier", null, null, 0);

        Map<String, Object> counts = asA(() -> cases.counts(null, null));
        assertThat((Map<String, Object>) counts.get("tiles")).containsExactlyInAnyOrderEntriesOf(Map.of(
            "toApprove", 2, "replacementToChoose", 2, "toLinkOrder", 2, "refundToRecord", 1, "bookingProblem", 1));
        assertThat((Map<String, Object>) counts.get("stages")).containsExactlyInAnyOrderEntriesOf(Map.of(
            "all", 10, "to_do", 8, "in_progress", 1, "done", 1));

        assertThat(ids(list(new ReturnCaseService.Filter(null, null, "toApprove", null))))
            .containsExactlyInAnyOrder(approve1.toString(), approve2.toString());
        assertThat(ids(list(new ReturnCaseService.Filter(null, null, "replacementToChoose", null))))
            .containsExactlyInAnyOrder(soldOut.toString(), mapping.toString());
        assertThat(ids(list(new ReturnCaseService.Filter(null, null, "toLinkOrder", null))))
            .containsExactlyInAnyOrder(link1.toString(), link2.toString());
        assertThat(ids(list(new ReturnCaseService.Filter(null, null, "refundToRecord", null)))).containsExactly(refund.toString());
        assertThat(ids(list(new ReturnCaseService.Filter(null, null, "bookingProblem", null)))).containsExactly(booking.toString());
    }

    // ── Filters, search, paging ──────────────────────────────────────────────────

    @Test
    void filters_stageTypeAndEverySearchField() {
        Order o = order("#1047");
        jdbc.update("UPDATE orders SET customer_name = 'Mariam Saleh' WHERE id = ?", o.id());
        UUID req = request(o, "refund", "requested", null, "awaiting");
        String ref = jdbc.queryForObject("SELECT reference FROM return_requests WHERE id = ?", String.class, req);
        String tn = tracking();
        UUID ex = exchange(tn, "mapped", null, null);
        UUID done = request(order("#2001"), "refund", "refunded", "booked", "done");
        String legTn = tracking();
        UUID legId = leg(order("#3001"), "with_courier", null, null, 0, legTn);

        assertThat(ids(list(new ReturnCaseService.Filter("to_do", null, null, null)))).containsExactly(req.toString());
        assertThat(ids(list(new ReturnCaseService.Filter("in_progress", null, null, null))))
            .containsExactlyInAnyOrder(ex.toString(), legId.toString());
        assertThat(ids(list(new ReturnCaseService.Filter("done", null, null, null)))).containsExactly(done.toString());
        assertThat(ids(list(new ReturnCaseService.Filter("all", "exchange", null, null)))).containsExactly(ex.toString());
        assertThat(ids(list(new ReturnCaseService.Filter(null, "refund", null, null))))
            .containsExactlyInAnyOrder(req.toString(), done.toString(), legId.toString());

        assertThat(ids(list(new ReturnCaseService.Filter(null, null, null, ref.toLowerCase())))).as("RR reference, exact").containsExactly(req.toString());
        assertThat(ids(list(new ReturnCaseService.Filter(null, null, null, ref.substring(0, 5))))).as("reference is exact, not contains").isEmpty();
        assertThat(ids(list(new ReturnCaseService.Filter(null, null, null, " " + tn + " ")))).as("tracking, exact").containsExactly(ex.toString());
        assertThat(ids(list(new ReturnCaseService.Filter(null, null, null, legTn)))).containsExactly(legId.toString());
        assertThat(ids(list(new ReturnCaseService.Filter(null, null, null, "104")))).as("order number contains").containsExactly(req.toString());
        assertThat(ids(list(new ReturnCaseService.Filter(null, null, null, "mariam")))).as("customer, case-insensitive").containsExactly(req.toString());
        assertThat(ids(list(new ReturnCaseService.Filter(null, null, null, "%")))).as("wildcards are literal").isEmpty();
    }

    @Test
    void paging_keyset_noDuplicatesNothingMissing_stableUnderEqualTimestamps() {
        Set<String> expected = new HashSet<>();
        for (int i = 0; i < 7; i++) expected.add(request(order("#P" + i), "refund", "requested", null, "awaiting").toString());
        for (int i = 0; i < 6; i++) expected.add(exchange(tracking(), "mapped", null, null).toString());
        for (int i = 0; i < 5; i++) expected.add(request(order("#Q" + i), "refund", "refunded", "booked", "done").toString());
        // Equal timestamps everywhere: only the case key orders them.
        jdbc.update("UPDATE return_requests SET created_at = '2026-09-20T10:00:00Z' WHERE tenant_id = ?", a.id());
        jdbc.update("UPDATE exchanges SET updated_at = '2026-09-20T10:00:00Z' WHERE tenant_id = ?", a.id());

        List<String> got = new ArrayList<>();
        List<String> stages = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        do {
            String c = cursor;
            @SuppressWarnings("unchecked")
            Map<String, Object> page = asA(() -> cases.list(new ReturnCaseService.Filter("all", null, null, null), c, 4));
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> items = (List<Map<String, Object>>) page.get("items");
            items.forEach(i -> { got.add((String) i.get("id")); stages.add((String) i.get("stage")); });
            cursor = (String) page.get("nextCursor");
            pages++;
        } while (cursor != null && pages < 20);

        assertThat(got).hasSize(18).doesNotHaveDuplicates();
        assertThat(new HashSet<>(got)).isEqualTo(expected);
        assertThat(pages).isEqualTo(5);
        assertThat(stages).as("grouped: to_do, then in_progress, then done")
            .isEqualTo(stages.stream().sorted(Comparator.comparingInt(s -> List.of("to_do", "in_progress", "done").indexOf(s))).toList());
        // The same walk again gives the same order.
        List<String> again = new ArrayList<>();
        String c2 = null;
        do {
            String cc = c2;
            @SuppressWarnings("unchecked")
            Map<String, Object> page = asA(() -> cases.list(new ReturnCaseService.Filter("all", null, null, null), cc, 4));
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> items = (List<Map<String, Object>>) page.get("items");
            items.forEach(i -> again.add((String) i.get("id")));
            c2 = (String) page.get("nextCursor");
        } while (c2 != null);
        assertThat(again).isEqualTo(got);
    }

    // ── Agreement with the alerts ────────────────────────────────────────────────

    @Test
    void agreement_pageFlagsEqualDetectorOutput_includingResolvedAlerts() {
        // One open and one resolved subject per alert, plus a non-flagged control.
        UUID bp = request(order("#G1"), "refund", "approved", "failed", "awaiting");
        UUID bpResolved = request(order("#G2"), "refund", "approved", "failed_ambiguous", "awaiting");
        resolve("pickup_booking_problem", bookingKey(bpResolved));
        UUID ro = request(order("#G3"), "refund", "refund_pending", "booked", "done");
        UUID roResolved = request(order("#G4"), "refund", "refund_pending", "booked", "done");
        jdbc.update("UPDATE return_requests SET refund_pending_at = now() - interval '9 days' WHERE id IN (?, ?)", ro, roResolved);
        resolve("refund_pending_overdue", "refund_pending_overdue:" + roResolved);
        UUID io = request(order("#G5"), "refund", "approved", null, "awaiting");
        UUID ioResolved = request(order("#G6"), "refund", "approved", null, "awaiting");
        jdbc.update("UPDATE return_requests SET decided_at = now() - interval '20 days' WHERE id IN (?, ?)", io, ioResolved);
        resolve("return_items_overdue", "return_items_overdue:" + ioResolved);
        UUID nm = exchange(tracking(), "needs_mapping", null, null);
        UUID un = leg(order("#G7"), "returned", null, null, 6);
        UUID unResolved = leg(order("#G8"), "returned", null, null, 6);
        resolve("return_leg_unscanned", "return_leg_unscanned:shipment:" + unResolved);
        UUID rtr = leg(order("#G9"), "returned", "now()", "received_untracked", 1);
        UUID rtrResolved = leg(order("#G10"), "returned", "now() - interval '1 hour'", "received_untracked", 1);
        resolve("return_to_receive", rtrResolved.toString());
        UUID itr = request(order("#G11"), "refund", "refund_pending", "booked", "done");
        jdbc.update("UPDATE return_request_items SET arrived_at = now(), arrived_condition = 'sellable' WHERE request_id = ?", itr);
        Order amb = order("#G12");
        request(amb, "refund", "approved", null, "awaiting", "now() - interval '3 days'");
        request(amb, "refund", "approved", null, "awaiting", "now() - interval '3 days'");
        UUID la = leg(amb, "with_courier", null, null, 0);
        jdbc.update("UPDATE shipments SET raw = jsonb_build_object('createdAt', to_char(now() AT TIME ZONE 'UTC', " +
                    "'YYYY-MM-DD\"T\"HH24:MI:SS\"Z\"')) WHERE id = ?", la);

        List<Map<String, Object>> all = allCases(a);
        Map<String, List<String>> subjectField = Map.of(
            "pickup_booking_problem", List.of("request_id"),
            "refund_pending_overdue", List.of("request_id"),
            "return_items_overdue", List.of("request_id"),
            "request_item_to_receive", List.of("request_id"),
            "exchange_needs_mapping", List.of("exchange_id"),
            "return_link_ambiguous", List.of("shipment_id"),
            "return_leg_unscanned", List.of("shipment_id"),
            "return_to_receive", List.of("shipment_id"));
        Map<String, Set<String>> expectedOpen = Map.of(
            "pickup_booking_problem", Set.of(bp.toString()),
            "refund_pending_overdue", Set.of(ro.toString()),
            "return_items_overdue", Set.of(io.toString()),
            "request_item_to_receive", Set.of(itr.toString()),
            "exchange_needs_mapping", Set.of(nm.toString()),
            "return_link_ambiguous", Set.of(la.toString()),
            "return_leg_unscanned", Set.of(un.toString()),
            "return_to_receive", Set.of(rtr.toString()));
        subjectField.forEach((type, field) -> {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> items = (List<Map<String, Object>>) asA(() -> exceptions.listExceptions(type, null, 0, 200)).get("items");
            Set<String> detector = items.stream().map(i -> String.valueOf(i.get(field.get(0)))).collect(Collectors.toSet());
            Set<String> page = all.stream().filter(c -> ((List<?>) c.get("alerts")).contains(type))
                .map(c -> (String) c.get("id")).collect(Collectors.toSet());
            assertThat(page).as("page flags " + type).isEqualTo(detector);
            assertThat(detector).as("detector " + type).isEqualTo(expectedOpen.get(type));
        });
        // Resolved alerts keep their stage.
        Map<String, Map<String, Object>> byId = all.stream().collect(Collectors.toMap(c -> (String) c.get("id"), c -> c));
        assertThat(byId.get(bpResolved.toString())).containsEntry("stage", "to_do").containsEntry("nextStep", "booking_problem");
        assertThat(byId.get(roResolved.toString())).containsEntry("stage", "to_do").containsEntry("tone", "action");
        assertThat(byId.get(ioResolved.toString())).containsEntry("stage", "in_progress").containsEntry("tone", "moving");
        assertThat(byId.get(unResolved.toString())).containsEntry("stage", "to_do").containsEntry("nextStep", "scan");
    }

    // ── Tenancy and roles ────────────────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void crossTenant_onAppUser_withSameTenantPositiveControl() {
        UUID mine = request(order("#X1"), "refund", "requested", null, "awaiting");
        Order theirs = order(b, "#X1");
        UUID theirReq = requestFor(b, theirs, "refund", "requested", null, "awaiting", "now()");
        String theirRef = jdbc.queryForObject("SELECT reference FROM return_requests WHERE id = ?", String.class, theirReq);

        Map<String, Object> page = TenantContext.runAs(a.id(), () -> appUserTx.execute(s ->
            appUserCases.list(new ReturnCaseService.Filter(null, null, null, null), null, 50)));
        List<String> got = ((List<Map<String, Object>>) page.get("items")).stream().map(i -> (String) i.get("id")).toList();
        assertThat(got).as("positive control").containsExactly(mine.toString());
        Map<String, Object> search = TenantContext.runAs(a.id(), () -> appUserTx.execute(s ->
            appUserCases.list(new ReturnCaseService.Filter(null, null, null, theirRef), null, 50)));
        assertThat((List<?>) search.get("items")).as("B's reference finds nothing for A").isEmpty();
        Map<String, Object> counts = TenantContext.runAs(a.id(), () -> appUserTx.execute(s -> appUserCases.counts(null, null)));
        assertThat((Map<String, Object>) counts.get("stages")).containsEntry("all", 1);
    }

    @Test
    @SuppressWarnings("unchecked")
    void http_ownerAndManager_workerForbidden_badParams400() {
        UUID mine = request(order("#H1"), "refund", "requested", null, "awaiting");
        ResponseEntity<Map> list = get("/api/v1/returns-exchanges?stage=to_do&limit=10", managerA);
        assertThat(list.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((List<Map<String, Object>>) list.getBody().get("items"))).extracting(i -> i.get("id")).containsExactly(mine.toString());
        assertThat(list.getBody()).containsKey("nextCursor");
        ResponseEntity<Map> counts = get("/api/v1/returns-exchanges/counts", ownerA);
        assertThat((Map<String, Object>) counts.getBody().get("tiles")).containsEntry("toApprove", 1);
        assertThat(get("/api/v1/returns-exchanges", workerA).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(get("/api/v1/returns-exchanges/counts", workerA).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(get("/api/v1/returns-exchanges?stage=soon", ownerA).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(get("/api/v1/returns-exchanges?tile=nope", ownerA).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(get("/api/v1/returns-exchanges?cursor=zzz", ownerA).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> allCases(Tenant t) {
        List<Map<String, Object>> out = new ArrayList<>();
        String cursor = null;
        do {
            String c = cursor;
            Map<String, Object> page = TenantContext.runAs(t.id(), () -> cases.list(new ReturnCaseService.Filter(null, null, null, null), c, 50));
            out.addAll((List<Map<String, Object>>) page.get("items"));
            cursor = (String) page.get("nextCursor");
        } while (cursor != null);
        return out;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> list(ReturnCaseService.Filter f) {
        return (List<Map<String, Object>>) asA(() -> cases.list(f, null, 50)).get("items");
    }

    private static List<String> ids(List<Map<String, Object>> items) {
        return items.stream().map(i -> (String) i.get("id")).toList();
    }

    private <T> T asA(Supplier<T> body) {
        return TenantContext.runAs(a.id(), body::get);
    }

    private Tenant tenant(String name) {
        UUID id = UUID.randomUUID(), store = UUID.randomUUID(), owner = UUID.randomUUID(), location = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name, portal_pickup_booking) VALUES (?, ?, true)", id, name);
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status) VALUES (?, ?, 'shopify', ?, 'disconnected')",
            store, id, "s-" + id + ".myshopify.com");
        jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role, active) VALUES (?, ?, 'Owner', ?, ?, 'owner', true)",
            owner, id, "owner-" + owner + "@cases.test", passwordEncoder.encode("pass123"));
        jdbc.update("INSERT INTO locations (id, tenant_id, name, is_fulfillment) VALUES (?, ?, 'Main', true)", location, id);
        UUID product = UUID.randomUUID();
        jdbc.update("INSERT INTO products (id, tenant_id, store_id, external_id, title, status) VALUES (?, ?, ?, ?, 'Linen Shirt', 'active')",
            product, id, store, "P-" + product);
        Tenant t = new Tenant(id, store, product, null, null, location, owner);
        return new Tenant(id, store, product, variant(t, "White / M"), variant(t, "White / L"), location, owner);
    }

    private UUID variant(Tenant t, String title) {
        UUID v = UUID.randomUUID();
        jdbc.update("INSERT INTO variants (id, tenant_id, product_id, external_id, title, sku) VALUES (?, ?, ?, ?, ?, ?)",
            v, t.id(), t.product(), "V-" + v, title, "SKU-" + v.toString().substring(0, 6));
        return v;
    }

    private UUID user(UUID tenant, String role) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role, active) VALUES (?, ?, ?, ?, ?, ?::user_role, true)",
            id, tenant, role, role + "-" + id + "@cases.test", passwordEncoder.encode("pass123"), role);
        return id;
    }

    private void stock(UUID variant, int count) {
        for (int i = 0; i < count; i++) {
            String id = UlidGenerator.generate();
            jdbc.update("INSERT INTO pieces (id, tenant_id, variant_id, barcode, short_code, status, current_location_id) " +
                "VALUES (?, ?, ?, ?, 'P' || LPAD((abs(hashtext(?)) % 999999 + 1)::text, 6, '0'), 'available'::piece_status, ?)",
                id, a.id(), variant, "PC-" + id, id, a.location());
        }
    }

    private void piece(Order o, String status) {
        String id = UlidGenerator.generate();
        jdbc.update("INSERT INTO pieces (id, tenant_id, variant_id, barcode, short_code, status, current_order_id) " +
            "VALUES (?, ?, ?, ?, 'P' || LPAD((abs(hashtext(?)) % 999999 + 1)::text, 6, '0'), ?::piece_status, ?)",
            id, a.id(), a.whiteM(), "PC-" + id, id, status, o.id());
    }

    private Order order(String number) {
        return order(a, number);
    }

    private Order order(Tenant t, String number) {
        UUID id = UUID.randomUUID(), item = UUID.randomUUID();
        jdbc.update("INSERT INTO orders (id, tenant_id, store_id, external_id, number, status, payment_method, placed_at, customer_name) " +
            "VALUES (?, ?, ?, ?, ?, 'delivered'::order_status, 'cod'::order_payment_method, now(), 'Mona Customer')",
            id, t.id(), t.store(), "gid://shopify/Order/" + UUID.randomUUID(), number);
        jdbc.update("INSERT INTO order_items (id, tenant_id, order_id, variant_id, quantity) VALUES (?, ?, ?, ?, 1)",
            item, t.id(), id, t.whiteM());
        return new Order(id, item, number);
    }

    private UUID request(Order o, String type, String status, String booking, String itemStatus) {
        return request(o, type, status, booking, itemStatus, "now()");
    }

    private UUID request(Order o, String type, String status, String booking, String itemStatus, String createdAtSql) {
        return requestFor(a, o, type, status, booking, itemStatus, createdAtSql);
    }

    /** One request with one untracked unit item of whiteM (exchange: replacement whiteL). */
    private UUID requestFor(Tenant t, Order o, String type, String status, String booking, String itemStatus, String createdAtSql) {
        UUID id = jdbc.queryForObject(
            "INSERT INTO return_requests (tenant_id, order_id, type, status, reference, booking_status, decided_at, " +
            "    booking_attempted_at, refund_pending_at, created_at) " +
            "VALUES (?, ?, ?, ?::return_request_status, ?, ?, CASE WHEN ?::text <> 'requested' THEN now() END, " +
            "    CASE WHEN ?::text IS NOT NULL THEN now() END, CASE WHEN ?::text = 'refund_pending' THEN now() END, " + createdAtSql + ") RETURNING id",
            UUID.class, t.id(), o.id(), type, status, reference(), booking, status, booking, status);
        boolean live = List.of("awaiting", "arrived").contains(itemStatus);
        jdbc.update("INSERT INTO return_request_items (tenant_id, request_id, order_item_id, unit_no, variant_id, reason_code, " +
                    "    item_status, active, arrived_at, replacement_variant_id) VALUES (?, ?, ?, " +
                    "    (SELECT COALESCE(MAX(unit_no), 0) + 1 FROM return_request_items WHERE order_item_id = ?), ?, 'wrong_size', ?, ?, ?, ?)",
            t.id(), id, o.item(), o.item(), t.whiteM(), itemStatus, live,
            "arrived".equals(itemStatus) ? new java.sql.Timestamp(System.currentTimeMillis()) : null,
            "exchange".equals(type) ? t.whiteL() : null);
        return id;
    }

    private UUID exchange(String tn, String status, UUID requestId, UUID matchedOrder) {
        String raw = "{\"receiver\":{\"fullName\":\"Laila Receiver\",\"phone\":\"01000000009\"}}";
        return jdbc.queryForObject(
            "INSERT INTO exchanges (tenant_id, tracking_number, status, inbound_description, inbound_description_ar, " +
            "    outbound_description, raw, return_request_id, matched_order_id) " +
            "VALUES (?, ?, ?, 'Shirt M', 'قميص M', 'Shirt L', ?::jsonb, ?, ?) RETURNING id",
            UUID.class, a.id(), tn, status, raw, requestId, matchedOrder);
    }

    private UUID leg(Order o, String state, String intakeAtSql, String outcome, int returnedDaysAgo) {
        return leg(o, state, intakeAtSql, outcome, returnedDaysAgo, tracking());
    }

    private UUID leg(Order o, String state, String intakeAtSql, String outcome, int returnedDaysAgo, String tn) {
        return jdbc.queryForObject(
            "INSERT INTO shipments (tenant_id, order_id, provider, tracking_number, internal_state, shipment_leg, returned_at, " +
            "    return_intake_completed_at, return_intake_outcome) " +
            "VALUES (?, ?, 'bosta', ?, ?::shipment_internal_state, 'return', " +
            "    CASE WHEN ? = 'returned' THEN now() - (interval '1 day' * ?) END, " + (intakeAtSql == null ? "NULL" : intakeAtSql) + ", ?) RETURNING id",
            UUID.class, a.id(), o.id(), tn, state, state, returnedDaysAgo, outcome);
    }

    private void resolve(String type, String key) {
        jdbc.update("INSERT INTO exception_resolutions (tenant_id, exception_type, subject_key, resolved_by, resolved_at, note) " +
                    "VALUES (?, ?, ?, ?, now(), 'handled')", a.id(), type, key, a.owner());
    }

    /** pickup_booking_problem's resolution key for this request (its attempt time). */
    private String bookingKey(UUID requestId) {
        return jdbc.queryForObject("SELECT 'pickup_booking_problem:' || id || ':' || " +
            "COALESCE(floor(extract(epoch FROM booking_attempted_at))::bigint::text, '0') FROM return_requests WHERE id = ?",
            String.class, requestId);
    }

    private static String tracking() {
        return String.valueOf(ThreadLocalRandom.current().nextLong(1_000_000_000L, 9_999_999_999L));
    }

    private static String reference() {
        String alphabet = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ";
        StringBuilder sb = new StringBuilder("RR-");
        for (int i = 0; i < 6; i++) sb.append(alphabet.charAt(ThreadLocalRandom.current().nextInt(alphabet.length())));
        return sb.toString();
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

    private ResponseEntity<Map> get(String path, String token) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        return rest.exchange(base() + path, HttpMethod.GET, new HttpEntity<>(h), Map.class);
    }
}
