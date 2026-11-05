package com.traceability.integrations.shopify;

import com.traceability.tenancy.TenantContext;
import org.jobrunr.jobs.annotations.Job;
import org.jobrunr.scheduling.JobScheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.flyway.FlywayDataSource;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Shopify-native returns S1 — per-store backfill of the returns mirror: for every order Traced already holds for the
 * store (merchant_orders — the store's ingested orders, so the pre-connect cutoff applies; orders Shopify has but
 * Traced doesn't are never asked about), fetch the order's returns and, for an order that has any, its fulfillment
 * orders, and upsert them (ShopifyReturnsSync — READ ONLY).
 *
 * When it runs: requested by app/scopes_update once the store granted the scopes ({@link #request}), and once after
 * deploy for every connected store whose stored scopes already grant them (startup; also resumes a 'failed' run or a
 * 'running' one whose heartbeat stopped). No endpoint: the owner can't trigger it.
 *
 * Resumable and safe to re-run: keyset cursor (orders.created_at, id) committed after each batch of 50; every write
 * is an upsert. Rate-limited: one nodes() query per batch through the gateway's throttle-aware transport, plus
 * {@code traced.shopify-returns.backfill.batch-pause-ms} between batches. ACCESS_DENIED → returns access
 * 'not_granted', the run stops as 'failed' and waits for app/scopes_update. One run per store at a time: the
 * claim is a conditional UPDATE to 'running'.
 */
@Component
public class ShopifyReturnsBackfill {

    private static final Logger log = LoggerFactory.getLogger(ShopifyReturnsBackfill.class);

    static final int BATCH = 50;
    static final int STALE_MINUTES = 15;

    private final JdbcTemplate jdbc;
    private final JdbcTemplate ownerJdbc;
    private final TransactionTemplate tx;
    private final ShopifyReturnsSync sync;
    private final ShopifyReturnsGateway gateway;
    private final JobScheduler jobScheduler;
    private final boolean enabled;
    private long batchPauseMs = 250;

    public ShopifyReturnsBackfill(JdbcTemplate jdbc, @FlywayDataSource DataSource ownerDs, PlatformTransactionManager txm,
                                  ShopifyReturnsSync sync, ShopifyReturnsGateway gateway, JobScheduler jobScheduler,
                                  @Value("${traced.shopify-returns.backfill.enabled:true}") boolean enabled) {
        this.jdbc = jdbc;
        this.ownerJdbc = new JdbcTemplate(ownerDs);
        this.tx = new TransactionTemplate(txm);
        this.sync = sync;
        this.gateway = gateway;
        this.jobScheduler = jobScheduler;
        this.enabled = enabled;
    }

    @org.springframework.beans.factory.annotation.Autowired
    public void setBatchPauseMs(@Value("${traced.shopify-returns.backfill.batch-pause-ms:250}") long ms) {
        this.batchPauseMs = ms;
    }

    /** Startup: stores already granted and never backfilled, a failed run, or a stalled one. Fail-soft. */
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        if (!enabled) return;
        try {
            for (Map<String, Object> s : ownerJdbc.queryForList(
                    "SELECT id, tenant_id, access_token_scopes, returns_backfill_status FROM stores " +
                    "WHERE status = 'connected' AND (returns_backfill_status IS NULL OR returns_backfill_status = 'failed' " +
                    "   OR (returns_backfill_status IN ('pending', 'running') " +
                    "       AND COALESCE(returns_backfill_heartbeat_at, returns_backfill_requested_at) < now() - (? * INTERVAL '1 minute'))) " +
                    "ORDER BY id", STALE_MINUTES)) {
                if (!ShopifyReturnsScopes.granted((String) s.get("access_token_scopes"))) continue;
                request((UUID) s.get("tenant_id"), (UUID) s.get("id"));
            }
        } catch (Exception e) {
            log.error("Shopify returns backfill: could not check stores at startup — continuing", e);
        }
    }

    /**
     * Marks the store's backfill pending and enqueues it, unless one is already pending / running (and alive). A store
     * whose last run is 'done' starts again from the beginning (safe: upserts). Never throws.
     */
    public boolean request(UUID tenantId, UUID storeId) {
        try {
            int n = TenantContext.runAs(tenantId, () -> tx.execute(s -> jdbc.update(
                "UPDATE stores SET returns_backfill_status = 'pending', returns_backfill_requested_at = now(), " +
                "  returns_backfill_heartbeat_at = NULL, returns_backfill_error = NULL, " +
                "  returns_backfill_cursor = CASE WHEN returns_backfill_status = 'done' THEN NULL ELSE returns_backfill_cursor END " +
                "WHERE id = ? AND tenant_id = ? AND status = 'connected' " +
                "  AND (returns_backfill_status IS NULL OR returns_backfill_status IN ('done', 'failed') " +
                "       OR COALESCE(returns_backfill_heartbeat_at, returns_backfill_requested_at) < now() - (? * INTERVAL '1 minute'))",
                storeId, tenantId, STALE_MINUTES)));
            if (n == 0) return false;
            jobScheduler.<ShopifyReturnsBackfill>enqueue(job -> job.run(storeId, tenantId));
            log.info("Shopify returns backfill requested for store {}", storeId);
            return true;
        } catch (RuntimeException e) {
            log.warn("Shopify returns backfill: could not request store {}: {}", storeId, e.getMessage());
            return false;
        }
    }

    @Job(name = "Shopify returns backfill — store %0", retries = 0)
    public void run(UUID storeId, UUID tenantId) {
        Map<String, Object> claimed = TenantContext.runAs(tenantId, () -> tx.execute(s -> {
            List<Map<String, Object>> r = jdbc.queryForList(
                "UPDATE stores SET returns_backfill_status = 'running', returns_backfill_heartbeat_at = now() " +
                "WHERE id = ? AND tenant_id = ? AND status = 'connected' AND (returns_backfill_status = 'pending' " +
                "   OR (returns_backfill_status = 'running' AND returns_backfill_heartbeat_at < now() - (? * INTERVAL '1 minute'))) " +
                "RETURNING shop_domain, returns_backfill_cursor", storeId, tenantId, STALE_MINUTES);
            return r.isEmpty() ? null : r.get(0);
        }));
        if (claimed == null) {
            log.info("Shopify returns backfill for store {}: not pending (already running, done or disconnected) — nothing to do", storeId);
            return;
        }
        String shop = (String) claimed.get("shop_domain");
        String cursor = (String) claimed.get("returns_backfill_cursor");
        int orders = 0, withReturns = 0;
        try {
            String token = sync.token(tenantId, storeId);
            if (token == null) { finish(tenantId, storeId, "failed", "no usable token"); return; }
            while (true) {
                List<Map<String, Object>> page = page(tenantId, storeId, cursor);
                if (page.isEmpty()) break;
                List<String> gids = new ArrayList<>();
                for (Map<String, Object> o : page) gids.add((String) o.get("external_id"));
                Map<String, List<String>> returns;
                try {
                    returns = gateway.fetchOrderReturnIds(shop, token, gids);
                } catch (ShopifyReturnsAccessDeniedException e) {
                    sync.denied(tenantId, storeId, shop);
                    finish(tenantId, storeId, "failed", "returns access not granted");
                    return;
                }
                for (String gid : gids) {
                    List<String> r = returns.getOrDefault(gid, List.of());
                    if (r.isEmpty()) continue;
                    withReturns++;
                    if (sync.syncOrder(tenantId, storeId, shop, token, gid, r) == ShopifyReturnsSync.Outcome.ACCESS_DENIED) {
                        finish(tenantId, storeId, "failed", "returns access not granted");
                        return;
                    }
                }
                orders += page.size();
                Map<String, Object> last = page.get(page.size() - 1);
                cursor = ((Timestamp) last.get("created_at")).toInstant() + "|" + last.get("id");
                String c = cursor;
                TenantContext.runAs(tenantId, () -> tx.execute(s -> jdbc.update(
                    "UPDATE stores SET returns_backfill_cursor = ?, returns_backfill_heartbeat_at = now() WHERE id = ? AND tenant_id = ?",
                    c, storeId, tenantId)));
                if (page.size() < BATCH) break;
                pause();
            }
            finish(tenantId, storeId, "done", null);
            log.info("Shopify returns backfill for store {} ({}): done — {} orders checked, {} with returns", storeId, shop,
                orders, withReturns);
        } catch (RuntimeException e) {
            log.warn("Shopify returns backfill for store {} ({}) failed after {} orders — resumes from its cursor at the next " +
                "startup or scopes grant: {}", storeId, shop, orders, e.toString());
            finish(tenantId, storeId, "failed", e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private List<Map<String, Object>> page(UUID tenantId, UUID storeId, String cursor) {
        Timestamp after = null;
        UUID afterId = null;
        if (cursor != null && cursor.contains("|")) {
            after = Timestamp.from(java.time.Instant.parse(cursor.substring(0, cursor.indexOf('|'))));
            afterId = UUID.fromString(cursor.substring(cursor.indexOf('|') + 1));
        }
        Timestamp a = after;
        UUID aId = afterId;
        return TenantContext.runAs(tenantId, () -> tx.execute(s -> jdbc.queryForList(
            "SELECT id, external_id, created_at FROM merchant_orders " +
            "WHERE tenant_id = ? AND store_id = ? AND external_id LIKE 'gid://shopify/Order/%' " +
            "  AND (?::timestamptz IS NULL OR (created_at, id) > (?::timestamptz, ?::uuid)) " +
            "ORDER BY created_at, id LIMIT " + BATCH,
            tenantId, storeId, a, a, aId)));
    }

    private void finish(UUID tenantId, UUID storeId, String status, String error) {
        String err = error == null ? null : error.length() > 500 ? error.substring(0, 500) : error;
        TenantContext.runAs(tenantId, () -> tx.execute(s -> jdbc.update(
            "UPDATE stores SET returns_backfill_status = ?, returns_backfill_error = ?, returns_backfill_heartbeat_at = now(), " +
            "  returns_backfill_done_at = CASE WHEN ? = 'done' THEN now() ELSE returns_backfill_done_at END " +
            "WHERE id = ? AND tenant_id = ?", status, err, status, storeId, tenantId)));
    }

    private void pause() {
        if (batchPauseMs <= 0) return;
        try {
            Thread.sleep(batchPauseMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
