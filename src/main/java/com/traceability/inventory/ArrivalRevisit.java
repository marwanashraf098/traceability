package com.traceability.inventory;

import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Skipped decrements are revisited once their arrival reaches Shopify (2026-10-30, drift fix 1).
 *
 * A departure decided while its piece's receiving +N had not reached Shopify is skipped as
 * arrival_unconfirmed / arrival_not_counted (PieceShopifyRules.arrival) — right at the time: Shopify
 * did not count the unit, so taking it out would push Shopify below the true count. But when that +N
 * applies later (a held update sent after a reconnect, a retry, a person's re-send), Shopify now counts
 * a unit that already left — +1 too high for every such departure (testreciving, claim 2551 / 2552).
 *
 * So when a receiving claim becomes 'applied', that receipt + variant's skipped departures are revisited:
 * a departure whose piece is STILL AWAY (PieceShopifyRules.stillAway — its current departure is the one
 * the claim was for) flips the SAME claim row skipped → queued, through a guarded UPDATE (exactly once:
 * the WHERE re-checks status and reason, so a concurrent return that closed it, or a second revisit, finds
 * nothing). The existing senders then send it with that trigger's existing method — the piece sweep
 * (pushPieceClaimNow: pushPieceSold, pushVoidCorrection, pushPieceWriteOff, pushHoldEnter, the damage
 * move) or the exchange-dispatch resume. No new decrement type.
 *
 * Never revisited: a claim made before an applied initial seed that came after it (the seed pushed the
 * current count, which already left the piece out), and legacy claims (before piece sync). A piece that
 * came back first is closed by the return itself (PieceShopifyRules, not_sent_piece_returned) and is
 * never flipped. Transfers are NOT re-sent: a transfer that left out such a piece raises the HIGH
 * transfer_stock_not_removed alert instead (ExceptionService).
 *
 * Runs on the caller's JdbcTemplate and transaction: {@link #afterArrival} inside the transaction that
 * marks the receiving claim applied, {@link #backstop} from the piece sweep (a crash right after the +N
 * commits, or a claim applied by any other path).
 */
public final class ArrivalRevisit {

    private ArrivalRevisit() {}

    /** The skip reasons a later arrival resolves. */
    public static final String REVISIT_REASONS_SQL =
        "('" + PieceShopifyRules.ARRIVAL_UNCONFIRMED + "', '" + PieceShopifyRules.ARRIVAL_NOT_COUNTED + "')";

    /** Every claim that decrements on a piece's departure: the piece engine's, and Step 5a's exchange dispatch. */
    static final String TRIGGERS_SQL =
        "('void_correction', 'hold_enter', 'damage_move', 'piece_write_off', 'piece_sold', 'exchange_dispatch')";

    /** A flipped claim, now 'queued'. */
    public record Flipped(long id, String triggerType) {}

    /** Skipped departures of pieces of this receipt + variant (alias sia; p = the piece). */
    private static final String CANDIDATES_SQL =
        "SELECT sia.id, sia.trigger_type, sia.trigger_id, sia.created_at, p.id AS piece_id " +
        "FROM shopify_inventory_adjustments sia " +
        "JOIN pieces p ON p.id = split_part(sia.trigger_id, ':', 1) AND p.tenant_id = sia.tenant_id " +
        "WHERE sia.tenant_id = ? AND sia.status = 'skipped' AND sia.skip_reason IN " + REVISIT_REASONS_SQL +
        "  AND sia.trigger_type IN " + TRIGGERS_SQL + " AND " + PieceShopifyRules.LIVE_SQL +
        "  AND p.receipt_id = ?::uuid AND p.variant_id = ? " +
        "  AND NOT EXISTS (SELECT 1 FROM shopify_inventory_adjustments seed WHERE seed.tenant_id = sia.tenant_id " +
        "        AND seed.trigger_type = 'initial_seed' AND seed.status = 'applied' AND seed.created_at > sia.created_at) " +
        "ORDER BY sia.created_at, sia.id";

    /**
     * The receiving claim (receipt = its trigger_id, variant) was just marked 'applied' in the caller's transaction:
     * flip its still-away skipped departures to 'queued'. Returns what was flipped, for the caller to send after commit.
     */
    public static List<Flipped> afterArrival(JdbcTemplate jdbc, UUID tenantId, String receiptId, UUID variantId) {
        List<Flipped> flipped = new ArrayList<>();
        for (Map<String, Object> c : jdbc.queryForList(CANDIDATES_SQL, tenantId, receiptId, variantId)) {
            long id = ((Number) c.get("id")).longValue();
            String triggerType = (String) c.get("trigger_type");
            if (!PieceShopifyRules.stillAway(jdbc, tenantId, (String) c.get("piece_id"), triggerType,
                    (String) c.get("trigger_id"), (Timestamp) c.get("created_at"))) {
                continue;
            }
            int n = jdbc.update(
                "UPDATE shopify_inventory_adjustments SET status = 'queued', skip_reason = NULL, " +
                "    payload = COALESCE(payload, '{}'::jsonb) || jsonb_build_object('revisited', " +
                "        jsonb_build_object('wasSkipped', skip_reason, 'receipt', ?::text, 'at', now())) " +
                "WHERE id = ? AND tenant_id = ? AND status = 'skipped' AND skip_reason IN " + REVISIT_REASONS_SQL,
                receiptId, id, tenantId);
            if (n > 0) flipped.add(new Flipped(id, triggerType));
        }
        return flipped;
    }

    /**
     * The piece sweep's backstop: every receipt + variant of this tenant whose receiving claim is 'applied' and that
     * still has a skipped departure waiting — revisited exactly as {@link #afterArrival} (the same guarded flip, so an
     * overlap with the live revisit flips nothing twice).
     */
    public static List<Flipped> backstop(JdbcTemplate jdbc, UUID tenantId) {
        List<Map<String, Object>> due = jdbc.queryForList(
            "SELECT DISTINCT r.trigger_id, r.variant_id FROM shopify_inventory_adjustments r " +
            "WHERE r.tenant_id = ? AND r.trigger_type = 'receiving_session' AND r.status = 'applied' " +
            "  AND EXISTS (SELECT 1 FROM shopify_inventory_adjustments sia " +
            "      JOIN pieces p ON p.id = split_part(sia.trigger_id, ':', 1) AND p.tenant_id = sia.tenant_id " +
            "      WHERE sia.tenant_id = r.tenant_id AND sia.status = 'skipped' AND sia.skip_reason IN " + REVISIT_REASONS_SQL +
            "        AND sia.trigger_type IN " + TRIGGERS_SQL + " AND " + PieceShopifyRules.LIVE_SQL +
            "        AND p.receipt_id::text = r.trigger_id AND p.variant_id = r.variant_id)",
            tenantId);
        List<Flipped> flipped = new ArrayList<>();
        for (Map<String, Object> r : due) {
            flipped.addAll(afterArrival(jdbc, tenantId, (String) r.get("trigger_id"), (UUID) r.get("variant_id")));
        }
        return flipped;
    }
}
