package com.traceability.inventory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.traceability.account.AuditService;
import com.traceability.integrations.shopify.ShopifyException;
import com.traceability.integrations.shopify.ShopifyGateway;
import com.traceability.integrations.shopify.ShopifyTokenProvider;
import com.traceability.integrations.shopify.StoreRepository;
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

    /** superseded = unapplied increment claims this seed made redundant (see supersedeIncrementClaims). */
    public record ApplyResult(int seeded, int skippedNonZero, int noop, int failed,
                               List<Map<String, String>> failures, int superseded) {}

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ShopifyGateway shopify;
    private final ShopifyTokenProvider tokenProvider;
    private final ObjectMapper mapper;
    private final AuditService auditService;
    private final StoreRepository storeRepository;
    private final InventoryItemIdService itemIds;

    public ShopifyInventoryReconcileService(JdbcTemplate jdbc, PlatformTransactionManager txm,
                                             ShopifyGateway shopify, ShopifyTokenProvider tokenProvider,
                                             ObjectMapper mapper, AuditService auditService,
                                             StoreRepository storeRepository) {
        this.jdbc          = jdbc;
        this.tx            = new TransactionTemplate(txm);
        this.shopify       = shopify;
        this.tokenProvider = tokenProvider;
        this.mapper        = mapper;
        this.auditService  = auditService;
        this.storeRepository = storeRepository;
        this.itemIds       = new InventoryItemIdService(jdbc, txm, shopify);
    }

    private record Context(UUID storeId, String shopDomain, UUID tracedLocationId, String tracedGid, String token) {}

    // FR-3.1 follow-up — StoreRepository.findActiveStoreByTenant() is the single canonical
    // pick shared by every job/service (never a disconnected row).
    private Context resolveContext(UUID tenantId) {
        StoreRepository.Store store = storeRepository.findActiveStoreByTenant(tenantId).orElse(null);
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
        List<Map<String, Object>> variants = loadVariants(tenantId, false);
        return buildReport(tenantId, ctx, variants, resolveItemIds(tenantId, ctx, variants));
    }

    /** Traced on_hand per variant — pieces present and sellable, scoped to is_fulfillment=true
     *  locations (same formula as CatalogController.list()'s on_hand(V)). */
    private Map<UUID, Long> tracedOnHand(UUID tenantId) {
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
        return tracedOnHand;
    }

    /** Every variant (the report), or — for the seed — only the candidates: Traced on_hand > 0 at
     *  the fulfillment location. A variant with on_hand 0 can never be a seed row, so the seed never
     *  reads Shopify for it. */
    private List<Map<String, Object>> loadVariants(UUID tenantId, boolean candidatesOnly) {
        String sql = "SELECT id, external_id, sku, title, shopify_inventory_item_id FROM variants v WHERE tenant_id = ?";
        if (candidatesOnly) {
            sql += " AND EXISTS (SELECT 1 FROM pieces p WHERE p.variant_id = v.id AND p.tenant_id = v.tenant_id" +
                   "  AND p.status IN ('available','reserved','packed','awaiting_pickup')" +
                   "  AND p.current_location_id IN (SELECT id FROM locations WHERE tenant_id = ? AND is_fulfillment = true))";
            String finalSql = sql;
            return tx.execute(s -> jdbc.queryForList(finalSql, tenantId, tenantId));
        }
        String finalSql = sql;
        return tx.execute(s -> jdbc.queryForList(finalSql, tenantId));
    }

    /** Item ids for these variants: stored first, misses through nodes(ids:) and written back. A
     *  failed read leaves those variants unresolved (the report shows them at 0 and the seed records
     *  them as failed — the same outcome the per-variant resolve had). */
    private Map<UUID, String> resolveItemIds(UUID tenantId, Context ctx, List<Map<String, Object>> variants) {
        List<InventoryItemIdService.VariantRef> refs = new ArrayList<>();
        for (Map<String, Object> v : variants) {
            refs.add(new InventoryItemIdService.VariantRef((UUID) v.get("id"),
                (String) v.get("external_id"), (String) v.get("shopify_inventory_item_id")));
        }
        try {
            return itemIds.resolveAll(tenantId, refs, ctx.shopDomain(), ctx.token());
        } catch (ShopifyException e) {
            log.warn("Reconcile: could not resolve inventoryItems tenant={} error={}", tenantId, e.getMessage());
            Map<UUID, String> stored = new LinkedHashMap<>();
            for (InventoryItemIdService.VariantRef r : refs) {
                if (r.inventoryItemGid() != null && !r.inventoryItemGid().isBlank()) stored.put(r.id(), r.inventoryItemGid());
            }
            return stored;
        }
    }

    private ReconcileReport buildReport(UUID tenantId, Context ctx, List<Map<String, Object>> variants,
                                        Map<UUID, String> variantToItemGid) {
        return buildReport(ctx, variants, variantToItemGid, tracedOnHand(tenantId));
    }

    private ReconcileReport buildReport(Context ctx, List<Map<String, Object>> variants,
                                        Map<UUID, String> variantToItemGid, Map<UUID, Long> tracedOnHand) {

        // Batch-read Shopify's current "available" at the Traced location (≤250 ids per read).
        Map<String, Integer> availableByItemGid = new HashMap<>();
        List<String> itemGids = new ArrayList<>();
        for (Map<String, Object> v : variants) {
            String itemGid = variantToItemGid.get((UUID) v.get("id"));
            if (itemGid != null) itemGids.add(itemGid);
        }
        List<ShopifyGateway.InventoryLevel> levels = shopify.fetchAvailableQuantities(
            ctx.shopDomain(), ctx.token(), ctx.tracedGid(), itemGids);
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
     * Serialized per tenant via pg_advisory_xact_lock, held for the compute + write section
     * (the live recompute + every per-variant Shopify write) — deliberately different from Part D's
     * triggers, which release their DB transaction before the Shopify HTTP call. apply() is
     * a manual, one-shot, one-tenant-at-a-time operator action (never a hot path), so tying
     * up one connection for its duration is the right trade to make two operators calling
     * apply() for the same tenant at the same moment impossible rather than merely unlikely.
     * A second concurrent apply() blocks on the lock until the first's transaction commits,
     * then recomputes and sees the now-non-zero Shopify values via the existing
     * ACTION_SKIP_NONZERO guard — never a double-add.
     *
     * Fetch-only preparation runs BEFORE the lock and transaction: the candidate variants (Traced
     * on_hand > 0 — a variant at 0 can never be seeded) and their inventory item ids (stored, or
     * resolved through nodes(ids:) and written back). An item id is a fixed Shopify mapping, so
     * resolving it early changes no write decision. Under the lock the seed recomputes Traced
     * on_hand and reads Shopify's live "available" for the candidates (the double-add guard needs
     * that read under the lock), then writes. A variant that became a candidate after the
     * preparation and has no stored item id is recorded as failed (no Shopify call under the lock);
     * the next run seeds it. Variants that aren't candidates count as noop — they were never
     * written before either.
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
        Context ctx = resolveContext(tenantId);
        Map<UUID, String> preResolved = resolveItemIds(tenantId, ctx, loadVariants(tenantId, true));

        return tx.execute(outerStatus -> {
            acquireTenantLock(tenantId);

            // Recompute live — never trust a stale client-held report for a write decision.
            List<Map<String, Object>> candidates = loadVariants(tenantId, true);
            Map<UUID, String> variantToItemGid = new HashMap<>(preResolved);
            for (Map<String, Object> v : candidates) {
                String stored = (String) v.get("shopify_inventory_item_id");
                if (stored != null && !stored.isBlank()) variantToItemGid.putIfAbsent((UUID) v.get("id"), stored);
            }
            // The on-hand snapshot: the cutoff is read under the lock immediately before Traced on_hand.
            // An increment claim created at or before it is covered by this snapshot (the increment
            // triggers fire after their pieces commit, so a claim never predates its own pieces).
            java.sql.Timestamp snapshotAt = jdbc.queryForObject("SELECT clock_timestamp()", java.sql.Timestamp.class);
            Map<UUID, Long> onHandAtSnapshot = tracedOnHand(tenantId);
            ReconcileReport report = buildReport(ctx, candidates, variantToItemGid, onHandAtSnapshot);

            int seeded = 0, skippedNonZero = 0, failed = 0;
            List<Map<String, String>> failures = new ArrayList<>();
            List<UUID> seededVariants = new ArrayList<>();

            for (VariantReconcileRow row : report.rows()) {
                switch (row.action()) {
                    case ACTION_SKIP_NONZERO -> skippedNonZero++;
                    case ACTION_NOOP -> { /* not reachable for a candidate; counted below */ }
                    case ACTION_SEED -> {
                        try {
                            // action=seed implies shopifyAvailable==0, so this call is always a
                            // strictly-positive delta from 0 — never a decrement.
                            String itemGid = variantToItemGid.get(row.variantId());
                            if (itemGid == null) {
                                throw new ShopifyException("Could not resolve the Shopify inventoryItem for variant "
                                    + row.variantId() + " — it will be seeded on the next run");
                            }
                            // Connect activates ACTIVE products' variants only — a draft/archived
                            // candidate may have no level at the Traced location yet. Same lazy
                            // activation as an increment (no quantity, "already active" tolerated,
                            // same key as the catalog activation); if it fails, no adjust is sent.
                            try {
                                shopify.activateInventoryItem(ctx.shopDomain(), ctx.token(), itemGid, ctx.tracedGid(),
                                    ShopifyCatalogActivationService.activationKey(tenantId, row.variantId(), ctx.tracedGid()));
                            } catch (Exception e) {
                                throw new ShopifyException("Activation at the Traced location failed (adjust not sent): "
                                    + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
                            }
                            String idempotencyKey = ShopifyGateway.idempotencyKey(tenantId, "initial_seed",
                                row.variantId().toString(), row.variantId(), ctx.tracedLocationId());
                            shopify.adjustInventoryQuantities(ctx.shopDomain(), ctx.token(), itemGid,
                                ctx.tracedGid(), (int) row.tracedOnHand(), "correction", idempotencyKey);
                            recordAudit(tenantId, row.variantId(), ctx.tracedLocationId(), row.tracedOnHand(),
                                "applied", null);
                            seededVariants.add(row.variantId());
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
            Integer allVariants = jdbc.queryForObject(
                "SELECT COUNT(*) FROM variants WHERE tenant_id = ?", Integer.class, tenantId);
            int noop = (allVariants == null ? 0 : allVariants) - report.rows().size();

            int superseded = supersedeIncrementClaims(tenantId, ctx.tracedLocationId(), snapshotAt,
                seededVariants, onHandAtSnapshot.keySet());

            log.info("Initial seed applied: tenant={} seeded={} skippedNonZero={} noop={} failed={} superseded={}",
                tenantId, seeded, skippedNonZero, noop, failed, superseded);

            auditService.record(actorUserId, "shopify_inventory_initial_seed", "location",
                ctx.tracedLocationId().toString(),
                Map.of("seeded", seeded, "skippedNonZero", skippedNonZero, "noop", noop, "failed", failed,
                       "superseded", superseded));

            return new ApplyResult(seeded, skippedNonZero, noop, failed, failures, superseded);
        });
    }

    /**
     * Marks 'superseded_by_seed' every increment claim (receiving_session / return_inspection /
     * hold_exit — legacy or not) at the Traced location that never applied ('failed' or 'pending')
     * and was created at or before the on-hand snapshot, for a variant this run either
     *   - SEEDED: Shopify now holds Traced's on-hand, which already counts the claim's units (or
     *     they have since left on-hand) — a retry would double count; or
     *   - had Traced on-hand 0 at the snapshot: the claim's pieces have all left on-hand, so
     *     replaying them would overstate Shopify.
     * skip_nonzero and failed-seed variants are left alone: the seed wrote nothing for them, so
     * their claims are still owed and stay retryable. A claim created after the snapshot is never
     * touched. Runs in the seed's transaction (under its lock), so it commits with the seed's
     * writes or not at all. claim() reclaims only 'failed' rows, so a superseded claim is never
     * retried or repushed; see ShopifyInventoryService.markIncrementResult for an in-flight
     * 'pending' claim whose result arrives later.
     */
    private int supersedeIncrementClaims(UUID tenantId, UUID tracedLocationId, java.sql.Timestamp snapshotAt,
                                         List<UUID> seededVariants, java.util.Set<UUID> onHandPositive) {
        Integer rows = jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Integer>) con -> {
            try (var ps = con.prepareStatement(
                    "UPDATE shopify_inventory_adjustments SET status = 'superseded_by_seed', " +
                    "       superseded_at = now(), next_attempt_at = NULL " +
                    "WHERE tenant_id = ? AND location_id = ? " +
                    "  AND trigger_type IN " + IncrementRecoveryRules.INCREMENT_TRIGGERS_SQL +
                    "  AND status IN ('failed', 'pending') " +
                    "  AND created_at <= ? " +
                    "  AND (variant_id = ANY(?) OR NOT (variant_id = ANY(?)))")) {
                ps.setObject(1, tenantId);
                ps.setObject(2, tracedLocationId);
                ps.setTimestamp(3, snapshotAt);
                ps.setArray(4, con.createArrayOf("uuid", seededVariants.toArray()));
                ps.setArray(5, con.createArrayOf("uuid", onHandPositive.toArray()));
                return ps.executeUpdate();
            }
        });
        int n = rows == null ? 0 : rows;
        if (n > 0) {
            log.info("Initial seed superseded {} unapplied increment claim(s): tenant={} location={} snapshot={}",
                n, tenantId, tracedLocationId, snapshotAt);
        }
        return n;
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
