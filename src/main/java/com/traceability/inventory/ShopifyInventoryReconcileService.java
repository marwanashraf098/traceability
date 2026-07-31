package com.traceability.inventory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.traceability.account.AuditService;
import com.traceability.integrations.shopify.ShopifyException;
import com.traceability.integrations.shopify.ShopifyGateway;
import com.traceability.integrations.shopify.ShopifyTokenProvider;
import com.traceability.tenancy.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Part C — reconcile-then-write initial seed of the (empty) Traced Main Warehouse.
 *
 * reconcile() is read-only: computes Traced's on_hand per variant, diffs against Shopify's
 * current "available" at the Traced GID, and returns the report. Nothing is written.
 *
 * apply() recomputes the same diff live (never trusts a client-supplied report — Shopify
 * state may have changed) and writes ONLY the positive-delta rows via
 * inventoryAdjustQuantities. FR-17 v2 guard: a variant already non-zero in Shopify at the
 * Traced location is skipped and flagged for manual reconcile, never auto-corrected —
 * this also makes a re-run after a successful seed a no-op (the seeded variant is now
 * non-zero, so the next reconcile flags it instead of double-adding).
 */
@Service
public class ShopifyInventoryReconcileService {

    private static final Logger log = LoggerFactory.getLogger(ShopifyInventoryReconcileService.class);

    public static final String ACTION_SEED         = "seed";
    public static final String ACTION_SKIP_NONZERO = "skip_nonzero";
    public static final String ACTION_NOOP         = "noop";

    public record VariantReconcileRow(
        UUID variantId, String sku, String title,
        long tracedOnHand, int shopifyAvailable, String action) {}

    public record ReconcileReport(String tracedLocationGid, List<VariantReconcileRow> rows) {}

    public record ApplyResult(int seeded, int skippedNonZero, int noop, int failed,
                               List<Map<String, String>> failures) {}

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ShopifyGateway shopify;
    private final ShopifyTokenProvider tokenProvider;
    private final ObjectMapper mapper;
    private final AuditService auditService;

    public ShopifyInventoryReconcileService(JdbcTemplate jdbc, PlatformTransactionManager txm,
                                             ShopifyGateway shopify, ShopifyTokenProvider tokenProvider,
                                             ObjectMapper mapper, AuditService auditService) {
        this.jdbc          = jdbc;
        this.tx            = new TransactionTemplate(txm);
        this.shopify       = shopify;
        this.tokenProvider = tokenProvider;
        this.mapper        = mapper;
        this.auditService  = auditService;
    }

    private record Context(UUID storeId, String shopDomain, UUID tracedLocationId, String tracedGid, String token) {}

    private Context resolveContext(UUID tenantId) {
        record StoreSnap(UUID id, String shopDomain) {}
        StoreSnap store = tx.execute(s -> jdbc.query(
            "SELECT id, shop_domain FROM stores WHERE tenant_id = ? LIMIT 1",
            rs -> rs.next() ? new StoreSnap(rs.getObject(1, UUID.class), rs.getString(2)) : null,
            tenantId));
        if (store == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "No Shopify store connected");
        }

        record LocSnap(UUID id, String gid) {}
        LocSnap loc = tx.execute(s -> jdbc.query(
            "SELECT id, shopify_location_id FROM locations " +
            "WHERE tenant_id = ? AND is_fulfillment = true AND shopify_sync_status = 'linked' LIMIT 1",
            rs -> rs.next() ? new LocSnap(rs.getObject(1, UUID.class), rs.getString(2)) : null,
            tenantId));
        if (loc == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Traced Main Warehouse is not linked to Shopify yet");
        }

