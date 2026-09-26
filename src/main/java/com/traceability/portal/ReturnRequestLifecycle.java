package com.traceability.portal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

/**
 * Returns Step 4d-1 — THE one place a return request's lifecycle moves after approval:
 * linking its return leg, attributing intake scans to its items, releasing item
 * reservations, and deriving received / refund_pending / closed. Also the only writer of
 * return_request_events (append-only request history).
 *
 * Not a Spring bean: every caller builds it from its OWN JdbcTemplate (like
 * {@link PickupAreaService}), so it joins the caller's transaction and an instance built
 * on an app_user connection reads and writes under RLS. Every statement also filters
 * tenant_id explicitly (defence in depth).
 *
 * Never moves a piece: InventoryLedger stays the only piece-status writer. Only
 * return_requests, return_request_items and return_request_events are written here.
 *
 * Item states: awaiting → arrived (a scan attributed to it) → done (its piece got a final
 * disposition: restocked or damaged — 'mismatch' is not final), or not_coming. `active`
 * (the one-live-item-per-piece flag) is kept equal to item_status IN ('awaiting','arrived')
 * — enforced by a CHECK (V108) — so a done / not_coming item always releases its piece.
 *
 * Request states derived by {@link #reevaluate}: from approved / pickup_booked, 'received'
 * once every item that isn't not_coming has arrived (or is done) and at least one did;
 * then 'refund_pending' once every such item is done.
 *
 * Step 4d-2: also the only writer of return_refunds (append-only — a refund is cancelled by a
 * 'void' row, never updated or deleted) and of 'refunded' ({@link #markRefunded}).
 */
public class ReturnRequestLifecycle {

