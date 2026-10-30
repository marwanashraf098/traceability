package com.traceability;

import com.traceability.integrations.bosta.AwbPrintResult;
import com.traceability.integrations.bosta.BostaAwbRejectedException;
import com.traceability.integrations.bosta.BostaAwbService;
import com.traceability.integrations.bosta.BostaException;
import com.traceability.integrations.bosta.BostaGateway;
import com.traceability.integrations.bosta.BostaTransientException;
import com.traceability.inventory.PackPrintBatchService;
import com.traceability.inventory.PackPrintBatchService.PrintBatchResult;
import com.traceability.security.EncryptionService;
import com.traceability.tenancy.TenantContext;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.jobrunr.scheduling.JobScheduler;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.ByteArrayOutputStream;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Deleted-in-Bosta workstream, slice 1 — one deleted or bad waybill never fails a whole print.
 *
 * BostaGateway is mocked as a fake Bosta: a mass-awb request holding a "deleted" tracking number is
 * refused the way the real gateway reports Bosta's 400 (BostaAwbRejectedException carrying Bosta's
 * message); any other request returns a PDF with one page per tracking number.
 *
 * Bosta's real body (The Snouts, 2026-10-02, shipment 3927856238) is stored in prod only cut at 120
 * characters: {"success":false,"message":"Couldn't find deliveries with ids: [] or trackingNum…  The
 * PREFIX mode below uses exactly that known text (no tracking number in it → bisect); NAMED mode
 * assumes the message goes on "…trackingNumbers: [<tn>, …]" (our reconstruction of the cut part).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PrintSkipDeletedWaybillTest {

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

    /** The part of Bosta's real 400 message we have verbatim (prod stores it cut at 120 chars). */
    static final String REAL_PREFIX = "Couldn't find deliveries with ids: [] or trackingNum";

    @Autowired JdbcTemplate          jdbc;            // postgres — fixtures only
    @Autowired PackPrintBatchService printService;
    @Autowired BostaAwbService       awbService;
    @Autowired EncryptionService     encryptionService;
    @MockBean  BostaGateway          bostaGateway;
    @MockBean  JobScheduler          jobScheduler;

    private enum Message { NAMED, PREFIX, WRONG }

    private final Set<String> deleted = new HashSet<>();
    private final List<List<String>> calls = new ArrayList<>();
    private boolean calledInsideTransaction;
    private Message message = Message.NAMED;
    private String wrongName;
    private long counter = 0;

    @BeforeEach
    void fakeBosta() {
        deleted.clear();
        calls.clear();
        calledInsideTransaction = false;
        message = Message.NAMED;
        reset(bostaGateway);
        when(bostaGateway.printMassAwb(anyString(), anyList(), anyString(), anyString()))
            .thenAnswer(inv -> {
                List<String> sent = List.copyOf(inv.getArgument(1));
                calls.add(sent);
                if (TransactionSynchronizationManager.isActualTransactionActive()) calledInsideTransaction = true;
                List<String> bad = sent.stream().filter(deleted::contains).toList();
                if (!bad.isEmpty()) throw rejection(bad);
                return new AwbPrintResult(pdf(sent), null);
            });
    }

    @AfterEach
    void clearContext() { TenantContext.clear(); }

    /** What BostaHttpGateway throws for Bosta's 400 on a request holding these deleted waybills. */
    private BostaAwbRejectedException rejection(List<String> bad) {
        String msg = switch (message) {
            case NAMED  -> "Couldn't find deliveries with ids: [] or trackingNumbers: [" + String.join(", ", bad) + "]";
            case PREFIX -> REAL_PREFIX;
            // Names an innocent waybill of the request and a number that isn't in it at all — never the culprit.
            case WRONG  -> "Couldn't find deliveries with ids: [] or trackingNumbers: [" + wrongName + ", 9999999999]";
        };
        String body = "{\"success\":false,\"message\":\"" + msg + "\"}";
        return new BostaAwbRejectedException("Bosta mass-awb error (400 BAD_REQUEST): " + body, 400, msg, null);
    }

    // ── 49 with one deleted ───────────────────────────────────────────────────

    @Test
    void d1_chunkOf49_oneDeleted_unnamed_48Printed_1Reported_noMissingAwbOnThe48() {
        message = Message.PREFIX;                         // Bosta's message names nobody → bisect
        Fixture f = fixture("D1");
        List<String> tns = f.orders(49);
        String culprit = tns.get(30);
        deleted.add(culprit);
        UUID cShip = f.shipmentOf(culprit);
        String stateBefore = state(cShip);

        PrintBatchResult r = as(f.tenant, () -> printService.print("all", "A6", "oldest", f.owner));

        assertThat(r.waybillCount()).isEqualTo(48);
        assertThat(printed(f.tenant)).hasSize(48).doesNotContain(culprit);
        assertThat(r.excluded()).extracting(PackPrintBatchService.Excluded::trackingNumber).containsExactly(culprit);
        assertThat(r.excluded().get(0).reason()).isEqualTo(BostaAwbService.DELETED_IN_BOSTA);
        // Only the culprit is marked (→ one missing_awb), never the 48.
        assertThat(failedReasons(f.tenant)).containsOnlyKeys(culprit);
        assertThat(failedReasons(f.tenant).get(culprit)).isEqualTo(BostaAwbService.DELETED_IN_BOSTA);
        // Extra calls within the cap (6 to bisect + 1 to print the rest + 1 spare); never > 49 per call.
        assertThat(calls.size() - 1).isBetween(1, 8);
        assertThat(calls).allSatisfy(c -> assertThat(c.size()).isLessThanOrEqualTo(49));
        assertThat(calledInsideTransaction).isFalse();
        // Slice 1 never touches the shipment's state, history, provider_not_found_at or pieces.
        assertThat(state(cShip)).isEqualTo(stateBefore);
        assertThat(jdbc.queryForObject("SELECT provider_not_found_at IS NULL FROM shipments WHERE id = ?",
            Boolean.class, cShip)).isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM shipment_status_history WHERE shipment_id = ?",
            Integer.class, cShip)).isZero();
    }

    @Test
    void d1b_namedInMessage_oneExtraCall() {
        Fixture f = fixture("D1B");
        List<String> tns = f.orders(49);
        deleted.add(tns.get(0));

        PrintBatchResult r = as(f.tenant, () -> printService.print("all", "A6", "oldest", f.owner));

        assertThat(r.waybillCount()).isEqualTo(48);
        assertThat(calls).hasSize(2);
        assertThat(calls.get(1)).hasSize(48).doesNotContain(tns.get(0));
        assertThat(failedReasons(f.tenant)).containsOnlyKeys(tns.get(0));
    }

    // ── Two culprits ──────────────────────────────────────────────────────────

    @Test
    void d2_twoCulprits_bothIsolatedWithinTheCap() {
        Fixture f = fixture("D2");
        List<String> tns = f.orders(49);
        deleted.addAll(List.of(tns.get(3), tns.get(44)));

        PrintBatchResult r = as(f.tenant, () -> printService.print("all", "A6", "oldest", f.owner));

        assertThat(r.waybillCount()).isEqualTo(47);
        assertThat(r.excluded()).extracting(PackPrintBatchService.Excluded::trackingNumber)
            .containsExactlyInAnyOrder(tns.get(3), tns.get(44));
        assertThat(failedReasons(f.tenant)).containsOnlyKeys(tns.get(3), tns.get(44));
        assertThat(calls.size() - 1).isLessThanOrEqualTo(8);
    }

    @Test
    void d2b_twoUnnamedCulprits_neighbours_bisectedWithinTheCap() {
        // Two unnamed culprits are only guaranteed when Bosta names them (d2). Bisecting two unnamed
        // ones needs ~11 calls in general; neighbours share one search path and fit in the cap of 8.
        message = Message.PREFIX;
        Fixture f = fixture("D2B");
        List<String> tns = f.orders(49);
        deleted.addAll(List.of(tns.get(46), tns.get(47)));

        PrintBatchResult r = as(f.tenant, () -> printService.print("all", "A6", "oldest", f.owner));

        assertThat(r.waybillCount()).isEqualTo(47);
        assertThat(failedReasons(f.tenant)).containsOnlyKeys(tns.get(46), tns.get(47));
        assertThat(calls.size() - 1).isLessThanOrEqualTo(8);
    }

    @Test
    void d2c_capReached_restReportedUnisolated_noInnocentMarked() {
        message = Message.PREFIX;
        Fixture f = fixture("D2C");
        List<String> tns = f.orders(49);
        deleted.addAll(List.of(tns.get(0), tns.get(12), tns.get(25), tns.get(37), tns.get(48)));

        PrintBatchResult r = as(f.tenant, () -> printService.print("all", "A6", "oldest", f.owner));

        assertThat(calls.size() - 1).isLessThanOrEqualTo(8);
        Set<String> marked = failedReasons(f.tenant).keySet();
        assertThat(deleted).containsAll(marked);                      // nobody innocent marked
        List<String> unisolated = r.excluded().stream()
            .filter(x -> x.reason().equals(BostaAwbService.BOSTA_REJECTED_UNISOLATED))
            .map(PackPrintBatchService.Excluded::trackingNumber).toList();
        assertThat(unisolated).isNotEmpty();
        // Every waybill is printed or reported, exactly once.
        Set<String> accounted = new HashSet<>(printed(f.tenant));
        r.excluded().forEach(x -> assertThat(accounted.add(x.trackingNumber())).isTrue());
        assertThat(accounted).containsExactlyInAnyOrderElementsOf(tns);
    }

    // ── Convergence: two unnamed culprits, "print again" ─────────────────────

    @Test
    void d6_twoUnnamedCulprits_printAgainConverges_within3Runs_neverMarksAnInnocent() {
        message = Message.PREFIX;
        int[][] pairs = { {40, 47}, {0, 48}, {3, 44}, {24, 25}, {12, 37}, {0, 1}, {47, 48}, {6, 30}, {17, 41}, {31, 32} };
        for (int[] pair : pairs) {
            deleted.clear();
            Fixture f = fixture("D6-" + pair[0] + "-" + pair[1]);
            List<String> tns = f.orders(49);
            Set<String> culprits = Set.of(tns.get(pair[0]), tns.get(pair[1]));
            deleted.addAll(culprits);

            int runs = 0;
            while (printed(f.tenant).size() < 47) {
                runs++;
                assertThat(runs).as("pair %s: runs", Arrays.toString(pair)).isLessThanOrEqualTo(3);
                Timestamp runStart = jdbc.queryForObject("SELECT clock_timestamp()", Timestamp.class);
                calls.clear();
                as(f.tenant, () -> printService.print("new", "A6", "oldest", f.owner));

                // Never an innocent; at least one culprit marked by THIS run; each request within the cap.
                assertThat(culprits).as("pair %s run %d", Arrays.toString(pair), runs)
                    .containsAll(failedReasons(f.tenant).keySet());
                Integer markedNow = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM shipments WHERE tenant_id = ? AND awb_print_failed_at >= ? " +
                    "AND awb_print_failed_reason = 'DELETED_IN_BOSTA'", Integer.class, f.tenant, runStart);
                assertThat(markedNow).as("pair %s run %d marked", Arrays.toString(pair), runs).isPositive();
                assertThat(calls.size()).isLessThanOrEqualTo(2 * (1 + 8));   // ≤ 2 requests, each 1 + cap
            }
            assertThat(printed(f.tenant)).as("pair %s", Arrays.toString(pair))
                .hasSize(47).doesNotContainAnyElementsOf(culprits);
            assertThat(failedReasons(f.tenant).keySet()).isEqualTo(culprits);
        }
    }

    // ── Bosta's message names the wrong number ───────────────────────────────

    @Test
    void d7_messageNamesWrongNumber_hintIgnored_bisectFindsTheCulprit_innocentNeverMarked() {
        message = Message.WRONG;
        Fixture f = fixture("D7");
        List<String> tns = f.orders(49);
        String culprit = tns.get(20);
        wrongName = tns.get(5);                          // innocent, in the chunk
        deleted.add(culprit);

        PrintBatchResult r = as(f.tenant, () -> printService.print("all", "A6", "oldest", f.owner));

        assertThat(r.waybillCount()).isEqualTo(48);
        assertThat(printed(f.tenant)).contains(wrongName).doesNotContain(culprit);
        assertThat(failedReasons(f.tenant)).containsOnlyKeys(culprit);
        assertThat(calls.get(1)).hasSize(48).doesNotContain(wrongName);   // the hint was tried once…
        assertThat(calls.size() - 1).isLessThanOrEqualTo(8);              // …then bisect, within the cap
        assertThat(calls).noneSatisfy(c -> assertThat(c).contains("9999999999"));
    }

    @Test
    void d7b_singleHintedWaybill_markedOnlyAfterThePrintWithoutItSucceeds() {
        Fixture f = fixture("D7B");
        List<String> tns = f.orders(3);
        deleted.add(tns.get(1));

        as(f.tenant, () -> printService.print("all", "A6", "oldest", f.owner));

        assertThat(calls).hasSize(2);
        assertThat(calls.get(1)).containsExactly(tns.get(0), tns.get(2));   // the confirming print
        assertThat(failedReasons(f.tenant)).containsOnlyKeys(tns.get(1));
    }

    // ── Pre-stamped provider_not_found_at ─────────────────────────────────────

    @Test
    void d3_preStampedNotFound_neverSentToBosta_reportedSkipped_notMarkedFailed() {
        Fixture f = fixture("D3");
        List<String> tns = f.orders(5);
        String stamped = tns.get(2);
        jdbc.update("UPDATE shipments SET provider_not_found_at = now() WHERE tracking_number = ?", stamped);

        PrintBatchResult r = as(f.tenant, () -> printService.print("all", "A6", "oldest", f.owner));

        assertThat(calls).hasSize(1);
        assertThat(calls).noneSatisfy(c -> assertThat(c).contains(stamped));
        assertThat(r.waybillCount()).isEqualTo(4);
        assertThat(r.excluded()).extracting(PackPrintBatchService.Excluded::trackingNumber, PackPrintBatchService.Excluded::reason)
            .containsExactly(tuple(stamped, BostaAwbService.DELETED_IN_BOSTA));
        assertThat(failedReasons(f.tenant)).isEmpty();
    }

    // ── Other errors unchanged ────────────────────────────────────────────────

    @Test
    void d4_5xx_noBisect_notMarked_reportedTemporary() {
        // Merged-PDF slice A (approved edit): a 5xx is temporary — never bisected, never marked failed.
        Fixture f = fixture("D4");
        f.orders(10);
        doAnswer(inv -> { calls.add(List.copyOf(inv.getArgument(1)));
                          throw new BostaTransientException("Bosta 5xx on mass-awb"); })
            .when(bostaGateway).printMassAwb(anyString(), anyList(), anyString(), anyString());

        PrintBatchResult r = as(f.tenant, () -> printService.print("all", "A6", "oldest", f.owner));

        assertThat(calls).hasSize(1);
        assertThat(r.batchId()).isNull();
        assertThat(r.excluded()).hasSize(10)
            .allSatisfy(x -> assertThat(x.reason()).isEqualTo(BostaAwbService.BOSTA_TEMPORARY));
        assertThat(failedReasons(f.tenant)).isEmpty();
    }

    @Test
    void d4b_other400_and429_noBisect() {
        Fixture f = fixture("D4B");
        f.orders(10);
        doAnswer(inv -> { calls.add(List.copyOf(inv.getArgument(1)));
                          throw new BostaAwbRejectedException("Bosta mass-awb error (400 BAD_REQUEST): x", 400,
                              "requestedAwbType must be one of [A4, A6]", null); })
            .when(bostaGateway).printMassAwb(anyString(), anyList(), anyString(), anyString());
        as(f.tenant, () -> printService.print("all", "A6", "oldest", f.owner));
        assertThat(calls).hasSize(1);

        calls.clear();
        doAnswer(inv -> { calls.add(List.copyOf(inv.getArgument(1)));
                          throw new BostaException("Bosta mass-awb error (429 TOO_MANY_REQUESTS): {}"); })
            .when(bostaGateway).printMassAwb(anyString(), anyList(), anyString(), anyString());
        as(f.tenant, () -> printService.print("all", "A6", "oldest", f.owner));
        assertThat(calls).hasSize(1);
    }

    // ── Single print ──────────────────────────────────────────────────────────

    @Test
    void d5_singlePrint_deletedWaybill_reportedDeletedInBosta() {
        Fixture f = fixture("D5");
        String tn = f.orders(1).get(0);
        UUID ship = f.shipmentOf(tn);
        deleted.add(tn);

        BostaAwbService.AwbBatchResult r = awbService.printAwb(f.tenant, List.of(ship), null, null);

        assertThat(r.pdfBase64List()).isEmpty();
        assertThat(r.exceptions()).extracting(BostaAwbService.AwbException::reason)
            .containsExactly(BostaAwbService.DELETED_IN_BOSTA);
        assertThat(calls).hasSize(1);                                  // a single bad waybill costs no search

        // Pre-stamped: not even sent.
        calls.clear();
        jdbc.update("UPDATE shipments SET provider_not_found_at = now() WHERE id = ?", ship);
        BostaAwbService.AwbBatchResult r2 = awbService.printAwb(f.tenant, List.of(ship), null, null);
        assertThat(calls).isEmpty();
        assertThat(r2.exceptions()).extracting(BostaAwbService.AwbException::reason)
            .containsExactly(BostaAwbService.DELETED_IN_BOSTA);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static <T> T as(UUID tenant, java.util.function.Supplier<T> body) {
        return TenantContext.runAs(tenant, body::get);
    }

    private List<String> printed(UUID tenant) {
        return jdbc.queryForList("SELECT tracking_number FROM pack_print_batch_items WHERE tenant_id = ?",
            String.class, tenant);
    }

    private Map<String, String> failedReasons(UUID tenant) {
        Map<String, String> m = new HashMap<>();
        jdbc.query("SELECT tracking_number, awb_print_failed_reason FROM shipments " +
                   "WHERE tenant_id = ? AND awb_print_failed_reason IS NOT NULL",
            rs -> { m.put(rs.getString(1), rs.getString(2)); }, tenant);
        return m;
    }

    private String state(UUID shipmentId) {
        return jdbc.queryForObject("SELECT internal_state::text FROM shipments WHERE id = ?", String.class, shipmentId);
    }

    private static byte[] pdf(List<String> trackings) throws Exception {
        try (PDDocument d = new PDDocument()) {
            for (String tn : trackings) {
                PDPage page = new PDPage();
                d.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(d, page)) {
                    cs.beginText();
                    cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    cs.newLineAtOffset(50, 700);
                    cs.showText("AWB " + tn);
                    cs.endText();
                }
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            d.save(out);
            return out.toByteArray();
        }
    }

    private Fixture fixture(String name) {
        UUID tenant = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?)", tenant, name);
        UUID owner = jdbc.queryForObject(
            "INSERT INTO users (tenant_id, name, email, password_hash, role) " +
            "VALUES (?, 'Owner', ?, 'x', 'owner'::user_role) RETURNING id",
            UUID.class, tenant, name.toLowerCase() + "-" + tenant + "@test.com");
        UUID store = UUID.randomUUID();
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status) " +
                    "VALUES (?, ?, 'shopify', ?, 'disconnected')",
                    store, tenant, "del-" + store + ".myshopify.com");
        jdbc.update("INSERT INTO courier_accounts " +
                    "(tenant_id, provider, api_key_encrypted, webhook_secret, status, awb_format, awb_lang) " +
                    "VALUES (?, 'bosta', ?, 'test-hash', 'active'::courier_account_status, 'A6', 'ar')",
                    tenant, encryptionService.encrypt("deleted-waybill-key"));
        return new Fixture(tenant, owner, store);
    }

    private final class Fixture {
        final UUID tenant, owner, store;
        Fixture(UUID tenant, UUID owner, UUID store) { this.tenant = tenant; this.owner = owner; this.store = store; }

        /** n pickable orders, oldest first, each with a 'created' forward leg; their bare numeric AWBs in order. */
        List<String> orders(int n) {
            List<String> tns = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                Timestamp at = Timestamp.from(Instant.now().minus(n - i, ChronoUnit.HOURS));
                UUID order = jdbc.queryForObject(
                    "INSERT INTO orders (tenant_id, store_id, external_id, number, status, created_at, placed_at) " +
                    "VALUES (?, ?, ?, ?, 'new'::order_status, ?, ?) RETURNING id",
                    UUID.class, tenant, store, "EXT-" + UUID.randomUUID(), "#" + (1000 + i), at, at);
                String tn = String.valueOf(5_000_000_000L + (++counter) * 104_729L + Math.abs(tenant.hashCode() % 100_000));
                jdbc.update("INSERT INTO shipments (tenant_id, order_id, provider, tracking_number, internal_state, shipment_leg) " +
                            "VALUES (?, ?, 'bosta', ?, 'created', 'forward')", tenant, order, tn);
                tns.add(tn);
            }
            return tns;
        }

        UUID shipmentOf(String tn) {
            return jdbc.queryForObject("SELECT id FROM shipments WHERE tracking_number = ?", UUID.class, tn);
        }
    }
}
