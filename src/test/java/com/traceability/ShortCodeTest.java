package com.traceability;

import com.traceability.inventory.LookupService;
import com.traceability.inventory.ReceivingService;
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

import java.util.*;

import static org.assertj.core.api.Assertions.*;

/**
 * FR-19 short-code integration tests.
 *
 * Test inventory:
 *   sc1 — two sequential batchReceive calls get non-overlapping short code ranges
 *   sc2 — short codes have correct P + 6-digit format and are unique within a batch
 *   sc3 — LookupService.lookupPiece() resolves a piece when queried by short code
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ShortCodeTest {

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

    @Autowired ReceivingService receivingSvc;
    @Autowired LookupService    lookupSvc;
    @Autowired JdbcTemplate     jdbc;

    UUID tenantId;
    UUID actorId;
    UUID variantId;
    UUID locationId;

    @BeforeAll
    void setup() {
        tenantId   = UUID.randomUUID();
        actorId    = UUID.randomUUID();
        UUID storeId   = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        variantId  = UUID.randomUUID();
        locationId = UUID.randomUUID();

        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, 'SCTestTenant')", tenantId);
        jdbc.update(
            "INSERT INTO users (id, tenant_id, name, email, password_hash, role) VALUES " +
            "(?, ?, 'Actor', 'actor@sctest.local', 'hash', 'owner')",
            actorId, tenantId);
        jdbc.update(
            "INSERT INTO stores (id, tenant_id, platform, shop_domain, status) VALUES " +
            "(?, ?, 'shopify', 'sctest.myshopify.com', 'disconnected')",
            storeId, tenantId);
        jdbc.update(
            "INSERT INTO products (id, tenant_id, store_id, external_id, title, status) VALUES " +
            "(?, ?, ?, 'SC-P001', 'SC Widget', 'active')",
            productId, tenantId, storeId);
        jdbc.update(
            "INSERT INTO variants (id, tenant_id, product_id, external_id, title, sku) VALUES " +
            "(?, ?, ?, 'SC-V001', 'SC Widget Blue', 'SCBLUE')",
            variantId, tenantId, productId);
        jdbc.update(
            "INSERT INTO locations (id, tenant_id, name, type, is_default) VALUES " +
            "(?, ?, 'SC Warehouse', 'warehouse', true)",
            locationId, tenantId);
    }

    @BeforeEach
    void setContext() { TenantContext.set(tenantId); }

    @AfterEach
    void teardown() {
        TenantContext.clear();
        jdbc.update("DELETE FROM piece_events  WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM pieces        WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM receipt_lines WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM receipts      WHERE tenant_id = ?", tenantId);
        // Reset counter so each test starts fresh from P000001.
        jdbc.update("DELETE FROM piece_counters WHERE tenant_id = ?", tenantId);
    }

    // ── sc1: non-overlapping code ranges ─────────────────────────────────────

    @Test
    void sc1_two_batchReceive_calls_get_non_overlapping_short_codes() {
        UUID s1 = receivingSvc.createSession(actorId, locationId, "SC1-A", null, null);
        receivingSvc.addLine(s1, variantId, 3);
        receivingSvc.finalize(s1, actorId);
        List<String> batch1 = jdbc.queryForList(
            "SELECT short_code FROM pieces WHERE receipt_id = ? ORDER BY short_code",
            String.class, s1);

        UUID s2 = receivingSvc.createSession(actorId, locationId, "SC1-B", null, null);
        receivingSvc.addLine(s2, variantId, 2);
        receivingSvc.finalize(s2, actorId);
        List<String> batch2 = jdbc.queryForList(
            "SELECT short_code FROM pieces WHERE receipt_id = ? ORDER BY short_code",
            String.class, s2);

        assertThat(batch1).hasSize(3);
        assertThat(batch2).hasSize(2);

        // Non-overlapping: no code appears in both batches.
        // Gaps are allowed (C3 — counter may leave gaps on rollback).
        Set<String> intersection = new HashSet<>(batch1);
        intersection.retainAll(new HashSet<>(batch2));
        assertThat(intersection)
            .as("short codes from different batchReceive calls must not overlap")
            .isEmpty();
    }

    // ── sc2: correct format and uniqueness ────────────────────────────────────

    @Test
    void sc2_short_codes_have_correct_format_and_are_unique_within_batch() {
        UUID s = receivingSvc.createSession(actorId, locationId, "SC2", null, null);
        receivingSvc.addLine(s, variantId, 10);
        receivingSvc.finalize(s, actorId);

        List<String> codes = jdbc.queryForList(
            "SELECT short_code FROM pieces WHERE receipt_id = ?", String.class, s);
        assertThat(codes).hasSize(10);

        for (String code : codes) {
            assertThat(code).as("must match P + 6 digits").matches("P\\d{6}");
        }
        assertThat(new HashSet<>(codes))
            .as("all short codes within a batch must be distinct")
            .hasSize(10);
    }

    // ── sc3: lookup resolves piece by short code ──────────────────────────────

    @Test
    void sc3_lookupPiece_resolves_piece_when_queried_by_short_code() {
        UUID s = receivingSvc.createSession(actorId, locationId, "SC3", null, null);
        receivingSvc.addLine(s, variantId, 1);
        receivingSvc.finalize(s, actorId);

        Map<String, Object> dbRow = jdbc.queryForMap(
            "SELECT id, short_code FROM pieces WHERE receipt_id = ? LIMIT 1", s);
        String expectedId        = (String) dbRow.get("id");
        String shortCode         = (String) dbRow.get("short_code");

        Map<String, Object> result = lookupSvc.lookupPiece(shortCode, false);

        assertThat(result.get("type")).isEqualTo("piece");
        assertThat(result.get("id").toString()).isEqualTo(expectedId);
        assertThat(result.get("shortCode")).isEqualTo(shortCode);
    }
}
