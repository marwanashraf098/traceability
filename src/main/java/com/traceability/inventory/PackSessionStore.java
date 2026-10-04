package com.traceability.inventory;

import com.traceability.tenancy.TenantContext;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

/**
 * Pick &amp; Pack S3 — the transactional steps of a waybill pack session. Each public method is
 * one transaction (called through the Spring proxy by {@link PackSessionService}); the
 * complete+link step lives in {@link PackCompleter} so it always runs in its OWN transaction,
 * after the scan that finished the order has committed.
 *
 * Every method: the session must exist in this tenant (RLS), belong to the caller (JWT user —
 * the station worker after a PIN switch) and be open. Piece moves go only through the existing
 * FulfillService scan / unscan paths (InventoryLedger stays the only piece-status writer).
 */
@Component
public class PackSessionStore {

    /** "Today" for the page tiles and the session's "left in batches" counter. */
    static final String TODAY_ZONE = "Africa/Cairo";

    static final Set<String> SET_ASIDE_REASONS =
        Set.of("piece_missing", "damaged_piece", "waybill_damaged", "other");

    public record Counters(int packed, int setAside, int rejected, int left) {}

    /** rawScan: what was scanned — shown for rejected rows, which usually have no order. */
    public record RecentRow(UUID orderId, String orderNumber, String customerName, String outcome,
                            String reason, String rawScan, Instant at) {}

    public record SessionView(UUID id, String mode, String status, Instant startedAt, String workerName,
                              Counters counters, List<RecentRow> recent, Map<String, Object> openOrder) {}

    /** OPEN → order card; anything else → the resolver's rejection. */
    public record WaybillOutcome(String result, Map<String, Object> order, String code, String subReason,
                                 String orderNumber, String who, Instant at, String state,
                                 String messageEn, String messageAr) {}

    public record Summary(int packedToday, UUID openSessionId) {}

    record Session(UUID id, UUID userId, String mode, String status, UUID currentOrderId, String currentWaybillScan) {}

    private final JdbcTemplate       jdbc;
    private final FulfillService     fulfill;
    private final WaybillResolver    resolver;

    public PackSessionStore(JdbcTemplate jdbc, FulfillService fulfill, WaybillResolver resolver) {
        this.jdbc         = jdbc;
        this.fulfill      = fulfill;
        this.resolver     = resolver;
    }

    // ── Start / resume ────────────────────────────────────────────────────────

    /**
     * Returns the caller's open session (resume after a reload), or starts one. A new session is
     * only started while the store is in waybill_scan mode; the mode is copied onto the session,
     * so an owner switching mid-shift doesn't change a session already open.
     */
    @Transactional
    public UUID start(UUID userId) {
        UUID tenantId = TenantContext.require();
        UUID open = openSessionOf(userId, tenantId);
        if (open != null) return open;
        String mode = PickPackModeController.currentMode(jdbc);
        if (!"waybill_scan".equals(mode)) throw PackSessionException.modeNotWaybill();
        try {
            return jdbc.queryForObject(
                "INSERT INTO pack_sessions (tenant_id, user_id, mode) VALUES (?, ?, ?) RETURNING id",
                UUID.class, tenantId, userId, mode);
        } catch (DuplicateKeyException race) {
            // A concurrent start for the same packer won (one open session per user) — resume it.
            return openSessionOf(userId, tenantId);
        }
    }

    @Transactional(readOnly = true)
    public Summary summary(UUID userId) {
        UUID tenantId = TenantContext.require();
        Integer packedToday = jdbc.queryForObject(
            "SELECT COUNT(DISTINCT pe.order_id) FROM piece_events pe " +
            "WHERE pe.tenant_id = ? AND pe.event_type = 'pack' " +
            "  AND (pe.created_at AT TIME ZONE '" + TODAY_ZONE + "')::date = (now() AT TIME ZONE '" + TODAY_ZONE + "')::date",
            Integer.class, tenantId);
        return new Summary(packedToday == null ? 0 : packedToday, openSessionOf(userId, tenantId));
    }

