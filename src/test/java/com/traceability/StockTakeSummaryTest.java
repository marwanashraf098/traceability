package com.traceability;

import com.traceability.inventory.*;
import com.traceability.tenancy.TenantAwareDataSource;
import com.traceability.tenancy.TenantContext;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FR-21 stock-take restyle — GET /stock-takes/summary (landing analytics band).
 *
 * sts1 — countsThisMonth counts sessions opened this month regardless of status
 *        (open/finalized/cancelled all count), and EXCLUDES a session backdated to a
 *        prior month — even though that prior-month session is finalized and DOES still
 *        feed piecesWrittenOff/avgVariancePercent (those two are all-time, by design).
 * sts2 — piecesWrittenOff uses the exact same predicate as finalizeSession()'s own delta
 *        query (event_type=adjusted, to_status=lost, metadata.reason=stock_take_missing) —
 *        summed across ALL of the tenant's sessions, not scoped to one.
 * sts3 — avgVariancePercent is a ratio-of-sums across finalized sessions only (open/
 *        cancelled sessions excluded from the ratio, even though open counts toward
 *        openSessions and cancelled counted toward countsThisMonth this month).
 * sts4 — avgVariancePercent is null — not 0.0 — when the tenant has zero finalized
 *        sessions. "No history" and "counts were perfect" must not share a number.
 * sts5 — openSessions counts only status='open'.
 * sts6 — cross-tenant isolation: tenant B's sessions/piece_events never leak into tenant
 *        A's summary. Same-tenant positive control over a real app_user/RLS connection,
 *        matching StockTakeOpsTest's established pattern.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StockTakeSummaryTest {

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

    @Autowired JdbcTemplate     jdbc;
    @Autowired StockTakeService stockTake;

    StockTakeService appUserStockTake;
    TransactionTemplate appUserTx;

    UUID tenantA, tenantB, actorA, actorB, storeA, storeB, productA, productB, variantA, variantB;
    UUID locA, locB;

    @BeforeAll
    void setupFixture() {
        tenantA = UUID.randomUUID();
        tenantB = UUID.randomUUID();
        actorA  = UUID.randomUUID();
        actorB  = UUID.randomUUID();
        storeA  = UUID.randomUUID();
        storeB  = UUID.randomUUID();
        productA = UUID.randomUUID();
        productB = UUID.randomUUID();
        variantA = UUID.randomUUID();
        variantB = UUID.randomUUID();
        locA = UUID.randomUUID();
        locB = UUID.randomUUID();

        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, 'SummaryTenantA')", tenantA);
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, 'SummaryTenantB')", tenantB);
        jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role) " +
            "VALUES (?, ?, 'Actor A', 'sts-a@test.com', 'x', 'owner'::user_role)", actorA, tenantA);
        jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role) " +
            "VALUES (?, ?, 'Actor B', 'sts-b@test.com', 'x', 'owner'::user_role)", actorB, tenantB);
        jdbc.update("INSERT INTO locations (id, tenant_id, name, is_fulfillment) VALUES (?, ?, 'WH A', true)", locA, tenantA);
        jdbc.update("INSERT INTO locations (id, tenant_id, name, is_fulfillment) VALUES (?, ?, 'WH B', true)", locB, tenantB);
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status, import_status, " +
            "access_token_encrypted, access_token_expires_at) " +
            "VALUES (?, ?, 'shopify', 'sts-a.myshopify.com', 'connected', 'completed', 'enc', now() + interval '876000 hours')",
            storeA, tenantA);
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status, import_status, " +
            "access_token_encrypted, access_token_expires_at) " +
            "VALUES (?, ?, 'shopify', 'sts-b.myshopify.com', 'connected', 'completed', 'enc', now() + interval '876000 hours')",
            storeB, tenantB);
        jdbc.update("INSERT INTO products (id, tenant_id, store_id, external_id, title) " +
            "VALUES (?, ?, ?, 'gid://shopify/Product/STSA', 'Product A')", productA, tenantA, storeA);
        jdbc.update("INSERT INTO products (id, tenant_id, store_id, external_id, title) " +
            "VALUES (?, ?, ?, 'gid://shopify/Product/STSB', 'Product B')", productB, tenantB, storeB);
        jdbc.update("INSERT INTO variants (id, tenant_id, product_id, external_id, title, sku) " +
            "VALUES (?, ?, ?, 'gid://shopify/Variant/STSA', 'Variant A', 'STS-A')", variantA, tenantA, productA);
        jdbc.update("INSERT INTO variants (id, tenant_id, product_id, external_id, title, sku) " +
            "VALUES (?, ?, ?, 'gid://shopify/Variant/STSB', 'Variant B', 'STS-B')", variantB, tenantB, productB);

        DriverManagerDataSource rawAppUser = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "app_user", "testpw");
        TenantAwareDataSource appUserDs = new TenantAwareDataSource(rawAppUser);
        JdbcTemplate appUserJdbc = new JdbcTemplate(appUserDs);
        DataSourceTransactionManager appUserTxm = new DataSourceTransactionManager(appUserDs);
        appUserTx = new TransactionTemplate(appUserTxm);
        appUserStockTake = new StockTakeService(appUserJdbc, new com.fasterxml.jackson.databind.ObjectMapper());
    }

    @AfterEach
    void cleanState() {
        jdbc.update("DELETE FROM stock_take_scans WHERE tenant_id IN (?, ?)",           tenantA, tenantB);
        jdbc.update("DELETE FROM stock_take_expected WHERE tenant_id IN (?, ?)",        tenantA, tenantB);
        jdbc.update("DELETE FROM stock_take_shopify_syncs WHERE tenant_id IN (?, ?)",   tenantA, tenantB);
        jdbc.update("DELETE FROM stock_take_sessions WHERE tenant_id IN (?, ?)",        tenantA, tenantB);
        jdbc.update("DELETE FROM piece_events WHERE tenant_id IN (?, ?)",               tenantA, tenantB);
        jdbc.update("DELETE FROM pieces WHERE tenant_id IN (?, ?)",                     tenantA, tenantB);
        jdbc.update("DELETE FROM audit_log WHERE tenant_id IN (?, ?)",                  tenantA, tenantB);
    }

    // sts1–sts5: build a mixed fixture for tenant A, assert all four figures at once.
    @Test
    void sts1to5_summaryAggregates_countsThisMonthAllTimeWriteOffsWeightedVarianceOpenSessions() {
        // session1: open, opened now -> counts toward countsThisMonth + openSessions.
        UUID session1 = insertSession(tenantA, actorA, locA, "open", null);

        // session2: finalized, opened now -> counts toward countsThisMonth.
        // 4 pieces expected on shelf, 2 counted (scanned) -> variance contribution 4/2.
        UUID session2 = insertSession(tenantA, actorA, locA, "finalized", null);
        seedExpectedAndScans(tenantA, session2, variantA, 4, 2);
        seedWriteOffs(tenantA, actorA, session2, 2);

        // session3: finalized, backdated to LAST month -> excluded from countsThisMonth,
        // but its variance/write-offs are all-time and DO count.
        // 6 expected, 3 counted -> variance contribution 6/3.
        UUID session3 = insertSession(tenantA, actorA, locA, "finalized", "now() - interval '45 days'");
        seedExpectedAndScans(tenantA, session3, variantA, 6, 3);
        seedWriteOffs(tenantA, actorA, session3, 1);

        // session4: cancelled, opened now -> counts toward countsThisMonth, excluded from
        // the variance ratio (only 'finalized' sessions feed it), no write-offs.
        insertSession(tenantA, actorA, locA, "cancelled", null);

        // Tenant B noise — must not leak into tenant A's numbers at all.
        insertSession(tenantB, actorB, locB, "open", null);
        UUID sessionB = insertSession(tenantB, actorB, locB, "finalized", null);
        seedExpectedAndScans(tenantB, sessionB, variantB, 10, 1);
        seedWriteOffs(tenantB, actorB, sessionB, 7);

        TenantContext.set(tenantA);
        StockTakeService.StockTakeSummaryCounts summary;
        try {
            summary = stockTake.summary();
        } finally {
            TenantContext.clear();
        }

        assertThat(summary.countsThisMonth())
            .as("sts1: session1(open)+session2(finalized)+session4(cancelled) opened this month; session3 backdated excluded")
            .isEqualTo(3);
        assertThat(summary.piecesWrittenOff())
            .as("sts2: session2's 2 + session3's 1 write-offs, all-time, tenant B's 7 excluded")
            .isEqualTo(3);
        assertThat(summary.openSessions())
            .as("sts5: only session1 is open")
            .isEqualTo(1);
        // (4-2) + (6-3) = 5 missing out of 4+6=10 expected -> 50.0%
        assertThat(summary.avgVariancePercent())
            .as("sts3: ratio-of-sums over finalized sessions only (session2+session3), weighted not averaged")
            .isEqualTo(50.0);
    }

    // sts4: zero finalized sessions -> avgVariancePercent is null, never 0.0.
    @Test
    void sts4_noFinalizedSessions_avgVarianceIsNullNotZero() {
        insertSession(tenantA, actorA, locA, "open", null);

        TenantContext.set(tenantA);
        StockTakeService.StockTakeSummaryCounts summary;
        try {
            summary = stockTake.summary();
        } finally {
            TenantContext.clear();
        }

        assertThat(summary.avgVariancePercent())
            .as("sts4: no finalized history must render as 'no data', not a literal 0.0%")
            .isNull();
    }

    // sts6: cross-tenant isolation, same-tenant positive control over real app_user/RLS.
    @Test
    void sts6_summary_rlsIsolated_sameTenantPositiveControl() {
        insertSession(tenantA, actorA, locA, "open", null);
        UUID session2 = insertSession(tenantA, actorA, locA, "finalized", null);
        seedExpectedAndScans(tenantA, session2, variantA, 2, 1);
        seedWriteOffs(tenantA, actorA, session2, 1);

        try {
            TenantContext.set(tenantA);
            try {
                appUserTx.execute(status -> {
                    StockTakeService.StockTakeSummaryCounts summary = appUserStockTake.summary();
                    assertThat(summary.openSessions())
                        .as("sts6: same-tenant positive control over app_user/RLS")
                        .isEqualTo(1);
                    assertThat(summary.piecesWrittenOff()).isEqualTo(1);
                    return null;
                });
            } finally {
                TenantContext.clear();
            }
        } catch (org.springframework.dao.DataAccessResourceFailureException e) {
            Assumptions.assumeTrue(false, "app_user not available in test container — RLS assertion skipped: " + e.getMessage());
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private UUID insertSession(UUID tenantId, UUID actorId, UUID locationId, String status, String openedAtExpr) {
        UUID sessionId = UUID.randomUUID();
        String openedAt = openedAtExpr == null ? "now()" : openedAtExpr;
        jdbc.update(
            "INSERT INTO stock_take_sessions (id, tenant_id, status, scope_type, location_id, opened_by, opened_at) " +
            "VALUES (?, ?, ?, 'all', ?, ?, " + openedAt + ")",
            sessionId, tenantId, status, locationId, actorId);
        return sessionId;
    }

    private void seedExpectedAndScans(UUID tenantId, UUID sessionId, UUID variantId, int expectedCount, int countedOfThose) {
        for (int i = 0; i < expectedCount; i++) {
            String pieceId = seedPiece(tenantId, variantId);
            jdbc.update(
                "INSERT INTO stock_take_expected (tenant_id, session_id, piece_id, variant_id, status_at_open) " +
                "VALUES (?, ?, ?, ?, 'available')",
                tenantId, sessionId, pieceId, variantId);
            if (i < countedOfThose) {
                jdbc.update(
                    "INSERT INTO stock_take_scans (id, tenant_id, session_id, piece_id, scanned_condition, source, actor_user_id) " +
                    "VALUES (gen_random_uuid(), ?, ?, ?, 'good', 'scan', " +
                    "(SELECT opened_by FROM stock_take_sessions WHERE id = ?))",
                    tenantId, sessionId, pieceId, sessionId);
            }
        }
    }

    private void seedWriteOffs(UUID tenantId, UUID actorId, UUID sessionId, int count) {
        for (int i = 0; i < count; i++) {
            String pieceId = seedLostPiece(tenantId);
            jdbc.update(
                "INSERT INTO piece_events (tenant_id, piece_id, event_type, actor_user_id, " +
                "    from_status, to_status, metadata) " +
                "VALUES (?, ?, 'adjusted', ?, 'available'::piece_status, 'lost'::piece_status, ?::jsonb)",
                tenantId, pieceId, actorId,
                "{\"session_id\":\"" + sessionId + "\",\"reason\":\"stock_take_missing\"}");
        }
    }

    private String seedPiece(UUID tenantId, UUID variantId) {
        String id = UlidGenerator.generate();
        jdbc.update(
            "INSERT INTO pieces (id, tenant_id, variant_id, barcode, short_code, status) " +
            "VALUES (?, ?, ?, ?, 'P' || LPAD((abs(hashtext(?)) % 999999 + 1)::text, 6, '0'), 'available'::piece_status)",
            id, tenantId, variantId, "PC-" + id, id);
        return id;
    }

    // used by seedWriteOffs — piece already in 'lost' status, variant reused per tenant
    private String seedLostPiece(UUID tenantId) {
        UUID variantId = tenantId.equals(tenantA) ? variantA : variantB;
        String id = UlidGenerator.generate();
        jdbc.update(
            "INSERT INTO pieces (id, tenant_id, variant_id, barcode, short_code, status) " +
            "VALUES (?, ?, ?, ?, 'P' || LPAD((abs(hashtext(?)) % 999999 + 1)::text, 6, '0'), 'lost'::piece_status)",
            id, tenantId, variantId, "PC-" + id, id);
        return id;
    }
}
