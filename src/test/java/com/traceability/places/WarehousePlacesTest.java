package com.traceability.places;

import com.traceability.TestPostgres;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.Result;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import com.traceability.tenancy.TenantAwareDataSource;
import com.traceability.tenancy.TenantContext;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
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

import java.awt.image.BufferedImage;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Warehouse places S1 (V189–V191) — schema rules, PlacesService, stickers, RLS.
 *
 *   s1  trigger refuses a zone / rack, an archived place, and a piece with no location; accepts a shelf and a box
 *   s2  allowed parents: every forbidden pair rejected, every allowed pair accepted, a parent in another location rejected
 *   s3  codes: same code in two locations OK; twice in one location rejected (case-insensitive); scan_code unique per tenant
 *   s4  a piece's place must be in the piece's location (FK)
 *   b1  bulk create: Z1 / Z1-R01 … Z1-R03-S4 / BOX-01 …, counts, preview writes nothing
 *   b2  next free Zn (and BOX-nn) skip used numbers, archived ones included; "+ Add zone" proposes it
 *   b3  quick add to an existing zone: racks numbered from its next free R; ids returned for the stickers
 *   b4  one transaction: a failure mid-way leaves nothing
 *   b5  limits refused before anything is written
 *   a1  archive refused while a piece is here or a live place is inside; an empty box on a shelf can be archived
 *   m1  a box moves zone ↔ shelf (same location only); racks / shelves don't move; events recorded
 *   p1  Damaged purpose on a zone or a box only; rename records an event
 *   t1  tree: rolled-up piece counts, "No place yet" counts tagged placeable pieces with no place
 *   k1  sticker PDF: the Q code decodes back, the readable code and the path render; a zone → its shelves + boxes
 *   f1  switch off: every method 404
 *   x1  app_user: bins and bin_events are tenant-scoped (same-tenant positive control); another tenant's place can't be
 *       assigned to a piece
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WarehousePlacesTest {

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

    @Autowired JdbcTemplate  jdbc;
    @Autowired PlacesService places;

    record T(UUID tenant, UUID user, UUID main, UUID other, UUID variant) {}

    final AtomicInteger seq = new AtomicInteger();

    @AfterEach
    void clear() { TenantContext.clear(); }

    // ── s: schema ──────────────────────────────────────────────────────────────

    @Test
    void s1_trigger_refusesZoneRackArchivedAndNoLocation_acceptsShelfAndBox() {
        T t = tenant("s1", true);
        UUID zone = bin(t, t.main(), "zone", null, null, "Z1");
        UUID rack = bin(t, t.main(), "rack", zone, "zone", "Z1-R01");
        UUID shelf = bin(t, t.main(), "shelf", rack, "rack", "Z1-R01-S1");
        UUID box = bin(t, t.main(), "box", shelf, "shelf", "BOX-01");
        String p = piece(t, t.main(), "available");

        assertThatThrownBy(() -> place(p, zone)).hasMessageContaining("not a zone");
        assertThatThrownBy(() -> place(p, rack)).hasMessageContaining("not a rack");
        place(p, shelf);
        assertThat(binOf(p)).isEqualTo(shelf);
        place(p, box);
        assertThat(binOf(p)).isEqualTo(box);

        UUID archivedBox = bin(t, t.main(), "box", zone, "zone", "BOX-02");
        jdbc.update("UPDATE bins SET archived_at = now() WHERE id = ?", archivedBox);
        assertThatThrownBy(() -> place(p, archivedBox)).hasMessageContaining("archived");
        assertThat(binOf(p)).as("a refused write changes nothing").isEqualTo(box);

        String nowhere = piece(t, null, "available");
        assertThatThrownBy(() -> place(nowhere, shelf)).hasMessageContaining("no location");

        for (String status : List.of("on_hold", "damaged")) {
            String q = piece(t, t.main(), status);
            place(q, shelf);
            assertThat(binOf(q)).as(status + " is placeable").isEqualTo(shelf);
        }
        String reserved = piece(t, t.main(), "reserved");
        place(reserved, shelf);
        assertThat(binOf(reserved)).as("a non-placeable status never keeps a place").isNull();
    }

    @Test
    void s2_allowedParents_everyPair() {
        T t = tenant("s2", true);
        Map<String, UUID> parents = new LinkedHashMap<>();
        parents.put("zone", bin(t, t.main(), "zone", null, null, "PZ"));
        parents.put("rack", bin(t, t.main(), "rack", parents.get("zone"), "zone", "PZ-R"));
        parents.put("shelf", bin(t, t.main(), "shelf", parents.get("rack"), "rack", "PZ-R-S"));
        parents.put("box", bin(t, t.main(), "box", parents.get("zone"), "zone", "PZ-B"));
        Map<String, Set<String>> allowed = Map.of(
            "zone", Set.of(), "rack", Set.of("zone"), "shelf", Set.of("rack"), "box", Set.of("zone", "shelf"));
        for (String kind : List.of("zone", "rack", "shelf", "box")) {
            for (String parentKind : List.of("zone", "rack", "shelf", "box")) {
                String code = "C" + seq.incrementAndGet();
                UUID parent = parents.get(parentKind);
                if (allowed.get(kind).contains(parentKind)) {
                    bin(t, t.main(), kind, parent, parentKind, code);
                } else {
                    assertThatThrownBy(() -> bin(t, t.main(), kind, parent, parentKind, code))
                        .as(kind + " under a " + parentKind).isInstanceOf(Exception.class);
                }
            }
            if (!"zone".equals(kind)) {
                assertThatThrownBy(() -> bin(t, t.main(), kind, null, null, "N" + seq.incrementAndGet()))
                    .as(kind + " with no parent").isInstanceOf(Exception.class);
            }
        }
        // parent_kind must be the parent's real kind (FK on (id, kind, location)).
        assertThatThrownBy(() -> bin(t, t.main(), "rack", parents.get("box"), "zone", "LIE"))
            .as("parent_kind that lies about the parent").isInstanceOf(Exception.class);
        // A parent in another location.
        UUID otherZone = bin(t, t.other(), "zone", null, null, "OZ");
        assertThatThrownBy(() -> bin(t, t.main(), "rack", otherZone, "zone", "X-R01"))
            .as("parent in another location").isInstanceOf(Exception.class);
        // Through the service: the same refusals, as PlaceException.
        assertThatThrownBy(() -> as(t, () -> places.create(t.user(),
            new PlacesService.CreateRequest(t.main(), "shelf", parents.get("zone"), null, null))))
            .isInstanceOf(PlaceException.class).extracting(e -> ((PlaceException) e).code())
            .isEqualTo(PlaceException.Code.INVALID_PARENT);
        assertThatThrownBy(() -> as(t, () -> places.create(t.user(),
            new PlacesService.CreateRequest(t.main(), "rack", otherZone, null, null))))
            .isInstanceOf(PlaceException.class);
    }

    @Test
    void s3_codes_uniquePerLocation_caseInsensitive_scanCodeUniquePerTenant() {
        T t = tenant("s3", true);
        bin(t, t.main(), "zone", null, null, "Z9");
        bin(t, t.other(), "zone", null, null, "Z9");
        assertThatThrownBy(() -> bin(t, t.main(), "zone", null, null, "z9"))
            .as("same code twice in one location, any case").isInstanceOf(Exception.class);
        UUID a = bin(t, t.main(), "zone", null, null, "ZA");
        String scan = jdbc.queryForObject("SELECT scan_code FROM bins WHERE id = ?", String.class, a);
        assertThatThrownBy(() -> jdbc.update(
            "INSERT INTO bins (tenant_id, location_id, kind, code, scan_code) VALUES (?, ?, 'zone', 'ZB', ?)",
            t.tenant(), t.other(), scan)).as("scan_code unique per tenant").isInstanceOf(Exception.class);
        T u = tenant("s3b", true);
        jdbc.update("INSERT INTO bins (tenant_id, location_id, kind, code, scan_code) VALUES (?, ?, 'zone', 'ZA', ?)",
            u.tenant(), u.main(), scan);   // another tenant may hold the same number

        // Shapes: a typed code never looks like a piece code or a sticker barcode.
        for (String bad : List.of("P000123", "q123456", "-A", "Z 1", "ZONE/1", "منطقة")) {
            assertThatThrownBy(() -> as(t, () -> places.create(t.user(),
                new PlacesService.CreateRequest(t.main(), "zone", null, bad, null))))
                .as(bad).isInstanceOf(PlaceException.class)
                .extracting(e -> ((PlaceException) e).code()).isEqualTo(PlaceException.Code.INVALID_CODE);
        }
        assertThatThrownBy(() -> as(t, () -> places.create(t.user(),
            new PlacesService.CreateRequest(t.main(), "zone", null, "za", null))))
            .extracting(e -> ((PlaceException) e).code()).isEqualTo(PlaceException.Code.CODE_TAKEN);
    }

    @Test
    void s4_piecePlace_mustBeInThePiecesLocation() {
        T t = tenant("s4", true);
        UUID z = bin(t, t.other(), "zone", null, null, "Z1");
        UUID shelf = bin(t, t.other(), "shelf", bin(t, t.other(), "rack", z, "zone", "Z1-R01"), "rack", "Z1-R01-S1");
        String p = piece(t, t.main(), "available");
        assertThatThrownBy(() -> place(p, shelf)).as("place in another location").isInstanceOf(Exception.class);
    }

    // ── b: bulk create ─────────────────────────────────────────────────────────

    @Test
    void b1_bulkCreate_namesCountsAndPreviewWritesNothing() {
        T t = tenant("b1", true);
        PlacesService.BulkRequest req = new PlacesService.BulkRequest(t.main(), null, 3, 4, 2, "Fast movers");
        PlacesService.BulkPlan preview = as(t, () -> places.bulkPreview(req));
        assertThat(preview.zoneCode()).isEqualTo("Z1");
        assertThat(preview.newZone()).isTrue();
        assertThat(preview.racks()).isEqualTo(3);
        assertThat(preview.shelves()).isEqualTo(12);
        assertThat(preview.boxes()).isEqualTo(2);
        assertThat(preview.total()).isEqualTo(1 + 3 + 12 + 2);
        assertThat(preview.firstShelf()).isEqualTo("Z1-R01-S1");
        assertThat(preview.lastShelf()).isEqualTo("Z1-R03-S4");
        assertThat(preview.firstBox()).isEqualTo("BOX-01");
        assertThat(preview.lastBox()).isEqualTo("BOX-02");
        assertThat(preview.conflicts()).isEmpty();
        assertThat(binCount(t)).as("preview writes nothing").isZero();

        PlacesService.BulkResult result = as(t, () -> places.bulkCreate(t.user(), req));
        assertThat(result.createdIds()).hasSize(18);
        assertThat(codes(t)).containsExactlyInAnyOrder(
            "Z1", "Z1-R01", "Z1-R02", "Z1-R03",
            "Z1-R01-S1", "Z1-R01-S2", "Z1-R01-S3", "Z1-R01-S4",
            "Z1-R02-S1", "Z1-R02-S2", "Z1-R02-S3", "Z1-R02-S4",
            "Z1-R03-S1", "Z1-R03-S2", "Z1-R03-S3", "Z1-R03-S4",
            "BOX-01", "BOX-02");
        assertThat(jdbc.queryForObject("SELECT name FROM bins WHERE id = ?", String.class, result.zoneId()))
            .isEqualTo("Fast movers");
        assertThat(jdbc.queryForList("SELECT DISTINCT scan_code FROM bins WHERE tenant_id = ?", String.class, t.tenant()))
            .hasSize(18).allMatch(c -> c.matches("Q[0-9]{6,}"));
        assertThat(jdbc.queryForObject("SELECT count(*)::int FROM bin_events WHERE tenant_id = ? AND event_type = 'created'",
            Integer.class, t.tenant())).isEqualTo(18);
        assertThat(jdbc.queryForObject(
            "SELECT parent_kind FROM bins WHERE tenant_id = ? AND code = 'BOX-01'", String.class, t.tenant()))
            .as("bulk boxes sit on the zone floor").isEqualTo("zone");

        PlacesService.Tree tree = as(t, () -> places.tree(t.main()));
        assertThat(tree.nodes()).extracting(PlacesService.Node::code).startsWith("Z1", "Z1-R01", "Z1-R01-S1");
        assertThat(tree.nodes()).extracting(PlacesService.Node::code).endsWith("Z1-R03-S4", "BOX-01", "BOX-02");
        PlacesService.Node shelf = tree.nodes().stream().filter(n -> n.code().equals("Z1-R02-S3")).findFirst().orElseThrow();
        assertThat(shelf.label()).isEqualTo("S3");
        assertThat(shelf.path()).containsExactly("Z1", "R02");
        assertThat(tree.nextZoneCode()).isEqualTo("Z2");
    }

    @Test
    void b2_nextFreeZoneAndBox_skipUsedNumbersArchivedIncluded() {
        T t = tenant("b2", true);
        bin(t, t.main(), "zone", null, null, "Z1");
        UUID z3 = bin(t, t.main(), "zone", null, null, "Z3");
        bin(t, t.main(), "box", z3, "zone", "BOX-01");
        UUID box2 = bin(t, t.main(), "box", z3, "zone", "BOX-02");
        jdbc.update("UPDATE bins SET archived_at = now() WHERE id = ?", box2);
        bin(t, t.other(), "zone", null, null, "Z2");   // another location doesn't count

        assertThat(as(t, () -> places.tree(t.main())).nextZoneCode()).isEqualTo("Z2");
        PlacesService.Node zone = as(t, () -> places.create(t.user(), new PlacesService.CreateRequest(t.main(), "zone", null, null, null)));
        assertThat(zone.code()).isEqualTo("Z2");
        assertThat(as(t, () -> places.tree(t.main())).nextZoneCode()).isEqualTo("Z4");
        PlacesService.Node box = as(t, () -> places.create(t.user(), new PlacesService.CreateRequest(null, "box", z3, null, null)));
        assertThat(box.code()).as("BOX-02 is archived but still taken").isEqualTo("BOX-03");
        PlacesService.Node rack = as(t, () -> places.create(t.user(), new PlacesService.CreateRequest(null, "rack", z3, null, null)));
        assertThat(rack.code()).isEqualTo("Z3-R01");
        PlacesService.Node shelf = as(t, () -> places.create(t.user(), new PlacesService.CreateRequest(null, "shelf", rack.id(), null, null)));
        assertThat(shelf.code()).isEqualTo("Z3-R01-S1");
        assertThat(shelf.path()).containsExactly("Z3", "R01");
    }

    @Test
    void b3_quickAdd_toExistingZone_racksFromNextFreeR_returnsIds() {
        T t = tenant("b3", true);
        PlacesService.BulkResult first = as(t, () -> places.bulkCreate(t.user(),
            new PlacesService.BulkRequest(t.main(), null, 2, 3, 0, null)));
        PlacesService.BulkRequest more = new PlacesService.BulkRequest(null, first.zoneId(), 2, 4, 1, null);
        PlacesService.BulkPlan plan = as(t, () -> places.bulkPreview(more));
        assertThat(plan.newZone()).isFalse();
        assertThat(plan.zoneCode()).isEqualTo("Z1");
        assertThat(plan.total()).isEqualTo(2 + 8 + 1);
        assertThat(plan.firstRack()).isEqualTo("Z1-R03");
        assertThat(plan.firstShelf()).isEqualTo("Z1-R03-S1");
        assertThat(plan.lastShelf()).isEqualTo("Z1-R04-S4");
        PlacesService.BulkResult added = as(t, () -> places.bulkCreate(t.user(), more));
        assertThat(added.zoneId()).isEqualTo(first.zoneId());
        assertThat(added.createdIds()).hasSize(11).doesNotContain(first.zoneId());
        assertThat(jdbc.queryForObject("SELECT count(*)::int FROM bins WHERE tenant_id = ? AND kind = 'zone'",
            Integer.class, t.tenant())).isEqualTo(1);
        assertThat(codes(t)).contains("Z1-R03", "Z1-R04-S4", "BOX-01");
        // A rack id isn't a zone.
        UUID rack = jdbc.queryForObject("SELECT id FROM bins WHERE tenant_id = ? AND code = 'Z1-R01'", UUID.class, t.tenant());
        assertThatThrownBy(() -> as(t, () -> places.bulkCreate(t.user(),
            new PlacesService.BulkRequest(null, rack, 1, 1, 0, null)))).isInstanceOf(PlaceException.class);
    }

    @Test
    void b4_bulkCreate_isOneTransaction_failureMidwayLeavesNothing() {
        T t = tenant("b4", true);
        // Fail the 9th row (a shelf of rack R02) from inside the INSERT — after the zone and earlier rows were written.
        jdbc.execute("CREATE FUNCTION b4_fail() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN " +
            "IF NEW.code = 'Z1-R02-S2' THEN RAISE EXCEPTION 'b4 boom'; END IF; RETURN NEW; END $$");
        jdbc.execute("CREATE TRIGGER b4_fail BEFORE INSERT ON bins FOR EACH ROW EXECUTE FUNCTION b4_fail()");
        try {
            assertThatThrownBy(() -> as(t, () -> places.bulkCreate(t.user(),
                new PlacesService.BulkRequest(t.main(), null, 3, 3, 2, null)))).hasStackTraceContaining("b4 boom");
        } finally {
            jdbc.execute("DROP TRIGGER b4_fail ON bins");
            jdbc.execute("DROP FUNCTION b4_fail()");
        }
        assertThat(binCount(t)).as("nothing of the bulk create is left").isZero();
        assertThat(jdbc.queryForObject("SELECT count(*)::int FROM bin_events WHERE tenant_id = ?", Integer.class, t.tenant())).isZero();
        // …and it works afterwards, still as Z1.
        assertThat(as(t, () -> places.bulkCreate(t.user(), new PlacesService.BulkRequest(t.main(), null, 1, 1, 0, null)))
            .plan().zoneCode()).isEqualTo("Z1");
    }

    @Test
    void b5_limits_refusedBeforeAnythingIsWritten() {
        T t = tenant("b5", true);
        for (PlacesService.BulkRequest bad : List.of(
                new PlacesService.BulkRequest(t.main(), null, 100, 1, 0, null),
                new PlacesService.BulkRequest(t.main(), null, 2, 0, 0, null),
                new PlacesService.BulkRequest(t.main(), null, 2, 21, 0, null),
                new PlacesService.BulkRequest(t.main(), null, 0, 0, 201, null),
                new PlacesService.BulkRequest(t.main(), null, -1, 1, 0, null),
                new PlacesService.BulkRequest(t.main(), null, 99, 20, 200, null))) {
            assertThatThrownBy(() -> as(t, () -> places.bulkCreate(t.user(), bad)))
                .as(bad.toString()).isInstanceOf(PlaceException.class)
                .extracting(e -> ((PlaceException) e).code()).isEqualTo(PlaceException.Code.INVALID_BULK);
        }
        assertThat(binCount(t)).isZero();
    }

    // ── a / m / p: archive, move, purpose, rename ───────────────────────────────

    @Test
    void a1_archive_refusedWithPiecesOrLiveChildren_emptyBoxOnShelfAllowed() {
        T t = tenant("a1", true);
        UUID zone = bin(t, t.main(), "zone", null, null, "Z1");
        UUID rack = bin(t, t.main(), "rack", zone, "zone", "Z1-R01");
        UUID shelf = bin(t, t.main(), "shelf", rack, "rack", "Z1-R01-S1");
        UUID box = bin(t, t.main(), "box", shelf, "shelf", "BOX-01");
        String p = piece(t, t.main(), "available");
        place(p, shelf);

        assertThatThrownBy(() -> as(t, () -> places.archive(t.user(), shelf)))
            .extracting(e -> ((PlaceException) e).code()).isEqualTo(PlaceException.Code.PLACE_NOT_EMPTY);
        assertThatThrownBy(() -> as(t, () -> places.archive(t.user(), rack)))
            .extracting(e -> ((PlaceException) e).code()).isEqualTo(PlaceException.Code.PLACE_HAS_CHILDREN);
        assertThatThrownBy(() -> as(t, () -> places.archive(t.user(), zone)))
            .extracting(e -> ((PlaceException) e).code()).isEqualTo(PlaceException.Code.PLACE_HAS_CHILDREN);

        PlacesService.Node archivedBox = as(t, () -> places.archive(t.user(), box));
        assertThat(archivedBox.archived()).as("an empty box on a shelf may be archived").isTrue();
        assertThat(as(t, () -> places.archive(t.user(), box)).archived()).as("again: no-op").isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*)::int FROM bin_events WHERE bin_id = ? AND event_type = 'archived'",
            Integer.class, box)).isEqualTo(1);
        assertThatThrownBy(() -> place(p, box)).hasMessageContaining("archived");

        // A piece that leaves (status) frees the shelf; then the shelf, rack and zone archive bottom-up.
        jdbc.update("UPDATE pieces SET status = 'lost' WHERE id = ?", p);
        as(t, () -> places.archive(t.user(), shelf));
        as(t, () -> places.archive(t.user(), rack));
        assertThat(as(t, () -> places.archive(t.user(), zone)).archived()).isTrue();
        assertThat(as(t, () -> places.tree(t.main())).nodes()).as("archived places leave the tree").isEmpty();
        assertThatThrownBy(() -> as(t, () -> places.rename(t.user(), shelf, "NEW", null)))
            .extracting(e -> ((PlaceException) e).code()).isEqualTo(PlaceException.Code.PLACE_ARCHIVED);
    }

    @Test
    void m1_boxMoves_zoneShelf_sameLocationOnly_piecesStayInIt() {
        T t = tenant("m1", true);
        UUID zone = bin(t, t.main(), "zone", null, null, "Z1");
        UUID zone2 = bin(t, t.main(), "zone", null, null, "Z2");
        UUID rack = bin(t, t.main(), "rack", zone, "zone", "Z1-R01");
        UUID shelf = bin(t, t.main(), "shelf", rack, "rack", "Z1-R01-S1");
        UUID box = bin(t, t.main(), "box", zone, "zone", "BOX-01");
        UUID elsewhere = bin(t, t.other(), "zone", null, null, "Z1");
        String p = piece(t, t.main(), "available");
        place(p, box);

        PlacesService.Node onShelf = as(t, () -> places.move(t.user(), box, shelf));
        assertThat(onShelf.parentId()).isEqualTo(shelf);
        assertThat(onShelf.path()).containsExactly("Z1", "R01", "S1");
        assertThat(onShelf.code()).as("a box keeps its code").isEqualTo("BOX-01");
        assertThat(binOf(p)).as("its pieces move with it").isEqualTo(box);
        assertThat(as(t, () -> places.move(t.user(), box, zone2)).parentId()).isEqualTo(zone2);

        assertThatThrownBy(() -> as(t, () -> places.move(t.user(), box, elsewhere)))
            .extracting(e -> ((PlaceException) e).code()).isEqualTo(PlaceException.Code.INVALID_PARENT);
        assertThatThrownBy(() -> as(t, () -> places.move(t.user(), box, rack)))
            .extracting(e -> ((PlaceException) e).code()).isEqualTo(PlaceException.Code.INVALID_PARENT);
        assertThatThrownBy(() -> as(t, () -> places.move(t.user(), shelf, zone2)))
            .extracting(e -> ((PlaceException) e).code()).isEqualTo(PlaceException.Code.NOT_A_BOX);
        assertThat(jdbc.queryForList("SELECT to_parent_id FROM bin_events WHERE bin_id = ? AND event_type = 'moved' " +
            "ORDER BY occurred_at, id", UUID.class, box)).containsExactlyInAnyOrder(shelf, zone2);
        assertThatThrownBy(() -> jdbc.update("UPDATE bins SET location_id = ? WHERE id = ?", t.other(), box))
            .as("a place never changes location (FK: its pieces / its parent are in this one)").isInstanceOf(Exception.class);
    }

    @Test
    void p1_damagedPurpose_zoneOrBoxOnly_renameRecordsEvent() {
        T t = tenant("p1", true);
        UUID zone = bin(t, t.main(), "zone", null, null, "Z1");
        UUID rack = bin(t, t.main(), "rack", zone, "zone", "Z1-R01");
        UUID box = bin(t, t.main(), "box", zone, "zone", "BOX-01");
        assertThat(as(t, () -> places.setPurpose(t.user(), zone, "damaged")).purpose()).isEqualTo("damaged");
        assertThat(as(t, () -> places.setPurpose(t.user(), box, "damaged")).purpose()).isEqualTo("damaged");
        assertThat(as(t, () -> places.setPurpose(t.user(), box, null)).purpose()).isNull();
        assertThatThrownBy(() -> as(t, () -> places.setPurpose(t.user(), rack, "damaged")))
            .extracting(e -> ((PlaceException) e).code()).isEqualTo(PlaceException.Code.PURPOSE_NOT_ALLOWED);
        assertThatThrownBy(() -> jdbc.update("UPDATE bins SET purpose = 'damaged' WHERE id = ?", rack))
            .as("CHECK too").isInstanceOf(Exception.class);

        PlacesService.Node renamed = as(t, () -> places.rename(t.user(), rack, "Z1-R09", "Back wall"));
        assertThat(renamed.code()).isEqualTo("Z1-R09");
        assertThat(renamed.name()).isEqualTo("Back wall");
        PlacesService.Detail d = as(t, () -> places.detail(rack));
        assertThat(d.history()).first().satisfies(h -> {
            assertThat(h.eventType()).isEqualTo("renamed");
            assertThat(h.fromCode()).isEqualTo("Z1-R01");
            assertThat(h.toCode()).isEqualTo("Z1-R09");
        });
        assertThat(as(t, () -> places.rename(t.user(), rack, null, "")).name()).as("'' clears the name").isNull();
        assertThatThrownBy(() -> as(t, () -> places.rename(t.user(), rack, "box-01", null)))
            .extracting(e -> ((PlaceException) e).code()).isEqualTo(PlaceException.Code.CODE_TAKEN);
    }

    // ── t: tree counts ─────────────────────────────────────────────────────────

    @Test
    void t1_tree_rolledUpCounts_andNoPlaceYet() {
        T t = tenant("t1", true);
        UUID zone = bin(t, t.main(), "zone", null, null, "Z1");
        UUID rack = bin(t, t.main(), "rack", zone, "zone", "Z1-R01");
        UUID shelf = bin(t, t.main(), "shelf", rack, "rack", "Z1-R01-S1");
        UUID box = bin(t, t.main(), "box", shelf, "shelf", "BOX-01");
        UUID floorBox = bin(t, t.main(), "box", zone, "zone", "BOX-02");
        for (int i = 0; i < 2; i++) place(piece(t, t.main(), "available"), shelf);
        for (int i = 0; i < 3; i++) place(piece(t, t.main(), "available"), box);
        place(piece(t, t.main(), "damaged"), floorBox);
        piece(t, t.main(), "available");                // no place yet
        piece(t, t.main(), "on_hold");                  // no place yet
        piece(t, t.main(), "reserved");                 // not placeable: not waiting
        untaggedPiece(t, t.main());                     // untagged: out of places in v1
        piece(t, t.other(), "available");               // another location

        PlacesService.Tree tree = as(t, () -> places.tree(t.main()));
        Map<String, PlacesService.Node> by = new HashMap<>();
        tree.nodes().forEach(n -> by.put(n.code(), n));
        assertThat(by.get("Z1-R01-S1").pieces()).isEqualTo(2);
        assertThat(by.get("Z1-R01-S1").totalPieces()).isEqualTo(5);
        assertThat(by.get("Z1-R01").totalPieces()).isEqualTo(5);
        assertThat(by.get("Z1").totalPieces()).isEqualTo(6);
        assertThat(by.get("BOX-01").depth()).isEqualTo(3);
        assertThat(tree.noPlaceYet()).isEqualTo(2);

        PlacesService.Detail d = as(t, () -> places.detail(shelf));
        assertThat(d.contents()).singleElement().satisfies(c -> assertThat(c.count()).isEqualTo(2));
        assertThat(d.boxes()).singleElement().satisfies(b -> assertThat(b.totalPieces()).isEqualTo(3));
        assertThat(d.locationName()).isEqualTo("Main Warehouse");
    }

    // ── k: stickers ────────────────────────────────────────────────────────────

    @Test
    void k1_stickerPdf_qCodeDecodes_codeAndPathRender_zoneExpands() throws Exception {
        T t = tenant("k1", true);
        PlacesService.BulkResult r = as(t, () -> places.bulkCreate(t.user(),
            new PlacesService.BulkRequest(t.main(), null, 1, 2, 1, null)));
        UUID shelf = jdbc.queryForObject("SELECT id FROM bins WHERE tenant_id = ? AND code = 'Z1-R01-S2'", UUID.class, t.tenant());
        String scan = jdbc.queryForObject("SELECT scan_code FROM bins WHERE id = ?", String.class, shelf);

        byte[] one = as(t, () -> places.stickers(List.of(shelf)));
        try (PDDocument doc = Loader.loadPDF(one)) {
            assertThat(doc.getNumberOfPages()).as("one sticker, no separator").isEqualTo(1);
            assertThat(decode(doc, 0)).isEqualTo(scan);
            String text = new PDFTextStripper().getText(doc);
            assertThat(text).contains("Z1-R01-S2").contains("Main Warehouse · Z1 › R01").contains(scan);
        }

        byte[] zone = as(t, () -> places.stickers(List.of(r.zoneId())));
        try (PDDocument doc = Loader.loadPDF(zone)) {
            assertThat(doc.getNumberOfPages()).as("separator + 2 shelves + 1 box (no zone / rack sticker)").isEqualTo(4);
            String text = new PDFTextStripper().getText(doc);
            assertThat(text).contains("Z1-R01-S1").contains("Z1-R01-S2").contains("BOX-01");
            assertThat(text).doesNotContain("Z1-R01\n");
            assertThat(decode(doc, 1)).matches("Q[0-9]{6,}");
        }
        assertThatThrownBy(() -> as(t, () -> places.stickers(List.of())))
            .extracting(e -> ((PlaceException) e).code()).isEqualTo(PlaceException.Code.NO_STICKERS);
    }

    // ── f: switch off ──────────────────────────────────────────────────────────

    @Test
    void f1_switchOff_everyMethod404() {
        T t = tenant("f1", false);
        UUID zone = bin(t, t.main(), "zone", null, null, "Z1");
        List<Runnable> calls = List.of(
            () -> places.tree(t.main()),
            () -> places.detail(zone),
            () -> places.create(t.user(), new PlacesService.CreateRequest(t.main(), "zone", null, null, null)),
            () -> places.bulkPreview(new PlacesService.BulkRequest(t.main(), null, 1, 1, 0, null)),
            () -> places.bulkCreate(t.user(), new PlacesService.BulkRequest(t.main(), null, 1, 1, 0, null)),
            () -> places.rename(t.user(), zone, "Z7", null),
            () -> places.setPurpose(t.user(), zone, "damaged"),
            () -> places.archive(t.user(), zone),
            () -> { try { places.stickers(List.of(zone)); } catch (java.io.IOException e) { throw new RuntimeException(e); } });
        for (Runnable call : calls) {
            assertThatThrownBy(() -> TenantContext.runAs(t.tenant(), call))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("404");
        }
        assertThat(codes(t)).containsExactly("Z1");
    }

    // ── x: RLS ─────────────────────────────────────────────────────────────────

    @Test
    void x1_appUser_binsAndBinEventsTenantScoped_withSameTenantPositiveControl() {
        T mine = tenant("x1a", true), theirs = tenant("x1b", true);
        DriverManagerDataSource raw = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "app_user", "testpw");
        TenantAwareDataSource ds = new TenantAwareDataSource(raw);
        JdbcTemplate app = new JdbcTemplate(ds);
        TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        PlacesService appPlaces = new PlacesService(app);

        // Both tenants build places through the real service on the app_user connection.
        PlacesService.BulkResult own = TenantContext.runAs(mine.tenant(), () -> tx.execute(s ->
            appPlaces.bulkCreate(mine.user(), new PlacesService.BulkRequest(mine.main(), null, 1, 1, 1, null))));
        PlacesService.BulkResult other = TenantContext.runAs(theirs.tenant(), () -> tx.execute(s ->
            appPlaces.bulkCreate(theirs.user(), new PlacesService.BulkRequest(theirs.main(), null, 1, 1, 0, null))));
        UUID theirShelf = jdbc.queryForObject("SELECT id FROM bins WHERE tenant_id = ? AND kind = 'shelf'", UUID.class, theirs.tenant());
        UUID myShelf = jdbc.queryForObject("SELECT id FROM bins WHERE tenant_id = ? AND kind = 'shelf'", UUID.class, mine.tenant());

        TenantContext.set(mine.tenant());
        try {
            assertThat((Integer) tx.execute(s -> app.queryForObject("SELECT count(*)::int FROM bins", Integer.class)))
                .as("same-tenant positive control: my 4 places").isEqualTo(4);
            assertThat((Integer) tx.execute(s -> app.queryForObject("SELECT count(*)::int FROM bin_events", Integer.class)))
                .as("same-tenant positive control: my 4 created events").isEqualTo(4);
            assertThat((Integer) tx.execute(s -> app.queryForObject(
                "SELECT count(*)::int FROM bins WHERE tenant_id = ?", Integer.class, theirs.tenant()))).isZero();
            assertThat((Integer) tx.execute(s -> app.queryForObject(
                "SELECT count(*)::int FROM bin_events WHERE tenant_id = ?", Integer.class, theirs.tenant()))).isZero();
            assertThat(tx.execute(s -> appPlaces.tree(mine.main())).nodes()).hasSize(4);
            assertThatThrownBy(() -> tx.execute(s -> appPlaces.detail(other.zoneId())))
                .extracting(e -> ((PlaceException) e).code()).isEqualTo(PlaceException.Code.PLACE_NOT_FOUND);
            assertThatThrownBy(() -> tx.execute(s -> appPlaces.tree(theirs.main())))
                .extracting(e -> ((PlaceException) e).code()).isEqualTo(PlaceException.Code.LOCATION_NOT_FOUND);
            assertThatThrownBy(() -> tx.execute(s -> app.update(
                "INSERT INTO bins (tenant_id, location_id, kind, code, scan_code) VALUES (?, ?, 'zone', 'ZX', 'Q999999')",
                theirs.tenant(), theirs.main()))).as("RLS WITH CHECK").isInstanceOf(Exception.class);
            assertThatThrownBy(() -> tx.execute(s -> app.update(
                "INSERT INTO bin_events (tenant_id, bin_id, event_type) VALUES (?, ?, 'renamed')",
                theirs.tenant(), other.zoneId()))).as("RLS WITH CHECK").isInstanceOf(Exception.class);
            assertThat((Integer) tx.execute(s -> app.update("UPDATE bins SET name = 'mine now' WHERE id = ?", other.zoneId())))
                .as("another tenant's place is invisible to UPDATE").isZero();

            // Grants: no DELETE on bins, no UPDATE / DELETE on bin_events, location / kind / scan_code fixed.
            assertThatThrownBy(() -> tx.execute(s -> app.update("DELETE FROM bins WHERE id = ?", own.zoneId())))
                .isInstanceOf(Exception.class);
            assertThatThrownBy(() -> tx.execute(s -> app.update("UPDATE bin_events SET event_type = 'moved'")))
                .isInstanceOf(Exception.class);
            assertThatThrownBy(() -> tx.execute(s -> app.update("UPDATE bins SET scan_code = 'Q000001' WHERE id = ?", own.zoneId())))
                .isInstanceOf(Exception.class);
            assertThatThrownBy(() -> tx.execute(s -> app.update("UPDATE bins SET location_id = location_id WHERE id = ?", own.zoneId())))
                .isInstanceOf(Exception.class);

            // The trigger runs as app_user: my piece onto my shelf works (positive control); onto another tenant's
            // shelf it can't — RLS hides that place from the trigger's lookup.
            String p = piece(mine, mine.main(), "available");
            assertThat((Integer) tx.execute(s -> app.update("UPDATE pieces SET bin_id = ? WHERE id = ?", myShelf, p))).isEqualTo(1);
            assertThat(binOf(p)).isEqualTo(myShelf);
            assertThatThrownBy(() -> tx.execute(s -> app.update("UPDATE pieces SET bin_id = ? WHERE id = ?", theirShelf, p)))
                .hasStackTraceContaining("not found");
            assertThat(binOf(p)).isEqualTo(myShelf);
        } finally {
            TenantContext.clear();
        }
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private <R> R as(T t, java.util.concurrent.Callable<R> call) {
        return TenantContext.runAs(t.tenant(), call);
    }

    private T tenant(String name, boolean placesOn) {
        UUID tenant = UUID.randomUUID(), user = UUID.randomUUID(), store = UUID.randomUUID(), product = UUID.randomUUID();
        UUID variant = UUID.randomUUID(), main = UUID.randomUUID(), other = UUID.randomUUID();
        String tag = name + "-" + tenant.toString().substring(0, 6);
        jdbc.update("INSERT INTO tenants (id, name, places_enabled) VALUES (?, ?, ?)", tenant, "Places " + name, placesOn);
        jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role) VALUES (?, ?, 'Owner', ?, 'h', 'owner')",
            user, tenant, tag + "@places.test");
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status) VALUES (?, ?, 'shopify', ?, 'disconnected')",
            store, tenant, tag + ".myshopify.com");
        jdbc.update("INSERT INTO products (id, tenant_id, store_id, external_id, title, status) VALUES (?, ?, ?, ?, 'Tee', 'active')",
            product, tenant, store, "P-" + tag);
        jdbc.update("INSERT INTO variants (id, tenant_id, product_id, external_id, title, sku) VALUES (?, ?, ?, ?, 'M', ?)",
            variant, tenant, product, "V-" + tag, "SKU-" + tag);
        jdbc.update("INSERT INTO locations (id, tenant_id, name, is_default, is_fulfillment) VALUES (?, ?, 'Main Warehouse', true, true)",
            main, tenant);
        jdbc.update("INSERT INTO locations (id, tenant_id, name) VALUES (?, ?, 'Second Store')", other, tenant);
        return new T(tenant, user, main, other, variant);
    }

    /** A place written directly (as postgres) — schema tests. */
    private UUID bin(T t, UUID location, String kind, UUID parent, String parentKind, String code) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO bins (id, tenant_id, location_id, kind, parent_id, parent_kind, code, scan_code) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            id, t.tenant(), location, kind, parent, parentKind, code, String.format("Q%06d", 500000 + seq.incrementAndGet()));
        return id;
    }

    private String piece(T t, UUID location, String status) {
        int k = seq.incrementAndGet();
        String id = "01PLC" + UUID.randomUUID().toString().replace("-", "").substring(0, 21).toUpperCase();
        jdbc.update("INSERT INTO pieces (id, tenant_id, variant_id, status, barcode, short_code, current_location_id) " +
                "VALUES (?, ?, ?, ?::piece_status, ?, ?, ?)",
            id, t.tenant(), t.variant(), status, "PC-" + id, String.format("P%06d", 700000 + k), location);
        return id;
    }

    private void untaggedPiece(T t, UUID location) {
        String id = "01PLU" + UUID.randomUUID().toString().replace("-", "").substring(0, 21).toUpperCase();
        jdbc.update("INSERT INTO pieces (id, tenant_id, variant_id, status, barcode, current_location_id) " +
            "VALUES (?, ?, ?, 'available', ?, ?)", id, t.tenant(), t.variant(), "PC-" + id, location);
    }

    private void place(String pieceId, UUID binId) {
        jdbc.update("UPDATE pieces SET bin_id = ? WHERE id = ?", binId, pieceId);
    }

    private UUID binOf(String pieceId) {
        return jdbc.queryForObject("SELECT bin_id FROM pieces WHERE id = ?", UUID.class, pieceId);
    }

    private int binCount(T t) {
        return jdbc.queryForObject("SELECT count(*)::int FROM bins WHERE tenant_id = ?", Integer.class, t.tenant());
    }

    private List<String> codes(T t) {
        return jdbc.queryForList("SELECT code FROM bins WHERE tenant_id = ?", String.class, t.tenant());
    }

    private static String decode(PDDocument doc, int page) throws Exception {
        BufferedImage img = new PDFRenderer(doc).renderImageWithDPI(page, 300);
        Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
        hints.put(DecodeHintType.POSSIBLE_FORMATS, List.of(BarcodeFormat.CODE_128));
        hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
        Result r = new MultiFormatReader().decode(
            new BinaryBitmap(new HybridBinarizer(new BufferedImageLuminanceSource(img))), hints);
        return r.getText();
    }
}
