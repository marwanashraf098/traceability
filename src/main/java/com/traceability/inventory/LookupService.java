package com.traceability.inventory;

import com.traceability.tenancy.TenantContext;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

@Service
public class LookupService {

    private final JdbcTemplate jdbc;

    public LookupService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ── Phrase key derivation ─────────────────────────────────────────────────

    static String phraseKey(String eventType, String fromStatus, String toStatus) {
        if ("received".equals(eventType))    return "received_at";
        if ("scan".equals(eventType))        return "reserved_for_order";
        if ("unscan".equals(eventType))      return "returned_to_stock";
        if ("pack".equals(eventType))        return "packed_for_order";
        if ("courier_update".equals(eventType)) {
            if ("delivered".equals(toStatus))                       return "courier_delivered";
            if ("with_courier".equals(toStatus))                    return "courier_picked_up";
            if ("awaiting_pickup".equals(toStatus))                 return "courier_awaiting_pickup";
            if ("return_in_transit".equals(toStatus))               return "courier_return_transit";
            if ("return_pending_inspection".equals(toStatus))       return "courier_return_received";
            if ("damaged".equals(toStatus))                         return "courier_damaged";
            if ("lost".equals(toStatus))                            return "courier_lost";
            if ("destroyed".equals(toStatus))                       return "courier_destroyed";
        }
        return "status_changed";
    }

    // ── Piece lookup ──────────────────────────────────────────────────────────

