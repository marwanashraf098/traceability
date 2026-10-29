package com.traceability.inventory;

import com.traceability.tenancy.TenantContext;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
public class LookupService {

    /** The label code in a label_bound event's metadata ({"code":"P000033", …}). */
    private static final java.util.regex.Pattern LABEL_CODE = java.util.regex.Pattern.compile("\"code\"\\s*:\\s*\"([^\"]+)\"");


    private final JdbcTemplate jdbc;

    public LookupService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ── Phrase key derivation ─────────────────────────────────────────────────

    static String phraseKey(String eventType, String fromStatus, String toStatus) {
        if ("received".equals(eventType))          return "received_at";
        if ("scan".equals(eventType))              return "reserved_for_order";
        if ("unscan".equals(eventType))            return "returned_to_stock";
        if ("pack".equals(eventType))              return "packed_for_order";
        if ("tracking_linked".equals(eventType))   return "tracking_linked";
        if ("return_received".equals(eventType))   return "return_received";
        if ("restocked".equals(eventType))         return "restocked";
        if ("damaged".equals(eventType))           return "damaged";
        if ("handover".equals(eventType))          return "handover";
        if ("unpacked".equals(eventType))          return "unpacked";
        if ("unreserved".equals(eventType))        return "unreserved";
        // FR-22 transfers — event_type strings ARE the phraseKey names (chosen that way at
        // write time in TransferService so no derivation is needed here).
        if ("transferred_out".equals(eventType))         return "transferred_out";
        if ("returned_from_transfer".equals(eventType))  return "returned_from_transfer";
        if ("condemned_at_vendor".equals(eventType))     return "condemned_at_vendor";
        if ("sold_offbook".equals(eventType))            return "sold_offbook";
        if ("lost_at_vendor".equals(eventType))          return "lost_at_vendor";
        if ("label_reprinted".equals(eventType))         return "label_reprinted";
        // Flexible labeling slice 3 — a label code bound to an untagged piece (Tag stock / packing).
        if ("label_bound".equals(eventType))             return "label_bound";
        // FR-22.10 (B1) — backfilled here: relocated_out was left unmapped and fell through
        // to the generic "status_changed" label; B2's return leg needs the same treatment
        // for consistency, so both are added together.
        if ("relocated_out".equals(eventType))           return "relocated_out";
        // FR-22.11 (B2) — return leg's send-out scan (transferred_out -> out_on_transfer).
        if ("return_transfer_out".equals(eventType))     return "return_transfer_out";
        if ("adjusted".equals(eventType)) {
            if ("available".equals(toStatus) && "damaged".equals(fromStatus)) return "back_to_good";
            if ("available".equals(toStatus))      return "found_it";
            return "adjusted";
        }
        // FR-13.x — void / on-hold. "voided" is terminal (no reverse edge); "held"/"unheld"
        // are the two halves of one reversible hold cycle.
        if ("voided".equals(eventType))            return "voided";
        if ("held".equals(eventType))              return "held";
        if ("unheld".equals(eventType))            return "unheld";
        // C1 (2026-10-25) — Lookup "Sold" and its undo, "Not sold".
        if ("sold_offline".equals(eventType))      return "sold_offline";
        if ("not_sold".equals(eventType))          return "not_sold";
        // 2026-10-08 restock repair: a location fix with no status change.
        if ("location_corrected".equals(eventType)) return "location_corrected";
        // Warehouse places S2 — a piece put on a shelf / in a box, moved between places, or its place cleared
        // (status unchanged; the event carries from_bin_id / to_bin_id → fromPlace / toPlace).
        if ("put_away".equals(eventType))          return "put_away";
        if ("place_moved".equals(eventType))       return "place_moved";
        if ("place_cleared".equals(eventType))     return "place_cleared";
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

    @Transactional(readOnly = true)
    public Map<String, Object> lookupPiece(String barcode, boolean isWorker) {
        UUID tenantId = TenantContext.require();
        String lookup = ScanInputNormalizer.normalizePiece(barcode);   // idempotent; see ScanInputNormalizer

        // 1. Piece header — single JOIN query across all context tables.
        Map<String, Object> piece;
        try {
            piece = jdbc.queryForMap(
                "SELECT p.id, p.barcode, p.short_code, p.status, p.condition, p.created_at AS received_at, " +
                "       v.id AS variant_id, v.title AS variant_title, v.sku, " +
                "       pr.title AS product_title, " +
                "       loc.id AS location_id, loc.name AS location_name, " +
                "       o.id AS order_id, o.number AS order_number, o.status AS order_status, " +
                "       o.customer_name, o.customer_phone, " +
                "       s.id AS shipment_id, s.tracking_number, s.internal_state, " +
                "       r.id AS receipt_id, rloc.name AS receipt_location_name " +
                "FROM pieces p " +
                "JOIN variants v  ON v.id = p.variant_id " +
                "JOIN products pr ON pr.id = v.product_id " +
                "LEFT JOIN locations loc ON loc.id = p.current_location_id " +
                "LEFT JOIN merchant_orders o      ON o.id  = p.current_order_id " +
                "LEFT JOIN shipments s   ON s.order_id = o.id AND s.shipment_leg = 'forward' " +
                "LEFT JOIN receipts r    ON r.id = p.receipt_id " +
                "LEFT JOIN locations rloc ON rloc.id = r.location_id " +
                "WHERE (p.barcode = ? OR p.id = ? OR p.short_code = ?) AND p.tenant_id = ?",
                lookup, lookup, lookup, tenantId);
        } catch (EmptyResultDataAccessException e) {
            // Flexible labeling: no piece carries this code, but it is a printed label nobody has tagged a piece
            // with yet — show what it was printed for instead of "not found".
            var unused = com.traceability.labels.UnusedLabels.find(jdbc, tenantId, lookup);
            if (unused.isPresent()) return unusedLabelResult(unused.get());
            throw new LookupNotFoundException("PIECE_NOT_FOUND", barcode);
        }

        // 2. Timeline — all events newest-first, with actor name, order number, tracking.
        // (label_bound rows also carry labelCode and a packing / Tag stock phraseKey — flexible labeling slice 5.)
        List<Map<String, Object>> rawEvents = jdbc.queryForList(
            "SELECT pe.id, pe.event_type, pe.from_status, pe.to_status, pe.occurred_at, " +
            "       pe.order_id, pe.shipment_id, pe.location_id, pe.metadata, " +
            "       u.name AS actor_name, " +
            "       o.number AS order_number, " +
            "       s.tracking_number, " +
            "       loc.name AS location_name, " +
            "       lr.factory_name AS run_factory, lr.reference AS run_reference, " +
            "       fb.code AS from_place, tb.code AS to_place " +
            "FROM piece_events pe " +
            "LEFT JOIN users u     ON u.id  = pe.actor_user_id " +
            "LEFT JOIN merchant_orders o    ON o.id  = pe.order_id " +
            "LEFT JOIN shipments s ON s.id  = pe.shipment_id " +
            "LEFT JOIN locations loc ON loc.id = pe.location_id " +
            // Flexible labeling slice 6b: a factory piece's received event names its run.
            "LEFT JOIN label_runs lr ON pe.event_type = 'received' AND lr.tenant_id = pe.tenant_id " +
            "      AND lr.id = (pe.metadata ->> 'label_run_id')::uuid " +
            // Warehouse places S2: the places a place event names.
            "LEFT JOIN bins fb ON fb.id = pe.from_bin_id " +
            "LEFT JOIN bins tb ON tb.id = pe.to_bin_id " +
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
            if ("label_bound".equals(eType)) {
                // Slice 5: which label went on, and where — at packing (the event names the order) or in Tag stock.
                String meta = e.get("metadata") == null ? "" : e.get("metadata").toString();
                java.util.regex.Matcher code = LABEL_CODE.matcher(meta);
                event.put("labelCode", code.find() ? code.group(1) : null);
                event.put("phraseKey", meta.contains("\"tag_session_id\"") ? "label_bound_tag"
                    : e.get("order_id") != null ? "label_bound_packing" : "label_bound");
            }
            if ("received".equals(eType) && e.get("run_factory") != null) {
                event.put("phraseKey", "received_factory_run");
                event.put("factoryRun", com.traceability.labels.FactoryReceivingService.runName(
                    (String) e.get("run_factory"), (String) e.get("run_reference")));
            }
            if (PLACE_EVENTS.contains(eType)) {
                event.put("fromPlace", e.get("from_place"));
                event.put("toPlace",   e.get("to_place"));
            }
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
        result.put("shortCode",       piece.get("short_code"));
        result.put("status",          piece.get("status"));
        // An untagged piece (flexible labeling) has no code to reprint, whatever its status.
        result.put("canReprint",      piece.get("short_code") != null
                                      && PieceLabelReprintService.canReprint(Objects.toString(piece.get("status"), null)));
        result.put("condition",       piece.get("condition"));
        result.put("receivedAt",      piece.get("received_at"));
        result.put("variant",         variantMap);
        result.put("currentLocation", locationMap);
        result.put("currentOrder",    orderMap);
        result.put("currentShipment", shipmentMap);
        result.put("receivingSession", sessionMap);
        result.put("timeline",        timeline);
        // C1: "Not sold" is offered only for a Lookup sale (sold_offline) — never for a transfer's
        // off-book sale. Present only on sold pieces; every other response is unchanged.
        if ("sold".equals(Objects.toString(piece.get("status"), null))) {
            result.put("canUndoSold", PieceAdjustService.isLookupSale(jdbc, tenantId, piece.get("id").toString()));
        }
        // C2: Lookup's Exchange — offered on an available piece to an owner / manager of a store that ships with its own
        // courier (LookupExchangeService.gateOpen); a piece reserved to a Lookup exchange names it. Both keys exist only
        // in those cases — every other response is unchanged.
        String status = Objects.toString(piece.get("status"), null);
        if (!isWorker && "available".equals(status)
                && com.traceability.portal.LookupExchangeService.gateOpen(jdbc, tenantId)) {
            result.put("canExchange", true);
        }
        if (!isWorker && ("reserved".equals(status) || "packed".equals(status))) {
            Map<String, Object> ex = com.traceability.portal.LookupExchangeService.reservedTo(jdbc, tenantId, piece.get("id").toString());
            if (ex != null) result.put("lookupExchange", ex);
        }
        // Flexible labeling: the variant's Available / Labeled / Untagged counts (only for tenants
        // that have it — everyone else's response is unchanged).
        if (com.traceability.labels.FlexibleLabeling.enabled(jdbc, tenantId)) {
            result.put("variantStock", UntaggedPieces.availableCounts(jdbc, tenantId, (UUID) piece.get("variant_id")));
        }
        // Warehouse places S2: the piece's place (with its path) and whether Move applies — only for tenants
        // that have places (everyone else's response is unchanged). A box's moves since the piece went into it
        // join the timeline.
        if (com.traceability.places.Places.enabled(jdbc, tenantId)) {
            UUID binId = jdbc.queryForObject("SELECT bin_id FROM pieces WHERE id = ?", UUID.class, piece.get("id").toString());
            var place = com.traceability.places.PlacePaths.describe(jdbc, tenantId, binId);
            result.put("place", place == null ? null : placeMap(place));
            result.put("canPlace", piece.get("short_code") != null && piece.get("location_id") != null
                && com.traceability.places.Places.PLACEABLE_STATUSES.contains(Objects.toString(piece.get("status"), "")));
            if (place != null && "box".equals(place.kind())) addBoxMoves(tenantId, piece.get("id").toString(), place, timeline);
        }
        return result;
    }

    private static final java.util.Set<String> PLACE_EVENTS = java.util.Set.of("put_away", "place_moved", "place_cleared");

    static Map<String, Object> placeMap(com.traceability.places.PlacePaths.Place p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", p.id().toString());
        m.put("kind", p.kind());
        m.put("code", p.code());
        m.put("trail", p.trail());
        m.put("parentCode", p.parentCode());
        m.put("parentKind", p.parentKind());
        return m;
    }

    /**
     * The box the piece is in now: its moves (bin_events 'moved') since the piece went into it, as timeline entries
     * ("Box BOX-07 moved from Z1 to Z1-R01-S2"), merged newest first.
     */
    private void addBoxMoves(UUID tenantId, String pieceId, com.traceability.places.PlacePaths.Place box,
                             List<Map<String, Object>> timeline) {
        List<Map<String, Object>> moves = jdbc.queryForList(
            "SELECT be.id, be.occurred_at, u.name AS actor_name, fp.code AS from_place, tp.code AS to_place " +
            "FROM bin_events be LEFT JOIN users u ON u.id = be.actor_user_id " +
            "LEFT JOIN bins fp ON fp.id = be.from_parent_id LEFT JOIN bins tp ON tp.id = be.to_parent_id " +
            "WHERE be.tenant_id = ? AND be.bin_id = ? AND be.event_type = 'moved' " +
            "  AND be.occurred_at >= (SELECT max(pe.occurred_at) FROM piece_events pe WHERE pe.piece_id = ? " +
            "                         AND pe.tenant_id = ? AND pe.to_bin_id = ?) " +
            "ORDER BY be.occurred_at DESC, be.id DESC",
            tenantId, box.id(), pieceId, tenantId, box.id());
        if (moves.isEmpty()) return;
        for (Map<String, Object> m : moves) {
            Map<String, Object> event = new LinkedHashMap<>();
            String actor = (String) m.get("actor_name");
            event.put("id",           "bin-" + m.get("id"));
            event.put("eventType",    "box_moved");
            event.put("phraseKey",    "box_moved");
            event.put("box",          box.code());
            event.put("fromPlace",    m.get("from_place"));
            event.put("toPlace",      m.get("to_place"));
            event.put("actor",        actor != null ? actor : "System");
            event.put("isSystem",     actor == null);
            event.put("fromStatus",   null);
            event.put("toStatus",     null);
            event.put("orderNumber",  null);
            event.put("orderId",      null);
            event.put("trackingNumber", null);
            event.put("locationName", null);
            event.put("metadata",     null);
            event.put("occurredAt",   m.get("occurred_at"));
            timeline.add(event);
        }
        timeline.sort((a, b) -> ((java.util.Date) b.get("occurredAt")).compareTo((java.util.Date) a.get("occurredAt")));
    }

    /**
     * Warehouse places S2 — a scanned place sticker in Lookup: {type: "place", code, kind, trail, parentCode,
     * parentKind, locationName, pieces, archived}. Null (the caller carries on as before) unless the tenant has
     * places and the sticker is one of its places.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> lookupPlaceSticker(String scanCode) {
        UUID tenantId = TenantContext.require();
        if (!com.traceability.places.Places.enabled(jdbc, tenantId)) return null;
        List<UUID> bin = jdbc.queryForList("SELECT id FROM bins WHERE tenant_id = ? AND scan_code = ?",
            UUID.class, tenantId, scanCode);
        if (bin.isEmpty()) return null;
        var place = com.traceability.places.PlacePaths.describe(jdbc, tenantId, bin.get(0));
        Map<String, Object> result = new LinkedHashMap<>(placeMap(place));
        result.put("type", "place");
        result.put("scanCode", place.scanCode());
        result.put("archived", place.archived());
        result.put("locationName", jdbc.queryForObject("SELECT name FROM locations WHERE id = ?", String.class, place.locationId()));
        result.put("pieces", jdbc.queryForObject("SELECT count(*)::int FROM pieces WHERE tenant_id = ? AND bin_id = ?",
            Integer.class, tenantId, place.id()));
        return result;
    }

    /**
     * {type: "label_code", code, status: "issued", variant{id, title, sku, productTitle}, messageEn, messageAr} —
     * an issued label code (no piece yet). Lookup shows it with "tag it in Tag stock first".
     */
    private static Map<String, Object> unusedLabelResult(com.traceability.labels.UnusedLabels.Unused u) {
        Map<String, Object> variant = new LinkedHashMap<>();
        variant.put("id", u.variantId());
        variant.put("title", u.variantTitle());
        variant.put("sku", u.sku());
        variant.put("productTitle", u.productTitle());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", "label_code");
        result.put("code", u.code());
        result.put("status", "issued");
        result.put("variant", variant);
        result.put("kind", u.kind());
        result.put("messageEn", com.traceability.labels.UnusedLabels.messageEnFor(u));
        result.put("messageAr", com.traceability.labels.UnusedLabels.messageArFor(u));
        return result;
    }

    // ── Tracking lookup ───────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public Map<String, Object> lookupTracking(String query) {
        UUID tenantId = TenantContext.require();

        // Normalize the search query so scanning or pasting a prefixed label
        // (e.g. "D-07-2944282510") resolves to the bare numeric form stored in the DB.
        // If normalization yields a non-null value use it; otherwise try the raw query
        // unchanged so that any edge-case tracking format still has a chance to match.
        String normalized = TrackingNumberNormalizer.normalize(query);
        String trackingNumber = normalized != null ? normalized : query;

        Map<String, Object> shipment;
        try {
            shipment = jdbc.queryForMap(
                "SELECT s.id, s.tracking_number, s.internal_state, " +
                "       o.id AS order_id, o.number AS order_number, o.status AS order_status " +
                "FROM shipments s " +
                "JOIN merchant_orders o ON o.id = s.order_id " +
                "WHERE s.tracking_number = ? AND s.tenant_id = ? AND s.provider = 'bosta'",
                trackingNumber, tenantId);
        } catch (EmptyResultDataAccessException e) {
            throw new LookupNotFoundException("TRACKING_NOT_FOUND", query);
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

    // ── Order lookup (FR-13.x) ────────────────────────────────────────────────

    /**
     * Resolves '#1042', '1042', or a full Shopify order name to its order — IDENTITY ONLY
     * (id + number), deliberately not a second order-detail data path. The full order detail
     * (customer, items, shipments, hold state, derived status, etc.) already has exactly one
     * tenant-scoped source — GET /api/v1/orders/{id} (OrderController.detail()) — which is
     * what OrderDrawer calls; this method exists only to turn a typed order number into the
     * id that endpoint needs, mirroring lookupTracking()'s "resolve then hand off" shape.
     * The frontend opens the SAME OrderDrawer used everywhere else with the resolved id — see
     * CLAUDE.md decision, approved by Marawan, on consolidating onto the existing order-detail
     * path instead of a parallel lookup-specific fetch.
     *
     * orders.number is stored exactly as Shopify's "name" field, WITH the leading '#' (see
     * ShopifySyncService — payload.path("name")) — so a bare-digit query is tried both as-is
     * and with '#' prepended. No UNIQUE constraint exists on (tenant_id, number) (only
     * (store_id, external_id) is unique) — ORDER BY placed_at DESC picks the most recent
     * match, same "most recent wins" convention used elsewhere in this codebase for a
     * non-unique lookup key.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> lookupOrder(String query) {
        UUID tenantId = TenantContext.require();
        String trimmed = query.trim();
        String withHash = trimmed.startsWith("#") ? trimmed : "#" + trimmed;

        record OrderRow(UUID id, String number) {}
        OrderRow order = jdbc.query(
            "SELECT id, number FROM merchant_orders " +
            "WHERE tenant_id = ? AND number IN (?, ?) " +
            "ORDER BY placed_at DESC NULLS LAST, id DESC LIMIT 1",
            rs -> rs.next() ? new OrderRow(rs.getObject("id", UUID.class), rs.getString("number")) : null,
            tenantId, trimmed, withHash);
        if (order == null) {
            throw new LookupNotFoundException("ORDER_NOT_FOUND", query);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type",        "order");
        result.put("orderId",     order.id().toString());
        result.put("orderNumber", order.number());
        return result;
    }
}
