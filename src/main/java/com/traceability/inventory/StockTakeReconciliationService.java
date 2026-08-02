package com.traceability.inventory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.traceability.tenancy.TenantContext;
import org.jobrunr.scheduling.JobScheduler;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * FR-21 Step 4: disposition report (reconciliation) + resolutions.
 *
 * Resolution actions in this build: found, lost (free stock, gated on complete_count),
 * lost (committed -> routes through the existing FR-13.2 PieceCommittedException guard),
 * and mark_damaged (available -> damaged via PieceAdjustService, the existing move path).
 *
 * damaged -> available ("mark_available") is deliberately NOT offered here — approved
 * scope decision. A piece that's damaged in the snapshot but scanned good shows up as a
 * read-only flagged row in on_shelf_uncounted; reinstating a damaged piece stays a
 * PieceAdjustService/InventoryLedger question, out of band from a stock take. No new
 * ALLOWED edge or PieceAdjustService change beyond damaged:lost.
 */
@Service
public class StockTakeReconciliationService {

    private static final Set<String> COMMITTED_STATUSES = Set.of("reserved", "packed", "awaiting_pickup");
    private static final Set<String> GONE_STATUSES = Set.of("with_courier", "delivered", "lost", "destroyed");

    private static final List<String> BUCKET_KEYS = List.of(
        "on_shelf_counted", "on_shelf_uncounted", "committed_to_orders",
        "with_courier_or_delivered", "returns_bench", "damaged",
        "previously_written_off", "unexpected_finds");

    private static final String EXPECTED_QUERY =
        "SELECT se.piece_id, se.variant_id, v.title AS variant_title, v.sku, pr.title AS product_title, " +
        "       se.status_at_open, p.status::text AS live_status, " +
        "       (ts.id IS NOT NULL) AS scanned, ts.scanned_condition, " +
        "       o.id AS order_id, o.number AS order_number, " +
        "       s.id AS shipment_id, s.tracking_number " +
        "FROM stock_take_expected se " +
        "JOIN pieces p ON p.id = se.piece_id AND p.tenant_id = se.tenant_id " +
        "JOIN variants v ON v.id = se.variant_id " +
        "JOIN products pr ON pr.id = v.product_id " +
        "LEFT JOIN stock_take_scans ts ON ts.session_id = se.session_id AND ts.piece_id = se.piece_id " +
        "LEFT JOIN orders o ON o.id = p.current_order_id AND o.tenant_id = p.tenant_id " +
        "LEFT JOIN shipments s ON s.order_id = o.id AND s.shipment_leg = 'forward' AND s.tenant_id = p.tenant_id " +
        "WHERE se.session_id = ? AND se.tenant_id = ? " +
        "ORDER BY pr.title, v.title, p.id";

    private static final String UNEXPECTED_QUERY =
        "SELECT ts.piece_id, p.variant_id, v.title AS variant_title, v.sku, pr.title AS product_title, " +
        "       p.status::text AS live_status " +
        "FROM stock_take_scans ts " +
        "JOIN pieces p ON p.id = ts.piece_id AND p.tenant_id = ts.tenant_id " +
        "JOIN variants v ON v.id = p.variant_id " +
        "JOIN products pr ON pr.id = v.product_id " +
        "WHERE ts.session_id = ? AND ts.tenant_id = ? AND ts.piece_id IS NOT NULL " +
        "  AND NOT EXISTS ( " +
        "      SELECT 1 FROM stock_take_expected se " +
        "      WHERE se.session_id = ts.session_id AND se.piece_id = ts.piece_id) " +
        "ORDER BY pr.title, v.title, p.id";

    private final JdbcTemplate        jdbc;
    private final StockTakeService    stockTake;
    private final InventoryLedger     ledger;
    private final PieceAdjustService  pieceAdjustService;
    private final com.traceability.account.AuditService auditService;
    private final ObjectMapper        mapper;
    private final JobScheduler        jobScheduler;
    private final StockTakeShopifyPushJob pushJob;

