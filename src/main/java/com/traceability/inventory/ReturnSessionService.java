package com.traceability.inventory;

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

    private final JdbcTemplate    jdbc;
    private final InventoryLedger ledger;
    private final ReturnService   returnService;
    private final Clock           clock;

    public ReturnSessionService(JdbcTemplate jdbc, InventoryLedger ledger,
                                ReturnService returnService, Clock clock) {
        this.jdbc          = jdbc;
        this.ledger        = ledger;
        this.returnService = returnService;
        this.clock         = clock;
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

        switch (current) {
            case RETURN_IN_TRANSIT -> {
                legal = true;
                String meta = "{\"return_kind\":\"rto\"," + metaSuffix + "}";
                ledger.transition(pieceId, PieceStatus.RETURN_IN_TRANSIT, PieceStatus.RETURN_PENDING_INSPECTION,
                    "return_received", actorUserId, new TransitionContext(orderId, shipmentId, locationId, orderId, meta));
            }
            case WITH_COURIER -> {
                legal = true; unexpected = true;
                String meta = "{\"return_kind\":\"rto\"," + metaSuffix + "}";
                ledger.transition(pieceId, PieceStatus.WITH_COURIER, PieceStatus.RETURN_PENDING_INSPECTION,
                    "return_received", actorUserId, new TransitionContext(orderId, shipmentId, locationId, orderId, meta));
            }
            case AWAITING_PICKUP -> {
                legal = true; unexpected = true;
                String meta = "{\"return_kind\":\"rto\"," + metaSuffix + "}";
                ledger.transition(pieceId, PieceStatus.AWAITING_PICKUP, PieceStatus.RETURN_PENDING_INSPECTION,
                    "return_received", actorUserId, new TransitionContext(orderId, shipmentId, locationId, orderId, meta));
            }
            case DELIVERED -> {
                if (withinReturnWindow(pieceId, tenantId)) {
                    legal = true;
                    String meta = "{\"return_kind\":\"customer_after_delivery\"," + metaSuffix + "}";
                    ledger.transition(pieceId, PieceStatus.DELIVERED, PieceStatus.RETURN_PENDING_INSPECTION,
                        "return_received", actorUserId, new TransitionContext(orderId, shipmentId, locationId, orderId, meta));
                } else {
                    // Outside the customer return window: ours, but no longer return-eligible.
                    // Illegal-state fork — no transition, mismatch-only.
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
            "INSERT INTO return_session_items (id, tenant_id, session_id, piece_id, scanned_by, scan_source, unexpected) " +
            "VALUES (?, ?, ?, ?, ?, 'barcode', ?)",
            itemId, tenantId, sessionId, pieceId, actorUserId, unexpected);

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
        return result;
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

        Map<String, Object> result = new LinkedHashMap<>(counts);
        result.put("sessionId", sessionId.toString());
        result.put("shipmentCount", shipmentCount);
        result.put("closedAt", Instant.now(clock).toString());
        return result;
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
            "       i.scanned_at, i.disposition_at " +
            "FROM return_session_items i " +
            "JOIN pieces p    ON p.id  = i.piece_id " +
            "JOIN variants v  ON v.id  = p.variant_id " +
            "JOIN products pr ON pr.id = v.product_id " +
            "WHERE i.session_id = ? AND i.tenant_id = ? " +
            "ORDER BY i.scanned_at ASC",
            sessionId, tenantId);

        Map<String, Object> result = new LinkedHashMap<>(rows.get(0));
        result.put("items", items);
        result.put("expectedPieces", fetchExpectedPieces(sessionId, tenantId));
        return result;
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
            "SELECT p.id, p.status::text AS status, " +
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
