package com.traceability.inventory;

import com.traceability.tenancy.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Unpack-order S2 (approved 2026-10-31) — the "Packed, not picked up" list on Pick &amp; Pack, both packing
 * modes. An order is on it while it is packed and still physically in the warehouse:
 * {@link #IN_WAREHOUSE_SQL}, THE one definition (FulfillService.unpackOrder's guards agree with it,
 * UnpackOrderTest pins that).
 *
 * orders.status alone can't say it: it never moves past 'awaiting_pickup' (a closed pickup session moves the
 * pieces to with_courier and leaves the order status) — so the pieces and the forward leg's history decide.
 * Read-only; RLS scopes it.
 */
@Service
public class PackedOrderService {

    /**
     * Packed and still in the warehouse — needs an orders alias {@code o}:
     * status packed / awaiting_pickup / self_pickup_pending, no cancel requested (those go through guided unpack),
     * the forward leg never moved past 'created' (history, like the ALREADY_SHIPPED guard), at least one packed
     * allocation whose piece is packed / awaiting_pickup, and none whose piece left custody.
     */
    public static final String IN_WAREHOUSE_SQL =
        "o.status IN ('packed', 'awaiting_pickup', 'self_pickup_pending') " +
        "AND o.cancel_requested_at IS NULL " +
        "AND NOT " + OrderLineFreeze.shippedPastCreatedSql("o.id", "o.tenant_id") + " " +
        "AND EXISTS (SELECT 1 FROM allocations a_iw JOIN order_items oi_iw ON oi_iw.id = a_iw.order_item_id " +
        "            JOIN pieces p_iw ON p_iw.id = a_iw.piece_id " +
        "            WHERE oi_iw.order_id = o.id AND oi_iw.tenant_id = o.tenant_id AND a_iw.status = 'packed' " +
        "              AND p_iw.status IN ('packed', 'awaiting_pickup')) " +
        "AND NOT EXISTS (SELECT 1 FROM allocations a_lc JOIN order_items oi_lc ON oi_lc.id = a_lc.order_item_id " +
        "            JOIN pieces p_lc ON p_lc.id = a_lc.piece_id " +
        "            WHERE oi_lc.order_id = o.id AND oi_lc.tenant_id = o.tenant_id AND a_lc.status = 'packed' " +
        "              AND p_lc.status::text IN (" + OrderLineFreeze.leftCustodySqlList() + ")) ";

    /**
     * The order's open pickup session holding one of its shipments, if any — LATERAL on {@code o}, alias
     * {@code ops}, columns pickup_id, scheduled_date, scheduled_time_slot. A Bosta-scheduled pickup row
     * (session_status 'closed' from the start) moves no pieces and is not one.
     */
    static final String OPEN_PICKUP_SESSION_LATERAL =
        "LEFT JOIN LATERAL ( " +
        "    SELECT pu.id AS pickup_id, pu.scheduled_date, pu.scheduled_time_slot " +
        "    FROM pickup_shipments ps " +
        "    JOIN pickups pu ON pu.id = ps.pickup_id AND pu.tenant_id = ps.tenant_id " +
        "    JOIN shipments sh ON sh.id = ps.shipment_id AND sh.tenant_id = ps.tenant_id " +
        "    WHERE sh.order_id = o.id AND sh.tenant_id = o.tenant_id AND pu.session_status = 'open' " +
        // UUIDv4 is not time-ordered — order by created_at, never id (see CLAUDE.md invariant)
        "    ORDER BY pu.created_at DESC, pu.id DESC LIMIT 1 " +
        ") ops ON true ";

    public record PackedOrderRow(UUID orderId, String orderNumber, String customerName, String status,
                                 boolean selfPickup, String trackingNumber, String courier, String carrierName,
                                 Instant packedAt, String packedByName, int pieces,
                                 UUID pickupSessionId, LocalDate pickupDate, String pickupTimeSlot,
                                 boolean editConflict, boolean codMismatch, BigDecimal bostaCod, BigDecimal orderCod) {}

