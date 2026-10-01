package com.traceability;

import com.traceability.integrations.bosta.AwbPrintResult;
import com.traceability.integrations.bosta.BostaAwbService;
import com.traceability.integrations.bosta.BostaException;
import com.traceability.integrations.bosta.BostaGateway;
import com.traceability.inventory.FulfillService;
import com.traceability.inventory.PackPrintBatchService;
import com.traceability.inventory.PackPrintBatchService.PrintBatchResult;
import com.traceability.inventory.PackPrintBatchStore;
import com.traceability.inventory.WaybillPdfAssembler;
import com.traceability.security.EncryptionService;
import com.traceability.tenancy.TenantAwareDataSource;
import com.traceability.tenancy.TenantContext;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.text.PDFTextStripper;
import org.jobrunr.scheduling.JobScheduler;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
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

import java.io.ByteArrayOutputStream;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Pick &amp; Pack S2 — batch waybill printing (V125 pack_print_batches / pack_print_batch_items,
 * PackPrintBatchService, BostaAwbService send order, WaybillPdfAssembler, getOrder awbPrinted,
 * getQueue awb_printed, batch-scoped gather list).
 *
 * BostaGateway is mocked: each mass-awb call returns a PDF built here, one page per tracking
 * number with the number as text, in an order each test chooses.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PackPrintBatchTest {

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

    @Autowired JdbcTemplate          jdbc;            // postgres — fixtures only
    @Autowired PackPrintBatchService printService;
    @Autowired PackPrintBatchStore   store;
    @Autowired FulfillService        fulfill;
    @Autowired BostaAwbService       awbService;
    @Autowired EncryptionService     encryptionService;
    @MockBean  BostaGateway          bostaGateway;
    @MockBean  JobScheduler          jobScheduler;

    private TransactionTemplate appUserTx;
    private JdbcTemplate        appUserJdbc;

    /** How the fake Bosta orders pages inside one chunk's PDF. */
    private enum PageOrder { AS_SENT, REVERSED, AS_SENT_FIRST_PAGE_BLANK }
    private PageOrder pageOrder = PageOrder.AS_SENT;
    private int       counter   = 0;

    @BeforeAll
    void setup() {
        DriverManagerDataSource rawDs = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), "app_user", "testpw");
        TenantAwareDataSource appDs = new TenantAwareDataSource(rawDs);
        appUserTx   = new TransactionTemplate(new DataSourceTransactionManager(appDs));
        appUserJdbc = new JdbcTemplate(appDs);
    }

    @BeforeEach
    void stubBosta() {
        pageOrder = PageOrder.AS_SENT;
        reset(bostaGateway);
        when(bostaGateway.printMassAwb(anyString(), anyList(), anyString(), anyString()))
            .thenAnswer(inv -> {
                List<String> chunk = inv.getArgument(1);
                List<String> pages = new ArrayList<>(chunk);
                if (pageOrder == PageOrder.REVERSED) Collections.reverse(pages);
                return new AwbPrintResult(pdf(pages, pageOrder == PageOrder.AS_SENT_FIRST_PAGE_BLANK), null);
            });
    }

    @AfterEach
    void clearContext() { TenantContext.clear(); }

    // ── Candidate set ─────────────────────────────────────────────────────────

    @Test
    void candidates_followQueueGateAndWindow_excludeSelfPickupAndNonCreated() {
        Fixture f = fixture("Candidates");
        UUID ok         = f.order("OK",        ago(2),  false, false);
        f.forward(ok, "created");
        UUID old        = f.order("OLD",       ago(45), false, false);   // outside 30-day window
        f.forward(old, "created");
        UUID selfPickup = f.order("SELF",      ago(2),  true,  false);
        f.forward(selfPickup, "created");
        UUID moving     = f.order("MOVING",    ago(2),  false, false);
        f.forward(moving, "with_courier");
        UUID onHold     = f.order("HOLD",      ago(2),  false, true);
        f.forward(onHold, "created");
        f.order("NOSHIP", ago(2), false, false);                          // no forward shipment
        UUID packed     = f.orderWithStatus("PACKED", ago(2), "packed");
        f.forward(packed, "created");

        List<PackPrintBatchStore.Candidate> all = as(f.tenant, () -> store.candidates("all", "oldest"));
        assertThat(all).extracting(PackPrintBatchStore.Candidate::orderId).containsExactly(ok);
    }

    @Test
    void scopeNew_excludesPreviouslyBatched_scopeAll_includesThem() {
        Fixture f = fixture("Scope");
        UUID a = f.order("A", ago(3), false, false); f.forward(a, "created");
        UUID b = f.order("B", ago(2), false, false); f.forward(b, "created");
        f.courier();

        PrintBatchResult first = as(f.tenant, () -> printService.print("all", "A6", "oldest", f.owner));
        assertThat(first.waybillCount()).isEqualTo(2);

        UUID c = f.order("C", ago(1), false, false); f.forward(c, "created");
        assertThat(as(f.tenant, () -> store.candidates("new", "oldest")))
            .extracting(PackPrintBatchStore.Candidate::orderId).containsExactly(c);
        assertThat(as(f.tenant, () -> store.candidates("all", "oldest")))
            .extracting(PackPrintBatchStore.Candidate::orderId).containsExactly(a, b, c);
    }

    @Test
    void sort_isDeterministic_withIdTieBreakOnEqualCreatedAt() {
        Fixture f = fixture("Sort");
        Timestamp same = ago(5);
        List<UUID> tied = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            UUID o = f.order("T" + i, same, false, false); f.forward(o, "created"); tied.add(o);
        }
        UUID newest = f.order("N", ago(1), false, false); f.forward(newest, "created");
        UUID oldest = f.order("O", ago(9), false, false); f.forward(oldest, "created");

        // Tie order comes from Postgres' own uuid ordering (Java's UUID.compareTo is signed).
        List<UUID> tiedAsc = jdbc.queryForList(
            "SELECT id FROM orders WHERE id = ANY(?::uuid[]) ORDER BY id ASC", UUID.class,
            (Object) tied.stream().map(UUID::toString).toArray(String[]::new));
        List<UUID> expectedOldest = new ArrayList<>();
        expectedOldest.add(oldest); expectedOldest.addAll(tiedAsc); expectedOldest.add(newest);
        List<UUID> expectedNewest = new ArrayList<>(expectedOldest);
        Collections.reverse(expectedNewest);

        for (int run = 0; run < 3; run++) {
            assertThat(as(f.tenant, () -> store.candidates("all", "oldest")))
                .extracting(PackPrintBatchStore.Candidate::orderId).containsExactlyElementsOf(expectedOldest);
            assertThat(as(f.tenant, () -> store.candidates("all", "newest")))
                .extracting(PackPrintBatchStore.Candidate::orderId).containsExactlyElementsOf(expectedNewest);
        }
    }

    // ── Send order + merge ────────────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void cap_130Candidates_prints49InSortedOrder_remaining81_nextNewPrintsNext49() throws Exception {
        Fixture f = fixture("Cap");
        f.courier();
        List<String> expected = new ArrayList<>();
        for (int i = 0; i < 130; i++) {
            UUID o = f.order("C" + i, minutesAgo(200 - i), false, false);   // created oldest → newest, all in window
            expected.add(f.forward(o, "created"));
        }
        // Newest first, so the sorted order differs from insertion / DB order.
        Collections.reverse(expected);

        PrintBatchResult first = as(f.tenant, () -> printService.print("new", "A6", "newest", f.owner));
        assertThat(first.waybillCount()).isEqualTo(PackPrintBatchService.MAX_WAYBILLS_PER_PRINT).isEqualTo(49);
        assertThat(first.candidateCount()).isEqualTo(130);
        assertThat(first.remainingCount()).isEqualTo(81);
        assertThat(first.orderGuaranteed()).isTrue();
        assertThat(pagesOf(first)).containsExactlyElementsOf(expected.subList(0, 49));
        assertThat(positionsOf(first.batchId())).containsExactlyElementsOf(expected.subList(0, 49));

        PrintBatchResult second = as(f.tenant, () -> printService.print("new", "A6", "newest", f.owner));
        assertThat(second.batchNo()).isEqualTo(first.batchNo() + 1);
        assertThat(second.candidateCount()).isEqualTo(81);
        assertThat(second.remainingCount()).isEqualTo(32);
        assertThat(positionsOf(second.batchId())).containsExactlyElementsOf(expected.subList(49, 98));

        // Exactly one Bosta request per print, each ≤ 49 tracking numbers, in sorted order.
        ArgumentCaptor<List<String>> sent = ArgumentCaptor.forClass(List.class);
        verify(bostaGateway, times(2)).printMassAwb(anyString(), sent.capture(), eq("A6"), anyString());
        assertThat(sent.getAllValues().get(0)).containsExactlyElementsOf(expected.subList(0, 49));
        assertThat(sent.getAllValues().get(1)).containsExactlyElementsOf(expected.subList(49, 98));
    }

    @Test
    @SuppressWarnings("unchecked")
    void bostaRequest_neverCarriesMoreThan49TrackingNumbers_singleOrderPathToo() {
        Fixture f = fixture("Max49");
        f.courier();
        List<UUID> ids = new ArrayList<>();
        List<String> tns = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            UUID o = f.order("M" + i, minutesAgo(200 - i), false, false);
            String tn = f.forward(o, "created");
            tns.add(tn);
            ids.add(jdbc.queryForObject("SELECT id FROM shipments WHERE tracking_number = ?", UUID.class, tn));
        }
        // BostaController's path (printAwb), any number of shipments: chunks of ≤ 49, in the given order.
        BostaAwbService.AwbBatchResult r = awbService.printAwb(f.tenant, ids, null, null);
        assertThat(r.pdfBase64List()).hasSize(3);

        ArgumentCaptor<List<String>> sent = ArgumentCaptor.forClass(List.class);
        verify(bostaGateway, times(3)).printMassAwb(anyString(), sent.capture(), anyString(), anyString());
        assertThat(sent.getAllValues()).allSatisfy(chunk -> assertThat(chunk).hasSizeLessThanOrEqualTo(49));
        assertThat(sent.getAllValues().stream().flatMap(List::stream).toList()).containsExactlyElementsOf(tns);
    }

    @Test
    void assembler_mergesEveryChunkPdf_inOneDocument() throws Exception {
        List<String> tns = new ArrayList<>();
        for (int i = 0; i < 60; i++) tns.add(String.valueOf(5_000_000_000L + i));
        byte[] chunk1 = pdf(tns.subList(0, 49), false);
        byte[] chunk2 = pdf(tns.subList(49, 60), false);

        WaybillPdfAssembler.Assembled a = WaybillPdfAssembler.assemble(List.of(chunk1, chunk2), tns);

        assertThat(a.pageCount()).isEqualTo(60);
        assertThat(a.orderGuaranteed()).isTrue();
        try (PDDocument doc = Loader.loadPDF(a.pdf())) {
            assertThat(doc.getNumberOfPages()).isEqualTo(60);
        }
    }

    @Test
    void pagesOutOfOrder_withTrackingText_areReorderedToSortedOrder() throws Exception {
        Fixture f = fixture("Reorder");
        f.courier();
        List<String> expected = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            UUID o = f.order("R" + i, ago(10 - i), false, false);
            expected.add(f.forward(o, "created"));
        }
        pageOrder = PageOrder.REVERSED;

        PrintBatchResult r = as(f.tenant, () -> printService.print("all", "A6", "oldest", f.owner));

        assertThat(r.orderGuaranteed()).isTrue();
        assertThat(pagesOf(r)).containsExactlyElementsOf(expected);
    }

    @Test
    void unmatchedPage_keepsBostaOrder_andOrderNotGuaranteed() throws Exception {
        Fixture f = fixture("Unmatched");
        f.courier();
        List<String> expected = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            UUID o = f.order("U" + i, ago(10 - i), false, false);
            expected.add(f.forward(o, "created"));
        }
        pageOrder = PageOrder.AS_SENT_FIRST_PAGE_BLANK;

        PrintBatchResult r = as(f.tenant, () -> printService.print("all", "A6", "oldest", f.owner));

        assertThat(r.orderGuaranteed()).isFalse();
        assertThat(r.waybillCount()).isEqualTo(3);
        List<String> pages = pagesOf(r);
        assertThat(pages).hasSize(3);
        assertThat(pages.get(0)).isEmpty();                  // the blank (barcode-only) page, left where Bosta put it
        assertThat(pages.subList(1, 3)).containsExactlyElementsOf(expected.subList(1, 3));
        assertThat(jdbc.queryForObject("SELECT order_guaranteed FROM pack_print_batches WHERE id = ?",
            Boolean.class, r.batchId())).isFalse();
    }

    // ── Excluded / email path / rejected → not printed ────────────────────────

    @Test
    void excludedEmailPathAndRejected_areNotRecordedAsPrinted() throws Exception {
        Fixture f = fixture("Excluded");
        f.courier();
        UUID good = f.order("GOOD", ago(5), false, false);
        String goodTn = f.forward(good, "created");
        UUID crp = f.order("CRP", ago(4), false, false);
        String crpTn = f.forward(crp, "created");
        jdbc.update("UPDATE shipments SET raw = '{\"type\":{\"code\":25}}'::jsonb WHERE tracking_number = ?", crpTn);

        PrintBatchResult r1 = as(f.tenant, () -> printService.print("all", "A6", "oldest", f.owner));
        assertThat(r1.waybillCount()).isEqualTo(1);
        assertThat(r1.excluded()).extracting(PackPrintBatchService.Excluded::trackingNumber).containsExactly(crpTn);
        assertThat(r1.excluded().get(0).orderNumber()).isEqualTo("CRP");
        assertThat(r1.excluded().get(0).reason()).isEqualTo("NON_PRINTABLE_TYPE:CRP");
        assertThat(printedTrackings(f.tenant)).containsExactly(goodTn);

        // Email path: Bosta answers with a message, no PDF → nothing printed, no batch row.
        UUID mail = f.order("MAIL", ago(3), false, false);
        String mailTn = f.forward(mail, "created");
        when(bostaGateway.printMassAwb(anyString(), anyList(), anyString(), anyString()))
            .thenReturn(new AwbPrintResult(null, "AWB has been exported to your email"));
        PrintBatchResult r2 = as(f.tenant, () -> printService.print("new", "A6", "oldest", f.owner));
        assertThat(r2.batchId()).isNull();
        assertThat(r2.pdfBase64()).isNull();
        assertThat(r2.excluded()).extracting(PackPrintBatchService.Excluded::reason).contains("BOSTA_EMAIL_PATH");

        // Rejected chunk → nothing printed.
        when(bostaGateway.printMassAwb(anyString(), anyList(), anyString(), anyString()))
            .thenThrow(new BostaException("rejected"));
        PrintBatchResult r3 = as(f.tenant, () -> printService.print("new", "A6", "oldest", f.owner));
        assertThat(r3.batchId()).isNull();
        assertThat(r3.excluded()).extracting(PackPrintBatchService.Excluded::trackingNumber).contains(mailTn);

        assertThat(printedTrackings(f.tenant)).containsExactly(goodTn);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pack_print_batches WHERE tenant_id = ?",
            Integer.class, f.tenant)).isEqualTo(1);
    }

    @Test
    void zeroCandidates_emptyResult_noBatchRow_noBostaCall() {
        Fixture f = fixture("Empty");
        f.courier();
        PrintBatchResult r = as(f.tenant, () -> printService.print("new", "A6", "oldest", f.owner));
        assertThat(r.batchId()).isNull();
        assertThat(r.candidateCount()).isZero();
        assertThat(r.message()).isNotBlank();
        verifyNoInteractions(bostaGateway);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pack_print_batches WHERE tenant_id = ?",
            Integer.class, f.tenant)).isZero();
    }

    // ── batch_no ──────────────────────────────────────────────────────────────

    @Test
    void batchNo_sequentialPerTenant_concurrentSafe_otherTenantOwnSequence() throws Exception {
        Fixture f = fixture("BatchNo");
        Fixture other = fixture("BatchNoOther");
        UUID o = f.order("B", ago(1), false, false);
        String tn = f.forward(o, "created");
        UUID sid = jdbc.queryForObject("SELECT id FROM shipments WHERE tracking_number = ?", UUID.class, tn);
        List<PackPrintBatchStore.PrintedItem> items = List.of(new PackPrintBatchStore.PrintedItem(o, sid, tn));

        int n = 8;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            futures.add(pool.submit(() -> {
                go.await();
                return TenantContext.runAs(f.tenant, () ->
                    store.record(f.owner, "A6", "oldest", "all", true, items).batchNo());
            }));
        }
        go.countDown();
        List<Integer> nos = new ArrayList<>();
        for (Future<Integer> fu : futures) nos.add(fu.get(30, TimeUnit.SECONDS));
        pool.shutdown();
        assertThat(nos).containsExactlyInAnyOrderElementsOf(List.of(1, 2, 3, 4, 5, 6, 7, 8));

        UUID oo = other.order("X", ago(1), false, false);
        String otn = other.forward(oo, "created");
        UUID osid = jdbc.queryForObject("SELECT id FROM shipments WHERE tracking_number = ?", UUID.class, otn);
        int otherNo = TenantContext.runAs(other.tenant, () -> store.record(other.owner, "A4", "newest", "new", true,
            List.of(new PackPrintBatchStore.PrintedItem(oo, osid, otn))).batchNo());
        assertThat(otherNo).isEqualTo(1);
    }

    // ── RLS (app_user) ────────────────────────────────────────────────────────

    @Test
    void rls_crossTenantReadsReturnNothing_withSameTenantPositiveControl() {
        Fixture a = fixture("RlsA");
        Fixture b = fixture("RlsB");
        a.courier();
        UUID o = a.order("A1", ago(1), false, false); a.forward(o, "created");
        PrintBatchResult r = as(a.tenant, () -> printService.print("all", "A6", "oldest", a.owner));
        assertThat(r.batchId()).isNotNull();

        assertThat(appUserCount(a.tenant, "pack_print_batches")).isEqualTo(1);
        assertThat(appUserCount(a.tenant, "pack_print_batch_items")).isEqualTo(1);
        assertThat(appUserCount(b.tenant, "pack_print_batches")).isZero();
        assertThat(appUserCount(b.tenant, "pack_print_batch_items")).isZero();

        // app_user has SELECT + INSERT only — a batch is history.
        assertThatThrownBy(() -> TenantContext.runAs(a.tenant, () -> appUserTx.execute(s ->
                appUserJdbc.update("UPDATE pack_print_batches SET paper = 'A4'"))))
            .hasStackTraceContaining("permission denied");
        assertThatThrownBy(() -> TenantContext.runAs(a.tenant, () -> appUserTx.execute(s ->
                appUserJdbc.update("DELETE FROM pack_print_batch_items"))))
            .hasStackTraceContaining("permission denied");
    }

    // ── awbPrinted / awb_printed / single-order path ──────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void awbPrinted_trueOnlyForBatchPrinted_queueFlagMatches_singleOrderPathUnchanged() {
        Fixture f = fixture("AwbPrinted");
        f.courier();
        UUID printed = f.order("P", ago(3), false, false);
        f.forward(printed, "created");
        UUID notPrinted = f.order("N", ago(2), false, false);
        String notPrintedTn = f.forward(notPrinted, "created");

        // Single-order path (PickScreen's Print Waybill) — unchanged result, and it records nothing.
        UUID notPrintedSid = jdbc.queryForObject("SELECT id FROM shipments WHERE tracking_number = ?",
            UUID.class, notPrintedTn);
        BostaAwbService.AwbBatchResult single = awbService.printAwb(f.tenant, List.of(notPrintedSid), null, null);
        assertThat(single.pdfBase64List()).hasSize(1);
        assertThat(single.exceptions()).isEmpty();
        assertThat(single.emailMessage()).isNull();

        // Batch-print only the first one: print "new" before the second exists in a batch.
        jdbc.update("UPDATE orders SET placed_at = now() - interval '60 days' WHERE id = ?", notPrinted);
        as(f.tenant, () -> printService.print("all", "A6", "oldest", f.owner));
        jdbc.update("UPDATE orders SET placed_at = now() WHERE id = ?", notPrinted);

        assertThat(as(f.tenant, () -> fulfill.getOrder(printed)).get("awbPrinted")).isEqualTo(true);
        assertThat(as(f.tenant, () -> fulfill.getOrder(notPrinted)).get("awbPrinted")).isEqualTo(false);

        Map<UUID, Object> queueFlags = new HashMap<>();
        for (Map<String, Object> row : as(f.tenant, () -> fulfill.getQueue())) {
            queueFlags.put((UUID) row.get("id"), row.get("awb_printed"));
        }
        assertThat(queueFlags).containsEntry(printed, true).containsEntry(notPrinted, false);
    }

    @Test
    @SuppressWarnings("unchecked")
    void printAwb_sendsTrackingNumbersInGivenOrder() {
        Fixture f = fixture("SingleOrder");
        f.courier();
        List<UUID> ids = new ArrayList<>();
        List<String> tns = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            UUID o = f.order("S" + i, ago(5 - i), false, false);
            String tn = f.forward(o, "created");
            tns.add(tn);
            ids.add(jdbc.queryForObject("SELECT id FROM shipments WHERE tracking_number = ?", UUID.class, tn));
        }
        List<Integer> order = List.of(3, 0, 4, 1, 2);
        awbService.printAwb(f.tenant, order.stream().map(ids::get).toList(), null, null);

        ArgumentCaptor<List<String>> sent = ArgumentCaptor.forClass(List.class);
        verify(bostaGateway).printMassAwb(anyString(), sent.capture(), anyString(), anyString());
        assertThat(sent.getValue()).containsExactlyElementsOf(order.stream().map(tns::get).toList());
    }

    // ── Gather list ───────────────────────────────────────────────────────────

    @Test
    void gatherList_batchScopedReturnsOnlyThatBatch_unscopedUnchanged() {
        Fixture f = fixture("Gather");
        Fixture other = fixture("GatherOther");
        f.courier();
        UUID variant = f.variant();
        UUID a = f.order("GA", ago(3), false, false); f.forward(a, "created"); f.item(a, variant, 2);
        UUID b = f.order("GB", ago(2), false, false); f.forward(b, "created"); f.item(b, variant, 1);

        PrintBatchResult r = as(f.tenant, () -> printService.print("all", "A6", "oldest", f.owner));
        UUID c = f.order("GC", ago(1), false, false); f.forward(c, "created"); f.item(c, variant, 5);

        FulfillService.GatherListResponse scoped = as(f.tenant, () -> fulfill.getGatherList(null, r.batchId()));
        assertThat(scoped.orderCount()).isEqualTo(2);
        assertThat(scoped.rows()).singleElement().satisfies(row -> {
            assertThat(row.needed()).isEqualTo(3);
            assertThat(row.orderNumbers()).containsExactlyInAnyOrder("GA", "GB");
        });

        FulfillService.GatherListResponse unscoped = as(f.tenant, () -> fulfill.getGatherList(null));
        assertThat(unscoped.orderCount()).isEqualTo(3);
        assertThat(as(f.tenant, () -> fulfill.getGatherList(null, null)).orderCount()).isEqualTo(3);

        // Another tenant's batch id → nothing.
        assertThat(as(other.tenant, () -> fulfill.getGatherList(null, r.batchId())).orderCount()).isZero();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static <T> T as(UUID tenant, java.util.function.Supplier<T> body) {
        return TenantContext.runAs(tenant, body::get);
    }

    private int appUserCount(UUID tenant, String table) {
        return TenantContext.runAs(tenant, () -> appUserTx.execute(s ->
            appUserJdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class)));
    }

    private List<String> printedTrackings(UUID tenant) {
        return jdbc.queryForList(
            "SELECT tracking_number FROM pack_print_batch_items WHERE tenant_id = ? ORDER BY tracking_number",
            String.class, tenant);
    }

    private List<String> positionsOf(UUID batchId) {
        return jdbc.queryForList(
            "SELECT tracking_number FROM pack_print_batch_items WHERE batch_id = ? ORDER BY position",
            String.class, batchId);
    }

    /** The tracking number printed on each page of the returned PDF ("" for a page with no text). */
    private static List<String> pagesOf(PrintBatchResult r) throws Exception {
        try (PDDocument doc = Loader.loadPDF(Base64.getDecoder().decode(r.pdfBase64()))) {
            PDFTextStripper s = new PDFTextStripper();
            List<String> out = new ArrayList<>();
            for (int p = 1; p <= doc.getNumberOfPages(); p++) {
                s.setStartPage(p); s.setEndPage(p);
                out.add(s.getText(doc).replace("AWB", "").trim());
            }
            return out;
        }
    }

    /** One page per tracking number, "AWB <tn>" as text; optionally the first page blank. */
    private static byte[] pdf(List<String> trackings, boolean firstBlank) throws Exception {
        try (PDDocument d = new PDDocument()) {
            for (int i = 0; i < trackings.size(); i++) {
                PDPage page = new PDPage();
                d.addPage(page);
                if (firstBlank && i == 0) continue;
                try (PDPageContentStream cs = new PDPageContentStream(d, page)) {
                    cs.beginText();
                    cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    cs.newLineAtOffset(50, 700);
                    cs.showText("AWB " + trackings.get(i));
                    cs.endText();
                }
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            d.save(out);
            return out.toByteArray();
        }
    }

    private static Timestamp minutesAgo(int minutes) {
        return Timestamp.from(Instant.now().minus(minutes, ChronoUnit.MINUTES));
    }

    private static Timestamp ago(int days) {
        return Timestamp.from(Instant.now().minus(days, ChronoUnit.DAYS));
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
                    store, tenant, "pp-" + store + ".myshopify.com");
        return new Fixture(tenant, owner, store);
    }

    private final class Fixture {
        final UUID tenant, owner, store;
        Fixture(UUID tenant, UUID owner, UUID store) { this.tenant = tenant; this.owner = owner; this.store = store; }

        void courier() {
            jdbc.update("INSERT INTO courier_accounts " +
                        "(tenant_id, provider, api_key_encrypted, webhook_secret, status, awb_format, awb_lang) " +
                        "VALUES (?, 'bosta', ?, 'test-hash', 'active'::courier_account_status, 'A6', 'ar')",
                        tenant, encryptionService.encrypt("print-batch-key"));
        }

        UUID order(String number, Timestamp createdAt, boolean selfPickup, boolean onHold) {
            return jdbc.queryForObject(
                "INSERT INTO orders (tenant_id, store_id, external_id, number, status, is_self_pickup, on_hold, " +
                "                    created_at, placed_at) " +
                "VALUES (?, ?, ?, ?, 'new'::order_status, ?, ?, ?, ?) RETURNING id",
                UUID.class, tenant, store, "EXT-" + UUID.randomUUID(), number, selfPickup, onHold,
                createdAt, createdAt);
        }

        UUID orderWithStatus(String number, Timestamp createdAt, String status) {
            return jdbc.queryForObject(
                "INSERT INTO orders (tenant_id, store_id, external_id, number, status, created_at, placed_at) " +
                "VALUES (?, ?, ?, ?, ?::order_status, ?, ?) RETURNING id",
                UUID.class, tenant, store, "EXT-" + UUID.randomUUID(), number, status, createdAt, createdAt);
        }

        /** Forward shipment; returns its (unique) tracking number. */
        String forward(UUID orderId, String state) {
            String tn = String.valueOf(1_000_000_000L + (++counter) * 7919L + Math.abs(tenant.hashCode() % 100_000));
            jdbc.update("INSERT INTO shipments (tenant_id, order_id, provider, tracking_number, internal_state, shipment_leg) " +
                        "VALUES (?, ?, 'bosta', ?, ?::shipment_internal_state, 'forward')",
                        tenant, orderId, tn, state);
            return tn;
        }

        UUID variant() {
            UUID product = jdbc.queryForObject(
                "INSERT INTO products (tenant_id, store_id, external_id, title, status) " +
                "VALUES (?, ?, ?, 'Shirt', 'active') RETURNING id",
                UUID.class, tenant, store, "gid://shopify/Product/" + UUID.randomUUID());
            return jdbc.queryForObject(
                "INSERT INTO variants (tenant_id, product_id, external_id, sku, title) " +
                "VALUES (?, ?, ?, 'SH-1', 'M') RETURNING id",
                UUID.class, tenant, product, "gid://shopify/ProductVariant/" + UUID.randomUUID());
        }

        void item(UUID orderId, UUID variantId, int qty) {
            jdbc.update("INSERT INTO order_items (tenant_id, order_id, variant_id, quantity) VALUES (?, ?, ?, ?)",
                        tenant, orderId, variantId, qty);
        }
    }
}
