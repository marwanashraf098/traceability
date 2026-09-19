package com.traceability.integrations.shopify;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * FR-3.1 follow-up — single source of truth for "the tenant's active Shopify store".
 *
 * Before this class, every call site that needed to resolve one store row for a tenant
 * wrote its own "SELECT ... FROM stores WHERE tenant_id = ?" — some with no ORDER BY at
 * all (a bare LIMIT 1 on an unordered result is an arbitrary row), some with
 * ORDER BY last_sync_at DESC NULLS LAST but no preference for non-disconnected rows.
 * Since the guard change that lets a tenant hold a disconnected (old) row alongside an
 * active (new) row after a disconnect-then-switch, an unordered or status-blind pick can
 * silently bind to the dead row — the exact class of bug ShopifySameShopGuard exists to
 * prevent at the write side, resurfacing on the read side instead.
 *
 * findByTenant() and findActiveStoreByTenant() share ONE query — the same ORDER BY
 * ConnectionsController's GET /connections uses (prefer status='connected' over
 * needs_reauth/error over disconnected, then most-recently-synced) — so the UI and every
 * background job agree on which row is "the store". findByTenant() returns whatever wins,
 * even a disconnected row (ConnectionsController needs that — it must still show a
 * disconnected/attention state with the stale domain when that is literally the only row
 * a tenant has). findActiveStoreByTenant() is the one jobs and write-path services should
 * use: it additionally maps a disconnected result to Optional.empty(), so an operational
 * caller can never silently pick up a dead row's domain, token, or scopes.
 */
@Repository
public class StoreRepository {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public StoreRepository(JdbcTemplate jdbc, PlatformTransactionManager txm) {
        this.jdbc = jdbc;
        this.tx   = new TransactionTemplate(txm);
    }

    public record Store(
        UUID id,
        String shopDomain,
        String status,
        String connectionType,
        String importStatus,
        String accessTokenScopes,
        Instant lastSyncAt
    ) {}

    private static final String SELECT_BEST_ROW =
        "SELECT id, shop_domain, status::text, connection_type, import_status::text, " +
        "       access_token_scopes, last_sync_at " +
        "FROM stores WHERE tenant_id = ? " +
        "ORDER BY (status = 'connected') DESC, (status <> 'disconnected') DESC, " +
        "         last_sync_at DESC NULLS LAST LIMIT 1";

    /**
     * The tenant's single "best" store row, any status — the same pick
     * ConnectionsController's GET /connections has always used. Returns Optional.empty()
     * only when the tenant has no store row at all.
     */
    public Optional<Store> findByTenant(UUID tenantId) {
        Store store = tx.execute(s -> jdbc.query(SELECT_BEST_ROW,
            rs -> rs.next() ? new Store(
                rs.getObject("id", UUID.class),
                rs.getString("shop_domain"),
                rs.getString("status"),
                rs.getString("connection_type"),
                rs.getString("import_status"),
                rs.getString("access_token_scopes"),
                rs.getTimestamp("last_sync_at") != null
                    ? rs.getTimestamp("last_sync_at").toInstant() : null
            ) : null,
            tenantId));
        return Optional.ofNullable(store);
    }

    /**
     * The tenant's active store — for every operational caller (background jobs, write-path
     * services) that needs a store it can actually call Shopify with. Never returns a
     * disconnected row: a disconnect-then-switch tenant's stale old row is invisible here
     * even if it happens to be "the best row" findByTenant() would surface (e.g. the only
     * row that exists). Callers must handle Optional.empty() per their own context —
     * background jobs skip/no-op with a log, controllers return a typed 4xx — never fall
     * back to the disconnected row instead.
     */
    public Optional<Store> findActiveStoreByTenant(UUID tenantId) {
        return findByTenant(tenantId).filter(store -> !"disconnected".equals(store.status()));
    }
}
