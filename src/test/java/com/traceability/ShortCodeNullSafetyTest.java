package com.traceability;

import com.traceability.inventory.LabelService;
import com.traceability.inventory.LookupService;
import com.traceability.inventory.ReceivingService;
import com.traceability.inventory.ScanHelperService;
import com.traceability.tenancy.TenantContext;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.*;

import static org.assertj.core.api.Assertions.*;

/**
 * Flexible labeling slice 0 — the short_code readers survive a piece with NO short_code.
 *
 * Production keeps pieces.short_code NOT NULL until slice 2 (count-only receiving creates untagged
 * pieces). This class's own container drops the constraint, as slice 2 will, so the guarded paths
 * actually run. Every assertion on a TAGGED piece is today's behaviour, unchanged.
 *
 *   n1 — labels: a session mixing tagged and untagged pieces prints the tagged ones; only
 *        untagged → 422 "No labeled pieces to print"
 *   n2 — scan helpers (pieces / lookup) never offer an untagged piece — it has no label to scan
 *   n3 — Lookup returns an untagged piece with shortCode null (no exception)
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ShortCodeNullSafetyTest {

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

    @Autowired ReceivingService  receivingSvc;
    @Autowired LabelService      labelSvc;
    @Autowired ScanHelperService scanHelpers;
    @Autowired LookupService     lookupSvc;
    @Autowired JdbcTemplate      jdbc;

    UUID tenantId;
    UUID actorId;
    UUID variantId;
    UUID locationId;
    UUID sessionId;
    List<String> untagged;   // piece ids whose short_code was cleared
    List<String> tagged;

    @BeforeAll
    void setup() {
        // Slice 2's schema change, in this throwaway container only.
        jdbc.execute("ALTER TABLE pieces ALTER COLUMN short_code DROP NOT NULL");

        tenantId   = UUID.randomUUID();
        actorId    = UUID.randomUUID();
        UUID storeId   = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        variantId  = UUID.randomUUID();
        locationId = UUID.randomUUID();

        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, 'NSTestTenant')", tenantId);
        jdbc.update(
            "INSERT INTO users (id, tenant_id, name, email, password_hash, role) VALUES " +
            "(?, ?, 'Actor', 'actor@nstest.local', 'hash', 'owner')",
            actorId, tenantId);
        jdbc.update(
            "INSERT INTO stores (id, tenant_id, platform, shop_domain, status) VALUES " +
            "(?, ?, 'shopify', 'nstest.myshopify.com', 'disconnected')",
            storeId, tenantId);
        jdbc.update(
            "INSERT INTO products (id, tenant_id, store_id, external_id, title, status) VALUES " +
            "(?, ?, ?, 'NS-P001', 'NS Widget', 'active')",
            productId, tenantId, storeId);
        jdbc.update(
            "INSERT INTO variants (id, tenant_id, product_id, external_id, title, sku) VALUES " +
            "(?, ?, ?, 'NS-V001', 'NS Widget Blue', 'NSBLUE')",
            variantId, tenantId, productId);
        jdbc.update(
            "INSERT INTO locations (id, tenant_id, name, type, is_default) VALUES " +
            "(?, ?, 'NS Warehouse', 'warehouse', true)",
            locationId, tenantId);

        TenantContext.set(tenantId);
        try {
            sessionId = receivingSvc.createSession(actorId, locationId, "NS1", null, null);
            receivingSvc.addLine(sessionId, variantId, 4);
            receivingSvc.finalize(sessionId, actorId);
        } finally {
            TenantContext.clear();
        }
        List<String> all = jdbc.queryForList(
            "SELECT id FROM pieces WHERE receipt_id = ? ORDER BY short_code", String.class, sessionId);
        tagged   = all.subList(0, 2);
        untagged = all.subList(2, 4);
        for (String id : untagged) jdbc.update("UPDATE pieces SET short_code = NULL WHERE id = ?", id);
    }

    @BeforeEach
    void setContext() { TenantContext.set(tenantId); }

    @AfterEach
    void clearContext() { TenantContext.clear(); }

    @Test
    void n1_labels_print_tagged_pieces_and_refuse_when_none_are_tagged() throws Exception {
        byte[] mixed = labelSvc.generateSessionLabels(sessionId, null, null);
        try (var doc = org.apache.pdfbox.Loader.loadPDF(mixed)) {
            assertThat(doc.getNumberOfPages()).as("one page per TAGGED piece").isEqualTo(2);
        }
        assertThat(labelSvc.generatePieceLabel(tagged.get(0), null, null)).isNotEmpty();

        // Slice 2: an untagged piece's label is refused outright — 409 PIECE_UNTAGGED.
        assertThatThrownBy(() -> labelSvc.generatePieceLabel(untagged.get(0), null, null))
            .isInstanceOf(com.traceability.ApiException.class)
            .satisfies(e -> assertThat(((com.traceability.ApiException) e).errorCode()).isEqualTo("PIECE_UNTAGGED"));
        assertThatThrownBy(() -> labelSvc.generatePieceLabels(untagged, null, null))
            .isInstanceOf(com.traceability.ApiException.class)
            .satisfies(e -> assertThat(((com.traceability.ApiException) e).errorCode()).isEqualTo("PIECE_UNTAGGED"));
    }

    @Test
    void n2_scan_helpers_never_offer_an_untagged_piece() {
        Set<String> untaggedBarcodes = new HashSet<>(jdbc.queryForList(
            "SELECT barcode FROM pieces WHERE short_code IS NULL AND tenant_id = ?", String.class, tenantId));

        List<Object> picks = scanHelpers.pieces(tenantId, variantId).stream().map(m -> m.get("code")).toList();
        assertThat(picks).hasSize(2).doesNotContainAnyElementsOf(untaggedBarcodes);

        List<Object> lookups = scanHelpers.lookup(tenantId).stream().map(m -> m.get("code")).toList();
        assertThat(lookups).isNotEmpty().doesNotContainAnyElementsOf(untaggedBarcodes);
    }

    @Test
    void n3_lookup_returns_an_untagged_piece_with_null_short_code() {
        String barcode = jdbc.queryForObject("SELECT barcode FROM pieces WHERE id = ?", String.class, untagged.get(0));
        Map<String, Object> r = lookupSvc.lookupPiece(barcode, false);
        assertThat(r).containsEntry("id", untagged.get(0));
        assertThat(r.get("shortCode")).isNull();

        String taggedCode = jdbc.queryForObject("SELECT short_code FROM pieces WHERE id = ?", String.class, tagged.get(0));
        assertThat(lookupSvc.lookupPiece(taggedCode, false)).containsEntry("shortCode", taggedCode);
    }
}