    public StockTakeReconciliationService(JdbcTemplate jdbc, StockTakeService stockTake,
                                          InventoryLedger ledger, PieceAdjustService pieceAdjustService,
                                          com.traceability.account.AuditService auditService,
                                          ObjectMapper mapper, JobScheduler jobScheduler,
                                          StockTakeShopifyPushJob pushJob) {
        this.jdbc               = jdbc;
        this.stockTake          = stockTake;
        this.ledger             = ledger;
        this.pieceAdjustService = pieceAdjustService;
        this.auditService       = auditService;
        this.mapper             = mapper;
        this.jobScheduler       = jobScheduler;
        this.pushJob            = pushJob;
    }

    // ── Reconciliation (disposition report) ──────────────────────────────────

    @Transactional(readOnly = true)
    public Map<String, Object> reconciliation(UUID sessionId) {
        UUID tenantId = TenantContext.require();
        StockTakeService.SessionRow session = stockTake.requireSession(sessionId, tenantId);

        List<Map<String, Object>> expectedRows = jdbc.queryForList(EXPECTED_QUERY, sessionId, tenantId);
        List<Map<String, Object>> unexpectedRows = jdbc.queryForList(UNEXPECTED_QUERY, sessionId, tenantId);

        Map<String, List<Map<String, Object>>> buckets = new LinkedHashMap<>();
        for (String key : BUCKET_KEYS) buckets.put(key, new ArrayList<>());

        Map<UUID, Map<String, Object>> rollupByVariant = new LinkedHashMap<>();
        int totalExpectedOnShelf = 0;
        int totalCounted = 0;

        for (Map<String, Object> row : expectedRows) {
            String liveStatus = (String) row.get("live_status");
            String statusAtOpen = (String) row.get("status_at_open");
            boolean scanned = Boolean.TRUE.equals(row.get("scanned"));
            String scannedCondition = (String) row.get("scanned_condition");
            UUID variantId = (UUID) row.get("variant_id");

            Map<String, Object> rollup = rollupByVariant.computeIfAbsent(variantId, id -> newRollupRow(row));
            bump(rollup, "totalKnown");

            boolean onShelfAtOpen = "available".equals(statusAtOpen) || "damaged".equals(statusAtOpen);
            if (onShelfAtOpen) {
                bump(rollup, "expectedOnShelf");
                totalExpectedOnShelf++;
                if (scanned) {
                    bump(rollup, "counted");
                    totalCounted++;
                }
            }
            if (COMMITTED_STATUSES.contains(liveStatus)) bump(rollup, "committed");
            if (GONE_STATUSES.contains(liveStatus))      bump(rollup, "gone");
            if ("damaged".equals(liveStatus))            bump(rollup, "damagedCount");

            String bucket = bucketFor(liveStatus, scanned, scannedCondition);
            Map<String, Object> summary = pieceSummary(row);
            if ("on_shelf_uncounted".equals(bucket) && scanned) {
                // scanned but condition disagreed with status_at_open — read-only flag,
                // no one-tap resolution (see class doc: mark_available is out of scope).
                summary.put("flag", "condition_mismatch");
            }
            buckets.get(bucket).add(summary);
        }

        for (Map<String, Object> row : unexpectedRows) {
            String liveStatus = (String) row.get("live_status");
            Map<String, Object> summary = pieceSummary(row);
            summary.put("reason", "lost".equals(liveStatus) ? "resurfaced_from_lost" : "out_of_scope");
            buckets.get("unexpected_finds").add(summary);
        }

        List<Map<String, Object>> rollupList = new ArrayList<>();
        for (Map<String, Object> r : rollupByVariant.values()) {
            int expected = (Integer) r.get("expectedOnShelf");
            int counted  = (Integer) r.get("counted");
            r.put("variance", expected - counted);
            rollupList.add(r);
        }

        double coveragePercent = totalExpectedOnShelf == 0
            ? 100.0
            : Math.round(10000.0 * totalCounted / totalExpectedOnShelf) / 100.0;

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sessionId", sessionId);
        out.put("status", session.status());
        out.put("completeCount", session.completeCount());
        out.put("coveragePercent", coveragePercent);
        out.put("buckets", buckets);
        out.put("variantRollup", rollupList);
        return out;
    }

