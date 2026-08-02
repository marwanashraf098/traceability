package com.traceability;

import com.traceability.inventory.*;
import com.traceability.tenancy.TenantContext;
import org.jobrunr.scheduling.JobScheduler;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

/**
 * FR-21 Step 3: blind, idempotent stock-take scan.
 *
 * ss1 — in snapshot, condition agrees -> match
 * ss2 — in snapshot, condition disagrees -> condition_mismatch
 * ss3 — idempotent: same piece scanned x3 -> exactly 1 stock_take_scans row
 * ss4 — unknown barcode -> logged with raw_barcode/piece_id NULL, classification=unknown,
 *       NOT an error
 * ss5 — cross-tenant barcode -> resolves to nothing under RLS, behaves identically to an
 *       unknown barcode (never leaks that the piece exists under another tenant)
 * ss6 — not in snapshot, live status=lost -> unexpected_resurfaced
 * ss7 — not in snapshot, live status not lost -> out_of_scope
 * ss8 — scan against a non-open session -> 409
 * ss9 — invalid condition value -> 400
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StockTakeScanTest {

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

    @Autowired StockTakeService   stockTake;
    @Autowired InventoryLedger    ledger;
    @Autowired JdbcTemplate       jdbc;
    @MockBean  JobScheduler       jobScheduler;

    UUID tenantId, actorId, storeId, productId, variantA;
    UUID fulfillmentLocationId, otherLocationId;
    UUID tenantB, actorB, storeB, productB, variantB, locationB;

    @BeforeAll
    void setupFixture() {
        tenantId   = UUID.randomUUID();
        actorId    = UUID.randomUUID();
        storeId    = UUID.randomUUID();
        productId  = UUID.randomUUID();
        variantA   = UUID.randomUUID();
        fulfillmentLocationId = UUID.randomUUID();
        otherLocationId       = UUID.randomUUID();

        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, 'StockTakeScanTenant')", tenantId);
        jdbc.update(
            "INSERT INTO users (id, tenant_id, name, email, password_hash, role) " +
            "VALUES (?, ?, 'Actor', 'sts@test.com', 'x', 'owner'::user_role)",
            actorId, tenantId);
        jdbc.update(
            "INSERT INTO locations (id, tenant_id, name, is_fulfillment) VALUES (?, ?, 'Main WH', true)",
            fulfillmentLocationId, tenantId);
        jdbc.update(
            "INSERT INTO locations (id, tenant_id, name, is_fulfillment) VALUES (?, ?, 'Other WH', false)",
            otherLocationId, tenantId);
        jdbc.update(
            "INSERT INTO stores (id, tenant_id, platform, shop_domain, status, import_status, " +
            "    access_token_encrypted, access_token_expires_at) " +
            "VALUES (?, ?, 'shopify', 'sts.myshopify.com', 'connected', 'completed', 'enc', " +
            "    now() + interval '876000 hours')",
            storeId, tenantId);
        jdbc.update(
            "INSERT INTO products (id, tenant_id, store_id, external_id, title) " +
            "VALUES (?, ?, ?, 'gid://shopify/Product/STS', 'Sts Product')",
            productId, tenantId, storeId);
        jdbc.update(
            "INSERT INTO variants (id, tenant_id, product_id, external_id, title, sku) " +
            "VALUES (?, ?, ?, 'gid://shopify/Variant/STSA', 'Variant A', 'STS-A')",
            variantA, tenantId, productId);

        // Second tenant, for the cross-tenant no-leak test.
        tenantB   = UUID.randomUUID();
        actorB    = UUID.randomUUID();
        storeB    = UUID.randomUUID();
        productB  = UUID.randomUUID();
        variantB  = UUID.randomUUID();
        locationB = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, 'StockTakeScanTenantB')", tenantB);
        jdbc.update(
            "INSERT INTO users (id, tenant_id, name, email, password_hash, role) " +
            "VALUES (?, ?, 'ActorB', 'stsb@test.com', 'x', 'owner'::user_role)",
            actorB, tenantB);
        jdbc.update(
            "INSERT INTO locations (id, tenant_id, name, is_fulfillment) VALUES (?, ?, 'B WH', true)",
            locationB, tenantB);
        jdbc.update(
            "INSERT INTO stores (id, tenant_id, platform, shop_domain, status, import_status, " +
            "    access_token_encrypted, access_token_expires_at) " +
            "VALUES (?, ?, 'shopify', 'stsb.myshopify.com', 'connected', 'completed', 'enc', " +
            "    now() + interval '876000 hours')",
            storeB, tenantB);
        jdbc.update(
            "INSERT INTO products (id, tenant_id, store_id, external_id, title) " +
            "VALUES (?, ?, ?, 'gid://shopify/Product/STSB', 'Sts Product B')",
            productB, tenantB, storeB);
        jdbc.update(
            "INSERT INTO variants (id, tenant_id, product_id, external_id, title, sku) " +
            "VALUES (?, ?, ?, 'gid://shopify/Variant/STSB', 'Variant B', 'STS-B')",
            variantB, tenantB, productB);
    }

    @AfterEach
    void cleanPieces() {
        jdbc.update("DELETE FROM stock_take_scans WHERE tenant_id IN (?, ?)",           tenantId, tenantB);
        jdbc.update("DELETE FROM stock_take_expected WHERE tenant_id IN (?, ?)",        tenantId, tenantB);
        jdbc.update("DELETE FROM stock_take_scope_variants WHERE tenant_id IN (?, ?)",  tenantId, tenantB);
        jdbc.update("DELETE FROM stock_take_sessions WHERE tenant_id IN (?, ?)",        tenantId, tenantB);
        jdbc.update("DELETE FROM piece_events WHERE tenant_id IN (?, ?)",               tenantId, tenantB);
        jdbc.update("DELETE FROM pieces WHERE tenant_id IN (?, ?)",                     tenantId, tenantB);
    }

    // ss1: in snapshot, condition agrees -> match
    @Test
    void ss1_inSnapshot_conditionAgrees_match() {
        String piece = seedPiece("SS1", variantA, fulfillmentLocationId, tenantId, "available");
        UUID sessionId = openAllScope(tenantId, fulfillmentLocationId, actorId);

        Map<String, Object> result;
        TenantContext.set(tenantId);
        try {
            result = stockTake.scan(sessionId, "PC-" + piece, "good", actorId);
        } finally {
            TenantContext.clear();
        }

        assertThat(result.get("classification")).isEqualTo("match");
        assertThat(result.get("pieceId")).isEqualTo(piece);
    }

    // ss2: in snapshot, condition disagrees -> condition_mismatch
    @Test
    void ss2_inSnapshot_conditionDisagrees_mismatch() {
        String piece = seedPiece("SS2", variantA, fulfillmentLocationId, tenantId, "damaged");
        UUID sessionId = openAllScope(tenantId, fulfillmentLocationId, actorId);

        Map<String, Object> result;
        TenantContext.set(tenantId);
        try {
            // status_at_open='damaged' -> expected condition 'damaged'; scanned 'good' -> mismatch.
            result = stockTake.scan(sessionId, "PC-" + piece, "good", actorId);
        } finally {
            TenantContext.clear();
        }

        assertThat(result.get("classification")).isEqualTo("condition_mismatch");
    }

    // ss3: idempotent — same piece scanned x3 -> exactly 1 row
    @Test
    void ss3_idempotent_samePieceScannedThrice_oneRow() {
        String piece = seedPiece("SS3", variantA, fulfillmentLocationId, tenantId, "available");
        UUID sessionId = openAllScope(tenantId, fulfillmentLocationId, actorId);

        TenantContext.set(tenantId);
        try {
            stockTake.scan(sessionId, "PC-" + piece, "good", actorId);
            stockTake.scan(sessionId, "PC-" + piece, "good", actorId);
            Map<String, Object> third = stockTake.scan(sessionId, "PC-" + piece, "good", actorId);
            assertThat(third.get("alreadyScanned")).isEqualTo(true);
        } finally {
            TenantContext.clear();
        }

        Integer count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM stock_take_scans WHERE session_id = ? AND piece_id = ?",
            Integer.class, sessionId, piece);
        assertThat(count).isEqualTo(1);
    }

    // ss4: unknown barcode -> logged, not errored
    @Test
    void ss4_unknownBarcode_loggedNotErrored() {
        UUID sessionId = openAllScope(tenantId, fulfillmentLocationId, actorId);

        Map<String, Object> result;
        TenantContext.set(tenantId);
        try {
            result = stockTake.scan(sessionId, "GARBAGE-BARCODE-NEVER-ISSUED", "good", actorId);
        } finally {
            TenantContext.clear();
        }

        assertThat(result.get("classification")).isEqualTo("unknown");
        assertThat(result.get("pieceId")).isNull();

        Map<String, Object> row = jdbc.queryForMap(
            "SELECT piece_id, raw_barcode FROM stock_take_scans WHERE session_id = ? AND raw_barcode = ?",
            sessionId, "GARBAGE-BARCODE-NEVER-ISSUED");
        assertThat(row.get("piece_id")).isNull();
        assertThat(row.get("raw_barcode")).isEqualTo("GARBAGE-BARCODE-NEVER-ISSUED");
    }

    // ss5: cross-tenant barcode -> resolves to nothing, never leaks existence
    @Test
    void ss5_crossTenantBarcode_neverLeaksExistence() {
        String crossPiece = seedPiece("SS5CROSS", variantB, locationB, tenantB, "available");
        UUID sessionId = openAllScope(tenantId, fulfillmentLocationId, actorId);

        Map<String, Object> result;
        TenantContext.set(tenantId);
        try {
            result = stockTake.scan(sessionId, "PC-" + crossPiece, "good", actorId);
        } finally {
            TenantContext.clear();
        }

        // Identical shape to a genuinely unknown barcode — no error, no leaked pieceId.
        assertThat(result.get("classification")).isEqualTo("unknown");
        assertThat(result.get("pieceId")).isNull();

        Integer tenantARows = jdbc.queryForObject(
            "SELECT COUNT(*) FROM stock_take_scans WHERE session_id = ? AND raw_barcode = ?",
            Integer.class, sessionId, "PC-" + crossPiece);
        assertThat(tenantARows).as("logged under tenant A's own scan, not resolved to tenant B's piece").isEqualTo(1);

        Integer resolvedAsRealPiece = jdbc.queryForObject(
            "SELECT COUNT(*) FROM stock_take_scans WHERE session_id = ? AND piece_id = ?",
            Integer.class, sessionId, crossPiece);
        assertThat(resolvedAsRealPiece).as("tenant B's piece_id must never appear").isEqualTo(0);
    }

    // ss6: not in snapshot, live status=lost -> unexpected_resurfaced
    @Test
    void ss6_notInSnapshot_liveStatusLost_unexpectedResurfaced() {
        // Never at the fulfillment location — excluded from the snapshot regardless of scope.
        String piece = seedPiece("SS6", variantA, otherLocationId, tenantId, "available");
        TenantContext.set(tenantId);
        try {
            ledger.transition(piece, PieceStatus.AVAILABLE, PieceStatus.LOST,
                "adjusted", actorId, TransitionContext.empty());
        } finally {
            TenantContext.clear();
        }

        UUID sessionId = openAllScope(tenantId, fulfillmentLocationId, actorId);

        Map<String, Object> result;
        TenantContext.set(tenantId);
        try {
            result = stockTake.scan(sessionId, "PC-" + piece, "good", actorId);
        } finally {
            TenantContext.clear();
        }

        assertThat(result.get("classification")).isEqualTo("unexpected_resurfaced");
        assertThat(result.get("pieceId")).isEqualTo(piece);
    }

    // ss7: not in snapshot, live status not lost -> out_of_scope
    @Test
    void ss7_notInSnapshot_notLost_outOfScope() {
        String piece = seedPiece("SS7", variantA, otherLocationId, tenantId, "available");
        UUID sessionId = openAllScope(tenantId, fulfillmentLocationId, actorId);

        Map<String, Object> result;
        TenantContext.set(tenantId);
        try {
            result = stockTake.scan(sessionId, "PC-" + piece, "good", actorId);
        } finally {
            TenantContext.clear();
        }

        assertThat(result.get("classification")).isEqualTo("out_of_scope");
    }

    // ss8: scan against a non-open session -> 409
    @Test
    void ss8_nonOpenSession_returns409() {
        String piece = seedPiece("SS8", variantA, fulfillmentLocationId, tenantId, "available");
        UUID sessionId = openAllScope(tenantId, fulfillmentLocationId, actorId);
        jdbc.update("UPDATE stock_take_sessions SET status = 'cancelled' WHERE id = ?", sessionId);

        TenantContext.set(tenantId);
        try {
            assertThatThrownBy(() -> stockTake.scan(sessionId, "PC-" + piece, "good", actorId))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                    .isEqualTo(HttpStatus.CONFLICT));
        } finally {
            TenantContext.clear();
        }
    }

    // ss9: invalid condition value -> 400
    @Test
    void ss9_invalidCondition_returns400() {
        String piece = seedPiece("SS9", variantA, fulfillmentLocationId, tenantId, "available");
        UUID sessionId = openAllScope(tenantId, fulfillmentLocationId, actorId);

        TenantContext.set(tenantId);
        try {
            assertThatThrownBy(() -> stockTake.scan(sessionId, "PC-" + piece, "bogus", actorId))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                    .isEqualTo(HttpStatus.BAD_REQUEST));
        } finally {
            TenantContext.clear();
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private UUID openAllScope(UUID tenant, UUID locationId, UUID actor) {
        UUID sessionId;
        TenantContext.set(tenant);
        try {
            Map<String, Object> result = stockTake.openSession("all", null, locationId, null, actor);
            sessionId = (UUID) result.get("sessionId");
        } finally {
            TenantContext.clear();
        }
        return sessionId;
    }

    private String seedPiece(String label, UUID variantId, UUID locationId, UUID tenant, String status) {
        String id = UlidGenerator.generate();
        // Barcode format PC-<ulid> matches production (ReceivingService.batchReceive) so
        // scan() resolves it the same way real scans do; label kept only for test readability.
        jdbc.update(
            "INSERT INTO pieces (id, tenant_id, variant_id, barcode, short_code, status, current_location_id) " +
            "VALUES (?, ?, ?, ?, 'P' || LPAD((abs(hashtext(?)) % 999999 + 1)::text, 6, '0'), ?::piece_status, ?)",
            id, tenant, variantId, "PC-" + id, label + id, status, locationId);
        return id;
    }
}
