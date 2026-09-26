package com.traceability.inventory;

import com.traceability.portal.ReturnRequestLifecycle;
import com.traceability.tenancy.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * FR-24: session-based returns rebuild. Replaces the old waybill-first returns session
 * (which required a shipment match up front and rode the shared `receipts` table,
 * kind='returns' — see V29__returns_session.sql). That flow is fully superseded here;
 * old closed sessions become inert history (piece history stays intact in piece_events,
 * no backfill into the new tables per the FR-24 build plan).
 *
 * Model: a session is a free-scan working set. Each scan resolves to one of three
 * outcomes — legal (piece transitions to return_pending_inspection, item created
 * disposition=pending), illegal-state (piece is ours but not in a return-eligible
 * status — no transition, item created unexpected=true, disposition can only ever
 * become 'mismatch'), or foreign (matches nothing of ours — 422, no row written at
 * all). An AWB scan doesn't create a persisted row; it surfaces the shipment's
 * still-unscanned expected pieces as a transient, recomputed-on-read list (backed by
 * return_session_shipments, which just remembers which AWBs were scanned into this
 * session for the close-summary count + audit).
 *
 * Abandon does NOT revert (change B, approved 2026-08-14): undispositioned legal-scan
 * pieces simply stay at return_pending_inspection and resurface as "unassigned
 * pending" — no reverse InventoryLedger.ALLOWED pairs, no cancel event. The session
 * itself is soft-deleted (status='abandoned', rows kept) since real pieces and
 * piece_events already reference it by the time abandon can be called.
 *
 * InventoryLedger remains the sole writer of piece_events — this service only calls
 * ledger.transition() (legal-scan transitions), ledger.recordReturnReceived() (the
 * "adopt" sibling-append for a piece already at return_pending_inspection before this
 * session touched it — 3rd write path), ledger.recordLabelReprinted() (reprint — 4th
 * write path), and ReturnService.restock()/markDamaged() (which themselves only call
 * ledger.transition()).
 */
@Service
public class ReturnSessionService {

    private static final Logger log = LoggerFactory.getLogger(ReturnSessionService.class);

    /** Statuses from which a scan legally moves a piece into return_pending_inspection. */
    private static final Set<PieceStatus> LEGAL_SCAN_STATUSES = Set.of(
        PieceStatus.RETURN_IN_TRANSIT, PieceStatus.WITH_COURIER, PieceStatus.AWAITING_PICKUP,
        PieceStatus.DELIVERED, PieceStatus.RETURN_PENDING_INSPECTION
    );

    private final JdbcTemplate       jdbc;
    private final InventoryLedger    ledger;
    private final ReturnService      returnService;
    private final ShipmentLinkService shipmentLinkService;
    private final Clock              clock;
    /** Step 4d-1: return-request attribution + lifecycle, on this service's own JdbcTemplate. */
    private final ReturnRequestLifecycle requests;

    public ReturnSessionService(JdbcTemplate jdbc, InventoryLedger ledger,
                                ReturnService returnService, ShipmentLinkService shipmentLinkService,
                                Clock clock) {
        this.jdbc                = jdbc;
        this.ledger              = ledger;
        this.returnService       = returnService;
        this.shipmentLinkService = shipmentLinkService;
        this.clock               = clock;
        this.requests            = new ReturnRequestLifecycle(jdbc);
    }

    // ── Create / open ─────────────────────────────────────────────────────────

    /**
     * Claim-before-call: the INSERT itself is the one-open-session-per-tenant guard
     * (return_sessions_one_open_per_tenant, V73). A concurrent second call gets a
     * DuplicateKeyException from the DB driver, propagated uncaught — Spring rolls the
     * transaction back cleanly. The controller catches it and calls
     * getOpenSessionSummary() (a fresh, separate transaction) to build the 409 body;
     * querying inside THIS transaction after the violation would fail outright —
     * Postgres aborts the whole transaction on the constraint violation and only a
     * rollback is valid until it ends (see TransferService's identical note on
     * transfer_pieces_one_active for the same pattern).
     */
    @Transactional
    public UUID createSession(String note, UUID actorUserId) {
        UUID tenantId = TenantContext.require();
        UUID id = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO return_sessions (id, tenant_id, status, opened_by, note) " +
            "VALUES (?, ?, 'open', ?, ?)",
            id, tenantId, actorUserId, note);
        return id;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getOpenSessionSummary() {
        UUID tenantId = TenantContext.require();
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT rs.id, rs.opened_by, rs.opened_at, " +
            "       (SELECT COUNT(*) FROM return_session_items i " +
            "        WHERE i.session_id = rs.id AND i.tenant_id = rs.tenant_id) AS piece_count " +
            "FROM return_sessions rs WHERE rs.tenant_id = ? AND rs.status = 'open'",
            tenantId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    // ── Abandon (soft-delete, no revert — change B) ──────────────────────────────

    @Transactional
    public void abandon(UUID sessionId, UUID actorUserId) {
        UUID tenantId = TenantContext.require();
        requireOpen(sessionId, tenantId);
        jdbc.update(
            "UPDATE return_sessions SET status = 'abandoned', closed_by = ?, closed_at = now() " +
            "WHERE id = ? AND tenant_id = ?",
            actorUserId, sessionId, tenantId);
    }

    // ── Scan ──────────────────────────────────────────────────────────────────

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Map<String, Object> scan(UUID sessionId, String rawScan, UUID locationId, UUID actorUserId) {
        UUID tenantId = TenantContext.require();
        requireOpen(sessionId, tenantId);

        String cleaned = rawScan == null ? "" : rawScan.replaceAll("\\s+", "");
        if (cleaned.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Empty scan");
        }

        Map<String, Object> piece = fetchPieceByScan(cleaned, tenantId);
        if (piece != null) {
            return scanPiece(sessionId, tenantId, piece, locationId, actorUserId);
        }

        String trackingNumber = TrackingNumberNormalizer.normalize(cleaned);
        if (trackingNumber != null) {
            List<Map<String, Object>> shipmentRows = jdbc.queryForList(
                "SELECT 1 FROM shipments WHERE tracking_number = ? AND tenant_id = ?",
                trackingNumber, tenantId);
            if (!shipmentRows.isEmpty()) {
                return scanAwb(sessionId, tenantId, trackingNumber);
            }
        }

        // Foreign scan: nothing of ours matched. No row written, ledger untouched.
        log.warn("Foreign return scan: raw={} session={} tenant={}", rawScan, sessionId, tenantId);
        throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
            "Scan does not match any piece or shipment: " + rawScan);
    }