    private final JdbcTemplate jdbc;

    public PackedOrderService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Packed, not picked up — newest pack first. */
    @Transactional(readOnly = true)
    public List<PackedOrderRow> list() {
        UUID tenantId = TenantContext.require();
        return jdbc.query(
            "SELECT o.id AS order_id, o.number, o.customer_name, o.status::text AS status, o.is_self_pickup, " +
            "       o.payment_method::text AS payment_method, o.cod_amount AS order_cod, " +
            "       (o.shopify_edit_conflict_at IS NOT NULL) AS edit_conflict, " +
            "       s.tracking_number, s.provider, s.carrier_name, s.cod_amount AS leg_cod, " +
            "       pk.pieces, pe.created_at AS packed_at, pe.packed_by, " +
            "       ops.pickup_id, ops.scheduled_date, ops.scheduled_time_slot " +
            "FROM merchant_orders o " +
            // The newest forward leg (UUIDv4 is not time-ordered — created_at, id only as the tie-break).
            "LEFT JOIN LATERAL ( " +
            "    SELECT fs.tracking_number, fs.provider::text AS provider, fs.carrier_name, fs.cod_amount " +
            "    FROM shipments fs WHERE fs.order_id = o.id AND fs.tenant_id = o.tenant_id AND fs.shipment_leg = 'forward' " +
            "    ORDER BY fs.created_at DESC, fs.id DESC LIMIT 1 " +
            ") s ON true " +
            "LEFT JOIN LATERAL ( " +
            "    SELECT COUNT(*)::int AS pieces FROM allocations a JOIN order_items oi ON oi.id = a.order_item_id " +
            "    WHERE oi.order_id = o.id AND oi.tenant_id = o.tenant_id AND a.status = 'packed' " +
            ") pk ON true " +
            // Latest pack: the newest 'pack' piece event of the order (bigserial id only as the tie-break).
            "LEFT JOIN LATERAL ( " +
            "    SELECT e.created_at, u.name AS packed_by FROM piece_events e LEFT JOIN users u ON u.id = e.actor_user_id " +
            "    WHERE e.order_id = o.id AND e.tenant_id = o.tenant_id AND e.event_type = 'pack' " +
            "    ORDER BY e.created_at DESC, e.id DESC LIMIT 1 " +
            ") pe ON true " +
            OPEN_PICKUP_SESSION_LATERAL +
            "WHERE o.tenant_id = ? AND " + IN_WAREHOUSE_SQL +
            "ORDER BY pe.created_at DESC NULLS LAST, o.number ASC",
            (rs, i) -> {
                String provider = rs.getString("provider");
                BigDecimal legCod = rs.getBigDecimal("leg_cod");
                BigDecimal orderCod = rs.getBigDecimal("order_cod");
                // Bosta legs only (a generic courier's COD isn't Traced's to compare), COD orders only.
                boolean mismatch = "bosta".equals(provider) && "cod".equals(rs.getString("payment_method"))
                    && legCod != null && orderCod != null && legCod.compareTo(orderCod) != 0;
                Timestamp packedAt = rs.getTimestamp("packed_at");
                java.sql.Date pickupDate = rs.getDate("scheduled_date");
                return new PackedOrderRow(
                    rs.getObject("order_id", UUID.class), rs.getString("number"), rs.getString("customer_name"),
                    rs.getString("status"), rs.getBoolean("is_self_pickup"),
                    rs.getString("tracking_number"), provider, rs.getString("carrier_name"),
                    packedAt == null ? null : packedAt.toInstant(), rs.getString("packed_by"), rs.getInt("pieces"),
                    rs.getObject("pickup_id", UUID.class), pickupDate == null ? null : pickupDate.toLocalDate(),
                    rs.getString("scheduled_time_slot"), rs.getBoolean("edit_conflict"),
                    mismatch, mismatch ? legCod : null, mismatch ? orderCod : null);
            },
            tenantId);
    }
}
