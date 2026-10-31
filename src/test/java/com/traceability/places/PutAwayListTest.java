package com.traceability.places;

import com.traceability.TestPostgres;
import com.traceability.inventory.InventoryLedger;
import com.traceability.tenancy.TenantAwareDataSource;
import com.traceability.tenancy.TenantContext;
import org.jobrunr.scheduling.JobScheduler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.access.AccessDeniedException;
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
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

/**
 * Warehouse places S4 (V197) — put-away lists after receiving, returns and transfers (PutAwayListService).
 *
 *   ls1  receiving: the receipt's TAGGED pieces at its location — untagged (count-only), another location, another
 *        receipt excluded; a placed one is done (its place = where it is); a departed one (sold) dropped; a receipt
 *        with no location → the main warehouse; an all count-only receipt → an empty list
 *   ls2  return session: RESTOCKED pieces only — damaged / mismatch / pending excluded (revert-check: remove the
 *        "restocked only" filter → red)
 *   ls3  transfer: pieces returned GOOD to the main warehouse only — condemned, still out, sold excluded
 *   ls4  tag session: the pieces that session tagged (its label_bound events), at its location
 *   gs1  walking order (Z2 before Z10, S9 before S10, a floor box after the zone's racks), no suggestion last;
 *        suggestion = S2's rule (last place beats most-of-variant)
 *   q1   batch: the same number of statements for 3 pieces as for 30 — nothing per piece
 *   ss1  scoped session: Start pre-fills the waiting pieces with their suggested place, resumes (same id, idle clock
 *        restarted); another person / another flow gets its own; scans: a list piece → progress, a waiting piece not
 *        on the list → extra, the same place again → ALREADY_HERE, another place → MOVED; sessionList scoped /
 *        manual
 *   ss2  12 h idle close still closes a scoped session; the next Start opens a new one
 *   i1   V197: a second OPEN scoped session for the same person + flow is refused by the database
 *   sp1  transfer send-out collect list: S3's batch places (PickPlacesService.variants, unchanged) for a line's
 *        variant = its AVAILABLE pieces still at the main warehouse, per place biggest first, the unplaced as
 *        noPlaceCount; pieces already scanned out, at another location or reserved are not counted
 *   r1   roles: a transfer's list / Start need owner or manager (AccessDenied); receiving, returns,
 *        tag stock don't
 *   f1   places off: every method 404 — a worker's transfer list too (404, not 403)
 *   x1   app_user: lists, Start (twice — the ON CONFLICT resume runs on app_user's grants) and sessionList under RLS;
 *        another tenant's receipt / return session / transfer / tag session → SOURCE_NOT_FOUND, same-tenant
 *        positive control
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PutAwayListTest {

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

    @Autowired JdbcTemplate       jdbc;
    @Autowired PutAwayService     putAway;
    @Autowired PutAwayListService lists;
    @Autowired PickPlacesService  pickPlaces;
    @Autowired DataSource         dataSource;
    PutAwayIdleCloseJob idleJob;

    @BeforeAll
    void job() { idleJob = new PutAwayIdleCloseJob(putAway, dataSource); }

    /** A tenant: main warehouse with Z2 › Z2-R01 › S9 / S10, floor box BOX-07 in Z2, Z10 › Z10-R01 › S1; a second store. */
    record T(UUID tenant, UUID user, UUID main, UUID other, UUID variant, UUID variant2, UUID variant3,
             UUID s9, UUID s10, UUID box, UUID z10s1, UUID otherShelf) {}

    final AtomicInteger seq = new AtomicInteger();

    @AfterEach
    void clear() { TenantContext.clear(); }

    // ── ls: who is on a list ───────────────────────────────────────────────────

    @Test
    void ls1_receiving_taggedAtItsLocation_doneAndDeparted() {
        T t = tenant("ls1", true);
        UUID r = receipt(t, t.main(), "PO-77");
        String waiting = piece(t, t.variant(), t.main(), "available", r);
        String placed = piece(t, t.variant(), t.main(), "available", r);
        place(placed, t.s10());
        String damaged = piece(t, t.variant2(), t.main(), "damaged", r);            // placeable: still on the list
        String untagged = untaggedPiece(t, t.main(), r);
        String elsewhere = piece(t, t.variant(), t.other(), "available", r);
        String sold = piece(t, t.variant(), t.main(), "sold", r);
        String otherReceipt = piece(t, t.variant(), t.main(), "available", receipt(t, t.main(), "PO-78"));

        var l = as(t, () -> lists.list("receiving", r, t.user(), false));
        assertThat(l.sourceRef()).isEqualTo("PO-77");
        assertThat(l.locationId()).isEqualTo(t.main());
        assertThat(l.locationName()).isEqualTo("Main Warehouse");
        assertThat(l.hasPlaces()).isTrue();
        assertThat(ids(l)).containsExactlyInAnyOrder(waiting, placed, damaged)
            .doesNotContain(untagged, elsewhere, sold, otherReceipt);
        assertThat(l.total()).isEqualTo(3);
        assertThat(l.placed()).isEqualTo(1);
        var done = byId(l, placed);
        assertThat(done.done()).isTrue();
        assertThat(done.placeReason()).isEqualTo("placed");
        assertThat(done.place().code()).isEqualTo("Z2-R01-S10");
        assertThat(byId(l, waiting).done()).isFalse();

        // it leaves (sold): dropped from the list
        jdbc.update("UPDATE pieces SET status = 'sold' WHERE id = ?", waiting);
        assertThat(ids(as(t, () -> lists.list("receiving", r, t.user(), false)))).doesNotContain(waiting);

        UUID noLocation = receipt(t, null, "PO-79");
        String atMain = piece(t, t.variant(), t.main(), "available", noLocation);
        var main = as(t, () -> lists.list("receiving", noLocation, t.user(), false));
        assertThat(main.locationId()).as("no location on the receipt → the main warehouse").isEqualTo(t.main());
        assertThat(ids(main)).containsExactly(atMain);

        UUID countOnly = receipt(t, t.main(), "PO-80");
        untaggedPiece(t, t.main(), countOnly);
        untaggedPiece(t, t.main(), countOnly);
        var empty = as(t, () -> lists.list("receiving", countOnly, t.user(), false));
        assertThat(empty.total()).as("an all count-only receipt has nothing to put away").isZero();
        assertThat(empty.pieces()).isEmpty();
    }

    @Test
    void ls2_returnSession_restockedOnly() {
        T t = tenant("ls2", true);
        UUID rs = returnSession(t);
        String restocked = piece(t, t.variant(), t.main(), "available", null);
        String damaged = piece(t, t.variant(), t.main(), "damaged", null);          // placeable at main — still excluded
        String mismatch = piece(t, t.variant(), t.main(), "available", null);
        String pending = piece(t, t.variant(), t.main(), "available", null);
        sessionItem(t, rs, restocked, "restocked");
        sessionItem(t, rs, damaged, "damaged");
        sessionItem(t, rs, mismatch, "mismatch");
        sessionItem(t, rs, pending, "pending");
        String otherSession = piece(t, t.variant(), t.main(), "available", null);
        sessionItem(t, returnSession(t), otherSession, "restocked");

        var l = as(t, () -> lists.list("return_session", rs, t.user(), false));
        assertThat(ids(l)).as("restocked pieces only — never damaged, mismatch, pending").containsExactly(restocked);
        assertThat(l.locationId()).isEqualTo(t.main());
    }

    @Test
    void ls3_transfer_returnedGoodToMainOnly() {
        T t = tenant("ls3", true);
        UUID tr = transfer(t);
        UUID line = transferLine(t, tr, t.variant());
        String good = piece(t, t.variant(), t.main(), "available", null);
        String condemned = piece(t, t.variant(), t.main(), "damaged", null);
        String out = piece(t, t.variant(), t.other(), "out_on_transfer", null);
        String sold = piece(t, t.variant(), t.other(), "sold", null);
        transferPiece(t, tr, line, good, "returned_good", Instant.now());
        transferPiece(t, tr, line, condemned, "condemned", Instant.now());
        transferPiece(t, tr, line, out, null, Instant.now());
        transferPiece(t, tr, line, sold, "sold", Instant.now());

        var l = as(t, () -> lists.list("transfer", tr, t.user(), true));
        assertThat(ids(l)).containsExactly(good);
        assertThat(l.locationId()).isEqualTo(t.main());
        assertThat(l.sourceRef()).isEqualTo("Second Store");

        // a returned-good piece that has since left the main warehouse is dropped
        jdbc.update("UPDATE pieces SET current_location_id = ? WHERE id = ?", t.other(), good);
        assertThat(as(t, () -> lists.list("transfer", tr, t.user(), true)).pieces()).isEmpty();
    }

    @Test
    void ls4_tagSession_thePiecesItTagged() {
        T t = tenant("ls4", true);
        UUID ts = tagSession(t, t.main());
        String a = piece(t, t.variant(), t.main(), "available", null);
        String b = piece(t, t.variant2(), t.main(), "available", null);
        String elsewhere = piece(t, t.variant(), t.main(), "available", null);
        String notTagged = piece(t, t.variant(), t.main(), "available", null);
        labelBound(t, a, ts);
        labelBound(t, b, ts);
        labelBound(t, elsewhere, tagSession(t, t.main()));
        var l = as(t, () -> lists.list("tag_session", ts, t.user(), false));
        assertThat(ids(l)).containsExactlyInAnyOrder(a, b).doesNotContain(elsewhere, notTagged);
        assertThat(l.sourceRef()).isNull();
    }

    // ── gs: order and suggestions ──────────────────────────────────────────────

    @Test
    void gs1_walkingOrder_noSuggestionLast_s2SuggestionRule() {
        T t = tenant("gs1", true);
        UUID r = receipt(t, t.main(), "PO-G");
        // variant 1 lives on Z10-R01-S1 (most of it), variant 2 in the floor box, variant 3 nowhere
        place(piece(t, t.variant(), t.main(), "available", null), t.z10s1());
        place(piece(t, t.variant(), t.main(), "available", null), t.z10s1());
        place(piece(t, t.variant2(), t.main(), "available", null), t.box());
        String mostZ10 = piece(t, t.variant(), t.main(), "available", r);
        String lastS9 = piece(t, t.variant(), t.main(), "available", r);             // its own last place beats Z10
        place(lastS9, t.s9());
        clearPlace(lastS9);
        String doneS10 = piece(t, t.variant3(), t.main(), "available", r);
        place(doneS10, t.s10());
        String box = piece(t, t.variant2(), t.main(), "available", r);
        String none = piece(t, variant(t, "XL"), t.main(), "available", r);         // its variant is placed nowhere

        var l = as(t, () -> lists.list("receiving", r, t.user(), false));
        assertThat(l.pieces()).extracting(PutAwayListService.ListPiece::pieceId)
            .as("Z2: S9, S10, floor box (after the racks) · Z10 · no suggestion last")
            .containsExactly(lastS9, doneS10, box, mostZ10, none);
        assertThat(byId(l, lastS9).placeReason()).isEqualTo("last_place");
        assertThat(byId(l, lastS9).place().code()).isEqualTo("Z2-R01-S9");
        assertThat(byId(l, mostZ10).placeReason()).isEqualTo("most_of_variant");
        assertThat(byId(l, mostZ10).place().zoneCode()).isEqualTo("Z10");
        assertThat(byId(l, box).place().code()).isEqualTo("BOX-07");
        assertThat(byId(l, box).place().parentCode()).isEqualTo("Z2");
        assertThat(byId(l, none).place()).isNull();
        assertThat(byId(l, none).placeReason()).isNull();
        assertThat(byId(l, doneS10).place().sortKey()).isLessThan(byId(l, box).place().sortKey());
    }

    @Test
    void q1_batch_sameStatementsForThreeAndThirtyPieces() {
        T t = tenant("q1", true);
        UUID small = receipt(t, t.main(), "PO-S"), big = receipt(t, t.main(), "PO-B");
        for (int i = 0; i < 3; i++) piece(t, i % 2 == 0 ? t.variant() : t.variant2(), t.main(), "available", small);
        for (int i = 0; i < 30; i++) {
            String p = piece(t, i % 3 == 0 ? t.variant() : i % 3 == 1 ? t.variant2() : t.variant3(), t.main(), "available", big);
            if (i % 4 == 0) place(p, i % 8 == 0 ? t.s9() : t.z10s1());
        }
        place(piece(t, t.variant(), t.main(), "available", null), t.s10());

        List<String> statements = new CopyOnWriteArrayList<>();
        JdbcTemplate counted = new JdbcTemplate(counting(dataSource, statements));
        PutAwayListService service = new PutAwayListService(counted, new PutAwayService(counted, new InventoryLedger(counted)));
        assertThat(as(t, () -> service.list("receiving", small, t.user(), false)).pieces()).hasSize(3);
        int three = statements.size();
        statements.clear();
        assertThat(as(t, () -> service.list("receiving", big, t.user(), false)).pieces()).hasSize(30);
        assertThat(statements).as("nothing per piece: %s", statements).hasSize(three);
        assertThat(statements.stream().filter(s -> s.contains("FROM pieces p JOIN variants")).count())
            .as("the list's pieces in one statement").isEqualTo(1);
    }

    // ── ss: scoped sessions ────────────────────────────────────────────────────

    @Test
    void ss1_scopedSession_prefill_resume_progress_extra_alreadyHere_moved() {
        T t = tenant("ss1", true);
        UUID r = receipt(t, t.main(), "PO-SS");
        place(piece(t, t.variant(), t.main(), "available", null), t.s9());          // variant 1's place: S9
        String a = piece(t, t.variant(), t.main(), "available", r);
        String b = piece(t, t.variant(), t.main(), "available", r);
        String c = piece(t, t.variant3(), t.main(), "available", r);                 // no suggestion
        String stray = piece(t, t.variant2(), t.main(), "available", null);          // waiting, not on this list

        var s = as(t, () -> lists.start("receiving", r, t.user(), false));
        assertThat(s.status()).isEqualTo("open");
        assertThat(s.locationId()).isEqualTo(t.main());
        Map<String, UUID> prefill = new HashMap<>();
        jdbc.query("SELECT piece_id, suggested_bin_id, placed_bin_id FROM put_away_session_pieces WHERE session_id = ?",
            rs -> {
                assertThat(rs.getObject("placed_bin_id")).isNull();
                prefill.put(rs.getString("piece_id"), rs.getObject("suggested_bin_id", UUID.class));
            }, s.id());
        assertThat(prefill).containsOnlyKeys(a, b, c);
        assertThat(prefill.get(a)).isEqualTo(t.s9());
        assertThat(prefill.get(c)).isNull();
        assertThat(jdbc.queryForObject("SELECT source || ':' || source_id FROM put_away_sessions WHERE id = ?", String.class, s.id()))
            .isEqualTo("receiving:" + r);

        jdbc.update("UPDATE put_away_sessions SET last_activity_at = now() - interval '3 hours' WHERE id = ?", s.id());
        var again = as(t, () -> lists.start("receiving", r, t.user(), false));
        assertThat(again.id()).as("Start resumes my open session on this list").isEqualTo(s.id());
        assertThat(jdbc.queryForObject("SELECT last_activity_at > now() - interval '1 minute' FROM put_away_sessions WHERE id = ?",
            Boolean.class, s.id())).as("resuming restarts the idle clock").isTrue();
        UUID worker = user(t, "worker");
        assertThat(as(t, () -> lists.start("receiving", r, worker, false)).id()).as("another person: their own").isNotEqualTo(s.id());
        UUID rs2 = returnSession(t);
        assertThat(as(t, () -> lists.start("return_session", rs2, t.user(), false)).id()).as("another flow: its own").isNotEqualTo(s.id());

        var before = as(t, () -> lists.list("receiving", r, t.user(), false));
        assertThat(before.session().id()).isEqualTo(s.id());
        assertThat(before.placed()).isZero();

        scan(t, s.id(), scanCode(t.s10()));
        assertThat(scan(t, s.id(), code(a)).outcome()).isEqualTo("PLACED");
        var one = as(t, () -> lists.list("receiving", r, t.user(), false));
        assertThat(one.placed()).as("progress 1 / 3").isEqualTo(1);
        assertThat(one.total()).isEqualTo(3);
        assertThat(one.session().placedInSession()).isEqualTo(1);
        assertThat(one.session().extra()).isZero();

        assertThat(scan(t, s.id(), code(stray)).outcome()).isEqualTo("PLACED");
        var extra = as(t, () -> lists.list("receiving", r, t.user(), false));
        assertThat(extra.session().extra()).as("a waiting piece not on this list counts as extra").isEqualTo(1);
        assertThat(extra.placed()).as("…and not as list progress").isEqualTo(1);

        assertThat(scan(t, s.id(), code(a)).outcome()).as("already placed here").isEqualTo("ALREADY_HERE");
        scan(t, s.id(), scanCode(t.s9()));
        assertThat(scan(t, s.id(), code(a)).outcome()).as("another place: moved, as in S2").isEqualTo("MOVED");
        var moved = as(t, () -> lists.list("receiving", r, t.user(), false));
        assertThat(moved.placed()).isEqualTo(1);
        assertThat(byId(moved, a).place().code()).isEqualTo("Z2-R01-S9");

        var scoped = as(t, () -> lists.sessionList(s.id(), t.user()));
        assertThat(scoped.scoped()).isTrue();
        assertThat(scoped.list().session().id()).isEqualTo(s.id());
        assertThat(scoped.list().placed()).isEqualTo(1);
        var manual = as(t, () -> putAway.start(t.user(), t.main()));
        var plain = as(t, () -> lists.sessionList(manual.id(), t.user()));
        assertThat(plain.scoped()).isFalse();
        assertThat(plain.list()).isNull();
        assertThatThrownBy(() -> as(t, () -> lists.sessionList(s.id(), worker)))
            .as("someone else's session").extracting(e -> ((PlaceException) e).code()).isEqualTo(PlaceException.Code.SESSION_NOT_FOUND);
    }

    @Test
    void ss2_idleClose_closesAScopedSession_nextStartIsNew() {
        T t = tenant("ss2", true);
        UUID r = receipt(t, t.main(), "PO-ID");
        piece(t, t.variant(), t.main(), "available", r);
        var s = as(t, () -> lists.start("receiving", r, t.user(), false));
        jdbc.update("UPDATE put_away_sessions SET last_activity_at = now() - interval '13 hours' WHERE id = ?", s.id());
        assertThat(idleJob.processTenant(t.tenant())).containsExactly(s.id());
        Map<String, Object> row = jdbc.queryForMap("SELECT status, close_reason, closed_by FROM put_away_sessions WHERE id = ?", s.id());
        assertThat(row.get("status")).isEqualTo("closed");
        assertThat(row.get("close_reason")).isEqualTo("idle");
        assertThat(row.get("closed_by")).isNull();
        assertThat(as(t, () -> lists.list("receiving", r, t.user(), false)).session()).as("no open session").isNull();
        assertThat(as(t, () -> lists.sessionList(s.id(), t.user())).list().session().status()).isEqualTo("closed");
        assertThat(as(t, () -> lists.start("receiving", r, t.user(), false)).id()).isNotEqualTo(s.id());
    }

    @Test
    void i1_oneOpenScopedSessionPerPersonAndFlow_enforcedByTheDatabase() {
        T t = tenant("i1", true);
        UUID r = receipt(t, t.main(), "PO-I");
        jdbc.update("INSERT INTO put_away_sessions (tenant_id, location_id, source, source_id, opened_by) VALUES (?, ?, 'receiving', ?, ?)",
            t.tenant(), t.main(), r, t.user());
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO put_away_sessions (tenant_id, location_id, source, source_id, opened_by) VALUES (?, ?, 'receiving', ?, ?)",
                t.tenant(), t.main(), r, t.user()))
            .hasMessageContaining("put_away_sessions_one_open_scoped");
        // 'tag_session' is a source now (V197); an unknown one still isn't
        jdbc.update("INSERT INTO put_away_sessions (tenant_id, location_id, source, source_id, opened_by) VALUES (?, ?, 'tag_session', ?, ?)",
            t.tenant(), t.main(), tagSession(t, t.main()), t.user());
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO put_away_sessions (tenant_id, location_id, source, source_id, opened_by) VALUES (?, ?, 'stock_take', ?, ?)",
                t.tenant(), t.main(), UUID.randomUUID(), t.user()))
            .hasMessageContaining("put_away_sessions_source_check");
    }

    // ── sp: a transfer's collect list (S3's batch places) ──────────────────────

    @Test
    void sp1_transferCollect_s3BatchPlaces_availableAtMain_biggestFirst_restNoPlace() {
        T t = tenant("sp1", true);
        for (int i = 0; i < 2; i++) place(piece(t, t.variant(), t.main(), "available", null), t.s9());
        place(piece(t, t.variant(), t.main(), "available", null), t.z10s1());
        piece(t, t.variant(), t.main(), "available", null);                     // no place yet
        piece(t, t.variant(), t.other(), "out_on_transfer", null);              // already scanned out
        place(piece(t, t.variant(), t.other(), "available", null), t.otherShelf());
        piece(t, t.variant(), t.main(), "reserved", null);

        var v = as(t, () -> pickPlaces.variants(List.of(t.variant()))).variants();
        assertThat(v).hasSize(1);
        assertThat(v.get(0).places()).extracting(PickPlacesService.PlaceCount::code, PickPlacesService.PlaceCount::count)
            .containsExactly(tuple("Z2-R01-S9", 2), tuple("Z10-R01-S1", 1));
        assertThat(v.get(0).noPlaceCount()).isEqualTo(1);
    }

    // ── r / f: roles and the switch ────────────────────────────────────────────

    @Test
    void r1_transferNeedsOwnerOrManager_theRestDoNot() {
        T t = tenant("r1", true);
        UUID tr = transfer(t);
        UUID worker = user(t, "worker");
        assertThatThrownBy(() -> as(t, () -> lists.list("transfer", tr, worker, false))).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> as(t, () -> lists.start("transfer", tr, worker, false))).isInstanceOf(AccessDeniedException.class);
        assertThat(as(t, () -> lists.list("transfer", tr, t.user(), true)).source()).isEqualTo("transfer");
        assertThat(as(t, () -> lists.list("receiving", receipt(t, t.main(), "PO-W"), worker, false))).isNotNull();
        assertThat(as(t, () -> lists.start("return_session", returnSession(t), worker, false)).status()).isEqualTo("open");
        assertThat(as(t, () -> lists.list("tag_session", tagSession(t, t.main()), worker, false))).isNotNull();
        assertThatThrownBy(() -> as(t, () -> lists.list("stock_take", tr, t.user(), true)))
            .extracting(e -> ((PlaceException) e).code()).isEqualTo(PlaceException.Code.SOURCE_NOT_FOUND);
    }

    @Test
    void f1_placesOff_every404_evenAWorkersTransferList() {
        T t = tenant("f1", false);
        UUID r = receipt(t, t.main(), "PO-F"), tr = transfer(t);
        UUID session = UUID.randomUUID();
        for (Callable<?> c : List.<Callable<?>>of(
                () -> lists.list("receiving", r, t.user(), true),
                () -> lists.start("receiving", r, t.user(), true),
                () -> lists.sessionList(session, t.user()),
                () -> lists.list("transfer", tr, t.user(), false),
                () -> lists.start("transfer", tr, t.user(), false))) {
            assertThatThrownBy(() -> as(t, c))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getStatusCode().value()).isEqualTo(404));
        }
        assertThat(jdbc.queryForObject("SELECT count(*)::int FROM put_away_sessions WHERE tenant_id = ?", Integer.class, t.tenant()))
            .isZero();
    }

    // ── x: RLS ─────────────────────────────────────────────────────────────────

    @Test
    void x1_appUser_listsAndStart_underRls_withSameTenantPositiveControl() {
        T mine = tenant("x1a", true), theirs = tenant("x1b", true);
        DriverManagerDataSource raw = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "app_user", "testpw");
        TenantAwareDataSource ds = new TenantAwareDataSource(raw);
        JdbcTemplate app = new JdbcTemplate(ds);
        TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        PutAwayListService appLists = new PutAwayListService(app, new PutAwayService(app, new InventoryLedger(app)));

        UUID myReceipt = receipt(mine, mine.main(), "PO-MINE");
        String myPiece = piece(mine, mine.variant(), mine.main(), "available", myReceipt);
        UUID myReturns = returnSession(mine);
        String myRestocked = piece(mine, mine.variant(), mine.main(), "available", null);
        sessionItem(mine, myReturns, myRestocked, "restocked");
        UUID myTag = tagSession(mine, mine.main());
        String myTagged = piece(mine, mine.variant(), mine.main(), "available", null);
        labelBound(mine, myTagged, myTag);

        UUID theirReceipt = receipt(theirs, theirs.main(), "PO-THEIRS");
        piece(theirs, theirs.variant(), theirs.main(), "available", theirReceipt);
        UUID theirReturns = returnSession(theirs);
        UUID theirTransfer = transfer(theirs);
        UUID theirTag = tagSession(theirs, theirs.main());

        TenantContext.set(mine.tenant());
        try {
            var own = tx.execute(s -> appLists.list("receiving", myReceipt, mine.user(), true));
            assertThat(ids(own)).as("same-tenant positive control").containsExactly(myPiece);
            assertThat(ids(tx.execute(s -> appLists.list("return_session", myReturns, mine.user(), false)))).containsExactly(myRestocked);
            assertThat(ids(tx.execute(s -> appLists.list("tag_session", myTag, mine.user(), false)))).containsExactly(myTagged);

            var started = tx.execute(s -> appLists.start("receiving", myReceipt, mine.user(), true));
            var resumed = tx.execute(s -> appLists.start("receiving", myReceipt, mine.user(), true));
            assertThat(resumed.id()).as("the ON CONFLICT resume works on app_user's grants").isEqualTo(started.id());
            assertThat(tx.execute(s -> appLists.sessionList(started.id(), mine.user())).list().total()).isEqualTo(1);

            for (Map.Entry<String, UUID> their : List.of(Map.entry("receiving", theirReceipt),
                    Map.entry("return_session", theirReturns), Map.entry("transfer", theirTransfer), Map.entry("tag_session", theirTag))) {
                assertThatThrownBy(() -> tx.execute(s -> appLists.list(their.getKey(), their.getValue(), mine.user(), true)))
                    .as("another tenant's " + their.getKey())
                    .extracting(e -> ((PlaceException) e).code()).isEqualTo(PlaceException.Code.SOURCE_NOT_FOUND);
                assertThatThrownBy(() -> tx.execute(s -> appLists.start(their.getKey(), their.getValue(), mine.user(), true)))
                    .extracting(e -> ((PlaceException) e).code()).isEqualTo(PlaceException.Code.SOURCE_NOT_FOUND);
            }
        } finally {
            TenantContext.clear();
        }
        assertThat(jdbc.queryForObject("SELECT count(*)::int FROM put_away_sessions WHERE tenant_id = ?", Integer.class, theirs.tenant()))
            .as("nothing written for them").isZero();
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private <R> R as(T t, Callable<R> call) {
        return TenantContext.runAs(t.tenant(), call);
    }

    private PutAwayService.ScanResult scan(T t, UUID session, String raw) {
        return as(t, () -> putAway.scan(session, raw, t.user(), false));
    }

    private static List<String> ids(PutAwayListService.PutAwayList l) {
        return l.pieces().stream().map(PutAwayListService.ListPiece::pieceId).toList();
    }

    private static PutAwayListService.ListPiece byId(PutAwayListService.PutAwayList l, String id) {
        return l.pieces().stream().filter(p -> p.pieceId().equals(id)).findFirst().orElseThrow();
    }

    private T tenant(String name, boolean placesOn) {
        UUID tenant = UUID.randomUUID(), user = UUID.randomUUID(), store = UUID.randomUUID(), product = UUID.randomUUID();
        UUID v1 = UUID.randomUUID(), v2 = UUID.randomUUID(), v3 = UUID.randomUUID(), main = UUID.randomUUID(), other = UUID.randomUUID();
        String tag = name + "-" + tenant.toString().substring(0, 6);
        jdbc.update("INSERT INTO tenants (id, name, places_enabled) VALUES (?, ?, ?)", tenant, "PutAwayLists " + name, placesOn);
        jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role) VALUES (?, ?, 'Owner', ?, 'h', 'owner')",
            user, tenant, tag + "@putawaylists.test");
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status) VALUES (?, ?, 'shopify', ?, 'disconnected')",
            store, tenant, tag + ".myshopify.com");
        jdbc.update("INSERT INTO products (id, tenant_id, store_id, external_id, title, status) VALUES (?, ?, ?, ?, 'Tee', 'active')",
            product, tenant, store, "P-" + tag);
        int i = 0;
        for (UUID v : List.of(v1, v2, v3)) {
            jdbc.update("INSERT INTO variants (id, tenant_id, product_id, external_id, title, sku) VALUES (?, ?, ?, ?, ?, ?)",
                v, tenant, product, "V" + (++i) + "-" + tag, List.of("M", "L", "S").get(i - 1), "SKU" + i + "-" + tag);
        }
        jdbc.update("INSERT INTO locations (id, tenant_id, name, is_default, is_fulfillment) VALUES (?, ?, 'Main Warehouse', true, true)",
            main, tenant);
        jdbc.update("INSERT INTO locations (id, tenant_id, name) VALUES (?, ?, 'Second Store')", other, tenant);
        UUID z2 = bin(tenant, main, "zone", null, null, "Z2");
        UUID r2 = bin(tenant, main, "rack", z2, "zone", "Z2-R01");
        UUID s9 = bin(tenant, main, "shelf", r2, "rack", "Z2-R01-S9");
        UUID s10 = bin(tenant, main, "shelf", r2, "rack", "Z2-R01-S10");
        UUID box = bin(tenant, main, "box", z2, "zone", "BOX-07");
        UUID z10 = bin(tenant, main, "zone", null, null, "Z10");
        UUID r10 = bin(tenant, main, "rack", z10, "zone", "Z10-R01");
        UUID z10s1 = bin(tenant, main, "shelf", r10, "rack", "Z10-R01-S1");
        UUID oz = bin(tenant, other, "zone", null, null, "Z1");
        UUID or = bin(tenant, other, "rack", oz, "zone", "Z1-R01");
        UUID os = bin(tenant, other, "shelf", or, "rack", "Z1-R01-S1");
        return new T(tenant, user, main, other, v1, v2, v3, s9, s10, box, z10s1, os);
    }

    private UUID variant(T t, String title) {
        UUID id = UUID.randomUUID();
        UUID product = jdbc.queryForObject("SELECT product_id FROM variants WHERE id = ?", UUID.class, t.variant());
        jdbc.update("INSERT INTO variants (id, tenant_id, product_id, external_id, title, sku) VALUES (?, ?, ?, ?, ?, ?)",
            id, t.tenant(), product, "VX-" + id, title, "SKUX-" + id);
        return id;
    }

    private UUID user(T t, String role) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role) VALUES (?, ?, 'U', ?, 'h', ?::user_role)",
            id, t.tenant(), id + "@putawaylists.test", role);
        return id;
    }

    private UUID bin(UUID tenant, UUID location, String kind, UUID parent, String parentKind, String code) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO bins (id, tenant_id, location_id, kind, parent_id, parent_kind, code, scan_code) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            id, tenant, location, kind, parent, parentKind, code, String.format("Q%07d", 5_000_000 + seq.incrementAndGet()));
        return id;
    }

    private String scanCode(UUID binId) {
        return jdbc.queryForObject("SELECT scan_code FROM bins WHERE id = ?", String.class, binId);
    }

    private String piece(T t, UUID variant, UUID location, String status, UUID receipt) {
        int k = seq.incrementAndGet();
        String id = "01PAL" + UUID.randomUUID().toString().replace("-", "").substring(0, 21).toUpperCase();
        jdbc.update("INSERT INTO pieces (id, tenant_id, variant_id, status, barcode, short_code, current_location_id, receipt_id) " +
                "VALUES (?, ?, ?, ?::piece_status, ?, ?, ?, ?)",
            id, t.tenant(), variant, status, "PC-" + id, String.format("P%07d", 6_000_000 + k), location, receipt);
        return id;
    }

    private String untaggedPiece(T t, UUID location, UUID receipt) {
        String id = "01PLU" + UUID.randomUUID().toString().replace("-", "").substring(0, 21).toUpperCase();
        jdbc.update("INSERT INTO pieces (id, tenant_id, variant_id, status, barcode, current_location_id, receipt_id) " +
            "VALUES (?, ?, ?, 'available', ?, ?, ?)", id, t.tenant(), t.variant(), "PC-" + id, location, receipt);
        return id;
    }

    private String code(String pieceId) {
        return jdbc.queryForObject("SELECT short_code FROM pieces WHERE id = ?", String.class, pieceId);
    }

    private void place(String pieceId, UUID binId) {
        UUID tenant = jdbc.queryForObject("SELECT tenant_id FROM pieces WHERE id = ?", UUID.class, pieceId);
        UUID actor = jdbc.queryForObject("SELECT id FROM users WHERE tenant_id = ? AND role = 'owner' LIMIT 1", UUID.class, tenant);
        TenantContext.runAs(tenant, () -> new InventoryLedger(jdbc).recordPlaceChange(pieceId, binId, actor,
            InventoryLedger.PlaceRef.lookup(), null));
    }

    private void clearPlace(String pieceId) {
        place(pieceId, null);
    }

    private UUID receipt(T t, UUID location, String reference) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO receipts (id, tenant_id, location_id, reference, status, finalized_at) " +
            "VALUES (?, ?, ?, ?, 'finalized', now())", id, t.tenant(), location, reference);
        return id;
    }

    private UUID returnSession(T t) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO return_sessions (id, tenant_id, status, opened_by, closed_by, closed_at) " +
            "VALUES (?, ?, 'closed', ?, ?, now())", id, t.tenant(), t.user(), t.user());
        return id;
    }

    private void sessionItem(T t, UUID session, String pieceId, String disposition) {
        jdbc.update("INSERT INTO return_session_items (tenant_id, session_id, piece_id, scan_source, disposition) " +
            "VALUES (?, ?, ?, 'barcode', ?)", t.tenant(), session, pieceId, disposition);
    }

    private UUID transfer(T t) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO transfers (id, tenant_id, transfer_type, destination_location_id, status, created_by) " +
            "VALUES (?, ?, 'showroom', ?, 'reconciling', ?)", id, t.tenant(), t.other(), t.user());
        return id;
    }

    private UUID transferLine(T t, UUID transfer, UUID variant) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO transfer_lines (id, tenant_id, transfer_id, variant_id, qty_out) VALUES (?, ?, ?, ?, 1)",
            id, t.tenant(), transfer, variant);
        return id;
    }

    private void transferPiece(T t, UUID transfer, UUID line, String pieceId, String outcome, Instant at) {
        jdbc.update("INSERT INTO transfer_pieces (tenant_id, transfer_id, line_id, piece_id, outcome, created_at) " +
            "VALUES (?, ?, ?, ?, ?, ?)", t.tenant(), transfer, line, pieceId, outcome, Timestamp.from(at));
    }

    private UUID tagSession(T t, UUID location) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO tag_sessions (id, tenant_id, location_id, started_by, ended_at, ended_by) VALUES (?, ?, ?, ?, now(), ?)",
            id, t.tenant(), location, t.user(), t.user());
        return id;
    }

    private void labelBound(T t, String pieceId, UUID tagSession) {
        jdbc.update("INSERT INTO piece_events (tenant_id, piece_id, event_type, from_status, to_status, metadata) " +
                "VALUES (?, ?, 'label_bound', 'available', 'available', ?::jsonb)",
            t.tenant(), pieceId, "{\"tag_session_id\": \"" + tagSession + "\"}");
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
