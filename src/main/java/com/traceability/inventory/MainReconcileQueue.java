package com.traceability.inventory;

import com.fasterxml.jackson.databind.JsonNode;
import com.traceability.tenancy.TenantContext;
import org.jobrunr.jobs.annotations.Job;
import org.jobrunr.scheduling.JobScheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Main reconcile (V201, R1) — the per-variant trigger from Shopify webhooks: orders/updated, orders/cancelled and
 * inventory_levels/update (Main location only) queue every variant they name on main_reconcile_variant_state and stamp
 * last_event_at — the two-run rule's "no Shopify event in between". Traced's own claims queue their variants through
 * the V201 triggers on the claim tables (every return restock and restock skip included). Debounced by MainReconcileJob.
 * Never throws: a failure here must not fail the webhook.
 *
 * <p>2026-11-15 (prod deadlocks + 48 s waits behind a reconcile): the mark locks its rows in ascending variant_id (the
 * same order as every other writer — {@link MainReconcileService#VARIANT_ORDER}), and never waits more than
 * {@code traced.main-reconcile.mark-lock-timeout-ms} (2 s) for a row lock. A mark that times out (or is picked as a
 * deadlock victim) is retried later as its own job ({@link #retryMark}, up to {@link #MAX_RETRIES} times, 15 s apart and
 * growing) with the ORIGINAL event time — so the two-run rule still sees "a Shopify event at that time" once the retry
 * lands. The webhook event itself is processed normally either way.
 */
@Component
public class MainReconcileQueue {

    private static final Logger log = LoggerFactory.getLogger(MainReconcileQueue.class);

    /** ORDER BY v.id: rows are inserted / locked in ascending variant_id (the lock order). */
    static final String MARK_SQL =
        "INSERT INTO main_reconcile_variant_state AS s (tenant_id, variant_id, pending_since, pending_touched_at, " +
        "  pending_source, last_event_at, last_event_source) " +
        "SELECT v.tenant_id, v.id, now(), now(), ?, ?::timestamptz, ? FROM variants v WHERE v.tenant_id = ? AND %s " +
        "ORDER BY v.id " +
        "ON CONFLICT (tenant_id, variant_id) DO UPDATE SET " +
        "  pending_since = COALESCE(s.pending_since, now()), pending_touched_at = now(), " +
        "  pending_source = EXCLUDED.pending_source, " +
        "  last_event_at = GREATEST(COALESCE(s.last_event_at, EXCLUDED.last_event_at), EXCLUDED.last_event_at), " +
        "  last_event_source = EXCLUDED.last_event_source";

    static final String INVENTORY_PREDICATE =
        "v.shopify_inventory_item_id = 'gid://shopify/InventoryItem/' || ? " +
        "AND EXISTS (SELECT 1 FROM locations l WHERE l.tenant_id = v.tenant_id AND l.is_fulfillment = true " +
        "            AND l.shopify_location_id = 'gid://shopify/Location/' || ?)";
    static final String ORDER_PREDICATE = "v.external_id = ANY(CAST(? AS text[]))";

    /**
     * U1a (2026-11-22): a Traced-side change Main reconcile must look at within a minute — a Keep-mode bazar's sale,
     * undo, return, exchange, lost or close (BazarKeepModeQueue). Same queue row and lock order as MARK_SQL, but NOT a
     * Shopify event: last_event_at is left alone (the two-run rule's "no Shopify event in between" stays Shopify's).
     */
    static final String TRACED_MARK_SQL =
        "INSERT INTO main_reconcile_variant_state AS s (tenant_id, variant_id, pending_since, pending_touched_at, " +
        "  pending_source) " +
        "SELECT v.tenant_id, v.id, now(), now(), ? FROM variants v WHERE v.tenant_id = ? AND v.id = ANY(CAST(? AS uuid[])) " +
        "ORDER BY v.id " +
        "ON CONFLICT (tenant_id, variant_id) DO UPDATE SET " +
        "  pending_since = COALESCE(s.pending_since, now()), pending_touched_at = now(), " +
        "  pending_source = EXCLUDED.pending_source";

    static final int MAX_RETRIES = 5;
    static final String KIND_INVENTORY = "inventory";
    static final String KIND_ORDER = "order";
    static final String KIND_TRACED = "traced";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    /** U1a: the Traced mark runs from afterCommit, where a REQUIRED template would join the finished transaction. */
    private final TransactionTemplate txNew;
    private JobScheduler jobScheduler;
    private long lockTimeoutMs = 2000;
    private long retryBaseSeconds = 15;

    public MainReconcileQueue(JdbcTemplate jdbc, PlatformTransactionManager txm) {
        this.jdbc = jdbc;
        this.tx   = new TransactionTemplate(txm);
        this.txNew = new TransactionTemplate(txm);
        this.txNew.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Autowired(required = false)
    public void setJobScheduler(JobScheduler jobScheduler) { this.jobScheduler = jobScheduler; }

    @Autowired
    public void setLockTimeoutMs(@Value("${traced.main-reconcile.mark-lock-timeout-ms:2000}") long ms) { this.lockTimeoutMs = ms; }

    @Autowired
    public void setRetryBaseSeconds(@Value("${traced.main-reconcile.mark-retry-base-seconds:15}") long s) { this.retryBaseSeconds = s; }

    /** Called by the webhook processor after the topic's own handler, under the tenant's context. */
    public void onWebhook(UUID tenantId, String topic, JsonNode payload) {
        try {
            switch (topic) {
                case "inventory_levels/update" -> {
                    String item = numeric(payload.path("inventory_item_id").asText(null));
                    String location = numeric(payload.path("location_id").asText(null));
                    if (item == null || location == null) return;
                    markOrRetry(tenantId, topic, KIND_INVENTORY, List.of(item, location), Instant.now(), 0);
                }
                case "orders/updated", "orders/cancelled" -> {
                    Set<String> gids = variantGids(payload);
                    if (gids.isEmpty()) return;
                    markOrRetry(tenantId, topic, KIND_ORDER, new ArrayList<>(gids), Instant.now(), 0);
                }
                default -> { }
            }
        } catch (RuntimeException e) {
            log.warn("Main reconcile queue: {} for tenant {} not queued: {}", topic, tenantId, e.toString());
        }
    }

    /**
     * U1a: queue these variants for Main reconcile because Traced changed them ({@code source}, e.g. "bazar:sale").
     * Inside a transaction the mark runs after it commits (its own short transaction, the 2 s lock_timeout, the retry
     * job on a lock timeout) — never inside the caller's, so a sale never waits on a reconcile and never fails because
     * of the queue; a rolled-back change queues nothing. Outside a transaction it runs now. Never throws.
     */
    public void onTracedChange(UUID tenantId, java.util.Collection<UUID> variantIds, String source) {
        if (tenantId == null || variantIds == null || variantIds.isEmpty()) return;
        List<String> ids = variantIds.stream().filter(java.util.Objects::nonNull).map(UUID::toString)
            .distinct().sorted().toList();
        if (ids.isEmpty()) return;
        Runnable mark = () -> {
            try {
                TenantContext.runAs(tenantId, (Runnable) () -> markOrRetry(tenantId, source, KIND_TRACED, ids, Instant.now(), 0));
            } catch (RuntimeException e) {
                log.warn("Main reconcile queue: {} for tenant {} not queued: {}", source, tenantId, e.toString());
            }
        };
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                new org.springframework.transaction.support.TransactionSynchronization() {
                    @Override public void afterCommit() { mark.run(); }
                });
        } else {
            mark.run();
        }
    }

    /** The retry of a mark that could not get its row locks in time. Its own job, under the tenant's context. */
    @Job(name = "Main reconcile — queue mark retry", retries = 0)
    public void retryMark(UUID tenantId, String topic, String kind, List<String> args, Instant eventAt, int attempt) {
        try {
            TenantContext.runAs(tenantId, (Runnable) () -> markOrRetry(tenantId, topic, kind, args, eventAt, attempt));
        } catch (RuntimeException e) {
            log.warn("Main reconcile queue: retry {} of {} for tenant {} failed: {}", attempt, topic, tenantId, e.toString());
        }
    }

    /** One attempt: the mark with a short lock_timeout; on a DB error, the next attempt scheduled (never thrown). */
    void markOrRetry(UUID tenantId, String topic, String kind, List<String> args, Instant eventAt, int attempt) {
        try {
            mark(tenantId, topic, kind, args, eventAt);
            if (attempt > 0) log.info("Main reconcile queue: {} for tenant {} queued on retry {}", topic, tenantId, attempt);
        } catch (DataAccessException e) {
            int next = attempt + 1;
            if (next > MAX_RETRIES || jobScheduler == null) {
                log.warn("Main reconcile queue: {} for tenant {} NOT queued after {} attempt(s) (event at {}): {}",
                    topic, tenantId, attempt + 1, eventAt, e.toString());
                return;
            }
            Instant at = Instant.now().plusSeconds(retryBaseSeconds * next);
            log.info("Main reconcile queue: {} for tenant {} could not lock its rows in {} ms ({}) — retry {} at {}",
                topic, tenantId, lockTimeoutMs, e.getClass().getSimpleName(), next, at);
            List<String> a = new ArrayList<>(args);
            jobScheduler.<MainReconcileQueue>schedule(at, q -> q.retryMark(tenantId, topic, kind, a, eventAt, next));
        }
    }

    private void mark(UUID tenantId, String topic, String kind, List<String> args, Instant eventAt) {
        Timestamp at = Timestamp.from(eventAt);
        (KIND_TRACED.equals(kind) ? txNew : tx).executeWithoutResult(s -> {
            jdbc.execute("SET LOCAL lock_timeout = '" + Math.max(1, lockTimeoutMs) + "ms'");
            if (KIND_TRACED.equals(kind)) {
                jdbc.update(TRACED_MARK_SQL, topic, tenantId, MainReconcileService.uuidArray(
                    args.stream().map(UUID::fromString).toList()));
            } else if (KIND_INVENTORY.equals(kind)) {
                jdbc.update(MARK_SQL.formatted(INVENTORY_PREDICATE), "webhook:" + topic, at, topic, tenantId,
                    args.get(0), args.get(1));
            } else {
                jdbc.update(MARK_SQL.formatted(ORDER_PREDICATE), "webhook:" + topic, at, topic, tenantId,
                    MainReconcileService.textArray(args));
            }
        });
    }

    /** Every variant an order payload names: its lines, its fulfillments' lines, its refunds' lines. */
    static Set<String> variantGids(JsonNode order) {
        Set<String> out = new LinkedHashSet<>();
        for (JsonNode li : order.path("line_items")) add(out, li.path("variant_id"));
        for (JsonNode f : order.path("fulfillments")) for (JsonNode li : f.path("line_items")) add(out, li.path("variant_id"));
        for (JsonNode r : order.path("refunds"))
            for (JsonNode rli : r.path("refund_line_items")) add(out, rli.path("line_item").path("variant_id"));
        return out;
    }

    private static void add(Set<String> out, JsonNode id) {
        String n = numeric(id.isNull() || id.isMissingNode() ? null : id.asText(null));
        if (n != null) out.add("gid://shopify/ProductVariant/" + n);
    }

    static String numeric(String s) {
        if (s == null || s.isBlank()) return null;
        String n = s.replaceAll("^.*/", "");
        return n.matches("[0-9]+") ? n : null;
    }
}
