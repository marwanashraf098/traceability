package com.traceability.inventory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.traceability.tenancy.TenantContext;
import org.jobrunr.scheduling.JobScheduler;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
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
 * Resolution actions: mark_damaged only (available -> damaged via PieceAdjustService, the
 * existing move path). found / lost were removed 2026-10-27 — a missed piece is scanned, and
 * finalize writes off what wasn't counted.
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

    /** 2026-10-27: did THIS session's finalize write the piece off / bring it back (the finalized review lists). */
    private static String sessionEventSql(String pieceCol, String sessionCol, String toStatus, String reason) {
        return "EXISTS (SELECT 1 FROM piece_events pe WHERE pe.tenant_id = p.tenant_id AND pe.piece_id = " + pieceCol +
            " AND pe.to_status = '" + toStatus + "'::piece_status AND pe.metadata->>'reason' = '" + reason + "'" +
            " AND pe.metadata->>'session_id' = " + sessionCol + "::text)";
    }

    private static final String EXPECTED_QUERY =
        "SELECT se.piece_id, se.variant_id, v.title AS variant_title, v.sku, pr.title AS product_title, p.short_code, " +
        "       se.status_at_open, p.status::text AS live_status, se.untagged_at_open, " +
        "       (ts.id IS NOT NULL) AS scanned, ts.scanned_condition, ts.scan_device, " +
        "       o.id AS order_id, o.number AS order_number, " +
        "       s.id AS shipment_id, s.tracking_number, " +
        "       " + sessionEventSql("se.piece_id", "se.session_id", "lost", "stock_take_missing") + " AS written_off_here, " +
        "       " + sessionEventSql("se.piece_id", "se.session_id", "available", "stock_take_found") + " AS found_here " +
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
        "SELECT ts.piece_id, p.variant_id, v.title AS variant_title, v.sku, pr.title AS product_title, p.short_code, " +
        "       p.status::text AS live_status, ts.scan_device, " +
        "       " + sessionEventSql("ts.piece_id", "ts.session_id", "lost", "stock_take_missing") + " AS written_off_here, " +
        "       " + sessionEventSql("ts.piece_id", "ts.session_id", "available", "stock_take_found") + " AS found_here " +
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
    private final ShopifyInventoryService shopifyInventory;
    private final TransactionTemplate tx;

    /** For a hand-built instance (tests): transactions on the JdbcTemplate's own DataSource. */
    public StockTakeReconciliationService(JdbcTemplate jdbc, StockTakeService stockTake,
                                          InventoryLedger ledger, PieceAdjustService pieceAdjustService,
                                          com.traceability.account.AuditService auditService,
                                          ObjectMapper mapper, JobScheduler jobScheduler,
                                          StockTakeShopifyPushJob pushJob,
                                          ShopifyInventoryService shopifyInventory) {
        this(jdbc, stockTake, ledger, pieceAdjustService, auditService, mapper, jobScheduler, pushJob, shopifyInventory,
            new org.springframework.jdbc.datasource.DataSourceTransactionManager(jdbc.getDataSource()));
    }

    @org.springframework.beans.factory.annotation.Autowired
    public StockTakeReconciliationService(JdbcTemplate jdbc, StockTakeService stockTake,
                                          InventoryLedger ledger, PieceAdjustService pieceAdjustService,
                                          com.traceability.account.AuditService auditService,
                                          ObjectMapper mapper, JobScheduler jobScheduler,
                                          StockTakeShopifyPushJob pushJob,
                                          ShopifyInventoryService shopifyInventory,
                                          PlatformTransactionManager txm) {
        this.jdbc               = jdbc;
        this.stockTake          = stockTake;
        this.ledger             = ledger;
        this.pieceAdjustService = pieceAdjustService;
        this.auditService       = auditService;
        this.mapper             = mapper;
        this.jobScheduler       = jobScheduler;
        this.pushJob            = pushJob;
        this.shopifyInventory   = shopifyInventory;
        this.tx                 = new TransactionTemplate(txm);
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
            // Slice 7: untagged pieces are counted per variant (the "untagged" section), never row by row.
            if (Boolean.TRUE.equals(row.get("untagged_at_open"))) continue;
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
            summary.put("scanDevice", row.get("scan_device"));             // Q1b: null when not scanned
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
            summary.put("scanDevice", row.get("scan_device"));             // Q1b
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
        // Q1b: "N of M scans came from a phone" on the review screen (informational, no guard).
        Map<String, Object> devices = jdbc.queryForMap(
            "SELECT COUNT(*) AS total, COUNT(*) FILTER (WHERE scan_device = 'phone') AS phone " +
            "FROM stock_take_scans WHERE session_id = ? AND tenant_id = ? AND source = 'scan'",
            sessionId, tenantId);
        out.put("scanCount", ((Number) devices.get("total")).intValue());
        out.put("phoneScanCount", ((Number) devices.get("phone")).intValue());
        // What finalize would do right now (open sessions only) — the review screen's finalize
        // modal shows exactly these numbers.
        if ("open".equals(session.status())) {
            out.put("finalizePlan", plan(sessionId, tenantId, session.completeCount()).toResponse());
        }
        // Slice 7: the per-variant untagged counts (switch on only — the key is absent otherwise).
        if (com.traceability.labels.FlexibleLabeling.enabled(jdbc, tenantId)) {
            boolean open = "open".equals(session.status());
            out.put("untagged", StockTakeUntagged.rows(jdbc, tenantId, sessionId, session.locationId(), open).stream()
                .map(r -> StockTakeUntagged.toResponse(r, open)).toList());
        }
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
        m.put("shortCode", row.get("short_code"));
        m.put("writtenOffHere", Boolean.TRUE.equals(row.get("written_off_here")));
        m.put("foundHere", Boolean.TRUE.equals(row.get("found_here")));
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

    /** The only resolution left on the review screen (2026-10-27): Apply damaged on a condition_mismatch row. */
    static final String ACTION_MARK_DAMAGED = "mark_damaged";

    /**
     * Batch of mark_damaged resolutions. Each item runs in its OWN transaction (TransactionTemplate),
     * never one shared batch transaction: the tenant GUC is set only when a transaction begins
     * (TenantAwareConnection), so a read outside one sees nothing under app_user / RLS — the cause of
     * the 2026-10-27 bare-404 bug — and a shared transaction would let one item's failure mark the
     * whole batch rollback-only.
     *
     * Found it / Mark lost were removed (2026-10-27): a missed piece is counted by scanning it (the
     * review screen's scan overlay, the same StockTakeService.scan path), and finalize writes off what
     * wasn't counted. Both are refused with 400 STOCK_TAKE_ACTION_REMOVED before anything is read.
     */
    public List<Map<String, Object>> resolve(UUID sessionId, List<ResolveItem> items, UUID actorUserId) {
        UUID tenantId = TenantContext.require();
        if (items == null || items.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "at least one resolution item is required");
        }
        for (ResolveItem item : items) {
            if (!ACTION_MARK_DAMAGED.equals(item.action())) throw StockTakeActionRemovedException.of(item.action());
        }
        StockTakeService.SessionRow session = tx.execute(st -> stockTake.requireOpenSession(sessionId, tenantId));

        List<Map<String, Object>> results = new ArrayList<>();
        for (ResolveItem item : items) {
            results.add(tx.execute(st -> resolveOne(session, tenantId, item.pieceId(), actorUserId)));
        }
        return results;
    }

    private Map<String, Object> resolveOne(StockTakeService.SessionRow session, UUID tenantId,
                                           String pieceId, UUID actorUserId) {
        // Slice 7: an untagged piece is counted per variant — never corrected one by one.
        if (UntaggedPieces.isUntagged(jdbc, tenantId, pieceId) || Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM stock_take_expected WHERE session_id = ? AND tenant_id = ? " +
                "  AND piece_id = ? AND untagged_at_open)", Boolean.class, session.id(), tenantId, pieceId))) {
            throw com.traceability.labels.UntaggedException.countedPerProduct();
        }
        return resolveMarkDamaged(session, pieceId, actorUserId);
    }

    /** Condition correction: available -> damaged, via the existing PieceAdjustService path
     *  (which also carries the FR-17 v2 Shopify damage-move trigger). */
    private Map<String, Object> resolveMarkDamaged(StockTakeService.SessionRow session,
                                                    String pieceId, UUID actorUserId) {
        pieceAdjustService.adjustPiece(pieceId, "damaged", "damaged_in_storage",
            "Condition correction during stock take " + session.id(), actorUserId);
        return Map.of("pieceId", pieceId, "action", ACTION_MARK_DAMAGED, "result", "damaged");
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

    // ── Finalize plan (shared by the review screen and finalize) ─────────────────

    /** Free stock: what a count can write off when it isn't on the shelf. */
    private static final Set<String> FREE_STATUSES = Set.of("available", "damaged", "on_hold");

    /** origin = status_at_open (available / damaged / on_hold); the display fields feed the finalize confirm. */
    public record WriteOff(String pieceId, UUID variantId, String origin,
                           String shortCode, String productTitle, String variantTitle) {
        /** −1 in Shopify only for available → lost; damaged / on_hold units already left Shopify "available". */
        public int shopifyEffect() { return "available".equals(origin) ? -1 : 0; }

        Map<String, Object> toResponse() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("pieceId", pieceId);
            m.put("shortCode", shortCode);
            m.put("productTitle", productTitle);
            m.put("variantTitle", variantTitle);
            m.put("variantId", variantId);
            m.put("origin", origin);
            m.put("shopifyEffect", shopifyEffect());
            return m;
        }
    }
    public record Found(String pieceId, UUID variantId, boolean shopifyIncrement) {}

    /**
     * What finalize will do with this session right now — computed by ONE method so the review
     * screen's numbers and finalize's actions can never disagree.
     *   writeOffs        expected free stock (available / damaged / on_hold at open), not scanned,
     *                    still in that same status (the drift guard) → lost
     *   damageCorrections expected pieces scanned 'damaged' that are live 'available' → damaged
     *   founds           scanned pieces that are live 'lost' → available (+1 to Shopify only when
     *                    their stock-take write-off was pushed — see foundIncrementEligible)
     *   driftSkipped     unscanned free stock whose status changed since open — left alone
     *   alreadyWrittenOff free stock this session already wrote off through the per-row resolve
     * shopifyDecrement per variant = available-origin write-offs (new + already) — damaged and
     * on_hold write-offs change Traced only (a damaged unit is not in Shopify "available"; an
     * on_hold one left it at hold-enter).
     */
    public record FinalizePlan(int scans, int expectedFree, int scannedFree, boolean completeCount,
                               List<WriteOff> writeOffs, List<String> damageCorrections, List<Found> founds,
                               int driftSkipped, int alreadyWrittenOff, List<Map<String, Object>> byVariant,
                               boolean untaggedCounting, List<StockTakeUntagged.Row> untagged) {

        /** Slice 7: the untagged pieces finalize writes off — the counted shortfall, oldest first. */
        public List<String> untaggedWriteOffs() {
            return untagged.stream().flatMap(r -> r.writeOffs().stream()).toList();
        }

        public int untaggedMissingCounts() {
            return (int) untagged.stream().filter(StockTakeUntagged.Row::missing).count();
        }

        public double coverage() {
            return expectedFree == 0 ? 1.0 : (double) scannedFree / expectedFree;
        }

        public boolean requiresTypedConfirmation() {
            return StockTakeFinalizePolicy.requiresTypedConfirmation(expectedFree, scannedFree, writeOffs.size());
        }

        /** Why finalize would be refused right now, or null. */
        public String blockedReason() {
            // Slice 7: a session whose only free stock is untagged is finished by its counts, not by scans.
            boolean onlyUntagged = untaggedCounting && !untagged.isEmpty() && expectedFree == 0;
            if (scans == 0 && !onlyUntagged) return "ZERO_SCANS";
            if (untaggedMissingCounts() > 0) return "UNTAGGED_COUNT_MISSING";
            if (!completeCount && (!writeOffs.isEmpty() || alreadyWrittenOff > 0 || !untaggedWriteOffs().isEmpty())) {
                return "ATTESTATION_REQUIRED";
            }
            return null;
        }

        public Map<String, Object> toResponse() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("scans", scans);
            m.put("expectedFree", expectedFree);
            m.put("scannedFree", scannedFree);
            m.put("coveragePercent", Math.round(10000.0 * coverage()) / 100.0);
            m.put("writeOffs", writeOffs.size());
            // 2026-10-27: every labeled piece finalize writes off, for the confirm's list (totals unchanged).
            m.put("writeOffPieces", writeOffs.stream().map(WriteOff::toResponse).toList());
            m.put("damageCorrections", damageCorrections.size());
            m.put("founds", founds.size());
            m.put("foundIncrements", founds.stream().filter(Found::shopifyIncrement).count());
            m.put("driftSkipped", driftSkipped);
            m.put("alreadyWrittenOff", alreadyWrittenOff);
            m.put("shopifyDecrement", byVariant.stream().mapToInt(v -> (Integer) v.get("shopifyDecrement")).sum()
                + untaggedWriteOffs().size());
            m.put("byVariant", byVariant);
            if (untaggedCounting) {
                // Slice 7: every untagged write-off is FROM available, so each one is −1 in Shopify too.
                m.put("untagged", untagged.stream().map(r -> StockTakeUntagged.toResponse(r, true)).toList());
                m.put("untaggedWriteOffs", untaggedWriteOffs().size());
                m.put("untaggedExtra", untagged.stream().mapToInt(StockTakeUntagged.Row::extra).sum());
                m.put("untaggedMissingCounts", untaggedMissingCounts());
            }
            m.put("requiresTypedConfirmation", requiresTypedConfirmation());
            m.put("minCoveragePercent", Math.round(StockTakeFinalizePolicy.MIN_COVERAGE * 100));
            m.put("maxWriteOffPercent", Math.round(StockTakeFinalizePolicy.MAX_WRITE_OFF_SHARE * 100));
            m.put("blockedReason", blockedReason());
            return m;
        }
    }

    private static final String PLAN_EXPECTED_QUERY =
        "SELECT se.piece_id, se.variant_id, v.title AS variant_title, v.sku, se.status_at_open, " +
        "       p.short_code, pr.title AS product_title, " +
        "       p.status::text AS live_status, (ts.id IS NOT NULL) AS scanned, ts.scanned_condition, " +
        "       " + UntaggedPieces.untaggedSql("p") + " AS untagged, se.untagged_at_open, " +
        "       EXISTS (SELECT 1 FROM piece_events pe WHERE pe.tenant_id = se.tenant_id AND pe.piece_id = se.piece_id " +
        "               AND pe.to_status = 'lost'::piece_status AND pe.metadata->>'reason' = 'stock_take_missing' " +
        "               AND pe.metadata->>'session_id' = se.session_id::text) AS written_off_here " +
        "FROM stock_take_expected se " +
        "JOIN pieces p ON p.id = se.piece_id AND p.tenant_id = se.tenant_id " +
        "JOIN variants v ON v.id = se.variant_id " +
        "JOIN products pr ON pr.id = v.product_id " +
        "LEFT JOIN stock_take_scans ts ON ts.session_id = se.session_id AND ts.piece_id = se.piece_id " +
        "WHERE se.session_id = ? AND se.tenant_id = ? " +
        "ORDER BY v.title, se.piece_id";

    /** Pieces finalize may touch, locked in a fixed order so two finalizes can't interleave. */
    private static final String LOCK_PIECES_QUERY =
        "SELECT p.id FROM pieces p WHERE p.tenant_id = ? AND p.id IN (" +
        "  SELECT piece_id FROM stock_take_expected WHERE session_id = ? AND tenant_id = ? " +
        "  UNION SELECT piece_id FROM stock_take_scans WHERE session_id = ? AND tenant_id = ? AND piece_id IS NOT NULL) " +
        "ORDER BY p.id FOR UPDATE";

    FinalizePlan plan(UUID sessionId, UUID tenantId, boolean completeCount) {
        boolean untaggedCounting = com.traceability.labels.FlexibleLabeling.enabled(jdbc, tenantId);
        List<StockTakeUntagged.Row> untagged = untaggedCounting
            ? StockTakeUntagged.rows(jdbc, tenantId, sessionId, stockTake.requireSession(sessionId, tenantId).locationId(), true)
            : List.of();
        List<Map<String, Object>> rows = jdbc.queryForList(PLAN_EXPECTED_QUERY, sessionId, tenantId);
        Integer scans = jdbc.queryForObject(
            "SELECT COUNT(*) FROM stock_take_scans WHERE session_id = ? AND tenant_id = ? AND piece_id IS NOT NULL",
            Integer.class, sessionId, tenantId);

        List<WriteOff> writeOffs = new ArrayList<>();
        List<String> damageCorrections = new ArrayList<>();
        int expectedFree = 0, scannedFree = 0, driftSkipped = 0, alreadyWrittenOff = 0;
        Map<UUID, Map<String, Object>> byVariant = new LinkedHashMap<>();

        for (Map<String, Object> r : rows) {
            String atOpen = (String) r.get("status_at_open");
            String live = (String) r.get("live_status");
            boolean scanned = Boolean.TRUE.equals(r.get("scanned"));
            String pieceId = (String) r.get("piece_id");
            UUID variantId = (UUID) r.get("variant_id");

            // Flexible labeling: an untagged piece can't be scanned, so the labeled rules never write it off
            // or correct it — slice 7's counted shortfall (StockTakeUntagged) is the ONLY way a stock take
            // writes one off. Untagged at open or now: both are skipped.
            if (Boolean.TRUE.equals(r.get("untagged")) || Boolean.TRUE.equals(r.get("untagged_at_open"))) continue;
            if (scanned && "damaged".equals(r.get("scanned_condition")) && "available".equals(live)) {
                damageCorrections.add(pieceId);
            }
            if (!FREE_STATUSES.contains(atOpen)) continue;
            expectedFree++;
            if (scanned) { scannedFree++; continue; }

            Map<String, Object> v = byVariant.computeIfAbsent(variantId, id -> variantRow(r));
            if (Boolean.TRUE.equals(r.get("written_off_here")) && "lost".equals(live)) {
                alreadyWrittenOff++;
                if ("available".equals(atOpen)) v.put("shopifyDecrement", (Integer) v.get("shopifyDecrement") + 1);
                v.put("alreadyWrittenOff", (Integer) v.get("alreadyWrittenOff") + 1);
            } else if (atOpen.equals(live)) {
                writeOffs.add(new WriteOff(pieceId, variantId, atOpen, (String) r.get("short_code"),
                    (String) r.get("product_title"), (String) r.get("variant_title")));
                String key = switch (atOpen) { case "available" -> "available"; case "damaged" -> "damaged"; default -> "onHold"; };
                v.put(key, (Integer) v.get(key) + 1);
                if ("available".equals(atOpen)) v.put("shopifyDecrement", (Integer) v.get("shopifyDecrement") + 1);
            } else {
                driftSkipped++;
                v.put("driftSkipped", (Integer) v.get("driftSkipped") + 1);
            }
        }

        List<Found> founds = new ArrayList<>();
        for (Map<String, Object> f : jdbc.queryForList(
                "SELECT ts.piece_id, p.variant_id FROM stock_take_scans ts " +
                "JOIN pieces p ON p.id = ts.piece_id AND p.tenant_id = ts.tenant_id " +
                "WHERE ts.session_id = ? AND ts.tenant_id = ? AND ts.piece_id IS NOT NULL AND p.status = 'lost' " +
                "ORDER BY ts.piece_id", sessionId, tenantId)) {
            String pieceId = (String) f.get("piece_id");
            founds.add(new Found(pieceId, (UUID) f.get("variant_id"), foundIncrementEligible(pieceId, tenantId)));
        }

        List<Map<String, Object>> variants = new ArrayList<>();
        for (Map<String, Object> v : byVariant.values()) {
            if ((Integer) v.get("available") + (Integer) v.get("damaged") + (Integer) v.get("onHold")
                    + (Integer) v.get("alreadyWrittenOff") + (Integer) v.get("driftSkipped") > 0) {
                variants.add(v);
            }
        }
        return new FinalizePlan(scans == null ? 0 : scans, expectedFree, scannedFree, completeCount,
            writeOffs, damageCorrections, founds, driftSkipped, alreadyWrittenOff, variants, untaggedCounting, untagged);
    }

    private static Map<String, Object> variantRow(Map<String, Object> r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("variantId", r.get("variant_id"));
        m.put("variantTitle", r.get("variant_title"));
        m.put("sku", r.get("sku"));
        m.put("available", 0);
        m.put("damaged", 0);
        m.put("onHold", 0);
        m.put("alreadyWrittenOff", 0);
        m.put("driftSkipped", 0);
        m.put("shopifyDecrement", 0);
        return m;
    }

    /**
     * A found piece gets +1 in Shopify only when the unit really left Shopify "available" when the
     * piece went lost. Its latest →lost event decides:
     *   - from 'available', a stock-take write-off (reason stock_take_missing), and that session's push
     *       a) applied — status 'pushed', pushed_at set, the variant in the pushed deltas; or
     *       b) was superseded by the seed (wholly, or this variant moved to payload.superseded) and the
     *          write-off happened at or before that seed's snapshot — the seed set Shopify to an on-hand
     *          that already excluded the piece (Marawan, 2026-10-01);
     *   - from 'on_hold' (any reason): the hold-enter decrement of the hold cycle the piece was in when
     *     it went lost was applied (shopify_inventory_adjustments hold_enter, piece:hold_event_id).
     *   - (2026-10-10) a Lookup write-off from 'available' whose piece_write_off −1 applied, and any
     *     departure on a seeded tenant made before the seed (the seed left the piece out).
     * Anything else — a manual lost adjustment made before Lookup write-offs synced, a damaged /
     * with_courier write-off, a push that failed, is pending or is ambiguous, a hold-enter that never
     * applied — never reached Shopify, so no +1. Decided by PieceShopifyRules.reach (shared, D5).
     */
    private boolean foundIncrementEligible(String pieceId, UUID tenantId) {
        // D5 (2026-10-10): the ONE shared "did the departure reach Shopify" predicate — read-only here
        // (the plan); finalize cancels an unsent departure claim when it applies the found.
        return PieceShopifyRules.reach(jdbc, tenantId, pieceId, false).reached();
    }

    /** Finalize without a typed confirmation — refused (409) whenever the plan requires one. */
    @Transactional
    public Map<String, Object> finalizeSession(UUID sessionId, UUID actorUserId) {
        return finalizeSession(sessionId, actorUserId, null);
    }

    @Transactional
    public Map<String, Object> finalizeSession(UUID sessionId, UUID actorUserId, Integer confirmWriteOffs) {
        UUID tenantId = TenantContext.require();

        StockTakeService.SessionRow session = jdbc.query(
            "SELECT id, status, scope_type, location_id, complete_count FROM stock_take_sessions " +
            "WHERE id = ? AND tenant_id = ? FOR UPDATE",
            rs -> rs.next() ? new StockTakeService.SessionRow(rs.getObject("id", UUID.class), rs.getString("status"),
                rs.getString("scope_type"), rs.getObject("location_id", UUID.class), rs.getBoolean("complete_count")) : null,
            sessionId, tenantId);
        if (session == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Stock take session not found");
        }
        if (!"open".equals(session.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Stock take session is not open — already finalized or cancelled");
        }
        jdbc.query(LOCK_PIECES_QUERY, rs -> {}, tenantId, sessionId, tenantId, sessionId, tenantId);

        // Flexible labeling slice 2: with the switch OFF, untagged stock (which may have arrived while the
        // session was open) still refuses finalize. Slice 7: with it ON, it is counted instead.
        if (!com.traceability.labels.FlexibleLabeling.enabled(jdbc, tenantId)) {
            StockTakeService.refuseUntaggedInScope(jdbc, tenantId, session.locationId(),
                "variant_subset".equals(session.scopeType())
                    ? jdbc.queryForList("SELECT variant_id FROM stock_take_scope_variants WHERE session_id = ? AND tenant_id = ?",
                                        UUID.class, sessionId, tenantId)
                    : null);
        }

        FinalizePlan plan = plan(sessionId, tenantId, session.completeCount());
        if ("ZERO_SCANS".equals(plan.blockedReason())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "ZERO_SCANS: nothing was scanned in this stock take — finalizing would write off the whole count");
        }
        if ("UNTAGGED_COUNT_MISSING".equals(plan.blockedReason())) {
            throw com.traceability.labels.UntaggedException.untaggedCountMissing(plan.untaggedMissingCounts());
        }
        if ("ATTESTATION_REQUIRED".equals(plan.blockedReason())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "ATTESTATION_REQUIRED: attest that the count covered the full scope before finalizing write-offs");
        }
        if (plan.requiresTypedConfirmation()
                && (confirmWriteOffs == null || confirmWriteOffs != plan.writeOffs().size())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "CONFIRMATION_REQUIRED: type the number of pieces that will be written off (" +
                plan.writeOffs().size() + ") to finalize");
        }

        // 3. Apply the count.
        for (WriteOff w : plan.writeOffs()) {
            // Second layer: the labeled not-scanned rule never writes off an untagged piece.
            if (UntaggedPieces.isUntagged(jdbc, tenantId, w.pieceId())) {
                throw new IllegalStateException("labeled write-off of an untagged piece " + w.pieceId());
            }
            String metadata = "{\"session_id\":\"" + sessionId + "\",\"reason\":\"stock_take_missing\"}";
            ledger.transition(w.pieceId(), PieceStatus.fromDb(w.origin()), PieceStatus.LOST, "adjusted", actorUserId,
                new TransitionContext(null, null, null, null, metadata));
        }
        // Slice 7: the counted untagged shortfall — the same available → lost write-off (reason
        // stock_take_missing), so the same per-variant pushStockTakeWriteOff below decrements Shopify.
        for (String pieceId : plan.untaggedWriteOffs()) {
            String metadata = "{\"session_id\":\"" + sessionId + "\",\"reason\":\"stock_take_missing\",\"untagged\":true}";
            ledger.transition(pieceId, PieceStatus.AVAILABLE, PieceStatus.LOST, "adjusted", actorUserId,
                new TransitionContext(null, null, null, null, metadata));
        }
        for (StockTakeUntagged.Row r : plan.untagged()) {
            jdbc.update("UPDATE stock_take_untagged_counts SET expected_at_finalize = ? " +
                "WHERE session_id = ? AND variant_id = ? AND tenant_id = ?", r.expected(), sessionId, r.variantId(), tenantId);
        }
        for (String pieceId : plan.damageCorrections()) {
            pieceAdjustService.adjustPiece(pieceId, "damaged", "damaged_in_storage",
                "Condition correction during stock take " + sessionId, actorUserId);
        }
        for (Found f : plan.founds()) {
            // The piece is back: a departure claim never sent (a Lookup write-off or hold still queued,
            // or failed with attempts left) is cancelled so the sweep never sends it now.
            PieceShopifyRules.reach(jdbc, tenantId, f.pieceId(), true);
            String metadata = "{\"session_id\":\"" + sessionId + "\",\"reason\":\"stock_take_found\"}";
            ledger.transition(f.pieceId(), PieceStatus.LOST, PieceStatus.AVAILABLE, "adjusted", actorUserId,
                new TransitionContext(null, null, session.locationId(), null, metadata));
            jdbc.update("UPDATE pieces SET current_location_id = ? WHERE id = ? AND tenant_id = ?",
                session.locationId(), f.pieceId(), tenantId);
            if (f.shopifyIncrement()) {
                String pieceId = f.pieceId();
                UUID locationId = session.locationId();
                ShopifyInventoryService.afterCommit(() ->
                    shopifyInventory.onStockTakeFound(tenantId, pieceId, sessionId, locationId));
            }
        }

        // 4. Finalize + claim.
        jdbc.update(
            "UPDATE stock_take_sessions SET status = 'finalized', finalized_by = ?, finalized_at = now() " +
            "WHERE id = ? AND tenant_id = ? AND status = 'open'",
            actorUserId, sessionId, tenantId);

        // Per-variant delta = this session's write-offs FROM 'available' (finalize's and the per-row
        // resolve's alike) — a damaged unit is not in Shopify "available", an on_hold one already
        // left it at hold-enter. Never expected-minus-counted.
        List<Map<String, Object>> deltaRows = jdbc.queryForList(
            "SELECT p.variant_id AS variant_id, COUNT(*) AS qty " +
            "FROM piece_events pe " +
            "JOIN pieces p ON p.id = pe.piece_id AND p.tenant_id = pe.tenant_id " +
            "WHERE pe.tenant_id = ? AND pe.event_type = 'adjusted' AND pe.to_status = 'lost'::piece_status " +
            "  AND pe.from_status = 'available'::piece_status " +
            "  AND pe.metadata->>'session_id' = ? AND pe.metadata->>'reason' = 'stock_take_missing' " +
            // Review mode S4 (V130): a simulated-courier tenant's fixture variants (not from Shopify)
            // are never pushed — same rule as ShopifyInventoryService.claim(). Real tenants: no-op.
            "  AND NOT (EXISTS (SELECT 1 FROM tenant_courier_simulation sim WHERE sim.tenant_id = pe.tenant_id) " +
            "           AND EXISTS (SELECT 1 FROM variants v WHERE v.id = p.variant_id AND v.tenant_id = p.tenant_id " +
            "                         AND v.external_id NOT LIKE 'gid://shopify/%')) " +
            "GROUP BY p.variant_id",
            tenantId, sessionId.toString());

        ObjectNode deltasNode = mapper.createObjectNode();
        for (Map<String, Object> row : deltaRows) {
            deltasNode.put(row.get("variant_id").toString(), ((Number) row.get("qty")).intValue());
        }
        ObjectNode payload = mapper.createObjectNode();
        payload.put("locationId", session.locationId() != null ? session.locationId().toString() : null);
        payload.set("deltas", deltasNode);

        String claimStatus = deltaRows.isEmpty() ? "nothing_to_push" : "pending";
        // Double-finalize guard #2: UNIQUE(session_id) referee (the row lock above is #1).
        jdbc.update(
            "INSERT INTO stock_take_shopify_syncs (id, tenant_id, session_id, status, payload) " +
            "VALUES (gen_random_uuid(), ?, ?, ?, ?::jsonb)",
            tenantId, sessionId, claimStatus, payload.toString());

        if (!deltaRows.isEmpty()) {
            // Claim-before-call, enqueued after commit: JobRunr's storage isn't Spring-
            // transaction-aware, so an enqueue inside this transaction would survive a rollback
            // and could run before the claim row is visible.
            ShopifyInventoryService.afterCommit(() -> jobScheduler.enqueue(() -> pushJob.push(sessionId, tenantId)));
        }

        Map<String, Object> auditMeta = new LinkedHashMap<>();
        auditMeta.put("variantCount", deltaRows.size());
        auditMeta.put("writeOffs", plan.writeOffs().size());
        auditMeta.put("damageCorrections", plan.damageCorrections().size());
        auditMeta.put("founds", plan.founds().size());
        auditMeta.put("driftSkipped", plan.driftSkipped());
        if (plan.untaggedCounting()) auditMeta.put("untaggedWriteOffs", plan.untaggedWriteOffs().size());
        auditService.record(actorUserId, "stock_take_finalize", "stock_take_session", sessionId.toString(), auditMeta);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sessionId", sessionId);
        out.put("status", "finalized");
        out.put("variantDeltas", deltaRows);
        out.put("writeOffs", plan.writeOffs().size());
        out.put("damageCorrections", plan.damageCorrections().size());
        out.put("founds", plan.founds().size());
        out.put("driftSkipped", plan.driftSkipped());
        if (plan.untaggedCounting()) {
            out.put("untaggedWriteOffs", plan.untaggedWriteOffs().size());
            out.put("untaggedExtra", plan.untagged().stream().mapToInt(StockTakeUntagged.Row::extra).sum());
        }
        out.put("shopifySyncStatus", claimStatus);
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

    // ── Step 6.1: failed_ambiguous ops tail ──────────────────────────────────

    /**
     * Operator asserts the decrement DID apply (verified in Shopify directly) — no
     * Shopify call here. Only valid from failed_ambiguous; a plain UPDATE with the status
     * in the WHERE clause is the guard (0 rows affected from pending/pushed/failed -> 409).
     */
    @Transactional
    public Map<String, Object> markSyncResolved(UUID sessionId, UUID actorUserId) {
        UUID tenantId = TenantContext.require();
        stockTake.requireSession(sessionId, tenantId);

        int rows = jdbc.update(
            "UPDATE stock_take_shopify_syncs SET status = 'pushed', pushed_at = now() " +
            "WHERE session_id = ? AND tenant_id = ? AND status = 'failed_ambiguous'",
            sessionId, tenantId);
        if (rows == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Sync can only be marked resolved from failed_ambiguous");
        }

        auditService.record(actorUserId, "stock_take_sync_mark_resolved",
            "stock_take_session", sessionId.toString(), null);
        return Map.of("sessionId", sessionId, "status", "pushed");
    }

    /**
     * Operator asserts the decrement did NOT apply — re-enqueues exactly one fresh
     * single-attempt push, after this transaction commits (a rolled-back repush leaves the row
     * 'failed'/'failed_ambiguous' and enqueues nothing — the job would otherwise treat 'failed'
     * as retryable and push anyway). Resets the SAME claim row to 'pending' rather than inserting a
     * second (UNIQUE(session_id) stays intact by construction — this is an UPDATE, not an
     * INSERT). StockTakeShopifyPushJob.push() falls through its own guard for 'pending'
     * (same as a first attempt), so this needs no changes on the Phase B side at all.
     */
    @Transactional
    public Map<String, Object> repushSync(UUID sessionId, UUID actorUserId) {
        UUID tenantId = TenantContext.require();
        stockTake.requireSession(sessionId, tenantId);

        int rows = jdbc.update(
            "UPDATE stock_take_shopify_syncs SET status = 'pending', error = NULL " +
            "WHERE session_id = ? AND tenant_id = ? AND status IN ('failed', 'failed_ambiguous')",
            sessionId, tenantId);
        if (rows == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Re-push is only valid from failed or failed_ambiguous");
        }

        auditService.record(actorUserId, "stock_take_sync_repush",
            "stock_take_session", sessionId.toString(), null);
        ShopifyInventoryService.afterCommit(() -> jobScheduler.enqueue(() -> pushJob.push(sessionId, tenantId)));
        return Map.of("sessionId", sessionId, "status", "pending");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────
}