    public Map<String, Object> lookupPiece(String barcode, boolean isWorker) {
        UUID tenantId = TenantContext.require();

        // 1. Piece header — single JOIN query across all context tables.
        Map<String, Object> piece;
        try {
            piece = jdbc.queryForMap(
                "SELECT p.id, p.barcode, p.status, p.created_at AS received_at, " +
                "       v.id AS variant_id, v.title AS variant_title, v.sku, " +
                "       pr.title AS product_title, " +
                "       loc.id AS location_id, loc.name AS location_name, " +
                "       o.id AS order_id, o.number AS order_number, o.status AS order_status, " +
                "       o.customer_name, o.customer_phone, " +
                "       s.id AS shipment_id, s.tracking_number, s.internal_state, " +
                "       r.id AS receipt_id, rl.id AS receipt_location_id, rloc.name AS receipt_location_name " +
                "FROM pieces p " +
                "JOIN variants v  ON v.id = p.variant_id " +
                "JOIN products pr ON pr.id = v.product_id " +
                "LEFT JOIN locations loc ON loc.id = p.current_location_id " +
                "LEFT JOIN orders o      ON o.id  = p.current_order_id " +
                "LEFT JOIN shipments s   ON s.order_id = o.id " +
                "LEFT JOIN receipts r    ON r.id = p.receipt_id " +
                "LEFT JOIN locations rloc ON rloc.id = r.location_id " +
                "LEFT JOIN receipt_lines rl ON rl.variant_id = v.id AND rl.receipt_id = r.id " +
                "WHERE p.barcode = ? AND p.tenant_id = ?",
                barcode, tenantId);
        } catch (EmptyResultDataAccessException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Piece not found");
        }

        // 2. Timeline — all events newest-first, with actor name, order number, tracking.
        List<Map<String, Object>> rawEvents = jdbc.queryForList(
            "SELECT pe.id, pe.event_type, pe.from_status, pe.to_status, pe.occurred_at, " +
            "       pe.order_id, pe.shipment_id, pe.location_id, pe.metadata, " +
            "       u.name AS actor_name, " +
            "       o.number AS order_number, " +
            "       s.tracking_number, " +
            "       loc.name AS location_name " +
            "FROM piece_events pe " +
            "LEFT JOIN users u     ON u.id  = pe.actor_user_id " +
            "LEFT JOIN orders o    ON o.id  = pe.order_id " +
            "LEFT JOIN shipments s ON s.id  = pe.shipment_id " +
            "LEFT JOIN locations loc ON loc.id = pe.location_id " +
            "WHERE pe.piece_id = ? AND pe.tenant_id = ? " +
            "ORDER BY pe.occurred_at DESC, pe.id DESC",
            piece.get("id").toString(), tenantId);

        List<Map<String, Object>> timeline = new ArrayList<>();
        for (Map<String, Object> e : rawEvents) {
            String from   = (String) e.get("from_status");
            String to     = (String) e.get("to_status");
            String eType  = (String) e.get("event_type");
            String actor  = (String) e.get("actor_name");

            Map<String, Object> event = new LinkedHashMap<>();
            event.put("id",           e.get("id"));
            event.put("eventType",    eType);
            event.put("phraseKey",    phraseKey(eType, from, to));
            event.put("actor",        actor != null ? actor : "System");
            event.put("isSystem",     actor == null);
            event.put("fromStatus",   from);
            event.put("toStatus",     to);
            event.put("orderNumber",  e.get("order_number"));
            event.put("orderId",      e.get("order_id") != null ? e.get("order_id").toString() : null);
            event.put("trackingNumber", e.get("tracking_number"));
            event.put("locationName", e.get("location_name"));
            event.put("metadata",     e.get("metadata"));
            event.put("occurredAt",   e.get("occurred_at"));
            timeline.add(event);
        }

        // 3. Assemble response — suppress PII for worker role.
        Map<String, Object> variantMap = new LinkedHashMap<>();
        variantMap.put("id",           piece.get("variant_id"));
        variantMap.put("title",        piece.get("variant_title"));
        variantMap.put("sku",          piece.get("sku"));
        variantMap.put("productTitle", piece.get("product_title"));

        Map<String, Object> locationMap = null;
        if (piece.get("location_id") != null) {
            locationMap = Map.of(
                "id",   piece.get("location_id").toString(),
                "name", piece.get("location_name"));
        }

        Map<String, Object> orderMap = null;
        if (piece.get("order_id") != null) {
            Map<String, Object> om = new LinkedHashMap<>();
            om.put("id",     piece.get("order_id").toString());
            om.put("number", piece.get("order_number"));
            om.put("status", piece.get("order_status"));
            if (!isWorker) {
                om.put("customerName",  piece.get("customer_name"));
                om.put("customerPhone", piece.get("customer_phone"));
            }
            orderMap = om;
        }

        Map<String, Object> shipmentMap = null;
        if (piece.get("shipment_id") != null) {
            shipmentMap = Map.of(
                "id",            piece.get("shipment_id").toString(),
                "trackingNumber", piece.get("tracking_number"),
                "internalState",  piece.get("internal_state"));
        }

        Map<String, Object> sessionMap = null;
        if (piece.get("receipt_id") != null) {
            sessionMap = Map.of(
                "id",           piece.get("receipt_id").toString(),
                "locationName", Objects.toString(piece.get("receipt_location_name"), null));
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type",            "piece");
        result.put("id",              piece.get("id").toString());
        result.put("barcode",         piece.get("barcode"));
        result.put("status",          piece.get("status"));
        result.put("receivedAt",      piece.get("received_at"));
        result.put("variant",         variantMap);
        result.put("currentLocation", locationMap);
        result.put("currentOrder",    orderMap);
        result.put("currentShipment", shipmentMap);
        result.put("receivingSession", sessionMap);
        result.put("timeline",        timeline);
        return result;
    }

    // ── Tracking lookup ───────────────────────────────────────────────────────

    public Map<String, Object> lookupTracking(String trackingNumber) {
        UUID tenantId = TenantContext.require();

        Map<String, Object> shipment;
        try {
            shipment = jdbc.queryForMap(
                "SELECT s.id, s.tracking_number, s.internal_state, " +
                "       o.id AS order_id, o.number AS order_number, o.status AS order_status " +
                "FROM shipments s " +
                "JOIN orders o ON o.id = s.order_id " +
                "WHERE s.tracking_number = ? AND s.tenant_id = ?",
                trackingNumber, tenantId);
        } catch (EmptyResultDataAccessException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Tracking number not found");
        }

        // Pieces linked via allocations on this order (join through order_items).
        List<Map<String, Object>> pieces = jdbc.queryForList(
            "SELECT DISTINCT p.id, p.barcode, p.status " +
            "FROM pieces p " +
            "JOIN allocations a  ON a.piece_id = p.id " +
            "JOIN order_items oi ON oi.id = a.order_item_id " +
            "WHERE oi.order_id = ? AND p.tenant_id = ? " +
            "ORDER BY p.barcode",
            shipment.get("order_id"), tenantId);

        List<Map<String, Object>> pieceList = new ArrayList<>();
        for (Map<String, Object> p : pieces) {
            Map<String, Object> pm = new LinkedHashMap<>();
            pm.put("pieceId", p.get("id").toString());
            pm.put("barcode", p.get("barcode"));
            pm.put("status",  p.get("status"));
            pieceList.add(pm);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type",           "tracking");
        result.put("trackingNumber", shipment.get("tracking_number"));
        result.put("shipmentId",     shipment.get("id").toString());
        result.put("orderId",        shipment.get("order_id").toString());
        result.put("orderNumber",    shipment.get("order_number"));
        result.put("internalState",  shipment.get("internal_state"));
        result.put("pieces",         pieceList);
        return result;
    }
}