    @Transactional(readOnly = true)
    public SessionView view(UUID sessionId, UUID userId) {
        UUID tenantId = TenantContext.require();
        Session s = load(sessionId, userId, tenantId, false, false);

        Map<String, Object> counts = jdbc.queryForMap(
            "SELECT COUNT(*) FILTER (WHERE outcome = 'packed')    AS packed, " +
            "       COUNT(*) FILTER (WHERE outcome = 'set_aside') AS set_aside, " +
            "       COUNT(*) FILTER (WHERE outcome = 'rejected')  AS rejected " +
            "FROM pack_session_orders WHERE session_id = ? AND tenant_id = ?",
            sessionId, tenantId);
        Integer left = jdbc.queryForObject(
            "SELECT COUNT(DISTINCT bi.shipment_id) FROM pack_print_batch_items bi " +
            "JOIN pack_print_batches b ON b.id = bi.batch_id AND b.tenant_id = bi.tenant_id " +
            "JOIN orders o ON o.id = bi.order_id AND o.tenant_id = bi.tenant_id " +
            "WHERE bi.tenant_id = ? " +
            "  AND (b.created_at AT TIME ZONE '" + TODAY_ZONE + "')::date = (now() AT TIME ZONE '" + TODAY_ZONE + "')::date " +
            "  AND o.status IN ('new', 'ready_to_pick') AND o.cancel_requested_at IS NULL",
            Integer.class, tenantId);

        List<RecentRow> recent = jdbc.query(
            "SELECT so.order_id, o.number, o.customer_name, so.outcome, so.reason, so.raw_scan, so.created_at " +
            "FROM pack_session_orders so LEFT JOIN orders o ON o.id = so.order_id " +
            "WHERE so.session_id = ? AND so.tenant_id = ? " +
            // UUIDv4 is not time-ordered — order by created_at, never id (see CLAUDE.md invariant)
            "ORDER BY so.created_at DESC, so.id DESC LIMIT 30",
            (rs, i) -> new RecentRow(rs.getObject("order_id", UUID.class), rs.getString("number"),
                rs.getString("customer_name"), rs.getString("outcome"), rs.getString("reason"),
                rs.getString("raw_scan"), rs.getTimestamp("created_at").toInstant()),
            sessionId, tenantId);

        Map<String, Object> started = jdbc.queryForMap(
            "SELECT ps.started_at, u.name FROM pack_sessions ps LEFT JOIN users u ON u.id = ps.user_id WHERE ps.id = ?",
            sessionId);

        return new SessionView(s.id(), s.mode(), s.status(),
            ((java.sql.Timestamp) started.get("started_at")).toInstant(), (String) started.get("name"),
            new Counters(n(counts.get("packed")), n(counts.get("set_aside")), n(counts.get("rejected")),
                left == null ? 0 : left),
            recent,
            s.currentOrderId() != null ? orderCard(s.currentOrderId(), tenantId) : null);
    }

    // ── Waybill ───────────────────────────────────────────────────────────────

    /**
     * Resolve the waybill; OPEN takes the claim and makes it this session's open order, anything
     * else is recorded as a 'rejected' outcome with the raw scan and returned to the packer.
     */
    @Transactional
    public WaybillOutcome openWaybill(UUID sessionId, String rawScan, UUID userId) {
        UUID tenantId = TenantContext.require();
        Session s = load(sessionId, userId, tenantId, true, true);
        if (s.currentOrderId() != null) throw PackSessionException.orderOpen();

        WaybillResolver.Resolution r = resolver.resolve(rawScan, userId);
        if (r.code() == WaybillResolver.Code.OPEN) {
            if (PackClaim.take(jdbc, r.orderId(), tenantId, userId)) {
                jdbc.update(
                    "UPDATE pack_sessions SET current_order_id = ?, current_waybill_scan = ?, current_opened_at = now() " +
                    "WHERE id = ? AND tenant_id = ?",
                    r.orderId(), rawScan, sessionId, tenantId);
                return new WaybillOutcome("opened", orderCard(r.orderId(), tenantId),
                    null, null, r.orderNumber(), null, null, null, null, null);
            }
            // Lost the race between resolving and claiming — someone else opened it just now.
            PackClaim.Holder h = PackClaim.heldByOther(jdbc, r.orderId(), tenantId, userId);
            String who = h != null ? h.name() : null;
            String label = r.orderNumber() != null ? r.orderNumber() : "";
            r = new WaybillResolver.Resolution(WaybillResolver.Code.CLAIMED_BY_OTHER, r.orderId(), r.orderNumber(),
                r.shipmentId(), r.trackingNumber(), who, null, null, null,
                "Order " + label + " is being packed by " + (who != null ? who : "another packer") + " right now.",
                "الطلب " + label + " يجهّزه " + (who != null ? who : "موظف آخر") + " الآن.");
        }
        record(sessionId, tenantId, r.orderId(), rawScan, "rejected",
            r.code().name() + (r.detail() != null ? ":" + r.detail() : ""));
        return new WaybillOutcome("rejected", null, r.code().name(), r.detail(), r.orderNumber(), r.who(),
            r.at(), r.state(), r.messageEn(), r.messageAr());
    }