    /** Requests whose items a scan may be attributed to, and that re-evaluation moves. */
    public static final Set<String> OPEN_STATUSES = Set.of("approved", "pickup_booked", "received");
    /** Statuses the merchant may close from. */
    static final Set<String> CLOSABLE = Set.of("approved", "pickup_booked", "received", "refund_pending");
    static final Set<String> CLOSE_REASONS = Set.of("no_refund", "other");
    static final int CLOSE_NOTE_MAX = 300;

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * THE hand-booked-leg candidate rule (alias {@code rr} = return_requests): same order,
     * approved, holding no leg and no Traced tracking number, booking never attempted or
     * definitely failed, created at/before the leg's Bosta createdAt. Shared by
     * {@link #autoMatchLeg} (binds) and ExceptionService's return_link_ambiguous detector
     * (correlated) — never write a second copy.
     */
    public static String linkCandidateSql(String tenantExpr, String orderExpr, String bostaCreatedAtExpr) {
        return "rr.tenant_id = " + tenantExpr + " AND rr.order_id = " + orderExpr + " AND rr.status = 'approved' " +
               "AND rr.return_shipment_id IS NULL AND rr.bosta_tracking_number IS NULL " +
               "AND (rr.booking_status IS NULL OR rr.booking_status = 'failed') " +
               "AND rr.created_at <= " + bostaCreatedAtExpr + " ";
    }

    /** A return leg's (alias {@code s}) Bosta raw.createdAt as timestamptz; NULL when absent or not ISO-shaped. */
    public static final String LEG_BOSTA_CREATED_AT_SQL =
        "(CASE WHEN s.raw->>'createdAt' ~ '^\\d{4}-\\d{2}-\\d{2}T' THEN (s.raw->>'createdAt')::timestamptz END)";

    private final JdbcTemplate jdbc;

    public ReturnRequestLifecycle(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ── History ───────────────────────────────────────────────────────────────

    /** Appends one request event. {@code actor} null = the system. */
    public void event(UUID tenantId, UUID requestId, String type, UUID actor, Map<String, Object> metadata) {
        jdbc.update(
            "INSERT INTO return_request_events (tenant_id, request_id, event_type, actor, metadata) " +
            "VALUES (?, ?, ?, ?, ?::jsonb)",
            tenantId, requestId, type, actor, toJson(metadata));
    }

    public List<Map<String, Object>> events(UUID tenantId, UUID requestId) {
        return jdbc.query(
            "SELECT e.event_type, e.actor, u.name AS actor_name, e.occurred_at, e.metadata::text AS metadata " +
            "FROM return_request_events e LEFT JOIN users u ON u.id = e.actor " +
            "WHERE e.request_id = ? AND e.tenant_id = ? ORDER BY e.occurred_at, e.id",
            (rs, i) -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("type", rs.getString("event_type"));
                Object actor = rs.getObject("actor");
                m.put("actorId", actor == null ? null : actor.toString());
                m.put("actorName", rs.getString("actor_name"));
                m.put("occurredAt", rs.getTimestamp("occurred_at").toInstant().toString());
                m.put("metadata", fromJson(rs.getString("metadata")));
                return m;
            },
            requestId, tenantId);
    }

    // ── Linking a return leg ──────────────────────────────────────────────────

    /**
     * A request whose Traced booking produced this tracking number gets the leg
     * (link_source 'traced_booking'). Skipped when the leg is already another request's.
     */
    public void linkByTracking(UUID tenantId, String trackingNumber, UUID shipmentId) {
        List<UUID> linked = jdbc.queryForList(
            "UPDATE return_requests rr SET return_shipment_id = ?, link_source = 'traced_booking' " +
            "WHERE rr.tenant_id = ? AND rr.bosta_tracking_number = ? AND rr.return_shipment_id IS NULL " +
            "  AND NOT EXISTS (SELECT 1 FROM return_requests o WHERE o.return_shipment_id = ?) " +
            "RETURNING rr.id",
            UUID.class, shipmentId, tenantId, trackingNumber, shipmentId);
        for (UUID id : linked) {
            event(tenantId, id, "leg_linked", null, meta("source", "traced_booking",
                "shipment_id", shipmentId.toString(), "tracking_number", trackingNumber));
        }
    }

    /**
     * Step 4d-1 — a return leg the merchant booked by hand in Bosta. Runs only when no request
     * holds this leg or its tracking number. Candidates: {@link #linkCandidateSql}.
     * Exactly one → linked ('auto_matched'), approved → pickup_booked. None → nothing. Two or
     * more → nothing here; the return_link_ambiguous exception asks the merchant to choose.
     * Deferred (nothing done) while a Traced booking on the order is in flight ('pending'):
     * its tracking number isn't saved yet, so this leg may be that booking's own.
     */
    public void autoMatchLeg(UUID tenantId, UUID orderId, UUID shipmentId, String trackingNumber,
                             Instant bostaCreatedAt) {
        if (orderId == null || bostaCreatedAt == null) return;
        Boolean taken = jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM return_requests WHERE tenant_id = ? " +
            "               AND (return_shipment_id = ? OR bosta_tracking_number = ?)) " +
            "    OR EXISTS (SELECT 1 FROM return_requests WHERE tenant_id = ? AND order_id = ? " +
            "               AND booking_status = 'pending')",
            Boolean.class, tenantId, shipmentId, trackingNumber, tenantId, orderId);
        if (Boolean.TRUE.equals(taken)) return;

        List<UUID> candidates = jdbc.queryForList(
            "SELECT rr.id FROM return_requests rr WHERE " + linkCandidateSql("?", "?", "?") +
            "ORDER BY rr.created_at, rr.id FOR UPDATE",
            UUID.class, tenantId, orderId, Timestamp.from(bostaCreatedAt));
        if (candidates.size() != 1) return;
        UUID requestId = candidates.get(0);
        int n = jdbc.update(
            "UPDATE return_requests SET return_shipment_id = ?, link_source = 'auto_matched', status = 'pickup_booked' " +
            "WHERE id = ? AND tenant_id = ? AND status = 'approved' AND return_shipment_id IS NULL " +
            "  AND NOT EXISTS (SELECT 1 FROM return_requests o WHERE o.return_shipment_id = ?)",
            shipmentId, requestId, tenantId, shipmentId);
        if (n != 1) return;
        event(tenantId, requestId, "leg_linked", null, meta("source", "auto_matched",
            "shipment_id", shipmentId.toString(), "tracking_number", trackingNumber));
        event(tenantId, requestId, "pickup_booked", null, meta("source", "auto_matched",
            "tracking_number", trackingNumber));
    }

