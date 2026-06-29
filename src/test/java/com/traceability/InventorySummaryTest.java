package com.traceability;

import com.traceability.catalog.CatalogController;
import com.traceability.identity.CustomUserDetails;
import com.traceability.integrations.bosta.BostaGateway;
import com.traceability.inventory.InventoryController;
import com.traceability.tenancy.TenantContext;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.jobrunr.scheduling.JobScheduler;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

/**
 * FR-15.1 — Inventory summary endpoint tests.
 *
 * Matrix:
 *   i1 — available count is point-in-time (pieces.status)
 *   i2 — piece delivered 40d ago is NOT in Delivered(30d) (window check)
 *   i3 — piece delivered 10d ago IS in Delivered(30d)
 *   i4 — piece now in return_pending_inspection (after delivery 10d ago) NOT in Delivered(30d)
 *   i5 — tenant isolation: tenant B's pieces not in tenant A's summary
 *   i6 — groupA available count equals catalog sum of available across all variants
 *   i7 — pieces endpoint: status filter returns only matching pieces
 *   i8 — pieces endpoint: within30d=true filters to window-only pieces
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class InventorySummaryTest {

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

    @Autowired InventoryController inventoryCtl;
    @Autowired CatalogController    catalogCtl;
    @Autowired JdbcTemplate         jdbc;
    @MockBean  BostaGateway         bostaGateway;
    @MockBean  JobScheduler         jobScheduler;

    UUID tenantId, tenant2Id, ownerId, variantId, storeId, productId;

    @BeforeAll
    void setup() {
        tenantId  = UUID.randomUUID();
        tenant2Id = UUID.randomUUID();
        ownerId   = UUID.randomUUID();
        storeId   = UUID.randomUUID();
        productId = UUID.randomUUID();
        variantId = UUID.randomUUID();

        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, 'InvT1')", tenantId);
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, 'InvT2')", tenant2Id);
        jdbc.update(
            "INSERT INTO users (id, tenant_id, name, email, password_hash, role) " +
            "VALUES (?, ?, 'Owner', 'o@inv.local', 'h', 'owner')",
            ownerId, tenantId);
        jdbc.update(
            "INSERT INTO stores (id, tenant_id, platform, shop_domain, status) " +
            "VALUES (?, ?, 'shopify', 'inv-test.myshopify.com', 'disconnected')",
            storeId, tenantId);
        jdbc.update(
            "INSERT INTO products (id, tenant_id, store_id, external_id, title, status) " +
            "VALUES (?, ?, ?, 'P-INV', 'InvProduct', 'active')",
            productId, tenantId, storeId);
        jdbc.update(
            "INSERT INTO variants (id, tenant_id, product_id, external_id, title, sku) " +
            "VALUES (?, ?, ?, 'V-INV', 'InvVariant', 'SKU-INV')",
            variantId, tenantId, productId);
    }

    @BeforeEach
    void ctx() {
        TenantContext.set(tenantId);
        var p = principal();
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(p, null, p.getAuthorities()));
    }

    @AfterEach
    void cleanupPieces() {
        TenantContext.clear();
        SecurityContextHolder.clearContext();
        // Clean pieces and events between tests; shared tenant+product+variant stay.
        jdbc.update("DELETE FROM piece_events WHERE tenant_id IN (?, ?)", tenantId, tenant2Id);
        jdbc.update("DELETE FROM pieces       WHERE tenant_id IN (?, ?)", tenantId, tenant2Id);
    }

    CustomUserDetails principal() {
        return new CustomUserDetails(ownerId, tenantId, "owner", null);
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    /** Insert a piece with the given status directly (bypassing the service). */
    private void insertPiece(String id, String status) {
        jdbc.update(
            "INSERT INTO pieces (id, tenant_id, variant_id, barcode, status) " +
            "VALUES (?, ?, ?, ?, ?::piece_status)",
            id, tenantId, variantId, "BC-" + id, status);
    }

    /** Insert a piece_events row with an explicit occurred_at offset. */
    private void insertEvent(String pieceId, String toStatus, String intervalAgo) {
        jdbc.update(
            "INSERT INTO piece_events (tenant_id, piece_id, event_type, to_status, occurred_at) " +
            "VALUES (?, ?, 'test_transition', ?::piece_status, now() - ?::interval)",
            tenantId, pieceId, toStatus, intervalAgo);
    }

    private long groupACount(InventoryController.InventorySummary s, String status) {
        return s.groupA().stream()
            .filter(c -> c.status().equals(status))
            .mapToLong(InventoryController.StatusCount::count)
            .findFirst().orElse(-1L);
    }

    private long groupBCount(InventoryController.InventorySummary s, String status) {
        return s.groupB().stream()
            .filter(c -> c.status().equals(status))
            .mapToLong(InventoryController.StatusCount::count)
            .findFirst().orElse(-1L);
    }

    // ── Tests ─────────────────────────────────────────────────────────────────

    @Test
    void i1_availableCount_pointInTime() {
        // Three available pieces, two reserved — groupA must reflect current status exactly.
        insertPiece("AVAIL-01", "available");
        insertPiece("AVAIL-02", "available");
        insertPiece("AVAIL-03", "available");
        insertPiece("RESRV-01", "reserved");
        insertPiece("RESRV-02", "reserved");

        var s = inventoryCtl.summary();

        assertThat(groupACount(s, "available")).isEqualTo(3);
        assertThat(groupACount(s, "reserved")).isEqualTo(2);
        // Group B should all be 0 (no events, no delivered/damaged/lost pieces)
        assertThat(groupBCount(s, "delivered")).isEqualTo(0);
        assertThat(groupBCount(s, "damaged")).isEqualTo(0);
        assertThat(groupBCount(s, "lost")).isEqualTo(0);
    }

    @Test
    void i2_deliveredOldPiece_notInWindow() {
        // Piece delivered 40 days ago — outside the 30-day window.
        insertPiece("DEL-OLD", "delivered");
        insertEvent("DEL-OLD", "delivered", "40 days");

        var s = inventoryCtl.summary();

        assertThat(groupBCount(s, "delivered")).isEqualTo(0);
    }

    @Test
    void i3_deliveredRecentPiece_inWindow() {
        // Piece delivered 10 days ago — inside the 30-day window.
        insertPiece("DEL-NEW", "delivered");
        insertEvent("DEL-NEW", "delivered", "10 days");

        var s = inventoryCtl.summary();

        assertThat(groupBCount(s, "delivered")).isEqualTo(1);
    }

    @Test
    void i4_returnedPiece_notCountedInDelivered() {
        // Piece whose delivery event is within 30d but current status is return_pending_inspection.
        // Must NOT appear in groupB delivered (p.status = 'delivered' predicate excludes it).
        insertPiece("DEL-RTRN", "return_pending_inspection");
        insertEvent("DEL-RTRN", "delivered", "5 days");

        var s = inventoryCtl.summary();

        assertThat(groupBCount(s, "delivered")).isEqualTo(0);
        // It should appear in groupA return_pending_inspection instead.
        assertThat(groupACount(s, "return_pending_inspection")).isEqualTo(1);
    }

    @Test
    void i5_tenantIsolation_otherTenantPiecesNotIncluded() {
        // Insert pieces under tenant2 — they must not appear in tenant1's summary.
        UUID v2 = UUID.randomUUID();
        UUID prod2 = UUID.randomUUID();
        UUID store2 = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO stores (id, tenant_id, platform, shop_domain, status) " +
            "VALUES (?, ?, 'shopify', 'inv-t2.myshopify.com', 'disconnected')",
            store2, tenant2Id);
        jdbc.update(
            "INSERT INTO products (id, tenant_id, store_id, external_id, title, status) " +
            "VALUES (?, ?, ?, 'P-T2', 'T2Product', 'active')",
            prod2, tenant2Id, store2);
        jdbc.update(
            "INSERT INTO variants (id, tenant_id, product_id, external_id, title, sku) " +
            "VALUES (?, ?, ?, 'V-T2', 'T2Variant', 'SKU-T2')",
            v2, tenant2Id, prod2);
        // Insert 5 available pieces for tenant2
        for (int i = 1; i <= 5; i++) {
            jdbc.update(
                "INSERT INTO pieces (id, tenant_id, variant_id, barcode, status) " +
                "VALUES (?, ?, ?, ?, 'available'::piece_status)",
                "T2-PC-" + i, tenant2Id, v2, "BC-T2-" + i);
        }

        // Insert 1 available piece for tenant1
        insertPiece("T1-PC-1", "available");

        // Tenant context is set to tenantId (tenant1) in @BeforeEach
        var s = inventoryCtl.summary();

        assertThat(groupACount(s, "available")).isEqualTo(1);
    }

    @Test
    void i6_groupA_matchesCatalogSumForAvailable() {
        // groupA available in summary must equal the sum of available across all
        // variants returned by the catalog — both query pieces.status directly.
        insertPiece("CAT-01", "available");
        insertPiece("CAT-02", "available");
        insertPiece("CAT-03", "available");
        insertPiece("CAT-RES", "reserved");

        long summaryAvailable = groupACount(inventoryCtl.summary(), "available");

        long catalogAvailable = catalogCtl.list().products().stream()
            .flatMap(p -> p.variants().stream())
            .mapToLong(v -> v.pieceCounts().getOrDefault("available", 0L))
            .sum();

        assertThat(summaryAvailable).isEqualTo(3);
        assertThat(catalogAvailable).isEqualTo(summaryAvailable);
    }

    @Test
    void i7_piecesEndpoint_statusFilter() {
        insertPiece("PL-AVAIL-1", "available");
        insertPiece("PL-AVAIL-2", "available");
        insertPiece("PL-RESRV-1", "reserved");

        var page = inventoryCtl.pieces("available", false, 0, 20);

        assertThat(page.total()).isEqualTo(2);
        assertThat(page.items()).hasSize(2);
        assertThat(page.items()).allMatch(r -> "available".equals(r.status()));
    }

    @Test
    void i8_piecesEndpoint_within30dFilter() {
        // Piece delivered 10d ago — included in within30d query.
        insertPiece("PL-DEL-NEW", "delivered");
        insertEvent("PL-DEL-NEW", "delivered", "10 days");

        // Piece delivered 40d ago — excluded from within30d query.
        insertPiece("PL-DEL-OLD", "delivered");
        insertEvent("PL-DEL-OLD", "delivered", "40 days");

        var without = inventoryCtl.pieces("delivered", false, 0, 20);
        var with30d = inventoryCtl.pieces("delivered", true,  0, 20);

        assertThat(without.total()).isEqualTo(2);  // both delivered pieces
        assertThat(with30d.total()).isEqualTo(1);  // only the 10d-ago one
        assertThat(with30d.items().get(0).id()).isEqualTo("PL-DEL-NEW");
    }

    @Test
    void i9_lostThenFoundPiece_countsInAvailableNotInLost_andDamagedWindowPositive() {
        // ── Case (c): Lost 10d ago → Available 2d ago ("found it") ─────────────
        // Current status = available.  The recent to_status='lost' event must NOT
        // make it appear in Lost-30d.  The outer p.status IN ('delivered','damaged','lost')
        // predicate excludes it before EXISTS is ever evaluated.
        insertPiece("FOUND-01", "available");
        insertEvent("FOUND-01", "lost",      "10 days");  // the lost transition
        insertEvent("FOUND-01", "available", "2 days");   // the "found it" transition

        // A piece that is currently lost with a recent event — ensures the lost-30d
        // bucket is plumbed and the exclusion above is not a side-effect of lost=0.
        insertPiece("LOST-LIVE", "lost");
        insertEvent("LOST-LIVE", "lost", "5 days");

        // ── Case (d): Damaged 10d ago, still damaged ─────────────────────────────
        insertPiece("DMG-01", "damaged");
        insertEvent("DMG-01", "damaged", "10 days");

        var s = inventoryCtl.summary();

        // Case (c): FOUND-01 in groupA available, NOT in groupB lost.
        assertThat(groupACount(s, "available")).isEqualTo(1);
        assertThat(groupBCount(s, "lost")).isEqualTo(1);   // only LOST-LIVE

        // Case (d): DMG-01 in groupB damaged.
        assertThat(groupBCount(s, "damaged")).isEqualTo(1);
    }
}
