package com.traceability.places;

import com.traceability.TestPostgres;
import com.traceability.inventory.InventoryLedger;
import com.traceability.inventory.StockTakeService;
import com.traceability.tenancy.TenantAwareDataSource;
import com.traceability.tenancy.TenantContext;
import org.jobrunr.scheduling.JobScheduler;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
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
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

/**
 * Warehouse places S5 (V198) — counting one place, place stickers in the full stock take, "Where is it", moving a
 * box by scanning.
 *
 *   c1  every scan result: found here / move here (shows where) / place here / other location / not placeable /
 *       untagged / unknown / duplicate (ignored) / the place's own sticker — and NOTHING changes before Finish
 *       (revert-check: apply per scan → red)
 *   c2  a box sticker (a box ON this shelf, or another place) during a shelf count → COUNT_SEPARATELY, nothing
 *       recorded; the shelf's count leaves the box's pieces alone at Finish
 *   c3  Finish: move here → moved, place here → placed, not found → "No place yet" (place_cleared, source
 *       place_count); NEVER lost, no status change, no Shopify claim (revert-check: mark not found lost → red)
 *   c4  Finish re-checks: moved elsewhere since its scan → left there; departed (sold) → skipped; put here during
 *       the count → not cleared; found here then moved away → left
 *   c5  Cancel changes nothing
 *   c6  one open count per place: Start again (another person too) joins it; the database refuses a second open one
 *   c7  12 h idle → cancelled by the job (nothing changes); a fresh count is open
 *   c8  Start refusals: zone / rack sticker → NOT_A_SHELF_OR_BOX, archived, unknown sticker; places off → 404 for
 *       every method
 *   c9  a shared count: each scan row records who scanned it, the view names everyone who scanned, Finish records who
 *       finished
 *   st1 full stock take: a place sticker sets "counting place X"; pieces after it are put there (source stock_take);
 *       the count classification is unchanged
 *   st2 no sticker → no place changes; places off → a sticker is an unknown scan, exactly as before
 *   st3 sticker refusals in a stock take: another location, a rack, archived — the counted place stays
 *   w1  Where is it: per location, every place with counts per status (available / on_hold / damaged), walking order,
 *       "No place yet"; sold / reserved / lost left out
 *   w2  Where is it: ONE counting statement for 3 places as for 30
 *   b1  Move a box: shelf → zone floor → another shelf; one bin_events 'moved' each (actor, from → to); the pieces
 *       keep their bin_id and their path follows the box; same target again → ALREADY_THERE, no event
 *   b2  Move a box refusals: not a box, archived box / target, rack target, the box itself, another box, another
 *       location
 *   x1  app_user: start (twice), scan, finish, cancel under RLS on V198's grants; another tenant's count invisible
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PlacesS5Test {

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

    @MockBean JobScheduler jobScheduler;

    @Autowired JdbcTemplate      jdbc;
    @Autowired PlaceCountService counts;
    @Autowired StockTakeService  stockTake;
    @Autowired WhereIsItService  whereIs;
    @Autowired BoxMoveService    boxes;
    @Autowired InventoryLedger   ledger;
    @Autowired DataSource        dataSource;
    PlaceCountIdleCancelJob idleJob;

    private static final AtomicInteger seq = new AtomicInteger();

    @BeforeAll
    void job() { idleJob = new PlaceCountIdleCancelJob(counts, dataSource); }

    /**
     * A tenant: main warehouse with Z2 › Z2-R01 › S9 / S10 (BOX-12 on S9), floor box BOX-07 in Z2, Z10 › Z10-R01 › S1,
     * an archived shelf S11; a second location with its own shelf and box.
     */
    record T(UUID tenant, UUID user, UUID main, UUID other, UUID variant, UUID variant2, UUID zone, UUID rack,
             UUID s9, UUID s10, UUID box12, UUID box07, UUID z10s1, UUID archived, UUID otherShelf, UUID otherBox) {}

    // ── c: one-place count ─────────────────────────────────────────────────────

    @Test
    void c1_everyScanResult_andNothingChangesBeforeFinish() {
        T t = tenant("c1", true);
        String here = piece(t, t.variant(), t.main(), "available", t.s9());
        String elsewhere = piece(t, t.variant(), t.main(), "available", t.s10());
        String nowhere = piece(t, t.variant(), t.main(), "available", null);
        String otherLoc = piece(t, t.variant(), t.other(), "available", null);
        String reserved = piece(t, t.variant(), t.main(), "reserved", null);
        String untagged = untaggedPiece(t);
        Map<String, Object[]> before = snapshot(t);

        var c = as(t, () -> counts.start(t.user(), t.s9(), null));
        assertThat(c.status()).isEqualTo("open");
        assertThat(c.expected()).isEqualTo(1);

        assertThat(scan(t, c.id(), code(here)).outcome()).isEqualTo("FOUND_HERE");
        var moved = scan(t, c.id(), code(elsewhere));
        assertThat(moved.outcome()).isEqualTo("MOVE_HERE");
        assertThat(moved.fromPlace().code()).as("shows where it was").isEqualTo("Z2-R01-S10");
        assertThat(scan(t, c.id(), code(nowhere)).outcome()).isEqualTo("PLACE_HERE");
        var other = scan(t, c.id(), code(otherLoc));
        assertThat(other.outcome()).isEqualTo("OTHER_LOCATION");
        assertThat(other.locationName()).isEqualTo("Second Store");
        var notPlaceable = scan(t, c.id(), code(reserved));
        assertThat(notPlaceable.outcome()).isEqualTo("NOT_PLACEABLE");
        assertThat(notPlaceable.status()).isEqualTo("reserved");
        assertThat(scan(t, c.id(), untagged).outcome()).isEqualTo("UNTAGGED");
        assertThat(scan(t, c.id(), "P0000000").outcome()).isEqualTo("UNKNOWN");
        assertThat(scan(t, c.id(), code(here)).outcome()).as("a repeat scan is ignored").isEqualTo("DUPLICATE");
        assertThat(scan(t, c.id(), "P0000000").outcome()).isEqualTo("DUPLICATE");
        assertThat(scan(t, c.id(), scanCode(t.s9())).outcome()).isEqualTo("THIS_PLACE");

        var view = as(t, () -> counts.get(c.id()));
        assertThat(view.found()).isEqualTo(1);
        assertThat(view.movedHere()).isEqualTo(2);
        assertThat(view.notFound()).isZero();
        assertThat(view.otherLines()).extracting(PlaceCountService.CountLine::result)
            .containsExactlyInAnyOrder("other_location", "not_placeable", "untagged", "unknown");
        assertThat(jdbc.queryForObject("SELECT count(*)::int FROM place_count_scans WHERE count_id = ?", Integer.class, c.id()))
            .as("one row per piece / unknown code, repeats ignored").isEqualTo(7);

        assertThat(snapshot(t)).as("nothing changes on any piece before Finish").isEqualTo(before);
        assertThat(placeEvents(t)).isZero();
    }

    @Test
    void c2_aBoxDuringAShelfCount_countSeparately_nothingRecorded_boxLeftAlone() {
        T t = tenant("c2", true);
        String onShelf = piece(t, t.variant(), t.main(), "available", t.s9());
        String inBox = piece(t, t.variant(), t.main(), "available", t.box12());
        var c = as(t, () -> counts.start(t.user(), t.s9(), null));
        assertThat(c.expected()).as("the box on the shelf is its own place").isEqualTo(1);

        var box = scan(t, c.id(), scanCode(t.box12()));
        assertThat(box.outcome()).isEqualTo("COUNT_SEPARATELY");
        assertThat(box.place().code()).isEqualTo("BOX-12");
        assertThat(scan(t, c.id(), scanCode(t.s10())).outcome()).isEqualTo("COUNT_SEPARATELY");
        assertThat(jdbc.queryForObject("SELECT count(*)::int FROM place_count_scans WHERE count_id = ?", Integer.class, c.id()))
            .isZero();

        scan(t, c.id(), code(onShelf));
        as(t, () -> counts.finish(c.id(), t.user()));
        assertThat(bin(inBox)).as("the shelf's count never touches the box").isEqualTo(t.box12());
        assertThat(bin(onShelf)).isEqualTo(t.s9());
    }

    @Test
    void c3_finish_appliesMovedPlacedCleared_neverLost_noStatusChange_noShopify() {
        T t = tenant("c3", true);
        String here = piece(t, t.variant(), t.main(), "available", t.s9());
        String missing = piece(t, t.variant(), t.main(), "available", t.s9());
        String missingHeld = piece(t, t.variant2(), t.main(), "on_hold", t.s9());
        String elsewhere = piece(t, t.variant(), t.main(), "damaged", t.s10());
        String nowhere = piece(t, t.variant(), t.main(), "available", null);
        Integer claims = shopifyClaims(t);

        var c = as(t, () -> counts.start(t.user(), t.s9(), null));
        assertThat(c.expected()).isEqualTo(3);
        scan(t, c.id(), code(here));
        scan(t, c.id(), code(elsewhere));
        scan(t, c.id(), code(nowhere));
        assertThat(as(t, () -> counts.get(c.id())).notFoundPieces())
            .extracting(PlaceCountService.CountPiece::pieceId).containsExactlyInAnyOrder(missing, missingHeld);

        var done = as(t, () -> counts.finish(c.id(), t.user()));
        assertThat(done.status()).isEqualTo("finished");
        assertThat(done.applied().moved()).isEqualTo(1);
        assertThat(done.applied().placed()).isEqualTo(1);
        assertThat(done.applied().cleared()).isEqualTo(2);
        assertThat(done.applied().skipped()).isEmpty();

        assertThat(bin(here)).isEqualTo(t.s9());
        assertThat(bin(elsewhere)).isEqualTo(t.s9());
        assertThat(bin(nowhere)).isEqualTo(t.s9());
        assertThat(bin(missing)).as("not found → No place yet").isNull();
        assertThat(bin(missingHeld)).isNull();
        assertThat(status(missing)).as("NEVER lost").isEqualTo("available");
        assertThat(status(missingHeld)).isEqualTo("on_hold");
        assertThat(status(elsewhere)).isEqualTo("damaged");
        assertThat(jdbc.queryForList("SELECT event_type FROM piece_events WHERE piece_id = ?", String.class, missing))
            .containsExactly("place_cleared");
        assertThat(jdbc.queryForObject("SELECT count(*)::int FROM piece_events WHERE tenant_id = ? AND to_status::text = 'lost'",
            Integer.class, t.tenant())).isZero();
        assertThat(jdbc.queryForList(
            "SELECT DISTINCT metadata->>'source' FROM piece_events WHERE tenant_id = ? " +
            "AND event_type IN ('put_away', 'place_moved', 'place_cleared')", String.class, t.tenant())).containsExactly("place_count");
        assertThat(jdbc.queryForObject(
            "SELECT count(*)::int FROM piece_events WHERE tenant_id = ? AND from_status IS DISTINCT FROM to_status",
            Integer.class, t.tenant())).as("no status change").isZero();
        assertThat(shopifyClaims(t)).as("never Shopify").isEqualTo(claims);
        assertThatThrownBy(() -> as(t, () -> counts.finish(c.id(), t.user())))
            .extracting(e -> ((PlaceException) e).code()).isEqualTo(PlaceException.Code.COUNT_CLOSED);
    }

    @Test
    void c4_finish_rechecksEveryPiece() {
        T t = tenant("c4", true);
        String movedSince = piece(t, t.variant(), t.main(), "available", t.s10());
        String departed = piece(t, t.variant(), t.main(), "available", null);
        String foundThenMoved = piece(t, t.variant(), t.main(), "available", t.s9());
        String putHereDuring = piece(t, t.variant(), t.main(), "available", null);
        var c = as(t, () -> counts.start(t.user(), t.s9(), null));
        scan(t, c.id(), code(movedSince));
        scan(t, c.id(), code(departed));
        scan(t, c.id(), code(foundThenMoved));

        as(t, () -> ledger.recordPlaceChange(movedSince, t.z10s1(), t.user(), InventoryLedger.PlaceRef.lookup(), null));
        jdbc.update("UPDATE pieces SET status = 'sold' WHERE id = ?", departed);
        as(t, () -> ledger.recordPlaceChange(foundThenMoved, t.s10(), t.user(), InventoryLedger.PlaceRef.lookup(), null));
        as(t, () -> ledger.recordPlaceChange(putHereDuring, t.s9(), t.user(), InventoryLedger.PlaceRef.lookup(), null));

        var done = as(t, () -> counts.finish(c.id(), t.user()));
        assertThat(bin(movedSince)).as("moved elsewhere since its scan: not pulled back blindly").isEqualTo(t.z10s1());
        assertThat(bin(foundThenMoved)).isEqualTo(t.s10());
        assertThat(bin(putHereDuring)).as("put here during the count: not cleared").isEqualTo(t.s9());
        assertThat(status(departed)).isEqualTo("sold");
        assertThat(done.applied().skipped()).extracting(PlaceCountService.Skip::pieceId, PlaceCountService.Skip::reason)
            .containsExactlyInAnyOrder(tuple(movedSince, "moved_since"), tuple(departed, "departed"),
                tuple(foundThenMoved, "moved_since"), tuple(putHereDuring, "placed_since"));
        assertThat(done.applied().moved() + done.applied().placed() + done.applied().cleared()).isZero();
    }

    @Test
    void c5_cancel_changesNothing() {
        T t = tenant("c5", true);
        String elsewhere = piece(t, t.variant(), t.main(), "available", t.s10());
        piece(t, t.variant(), t.main(), "available", t.s9());
        var c = as(t, () -> counts.start(t.user(), t.s9(), null));
        scan(t, c.id(), code(elsewhere));
        Map<String, Object[]> before = snapshot(t);
        var cancelled = as(t, () -> counts.cancel(c.id(), t.user()));
        assertThat(cancelled.status()).isEqualTo("cancelled");
        assertThat(cancelled.applied().reason()).isEqualTo("cancelled");
        assertThat(snapshot(t)).isEqualTo(before);
        assertThat(placeEvents(t)).isZero();
        assertThatThrownBy(() -> scan(t, c.id(), code(elsewhere)))
            .extracting(e -> ((PlaceException) e).code()).isEqualTo(PlaceException.Code.COUNT_CLOSED);
    }

    @Test
    void c6_oneOpenCountPerPlace() {
        T t = tenant("c6", true);
        UUID worker = user(t, "worker");
        var first = as(t, () -> counts.start(t.user(), t.s9(), null));
        var again = as(t, () -> counts.start(worker, null, scanCode(t.s9())));
        assertThat(again.id()).as("Start on a place with an open count joins it").isEqualTo(first.id());
        assertThat(as(t, () -> counts.start(t.user(), t.s10(), null)).id()).isNotEqualTo(first.id());
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO place_counts (tenant_id, location_id, bin_id, opened_by) VALUES (?, ?, ?, ?)",
                t.tenant(), t.main(), t.s9(), worker))
            .hasMessageContaining("place_counts_one_open_per_place");
        as(t, () -> counts.cancel(first.id(), t.user()));
        assertThat(as(t, () -> counts.start(t.user(), t.s9(), null)).id()).isNotEqualTo(first.id());
    }

    @Test
    void c7_idleCount_cancelledByTheJob_nothingChanges() {
        T t = tenant("c7", true);
        String elsewhere = piece(t, t.variant(), t.main(), "available", t.s10());
        var idle = as(t, () -> counts.start(t.user(), t.s9(), null));
        scan(t, idle.id(), code(elsewhere));
        var fresh = as(t, () -> counts.start(t.user(), t.s10(), null));
        jdbc.update("UPDATE place_counts SET last_activity_at = now() - interval '13 hours' WHERE id = ?", idle.id());
        Map<String, Object[]> before = snapshot(t);

        assertThat(idleJob.processTenant(t.tenant())).containsExactly(idle.id());
        var after = as(t, () -> counts.get(idle.id()));
        assertThat(after.status()).isEqualTo("cancelled");
        assertThat(after.applied().reason()).isEqualTo("idle");
        assertThat(as(t, () -> counts.get(fresh.id())).status()).isEqualTo("open");
        assertThat(snapshot(t)).isEqualTo(before);
    }

    @Test
    void c8_startRefusals_andPlacesOff404() {
        T t = tenant("c8", true);
        assertThatThrownBy(() -> as(t, () -> counts.start(t.user(), null, scanCode(t.zone()))))
            .extracting(e -> ((PlaceException) e).code()).isEqualTo(PlaceException.Code.NOT_A_SHELF_OR_BOX);
        assertThatThrownBy(() -> as(t, () -> counts.start(t.user(), t.rack(), null)))
            .extracting(e -> ((PlaceException) e).code()).isEqualTo(PlaceException.Code.NOT_A_SHELF_OR_BOX);
        assertThatThrownBy(() -> as(t, () -> counts.start(t.user(), t.archived(), null)))
            .extracting(e -> ((PlaceException) e).code()).isEqualTo(PlaceException.Code.PLACE_ARCHIVED);
        assertThatThrownBy(() -> as(t, () -> counts.start(t.user(), null, "Q9999999")))
            .extracting(e -> ((PlaceException) e).code()).isEqualTo(PlaceException.Code.UNKNOWN_STICKER);
        assertThat(as(t, () -> counts.start(t.user(), t.box12(), null)).place().code()).as("a box can be counted").isEqualTo("BOX-12");

        T off = tenant("c8off", false);
        UUID anyCount = UUID.randomUUID();
        for (Callable<?> call : List.<Callable<?>>of(
                () -> counts.start(off.user(), off.s9(), null), () -> counts.get(anyCount),
                () -> counts.scan(anyCount, "P1", off.user(), false), () -> counts.finish(anyCount, off.user()),
                () -> counts.cancel(anyCount, off.user()), () -> whereIs.where(off.variant()),
                () -> boxes.checkBox(scanCode(off.box07())), () -> boxes.move(scanCode(off.box07()), scanCode(off.s9()), off.user()))) {
            assertThatThrownBy(() -> as(off, call))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getStatusCode().value()).isEqualTo(404));
        }
        assertThat(jdbc.queryForObject("SELECT count(*)::int FROM place_counts WHERE tenant_id = ?", Integer.class, off.tenant()))
            .isZero();
    }

    @Test
    void c9_sharedCount_recordsWhoScannedEachPiece_andWhoFinished() {
        T t = tenant("c9", true);
        UUID worker = user(t, "worker");
        String a = piece(t, t.variant(), t.main(), "available", t.s9());
        String b = piece(t, t.variant(), t.main(), "available", t.s10());
        var c = as(t, () -> counts.start(t.user(), t.s9(), null));
        as(t, () -> counts.scan(c.id(), code(a), t.user(), false));
        as(t, () -> counts.start(worker, t.s9(), null));                       // the worker joins the same count
        as(t, () -> counts.scan(c.id(), code(b), worker, true));
        assertThat(jdbc.queryForList("SELECT piece_id, scanned_by FROM place_count_scans WHERE count_id = ? ORDER BY scanned_at",
                c.id()))
            .extracting(r -> r.get("piece_id"), r -> r.get("scanned_by"))
            .containsExactly(tuple(a, t.user()), tuple(b, worker));
        var view = as(t, () -> counts.get(c.id()));
        assertThat(view.scannedBy()).containsExactly("Owner", "U");
        assertThat(view.movedLines()).extracting(PlaceCountService.CountLine::scannedByName).containsExactly("U");

        var done = as(t, () -> counts.finish(c.id(), worker));
        assertThat(jdbc.queryForObject("SELECT finished_by FROM place_counts WHERE id = ?", UUID.class, c.id())).isEqualTo(worker);
        assertThat(done.finishedByName()).isEqualTo("U");
        assertThat(done.scannedBy()).containsExactly("Owner", "U");
    }

    // ── st: the full stock take ────────────────────────────────────────────────

    @Test
    void st1_stockTake_placeStickerSetsTarget_piecesAfterItArePutThere() {
        T t = tenant("st1", true);
        String before = piece(t, t.variant(), t.main(), "available", null);
        String a = piece(t, t.variant(), t.main(), "available", null);
        String b = piece(t, t.variant(), t.main(), "available", t.s10());
        String c = piece(t, t.variant(), t.main(), "available", t.s9());
        UUID session = openStockTake(t);

        assertThat(stScan(t, session, code(before)).get("placed")).as("no sticker yet: no place").isNull();
        var set = stScan(t, session, scanCode(t.s9()));
        assertThat(set.get("classification")).isEqualTo("place_set");
        assertThat(((PutAwayService.PlaceView) set.get("place")).code()).isEqualTo("Z2-R01-S9");
        assertThat(jdbc.queryForObject("SELECT count(*)::int FROM stock_take_scans WHERE session_id = ?", Integer.class, session))
            .as("a sticker is not a counted scan").isEqualTo(1);

        var ra = stScan(t, session, code(a));
        assertThat(ra.get("classification")).as("the count itself is unchanged").isEqualTo("match");
        assertThat(placed(ra)).isEqualTo("PLACED");
        assertThat(placed(stScan(t, session, code(b)))).isEqualTo("MOVED");
        assertThat(placed(stScan(t, session, code(c)))).isEqualTo("ALREADY_HERE");
        assertThat(bin(before)).isNull();
        assertThat(bin(a)).isEqualTo(t.s9());
        assertThat(bin(b)).isEqualTo(t.s9());
        assertThat(jdbc.queryForList("SELECT DISTINCT metadata->>'source' FROM piece_events WHERE piece_id IN (?, ?)",
            String.class, a, b)).containsExactly("stock_take");
        assertThat(as(t, () -> stockTake.getSessionDetail(session)).get("countingPlace"))
            .extracting(p -> ((PutAwayService.PlaceView) p).code()).isEqualTo("Z2-R01-S9");

        stScan(t, session, scanCode(t.box12()));
        String d = piece(t, t.variant(), t.main(), "available", null);
        stScan(t, session, code(d));
        assertThat(bin(d)).as("the next sticker switches the place").isEqualTo(t.box12());
    }

    @Test
    void st2_stockTake_noSticker_placesUntouched_andPlacesOffStickerIsUnknownAsBefore() {
        T t = tenant("st2", true);
        String a = piece(t, t.variant(), t.main(), "available", null);
        String b = piece(t, t.variant(), t.main(), "available", t.s10());
        UUID session = openStockTake(t);
        assertThat(stScan(t, session, code(a))).doesNotContainKey("placed");
        assertThat(stScan(t, session, code(b))).doesNotContainKey("placed");
        assertThat(bin(a)).isNull();
        assertThat(bin(b)).isEqualTo(t.s10());
        assertThat(placeEvents(t)).isZero();
        assertThat(as(t, () -> stockTake.getSessionDetail(session))).doesNotContainKey("countingPlace");

        T off = tenant("st2off", false);
        UUID offSession = openStockTake(off);
        var r = stScan(off, offSession, scanCode(off.s9()));
        assertThat(r.get("classification")).as("without places a sticker is an unknown scan, as before").isEqualTo("unknown");
        assertThat(jdbc.queryForObject("SELECT current_bin_id FROM stock_take_sessions WHERE id = ?", UUID.class, offSession)).isNull();
    }

    @Test
    void st3_stockTake_stickerRefusals_keepTheCountedPlace() {
        T t = tenant("st3", true);
        UUID session = openStockTake(t);
        stScan(t, session, scanCode(t.s9()));
        for (Map.Entry<UUID, String> refused : List.of(Map.entry(t.otherShelf(), "PLACE_OTHER_LOCATION"),
                Map.entry(t.rack(), "SCAN_SHELF_OR_BOX"), Map.entry(t.archived(), "PLACE_ARCHIVED"))) {
            var r = stScan(t, session, scanCode(refused.getKey()));
            assertThat(r.get("classification")).isEqualTo("place_refused");
            assertThat(r.get("placeRefusal")).isEqualTo(refused.getValue());
        }
        assertThat(stScan(t, session, "Q9999999").get("placeRefusal")).isEqualTo("UNKNOWN_STICKER");
        assertThat(jdbc.queryForObject("SELECT current_bin_id FROM stock_take_sessions WHERE id = ?", UUID.class, session))
            .isEqualTo(t.s9());
    }

    // ── w: Where is it ─────────────────────────────────────────────────────────

    @Test
    void w1_whereIsIt_perLocation_perPlace_perStatus_walkingOrder_noPlace() {
        T t = tenant("w1", true);
        piece(t, t.variant(), t.main(), "available", t.s10());
        piece(t, t.variant(), t.main(), "available", t.s10());
        piece(t, t.variant(), t.main(), "on_hold", t.s9());
        piece(t, t.variant(), t.main(), "damaged", t.z10s1());
        piece(t, t.variant(), t.main(), "available", null);
        piece(t, t.variant(), t.main(), "sold", null);
        piece(t, t.variant(), t.main(), "reserved", null);
        piece(t, t.variant(), t.main(), "lost", null);
        piece(t, t.variant(), t.other(), "available", t.otherShelf());
        piece(t, t.variant2(), t.main(), "available", t.s9());                      // another variant

        var w = as(t, () -> whereIs.where(t.variant()));
        assertThat(w.locations()).extracting(WhereIsItService.LocationStock::locationName)
            .containsExactly("Main Warehouse", "Second Store");
        var main = w.locations().get(0);
        assertThat(main.places()).extracting(WhereIsItService.PlaceStock::code)
            .as("walking order: S9 before S10, Z2 before Z10").containsExactly("Z2-R01-S9", "Z2-R01-S10", "Z10-R01-S1");
        assertThat(main.places()).extracting(p -> p.counts().available(), p -> p.counts().onHold(), p -> p.counts().damaged())
            .containsExactly(tuple(0, 1, 0), tuple(2, 0, 0), tuple(0, 0, 1));
        assertThat(main.noPlace().total()).as("sold / reserved / lost left out").isEqualTo(1);
        assertThat(main.total()).isEqualTo(5);
        assertThat(w.locations().get(1).places()).extracting(WhereIsItService.PlaceStock::code).containsExactly("Z1-R01-S1");
    }

    @Test
    void w2_whereIsIt_oneCountingStatement_for3And30Places() {
        T t = tenant("w2", true);
        List<String> statements = new CopyOnWriteArrayList<>();
        WhereIsItService counted = new WhereIsItService(new JdbcTemplate(counting(dataSource, statements)));
        List<UUID> shelves = new ArrayList<>();
        for (int i = 0; i < 30; i++) shelves.add(bin(t.tenant(), t.main(), "shelf", t.rack(), "rack", "Z2-R01-X" + i));
        for (int i = 0; i < 3; i++) piece(t, t.variant(), t.main(), "available", shelves.get(i));
        as(t, () -> counted.where(t.variant()));
        int small = statements.size();
        for (int i = 3; i < 30; i++) piece(t, t.variant(), t.main(), "available", shelves.get(i));
        statements.clear();
        var w = as(t, () -> counted.where(t.variant()));
        assertThat(w.locations().get(0).places()).hasSize(30);
        assertThat(statements).as("the same statements whatever the number of places").hasSize(small);
        assertThat(statements.stream().filter(s -> s.contains("FROM pieces")).count()).isEqualTo(1);
    }

    // ── b: Move a box ──────────────────────────────────────────────────────────

    @Test
    void b1_moveABox_shelfToFloorToShelf_binEvents_piecesFollow() {
        T t = tenant("b1", true);
        String inBox = piece(t, t.variant(), t.main(), "available", t.box12());
        assertThat(as(t, () -> boxes.checkBox(scanCode(t.box12()))).pieces()).isEqualTo(1);

        var toFloor = as(t, () -> boxes.move(scanCode(t.box12()), scanCode(t.zone()), t.user()));
        assertThat(toFloor.outcome()).isEqualTo("MOVED");
        assertThat(toFloor.from().code()).isEqualTo("Z2-R01-S9");
        assertThat(toFloor.to().code()).isEqualTo("Z2");
        assertThat(parent(t.box12())).isEqualTo(t.zone());
        var toShelf = as(t, () -> boxes.move(scanCode(t.box12()), scanCode(t.z10s1()), t.user()));
        assertThat(toShelf.box().trail()).as("the box's path follows").containsExactly("Z10", "R01", "S1", "BOX-12");
        assertThat(bin(inBox)).as("the piece keeps its box").isEqualTo(t.box12());
        assertThat(PlacePaths.describe(jdbc, t.tenant(), bin(inBox)).trail()).containsExactly("Z10", "R01", "S1", "BOX-12");

        assertThat(jdbc.queryForList(
                "SELECT event_type, actor_user_id, from_parent_id, to_parent_id FROM bin_events WHERE bin_id = ? " +
                "AND event_type = 'moved' ORDER BY occurred_at, id", t.box12()))
            .extracting(r -> r.get("actor_user_id"), r -> r.get("from_parent_id"), r -> r.get("to_parent_id"))
            .containsExactly(tuple(t.user(), t.s9(), t.zone()), tuple(t.user(), t.zone(), t.z10s1()));
        assertThat(as(t, () -> boxes.move(scanCode(t.box12()), scanCode(t.z10s1()), t.user())).outcome())
            .isEqualTo("ALREADY_THERE");
        assertThat(jdbc.queryForObject("SELECT count(*)::int FROM bin_events WHERE bin_id = ? AND event_type = 'moved'",
            Integer.class, t.box12())).isEqualTo(2);
        assertThat(placeEvents(t)).as("no piece row changes").isZero();
    }

    @Test
    void b2_moveABox_refusals() {
        T t = tenant("b2", true);
        record Case(String box, String target, PlaceException.Code code) {}
        jdbc.update("UPDATE bins SET archived_at = now() WHERE id = ?", t.box07());
        for (Case c : List.of(
                new Case(scanCode(t.s9()), scanCode(t.s10()), PlaceException.Code.NOT_A_BOX),
                new Case(scanCode(t.box07()), scanCode(t.s10()), PlaceException.Code.PLACE_ARCHIVED),
                new Case(scanCode(t.box12()), scanCode(t.archived()), PlaceException.Code.PLACE_ARCHIVED),
                new Case(scanCode(t.box12()), scanCode(t.rack()), PlaceException.Code.BOX_TARGET),
                new Case(scanCode(t.box12()), scanCode(t.box07()), PlaceException.Code.BOX_TARGET),
                new Case(scanCode(t.box12()), scanCode(t.box12()), PlaceException.Code.BOX_ONTO_ITSELF),
                new Case(scanCode(t.box12()), scanCode(t.otherShelf()), PlaceException.Code.OTHER_LOCATION),
                new Case(scanCode(t.box12()), "Q9999999", PlaceException.Code.UNKNOWN_STICKER))) {
            assertThatThrownBy(() -> as(t, () -> boxes.move(c.box(), c.target(), t.user())))
                .as(c.code().name()).extracting(e -> ((PlaceException) e).code()).isEqualTo(c.code());
        }
        assertThat(parent(t.box12())).isEqualTo(t.s9());
        assertThat(jdbc.queryForObject("SELECT count(*)::int FROM bin_events WHERE tenant_id = ? AND event_type = 'moved'",
            Integer.class, t.tenant())).isZero();
    }

    // ── x: RLS ─────────────────────────────────────────────────────────────────

    @Test
    void x1_appUser_count_startScanFinishCancel_underRls() {
        T mine = tenant("x1a", true), theirs = tenant("x1b", true);
        DriverManagerDataSource raw = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "app_user", "testpw");
        TenantAwareDataSource ds = new TenantAwareDataSource(raw);
        JdbcTemplate app = new JdbcTemplate(ds);
        TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        PlaceCountService appCounts = new PlaceCountService(app, new InventoryLedger(app));
        String elsewhere = piece(mine, mine.variant(), mine.main(), "available", mine.s10());
        String missing = piece(mine, mine.variant(), mine.main(), "available", mine.s9());
        var theirCount = as(theirs, () -> counts.start(theirs.user(), theirs.s9(), null));

        TenantContext.set(mine.tenant());
        try {
            var c = tx.execute(s -> appCounts.start(mine.user(), mine.s9(), null));
            assertThat(tx.execute(s -> appCounts.start(mine.user(), mine.s9(), null)).id())
                .as("the ON CONFLICT join works on app_user's grants").isEqualTo(c.id());
            assertThat(tx.execute(s -> appCounts.scan(c.id(), code(elsewhere), mine.user(), true)).outcome()).isEqualTo("MOVE_HERE");
            var done = tx.execute(s -> appCounts.finish(c.id(), mine.user()));
            assertThat(done.applied().moved()).isEqualTo(1);
            assertThat(done.applied().cleared()).isEqualTo(1);
            var c2 = tx.execute(s -> appCounts.start(mine.user(), mine.s10(), null));
            assertThat(tx.execute(s -> appCounts.cancel(c2.id(), mine.user())).status()).isEqualTo("cancelled");
            assertThatThrownBy(() -> tx.execute(s -> appCounts.get(theirCount.id())))
                .extracting(e -> ((PlaceException) e).code()).isEqualTo(PlaceException.Code.COUNT_NOT_FOUND);
            assertThatThrownBy(() -> tx.execute(s -> appCounts.scan(theirCount.id(), "P1", mine.user(), false)))
                .extracting(e -> ((PlaceException) e).code()).isEqualTo(PlaceException.Code.COUNT_NOT_FOUND);
        } finally {
            TenantContext.clear();
        }
        assertThat(bin(elsewhere)).isEqualTo(mine.s9());
        assertThat(bin(missing)).isNull();
        assertThat(jdbc.queryForObject("SELECT via_phone FROM place_count_scans WHERE piece_id = ?", Boolean.class, elsewhere)).isTrue();
        assertThat(as(theirs, () -> counts.get(theirCount.id())).status()).as("theirs untouched").isEqualTo("open");
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private <R> R as(T t, Callable<R> call) {
        return TenantContext.runAs(t.tenant(), call);
    }

    private PlaceCountService.ScanResult scan(T t, UUID count, String raw) {
        return as(t, () -> counts.scan(count, raw, t.user(), false));
    }

    private UUID openStockTake(T t) {
        Map<String, Object> s = as(t, () -> stockTake.openSession("all", null, t.main(), null, t.user()));
        return (UUID) s.get("sessionId");
    }

    private Map<String, Object> stScan(T t, UUID session, String raw) {
        return as(t, () -> stockTake.scan(session, raw, "good", t.user(), false));
    }

    @SuppressWarnings("unchecked")
    private static String placed(Map<String, Object> scan) {
        return (String) ((Map<String, Object>) scan.get("placed")).get("outcome");
    }

    /** Every piece's (bin, status, location) — what a count must not touch before Finish. */
    private Map<String, Object[]> snapshot(T t) {
        Map<String, Object[]> out = new TreeMap<>();
        jdbc.query("SELECT id, bin_id, status::text AS status, current_location_id FROM pieces WHERE tenant_id = ?",
            rs -> { out.put(rs.getString("id"), new Object[] {rs.getObject("bin_id"), rs.getString("status"),
                rs.getObject("current_location_id")}); }, t.tenant());
        return new TreeMap<>(out) {
            @Override public boolean equals(Object o) {
                if (!(o instanceof Map<?, ?> m) || m.size() != size()) return false;
                for (var e : entrySet()) if (!Arrays.equals(e.getValue(), (Object[]) m.get(e.getKey()))) return false;
                return true;
            }
        };
    }

    private int placeEvents(T t) {
        return jdbc.queryForObject("SELECT count(*)::int FROM piece_events WHERE tenant_id = ? " +
            "AND event_type IN ('put_away', 'place_moved', 'place_cleared')", Integer.class, t.tenant());
    }

    private Integer shopifyClaims(T t) {
        return jdbc.queryForObject("SELECT count(*)::int FROM shopify_inventory_adjustments WHERE tenant_id = ?",
            Integer.class, t.tenant());
    }

    private UUID bin(String pieceId) {
        return jdbc.queryForObject("SELECT bin_id FROM pieces WHERE id = ?", UUID.class, pieceId);
    }

    private String status(String pieceId) {
        return jdbc.queryForObject("SELECT status::text FROM pieces WHERE id = ?", String.class, pieceId);
    }

    private UUID parent(UUID binId) {
        return jdbc.queryForObject("SELECT parent_id FROM bins WHERE id = ?", UUID.class, binId);
    }

    private T tenant(String name, boolean placesOn) {
        UUID tenant = UUID.randomUUID(), user = UUID.randomUUID(), store = UUID.randomUUID(), product = UUID.randomUUID();
        UUID v1 = UUID.randomUUID(), v2 = UUID.randomUUID(), main = UUID.randomUUID(), other = UUID.randomUUID();
        String tag = name + "-" + tenant.toString().substring(0, 6);
        jdbc.update("INSERT INTO tenants (id, name, places_enabled) VALUES (?, ?, ?)", tenant, "PlacesS5 " + name, placesOn);
        jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role) VALUES (?, ?, 'Owner', ?, 'h', 'owner')",
            user, tenant, tag + "@placess5.test");
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status) VALUES (?, ?, 'shopify', ?, 'disconnected')",
            store, tenant, tag + ".myshopify.com");
        jdbc.update("INSERT INTO products (id, tenant_id, store_id, external_id, title, status) VALUES (?, ?, ?, ?, 'Tee', 'active')",
            product, tenant, store, "P-" + tag);
        jdbc.update("INSERT INTO variants (id, tenant_id, product_id, external_id, title, sku) VALUES (?, ?, ?, ?, 'M', ?)",
            v1, tenant, product, "V1-" + tag, "SKU1-" + tag);
        jdbc.update("INSERT INTO variants (id, tenant_id, product_id, external_id, title, sku) VALUES (?, ?, ?, ?, 'L', ?)",
            v2, tenant, product, "V2-" + tag, "SKU2-" + tag);
        jdbc.update("INSERT INTO locations (id, tenant_id, name, is_default, is_fulfillment) VALUES (?, ?, 'Main Warehouse', true, true)",
            main, tenant);
        jdbc.update("INSERT INTO locations (id, tenant_id, name) VALUES (?, ?, 'Second Store')", other, tenant);
        UUID z2 = bin(tenant, main, "zone", null, null, "Z2");
        UUID r2 = bin(tenant, main, "rack", z2, "zone", "Z2-R01");
        UUID s9 = bin(tenant, main, "shelf", r2, "rack", "Z2-R01-S9");
        UUID s10 = bin(tenant, main, "shelf", r2, "rack", "Z2-R01-S10");
        UUID s11 = bin(tenant, main, "shelf", r2, "rack", "Z2-R01-S11");
        jdbc.update("UPDATE bins SET archived_at = now() WHERE id = ?", s11);
        UUID box12 = bin(tenant, main, "box", s9, "shelf", "BOX-12");
        UUID box07 = bin(tenant, main, "box", z2, "zone", "BOX-07");
        UUID z10 = bin(tenant, main, "zone", null, null, "Z10");
        UUID r10 = bin(tenant, main, "rack", z10, "zone", "Z10-R01");
        UUID z10s1 = bin(tenant, main, "shelf", r10, "rack", "Z10-R01-S1");
        UUID oz = bin(tenant, other, "zone", null, null, "Z1");
        UUID or = bin(tenant, other, "rack", oz, "zone", "Z1-R01");
        UUID os = bin(tenant, other, "shelf", or, "rack", "Z1-R01-S1");
        UUID ob = bin(tenant, other, "box", oz, "zone", "BOX-90");
        return new T(tenant, user, main, other, v1, v2, z2, r2, s9, s10, box12, box07, z10s1, s11, os, ob);
    }

    private UUID user(T t, String role) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role) VALUES (?, ?, 'U', ?, 'h', ?::user_role)",
            id, t.tenant(), id + "@placess5.test", role);
        return id;
    }

    private UUID bin(UUID tenant, UUID location, String kind, UUID parent, String parentKind, String code) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO bins (id, tenant_id, location_id, kind, parent_id, parent_kind, code, scan_code) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            id, tenant, location, kind, parent, parentKind, code, String.format("Q%07d", 6_000_000 + seq.incrementAndGet()));
        return id;
    }

    private String scanCode(UUID binId) {
        return jdbc.queryForObject("SELECT scan_code FROM bins WHERE id = ?", String.class, binId);
    }

    /** A tagged piece; {@code bin} set directly (a fixture, before any count starts — no place event). */
    private String piece(T t, UUID variant, UUID location, String status, UUID bin) {
        int k = seq.incrementAndGet();
        String id = "01PS5" + UUID.randomUUID().toString().replace("-", "").substring(0, 21).toUpperCase();
        jdbc.update("INSERT INTO pieces (id, tenant_id, variant_id, status, barcode, short_code, current_location_id, bin_id) " +
                "VALUES (?, ?, ?, ?::piece_status, ?, ?, ?, ?)",
            id, t.tenant(), variant, status, "PC-" + id, String.format("P%07d", 7_000_000 + k), location, bin);
        return id;
    }

    private String untaggedPiece(T t) {
        String id = "01PSU" + UUID.randomUUID().toString().replace("-", "").substring(0, 21).toUpperCase();
        jdbc.update("INSERT INTO pieces (id, tenant_id, variant_id, status, barcode, current_location_id) " +
            "VALUES (?, ?, ?, 'available', ?, ?)", id, t.tenant(), t.variant(), "PC-" + id, t.main());
        return id;
    }

    private String code(String pieceId) {
        return jdbc.queryForObject("SELECT short_code FROM pieces WHERE id = ?", String.class, pieceId);
    }

    private static DataSource counting(DataSource target, List<String> statements) {
        return (DataSource) Proxy.newProxyInstance(DataSource.class.getClassLoader(), new Class<?>[] {DataSource.class},
            (proxy, m, args) -> {
                Object out = m.invoke(target, args);
                if (!(out instanceof Connection con)) return out;
                return Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[] {Connection.class},
                    (p2, m2, a2) -> {
                        if ((m2.getName().equals("prepareStatement") || m2.getName().equals("prepareCall"))
                                && a2 != null && a2[0] instanceof String sql) statements.add(sql);
                        try {
                            return m2.invoke(con, a2);
                        } catch (java.lang.reflect.InvocationTargetException e) {
                            throw e.getCause();
                        }
                    });
            });
    }
}
