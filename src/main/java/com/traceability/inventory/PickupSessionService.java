package com.traceability.inventory;

import com.traceability.tenancy.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.*;

/**
 * FR-16 Phase 1 — Pickup session lifecycle.
 *
 * Flow: open → scan* → close
 *
 * At close:
 *   - Every scanned forward-leg shipment receives internal_state='with_courier'
 *     + custody_locked_by_scan=true (Traced-owned custody state).
 *   - Pieces in packed/awaiting_pickup transition to with_courier via InventoryLedger
 *     using the 'handed_to_courier' event type (person-attributed).
 *   - Pieces in any other status cannot be transitioned; an exception_resolutions
 *     record is raised per affected piece so the operator can see the custody gap.
 *
 * The Bosta API is NOT called in Phase 1 (merchant manages pickups in Bosta's dashboard).
 * custody_locked_by_scan protects the with_courier state from being demoted by Bosta's
 * pre-transit webhooks (codes 10/11/20) arriving after physical handover.
 */
@Service
public class PickupSessionService {

    private static final Logger log = LoggerFactory.getLogger(PickupSessionService.class);

    private final JdbcTemplate     jdbc;
    private final TransactionTemplate tx;
    private final InventoryLedger  ledger;

    public PickupSessionService(JdbcTemplate jdbc,
                                 PlatformTransactionManager txm,
                                 InventoryLedger ledger) {
        this.jdbc   = jdbc;
        this.tx     = new TransactionTemplate(txm);
        this.ledger = ledger;
    }

    // ── Public types ──────────────────────────────────────────────────────────

    public enum ScanOutcome { ACCEPTED, DUPLICATE, OTHER_SESSION, NOT_FORWARD_LEG, NOT_PACKED, UNKNOWN_AWB }

    public record SessionSummary(
            UUID id, String sessionStatus, LocalDate scheduledDate,
            String scheduledTimeSlot, int scannedCount,
            String openedByName, String createdAt) {}

    public record ScanEntry(
            UUID shipmentId, String trackingNumber, String orderNumber,
            BigDecimal codAmount, String scannedAt, String scannedByName) {}

    public record SessionDetail(
            UUID id, String sessionStatus, LocalDate scheduledDate,
            String scheduledTimeSlot, String notes, int scannedCount,
            String openedByName, String closedByName,
            String createdAt, String closedAt,
            List<ScanEntry> scans) {}

    public record ScanResult(ScanOutcome outcome, ScanEntry entry) {}

    public record CloseResult(int shipmentsClosed, List<String> pieceExceptions) {}

    // ── Open ─────────────────────────────────────────────────────────────────

    public UUID openSession(UUID tenantId, UUID userId,
                             LocalDate scheduledDate, String timeSlot, String notes) {
        return TenantContext.runAs(tenantId, () ->
            tx.execute(s -> {
                UUID pid = jdbc.query(
                    "INSERT INTO pickups " +
                    "(tenant_id, session_status, scheduled_date, scheduled_time_slot," +
                    " notes, opened_by_user_id) " +
                    "VALUES (?, 'open', ?, ?, ?, ?) RETURNING id",
                    rs -> rs.next() ? rs.getObject("id", UUID.class) : null,
                    tenantId, Date.valueOf(scheduledDate), timeSlot, notes, userId);
                if (pid == null) throw new RuntimeException("pickup INSERT returned no id");
                return pid;
            }));
    }

    // ── List ──────────────────────────────────────────────────────────────────

    public List<SessionSummary> listSessions(UUID tenantId) {
        return TenantContext.runAs(tenantId, () ->
            tx.execute(s -> jdbc.query(
                """
                SELECT p.id, p.session_status, p.scheduled_date,
                       p.scheduled_time_slot, p.created_at::text,
                       u.name AS opened_by_name,
                       (SELECT COUNT(*) FROM pickup_shipments ps
                        WHERE ps.pickup_id = p.id) AS scanned_count
                FROM pickups p
                LEFT JOIN users u ON u.id = p.opened_by_user_id
                WHERE p.tenant_id = ?
                ORDER BY p.created_at DESC
                LIMIT 100
                """,
                (rs, i) -> new SessionSummary(
                    rs.getObject("id", UUID.class),
                    rs.getString("session_status"),
                    rs.getDate("scheduled_date").toLocalDate(),
                    rs.getString("scheduled_time_slot"),
                    rs.getInt("scanned_count"),
                    rs.getString("opened_by_name"),
                    rs.getString("created_at")),
                tenantId)));
    }