        String token = tokenProvider.getValidToken(store.id());
        return new Context(store.id(), store.shopDomain(), loc.id(), loc.gid(), token);
    }

    // ---- read-only report ----------------------------------------------

    public ReconcileReport reconcile() {
        UUID tenantId = TenantContext.require();
        Context ctx = resolveContext(tenantId);
        return buildReport(tenantId, ctx);
    }

    private ReconcileReport buildReport(UUID tenantId, Context ctx) {
        // Traced on_hand per variant — pieces present and sellable, scoped to is_fulfillment=true
        // locations (same formula as CatalogController.list()'s on_hand(V)).
        Map<UUID, Long> tracedOnHand = new HashMap<>();
        tx.execute(s -> {
            jdbc.query(
                "SELECT p.variant_id, COUNT(*) AS on_hand " +
                "FROM pieces p " +
                "WHERE p.tenant_id = ? " +
                "  AND p.status IN ('available','reserved','packed','awaiting_pickup') " +
                "  AND p.current_location_id IN (" +
                "      SELECT id FROM locations WHERE tenant_id = ? AND is_fulfillment = true)" +
                "GROUP BY p.variant_id",
                (org.springframework.jdbc.core.RowCallbackHandler) rs ->
                    tracedOnHand.put(rs.getObject("variant_id", UUID.class), rs.getLong("on_hand")),
                tenantId, tenantId);
            return null;
        });

        List<Map<String, Object>> variants = tx.execute(s -> jdbc.queryForList(
            "SELECT id, external_id, sku, title FROM variants WHERE tenant_id = ?", tenantId));

        // Resolve each variant's inventoryItem GID, then batch-read Shopify's current
        // "available" at the Traced location for all of them in one call.
        Map<UUID, String> variantToItemGid = new LinkedHashMap<>();
        for (Map<String, Object> v : variants) {
            UUID variantId = (UUID) v.get("id");
            String variantGid = (String) v.get("external_id");
            try {
                variantToItemGid.put(variantId,
                    shopify.resolveInventoryItemId(ctx.shopDomain(), ctx.token(), variantGid));
            } catch (ShopifyException e) {
                log.warn("Reconcile: could not resolve inventoryItem for variant={} error={}", variantId, e.getMessage());
            }
        }

        Map<String, Integer> availableByItemGid = new HashMap<>();
        List<ShopifyGateway.InventoryLevel> levels = shopify.fetchAvailableQuantities(
            ctx.shopDomain(), ctx.token(), ctx.tracedGid(), new ArrayList<>(variantToItemGid.values()));
        for (ShopifyGateway.InventoryLevel level : levels) {
            availableByItemGid.put(level.inventoryItemGid(), level.available());
        }

        List<VariantReconcileRow> rows = new ArrayList<>();
        for (Map<String, Object> v : variants) {
            UUID variantId = (UUID) v.get("id");
            long onHand = tracedOnHand.getOrDefault(variantId, 0L);
            String itemGid = variantToItemGid.get(variantId);
            int available = itemGid != null ? availableByItemGid.getOrDefault(itemGid, 0) : 0;

            String action;
            if (available != 0) {
                // FR-17 v2 guard: never auto-correct a non-zero Shopify value, up or down.
                action = ACTION_SKIP_NONZERO;
            } else if (onHand > 0) {
                action = ACTION_SEED;
            } else {
                action = ACTION_NOOP;
            }

            rows.add(new VariantReconcileRow(
                variantId, (String) v.get("sku"), (String) v.get("title"), onHand, available, action));
        }

        return new ReconcileReport(ctx.tracedGid(), rows);
    }

    // ---- guarded write ---------------------------------------------------

    /**
     * Serialized per tenant via pg_advisory_xact_lock, held for the ENTIRE operation
     * (recompute + every per-variant Shopify write) — deliberately different from Part D's
     * triggers, which release their DB transaction before the Shopify HTTP call. apply() is
     * a manual, one-shot, one-tenant-at-a-time operator action (never a hot path), so tying
     * up one connection for its duration is the right trade to make two operators calling
     * apply() for the same tenant at the same moment impossible rather than merely unlikely.
     * A second concurrent apply() blocks on the lock until the first's transaction commits,
     * then recomputes and sees the now-non-zero Shopify values via the existing
     * ACTION_SKIP_NONZERO guard — never a double-add.
     *
     * Trade-off, stated explicitly: wrapping the whole batch in one transaction means an
     * unexpected exception escaping the per-variant try/catch below (not an ordinary Shopify
     * failure — those are caught and recorded per-variant without aborting) rolls back the
     * WHOLE transaction, including audit rows for variants that already succeeded earlier in
     * the same batch. This does NOT create a double-add risk: a subsequent apply() re-reads
     * Shopify's actual live state, which is unaffected by our rolled-back local transaction,
     * and correctly skips those variants via ACTION_SKIP_NONZERO. It only means the local
     * audit trail could be incomplete for that one crashed run — a smaller, more localized
     * concession than the double-add bug this whole fix removes.
     */
    public ApplyResult apply(UUID actorUserId) {
        UUID tenantId = TenantContext.require();
        return tx.execute(outerStatus -> {
            acquireTenantLock(tenantId);

            Context ctx = resolveContext(tenantId);
            // Recompute live — never trust a stale client-held report for a write decision.
            ReconcileReport report = buildReport(tenantId, ctx);

            int seeded = 0, skippedNonZero = 0, noop = 0, failed = 0;
            List<Map<String, String>> failures = new ArrayList<>();

            for (VariantReconcileRow row : report.rows()) {
                switch (row.action()) {
                    case ACTION_SKIP_NONZERO -> skippedNonZero++;
                    case ACTION_NOOP -> noop++;
                    case ACTION_SEED -> {
                        try {
                            // action=seed implies shopifyAvailable==0, so this call is always a
                            // strictly-positive delta from 0 — never a decrement.
                            String variantGid = jdbc.query(
                                "SELECT external_id FROM variants WHERE id = ? AND tenant_id = ?",
                                rs -> rs.next() ? rs.getString(1) : null,
                                row.variantId(), tenantId);
                            String itemGid = shopify.resolveInventoryItemId(ctx.shopDomain(), ctx.token(), variantGid);
                            String idempotencyKey = ShopifyGateway.idempotencyKey(tenantId, "initial_seed",
                                row.variantId().toString(), row.variantId(), ctx.tracedLocationId());
                            shopify.adjustInventoryQuantities(ctx.shopDomain(), ctx.token(), itemGid,
                                ctx.tracedGid(), (int) row.tracedOnHand(), "correction", idempotencyKey);
                            recordAudit(tenantId, row.variantId(), ctx.tracedLocationId(), row.tracedOnHand(),
                                "applied", null);
                            seeded++;
                        } catch (Exception e) {
                            String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                            recordAudit(tenantId, row.variantId(), ctx.tracedLocationId(), row.tracedOnHand(),
                                "failed", msg);
                            Map<String, String> failure = new LinkedHashMap<>();
                            failure.put("variantId", row.variantId().toString());
                            failure.put("error", msg);
                            failures.add(failure);
                            failed++;
                            log.warn("Initial seed failed: tenant={} variant={} error={}", tenantId, row.variantId(), msg);
                        }
                    }
                    default -> throw new IllegalStateException("Unknown reconcile action: " + row.action());
                }
            }

            log.info("Initial seed applied: tenant={} seeded={} skippedNonZero={} noop={} failed={}",
                tenantId, seeded, skippedNonZero, noop, failed);

            auditService.record(actorUserId, "shopify_inventory_initial_seed", "location",
                ctx.tracedLocationId().toString(),
                Map.of("seeded", seeded, "skippedNonZero", skippedNonZero, "noop", noop, "failed", failed));

            return new ApplyResult(seeded, skippedNonZero, noop, failed, failures);
        });
    }

    /** pg_advisory_xact_lock is transaction-scoped — automatically released when the
     *  surrounding tx.execute(...) transaction commits or rolls back, no explicit unlock. */
    private void acquireTenantLock(UUID tenantId) {
        jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Void>) con -> {
            try (var ps = con.prepareStatement("SELECT pg_advisory_xact_lock(hashtext(?)::bigint)")) {
                ps.setString(1, tenantId.toString());
                ps.execute();
            }
            return null;
        });
    }

    /**
     * Records the outcome of one variant's seed attempt. Two-phase-consistent with Part D's
     * claim()/markResult() pattern: ON CONFLICT DO UPDATE ... WHERE status='failed' means a
     * prior failure IS overwritten by a later outcome (including a successful retry moving
     * failed -> applied), but an already-'applied' row is never touched again — which matches
     * reality anyway, since a variant that's already applied shows non-zero in Shopify and
     * buildReport() will never re-classify it as ACTION_SEED, so recordAudit() is never called
     * again for that key once it reaches 'applied'.
     *
     * The prior version was a plain INSERT ... ON CONFLICT DO NOTHING: a variant that failed
     * once and then succeeded on a later reconnect would silently stay recorded as 'failed'
     * forever — the underlying Shopify write and Traced on_hand were correct (buildReport()'s
     * live-state check already prevents any double-add), but the audit trail lied. Fixed
     * 2026-07-30; no change to the seed decision logic itself.
     */
    private void recordAudit(UUID tenantId, UUID variantId, UUID locationId, long delta,
                              String status, String error) {
        ObjectNode payload = mapper.createObjectNode().put("reason", "initial_seed").put("delta", delta);
        String payloadJson;
        try { payloadJson = mapper.writeValueAsString(payload); }
        catch (Exception e) { payloadJson = "{}"; }
        final String finalPayload = payloadJson;
        final UUID batchId = UUID.randomUUID();

        tx.execute(s -> {
            jdbc.update(
                "INSERT INTO shopify_inventory_adjustments " +
                "(tenant_id, batch_id, variant_id, location_id, delta, trigger_type, trigger_id, " +
                " payload, status, error) " +
                "VALUES (?, ?, ?, ?, ?, 'initial_seed', ?, ?::jsonb, ?, ?) " +
                "ON CONFLICT (trigger_type, trigger_id, variant_id, location_id) DO UPDATE " +
                "  SET status = EXCLUDED.status, error = EXCLUDED.error, " +
                "      batch_id = EXCLUDED.batch_id, payload = EXCLUDED.payload " +
                "  WHERE shopify_inventory_adjustments.status = 'failed'",
                tenantId, batchId, variantId, locationId, delta,
                variantId.toString(), finalPayload, status, error);
            return null;
        });
    }
}
