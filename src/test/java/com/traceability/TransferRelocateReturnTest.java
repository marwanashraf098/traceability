package com.traceability;

import com.traceability.catalog.CatalogController;
import com.traceability.identity.CustomUserDetails;
import com.traceability.inventory.*;
import com.traceability.tenancy.TenantContext;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.lang.reflect.Field;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * FR-22.11 — Relocate Return (B2): TransferService.returnScanOut() and the InventoryLedger
 * edge it opens ("transferred_out:out_on_transfer") — the one legal exit from the terminal
 * B1 (FR-22.10) left un-returnable on purpose.
 *
 * Half A (relocate_returnFullLoop...) is the door-works proof: RED before this change (the
 * edge didn't exist, so returnScanOut() would throw IllegalTransitionException — verified by
 * temporarily reverting the InventoryLedger.ALLOWED addition and re-running this test during
 * the B2 build, same revert-to-confirm cycle as B1's own tests; not committed as a separate
 * red variant, this IS the green assertion the revert cycle checks against), GREEN after.
 *
 * Half B (allowedEdges_.../pieceAdjustService_.../wrongOrigin...) is the door-is-single-purpose
 * proof — the B2 analog of B1's un-pickable/returnable pair: opening the edge must not make
 * 'transferred_out' reachable/exitable from anywhere but returnScanOut().
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TransferRelocateReturnTest {

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
    @Autowired FulfillService      fulfillSvc;
    @Autowired PieceAdjustService  pieceAdjustSvc;
    @Autowired CatalogController   catalogCtl;
    @Autowired JdbcTemplate        jdbc;

    UUID tenantId;
    UUID ownerId;
    UUID storeId;
    UUID variantId;
    UUID fulfillmentLocationId;
    UUID locationB1;
    UUID locationB2;

    @BeforeAll
    void setup() {
        tenantId = UUID.randomUUID();
        ownerId  = UUID.randomUUID();
        storeId  = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        variantId = UUID.randomUUID();
        fulfillmentLocationId = UUID.randomUUID();
        locationB1 = UUID.randomUUID();
        locationB2 = UUID.randomUUID();

        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, 'Relocate Return Test Co')", tenantId);
        jdbc.update(
            "INSERT INTO users (id, tenant_id, name, email, password_hash, role) " +
            "VALUES (?, ?, 'Test Owner', 'owner@relocate-return-test.com', 'h', 'owner')",
            ownerId, tenantId);
        jdbc.update(
            "INSERT INTO stores (id, tenant_id, platform, shop_domain, status) " +
            "VALUES (?, ?, 'shopify', 'relocate-return-test.myshopify.com', 'disconnected')",
            storeId, tenantId);
        jdbc.update(
            "INSERT INTO products (id, tenant_id, store_id, external_id, title, status) " +
            "VALUES (?, ?, ?, 'PROD-RR', 'Relocate Return Widget', 'active')",
            productId, tenantId, storeId);
        jdbc.update(
            "INSERT INTO variants (id, tenant_id, product_id, external_id, title) " +
            "VALUES (?, ?, ?, 'VAR-RR', 'Relocate Return Widget Variant')",
            variantId, tenantId, productId);
        jdbc.update(
            "INSERT INTO locations (id, tenant_id, name, type, is_default, is_fulfillment) " +
            "VALUES (?, ?, 'Main Warehouse', 'warehouse', true, true)",
            fulfillmentLocationId, tenantId);
        jdbc.update(
            "INSERT INTO locations (id, tenant_id, name, type, is_default, is_fulfillment) " +
            "VALUES (?, ?, 'Warehouse B1', 'warehouse', false, false)",
            locationB1, tenantId);
        jdbc.update(
            "INSERT INTO locations (id, tenant_id, name, type, is_default, is_fulfillment) " +
            "VALUES (?, ?, 'Warehouse B2', 'warehouse', false, false)",
            locationB2, tenantId);
    }

    @BeforeEach
    void setTenantContext() {
        TenantContext.set(tenantId);
        CustomUserDetails principal = new CustomUserDetails(ownerId, tenantId, "owner", null);
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }

    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
        SecurityContextHolder.clearContext();
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private String insertAvailablePiece() {
        String id = UlidGenerator.generate();
        jdbc.update(
            "INSERT INTO pieces (id, tenant_id, variant_id, barcode, short_code, status) " +
            "VALUES (?, ?, ?, ?, 'P' || LPAD((abs(hashtext(?)) % 999999 + 1)::text, 6, '0'), 'available'::piece_status)",
            id, tenantId, variantId, "PC-" + id, id);
        return id;
    }

    /** Relocates a fresh available piece one-way to the given destination, landing it
     *  terminal 'transferred_out' there — the fixture B2's tests build on. */
    private String relocateToTerminal(UUID destinationLocationId) {
        UUID relocateTransferId = transferSvc.createTransfer(
            "other", destinationLocationId, null, "B2 fixture", ownerId, "relocate_out");
        String pieceId = insertAvailablePiece();
        TransferService.ScanOutResult scanOut = transferSvc.scanOut(relocateTransferId, "PC-" + pieceId, ownerId);
        assertThat(scanOut.success()).as("fixture relocate scanOut must succeed").isTrue();
        transferSvc.closeOneWay(relocateTransferId, ownerId);
        assertThat(fetchStatus(pieceId)).isEqualTo("transferred_out");
        return pieceId;
    }

    private String fetchStatus(String pieceId) {
        return jdbc.queryForObject("SELECT status FROM pieces WHERE id = ?", String.class, pieceId);
    }

    private UUID fetchCurrentLocation(String pieceId) {
        return jdbc.queryForObject("SELECT current_location_id FROM pieces WHERE id = ?", UUID.class, pieceId);
    }

    private long onHandForVariant() {
        return catalogCtl.list().products().stream()
            .flatMap(p -> p.variants().stream())
            .filter(v -> variantId.toString().equals(v.id()))
            .findFirst()
            .orElseThrow()
            .available();
    }

    // -----------------------------------------------------------------------
    // Half A — THE DOOR WORKS: full A->B->A loop, piece lands available at A,
    // and A's on-hand query reflects it.
    // -----------------------------------------------------------------------

    @Test
    void relocate_returnFullLoop_landsPieceAvailableAtFulfillment_andOnHandReflectsIt() {
        String pieceId = relocateToTerminal(locationB1);

        long onHandBefore = onHandForVariant();

        UUID returnTransferId = transferSvc.createTransfer(
            "other", fulfillmentLocationId, null, "B2 return", ownerId, "relocate_return", locationB1);

        TransferService.ScanOutResult returnScanOut =
            transferSvc.returnScanOut(returnTransferId, "PC-" + pieceId, ownerId);
        assertThat(returnScanOut.success()).as("returnScanOut must accept a transferred_out piece at its own source").isTrue();
        assertThat(fetchStatus(pieceId)).isEqualTo("out_on_transfer");

        transferSvc.beginReconcile(returnTransferId, ownerId);
        TransferService.ScanBackResult scanBack =
            transferSvc.reconcileScanBack(returnTransferId, "PC-" + pieceId, "good", ownerId);
        assertThat(scanBack.success()).isTrue();
        assertThat(scanBack.outcome()).isEqualTo("returned_good");

        transferSvc.closeTransfer(returnTransferId, ownerId);

        assertThat(fetchStatus(pieceId)).isEqualTo("available");
        assertThat(fetchCurrentLocation(pieceId))
            .as("a returned piece must land at the FULFILLMENT warehouse, not the transfer's own destination field value coincidentally")
            .isEqualTo(fulfillmentLocationId);

        long onHandAfter = onHandForVariant();
        assertThat(onHandAfter - onHandBefore)
            .as("A's on-hand query must reflect the returned piece")
            .isEqualTo(1);
    }

    // -----------------------------------------------------------------------
    // Half B — THE DOOR IS SINGLE-PURPOSE
    // -----------------------------------------------------------------------

    @Test
    @SuppressWarnings("unchecked")
    void allowedEdges_transferredOut_hasExactlyOneEdgeIn_andExactlyOneEdgeOut() throws Exception {
        Field f = InventoryLedger.class.getDeclaredField("ALLOWED");
        f.setAccessible(true);
        Set<String> allowed = (Set<String>) f.get(null);

        long edgesIn  = allowed.stream().filter(e -> e.endsWith(":transferred_out")).count();
        long edgesOut = allowed.stream().filter(e -> e.startsWith("transferred_out:")).count();

        assertThat(edgesIn).as("exactly one edge into transferred_out (out_on_transfer:transferred_out)").isEqualTo(1);
        assertThat(edgesOut).as("exactly one edge out of transferred_out (transferred_out:out_on_transfer)").isEqualTo(1);
        assertThat(allowed).contains("out_on_transfer:transferred_out", "transferred_out:out_on_transfer");
    }

    @Test
    void pieceAdjustService_stillRejectsTransferredOutPiece_unchangedByB2() {
        String pieceId = relocateToTerminal(locationB1);

        // adjustPiece(): 'damaged' is a normally-legal target for an available/lost piece,
        // but for a transferred_out piece it must still fail at the state-machine check —
        // ALLOWED has no "transferred_out:damaged" edge, B2 added none.
        assertThatThrownBy(() -> pieceAdjustSvc.adjustPiece(pieceId, "damaged", "other", "note", ownerId))
            .isInstanceOf(IllegalTransitionException.class);
        assertThat(fetchStatus(pieceId)).as("piece must not move").isEqualTo("transferred_out");

        // voidPiece()/hold() both hard-require current==AVAILABLE — untouched by B2.
        assertThatThrownBy(() -> pieceAdjustSvc.voidPiece(pieceId, "other", "note", ownerId))
            .isInstanceOf(ResponseStatusException.class);
        assertThat(fetchStatus(pieceId)).as("piece must not move").isEqualTo("transferred_out");

        assertThatThrownBy(() -> pieceAdjustSvc.hold(pieceId, "other", "note", ownerId))
            .isInstanceOf(ResponseStatusException.class);
        assertThat(fetchStatus(pieceId)).as("piece must not move").isEqualTo("transferred_out");
    }

    @Test
    void returnScanOut_rejectsPieceAtAWrongOrigin_wrongOriginControl() {
        // Piece is relocated to (and terminal at) B2 — but the return transfer below is
        // declared against B1. Proves origin scoping actually discriminates between two
        // non-fulfillment locations, not just "any B" per the B2 diagnosis's option (c) risk.
        String pieceId = relocateToTerminal(locationB2);

        UUID returnTransferId = transferSvc.createTransfer(
            "other", fulfillmentLocationId, null, "wrong-origin control", ownerId, "relocate_return", locationB1);

        TransferService.ScanOutResult result =
            transferSvc.returnScanOut(returnTransferId, "PC-" + pieceId, ownerId);

        assertThat(result.success()).isFalse();
        assertThat(result.code()).isEqualTo("WRONG_ORIGIN");
        assertThat(fetchStatus(pieceId)).as("piece must not move").isEqualTo("transferred_out");
    }

    // -----------------------------------------------------------------------
    // Positive control — round_trip / relocate_out createTransfer() unaffected
    // -----------------------------------------------------------------------

    @Test
    void createTransfer_relocateOut_stillRejectsFulfillmentDestination_guardUnweakened() {
        assertThatThrownBy(() -> transferSvc.createTransfer(
                "other", fulfillmentLocationId, null, "guard control", ownerId, "relocate_out"))
            .isInstanceOf(TransferException.class)
            .satisfies(e -> assertThat(((TransferException) e).code())
                .isEqualTo(TransferException.Code.TRANSFER_DESTINATION_IS_FULFILLMENT));
    }

    @Test
    void createTransfer_relocateReturn_requiresSourceLocation() {
        assertThatThrownBy(() -> transferSvc.createTransfer(
                "other", fulfillmentLocationId, null, "missing source", ownerId, "relocate_return", null))
            .isInstanceOf(TransferException.class)
            .satisfies(e -> assertThat(((TransferException) e).code())
                .isEqualTo(TransferException.Code.TRANSFER_SOURCE_REQUIRED));
    }

    @Test
    void createTransfer_relocateReturn_requiresFulfillmentDestination() {
        assertThatThrownBy(() -> transferSvc.createTransfer(
                "other", locationB2, null, "wrong destination", ownerId, "relocate_return", locationB1))
            .isInstanceOf(TransferException.class)
            .satisfies(e -> assertThat(((TransferException) e).code())
                .isEqualTo(TransferException.Code.TRANSFER_RETURN_DESTINATION_NOT_FULFILLMENT));
    }
}
