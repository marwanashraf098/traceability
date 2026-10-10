package com.traceability.inventory;

import com.traceability.tenancy.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

/**
 * D10 (approved 2026-10-10) — the one-off repair of the two Snouts pieces whose Lookup adjustments
 * on 2026-10-10 never reached Shopify (before piece sync existed). EXACTLY these two pieces, nothing
 * else; run through scripts/repair-lookup-adjust-2026-10-10 (dry run unless --apply):
 *
 *   01M3S6YXH3Z0PCC6J6467HPR0T  voided  — its void_correction was wrongly 'skipped' (received before the
 *       seed; the old rule only looked for an 'applied' receiving claim). New claim
 *       void_correction piece:repair-2026-10-10 → −1 at main via pushVoidCorrection. The legacy
 *       'skipped' row stays as history.
 *   01M3S6YXH3VPJ3FDXA4RTN9N9P  damaged — its damage_move 'failed' (no referenceDocumentUri). New claim
 *       damage_move piece:repair-2026-10-10 → the fixed available → damaged move; the legacy 'failed'
 *       row is marked 'superseded_by_repair'.
 *
 * Server-side: refused (404) for any tenant but The Snouts (SNOUTS_TENANT), before any read.
 * Both are decided by the live rule (PieceShopifyRules.countedAtMain) — a piece that rule doesn't
 * count is reported and left alone. Runs as app_user under the given tenant (RLS): the pieces must be
 * that tenant's. Dry run: reads only (Traced rows and Shopify's available / damaged), writes nothing,
 * calls no Shopify write. Apply: idempotent — the repair key is a claim key, a second run claims nothing.
 */
@Service
public class LookupAdjustRepairService {

    private static final Logger log = LoggerFactory.getLogger(LookupAdjustRepairService.class);

    /** The Snouts — the only tenant this repair ever runs for (checked server-side, before any read). */
    static final UUID SNOUTS_TENANT  = UUID.fromString("e785e5e4-2c5c-428e-afdd-d26d90754229");
    static final String VOID_PIECE   = "01M3S6YXH3Z0PCC6J6467HPR0T";
    static final String DAMAGE_PIECE = "01M3S6YXH3VPJ3FDXA4RTN9N9P";
    static final String REPAIR_KEY   = "repair-2026-10-10";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ShopifyInventoryService inventory;

    public LookupAdjustRepairService(JdbcTemplate jdbc, PlatformTransactionManager txm, ShopifyInventoryService inventory) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txm);
        this.inventory = inventory;
    }

    private record Target(String pieceId, String expectedStatus, String triggerType, Map<String, Integer> intended) {}

    private static final List<Target> TARGETS = List.of(
        new Target(VOID_PIECE, "voided", "void_correction", Map.of("available", -1, "on_hand", -1)),
        new Target(DAMAGE_PIECE, "damaged", "damage_move", Map.of("available", -1, "damaged", 1)));

    public Map<String, Object> run(UUID tenantId, boolean apply) {
        if (!SNOUTS_TENANT.equals(tenantId)) {
            // Exactly one tenant, exactly two pieces — anything else is not this repair.
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such repair for this tenant");
        }
        return TenantContext.runAs(tenantId, () -> runInTenant(tenantId, apply));
    }

    private Map<String, Object> runInTenant(UUID tenantId, boolean apply) {
        List<Map<String, Object>> pieces = new ArrayList<>();
        List<UUID> variants = new ArrayList<>();
        for (Target t : TARGETS) {
            Map<String, Object> row = jdbc.query(
                "SELECT status::text AS status, variant_id FROM pieces WHERE id = ? AND tenant_id = ?",
                rs -> rs.next() ? Map.<String, Object>of("status", rs.getString(1), "variant", rs.getObject(2, UUID.class)) : null,
                t.pieceId(), tenantId);
            if (row == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Piece " + t.pieceId() + " not found in this tenant");
            variants.add((UUID) row.get("variant"));
        }

        Map<UUID, Map<String, Integer>> before = inventory.shopifyStates(tenantId, variants, List.of("available", "damaged", "on_hand"));

        for (int i = 0; i < TARGETS.size(); i++) {
            Target t = TARGETS.get(i);
            UUID variant = variants.get(i);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("pieceId", t.pieceId());
            out.put("variantId", variant.toString());
            out.put("shopifyBefore", before.getOrDefault(variant, Map.of()));
            out.put("intendedDelta", t.intended());
            out.put("legacyClaims", jdbc.queryForList(
                "SELECT trigger_id, status, COALESCE(skip_reason, error) AS why, created_at FROM shopify_inventory_adjustments " +
                "WHERE tenant_id = ? AND trigger_type = ? AND split_part(trigger_id, ':', 1) = ? ORDER BY created_at",
                tenantId, t.triggerType(), t.pieceId()));
            String status = jdbc.queryForObject("SELECT status::text FROM pieces WHERE id = ? AND tenant_id = ?",
                String.class, t.pieceId(), tenantId);
            PieceShopifyRules.Verdict v = PieceShopifyRules.countedAtMain(jdbc, tenantId, t.pieceId());
            out.put("countedAtMain", v.write());
            if (!v.write()) out.put("reason", v.skipReason());
            String triggerId = t.pieceId() + ":" + REPAIR_KEY;
            if (!t.expectedStatus().equals(status)) {
                out.put("action", "none — piece is " + status + ", expected " + t.expectedStatus());
            } else if (!v.write()) {
                out.put("action", "none — Shopify does not count it at the main warehouse (" + v.skipReason() + ")");
            } else if (!apply) {
                out.put("action", "would claim " + t.triggerType() + " " + triggerId + " and send it once");
            } else {
                Long claimId = tx.execute(st -> {
                    if ("damage_move".equals(t.triggerType())) {
                        jdbc.update("UPDATE shopify_inventory_adjustments SET status = 'superseded_by_repair', " +
                            "    error = COALESCE(error, '') || ' — replaced by ' || ? " +
                            "WHERE tenant_id = ? AND trigger_type = 'damage_move' AND trigger_id = ? AND status = 'failed' " +
                            "  AND created_at < piece_sync_cutoff()",
                            triggerId, tenantId, t.pieceId());
                    }
                    return inventory.claimRepairDeparture(tenantId, t.triggerType(), t.pieceId(), triggerId);
                });
                if (claimId == null) {
                    out.put("action", "nothing new — " + triggerId + " was already claimed");
                } else {
                    inventory.pushPieceClaimNow(tenantId, claimId, false);
                    out.put("action", "claimed and sent " + triggerId);
                }
                out.put("claim", jdbc.queryForList("SELECT trigger_id, status, error FROM shopify_inventory_adjustments " +
                    "WHERE tenant_id = ? AND trigger_type = ? AND trigger_id = ?", tenantId, t.triggerType(), triggerId));
            }
            pieces.add(out);
        }
        if (apply) {
            log.info("Lookup-adjust repair applied for tenant {}", tenantId);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("mode", apply ? "apply" : "dry_run");
        result.put("tenantId", tenantId.toString());
        result.put("pieces", pieces);
        return result;
    }
}
