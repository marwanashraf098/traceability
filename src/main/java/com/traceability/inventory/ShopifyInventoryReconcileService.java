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

    public ApplyResult apply(UUID actorUserId) {
        UUID tenantId = TenantContext.require();
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
                        String variantGid = tx.execute(s -> jdbc.query(
                            "SELECT external_id FROM variants WHERE id = ? AND tenant_id = ?",
                            rs -> rs.next() ? rs.getString(1) : null,
                            row.variantId(), tenantId));
                        String itemGid = shopify.resolveInventoryItemId(ctx.shopDomain(), ctx.token(), variantGid);
                        shopify.adjustInventoryQuantities(ctx.shopDomain(), ctx.token(), itemGid,
                            ctx.tracedGid(), (int) row.tracedOnHand(), "correction");
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

        final int finalSeeded = seeded, finalSkipped = skippedNonZero, finalNoop = noop, finalFailed = failed;
        tx.execute(s -> {
            auditService.record(actorUserId, "shopify_inventory_initial_seed", "location",
                ctx.tracedLocationId().toString(),
                Map.of("seeded", finalSeeded, "skippedNonZero", finalSkipped, "noop", finalNoop, "failed", finalFailed));
            return null;
        });

        return new ApplyResult(seeded, skippedNonZero, noop, failed, failures);
    }

    private void recordAudit(UUID tenantId, UUID variantId, UUID locationId, long delta,
                              String status, String error) {
        ObjectNode payload = mapper.createObjectNode().put("reason", "initial_seed").put("delta", delta);
        String payloadJson;
        try { payloadJson = mapper.writeValueAsString(payload); }
        catch (Exception e) { payloadJson = "{}"; }
        final String finalPayload = payloadJson;

        tx.execute(s -> {
            jdbc.update(
                "INSERT INTO shopify_inventory_adjustments " +
                "(tenant_id, batch_id, variant_id, location_id, delta, trigger_type, trigger_id, " +
                " payload, status, error) " +
                "VALUES (?, ?, ?, ?, ?, 'initial_seed', ?, ?::jsonb, ?, ?) " +
                "ON CONFLICT (trigger_type, trigger_id, variant_id, location_id) DO NOTHING",
                tenantId, UUID.randomUUID(), variantId, locationId, delta,
                variantId.toString(), finalPayload, status, error);
            return null;
        });
    }
}
