package com.traceability;

import com.traceability.inventory.*;
import com.traceability.tenancy.TenantContext;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V118 transfer lifecycle — preparing → sent → reconciling → closed, relocate_out preparing →
 * closed, cancelled from preparing with zero pieces ever scanned.
 *
 * The race tests hold the FIRST action's transaction open (TransactionTemplate, so the service
 * method joins it and its locks stay held) while the SECOND action starts on another thread.
 * With the transfer-row locks the second action blocks until the first commits and then sees
 * its result; with them removed it runs straight through against the stale committed state —
 * which is exactly how a piece ends up out_on_transfer on a cancelled / closed transfer with
 * outcome NULL. Every race test asserts that never happens.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TransferLifecycleTest {

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

    @Autowired TransferService            transferSvc;
    @Autowired JdbcTemplate               jdbc;
    @Autowired PlatformTransactionManager txManager;

    UUID tenantId;
    UUID actorId;
    UUID variantId;
    UUID destinationLocationId;
    UUID fulfillmentLocationId;

    @BeforeAll
    void setup() {
        tenantId = UUID.randomUUID();
        actorId  = UUID.randomUUID();
        UUID storeId   = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        variantId = UUID.randomUUID();
        destinationLocationId = UUID.randomUUID();
        fulfillmentLocationId = UUID.randomUUID();

        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, 'Transfer Lifecycle Co')", tenantId);
        jdbc.update(
            "INSERT INTO users (id, tenant_id, name, email, password_hash, role) " +
            "VALUES (?, ?, 'Lifecycle Actor', 'actor@transfer-lifecycle-test.com', 'h', 'owner')",
            actorId, tenantId);
        jdbc.update(
            "INSERT INTO stores (id, tenant_id, platform, shop_domain, status) " +
            "VALUES (?, ?, 'shopify', 'transfer-lifecycle.myshopify.com', 'disconnected')",
            storeId, tenantId);
        jdbc.update(
            "INSERT INTO products (id, tenant_id, store_id, external_id, title, status) " +
            "VALUES (?, ?, ?, 'PROD-TL', 'Lifecycle Widget', 'active')",
            productId, tenantId, storeId);
        jdbc.update(
            "INSERT INTO variants (id, tenant_id, product_id, external_id, title) " +
            "VALUES (?, ?, ?, 'VAR-TL', 'Lifecycle Widget Variant')",
            variantId, tenantId, productId);
        jdbc.update(
            "INSERT INTO locations (id, tenant_id, name, type, is_default, is_fulfillment) " +
            "VALUES (?, ?, 'Lifecycle Showroom', 'showroom', false, false)",
            destinationLocationId, tenantId);
        jdbc.update(
            "INSERT INTO locations (id, tenant_id, name, type, is_default, is_fulfillment) " +
            "VALUES (?, ?, 'Main Warehouse', 'warehouse', true, true)",
            fulfillmentLocationId, tenantId);
    }

    @BeforeEach
    void setTenantContext() { TenantContext.set(tenantId); }

    @AfterEach
    void clearTenantContext() { TenantContext.clear(); }

    // ── helpers ──────────────────────────────────────────────────────────────

    private String insertPiece(String status, UUID locationId) {
        String id = UlidGenerator.generate();
        jdbc.update(
            "INSERT INTO pieces (id, tenant_id, variant_id, barcode, short_code, status, current_location_id) " +
            "VALUES (?, ?, ?, ?, 'P' || LPAD((abs(hashtext(?)) % 999999 + 1)::text, 6, '0'), ?::piece_status, ?)",
            id, tenantId, variantId, "PC-" + id, id, status, locationId);
        return id;
    }

    private UUID newTransfer(String mode) {
        return transferSvc.createTransfer("other", destinationLocationId, null, null, actorId, mode);
    }

    private UUID transferWithPieces(String mode, int n) {
        UUID id = newTransfer(mode);
        for (int i = 0; i < n; i++) {
            String piece = insertPiece("available", fulfillmentLocationId);
            assertThat(transferSvc.scanOut(id, "PC-" + piece, actorId).success()).as("fixture scan").isTrue();
        }
        return id;
    }

    private Map<String, Object> row(UUID transferId) {
        return jdbc.queryForMap("SELECT * FROM transfers WHERE id = ?", transferId);
    }

    private String status(UUID transferId) {
        return jdbc.queryForObject("SELECT status FROM transfers WHERE id = ?", String.class, transferId);
    }

    private String pieceStatus(String pieceId) {
        return jdbc.queryForObject("SELECT status::text FROM pieces WHERE id = ?", String.class, pieceId);
    }

    private int eventCount(UUID transferId) {
        return jdbc.queryForObject(
            "SELECT COUNT(*) FROM piece_events WHERE metadata->>'transfer_id' = ?", Integer.class, transferId.toString());
    }

    /** The invariant every race protects: no piece is left without an outcome on a transfer
     *  that can never resolve it. */
    private int strandedPieces(UUID transferId) {
        return jdbc.queryForObject(
            "SELECT COUNT(*) FROM transfer_pieces tp JOIN transfers t ON t.id = tp.transfer_id " +
            "WHERE t.id = ? AND t.status IN ('closed', 'cancelled') AND tp.outcome IS NULL",
            Integer.class, transferId);
    }

    private static TransferException.Code codeOf(Runnable r) {
        try {
            r.run();
        } catch (TransferException e) {
            return e.code();
        }
        throw new AssertionError("expected a TransferException");
    }

    // ── Mark as sent ─────────────────────────────────────────────────────────

    @Test
    void markSent_roundTripWithPieces_movesToSent_stampsWho_writesNoPieceEvents() {
        UUID id = transferWithPieces("round_trip", 2);
        int eventsBefore = eventCount(id);

        transferSvc.markSent(id, actorId);

        Map<String, Object> r = row(id);
        assertThat(r.get("status")).isEqualTo("sent");
        assertThat(r.get("sent_at")).isNotNull();
        assertThat(r.get("sent_by").toString()).isEqualTo(actorId.toString());
        assertThat(eventCount(id)).as("mark-sent writes no piece events").isEqualTo(eventsBefore);
    }

    @Test
    void markSent_nothingScanned_refusesAndStaysPreparing() {
        UUID id = newTransfer("round_trip");
        assertThat(codeOf(() -> transferSvc.markSent(id, actorId))).isEqualTo(TransferException.Code.TRANSFER_EMPTY);
        assertThat(status(id)).isEqualTo("preparing");
    }

    @Test
    void markSent_relocateOut_refusesWrongMode() {
        UUID id = transferWithPieces("relocate_out", 1);
        assertThat(codeOf(() -> transferSvc.markSent(id, actorId))).isEqualTo(TransferException.Code.TRANSFER_WRONG_MODE);
        assertThat(status(id)).isEqualTo("preparing");
    }

    @Test
    void markSent_twice_secondRefusedNotPreparing() {
        UUID id = transferWithPieces("round_trip", 1);
        transferSvc.markSent(id, actorId);
        assertThat(codeOf(() -> transferSvc.markSent(id, actorId))).isEqualTo(TransferException.Code.TRANSFER_NOT_PREPARING);
    }

    @Test
    void markSent_relocateReturn_withPieces_movesToSent() {
        UUID id = transferSvc.createTransfer("other", fulfillmentLocationId, null, null, actorId,
            "relocate_return", destinationLocationId);
        String piece = insertPiece("transferred_out", destinationLocationId);
        assertThat(transferSvc.returnScanOut(id, "PC-" + piece, actorId).success()).isTrue();

        transferSvc.markSent(id, actorId);

        assertThat(status(id)).isEqualTo("sent");
    }

    @Test
    void scanOut_afterSent_isRejected_pieceStaysAvailable() {
        UUID id = transferWithPieces("round_trip", 1);
        transferSvc.markSent(id, actorId);
        String piece = insertPiece("available", fulfillmentLocationId);

        TransferService.ScanOutResult r = transferSvc.scanOut(id, "PC-" + piece, actorId);

        assertThat(r.success()).isFalse();
        assertThat(r.code()).isEqualTo("TRANSFER_NOT_OPEN");
        assertThat(pieceStatus(piece)).isEqualTo("available");
    }

    // ── Cancel ───────────────────────────────────────────────────────────────

    @Test
    void cancel_emptyPreparing_movesToCancelled_stampsWho() {
        for (String mode : List.of("round_trip", "relocate_out")) {
            UUID id = newTransfer(mode);
            transferSvc.cancel(id, actorId);
            Map<String, Object> r = row(id);
            assertThat(r.get("status")).as(mode).isEqualTo("cancelled");
            assertThat(r.get("cancelled_at")).as(mode).isNotNull();
            assertThat(r.get("cancelled_by").toString()).as(mode).isEqualTo(actorId.toString());
            assertThat(eventCount(id)).as(mode).isZero();
        }
    }

    @Test
    void cancel_withAScannedPiece_refusesHasPieces() {
        UUID id = transferWithPieces("round_trip", 1);
        assertThat(codeOf(() -> transferSvc.cancel(id, actorId))).isEqualTo(TransferException.Code.TRANSFER_HAS_PIECES);
        assertThat(status(id)).isEqualTo("preparing");
    }

    @Test
    void cancel_notPreparing_refused() {
        UUID sent = transferWithPieces("round_trip", 1);
        transferSvc.markSent(sent, actorId);
        assertThat(codeOf(() -> transferSvc.cancel(sent, actorId))).isEqualTo(TransferException.Code.TRANSFER_NOT_PREPARING);

        UUID cancelled = newTransfer("round_trip");
        transferSvc.cancel(cancelled, actorId);
        assertThat(codeOf(() -> transferSvc.cancel(cancelled, actorId))).isEqualTo(TransferException.Code.TRANSFER_NOT_PREPARING);
    }

    @Test
    void cancelled_transfer_refusesScanOut() {
        UUID id = newTransfer("round_trip");
        transferSvc.cancel(id, actorId);
        String piece = insertPiece("available", fulfillmentLocationId);

        assertThat(transferSvc.scanOut(id, "PC-" + piece, actorId).success()).isFalse();
        assertThat(pieceStatus(piece)).isEqualTo("available");
    }

    // ── Begin reconcile / close one-way ──────────────────────────────────────

    @Test
    void beginReconcile_fromPreparing_refusedNotSent() {
        UUID id = transferWithPieces("round_trip", 1);
        assertThat(codeOf(() -> transferSvc.beginReconcile(id, actorId))).isEqualTo(TransferException.Code.TRANSFER_NOT_SENT);
        assertThat(status(id)).isEqualTo("preparing");
    }

    @Test
    void beginReconcile_relocateOut_refusedWrongMode() {
        UUID id = transferWithPieces("relocate_out", 1);
        assertThat(codeOf(() -> transferSvc.beginReconcile(id, actorId))).isEqualTo(TransferException.Code.TRANSFER_WRONG_MODE);
        assertThat(status(id)).isEqualTo("preparing");
    }

    @Test
    void beginReconcile_fromSent_movesToReconciling_stampsWho() {
        UUID id = transferWithPieces("round_trip", 1);
        transferSvc.markSent(id, actorId);

        transferSvc.beginReconcile(id, actorId);

        Map<String, Object> r = row(id);
        assertThat(r.get("status")).isEqualTo("reconciling");
        assertThat(r.get("reconcile_started_at")).isNotNull();
        assertThat(r.get("reconcile_started_by").toString()).isEqualTo(actorId.toString());
    }

    @Test
    void closeOneWay_onCancelled_refusedNotPreparing() {
        UUID id = newTransfer("relocate_out");
        transferSvc.cancel(id, actorId);
        assertThat(codeOf(() -> transferSvc.closeOneWay(id, actorId))).isEqualTo(TransferException.Code.TRANSFER_NOT_PREPARING);
    }

    // ── List / detail ────────────────────────────────────────────────────────

    @Test
    void listOpen_groupsStatuses_andDetailCountsEveryScannedPiece() {
        UUID preparing = newTransfer("round_trip");
        UUID sent = transferWithPieces("round_trip", 1);
        transferSvc.markSent(sent, actorId);
        UUID reconciling = transferWithPieces("round_trip", 1);
        transferSvc.markSent(reconciling, actorId);
        transferSvc.beginReconcile(reconciling, actorId);
        UUID cancelled = newTransfer("round_trip");
        transferSvc.cancel(cancelled, actorId);
        UUID closed = transferWithPieces("relocate_out", 2);
        transferSvc.closeOneWay(closed, actorId);

        List<String> open = transferSvc.listOpen("open").stream().map(m -> m.get("id").toString()).toList();
        List<String> done = transferSvc.listOpen("closed").stream().map(m -> m.get("id").toString()).toList();

        assertThat(open).contains(preparing.toString(), sent.toString(), reconciling.toString())
            .doesNotContain(cancelled.toString(), closed.toString());
        assertThat(done).contains(cancelled.toString(), closed.toString())
            .doesNotContain(preparing.toString(), sent.toString(), reconciling.toString());

        assertThat(transferSvc.getTransfer(closed).get("piecesEverCount"))
            .as("every piece ever scanned, including resolved ones").isEqualTo(2);
        assertThat(transferSvc.getTransfer(closed).get("outstandingCount")).isEqualTo(0);
        assertThat(transferSvc.getTransfer(preparing).get("piecesEverCount")).isEqualTo(0);
    }

    // ── Races ────────────────────────────────────────────────────────────────

    private record RaceResult(Object secondResult, Throwable secondFailure, boolean secondWaited) {}

    /**
     * Runs {@code first} inside a transaction that stays open until {@code second} has had
     * 700 ms to run on another thread, then commits. secondWaited = the second action was
     * still blocked when the first committed (i.e. it waited on the first's lock).
     */
    private RaceResult race(Runnable first, Supplier<Object> second) throws InterruptedException {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        CountDownLatch firstDone = new CountDownLatch(1);
        CountDownLatch release   = new CountDownLatch(1);
        AtomicReference<Throwable> firstFailure  = new AtomicReference<>();
        AtomicReference<Throwable> secondFailure = new AtomicReference<>();
        AtomicReference<Object>    secondResult  = new AtomicReference<>();

        Thread holder = new Thread(() -> {
            TenantContext.set(tenantId);
            try {
                tx.executeWithoutResult(s -> {
                    first.run();
                    firstDone.countDown();
                    try { release.await(10, TimeUnit.SECONDS); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                });
            } catch (Throwable t) {
                firstFailure.set(t);
            } finally {
                firstDone.countDown();
                TenantContext.clear();
            }
        });
        holder.start();
        assertThat(firstDone.await(10, TimeUnit.SECONDS)).isTrue();

        Thread other = new Thread(() -> {
            TenantContext.set(tenantId);
            try { secondResult.set(second.get()); }
            catch (Throwable t) { secondFailure.set(t); }
            finally { TenantContext.clear(); }
        });
        other.start();
        other.join(700);
        boolean waited = other.isAlive();
        release.countDown();
        holder.join(10_000);
        other.join(10_000);

        assertThat(firstFailure.get()).as("the first action must succeed").isNull();
        return new RaceResult(secondResult.get(), secondFailure.get(), waited);
    }

    private static TransferException.Code code(Throwable t) {
        assertThat(t).isInstanceOf(TransferException.class);
        return ((TransferException) t).code();
    }

    @Test
    void race_scanThenCancel_cancelWaitsThenSeesThePiece() throws InterruptedException {
        UUID id = newTransfer("round_trip");
        String piece = insertPiece("available", fulfillmentLocationId);

        RaceResult r = race(
            () -> assertThat(transferSvc.scanOut(id, "PC-" + piece, actorId).success()).isTrue(),
            () -> { transferSvc.cancel(id, actorId); return null; });

        assertThat(r.secondWaited()).as("cancel waits for the in-flight scan").isTrue();
        assertThat(code(r.secondFailure())).isEqualTo(TransferException.Code.TRANSFER_HAS_PIECES);
        assertThat(status(id)).isEqualTo("preparing");
        assertThat(strandedPieces(id)).isZero();
    }

    @Test
    void race_cancelThenScan_scanWaitsThenIsRejected() throws InterruptedException {
        UUID id = newTransfer("round_trip");
        String piece = insertPiece("available", fulfillmentLocationId);

        RaceResult r = race(
            () -> transferSvc.cancel(id, actorId),
            () -> transferSvc.scanOut(id, "PC-" + piece, actorId));

        assertThat(r.secondWaited()).as("the scan waits for the in-flight cancel").isTrue();
        assertThat(r.secondFailure()).isNull();
        assertThat(((TransferService.ScanOutResult) r.secondResult()).success()).isFalse();
        assertThat(status(id)).isEqualTo("cancelled");
        assertThat(pieceStatus(piece)).isEqualTo("available");
        assertThat(strandedPieces(id)).isZero();
    }

    @Test
    void race_scanThenMarkSent_markSentWaitsThenIncludesThePiece() throws InterruptedException {
        UUID id = transferWithPieces("round_trip", 1);
        String piece = insertPiece("available", fulfillmentLocationId);

        RaceResult r = race(
            () -> assertThat(transferSvc.scanOut(id, "PC-" + piece, actorId).success()).isTrue(),
            () -> { transferSvc.markSent(id, actorId); return null; });

        assertThat(r.secondWaited()).as("mark-sent waits for the in-flight scan").isTrue();
        assertThat(r.secondFailure()).isNull();
        assertThat(status(id)).isEqualTo("sent");
        assertThat(transferSvc.getTransfer(id).get("outstandingCount")).isEqualTo(2);
    }

    @Test
    void race_markSentThenScan_scanWaitsThenIsRejected() throws InterruptedException {
        UUID id = transferWithPieces("round_trip", 1);
        String piece = insertPiece("available", fulfillmentLocationId);

        RaceResult r = race(
            () -> transferSvc.markSent(id, actorId),
            () -> transferSvc.scanOut(id, "PC-" + piece, actorId));

        assertThat(r.secondWaited()).as("the scan waits for the in-flight mark-sent").isTrue();
        assertThat(((TransferService.ScanOutResult) r.secondResult()).success())
            .as("no piece joins a transfer after it was marked sent").isFalse();
        assertThat(pieceStatus(piece)).isEqualTo("available");
        assertThat(transferSvc.getTransfer(id).get("outstandingCount")).isEqualTo(1);
    }

    @Test
    void race_scanThenCloseOneWay_closeWaitsThenRelocatesThePieceToo() throws InterruptedException {
        UUID id = transferWithPieces("relocate_out", 1);
        String piece = insertPiece("available", fulfillmentLocationId);

        RaceResult r = race(
            () -> assertThat(transferSvc.scanOut(id, "PC-" + piece, actorId).success()).isTrue(),
            () -> { transferSvc.closeOneWay(id, actorId); return null; });

        assertThat(r.secondWaited()).as("close waits for the in-flight scan").isTrue();
        assertThat(r.secondFailure()).isNull();
        assertThat(status(id)).isEqualTo("closed");
        assertThat(pieceStatus(piece)).isEqualTo("transferred_out");
        assertThat(strandedPieces(id)).isZero();
    }

    @Test
    void race_closeOneWayThenScan_scanWaitsThenIsRejected() throws InterruptedException {
        UUID id = transferWithPieces("relocate_out", 1);
        String piece = insertPiece("available", fulfillmentLocationId);

        RaceResult r = race(
            () -> transferSvc.closeOneWay(id, actorId),
            () -> transferSvc.scanOut(id, "PC-" + piece, actorId));

        assertThat(r.secondWaited()).as("the scan waits for the in-flight close").isTrue();
        assertThat(((TransferService.ScanOutResult) r.secondResult()).success()).isFalse();
        assertThat(status(id)).isEqualTo("closed");
        assertThat(pieceStatus(piece)).isEqualTo("available");
        assertThat(strandedPieces(id)).isZero();
    }
}
