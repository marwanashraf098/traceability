package com.traceability;

import com.traceability.identity.JwtService;
import com.traceability.integrations.bosta.AwbPrintResult;
import com.traceability.integrations.bosta.BostaGateway;
import com.traceability.inventory.ExceptionService;
import com.traceability.inventory.PackListService;
import com.traceability.inventory.PackPrintBatchService;
import com.traceability.inventory.ShipmentLinkService;
import com.traceability.security.EncryptionService;
import com.traceability.tenancy.TenantAwareDataSource;
import com.traceability.tenancy.TenantContext;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.jobrunr.scheduling.JobScheduler;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
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
import java.time.Clock;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Pick &amp; Pack S4 — manager exceptions (pack_set_aside, pack_cancelled_after_print), today's
 * print batches with progress, batch reprint, "Printed but not packed", session summary.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PackListsTest {

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
    @Autowired TestRestTemplate    rest;
    @Autowired JdbcTemplate        jdbc;
    @Autowired JwtService          jwt;
    @Autowired ExceptionService    exceptions;
    @Autowired PackListService     lists;
    @Autowired ShipmentLinkService shipmentLinkService;
    @Autowired EncryptionService   encryption;
    @Autowired Clock               clock;
    @MockBean  BostaGateway        bostaGateway;
    @MockBean  JobScheduler        jobScheduler;

    private TransactionTemplate appUserTx;
    private ExceptionService    appUserExceptions;

    @BeforeAll
    void setup() {
        DriverManagerDataSource rawDs = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "app_user", "testpw");
        TenantAwareDataSource appDs = new TenantAwareDataSource(rawDs);
        appUserTx = new TransactionTemplate(new DataSourceTransactionManager(appDs));
        appUserExceptions = new ExceptionService(new JdbcTemplate(appDs), clock, shipmentLinkService);
    }

    @BeforeEach
    void resetBosta() { reset(bostaGateway); }

    @AfterEach
    void clear() { TenantContext.clear(); }

    // ── Fixture: a store with orders printed in batches ───────────────────────

    final class Shop {
        final PackFixtures f;
        final UUID manager, packer;
        Shop(String name) {
            f = new PackFixtures(jdbc, name);
            manager = f.user("Mona", "manager");
            packer = f.user("Ahmed", "worker");
        }
        /** A printed order: order + forward leg; returns {orderId, shipmentId, tracking}. */
        Object[] printedOrder(String number, UUID batch, int position) {
            UUID order = f.order(number, 1);
            String tn = f.forward(order);
            UUID sid = jdbc.queryForObject("SELECT id FROM shipments WHERE tracking_number = ?", UUID.class, tn);
            addToBatch(batch, order, sid, tn, position);
            return new Object[]{order, sid, tn};
        }
        UUID batch(int no, String createdAtSql) {
            return jdbc.queryForObject(
                "INSERT INTO pack_print_batches (tenant_id, batch_no, printed_by, paper, sort, scope, waybill_count, " +
                "order_guaranteed, created_at) VALUES (?, ?, ?, 'A6', 'oldest', 'new', 1, true, " + createdAtSql + ") RETURNING id",
                UUID.class, f.tenant, no, manager);
        }
        void addToBatch(UUID batch, UUID order, UUID sid, String tn, int position) {
            jdbc.update("INSERT INTO pack_print_batch_items (batch_id, tenant_id, order_id, shipment_id, tracking_number, position) " +
                        "VALUES (?, ?, ?, ?, ?, ?)", batch, f.tenant, order, sid, tn, position);
        }
        UUID session(UUID user) {
            return jdbc.queryForObject("INSERT INTO pack_sessions (tenant_id, user_id, mode) VALUES (?, ?, 'waybill_scan') RETURNING id",
                UUID.class, f.tenant, user);
        }
        void outcome(UUID session, UUID order, String outcome, String reason, String raw) {
            jdbc.update("INSERT INTO pack_session_orders (tenant_id, session_id, order_id, raw_scan, outcome, reason) " +
                        "VALUES (?, ?, ?, ?, ?, ?)", f.tenant, session, order, raw, outcome, reason);
        }
        List<Map<String, Object>> open(String type) {
            return TenantContext.runAs(f.tenant, () -> exceptions.detectAllOpen()).stream()
                .filter(e -> type.equals(e.get("type"))).toList();
        }
        void resolve(String type, String key) {
            TenantContext.runAs(f.tenant, () -> exceptions.resolve(type, key, manager, "done"));
        }
    }

    // ── Exceptions ────────────────────────────────────────────────────────────

    @Test
    void packSetAside_appears_autoResolvesWhenPacked_manualResolve_reopensOnANewSetAside() {
        Shop s = new Shop("SetAside");
        UUID batch = s.batch(1, "now()");
        UUID order = (UUID) s.printedOrder("#A1", batch, 1)[0];
        UUID session = s.session(s.packer);
        s.outcome(session, order, "set_aside", "piece_missing", "T1");

        List<Map<String, Object>> open = s.open("pack_set_aside");
        assertThat(open).singleElement().satisfies(e -> {
            assertThat(e.get("severity")).isEqualTo("MEDIUM");
            assertThat(e.get("order_number")).isEqualTo("#A1");
            assertThat(e.get("set_aside_reason")).isEqualTo("piece_missing");
            assertThat(e.get("set_aside_by_name")).isEqualTo("Ahmed");
            assertThat(e.get("tracking_number")).isNotNull();
            assertThat((String) e.get("descriptionEn")).contains("piece missing on the shelf").contains("Ahmed");
        });

        // A later rejection of its waybill doesn't change it; a manager resolves it → gone.
        s.outcome(session, order, "rejected", "CLAIMED_BY_OTHER", "T1");
        assertThat(s.open("pack_set_aside")).hasSize(1);
        s.resolve("pack_set_aside", (String) open.get(0).get("subject_key"));
        assertThat(s.open("pack_set_aside")).isEmpty();

        // Set aside again (new event) → open again; packed → auto-resolved.
        s.outcome(session, order, "set_aside", "damaged_piece", "T1");
        assertThat(s.open("pack_set_aside")).singleElement()
            .satisfies(e -> assertThat(e.get("set_aside_reason")).isEqualTo("damaged_piece"));
        jdbc.update("UPDATE orders SET status = 'awaiting_pickup' WHERE id = ?", order);
        assertThat(s.open("pack_set_aside")).isEmpty();

        // Cancelled also clears it.
        UUID order2 = (UUID) s.printedOrder("#A2", batch, 2)[0];
        s.outcome(session, order2, "set_aside", "other", "T2");
        assertThat(s.open("pack_set_aside")).hasSize(1);
        jdbc.update("UPDATE orders SET status = 'cancelled' WHERE id = ?", order2);
        assertThat(s.open("pack_set_aside")).isEmpty();
    }

    @Test
    void packCancelledAfterPrint_appearsForPrintedOnly_resolvedByWaybillDiscarded() {
        Shop s = new Shop("CancelPrint");
        UUID b1 = s.batch(1, "now() - interval '2 days'");
        UUID b2 = s.batch(2, "now() - interval '1 day'");
        Object[] printed = s.printedOrder("#C1", b1, 1);
        s.addToBatch(b2, (UUID) printed[0], (UUID) printed[1], (String) printed[2], 1);   // reprinted in batch 2
        UUID notPrinted = s.f.order("#C2", 1);
        s.f.forward(notPrinted);
        jdbc.update("UPDATE orders SET status = 'cancelled' WHERE id IN (?, ?)", printed[0], notPrinted);

        List<Map<String, Object>> open = s.open("pack_cancelled_after_print");
        assertThat(open).singleElement().satisfies(e -> {
            assertThat(e.get("order_number")).isEqualTo("#C1");
            assertThat(e.get("batch_no")).isEqualTo(2);                       // latest batch
            assertThat(e.get("tracking_number")).isEqualTo(printed[2]);
            assertThat(e.get("severity")).isEqualTo("MEDIUM");
        });

        // cancel requested after pack counts too
        Object[] packedThenCancelled = s.printedOrder("#C3", b2, 2);
        jdbc.update("UPDATE orders SET status = 'awaiting_pickup', cancel_requested_at = now() WHERE id = ?", packedThenCancelled[0]);
        assertThat(s.open("pack_cancelled_after_print")).hasSize(2);

        s.resolve("pack_cancelled_after_print", (String) open.get(0).get("subject_key"));
        assertThat(s.open("pack_cancelled_after_print")).extracting(e -> e.get("order_number")).containsExactly("#C3");
    }

    @Test
    void exceptions_crossTenantIsolation_asAppUser_withSameTenantPositiveControl() {
        Shop a = new Shop("ExcA");
        Shop b = new Shop("ExcB");
        UUID batch = a.batch(1, "now()");
        UUID order = (UUID) a.printedOrder("#X1", batch, 1)[0];
        a.outcome(a.session(a.packer), order, "set_aside", "other", "X");
        Object[] cancelled = a.printedOrder("#X2", batch, 2);
        jdbc.update("UPDATE orders SET status = 'cancelled' WHERE id = ?", cancelled[0]);

        List<String> own = types(a.f.tenant);
        assertThat(own).contains("pack_set_aside", "pack_cancelled_after_print");
        assertThat(types(b.f.tenant)).doesNotContain("pack_set_aside", "pack_cancelled_after_print");
    }

    private List<String> types(UUID tenant) {
        return TenantContext.runAs(tenant, () -> appUserTx.execute(s -> appUserExceptions.detectAllOpen()))
            .stream().map(e -> (String) e.get("type")).toList();
    }

    @Test
    void resolveEndpoint_ownerManagerOnly_workerForbidden() {
        Shop s = new Shop("ResolveRoles");
        UUID batch = s.batch(1, "now()");
        UUID order = (UUID) s.printedOrder("#R1", batch, 1)[0];
        s.outcome(s.session(s.packer), order, "set_aside", "other", "R");
        String key = (String) s.open("pack_set_aside").get(0).get("subject_key");

        String worker = jwt.issueAccessToken(s.packer, s.f.tenant, "worker");
        String manager = jwt.issueAccessToken(s.manager, s.f.tenant, "manager");
        Map<String, Object> body = Map.of("exceptionType", "pack_set_aside", "subjectKey", key, "note", "found it");
        assertThat(post(worker, "/api/v1/exceptions/resolve", body).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(s.open("pack_set_aside")).hasSize(1);
        assertThat(post(manager, "/api/v1/exceptions/resolve", body).getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(s.open("pack_set_aside")).isEmpty();
    }

    // ── Print batches today ───────────────────────────────────────────────────

    @Test
    void batchesToday_progressCounts_andCairoDayBoundary() {
        Shop s = new Shop("BatchCounts");
        // Cairo midnight today (+1 min) is today; one minute before it is yesterday.
        String cairoMidnight = "((date_trunc('day', now() AT TIME ZONE 'Africa/Cairo')) AT TIME ZONE 'Africa/Cairo')";
        UUID today = s.batch(4, cairoMidnight + " + interval '1 minute'");
        UUID yesterday = s.batch(3, cairoMidnight + " - interval '1 minute'");
        s.printedOrder("#Y1", yesterday, 1);

        UUID packed = (UUID) s.printedOrder("#P1", today, 1)[0];
        jdbc.update("UPDATE orders SET status = 'awaiting_pickup' WHERE id = ?", packed);
        UUID setAside = (UUID) s.printedOrder("#P2", today, 2)[0];
        s.outcome(s.session(s.packer), setAside, "set_aside", "piece_missing", "x");
        UUID cancelled = (UUID) s.printedOrder("#P3", today, 3)[0];
        jdbc.update("UPDATE orders SET status = 'cancelled' WHERE id = ?", cancelled);
        s.printedOrder("#P4", today, 4);                                           // waiting
        UUID packing = (UUID) s.printedOrder("#P5", today, 5)[0];
        s.f.claim(packing, s.packer, 1);                                          // being packed → still waiting

        List<PackListService.BatchToday> batches = TenantContext.runAs(s.f.tenant, () -> lists.batchesToday());
        assertThat(batches).singleElement().satisfies(b -> {
            assertThat(b.batchNo()).isEqualTo(4);
            assertThat(b.printedByName()).isEqualTo("Mona");
            assertThat(b.packed()).isEqualTo(1);
            assertThat(b.setAside()).isEqualTo(1);
            assertThat(b.cancelled()).isEqualTo(1);
            assertThat(b.waiting()).isEqualTo(2);
        });
    }

    // ── Reprint ───────────────────────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void reprint_storedOrder_noNewBatch_cancelledSkippedAndReported_oneRequestUnder50() throws Exception {
        Shop s = new Shop("Reprint");
        jdbc.update("INSERT INTO courier_accounts (tenant_id, provider, api_key_encrypted, webhook_secret, status, awb_format, awb_lang) " +
                    "VALUES (?, 'bosta', ?, 'h', 'active'::courier_account_status, 'A6', 'ar')",
                    s.f.tenant, encryption.encrypt("reprint-key"));
        UUID batch = s.batch(7, "now()");
        // Positions deliberately not in insertion order.
        Object[] third = s.printedOrder("#R3", batch, 3);
        Object[] first = s.printedOrder("#R1", batch, 1);
        Object[] cancelled = s.printedOrder("#R2", batch, 2);
        jdbc.update("UPDATE orders SET status = 'cancelled' WHERE id = ?", cancelled[0]);
        when(bostaGateway.printMassAwb(anyString(), anyList(), anyString(), anyString()))
            .thenAnswer(inv -> new AwbPrintResult(pdf(inv.getArgument(1)), null));
        int batchesBefore = jdbc.queryForObject("SELECT COUNT(*) FROM pack_print_batches WHERE tenant_id = ?", Integer.class, s.f.tenant);

        PackPrintBatchService.PrintBatchResult r = TenantContext.runAs(s.f.tenant, () -> lists.reprint(batch));

        ArgumentCaptor<List<String>> sent = ArgumentCaptor.forClass(List.class);
        verify(bostaGateway, times(1)).printMassAwb(anyString(), sent.capture(), eq("A6"), anyString());
        assertThat(sent.getValue()).containsExactly((String) first[2], (String) third[2]).hasSizeLessThanOrEqualTo(49);
        assertThat(r.batchNo()).isEqualTo(7);
        assertThat(r.waybillCount()).isEqualTo(2);
        assertThat(r.pdfBase64()).isNotNull();
        assertThat(r.excluded()).singleElement().satisfies(x -> {
            assertThat(x.trackingNumber()).isEqualTo(cancelled[2]);
            assertThat(x.reason()).isEqualTo("ORDER_CANCELLED");
        });
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pack_print_batches WHERE tenant_id = ?", Integer.class, s.f.tenant))
            .isEqualTo(batchesBefore);
    }

    // ── Printed but not packed ────────────────────────────────────────────────

    @Test
    void printedNotPacked_statusPriority_sort_dedupe_packedGone_cancelledUntilResolved_noTimeWindow() {
        Shop s = new Shop("NotPacked");
        UUID old = s.batch(1, "now() - interval '40 days'");
        UUID recent = s.batch(2, "now()");
        UUID session = s.session(s.packer);

        Object[] waitingOld = s.printedOrder("#W1", old, 1);                       // old batch, still waiting
        Object[] reprinted = s.printedOrder("#W2", old, 2);
        s.addToBatch(recent, (UUID) reprinted[0], (UUID) reprinted[1], (String) reprinted[2], 1);
        Object[] setAside = s.printedOrder("#S1", recent, 2);
        s.outcome(session, (UUID) setAside[0], "set_aside", "waybill_damaged", "x");
        Object[] packing = s.printedOrder("#K1", recent, 3);
        s.outcome(session, (UUID) packing[0], "set_aside", "other", "x");          // set aside AND claimed → packing wins
        s.f.claim((UUID) packing[0], s.packer, 1);
        Object[] stale = s.printedOrder("#K2", recent, 4);
        s.f.claim((UUID) stale[0], s.packer, 30);                                  // stale claim → waiting
        Object[] cancelled = s.printedOrder("#X1", recent, 5);
        jdbc.update("UPDATE orders SET status = 'cancelled' WHERE id = ?", cancelled[0]);
        Object[] packed = s.printedOrder("#P1", recent, 6);
        jdbc.update("UPDATE orders SET status = 'awaiting_pickup' WHERE id = ?", packed[0]);

        List<PackListService.NotPackedRow> rows = TenantContext.runAs(s.f.tenant, () -> lists.printedNotPacked());
        assertThat(rows).extracting(PackListService.NotPackedRow::orderNumber)
            .containsExactly("#X1", "#S1", "#K1", "#W1", "#W2", "#K2");
        Map<String, PackListService.NotPackedRow> by = new HashMap<>();
        rows.forEach(r -> by.put(r.orderNumber(), r));
        assertThat(by.get("#X1").status()).isEqualTo("cancelled");
        assertThat(by.get("#X1").exceptionType()).isEqualTo("pack_cancelled_after_print");
        assertThat(by.get("#S1").status()).isEqualTo("set_aside");
        assertThat(by.get("#S1").setAsideReason()).isEqualTo("waybill_damaged");
        assertThat(by.get("#S1").subjectKey()).startsWith("pack_set_aside:");
        assertThat(by.get("#K1").status()).isEqualTo("packing");
        assertThat(by.get("#K1").packerName()).isEqualTo("Ahmed");
        assertThat(by.get("#K2").status()).isEqualTo("waiting");
        assertThat(by.get("#W1").batchNo()).isEqualTo(1);                          // 40-day-old batch still listed
        assertThat(by.get("#W2").batchNo()).isEqualTo(2);                          // deduped → latest batch
        assertThat(rows.stream().filter(r -> "#W2".equals(r.orderNumber()))).hasSize(1);
        assertThat(by).doesNotContainKey("#P1");

        s.resolve("pack_cancelled_after_print", by.get("#X1").subjectKey());
        assertThat(TenantContext.runAs(s.f.tenant, () -> lists.printedNotPacked()))
            .extracting(PackListService.NotPackedRow::orderNumber).doesNotContain("#X1");
    }

    // ── Session summary ───────────────────────────────────────────────────────

    @Test
    void summary_countsAndNeedsManagerMatchTheSession_otherUser403() {
        Shop s = new Shop("Summary");
        UUID batch = s.batch(1, "now()");
        UUID session = s.session(s.packer);
        UUID p1 = (UUID) s.printedOrder("#1", batch, 1)[0];
        UUID p2 = (UUID) s.printedOrder("#2", batch, 2)[0];
        UUID aside = (UUID) s.printedOrder("#3", batch, 3)[0];
        UUID cancelled = (UUID) s.printedOrder("#4", batch, 4)[0];
        s.printedOrder("#5", batch, 5);                                           // never scanned → waiting
        jdbc.update("UPDATE orders SET status = 'awaiting_pickup' WHERE id IN (?, ?)", p1, p2);
        jdbc.update("UPDATE orders SET status = 'cancelled' WHERE id = ?", cancelled);
        s.outcome(session, p1, "packed", null, "a");
        s.outcome(session, p2, "packed", null, "b");
        s.outcome(session, aside, "set_aside", "damaged_piece", "c");
        s.outcome(session, cancelled, "rejected", "CANCELLED", "d");
        s.outcome(session, null, "rejected", "NOT_FOUND", "1234567890");
        jdbc.update("UPDATE pack_sessions SET status = 'ended', ended_at = started_at + interval '103 minutes' WHERE id = ?", session);

        String token = jwt.issueAccessToken(s.packer, s.f.tenant, "worker");
        ResponseEntity<Map> r = get(token, "/api/v1/pack-sessions/" + session + "/summary");
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> b = r.getBody();
        assertThat(b.get("workerName")).isEqualTo("Ahmed");
        assertThat(b.get("durationSeconds")).isEqualTo(103 * 60);
        assertThat(b.get("packed")).isEqualTo(2);
        assertThat(b.get("setAside")).isEqualTo(1);
        assertThat(b.get("rejected")).isEqualTo(2);
        List<Map<String, Object>> needs = (List<Map<String, Object>>) b.get("needsManager");
        assertThat(needs).extracting(n -> n.get("orderNumber") + ":" + n.get("kind")).containsExactly("#3:set_aside", "#4:cancelled");
        assertThat(needs.get(0).get("reason")).isEqualTo("damaged_piece");
        assertThat(b.get("unscannedFromTodaysBatches")).isEqualTo(1);              // only #5 is waiting

        String other = jwt.issueAccessToken(s.manager, s.f.tenant, "manager");
        assertThat(get(other, "/api/v1/pack-sessions/" + session + "/summary").getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private ResponseEntity<Map> get(String token, String path) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        return rest.exchange("http://localhost:" + port + path, HttpMethod.GET, new HttpEntity<>(h), Map.class);
    }

    private ResponseEntity<String> post(String token, String path, Object body) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        h.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange("http://localhost:" + port + path, HttpMethod.POST, new HttpEntity<>(body, h), String.class);
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
}