    private Map<String, Object> scanPiece(UUID sessionId, UUID tenantId, Map<String, Object> piece,
                                          UUID locationId, UUID actorUserId) {
        String pieceId = (String) piece.get("id");

        // Idempotent re-scan: same piece already has an item row in this session — return
        // it as-is rather than re-processing (single post-action view, no duplicate work).
        List<Map<String, Object>> existing = jdbc.queryForList(
            "SELECT id FROM return_session_items WHERE session_id = ? AND piece_id = ? AND tenant_id = ?",
            sessionId, pieceId, tenantId);
        if (!existing.isEmpty()) {
            return itemRow((UUID) existing.get(0).get("id"), tenantId);
        }

        PieceStatus current   = PieceStatus.fromDb((String) piece.get("status"));
        UUID orderId          = (UUID) piece.get("order_id");
        UUID shipmentId       = (UUID) piece.get("shipment_id");
        String metaSuffix     = "\"session_id\":\"" + sessionId + "\"";
        boolean legal;
        boolean unexpected = false;
        // Step 4d-1: the open return-request item this scan was attributed to (DELIVERED only).
        ReturnRequestLifecycle.Attribution attribution = null;

        switch (current) {
            case RETURN_IN_TRANSIT -> {
                legal = true;
                String meta = "{\"return_kind\":\"rto\"," + metaSuffix + "}";
                ledger.transition(pieceId, PieceStatus.RETURN_IN_TRANSIT, PieceStatus.RETURN_PENDING_INSPECTION,
                    "return_received", actorUserId, new TransitionContext(orderId, shipmentId, locationId, orderId, meta));
            }
            case WITH_COURIER -> {
                legal = true;
                // Not unexpected if this order has a matched CRP return leg already in
                // flight — Bosta just hasn't caught the piece's own status up yet. See
                // ShipmentLinkService.hasActiveReturnLeg() javadoc.
                unexpected = !shipmentLinkService.hasActiveReturnLeg(orderId, tenantId);
                String meta = "{\"return_kind\":\"rto\"," + metaSuffix + "}";
                ledger.transition(pieceId, PieceStatus.WITH_COURIER, PieceStatus.RETURN_PENDING_INSPECTION,
                    "return_received", actorUserId, new TransitionContext(orderId, shipmentId, locationId, orderId, meta));
            }
            case AWAITING_PICKUP -> {
                legal = true;
                unexpected = !shipmentLinkService.hasActiveReturnLeg(orderId, tenantId);
                String meta = "{\"return_kind\":\"rto\"," + metaSuffix + "}";
                ledger.transition(pieceId, PieceStatus.AWAITING_PICKUP, PieceStatus.RETURN_PENDING_INSPECTION,
                    "return_received", actorUserId, new TransitionContext(orderId, shipmentId, locationId, orderId, meta));
            }
            case DELIVERED -> {
                // Step 4d-1: a piece bound to an 'awaiting' item of an open return request on
                // this order (approved / pickup_booked / received) — or a same-variant
                // substitute for one — is expected back regardless of the return window: the
                // merchant approved this specific return. Anything else is never attached.
                UUID variantId = (UUID) piece.get("variant_id");
                attribution = requests.attributionFor(tenantId, orderId, pieceId, variantId);
                boolean inWindow = withinReturnWindow(pieceId, tenantId);
                // Step 3C: a matched exchange's old item is expected back regardless of the
                // generic customer-return window — the merchant already confirmed this
                // specific return via matching (ExchangeMatchService/Part D), so it isn't a
                // walk-in "is this even still returnable" judgment call. hasActiveReturnLeg()
                // is order-scoped (see Test 2 in the Step 3C report) — any delivered piece on
                // a matched-exchange order clears this, not only the specific matched variant.
                boolean matchedExchangeCover = !inWindow && shipmentLinkService.hasActiveReturnLeg(orderId, tenantId);
                // Scan-as-truth: a CRP return leg that hasn't had its intake scan yet —
                // including one Bosta already reports 'returned' (state 46), which
                // hasActiveReturnLeg() treats as terminal — covers the order's delivered
                // pieces. Return-leg courier states no longer move pieces, so this scan is
                // the ONLY way such a piece reaches return_pending_inspection.
                boolean crpAwaitingIntake = shipmentLinkService.hasReturnLegAwaitingIntake(orderId, tenantId);
                if (attribution != null || inWindow || matchedExchangeCover || crpAwaitingIntake) {
                    legal = true;
                    // Label = what actually covers the piece, independent of the window
                    // (acceptance above is unchanged). Precedence:
                    // exchange_match > request_return > crp_return > customer_after_delivery.
                    //   exchange_match — a matched exchange on this order.
                    //   request_return — attributed to an open return-request item (4d-1).
                    //   crp_return     — a return leg, non-terminal or awaiting intake
                    //                    (hasReturnLegAwaitingIntake covers both).
                    //   customer_after_delivery — in window, none of the above.
                    String returnKind = shipmentLinkService.hasMatchedExchange(orderId, tenantId) ? "exchange_match"
                        : attribution != null ? "request_return"
                        : crpAwaitingIntake ? "crp_return"
                        : "customer_after_delivery";
                    String requestMeta = attribution == null ? ""
                        : ",\"request_id\":\"" + attribution.requestId() + "\",\"request_item_id\":\"" + attribution.itemId() + "\"";
                    String meta = "{\"return_kind\":\"" + returnKind + "\"," + metaSuffix + requestMeta + "}";
                    ledger.transition(pieceId, PieceStatus.DELIVERED, PieceStatus.RETURN_PENDING_INSPECTION,
                        "return_received", actorUserId, new TransitionContext(orderId, shipmentId, locationId, orderId, meta));
                    if (attribution != null) {
                        requests.markArrived(tenantId, attribution, pieceId, actorUserId, sessionId);
                    } else {
                        // Accepted by today's rules but part of no request item (e.g. a different
                        // variant): noted on the order's open requests, never bound to them.
                        requests.noteUnexpected(tenantId, orderId, pieceId, variantId, actorUserId, sessionId);
                    }
                } else {
                    // Outside the customer return window and no active return leg / matched
                    // exchange / return leg awaiting intake / open request item covers it:
                    // ours, but no longer return-eligible. Illegal-state
                    // fork — no transition, mismatch-only.
                    legal = false; unexpected = true;
                    log.warn("Illegal-state return scan (delivered, out of window): piece={} session={}", pieceId, sessionId);
                }
            }
            case RETURN_PENDING_INSPECTION -> {
                // Adopt: Bosta state-46 (or a prior session) already put this piece here.
                // No status transition — sibling-append only, carrying this session's id.
                legal = true;
                String meta = "{" + metaSuffix + "}";
                ledger.recordReturnReceived(pieceId, locationId, actorUserId, orderId, shipmentId, meta);
            }
            default -> {
                // Ours, but in a status with no return-eligible transition (available,
                // reserved, packed, damaged, lost, destroyed, out_on_transfer, sold).
                // No ledger write at all — restock()/markDamaged() will independently
                // reject this piece with 409 since it never reaches return_pending_inspection.
                legal = false; unexpected = true;
                log.warn("Illegal-state return scan (status={}): piece={} session={}", current.db, pieceId, sessionId);
            }
        }

        if (legal && locationId != null) {
            jdbc.update("UPDATE pieces SET current_location_id = ? WHERE id = ?", locationId, pieceId);
        }

        UUID itemId = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO return_session_items (id, tenant_id, session_id, piece_id, scanned_by, scan_source, unexpected, " +
            "    request_item_id) " +
            "VALUES (?, ?, ?, ?, ?, 'barcode', ?, ?)",
            itemId, tenantId, sessionId, pieceId, actorUserId, unexpected,
            attribution == null ? null : attribution.itemId());
        if (attribution != null) requests.reevaluate(tenantId, attribution.requestId(), actorUserId);

        return itemRow(itemId, tenantId);
    }

