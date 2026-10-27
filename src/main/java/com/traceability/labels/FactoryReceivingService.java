package com.traceability.labels;

import com.traceability.inventory.InventoryLedger;
import com.traceability.inventory.ScanInputNormalizer;
import com.traceability.inventory.ShopifyInventoryService;
import com.traceability.inventory.UlidGenerator;
import com.traceability.tenancy.TenantContext;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

/**
 * Flexible labeling slice 6b — FACTORY receiving: a receiving session (receipts.mode 'factory', V188) that receives
 * one factory run. The pieces arrived wearing the run's printed factory labels; each scan is STAGED
 * (receipt_factory_scans — at most one live row per code across every session), and only scanned pieces enter stock,
 * at finalize, with exactly the scanned codes.
 *
 * <ul>
 *   <li>{@link #scan}: one transaction; refusals are returned as outcomes (REJECTED:&lt;code&gt;, readable EN / AR), in
 *       this order — unknown code; a piece's own short code (already in stock); a pack / tag / replacement label; a
 *       voided label; a received (bound) label; another run's label; a label already staged here / in another
 *       session.</li>
 *   <li>{@link #undoLast}: the session's newest live scan gets undone_at (never deleted).</li>
 *   <li>{@link #extraLabel}: "Piece without a label" — one factory code for the chosen line of this run, flagged
 *       overage when the line is already full (received + staged everywhere ≥ expected). A flag, never a block.</li>
 *   <li>{@link #finalize}: run FOR UPDATE, receipt FOR UPDATE + open re-check, every staged code re-checked issued
 *       (any other → nothing is received), receipt_lines written, InventoryLedger.receiveFactoryLabeled (pieces created
 *       with the codes and the codes bound, one transaction), receipt finalized only while 'open', then the same
 *       Shopify receiving trigger as every receiving.</li>
 * </ul>
 * Lock order everywhere: run → receipt → codes (close-run, slice 6c, takes the run FOR UPDATE first too).
 * Caller contract: owner / manager (controller); 404 unless the tenant has flexible labeling.
 */
@Service
public class FactoryReceivingService {

    public record LineProgress(UUID lineId, int lineNo, UUID variantId, String productTitle, String variantTitle,
                               String sku, String imageUrl, int expected, int received, int staged) {}
    public record Progress(UUID runId, String factoryName, String reference, String runStatus, int staged,
                           List<LineProgress> lines) {}
    public record ScanOutcome(String outcome, String code, String messageEn, String messageAr, LineProgress line,
                              boolean overage, Progress progress, boolean viaPhone) {}
    public record UndoResult(String code, Progress progress) {}
    public record ExtraLabel(UUID codeId, String code, boolean overage, String productTitle, String variantTitle) {}

    private final JdbcTemplate jdbc;
    private final InventoryLedger ledger;
    private final LabelCodeService labelCodes;
    private final ShopifyInventoryService shopifyInventory;

    public FactoryReceivingService(JdbcTemplate jdbc, InventoryLedger ledger, LabelCodeService labelCodes,
                                   ShopifyInventoryService shopifyInventory) {
        this.jdbc = jdbc;
        this.ledger = ledger;
        this.labelCodes = labelCodes;
        this.shopifyInventory = shopifyInventory;
    }

    // ── progress ──────────────────────────────────────────────────────────────