    // ── Detail ────────────────────────────────────────────────────────────────

    public SessionDetail getSession(UUID tenantId, UUID pickupId) {
        record HeaderRow(UUID id, String status, LocalDate date, String slot,
                         String notes, String openedBy, String closedBy,
                         String createdAt, String closedAt) {}

        HeaderRow hdr = TenantContext.runAs(tenantId, () ->
            tx.execute(s -> jdbc.query(
                """
                SELECT p.id, p.session_status, p.scheduled_date,
                       p.scheduled_time_slot, p.notes,
                       p.created_at::text, p.closed_at::text,
                       uo.name AS opened_by, uc.name AS closed_by
                FROM pickups p
                LEFT JOIN users uo ON uo.id = p.opened_by_user_id
                LEFT JOIN users uc ON uc.id = p.closed_by_user_id
                WHERE p.id = ? AND p.tenant_id = ?
                """,
                rs -> {
                    if (!rs.next()) return null;
                    return new HeaderRow(
                        rs.getObject("id", UUID.class),
                        rs.getString("session_status"),
                        rs.getDate("scheduled_date").toLocalDate(),
                        rs.getString("scheduled_time_slot"),
                        rs.getString("notes"),
                        rs.getString("opened_by"),
                        rs.getString("closed_by"),
                        rs.getString("created_at"),
                        rs.getString("closed_at"));
                }, pickupId, tenantId)));

        if (hdr == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Session not found");

        List<ScanEntry> scans = TenantContext.runAs(tenantId, () ->
            tx.execute(s -> jdbc.query(
                """
                SELECT s.id AS shipment_id, s.tracking_number,
                       o.number AS order_number, s.cod_amount,
                       ps.scanned_at::text, u.name AS scanned_by_name
                FROM pickup_shipments ps
                JOIN shipments s ON s.id = ps.shipment_id
                JOIN orders    o ON o.id = s.order_id
                LEFT JOIN users u ON u.id = ps.scanned_by_user_id
                WHERE ps.pickup_id = ? AND ps.tenant_id = ?
                ORDER BY ps.scanned_at DESC
                """,
                (rs, i) -> new ScanEntry(
                    rs.getObject("shipment_id", UUID.class),
                    rs.getString("tracking_number"),
                    rs.getString("order_number"),
                    rs.getBigDecimal("cod_amount"),
                    rs.getString("scanned_at"),
                    rs.getString("scanned_by_name")),
                pickupId, tenantId)));

        return new SessionDetail(
            hdr.id(), hdr.status(), hdr.date(), hdr.slot(), hdr.notes(),
            scans.size(), hdr.openedBy(), hdr.closedBy(),
            hdr.createdAt(), hdr.closedAt(), scans);
    }

    // ── Scan ──────────────────────────────────────────────────────────────────

    public ScanResult scan(UUID tenantId, UUID pickupId, UUID actorUserId, String trackingNumber) {
        record ShipmentRow(UUID id, UUID orderId, String leg, String internalState,
                           String orderNumber, BigDecimal cod) {}

        return TenantContext.runAs(tenantId, () ->
            tx.execute(s -> {
                // 1. Verify session is open.
                String sessionStatus = jdbc.query(
                    "SELECT session_status FROM pickups WHERE id = ? AND tenant_id = ?",
                    rs -> rs.next() ? rs.getString("session_status") : null,
                    pickupId, tenantId);
                if (sessionStatus == null)
                    throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Session not found");
                if (!"open".equals(sessionStatus))
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Session is not open (status: " + sessionStatus + ")");

                // 2. Look up shipment by tracking number.
                ShipmentRow shipment = jdbc.query(
                    """
                    SELECT s.id, s.order_id, s.shipment_leg::text AS leg,
                           s.internal_state::text AS internal_state,
                           o.number AS order_number, s.cod_amount
                    FROM shipments s
                    JOIN orders o ON o.id = s.order_id
                    WHERE s.tracking_number = ? AND s.tenant_id = ?
                    """,
                    rs -> rs.next() ? new ShipmentRow(
                        rs.getObject("id", UUID.class),
                        rs.getObject("order_id", UUID.class),
                        rs.getString("leg"),
                        rs.getString("internal_state"),
                        rs.getString("order_number"),
                        rs.getBigDecimal("cod_amount")) : null,
                    trackingNumber, tenantId);

                if (shipment == null) return new ScanResult(ScanOutcome.UNKNOWN_AWB, null);

                // 3. Must be forward leg.
                if (!"forward".equals(shipment.leg()))
                    return new ScanResult(ScanOutcome.NOT_FORWARD_LEG, null);

                // 4. Check for duplicate in THIS session.
                boolean inThisSession = Boolean.TRUE.equals(jdbc.queryForObject(
                    "SELECT EXISTS(SELECT 1 FROM pickup_shipments " +
                    "WHERE pickup_id = ? AND shipment_id = ?)",
                    Boolean.class, pickupId, shipment.id()));
                if (inThisSession) return new ScanResult(ScanOutcome.DUPLICATE, null);

                // 5. Check for conflict with another open session.
                boolean inOtherSession = Boolean.TRUE.equals(jdbc.queryForObject(
                    "SELECT EXISTS(" +
                    "  SELECT 1 FROM pickup_shipments ps " +
                    "  JOIN pickups p ON p.id = ps.pickup_id " +
                    "  WHERE ps.shipment_id = ? AND ps.tenant_id = ? " +
                    "    AND p.session_status = 'open' AND p.id != ?)",
                    Boolean.class, shipment.id(), tenantId, pickupId));
                if (inOtherSession) return new ScanResult(ScanOutcome.OTHER_SESSION, null);

                // 6. Verify pieces are in a shippable state (packed or awaiting_pickup).
                Integer packableCount = jdbc.queryForObject(
                    """
                    SELECT COUNT(*)::int FROM pieces p2
                    JOIN allocations a  ON a.piece_id      = p2.id
                    JOIN order_items oi ON oi.id            = a.order_item_id
                    WHERE oi.order_id = ? AND a.status IN ('active','packed')
                      AND p2.status::text IN ('packed','awaiting_pickup')
                    """,
                    Integer.class, shipment.orderId());
                Integer totalAllocated = jdbc.queryForObject(
                    """
                    SELECT COUNT(*)::int FROM allocations a
                    JOIN order_items oi ON oi.id = a.order_item_id
                    WHERE oi.order_id = ? AND a.status IN ('active','packed')
                    """,
                    Integer.class, shipment.orderId());
                if (totalAllocated == null || totalAllocated == 0 ||
                    packableCount == null || packableCount == 0)
                    return new ScanResult(ScanOutcome.NOT_PACKED, null);

                // 7. Insert scan record.
                jdbc.update(
                    "INSERT INTO pickup_shipments (pickup_id, shipment_id, tenant_id, " +
                    "scanned_at, scanned_by_user_id) VALUES (?, ?, ?, now(), ?)",
                    pickupId, shipment.id(), tenantId, actorUserId);

                String scannedAt = jdbc.queryForObject(
                    "SELECT scanned_at::text FROM pickup_shipments " +
                    "WHERE pickup_id = ? AND shipment_id = ?",
                    String.class, pickupId, shipment.id());
                String actorName = jdbc.query(
                    "SELECT name FROM users WHERE id = ?",
                    rs -> rs.next() ? rs.getString("name") : null, actorUserId);

                return new ScanResult(ScanOutcome.ACCEPTED,
                    new ScanEntry(shipment.id(), trackingNumber, shipment.orderNumber(),
                                  shipment.cod(), scannedAt, actorName));
            }));
    }

    // ── Remove scan ───────────────────────────────────────────────────────────

    public void removeScan(UUID tenantId, UUID pickupId, UUID shipmentId) {
        TenantContext.runAs(tenantId, () -> {
            tx.execute(s -> {
                String status = jdbc.query(
                    "SELECT session_status FROM pickups WHERE id = ? AND tenant_id = ?",
                    rs -> rs.next() ? rs.getString("session_status") : null,
                    pickupId, tenantId);
                if (status == null)
                    throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Session not found");
                if (!"open".equals(status))
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Cannot remove scan from a " + status + " session");
                jdbc.update(
                    "DELETE FROM pickup_shipments WHERE pickup_id = ? AND shipment_id = ?",
                    pickupId, shipmentId);
                return null;
            });
            return null;
        });
    }

    // ── Close ─────────────────────────────────────────────────────────────────

    public CloseResult closeSession(UUID tenantId, UUID pickupId, UUID actorUserId) {
        record ScannedShipment(UUID shipmentId, UUID orderId, String trackingNumber) {}

        return TenantContext.runAs(tenantId, () ->
            tx.execute(s -> {
                // 1. Load and validate session.
                String status = jdbc.query(
                    "SELECT session_status FROM pickups WHERE id = ? AND tenant_id = ?",
                    rs -> rs.next() ? rs.getString("session_status") : null,
                    pickupId, tenantId);
                if (status == null)
                    throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Session not found");
                if (!"open".equals(status))
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Session is already " + status);

                // 2. Load scanned shipments.
                List<ScannedShipment> scanned = jdbc.query(
                    """
                    SELECT ps.shipment_id, s.order_id, s.tracking_number
                    FROM pickup_shipments ps
                    JOIN shipments s ON s.id = ps.shipment_id
                    WHERE ps.pickup_id = ? AND ps.tenant_id = ?
                    """,
                    (rs, i) -> new ScannedShipment(
                        rs.getObject("shipment_id", UUID.class),
                        rs.getObject("order_id", UUID.class),
                        rs.getString("tracking_number")),
                    pickupId, tenantId);

                if (scanned.isEmpty())
                    throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "Cannot close an empty session — scan at least one shipment");

                List<String> pieceExceptions = new ArrayList<>();

                // 3. For each scanned shipment: set with_courier + custody lock; transition pieces.
                for (ScannedShipment sh : scanned) {
                    // Set shipment-level custody state unconditionally (C1).
                    jdbc.update("""
                        UPDATE shipments
                        SET internal_state = 'with_courier'::shipment_internal_state,
                            custody_locked_by_scan = true
                        WHERE id = ? AND tenant_id = ?
                        """, sh.shipmentId(), tenantId);

                    // Transition pieces: packed/awaiting_pickup → with_courier.
                    record PieceRow(String id, String status) {}
                    List<PieceRow> pieces = jdbc.query(
                        """
                        SELECT p.id, p.status::text AS status
                        FROM pieces p
                        JOIN allocations a  ON a.piece_id      = p.id
                        JOIN order_items oi ON oi.id            = a.order_item_id
                        WHERE oi.order_id = ? AND a.status IN ('active','packed')
                        """,
                        (rs, i) -> new PieceRow(rs.getString("id"), rs.getString("status")),
                        sh.orderId());

                    for (PieceRow pr : pieces) {
                        PieceStatus current = PieceStatus.fromDb(pr.status());
                        if (current == PieceStatus.WITH_COURIER) continue; // already there

                        if (current == PieceStatus.PACKED || current == PieceStatus.AWAITING_PICKUP) {
                            TransitionContext ctx = new TransitionContext(
                                sh.orderId(), sh.shipmentId(), null, sh.orderId(),
                                "{\"event\":\"pickup_session_close\",\"pickup_id\":\"" + pickupId + "\"}");
                            try {
                                ledger.transition(pr.id(), current, PieceStatus.WITH_COURIER,
                                    "handed_to_courier", actorUserId, ctx);
                            } catch (StateConflictException e) {
                                if (e.getActual() == PieceStatus.WITH_COURIER) continue;
                                raisePieceException(tenantId, pr.id(), actorUserId, pickupId,
                                    "Piece in unexpected state " + e.getActual() +
                                    " during pickup close — custody gap on shipment " +
                                    sh.trackingNumber());
                                pieceExceptions.add(pr.id());
                            } catch (IllegalTransitionException e) {
                                raisePieceException(tenantId, pr.id(), actorUserId, pickupId,
                                    "No legal transition from " + current +
                                    " to with_courier for piece in shipment " +
                                    sh.trackingNumber());
                                pieceExceptions.add(pr.id());
                            }
                        } else {
                            // Piece in unexpected status (available, reserved, delivered, etc.)
                            raisePieceException(tenantId, pr.id(), actorUserId, pickupId,
                                "Piece in status " + current +
                                " during pickup close (expected packed/awaiting_pickup) — " +
                                "custody gap on shipment " + sh.trackingNumber());
                            pieceExceptions.add(pr.id());
                        }
                    }
                }

                // 4. Mark session closed.
                jdbc.update("""
                    UPDATE pickups
                    SET session_status = 'closed',
                        no_of_packages = ?,
                        closed_by_user_id = ?,
                        closed_at = now()
                    WHERE id = ? AND tenant_id = ?
                    """, scanned.size(), actorUserId, pickupId, tenantId);

                log.info("Pickup session {} closed: {} shipments, {} piece exceptions",
                    pickupId, scanned.size(), pieceExceptions.size());

                return new CloseResult(scanned.size(), pieceExceptions);
            }));
    }

