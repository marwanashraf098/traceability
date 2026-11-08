package com.traceability.inventory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.traceability.integrations.shopify.ShopifyAmbiguousException;
import com.traceability.integrations.shopify.ShopifyCasStaleException;
import com.traceability.integrations.shopify.ShopifyException;
import com.traceability.integrations.shopify.ShopifyGateway;
import com.traceability.integrations.shopify.ShopifyMainLevelInactiveException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/**
 * Main reconcile R2 (V203, approved 2026-11-04) — THE one caller of the two named writes of the FR-17 v2 amendment:
 * (8) {@code pushPart1MainDecrement} and (9) {@code pushMainReconcile}. Not a bean: MainReconcileService builds it on
 * its own JdbcTemplate (app_user + RLS, inside TenantContext.runAs). Every write targets the tenant's Traced Main
 * location gid (the caller passes it from locations.is_fulfillment; a claim at any other location is refused before
 * any call). Claim-before-call: the claim row is committed BEFORE the HTTP call, no DB transaction is open during it,
 * ONE attempt, and the outcome is recorded after. failed_ambiguous is never sent again automatically.
 * The kill switch ({@code enabled}) is checked before every call.
 */
final class MainReconcileWriter {

    private static final Logger log = LoggerFactory.getLogger(MainReconcileWriter.class);
    static final int PART1_MAX_ATTEMPTS = 5;

    /** One confirmed difference to write. */
    record Candidate(UUID variantId, String item, int shopifyOnHand, int target, int custody, int away, int fulfilled,
                     int unpicked, int owed, int unfulfilled, int delta, List<Long> settleClaims, List<UUID> settlePart1,
                     List<UUID> settleWrites) {}

    record Main(UUID id, String gid) {}

    record Outcome(int attempted, int applied) {}

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ShopifyGateway shopify;
    private final BooleanSupplier enabled;
    private final ObjectMapper mapper = new ObjectMapper();

