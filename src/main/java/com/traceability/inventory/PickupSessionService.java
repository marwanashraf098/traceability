package com.traceability.inventory;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.traceability.courier.GenericLegTransitions;
import com.traceability.account.AuditService;
import com.traceability.tenancy.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
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
    private final ShopifyInventoryService shopifyInventory;
    private final AuditService     audit;
    /** Non-Bosta couriers G3: the generic leg's handover (source pickup_scan) — on this service's own connection. */
    private final GenericLegTransitions genericLegs;

    @Autowired
    public PickupSessionService(JdbcTemplate jdbc,
                                 PlatformTransactionManager txm,
                                 InventoryLedger ledger,
                                 ShopifyInventoryService shopifyInventory,
                                 AuditService audit) {
        this.jdbc   = jdbc;
        this.tx     = new TransactionTemplate(txm);
        this.ledger = ledger;
        this.shopifyInventory = shopifyInventory;
        this.audit  = audit;
        this.genericLegs = new GenericLegTransitions(jdbc, ledger);
    }

    /** Built by hand (ReviewTenantRlsTest, on an app_user connection): audit entries go through the same JdbcTemplate. */
    public PickupSessionService(JdbcTemplate jdbc,
                                 PlatformTransactionManager txm,
                                 InventoryLedger ledger,
                                 ShopifyInventoryService shopifyInventory) {
        this(jdbc, txm, ledger, shopifyInventory, new AuditService(jdbc, new ObjectMapper()));
    }


    // ── Public types ──────────────────────────────────────────────────────────

    /**
     * G3 adds WRONG_CARRIER (the session already holds another courier's parcels — {@link ScanResult#carrier}
     * names it) and AMBIGUOUS (the scan matches a Bosta and a generic shipment). Neither can happen for a tenant
     * with no generic shipments and the switch off.
     */
    public enum ScanOutcome { ACCEPTED, DUPLICATE, OTHER_SESSION, NOT_FORWARD_LEG, LEG_ENDED, NOT_PACKED, UNKNOWN_AWB,
                              WRONG_CARRIER, AMBIGUOUS }

    /**
     * A forward leg that must never be handed to a courier: cancelled or terminated in Bosta, or
     * deleted there (the status poll stamped {@code provider_not_found_at}). The ONE definition,
     * checked at scan (LEG_ENDED, nothing recorded) and again for every scanned shipment at close
     * (left out of the handover). Expects the shipments row aliased {@code s}.
     */
    static final String LEG_ENDED_SQL =
        "(s.internal_state IN ('cancelled'::shipment_internal_state, 'terminated'::shipment_internal_state) " +
        // Slice 4: suspected (one 400) AND confirmed deleted in Bosta are both refused.
        " OR " + com.traceability.integrations.bosta.BostaDeletionService.suspectedOrDeletedSql("s") + ")";

    /** Why {@link #LEG_ENDED_SQL} matched: cancelled | terminated | deleted_in_bosta (else NULL). */
    static final String LEG_ENDED_REASON_SQL =
        "CASE WHEN s.internal_state = 'cancelled'::shipment_internal_state THEN 'cancelled' " +
        "     WHEN s.internal_state = 'terminated'::shipment_internal_state THEN 'terminated' " +
        "     WHEN " + com.traceability.integrations.bosta.BostaDeletionService.suspectedOrDeletedSql("s") +
        "     THEN 'deleted_in_bosta' END";

    /** audit_log action for a scanned shipment left out of a pickup handover at close. */
    public static final String AUDIT_LEG_LEFT_OUT = "pickup_leg_left_out";

    public record SessionSummary(
            UUID id, String sessionStatus, LocalDate scheduledDate,
            String scheduledTimeSlot, int scannedCount,
            String openedByName, String createdAt) {}

    /** {@code carrierName}: G3, a generic shipment's carrier only — absent from the JSON for Bosta parcels. */
    public record ScanEntry(
            UUID shipmentId, String trackingNumber, String orderNumber,
            BigDecimal codAmount, String scannedAt, String scannedByName,
            @JsonInclude(JsonInclude.Include.NON_NULL) String carrierName) {
        public ScanEntry(UUID shipmentId, String trackingNumber, String orderNumber,
                         BigDecimal codAmount, String scannedAt, String scannedByName) {
            this(shipmentId, trackingNumber, orderNumber, codAmount, scannedAt, scannedByName, null);
        }
    }

    /** {@code carrier}: G3, the generic carrier of a session holding other couriers' parcels — absent for Bosta sessions. */
    public record SessionDetail(
            UUID id, String sessionStatus, LocalDate scheduledDate,
            String scheduledTimeSlot, String notes, int scannedCount,
            String openedByName, String closedByName,
            String createdAt, String closedAt,
            List<ScanEntry> scans,
            @JsonInclude(JsonInclude.Include.NON_NULL) String carrier) {}

    /** {@code carrier}: G3, on WRONG_CARRIER — the courier this session is for ("Bosta" or the generic carrier name). */
    public record ScanResult(ScanOutcome outcome, ScanEntry entry,
                             @JsonInclude(JsonInclude.Include.NON_NULL) String carrier) {
        public ScanResult(ScanOutcome outcome, ScanEntry entry) { this(outcome, entry, null); }
    }

    /** A scanned shipment whose leg ended between scan and close: not handed over, its pieces untouched. */
    public record LeftOut(UUID shipmentId, String trackingNumber, String orderNumber, String reason) {}

    public record CloseResult(int shipmentsClosed, List<String> pieceExceptions, List<LeftOut> leftOut) {}

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
                       ps.scanned_at::text, u.name AS scanned_by_name,
                       CASE WHEN s.provider = 'generic' THEN COALESCE(btrim(s.carrier_name), '') END AS generic_carrier
                FROM pickup_shipments ps
                JOIN shipments s ON s.id = ps.shipment_id
                JOIN merchant_orders    o ON o.id = s.order_id
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
                    rs.getString("scanned_by_name"),
                    rs.getString("generic_carrier")),
                pickupId, tenantId)));

        // G3: one courier per session — a session of generic parcels names their carrier (manifest, header).
        // The session's courier is its FIRST scan's (scans are listed newest first).
        String carrier = null;
        for (ScanEntry e : scans) if (e.carrierName() != null) carrier = e.carrierName();
        return new SessionDetail(
            hdr.id(), hdr.status(), hdr.date(), hdr.slot(), hdr.notes(),
            scans.size(), hdr.openedBy(), hdr.closedBy(),
            hdr.createdAt(), hdr.closedAt(), scans, carrier);
    }

    // ── Scan ──────────────────────────────────────────────────────────────────

    public ScanResult scan(UUID tenantId, UUID pickupId, UUID actorUserId, String trackingNumber) {
        record ShipmentRow(UUID id, UUID orderId, String leg, String internalState,
                           String orderNumber, BigDecimal cod, boolean ended) {}

        // Normalize before matching — the physical Bosta label's top barcode carries a hub
        // routing prefix (e.g. D-07-2944282510) that shipments.tracking_number never stores.
        // Canonical everywhere from here on: the normalized value is what gets matched and
        // echoed back — never the raw scan (mirrors ReturnSessionService.createSession).
        String normalizedTracking = TrackingNumberNormalizer.normalize(trackingNumber);
        // G3: the shared generic resolver (G2). OFF — no key lookup at all — for a tenant with the switch off and
        // no live generic shipment, so everything below is exactly the Bosta path for it.
        WaybillScanKeys.Generic generic = TenantContext.runAs(tenantId, () ->
            tx.execute(s -> WaybillScanKeys.lookup(jdbc, tenantId, trackingNumber)));
        boolean genericKeys = generic.kind() != WaybillScanKeys.Kind.OFF;
        if (normalizedTracking == null && !generic.matched()
                && (!genericKeys || generic.kind() == WaybillScanKeys.Kind.PIECE || generic.kind() == WaybillScanKeys.Kind.LAYOUT)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Unreadable waybill scan: " + trackingNumber);
        }

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

                // G3: one courier per session — serialise scans of a switched-on tenant on the session row.
                if (genericKeys) {
                    jdbc.queryForList("SELECT id FROM pickups WHERE id = ? AND tenant_id = ? FOR UPDATE", pickupId, tenantId);
                }

                // 2. Look up shipment by tracking number.
                ShipmentRow shipment = normalizedTracking == null ? null : jdbc.query(
                    """
                    SELECT s.id, s.order_id, s.shipment_leg::text AS leg,
                           s.internal_state::text AS internal_state,
                           o.number AS order_number, s.cod_amount,
                           """ + LEG_ENDED_SQL + """
                           AS ended
                    FROM shipments s
                    JOIN merchant_orders o ON o.id = s.order_id
                    WHERE s.tracking_number = ? AND s.tenant_id = ? AND s.provider = 'bosta'
                    """,
                    rs -> rs.next() ? new ShipmentRow(
                        rs.getObject("id", UUID.class),
                        rs.getObject("order_id", UUID.class),
                        rs.getString("leg"),
                        rs.getString("internal_state"),
                        rs.getString("order_number"),
                        rs.getBigDecimal("cod_amount"),
                        rs.getBoolean("ended")) : null,
                    normalizedTracking, tenantId);

                // G3: Bosta first; the generic key only when the Bosta key found nothing — both → never guess.
                String echoTracking = normalizedTracking;
                if (generic.matched()) {
                    if (shipment != null) return new ScanResult(ScanOutcome.AMBIGUOUS, null);
                    shipment = jdbc.query(
                        "SELECT s.id, s.order_id, s.shipment_leg::text AS leg, s.internal_state::text AS internal_state, " +
                        "       o.number AS order_number, s.cod_amount, " + LEG_ENDED_SQL + " AS ended " +
                        "FROM shipments s JOIN merchant_orders o ON o.id = s.order_id " +
                        "WHERE s.id = ? AND s.tenant_id = ? AND s.provider = 'generic'",
                        rs -> rs.next() ? new ShipmentRow(
                            rs.getObject("id", UUID.class),
                            rs.getObject("order_id", UUID.class),
                            rs.getString("leg"),
                            rs.getString("internal_state"),
                            rs.getString("order_number"),
                            rs.getBigDecimal("cod_amount"),
                            rs.getBoolean("ended")) : null,
                        generic.match().shipmentId(), tenantId);
                    echoTracking = generic.match().trackingNumber();
                }

                if (shipment == null) return new ScanResult(ScanOutcome.UNKNOWN_AWB, null);

                // 3. Must be forward leg.
                if (!"forward".equals(shipment.leg()))
                    return new ScanResult(ScanOutcome.NOT_FORWARD_LEG, null);

                // 3b. Cancelled, terminated or deleted in Bosta — never hand it to the courier.
                if (shipment.ended()) return new ScanResult(ScanOutcome.LEG_ENDED, null);

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

                // 5b. G3 — one courier per session: the first scan sets it (Bosta, or the generic carrier name);
                //     a parcel for another courier is refused, naming the session's. Switched-on tenants only.
                if (genericKeys) {
                    String sessionCarrier = otherCarrier(pickupId, shipment.id());
                    if (sessionCarrier != null) return new ScanResult(ScanOutcome.WRONG_CARRIER, null, sessionCarrier);
                }

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

                String genericCarrier = generic.matched()
                    ? (generic.match().carrierName() == null ? "" : generic.match().carrierName().trim()) : null;
                return new ScanResult(ScanOutcome.ACCEPTED,
                    new ScanEntry(shipment.id(), echoTracking, shipment.orderNumber(),
                                  shipment.cod(), scannedAt, actorName, genericCarrier));
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
        record ScannedShipment(UUID shipmentId, UUID orderId, String trackingNumber,
                               String orderNumber, String endedReason, boolean generic) {
            boolean ended() { return endedReason != null; }
        }

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
                    SELECT ps.shipment_id, s.order_id, s.tracking_number, o.number AS order_number,
                           (s.provider = 'generic') AS generic,
                           """ + LEG_ENDED_REASON_SQL + """
                           AS ended_reason
                    FROM pickup_shipments ps
                    JOIN shipments s ON s.id = ps.shipment_id
                    LEFT JOIN merchant_orders o ON o.id = s.order_id
                    WHERE ps.pickup_id = ? AND ps.tenant_id = ?
                    """,
                    (rs, i) -> new ScannedShipment(
                        rs.getObject("shipment_id", UUID.class),
                        rs.getObject("order_id", UUID.class),
                        rs.getString("tracking_number"),
                        rs.getString("order_number"),
                        rs.getString("ended_reason"),
                        rs.getBoolean("generic")),
                    pickupId, tenantId);

                if (scanned.isEmpty())
                    throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "Cannot close an empty session — scan at least one shipment");

                List<String> pieceExceptions = new ArrayList<>();

                // 2b. A leg that ended since its scan (cancelled / terminated / deleted in Bosta) is
                // left out of the handover: no with_courier, no history row, its pieces stay packed.
                // Its scan row is removed so the session and its manifest list only what the courier
                // took — after an audit_log entry (same transaction) records that it was scanned and
                // left out, by whom; the close summary names it.
                List<LeftOut> leftOut = new ArrayList<>();
                for (ScannedShipment sh : scanned) {
                    if (!sh.ended()) continue;
                    Map<String, Object> meta = new LinkedHashMap<>();
                    meta.put("pickup_id", pickupId.toString());
                    meta.put("shipment_id", sh.shipmentId().toString());
                    meta.put("tracking_number", sh.trackingNumber());
                    meta.put("order_number", sh.orderNumber());
                    meta.put("reason", sh.endedReason());
                    audit.record(actorUserId, AUDIT_LEG_LEFT_OUT, "pickup", pickupId.toString(), meta);
                    jdbc.update("DELETE FROM pickup_shipments WHERE pickup_id = ? AND shipment_id = ?",
                        pickupId, sh.shipmentId());
                    leftOut.add(new LeftOut(sh.shipmentId(), sh.trackingNumber(), sh.orderNumber(), sh.endedReason()));
                }
                List<ScannedShipment> handed = scanned.stream().filter(sh -> !sh.ended()).toList();

                // 3. For each scanned shipment: set with_courier + custody lock; transition pieces.
                for (ScannedShipment sh : handed) {
                    // Set shipment-level custody state unconditionally (C1).
                    jdbc.update("""
                        UPDATE shipments
                        SET internal_state = 'with_courier'::shipment_internal_state,
                            custody_locked_by_scan = true
                        WHERE id = ? AND tenant_id = ?
                        """, sh.shipmentId(), tenantId);

                    // Journal the handover into shipment_status_history so a "physically
                    // with courier" read (FulfillService.hasEverShippedPastCreated()) is
                    // true from this moment, even before any Bosta webhook confirms it —
                    // closes the blind window between physical handover and Bosta catching
                    // up. webhook_event_id is deliberately NULL: there is no webhook event
                    // for this manual action, and NULL is what the unique index on
                    // webhook_event_id (V40, WHERE webhook_event_id IS NOT NULL) exempts,
                    // so a shipment re-scanned into a later pickup session after a failed
                    // handover can insert another row here without conflict. Do not
                    // fabricate a provider_state or webhook_event_id.
                    if (sh.generic()) {
                        // G3: a generic leg's handover — the same history row plus its source (pickup_scan)
                        // and handed_over_at, through the one writer of generic leg states.
                        genericLegs.recordHandover(tenantId, sh.shipmentId());
                    } else {
                        jdbc.update("""
                            INSERT INTO shipment_status_history
                                (tenant_id, shipment_id, internal_state, provider_state,
                                 exception_code, exception_reason, occurred_at, webhook_event_id)
                            VALUES (?, ?, 'with_courier', NULL, NULL, NULL, now(), NULL)
                            """, tenantId, sh.shipmentId());
                    }

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
                                // Step 5a: the piece really left Traced custody — the Shopify
                                // decrement for an internal exchange replacement runs once this
                                // close commits (a no-op for any other order; once per piece).
                                if (ShopifyInventoryService.leavesCustody(current, PieceStatus.WITH_COURIER)) {
                                    String pieceId = pr.id();
                                    ShopifyInventoryService.afterCommit(() ->
                                        shopifyInventory.onExchangeReplacementDispatched(tenantId, pieceId));
                                }
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
                    """, handed.size(), actorUserId, pickupId, tenantId);

                log.info("Pickup session {} closed: {} shipments, {} piece exceptions, {} left out (leg ended: {})",
                    pickupId, handed.size(), pieceExceptions.size(), leftOut.size(),
                    leftOut.stream().map(LeftOut::trackingNumber).toList());

                return new CloseResult(handed.size(), pieceExceptions, leftOut);
            }));
    }

    // ── Manifest data (for PDF endpoint) ─────────────────────────────────────

    /** {@code carrier}: G3, the generic carrier of a session of other couriers' parcels — absent for Bosta. */
    public record ManifestData(
            UUID pickupId, String scheduledDate, String timeSlot,
            String closedByName, String closedAt, int packageCount,
            List<ScanEntry> shipments,
            @JsonInclude(JsonInclude.Include.NON_NULL) String carrier) {}

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
            hdr.closedBy(), hdr.closedAt(), hdr.count(), detail.scans(), detail.carrier());
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * G3 — the session's courier, when {@code shipmentId} isn't for it: the first other scan of the session
     * decides it (all scans share one courier). Returns "Bosta", the generic carrier name ("" when the
     * shipment names none), or null when the parcel fits (or the session is still empty).
     */
    private String otherCarrier(UUID pickupId, UUID shipmentId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT s.provider::text AS provider, s.carrier_name, " +
            "       (s.provider <> n.provider OR (s.provider = 'generic' " +
            "         AND lower(btrim(coalesce(s.carrier_name, ''))) <> lower(btrim(coalesce(n.carrier_name, ''))))) AS differs " +
            "FROM pickup_shipments ps JOIN shipments s ON s.id = ps.shipment_id " +
            "CROSS JOIN (SELECT provider, carrier_name FROM shipments WHERE id = ?) n " +
            "WHERE ps.pickup_id = ? ORDER BY ps.scanned_at, ps.shipment_id LIMIT 1",
            shipmentId, pickupId);
        if (rows.isEmpty() || !Boolean.TRUE.equals(rows.get(0).get("differs"))) return null;
        if ("bosta".equals(rows.get(0).get("provider"))) return "Bosta";
        Object name = rows.get(0).get("carrier_name");
        return name == null ? "" : name.toString().trim();
    }

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