    /**
     * Per run line: expected, received (the line's bound codes, every session) and staged (this session's live scans
     * whose code is still issued — so a finalized session shows none). Caller contract: TenantContext set.
     */
    public Progress progress(UUID tenantId, UUID receiptId, UUID runId) {
        List<Map<String, Object>> run = jdbc.queryForList(
            "SELECT factory_name, reference, status FROM label_runs WHERE id = ? AND tenant_id = ?", runId, tenantId);
        if (run.isEmpty()) throw LabelCodeException.runNotFound();
        List<LineProgress> lines = jdbc.query(
            "SELECT l.id, l.line_no, l.variant_id, l.expected_qty, pr.title AS product_title, v.title AS variant_title, " +
            "       v.sku, pr.image_url, " +
            "       (SELECT count(*)::int FROM label_codes c " +
            "         WHERE c.run_line_id = l.id AND c.tenant_id = l.tenant_id AND c.status = 'bound') AS received, " +
            "       (SELECT count(*)::int FROM receipt_factory_scans s " +
            "          JOIN label_codes c ON c.id = s.label_code_id AND c.tenant_id = s.tenant_id AND c.status = 'issued' " +
            "         WHERE s.run_line_id = l.id AND s.tenant_id = l.tenant_id AND s.receipt_id = ? " +
            "           AND s.undone_at IS NULL) AS staged " +
            "FROM label_run_lines l JOIN variants v ON v.id = l.variant_id JOIN products pr ON pr.id = v.product_id " +
            "WHERE l.run_id = ? AND l.tenant_id = ? ORDER BY l.line_no",
            (rs, i) -> new LineProgress(rs.getObject("id", UUID.class), rs.getInt("line_no"),
                rs.getObject("variant_id", UUID.class), rs.getString("product_title"), rs.getString("variant_title"),
                rs.getString("sku"), rs.getString("image_url"), rs.getInt("expected_qty"), rs.getInt("received"),
                rs.getInt("staged")),
            receiptId, runId, tenantId);
        Map<String, Object> r = run.get(0);
        return new Progress(runId, (String) r.get("factory_name"), (String) r.get("reference"), (String) r.get("status"),
            lines.stream().mapToInt(LineProgress::staged).sum(), lines);
    }

    // ── scan ──────────────────────────────────────────────────────────────────

    @Transactional
    public ScanOutcome scan(UUID receiptId, String raw, UUID actorUserId, boolean viaPhone) {
        UUID tenantId = TenantContext.require();
        FlexibleLabeling.require(jdbc, tenantId);
        UUID runId = lockOpenSession(tenantId, receiptId, "SHARE");
        String code = ScanInputNormalizer.normalizePiece(raw == null ? "" : raw);

        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT c.id, c.kind, c.status, c.run_line_id, c.overage, c.variant_id, l.run_id, r.factory_name, r.reference " +
            "FROM label_codes c " +
            "LEFT JOIN label_run_lines l ON l.id = c.run_line_id AND l.tenant_id = c.tenant_id " +
            "LEFT JOIN label_runs r ON r.id = l.run_id AND r.tenant_id = c.tenant_id " +
            "WHERE c.tenant_id = ? AND c.code = ? FOR UPDATE OF c",
            tenantId, code);
        if (rows.isEmpty()) {
            Integer piece = jdbc.queryForObject(
                "SELECT count(*)::int FROM pieces WHERE tenant_id = ? AND short_code = ?", Integer.class, tenantId, code);
            return rejected(tenantId, receiptId, runId, code, null, viaPhone,
                piece != null && piece > 0 ? LabelCodeException.pieceAlreadyInStock() : LabelCodeException.unknownCode());
        }
        Map<String, Object> c = rows.get(0);
        UUID codeId = (UUID) c.get("id");
        UUID lineId = (UUID) c.get("run_line_id");
        String kind = (String) c.get("kind");
        if (!"factory".equals(kind)) {
            return rejected(tenantId, receiptId, runId, code, null, viaPhone, LabelCodeException.notFactoryLabel(kind));
        }
        if ("voided".equals(c.get("status"))) {
            return rejected(tenantId, receiptId, runId, code, lineId, viaPhone, LabelCodeException.codeVoided());
        }
        if ("bound".equals(c.get("status"))) {
            return rejected(tenantId, receiptId, runId, code, lineId, viaPhone, LabelCodeException.alreadyReceived());
        }
        if (!runId.equals(c.get("run_id"))) {
            return rejected(tenantId, receiptId, runId, code, null, viaPhone,
                LabelCodeException.otherRun(runName((String) c.get("factory_name"), (String) c.get("reference"))));
        }
        LabelCodeException staged = stagedRefusal(tenantId, receiptId, codeId);
        if (staged != null) return rejected(tenantId, receiptId, runId, code, lineId, viaPhone, staged);

