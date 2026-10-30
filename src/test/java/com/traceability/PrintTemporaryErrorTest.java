package com.traceability;

import com.traceability.integrations.bosta.AwbPrintResult;
import com.traceability.integrations.bosta.BostaAwbRejectedException;
import com.traceability.integrations.bosta.BostaAwbService;
import com.traceability.integrations.bosta.BostaAwbService.AwbChunk;
import com.traceability.integrations.bosta.BostaAwbService.AwbDetailedResult;
import com.traceability.integrations.bosta.BostaGateway;
import com.traceability.integrations.bosta.BostaRateLimitException;
import com.traceability.integrations.bosta.BostaTransientException;
import com.traceability.inventory.PackPrintBatchService;
import com.traceability.inventory.PackPrintBatchService.PrintBatchResult;
import com.traceability.security.EncryptionService;
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
import java.util.function.Function;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Merged-PDF slice A — temporary Bosta errors never mark waybills failed.
 *
 * BostaGateway is mocked as a fake Bosta that prints one page per tracking number, except on the
 * requests a test makes fail (by call number): a rate limit (Bosta's 429 and the shared limiter's time-out
 * both reach the service as BostaRateLimitException — MassAwbRateLimitWireTest proves the wire side), or a
 * 5xx / network error (BostaTransientException, after the client's own retries).
 *
 *   t1 429 at request 2 of 3      → request 1's labels kept, 0 marked, requests 2–3 BOSTA_BUSY, request 3 never sent
 *   t2 5xx on request 2 of 3      → 0 marked, requests 1 and 3 printed, request 2 BOSTA_TEMPORARY
 *   t3 limiter time-out at request 2 → same as t1
 *   t4 a rate limit during slice 1's isolation → what printed is merged and recorded, 0 marked, the rest busy
 *   t5 a 5xx during isolation      → isolation stops, 0 marked, the rest BOSTA_TEMPORARY
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PrintTemporaryErrorTest {

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

    /** Bosta's real 400 prefix for a deleted waybill (no tracking number in it → isolation bisects). */
    static final String NOT_FOUND = "Couldn't find deliveries with ids: [] or trackingNum";

    @Autowired JdbcTemplate          jdbc;            // postgres — fixtures only
    @Autowired PackPrintBatchService printService;
    @Autowired BostaAwbService       awbService;
    @Autowired EncryptionService     encryptionService;
    @MockBean  BostaGateway          bostaGateway;
    @MockBean  JobScheduler          jobScheduler;

    private final List<List<String>> calls = new ArrayList<>();
    private final Set<String> deleted = new HashSet<>();
    /** call number (1-based) → what that request throws instead of printing. */
    private final Map<Integer, Function<Integer, RuntimeException>> failAt = new HashMap<>();
    private boolean calledInsideTransaction;
    private long counter = 0;

    @BeforeEach
    void fakeBosta() {
        calls.clear();
        deleted.clear();
        failAt.clear();
        calledInsideTransaction = false;
        reset(bostaGateway);
        when(bostaGateway.printMassAwb(anyString(), anyList(), anyString(), anyString()))
            .thenAnswer(inv -> {
                List<String> sent = List.copyOf(inv.getArgument(1));
                calls.add(sent);
                if (TransactionSynchronizationManager.isActualTransactionActive()) calledInsideTransaction = true;
                Function<Integer, RuntimeException> fail = failAt.get(calls.size());
                if (fail != null) throw fail.apply(calls.size());
                if (sent.stream().anyMatch(deleted::contains)) {
                    String body = "{\"success\":false,\"message\":\"" + NOT_FOUND + "\"}";
                    throw new BostaAwbRejectedException("Bosta mass-awb error (400 BAD_REQUEST): " + body, 400, NOT_FOUND, null);
                }
                return new AwbPrintResult(pdf(sent), null);
            });
    }

    @AfterEach
    void clearContext() { TenantContext.clear(); }

    // ── Across requests (printAwbDetailed — any number of waybills, chunks of 49) ──

    @Test
    void t1_429AtRequest2of3_request1Kept_noneMarked_restBusy_noFurtherRequest() {
        Fixture f = fixture("T1");
        List<String> tns = f.orders(120);                                  // requests of 49, 49, 22
        failAt.put(2, n -> new BostaRateLimitException(300));

        AwbDetailedResult r = awbService.printAwbDetailed(f.tenant, f.shipments(tns), null, null);

        assertThat(calls).hasSize(2);                                       // request 3 never sent
        assertThat(printedIn(r)).containsExactlyElementsOf(tns.subList(0, 49));
        assertThat(reasonOf(r, BostaAwbService.BOSTA_BUSY)).containsExactlyElementsOf(tns.subList(49, 120));
        assertThat(failedReasons(f.tenant)).isEmpty();
        assertThat(calledInsideTransaction).isFalse();
    }

    @Test
    void t2_5xxOnOneRequest_noneMarked_otherRequestsStillPrinted() {
        Fixture f = fixture("T2");
        List<String> tns = f.orders(120);
        failAt.put(2, n -> new BostaTransientException("Bosta 5xx on mass-awb"));

        AwbDetailedResult r = awbService.printAwbDetailed(f.tenant, f.shipments(tns), null, null);

        assertThat(calls).hasSize(3);
        List<String> expected = new ArrayList<>(tns.subList(0, 49));
        expected.addAll(tns.subList(98, 120));
        assertThat(printedIn(r)).containsExactlyElementsOf(expected);
        assertThat(reasonOf(r, BostaAwbService.BOSTA_TEMPORARY)).containsExactlyElementsOf(tns.subList(49, 98));
        assertThat(failedReasons(f.tenant)).isEmpty();
    }

    @Test
    void t3_limiterTimeOut_sameAs429() {
        Fixture f = fixture("T3");
        List<String> tns = f.orders(120);
        // BostaRateLimiter.acquire's time-out: thrown before any HTTP request, retry-after = the remaining wait.
        failAt.put(2, n -> new BostaRateLimitException(61));

        AwbDetailedResult r = awbService.printAwbDetailed(f.tenant, f.shipments(tns), null, null);

        assertThat(calls).hasSize(2);
        assertThat(printedIn(r)).containsExactlyElementsOf(tns.subList(0, 49));
        assertThat(reasonOf(r, BostaAwbService.BOSTA_BUSY)).containsExactlyElementsOf(tns.subList(49, 120));
        assertThat(failedReasons(f.tenant)).isEmpty();
    }

    @Test
    void t3b_singlePrint_rateLimited_reportedBusy_notMarked() {
        Fixture f = fixture("T3B");
        String tn = f.orders(1).get(0);
        failAt.put(1, n -> new BostaRateLimitException(30));

        BostaAwbService.AwbBatchResult r = awbService.printAwb(f.tenant, f.shipments(List.of(tn)), null, null);

        assertThat(r.pdfBase64List()).isEmpty();
        assertThat(r.exceptions()).extracting(BostaAwbService.AwbException::reason)
            .containsExactly(BostaAwbService.BOSTA_BUSY);
        assertThat(failedReasons(f.tenant)).isEmpty();
    }

    // ── Inside slice 1's isolation (the batch print, ≤ 49) ──────────────────────

    @Test
    void t4_rateLimitDuringIsolation_printedPartMergedAndRecorded_noneMarked_restBusy() throws Exception {
        Fixture f = fixture("T4");
        List<String> tns = f.orders(49);
        deleted.add(tns.get(40));                    // call 1 refused → bisect: call 2 prints 0–24, call 3 is limited
        failAt.put(3, n -> new BostaRateLimitException(300));

        PrintBatchResult r = as(f.tenant, () -> printService.print("all", "A6", "oldest", f.owner));

        assertThat(calls).hasSize(3);
        assertThat(r.waybillCount()).isEqualTo(25);
        assertThat(pagesOf(r)).containsExactlyElementsOf(tns.subList(0, 25));
        assertThat(printed(f.tenant)).containsExactlyInAnyOrderElementsOf(tns.subList(0, 25));
        assertThat(r.excluded()).hasSize(24)
            .allSatisfy(x -> assertThat(x.reason()).isEqualTo(BostaAwbService.BOSTA_BUSY));
        assertThat(failedReasons(f.tenant)).isEmpty();
    }

    @Test
    void t5_5xxDuringIsolation_stopsIsolation_noneMarked_restTemporary() {
        Fixture f = fixture("T5");
        List<String> tns = f.orders(49);
        deleted.add(tns.get(40));
        failAt.put(3, n -> new BostaTransientException("Network error on mass-awb"));

        PrintBatchResult r = as(f.tenant, () -> printService.print("all", "A6", "oldest", f.owner));

        assertThat(calls).hasSize(3);
        assertThat(r.waybillCount()).isEqualTo(25);
        assertThat(r.excluded()).hasSize(24)
            .allSatisfy(x -> assertThat(x.reason()).isEqualTo(BostaAwbService.BOSTA_TEMPORARY));
        assertThat(failedReasons(f.tenant)).isEmpty();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static <T> T as(UUID tenant, java.util.function.Supplier<T> body) {
        return TenantContext.runAs(tenant, body::get);
    }

    private static List<String> printedIn(AwbDetailedResult r) {
        return r.chunks().stream().filter(c -> c.pdf() != null).flatMap(c -> c.trackingNumbers().stream()).toList();
    }

    private static List<String> reasonOf(AwbDetailedResult r, String reason) {
        return r.chunks().stream().filter(c -> reason.equals(c.rejectedReason()))
            .flatMap((AwbChunk c) -> c.trackingNumbers().stream()).toList();
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

    /** The tracking number on each page of the merged PDF, in page order. */
    private static List<String> pagesOf(PrintBatchResult r) throws Exception {
        List<String> out = new ArrayList<>();
        try (PDDocument d = Loader.loadPDF(Base64.getDecoder().decode(r.pdfBase64()))) {
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
                    store, tenant, "tmp-" + store + ".myshopify.com");
        jdbc.update("INSERT INTO courier_accounts " +
                    "(tenant_id, provider, api_key_encrypted, webhook_secret, status, awb_format, awb_lang) " +
                    "VALUES (?, 'bosta', ?, 'test-hash', 'active'::courier_account_status, 'A6', 'ar')",
                    tenant, encryptionService.encrypt("temporary-error-key"));
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
                String tn = String.valueOf(6_000_000_000L + (++counter) * 104_729L + Math.abs(tenant.hashCode() % 100_000));
                jdbc.update("INSERT INTO shipments (tenant_id, order_id, provider, tracking_number, internal_state, shipment_leg) " +
                            "VALUES (?, ?, 'bosta', ?, 'created', 'forward')", tenant, order, tn);
                tns.add(tn);
            }
            return tns;
        }

        List<UUID> shipments(List<String> tns) {
            List<UUID> ids = new ArrayList<>();
            for (String tn : tns) {
                ids.add(jdbc.queryForObject("SELECT id FROM shipments WHERE tracking_number = ?", UUID.class, tn));
            }
            return ids;
        }
    }
}