    // ── Manifest data (for PDF endpoint) ─────────────────────────────────────

    public record ManifestData(
            UUID pickupId, String scheduledDate, String timeSlot,
            String closedByName, String closedAt, int packageCount,
            List<ScanEntry> shipments) {}

    public ManifestData getManifestData(UUID tenantId, UUID pickupId) {
        record HeaderRow(String date, String slot, String closedBy, String closedAt, int count) {}

        HeaderRow hdr = TenantContext.runAs(tenantId, () ->
            tx.execute(s -> jdbc.query(
                """
                SELECT p.scheduled_date::text, p.scheduled_time_slot,
                       p.closed_at::text, p.no_of_packages,
                       u.name AS closed_by_name
                FROM pickups p
                LEFT JOIN users u ON u.id = p.closed_by_user_id
                WHERE p.id = ? AND p.tenant_id = ?
                """,
                rs -> {
                    if (!rs.next()) return null;
                    return new HeaderRow(
                        rs.getString("scheduled_date"),
                        rs.getString("scheduled_time_slot"),
                        rs.getString("closed_by_name"),
                        rs.getString("closed_at"),
                        rs.getInt("no_of_packages"));
                }, pickupId, tenantId)));

        if (hdr == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Session not found");

        SessionDetail detail = getSession(tenantId, pickupId);
        return new ManifestData(pickupId, hdr.date(), hdr.slot(),
            hdr.closedBy(), hdr.closedAt(), hdr.count(), detail.scans());
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private void raisePieceException(UUID tenantId, String pieceId, UUID resolvedBy,
                                      UUID pickupId, String note) {
        try {
            jdbc.update("""
                INSERT INTO exception_resolutions
                    (tenant_id, exception_type, subject_key, resolved_by, note)
                VALUES (?, 'pickup_piece_custody_gap', ?, ?, ?)
                """,
                tenantId,
                "piece:" + pieceId,
                resolvedBy,
                note + " [pickup:" + pickupId + "]");
        } catch (Exception ex) {
            log.warn("Failed to raise piece custody exception for piece {}: {}", pieceId, ex.getMessage());
        }
    }
}