    private Map<String, Object> scanAwb(UUID sessionId, UUID tenantId, String trackingNumber) {
        jdbc.update(
            "INSERT INTO return_session_shipments (id, tenant_id, session_id, awb) VALUES (?, ?, ?, ?) " +
            "ON CONFLICT (session_id, awb) DO NOTHING",
            UUID.randomUUID(), tenantId, sessionId, trackingNumber);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("scanType", "awb");
        result.put("awb", trackingNumber);
        result.put("expectedPieces", fetchExpectedPieces(sessionId, tenantId));
        // Courier (CRP) return label: add Bosta's own parcel description so the worker knows
        // which of the order's pieces are actually in this parcel. Forward AWBs unchanged.
        List<Map<String, Object>> crp = courierReturnInfo(
            "rss.session_id = ? AND rss.tenant_id = ? AND rss.awb = ?", sessionId, tenantId, trackingNumber);
        if (!crp.isEmpty()) {
            result.put("itemsCount",    crp.get(0).get("itemsCount"));
            result.put("description",   crp.get(0).get("description"));
            result.put("descriptionAr", crp.get(0).get("descriptionAr"));
        }
        return result;
    }

    /**
     * Bosta's returnSpecs.packageDetails for return-leg (CRP) AWBs scanned into a session.
     * Forward-leg AWBs never match (shipment_leg='return' filter).
     */
    private List<Map<String, Object>> courierReturnInfo(String where, Object... args) {
        return jdbc.queryForList(
            "SELECT rss.awb, " +
            "       (s.raw #>> '{returnSpecs,packageDetails,itemsCount}')::int AS \"itemsCount\", " +
            "       s.raw #>> '{returnSpecs,packageDetails,description}'   AS \"description\", " +
            "       s.raw #>> '{returnSpecs,packageDetails,descriptionAr}' AS \"descriptionAr\" " +
            "FROM return_session_shipments rss " +
            "JOIN shipments s ON s.tracking_number = rss.awb AND s.tenant_id = rss.tenant_id " +
            "                AND s.shipment_leg = 'return' " +
            "WHERE " + where + " ORDER BY rss.awb",
            args);
    }

    // ── Disposition ───────────────────────────────────────────────────────────

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Map<String, Object> disposition(UUID sessionId, String pieceId, String disposition,
                                           String reason, UUID locationId, UUID actorUserId) {
        UUID tenantId = TenantContext.require();
        requireOpen(sessionId, tenantId);

        if (!Set.of("restock", "damaged", "mismatch").contains(disposition)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "disposition must be one of restock, damaged, mismatch");
        }

        Map<String, Object> item = fetchItemByPiece(sessionId, pieceId, tenantId);
        if (!"pending".equals(item.get("disposition"))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Item already dispositioned as " + item.get("disposition"));
        }

        switch (disposition) {
            // restock()/markDamaged() are the existing return_pending_inspection guard —
            // this is what makes "illegal-state item rejects restock/damage" free: those
            // pieces never reached return_pending_inspection, so these throw 409 unchanged.
            case "restock"  -> returnService.restock(pieceId, locationId, actorUserId);
            case "damaged"  -> returnService.markDamaged(pieceId, reason, actorUserId); // 400 inside if reason blank
            case "mismatch" -> log.warn("Return mismatch: piece={} session={}", pieceId, sessionId);
            // No ledger transition for mismatch — piece stays at return_pending_inspection.
            // No revert, no cancel event (change B) — a dedicated mismatch-resolution
            // action is a tracked future item, not this pass.
        }

        String stored = "restock".equals(disposition) ? "restocked" : disposition;
        jdbc.update(
            "UPDATE return_session_items SET disposition = ?, disposition_at = now(), disposition_by = ?, damage_reason = ? " +
            "WHERE session_id = ? AND piece_id = ? AND tenant_id = ?",
            stored, actorUserId, "damaged".equals(disposition) ? reason : null, sessionId, pieceId, tenantId);

        // Step 4d-1: a FINAL disposition (restocked / damaged — never mismatch) of a scan
        // attributed to a return-request item finishes that item and releases its piece now.
        if (!"mismatch".equals(stored)) {
            UUID requestItemId = jdbc.query(
                "SELECT request_item_id FROM return_session_items WHERE session_id = ? AND piece_id = ? AND tenant_id = ?",
                rs -> rs.next() ? rs.getObject("request_item_id", UUID.class) : null,
                sessionId, pieceId, tenantId);
            requests.onFinalDisposition(tenantId, requestItemId, stored, actorUserId);
        }