    /**
     * POST /return-requests/{id}/link-leg — the merchant picks the leg (link_source
     * 'merchant_selected'). The leg must be a courier-return (type 25) leg of the SAME order,
     * not held by another request; the request must be open and hold no leg yet.
     * Linking it also takes it out of the return_link_ambiguous exception.
     */
    public void linkLegByMerchant(UUID tenantId, UUID requestId, UUID shipmentId, UUID actor) {
        if (shipmentId == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "shipmentId is required");
        Map<String, Object> rr = lockRequest(tenantId, requestId);
        List<Map<String, Object>> legs = jdbc.queryForList(
            "SELECT id, order_id, shipment_leg, tracking_number, raw #>> '{type,code}' AS type_code " +
            "FROM shipments WHERE id = ? AND tenant_id = ?", shipmentId, tenantId);
        if (legs.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Return shipment not found");
        Map<String, Object> leg = legs.get(0);
        Object typeCode = leg.get("type_code");
        if (!"return".equals(leg.get("shipment_leg")) || (typeCode != null && !"25".equals(typeCode))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "That shipment isn't a customer return pickup.");
        }
        if (!Objects.equals(leg.get("order_id"), rr.get("order_id"))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "That return belongs to a different order.");
        }
        if (!OPEN_STATUSES.contains((String) rr.get("status"))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Only an open request can be linked to a return.");
        }
        if (rr.get("return_shipment_id") != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This request is already linked to a return.");
        }
        Boolean held = jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM return_requests WHERE tenant_id = ? AND return_shipment_id = ?)",
            Boolean.class, tenantId, shipmentId);
        if (Boolean.TRUE.equals(held)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "That return is already linked to another request.");
        }
        boolean wasApproved = "approved".equals(rr.get("status"));
        jdbc.update(
            "UPDATE return_requests SET return_shipment_id = ?, link_source = 'merchant_selected', " +
            "    status = CASE WHEN status = 'approved' THEN 'pickup_booked'::return_request_status ELSE status END " +
            "WHERE id = ? AND tenant_id = ?",
            shipmentId, requestId, tenantId);
        String tracking = (String) leg.get("tracking_number");
        event(tenantId, requestId, "leg_linked", actor, meta("source", "merchant_selected",
            "shipment_id", shipmentId.toString(), "tracking_number", tracking));
        if (wasApproved) {
            event(tenantId, requestId, "pickup_booked", actor, meta("source", "merchant_selected",
                "tracking_number", tracking));
        }
    }

    /**
     * Sweeper repair of the booking/webhook race: a request whose bosta_tracking_number
     * matches a return leg but whose return_shipment_id is still NULL gets it. Returns the
     * number linked.
     */
    public int repairTrackingLinks(UUID tenantId) {
        List<Map<String, Object>> linked = jdbc.queryForList(
            "UPDATE return_requests rr SET return_shipment_id = s.id, link_source = 'traced_booking' " +
            "FROM shipments s " +
            "WHERE rr.tenant_id = ? AND rr.return_shipment_id IS NULL AND rr.bosta_tracking_number IS NOT NULL " +
            "  AND s.tenant_id = rr.tenant_id AND s.tracking_number = rr.bosta_tracking_number " +
            "  AND s.shipment_leg = 'return' " +
            "  AND NOT EXISTS (SELECT 1 FROM return_requests o WHERE o.return_shipment_id = s.id) " +
            "RETURNING rr.id, s.id AS shipment_id, s.tracking_number",
            tenantId);
        for (Map<String, Object> r : linked) {
            event(tenantId, (UUID) r.get("id"), "leg_linked", null, meta("source", "traced_booking",
                "shipment_id", r.get("shipment_id").toString(), "tracking_number", r.get("tracking_number"),
                "repaired", true));
        }
        return linked.size();
    }

    // ── Intake scans ──────────────────────────────────────────────────────────

    /** A scan attributed to a request item. {@code substitutedFrom} = the piece it replaced, or null. */
    public record Attribution(UUID requestId, UUID itemId, String substitutedFrom) {}

    /**
     * Which open request item a delivered piece of {@code orderId} arriving at intake belongs
     * to, or null. (1) The piece itself is bound to an 'awaiting' item of an open request on
     * this order. (2) Otherwise, when the piece is bound to no live item: the oldest open
     * request on this order with an 'awaiting' item of the SAME variant (a substitute — the
     * bound piece stays with the customer). Anything else is never attached to a request.
     * Locks the chosen item row.
     */
    public Attribution attributionFor(UUID tenantId, UUID orderId, String pieceId, UUID variantId) {
        if (orderId == null) return null;
        List<Map<String, Object>> bound = jdbc.queryForList(
            "SELECT i.id, i.request_id FROM return_request_items i " +
            "JOIN return_requests rr ON rr.id = i.request_id AND rr.tenant_id = i.tenant_id " +
            "WHERE i.tenant_id = ? AND i.piece_id = ? AND i.item_status = 'awaiting' " +
            "  AND rr.order_id = ? AND rr.status::text IN ('approved', 'pickup_booked', 'received') " +
            "FOR UPDATE OF i",
            tenantId, pieceId, orderId);
        if (!bound.isEmpty()) {
            return new Attribution((UUID) bound.get(0).get("request_id"), (UUID) bound.get(0).get("id"), null);
        }
        Boolean boundElsewhere = jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM return_request_items WHERE tenant_id = ? AND piece_id = ? AND active)",
            Boolean.class, tenantId, pieceId);
        if (Boolean.TRUE.equals(boundElsewhere) || variantId == null) return null;
        List<Map<String, Object>> sub = jdbc.queryForList(
            "SELECT i.id, i.request_id, i.piece_id FROM return_request_items i " +
            "JOIN return_requests rr ON rr.id = i.request_id AND rr.tenant_id = i.tenant_id " +
            "WHERE i.tenant_id = ? AND i.variant_id = ? AND i.item_status = 'awaiting' " +
            "  AND rr.order_id = ? AND rr.status::text IN ('approved', 'pickup_booked', 'received') " +
            "ORDER BY rr.created_at, rr.id, i.created_at, i.id LIMIT 1 FOR UPDATE OF i",
            tenantId, variantId, orderId);
        if (sub.isEmpty()) return null;
        return new Attribution((UUID) sub.get(0).get("request_id"), (UUID) sub.get(0).get("id"),
            (String) sub.get(0).get("piece_id"));
    }

    /**
     * The attributed item → 'arrived'. A substitute first swaps the item's piece_id to the
     * arriving piece (event item_substituted {from, to}); the original piece stays with the
     * customer and is no longer reserved.
     */
    public void markArrived(UUID tenantId, Attribution a, String pieceId, UUID actor, UUID sessionId) {
        if (a.substitutedFrom() != null) {
            jdbc.update("UPDATE return_request_items SET piece_id = ? WHERE id = ? AND tenant_id = ?",
                pieceId, a.itemId(), tenantId);
            event(tenantId, a.requestId(), "item_substituted", actor, meta("item_id", a.itemId().toString(),
                "from", a.substitutedFrom(), "to", pieceId));
        }
        jdbc.update(
            "UPDATE return_request_items SET item_status = 'arrived', arrived_at = now() " +
            "WHERE id = ? AND tenant_id = ? AND item_status = 'awaiting'",
            a.itemId(), tenantId);
        event(tenantId, a.requestId(), "item_arrived", actor, meta("item_id", a.itemId().toString(),
            "piece_id", pieceId, "session_id", sessionId == null ? null : sessionId.toString()));
    }

    /**
     * A piece of an order with an open request arrived but belongs to none of its items
     * (e.g. a different variant): an 'unexpected_item_received' event on each open request of
     * the order. No binding changes.
     */
    public void noteUnexpected(UUID tenantId, UUID orderId, String pieceId, UUID variantId, UUID actor, UUID sessionId) {
        if (orderId == null) return;
        List<UUID> open = jdbc.queryForList(
            "SELECT id FROM return_requests WHERE tenant_id = ? AND order_id = ? " +
            "  AND status::text IN ('approved', 'pickup_booked', 'received') ORDER BY created_at, id",
            UUID.class, tenantId, orderId);
        for (UUID id : open) {
            event(tenantId, id, "unexpected_item_received", actor, meta("piece_id", pieceId,
                "variant_id", variantId == null ? null : variantId.toString(),
                "session_id", sessionId == null ? null : sessionId.toString()));
        }
    }

    /**
     * The piece of an arrived item got a FINAL disposition (restocked / damaged): the item →
     * 'done' and its reservation is released at once, even while other items are awaited.
     */
    public void onFinalDisposition(UUID tenantId, UUID requestItemId, String disposition, UUID actor) {
        if (requestItemId == null) return;
        List<Map<String, Object>> done = jdbc.queryForList(
            "UPDATE return_request_items SET item_status = 'done', done_at = now(), active = false " +
            "WHERE id = ? AND tenant_id = ? AND item_status = 'arrived' RETURNING request_id, piece_id",
            requestItemId, tenantId);
        if (done.isEmpty()) return;
        UUID requestId = (UUID) done.get(0).get("request_id");
        event(tenantId, requestId, "item_done", actor, meta("item_id", requestItemId.toString(),
            "piece_id", done.get(0).get("piece_id"), "disposition", disposition));
        reevaluate(tenantId, requestId, actor);
    }

    // ── Status derivation ─────────────────────────────────────────────────────

    /**
     * Moves an open request forward from its items: approved / pickup_booked → received →
     * refund_pending. Never moves backward, never touches a closed / rejected / refunded one.
     */
    public void reevaluate(UUID tenantId, UUID requestId, UUID actor) {
        Map<String, Object> rr = lockRequest(tenantId, requestId);
        String status = (String) rr.get("status");
        if (!OPEN_STATUSES.contains(status)) return;
        Map<String, Object> c = itemCounts(tenantId, requestId);
        long awaiting = (Long) c.get("awaiting"), arrived = (Long) c.get("arrived"), done = (Long) c.get("done");
        if (awaiting > 0 || arrived + done == 0) return;
        if (!"received".equals(status)) {
            jdbc.update(
                "UPDATE return_requests SET status = 'received', received_at = now() WHERE id = ? AND tenant_id = ?",
                requestId, tenantId);
            event(tenantId, requestId, "received", actor, meta("items", arrived + done));
        }
        if (arrived == 0) {
            jdbc.update(
                "UPDATE return_requests SET status = 'refund_pending', refund_pending_at = now() " +
                "WHERE id = ? AND tenant_id = ?", requestId, tenantId);
            event(tenantId, requestId, "refund_pending", actor, meta("items", done));
        }
    }

    // ── Merchant actions ──────────────────────────────────────────────────────

    /**
     * POST /return-requests/{id}/rest-not-coming: every 'awaiting' item → not_coming (released).
     * Nothing ever arrived → closed (close_reason 'rest_not_coming'); otherwise re-evaluated.
     */
    public void restNotComing(UUID tenantId, UUID requestId, UUID actor) {
        Map<String, Object> rr = lockRequest(tenantId, requestId);
        if (!OPEN_STATUSES.contains((String) rr.get("status"))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Only an open request can be updated.");
        }
        int n = jdbc.update(
            "UPDATE return_request_items SET item_status = 'not_coming', active = false " +
            "WHERE request_id = ? AND tenant_id = ? AND item_status = 'awaiting'",
            requestId, tenantId);
        if (n == 0) throw new ResponseStatusException(HttpStatus.CONFLICT, "No items are still awaited.");
        event(tenantId, requestId, "rest_not_coming", actor, meta("items", n));
        Map<String, Object> c = itemCounts(tenantId, requestId);
        if ((Long) c.get("arrived") + (Long) c.get("done") == 0) {
            closeInternal(tenantId, requestId, "rest_not_coming", null, actor);
        } else {
            reevaluate(tenantId, requestId, actor);
        }
    }

    /** POST /return-requests/{id}/close {reason: no_refund|other, note}. */
    public void close(UUID tenantId, UUID requestId, String reason, String note, UUID actor) {
        if (reason == null || !CLOSE_REASONS.contains(reason)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "reason must be no_refund or other");
        }
        String n = note == null || note.isBlank() ? null : note.trim();
        if (n != null && n.length() > CLOSE_NOTE_MAX) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The note can be at most " + CLOSE_NOTE_MAX + " characters.");
        }
        Map<String, Object> rr = lockRequest(tenantId, requestId);
        if (!CLOSABLE.contains((String) rr.get("status"))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This request can't be closed.");
        }
        closeInternal(tenantId, requestId, reason, n, actor);
    }

    /**
     * Releases every remaining reservation (awaiting → not_coming; arrived → done, since its
     * piece is already back in the warehouse) and closes the request.
     */
    private void closeInternal(UUID tenantId, UUID requestId, String reason, String note, UUID actor) {
        jdbc.update(
            "UPDATE return_request_items SET " +
            "    item_status = CASE WHEN item_status = 'arrived' THEN 'done' ELSE 'not_coming' END, " +
            "    done_at = CASE WHEN item_status = 'arrived' THEN now() ELSE done_at END, " +
            "    active = false " +
            "WHERE request_id = ? AND tenant_id = ? AND item_status IN ('awaiting', 'arrived')",
            requestId, tenantId);
        jdbc.update(
            "UPDATE return_requests SET status = 'closed', closed_at = now(), closed_by = ?, " +
            "    close_reason = ?, close_note = ? WHERE id = ? AND tenant_id = ?",
            actor, reason, note, requestId, tenantId);
        event(tenantId, requestId, "closed", actor, meta("reason", reason));
    }

    /** Rejecting a requested return releases all its items (not_coming). */
    public void releaseAllOnReject(UUID tenantId, UUID requestId) {
        jdbc.update(
            "UPDATE return_request_items SET item_status = 'not_coming', active = false " +
            "WHERE request_id = ? AND tenant_id = ? AND item_status IN ('awaiting', 'arrived')",
            requestId, tenantId);
    }

    // ── Step 4d-2: refunds (append-only ledger) ──────────────────────────────

    public static final Set<String> REFUND_METHODS = Set.of("cash", "instapay", "wallet", "bank_transfer", "other");
    static final Set<String> REFUNDABLE = Set.of("received", "refund_pending");
    static final int REFUND_REFERENCE_MAX = 100;
    static final int REFUND_NOTE_MAX = 300;
    static final BigDecimal REFUND_AMOUNT_MAX = new BigDecimal("9999999999.99");
    /** Refund dates are the merchant's calendar day; Traced's tenants are in Egypt. */
    static final ZoneId BUSINESS_ZONE = ZoneId.of("Africa/Cairo");

    /**
     * THE predicate "the refund of this request is overdue" (aliases {@code rr} = return_requests,
     * {@code t} = its tenant): refund_pending for longer than tenants.refund_pending_window_days.
     * Shared by ExceptionService's refund_pending_overdue detector and the Requests list badge.
     */
    public static final String REFUND_OVERDUE_SQL =
        "rr.status = 'refund_pending' AND rr.refund_pending_at IS NOT NULL " +
        "AND rr.refund_pending_at < now() - (interval '1 day' * t.refund_pending_window_days) ";

    /** The resolution key of the refund_pending_overdue exception for request {@code rr}. */
    public static final String REFUND_OVERDUE_KEY_SQL = "'refund_pending_overdue:' || rr.id";

    /**
     * When the return_items_overdue clock starts (alias {@code rr}): the courier booking
     * (the latest pickup_booked event, else the booking attempt) for a pickup_booked request,
     * else the approval.
     */
    public static final String ITEMS_OVERDUE_ANCHOR_SQL =
        "(CASE WHEN rr.status = 'pickup_booked' THEN COALESCE(" +
        "    (SELECT MAX(e.occurred_at) FROM return_request_events e " +
        "      WHERE e.request_id = rr.id AND e.tenant_id = rr.tenant_id AND e.event_type = 'pickup_booked'), " +
        "    rr.booking_attempted_at, rr.decided_at) " +
        " ELSE rr.decided_at END)";

    /**
     * THE predicate "items of this request are overdue" (aliases {@code rr}, {@code t}): approved
     * or pickup_booked, at least one item still awaited, and the clock
     * ({@link #ITEMS_OVERDUE_ANCHOR_SQL}) older than tenants.return_arrival_window_days.
     */
    public static final String ITEMS_OVERDUE_SQL =
        "rr.status::text IN ('approved', 'pickup_booked') " +
        "AND EXISTS (SELECT 1 FROM return_request_items i WHERE i.request_id = rr.id " +
        "            AND i.tenant_id = rr.tenant_id AND i.item_status = 'awaiting') " +
        "AND " + ITEMS_OVERDUE_ANCHOR_SQL + " < now() - (interval '1 day' * t.return_arrival_window_days) ";

    /**
     * POST /return-requests/{id}/refunds — records a refund the merchant made (Traced moves no
     * money). Allowed in received and refund_pending. The currency is the order's (its stored
     * Shopify payload), else EGP. Event refund_recorded {amount, method}.
     */
    public UUID recordRefund(UUID tenantId, UUID requestId, String method, BigDecimal amount, LocalDate refundedOn,
                             String reference, String note, UUID actor) {
        if (method == null || !REFUND_METHODS.contains(method)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose how the refund was paid.");
        }
        if (amount == null || amount.signum() <= 0 || amount.scale() > 2 && amount.stripTrailingZeros().scale() > 2
                || amount.compareTo(REFUND_AMOUNT_MAX) > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Enter an amount greater than 0.");
        }
        if (refundedOn == null || refundedOn.isAfter(LocalDate.now(BUSINESS_ZONE))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The refund date can't be in the future.");
        }
        String ref = blankToNull(reference), n = blankToNull(note);
        if (ref != null && ref.length() > REFUND_REFERENCE_MAX) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The reference can be at most " + REFUND_REFERENCE_MAX + " characters.");
        }
        if (n != null && n.length() > REFUND_NOTE_MAX) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The note can be at most " + REFUND_NOTE_MAX + " characters.");
        }
        Map<String, Object> rr = lockRequest(tenantId, requestId);
        if (!REFUNDABLE.contains((String) rr.get("status"))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A refund can be recorded once the return has been received.");
        }
        String currency = jdbc.queryForObject(
            "SELECT COALESCE(NULLIF(o.raw->>'currency', ''), 'EGP') FROM orders o WHERE o.id = ? AND o.tenant_id = ?",
            String.class, rr.get("order_id"), tenantId);
        BigDecimal value = amount.setScale(2, RoundingMode.HALF_UP);
        UUID id = jdbc.queryForObject(
            "INSERT INTO return_refunds (tenant_id, request_id, kind, method, amount, currency, refunded_on, reference, note, recorded_by) " +
            "VALUES (?, ?, 'refund', ?, ?, ?, ?, ?, ?, ?) RETURNING id",
            UUID.class, tenantId, requestId, method, value, currency, java.sql.Date.valueOf(refundedOn), ref, n, actor);
        event(tenantId, requestId, "refund_recorded", actor, meta("refund_id", id.toString(),
            "amount", value.toPlainString(), "currency", currency, "method", method));
        return id;
    }

    /**
     * POST /return-requests/{id}/refunds/{refundId}/void — cancels a recorded refund with a
     * 'void' row (never an UPDATE). Once per refund; not after the request is refunded.
     */
    public void voidRefund(UUID tenantId, UUID requestId, UUID refundId, String note, UUID actor) {
        String n = blankToNull(note);
        if (n != null && n.length() > REFUND_NOTE_MAX) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The note can be at most " + REFUND_NOTE_MAX + " characters.");
        }
        Map<String, Object> rr = lockRequest(tenantId, requestId);
        List<Map<String, Object>> refund = jdbc.queryForList(
            "SELECT id, amount, currency FROM return_refunds WHERE id = ? AND request_id = ? AND tenant_id = ? AND kind = 'refund'",
            refundId, requestId, tenantId);
        if (refund.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Refund not found");
        if (!REFUNDABLE.contains((String) rr.get("status"))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Refunds can't be changed once the request is closed.");
        }
        Boolean voided = jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM return_refunds WHERE voids_refund_id = ? AND tenant_id = ? AND kind = 'void')",
            Boolean.class, refundId, tenantId);
        if (Boolean.TRUE.equals(voided)) throw new ResponseStatusException(HttpStatus.CONFLICT, "This refund was already voided.");
        Map<String, Object> r = refund.get(0);
        jdbc.update(
            "INSERT INTO return_refunds (tenant_id, request_id, kind, voids_refund_id, currency, note, recorded_by) " +
            "VALUES (?, ?, 'void', ?, ?, ?, ?)",
            tenantId, requestId, refundId, r.get("currency"), n, actor);
        event(tenantId, requestId, "refund_voided", actor, meta("refund_id", refundId.toString(),
            "amount", ((BigDecimal) r.get("amount")).toPlainString(), "currency", r.get("currency")));
    }

    /** POST /return-requests/{id}/mark-refunded — from refund_pending, with ≥ 1 refund that isn't voided. */
    public void markRefunded(UUID tenantId, UUID requestId, UUID actor) {
        Map<String, Object> rr = lockRequest(tenantId, requestId);
        if (!"refund_pending".equals(rr.get("status"))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Only a request waiting for its refund can be marked refunded.");
        }
        BigDecimal total = refundTotal(tenantId, requestId);
        if (total.signum() <= 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Record the refund before marking the request refunded.");
        }
        jdbc.update(
            "UPDATE return_requests SET status = 'refunded', refunded_at = now(), refunded_by = ? WHERE id = ? AND tenant_id = ?",
            actor, requestId, tenantId);
        event(tenantId, requestId, "refunded", actor, meta("total", total.toPlainString()));
    }

    /** Sum of the request's refunds that are not voided (0 when none). */
    public BigDecimal refundTotal(UUID tenantId, UUID requestId) {
        return jdbc.queryForObject(
            "SELECT COALESCE(SUM(r.amount), 0) FROM return_refunds r WHERE r.request_id = ? AND r.tenant_id = ? " +
            "AND r.kind = 'refund' AND NOT EXISTS (SELECT 1 FROM return_refunds v " +
            "    WHERE v.voids_refund_id = r.id AND v.tenant_id = r.tenant_id AND v.kind = 'void')",
            BigDecimal.class, requestId, tenantId);
    }

    /** The request's refunds, oldest first, each with its void state. */
    public List<Map<String, Object>> refunds(UUID tenantId, UUID requestId) {
        return jdbc.query(
            "SELECT r.id, r.method, r.amount, r.currency, r.refunded_on, r.reference, r.note, r.created_at, " +
            "       u.name AS recorded_by_name, v.created_at AS voided_at, vu.name AS voided_by_name, v.note AS void_note " +
            "FROM return_refunds r " +
            "LEFT JOIN users u ON u.id = r.recorded_by " +
            "LEFT JOIN return_refunds v ON v.voids_refund_id = r.id AND v.tenant_id = r.tenant_id AND v.kind = 'void' " +
            "LEFT JOIN users vu ON vu.id = v.recorded_by " +
            "WHERE r.request_id = ? AND r.tenant_id = ? AND r.kind = 'refund' ORDER BY r.created_at, r.id",
            (rs, i) -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", rs.getObject("id").toString());
                m.put("method", rs.getString("method"));
                m.put("amount", rs.getBigDecimal("amount").toPlainString());
                m.put("currency", rs.getString("currency"));
                m.put("refundedOn", rs.getDate("refunded_on").toLocalDate().toString());
                m.put("reference", rs.getString("reference"));
                m.put("note", rs.getString("note"));
                m.put("recordedByName", rs.getString("recorded_by_name"));
                m.put("createdAt", rs.getTimestamp("created_at").toInstant().toString());
                Timestamp voidedAt = rs.getTimestamp("voided_at");
                m.put("voided", voidedAt != null);
                m.put("voidedAt", voidedAt == null ? null : voidedAt.toInstant().toString());
                m.put("voidedByName", rs.getString("voided_by_name"));
                m.put("voidNote", rs.getString("void_note"));
                return m;
            },
            requestId, tenantId);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Map<String, Object> lockRequest(UUID tenantId, UUID requestId) {
        return jdbc.queryForList(
            "SELECT id, order_id, status::text AS status, return_shipment_id FROM return_requests " +
            "WHERE id = ? AND tenant_id = ? FOR UPDATE", requestId, tenantId)
            .stream().findFirst()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Return request not found"));
    }

    private Map<String, Object> itemCounts(UUID tenantId, UUID requestId) {
        return jdbc.queryForMap(
            "SELECT COUNT(*) FILTER (WHERE item_status = 'awaiting') AS awaiting, " +
            "       COUNT(*) FILTER (WHERE item_status = 'arrived')  AS arrived, " +
            "       COUNT(*) FILTER (WHERE item_status = 'done')     AS done " +
            "FROM return_request_items WHERE request_id = ? AND tenant_id = ?",
            requestId, tenantId);
    }

    /** An ISO-8601 instant (Bosta's createdAt), or null when missing / unreadable. */
    public static Instant parseInstant(String s) {
        if (s == null || s.isBlank()) return null;
        try { return Instant.parse(s); } catch (Exception e) {
            try { return java.time.OffsetDateTime.parse(s).toInstant(); } catch (Exception e2) { return null; }
        }
    }

    /** meta("k1", v1, "k2", v2, ...) — null values are kept as JSON null. */
    public static Map<String, Object> meta(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    private static String toJson(Map<String, Object> m) {
        if (m == null || m.isEmpty()) return null;
        try { return JSON.writeValueAsString(m); } catch (JsonProcessingException e) { throw new IllegalStateException(e); }
    }

    private static Object fromJson(String s) {
        if (s == null) return null;
        try { return JSON.readValue(s, Map.class); } catch (JsonProcessingException e) { return null; }
    }
}