        // The partial UNIQUE (one live row per code) is the real guard; the code row lock above already serializes.
        List<UUID> inserted = jdbc.queryForList(
            "INSERT INTO receipt_factory_scans (tenant_id, receipt_id, label_code_id, run_line_id, variant_id, scanned_by, via_phone) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?) ON CONFLICT (label_code_id) WHERE undone_at IS NULL DO NOTHING RETURNING id",
            UUID.class, tenantId, receiptId, codeId, lineId, c.get("variant_id"), actorUserId, viaPhone);
        if (inserted.isEmpty()) {
            return rejected(tenantId, receiptId, runId, code, lineId, viaPhone, LabelCodeException.alreadyScanned());
        }
        Progress p = progress(tenantId, receiptId, runId);
        return new ScanOutcome("STAGED", code, null, null, line(p, lineId), Boolean.TRUE.equals(c.get("overage")), p, viaPhone);
    }

    private LabelCodeException stagedRefusal(UUID tenantId, UUID receiptId, UUID codeId) {
        List<Map<String, Object>> live = jdbc.queryForList(
            "SELECT s.receipt_id, r.reference, r.created_at FROM receipt_factory_scans s " +
            "JOIN receipts r ON r.id = s.receipt_id AND r.tenant_id = s.tenant_id " +
            "WHERE s.tenant_id = ? AND s.label_code_id = ? AND s.undone_at IS NULL",
            tenantId, codeId);
        if (live.isEmpty()) return null;
        if (receiptId.equals(live.get(0).get("receipt_id"))) return LabelCodeException.alreadyScanned();
        return LabelCodeException.scannedInOtherSession(sessionName(live.get(0)));
    }

    private ScanOutcome rejected(UUID tenantId, UUID receiptId, UUID runId, String code, UUID lineId, boolean viaPhone,
                                 LabelCodeException e) {
        Progress p = progress(tenantId, receiptId, runId);
        return new ScanOutcome("REJECTED:" + e.code().name(), code, e.messageEn(), e.messageAr(),
            lineId == null ? null : line(p, lineId), false, p, viaPhone);
    }

    private static LineProgress line(Progress p, UUID lineId) {
        return p.lines().stream().filter(l -> l.lineId().equals(lineId)).findFirst().orElse(null);
    }

    // ── undo ──────────────────────────────────────────────────────────────────

    /** Undoes this session's newest live scan. */
    @Transactional
    public UndoResult undoLast(UUID receiptId, UUID actorUserId) {
        UUID tenantId = TenantContext.require();
        FlexibleLabeling.require(jdbc, tenantId);
        UUID runId = lockOpenSession(tenantId, receiptId, "SHARE");
        List<UUID> undone = jdbc.queryForList(
            "UPDATE receipt_factory_scans SET undone_at = now(), undone_by = ? " +
            "WHERE id = (SELECT id FROM receipt_factory_scans WHERE tenant_id = ? AND receipt_id = ? AND undone_at IS NULL " +
            "            ORDER BY scanned_at DESC, id DESC LIMIT 1 FOR UPDATE) " +
            "  AND undone_at IS NULL RETURNING label_code_id",
            UUID.class, actorUserId, tenantId, receiptId);
        if (undone.isEmpty()) throw LabelCodeException.nothingToUndo();
        String code = jdbc.queryForObject("SELECT code FROM label_codes WHERE id = ? AND tenant_id = ?",
            String.class, undone.get(0), tenantId);
        return new UndoResult(code, progress(tenantId, receiptId, runId));
    }

    // ── piece without a label ─────────────────────────────────────────────────

    /**
     * One factory code for the run line of {@code variantId}. Overage when the line is already full: its received
     * pieces plus every live staged scan of it (any session) reach the expected quantity. A repeated
     * {@code idempotencyKey} returns the code it already made.
     */
    @Transactional
    public ExtraLabel extraLabel(UUID receiptId, UUID variantId, String idempotencyKey, UUID actorUserId) {
        UUID tenantId = TenantContext.require();
        FlexibleLabeling.require(jdbc, tenantId);
        String clientKey = idempotencyKey == null ? "" : idempotencyKey.strip();
        if (clientKey.isEmpty() || clientKey.length() > 50) throw LabelCodeException.invalidKey();
        UUID runId = lockOpenSession(tenantId, receiptId, "UPDATE");   // a print: the run's row lock, like LabelRunService.print
        List<Map<String, Object>> line = jdbc.queryForList(
            "SELECT l.id, l.expected_qty, pr.title AS product_title, v.title AS variant_title " +
            "FROM label_run_lines l JOIN variants v ON v.id = l.variant_id JOIN products pr ON pr.id = v.product_id " +
            "WHERE l.run_id = ? AND l.tenant_id = ? AND l.variant_id = ?",
            runId, tenantId, variantId);
        if (line.isEmpty()) {
            throw new LabelCodeException(HttpStatus.BAD_REQUEST, LabelCodeException.Code.UNKNOWN_VARIANT,
                "This product isn't in this factory run", "هذا المنتج ليس ضمن دفعة المصنع هذه");
        }
        Map<String, Object> l = line.get(0);
        UUID lineId = (UUID) l.get("id");
        String key = "extra-" + receiptId + "-" + clientKey;

        List<Map<String, Object>> replay = jdbc.queryForList(
            "SELECT c.id, c.code, c.overage, c.run_line_id FROM label_code_batches b " +
            "JOIN label_codes c ON c.batch_id = b.id AND c.tenant_id = b.tenant_id " +
            "WHERE b.tenant_id = ? AND b.idempotency_key = ?", tenantId, key);
        if (!replay.isEmpty()) {
            Map<String, Object> r = replay.get(0);
            if (!lineId.equals(r.get("run_line_id"))) throw LabelCodeException.keyReused();
            return new ExtraLabel((UUID) r.get("id"), (String) r.get("code"), Boolean.TRUE.equals(r.get("overage")),
                (String) l.get("product_title"), (String) l.get("variant_title"));
        }

        Integer here = jdbc.queryForObject(
            "SELECT (SELECT count(*)::int FROM label_codes c " +
            "         WHERE c.run_line_id = ? AND c.tenant_id = ? AND c.status = 'bound') + " +
            "       (SELECT count(*)::int FROM receipt_factory_scans s " +
            "          JOIN label_codes c ON c.id = s.label_code_id AND c.tenant_id = s.tenant_id AND c.status = 'issued' " +
            "         WHERE s.run_line_id = ? AND s.tenant_id = ? AND s.undone_at IS NULL)",
            Integer.class, lineId, tenantId, lineId, tenantId);
        boolean overage = here != null && here >= (Integer) l.get("expected_qty");
        LabelCodeService.IssuedCode issued = labelCodes.issueFactoryBatch(tenantId, actorUserId, runId,
            List.of(new LabelCodeService.FactoryLine(lineId, variantId, 1, overage)), key).get(0);
        return new ExtraLabel(issued.id(), issued.code(), overage, (String) l.get("product_title"),
            (String) l.get("variant_title"));
    }

    /** The PDF of one code printed by {@link #extraLabel} for this session's run (an issued code only). */
    @Transactional(readOnly = true)
    public byte[] extraLabelPdf(UUID receiptId, UUID codeId) throws java.io.IOException {
        UUID tenantId = TenantContext.require();
        FlexibleLabeling.require(jdbc, tenantId);
        Integer ofRun = jdbc.queryForObject(
            "SELECT count(*)::int FROM label_codes c JOIN label_run_lines l ON l.id = c.run_line_id AND l.tenant_id = c.tenant_id " +
            "JOIN receipts r ON r.label_run_id = l.run_id AND r.tenant_id = l.tenant_id " +
            "WHERE c.id = ? AND c.tenant_id = ? AND r.id = ?", Integer.class, codeId, tenantId, receiptId);
        if (ofRun == null || ofRun == 0) throw LabelCodeException.unknownCode();
        return labelCodes.codesPdf(List.of(codeId));
    }

    // ── finalize ──────────────────────────────────────────────────────────────

    /**
     * Receives the session's staged pieces — all or nothing. Called by ReceivingService.finalize inside its
     * transaction, BEFORE that method takes any lock of its own (lock order run → receipt).
     *
     * @return the number of pieces created
     */
    @Transactional
    public int finalize(UUID receiptId, UUID actorUserId) {
        UUID tenantId = TenantContext.require();
        FlexibleLabeling.require(jdbc, tenantId);
        List<UUID> runIds = jdbc.queryForList(
            "SELECT label_run_id FROM receipts WHERE id = ? AND tenant_id = ? AND kind = 'inbound' AND mode = 'factory'",
            UUID.class, receiptId, tenantId);
        if (runIds.isEmpty()) throw LabelCodeException.notFactorySession();
        UUID runId = runIds.get(0);
        // 1. The run: two sessions of one run finalize one after the other; finalize and close-run never overlap.
        List<String> runStatus = jdbc.queryForList(
            "SELECT status FROM label_runs WHERE id = ? AND tenant_id = ? FOR UPDATE", String.class, runId, tenantId);
        if (runStatus.isEmpty()) throw LabelCodeException.runNotFound();
        if (!"open".equals(runStatus.get(0))) throw LabelCodeException.runClosed();
        // 2. The receipt, locked and re-checked open (a second finalize waits here, then gets the 409).
        Map<String, Object> receipt = jdbc.queryForMap(
            "SELECT status, location_id FROM receipts WHERE id = ? AND tenant_id = ? FOR UPDATE", receiptId, tenantId);
        if (!"open".equals(receipt.get("status"))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Session is already finalized and cannot be modified");
        }
        UUID locationId = (UUID) receipt.get("location_id");
        requireMainWarehouse(tenantId, locationId);

        // 3. The staged codes, locked and re-checked: a code voided or bound since its scan refuses everything.
        List<Map<String, Object>> scans = jdbc.queryForList(
            "SELECT s.label_code_id, s.variant_id, c.code, c.status FROM receipt_factory_scans s " +
            "JOIN label_codes c ON c.id = s.label_code_id AND c.tenant_id = s.tenant_id " +
            "WHERE s.tenant_id = ? AND s.receipt_id = ? AND s.undone_at IS NULL " +
            "ORDER BY s.scanned_at, s.id FOR UPDATE OF c",
            tenantId, receiptId);
        if (scans.isEmpty()) throw LabelCodeException.noFactoryScans();
        List<Map<String, Object>> changed = new ArrayList<>();
        for (Map<String, Object> s : scans) {
            if (!"issued".equals(s.get("status"))) changed.add(Map.of("code", s.get("code"), "status", s.get("status")));
        }
        if (!changed.isEmpty()) throw LabelCodeException.factoryCodesChanged(changed);

        // 4. receipt_lines (variant × pieces) — the session view, piece list and Shopify map read them unchanged.
        Map<UUID, Integer> perVariant = new LinkedHashMap<>();
        List<InventoryLedger.FactoryReceiveSpec> specs = new ArrayList<>(scans.size());
        for (Map<String, Object> s : scans) {
            UUID variantId = (UUID) s.get("variant_id");
            perVariant.merge(variantId, 1, Integer::sum);
            specs.add(new InventoryLedger.FactoryReceiveSpec(UlidGenerator.generate(), tenantId, variantId, receiptId,
                locationId, (UUID) s.get("label_code_id"), (String) s.get("code")));
        }
        perVariant.forEach((variantId, qty) -> jdbc.update(
            "INSERT INTO receipt_lines (id, tenant_id, receipt_id, variant_id, quantity) VALUES (?, ?, ?, ?, ?)",
            UUID.randomUUID(), tenantId, receiptId, variantId, qty));

        // 5. Pieces with the scanned codes + the codes bound (ledger, all or nothing).
        ledger.receiveFactoryLabeled(specs, actorUserId, runId);

        // 6. Finalized only while open.
        int finalized = jdbc.update(
            "UPDATE receipts SET status = 'finalized', finalized_at = now() WHERE id = ? AND tenant_id = ? AND status = 'open'",
            receiptId, tenantId);
        if (finalized != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Session is already finalized and cannot be modified");
        }

        // 7. Shopify +N per variant — the existing receiving trigger, after commit (key = this session).
        if (shopifyInventory != null) {
            ShopifyInventoryService.afterCommit(() ->
                shopifyInventory.onReceivingSessionClose(tenantId, receiptId, locationId, perVariant));
        }
        return specs.size();
    }

    // ── create / delete support (ReceivingService) ───────────────────────────

    /** An OPEN run of this tenant for a new factory session (its row share-locked); its factory name and reference. */
    public Map<String, Object> requireOpenRun(UUID tenantId, UUID runId) {
        FlexibleLabeling.require(jdbc, tenantId);
        if (runId == null) throw LabelCodeException.runNotFound();
        List<Map<String, Object>> run = jdbc.queryForList(
            "SELECT status, factory_name, reference FROM label_runs WHERE id = ? AND tenant_id = ? FOR SHARE", runId, tenantId);
        if (run.isEmpty()) throw LabelCodeException.runNotFound();
        if (!"open".equals(run.get(0).get("status"))) throw LabelCodeException.runClosed();
        return run.get(0);
    }

    /** Live scans of a session (delete refuses while any). */
    public int liveScans(UUID tenantId, UUID receiptId) {
        Integer n = jdbc.queryForObject(
            "SELECT count(*)::int FROM receipt_factory_scans WHERE tenant_id = ? AND receipt_id = ? AND undone_at IS NULL",
            Integer.class, tenantId, receiptId);
        return n == null ? 0 : n;
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /**
     * Locks the session's run ({@code runLock} SHARE for a scan / undo, UPDATE for a print) and then the receipt, and
     * checks: an inbound factory session, open, its run open. Returns the run id.
     */
    private UUID lockOpenSession(UUID tenantId, UUID receiptId, String runLock) {
        List<Map<String, Object>> r = jdbc.queryForList(
            "SELECT mode, label_run_id FROM receipts WHERE id = ? AND tenant_id = ? AND kind = 'inbound'", receiptId, tenantId);
        if (r.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Receiving session not found");
        if (!"factory".equals(r.get(0).get("mode"))) throw LabelCodeException.notFactorySession();
        UUID runId = (UUID) r.get(0).get("label_run_id");
        List<String> runStatus = jdbc.queryForList(
            "SELECT status FROM label_runs WHERE id = ? AND tenant_id = ? FOR " + runLock, String.class, runId, tenantId);
        if (runStatus.isEmpty()) throw LabelCodeException.runNotFound();
        if (!"open".equals(runStatus.get(0))) throw LabelCodeException.runClosed();
        List<String> status = jdbc.queryForList(
            "SELECT status FROM receipts WHERE id = ? AND tenant_id = ? FOR SHARE", String.class, receiptId, tenantId);
        if (status.isEmpty() || !"open".equals(status.get(0))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Session is already finalized and cannot be modified");
        }
        return runId;
    }

    private void requireMainWarehouse(UUID tenantId, UUID locationId) {
        Boolean main = locationId != null && Boolean.TRUE.equals(jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM locations WHERE id = ? AND tenant_id = ? AND is_fulfillment)",
            Boolean.class, locationId, tenantId));
        if (!main) throw LabelCodeException.factoryMainWarehouseOnly();
    }

    /** "factory · reference", or the factory alone. */
    public static String runName(String factory, String reference) {
        return reference == null || reference.isBlank() ? factory : factory + " · " + reference;
    }

    private static String sessionName(Map<String, Object> receipt) {
        Object ref = receipt.get("reference");
        if (ref != null && !ref.toString().isBlank()) return ref.toString();
        Object at = receipt.get("created_at");
        return at == null ? "—" : at.toString().substring(0, Math.min(16, at.toString().length())).replace('T', ' ');
    }
}
