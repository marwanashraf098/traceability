package com.traceability;

import com.traceability.integrations.shopify.ShopifyGateway;
import com.traceability.integrations.shopify.ShopifyTokenProvider;
import com.traceability.inventory.StockTakeReconciliationService;
import com.traceability.inventory.StockTakeService;
import com.traceability.inventory.UlidGenerator;
import com.traceability.tenancy.TenantContext;
import org.jobrunr.jobs.lambdas.JobLambda;
import org.jobrunr.scheduling.JobScheduler;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Stock-take push job (StockTakeShopifyPushJob) is enqueued only AFTER the finalize / repush
 * transaction commits.
 *
 *   pc1 — finalize, commit delayed: at enqueue time the claim row is committed 'pending'; the job
 *         then pushes once.
 *   pc2 — repush, commit delayed: at enqueue time the claim is committed 'pending' (not the old
 *         'failed').
 *   pc3 — repush rolled back: no job enqueued, no Shopify call, claim still 'failed'.
 *   pc4 — finalize rolled back: no job enqueued, no claim, session still open.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StockTakePushAfterCommitTest {

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

    static final long LATE_COMMIT_MS = 1000;

    @MockBean JobScheduler         jobScheduler;
    @MockBean ShopifyGateway       shopifyGateway;
    @MockBean ShopifyTokenProvider tokenProvider;

    @Autowired JdbcTemplate                   jdbc;
    @Autowired PlatformTransactionManager     txm;
    @Autowired StockTakeService               stockTake;
    @Autowired StockTakeReconciliationService reconciliation;

    UUID tenantId, actorId, storeId, variantId, locationId;

    /** The claim status a separate connection saw at each enqueue, and the enqueued jobs. */
    final List<String> claimSeenAtEnqueue = new ArrayList<>();
    final List<JobLambda> enqueued = new ArrayList<>();

    @BeforeAll
    void fixture() {
        tenantId = UUID.randomUUID(); actorId = UUID.randomUUID(); storeId = UUID.randomUUID();
        variantId = UUID.randomUUID(); locationId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, 'StockTakeAfterCommit')", tenantId);
        jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role) " +
            "VALUES (?, ?, 'Actor', 'stac@test.com', 'x', 'owner'::user_role)", actorId, tenantId);
        jdbc.update("INSERT INTO locations (id, tenant_id, name, is_fulfillment, shopify_location_id, shopify_sync_status) " +
            "VALUES (?, ?, 'Main WH', true, 'gid://shopify/Location/STAC', 'linked')", locationId, tenantId);
        jdbc.update("INSERT INTO stores (id, tenant_id, shop_domain, status, import_status, access_token_scopes, last_sync_at) " +
            "VALUES (?, ?, 'stac.myshopify.com', 'connected', 'idle', 'read_products,write_inventory', now())", storeId, tenantId);
        jdbc.update("INSERT INTO products (id, tenant_id, store_id, external_id, title, status) " +
            "VALUES (?, ?, ?, 'gid://shopify/Product/STAC', 'P', 'active')", productId, tenantId, storeId);
        jdbc.update("INSERT INTO variants (id, tenant_id, product_id, external_id, title, sku) " +
            "VALUES (?, ?, ?, 'gid://shopify/ProductVariant/STAC', 'V', 'STAC-A')", variantId, tenantId, productId);
    }

    @BeforeEach
    void stubs() {
        reset(jobScheduler, shopifyGateway, tokenProvider);
        claimSeenAtEnqueue.clear();
        enqueued.clear();
        when(tokenProvider.getValidToken(storeId)).thenReturn("tok");
        when(shopifyGateway.resolveInventoryItemId(anyString(), anyString(), anyString()))
            .thenAnswer(inv -> "gid://shopify/InventoryItem/" + inv.getArgument(2));
        when(jobScheduler.enqueue(any(JobLambda.class))).thenAnswer(inv -> {
            claimSeenAtEnqueue.add(committedClaimStatus());
            enqueued.add(inv.getArgument(0));
            return null;
        });
    }

    @AfterEach
    void clean() {
        TenantContext.clear();
        jdbc.update("DELETE FROM stock_take_shopify_syncs WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM stock_take_scans WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM stock_take_expected WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM stock_take_scope_variants WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM stock_take_sessions WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM audit_log WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM piece_events WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM pieces WHERE tenant_id = ?", tenantId);
    }

    @Test
    void pc1_finalizeCommitted_jobSeesCommittedClaim() throws Exception {
        UUID session = sessionWithOneWriteOff();

        late(() -> reconciliation.finalizeSession(session, actorId));

        assertThat(claimSeenAtEnqueue).as("enqueued once, after the claim committed").containsExactly("pending");
        enqueued.get(0).run();
        verify(shopifyGateway, times(1)).pushStockTakeWriteOff(any(), any(), any(), any(), any(), any());
        assertThat(claimStatus(session)).isEqualTo("pushed");
    }

    @Test
    void pc2_repushCommitted_jobSeesCommittedPending() {
        UUID session = finalizedWithFailedClaim();

        late(() -> reconciliation.repushSync(session, actorId));

        assertThat(claimSeenAtEnqueue).as("enqueued once, after 'failed' → 'pending' committed").containsExactly("pending");
    }

    @Test
    void pc3_repushRolledBack_noJob_noShopifyCall() {
        UUID session = finalizedWithFailedClaim();

        rolledBack(() -> reconciliation.repushSync(session, actorId));

        verify(jobScheduler, never()).enqueue(any(JobLambda.class));
        verify(shopifyGateway, never()).pushStockTakeWriteOff(any(), any(), any(), any(), any(), any());
        assertThat(claimStatus(session)).isEqualTo("failed");
    }

    @Test
    void pc4_finalizeRolledBack_noJob_noClaim() {
        UUID session = sessionWithOneWriteOff();

        rolledBack(() -> reconciliation.finalizeSession(session, actorId));

        verify(jobScheduler, never()).enqueue(any(JobLambda.class));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM stock_take_shopify_syncs WHERE session_id = ?",
            Integer.class, session)).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM stock_take_sessions WHERE id = ?", String.class, session))
            .isEqualTo("open");
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    /** An open session over one available piece, attested complete and written off as lost. */
    private UUID sessionWithOneWriteOff() {
        String piece = UlidGenerator.generate();
        jdbc.update("INSERT INTO pieces (id, tenant_id, variant_id, barcode, short_code, status, current_location_id) " +
            "VALUES (?, ?, ?, ?, 'S' || LPAD((abs(hashtext(?)) % 999999 + 1)::text, 6, '0'), 'available', ?)",
            piece, tenantId, variantId, "PC-" + piece, piece, locationId);
        return TenantContext.runAs(tenantId, () -> {
            UUID session = (UUID) stockTake.openSession("all", null, locationId, null, actorId).get("sessionId");
            reconciliation.attestComplete(session, actorId);
            reconciliation.resolve(session, List.of(new StockTakeReconciliationService.ResolveItem(piece, "lost")), actorId);
            return session;
        });
    }

    private UUID finalizedWithFailedClaim() {
        UUID session = sessionWithOneWriteOff();
        TenantContext.runAs(tenantId, () -> reconciliation.finalizeSession(session, actorId));
        jdbc.update("UPDATE stock_take_shopify_syncs SET status = 'failed' WHERE session_id = ?", session);
        reset(jobScheduler);
        stubs();
        return session;
    }

    private void late(Runnable caller) {
        TenantContext.runAs(tenantId, () -> new TransactionTemplate(txm).executeWithoutResult(st -> {
            caller.run();
            sleep(LATE_COMMIT_MS);
        }));
    }

    private void rolledBack(Runnable caller) {
        TenantContext.runAs(tenantId, () -> new TransactionTemplate(txm).executeWithoutResult(st -> {
            caller.run();
            st.setRollbackOnly();
        }));
    }

    private String claimStatus(UUID session) {
        return jdbc.queryForObject("SELECT status FROM stock_take_shopify_syncs WHERE session_id = ?", String.class, session);
    }

    /** This tenant's claim status as a separate connection sees it (committed), or "none". */
    private String committedClaimStatus() {
        try (Connection c = DriverManager.getConnection(POSTGRES.getJdbcUrl(), "postgres", "postgres");
             PreparedStatement ps = c.prepareStatement(
                 "SELECT status FROM stock_take_shopify_syncs WHERE tenant_id = ? ORDER BY created_at DESC LIMIT 1")) {
            ps.setObject(1, tenantId);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? rs.getString(1) : "none"; }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