    // ── Pieces ────────────────────────────────────────────────────────────────

    /**
     * A piece scan on the session's open order, through the existing FulfillService.scan()
     * (validation unchanged). A waybill scanned here is refused — finish or set aside first.
     * Refreshes the claim (and re-takes it if it went stale while the packer was idle).
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public FulfillService.ScanResult scanPiece(UUID sessionId, UUID orderId, String code, UUID userId) {
        UUID tenantId = TenantContext.require();
        Session s = load(sessionId, userId, tenantId, true, true);
        requireOpenOrder(s, orderId);
        if (TrackingNumberNormalizer.normalize(code) != null) {
            return FulfillService.ScanResult.rejected("WAYBILL_WHILE_PACKING",
                "That's a waybill. Finish or set aside this order before scanning the next waybill.");
        }
        PackClaim.take(jdbc, orderId, tenantId, userId);
        return fulfill.scan(orderId, code, userId);
    }

    /** Undo one scanned piece — only while the order is still open in this session. */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void unscan(UUID sessionId, UUID orderId, String pieceId, UUID userId) {
        UUID tenantId = TenantContext.require();
        Session s = load(sessionId, userId, tenantId, true, true);
        requireOpenOrder(s, orderId);
        fulfill.unscan(orderId, pieceId, userId);
        PackClaim.take(jdbc, orderId, tenantId, userId);
    }

    /**
     * Set the open order aside: every active allocation goes back through the existing unscan
     * path (reserved → available, 'unscan' event, allocation released), the claim is released, the
     * outcome recorded with the reason, and the session waits for the next waybill.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public int setAside(UUID sessionId, UUID orderId, String reason, UUID userId) {
        if (reason == null || !SET_ASIDE_REASONS.contains(reason)) throw PackSessionException.badReason();
        UUID tenantId = TenantContext.require();
        Session s = load(sessionId, userId, tenantId, true, true);
        requireOpenOrder(s, orderId);

        List<String> pieces = jdbc.queryForList(
            "SELECT a.piece_id FROM allocations a JOIN order_items oi ON oi.id = a.order_item_id " +
            "WHERE oi.order_id = ? AND a.tenant_id = ? AND a.status = 'active' " +
            "ORDER BY a.allocated_at",
            String.class, orderId, tenantId);
        for (String pieceId : pieces) fulfill.unscan(orderId, pieceId, userId);

        PackClaim.release(jdbc, orderId, tenantId, userId);
        record(sessionId, tenantId, orderId, s.currentWaybillScan(), "set_aside", reason);
        clearOpenOrder(sessionId, tenantId);
        return pieces.size();
    }

    /**
     * End the session — refused while an order is open; releases every claim this packer holds.
     * Q1: the worker's paired phone is NOT unpaired — a pairing belongs to the tablet and worker
     * and lasts the shift (ScanPairingService).
     */
    @Transactional
    public void end(UUID sessionId, UUID userId) {
        UUID tenantId = TenantContext.require();
        Session s = load(sessionId, userId, tenantId, true, true);
        if (s.currentOrderId() != null) throw PackSessionException.orderOpen();
        jdbc.update("UPDATE orders SET locked_by = NULL, locked_at = NULL WHERE tenant_id = ? AND locked_by = ?",
            tenantId, userId);
        jdbc.update("UPDATE pack_sessions SET status = 'ended', ended_at = now() WHERE id = ? AND tenant_id = ?",
            sessionId, tenantId);
    }

    // ── Shared with PackCompleter ─────────────────────────────────────────────

