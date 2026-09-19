package com.traceability;

import com.traceability.integrations.shopify.StoreRepository;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FR-3.1 follow-up — StoreRepository, the single canonical "which store row" pick shared
 * by ConnectionsController and every job/service that was previously hand-rolling its own
 * (sometimes unordered, sometimes under-ordered) SELECT.
 *
 * Matrix:
 *   sr1 — single-store tenant, connected → findByTenant and findActiveStoreByTenant agree,
 *         behaviour unchanged from before this class existed
 *   sr2 — disconnect-then-switch (1 disconnected + 1 connected) → both methods resolve the
 *         ACTIVE (connected) row, never the disconnected one, regardless of last_sync_at
 *   sr3 — all rows disconnected → findActiveStoreByTenant is empty; findByTenant still
 *         returns the most-recently-synced disconnected row (ConnectionsController's display
 *         need) — the empty case NEVER silently falls back to a disconnected row
 *   sr4 — no store rows at all → both methods empty
 *   sr5 — connected beats needs_reauth/error even if the other is more recently synced —
 *         the resolver prefers an actually-usable store over a stale-but-recent broken one
 *   sr6 — among two non-connected-but-active rows (needs_reauth vs error), recency
 *         (last_sync_at DESC NULLS LAST) is the tiebreak
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StoreRepositoryTest {

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

    @Autowired JdbcTemplate jdbc;
    @Autowired StoreRepository storeRepository;

    UUID tenantId;

    @BeforeEach
    void setup() {
        tenantId = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, 'StoreRepo Tenant')", tenantId);
    }

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM stores WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM tenants WHERE id = ?", tenantId);
    }

    private UUID insertStore(String domain, String status, String lastSyncAtExpr) {
        UUID id = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO stores (id, tenant_id, shop_domain, status, last_sync_at) " +
            "VALUES (?, ?, ?, ?::store_status, " + lastSyncAtExpr + ")",
            id, tenantId, domain, status);
        return id;
    }

    // ── sr1: single-store tenant — behaviour unchanged ─────────────────────

    @Test
    void sr1_singleConnectedStore_bothMethodsAgree() {
        UUID storeId = insertStore("sr1.myshopify.com", "connected", "now()");

        Optional<StoreRepository.Store> viaFind   = storeRepository.findByTenant(tenantId);
        Optional<StoreRepository.Store> viaActive = storeRepository.findActiveStoreByTenant(tenantId);

        assertThat(viaFind).isPresent();
        assertThat(viaActive).isPresent();
        assertThat(viaFind.get().id()).isEqualTo(storeId);
        assertThat(viaActive.get().id()).isEqualTo(storeId);
        assertThat(viaActive.get().shopDomain()).isEqualTo("sr1.myshopify.com");
    }

    // ── sr2: disconnect-then-switch — active row wins regardless of recency ─

    @Test
    void sr2_disconnectThenSwitch_activeRowWinsOverMoreRecentDisconnected() {
        // Disconnected row is MORE recently synced than the active one — a naive
        // "ORDER BY last_sync_at DESC" pick would wrongly choose it.
        insertStore("sr2-old.myshopify.com", "disconnected", "now()");
        UUID activeId = insertStore("sr2-new.myshopify.com", "connected", "now() - interval '1 hour'");

        Optional<StoreRepository.Store> active = storeRepository.findActiveStoreByTenant(tenantId);
        assertThat(active).isPresent();
        assertThat(active.get().id()).isEqualTo(activeId);
        assertThat(active.get().shopDomain()).isEqualTo("sr2-new.myshopify.com");

        // findByTenant (the display pick) also prefers the active row here.
        assertThat(storeRepository.findByTenant(tenantId).get().id()).isEqualTo(activeId);
    }

    // ── sr3: all rows disconnected — active is empty, findByTenant still shows one ──

    @Test
    void sr3_allDisconnected_activeEmpty_findByTenantStillReturnsMostRecent() {
        insertStore("sr3-older.myshopify.com", "disconnected", "now() - interval '1 day'");
        UUID mostRecent = insertStore("sr3-newer.myshopify.com", "disconnected", "now()");

        assertThat(storeRepository.findActiveStoreByTenant(tenantId))
            .as("must NEVER fall back to a disconnected row").isEmpty();

        Optional<StoreRepository.Store> display = storeRepository.findByTenant(tenantId);
        assertThat(display).isPresent();
        assertThat(display.get().id()).isEqualTo(mostRecent);
        assertThat(display.get().status()).isEqualTo("disconnected");
    }

    // ── sr4: no store rows at all ───────────────────────────────────────────

    @Test
    void sr4_noStoreRows_bothEmpty() {
        assertThat(storeRepository.findByTenant(tenantId)).isEmpty();
        assertThat(storeRepository.findActiveStoreByTenant(tenantId)).isEmpty();
    }

    // ── sr5: connected beats needs_reauth even if needs_reauth is more recent ──

    @Test
    void sr5_connectedBeatsNeedsReauth_regardlessOfRecency() {
        UUID connectedId = insertStore("sr5-connected.myshopify.com", "connected", "now() - interval '2 hours'");
        insertStore("sr5-needsreauth.myshopify.com", "needs_reauth", "now()");

        Optional<StoreRepository.Store> active = storeRepository.findActiveStoreByTenant(tenantId);
        assertThat(active).isPresent();
        assertThat(active.get().id()).isEqualTo(connectedId);
        assertThat(active.get().status()).isEqualTo("connected");
    }

    // ── sr6: among two non-connected-active rows, recency tiebreaks ─────────

    @Test
    void sr6_needsReauthVsError_recencyTiebreaks() {
        insertStore("sr6-older-error.myshopify.com", "error", "now() - interval '1 day'");
        UUID newerId = insertStore("sr6-newer-reauth.myshopify.com", "needs_reauth", "now()");

        Optional<StoreRepository.Store> active = storeRepository.findActiveStoreByTenant(tenantId);
        assertThat(active).isPresent();
        assertThat(active.get().id()).isEqualTo(newerId);
        assertThat(active.get().status()).isEqualTo("needs_reauth");
    }
}
