package com.traceability.inventory;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Review mode S7 — what a click-to-scan chip offers on each scan screen, for tenants with the
 * scanHelpers capability only (the controller checks it: ReviewCapabilities). Read-only; at most
 * {@link #LIMIT} candidates; each is something the screen's own scan would accept right now. A chip
 * click goes through the screen's normal scan handler — nothing here scans anything.
 *
 * Every row is {@code code} (what gets "scanned") + {@code label} (what the chip shows next to it).
 */
@Service
public class ScanHelperService {

    static final int LIMIT = 5;

    private final JdbcTemplate jdbc;
    private final int lookbackDays;

    public ScanHelperService(JdbcTemplate jdbc, @Value("${shopify.import.lookback-days:30}") int lookbackDays) {
        this.jdbc = jdbc;
        this.lookbackDays = lookbackDays;
    }

    /** Pick a piece: available pieces of the variant (queue-mode pick screen, waybill-mode order card). */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> pieces(UUID tenantId, UUID variantId) {
        return jdbc.queryForList(
            "SELECT p.barcode AS code, p.short_code::text AS label FROM pieces p " +
            "WHERE p.tenant_id = ? AND p.variant_id = ? AND p.status = 'available' " +
            "ORDER BY p.short_code LIMIT " + LIMIT, tenantId, variantId);
    }

    /**
     * Waybill-scan mode, no order open: the waybills of orders in the Pick &amp; Pack queue (the
     * queue's own predicate, FulfillService.PICKABLE_ORDERS_FILTER) that nobody is packing yet.
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> waybills(UUID tenantId) {
        return jdbc.queryForList(
            "SELECT fs.tracking_number AS code, o.number AS label FROM merchant_orders o " +
            "JOIN LATERAL ( " +
            "    SELECT tracking_number FROM shipments " +
            "    WHERE order_id = o.id AND tenant_id = o.tenant_id AND shipment_leg = 'forward' " +
            "    ORDER BY created_at DESC, id DESC LIMIT 1 " +
            ") fs ON fs.tracking_number IS NOT NULL " +
            FulfillService.PICKABLE_ORDERS_FILTER +
            "  AND o.is_self_pickup = false AND o.locked_by IS NULL " +
            "  AND NOT EXISTS (SELECT 1 FROM allocations a JOIN order_items oi ON oi.id = a.order_item_id " +
            "                  WHERE oi.order_id = o.id AND a.status IN ('active','packed')) " +
            "ORDER BY o.placed_at ASC, o.id LIMIT " + LIMIT, tenantId, lookbackDays);
    }

    /**
     * Pickup session: forward waybills whose pieces are all packed / awaiting pickup (what the
     * pickup scan accepts — PickupSessionService NOT_PACKED rule) and not on any open session yet.
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> pickup(UUID tenantId) {
        return jdbc.queryForList(
            "SELECT s.tracking_number AS code, o.number AS label FROM shipments s " +
            "JOIN merchant_orders o ON o.id = s.order_id AND o.tenant_id = s.tenant_id " +
            "WHERE s.tenant_id = ? AND s.shipment_leg = 'forward' AND s.tracking_number IS NOT NULL " +
            "  AND s.internal_state = 'created' " +
            "  AND EXISTS (SELECT 1 FROM allocations a JOIN order_items oi ON oi.id = a.order_item_id " +
            "              WHERE oi.order_id = o.id AND a.status IN ('active','packed')) " +
            "  AND NOT EXISTS (SELECT 1 FROM allocations a JOIN order_items oi ON oi.id = a.order_item_id " +
            "                  JOIN pieces p ON p.id = a.piece_id " +
            "                  WHERE oi.order_id = o.id AND a.status IN ('active','packed') " +
            "                    AND p.status::text NOT IN ('packed','awaiting_pickup')) " +
            "  AND NOT EXISTS (SELECT 1 FROM pickup_shipments ps JOIN pickups pk ON pk.id = ps.pickup_id " +
            "                  WHERE ps.shipment_id = s.id AND pk.session_status = 'open') " +
            "ORDER BY o.placed_at ASC, o.id LIMIT " + LIMIT, tenantId);
    }

    /** Return session: delivered pieces still awaited on an open return request (scan attribution's set). */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> returns(UUID tenantId) {
        return jdbc.queryForList(
            "SELECT p.barcode AS code, o.number AS label FROM return_request_items i " +
            "JOIN return_requests r ON r.id = i.request_id AND r.tenant_id = i.tenant_id " +
            "JOIN pieces p ON p.id = i.piece_id AND p.tenant_id = i.tenant_id " +
            "JOIN merchant_orders o ON o.id = r.order_id AND o.tenant_id = r.tenant_id " +
            "WHERE i.tenant_id = ? AND i.active AND i.item_status = 'awaiting' " +
            "  AND r.status IN ('approved','pickup_booked','received') AND p.status = 'delivered' " +
            "ORDER BY r.created_at ASC, i.id LIMIT " + LIMIT, tenantId);
    }

    /** Lookup: a few pieces worth tracing — furthest along their journey first. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> lookup(UUID tenantId) {
        return jdbc.queryForList(
            "SELECT p.barcode AS code, p.status::text AS label FROM pieces p WHERE p.tenant_id = ? " +
            "ORDER BY CASE p.status::text WHEN 'delivered' THEN 0 WHEN 'with_courier' THEN 1 " +
            "         WHEN 'packed' THEN 2 ELSE 3 END, p.short_code LIMIT 3", tenantId);
    }
}
