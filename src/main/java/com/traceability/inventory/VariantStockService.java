package com.traceability.inventory;

import com.traceability.tenancy.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * PHASE 0/committed-over-counting fix — the ONE place committed/available/on_hand are
 * derived per variant. {@code CatalogController}, {@code InventoryStockController}, and
 * any future stock-breakdown endpoint MUST call this rather than re-deriving the
 * numbers — no second path.
 *
 * <p><b>committed(variant)</b> = SUM over PICKABLE order lines of
 * {@code MAX(0, order_item.quantity - active_allocations_on_that_line)}, where
 * "pickable" is {@link FulfillService#PICKABLE_SHIPMENT_GATE} — the EXACT SAME gate the
 * Fulfill/Pick&Pack queue uses (shared field, not a forked copy), MINUS that class's
 * {@code placed_at} lookback window (a queue-only UX filter — see below).
 *
 * <p>PHASE 0 fixed the first bug: the original formula filtered on
 * {@code orders.status IN (...,'packed','awaiting_pickup')}, but nothing in the codebase
 * ever advances {@code orders.status} past 'awaiting_pickup' for a courier-fulfilled
 * order, so a packed/shipped/delivered order stayed "committed" forever. The Phase 0 fix
 * narrowed to {@code orders.status = 'new'} alone (the only pre-pack value ever written)
 * plus netting against active allocations.
 *
 * <p>That fix was still wrong, proven on live pilot data (The Snouts tenant, 2026-08):
 * {@code orders.status = 'new'} is necessary but not sufficient. An order AWB-linked to
 * Bosta advances to {@code 'awaiting_pickup'} — fine, Phase 0 already excludes that — but
 * {@code orders.status} can ALSO simply never move at all for orders that were never
 * picked in Traced in the first place: an order whose Shopify webhook never triggered a
 * pack (e.g. fulfilled directly in Shopify, bypassing Traced/Bosta entirely — see the
 * committed-over-counting diagnosis: there is no {@code orders/fulfilled} webhook handler
 * at all) sits at {@code 'new'} indefinitely with its {@code shipments.internal_state}
 * showing 'delivered'/'returned'/'with_courier' — the shipment progressed, but nothing
 * ever touches {@code orders.status} for it because Traced's own pack flow never ran. 40
 * of 45 units counted for one live variant were exactly this: real orders, correctly
 * still 'new', but with a forward shipment already delivered/returned/in-transit — not
 * open backlog by any operational definition. The fix: require the SAME shipment-state
 * gate the queue already uses (self-pickup, OR latest forward shipment
 * {@code internal_state = 'created'}) — an order isn't "open demand" just because
 * {@code orders.status} says 'new'; it has to actually be sitting pre-shipment.
 *
 * <p>The lookback window ({@code FulfillService}'s {@code placed_at} filter) is
 * deliberately NOT reused here — that's a Pick & Pack display filter (don't clutter the
 * queue with ancient orders), not a fact about whether the order still owes its customer
 * stock. A genuinely open order placed 45 days ago is still committed.
 *
 * <p>Subtracting active allocations (not just checking order status) also fixes a
 * second, smaller gap: a piece scanned to an order (allocation status='active') already
 * satisfies that unit of demand even before the order is packed — counting it as still-
 * committed until pack would double-cover the same unit (once as a reserved piece, once
 * as demand).
 *
 * <p><b>on_hand(variant)</b> = COUNT(pieces WHERE status='available' AND at an
 * is_fulfillment location). Reserved/packed/beyond pieces are NOT in this number —
 * once a piece is reserved it already exists to satisfy demand (committed already
 * dropped for that line) and is surfaced separately in the per-status breakdown, not
 * folded into these three numbers.
 *
 * <p><b>available(variant)</b> = on_hand - committed. NOT floored at zero — a
 * negative value is a genuine short (promised more than is physically on hand) and
 * must stay visible, not hidden by clamping.
 */
@Service
public class VariantStockService {

    private final JdbcTemplate jdbc;

    public VariantStockService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record VariantStock(long committed, long available, long onHand) {}

    private static final VariantStock ZERO = new VariantStock(0, 0, 0);

    // Binds tenantId TWICE, in this order: (1) the allocations subquery's own filter,
    // (2) FulfillService.PICKABLE_SHIPMENT_GATE's "WHERE o.tenant_id = ?" — the gate is
    // appended verbatim, not re-typed, so this query can never drift from what the
    // Fulfill queue actually shows as pickable.
    private static final String COMMITTED_SQL = """
        SELECT oi.variant_id,
               SUM(GREATEST(oi.quantity - COALESCE(alloc.active_count, 0), 0)) AS committed
        FROM orders o
        JOIN order_items oi ON oi.order_id = o.id
        LEFT JOIN (
            SELECT a.order_item_id, COUNT(*) AS active_count
            FROM allocations a
            WHERE a.tenant_id = ? AND a.status = 'active'
            GROUP BY a.order_item_id
        ) alloc ON alloc.order_item_id = oi.id
        """ + FulfillService.PICKABLE_SHIPMENT_GATE + """
        GROUP BY oi.variant_id
        """;

    private static final String ON_HAND_SQL = """
        SELECT p.variant_id, COUNT(*) AS on_hand
        FROM pieces p
        WHERE p.tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid
          AND p.status = 'available'::piece_status
          AND p.current_location_id IN (
              SELECT id FROM locations
              WHERE tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid
                AND is_fulfillment = true
          )
        GROUP BY p.variant_id
        """;

    /**
     * Tenant-scoped (RLS via app_user + explicit {@code tenant_id} predicates as
     * defense-in-depth) stock triple for every variant that has either an open-line
     * commitment or on-hand available stock. A variant absent from both maps has
     * zero for all three and is not a key in the returned map — callers must default
     * missing variants via {@link #forVariant}.
     */
    @Transactional(readOnly = true)
    public Map<UUID, VariantStock> computeAll() {
        UUID tenantId = TenantContext.require();

        Map<UUID, Long> committedByVariant = new HashMap<>();
        jdbc.query(COMMITTED_SQL, (RowCallbackHandler) rs -> committedByVariant.put(
            rs.getObject("variant_id", UUID.class), rs.getLong("committed")),
            tenantId, tenantId);

        Map<UUID, Long> onHandByVariant = new HashMap<>();
        jdbc.query(ON_HAND_SQL, (RowCallbackHandler) rs -> onHandByVariant.put(
            rs.getObject("variant_id", UUID.class), rs.getLong("on_hand")));

        Set<UUID> variantIds = new HashSet<>();
        variantIds.addAll(committedByVariant.keySet());
        variantIds.addAll(onHandByVariant.keySet());

        Map<UUID, VariantStock> result = new HashMap<>();
        for (UUID variantId : variantIds) {
            long committed     = committedByVariant.getOrDefault(variantId, 0L);
            long onHandPieces  = onHandByVariant.getOrDefault(variantId, 0L);
            long available     = onHandPieces - committed;
            // on_hand ≡ available + committed by construction (available is defined as
            // on_hand - committed above) — assigned directly rather than re-added.
            result.put(variantId, new VariantStock(committed, available, onHandPieces));
        }
        return result;
    }

    public VariantStock forVariant(Map<UUID, VariantStock> all, UUID variantId) {
        return all.getOrDefault(variantId, ZERO);
    }
}
