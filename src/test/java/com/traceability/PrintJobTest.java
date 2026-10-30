package com.traceability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.traceability.integrations.bosta.AwbPrintResult;
import com.traceability.integrations.bosta.BostaAuthException;
import com.traceability.integrations.bosta.BostaAwbRejectedException;
import com.traceability.integrations.bosta.BostaAwbService;
import com.traceability.integrations.bosta.BostaGateway;
import com.traceability.integrations.bosta.BostaRateLimitException;
import com.traceability.inventory.PackPrintBatchStore;
import com.traceability.inventory.PrintJobRunner;
import com.traceability.inventory.PrintJobService;
import com.traceability.inventory.PrintJobService.JobView;
import com.traceability.inventory.PrintJobSlots;
import com.traceability.inventory.PrintJobStore;
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
import org.jobrunr.jobs.lambdas.IocJobLambda;
import org.jobrunr.scheduling.JobScheduler;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.ByteArrayOutputStream;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Merged-PDF slice B — background print jobs.
 *
 * BostaGateway is a fake Bosta: one page per tracking number, pages in REVERSE of the order sent (so the
 * merged PDF's pick-list order is proven, not assumed); a "deleted" tracking number is refused with Bosta's
 * real 400; failAt makes chosen requests (by call number) throw. JobScheduler is mocked: tests start a job
 * through PrintJobService and run it by calling PrintJobRunner.run, as JobRunr would.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PrintJobTest {

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

    static final String NOT_FOUND = "Couldn't find deliveries with ids: [] or trackingNum";

    @Autowired JdbcTemplate        jdbc;            // postgres — fixtures only
    @Autowired PrintJobService     jobs;
    @Autowired PrintJobRunner      runner;
    @Autowired PrintJobStore       store;
    @Autowired PrintJobSlots       slots;
    @Autowired PackPrintBatchStore batchStore;
    @Autowired BostaAwbService     awbService;
    @Autowired EncryptionService   encryptionService;
    @Autowired ObjectMapper        mapper;
    @MockBean  BostaGateway        bostaGateway;
    @MockBean  JobScheduler        jobScheduler;

    private final List<List<String>> calls = new ArrayList<>();
    private final Set<String> deleted = new HashSet<>();
    private final Map<Integer, Function<Integer, Throwable>> failAt = new HashMap<>();
    private boolean calledInsideTransaction;
    /** Runs at the start of each fake Bosta call (call number), before it answers. */
    private java.util.function.IntConsumer onCall;
    private long counter = 0;

    @BeforeEach
    void fakeBosta() {
        calls.clear();
        deleted.clear();
        failAt.clear();
        calledInsideTransaction = false;
        onCall = null;
        reset(bostaGateway, jobScheduler);
        when(bostaGateway.printMassAwb(anyString(), anyList(), anyString(), anyString()))
            .thenAnswer(inv -> {
                List<String> sent = List.copyOf(inv.getArgument(1));
                calls.add(sent);
                if (TransactionSynchronizationManager.isActualTransactionActive()) calledInsideTransaction = true;
                if (onCall != null) onCall.accept(calls.size());
                Function<Integer, Throwable> fail = failAt.get(calls.size());
                if (fail != null) throw fail.apply(calls.size());
                if (sent.stream().anyMatch(deleted::contains)) {
                    String body = "{\"success\":false,\"message\":\"" + NOT_FOUND + "\"}";
                    throw new BostaAwbRejectedException("Bosta mass-awb error (400 BAD_REQUEST): " + body, 400, NOT_FOUND, null);
                }
                List<String> pages = new ArrayList<>(sent);
                Collections.reverse(pages);
                return new AwbPrintResult(pdf(pages), null);
            });
    }

    @AfterEach
    void clearContext() { TenantContext.clear(); }

    // ── Happy path and the cap ────────────────────────────────────────────────

    @Test
    void j1_245Waybills_5RequestsOf49_oneMergedPdfInPickListOrder_oneBatch() throws Exception {
        Fixture f = fixture("J1");
        List<String> tns = f.orders(245);

        JobView v = startAndRun(f, "all");

        assertThat(calls).hasSize(5).allSatisfy(c -> assertThat(c.size()).isLessThanOrEqualTo(49));
        assertThat(calls.stream().flatMap(List::stream).toList()).containsExactlyElementsOf(tns);
        assertThat(v.status()).isEqualTo("done");
        assertThat(v.total()).isEqualTo(245);
        assertThat(v.done()).isEqualTo(245);
        assertThat(v.printedCount()).isEqualTo(245);
        assertThat(v.excluded()).isEmpty();
        assertThat(v.orderGuaranteed()).isTrue();
        assertThat(pagesOf(as(f.tenant, () -> jobs.pdf(v.jobId())).bytes())).containsExactlyElementsOf(tns);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pack_print_batches WHERE tenant_id = ?", Integer.class, f.tenant))
            .isEqualTo(1);
        assertThat(v.batchId()).isNotNull();
        assertThat(positions(v.batchId())).containsExactlyElementsOf(tns);
        assertThat(calledInsideTransaction).isFalse();
    }

    @Test
    void j2_cap_600Ready_490Printed_110MoreReady() {
        Fixture f = fixture("J2");
        List<String> tns = f.orders(600);

        JobView v = startAndRun(f, "all");

        assertThat(calls).hasSize(10).allSatisfy(c -> assertThat(c.size()).isLessThanOrEqualTo(49));
        assertThat(v.printedCount()).isEqualTo(PrintJobRunner.MAX_WAYBILLS_PER_JOB).isEqualTo(490);
        assertThat(v.candidateCount()).isEqualTo(600);
        assertThat(v.remainingCount()).isEqualTo(110);
        assertThat(positions(v.batchId())).containsExactlyElementsOf(tns.subList(0, 490));
    }

    // ── Failures across requests ──────────────────────────────────────────────

    @Test
    void j3_deletedInChunk3_isolatedAsSlice1_otherChunksUnaffected() throws Exception {
        Fixture f = fixture("J3");
        List<String> tns = f.orders(245);
        String culprit = tns.get(110);                       // chunk 3 = waybills 98–146
        deleted.add(culprit);

        JobView v = startAndRun(f, "all");

        assertThat(v.status()).isEqualTo("done");
        assertThat(v.printedCount()).isEqualTo(244);
        assertThat(v.excluded()).extracting(PrintJobService.Excluded::trackingNumber, PrintJobService.Excluded::reason)
            .containsExactly(tuple(culprit, BostaAwbService.DELETED_IN_BOSTA));
        assertThat(failedReasons(f.tenant)).containsOnlyKeys(culprit);
        List<String> expected = new ArrayList<>(tns);
        expected.remove(culprit);
        assertThat(pagesOf(as(f.tenant, () -> jobs.pdf(v.jobId())).bytes())).containsExactlyElementsOf(expected);
    }

    @Test
    void j4_429AtChunk4_partial_chunks1to3Downloadable_restPrintAgain_noneMarked() throws Exception {
        Fixture f = fixture("J4");
        List<String> tns = f.orders(245);
        failAt.put(4, n -> new BostaRateLimitException(300));

        JobView v = startAndRun(f, "all");

        assertThat(calls).hasSize(4);                         // chunk 5 never sent
        assertThat(v.status()).isEqualTo("partial");
        assertThat(v.printedCount()).isEqualTo(147);
        assertThat(v.excluded()).hasSize(98)
            .allSatisfy(x -> assertThat(x.reason()).isEqualTo(BostaAwbService.BOSTA_BUSY));
        assertThat(v.message().code()).isEqualTo(BostaAwbService.BOSTA_BUSY);
        assertThat(pagesOf(as(f.tenant, () -> jobs.pdf(v.jobId())).bytes())).containsExactlyElementsOf(tns.subList(0, 147));
        assertThat(positions(v.batchId())).containsExactlyElementsOf(tns.subList(0, 147));
        assertThat(failedReasons(f.tenant)).isEmpty();
    }

    @Test
    void j5_401_nobodyMarked_noMissingAwb_connectionMessage_singlePrintToo() {
        Fixture f = fixture("J5");
        List<String> tns = f.orders(60);
        failAt.put(1, n -> new BostaAuthException("Bosta mass-awb error (401 UNAUTHORIZED): {}", 401, null));

        JobView v = startAndRun(f, "all");

        assertThat(calls).hasSize(1);
        assertThat(v.status()).isEqualTo("failed");
        assertThat(v.printedCount()).isZero();
        assertThat(v.pdfAvailable()).isFalse();
        assertThat(v.excluded()).hasSize(60)
            .allSatisfy(x -> assertThat(x.reason()).isEqualTo(BostaAwbService.BOSTA_AUTH));
        assertThat(v.message().code()).isEqualTo(BostaAwbService.BOSTA_AUTH);
        assertThat(v.message().messageEn()).isEqualTo("Bosta connection problem — check Settings → Connections.");
        assertThat(v.message().messageAr()).isNotBlank();
        assertThat(failedReasons(f.tenant)).isEmpty();

        // Single print (BostaController's path): 403 → BOSTA_AUTH, nobody marked.
        calls.clear();
        failAt.clear();
        failAt.put(1, n -> new BostaAuthException("Bosta mass-awb error (403 FORBIDDEN): {}", 403, null));
        BostaAwbService.AwbBatchResult single = awbService.printAwb(f.tenant, List.of(f.shipmentOf(tns.get(0))), null, null);
        assertThat(single.exceptions()).extracting(BostaAwbService.AwbException::reason)
            .containsExactly(BostaAwbService.BOSTA_AUTH);
        assertThat(failedReasons(f.tenant)).isEmpty();
    }

    // ── One per tenant, two per server ────────────────────────────────────────

    @Test
    void j6_secondStart409WithRunningJobId_thirdTenantQueuesWhileTwoRun() {
        Fixture a = fixture("J6A");
        a.orders(3);
        UUID first = as(a.tenant, () -> jobs.startPrint("all", "A6", "oldest", a.owner));
        Throwable second = catchThrowable(() -> as(a.tenant, () -> jobs.startPrint("new", "A6", "oldest", a.owner)));
        assertThat(second).isInstanceOf(PrintJobService.JobRunningException.class);
        assertThat(((PrintJobService.JobRunningException) second).jobId()).isEqualTo(first);
        runner.run(first, a.tenant);

        Fixture c = fixture("J6C");
        c.orders(3);
        UUID third = as(c.tenant, () -> jobs.startPrint("all", "A6", "oldest", c.owner));
        assertThat(slots.tryAcquire()).isTrue();                       // two jobs running elsewhere
        assertThat(slots.tryAcquire()).isTrue();
        try {
            runner.run(third, c.tenant);
            assertThat(calls).hasSize(1);                               // only tenant A's job called Bosta
            assertThat(as(c.tenant, () -> jobs.view(third)).status()).isEqualTo("queued");
            verify(jobScheduler).schedule(any(Instant.class), any(IocJobLambda.class));
        } finally {
            slots.release();
            slots.release();
        }
        runner.run(third, c.tenant);                                    // a slot is free again
        assertThat(as(c.tenant, () -> jobs.view(third)).status()).isEqualTo("done");
    }

    // ── Sweeper ───────────────────────────────────────────────────────────────

    @Test
    void j7_stuckJobSwept_partialPdfDownloadable_restPrintAgain_tenantUnblocked() throws Exception {
        Fixture f = fixture("J7");
        List<String> tns = f.orders(120);
        failAt.put(3, n -> new AssertionError("app killed mid-print"));   // not caught: the job is left running
        UUID jobId = as(f.tenant, () -> jobs.startPrint("all", "A6", "oldest", f.owner));
        assertThatThrownBy(() -> runner.run(jobId, f.tenant)).isInstanceOf(AssertionError.class);
        assertThat(as(f.tenant, () -> jobs.view(jobId)).status()).isEqualTo("running");

        // Not stuck yet (updated just now): the sweeper leaves it.
        assertThat(sweep(f.tenant)).isZero();
        jdbc.update("UPDATE pack_print_jobs SET updated_at = now() - interval '11 minutes' WHERE id = ?", jobId);
        assertThat(sweep(f.tenant)).isEqualTo(1);

        JobView v = as(f.tenant, () -> jobs.view(jobId));
        assertThat(v.status()).isEqualTo("failed");
        assertThat(v.printedCount()).isEqualTo(98);
        assertThat(v.excluded()).hasSize(22)
            .allSatisfy(x -> assertThat(x.reason()).isEqualTo(PrintJobStore.PRINT_INTERRUPTED));
        assertThat(v.message().code()).isEqualTo("PRINT_STOPPED");
        assertThat(pagesOf(as(f.tenant, () -> jobs.pdf(jobId)).bytes())).containsExactlyElementsOf(tns.subList(0, 98));
        assertThat(positions(v.batchId())).containsExactlyElementsOf(tns.subList(0, 98));
        assertThat(failedReasons(f.tenant)).isEmpty();

        // Unblocked: the next print starts, and "New only" picks up the 22.
        failAt.clear();
        UUID next = as(f.tenant, () -> jobs.startPrint("new", "A6", "oldest", f.owner));
        runner.run(next, f.tenant);
        assertThat(as(f.tenant, () -> jobs.view(next)).printedCount()).isEqualTo(22);
    }

    @Test
    void j8_cleanupDeletesPdfBytesAfter24h() {
        Fixture f = fixture("J8");
        f.orders(3);
        JobView v = startAndRun(f, "all");
        assertThat(v.pdfAvailable()).isTrue();
        assertThat(v.expiresAt()).isAfter(Instant.now().plus(23, ChronoUnit.HOURS));

        assertThat(expire(f.tenant)).isZero();   // not yet
        jdbc.update("UPDATE pack_print_jobs SET expires_at = now() - interval '1 minute' WHERE id = ?", v.jobId());
        assertThat(expire(f.tenant)).isEqualTo(1);

        assertThat(jdbc.queryForObject("SELECT pdf IS NULL AND pdf_bytes IS NULL FROM pack_print_jobs WHERE id = ?",
            Boolean.class, v.jobId())).isTrue();
        assertThat(as(f.tenant, () -> jobs.view(v.jobId())).pdfAvailable()).isFalse();
        Throwable gone = catchThrowable(() -> as(f.tenant, () -> jobs.pdf(v.jobId())));
        assertThat(((ResponseStatusException) gone).getStatusCode()).isEqualTo(HttpStatus.GONE);
    }

    // ── Isolation ─────────────────────────────────────────────────────────────

    @Test
    void j9_crossTenant_progressAndDownload_404_sameTenantWorks_rlsAsAppUser() {
        Fixture a = fixture("J9A");
        a.orders(2);
        Fixture b = fixture("J9B");
        JobView v = startAndRun(a, "all");

        // Same tenant: progress, latest and download work.
        assertThat(as(a.tenant, () -> jobs.view(v.jobId())).status()).isEqualTo("done");
        assertThat(as(a.tenant, () -> jobs.latest()).map(JobView::jobId)).contains(v.jobId());
        assertThat(as(a.tenant, () -> jobs.pdf(v.jobId())).bytes()).isNotEmpty();
        // Another tenant: 404 for both, and no "latest".
        Throwable p = catchThrowable(() -> as(b.tenant, () -> jobs.view(v.jobId())));
        assertThat(((ResponseStatusException) p).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        Throwable d = catchThrowable(() -> as(b.tenant, () -> jobs.pdf(v.jobId())));
        assertThat(((ResponseStatusException) d).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(as(b.tenant, () -> jobs.latest())).isEmpty();

        // RLS itself, as app_user with no tenant filter in the SQL.
        TenantAwareDataSource appDs = new TenantAwareDataSource(
            new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "app_user", "testpw"));
        TransactionTemplate appTx = new TransactionTemplate(new DataSourceTransactionManager(appDs));
        JdbcTemplate app = new JdbcTemplate(appDs);
        String sql = "SELECT COUNT(*) FROM pack_print_jobs WHERE id = ?";
        Integer asOther = as(b.tenant, () -> appTx.execute(s -> app.queryForObject(sql, Integer.class, v.jobId())));
        Integer asOwn   = as(a.tenant, () -> appTx.execute(s -> app.queryForObject(sql, Integer.class, v.jobId())));
        Integer asNone  = appTx.execute(s -> app.queryForObject(sql, Integer.class, v.jobId()));
        assertThat(asOther).isZero();
        assertThat(asOwn).isEqualTo(1);
        assertThat(asNone).as("no tenant set → nothing").isZero();
        String parts = "SELECT COUNT(*) FROM pack_print_job_parts WHERE job_id = ?";
        Integer partsOther = as(b.tenant, () -> appTx.execute(s -> app.queryForObject(parts, Integer.class, v.jobId())));
        Integer partsOwn   = as(a.tenant, () -> appTx.execute(s -> app.queryForObject(parts, Integer.class, v.jobId())));
        assertThat(partsOther).isZero();
        assertThat(partsOwn).isEqualTo(1);
    }

    @Test
    void j10_reprintMovesOntoJobs_cancelledSkipped_noNewBatch() throws Exception {
        Fixture f = fixture("J10");
        List<String> tns = f.orders(60);
        JobView first = startAndRun(f, "all");
        UUID cancelledOrder = jdbc.queryForObject("SELECT order_id FROM shipments WHERE tracking_number = ?",
            UUID.class, tns.get(5));
        jdbc.update("UPDATE orders SET status = 'cancelled'::order_status WHERE id = ?", cancelledOrder);
        calls.clear();

        UUID reprint = as(f.tenant, () -> jobs.startReprint(first.batchId(), f.owner));
        runner.run(reprint, f.tenant);
        JobView v = as(f.tenant, () -> jobs.view(reprint));

        assertThat(v.kind()).isEqualTo("reprint");
        assertThat(v.status()).isEqualTo("done");
        assertThat(v.printedCount()).isEqualTo(59);
        assertThat(v.excluded()).extracting(PrintJobService.Excluded::trackingNumber, PrintJobService.Excluded::reason)
            .containsExactly(tuple(tns.get(5), "ORDER_CANCELLED"));
        assertThat(calls).hasSize(2);
        List<String> expected = new ArrayList<>(tns);
        expected.remove(5);
        assertThat(pagesOf(as(f.tenant, () -> jobs.pdf(reprint)).bytes())).containsExactlyElementsOf(expected);
        assertThat(v.batchId()).isNull();
        assertThat(v.reprintBatchNo()).isEqualTo(first.batchNo());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pack_print_batches WHERE tenant_id = ?", Integer.class, f.tenant))
            .isEqualTo(1);
    }

    // ── PDF storage: parts written once, final PDF written once ────────────────

    @Test
    void j11_threeRequests_threePartRowsWrittenOnce_finalPdfWrittenOnce_noPdfRewriteDuringTheRun() throws Exception {
        Fixture f = fixture("J11");
        List<String> tns = f.orders(120);                              // requests of 49, 49, 22
        jdbc.execute("CREATE TABLE IF NOT EXISTS j11_pdf_writes (job_id uuid, what text)");
        jdbc.execute("CREATE OR REPLACE FUNCTION j11_log_job_pdf() RETURNS trigger AS $$ BEGIN " +
                     "INSERT INTO j11_pdf_writes VALUES (NEW.id, 'job_pdf'); RETURN NEW; END $$ LANGUAGE plpgsql");
        jdbc.execute("CREATE OR REPLACE FUNCTION j11_log_part_update() RETURNS trigger AS $$ BEGIN " +
                     "INSERT INTO j11_pdf_writes VALUES (NEW.job_id, 'part_update'); RETURN NEW; END $$ LANGUAGE plpgsql");
        jdbc.execute("CREATE TRIGGER j11_job_pdf BEFORE UPDATE ON pack_print_jobs FOR EACH ROW " +
                     "WHEN (OLD.pdf IS DISTINCT FROM NEW.pdf) EXECUTE FUNCTION j11_log_job_pdf()");
        jdbc.execute("CREATE TRIGGER j11_part_update BEFORE UPDATE ON pack_print_job_parts FOR EACH ROW " +
                     "EXECUTE FUNCTION j11_log_part_update()");
        List<Boolean> pdfEmptyDuringRun = new ArrayList<>();
        try {
            UUID jobId = as(f.tenant, () -> jobs.startPrint("all", "A6", "oldest", f.owner));
            onCall = n -> pdfEmptyDuringRun.add(jdbc.queryForObject(
                "SELECT pdf IS NULL FROM pack_print_jobs WHERE id = ?", Boolean.class, jobId));
            runner.run(jobId, f.tenant);
            JobView v = as(f.tenant, () -> jobs.view(jobId));

            assertThat(calls).hasSize(3);
            assertThat(v.status()).isEqualTo("done");
            assertThat(jdbc.queryForList("SELECT label_count FROM pack_print_job_parts WHERE job_id = ? ORDER BY seq",
                Integer.class, jobId)).containsExactly(49, 49, 22);
            assertThat(jdbc.queryForList("SELECT seq FROM pack_print_job_parts WHERE job_id = ? ORDER BY seq",
                Integer.class, jobId)).containsExactly(1, 2, 3);
            // The job's pdf is empty while Bosta is being called, and written exactly once — at the end.
            assertThat(pdfEmptyDuringRun).containsExactly(true, true, true);
            assertThat(jdbc.queryForList("SELECT what FROM j11_pdf_writes WHERE job_id = ?", String.class, jobId))
                .containsExactly("job_pdf");
            assertThat(pagesOf(as(f.tenant, () -> jobs.pdf(jobId)).bytes())).containsExactlyElementsOf(tns);

            // Cleanup after 24 h deletes the parts and the final PDF.
            jdbc.update("UPDATE pack_print_jobs SET expires_at = now() - interval '1 minute' WHERE id = ?", jobId);
            assertThat(expire(f.tenant)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pack_print_job_parts WHERE job_id = ?",
                Integer.class, jobId)).isZero();
            assertThat(jdbc.queryForObject("SELECT pdf IS NULL FROM pack_print_jobs WHERE id = ?",
                Boolean.class, jobId)).isTrue();
        } finally {
            jdbc.execute("DROP TRIGGER IF EXISTS j11_job_pdf ON pack_print_jobs");
            jdbc.execute("DROP TRIGGER IF EXISTS j11_part_update ON pack_print_job_parts");
        }
    }

    // ── Heartbeat ─────────────────────────────────────────────────────────────

    @Test
    void j12_heartbeatBeforeEveryCall_longIsolationNotSwept_noHeartbeatFor10MinutesIs() {
        // Fake time: each Bosta call "takes" 90 s — updated_at is moved 90 s into the past at the start of
        // every call, then the sweeper runs. 8 calls (one request + 7 isolation calls) = 12 minutes in all.
        Fixture f = fixture("J12A");
        List<String> tns = f.orders(49);
        deleted.add(tns.get(40));                                        // unnamed → bisected
        UUID jobId = as(f.tenant, () -> jobs.startPrint("all", "A6", "oldest", f.owner));
        int[] swept = {0};
        onCall = n -> {
            jdbc.update("UPDATE pack_print_jobs SET updated_at = updated_at - interval '90 seconds' WHERE id = ?", jobId);
            swept[0] += store.sweepStuck();
        };
        runner.run(jobId, f.tenant);
        JobView v = as(f.tenant, () -> jobs.view(jobId));

        assertThat(calls.size()).as("one request + its isolation calls").isGreaterThanOrEqualTo(7);
        assertThat(calls.size() * 90).as("fake seconds of Bosta calls").isGreaterThanOrEqualTo(9 * 60);
        assertThat(swept[0]).as("never swept while calls are still being made").isZero();
        assertThat(v.status()).isEqualTo("done");
        assertThat(v.printedCount()).isEqualTo(48);

        // No heartbeat for 10 minutes (one call hangs 11): swept, and the job sends nothing more.
        calls.clear();
        deleted.clear();
        Fixture g = fixture("J12B");
        g.orders(120);
        UUID stuckJob = as(g.tenant, () -> jobs.startPrint("all", "A6", "oldest", g.owner));
        int[] sweptB = {0};
        onCall = n -> {
            if (n == 2) {
                jdbc.update("UPDATE pack_print_jobs SET updated_at = now() - interval '11 minutes' WHERE id = ?", stuckJob);
                sweptB[0] += store.sweepStuck();
            }
        };
        runner.run(stuckJob, g.tenant);
        JobView s = as(g.tenant, () -> jobs.view(stuckJob));

        assertThat(sweptB[0]).isEqualTo(1);
        assertThat(calls).as("the heartbeat before call 3 finds the job swept").hasSize(2);
        assertThat(s.status()).isEqualTo("failed");
        assertThat(s.message().code()).isEqualTo("PRINT_STOPPED");
        assertThat(s.printedCount()).as("request 1's part, merged by the sweeper").isEqualTo(49);
        assertThat(s.pdfAvailable()).isTrue();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private JobView startAndRun(Fixture f, String scope) {
        UUID jobId = as(f.tenant, () -> jobs.startPrint(scope, "A6", "oldest", f.owner));
        runner.run(jobId, f.tenant);
        return as(f.tenant, () -> jobs.view(jobId));
    }

    private int sweep(UUID tenant) {
        Integer n = as(tenant, () -> store.sweepStuck());
        return n;
    }

    private int expire(UUID tenant) {
        Integer n = as(tenant, () -> store.deleteExpiredPdfs());
        return n;
    }

    private static <T> T as(UUID tenant, java.util.function.Supplier<T> body) {
        return TenantContext.runAs(tenant, body::get);
    }

    private List<String> positions(UUID batchId) {
        return jdbc.queryForList("SELECT tracking_number FROM pack_print_batch_items WHERE batch_id = ? ORDER BY position",
            String.class, batchId);
    }

    private Map<String, String> failedReasons(UUID tenant) {
        Map<String, String> m = new HashMap<>();
        jdbc.query("SELECT tracking_number, awb_print_failed_reason FROM shipments " +
                   "WHERE tenant_id = ? AND awb_print_failed_reason IS NOT NULL",
            rs -> { m.put(rs.getString(1), rs.getString(2)); }, tenant);
        return m;
    }

    private static List<String> pagesOf(byte[] pdf) throws Exception {
        List<String> out = new ArrayList<>();
        try (PDDocument d = Loader.loadPDF(pdf)) {
            PDFTextStripper s = new PDFTextStripper();
            for (int p = 1; p <= d.getNumberOfPages(); p++) {
                s.setStartPage(p);
                s.setEndPage(p);
                out.add(s.getText(d).replace("AWB", "").trim());
            }
        }
        return out;
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
                    store, tenant, "job-" + store + ".myshopify.com");
        jdbc.update("INSERT INTO courier_accounts " +
                    "(tenant_id, provider, api_key_encrypted, webhook_secret, status, awb_format, awb_lang) " +
                    "VALUES (?, 'bosta', ?, 'test-hash', 'active'::courier_account_status, 'A6', 'ar')",
                    tenant, encryptionService.encrypt("print-job-key"));
        return new Fixture(tenant, owner, store);
    }

    private final class Fixture {
        final UUID tenant, owner, store;
        Fixture(UUID tenant, UUID owner, UUID store) { this.tenant = tenant; this.owner = owner; this.store = store; }

        /** n pickable orders, oldest first, each with a 'created' forward leg; their bare numeric AWBs in order. */
        List<String> orders(int n) {
            List<String> tns = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                Timestamp at = Timestamp.from(Instant.now().minus(n - i, ChronoUnit.MINUTES));
                UUID order = jdbc.queryForObject(
                    "INSERT INTO orders (tenant_id, store_id, external_id, number, status, created_at, placed_at) " +
                    "VALUES (?, ?, ?, ?, 'new'::order_status, ?, ?) RETURNING id",
                    UUID.class, tenant, store, "EXT-" + UUID.randomUUID(), "#" + (1000 + i), at, at);
                String tn = String.valueOf(7_000_000_000L + (++counter) * 104_729L + Math.abs(tenant.hashCode() % 100_000));
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
