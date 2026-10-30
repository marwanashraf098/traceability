package com.traceability.places;

import com.traceability.TestPostgres;
import com.traceability.inventory.InventoryLedger;
import com.traceability.inventory.LookupService;
import com.traceability.inventory.PlaceChangeException;
import com.traceability.tenancy.TenantAwareDataSource;
import com.traceability.tenancy.TenantContext;
import org.jobrunr.scheduling.JobScheduler;
import org.junit.jupiter.api.AfterEach;
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

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Warehouse places S2 (V195) — put away.
 *
 *   l1  recordPlaceChange: put_away / place_moved / place_cleared with from / to bins, status unchanged, metadata;
 *       the same place again (and clearing no place) writes nothing
 *   l2  every refusal writes nothing: untagged, each non-placeable status, zone, rack, archived, another location's
 *       place, a piece with no location, another tenant's place, an unknown piece
 *   s1  session flow: piece before a place, zone / rack sticker, unknown sticker, set place (q / ض), put, already
 *       here, switch place, move from a session place and from a place set elsewhere, other location (piece and
 *       place), status, untagged, archived; totals; close summary; closed / someone else's session refused
 *   s2  via phone lands in the event metadata; Start resumes the open session
 *   s3  one open session per person per location: Start / the waiting list resume it; another location or another
 *       person gets its own; resuming restarts the idle clock
 *   s4  an owner / manager closes anyone's open session (closed_by_manager, summary, pieces stay placed); a worker
 *       can't close someone else's; the opener's own close is 'finished'; the open-sessions list
 *   s5  idle close: no scan for 12 h → closed 'idle' by the system with its summary, pieces stay placed; 11 h → open;
 *       a scan restarts the clock; a closed session refuses scans
 *   w1  waiting list: source per latest relevant event, counts per source, filter, since, return order number,
 *       only tagged placeable pieces with no place at that location
 *   g1  suggestion order: last place beats most-of-variant; an archived / other-location last place is skipped;
 *       most-of-variant counts available pieces at this location only; nothing → no suggestion
 *   k1  Lookup: place + path, canPlace, Move by sticker / by pick / No place, timeline phrase keys with places,
 *       a box's moves since the piece went in; a scanned place sticker answers "place"
 *   f1  switch off: every method 404, Lookup unchanged
 *   x1  app_user: put_away_sessions / put_away_session_pieces tenant-scoped, same-tenant positive control; grants
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PutAwayTest {

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

    @Autowired JdbcTemplate    jdbc;
    @Autowired InventoryLedger ledger;
    @Autowired PutAwayService  putAway;
    @Autowired LookupService   lookup;
    @Autowired javax.sql.DataSource dataSource;
    /** Built here: the job bean exists only where JobRunr's background server runs (not in this test context). */
    PutAwayIdleCloseJob idleJob;

    @org.junit.jupiter.api.BeforeAll
    void job() { idleJob = new PutAwayIdleCloseJob(putAway, dataSource); }

    /** A tenant with two locations; at main: zone Z1 › rack Z1-R01 › shelves S1 / S2, box BOX-01 in Z1. */
    record T(UUID tenant, UUID user, UUID main, UUID other, UUID variant, UUID variant2,
             UUID zone, UUID rack, UUID s1, UUID s2, UUID box, UUID otherShelf) {}

    final AtomicInteger seq = new AtomicInteger();

    @AfterEach
    void clear() { TenantContext.clear(); }

    // ── l: the ledger writer ───────────────────────────────────────────────────

    @Test
    void l1_recordPlaceChange_putMoveClear_andAlreadyHereWritesNothing() {
        T t = tenant("l1", true);
        String p = piece(t, t.main(), "on_hold");

        var put = as(t, () -> ledger.recordPlaceChange(p, t.s1(), t.user(), InventoryLedger.PlaceRef.putAway(null, false), "P1"));
        assertThat(put.kind()).isEqualTo(InventoryLedger.PlaceChangeKind.PUT_AWAY);
        assertThat(binOf(p)).isEqualTo(t.s1());
        Map<String, Object> e1 = lastEvent(p);
        assertThat(e1.get("event_type")).isEqualTo("put_away");
        assertThat(e1.get("from_status")).isEqualTo("on_hold");
        assertThat(e1.get("to_status")).as("a place is not a status").isEqualTo("on_hold");
        assertThat(e1.get("from_bin_id")).isNull();
        assertThat(e1.get("to_bin_id")).isEqualTo(t.s1());
        assertThat(e1.get("location_id")).isEqualTo(t.main());
        assertThat(e1.get("raw_scan")).isEqualTo("P1");
        assertThat(e1.get("metadata").toString()).contains("\"source\": \"put_away\"").doesNotContain("via_phone");
        assertThat(status(p)).isEqualTo("on_hold");

        var moved = as(t, () -> ledger.recordPlaceChange(p, t.box(), t.user(), InventoryLedger.PlaceRef.lookup(), null));
        assertThat(moved.kind()).isEqualTo(InventoryLedger.PlaceChangeKind.MOVED);
        Map<String, Object> e2 = lastEvent(p);
        assertThat(e2.get("event_type")).isEqualTo("place_moved");
        assertThat(e2.get("from_bin_id")).isEqualTo(t.s1());
        assertThat(e2.get("to_bin_id")).isEqualTo(t.box());
        assertThat(e2.get("metadata").toString()).contains("\"source\": \"lookup\"");

        int events = eventCount(p);
        var again = as(t, () -> ledger.recordPlaceChange(p, t.box(), t.user(), InventoryLedger.PlaceRef.lookup(), null));
        assertThat(again.kind()).isEqualTo(InventoryLedger.PlaceChangeKind.ALREADY_HERE);
        assertThat(eventCount(p)).as("already here writes nothing").isEqualTo(events);

        var cleared = as(t, () -> ledger.recordPlaceChange(p, null, t.user(), InventoryLedger.PlaceRef.lookup(), null));
        assertThat(cleared.kind()).isEqualTo(InventoryLedger.PlaceChangeKind.CLEARED);
        assertThat(binOf(p)).isNull();
        Map<String, Object> e3 = lastEvent(p);
        assertThat(e3.get("event_type")).isEqualTo("place_cleared");
        assertThat(e3.get("from_bin_id")).isEqualTo(t.box());
        assertThat(e3.get("to_bin_id")).isNull();

        int afterClear = eventCount(p);
        var clearAgain = as(t, () -> ledger.recordPlaceChange(p, null, t.user(), InventoryLedger.PlaceRef.lookup(), null));
        assertThat(clearAgain.kind()).isEqualTo(InventoryLedger.PlaceChangeKind.ALREADY_HERE);
        assertThat(eventCount(p)).isEqualTo(afterClear);
        assertThat(status(p)).as("status never changed").isEqualTo("on_hold");
    }

    @Test
    void l2_everyRefusal_writesNothing() {
        T t = tenant("l2", true);
        T stranger = tenant("l2x", true);

        String untagged = untaggedPiece(t, t.main());
        refused(t, untagged, t.s1(), PlaceChangeException.Code.UNTAGGED);

        for (String st : List.of("reserved", "packed", "with_courier", "delivered", "lost", "sold", "return_pending_inspection")) {
            refused(t, piece(t, t.main(), st), t.s1(), PlaceChangeException.Code.STATUS_NOT_PLACEABLE);
        }
        String ok = piece(t, t.main(), "available");
        refused(t, ok, t.zone(), PlaceChangeException.Code.NOT_A_SHELF_OR_BOX);
        refused(t, ok, t.rack(), PlaceChangeException.Code.NOT_A_SHELF_OR_BOX);
        UUID archived = bin(t, t.main(), "box", t.zone(), "zone", "BOX-99");
        jdbc.update("UPDATE bins SET archived_at = now() WHERE id = ?", archived);
        refused(t, ok, archived, PlaceChangeException.Code.PLACE_ARCHIVED);
        refused(t, ok, t.otherShelf(), PlaceChangeException.Code.OTHER_LOCATION);
        refused(t, ok, stranger.s1(), PlaceChangeException.Code.PLACE_NOT_FOUND);
        refused(t, piece(t, null, "available"), t.s1(), PlaceChangeException.Code.NO_LOCATION);
        refused(t, "01NOSUCHPIECE0000000000000", t.s1(), PlaceChangeException.Code.PIECE_NOT_FOUND);
        refused(t, piece(stranger, stranger.main(), "available"), t.s1(), PlaceChangeException.Code.PIECE_NOT_FOUND);

        // a placed piece refused a bad target keeps its place
        as(t, () -> ledger.recordPlaceChange(ok, t.s1(), t.user(), InventoryLedger.PlaceRef.lookup(), null));
        refused(t, ok, t.zone(), PlaceChangeException.Code.NOT_A_SHELF_OR_BOX);
        assertThat(binOf(ok)).isEqualTo(t.s1());
    }

    private void refused(T t, String pieceId, UUID binId, PlaceChangeException.Code code) {
        int before = jdbc.queryForObject("SELECT count(*)::int FROM piece_events WHERE piece_id = ?", Integer.class, pieceId);
        UUID binBefore = binOrNull(pieceId);
        assertThatThrownBy(() -> as(t, () -> ledger.recordPlaceChange(pieceId, binId, t.user(),
                InventoryLedger.PlaceRef.lookup(), null)))
            .isInstanceOf(PlaceChangeException.class)
            .extracting(e -> ((PlaceChangeException) e).code()).isEqualTo(code);
        assertThat(jdbc.queryForObject("SELECT count(*)::int FROM piece_events WHERE piece_id = ?", Integer.class, pieceId))
            .as(code + ": no event").isEqualTo(before);
        assertThat(binOrNull(pieceId)).as(code + ": place unchanged").isEqualTo(binBefore);
    }

    /** The piece's place; null for no place and for a piece that doesn't exist. */
    private UUID binOrNull(String pieceId) {
        List<UUID> b = jdbc.queryForList("SELECT bin_id FROM pieces WHERE id = ?", UUID.class, pieceId);
        return b.isEmpty() ? null : b.get(0);
    }

    // ── s: sessions ────────────────────────────────────────────────────────────

    @Test
    void s1_sessionFlow_everyOutcome() {
        T t = tenant("s1", true);
        String a = piece(t, t.main(), "available"), b = piece(t, t.main(), "damaged"), c = piece(t, t.main(), "available");
        String elsewhere = piece(t, t.other(), "available");
        String reserved = piece(t, t.main(), "reserved");
        String untagged = untaggedPiece(t, t.main());
        UUID archivedBox = bin(t, t.main(), "box", t.zone(), "zone", "BOX-50");
        jdbc.update("UPDATE bins SET archived_at = now() WHERE id = ?", archivedBox);
        // c already sits on S2 from an earlier day
        as(t, () -> ledger.recordPlaceChange(c, t.s2(), t.user(), InventoryLedger.PlaceRef.lookup(), null));

        PutAwayService.Session s = as(t, () -> putAway.start(t.user(), null));
        assertThat(s.locationId()).isEqualTo(t.main());
        assertThat(s.current()).isNull();
        assertThat(s.stillWaiting()).as("a, b and the waiting untagged doesn't count").isEqualTo(2);

        assertThat(scan(t, s, code(a)).outcome()).isEqualTo("SCAN_PLACE_FIRST");
        assertThat(binOf(a)).isNull();
        assertThat(scan(t, s, scanCode(t.zone())).outcome()).isEqualTo("SCAN_SHELF_OR_BOX");
        assertThat(scan(t, s, scanCode(t.rack())).outcome()).isEqualTo("SCAN_SHELF_OR_BOX");
        assertThat(scan(t, s, "Q999999").outcome()).isEqualTo("UNKNOWN_STICKER");
        assertThat(scan(t, s, "hello").outcome()).isEqualTo("UNKNOWN_STICKER");
        var otherLoc = scan(t, s, scanCode(t.otherShelf()));
        assertThat(otherLoc.outcome()).isEqualTo("PLACE_OTHER_LOCATION");
        assertThat(otherLoc.locationName()).isEqualTo("Second Store");
        assertThat(scan(t, s, scanCode(archivedBox)).outcome()).isEqualTo("PLACE_ARCHIVED");

        var set = scan(t, s, scanCode(t.s1()).toLowerCase(Locale.ROOT));
        assertThat(set.outcome()).isEqualTo("PLACE_SET");
        assertThat(set.place().code()).isEqualTo("Z1-R01-S1");
        assertThat(set.place().trail()).containsExactly("Z1", "R01", "S1");
        assertThat(set.fromPlace()).isNull();

        var placed = scan(t, s, code(a));
        assertThat(placed.outcome()).isEqualTo("PLACED");
        assertThat(binOf(a)).isEqualTo(t.s1());
        assertThat(placed.session().current().putHereNow()).isEqualTo(1);
        assertThat(placed.session().current().onThisPlace()).isEqualTo(1);
        assertThat(lastEvent(a).get("metadata").toString()).contains("\"session_id\": \"" + s.id() + "\"");

        int events = eventCount(a);
        assertThat(scan(t, s, code(a)).outcome()).isEqualTo("ALREADY_HERE");
        assertThat(eventCount(a)).isEqualTo(events);

        assertThat(scan(t, s, code(b)).outcome()).as("damaged is placeable").isEqualTo("PLACED");

        // ض + the box's digits: the Arabic-layout Q key
        var sw = scan(t, s, "ض" + scanCode(t.box()).substring(1));
        assertThat(sw.outcome()).isEqualTo("PLACE_SET");
        assertThat(sw.place().code()).isEqualTo("BOX-01");
        assertThat(sw.fromPlace().code()).isEqualTo("Z1-R01-S1");

        var movedInSession = scan(t, s, code(a));
        assertThat(movedInSession.outcome()).isEqualTo("MOVED");
        assertThat(movedInSession.fromPlace().code()).isEqualTo("Z1-R01-S1");
        assertThat(binOf(a)).isEqualTo(t.box());
        var movedFromOld = scan(t, s, code(c));
        assertThat(movedFromOld.outcome()).isEqualTo("MOVED");
        assertThat(movedFromOld.fromPlace().code()).isEqualTo("Z1-R01-S2");

        var notHere = scan(t, s, code(elsewhere));
        assertThat(notHere.outcome()).isEqualTo("NOT_AT_THIS_LOCATION");
        assertThat(notHere.locationName()).isEqualTo("Second Store");
        var cannot = scan(t, s, code(reserved));
        assertThat(cannot.outcome()).isEqualTo("CANNOT_PLACE");
        assertThat(cannot.status()).isEqualTo("reserved");
        assertThat(scan(t, s, "PC-" + untagged).outcome()).isEqualTo("UNTAGGED");
        for (String p : List.of(elsewhere, reserved, untagged)) assertThat(binOf(p)).isNull();

        PutAwayService.Session now = as(t, () -> putAway.get(s.id(), t.user()));
        assertThat(now.placed()).as("a, b, c").isEqualTo(3);
        assertThat(now.placesUsed()).as("S1 (b) and BOX-01 (a, c)").isEqualTo(2);
        assertThat(now.stillWaiting()).isZero();
        assertThat(now.current().place().code()).isEqualTo("BOX-01");
        assertThat(now.current().putHereNow()).isEqualTo(2);

        PutAwayService.CloseSummary done = as(t, () -> putAway.close(s.id(), t.user()));
        assertThat(done.session().status()).isEqualTo("closed");
        assertThat(done.places()).extracting(u -> u.place().code() + "=" + u.pieces())
            .containsExactly("BOX-01=2", "Z1-R01-S1=1");
        assertThatThrownBy(() -> scan(t, s, code(a)))
            .extracting(e -> ((PlaceException) e).code()).isEqualTo(PlaceException.Code.SESSION_CLOSED);
        assertThat(as(t, () -> putAway.close(s.id(), t.user())).session().status()).as("close is idempotent").isEqualTo("closed");

        UUID colleague = user(t, "worker");
        assertThatThrownBy(() -> as(t, () -> putAway.get(s.id(), colleague)))
            .extracting(e -> ((PlaceException) e).code()).isEqualTo(PlaceException.Code.SESSION_NOT_FOUND);
    }

    @Test
    void s2_viaPhone_inMetadata_andStartResumes() {
        T t = tenant("s2", true);
        String p = piece(t, t.main(), "available");
        PutAwayService.Session s = as(t, () -> putAway.start(t.user(), t.main()));
        assertThat(as(t, () -> putAway.start(t.user(), null)).id()).as("resumes the open session").isEqualTo(s.id());
        as(t, () -> putAway.scan(s.id(), scanCode(t.s1()), t.user(), true));
        var r = as(t, () -> putAway.scan(s.id(), code(p), t.user(), true));
        assertThat(r.viaPhone()).isTrue();
        assertThat(lastEvent(p).get("metadata").toString()).contains("\"via_phone\": true");
        as(t, () -> putAway.close(s.id(), t.user()));
        assertThat(as(t, () -> putAway.start(t.user(), null)).id()).as("a closed session isn't resumed").isNotEqualTo(s.id());
    }

    @Test
    void s3_resume_onePerPersonPerLocation() {
        T t = tenant("s3", true);
        UUID colleague = user(t, "worker");
        PutAwayService.Session s = as(t, () -> putAway.start(t.user(), t.main()));
        assertThat(as(t, () -> putAway.waiting(t.main(), null, t.user())).mine().id()).as("the waiting list offers it").isEqualTo(s.id());
        assertThat(as(t, () -> putAway.waiting(t.main(), null, colleague)).mine()).as("not someone else's").isNull();
        jdbc.update("UPDATE put_away_sessions SET last_activity_at = now() - interval '5 hours' WHERE id = ?", s.id());
        assertThat(as(t, () -> putAway.start(t.user(), t.main())).id()).isEqualTo(s.id());
        assertThat(jdbc.queryForObject("SELECT last_activity_at > now() - interval '1 minute' FROM put_away_sessions WHERE id = ?",
            Boolean.class, s.id())).as("resuming restarts the idle clock").isTrue();
        assertThat(as(t, () -> putAway.start(t.user(), t.other())).id()).as("another location").isNotEqualTo(s.id());
        assertThat(as(t, () -> putAway.start(colleague, t.main())).id()).as("another person").isNotEqualTo(s.id());
        assertThat(jdbc.queryForObject("SELECT count(*)::int FROM put_away_sessions WHERE tenant_id = ? AND status = 'open'",
            Integer.class, t.tenant())).isEqualTo(3);
    }

    @Test
    @SuppressWarnings("unchecked")
    void s4_managerClosesAnyone_workerCannot() {
        T t = tenant("s4", true);
        UUID worker = user(t, "worker"), worker2 = user(t, "worker"), manager = user(t, "manager");
        String p = piece(t, t.main(), "available");
        PutAwayService.Session s = as(t, () -> putAway.start(worker, null));
        as(t, () -> putAway.scan(s.id(), scanCode(t.s1()), worker, false));
        as(t, () -> putAway.scan(s.id(), code(p), worker, false));

        List<PutAwayService.OpenSession> open = as(t, () -> putAway.openSessions());
        assertThat(open).extracting(PutAwayService.OpenSession::id).containsExactly(s.id());
        assertThat(open.get(0).placed()).isEqualTo(1);
        assertThat(open.get(0).openedBy()).isEqualTo(worker);
        assertThat(open.get(0).locationName()).isEqualTo("Main Warehouse");

        assertThatThrownBy(() -> as(t, () -> putAway.close(s.id(), worker2, false)))
            .as("a worker can't close someone else's session")
            .extracting(e -> ((PlaceException) e).code()).isEqualTo(PlaceException.Code.SESSION_NOT_FOUND);
        assertThat(jdbc.queryForObject("SELECT status FROM put_away_sessions WHERE id = ?", String.class, s.id())).isEqualTo("open");

        PutAwayService.CloseSummary done = as(t, () -> putAway.close(s.id(), manager, true));
        assertThat(done.session().status()).isEqualTo("closed");
        assertThat(done.session().closeReason()).isEqualTo("closed_by_manager");
        Map<String, Object> row = jdbc.queryForMap(
            "SELECT closed_by, close_reason, summary::text AS summary FROM put_away_sessions WHERE id = ?", s.id());
        assertThat(row.get("closed_by")).isEqualTo(manager);
        assertThat((String) row.get("summary")).contains("\"placed\": 1").contains("\"placesUsed\": 1")
            .contains("\"code\": \"Z1-R01-S1\"");
        assertThat(binOf(p)).as("closing never moves a piece").isEqualTo(t.s1());
        assertThat(as(t, () -> putAway.openSessions())).isEmpty();

        PutAwayService.Session own = as(t, () -> putAway.start(worker2, null));
        assertThat(as(t, () -> putAway.close(own.id(), worker2, false)).session().closeReason()).isEqualTo("finished");
        assertThat(jdbc.queryForObject("SELECT closed_by FROM put_away_sessions WHERE id = ?", UUID.class, own.id())).isEqualTo(worker2);
    }

    @Test
    void s5_idleClose_12h() {
        T t = tenant("s5", true);
        UUID worker = user(t, "worker");
        String p = piece(t, t.main(), "available");
        PutAwayService.Session stale = as(t, () -> putAway.start(t.user(), null));
        as(t, () -> putAway.scan(stale.id(), scanCode(t.box()), t.user(), false));
        as(t, () -> putAway.scan(stale.id(), code(p), t.user(), false));
        PutAwayService.Session recent = as(t, () -> putAway.start(worker, null));
        jdbc.update("UPDATE put_away_sessions SET last_activity_at = now() - interval '13 hours' WHERE id = ?", stale.id());
        jdbc.update("UPDATE put_away_sessions SET last_activity_at = now() - interval '11 hours' WHERE id = ?", recent.id());

        assertThat(idleJob.processTenant(t.tenant())).containsExactly(stale.id());
        Map<String, Object> row = jdbc.queryForMap(
            "SELECT status, close_reason, closed_by, closed_at IS NOT NULL AS has_closed_at, summary::text AS summary " +
            "FROM put_away_sessions WHERE id = ?", stale.id());
        assertThat(row).containsEntry("status", "closed").containsEntry("close_reason", "idle")
            .containsEntry("has_closed_at", true);
        assertThat(row.get("closed_by")).as("closed by the system").isNull();
        assertThat((String) row.get("summary")).contains("\"placed\": 1").contains("BOX-01");
        assertThat(binOf(p)).as("placed pieces stay placed").isEqualTo(t.box());
        assertThat(jdbc.queryForObject("SELECT status FROM put_away_sessions WHERE id = ?", String.class, recent.id()))
            .isEqualTo("open");
        assertThatThrownBy(() -> scan(t, stale, scanCode(t.s1())))
            .extracting(e -> ((PlaceException) e).code()).isEqualTo(PlaceException.Code.SESSION_CLOSED);

        // a scan restarts the clock; another pass a moment later closes nothing
        as(t, () -> putAway.scan(recent.id(), scanCode(t.s1()), worker, false));
        assertThat(jdbc.queryForObject("SELECT last_activity_at > now() - interval '1 minute' FROM put_away_sessions WHERE id = ?",
            Boolean.class, recent.id())).isTrue();
        assertThat(idleJob.processTenant(t.tenant())).isEmpty();
        // the job's run() reaches this tenant by itself once it is idle
        jdbc.update("UPDATE put_away_sessions SET last_activity_at = now() - interval '12 hours 1 minute' WHERE id = ?", recent.id());
        idleJob.run();
        assertThat(jdbc.queryForObject("SELECT close_reason FROM put_away_sessions WHERE id = ?", String.class, recent.id()))
            .isEqualTo("idle");
    }

    // ── w: waiting list ────────────────────────────────────────────────────────

    @Test
    void w1_waitingList_sourcesCountsFilterSince() {
        T t = tenant("w1", true);
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        String received = piece(t, t.main(), "available");
        event(received, "received", null, "available", null, now.minus(2, ChronoUnit.HOURS));
        event(received, "label_reprinted", "available", "available", null, now.minus(1, ChronoUnit.HOURS));

        UUID order = order(t, "#16443");
        String returned = piece(t, t.main(), "available");
        event(returned, "received", null, "available", null, now.minus(5, ChronoUnit.DAYS));
        eventWithOrder(returned, "restocked", "return_pending_inspection", "available", order, now.minus(5, ChronoUnit.HOURS));
        String returnedDamaged = piece(t, t.main(), "damaged");
        event(returnedDamaged, "damaged", "return_pending_inspection", "damaged", null, now.minus(6, ChronoUnit.HOURS));

        String transfer = piece(t, t.main(), "available");
        event(transfer, "returned_from_transfer", "out_on_transfer", "available", null, now.minus(1, ChronoUnit.DAYS));
        event(transfer, "held", "available", "on_hold", null, now.minus(20, ChronoUnit.HOURS));
        event(transfer, "unheld", "on_hold", "available", null, now.minus(19, ChronoUnit.HOURS));

        String found = piece(t, t.main(), "available");
        event(found, "adjusted", "lost", "available", "{\"reason\":\"stock_take_found\"}", now.minus(3, ChronoUnit.DAYS));
        event(found, "adjusted", "available", "damaged", "{\"reason\":\"damaged_in_storage\"}", now.minus(2, ChronoUnit.DAYS));
        jdbc.update("UPDATE pieces SET status = 'damaged' WHERE id = ?", found);

        String other = piece(t, t.main(), "available");
        event(other, "received", null, "available", null, now.minus(10, ChronoUnit.DAYS));
        event(other, "unscan", "reserved", "available", null, now.minus(30, ChronoUnit.MINUTES));

        // not waiting: placed, untagged, not placeable, another location
        String placed = piece(t, t.main(), "available");
        as(t, () -> ledger.recordPlaceChange(placed, t.s1(), t.user(), InventoryLedger.PlaceRef.lookup(), null));
        untaggedPiece(t, t.main());
        piece(t, t.main(), "reserved");
        piece(t, t.other(), "available");

        PutAwayService.Waiting w = as(t, () -> putAway.waiting(null, null, t.user()));
        assertThat(w.locationId()).isEqualTo(t.main());
        assertThat(w.hasPlaces()).isTrue();
        assertThat(w.total()).isEqualTo(6);
        assertThat(w.counts()).containsExactly(Map.entry("receiving", 1), Map.entry("returns", 2),
            Map.entry("transfers", 1), Map.entry("stock_take", 1), Map.entry("other", 1));
        Map<String, PutAwayService.WaitingRow> rows = new HashMap<>();
        for (var r : w.rows()) rows.put(r.pieceId(), r);
        assertThat(rows.keySet()).containsExactlyInAnyOrder(received, returned, returnedDamaged, transfer, found, other);
        assertThat(rows.get(received).source()).as("a label reprint isn't where it came from").isEqualTo("receiving");
        assertThat(rows.get(received).waitingSince()).isEqualTo(now.minus(2, ChronoUnit.HOURS));
        assertThat(rows.get(returned).source()).isEqualTo("returns");
        assertThat(rows.get(returned).sourceRef()).isEqualTo("#16443");
        assertThat(rows.get(returnedDamaged).source()).isEqualTo("returns");
        assertThat(rows.get(transfer).source()).as("hold / release isn't where it came from").isEqualTo("transfers");
        assertThat(rows.get(found).source()).as("damaged in the warehouse isn't where it came from").isEqualTo("stock_take");
        assertThat(rows.get(other).source()).isEqualTo("other");
        assertThat(w.rows().get(0).pieceId()).as("newest first").isEqualTo(other);

        PutAwayService.Waiting onlyReturns = as(t, () -> putAway.waiting(t.main(), "returns", t.user()));
        assertThat(onlyReturns.rows()).extracting(PutAwayService.WaitingRow::pieceId)
            .containsExactly(returned, returnedDamaged);
        assertThat(onlyReturns.total()).as("counts never filtered").isEqualTo(6);
        assertThat(onlyReturns.source()).isEqualTo("returns");

        PutAwayService.Summary sum = as(t, () -> putAway.summary());
        assertThat(sum.waiting()).as("only locations with places count; Second Store has a shelf too").isEqualTo(7);
        assertThat(sum.locations()).extracting(PutAwayService.LocationCount::name).containsExactly("Main Warehouse", "Second Store");
    }

    // ── g: suggestions ─────────────────────────────────────────────────────────

    @Test
    void g1_suggestionOrder() {
        T t = tenant("g1", true);
        // most-of-variant: S2 holds 2 available pieces of the variant, S1 holds 1 (+1 damaged, not counted)
        for (int i = 0; i < 2; i++) placeNow(t, piece(t, t.main(), "available"), t.s2());
        placeNow(t, piece(t, t.main(), "available"), t.s1());
        placeNow(t, piece(t, t.main(), "damaged"), t.s1());
        placeNow(t, piece(t, t.main(), "damaged"), t.s1());
        // at the other location the variant fills its shelf — never suggested here
        for (int i = 0; i < 5; i++) placeNow(t, piece(t, t.other(), "available"), t.otherShelf());

        String fresh = piece(t, t.main(), "available");                     // no history → most of variant
        String lastBox = piece(t, t.main(), "available");                   // last place BOX-01 beats S2
        placeNow(t, lastBox, t.box());
        clearNow(t, lastBox);
        String lastArchived = piece(t, t.main(), "available");              // last place archived → its earlier live one
        UUID gone = bin(t, t.main(), "box", t.zone(), "zone", "BOX-77");
        placeNow(t, lastArchived, t.s1());
        placeNow(t, lastArchived, gone);
        clearNow(t, lastArchived);
        jdbc.update("UPDATE bins SET archived_at = now() WHERE id = ?", gone);
        String lastElsewhere = piece(t, t.main(), "available");             // last place at another location → skipped
        jdbc.update("INSERT INTO piece_events (tenant_id, piece_id, event_type, from_status, to_status, to_bin_id) " +
            "VALUES (?, ?, 'put_away', 'available', 'available', ?)", t.tenant(), lastElsewhere, t.otherShelf());
        String lonely = piece(t, t.main(), "available");
        jdbc.update("UPDATE pieces SET variant_id = ? WHERE id = ?", t.variant2(), lonely);   // no piece of its variant placed

        Map<String, PutAwayService.Suggestion> s = new HashMap<>();
        for (var r : as(t, () -> putAway.waiting(t.main(), null, t.user())).rows()) s.put(r.pieceId(), r.suggestion());
        assertThat(s.get(fresh).place().code()).isEqualTo("Z1-R01-S2");
        assertThat(s.get(fresh).reason()).isEqualTo("most_of_variant");
        assertThat(s.get(lastBox).place().code()).isEqualTo("BOX-01");
        assertThat(s.get(lastBox).reason()).isEqualTo("last_place");
        assertThat(s.get(lastArchived).place().code()).isEqualTo("Z1-R01-S1");
        assertThat(s.get(lastArchived).reason()).isEqualTo("last_place");
        assertThat(s.get(lastElsewhere).place().code()).isEqualTo("Z1-R01-S2");
        assertThat(s.get(lastElsewhere).reason()).isEqualTo("most_of_variant");
        assertThat(s.get(lonely)).as("no suggestion").isNull();
    }

    // ── k: Lookup ──────────────────────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void k1_lookup_placePath_move_clear_timeline_boxMoves_sticker() {
        T t = tenant("k1", true);
        String p = piece(t, t.main(), "available");
        Map<String, Object> before = as(t, () -> lookup.lookupPiece(code(p), true));
        assertThat(before).containsEntry("place", null).containsEntry("canPlace", true);

        var bySticker = as(t, () -> putAway.movePiece(p, null, scanCode(t.s2()).toLowerCase(Locale.ROOT), user(t, "worker")));
        assertThat(bySticker.kind()).isEqualTo("PUT_AWAY");
        assertThat(bySticker.to().code()).isEqualTo("Z1-R01-S2");
        Map<String, Object> onShelf = as(t, () -> lookup.lookupPiece(code(p), true));
        Map<String, Object> place = (Map<String, Object>) onShelf.get("place");
        assertThat(place).containsEntry("code", "Z1-R01-S2").containsEntry("kind", "shelf");
        assertThat((List<String>) place.get("trail")).containsExactly("Z1", "R01", "S2");

        var byPick = as(t, () -> putAway.movePiece(p, t.box(), null, t.user()));
        assertThat(byPick.kind()).isEqualTo("MOVED");
        assertThat(byPick.from().code()).isEqualTo("Z1-R01-S2");
        // the box then moves onto shelf S1 (Settings › Places)
        jdbc.update("UPDATE bins SET parent_id = ?, parent_kind = 'shelf' WHERE id = ?", t.s1(), t.box());
        jdbc.update("INSERT INTO bin_events (tenant_id, bin_id, event_type, actor_user_id, from_parent_id, to_parent_id) " +
            "VALUES (?, ?, 'moved', ?, ?, ?)", t.tenant(), t.box(), t.user(), t.zone(), t.s1());
        Map<String, Object> inBox = as(t, () -> lookup.lookupPiece(code(p), true));
        Map<String, Object> boxPlace = (Map<String, Object>) inBox.get("place");
        assertThat(boxPlace).containsEntry("code", "BOX-01").containsEntry("kind", "box").containsEntry("parentCode", "Z1-R01-S1");
        List<Map<String, Object>> tl = (List<Map<String, Object>>) inBox.get("timeline");
        assertThat(tl.get(0)).containsEntry("phraseKey", "box_moved").containsEntry("box", "BOX-01")
            .containsEntry("fromPlace", "Z1").containsEntry("toPlace", "Z1-R01-S1");
        assertThat(tl.get(1)).containsEntry("phraseKey", "place_moved").containsEntry("fromPlace", "Z1-R01-S2")
            .containsEntry("toPlace", "BOX-01");
        assertThat(tl.get(2)).containsEntry("phraseKey", "put_away").containsEntry("toPlace", "Z1-R01-S2");

        var cleared = as(t, () -> putAway.movePiece(p, null, null, t.user()));
        assertThat(cleared.kind()).isEqualTo("CLEARED");
        Map<String, Object> after = as(t, () -> lookup.lookupPiece(code(p), true));
        assertThat(after.get("place")).isNull();
        List<Map<String, Object>> tl2 = (List<Map<String, Object>>) after.get("timeline");
        assertThat(tl2.get(0)).containsEntry("phraseKey", "place_cleared").containsEntry("fromPlace", "BOX-01");
        assertThat(tl2).noneMatch(e -> "box_moved".equals(e.get("phraseKey")));

        assertThatThrownBy(() -> as(t, () -> putAway.movePiece(p, null, "Q999999", t.user())))
            .extracting(e -> ((PlaceException) e).code()).isEqualTo(PlaceException.Code.UNKNOWN_STICKER);
        assertThatThrownBy(() -> as(t, () -> putAway.movePiece(p, null, scanCode(t.zone()), t.user())))
            .extracting(e -> ((PlaceChangeException) e).code()).isEqualTo(PlaceChangeException.Code.NOT_A_SHELF_OR_BOX);

        String sold = piece(t, t.main(), "sold");
        assertThat(as(t, () -> lookup.lookupPiece(code(sold), true))).containsEntry("canPlace", false);

        Map<String, Object> sticker = as(t, () -> lookup.lookupPlaceSticker(scanCode(t.s1())));
        assertThat(sticker).containsEntry("type", "place").containsEntry("code", "Z1-R01-S1")
            .containsEntry("locationName", "Main Warehouse").containsEntry("pieces", 0);
        assertThat(as(t, () -> lookup.lookupPlaceSticker("Q999999"))).isNull();
    }

    // ── f: switch off ──────────────────────────────────────────────────────────

    @Test
    void f1_switchOff_every404_lookupUnchanged() {
        T t = tenant("f1", false);
        String p = piece(t, t.main(), "available");
        UUID session = UUID.randomUUID();
        List<Callable<?>> calls = List.of(
            () -> putAway.summary(), () -> putAway.waiting(null, null, t.user()), () -> putAway.holdablePlaces(null), () -> putAway.openSessions(),
            () -> putAway.start(t.user(), null), () -> putAway.get(session, t.user()),
            () -> putAway.scan(session, "x", t.user(), false), () -> putAway.close(session, t.user()),
            () -> putAway.movePiece(p, t.s1(), null, t.user()));
        for (Callable<?> c : calls) {
            assertThatThrownBy(() -> as(t, c)).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404");
        }
        Map<String, Object> r = as(t, () -> lookup.lookupPiece(code(p), false));
        assertThat(r).doesNotContainKeys("place", "canPlace");
        assertThat(as(t, () -> lookup.lookupPlaceSticker(scanCode(t.s1())))).isNull();
        assertThat(binOf(p)).isNull();
    }

    // ── x: RLS ─────────────────────────────────────────────────────────────────

    @Test
    void x1_appUser_sessionsTenantScoped_withSameTenantPositiveControl() {
        T mine = tenant("x1a", true), theirs = tenant("x1b", true);
        DriverManagerDataSource raw = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "app_user", "testpw");
        TenantAwareDataSource ds = new TenantAwareDataSource(raw);
        JdbcTemplate app = new JdbcTemplate(ds);
        TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        PutAwayService appPutAway = new PutAwayService(app, new InventoryLedger(app));

        String myPiece = piece(mine, mine.main(), "available"), theirPiece = piece(theirs, theirs.main(), "available");
        PutAwayService.Session own = TenantContext.runAs(mine.tenant(), () -> tx.execute(s -> {
            PutAwayService.Session x = appPutAway.start(mine.user(), null);
            appPutAway.scan(x.id(), scanCode(mine.s1()), mine.user(), false);
            assertThat(appPutAway.scan(x.id(), code(myPiece), mine.user(), false).outcome()).isEqualTo("PLACED");
            return x;
        }));
        PutAwayService.Session other = TenantContext.runAs(theirs.tenant(), () -> tx.execute(s -> {
            PutAwayService.Session x = appPutAway.start(theirs.user(), null);
            appPutAway.scan(x.id(), scanCode(theirs.s1()), theirs.user(), false);
            appPutAway.scan(x.id(), code(theirPiece), theirs.user(), false);
            return x;
        }));
        assertThat(binOf(myPiece)).as("placed as app_user under RLS").isEqualTo(mine.s1());

        TenantContext.set(mine.tenant());
        try {
            assertThat((Integer) tx.execute(s -> app.queryForObject("SELECT count(*)::int FROM put_away_sessions", Integer.class)))
                .as("same-tenant positive control: my session").isEqualTo(1);
            assertThat((Integer) tx.execute(s -> app.queryForObject("SELECT count(*)::int FROM put_away_session_pieces", Integer.class)))
                .as("same-tenant positive control: my placed piece").isEqualTo(1);
            assertThat((Integer) tx.execute(s -> app.queryForObject(
                "SELECT count(*)::int FROM put_away_sessions WHERE tenant_id = ?", Integer.class, theirs.tenant()))).isZero();
            assertThat((Integer) tx.execute(s -> app.queryForObject(
                "SELECT count(*)::int FROM put_away_session_pieces WHERE tenant_id = ?", Integer.class, theirs.tenant()))).isZero();
            assertThatThrownBy(() -> tx.execute(s -> appPutAway.get(other.id(), mine.user())))
                .extracting(e -> ((PlaceException) e).code()).isEqualTo(PlaceException.Code.SESSION_NOT_FOUND);
            assertThatThrownBy(() -> tx.execute(s -> appPutAway.scan(other.id(), code(myPiece), mine.user(), false)))
                .extracting(e -> ((PlaceException) e).code()).isEqualTo(PlaceException.Code.SESSION_NOT_FOUND);
            // their piece and their place are invisible: another tenant's sticker is unknown here
            assertThat(tx.execute(s -> appPutAway.scan(own.id(), scanCode(theirs.s1()), mine.user(), false)).outcome())
                .isEqualTo("UNKNOWN_STICKER");
            assertThat(tx.execute(s -> appPutAway.scan(own.id(), code(theirPiece), mine.user(), false)).outcome())
                .isEqualTo("UNKNOWN_STICKER");
            assertThatThrownBy(() -> tx.execute(s -> app.update(
                "INSERT INTO put_away_sessions (tenant_id, location_id, source, opened_by) VALUES (?, ?, 'manual', ?)",
                theirs.tenant(), theirs.main(), theirs.user()))).as("RLS WITH CHECK").isInstanceOf(Exception.class);
            assertThat((Integer) tx.execute(s -> app.update(
                "UPDATE put_away_sessions SET status = 'closed', closed_at = now() WHERE id = ?", other.id())))
                .as("another tenant's session is invisible to UPDATE").isZero();

            // Grants: no DELETE; location / source / opener fixed.
            assertThatThrownBy(() -> tx.execute(s -> app.update("DELETE FROM put_away_sessions WHERE id = ?", own.id())))
                .isInstanceOf(Exception.class);
            assertThatThrownBy(() -> tx.execute(s -> app.update("DELETE FROM put_away_session_pieces WHERE session_id = ?", own.id())))
                .isInstanceOf(Exception.class);
            assertThatThrownBy(() -> tx.execute(s -> app.update(
                "UPDATE put_away_sessions SET location_id = location_id WHERE id = ?", own.id()))).isInstanceOf(Exception.class);
            assertThatThrownBy(() -> tx.execute(s -> app.update(
                "UPDATE put_away_sessions SET opened_by = opened_by WHERE id = ?", own.id()))).isInstanceOf(Exception.class);
        } finally {
            TenantContext.clear();
        }
        assertThat(binOf(theirPiece)).isEqualTo(theirs.s1());
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private <R> R as(T t, Callable<R> call) {
        return TenantContext.runAs(t.tenant(), call);
    }

    private PutAwayService.ScanResult scan(T t, PutAwayService.Session s, String raw) {
        return as(t, () -> putAway.scan(s.id(), raw, t.user(), false));
    }

    private T tenant(String name, boolean placesOn) {
        UUID tenant = UUID.randomUUID(), user = UUID.randomUUID(), store = UUID.randomUUID(), product = UUID.randomUUID();
        UUID variant = UUID.randomUUID(), variant2 = UUID.randomUUID(), main = UUID.randomUUID(), other = UUID.randomUUID();
        String tag = name + "-" + tenant.toString().substring(0, 6);
        jdbc.update("INSERT INTO tenants (id, name, places_enabled) VALUES (?, ?, ?)", tenant, "PutAway " + name, placesOn);
        jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role) VALUES (?, ?, 'Owner', ?, 'h', 'owner')",
            user, tenant, tag + "@putaway.test");
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status) VALUES (?, ?, 'shopify', ?, 'disconnected')",
            store, tenant, tag + ".myshopify.com");
        jdbc.update("INSERT INTO products (id, tenant_id, store_id, external_id, title, status) VALUES (?, ?, ?, ?, 'Tee', 'active')",
            product, tenant, store, "P-" + tag);
        jdbc.update("INSERT INTO variants (id, tenant_id, product_id, external_id, title, sku) VALUES (?, ?, ?, ?, 'M', ?)",
            variant, tenant, product, "V-" + tag, "SKU-" + tag);
        jdbc.update("INSERT INTO variants (id, tenant_id, product_id, external_id, title, sku) VALUES (?, ?, ?, ?, 'L', ?)",
            variant2, tenant, product, "V2-" + tag, "SKU2-" + tag);
        jdbc.update("INSERT INTO locations (id, tenant_id, name, is_default, is_fulfillment) VALUES (?, ?, 'Main Warehouse', true, true)",
            main, tenant);
        jdbc.update("INSERT INTO locations (id, tenant_id, name) VALUES (?, ?, 'Second Store')", other, tenant);
        T base = new T(tenant, user, main, other, variant, variant2, null, null, null, null, null, null);
        UUID zone = bin(base, main, "zone", null, null, "Z1");
        UUID rack = bin(base, main, "rack", zone, "zone", "Z1-R01");
        UUID s1 = bin(base, main, "shelf", rack, "rack", "Z1-R01-S1");
        UUID s2 = bin(base, main, "shelf", rack, "rack", "Z1-R01-S2");
        UUID box = bin(base, main, "box", zone, "zone", "BOX-01");
        UUID oz = bin(base, other, "zone", null, null, "Z1");
        UUID or = bin(base, other, "rack", oz, "zone", "Z1-R01");
        UUID os = bin(base, other, "shelf", or, "rack", "Z1-R01-S1");
        return new T(tenant, user, main, other, variant, variant2, zone, rack, s1, s2, box, os);
    }

    private UUID user(T t, String role) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role) VALUES (?, ?, 'U', ?, 'h', ?::user_role)",
            id, t.tenant(), id + "@putaway.test", role);
        return id;
    }

    private UUID bin(T t, UUID location, String kind, UUID parent, String parentKind, String code) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO bins (id, tenant_id, location_id, kind, parent_id, parent_kind, code, scan_code) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            id, t.tenant(), location, kind, parent, parentKind, code, String.format("Q%07d", 3_000_000 + seq.incrementAndGet()));
        return id;
    }

    private String scanCode(UUID binId) {
        return jdbc.queryForObject("SELECT scan_code FROM bins WHERE id = ?", String.class, binId);
    }

    private String piece(T t, UUID location, String status) {
        int k = seq.incrementAndGet();
        String id = "01PAW" + UUID.randomUUID().toString().replace("-", "").substring(0, 21).toUpperCase();
        jdbc.update("INSERT INTO pieces (id, tenant_id, variant_id, status, barcode, short_code, current_location_id) " +
                "VALUES (?, ?, ?, ?::piece_status, ?, ?, ?)",
            id, t.tenant(), t.variant(), status, "PC-" + id, String.format("P%07d", 4_000_000 + k), location);
        return id;
    }

    private String untaggedPiece(T t, UUID location) {
        String id = "01PAU" + UUID.randomUUID().toString().replace("-", "").substring(0, 21).toUpperCase();
        jdbc.update("INSERT INTO pieces (id, tenant_id, variant_id, status, barcode, current_location_id) " +
            "VALUES (?, ?, ?, 'available', ?, ?)", id, t.tenant(), t.variant(), "PC-" + id, location);
        return id;
    }

    private String code(String pieceId) {
        return jdbc.queryForObject("SELECT short_code FROM pieces WHERE id = ?", String.class, pieceId);
    }

    private UUID order(T t, String number) {
        UUID id = UUID.randomUUID();
        UUID store = jdbc.queryForObject("SELECT id FROM stores WHERE tenant_id = ?", UUID.class, t.tenant());
        jdbc.update("INSERT INTO orders (id, tenant_id, store_id, external_id, number, status) VALUES (?, ?, ?, ?, ?, 'delivered')",
            id, t.tenant(), store, "ord-" + id, number);
        return id;
    }

    private void event(String pieceId, String type, String from, String to, String metadata, Instant at) {
        jdbc.update("INSERT INTO piece_events (tenant_id, piece_id, event_type, from_status, to_status, metadata, occurred_at) " +
                "SELECT tenant_id, id, ?, ?::piece_status, ?::piece_status, ?::jsonb, ? FROM pieces WHERE id = ?",
            type, from, to, metadata, Timestamp.from(at), pieceId);
    }

    private void eventWithOrder(String pieceId, String type, String from, String to, UUID orderId, Instant at) {
        jdbc.update("INSERT INTO piece_events (tenant_id, piece_id, event_type, from_status, to_status, order_id, occurred_at) " +
                "SELECT tenant_id, id, ?, ?::piece_status, ?::piece_status, ?, ? FROM pieces WHERE id = ?",
            type, from, to, orderId, Timestamp.from(at), pieceId);
    }

    private void placeNow(T t, String pieceId, UUID binId) {
        as(t, () -> ledger.recordPlaceChange(pieceId, binId, t.user(), InventoryLedger.PlaceRef.lookup(), null));
    }

    private void clearNow(T t, String pieceId) {
        as(t, () -> ledger.recordPlaceChange(pieceId, null, t.user(), InventoryLedger.PlaceRef.lookup(), null));
    }

    private UUID binOf(String pieceId) {
        return jdbc.queryForObject("SELECT bin_id FROM pieces WHERE id = ?", UUID.class, pieceId);
    }

    private String status(String pieceId) {
        return jdbc.queryForObject("SELECT status::text FROM pieces WHERE id = ?", String.class, pieceId);
    }

    private int eventCount(String pieceId) {
        return jdbc.queryForObject("SELECT count(*)::int FROM piece_events WHERE piece_id = ?", Integer.class, pieceId);
    }

    private Map<String, Object> lastEvent(String pieceId) {
        return jdbc.queryForMap(
            "SELECT event_type, from_status::text AS from_status, to_status::text AS to_status, from_bin_id, to_bin_id, " +
            "       location_id, metadata, raw_scan " +
            "FROM piece_events WHERE piece_id = ? ORDER BY occurred_at DESC, id DESC LIMIT 1", pieceId);
    }
}
