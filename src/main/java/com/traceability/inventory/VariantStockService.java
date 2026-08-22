package com.traceability.inventory;

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
 * PHASE 0 committed-inventory fix — the ONE place committed/available/on_hand are
 * derived per variant. {@code CatalogController} and any future stock-breakdown
 * endpoint (e.g. the planned {@code /inventory/stock}) MUST call this rather than
 * re-deriving the numbers — no second path.
 *
 * Root cause this replaces: the old formula filtered on {@code orders.status IN
 * (...,'packed','awaiting_pickup')}, but nothing in the codebase ever advances
 * {@code orders.status} past 'awaiting_pickup' for a courier-fulfilled order — not
 * on courier pickup, not on delivery, not ever (confirmed by grepping every
 * {@code UPDATE orders SET status}: only 'packed'/'self_pickup_pending' (pack),
 * 'awaiting_pickup' (AWB-link), 'delivered' (self-pickup handover only), and
 * 'cancelled' are ever written). So a packed/shipped/delivered order stayed
 * "committed" forever.
 *
 * <p><b>committed(variant)</b> = SUM over OPEN order lines of
 * {@code MAX(0, order_item.quantity - active_allocations_on_that_line)}, where
 * "open" = {@code orders.status = 'new'}. 'new' is the ONLY pre-pack value ever
 * written to {@code orders.status} — the enum also defines 'confirmed'/'ready_to_pick'/
 * 'picking' but grepping every {@code UPDATE orders SET status} in this codebase
 * shows none of the three is ever written anywhere (that sub-flow was never built),
 * so a whitelist including them would wrongly imply orders can reach those states
 * today. Using 'new' alone: (1) is exactly what both order-ingest paths
 * ({@code ShopifySyncService.UPSERT_ORDER}, {@code ExchangeService}'s order insert)
 * leave behind, since neither specifies the {@code status} column and it defaults to
 * 'new'; (2) already excludes cancelled orders with no extra clause needed —
 * {@code FulfillService.cancelOrder()} rewrites a pre-pack order's status straight to
 * 'cancelled' synchronously, in the same transaction, confirmed by tracing the
 * zero-packed-pieces branch directly (no deferred/flag-only cancellation exists for
 * an order still in 'new'). If 'confirmed'/'ready_to_pick'/'picking' are ever wired
 * up, this predicate must be revisited alongside that work — do not widen it
 * preemptively. Once an order leaves 'new' it is excluded from committed entirely,
 * regardless of piece status — packed/awaiting_pickup/with_courier/etc. are
 * deliberately NOT read from {@code orders.status} again, because that column stops
 * updating right there.
 *
 * <p>Subtracting active allocations (not just checking order status) also fixes a
 * second, smaller gap in the old formula: a piece scanned to an order (allocation
 * status='active') already satisfies that unit of demand even before the order is
 * packed — the old formula counted it as still-committed until the whole order
 * packed, double-covering the same unit (once as a reserved piece, once as demand).
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

    private static final String COMMITTED_SQL = """
        SELECT oi.variant_id,
               SUM(GREATEST(oi.quantity - COALESCE(alloc.active_count, 0), 0)) AS committed
        FROM order_items oi
        JOIN orders o ON o.id = oi.order_id
        LEFT JOIN (
            SELECT a.order_item_id, COUNT(*) AS active_count
            FROM allocations a
            WHERE a.tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid
              AND a.status = 'active'
            GROUP BY a.order_item_id
        ) alloc ON alloc.order_item_id = oi.id
        WHERE oi.tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid
          AND o.status = 'new'
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
        Map<UUID, Long> committedByVariant = new HashMap<>();
        jdbc.query(COMMITTED_SQL, (RowCallbackHandler) rs -> committedByVariant.put(
            rs.getObject("variant_id", UUID.class), rs.getLong("committed")));

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