    MainReconcileWriter(JdbcTemplate jdbc, TransactionTemplate tx, ShopifyGateway shopify, BooleanSupplier enabled) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.shopify = shopify;
        this.enabled = enabled;
    }

    // ── (8) Part 1 ──────────────────────────────────────────────────────────────────────────────────────────────

    /** Sends every queued / definitely-failed (attempts left) Part 1 claim of the tenant, once each. */
    Outcome sendPart1(UUID tenantId, String shop, String token, Main main) {
        List<UUID> ids = tx.execute(s -> jdbc.queryForList(
            "SELECT id FROM main_reconcile_part1_claims WHERE tenant_id = ? " +
            "  AND (status = 'queued' OR (status = 'failed' AND attempt_count < ?)) ORDER BY created_at, id",
            UUID.class, tenantId, PART1_MAX_ATTEMPTS));
        int attempted = 0, applied = 0;
        for (UUID id : ids == null ? List.<UUID>of() : ids) {
            if (!enabled.getAsBoolean()) break;   // kill switch
            Object[] c = tx.execute(s -> {
                List<Object[]> rows = jdbc.query(
                    "UPDATE main_reconcile_part1_claims c SET status = 'pending', attempt_count = attempt_count + 1, " +
                    "       send_started_at = now(), updated_at = now() " +
                    "FROM variants v WHERE c.id = ? AND c.tenant_id = ? AND v.id = c.variant_id AND v.tenant_id = c.tenant_id " +
                    "  AND (c.status = 'queued' OR (c.status = 'failed' AND c.attempt_count < ?)) " +
                    "RETURNING c.variant_id, c.location_id, c.quantity, c.attempt_count, v.shopify_inventory_item_id, c.order_id",
                    (rs, i) -> new Object[]{rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getInt(3),
                        rs.getInt(4), rs.getString(5), rs.getObject(6, UUID.class)},
                    id, tenantId, PART1_MAX_ATTEMPTS);
                return rows.isEmpty() ? null : rows.get(0);
            });
            if (c == null) continue;
            UUID variantId = (UUID) c[0];
            UUID locationId = (UUID) c[1];
            int qty = (Integer) c[2];
            int attempt = (Integer) c[3];
            String item = (String) c[4];
            if (!main.id().equals(locationId) || item == null || item.isBlank()) {
                // Never sent: a claim not at the Traced Main location (or no inventory item) is refused before any call.
                finishPart1(tenantId, id, "failed", !main.id().equals(locationId) ? "not_main_location" : "no_inventory_item");
                logPart1(shop, variantId, id, -qty, "refused");
                continue;
            }
            // Part D P2 (2026-11-08): never a −N onto a hidden (inactive) level — the existing Main read
            // (inventoryLevel(locationId:) answers null for an inactive level → stocked false). Not active → nothing
            // sent, 'failed' with the main_level_inactive error (CRITICAL void_hold_sync_failed); the next run checks
            // again while attempts remain. A failed read is a definite "not sent".
            String levelProblem = null;
            try {
                ShopifyGateway.MainLevel lv = shopify.fetchMainLevels(shop, token, main.gid(), List.of(item)).stream()
                    .filter(l -> item.equals(l.inventoryItemGid())).findFirst().orElse(null);
                if (lv == null || !lv.stocked()) {
                    levelProblem = ShopifyMainLevelInactiveException.REASON
                        + ": Part 1 decrement not sent — no active level at the Traced location";
                }
            } catch (RuntimeException e) {
                levelProblem = "Not sent — level read failed: " + e.getMessage();
            }
            if (levelProblem != null) {
                finishPart1(tenantId, id, "failed", levelProblem);
                logPart1(shop, variantId, id, -qty, "not_sent");
                continue;
            }
            attempted++;
            String result;
            String error = null;
            try {
                shopify.pushPart1MainDecrement(shop, token, item, main.gid(), -qty,
                    "traced://main-reconcile/part1/" + id, "main-p1:" + id + ":" + attempt);
                result = "applied";
                applied++;
            } catch (ShopifyAmbiguousException e) {
                result = "failed_ambiguous"; error = e.getMessage();
            } catch (ShopifyException e) {
                result = "failed"; error = e.getMessage();
            } catch (RuntimeException e) {
                result = "failed_ambiguous"; error = e.getClass().getSimpleName() + ": " + e.getMessage();
            }
            finishPart1(tenantId, id, result, error);
            if ("applied".equals(result)) queueVariant(tenantId, variantId, "part1_applied");
            logPart1(shop, variantId, id, -qty, result);
        }
        return new Outcome(attempted, applied);
    }

    private void finishPart1(UUID tenantId, UUID id, String status, String error) {
        tx.executeWithoutResult(s -> jdbc.update(
            "UPDATE main_reconcile_part1_claims SET status = ?, error = ?, updated_at = now(), " +
            "       applied_at = CASE WHEN ? = 'applied' THEN now() ELSE applied_at END " +
            "WHERE id = ? AND tenant_id = ? AND status = 'pending'",
            status, truncate(error), status, id, tenantId));
    }

    private static void logPart1(String shop, UUID variantId, UUID claimId, int delta, String result) {
        log.info("Main reconcile write [part1] store={} variant={} delta={} reason=shipped_by_traced_fulfilled_elsewhere " +
                 "claim={} result={}", shop, variantId, delta, claimId, result);
    }

    // ── (9) reconcile writes ────────────────────────────────────────────────────────────────────────────────────

    /** One confirmed difference: claim, fresh read, compare-and-set write, outcome (+ settling on success). */
    boolean write(UUID tenantId, UUID runId, String shop, String token, Main main, Candidate c) {
        if (!enabled.getAsBoolean()) return false;   // kill switch
        UUID writeId = tx.execute(s -> {
            List<UUID> r = jdbc.queryForList(
                "INSERT INTO main_reconcile_writes (tenant_id, run_id, variant_id, location_id, shopify_location_id, " +
                "  inventory_item_id, shopify_on_hand, target, custody_at_main, away_counted, fulfilled_from_main, " +
                "  part1_owed, unfulfilled_shipped, delta, fulfilled_unpicked) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) " +
                "ON CONFLICT (tenant_id, variant_id) WHERE status = 'pending' DO NOTHING RETURNING id",
                UUID.class, tenantId, runId, c.variantId(), main.id(), main.gid(), c.item(), c.shopifyOnHand(),
                c.target(), c.custody(), c.away(), c.fulfilled(), c.owed(), c.unfulfilled(), c.delta(), c.unpicked());
            return r.isEmpty() ? null : r.get(0);
        });
        if (writeId == null) return false;   // another write of this variant is pending

        // Re-read Shopify immediately before the write.
        ShopifyGateway.MainLevel fresh;
        try {
            fresh = shopify.fetchMainLevels(shop, token, main.gid(), List.of(c.item())).stream()
                .filter(l -> c.item().equals(l.inventoryItemGid())).findFirst().orElse(null);
        } catch (RuntimeException e) {
            finishWrite(tenantId, writeId, "failed", null, null, "fresh read failed (write not sent): " + e.getMessage(), null);
            logWrite(shop, c, writeId, null, "failed");
            return false;
        }
        if (fresh == null || !fresh.stocked() || fresh.onHand() != c.shopifyOnHand()) {
            // Shopify moved since the confirmed read: nothing sent; the next run decides again.
            finishWrite(tenantId, writeId, "changed_before_write", fresh == null ? null : fresh.onHand(),
                null, null, null);
            queueVariant(tenantId, c.variantId(), "recheck");
            logWrite(shop, c, writeId, fresh == null ? null : fresh.onHand(), "changed_before_write");
            return false;
        }
        int changeFrom = fresh.available();
        tx.executeWithoutResult(s -> jdbc.update(
            "UPDATE main_reconcile_writes SET fresh_on_hand = ?, change_from_quantity = ? WHERE id = ? AND tenant_id = ?",
            fresh.onHand(), changeFrom, writeId, tenantId));
        if (!enabled.getAsBoolean()) {
            finishWrite(tenantId, writeId, "failed", fresh.onHand(), changeFrom, "kill switch: not sent", null);
            return false;
        }

        String result;
        String error = null;
        try {
            shopify.pushMainReconcile(shop, token, c.item(), main.gid(), c.delta(), changeFrom,
                "traced://main-reconcile/write/" + writeId, "main-rc:" + writeId);
            result = "applied";
        } catch (ShopifyCasStaleException e) {
            result = "cas_rejected"; error = e.getMessage();
        } catch (ShopifyAmbiguousException e) {
            result = "failed_ambiguous"; error = e.getMessage();
        } catch (ShopifyException e) {
            result = "failed"; error = e.getMessage();
        } catch (RuntimeException e) {
            result = "failed_ambiguous"; error = e.getClass().getSimpleName() + ": " + e.getMessage();
        }
        finishWrite(tenantId, writeId, result, fresh.onHand(), changeFrom, error, "applied".equals(result) ? c : null);
        if ("cas_rejected".equals(result)) queueVariant(tenantId, c.variantId(), "recheck");
        logWrite(shop, c, writeId, fresh.onHand(), result);
        return "applied".equals(result);
    }

    /**
     * The outcome, in one transaction. On 'applied' the same transaction settles the variant's old failed_ambiguous
     * claims of other write paths (and earlier ambiguous reconcile writes / Part 1 claims) as 'superseded_by_reconcile',
     * and restarts the variant's two-run chain.
     */
    private void finishWrite(UUID tenantId, UUID writeId, String status, Integer freshOnHand, Integer changeFrom,
                             String error, Candidate settle) {
        tx.executeWithoutResult(s -> {
            ArrayNode settled = mapper.createArrayNode();
            if (settle != null) {
                if (!settle.settleClaims().isEmpty()) {
                    for (Long id : jdbc.queryForList(
                            "UPDATE shopify_inventory_adjustments SET status = 'superseded_by_reconcile', superseded_at = now() " +
                            "WHERE tenant_id = ? AND id = ANY(CAST(? AS bigint[])) AND status = 'failed_ambiguous' RETURNING id",
                            Long.class, tenantId, "{" + String.join(",", settle.settleClaims().stream().map(String::valueOf).toList()) + "}")) {
                        settled.addObject().put("table", "shopify_inventory_adjustments").put("id", id);
                    }
                }
                if (!settle.settlePart1().isEmpty()) {
                    for (UUID id : jdbc.queryForList(
                            "UPDATE main_reconcile_part1_claims SET status = 'superseded_by_reconcile', updated_at = now() " +
                            "WHERE tenant_id = ? AND id = ANY(CAST(? AS uuid[])) AND status = 'failed_ambiguous' RETURNING id",
                            UUID.class, tenantId, MainReconcileService.uuidArray(settle.settlePart1()))) {
                        settled.addObject().put("table", "main_reconcile_part1_claims").put("id", id.toString());
                    }
                }
                if (!settle.settleWrites().isEmpty()) {
                    for (UUID id : jdbc.queryForList(
                            "UPDATE main_reconcile_writes SET status = 'superseded_by_reconcile', finished_at = COALESCE(finished_at, now()) " +
                            "WHERE tenant_id = ? AND id = ANY(CAST(? AS uuid[])) AND status = 'failed_ambiguous' AND id <> ? RETURNING id",
                            UUID.class, tenantId, MainReconcileService.uuidArray(settle.settleWrites()), writeId)) {
                        settled.addObject().put("table", "main_reconcile_writes").put("id", id.toString());
                    }
                }
                jdbc.update("UPDATE main_reconcile_variant_state SET last_delta = NULL, delta_since = NULL " +
                            "WHERE tenant_id = ? AND variant_id = ?", tenantId, settle.variantId());
            }
            jdbc.update("UPDATE main_reconcile_writes SET status = ?, fresh_on_hand = COALESCE(?, fresh_on_hand), " +
                        "  change_from_quantity = COALESCE(?, change_from_quantity), error = ?, settled = ?::jsonb, finished_at = now() " +
                        "WHERE id = ? AND tenant_id = ? AND status = 'pending'",
                status, freshOnHand, changeFrom, truncate(error), settled.toString(), writeId, tenantId);
        });
    }

    private static void logWrite(String shop, Candidate c, UUID writeId, Integer freshOnHand, String result) {
        log.info("Main reconcile write [reconcile] store={} variant={} shopify_before={} fresh_on_hand={} target={} " +
                 "(custody={} away={} fulfilled_from_main={} fulfilled_unpicked={} part1_owed={} unfulfilled_shipped={}) delta={} " +
                 "reason=confirmed_difference claim={} result={}",
            shop, c.variantId(), c.shopifyOnHand(), freshOnHand, c.target(), c.custody(), c.away(), c.fulfilled(),
            c.unpicked(), c.owed(), c.unfulfilled(), c.delta(), writeId, result);
    }

    private void queueVariant(UUID tenantId, UUID variantId, String source) {
        tx.executeWithoutResult(s -> jdbc.query("SELECT main_reconcile_queue_variant(?, ?, ?)", rs -> null,
            tenantId, variantId, source));
    }

    private static String truncate(String s) {
        return s == null ? null : (s.length() > 500 ? s.substring(0, 500) : s);
    }
}