    /**
     * Load the session, checking tenant (RLS), owner and (optionally) that it's open.
     * forUpdate serializes concurrent requests on one session.
     */
    Session load(UUID sessionId, UUID userId, UUID tenantId, boolean forUpdate, boolean requireOpen) {
        List<Session> rows = jdbc.query(
            "SELECT id, user_id, mode, status, current_order_id, current_waybill_scan FROM pack_sessions " +
            "WHERE id = ? AND tenant_id = ?" + (forUpdate ? " FOR UPDATE" : ""),
            (rs, i) -> new Session(rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class),
                rs.getString("mode"), rs.getString("status"), rs.getObject("current_order_id", UUID.class),
                rs.getString("current_waybill_scan")),
            sessionId, tenantId);
        if (rows.isEmpty()) throw PackSessionException.notFound();
        Session s = rows.get(0);
        if (!s.userId().equals(userId)) throw PackSessionException.notYours();
        if (requireOpen && !"open".equals(s.status())) throw PackSessionException.ended();
        return s;
    }

    static void requireOpenOrder(Session s, UUID orderId) {
        if (s.currentOrderId() == null || !s.currentOrderId().equals(orderId)) throw PackSessionException.orderNotOpen();
    }

    void record(UUID sessionId, UUID tenantId, UUID orderId, String rawScan, String outcome, String reason) {
        jdbc.update(
            "INSERT INTO pack_session_orders (tenant_id, session_id, order_id, raw_scan, outcome, reason) " +
            "VALUES (?, ?, ?, ?, ?, ?)",
            tenantId, sessionId, orderId, rawScan, outcome, reason);
    }

    void clearOpenOrder(UUID sessionId, UUID tenantId) {
        jdbc.update(
            "UPDATE pack_sessions SET current_order_id = NULL, current_waybill_scan = NULL, current_opened_at = NULL " +
            "WHERE id = ? AND tenant_id = ?",
            sessionId, tenantId);
    }

    /**
     * The order card: GET /fulfill/{id}'s shape (lines with imageUrl, tracking number, COD, …) plus
     * area (city/zone from the order, else Bosta's drop-off address), courier type, and the print
     * batch the waybill was last printed in.
     */
    Map<String, Object> orderCard(UUID orderId, UUID tenantId) {
        Map<String, Object> card = new LinkedHashMap<>(fulfill.getOrder(orderId));
        List<Map<String, Object>> extra = jdbc.queryForList(
            "SELECT NULLIF(concat_ws(', ', " +
            "         COALESCE(NULLIF(o.address ->> 'zone', ''), NULLIF(o.address ->> 'district', ''), " +
            "                  NULLIF(s.raw #>> '{dropOffAddress,zone,name}', '')), " +
            "         COALESCE(NULLIF(o.address ->> 'city', ''), NULLIF(s.raw #>> '{dropOffAddress,city,name}', ''))), '') AS area, " +
            "       s.raw -> 'type' ->> 'code' AS courier_type_code, " +
            "       (SELECT b.batch_no FROM pack_print_batch_items bi JOIN pack_print_batches b ON b.id = bi.batch_id " +
            "        WHERE bi.shipment_id = s.id AND bi.tenant_id = o.tenant_id " +
            // UUIDv4 is not time-ordered — order by created_at, never id (see CLAUDE.md invariant)
            "        ORDER BY b.created_at DESC, b.id DESC LIMIT 1) AS batch_no, " +
            "       (SELECT b.created_at FROM pack_print_batch_items bi JOIN pack_print_batches b ON b.id = bi.batch_id " +
            "        WHERE bi.shipment_id = s.id AND bi.tenant_id = o.tenant_id " +
            "        ORDER BY b.created_at DESC, b.id DESC LIMIT 1) AS batch_printed_at " +
            "FROM orders o LEFT JOIN shipments s ON s.id = ? " +
            "WHERE o.id = ? AND o.tenant_id = ?",
            card.get("shipment_id"), orderId, tenantId);
        if (!extra.isEmpty()) {
            Map<String, Object> e = extra.get(0);
            card.put("area", e.get("area"));
            card.put("courierType", courierType((String) e.get("courier_type_code")));
            card.put("batchNo", e.get("batch_no"));
            card.put("batchPrintedAt", e.get("batch_printed_at"));
        }
        return card;
    }

    /** Bosta delivery type → a short label key; unknown / missing → 'delivery'. */
    private static String courierType(String code) {
        if ("30".equals(code)) return "exchange";
        if ("20".equals(code)) return "return_to_origin";
        return "delivery";
    }

    private UUID openSessionOf(UUID userId, UUID tenantId) {
        List<UUID> ids = jdbc.queryForList(
            "SELECT id FROM pack_sessions WHERE tenant_id = ? AND user_id = ? AND status = 'open'",
            UUID.class, tenantId, userId);
        return ids.isEmpty() ? null : ids.get(0);
    }

    private static int n(Object o) { return o == null ? 0 : ((Number) o).intValue(); }
}
