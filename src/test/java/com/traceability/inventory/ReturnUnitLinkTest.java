package com.traceability.inventory;

import com.traceability.TestPostgres;
import com.traceability.integrations.shopify.ShopifyGateway;
import com.traceability.tenancy.TenantAwareDataSource;
import com.traceability.tenancy.TenantContext;
import org.jobrunr.scheduling.JobScheduler;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V246 (approved 2026-11-27, BRK-47010-EG) — Scan returns: link an unexpected tracked piece to the untracked unit it
 * physically is, and the close rule for illegal-state rows (Q2), through the real ReturnSessionService paths.
 *
 *   l1 sellable unit already marked Arrived + same-variant available piece scanned → the item carries linkCandidates;
 *      link → item 'linked', one link row, event return_unit_linked (status unchanged), the unit's Return To Receive
 *      closes, Close is allowed, the summary / list / analytics count it as linked
 *   l2 unit not marked yet → link sellable creates the intake (created_intake) and stamps the leg; undo → link and
 *      intake undone, item pending again, event return_unit_unlinked
 *   l3 damaged → the piece goes available → damaged through PieceAdjustService; undo refused (damaged_link)
 *   l4 double tap on the same unit → the same link (one row, one event); another unit → 409 item_already_linked
 *   g1 guards: other variant (no candidate, unit_not_linkable), lost (piece_lost), on another order
 *      (piece_on_another_order, names it), not at main (piece_not_at_main), unit already linked to another piece,
 *      unit taken by a Receiving line, a marked condition that differs, pending item of a closed session
 *   g2 a linked unit's Arrived mark can't be undone (session undo 409; drawer arrivals show it 'received')
 *   c1 close with an illegal pending row → closes; the row is 'needs_review' with one return_needs_review alert
 *      (names the piece + session, links to /returns?session=…&piece=…); a legal pending row still blocks
 *   c2 needs_review settled by a manager: link (closed session) clears the alert; "Not the real piece" clears it;
 *      "nothing to do" (resolve) clears it; a worker gets 403 NEEDS_MANAGER on link
 *   a1 ReturnSessionAutoCloseJob closes an idle session whose only pending row is illegal → needs_review
 *   b1 case B: the order's Shopify fulfilment left from Main → shopifyMayBeLow on the link; another location → false
 *   x1 app_user: links are tenant-isolated (another tenant's rows invisible, its session a 404), own-tenant link works;
 *      grants — UPDATE of the undo columns only, no DELETE
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReturnUnitLinkTest {

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

    @Autowired JdbcTemplate jdbc;
    @Autowired ReturnSessionService sessions;
    @Autowired ReturnService returnService;
    @Autowired ShipmentLinkService shipmentLinkService;
    @Autowired InventoryLedger ledger;
    @Autowired ExceptionService exceptions;
    @Autowired org.springframework.transaction.PlatformTransactionManager txm;
    @MockBean ShopifyGateway shopifyGateway;
    @MockBean JobScheduler jobScheduler;

    record T(UUID id, UUID store, UUID location, UUID owner, UUID worker, UUID shirt, UUID scarf) {}
    record Parcel(UUID order, UUID shipment, String awb) {}

    final AtomicInteger seq = new AtomicInteger();

    @AfterEach
    void clear() { TenantContext.clear(); }

    // ── l1 ─────────────────────────────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void l1_markedSellableUnit_linkClosesReturnToReceive_itemLinked_closeAllowed_counted() {
        T t = tenant("l1");
        Parcel p = forwardRto(t);
        UUID line = line(t, p.order(), t.shirt(), 1);
        UUID s = session(t);
        scan(t, s, p.awb());
        arrived(t, s, p, line, 1, "sellable");
        assertThat(toReceive(t)).hasSize(1);

        String piece = availablePiece(t, t.shirt(), t.location());
        scan(t, s, code(piece));
        Map<String, Object> item = item(t, s, piece);
        assertThat(item.get("disposition")).isEqualTo("pending");
        List<Map<String, Object>> cands = (List<Map<String, Object>>) item.get("linkCandidates");
        assertThat(cands).singleElement().satisfies(c -> {
            assertThat(c.get("orderItemId")).isEqualTo(line.toString());
            assertThat(c.get("intakeId")).isNotNull();
            assertThat(c.get("condition")).isEqualTo("sellable");
        });

        Map<String, Object> res = link(t, s, piece, p, line, 1, null, "worker");
        assertThat(res.get("disposition")).isEqualTo("linked");
        assertThat(((Map<String, Object>) res.get("unitLink")).get("shopifyMayBeLow")).isEqualTo(false);
        assertThat(liveLinks(piece)).isEqualTo(1);
        assertThat(status(piece)).isEqualTo("available");
        assertThat(events(piece, "return_unit_linked")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT from_status::text || '>' || to_status::text FROM piece_events " +
            "WHERE piece_id = ? AND event_type = 'return_unit_linked'", String.class, piece)).isEqualTo("available>available");
        assertThat(toReceive(t)).as("the unit is in stock as the piece — nothing to receive").isEmpty();
        assertThat(item(t, s, piece)).doesNotContainKey("linkCandidates");
        Map<String, Object> detail = TenantContext.runAs(t.id(), () -> sessions.getSession(s));
        assertThat((List<Map<String, Object>>) detail.get("unitLinks")).singleElement()
            .satisfies(l -> assertThat(l.get("pieceId")).isEqualTo(piece));

        Map<String, Object> closed = TenantContext.runAs(t.id(), () -> sessions.close(s, t.worker()));
        assertThat(closed).containsEntry("linkedCount", 1).doesNotContainKey("needsReviewCount").containsEntry("mismatchCount", 0);
        Map<String, Object> list = TenantContext.runAs(t.id(), () -> sessions.listSessions(0, 10));
        assertThat(((List<Map<String, Object>>) list.get("items")).get(0).get("linked_count")).isEqualTo(1L);
        Map<String, Object> an = TenantContext.runAs(t.id(), () -> sessions.analytics(null, null));
        assertThat(an).containsEntry("linkedCount", 1).doesNotContainKey("needsReviewCount");
        assertThat(needsReview(t)).isEmpty();
    }

    // ── l2 ─────────────────────────────────────────────────────────────────────

    @Test
    void l2_unmarkedUnit_linkCreatesIntake_undoRevertsEverything() {
        T t = tenant("l2");
        Parcel p = forwardRto(t);
        UUID line = line(t, p.order(), t.shirt(), 1);
        UUID s = session(t);
        scan(t, s, p.awb());
        String piece = availablePiece(t, t.shirt(), t.location());
        scan(t, s, code(piece));

        assertThatThrownBy(() -> link(t, s, piece, p, line, 1, null, "worker"))
            .as("no intake yet → the condition is required")
            .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getStatusCode().value()).isEqualTo(400));

        link(t, s, piece, p, line, 1, "sellable", "worker");
        assertThat(liveIntakes(p.shipment())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT created_intake FROM untracked_unit_piece_links WHERE piece_id = ?",
            Boolean.class, piece)).isTrue();
        assertThat(outcome(p.shipment())).isEqualTo("untracked_units_arrived");
        assertThat(toReceive(t)).isEmpty();

        TenantContext.runAs(t.id(), () -> sessions.undoLinkUnit(s, piece, t.worker()));
        assertThat(liveLinks(piece)).isZero();
        assertThat(liveIntakes(p.shipment())).as("the intake the link made is undone with it").isZero();
        assertThat(outcome(p.shipment())).isNull();
        assertThat(item(t, s, piece).get("disposition")).isEqualTo("pending");
        assertThat(events(piece, "return_unit_unlinked")).isEqualTo(1);
        assertThat(status(piece)).isEqualTo("available");
    }

    // ── l3 ─────────────────────────────────────────────────────────────────────

    @Test
    void l3_damagedLink_movesPieceToDamaged_undoRefused() {
        T t = tenant("l3");
        Parcel p = forwardRto(t);
        UUID line = line(t, p.order(), t.shirt(), 1);
        UUID s = session(t);
        scan(t, s, p.awb());
        String piece = availablePiece(t, t.shirt(), t.location());
        scan(t, s, code(piece));

        link(t, s, piece, p, line, 1, "damaged", "worker");
        assertThat(status(piece)).isEqualTo("damaged");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM piece_events WHERE piece_id = ? AND event_type = 'adjusted' " +
            "AND to_status = 'damaged'::piece_status", Integer.class, piece)).isEqualTo(1);
        assertThat(toReceive(t)).as("damaged units never raise one").isEmpty();
        assertThatThrownBy(() -> TenantContext.runAs(t.id(), () -> sessions.undoLinkUnit(s, piece, t.worker())))
            .isInstanceOfSatisfying(ReturnSessionException.class, e -> assertThat(e.details()).containsEntry("reason", "damaged_link"));
        assertThat(liveLinks(piece)).isEqualTo(1);
    }

    // ── l4 ─────────────────────────────────────────────────────────────────────

    @Test
    void l4_doubleTap_sameLink_otherUnitRefused() {
        T t = tenant("l4");
        Parcel p = forwardRto(t);
        UUID line = line(t, p.order(), t.shirt(), 2);
        UUID s = session(t);
        scan(t, s, p.awb());
        String piece = availablePiece(t, t.shirt(), t.location());
        scan(t, s, code(piece));

        link(t, s, piece, p, line, 1, "sellable", "worker");
        Map<String, Object> again = link(t, s, piece, p, line, 1, "sellable", "worker");
        assertThat(again.get("disposition")).isEqualTo("linked");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM untracked_unit_piece_links WHERE piece_id = ?", Integer.class, piece))
            .isEqualTo(1);
        assertThat(events(piece, "return_unit_linked")).isEqualTo(1);
        assertThat(liveIntakes(p.shipment())).isEqualTo(1);
        assertThatThrownBy(() -> link(t, s, piece, p, line, 2, "sellable", "worker"))
            .isInstanceOfSatisfying(ReturnSessionException.class, e -> assertThat(e.details()).containsEntry("reason", "item_already_linked"));
    }

    // ── g1 guards ──────────────────────────────────────────────────────────────

    @Test
    void g1_everyGuard_refusesWithItsReason_andWritesNothing() {
        T t = tenant("g1");
        Parcel p = forwardRto(t);
        UUID shirtLine = line(t, p.order(), t.shirt(), 1);
        UUID s = session(t);
        scan(t, s, p.awb());

        // Other variant (Q1): no candidate, refused.
        String scarf = availablePiece(t, t.scarf(), t.location());
        scan(t, s, code(scarf));
        assertThat(item(t, s, scarf)).doesNotContainKey("linkCandidates");
        assertRefused(() -> link(t, s, scarf, p, shirtLine, 1, "sellable", "owner"), "unit_not_linkable");

        // Lost (Q4).
        String lost = piece(t, t.shirt(), "lost", t.location());
        scan(t, s, code(lost));
        assertThat(item(t, s, lost)).doesNotContainKey("linkCandidates");
        assertRefused(() -> link(t, s, lost, p, shirtLine, 1, "sellable", "owner"), "piece_lost");

        // On another order: names it.
        String reserved = piece(t, t.shirt(), "reserved", t.location());
        UUID other = order(t);
        UUID otherLine = line(t, other, t.shirt(), 1);
        jdbc.update("INSERT INTO allocations (tenant_id, order_item_id, piece_id, status) VALUES (?, ?, ?, 'active')",
            t.id(), otherLine, reserved);
        scan(t, s, code(reserved));
        assertThatThrownBy(() -> link(t, s, reserved, p, shirtLine, 1, "sellable", "owner"))
            .isInstanceOfSatisfying(ReturnSessionException.class, e -> {
                assertThat(e.details()).containsEntry("reason", "piece_on_another_order");
                assertThat(e.messageEn()).contains(number(other));
            });

        // Not at the main warehouse.
        UUID shop = UUID.randomUUID();
        jdbc.update("INSERT INTO locations (id, tenant_id, name, is_fulfillment) VALUES (?, ?, 'Shop', false)", shop, t.id());
        String away = availablePiece(t, t.shirt(), shop);
        scan(t, s, code(away));
        assertRefused(() -> link(t, s, away, p, shirtLine, 1, "sellable", "owner"), "piece_not_at_main");

        // A marked condition that differs.
        arrived(t, s, p, shirtLine, 1, "damaged");
        String good = availablePiece(t, t.shirt(), t.location());
        scan(t, s, code(good));
        assertRefused(() -> link(t, s, good, p, shirtLine, 1, "sellable", "owner"), "unit_marked_other_condition");

        // The unit is already linked to another piece.
        link(t, s, good, p, shirtLine, 1, null, "owner");
        String second = availablePiece(t, t.shirt(), t.location());
        scan(t, s, code(second));
        assertThat(item(t, s, second)).doesNotContainKey("linkCandidates");
        assertRefused(() -> link(t, s, second, p, shirtLine, 1, null, "owner"), "unit_not_linkable");

        // A unit a Receiving line already took.
        Parcel q = forwardRto(t);
        UUID qLine = line(t, q.order(), t.shirt(), 1);
        scan(t, s, q.awb());
        arrived(t, s, q, qLine, 1, "sellable");
        UUID intake = jdbc.queryForObject("SELECT id FROM untracked_unit_intakes WHERE shipment_id = ? AND undone_at IS NULL",
            UUID.class, q.shipment());
        UUID receipt = jdbc.queryForObject("INSERT INTO receipts (tenant_id, location_id) VALUES (?, ?) RETURNING id",
            UUID.class, t.id(), t.location());
        jdbc.update("INSERT INTO receipt_lines (tenant_id, receipt_id, variant_id, quantity, return_order_id, return_source) " +
            "VALUES (?, ?, ?, 1, ?, ?)", t.id(), receipt, t.shirt(), q.order(), "untracked_unit:" + intake);
        assertRefused(() -> link(t, s, second, q, qLine, 1, null, "owner"), "unit_not_linkable");

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM untracked_unit_piece_links WHERE tenant_id = ?", Integer.class, t.id()))
            .as("only the one allowed link").isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM piece_events WHERE tenant_id = ? AND event_type = 'return_unit_linked'",
            Integer.class, t.id())).isEqualTo(1);
    }

    // ── g2 ─────────────────────────────────────────────────────────────────────

    @Test
    void g2_linkedUnitsMark_cantBeUndone_untilTheLinkIs() {
        T t = tenant("g2");
        Parcel p = forwardRto(t);
        UUID line = line(t, p.order(), t.shirt(), 1);
        UUID s = session(t);
        scan(t, s, p.awb());
        arrived(t, s, p, line, 1, "sellable");
        String piece = availablePiece(t, t.shirt(), t.location());
        scan(t, s, code(piece));
        link(t, s, piece, p, line, 1, null, "worker");

        assertThatThrownBy(() -> TenantContext.runAs(t.id(), () -> sessions.undoUnitArrived(s, p.shipment(), line, 1, t.worker())))
            .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getStatusCode().value()).isEqualTo(409));
        assertThat(liveIntakes(p.shipment())).isEqualTo(1);
        List<Map<String, Object>> arrivals = TenantContext.runAs(t.id(), () ->
            new UntrackedParcelUnits(jdbc).arrivals(t.id(), p.order()));
        assertThat(arrivals).singleElement().satisfies(a -> assertThat(a.get("undoBlocked")).isEqualTo("received"));

        TenantContext.runAs(t.id(), () -> sessions.undoLinkUnit(s, piece, t.worker()));
        TenantContext.runAs(t.id(), () -> sessions.undoUnitArrived(s, p.shipment(), line, 1, t.worker()));
        assertThat(liveIntakes(p.shipment())).isZero();
    }

    // ── c1 / c2 close with an illegal row ──────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void c1_closeWithIllegalPendingRow_needsReviewAndOneAlert_legalPendingStillBlocks() {
        T t = tenant("c1");
        UUID s = session(t);
        String illegal = availablePiece(t, t.shirt(), t.location());
        scan(t, s, code(illegal));

        // A legal pending row (delivered piece of an order, scanned → return_pending_inspection) still blocks.
        String legal = deliveredPiece(t, t.scarf());
        scan(t, s, code(legal));
        assertThat(status(legal)).isEqualTo("return_pending_inspection");
        assertThatThrownBy(() -> TenantContext.runAs(t.id(), () -> sessions.close(s, t.worker())))
            .isInstanceOfSatisfying(ReturnSessionException.class, e -> {
                assertThat(e.code()).isEqualTo(ReturnSessionException.Code.SESSION_CLOSE_BLOCKED);
                assertThat((List<Map<String, Object>>) e.details().get("blockingItems"))
                    .extracting(m -> m.get("pieceId")).containsExactly(legal);
            });
        assertThat(item(t, s, illegal).get("disposition")).as("a refused close changes nothing").isEqualTo("pending");

        TenantContext.runAs(t.id(), () -> sessions.disposition(s, legal, "restock", null, null, t.worker(), "worker"));
        Map<String, Object> closed = TenantContext.runAs(t.id(), () -> sessions.close(s, t.worker()));
        assertThat(closed).containsEntry("needsReviewCount", 1).containsEntry("restockedCount", 1);
        assertThat(item(t, s, illegal).get("disposition")).isEqualTo("needs_review");
        assertThat(status(illegal)).as("nothing moves").isEqualTo("available");

        List<Map<String, Object>> alerts = needsReview(t);
        assertThat(alerts).singleElement().satisfies(a -> {
            assertThat(a.get("severity")).isEqualTo("MEDIUM");
            assertThat((String) a.get("descriptionEn")).contains(code(illegal)).contains(s.toString().substring(0, 8).toUpperCase());
            assertThat((String) a.get("descriptionAr")).contains(code(illegal));
            assertThat(a.get("actionUrl")).isEqualTo("/returns?session=" + s + "&piece=" + illegal);
        });
        Map<String, Object> an = TenantContext.runAs(t.id(), () -> sessions.analytics(null, null));
        assertThat(an).containsEntry("needsReviewCount", 1);
        assertThat(item(t, s, illegal)).containsEntry("reviewResolved", false);
    }

    @Test
    void c2_needsReview_settledByManager_link_mismatch_or_nothingToDo_workerRefused() {
        T t = tenant("c2");
        Parcel p = forwardRto(t);
        UUID line = line(t, p.order(), t.shirt(), 1);
        UUID s = session(t);
        scan(t, s, p.awb());
        String a = availablePiece(t, t.shirt(), t.location());
        String b = availablePiece(t, t.scarf(), t.location());
        String c = availablePiece(t, t.scarf(), t.location());
        scan(t, s, code(a));
        scan(t, s, code(b));
        scan(t, s, code(c));
        TenantContext.runAs(t.id(), () -> sessions.close(s, t.worker()));
        assertThat(needsReview(t)).hasSize(3);
        assertThat(item(t, s, a)).containsKey("linkCandidates");

        // A worker can't settle a needs-review item.
        assertThatThrownBy(() -> link(t, s, a, p, line, 1, "sellable", "worker"))
            .isInstanceOfSatisfying(ReturnSessionException.class,
                e -> assertThat(e.code()).isEqualTo(ReturnSessionException.Code.NEEDS_MANAGER));

        // Manager links it — the session is closed.
        link(t, s, a, p, line, 1, "sellable", "manager");
        assertThat(item(t, s, a).get("disposition")).isEqualTo("linked");
        assertThat(toReceive(t)).isEmpty();
        // Manager: not the real piece.
        TenantContext.runAs(t.id(), () -> sessions.disposition(s, b, "mismatch", null, null, t.owner(), "owner"));
        assertThat(item(t, s, b).get("disposition")).isEqualTo("mismatch");
        // Manager: nothing to do.
        UUID cItem = jdbc.queryForObject("SELECT id FROM return_session_items WHERE session_id = ? AND piece_id = ?",
            UUID.class, s, c);
        TenantContext.runAs(t.id(), () -> exceptions.resolve("return_needs_review", "return_needs_review:item:" + cItem,
            t.owner(), "nothing to do"));
        assertThat(needsReview(t)).isEmpty();
        assertThat(item(t, s, c)).containsEntry("reviewResolved", true);

        // A worker still can't mismatch.
        assertThatThrownBy(() -> TenantContext.runAs(t.id(), () ->
            sessions.disposition(s, c, "mismatch", null, null, t.worker(), "worker")))
            .isInstanceOf(ReturnSessionException.class);
    }

    // ── a1 auto-close ──────────────────────────────────────────────────────────

    @Test
    void a1_autoClose_closesIdleSessionWhoseOnlyPendingRowIsIllegal() {
        T t = tenant("a1");
        UUID s = session(t);
        String illegal = availablePiece(t, t.shirt(), t.location());
        scan(t, s, code(illegal));
        jdbc.update("UPDATE return_sessions SET opened_at = now() - interval '13 hours' WHERE id = ?", s);
        jdbc.update("UPDATE return_session_items SET scanned_at = now() - interval '13 hours' WHERE session_id = ?", s);

        // The job bean only exists with the JobRunr server on; built here on the same beans (processTenant is its
        // per-tenant pass, the scheduled run() just loops tenants).
        ReturnSessionAutoCloseJob autoClose = new ReturnSessionAutoCloseJob(
            new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "postgres", "postgres"), jdbc, txm, sessions);
        autoClose.processTenant(t.id());
        assertThat(jdbc.queryForObject("SELECT status FROM return_sessions WHERE id = ?", String.class, s)).isEqualTo("closed");
        assertThat(item(t, s, illegal).get("disposition")).isEqualTo("needs_review");
        assertThat(needsReview(t)).hasSize(1);
    }

    // ── b1 case B ──────────────────────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void b1_shopifyFulfilledFromMain_flagsShopifyMayBeLow_otherLocationDoesNot() {
        T t = tenant("b1");
        jdbc.update("UPDATE locations SET shopify_location_id = 'gid://shopify/Location/555' WHERE id = ?", t.location());
        jdbc.update("UPDATE variants SET external_id = 'gid://shopify/ProductVariant/9001' WHERE id = ?", t.shirt());
        Parcel main = forwardRto(t);
        jdbc.update("UPDATE orders SET raw = ?::jsonb WHERE id = ?", fulfilledAt(555, 9001), main.order());
        UUID mainLine = line(t, main.order(), t.shirt(), 1);
        Parcel elsewhere = forwardRto(t);
        jdbc.update("UPDATE orders SET raw = ?::jsonb WHERE id = ?", fulfilledAt(777, 9001), elsewhere.order());
        UUID elseLine = line(t, elsewhere.order(), t.shirt(), 1);
        UUID s = session(t);
        scan(t, s, main.awb());
        scan(t, s, elsewhere.awb());
        String x = availablePiece(t, t.shirt(), t.location());
        String y = availablePiece(t, t.shirt(), t.location());
        scan(t, s, code(x));
        scan(t, s, code(y));

        Map<String, Object> lx = link(t, s, x, main, mainLine, 1, "sellable", "worker");
        assertThat(((Map<String, Object>) lx.get("unitLink")).get("shopifyMayBeLow")).isEqualTo(true);
        Map<String, Object> ly = link(t, s, y, elsewhere, elseLine, 1, "sellable", "worker");
        assertThat(((Map<String, Object>) ly.get("unitLink")).get("shopifyMayBeLow")).isEqualTo(false);
        org.mockito.Mockito.verifyNoInteractions(shopifyGateway);
    }

    // ── x1 app_user ────────────────────────────────────────────────────────────

    @Test
    void x1_appUser_linksTenantIsolated_ownTenantWorks_grants() {
        DataSource ds = new TenantAwareDataSource(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "app_user", "testpw"));
        JdbcTemplate appJdbc = new JdbcTemplate(ds);
        TransactionTemplate appTx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        ReturnSessionService appSessions = new ReturnSessionService(appJdbc, ledger, returnService, shipmentLinkService, Clock.systemUTC());

        T a = tenant("xa");
        T b = tenant("xb");
        Parcel pa = forwardRto(a);
        UUID la = line(a, pa.order(), a.shirt(), 1);
        Parcel pb = forwardRto(b);
        UUID lb = line(b, pb.order(), b.shirt(), 1);
        UUID sa = session(a);
        UUID sb = session(b);
        scan(a, sa, pa.awb());
        scan(b, sb, pb.awb());
        String pieceA = availablePiece(a, a.shirt(), a.location());
        String pieceB = availablePiece(b, b.shirt(), b.location());
        scan(a, sa, code(pieceA));
        scan(b, sb, code(pieceB));
        link(b, sb, pieceB, pb, lb, 1, "sellable", "worker");

        // Tenant A on app_user: B's link invisible; B's session a 404; nothing written.
        Integer seen = TenantContext.runAs(a.id(), () -> appTx.execute(x ->
            appJdbc.queryForObject("SELECT COUNT(*) FROM untracked_unit_piece_links", Integer.class)));
        assertThat(seen).isZero();
        assertThatThrownBy(() -> TenantContext.runAs(a.id(), () -> appTx.execute(x ->
            appSessions.linkUnit(sb, pieceB, pb.shipment(), lb, 1, "sellable", a.owner(), "owner"))))
            .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getStatusCode().value()).isEqualTo(404));
        Integer none = appTx.execute(x -> appJdbc.queryForObject("SELECT COUNT(*) FROM untracked_unit_piece_links", Integer.class));
        assertThat(none).as("no tenant set → nothing").isZero();

        // Positive control: A links its own piece on app_user (the whole path: intake, link, event, item).
        TenantContext.runAs(a.id(), () -> appTx.execute(x ->
            appSessions.linkUnit(sa, pieceA, pa.shipment(), la, 1, "sellable", a.owner(), "owner")));
        Integer own = TenantContext.runAs(a.id(), () -> appTx.execute(x ->
            appJdbc.queryForObject("SELECT COUNT(*) FROM untracked_unit_piece_links", Integer.class)));
        assertThat(own).isEqualTo(1);
        assertThat(item(a, sa, pieceA).get("disposition")).isEqualTo("linked");
        assertThat(events(pieceA, "return_unit_linked")).isEqualTo(1);

        // Grants: other columns and DELETE refused; the undo columns allowed; undo works on app_user.
        assertThatThrownBy(() -> TenantContext.runAs(a.id(), () -> appTx.execute(x ->
            appJdbc.update("UPDATE untracked_unit_piece_links SET condition = 'damaged'"))))
            .rootCause().hasMessageContaining("permission denied");
        assertThatThrownBy(() -> TenantContext.runAs(a.id(), () -> appTx.execute(x ->
            appJdbc.update("DELETE FROM untracked_unit_piece_links"))))
            .rootCause().hasMessageContaining("permission denied");
        TenantContext.runAs(a.id(), () -> appTx.execute(x -> appSessions.undoLinkUnit(sa, pieceA, a.owner())));
        assertThat(liveLinks(pieceA)).isZero();
        assertThat(liveLinks(pieceB)).as("B's link untouched").isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pg_policies WHERE tablename = 'untracked_unit_piece_links' " +
            "AND policyname = 'tenant_isolation'", Integer.class)).isEqualTo(1);
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private void assertRefused(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, String reason) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ReturnSessionException.class, e -> {
            assertThat(e.code()).isEqualTo(ReturnSessionException.Code.LINK_REFUSED);
            assertThat(e.details()).containsEntry("reason", reason);
            assertThat(e.messageAr()).isNotBlank();
        });
    }

    private Map<String, Object> link(T t, UUID s, String piece, Parcel p, UUID line, int unit, String condition, String role) {
        UUID actor = "worker".equals(role) ? t.worker() : t.owner();
        return TenantContext.runAs(t.id(), () -> sessions.linkUnit(s, piece, p.shipment(), line, unit, condition, actor, role));
    }

    private void arrived(T t, UUID s, Parcel p, UUID line, int unit, String condition) {
        TenantContext.runAs(t.id(), () -> sessions.unitArrived(s, p.shipment(), line, unit, condition, t.owner()));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> item(T t, UUID s, String piece) {
        Map<String, Object> detail = TenantContext.runAs(t.id(), () -> sessions.getSession(s));
        return ((List<Map<String, Object>>) detail.get("items")).stream()
            .filter(i -> piece.equals(i.get("piece_id"))).findFirst().orElseThrow();
    }

    private List<Map<String, Object>> toReceive(T t) {
        return TenantContext.runAs(t.id(), () -> exceptions.detectAllOpen()).stream()
            .filter(e -> "return_to_receive".equals(e.get("type"))).toList();
    }

    private List<Map<String, Object>> needsReview(T t) {
        return TenantContext.runAs(t.id(), () -> exceptions.detectAllOpen()).stream()
            .filter(e -> "return_needs_review".equals(e.get("type"))).toList();
    }

    private int liveLinks(String piece) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM untracked_unit_piece_links WHERE piece_id = ? AND undone_at IS NULL",
            Integer.class, piece);
    }

    private int liveIntakes(UUID shipment) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM untracked_unit_intakes WHERE shipment_id = ? AND undone_at IS NULL",
            Integer.class, shipment);
    }

    private int events(String piece, String type) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM piece_events WHERE piece_id = ? AND event_type = ?",
            Integer.class, piece, type);
    }

    private String status(String piece) {
        return jdbc.queryForObject("SELECT status::text FROM pieces WHERE id = ?", String.class, piece);
    }

    private String code(String piece) {
        return jdbc.queryForObject("SELECT short_code FROM pieces WHERE id = ?", String.class, piece);
    }

    private String number(UUID order) {
        return jdbc.queryForObject("SELECT number FROM orders WHERE id = ?", String.class, order);
    }

    private String outcome(UUID shipment) {
        return jdbc.queryForObject("SELECT return_intake_outcome FROM shipments WHERE id = ?", String.class, shipment);
    }

    private UUID session(T t) {
        return TenantContext.runAs(t.id(), () -> sessions.createSession(null, t.owner()));
    }

    private void scan(T t, UUID s, String code) {
        TenantContext.runAs(t.id(), () -> sessions.scan(s, code, t.location(), t.owner()));
    }

    private UUID order(T t) {
        int k = seq.incrementAndGet();
        return jdbc.queryForObject(
            "INSERT INTO orders (tenant_id, store_id, external_id, number, status, payment_method, placed_at) " +
            "VALUES (?, ?, ?, ?, 'new'::order_status, 'cod'::order_payment_method, now()) RETURNING id",
            UUID.class, t.id(), t.store(), "gid://shopify/Order/LK" + k + "-" + t.id(), "#LK" + k);
    }

    /** A returned-to-sender forward leg (Bosta Return to Origin, back at the merchant) — the BRK-47010-EG shape. */
    private Parcel forwardRto(T t) {
        UUID o = order(t);
        String awb = String.valueOf(7_500_000_000L + seq.incrementAndGet());
        UUID id = jdbc.queryForObject(
            "INSERT INTO shipments (tenant_id, order_id, provider, tracking_number, internal_state, shipment_leg, raw) " +
            "VALUES (?, ?, 'bosta', ?, 'returned'::shipment_internal_state, 'forward', ?::jsonb) RETURNING id",
            UUID.class, t.id(), o, awb,
            "{\"type\": {\"code\": 20, \"value\": \"Return to Origin\"}, \"state\": {\"code\": 46, \"value\": \"Returned to business\"}}");
        return new Parcel(o, id, awb);
    }

    private UUID line(T t, UUID order, UUID variant, int qty) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO order_items (id, tenant_id, order_id, variant_id, quantity) VALUES (?, ?, ?, ?, ?)",
            id, t.id(), order, variant, qty);
        return id;
    }

    private String availablePiece(T t, UUID variant, UUID location) {
        return piece(t, variant, "available", location);
    }

    private String piece(T t, UUID variant, String status, UUID location) {
        String piece = UlidGenerator.generate();
        jdbc.update("INSERT INTO pieces (id, tenant_id, variant_id, barcode, short_code, status, current_location_id, last_event_at) " +
            "VALUES (?, ?, ?, ?, 'L' || LPAD((abs(hashtext(?)) % 999999 + 1)::text, 6, '0'), ?::piece_status, ?, now())",
            piece, t.id(), variant, "LK-" + piece, piece, status, location);
        return piece;
    }

    /** A delivered piece of its own order, within the return window — its scan is legal. */
    private String deliveredPiece(T t, UUID variant) {
        UUID o = order(t);
        String piece = UlidGenerator.generate();
        jdbc.update("INSERT INTO pieces (id, tenant_id, variant_id, barcode, short_code, status, current_order_id, last_event_at) " +
            "VALUES (?, ?, ?, ?, 'L' || LPAD((abs(hashtext(?)) % 999999 + 1)::text, 6, '0'), 'delivered'::piece_status, ?, now())",
            piece, t.id(), variant, "LK-" + piece, piece, o);
        UUID item = line(t, o, variant, 1);
        jdbc.update("INSERT INTO allocations (tenant_id, order_item_id, piece_id, status) VALUES (?, ?, ?, 'packed')",
            t.id(), item, piece);
        return piece;
    }

    private static String fulfilledAt(long locationId, long variantId) {
        return "{\"fulfillments\": [{\"status\": \"success\", \"location_id\": " + locationId + ", " +
               "\"line_items\": [{\"variant_id\": " + variantId + ", \"quantity\": 1}]}]}";
    }

    private T tenant(String name) {
        UUID id = UUID.randomUUID(), store = UUID.randomUUID(), owner = UUID.randomUUID(), worker = UUID.randomUUID(),
             location = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?)", id, "Links " + name);
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status) VALUES (?, ?, 'shopify', ?, 'disconnected')",
            store, id, "links-" + name + "-" + id.toString().substring(0, 6) + ".myshopify.com");
        jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role, active) VALUES (?, ?, 'Owner', ?, 'h', 'owner', true)",
            owner, id, "owner-" + owner + "@test.local");
        jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role, active) VALUES (?, ?, 'Worker', ?, 'h', 'worker', true)",
            worker, id, "worker-" + worker + "@test.local");
        jdbc.update("INSERT INTO locations (id, tenant_id, name, is_fulfillment) VALUES (?, ?, 'Main', true)", location, id);
        UUID shirtP = UUID.randomUUID(), scarfP = UUID.randomUUID();
        jdbc.update("INSERT INTO products (id, tenant_id, store_id, external_id, title, status) VALUES (?, ?, ?, ?, 'Eagle Tee', 'active')",
            shirtP, id, store, "P-shirt-" + id);
        jdbc.update("INSERT INTO products (id, tenant_id, store_id, external_id, title, status) VALUES (?, ?, ?, ?, 'Wool Scarf', 'active')",
            scarfP, id, store, "P-scarf-" + id);
        UUID shirt = UUID.randomUUID(), scarf = UUID.randomUUID();
        jdbc.update("INSERT INTO variants (id, tenant_id, product_id, external_id, title, sku) VALUES (?, ?, ?, ?, 'L / Black', 'ET-L')",
            shirt, id, shirtP, "V-" + shirt);
        jdbc.update("INSERT INTO variants (id, tenant_id, product_id, external_id, title, sku) VALUES (?, ?, ?, ?, 'Grey', 'SC-G')",
            scarf, id, scarfP, "V-" + scarf);
        return new T(id, store, location, owner, worker, shirt, scarf);
    }
}
