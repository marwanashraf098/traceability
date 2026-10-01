package com.traceability.inventory;

import com.traceability.integrations.bosta.BostaAwbService;
import com.traceability.tenancy.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Pick &amp; Pack S4 — the waybill-mode page lists and the session summary. Read-only except
 * {@link #reprint}, which only calls Bosta (no batch row is written). Every predicate comes from
 * {@link PackListRules}, shared with the two manager exceptions.
 */
@Service
public class PackListService {

    private static final Logger log = LoggerFactory.getLogger(PackListService.class);

    public record BatchToday(UUID batchId, int batchNo, Instant printedAt, String printedByName, int waybillCount,
                             int packed, int setAside, int cancelled, int waiting) {}

    /**
     * status: 'cancelled' (unresolved cancelled-after-print), 'packing' (live claim — packerName),
     * 'set_aside' (open set-aside — setAsideReason), 'waiting'. exceptionType / subjectKey are set
     * for the two exception rows so a manager can open the exception.
     */
    public record NotPackedRow(UUID orderId, String orderNumber, String customerName, UUID shipmentId,
                               String trackingNumber, UUID batchId, int batchNo, Instant batchPrintedAt,
                               String status, String packerName, String setAsideReason,
                               String exceptionType, String subjectKey) {}

    public record NeedsManager(UUID orderId, String orderNumber, String customerName, String kind,
                               String reason, String rawScan, Instant at) {}

    public record SessionSummary(UUID sessionId, String workerName, Instant startedAt, Instant endedAt,
                                 long durationSeconds, int packed, int setAside, int rejected,
                                 List<NeedsManager> needsManager, int unscannedFromTodaysBatches) {}

    private final JdbcTemplate        jdbc;
    private final TransactionTemplate readTx;
    private final PackSessionStore    sessions;
    private final BostaAwbService     awbService;

    public PackListService(JdbcTemplate jdbc, PlatformTransactionManager txm, PackSessionStore sessions,
                           BostaAwbService awbService) {
        this.jdbc       = jdbc;
        this.readTx     = new TransactionTemplate(txm);
        this.readTx.setReadOnly(true);
        this.sessions   = sessions;
        this.awbService = awbService;
    }

    private static final String TODAY_SQL =
        "(b.created_at AT TIME ZONE '" + PackSessionStore.TODAY_ZONE + "')::date = " +
        "(now() AT TIME ZONE '" + PackSessionStore.TODAY_ZONE + "')::date";

    /** Each batch item with its state ({@link PackListRules#BATCH_ITEM_STATE_SQL}); aliases b, bi, s, o, ps. */
    private static final String ITEM_STATE_FROM =
        "FROM pack_print_batch_items bi " +
        "JOIN pack_print_batches b ON b.id = bi.batch_id AND b.tenant_id = bi.tenant_id " +
        "JOIN shipments s ON s.id = bi.shipment_id AND s.tenant_id = bi.tenant_id " +
        "JOIN orders o ON o.id = bi.order_id AND o.tenant_id = bi.tenant_id " +
        PackListRules.LATEST_PACK_OUTCOME_LATERAL;

    // ── Print batches today ───────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<BatchToday> batchesToday() {
        UUID tenantId = TenantContext.require();
        return jdbc.query(
            "SELECT b.id, b.batch_no, b.created_at, u.name AS printed_by_name, b.waybill_count, " +
            "       COUNT(*) FILTER (WHERE st.state = 'packed')    AS packed, " +
            "       COUNT(*) FILTER (WHERE st.state = 'set_aside') AS set_aside, " +
            "       COUNT(*) FILTER (WHERE st.state = 'cancelled') AS cancelled, " +
            "       COUNT(*) FILTER (WHERE st.state = 'waiting')   AS waiting " +
            "FROM pack_print_batches b " +
            "LEFT JOIN users u ON u.id = b.printed_by " +
            "LEFT JOIN LATERAL ( " +
            "    SELECT " + PackListRules.BATCH_ITEM_STATE_SQL + " AS state " +
            "    FROM pack_print_batch_items bi " +
            "    JOIN shipments s ON s.id = bi.shipment_id AND s.tenant_id = bi.tenant_id " +
            "    JOIN orders o ON o.id = bi.order_id AND o.tenant_id = bi.tenant_id " +
            PackListRules.LATEST_PACK_OUTCOME_LATERAL +
            "    WHERE bi.batch_id = b.id AND bi.tenant_id = b.tenant_id " +
            ") st ON true " +
            "WHERE b.tenant_id = ? AND " + TODAY_SQL + " " +
            "GROUP BY b.id, b.batch_no, b.created_at, u.name, b.waybill_count " +
            // UUIDv4 is not time-ordered — order by created_at, never id (see CLAUDE.md invariant)
            "ORDER BY b.created_at DESC, b.id DESC",
            (rs, i) -> new BatchToday(rs.getObject("id", UUID.class), rs.getInt("batch_no"),
                rs.getTimestamp("created_at").toInstant(), rs.getString("printed_by_name"),
                rs.getInt("waybill_count"), rs.getInt("packed"), rs.getInt("set_aside"),
                rs.getInt("cancelled"), rs.getInt("waiting")),
            tenantId);
    }

    // ── Reprint ───────────────────────────────────────────────────────────────

    /**
     * Regenerate one batch's PDF: its shipments in stored position order, one mass-AWB call (a batch
     * is ≤ 49), merged by WaybillPdfAssembler. No new batch row. Shipments whose order is now
     * cancelled are skipped (ORDER_CANCELLED) — never reprint a cancelled order's waybill; Bosta's
     * own exclusions (terminal state, return pickup) and email-path / rejected chunks are reported.
     * Not @Transactional: the Bosta call stays outside any transaction.
     */
    public PackPrintBatchService.PrintBatchResult reprint(UUID batchId) {
        UUID tenantId = TenantContext.require();
        // Own short read transaction (RLS needs one); the Bosta call below stays outside it.
        List<Map<String, Object>> items = readTx.execute(st -> reprintItems(batchId, tenantId));
        if (items == null || items.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Print batch not found");
        int batchNo = ((Number) items.get(0).get("batch_no")).intValue();
        String paper = (String) items.get(0).get("paper");

        List<PackPrintBatchService.Excluded> excluded = new ArrayList<>();
        List<Map<String, Object>> printable = new ArrayList<>();
        Map<String, Map<String, Object>> byTracking = new HashMap<>();
        for (Map<String, Object> it : items) {
            byTracking.put((String) it.get("tracking_number"), it);
            if (Boolean.TRUE.equals(it.get("cancelled"))) {
                excluded.add(new PackPrintBatchService.Excluded((String) it.get("number"),
                    (String) it.get("tracking_number"), "ORDER_CANCELLED"));
            } else {
                printable.add(it);
            }
        }
        if (printable.isEmpty()) {
            return new PackPrintBatchService.PrintBatchResult(batchId, batchNo, 0, items.size(), 0, true, null,
                excluded, "Nothing in this batch can be printed again.");
        }

        BostaAwbService.AwbDetailedResult bosta = awbService.printAwbDetailed(tenantId,
            printable.stream().map(it -> (UUID) it.get("shipment_id")).toList(), paper, null);
        for (BostaAwbService.AwbException ex : bosta.exclusions()) {
            Map<String, Object> it = byTracking.get(ex.trackingNumber());
            excluded.add(new PackPrintBatchService.Excluded(it != null ? (String) it.get("number") : null,
                ex.trackingNumber(), ex.reason()));
        }
        List<byte[]> pdfs = new ArrayList<>();
        Set<String> inPdf = new HashSet<>();
        for (BostaAwbService.AwbChunk chunk : bosta.chunks()) {
            if (chunk.pdf() != null) { pdfs.add(chunk.pdf()); inPdf.addAll(chunk.trackingNumbers()); }
            else {
                String reason = chunk.emailMessage() != null ? "BOSTA_EMAIL_PATH" : chunk.rejectedReason();
                for (String tn : chunk.trackingNumbers()) {
                    Map<String, Object> it = byTracking.get(tn);
                    excluded.add(new PackPrintBatchService.Excluded(it != null ? (String) it.get("number") : null, tn, reason));
                }
            }
        }
        List<String> printedInOrder = printable.stream().map(it -> (String) it.get("tracking_number"))
            .filter(inPdf::contains).toList();
        if (printedInOrder.isEmpty()) {
            return new PackPrintBatchService.PrintBatchResult(batchId, batchNo, 0, items.size(), 0, true, null,
                excluded, "Bosta didn't return any waybills to print.");
        }
        WaybillPdfAssembler.Assembled pdf;
        try {
            pdf = WaybillPdfAssembler.assemble(pdfs, printedInOrder);
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                "Bosta returned a waybill PDF that couldn't be read — try again");
        }
        log.info("print batch reprint: tenant={} batch={} waybills={} orderVerified={}",
            tenantId, batchNo, printedInOrder.size(), pdf.orderGuaranteed());
        return new PackPrintBatchService.PrintBatchResult(batchId, batchNo, printedInOrder.size(), items.size(), 0,
            pdf.orderGuaranteed(),
            Base64.getEncoder().encodeToString(pdf.pdf()), excluded, null);
    }

    private List<Map<String, Object>> reprintItems(UUID batchId, UUID tenantId) {
        return jdbc.queryForList(
            "SELECT b.batch_no, b.paper, bi.shipment_id, bi.tracking_number, bi.position, " +
            "       o.number, " + PackListRules.CANCELLED_SQL + " AS cancelled " +
            "FROM pack_print_batches b " +
            "JOIN pack_print_batch_items bi ON bi.batch_id = b.id AND bi.tenant_id = b.tenant_id " +
            "JOIN orders o ON o.id = bi.order_id AND o.tenant_id = bi.tenant_id " +
            "WHERE b.id = ? AND b.tenant_id = ? " +
            "ORDER BY bi.position",
            batchId, tenantId);
    }

    // ── Printed but not packed ────────────────────────────────────────────────

    /**
     * Every printed waybill (any batch, no time window) whose order isn't packed yet, plus unresolved
     * cancelled-after-print ones; one row per shipment (its latest batch). Status priority:
     * cancelled → packing now (live claim) → set aside → waiting; sorted cancelled, set aside, packing
     * now, waiting, then the oldest batch first, then the waybill's position in the printed stack.
     */
    @Transactional(readOnly = true)
    public List<NotPackedRow> printedNotPacked() {
        UUID tenantId = TenantContext.require();
        return jdbc.query(
            "SELECT * FROM ( " +
            "  SELECT DISTINCT ON (s.id) o.id AS order_id, o.number, o.customer_name, s.id AS shipment_id, " +
            "         s.tracking_number, b.id AS batch_id, b.batch_no, b.created_at AS printed_at, bi.position, " +
            "         CASE WHEN " + PackListRules.CANCELLED_AFTER_PRINT_OPEN_SQL + " THEN 'cancelled' " +
            "              WHEN " + PackListRules.CANCELLED_SQL + " THEN NULL " +
            "              WHEN NOT " + PackListRules.NOT_YET_PACKED_SQL + " THEN NULL " +
            "              WHEN o.locked_by IS NOT NULL AND COALESCE(o.locked_at, '-infinity'::timestamptz) " +
            "                   > now() - interval '" + PackClaim.STALE_AFTER_MINUTES + " minutes' THEN 'packing' " +
            "              WHEN " + PackListRules.SET_ASIDE_OPEN_SQL + " THEN 'set_aside' " +
            "              ELSE 'waiting' END AS status, " +
            "         lu.name AS packer_name, ps.reason AS set_aside_reason, " +
            "         " + PackListRules.SET_ASIDE_KEY_SQL + " AS set_aside_key, " +
            "         " + PackListRules.CANCELLED_AFTER_PRINT_KEY_SQL + " AS cancelled_key " +
            "  " + ITEM_STATE_FROM +
            "  LEFT JOIN users lu ON lu.id = o.locked_by " +
            "  WHERE bi.tenant_id = ? " +
            // latest batch per shipment (UUIDv4 is not time-ordered — created_at, id only as tie-break)
            "  ORDER BY s.id, b.created_at DESC, b.id DESC " +
            ") x WHERE x.status IS NOT NULL " +
            "ORDER BY CASE x.status WHEN 'cancelled' THEN 0 WHEN 'set_aside' THEN 1 WHEN 'packing' THEN 2 ELSE 3 END, " +
            "         x.printed_at ASC, x.batch_no ASC, x.position ASC",
            (rs, i) -> {
                String status = rs.getString("status");
                return new NotPackedRow(rs.getObject("order_id", UUID.class), rs.getString("number"),
                    rs.getString("customer_name"), rs.getObject("shipment_id", UUID.class),
                    rs.getString("tracking_number"), rs.getObject("batch_id", UUID.class), rs.getInt("batch_no"),
                    rs.getTimestamp("printed_at").toInstant(), status,
                    "packing".equals(status) ? rs.getString("packer_name") : null,
                    "set_aside".equals(status) ? rs.getString("set_aside_reason") : null,
                    "cancelled".equals(status) ? "pack_cancelled_after_print"
                        : "set_aside".equals(status) ? "pack_set_aside" : null,
                    "cancelled".equals(status) ? rs.getString("cancelled_key")
                        : "set_aside".equals(status) ? rs.getString("set_aside_key") : null);
            },
            tenantId);
    }

    // ── Session summary ───────────────────────────────────────────────────────

    /** The caller's own session (403 otherwise, 404 for another tenant's). */
    @Transactional(readOnly = true)
    public SessionSummary summary(UUID sessionId, UUID userId) {
        UUID tenantId = TenantContext.require();
        sessions.load(sessionId, userId, tenantId, false, false);

        Map<String, Object> head = jdbc.queryForMap(
            "SELECT ps.started_at, ps.ended_at, u.name FROM pack_sessions ps LEFT JOIN users u ON u.id = ps.user_id " +
            "WHERE ps.id = ? AND ps.tenant_id = ?", sessionId, tenantId);
        Map<String, Object> counts = jdbc.queryForMap(
            "SELECT COUNT(*) FILTER (WHERE outcome = 'packed')    AS packed, " +
            "       COUNT(*) FILTER (WHERE outcome = 'set_aside') AS set_aside, " +
            "       COUNT(*) FILTER (WHERE outcome = 'rejected')  AS rejected " +
            "FROM pack_session_orders WHERE session_id = ? AND tenant_id = ?", sessionId, tenantId);
        List<NeedsManager> needs = jdbc.query(
            "SELECT so.order_id, o.number, o.customer_name, so.outcome, so.reason, so.raw_scan, so.created_at " +
            "FROM pack_session_orders so LEFT JOIN orders o ON o.id = so.order_id " +
            "WHERE so.session_id = ? AND so.tenant_id = ? " +
            "  AND (so.outcome = 'set_aside' OR (so.outcome = 'rejected' AND so.reason LIKE 'CANCELLED%')) " +
            // UUIDv4 is not time-ordered — order by created_at, never id (see CLAUDE.md invariant)
            "ORDER BY so.created_at ASC, so.id ASC",
            (rs, i) -> new NeedsManager(rs.getObject("order_id", UUID.class), rs.getString("number"),
                rs.getString("customer_name"),
                "set_aside".equals(rs.getString("outcome")) ? "set_aside" : "cancelled",
                rs.getString("reason"), rs.getString("raw_scan"), rs.getTimestamp("created_at").toInstant()),
            sessionId, tenantId);
        Integer unscanned = jdbc.queryForObject(
            "SELECT COUNT(DISTINCT s.id) " + ITEM_STATE_FROM +
            "WHERE bi.tenant_id = ? AND " + TODAY_SQL + " AND " + PackListRules.BATCH_ITEM_STATE_SQL + " = 'waiting'",
            Integer.class, tenantId);

        Instant started = ((java.sql.Timestamp) head.get("started_at")).toInstant();
        Instant ended = head.get("ended_at") != null ? ((java.sql.Timestamp) head.get("ended_at")).toInstant() : null;
        long duration = Duration.between(started, ended != null ? ended : Instant.now()).getSeconds();
        return new SessionSummary(sessionId, (String) head.get("name"), started, ended, duration,
            n(counts.get("packed")), n(counts.get("set_aside")), n(counts.get("rejected")), needs,
            unscanned == null ? 0 : unscanned);
    }

    private static int n(Object o) { return o == null ? 0 : ((Number) o).intValue(); }
}