        return fetchItemByPiece(sessionId, pieceId, tenantId);
    }

    // ── Reprint ───────────────────────────────────────────────────────────────

    /**
     * Reprint is allowed in ANY piece status (change per FR-24 §3 — widens the old
     * ReturnSessionController.validateAndRecordReprint() gate, which only allowed
     * return_pending_inspection/damaged; that old gated endpoint is untouched — this is
     * a separate surface, per the transfers-spec precedent of not widening it). Reads
     * the stored barcode; never mints one. Non-status sibling-append via
     * InventoryLedger.recordLabelReprinted() (4th write path, unchanged).
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Map<String, Object> recordReprint(UUID sessionId, String pieceId, UUID actorUserId) {
        UUID tenantId = TenantContext.require();
        requireOpen(sessionId, tenantId);

        Map<String, Object> piece = jdbc.query(
            "SELECT p.id, p.barcode, p.current_order_id AS order_id, p.current_location_id AS location_id, " +
            "       s.id AS shipment_id " +
            "FROM pieces p " +
            "LEFT JOIN orders o    ON o.id = p.current_order_id AND o.tenant_id = ? " +
            "LEFT JOIN shipments s ON s.order_id = o.id AND s.tenant_id = ? AND s.shipment_leg = 'forward' " +
            "WHERE p.id = ? AND p.tenant_id = ?",
            rs -> {
                if (!rs.next()) return null;
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id",         rs.getString("id"));
                m.put("barcode",    rs.getString("barcode"));
                m.put("orderId",    rs.getObject("order_id",    UUID.class));
                m.put("locationId", rs.getObject("location_id", UUID.class));
                m.put("shipmentId", rs.getObject("shipment_id", UUID.class));
                return m;
            },
            tenantId, tenantId, pieceId, tenantId);

        if (piece == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Piece not found: " + pieceId);
        }

        ledger.recordLabelReprinted(pieceId, actorUserId,
            (UUID) piece.get("locationId"), (UUID) piece.get("orderId"), (UUID) piece.get("shipmentId"));

        return Map.of("pieceId", pieceId, "barcode", piece.get("barcode"));
    }

    /**
     * PRE-EXISTING, UNTOUCHED (FR-12 change 3) — the old single-piece gated reprint path.
     * Kept exactly as it was: only return_pending_inspection/damaged pieces qualify.
     * recordReprint() above is a deliberately separate, unrestricted surface for the new
     * session model — this one is not widened to match it, per the transfers-spec
     * precedent of never widening this specific gate (TransferService.
     * reprintOutstandingLabels() built its own method rather than reuse/widen this one).
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Map<String, Object> validateAndRecordReprint(String pieceId, UUID actorUserId) {
        UUID tenantId = TenantContext.require();

        Map<String, Object> piece = fetchPieceContextForOldReprintGate(pieceId, tenantId);
        PieceStatus current = PieceStatus.fromDb((String) piece.get("status"));

        if (current != PieceStatus.RETURN_PENDING_INSPECTION && current != PieceStatus.DAMAGED) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                "Label reprint in the returns flow is only available for pieces in " +
                "return_pending_inspection or damaged status (current: " + current.db + ")");
        }

        ledger.recordLabelReprinted(pieceId, actorUserId,
                (UUID) piece.get("locationId"), (UUID) piece.get("orderId"), (UUID) piece.get("shipmentId"));

        return Map.of("pieceId", pieceId, "barcode", piece.get("barcode"));
    }

    private Map<String, Object> fetchPieceContextForOldReprintGate(String pieceId, UUID tenantId) {
        Map<String, Object> row = jdbc.query(
            "SELECT p.id, p.barcode, p.status::text AS status, " +
            "       p.current_order_id AS order_id, " +
            "       p.current_location_id AS location_id, " +
            "       s.id AS shipment_id " +
            "FROM pieces p " +
            "LEFT JOIN orders o    ON o.id = p.current_order_id AND o.tenant_id = ? " +
            "LEFT JOIN shipments s ON s.order_id = o.id AND s.tenant_id = ? AND s.shipment_leg = 'forward' " +
            "WHERE p.id = ? AND p.tenant_id = ?",
            rs -> {
                if (!rs.next()) return null;
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id",         rs.getString("id"));
                m.put("barcode",    rs.getString("barcode"));
                m.put("status",     rs.getString("status"));
                m.put("orderId",    rs.getObject("order_id",    UUID.class));
                m.put("locationId", rs.getObject("location_id", UUID.class));
                m.put("shipmentId", rs.getObject("shipment_id", UUID.class));
                return m;
            },
            tenantId, tenantId, pieceId, tenantId);

        if (row == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Piece not found: " + pieceId);
        }
        return row;
    }

    // ── Close ─────────────────────────────────────────────────────────────────

    @Transactional
    public Map<String, Object> close(UUID sessionId, UUID actorUserId) {
        UUID tenantId = TenantContext.require();
        requireOpen(sessionId, tenantId);

        List<Map<String, Object>> pending = jdbc.queryForList(
            "SELECT i.piece_id AS \"pieceId\", p.barcode, pr.title AS \"productTitle\" " +
            "FROM return_session_items i " +
            "JOIN pieces p    ON p.id = i.piece_id " +
            "JOIN variants v  ON v.id = p.variant_id " +
            "JOIN products pr ON pr.id = v.product_id " +
            "WHERE i.session_id = ? AND i.tenant_id = ? AND i.disposition = 'pending' " +
            "ORDER BY i.scanned_at ASC",
            sessionId, tenantId);

        if (!pending.isEmpty()) {
            throw new ReturnSessionException(ReturnSessionException.Code.SESSION_CLOSE_BLOCKED,
                pending.size() + " piece(s) still need a disposition before this session can close",
                "يوجد " + pending.size() + " قطعة بحاجة إلى قرار قبل إمكانية إغلاق الجلسة",
                HttpStatus.CONFLICT,
                Map.of("blockingItems", pending));
        }

        Map<String, Object> counts = jdbc.query(
            "SELECT " +
            "  COUNT(*)                                          AS piece_count, " +
            "  COUNT(*) FILTER (WHERE disposition = 'restocked') AS restocked_count, " +
            "  COUNT(*) FILTER (WHERE disposition = 'damaged')   AS damaged_count, " +
            "  COUNT(*) FILTER (WHERE disposition = 'mismatch')  AS mismatch_count " +
            "FROM return_session_items WHERE session_id = ? AND tenant_id = ?",
            rs -> {
                rs.next();
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("pieceCount",      rs.getInt("piece_count"));
                m.put("restockedCount",  rs.getInt("restocked_count"));
                m.put("damagedCount",    rs.getInt("damaged_count"));
                m.put("mismatchCount",   rs.getInt("mismatch_count"));
                return m;
            },
            sessionId, tenantId);

        Integer shipmentCount = jdbc.queryForObject(
            "SELECT COUNT(*) FROM return_session_shipments WHERE session_id = ? AND tenant_id = ?",
            Integer.class, sessionId, tenantId);

        jdbc.update(
            "UPDATE return_sessions SET status = 'closed', closed_by = ?, closed_at = now() " +
            "WHERE id = ? AND tenant_id = ?",
            actorUserId, sessionId, tenantId);

        // Scan-as-truth intake completion (close only — abandon never gets here): a return
        // leg is intake-complete when it has scan evidence FROM THIS SESSION under the
        // canonical ShipmentLinkService.returnLegScanEvidenceSql (Step 4c-1): its own AWB was
        // scanned here, or it is its order's only unstamped return leg and a piece of the
        // order was legally scanned here. Either way the evidence needs a return_received
        // event of this session, so illegal-state (mismatch) scans, which write no event,
        // never complete a leg. Several legs of one order and no AWB → none is stamped
        // (never guess). The single-statement UPDATE evaluates "only unstamped leg" against
        // the pre-close state, so stamping one leg here can't make another eligible.
        // Step 5 (V101): also record HOW and BY WHOM — outcome 'scanned', the closing user
        // (NULL when ReturnSessionAutoCloseJob closes it = system), and this session.
        jdbc.update(
            "UPDATE shipments s SET return_intake_completed_at = now(), " +
            "    return_intake_outcome = 'scanned', return_intake_by = ?, return_intake_session_id = cs.session_id " +
            "FROM (SELECT ?::uuid AS session_id) cs " +
            "WHERE s.tenant_id = ? AND s.shipment_leg = 'return' " +
            "  AND s.return_intake_completed_at IS NULL " +
            "  AND " + ShipmentLinkService.returnLegScanEvidenceSql("cs.session_id"),
            actorUserId, sessionId, tenantId);

        Map<String, Object> result = new LinkedHashMap<>(counts);
        result.put("sessionId", sessionId.toString());
        result.put("shipmentCount", shipmentCount);
        result.put("closedAt", Instant.now(clock).toString());
        return result;
    }

    // ── Mark a courier-return parcel received (untracked order) ────────────────

    /**
     * Step 5: a courier-return (CRP) parcel whose order Traced never tracked has no Traced
     * labels to scan, so its intake can never complete through a piece scan. A worker opens
     * the parcel, checks it against Bosta's note, and marks it received here. NO piece is
     * created or moved and NO Shopify write happens — the return_to_receive exception asks a
     * manager to add the item in their next Receiving session.
     *
     * Allowed only when: the session is open; the shipment is a return leg of this tenant;
     * its AWB was scanned in THIS session; its intake is not already complete; and the order
     * is untracked (ShipmentLinkService.orderUntrackedSql). Otherwise 409 with a specific
     * message; another tenant's shipment is a plain 404.
     */
    @Transactional
    public void markReceived(UUID sessionId, UUID shipmentId, UUID actorUserId) {
        UUID tenantId = TenantContext.require();
        requireOpen(sessionId, tenantId);
        Map<String, Object> leg = requireScannedParcel(sessionId, shipmentId, tenantId);

        if (!"return".equals(leg.get("shipment_leg"))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Only courier-return parcels can be marked received.");
        }
        if (leg.get("return_intake_completed_at") != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "This parcel's intake is already complete.");
        }
        if (!shipmentLinkService.isOrderUntracked((UUID) leg.get("order_id"), tenantId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "This order has tracked items — scan them instead.");
        }
        int updated = jdbc.update(
            "UPDATE shipments SET return_intake_completed_at = now(), " +
            "    return_intake_outcome = 'received_untracked', return_intake_by = ?, " +
            "    return_intake_session_id = ? " +
            "WHERE id = ? AND tenant_id = ? AND shipment_leg = 'return' " +
            "  AND return_intake_completed_at IS NULL",
            actorUserId, sessionId, shipmentId, tenantId);
        if (updated != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "This parcel's intake is already complete.");
        }
    }

    /**
     * Reverses {@link #markReceived} — only while the SAME session is still open, and only
     * for an intake this session recorded as 'received_untracked'. Clears all four fields,
     * so the parcel is back in "waiting to be scanned" and the exception disappears.
     */
    @Transactional
    public void undoMarkReceived(UUID sessionId, UUID shipmentId, UUID actorUserId) {
        UUID tenantId = TenantContext.require();
        requireOpen(sessionId, tenantId);
        requireScannedParcel(sessionId, shipmentId, tenantId);
        int updated = jdbc.update(
            "UPDATE shipments SET return_intake_completed_at = NULL, return_intake_outcome = NULL, " +
            "    return_intake_by = NULL, return_intake_session_id = NULL " +
            "WHERE id = ? AND tenant_id = ? AND shipment_leg = 'return' " +
            "  AND return_intake_outcome = 'received_untracked' AND return_intake_session_id = ?",
            shipmentId, tenantId, sessionId);
        if (updated != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Only a parcel marked received in this session can be undone.");
        }
    }

    /** The shipment (this tenant, else 404) whose AWB was scanned in this session (else 409). */
    private Map<String, Object> requireScannedParcel(UUID sessionId, UUID shipmentId, UUID tenantId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT s.id, s.order_id, s.shipment_leg, s.tracking_number, s.return_intake_completed_at, " +
            "       EXISTS (SELECT 1 FROM return_session_shipments rss " +
            "               WHERE rss.session_id = ? AND rss.tenant_id = s.tenant_id " +
            "                 AND rss.awb = s.tracking_number) AS scanned_here " +
            "FROM shipments s WHERE s.id = ? AND s.tenant_id = ?",
            sessionId, shipmentId, tenantId);
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Shipment not found");
        }
        Map<String, Object> leg = rows.get(0);
        if (!Boolean.TRUE.equals(leg.get("scanned_here"))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Scan this parcel's AWB in this session first.");
        }
        return leg;
    }

    // ── List / detail ─────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public Map<String, Object> listSessions(int page, int size) {
        UUID tenantId = TenantContext.require();
        List<Map<String, Object>> items = jdbc.queryForList(
            "SELECT rs.id, rs.status, rs.opened_by, rs.opened_at, rs.closed_by, rs.closed_at, rs.note, " +
            "       (SELECT COUNT(*) FROM return_session_items i WHERE i.session_id = rs.id) AS piece_count, " +
            "       (SELECT COUNT(*) FILTER (WHERE i.disposition = 'restocked') " +
            "        FROM return_session_items i WHERE i.session_id = rs.id) AS restocked_count, " +
            "       (SELECT COUNT(*) FILTER (WHERE i.disposition = 'damaged') " +
            "        FROM return_session_items i WHERE i.session_id = rs.id) AS damaged_count, " +
            "       (SELECT COUNT(*) FILTER (WHERE i.disposition = 'mismatch') " +
            "        FROM return_session_items i WHERE i.session_id = rs.id) AS mismatch_count " +
            "FROM return_sessions rs " +
            "WHERE rs.tenant_id = ? " +
            "ORDER BY rs.opened_at DESC, rs.id DESC " +
            "LIMIT ? OFFSET ?",
            tenantId, size, (long) page * size);
        int total = jdbc.queryForObject(
            "SELECT COUNT(*) FROM return_sessions WHERE tenant_id = ?", Integer.class, tenantId);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", items);
        result.put("total", total);
        return result;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getSession(UUID sessionId) {
        UUID tenantId = TenantContext.require();
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT id, status, opened_by, opened_at, closed_by, closed_at, note " +
            "FROM return_sessions WHERE id = ? AND tenant_id = ?",
            sessionId, tenantId);
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Return session not found");
        }

        List<Map<String, Object>> items = jdbc.queryForList(
            "SELECT i.id, i.piece_id, p.barcode, p.status::text AS status, " +
            "       v.title AS variant_title, pr.title AS product_title, v.sku, " +
            "       i.disposition, i.unexpected, i.scan_source, i.damage_reason, " +
            "       i.scanned_at, i.disposition_at, p.short_code " +
            "FROM return_session_items i " +
            "JOIN pieces p    ON p.id  = i.piece_id " +
            "JOIN variants v  ON v.id  = p.variant_id " +
            "JOIN products pr ON pr.id = v.product_id " +
            "WHERE i.session_id = ? AND i.tenant_id = ? " +
            "ORDER BY i.scanned_at ASC",
            sessionId, tenantId);
        List<Map<String, Object>> expected = fetchExpectedPieces(sessionId, tenantId);

        Map<String, Object> result = new LinkedHashMap<>(rows.get(0));
        result.put("items", items);
        result.put("expectedPieces", expected);
        // Re-read on every load (the scan handler reloads detail, it never keeps the scan
        // response), so the session view renders Bosta's parcel description from here.
        result.put("courierReturns", courierReturnInfo(
            "rss.session_id = ? AND rss.tenant_id = ?", sessionId, tenantId));
        addParcelView(result, sessionId, tenantId, items, expected);
        return result;
    }

    /**
     * Step 5 parcel view — additive to items / expectedPieces / courierReturns (kept for
     * compatibility). One parcel per AWB scanned in this session, newest first, with the
     * expected pieces grouped under the AWB they came from and the session items that belong
     * to that parcel's order; otherItems = items tied to no scanned AWB (plain piece scans);
     * lastScan = the newest of piece scan / AWB scan / mark-received, for the feedback strip.
     *
     * An item's order is the order on its return_received event in THIS session, falling
     * back to pieces.current_order_id (illegal-state scans write no event) — never
     * current_order_id alone, because restock() clears it.
     */
    private void addParcelView(Map<String, Object> result, UUID sessionId, UUID tenantId,
                               List<Map<String, Object>> items, List<Map<String, Object>> expected) {
        List<Map<String, Object>> legs = jdbc.queryForList(
            "SELECT s.id AS shipment_id, rss.awb, s.shipment_leg, s.order_id, o.number AS order_number, " +
            "       o.customer_name, rss.linked_at, " +
            "       CASE WHEN s.shipment_leg = 'return' THEN " + ShipmentLinkService.RETURN_LEG_ENTERED_RETURNED_AT_SQL +
            "            ELSE s.returned_at END AS returned_at, " +
            "       (s.raw #>> '{returnSpecs,packageDetails,itemsCount}')::int AS items_count, " +
            "       s.raw #>> '{returnSpecs,packageDetails,description}'   AS description, " +
            "       s.raw #>> '{returnSpecs,packageDetails,descriptionAr}' AS description_ar, " +
            "       " + ShipmentLinkService.orderUntrackedSql("s.order_id") + " AS untracked, " +
            "       s.return_intake_outcome, s.return_intake_completed_at, s.return_intake_session_id, " +
            "       u.name AS marked_by, rq.id AS request_id, rq.reference AS request_reference " +
            "FROM return_session_shipments rss " +
            "JOIN shipments s ON s.tracking_number = rss.awb AND s.tenant_id = rss.tenant_id " +
            "JOIN orders o    ON o.id = s.order_id AND o.tenant_id = s.tenant_id " +
            "LEFT JOIN users u ON u.id = s.return_intake_by " +
            "LEFT JOIN return_requests rq ON rq.return_shipment_id = s.id AND rq.tenant_id = s.tenant_id " +
            "WHERE rss.session_id = ? AND rss.tenant_id = ? " +
            "ORDER BY rss.linked_at DESC, rss.id DESC",
            sessionId, tenantId);

        Map<Object, Object> itemOrder = new HashMap<>();
        jdbc.query(
            "SELECT i.id, COALESCE((SELECT pe.order_id FROM piece_events pe " +
            "                       WHERE pe.piece_id = i.piece_id AND pe.tenant_id = i.tenant_id " +
            "                         AND pe.event_type = 'return_received' " +
            "                         AND pe.metadata->>'session_id' = ? " +
            "                       ORDER BY pe.occurred_at DESC, pe.id DESC LIMIT 1), " +
            "                      p.current_order_id) AS order_id " +
            "FROM return_session_items i JOIN pieces p ON p.id = i.piece_id " +
            "WHERE i.session_id = ? AND i.tenant_id = ?",
            (org.springframework.jdbc.core.RowCallbackHandler) rs -> itemOrder.put(rs.getObject("id"), rs.getObject("order_id")),
            sessionId.toString(), sessionId, tenantId);

        // Step 4d-2: scans attributed to a return request (return_session_items.request_item_id)
        // belong to the parcel card of the leg linked to that request — exactly, per leg.
        Map<Object, Object> itemRequest = new HashMap<>();
        jdbc.query(
            "SELECT i.id, ri.request_id FROM return_session_items i " +
            "JOIN return_request_items ri ON ri.id = i.request_item_id AND ri.tenant_id = i.tenant_id " +
            "WHERE i.session_id = ? AND i.tenant_id = ?",
            (org.springframework.jdbc.core.RowCallbackHandler) rs -> itemRequest.put(rs.getObject("id"), rs.getObject("request_id")),
            sessionId, tenantId);
        Set<Object> linkedRequests = new HashSet<>();
        for (Map<String, Object> leg : legs) if (leg.get("request_id") != null) linkedRequests.add(leg.get("request_id"));

        List<Map<String, Object>> parcels = new ArrayList<>();
        Set<Object> ordersTaken = new HashSet<>();
        Set<Object> itemsTaken = new HashSet<>();
        for (Map<String, Object> it : items) {
            if (linkedRequests.contains(itemRequest.get(it.get("id")))) itemsTaken.add(it.get("id"));
        }
        Set<Object> expectedTaken = new HashSet<>();
        for (Map<String, Object> leg : legs) {
            Object orderId = leg.get("order_id");
            String awb = (String) leg.get("awb");
            Object requestId = leg.get("request_id");

            List<Map<String, Object>> parcelExpected = new ArrayList<>();
            List<Map<String, Object>> scanned = new ArrayList<>();
            if (requestId != null) {
                // Linked to a request: exactly its items — still-awaited ones as expected, the
                // scans attributed to it as scanned.
                parcelExpected.addAll(jdbc.queryForList(
                    "SELECT p.id, p.barcode, p.status::text AS status, v.title AS variant_title, " +
                    "       pr.title AS product_title, v.sku, ?::text AS awb " +
                    "FROM return_request_items ri " +
                    "JOIN pieces p    ON p.id = ri.piece_id AND p.tenant_id = ri.tenant_id " +
                    "JOIN variants v  ON v.id = p.variant_id " +
                    "JOIN products pr ON pr.id = v.product_id " +
                    "WHERE ri.request_id = ? AND ri.tenant_id = ? AND ri.item_status = 'awaiting' " +
                    "  AND NOT EXISTS (SELECT 1 FROM return_session_items si " +
                    "                  WHERE si.session_id = ? AND si.piece_id = p.id) " +
                    "ORDER BY pr.title, v.title, p.id",
                    awb, requestId, tenantId, sessionId));
                for (Map<String, Object> it : items) {
                    if (requestId.equals(itemRequest.get(it.get("id")))) scanned.add(it);
                }
            } else {
                // A leg with no request: the order-level grouping — scanned items go to the order's
                // first such parcel card.
                boolean firstParcelForOrder = ordersTaken.add(orderId);

                for (Map<String, Object> e : expected) {
                    if (awb.equals(e.get("awb")) && expectedTaken.add(e.get("id"))) parcelExpected.add(e);
                }
                if (firstParcelForOrder) {
                    for (Map<String, Object> it : items) {
                        if (orderId != null && orderId.equals(itemOrder.get(it.get("id"))) && itemsTaken.add(it.get("id"))) {
                            scanned.add(it);
                        }
                    }
                }
            }
            String outcome = (String) leg.get("return_intake_outcome");
            boolean anyPending = scanned.stream().anyMatch(it -> "pending".equals(it.get("disposition")));
            boolean complete = outcome != null
                || (!scanned.isEmpty() && parcelExpected.isEmpty() && !anyPending);

            Map<String, Object> parcel = new LinkedHashMap<>();
            parcel.put("shipmentId", leg.get("shipment_id").toString());
            parcel.put("awb", awb);
            parcel.put("leg", leg.get("shipment_leg"));
            parcel.put("orderNumber", leg.get("order_number"));
            parcel.put("requestReference", leg.get("request_reference"));
            parcel.put("customerShortName", shortName((String) leg.get("customer_name")));
            parcel.put("returnedAt", leg.get("returned_at"));
            if ("return".equals(leg.get("shipment_leg"))) {
                Map<String, Object> bosta = new LinkedHashMap<>();
                bosta.put("itemsCount", leg.get("items_count"));
                bosta.put("description", leg.get("description"));
                bosta.put("descriptionAr", leg.get("description_ar"));
                parcel.put("bosta", bosta);
            } else {
                parcel.put("bosta", null);
            }
            parcel.put("tracked", !Boolean.TRUE.equals(leg.get("untracked")));
            parcel.put("intakeOutcome", outcome);
            parcel.put("markedBy", outcome != null ? leg.get("marked_by") : null);
            parcel.put("markedAt", outcome != null ? leg.get("return_intake_completed_at") : null);
            parcel.put("markedInThisSession", sessionId.equals(leg.get("return_intake_session_id")));
            parcel.put("expectedPieces", parcelExpected);
            parcel.put("scannedItems", scanned);
            Map<String, Object> counts = new LinkedHashMap<>();
            counts.put("expected", scanned.size() + parcelExpected.size());
            counts.put("scanned", scanned.size());
            parcel.put("counts", counts);
            parcel.put("complete", complete);
            parcels.add(parcel);
        }

        List<Map<String, Object>> otherItems = new ArrayList<>();
        for (Map<String, Object> it : items) {
            if (!itemsTaken.contains(it.get("id"))) otherItems.add(it);
        }
        result.put("parcels", parcels);
        result.put("otherItems", otherItems);
        result.put("lastScan", lastScan(sessionId, tenantId, items, legs));
    }

    /** Newest of: piece scan, AWB scan, mark-received in this session. Null when nothing yet. */
    private Map<String, Object> lastScan(UUID sessionId, UUID tenantId,
                                         List<Map<String, Object>> items, List<Map<String, Object>> legs) {
        Map<String, Object> best = null;
        java.sql.Timestamp bestAt = null;
        for (Map<String, Object> it : items) {
            java.sql.Timestamp at = (java.sql.Timestamp) it.get("scanned_at");
            if (at != null && (bestAt == null || at.after(bestAt))) {
                bestAt = at;
                Object code = it.get("short_code") != null ? it.get("short_code") : it.get("barcode");
                best = scanEntry("piece", code, it.get("product_title"), at);
            }
        }
        for (Map<String, Object> leg : legs) {
            java.sql.Timestamp at = (java.sql.Timestamp) leg.get("linked_at");
            if (at != null && (bestAt == null || at.after(bestAt))) {
                bestAt = at;
                best = scanEntry("awb", leg.get("awb"), null, at);
            }
            java.sql.Timestamp marked = (java.sql.Timestamp) leg.get("return_intake_completed_at");
            if ("received_untracked".equals(leg.get("return_intake_outcome"))
                    && sessionId.equals(leg.get("return_intake_session_id"))
                    && marked != null && (bestAt == null || marked.after(bestAt))) {
                bestAt = marked;
                best = scanEntry("marked_received", leg.get("awb"), null, marked);
            }
        }
        return best;
    }

    private static Map<String, Object> scanEntry(String kind, Object code, Object label, java.sql.Timestamp at) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kind", kind);
        m.put("code", code);
        m.put("label", label);
        m.put("at", at.toInstant().toString());
        return m;
    }

    /** "Mariam Samir" → "Mariam S." ; single name as-is ; null stays null. First name + last initial only. */
    static String shortName(String fullName) {
        if (fullName == null || fullName.isBlank()) return null;
        String[] parts = fullName.trim().split("\\s+");
        if (parts.length == 1) return parts[0];
        return parts[0] + " " + parts[parts.length - 1].codePoints()
            .limit(1).collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append) + ".";
    }

    // ── Analytics (derive-on-read) ────────────────────────────────────────────

    /**
     * "Unassigned pending" is defined by the session relationship, not status alone
     * (load-bearing per the FR-24 build plan, change B): a piece at
     * return_pending_inspection resurfaces here unless it has an item row that is
     * either in a still-open session, or already resolved (disposition <> 'pending').
     * This is what stops a mismatched piece from re-appearing here even though it
     * physically remains at return_pending_inspection forever (mismatch never
     * transitions it) — its item row has a resolved disposition, so the NOT EXISTS
     * excludes it. An abandoned session's still-pending items do NOT exclude their
     * pieces (status='abandoned' is neither 'open' nor a resolved disposition) — those
     * pieces correctly resurface, which is the point of change B's no-revert design.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> analytics(Instant from, Instant to) {
        UUID tenantId = TenantContext.require();
        Instant effTo   = to != null ? to : clock.instant();
        Instant effFrom = from != null ? from : effTo.minus(30, ChronoUnit.DAYS);
        Timestamp tsFrom = Timestamp.from(effFrom);
        Timestamp tsTo   = Timestamp.from(effTo);

        Integer totalReturns = jdbc.queryForObject(
            "SELECT COUNT(*) FROM return_session_items WHERE tenant_id = ? AND scanned_at BETWEEN ? AND ?",
            Integer.class, tenantId, tsFrom, tsTo);
        Integer restockedCount = jdbc.queryForObject(
            "SELECT COUNT(*) FROM return_session_items " +
            "WHERE tenant_id = ? AND disposition = 'restocked' AND disposition_at BETWEEN ? AND ?",
            Integer.class, tenantId, tsFrom, tsTo);
        Integer damagedCount = jdbc.queryForObject(
            "SELECT COUNT(*) FROM return_session_items " +
            "WHERE tenant_id = ? AND disposition = 'damaged' AND disposition_at BETWEEN ? AND ?",
            Integer.class, tenantId, tsFrom, tsTo);
        Integer mismatchCount = jdbc.queryForObject(
            "SELECT COUNT(*) FROM return_session_items " +
            "WHERE tenant_id = ? AND disposition = 'mismatch' AND disposition_at BETWEEN ? AND ?",
            Integer.class, tenantId, tsFrom, tsTo);

        int neverReceivedWindowDays = jdbc.queryForObject(
            "SELECT never_received_window_days FROM tenants WHERE id = ?", Integer.class, tenantId);
        int expectedNotScannedCount = returnService.neverReceived(neverReceivedWindowDays).size();

        Integer unassignedPendingCount = jdbc.queryForObject(unassignedPendingCountSql(), Integer.class, tenantId);
        List<Map<String, Object>> unassignedPending = jdbc.queryForList(unassignedPendingListSql(), tenantId);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("from", effFrom.toString());
        result.put("to", effTo.toString());
        result.put("totalReturns", totalReturns);
        result.put("restockedCount", restockedCount);
        result.put("damagedCount", damagedCount);
        result.put("mismatchCount", mismatchCount);
        result.put("expectedNotScannedCount", expectedNotScannedCount);
        result.put("unassignedPendingCount", unassignedPendingCount);
        result.put("unassignedPending", unassignedPending);
        return result;
    }

    private static String unassignedPendingPredicate() {
        return
            "p.tenant_id = ? AND p.status = 'return_pending_inspection'::piece_status " +
            "  AND NOT EXISTS ( " +
            "      SELECT 1 FROM return_session_items i " +
            "      JOIN return_sessions s ON i.session_id = s.id " +
            "      WHERE i.piece_id = p.id AND i.tenant_id = p.tenant_id " +
            "        AND (s.status = 'open' OR i.disposition <> 'pending')) ";
    }

    private static String unassignedPendingCountSql() {
        return "SELECT COUNT(*) FROM pieces p WHERE " + unassignedPendingPredicate();
    }

    private static String unassignedPendingListSql() {
        return
            "SELECT p.id AS \"pieceId\", p.barcode, pr.title AS \"productTitle\", v.sku, " +
            "       p.last_event_at AS \"lastEventAt\" " +
            "FROM pieces p " +
            "JOIN variants v  ON v.id  = p.variant_id " +
            "JOIN products pr ON pr.id = v.product_id " +
            "WHERE " + unassignedPendingPredicate() +
            "ORDER BY p.last_event_at ASC LIMIT 50";
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private Map<String, Object> fetchPieceByScan(String scan, UUID tenantId) {
        return jdbc.query(
            "SELECT p.id, p.status::text AS status, p.variant_id, " +
            "       p.current_order_id AS order_id, s.id AS shipment_id " +
            "FROM pieces p " +
            "LEFT JOIN orders o    ON o.id = p.current_order_id AND o.tenant_id = ? " +
            "LEFT JOIN shipments s ON s.order_id = o.id AND s.tenant_id = ? AND s.shipment_leg = 'forward' " +
            "WHERE (p.barcode = ? OR p.id = ? OR p.short_code = ?) AND p.tenant_id = ?",
            rs -> {
                if (!rs.next()) return null;
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id",         rs.getString("id"));
                m.put("status",     rs.getString("status"));
                m.put("variant_id", rs.getObject("variant_id", UUID.class));
                m.put("order_id",   rs.getObject("order_id",   UUID.class));
                m.put("shipment_id", rs.getObject("shipment_id", UUID.class));
                return m;
            },
            tenantId, tenantId, scan, scan, scan, tenantId);
    }

    /** Expected-but-unscanned pieces for every AWB scanned into this session — recomputed on read. */
    private List<Map<String, Object>> fetchExpectedPieces(UUID sessionId, UUID tenantId) {
        return jdbc.queryForList(
            "SELECT p.id, p.barcode, p.status::text AS status, " +
            "       v.title AS variant_title, pr.title AS product_title, v.sku, " +
            "       rss.awb " +
            "FROM return_session_shipments rss " +
            "JOIN shipments s    ON s.tracking_number = rss.awb AND s.tenant_id = rss.tenant_id " +
            "JOIN orders o       ON o.id = s.order_id AND o.tenant_id = rss.tenant_id " +
            "JOIN order_items oi ON oi.order_id = o.id " +
            "JOIN allocations a  ON a.order_item_id = oi.id " +
            "                    AND a.status IN ('active','packed','released') " +
            "                    AND a.id = ( " +
            "                        SELECT a2.id FROM allocations a2 " +
            "                        WHERE a2.piece_id = a.piece_id " +
            "                        ORDER BY a2.allocated_at DESC LIMIT 1) " +
            "JOIN pieces p       ON p.id = a.piece_id AND p.tenant_id = rss.tenant_id " +
            "JOIN variants v     ON v.id = p.variant_id " +
            "JOIN products pr    ON pr.id = v.product_id " +
            "WHERE rss.session_id = ? AND rss.tenant_id = ? " +
            "  AND p.status IN ('return_in_transit'::piece_status, 'with_courier'::piece_status, " +
            "                   'awaiting_pickup'::piece_status, 'delivered'::piece_status, " +
            "                   'return_pending_inspection'::piece_status) " +
            "  AND NOT EXISTS ( " +
            "      SELECT 1 FROM return_session_items i " +
            "      WHERE i.session_id = rss.session_id AND i.piece_id = p.id) " +
            "ORDER BY p.last_event_at ASC",
            sessionId, tenantId);
    }

    private Map<String, Object> itemRow(UUID itemId, UUID tenantId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT i.id, i.piece_id, p.barcode, p.status::text AS status, " +
            "       v.title AS variant_title, pr.title AS product_title, v.sku, " +
            "       i.disposition, i.unexpected, i.scan_source, i.damage_reason, " +
            "       i.scanned_at, i.disposition_at " +
            "FROM return_session_items i " +
            "JOIN pieces p    ON p.id  = i.piece_id " +
            "JOIN variants v  ON v.id  = p.variant_id " +
            "JOIN products pr ON pr.id = v.product_id " +
            "WHERE i.id = ? AND i.tenant_id = ?",
            itemId, tenantId);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Item not found");
        return rows.get(0);
    }

    private Map<String, Object> fetchItemByPiece(UUID sessionId, String pieceId, UUID tenantId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT i.id, i.piece_id, p.barcode, p.status::text AS status, " +
            "       v.title AS variant_title, pr.title AS product_title, v.sku, " +
            "       i.disposition, i.unexpected, i.scan_source, i.damage_reason, " +
            "       i.scanned_at, i.disposition_at " +
            "FROM return_session_items i " +
            "JOIN pieces p    ON p.id  = i.piece_id " +
            "JOIN variants v  ON v.id  = p.variant_id " +
            "JOIN products pr ON pr.id = v.product_id " +
            "WHERE i.session_id = ? AND i.piece_id = ? AND i.tenant_id = ?",
            sessionId, pieceId, tenantId);
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Item not found in this session");
        }
        return rows.get(0);
    }

    private boolean withinReturnWindow(String pieceId, UUID tenantId) {
        int windowDays = jdbc.queryForObject(
            "SELECT customer_return_window_days FROM tenants WHERE id = ?", Integer.class, tenantId);
        Timestamp lastEventAt = jdbc.queryForObject(
            "SELECT last_event_at FROM pieces WHERE id = ? AND tenant_id = ?",
            Timestamp.class, pieceId, tenantId);
        Instant cutoff = clock.instant().minus(windowDays, ChronoUnit.DAYS);
        return lastEventAt != null && !lastEventAt.toInstant().isBefore(cutoff);
    }

    private void requireOpen(UUID sessionId, UUID tenantId) {
        List<String> rows = jdbc.queryForList(
            "SELECT status FROM return_sessions WHERE id = ? AND tenant_id = ?",
            String.class, sessionId, tenantId);
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Return session not found");
        }
        if (!"open".equals(rows.get(0))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Return session is not open");
        }
    }
}