    /**
     * Live-status-driven bucketing (not status_at_open) — a piece that drifted since the
     * snapshot must show up where it actually is NOW, not where it was at open. "Match" and
     * "Damaged" both require a scan that agrees with the live status; anything unscanned or
     * disagreeing on an on-shelf piece lands in on_shelf_uncounted (the single review set).
     */
    private String bucketFor(String liveStatus, boolean scanned, String scannedCondition) {
        if (COMMITTED_STATUSES.contains(liveStatus)) return "committed_to_orders";
        if ("with_courier".equals(liveStatus) || "delivered".equals(liveStatus)) return "with_courier_or_delivered";
        if ("return_pending_inspection".equals(liveStatus)) return "returns_bench";
        if ("lost".equals(liveStatus) || "destroyed".equals(liveStatus)) return "previously_written_off";
        if ("damaged".equals(liveStatus)) {
            return scanned && "damaged".equals(scannedCondition) ? "damaged" : "on_shelf_uncounted";
        }
        if ("available".equals(liveStatus)) {
            return scanned && "good".equals(scannedCondition) ? "on_shelf_counted" : "on_shelf_uncounted";
        }
        return "on_shelf_uncounted";
    }

    private Map<String, Object> pieceSummary(Map<String, Object> row) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("pieceId", row.get("piece_id"));
        m.put("variantId", row.get("variant_id"));
        m.put("variantTitle", row.get("variant_title"));
        m.put("sku", row.get("sku"));
        m.put("productTitle", row.get("product_title"));
        m.put("liveStatus", row.get("live_status"));
        if (row.get("order_id") != null) {
            m.put("orderId", row.get("order_id"));
            m.put("orderNumber", row.get("order_number"));
        }
        if (row.get("shipment_id") != null) {
            m.put("shipmentId", row.get("shipment_id"));
            m.put("trackingNumber", row.get("tracking_number"));
        }
        return m;
    }

    private Map<String, Object> newRollupRow(Map<String, Object> row) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("variantId", row.get("variant_id"));
        m.put("variantTitle", row.get("variant_title"));
        m.put("sku", row.get("sku"));
        m.put("totalKnown", 0);
        m.put("expectedOnShelf", 0);
        m.put("counted", 0);
        m.put("committed", 0);
        m.put("gone", 0);
        m.put("damagedCount", 0);
        return m;
    }

    private void bump(Map<String, Object> rollup, String key) {
        rollup.put(key, (Integer) rollup.get(key) + 1);
    }

    // ── Resolutions ───────────────────────────────────────────────────────────

    public record ResolveItem(String pieceId, String action) {}

    /**
     * Deliberately NOT @Transactional at the batch level: each item's resolution (found /
     * lost / mark_damaged) already commits atomically on its own via ledger.transition() or
     * PieceAdjustService's own @Transactional boundary. If this method were wrapped in one
     * shared transaction, a StateConflictException from the drift guard on item N — even
     * caught right here — would still mark the whole physical transaction rollback-only
     * (REQUIRED propagation joins ledger.transition()'s transaction into this one), dooming
     * every OTHER item in the same batch to UnexpectedRollbackException at commit time. Each
     * resolution must stand alone so one drift-guard skip can't take the rest of the batch
     * down with it.
     */
    public List<Map<String, Object>> resolve(UUID sessionId, List<ResolveItem> items, UUID actorUserId) {
        UUID tenantId = TenantContext.require();
        StockTakeService.SessionRow session = stockTake.requireOpenSession(sessionId, tenantId);

        if (items == null || items.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "at least one resolution item is required");
        }

        List<Map<String, Object>> results = new ArrayList<>();
        for (ResolveItem item : items) {
            results.add(resolveOne(session, tenantId, item.pieceId(), item.action(), actorUserId));
        }
        return results;
    }

    private Map<String, Object> resolveOne(StockTakeService.SessionRow session, UUID tenantId,
                                           String pieceId, String action, UUID actorUserId) {
        return switch (action) {
            case "found"        -> resolveFound(session, tenantId, pieceId, actorUserId);
            case "lost"         -> resolveLost(session, tenantId, pieceId, actorUserId);
            case "mark_damaged" -> resolveMarkDamaged(session, pieceId, actorUserId);
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "action must be one of: found, lost, mark_damaged");
        };
    }

    /**
     * "Found it": the piece is still physically on shelf, just missed by the scanner.
     * Appends a manager_found scan row — NO piece_event, nothing about the piece changed.
     * Moves the piece from on_shelf_uncounted to on_shelf_counted / damaged on the next
     * reconciliation read.
     */
    private Map<String, Object> resolveFound(StockTakeService.SessionRow session, UUID tenantId,
                                             String pieceId, UUID actorUserId) {
        fetchStatusAtOpen(session.id(), tenantId, pieceId); // 404 if not in this session's snapshot
        String liveStatus = fetchLiveStatus(pieceId, tenantId);

        if (!"available".equals(liveStatus) && !"damaged".equals(liveStatus)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Piece is not currently on shelf (status=" + liveStatus + ") — cannot mark found");
        }

        String condition = "damaged".equals(liveStatus) ? "damaged" : "good";
        int rows = jdbc.update(
            "INSERT INTO stock_take_scans " +
            "(id, tenant_id, session_id, piece_id, scanned_condition, source, actor_user_id) " +
            "VALUES (gen_random_uuid(), ?, ?, ?, ?, 'manager_found', ?) " +
            "ON CONFLICT (session_id, piece_id) WHERE piece_id IS NOT NULL DO NOTHING",
            tenantId, session.id(), pieceId, condition, actorUserId);

        if (rows == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Piece was already scanned in this session");
        }

        return Map.of("pieceId", pieceId, "action", "found", "result", "match");
    }

    /**
     * Write-off, routed by status_at_open (the snapshot's own classification), not live
     * status:
     *  - committed at snapshot time (reserved/packed/awaiting_pickup) -> reuse the exact
     *    PieceCommittedException PieceAdjustService.adjustPiece() throws for the same
     *    reason, pointing the caller at releaseForAdjust (FR-13.2). No one-tap write-off.
     *  - free stock at snapshot time (available/damaged) -> gated on complete_count, then
     *    ledger.transition(expectedStatus = status_at_open, -> LOST). The drift guard is
     *    exactly this optimistic UPDATE: if the piece moved between snapshot and now (a
     *    real pick, say), the WHERE clause won't match and StateConflictException fires —
     *    caught here and surfaced as a skip, never forced through.
     *  - anything else (with_courier/delivered/returns bench/already lost or destroyed) ->
     *    not eligible for a stock-take write-off at all.
     */
    private Map<String, Object> resolveLost(StockTakeService.SessionRow session, UUID tenantId,
                                            String pieceId, UUID actorUserId) {
        String statusAtOpen = fetchStatusAtOpen(session.id(), tenantId, pieceId);

        if (COMMITTED_STATUSES.contains(statusAtOpen)) {
            Map<String, Object> order = fetchCommittedOrder(pieceId, tenantId);
            if (order != null) {
                throw new PieceCommittedException(
                    (UUID) order.get("orderId"), (String) order.get("orderNumber"));
            }
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Piece was committed to an order at snapshot time (status_at_open=" + statusAtOpen +
                ") — release it from the order first (release-for-adjust) before writing it off");
        }

        if (!"available".equals(statusAtOpen) && !"damaged".equals(statusAtOpen)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                "Piece was not free stock at snapshot time (status_at_open=" + statusAtOpen +
                ") — cannot write off via stock take");
        }

        if (!session.completeCount()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Write-offs are locked until the count is attested complete");
        }

        PieceStatus expected = PieceStatus.fromDb(statusAtOpen);
        String metadata = "{\"session_id\":\"" + session.id() + "\",\"reason\":\"stock_take_missing\"}";
        try {
            ledger.transition(pieceId, expected, PieceStatus.LOST, "adjusted", actorUserId,
                new TransitionContext(null, null, null, null, metadata));
        } catch (StateConflictException e) {
            return Map.of("pieceId", pieceId, "action", "lost", "result", "skipped_changed_during_count");
        }

        auditService.record(actorUserId, "stock_take_write_off", "piece", pieceId,
            Map.of("sessionId", session.id().toString(), "statusAtOpen", statusAtOpen));
        return Map.of("pieceId", pieceId, "action", "lost", "result", "written_off");
    }

    /** Condition correction: available -> damaged, via the existing PieceAdjustService path
     *  (which also carries the FR-17 v2 Shopify damage-move trigger). */
    private Map<String, Object> resolveMarkDamaged(StockTakeService.SessionRow session,
                                                    String pieceId, UUID actorUserId) {
        pieceAdjustService.adjustPiece(pieceId, "damaged", "damaged_in_storage",
            "Condition correction during stock take " + session.id(), actorUserId);
        return Map.of("pieceId", pieceId, "action", "mark_damaged", "result", "damaged");
    }

    // ── Attest-complete / cancel ──────────────────────────────────────────────

    @Transactional
    public Map<String, Object> attestComplete(UUID sessionId, UUID actorUserId) {
        UUID tenantId = TenantContext.require();
        stockTake.requireOpenSession(sessionId, tenantId);

        jdbc.update(
            "UPDATE stock_take_sessions SET complete_count = true WHERE id = ? AND tenant_id = ?",
            sessionId, tenantId);

        auditService.record(actorUserId, "stock_take_attest_complete",
            "stock_take_session", sessionId.toString(), null);

        return Map.of("sessionId", sessionId, "completeCount", true);
    }

    /**
     * FR-21 Step 5, Phase A — claim (one committed transaction, mandatory shape). Guards
     * the open->finalized flip, computes the per-variant write-off delta from this
     * session's committed piece_events, inserts the pending claim row, and enqueues the
     * push job (Phase B). Contains NO Shopify HTTP call — that's
     * StockTakeShopifyPushJob.push(), running later on a JobRunr worker with no DB
     * transaction around the call (same lesson as resolve()'s srt7 fix, one level up).
     * Internal -> lost transitions already committed per-item at Step 4 resolve() time;
     * this method writes no piece_events — the push is a separate, retryable side effect
     * of already-durable custody truth.
     */
    @Transactional
    public Map<String, Object> finalizeSession(UUID sessionId, UUID actorUserId) {
        UUID tenantId = TenantContext.require();

        // Double-finalize guard #1: atomic open->finalized flip. Computing the delta and
        // locking the session in the SAME tx means the pushed number can't drift from the
        // ledger between the guard check and the delta read.
        int rows = jdbc.update(
            "UPDATE stock_take_sessions SET status = 'finalized', finalized_by = ?, finalized_at = now() " +
            "WHERE id = ? AND tenant_id = ? AND status = 'open'",
            actorUserId, sessionId, tenantId);
        if (rows == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Stock take session is not open — already finalized or cancelled");
        }

        UUID locationId = jdbc.query(
            "SELECT location_id FROM stock_take_sessions WHERE id = ? AND tenant_id = ?",
            rs -> rs.next() ? rs.getObject("location_id", UUID.class) : null,
            sessionId, tenantId);

        // Per-variant delta = count of stock_take_missing piece_events for this session —
        // NOT expected-minus-counted. Variance includes committed/soft pieces deliberately
        // not written off, and drift-skipped write-offs are naturally excluded (they never
        // became 'lost' — see resolveLost()'s drift guard).
        List<Map<String, Object>> deltaRows = jdbc.queryForList(
            "SELECT p.variant_id AS variant_id, COUNT(*) AS qty " +
            "FROM piece_events pe " +
            "JOIN pieces p ON p.id = pe.piece_id AND p.tenant_id = pe.tenant_id " +
            "WHERE pe.tenant_id = ? AND pe.event_type = 'adjusted' AND pe.to_status = 'lost'::piece_status " +
            "  AND pe.metadata->>'session_id' = ? AND pe.metadata->>'reason' = 'stock_take_missing' " +
            "GROUP BY p.variant_id",
            tenantId, sessionId.toString());

        ObjectNode deltasNode = mapper.createObjectNode();
        for (Map<String, Object> row : deltaRows) {
            deltasNode.put(row.get("variant_id").toString(), ((Number) row.get("qty")).intValue());
        }
        ObjectNode payload = mapper.createObjectNode();
        payload.put("locationId", locationId != null ? locationId.toString() : null);
        payload.set("deltas", deltasNode);

        // Zero deltas -> nothing to push; claim lands 'pushed' directly and no job is
        // enqueued at all, rather than sending an empty mutation.
        String claimStatus = deltaRows.isEmpty() ? "pushed" : "pending";

        // Double-finalize guard #2: UNIQUE(session_id) referee. Guard #1 already makes a
        // second finalize call impossible in practice; this is defense in depth.
        jdbc.update(
            "INSERT INTO stock_take_shopify_syncs (id, tenant_id, session_id, status, payload) " +
            "VALUES (gen_random_uuid(), ?, ?, ?, ?::jsonb)",
            tenantId, sessionId, claimStatus, payload.toString());

        if (!deltaRows.isEmpty()) {
            // Claim-before-call: this enqueue only ever fires after the claim INSERT above
            // has been issued in this same transaction. JobRunr's own storage isn't
            // Spring-transaction-aware, but its worker polls on an interval — ample
            // separation from this transaction's commit. Matches the established pattern
            // elsewhere in this codebase (BostaIngestionHelper, ShopifyOAuthService).
            jobScheduler.enqueue(() -> pushJob.push(sessionId, tenantId));
        }

        auditService.record(actorUserId, "stock_take_finalize",
            "stock_take_session", sessionId.toString(),
            Map.of("variantCount", deltaRows.size()));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sessionId", sessionId);
        out.put("status", "finalized");
        out.put("variantDeltas", deltaRows);
        return out;
    }

    @Transactional
    public void cancel(UUID sessionId, UUID actorUserId) {
        UUID tenantId = TenantContext.require();
        stockTake.requireOpenSession(sessionId, tenantId);

        jdbc.update(
            "UPDATE stock_take_sessions SET status = 'cancelled' WHERE id = ? AND tenant_id = ?",
            sessionId, tenantId);

        auditService.record(actorUserId, "stock_take_cancel",
            "stock_take_session", sessionId.toString(), null);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String fetchStatusAtOpen(UUID sessionId, UUID tenantId, String pieceId) {
        String statusAtOpen = jdbc.query(
            "SELECT status_at_open FROM stock_take_expected " +
            "WHERE session_id = ? AND piece_id = ? AND tenant_id = ?",
            rs -> rs.next() ? rs.getString("status_at_open") : null,
            sessionId, pieceId, tenantId);
        if (statusAtOpen == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                "Piece " + pieceId + " is not part of this stock take's snapshot");
        }
        return statusAtOpen;
    }

    private String fetchLiveStatus(String pieceId, UUID tenantId) {
        String status = jdbc.query(
            "SELECT status::text FROM pieces WHERE id = ? AND tenant_id = ?",
            rs -> rs.next() ? rs.getString(1) : null,
            pieceId, tenantId);
        if (status == null) {
            throw new PieceNotFoundException(pieceId);
        }
        return status;
    }

    private Map<String, Object> fetchCommittedOrder(String pieceId, UUID tenantId) {
        return jdbc.query(
            "SELECT o.id AS order_id, o.number AS order_number " +
            "FROM allocations a " +
            "JOIN order_items oi ON oi.id = a.order_item_id " +
            "JOIN orders o ON o.id = oi.order_id " +
            "WHERE a.piece_id = ? AND a.tenant_id = ? AND a.status IN ('active','packed') " +
            "LIMIT 1",
            rs -> rs.next() ? Map.of(
                "orderId", rs.getObject("order_id", UUID.class),
                "orderNumber", rs.getString("order_number")) : null,
            pieceId, tenantId);
    }
}
