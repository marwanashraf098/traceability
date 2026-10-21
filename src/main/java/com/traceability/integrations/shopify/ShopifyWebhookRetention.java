package com.traceability.integrations.shopify;

import com.traceability.tenancy.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.flyway.FlywayDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Retention for shopify_webhook_events (write-churn slice B, 2026-10-21). Nothing ever deleted these rows: ~6k a day,
 * ~25 MB, almost all of it payload_raw in TOAST.
 *
 * <p>Deletes only PROCESSED rows (processed_at set — superseded ones included) received before the tenant's cutoff:
 * <pre>
 *   cutoff = LEAST(now() - days, the oldest received_at of the tenant's rows that can still run)
 * </pre>
 * A pending, retry-due or failed row is never deleted, at any age. "Can still run" is processed_at IS NULL, except
 * an exhausted failure (process_error set, next_retry_at NULL) received more than {@link #EXHAUSTED_IGNORED_DAYS}
 * days ago — one dead row must not freeze a tenant's cutoff forever. Why the cutoff: the ordering check
 * ({@link ShopifyWebhookProcessorJob#supersededReason}) looks for a LATER processed event for the same object, with
 * no lower time bound, and the manual reprocess can run an old event at any age — so a processed row newer than
 * any event that can still run must stay. Rows older than every runnable event can never supersede one.
 *
 * <p>Why {@code days} ≥ {@link #MIN_DAYS}: the insert's dedupe is ON CONFLICT (webhook_id) against the stored row,
 * and Shopify redelivers a webhook for up to 48 h. A smaller value refuses to run (logged), deleting nothing.
 *
 * <p>Mechanics: the tenant list is a cross-tenant read of tenant ids only, on the owner pool (the
 * RefundDetailsPurgeJob pattern); every read and delete then runs per tenant in TenantContext.runAs under RLS.
 * The ids are collected first (oldest first), then deleted by primary key in batches of {@code batchSize}, one short
 * transaction per batch with a pause between, at most {@code maxPerRun} rows per run — the backlog clears over
 * several nights instead of one long transaction. customer_data_requests.webhook_event_id has no FK on purpose
 * (V143): a data request outlives its event, and the GDPR export / redact find webhook rows by order or customer,
 * never by that id. Logs counts only.
 */
@Service
public class ShopifyWebhookRetention {

    private static final Logger log = LoggerFactory.getLogger(ShopifyWebhookRetention.class);

    public static final int MIN_DAYS = 3;
    public static final int EXHAUSTED_IGNORED_DAYS = 30;

    /** The tenant's ids that may go, oldest first. Args: tenant, days, tenant, limit. */
    static final String QUALIFYING_IDS =
        "SELECT id FROM shopify_webhook_events " +
        "WHERE tenant_id = ? AND processed_at IS NOT NULL " +
        "  AND received_at < LEAST(now() - (? * INTERVAL '1 day'), COALESCE((" +
        "        SELECT min(r.received_at) FROM shopify_webhook_events r " +
        "        WHERE r.tenant_id = ? AND r.processed_at IS NULL " +
        "          AND NOT (r.process_error IS NOT NULL AND r.next_retry_at IS NULL " +
        "                   AND r.received_at < now() - INTERVAL '" + EXHAUSTED_IGNORED_DAYS + " days')" +
        "      ), 'infinity'::timestamptz)) " +
        "ORDER BY received_at " +
        "LIMIT ?";

    public record Result(boolean refused, int total, Map<UUID, Integer> perTenant) {}

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final JdbcTemplate ownerJdbc;
    private final int days, batchSize, maxPerRun;
    private final long pauseMs;

    @Autowired
    public ShopifyWebhookRetention(JdbcTemplate jdbc,
                                   PlatformTransactionManager txm,
                                   @FlywayDataSource DataSource ownerDs,
                                   @Value("${traced.webhook-retention.days:7}") int days,
                                   @Value("${traced.webhook-retention.batch-size:1000}") int batchSize,
                                   @Value("${traced.webhook-retention.max-per-run:20000}") int maxPerRun,
                                   @Value("${traced.webhook-retention.pause-ms:200}") long pauseMs) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txm);
        this.ownerJdbc = new JdbcTemplate(ownerDs);
        this.days = days;
        this.batchSize = Math.max(1, batchSize);
        this.maxPerRun = Math.max(0, maxPerRun);
        this.pauseMs = Math.max(0, pauseMs);
    }

    public Result run() {
        if (days < MIN_DAYS) {
            log.error("Shopify webhook retention refused: traced.webhook-retention.days={} is under {} " +
                "(Shopify redelivers for up to 48 h; the dedupe needs the row) — nothing deleted", days, MIN_DAYS);
            return new Result(true, 0, Map.of());
        }
        List<UUID> tenants = ownerJdbc.queryForList(
            "SELECT DISTINCT tenant_id FROM shopify_webhook_events " +
            "WHERE processed_at IS NOT NULL AND received_at < now() - (? * INTERVAL '1 day')", UUID.class, days);
        Map<UUID, Integer> perTenant = new LinkedHashMap<>();
        int total = 0;
        for (UUID tenantId : tenants) {
            if (total >= maxPerRun) break;
            try {
                int n = purgeTenant(tenantId, maxPerRun - total);
                total += n;
                if (n > 0) {
                    perTenant.put(tenantId, n);
                    log.info("Shopify webhook retention tenant={} deleted={}", tenantId, n);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.warn("Shopify webhook retention failed for tenant {}: {}", tenantId, e.getClass().getSimpleName());
            }
        }
        log.info("Shopify webhook retention run: deleted={} tenants={} (keep {} days, cap {} per run{})",
            total, perTenant.size(), days, maxPerRun, total >= maxPerRun ? " — cap reached, the rest next run" : "");
        return new Result(false, total, perTenant);
    }

    private int purgeTenant(UUID tenantId, int limit) throws InterruptedException {
        List<UUID> ids = TenantContext.runAs(tenantId, () -> tx.execute(s ->
            jdbc.queryForList(QUALIFYING_IDS, UUID.class, tenantId, days, tenantId, limit)));
        if (ids == null || ids.isEmpty()) return 0;
        int deleted = 0;
        for (int from = 0; from < ids.size(); from += batchSize) {
            if (from > 0 && pauseMs > 0) Thread.sleep(pauseMs);
            UUID[] batch = ids.subList(from, Math.min(ids.size(), from + batchSize)).toArray(new UUID[0]);
            Integer n = TenantContext.runAs(tenantId, () -> tx.execute(s -> jdbc.update(
                "DELETE FROM shopify_webhook_events WHERE tenant_id = ? AND id = ANY(?::uuid[]) AND processed_at IS NOT NULL",
                tenantId, batch)));
            deleted += n == null ? 0 : n;
        }
        return deleted;
    }
}
