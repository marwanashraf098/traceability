package com.traceability;

import com.traceability.inventory.*;
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

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * FR-22.4 — TransferService reconcile: beginReconcile / reconcileScanBack /
 * classifyShortfall / closeTransfer.
 *
 * No TransferController yet (FR-22.6) — role gating (MANAGER/OWNER on all four methods)
 * is a controller-level @PreAuthorize concern, not tested here; these tests exercise the
 * service layer directly, matching TransferServiceTest's FR-22.3 pattern.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TransferReconcileTest {

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

    @Autowired TransferService     transferSvc;
    @Autowired PieceAdjustService  pieceAdjustSvc;
    @Autowired JdbcTemplate        jdbc;

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
        destinationLocationId  = UUID.randomUUID();
        fulfillmentLocationId  = UUID.randomUUID();

        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, 'Reconcile Test Co')", tenantId);
        jdbc.update(
            "INSERT INTO users (id, tenant_id, name, email, password_hash, role) " +
            "VALUES (?, ?, 'Test Actor', 'actor@reconcile-test.com', 'h', 'owner')",
            actorId, tenantId);
        jdbc.update(
            "INSERT INTO stores (id, tenant_id, platform, shop_domain, status) " +
            "VALUES (?, ?, 'shopify', 'reconcile-test.myshopify.com', 'disconnected')",
            storeId, tenantId);
        jdbc.update(
            "INSERT INTO products (id, tenant_id, store_id, external_id, title, status) " +
            "VALUES (?, ?, ?, 'PROD-RC', 'Reconcile Widget', 'active')",
            productId, tenantId, storeId);
        jdbc.update(
            "INSERT INTO variants (id, tenant_id, product_id, external_id, title) " +
            "VALUES (?, ?, ?, 'VAR-RC', 'Reconcile Widget Variant')",
            variantId, tenantId, productId);
        jdbc.update(
            "INSERT INTO locations (id, tenant_id, name, type, is_default, is_fulfillment) " +
            "VALUES (?, ?, 'Main Warehouse', 'warehouse', true, true)",
            fulfillmentLocationId, tenantId);
        jdbc.update(
            "INSERT INTO locations (id, tenant_id, name, type, is_default, is_fulfillment) " +
            "VALUES (?, ?, 'Test Showroom', 'showroom', false, false)",
            destinationLocationId, tenantId);
    }

    @BeforeEach
    void setTenantContext() { TenantContext.set(tenantId); }

    @AfterEach
    void clearTenantContext() { TenantContext.clear(); }

    // ── helpers ──────────────────────────────────────────────────────────────

    private String insertAvailablePiece() {
        String id = UlidGenerator.generate();
        jdbc.update(
            "INSERT INTO pieces (id, tenant_id, variant_id, barcode, short_code, status) " +
            "VALUES (?, ?, ?, ?, 'P' || LPAD((abs(hashtext(?)) % 999999 + 1)::text, 6, '0'), 'available'::piece_status)",
            id, tenantId, variantId, "PC-" + id, id);
        return id;
    }

    /** Opens a transfer and sends N fresh available pieces out on it via the real scanOut(). */
    private UUID openTransferWithOutstanding(int n) {
        UUID transferId = transferSvc.createTransfer("showroom", destinationLocationId, null, "test", actorId);
        for (int i = 0; i < n; i++) {
            String pieceId = insertAvailablePiece();
            TransferService.ScanOutResult r = transferSvc.scanOut(transferId, "PC-" + pieceId, actorId);
            assertThat(r.success()).as("fixture scanOut must succeed").isTrue();
        }
        return transferId;
    }

    private UUID lineIdFor(UUID transferId) {
        return jdbc.queryForObject(
            "SELECT id FROM transfer_lines WHERE transfer_id = ? AND variant_id = ?",
            UUID.class, transferId, variantId);
    }

    private String fetchStatus(String pieceId) {
        return jdbc.queryForObject("SELECT status FROM pieces WHERE id = ?", String.class, pieceId);
    }

    private String outstandingPieceId(UUID transferId) {
        return jdbc.queryForObject(
            "SELECT piece_id FROM transfer_pieces WHERE transfer_id = ? AND outcome IS NULL " +
            "ORDER BY created_at ASC LIMIT 1", String.class, transferId);
    }

    private String transferPieceOutcome(UUID transferId, String pieceId) {
        return jdbc.queryForObject(
            "SELECT outcome FROM transfer_pieces WHERE transfer_id = ? AND piece_id = ?",
            String.class, transferId, pieceId);
    }

    // -----------------------------------------------------------------------
    // beginReconcile
    // -----------------------------------------------------------------------

    @Test
    void beginReconcile_openTransfer_movesToReconciling() {
        UUID transferId = openTransferWithOutstanding(1);

        transferSvc.beginReconcile(transferId, actorId);

        String status = jdbc.queryForObject("SELECT status FROM transfers WHERE id = ?", String.class, transferId);
        assertThat(status).isEqualTo("reconciling");
    }

    @Test
    void beginReconcile_alreadyReconciling_rejectsWithNoStateChange() {
        UUID transferId = openTransferWithOutstanding(1);
        transferSvc.beginReconcile(transferId, actorId);

        assertThatThrownBy(() -> transferSvc.beginReconcile(transferId, actorId))
            .isInstanceOf(TransferException.class);

        String status = jdbc.queryForObject("SELECT status FROM transfers WHERE id = ?", String.class, transferId);
        assertThat(status).isEqualTo("reconciling");
    }

    @Test
    void beginReconcile_notFound_rejects() {
        assertThatThrownBy(() -> transferSvc.beginReconcile(UUID.randomUUID(), actorId))
            .isInstanceOf(TransferException.class);
    }

    // -----------------------------------------------------------------------
    // reconcileScanBack
    // -----------------------------------------------------------------------

    @Test
    void reconcileScanBack_good_setsAvailableAndFulfillmentLocationAndVerifiedMetadata() {
        UUID transferId = openTransferWithOutstanding(1);
        String pieceId = outstandingPieceId(transferId);
        transferSvc.beginReconcile(transferId, actorId);

        TransferService.ScanBackResult result =
            transferSvc.reconcileScanBack(transferId, "PC-" + pieceId, "good", actorId);

        assertThat(result.success()).isTrue();
        assertThat(result.outcome()).isEqualTo("returned_good");
        assertThat(fetchStatus(pieceId)).isEqualTo("available");

        Map<String, Object> pieceRow = jdbc.queryForMap(
            "SELECT current_location_id FROM pieces WHERE id = ?", pieceId);
        assertThat(pieceRow.get("current_location_id").toString()).isEqualTo(fulfillmentLocationId.toString());

        Map<String, Object> event = jdbc.queryForMap(
            "SELECT event_type, metadata FROM piece_events WHERE piece_id = ? ORDER BY id DESC LIMIT 1", pieceId);
        assertThat(event.get("event_type")).isEqualTo("returned_from_transfer");
        String metaJson = event.get("metadata").toString();
        assertThat(metaJson).contains("\"verified\": true").contains("\"reconciliation\": \"scan\"");
        assertThat(metaJson).doesNotContain("attributed_to");

        Map<String, Object> tpRow = jdbc.queryForMap(
            "SELECT outcome, outcome_verified FROM transfer_pieces WHERE transfer_id = ? AND piece_id = ?",
            transferId, pieceId);
        assertThat(tpRow.get("outcome")).isEqualTo("returned_good");
        assertThat(tpRow.get("outcome_verified")).isEqualTo(true);

        Integer qtyReturnedGood = jdbc.queryForObject(
            "SELECT qty_returned_good FROM transfer_lines WHERE transfer_id = ? AND variant_id = ?",
            Integer.class, transferId, variantId);
        assertThat(qtyReturnedGood).isEqualTo(1);
    }

    @Test
    void reconcileScanBack_condemned_setsDamagedWithVendorAttribution() {
        UUID transferId = openTransferWithOutstanding(1);
        String pieceId = outstandingPieceId(transferId);
        transferSvc.beginReconcile(transferId, actorId);

        TransferService.ScanBackResult result =
            transferSvc.reconcileScanBack(transferId, "PC-" + pieceId, "condemned", actorId);

        assertThat(result.success()).isTrue();
        assertThat(result.outcome()).isEqualTo("condemned");
        assertThat(fetchStatus(pieceId)).isEqualTo("damaged");

        Map<String, Object> event = jdbc.queryForMap(
            "SELECT event_type, metadata FROM piece_events WHERE piece_id = ? ORDER BY id DESC LIMIT 1", pieceId);
        assertThat(event.get("event_type")).isEqualTo("condemned_at_vendor");
        String metaJson = event.get("metadata").toString();
        assertThat(metaJson)
            .contains("\"verified\": true")
            .contains("\"reconciliation\": \"scan\"")
            .contains("\"attributed_to\": \"vendor\"");

        // Condemned-but-scanned-back is still a physical return — location moves too.
        Map<String, Object> pieceRow = jdbc.queryForMap(
            "SELECT current_location_id FROM pieces WHERE id = ?", pieceId);
        assertThat(pieceRow.get("current_location_id").toString()).isEqualTo(fulfillmentLocationId.toString());

        Integer qtyCondemned = jdbc.queryForObject(
            "SELECT qty_condemned FROM transfer_lines WHERE transfer_id = ? AND variant_id = ?",
            Integer.class, transferId, variantId);
        assertThat(qtyCondemned).isEqualTo(1);
    }

    @Test
    void reconcileScanBack_pieceNotOutstandingOnThisTransfer_cleanErrorNoMutation() {
        UUID transferId = openTransferWithOutstanding(0);
        transferSvc.beginReconcile(transferId, actorId);
        // A piece that exists but was never sent out on ANY transfer.
        String pieceId = insertAvailablePiece();

        TransferService.ScanBackResult result =
            transferSvc.reconcileScanBack(transferId, "PC-" + pieceId, "good", actorId);

        assertThat(result.success()).isFalse();
        assertThat(result.code()).isEqualTo("NOT_OUTSTANDING_ON_TRANSFER");
        assertThat(fetchStatus(pieceId)).as("no mutation on mis-scan").isEqualTo("available");
        Integer events = jdbc.queryForObject(
            "SELECT COUNT(*) FROM piece_events WHERE piece_id = ?", Integer.class, pieceId);
        assertThat(events).isEqualTo(0);
    }

    @Test
    void reconcileScanBack_pieceOutstandingOnAnotherTransfer_cleanErrorNoMutation() {
        UUID transferA = openTransferWithOutstanding(1);
        String pieceId = outstandingPieceId(transferA);
        UUID transferB = openTransferWithOutstanding(0);
        transferSvc.beginReconcile(transferB, actorId);

        TransferService.ScanBackResult result =
            transferSvc.reconcileScanBack(transferB, "PC-" + pieceId, "good", actorId);

        assertThat(result.success()).isFalse();
        assertThat(result.code()).isEqualTo("NOT_OUTSTANDING_ON_TRANSFER");
        assertThat(fetchStatus(pieceId)).isEqualTo("out_on_transfer");
    }

    @Test
    void reconcileScanBack_transferNotReconciling_rejects() {
        UUID transferId = openTransferWithOutstanding(1);
        String pieceId = outstandingPieceId(transferId);
        // Still 'open' — beginReconcile never called.

        TransferService.ScanBackResult result =
            transferSvc.reconcileScanBack(transferId, "PC-" + pieceId, "good", actorId);

        assertThat(result.success()).isFalse();
        assertThat(result.code()).isEqualTo("TRANSFER_NOT_RECONCILING");
    }

    // -----------------------------------------------------------------------
    // classifyShortfall
    // -----------------------------------------------------------------------

    @Test
    void classifyShortfall_fifoOrder_oldestPiecesClosedFirst() throws InterruptedException {
        UUID transferId = transferSvc.createTransfer("showroom", destinationLocationId, null, "test", actorId);
        String first  = insertAvailablePiece();
        transferSvc.scanOut(transferId, "PC-" + first, actorId);
        Thread.sleep(5); // ensure created_at strictly increases across statements
        String second = insertAvailablePiece();
        transferSvc.scanOut(transferId, "PC-" + second, actorId);
        Thread.sleep(5);
        String third  = insertAvailablePiece();
        transferSvc.scanOut(transferId, "PC-" + third, actorId);

        transferSvc.beginReconcile(transferId, actorId);
        UUID lineId = lineIdFor(transferId);

        // Classify 2 as sold — FIFO must pick "first" and "second", leaving "third" outstanding.
        transferSvc.classifyShortfall(transferId, lineId,
            new TransferService.ShortfallCounts(2, 0, 0), actorId);

        assertThat(fetchStatus(first)).isEqualTo("sold");
        assertThat(fetchStatus(second)).isEqualTo("sold");
        assertThat(fetchStatus(third)).isEqualTo("out_on_transfer");
    }

    @Test
    void classifyShortfall_lost_setsLostWithUnverifiedVendorAttribution() {
        UUID transferId = openTransferWithOutstanding(1);
        String pieceId = outstandingPieceId(transferId);
        transferSvc.beginReconcile(transferId, actorId);
        UUID lineId = lineIdFor(transferId);

        transferSvc.classifyShortfall(transferId, lineId,
            new TransferService.ShortfallCounts(0, 1, 0), actorId);

        assertThat(fetchStatus(pieceId)).isEqualTo("lost");

        Map<String, Object> event = jdbc.queryForMap(
            "SELECT event_type, metadata FROM piece_events WHERE piece_id = ? ORDER BY id DESC LIMIT 1", pieceId);
        assertThat(event.get("event_type")).isEqualTo("lost_at_vendor");
        String metaJson = event.get("metadata").toString();
        assertThat(metaJson)
            .contains("\"verified\": false")
            .contains("\"reconciliation\": \"quantity_based\"")
            .contains("\"attributed_to\": \"vendor\"");

        Map<String, Object> tpRow = jdbc.queryForMap(
            "SELECT outcome, outcome_verified FROM transfer_pieces WHERE transfer_id = ? AND piece_id = ?",
            transferId, pieceId);
        assertThat(tpRow.get("outcome")).isEqualTo("lost");
        assertThat(tpRow.get("outcome_verified")).isEqualTo(false);
    }

    @Test
    void classifyShortfall_condemnedNotReturned_setsDamagedUnverified() {
        UUID transferId = openTransferWithOutstanding(1);
        String pieceId = outstandingPieceId(transferId);
        transferSvc.beginReconcile(transferId, actorId);
        UUID lineId = lineIdFor(transferId);

        transferSvc.classifyShortfall(transferId, lineId,
            new TransferService.ShortfallCounts(0, 0, 1), actorId);

        assertThat(fetchStatus(pieceId)).isEqualTo("damaged");
        Map<String, Object> tpRow = jdbc.queryForMap(
            "SELECT outcome, outcome_verified FROM transfer_pieces WHERE transfer_id = ? AND piece_id = ?",
            transferId, pieceId);
        assertThat(tpRow.get("outcome")).isEqualTo("condemned");
        assertThat(tpRow.get("outcome_verified")).isEqualTo(false);
    }

    @Test
    void classifyShortfall_sold_hasNoVendorAttribution() {
        // A sale is not a vendor loss — attributed_to must be absent, or it would pollute
        // the Phase-3 vendor-loss report. lost/condemned_not_returned DO carry it (above).
        UUID transferId = openTransferWithOutstanding(1);
        String pieceId = outstandingPieceId(transferId);
        transferSvc.beginReconcile(transferId, actorId);
        UUID lineId = lineIdFor(transferId);

        transferSvc.classifyShortfall(transferId, lineId,
            new TransferService.ShortfallCounts(1, 0, 0), actorId);

        assertThat(fetchStatus(pieceId)).isEqualTo("sold");
        Map<String, Object> event = jdbc.queryForMap(
            "SELECT event_type, metadata FROM piece_events WHERE piece_id = ? ORDER BY id DESC LIMIT 1", pieceId);
        assertThat(event.get("event_type")).isEqualTo("sold_offbook");
        String metaJson = event.get("metadata").toString();
        assertThat(metaJson)
            .contains("\"verified\": false")
            .contains("\"reconciliation\": \"quantity_based\"")
            .doesNotContain("attributed_to");
    }

    @Test
    void classifyShortfall_transferNotReconciling_rejects() {
        UUID transferId = openTransferWithOutstanding(1);
        UUID lineId = lineIdFor(transferId);
        // Still 'open' — beginReconcile never called.

        assertThatThrownBy(() -> transferSvc.classifyShortfall(transferId, lineId,
                new TransferService.ShortfallCounts(1, 0, 0), actorId))
            .isInstanceOf(TransferException.class);

        Integer stillOutstanding = jdbc.queryForObject(
            "SELECT COUNT(*) FROM transfer_pieces WHERE transfer_id = ? AND outcome IS NULL",
            Integer.class, transferId);
        assertThat(stillOutstanding).as("no mutation when transfer isn't reconciling").isEqualTo(1);
    }

    @Test
    void classifyShortfall_exceedsRemainingOutstanding_rejectsWithNoMutation() {
        UUID transferId = openTransferWithOutstanding(2);
        transferSvc.beginReconcile(transferId, actorId);
        UUID lineId = lineIdFor(transferId);

        assertThatThrownBy(() -> transferSvc.classifyShortfall(transferId, lineId,
                new TransferService.ShortfallCounts(3, 0, 0), actorId))
            .isInstanceOf(TransferException.class);

        Integer stillOutstanding = jdbc.queryForObject(
            "SELECT COUNT(*) FROM transfer_pieces WHERE transfer_id = ? AND outcome IS NULL",
            Integer.class, transferId);
        assertThat(stillOutstanding).as("no mutation on a rejected shortfall request").isEqualTo(2);
    }

    @Test
    void classifyShortfall_accountsForAlreadyScannedBackPieces() {
        UUID transferId = openTransferWithOutstanding(3);
        String scannedBackPiece = outstandingPieceId(transferId);
        transferSvc.beginReconcile(transferId, actorId);
        transferSvc.reconcileScanBack(transferId, "PC-" + scannedBackPiece, "good", actorId);
        UUID lineId = lineIdFor(transferId);

        // 2 remain outstanding — requesting 3 must be rejected even though qty_out is 3.
        assertThatThrownBy(() -> transferSvc.classifyShortfall(transferId, lineId,
                new TransferService.ShortfallCounts(3, 0, 0), actorId))
            .isInstanceOf(TransferException.class);

        // Requesting exactly 2 (the true remaining outstanding count) must succeed.
        transferSvc.classifyShortfall(transferId, lineId,
            new TransferService.ShortfallCounts(2, 0, 0), actorId);

        Integer stillOutstanding = jdbc.queryForObject(
            "SELECT COUNT(*) FROM transfer_pieces WHERE transfer_id = ? AND outcome IS NULL",
            Integer.class, transferId);
        assertThat(stillOutstanding).isEqualTo(0);
    }

    // -----------------------------------------------------------------------
    // closeTransfer
    // -----------------------------------------------------------------------

    @Test
    void closeTransfer_withOutstandingPieces_rejects() {
        UUID transferId = openTransferWithOutstanding(1);
        transferSvc.beginReconcile(transferId, actorId);

        assertThatThrownBy(() -> transferSvc.closeTransfer(transferId, actorId))
            .isInstanceOf(TransferException.class);

        String status = jdbc.queryForObject("SELECT status FROM transfers WHERE id = ?", String.class, transferId);
        assertThat(status).isEqualTo("reconciling");
    }

    @Test
    void closeTransfer_dryclean_fullReturn_allGoodClosesCleanly() {
        UUID transferId = openTransferWithOutstanding(3);
        transferSvc.beginReconcile(transferId, actorId);

        for (int i = 0; i < 3; i++) {
            String pieceId = outstandingPieceId(transferId);
            transferSvc.reconcileScanBack(transferId, "PC-" + pieceId, "good", actorId);
        }

        transferSvc.closeTransfer(transferId, actorId);

        Map<String, Object> row = jdbc.queryForMap(
            "SELECT status, closed_by FROM transfers WHERE id = ?", transferId);
        assertThat(row.get("status")).isEqualTo("closed");
        assertThat(row.get("closed_by").toString()).isEqualTo(actorId.toString());

        Integer qtyReturnedGood = jdbc.queryForObject(
            "SELECT qty_returned_good FROM transfer_lines WHERE transfer_id = ? AND variant_id = ?",
            Integer.class, transferId, variantId);
        assertThat(qtyReturnedGood).isEqualTo(3);
    }

    @Test
    void closeTransfer_showroom_mixedDispositions_balancesAndCloses() {
        // Showroom scenario from the spec's test list: out 10 -> 6 good + 1 condemned
        // (scanned back) + 3 sold (classified) -> line balances, transfer closes.
        UUID transferId = openTransferWithOutstanding(10);
        transferSvc.beginReconcile(transferId, actorId);

        for (int i = 0; i < 6; i++) {
            String pieceId = outstandingPieceId(transferId);
            transferSvc.reconcileScanBack(transferId, "PC-" + pieceId, "good", actorId);
        }
        String condemnedPieceId = outstandingPieceId(transferId);
        transferSvc.reconcileScanBack(transferId, "PC-" + condemnedPieceId, "condemned", actorId);

        UUID lineId = lineIdFor(transferId);
        transferSvc.classifyShortfall(transferId, lineId,
            new TransferService.ShortfallCounts(3, 0, 0), actorId);

        transferSvc.closeTransfer(transferId, actorId);

        Map<String, Object> line = jdbc.queryForMap(
            "SELECT qty_out, qty_returned_good, qty_condemned, qty_sold, qty_lost " +
            "FROM transfer_lines WHERE id = ?", lineId);
        assertThat(line.get("qty_out")).isEqualTo(10);
        assertThat(line.get("qty_returned_good")).isEqualTo(6);
        assertThat(line.get("qty_condemned")).isEqualTo(1);
        assertThat(line.get("qty_sold")).isEqualTo(3);
        assertThat(line.get("qty_lost")).isEqualTo(0);

        String status = jdbc.queryForObject("SELECT status FROM transfers WHERE id = ?", String.class, transferId);
        assertThat(status).isEqualTo("closed");
    }

    // -----------------------------------------------------------------------
    // listOpen() — must include reconciling transfers (not status='open' only),
    // and outstanding_count must track the live balance mid-reconcile.
    // -----------------------------------------------------------------------

    @Test
    void listOpen_includesReconcilingTransfer_excludesClosedTransfer_countAccurateMidReconcile() {
        UUID openTransferId = openTransferWithOutstanding(1);

        UUID reconcilingTransferId = openTransferWithOutstanding(5);
        transferSvc.beginReconcile(reconcilingTransferId, actorId);
        // Resolve 2 of 5 mid-reconcile — outstanding_count must reflect exactly 3 remaining,
        // not the original qty_out and not zero.
        for (int i = 0; i < 2; i++) {
            String pieceId = outstandingPieceId(reconcilingTransferId);
            transferSvc.reconcileScanBack(reconcilingTransferId, "PC-" + pieceId, "good", actorId);
        }

        UUID closedTransferId = openTransferWithOutstanding(1);
        transferSvc.beginReconcile(closedTransferId, actorId);
        String onlyPieceId = outstandingPieceId(closedTransferId);
        transferSvc.reconcileScanBack(closedTransferId, "PC-" + onlyPieceId, "good", actorId);
        transferSvc.closeTransfer(closedTransferId, actorId);

        List<Map<String, Object>> open = transferSvc.listOpen();
        Map<UUID, Long> outstandingById = open.stream()
            .collect(java.util.stream.Collectors.toMap(
                r -> (UUID) r.get("id"),
                r -> ((Number) r.get("outstanding_count")).longValue()));

        assertThat(outstandingById)
            .as("open transfer present")
            .containsKey(openTransferId);
        assertThat(outstandingById)
            .as("reconciling transfer must appear in listOpen(), not just status='open'")
            .containsKey(reconcilingTransferId);
        assertThat(outstandingById.get(reconcilingTransferId))
            .as("mid-reconcile outstanding_count must reflect 5 - 2 resolved so far")
            .isEqualTo(3L);
        assertThat(outstandingById)
            .as("closed transfer must NOT appear in listOpen()")
            .doesNotContainKey(closedTransferId);
    }

    @Test
    void fullCycle_everyPieceWritesExactlyOnePieceEvent() {
        UUID transferId = openTransferWithOutstanding(3);
        List<String> pieceIds = jdbc.queryForList(
            "SELECT piece_id FROM transfer_pieces WHERE transfer_id = ?", String.class, transferId);
        // Each scanOut already wrote exactly one event (transferred_out) — confirmed here too.
        for (String pieceId : pieceIds) {
            Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM piece_events WHERE piece_id = ?", Integer.class, pieceId);
            assertThat(n).isEqualTo(1);
        }

        transferSvc.beginReconcile(transferId, actorId);
        transferSvc.reconcileScanBack(transferId, "PC-" + pieceIds.get(0), "good", actorId);
        transferSvc.reconcileScanBack(transferId, "PC-" + pieceIds.get(1), "condemned", actorId);
        UUID lineId = lineIdFor(transferId);
        transferSvc.classifyShortfall(transferId, lineId,
            new TransferService.ShortfallCounts(0, 1, 0), actorId);
        transferSvc.closeTransfer(transferId, actorId);

        // Invariant 1: every transition = exactly one transition() call = exactly one event.
        // Each piece here has exactly 2 events total: transferred_out (scanOut) + its
        // resolution (scan-back or shortfall classification). closeTransfer() itself
        // writes none — every piece was already resolved before it ran.
        for (String pieceId : pieceIds) {
            Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM piece_events WHERE piece_id = ?", Integer.class, pieceId);
            assertThat(n).as("piece %s must have exactly 2 events (out + resolution)", pieceId).isEqualTo(2);
        }
    }

    // -----------------------------------------------------------------------
    // PieceAdjustService.adjustPiece() guard — desync prevention. Confirmed by grep
    // (reported to Marawan before this fix) that adjustPiece() had no OUT_ON_TRANSFER
    // check: its only status guard was RESERVED/PACKED, and out_on_transfer:available/
    // damaged/lost are all legal ALLOWED edges, so calling adjustPiece() on a piece
    // currently out_on_transfer would silently succeed while leaving its transfer_pieces
    // row orphaned (outcome IS NULL forever) — permanently blocking closeTransfer(). These
    // tests prove the new guard closes that hole for all three reachable targets, without
    // touching the piece or the transfer_pieces row, and that an ordinary piece adjust is
    // unaffected.
    // -----------------------------------------------------------------------

    @Test
    void adjustPiece_outOnTransferPiece_toDamaged_rejectsWithoutOrphaning() {
        UUID transferId = openTransferWithOutstanding(1);
        String pieceId = outstandingPieceId(transferId);

        assertThatThrownBy(() ->
            pieceAdjustSvc.adjustPiece(pieceId, "damaged", "damaged_in_storage", null, actorId))
            .isInstanceOf(PieceOutOnTransferException.class)
            .satisfies(e -> assertThat(((PieceOutOnTransferException) e).getTransferId()).isEqualTo(transferId));

        assertThat(fetchStatus(pieceId)).as("piece must not move").isEqualTo("out_on_transfer");
        assertThat(transferPieceOutcome(transferId, pieceId))
            .as("transfer_pieces row must stay unresolved, not orphaned").isNull();

        transferSvc.beginReconcile(transferId, actorId);
        assertThatThrownBy(() -> transferSvc.closeTransfer(transferId, actorId))
            .as("closeTransfer must still correctly block on the still-outstanding piece")
            .isInstanceOf(TransferException.class)
            .satisfies(e -> assertThat(((TransferException) e).code())
                .isEqualTo(TransferException.Code.TRANSFER_HAS_OUTSTANDING_PIECES));
    }

    @Test
    void adjustPiece_outOnTransferPiece_toLost_rejectsWithoutOrphaning() {
        UUID transferId = openTransferWithOutstanding(1);
        String pieceId = outstandingPieceId(transferId);

        assertThatThrownBy(() ->
            pieceAdjustSvc.adjustPiece(pieceId, "lost", "theft_suspected", null, actorId))
            .isInstanceOf(PieceOutOnTransferException.class)
            .satisfies(e -> assertThat(((PieceOutOnTransferException) e).getTransferId()).isEqualTo(transferId));

        assertThat(fetchStatus(pieceId)).as("piece must not move").isEqualTo("out_on_transfer");
        assertThat(transferPieceOutcome(transferId, pieceId))
            .as("transfer_pieces row must stay unresolved, not orphaned").isNull();

        transferSvc.beginReconcile(transferId, actorId);
        assertThatThrownBy(() -> transferSvc.closeTransfer(transferId, actorId))
            .isInstanceOf(TransferException.class)
            .satisfies(e -> assertThat(((TransferException) e).code())
                .isEqualTo(TransferException.Code.TRANSFER_HAS_OUTSTANDING_PIECES));
    }

    @Test
    void adjustPiece_outOnTransferPiece_toAvailable_rejectsWithoutOrphaning() {
        UUID transferId = openTransferWithOutstanding(1);
        String pieceId = outstandingPieceId(transferId);

        assertThatThrownBy(() ->
            pieceAdjustSvc.adjustPiece(pieceId, "available", "cycle_count_missing", null, actorId))
            .isInstanceOf(PieceOutOnTransferException.class)
            .satisfies(e -> assertThat(((PieceOutOnTransferException) e).getTransferId()).isEqualTo(transferId));

        assertThat(fetchStatus(pieceId)).as("piece must not move").isEqualTo("out_on_transfer");
        assertThat(transferPieceOutcome(transferId, pieceId))
            .as("transfer_pieces row must stay unresolved, not orphaned").isNull();

        transferSvc.beginReconcile(transferId, actorId);
        assertThatThrownBy(() -> transferSvc.closeTransfer(transferId, actorId))
            .isInstanceOf(TransferException.class)
            .satisfies(e -> assertThat(((TransferException) e).code())
                .isEqualTo(TransferException.Code.TRANSFER_HAS_OUTSTANDING_PIECES));
    }

    @Test
    void adjustPiece_ordinaryAvailablePiece_toDamaged_stillAdjustsNormally() {
        // Positive control: the new OUT_ON_TRANSFER guard must not affect a piece that was
        // never on a transfer — proves the guard is scoped to OUT_ON_TRANSFER, not a
        // regression that blocks adjustPiece() generally.
        String pieceId = insertAvailablePiece();

        pieceAdjustSvc.adjustPiece(pieceId, "damaged", "damaged_in_storage", null, actorId);

        assertThat(fetchStatus(pieceId)).isEqualTo("damaged");
    }
}
