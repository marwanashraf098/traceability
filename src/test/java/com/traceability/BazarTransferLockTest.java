package com.traceability;

import com.traceability.bazar.BazarService;
import com.traceability.inventory.TransferException;
import com.traceability.inventory.TransferService;
import com.traceability.inventory.UlidGenerator;
import com.traceability.tenancy.TenantContext;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Bazar POS B1 — a bazar event's linked transfer is LOCKED from every generic Transfers action
 * (BAZAR_TRANSFER_LOCKED), while the same actions keep working on a normal transfer.
 *
 * Each lock case puts the bazar transfer in the exact state where the generic action would otherwise SUCCEED, so the
 * refusal can only come from the lock (revert-checked: with the lock checks removed every *_refused test fails).
 * Fixture states the bazar service can't reach in B1 (sent / reconciling) are set with SQL on the postgres connection.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BazarTransferLockTest {

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

    @Autowired TransferService transferSvc;
    @Autowired BazarService    bazar;
    @Autowired JdbcTemplate    jdbc;

    UUID tenantId, actorId, variantId, mainId, showroomId;
    int locationSeq = 0;

    @BeforeAll
    void setup() {
        tenantId = UUID.randomUUID();
        actorId  = UUID.randomUUID();
        UUID storeId = UUID.randomUUID(), productId = UUID.randomUUID();
        variantId = UUID.randomUUID();
        mainId = UUID.randomUUID();
        showroomId = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name, bazar_enabled) VALUES (?, 'Bazar Lock Co', true)", tenantId);
        jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role) " +
            "VALUES (?, ?, 'Owner', 'owner@bazar-lock-test.com', 'h', 'owner')", actorId, tenantId);
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status) " +
            "VALUES (?, ?, 'shopify', 'bazar-lock.myshopify.com', 'disconnected')", storeId, tenantId);
        jdbc.update("INSERT INTO products (id, tenant_id, store_id, external_id, title, status) " +
            "VALUES (?, ?, ?, 'PROD-BL', 'Lock Dress', 'active')", productId, tenantId, storeId);
        jdbc.update("INSERT INTO variants (id, tenant_id, product_id, external_id, title, price) " +
            "VALUES (?, ?, ?, 'VAR-BL', 'Lock Dress / M', 500)", variantId, tenantId, productId);
        jdbc.update("INSERT INTO locations (id, tenant_id, name, type, is_default, is_fulfillment) " +
            "VALUES (?, ?, 'Main Warehouse', 'warehouse', true, true)", mainId, tenantId);
        jdbc.update("INSERT INTO locations (id, tenant_id, name, type, is_default, is_fulfillment) " +
            "VALUES (?, ?, 'Lock Showroom', 'showroom', false, false)", showroomId, tenantId);
    }

    @BeforeEach void ctx()   { TenantContext.set(tenantId); }
    @AfterEach  void clear() { TenantContext.clear(); }

    // ── fixtures ─────────────────────────────────────────────────────────────

    private String piece() {
        String id = UlidGenerator.generate();
        jdbc.update("INSERT INTO pieces (id, tenant_id, variant_id, barcode, short_code, status, current_location_id) " +
            "VALUES (?, ?, ?, ?, 'P' || LPAD((abs(hashtext(?)) % 999999 + 1)::text, 6, '0'), 'available', ?)",
            id, tenantId, variantId, "PC-" + id, id, mainId);
        return id;
    }

    /** A fresh bazar event (own location); returns {eventId, transferId}. */
    private UUID[] bazarEvent() {
        Map<String, Object> ev = bazar.create(new BazarService.CreateEvent("Lock Bazar " + (++locationSeq),
            LocalDate.of(2026, 12, 1), LocalDate.of(2026, 12, 3), null, "Lock Bazar Hall " + locationSeq,
            null, null, null, null, null), actorId);
        return new UUID[] { (UUID) ev.get("id"), (UUID) ev.get("transfer_id") };
    }

    private String bazarPieceOut(UUID eventId) {
        String p = piece();
        assertThat(bazar.scanOut(eventId, "PC-" + p, actorId, false).success()).as("bazar scan-out").isTrue();
        return p;
    }

    private void setStatus(UUID transferId, String status) {
        jdbc.update("UPDATE transfers SET status = ?, sent_at = COALESCE(sent_at, now()), " +
            "reconcile_started_at = CASE WHEN ? = 'reconciling' THEN now() END WHERE id = ?", status, status, transferId);
    }

    private static TransferException.Code codeOf(Runnable r) {
        try { r.run(); } catch (TransferException e) { return e.code(); }
        throw new AssertionError("expected a TransferException");
    }

    private static final TransferException.Code LOCKED = TransferException.Code.BAZAR_TRANSFER_LOCKED;

    private String status(UUID transferId) {
        return jdbc.queryForObject("SELECT status FROM transfers WHERE id = ?", String.class, transferId);
    }

    // ── create ───────────────────────────────────────────────────────────────

    @Test
    void genericCreate_ofTypeBazar_refused() {
        assertThat(codeOf(() -> transferSvc.createTransfer("bazar", showroomId, null, null, actorId))).isEqualTo(LOCKED);
    }

    @Test
    void bazarTransfer_isRoundTripBazarPreparing_toTheEventLocation() {
        UUID[] ev = bazarEvent();
        Map<String, Object> t = jdbc.queryForMap("SELECT * FROM transfers WHERE id = ?", ev[1]);
        assertThat(t.get("transfer_type")).isEqualTo("bazar");
        assertThat(t.get("transfer_mode")).isEqualTo("round_trip");
        assertThat(t.get("status")).isEqualTo("preparing");
        String locType = jdbc.queryForObject("SELECT type::text FROM locations WHERE id = ?", String.class,
            t.get("destination_location_id"));
        assertThat(locType).isEqualTo("event");
    }

    // ── each generic action refuses a bazar transfer ─────────────────────────

    @Test
    void scanOut_refused() {
        UUID[] ev = bazarEvent();
        String p = piece();
        TransferService.ScanOutResult r = transferSvc.scanOut(ev[1], "PC-" + p, actorId);
        assertThat(r.success()).isFalse();
        assertThat(r.code()).isEqualTo("BAZAR_TRANSFER_LOCKED");
        assertThat(jdbc.queryForObject("SELECT status::text FROM pieces WHERE id = ?", String.class, p))
            .isEqualTo("available");
    }

    @Test
    void returnScanOut_refused() {
        UUID[] ev = bazarEvent();
        TransferService.ScanOutResult r = transferSvc.returnScanOut(ev[1], "PC-" + piece(), actorId);
        assertThat(r.code()).isEqualTo("BAZAR_TRANSFER_LOCKED");
    }

    @Test
    void markSent_refused() {
        UUID[] ev = bazarEvent();
        bazarPieceOut(ev[0]);
        assertThat(codeOf(() -> transferSvc.markSent(ev[1], actorId))).isEqualTo(LOCKED);
        assertThat(status(ev[1])).isEqualTo("preparing");
    }

    @Test
    void cancel_refused() {
        UUID[] ev = bazarEvent();   // preparing, no piece: a normal transfer would cancel
        assertThat(codeOf(() -> transferSvc.cancel(ev[1], actorId))).isEqualTo(LOCKED);
        assertThat(status(ev[1])).isEqualTo("preparing");
    }

    @Test
    void beginReconcile_refused() {
        UUID[] ev = bazarEvent();
        bazarPieceOut(ev[0]);
        bazar.markSent(ev[0], actorId);
        assertThat(codeOf(() -> transferSvc.beginReconcile(ev[1], actorId))).isEqualTo(LOCKED);
        assertThat(status(ev[1])).isEqualTo("sent");
    }

    @Test
    void reconcileScanBack_refused() {
        UUID[] ev = bazarEvent();
        String p = bazarPieceOut(ev[0]);
        bazar.markSent(ev[0], actorId);
        setStatus(ev[1], "reconciling");
        TransferService.ScanBackResult r = transferSvc.reconcileScanBack(ev[1], "PC-" + p, "good", actorId);
        assertThat(r.success()).isFalse();
        assertThat(r.code()).isEqualTo("BAZAR_TRANSFER_LOCKED");
        assertThat(jdbc.queryForObject("SELECT status::text FROM pieces WHERE id = ?", String.class, p))
            .isEqualTo("out_on_transfer");
    }

    @Test
    void classifyShortfall_refused() {
        UUID[] ev = bazarEvent();
        bazarPieceOut(ev[0]);
        bazar.markSent(ev[0], actorId);
        setStatus(ev[1], "reconciling");
        UUID lineId = jdbc.queryForObject("SELECT id FROM transfer_lines WHERE transfer_id = ?", UUID.class, ev[1]);
        assertThat(codeOf(() -> transferSvc.classifyShortfall(ev[1], lineId,
            new TransferService.ShortfallCounts(1, 0, 0), actorId))).isEqualTo(LOCKED);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM transfer_pieces WHERE transfer_id = ? AND outcome IS NULL",
            Integer.class, ev[1])).isEqualTo(1);
    }

    @Test
    void closeTransfer_refused() {
        UUID[] ev = bazarEvent();   // no pieces: once reconciling, a normal transfer with 0 outstanding would close
        setStatus(ev[1], "reconciling");
        assertThat(codeOf(() -> transferSvc.closeTransfer(ev[1], actorId))).isEqualTo(LOCKED);
        assertThat(status(ev[1])).isEqualTo("reconciling");
    }

    @Test
    void closeOneWay_refused() {
        UUID[] ev = bazarEvent();
        assertThat(codeOf(() -> transferSvc.closeOneWay(ev[1], actorId))).isEqualTo(LOCKED);
    }

    // ── the same actions still work on a normal transfer ─────────────────────

    @Test
    void normalTransfer_fullLifecycle_stillWorks() {
        UUID id = transferSvc.createTransfer("showroom", showroomId, null, null, actorId);
        String good = piece(), short1 = piece();
        assertThat(transferSvc.scanOut(id, "PC-" + good, actorId).success()).isTrue();
        assertThat(transferSvc.scanOut(id, "PC-" + short1, actorId).success()).isTrue();
        transferSvc.markSent(id, actorId);
        transferSvc.beginReconcile(id, actorId);
        assertThat(transferSvc.reconcileScanBack(id, "PC-" + good, "good", actorId).success()).isTrue();
        UUID lineId = jdbc.queryForObject("SELECT id FROM transfer_lines WHERE transfer_id = ?", UUID.class, id);
        transferSvc.classifyShortfall(id, lineId, new TransferService.ShortfallCounts(0, 1, 0), actorId);
        transferSvc.closeTransfer(id, actorId);
        assertThat(status(id)).isEqualTo("closed");
    }

    @Test
    void normalTransfer_cancel_andRelocateClose_stillWork() {
        UUID c = transferSvc.createTransfer("other", showroomId, null, null, actorId);
        transferSvc.cancel(c, actorId);
        assertThat(status(c)).isEqualTo("cancelled");

        UUID r = transferSvc.createTransfer("other", showroomId, null, null, actorId, "relocate_out");
        assertThat(transferSvc.scanOut(r, "PC-" + piece(), actorId).success()).isTrue();
        transferSvc.closeOneWay(r, actorId);
        assertThat(status(r)).isEqualTo("closed");
    }

    // ── the bazar entry points don't open normal transfers ───────────────────

    @Test
    void bazarEntryPoints_refuseANormalTransfer() {
        UUID id = transferSvc.createTransfer("showroom", showroomId, null, null, actorId);
        assertThat(transferSvc.scanOutForBazar(id, "PC-" + piece(), actorId, false).code()).isEqualTo("TRANSFER_NOT_FOUND");
        assertThat(codeOf(() -> transferSvc.markSentForBazar(id, actorId)))
            .isEqualTo(TransferException.Code.TRANSFER_NOT_FOUND);
    }

    // ── the bazar entry points have ONE caller ───────────────────────────────

    private static final Pattern BAZAR_ENTRY = Pattern.compile("\\.(scanOutForBazar|markSentForBazar|createBazarTransfer)\\(");

    @Test
    void bazarEntryPoints_areCalledOnlyByBazarService() throws IOException {
        List<String> callers;
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            callers = files.filter(f -> f.toString().endsWith(".java"))
                .filter(f -> {
                    try { return BAZAR_ENTRY.matcher(Files.readString(f)).find(); }
                    catch (IOException e) { throw new RuntimeException(e); }
                })
                .map(f -> f.getFileName().toString())
                .toList();
        }
        assertThat(callers).containsExactly("BazarService.java");
    }

    // B5 (approved 2026-11-18): the reconcile side — end, scan back, mark lost, close — has ONE caller too.
    private static final Pattern BAZAR_RECONCILE_ENTRY = Pattern.compile(
        "\\.(beginReconcileForBazar|reconcileScanBackForBazar|bringBackDamagedForBazar|markLostForBazar|closeTransferForBazar)\\(");

    @Test
    void bazarReconcileEntryPoints_areCalledOnlyByBazarEndService() throws IOException {
        List<String> callers;
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            callers = files.filter(f -> f.toString().endsWith(".java"))
                .filter(f -> {
                    try { return BAZAR_RECONCILE_ENTRY.matcher(Files.readString(f)).find(); }
                    catch (IOException e) { throw new RuntimeException(e); }
                })
                .map(f -> f.getFileName().toString())
                .toList();
        }
        assertThat(callers).containsExactly("BazarEndService.java");
    }

    @Test
    void bazarReconcileEntryPoints_refuseANormalTransfer() {
        UUID id = transferSvc.createTransfer("showroom", showroomId, null, null, actorId);
        String p = piece();
        assertThat(transferSvc.scanOut(id, "PC-" + p, actorId).success()).isTrue();
        transferSvc.markSent(id, actorId);
        assertThat(codeOf(() -> transferSvc.beginReconcileForBazar(id, actorId)))
            .isEqualTo(TransferException.Code.TRANSFER_NOT_FOUND);
        transferSvc.beginReconcile(id, actorId);
        assertThat(transferSvc.reconcileScanBackForBazar(id, "PC-" + p, actorId, "x").code()).isEqualTo("TRANSFER_NOT_FOUND");
        UUID lineId = jdbc.queryForObject("SELECT id FROM transfer_lines WHERE transfer_id = ?", UUID.class, id);
        assertThat(codeOf(() -> transferSvc.markLostForBazar(id, lineId, List.of(p), actorId)))
            .isEqualTo(TransferException.Code.TRANSFER_NOT_FOUND);
        assertThat(codeOf(() -> transferSvc.closeTransferForBazar(id, actorId)))
            .isEqualTo(TransferException.Code.TRANSFER_NOT_FOUND);
        assertThat(status(id)).isEqualTo("reconciling");
        assertThat(jdbc.queryForObject("SELECT status::text FROM pieces WHERE id = ?", String.class, p))
            .isEqualTo("out_on_transfer");
    }
}
