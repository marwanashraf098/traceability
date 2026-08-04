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

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * FR-22.3 — TransferService.createTransfer() + scanOut() (the send-out vertical slice).
 *
 * scanOut() mirrors FulfillService.scan() exactly: piece lookup → WRONG_STATUS check →
 * InventoryLedger.transition() as the sole atomic race guard → transfer_pieces claim row
 * written only after a successful transition (mirrors FulfillService inserting into
 * allocations only after transition() succeeds).
 *
 * No TransferController yet (FR-22.6) — this test calls TransferService directly.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TransferServiceTest {

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

    @Autowired TransferService transferSvc;
    @Autowired InventoryLedger ledger;
    @Autowired JdbcTemplate    jdbc;

    UUID tenantId;
    UUID actorId;
    UUID variantId;
    UUID destinationLocationId;
    UUID fulfillmentLocationId;
    UUID otherTenantLocationId;

    @BeforeAll
    void setup() {
        tenantId = UUID.randomUUID();
        actorId  = UUID.randomUUID();
        UUID storeId   = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        variantId = UUID.randomUUID();
        destinationLocationId = UUID.randomUUID();
        fulfillmentLocationId = UUID.randomUUID();

        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, 'Transfer Test Co')", tenantId);
        jdbc.update(
            "INSERT INTO users (id, tenant_id, name, email, password_hash, role) " +
            "VALUES (?, ?, 'Test Actor', 'actor@transfer-test.com', 'h', 'owner')",
            actorId, tenantId);
        jdbc.update(
            "INSERT INTO stores (id, tenant_id, platform, shop_domain, status) " +
            "VALUES (?, ?, 'shopify', 'transfer-test.myshopify.com', 'disconnected')",
            storeId, tenantId);
        jdbc.update(
            "INSERT INTO products (id, tenant_id, store_id, external_id, title, status) " +
            "VALUES (?, ?, ?, 'PROD-TR', 'Transfer Widget', 'active')",
            productId, tenantId, storeId);
        jdbc.update(
            "INSERT INTO variants (id, tenant_id, product_id, external_id, title) " +
            "VALUES (?, ?, ?, 'VAR-TR', 'Transfer Widget Variant')",
            variantId, tenantId, productId);
        // External destination: is_fulfillment=false, existing 'showroom' location_type.
        jdbc.update(
            "INSERT INTO locations (id, tenant_id, name, type, is_default, is_fulfillment) " +
            "VALUES (?, ?, 'Test Showroom', 'showroom', false, false)",
            destinationLocationId, tenantId);
        // This tenant's own fulfillment warehouse — must be rejected as a transfer destination.
        jdbc.update(
            "INSERT INTO locations (id, tenant_id, name, type, is_default, is_fulfillment) " +
            "VALUES (?, ?, 'Main Warehouse', 'warehouse', true, true)",
            fulfillmentLocationId, tenantId);

        // A second, unrelated tenant + its own location — used to prove createTransfer()
        // cannot be pointed at another tenant's location id (the FK alone would allow it;
        // RLS does not apply to FK satisfaction checks).
        UUID otherTenantId = UUID.randomUUID();
        otherTenantLocationId = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, 'Other Tenant Co')", otherTenantId);
        jdbc.update(
            "INSERT INTO locations (id, tenant_id, name, type, is_default, is_fulfillment) " +
            "VALUES (?, ?, 'Other Tenant Showroom', 'showroom', false, false)",
            otherTenantLocationId, otherTenantId);
    }

    @BeforeEach
    void setTenantContext() { TenantContext.set(tenantId); }

    @AfterEach
    void clearTenantContext() { TenantContext.clear(); }

    // ── helpers ──────────────────────────────────────────────────────────────

    private String insertPiece(PieceStatus status) {
        String id = UlidGenerator.generate();
        jdbc.update(
            "INSERT INTO pieces (id, tenant_id, variant_id, barcode, short_code, status) " +
            "VALUES (?, ?, ?, ?, 'P' || LPAD((abs(hashtext(?)) % 999999 + 1)::text, 6, '0'), ?::piece_status)",
            id, tenantId, variantId, "PC-" + id, id, status.db);
        return id;
    }

    private UUID insertTransfer(String status) {
        UUID id = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO transfers (id, tenant_id, transfer_type, destination_location_id, status, created_by) " +
            "VALUES (?, ?, 'showroom', ?, ?, ?)",
            id, tenantId, destinationLocationId, status, actorId);
        return id;
    }

    private int countEvents(String pieceId) {
        Integer n = jdbc.queryForObject(
            "SELECT COUNT(*) FROM piece_events WHERE piece_id = ?", Integer.class, pieceId);
        return n == null ? 0 : n;
    }

    private String fetchStatus(String pieceId) {
        return jdbc.queryForObject("SELECT status FROM pieces WHERE id = ?", String.class, pieceId);
    }

    // -----------------------------------------------------------------------
    // createTransfer
    // -----------------------------------------------------------------------

    @Test
    void createTransfer_opensTransferWithGivenFields() {
        Instant expectedReturn = Instant.now().plusSeconds(7 * 24 * 3600);

        UUID transferId = transferSvc.createTransfer(
            "showroom", destinationLocationId, expectedReturn, "Downtown consignment", actorId);

        Map<String, Object> row = jdbc.queryForMap(
            "SELECT transfer_type, destination_location_id, status, note, created_by " +
            "FROM transfers WHERE id = ?", transferId);
        assertThat(row.get("transfer_type")).isEqualTo("showroom");
        assertThat(row.get("destination_location_id").toString()).isEqualTo(destinationLocationId.toString());
        assertThat(row.get("status")).isEqualTo("open");
        assertThat(row.get("note")).isEqualTo("Downtown consignment");
        assertThat(row.get("created_by").toString()).isEqualTo(actorId.toString());
    }

    @Test
    void createTransfer_invalidTransferType_rejectsWithNoRowWritten() {
        assertThatThrownBy(() -> transferSvc.createTransfer(
                "junk", destinationLocationId, null, null, actorId))
            .isInstanceOf(TransferException.class)
            .satisfies(e -> assertThat(((TransferException) e).code())
                .isEqualTo(TransferException.Code.TRANSFER_TYPE_INVALID));

        Long count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM transfers WHERE created_by = ? AND transfer_type = 'junk'",
            Long.class, actorId);
        assertThat(count).isEqualTo(0L);
    }

    @Test
    void createTransfer_crossTenantLocation_rejectsWithNoRowWritten() {
        // The FK alone (destination_location_id REFERENCES locations(id)) would let this
        // through — Postgres row security does not apply to FK satisfaction checks. Only
        // the app-level tenant-scoped SELECT in createTransfer() catches this.
        assertThatThrownBy(() -> transferSvc.createTransfer(
                "showroom", otherTenantLocationId, null, null, actorId))
            .isInstanceOf(TransferException.class)
            .satisfies(e -> assertThat(((TransferException) e).code())
                .isEqualTo(TransferException.Code.TRANSFER_DESTINATION_NOT_FOUND));

        Long count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM transfers WHERE destination_location_id = ?",
            Long.class, otherTenantLocationId);
        assertThat(count).isEqualTo(0L);
    }

    @Test
    void createTransfer_ownFulfillmentLocation_rejectsWithNoRowWritten() {
        assertThatThrownBy(() -> transferSvc.createTransfer(
                "showroom", fulfillmentLocationId, null, null, actorId))
            .isInstanceOf(TransferException.class)
            .satisfies(e -> assertThat(((TransferException) e).code())
                .isEqualTo(TransferException.Code.TRANSFER_DESTINATION_IS_FULFILLMENT));

        Long count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM transfers WHERE destination_location_id = ?",
            Long.class, fulfillmentLocationId);
        assertThat(count).isEqualTo(0L);
    }

    // -----------------------------------------------------------------------
    // scanOut — happy path
    // -----------------------------------------------------------------------

    @Test
    void scanOut_success_transitionsPieceAndUpdatesLocationAndLine() {
        UUID transferId = insertTransfer("open");
        String pieceId = insertPiece(PieceStatus.AVAILABLE);
        String barcode = "PC-" + pieceId;

        TransferService.ScanOutResult result = transferSvc.scanOut(transferId, barcode, actorId);

        assertThat(result.success()).isTrue();
        assertThat(result.code()).isEqualTo("SCANNED");
        assertThat(result.pieceId()).isEqualTo(pieceId);
        assertThat(result.variantId()).isEqualTo(variantId);
        assertThat(result.qtyOut()).isEqualTo(1);

        assertThat(fetchStatus(pieceId)).isEqualTo("out_on_transfer");
        assertThat(countEvents(pieceId)).isEqualTo(1);

        Map<String, Object> pieceRow = jdbc.queryForMap(
            "SELECT current_location_id FROM pieces WHERE id = ?", pieceId);
        assertThat(pieceRow.get("current_location_id").toString()).isEqualTo(destinationLocationId.toString());

        Map<String, Object> lineRow = jdbc.queryForMap(
            "SELECT qty_out FROM transfer_lines WHERE transfer_id = ? AND variant_id = ?",
            transferId, variantId);
        assertThat(((Number) lineRow.get("qty_out")).intValue()).isEqualTo(1);

        Map<String, Object> tpRow = jdbc.queryForMap(
            "SELECT outcome FROM transfer_pieces WHERE transfer_id = ? AND piece_id = ?",
            transferId, pieceId);
        assertThat(tpRow.get("outcome")).as("outstanding piece has null outcome").isNull();

        Map<String, Object> event = jdbc.queryForMap(
            "SELECT event_type, metadata FROM piece_events WHERE piece_id = ? ORDER BY id DESC LIMIT 1", pieceId);
        assertThat(event.get("event_type")).isEqualTo("transferred_out");
        String metaJson = event.get("metadata").toString();
        assertThat(metaJson)
            .as("carries transfer_id + reason (the transfer's category)")
            .contains("\"transfer_id\"")
            .contains("\"reason\": \"showroom\"");
    }

    @Test
    void scanOut_secondPieceSameVariant_incrementsSameLine() {
        UUID transferId = insertTransfer("open");
        String piece1 = insertPiece(PieceStatus.AVAILABLE);
        String piece2 = insertPiece(PieceStatus.AVAILABLE);

        transferSvc.scanOut(transferId, "PC-" + piece1, actorId);
        TransferService.ScanOutResult result2 = transferSvc.scanOut(transferId, "PC-" + piece2, actorId);

        assertThat(result2.qtyOut()).isEqualTo(2);
        Long lineCount = jdbc.queryForObject(
            "SELECT COUNT(*) FROM transfer_lines WHERE transfer_id = ? AND variant_id = ?",
            Long.class, transferId, variantId);
        assertThat(lineCount).as("one line row shared by both pieces").isEqualTo(1L);
    }

    // -----------------------------------------------------------------------
    // scanOut — rejections
    // -----------------------------------------------------------------------

    @Test
    void scanOut_pieceNotFound_rejectsAndWritesNoEvent() {
        UUID transferId = insertTransfer("open");

        TransferService.ScanOutResult result = transferSvc.scanOut(transferId, "PC-DOESNOTEXIST", actorId);

        assertThat(result.success()).isFalse();
        assertThat(result.code()).isEqualTo("PIECE_NOT_FOUND");
        assertThat(result.messageEn()).isNotBlank();
        assertThat(result.messageAr()).as("scan codes must be bilingual, not English-only").isNotBlank();
    }

    @Test
    void scanOut_wrongStatus_rejectsAndDoesNotTransition() {
        UUID transferId = insertTransfer("open");
        String pieceId = insertPiece(PieceStatus.RESERVED);

        TransferService.ScanOutResult result = transferSvc.scanOut(transferId, "PC-" + pieceId, actorId);

        assertThat(result.success()).isFalse();
        assertThat(result.code()).isEqualTo("WRONG_STATUS");
        assertThat(fetchStatus(pieceId)).isEqualTo("reserved");
        assertThat(countEvents(pieceId)).isEqualTo(0);
    }

    @Test
    void scanOut_transferNotFound_rejects() {
        String pieceId = insertPiece(PieceStatus.AVAILABLE);

        TransferService.ScanOutResult result =
            transferSvc.scanOut(UUID.randomUUID(), "PC-" + pieceId, actorId);

        assertThat(result.success()).isFalse();
        assertThat(result.code()).isEqualTo("TRANSFER_NOT_FOUND");
        assertThat(fetchStatus(pieceId)).isEqualTo("available");
    }

    @Test
    void scanOut_transferNotOpen_rejects() {
        UUID transferId = insertTransfer("closed");
        String pieceId = insertPiece(PieceStatus.AVAILABLE);

        TransferService.ScanOutResult result = transferSvc.scanOut(transferId, "PC-" + pieceId, actorId);

        assertThat(result.success()).isFalse();
        assertThat(result.code()).isEqualTo("TRANSFER_NOT_OPEN");
        assertThat(fetchStatus(pieceId)).isEqualTo("available");
    }

    // -----------------------------------------------------------------------
    // Send-out race: two threads scan the same piece → exactly one wins
    // -----------------------------------------------------------------------

    @Test
    void sendOutRace_samePiece_exactlyOneWinsExactlyOneEvent() throws InterruptedException {
        UUID transferId = insertTransfer("open");
        String pieceId = insertPiece(PieceStatus.AVAILABLE);
        String barcode = "PC-" + pieceId;

        CountDownLatch ready      = new CountDownLatch(2);
        CountDownLatch go         = new CountDownLatch(1);
        AtomicInteger  successes  = new AtomicInteger();
        AtomicInteger  rejections = new AtomicInteger();
        // Two REAL, independent JDBC connections/transactions — each raw Thread borrows its
        // own connection from the pool on first use; TenantContext is a ThreadLocal so each
        // thread sets it independently. Captured explicitly (not just inferred from the
        // success/rejection counters) so a stray UnexpectedRollbackException — or any other
        // exception escaping scanOut() uncaught in a raw Thread, which JUnit would otherwise
        // never see — fails this test loudly instead of silently zeroing out a counter.
        AtomicReference<Throwable> t1Failure = new AtomicReference<>();
        AtomicReference<Throwable> t2Failure = new AtomicReference<>();

        Consumer<AtomicReference<Throwable>> attempt = (failureSlot) -> {
            TenantContext.set(tenantId);
            try {
                ready.countDown();
                go.await();
                TransferService.ScanOutResult result = transferSvc.scanOut(transferId, barcode, actorId);
                if (result.success()) successes.incrementAndGet();
                else rejections.incrementAndGet();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Throwable t) {
                failureSlot.set(t);
            } finally {
                TenantContext.clear();
            }
        };

        Thread t1 = new Thread(() -> attempt.accept(t1Failure));
        Thread t2 = new Thread(() -> attempt.accept(t2Failure));
        t1.start(); t2.start();
        ready.await();
        go.countDown();
        t1.join(); t2.join();

        assertThat(t1Failure.get()).as("thread 1 must not throw (e.g. UnexpectedRollbackException)").isNull();
        assertThat(t2Failure.get()).as("thread 2 must not throw (e.g. UnexpectedRollbackException)").isNull();

        assertThat(successes.get()).as("exactly one scanOut wins").isEqualTo(1);
        assertThat(rejections.get()).as("exactly one scanOut rejected").isEqualTo(1);
        assertThat(countEvents(pieceId)).as("exactly one piece_event written").isEqualTo(1);

        Long transferPieceCount = jdbc.queryForObject(
            "SELECT COUNT(*) FROM transfer_pieces WHERE piece_id = ?", Long.class, pieceId);
        assertThat(transferPieceCount).as("exactly one transfer_pieces row").isEqualTo(1L);

        assertThat(fetchStatus(pieceId)).isEqualTo("out_on_transfer");
    }
}
