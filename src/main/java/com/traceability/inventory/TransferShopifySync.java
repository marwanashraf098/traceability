package com.traceability.inventory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.traceability.integrations.shopify.ShopifyAmbiguousException;
import com.traceability.integrations.shopify.ShopifyException;
import com.traceability.integrations.shopify.ShopifyGateway;
import com.traceability.integrations.shopify.ShopifyTokenProvider;
import com.traceability.integrations.shopify.StoreRepository;
import com.traceability.tenancy.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * Issue 2 (2026-10-08; approved: model (b), sync by custody) — a transfer's send-time Shopify
 * decrement. Traced still writes ONLY to the Traced Main Warehouse (no mirroring, no new locations).
 *
 * Send (TransferService.markSent for send-and-back / bring-back, closeOneWay for a permanent move):
 * the destination's locations.shopify_sync_mode is snapshot onto the transfer; in 'remove' mode the
 * pieces that left the MAIN warehouse (transfer_pieces.from_location_id) are decremented there —
 * ONE transfer_shopify_syncs claim per transfer, ONE pushTransferOut call (−N per variant). 'leave'
 * → nothing at all. Not from the main warehouse / no main warehouse / main not linked → a 'skipped'
 * row with the reason (never a silent skip).
 *
 * Claim-before-call: {@link #claimSend} runs in the caller's transaction ('queued'); after commit
 * {@link #push} takes it queued→pending (a conditional UPDATE — the one-sender guard), makes ONE
 * attempt, and records 'pushed' | 'failed' (definitive — nothing applied) | 'failed_ambiguous'
 * (no confirmed response — NEVER sent again automatically; the void_hold_sync_failed alert asks a
 * person to verify in Shopify). {@link #sweep} sends claims still 'queued' (repair rows, a crash
 * before the async push) and re-sends 'failed' (definitive) up to {@link #MAX_ATTEMPTS}; a send
 * stuck 'pending' for 15 min becomes 'failed_ambiguous'. A retry of any step never double-applies:
 * the claim row is per transfer, and only queued/failed rows are ever (re)sent.
 *
 * The per-piece +1 when a piece comes back to the main warehouse is ShopifyInventoryService
 * .onTransferReturn (the existing increment path); {@link #RETURN_COUNTED_SQL} decides it.
 */
@Service
public class TransferShopifySync {

    private static final Logger log = LoggerFactory.getLogger(TransferShopifySync.class);
    static final int MAX_ATTEMPTS = 5;

    private final JdbcTemplate         jdbc;
    private final TransactionTemplate  tx;
    private final ShopifyGateway       shopify;
    private final ShopifyTokenProvider tokenProvider;
    private final StoreRepository      storeRepository;
    private final ObjectMapper         mapper;
    private final InventoryItemIdService itemIds;
    private final TransferShopifySync  self;

    public TransferShopifySync(JdbcTemplate jdbc, PlatformTransactionManager txm, ShopifyGateway shopify,
                               ShopifyTokenProvider tokenProvider, StoreRepository storeRepository,
                               ObjectMapper mapper, @Lazy TransferShopifySync self) {
        this.jdbc            = jdbc;
        this.tx              = new TransactionTemplate(txm);
        this.shopify         = shopify;
        this.tokenProvider   = tokenProvider;
        this.storeRepository = storeRepository;
        this.mapper          = mapper;
        this.itemIds         = new InventoryItemIdService(jdbc, txm, shopify);
        this.self            = self;
    }

    /**
     * THE rule (alias {@code tp_out} = the transfer_pieces row of the transfer that took the piece OUT
     * of the main warehouse, {@code t_out} that transfer): did that departure leave Shopify's Traced
     * Main Warehouse count? Yes when the transfer's send decrement was pushed and counted this piece,
     * or when it went out before the tenant's initial seed (the seed counted only pieces at the main
     * warehouse, so it never counted this one). Then its return gets +1; otherwise (mode 'leave', a
     * skipped / failed / ambiguous send) Shopify still counts it and the return writes nothing.
     */
    /**
     * When the pieces of transfer {@code alias} left their location — THE one definition (also inlined,
     * verbatim, in scripts/ops/2026-10-08-transfer-shopify-repair.sql). A permanent move leaves when it
     * closes; a send-and-back when it is marked sent — transfers sent before V118 have no sent_at, so
     * their reconcile start, else their creation, stands in (they are past 'preparing' by then).
     */
    static String departedAtSql(String alias) {
        return "(CASE WHEN " + alias + ".transfer_mode = 'relocate_out' THEN " + alias + ".closed_at " +
               "      ELSE COALESCE(" + alias + ".sent_at, " + alias + ".reconcile_started_at, " + alias + ".created_at) END)";
    }

    /** The tenant (of transfer {@code t_out}) has an applied initial seed — Shopify's Traced location holds its count. */
    static final String SEEDED_SQL =
        "SELECT 1 FROM shopify_inventory_adjustments sd WHERE sd.tenant_id = t_out.tenant_id " +
        "AND sd.trigger_type = 'initial_seed' AND sd.status = 'applied'";

    /**
     * Never fires for a tenant with no applied seed (a +1 then would be counted again by the seed):
     * the seed requirement wraps both branches.
     */
    static final String RETURN_COUNTED_SQL =
        "(EXISTS (" + SEEDED_SQL + ") AND " +
        "(EXISTS (SELECT 1 FROM transfer_shopify_syncs y WHERE y.tenant_id = t_out.tenant_id " +
        "         AND y.transfer_id = t_out.id AND y.status = 'pushed' " +
        "         AND jsonb_exists(y.piece_ids, tp_out.piece_id)) " +
        " OR (SELECT MIN(seed.created_at) FROM shopify_inventory_adjustments seed " +
        "     WHERE seed.tenant_id = t_out.tenant_id AND seed.trigger_type = 'initial_seed' " +
        "       AND seed.status = 'applied') > " + departedAtSql("t_out") + ")) ";

    // ── Phase A: the claim, in the send transaction ─────────────────────────────

    /**
     * Snapshots the destination's mode onto the transfer and writes its one claim row (queued, or
     * skipped with a reason; nothing in 'leave' mode). Call inside the transaction that moves the
     * transfer to sent / closed; the push is registered for after its commit.
     */
    public void claimSend(UUID tenantId, UUID transferId) {
        Map<String, Object> t = jdbc.queryForMap(
            "SELECT tr.transfer_mode, tr.destination_location_id, tr.source_location_id, " +
            "       dl.is_fulfillment AS dest_is_main, dl.shopify_sync_mode AS dest_mode, " +
            "       sl.shopify_sync_mode AS src_mode " +
            "FROM transfers tr JOIN locations dl ON dl.id = tr.destination_location_id " +
            "LEFT JOIN locations sl ON sl.id = tr.source_location_id " +
            "WHERE tr.id = ? AND tr.tenant_id = ?", transferId, tenantId);
        // The mode that governs this trip: the non-main end's (a bring-back's destination is main).
        String mode = Boolean.TRUE.equals(t.get("dest_is_main"))
            ? (String) t.get("src_mode") : (String) t.get("dest_mode");
        jdbc.update("UPDATE transfers SET shopify_sync_mode = ? WHERE id = ? AND tenant_id = ?",
            mode, transferId, tenantId);
        if ("leave".equals(mode)) return;   // Shopify unchanged: zero writes

        List<Map<String, Object>> main = jdbc.queryForList(
            "SELECT id, shopify_location_id, shopify_sync_status FROM locations " +
            "WHERE tenant_id = ? AND is_fulfillment = true", tenantId);
        if (main.isEmpty()) { skip(tenantId, transferId, null, "no_main_warehouse"); return; }
        UUID mainId = (UUID) main.get(0).get("id");

        // Pieces that left the MAIN warehouse on this trip (a bring-back's never did).
        List<Map<String, Object>> leaving = jdbc.queryForList(
            "SELECT tp.piece_id, p.variant_id FROM transfer_pieces tp JOIN pieces p ON p.id = tp.piece_id " +
            "WHERE tp.transfer_id = ? AND tp.tenant_id = ? AND tp.from_location_id = ? " +
            "  AND (tp.outcome IS NULL OR tp.outcome = 'relocated') " +
            "ORDER BY tp.created_at, tp.id", transferId, tenantId, mainId);
        if (leaving.isEmpty()) { skip(tenantId, transferId, mainId, "not_from_main_warehouse"); return; }
        if (!"linked".equals(main.get(0).get("shopify_sync_status")) || main.get(0).get("shopify_location_id") == null) {
            skip(tenantId, transferId, mainId, "main_warehouse_not_linked"); return;
        }
        // Linked but never seeded: Shopify's Traced location holds nothing yet, and the seed will count
        // only what is in the main warehouse then — treated exactly like an unlinked one (no −N now).
        if (!Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (" + SEEDED_SQL.replace("t_out.tenant_id", "?") + ")", Boolean.class, tenantId))) {
            skip(tenantId, transferId, mainId, "not_seeded"); return;
        }

        Map<String, Integer> deltas = new LinkedHashMap<>();
        List<String> pieceIds = new ArrayList<>();
        for (Map<String, Object> r : leaving) {
            deltas.merge(r.get("variant_id").toString(), 1, Integer::sum);
            pieceIds.add((String) r.get("piece_id"));
        }
        UUID syncId = jdbc.query(
            "INSERT INTO transfer_shopify_syncs (tenant_id, transfer_id, location_id, status, deltas, piece_ids) " +
            "VALUES (?, ?, ?, 'queued', ?::jsonb, ?::jsonb) ON CONFLICT (transfer_id) DO NOTHING RETURNING id",
            rs -> rs.next() ? rs.getObject(1, UUID.class) : null,
            tenantId, transferId, mainId, json(deltas), json(pieceIds));
        if (syncId != null) {
            ShopifyInventoryService.afterCommit(() -> self.push(tenantId, syncId));
        }
    }

    private void skip(UUID tenantId, UUID transferId, UUID locationId, String reason) {
        log.info("Transfer {} Shopify decrement skipped: {}", transferId, reason);
        jdbc.update(
            "INSERT INTO transfer_shopify_syncs (tenant_id, transfer_id, location_id, status, reason) " +
            "VALUES (?, ?, ?, 'skipped', ?) ON CONFLICT (transfer_id) DO NOTHING",
            tenantId, transferId, locationId, reason);
    }

    // ── Phase B: the one attempt ────────────────────────────────────────────────

    @Async
    public CompletableFuture<Void> push(UUID tenantId, UUID syncId) {
        TenantContext.runAs(tenantId, () -> {
            try {
                pushNow(tenantId, syncId);
            } catch (Exception e) {
                log.error("Transfer Shopify push failed unexpectedly sync={}", syncId, e);
            }
        });
        return CompletableFuture.completedFuture(null);
    }

    /** queued/failed → pending (the one-sender guard), ONE pushTransferOut attempt, then the outcome. */
    void pushNow(UUID tenantId, UUID syncId) {
        Map<String, Object> claim = tx.execute(s -> {
            List<Map<String, Object>> rows = jdbc.queryForList(
                "UPDATE transfer_shopify_syncs SET status = 'pending', send_started_at = now(), " +
                "    attempt_count = attempt_count + 1 " +
                "WHERE id = ? AND tenant_id = ? AND status IN ('queued', 'failed') AND attempt_count < ? " +
                "RETURNING transfer_id, location_id, deltas::text AS deltas",
                syncId, tenantId, MAX_ATTEMPTS);
            return rows.isEmpty() ? null : rows.get(0);
        });
        if (claim == null) return;   // already sent / being sent / ambiguous / exhausted
        UUID transferId = (UUID) claim.get("transfer_id");

        Prepared p;
        try {
            p = tx.execute(s -> prepare(tenantId, (UUID) claim.get("location_id"), (String) claim.get("deltas")));
        } catch (RuntimeException e) {
            mark(tenantId, syncId, "failed", "Not sent: " + e.getMessage());
            return;
        }
        String key = ShopifyGateway.idempotencyKey(tenantId, "transfer_out", transferId.toString(), null, p.locationGid());
        try {
            shopify.pushTransferOut(p.shopDomain(), p.token(), p.deltas(), p.locationGid(),
                "traced://transfer/" + transferId, key);
            mark(tenantId, syncId, "pushed", null);
        } catch (ShopifyAmbiguousException e) {
            mark(tenantId, syncId, "failed_ambiguous", e.getMessage());
            log.error("Transfer Shopify push AMBIGUOUS transfer={} — verify in Shopify, NOT re-sending: {}",
                transferId, e.getMessage());
        } catch (ShopifyException e) {
            // Definite rejection (HTTP 4xx / userErrors — ShopifyHttpGateway.pushTransferOut's only two):
            // nothing applied, the sweep may re-send.
            mark(tenantId, syncId, "failed", e.getMessage());
        } catch (RuntimeException e) {
            // Anything unexpected after the call started is unknown → ambiguous, never re-sent.
            mark(tenantId, syncId, "failed_ambiguous", e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private record Prepared(String shopDomain, String token, String locationGid, List<ShopifyGateway.InventoryDelta> deltas) {}

    /** Everything the call needs. The GID is read off the same main-warehouse row that must be linked. */
    private Prepared prepare(UUID tenantId, UUID locationId, String deltasJson) {
        Map<String, Object> loc = jdbc.queryForMap(
            "SELECT shopify_location_id, shopify_sync_status, is_fulfillment FROM locations WHERE id = ? AND tenant_id = ?",
            locationId, tenantId);
        if (!Boolean.TRUE.equals(loc.get("is_fulfillment")) || !"linked".equals(loc.get("shopify_sync_status"))
                || loc.get("shopify_location_id") == null) {
            throw new IllegalStateException("main warehouse not linked to Shopify");
        }
        StoreRepository.Store store = storeRepository.findActiveStoreByTenant(tenantId)
            .orElseThrow(() -> new IllegalStateException("no store"));
        if (!ShopifyGateway.isScopeGranted("write_inventory", store.accessTokenScopes())) {
            throw new IllegalStateException(ShopifyGateway.scopeGrantMessage(
                store.connectionType(), "write_inventory", store.accessTokenScopes()));
        }
        String token = tokenProvider.getValidToken(store.id());
        List<ShopifyGateway.InventoryDelta> deltas = new ArrayList<>();
        try {
            JsonNode node = mapper.readTree(deltasJson);
            for (Iterator<Map.Entry<String, JsonNode>> it = node.fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> e = it.next();
                UUID variantId = UUID.fromString(e.getKey());
                String gid = jdbc.queryForObject("SELECT external_id FROM variants WHERE id = ? AND tenant_id = ?",
                    String.class, variantId, tenantId);
                String item = itemIds.resolve(tenantId, variantId, gid, store.shopDomain(), token);
                deltas.add(new ShopifyGateway.InventoryDelta(item, -e.getValue().asInt()));
            }
        } catch (ShopifyException e) {
            throw new IllegalStateException("inventory item lookup failed: " + e.getMessage(), e);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("unreadable deltas", e);
        }
        return new Prepared(store.shopDomain(), token, (String) loc.get("shopify_location_id"), deltas);
    }

    private void mark(UUID tenantId, UUID syncId, String status, String error) {
        tx.execute(s -> jdbc.update(
            "UPDATE transfer_shopify_syncs SET status = ?, error = ?, " +
            "    pushed_at = CASE WHEN ? = 'pushed' THEN now() ELSE pushed_at END " +
            "WHERE id = ? AND tenant_id = ?", status, error, status, syncId, tenantId));
        if (!"pushed".equals(status)) {
            log.warn("Transfer Shopify push recorded as {}: sync={} error={}", status, syncId, error);
        }
    }

    // ── Sweep (TransferShopifySweepJob, every 10 min; tests call it directly) ───

    /** Sends this tenant's queued claims (repair rows, a crash before the async push) and re-sends
     *  definitive failures; a send 'pending' for 15+ minutes may have reached Shopify → ambiguous. */
    public int sweep(UUID tenantId) {
        tx.execute(s -> jdbc.update(
            "UPDATE transfer_shopify_syncs SET status = 'failed_ambiguous', " +
            "    error = 'Send started but never confirmed — verify in Shopify' " +
            "WHERE tenant_id = ? AND status = 'pending' AND send_started_at < now() - interval '15 minutes'",
            tenantId));
        List<UUID> due = tx.execute(s -> jdbc.queryForList(
            "SELECT id FROM transfer_shopify_syncs WHERE tenant_id = ? " +
            "AND ((status = 'queued' AND created_at < now() - interval '1 minute') " +
            "  OR (status = 'failed' AND attempt_count < ?)) " +
            "ORDER BY created_at, id LIMIT 50", UUID.class, tenantId, MAX_ATTEMPTS));
        if (due == null) return 0;
        for (UUID id : due) pushNow(tenantId, id);
        return due.size();
    }

    private String json(Object o) {
        try { return mapper.writeValueAsString(o); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
}
