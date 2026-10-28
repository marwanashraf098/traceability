package com.traceability.inventory;

import com.traceability.labels.FactoryReceivingService;
import com.traceability.labels.FlexibleLabeling;
import com.traceability.labels.LabelCodeException;
import com.traceability.labels.UntaggedException;

import com.traceability.tenancy.TenantContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

@Service
public class ReceivingService {

    private final JdbcTemplate           jdbc;
    private final InventoryLedger        ledger;
    private final ShopifyInventoryService shopifyInventory;
    /** Flexible labeling slice 6b — factory-labeled sessions (null: no factory mode, e.g. tests' hand-built service). */
    private final FactoryReceivingService factoryReceiving;

    public ReceivingService(JdbcTemplate jdbc, InventoryLedger ledger,
                            ShopifyInventoryService shopifyInventory) {
        this(jdbc, ledger, shopifyInventory, null);
    }

    @Autowired
    public ReceivingService(JdbcTemplate jdbc, InventoryLedger ledger,
                            ShopifyInventoryService shopifyInventory, FactoryReceivingService factoryReceiving) {
        this.jdbc             = jdbc;
        this.ledger           = ledger;
        this.shopifyInventory = shopifyInventory;
        this.factoryReceiving = factoryReceiving;
    }

    // ── Create ──────────────────────────────────────────────────────────────

    @Transactional
    public UUID createSession(UUID actorUserId, UUID locationId,
                              String reference, String supplierName, String note) {
        return createSession(actorUserId, locationId, reference, supplierName, note, MODE_PRINT_STICK);
    }

    /** Today's flow: a label printed for every piece at receiving. The default. */
    public static final String MODE_PRINT_STICK = "print_stick";
    /** Flexible labeling slice 2: no labels now — the pieces go in untagged (short_code NULL). */
    public static final String MODE_COUNT_ONLY  = "count_only";
    /** Flexible labeling slice 6b: the pieces arrived wearing a factory run's labels — scanned in, never picked. */
    public static final String MODE_FACTORY     = "factory";

    /**
     * {@code mode} null → print_stick. count_only only for a tenant with flexible labeling, and only
     * into the main warehouse (the fulfillment location — where packing labels untagged pieces).
     */
    @Transactional
    public UUID createSession(UUID actorUserId, UUID locationId,
                              String reference, String supplierName, String note, String mode) {
        return createSession(actorUserId, locationId, reference, supplierName, note, mode, null);
    }

    /**
     * {@code labelRunId}: factory mode only (and required there) — an OPEN factory run of this tenant. A factory
     * session receives into the main warehouse only; its supplier / reference default to the run's factory and
     * reference.
     */
    @Transactional
    public UUID createSession(UUID actorUserId, UUID locationId,
                              String reference, String supplierName, String note, String mode, UUID labelRunId) {
        UUID tenantId = TenantContext.require();
        String m = mode == null ? MODE_PRINT_STICK : mode;
        if (!MODE_PRINT_STICK.equals(m) && !MODE_COUNT_ONLY.equals(m) && !MODE_FACTORY.equals(m)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown receiving mode");
        }
        if (!MODE_FACTORY.equals(m) && labelRunId != null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Only a factory-labeled session names a factory run");
        }
        if (MODE_COUNT_ONLY.equals(m)) {
            if (!FlexibleLabeling.enabled(jdbc, tenantId)) throw UntaggedException.countOnlyUnavailable();
            if (!isMainWarehouse(locationId, tenantId)) throw UntaggedException.countOnlyMainWarehouse();
        }
        if (MODE_FACTORY.equals(m)) {
            if (factoryReceiving == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
            if (labelRunId == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A factory-labeled session needs a factory run");
            }
            Map<String, Object> run = factoryReceiving.requireOpenRun(tenantId, labelRunId);
            if (!isMainWarehouse(locationId, tenantId)) throw LabelCodeException.factoryMainWarehouseOnly();
            if (supplierName == null || supplierName.isBlank()) supplierName = (String) run.get("factory_name");
            if (reference == null || reference.isBlank()) reference = (String) run.get("reference");
        }
        UUID id = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO receipts (id, tenant_id, reference, supplier_name, received_by, location_id, note, status, mode, label_run_id) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, 'open', ?, ?)",
            id, tenantId, reference, supplierName, actorUserId, locationId, note, m, labelRunId);
        return id;
    }

    private boolean isMainWarehouse(UUID locationId, UUID tenantId) {
        if (locationId == null) return false;
        return Boolean.TRUE.equals(jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM locations WHERE id = ? AND tenant_id = ? AND is_fulfillment)",
            Boolean.class, locationId, tenantId));
    }

    // ── Lines ────────────────────────────────────────────────────────────────

    @Transactional
    public UUID addLine(UUID sessionId, UUID variantId, int quantity) {
        UUID tenantId = TenantContext.require();
        requireOpenWithLines(sessionId, tenantId);
        UUID id = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO receipt_lines (id, tenant_id, receipt_id, variant_id, quantity) VALUES (?, ?, ?, ?, ?)",
            id, tenantId, sessionId, variantId, quantity);
        return id;
    }

    @Transactional
    public void updateLine(UUID sessionId, UUID lineId, int quantity) {
        UUID tenantId = TenantContext.require();
        requireOpenWithLines(sessionId, tenantId);
        int rows = jdbc.update(
            "UPDATE receipt_lines SET quantity = ? " +
            "WHERE id = ? AND receipt_id = ? AND tenant_id = ?",
            quantity, lineId, sessionId, tenantId);
        if (rows == 0) throw notFound("line");
    }

    @Transactional
    public void deleteLine(UUID sessionId, UUID lineId) {
        UUID tenantId = TenantContext.require();
        requireOpenWithLines(sessionId, tenantId);
        jdbc.update(
            "DELETE FROM receipt_lines WHERE id = ? AND receipt_id = ? AND tenant_id = ?",
            lineId, sessionId, tenantId);
    }

    // ── Delete ───────────────────────────────────────────────────────────────

    /**
     * Deletes an OPEN receiving session and its lines. Rejects anything not
     * status='open' (a finalized session has generated pieces — real inventory
     * and custody — and must never be deletable). kind='inbound' is checked
     * explicitly here — as of FR-24 this is no longer the only kind-filtered
     * query: requireOpen() and getSession() below are also scoped to
     * kind='inbound' so no receipts row can be reached across the shared
     * table/id space with `returns`-kind rows, in either direction.
     */
    @Transactional
    public void deleteSession(UUID sessionId) {
        UUID tenantId = TenantContext.require();
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT status, mode FROM receipts WHERE id = ? AND tenant_id = ? AND kind = 'inbound' FOR UPDATE",
            sessionId, tenantId);
        if (rows.isEmpty()) throw notFound("session");
        if (!"open".equals(rows.get(0).get("status"))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Session is already finalized and cannot be deleted");
        }
        // Factory receiving (slice 6b): a staged scan is a record — undo them first; undone ones go with the session.
        if (MODE_FACTORY.equals(rows.get(0).get("mode")) && factoryReceiving != null) {
            int live = factoryReceiving.liveScans(tenantId, sessionId);
            if (live > 0) throw LabelCodeException.undoScansFirst(live);
        }
        jdbc.update("DELETE FROM receipt_lines WHERE receipt_id = ? AND tenant_id = ?",
            sessionId, tenantId);
        jdbc.update("DELETE FROM receipts WHERE id = ? AND tenant_id = ? AND kind = 'inbound'",
            sessionId, tenantId);
    }

    // ── Finalize ─────────────────────────────────────────────────────────────

    /**
     * Finalizes a receiving session: generates one ULID piece per unit across all
     * lines, then delegates to InventoryLedger.batchReceive() which inserts all
     * pieces and received events in two SQL round-trips inside one ACID transaction.
     *
     * All-or-nothing: if any INSERT fails (barcode uniqueness, FK) the entire
     * transaction rolls back and no partial session is committed.
     *
     * @return number of pieces created
     */
    @Transactional
    public int finalize(UUID sessionId, UUID actorUserId) {
        UUID tenantId = TenantContext.require();
        // Factory receiving (slice 6b) has its own finalize — dispatched BEFORE any lock here, because it locks the
        // run first and then the receipt (the order every factory path uses).
        if (factoryReceiving != null && MODE_FACTORY.equals(modeOf(sessionId, tenantId))) {
            return factoryReceiving.finalize(sessionId, actorUserId);
        }
        // Locked, not just read: two finalizes of one session at the same moment both saw 'open' and both
        // created the session's pieces. The second now waits here, then sees 'finalized' and gets the 409.
        requireOpen(sessionId, tenantId, true);

        // Fetch location_id for this session (needed per-piece).
        UUID locationId = jdbc.queryForObject(
            "SELECT location_id FROM receipts WHERE id = ? AND tenant_id = ?",
            UUID.class, sessionId, tenantId);
        boolean countOnly = MODE_COUNT_ONLY.equals(jdbc.queryForObject(
            "SELECT mode FROM receipts WHERE id = ? AND tenant_id = ?", String.class, sessionId, tenantId));
        if (locationId == null) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                "Session has no location set");
        }

        // Fetch lines: variantId × quantity.
        List<Map<String, Object>> lines = jdbc.queryForList(
            "SELECT variant_id, quantity FROM receipt_lines WHERE receipt_id = ? AND tenant_id = ?",
            sessionId, tenantId);

        if (lines.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                "Session has no lines — add items before finalizing");
        }

        // Build one ReceiveSpec per unit.
        List<InventoryLedger.ReceiveSpec> specs = new ArrayList<>();
        for (Map<String, Object> line : lines) {
            UUID variantId = (UUID) line.get("variant_id");
            int  qty       = (Integer) line.get("quantity");
            for (int i = 0; i < qty; i++) {
                specs.add(new InventoryLedger.ReceiveSpec(
                    UlidGenerator.generate(),
                    tenantId,
                    variantId,
                    sessionId,
                    locationId));
            }
        }

        // Count-only: the flag and the main warehouse are re-checked (either may have changed since
        // the session opened) — then the same pieces and events, with no short codes claimed.
        if (countOnly) {
            if (!FlexibleLabeling.enabled(jdbc, tenantId)) throw UntaggedException.countOnlyUnavailable();
            if (!isMainWarehouse(locationId, tenantId)) throw UntaggedException.countOnlyMainWarehouse();
        }

        // Two-INSERT atomic commit — all pieces + events or nothing.
        ledger.batchReceive(specs, actorUserId, !countOnly);

        // Mark session finalized — conditional on 'open', so a finalize that got past the check some other way
        // rolls back (pieces included) instead of finalizing twice.
        int finalized = jdbc.update(
            "UPDATE receipts SET status = 'finalized', finalized_at = now() " +
            "WHERE id = ? AND tenant_id = ? AND status = 'open'",
            sessionId, tenantId);
        if (finalized != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Session is already finalized and cannot be modified");
        }

        // Build variant→qty delta map and kick off async Shopify shadow sync (Trigger 1).
        // Fired AFTER this transaction commits (a rolled-back finalize never reaches Shopify, and
        // the claim is never created before its pieces are visible — the seed's cutoff relies on
        // that). Runs on a new thread; TenantContext.runAs() is the outer wrapper inside
        // onReceivingSessionClose — ThreadLocal does not propagate.
        Map<UUID, Integer> variantDeltaMap = new java.util.LinkedHashMap<>();
        for (Map<String, Object> line : lines) {
            UUID variantId = (UUID) line.get("variant_id");
            int  qty       = (Integer) line.get("quantity");
            variantDeltaMap.merge(variantId, qty, Integer::sum);
        }
        if (shopifyInventory != null) {
            ShopifyInventoryService.afterCommit(() ->
                shopifyInventory.onReceivingSessionClose(tenantId, sessionId, locationId, variantDeltaMap));
        }

        return specs.size();
    }

    // ── Queries ───────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public Map<String, Object> getSession(UUID sessionId) {
        UUID tenantId = TenantContext.require();
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT r.id, r.status, r.mode, r.reference, r.supplier_name, r.note, r.label_run_id, " +
            "       r.location_id, l.name AS location_name, " +
            "       r.received_by, r.created_at, r.finalized_at, " +
            "       (SELECT COUNT(*) FROM pieces p WHERE p.receipt_id = r.id) AS piece_count " +
            "FROM receipts r " +
            "LEFT JOIN locations l ON l.id = r.location_id " +
            "WHERE r.id = ? AND r.tenant_id = ? AND r.kind = 'inbound'",
            sessionId, tenantId);
        if (rows.isEmpty()) throw notFound("session");
        Map<String, Object> session = new LinkedHashMap<>(rows.get(0));
        session.put("lines", getLines(sessionId, tenantId));
        if (MODE_COUNT_ONLY.equals(session.get("mode")) && "finalized".equals(session.get("status"))) {
            session.put("shopify", shopifySync(sessionId, tenantId));
        }
        if (MODE_FACTORY.equals(session.get("mode")) && factoryReceiving != null) {
            session.put("factory", factoryReceiving.progress(tenantId, sessionId, (UUID) session.get("label_run_id")));
        }
        return session;
    }

    /**
     * The count-only finished screen's "Shopify" line: units of this session's receiving claims
     * (trigger receiving_session, keyed by the session) per outcome. No claim rows yet → pending,
     * the after-commit sync hasn't written them.
     */
    private Map<String, Object> shopifySync(UUID sessionId, UUID tenantId) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("applied", 0); out.put("pending", 0); out.put("failed", 0); out.put("skipped", 0);
        jdbc.query(
            "SELECT CASE WHEN status IN ('applied', 'superseded_by_seed') THEN 'applied' " +
            "            WHEN status IN ('shadow', 'pending') THEN 'pending' " +
            "            WHEN status = 'failed' THEN 'failed' ELSE 'skipped' END AS outcome, " +
            "       SUM(delta)::int AS units " +
            "FROM shopify_inventory_adjustments " +
            "WHERE tenant_id = ? AND trigger_type = 'receiving_session' AND trigger_id = ? " +
            "GROUP BY 1",
            rs -> { out.put(rs.getString("outcome"), rs.getInt("units")); },
            tenantId, sessionId.toString());
        return out;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listSessions() {
        UUID tenantId = TenantContext.require();
        return jdbc.queryForList(
            "SELECT r.id, r.status, r.mode, r.reference, r.supplier_name, r.created_at, r.finalized_at, r.label_run_id, " +
            "       l.name AS location_name, " +
            // An open factory session has no lines yet: its units are its live scans.
            "       CASE WHEN r.mode = 'factory' AND r.status = 'open' " +
            "            THEN (SELECT count(*) FROM receipt_factory_scans s WHERE s.receipt_id = r.id AND s.undone_at IS NULL) " +
            "            ELSE COALESCE((SELECT SUM(rl.quantity) FROM receipt_lines rl WHERE rl.receipt_id = r.id), 0) END AS line_units, " +
            "       (SELECT COUNT(*) FROM pieces p WHERE p.receipt_id = r.id) AS piece_count " +
            "FROM receipts r " +
            "LEFT JOIN locations l ON l.id = r.location_id " +
            "WHERE r.tenant_id = ? AND r.kind = 'inbound' " +
            "ORDER BY r.created_at DESC",
            tenantId);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> getSessionPieces(UUID sessionId) {
        UUID tenantId = TenantContext.require();
        return jdbc.queryForList(
            "SELECT p.id, p.barcode, p.status, v.title AS variant_title, pr.title AS product_title " +
            "FROM pieces p " +
            "JOIN variants v  ON v.id  = p.variant_id " +
            "JOIN products pr ON pr.id = v.product_id " +
            "WHERE p.receipt_id = ? AND p.tenant_id = ? " +
            "ORDER BY p.created_at",
            sessionId, tenantId);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> searchVariants(String query) {
        UUID tenantId = TenantContext.require();
        String pattern = "%" + query.toLowerCase() + "%";
        return jdbc.queryForList(
            "SELECT v.id, v.title, v.sku, p.title AS product_title " +
            "FROM variants v " +
            "JOIN products p ON p.id = v.product_id " +
            "WHERE v.tenant_id = ? AND p.status = 'active' " +
            "  AND (LOWER(v.sku) LIKE ? OR LOWER(v.title) LIKE ? OR LOWER(p.title) LIKE ?) " +
            "ORDER BY v.sku NULLS LAST, v.title " +
            "LIMIT 20",
            tenantId, pattern, pattern, pattern);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private List<Map<String, Object>> getLines(UUID sessionId, UUID tenantId) {
        return jdbc.queryForList(
            "SELECT rl.id, rl.variant_id, v.title AS variant_title, v.sku, " +
            "       p.title AS product_title, rl.quantity, " +
            "       (SELECT COUNT(*) FROM pieces pc " +
            "        WHERE pc.receipt_id = rl.receipt_id " +
            "          AND pc.variant_id = rl.variant_id " +
            "          AND pc.tenant_id  = rl.tenant_id) AS piece_count " +
            "FROM receipt_lines rl " +
            "JOIN variants v ON v.id = rl.variant_id " +
            "JOIN products p ON p.id = v.product_id " +
            "WHERE rl.receipt_id = ? AND rl.tenant_id = ? " +
            "ORDER BY rl.created_at",
            sessionId, tenantId);
    }

    private void requireOpen(UUID sessionId, UUID tenantId) {
        requireOpen(sessionId, tenantId, false);
    }

    /** Open, and not a factory session — factory receiving takes scans, never lines. */
    private void requireOpenWithLines(UUID sessionId, UUID tenantId) {
        requireOpen(sessionId, tenantId, false);
        if (MODE_FACTORY.equals(modeOf(sessionId, tenantId))) throw LabelCodeException.factorySessionNoLines();
    }

    private String modeOf(UUID sessionId, UUID tenantId) {
        List<String> mode = jdbc.queryForList(
            "SELECT mode FROM receipts WHERE id = ? AND tenant_id = ? AND kind = 'inbound'", String.class, sessionId, tenantId);
        return mode.isEmpty() ? null : mode.get(0);
    }

    private void requireOpen(UUID sessionId, UUID tenantId, boolean forUpdate) {
        List<String> rows = jdbc.queryForList(
            "SELECT status FROM receipts WHERE id = ? AND tenant_id = ? AND kind = 'inbound'" +
            (forUpdate ? " FOR UPDATE" : ""),
            String.class, sessionId, tenantId);
        if (rows.isEmpty()) throw notFound("session");
        if (!"open".equals(rows.get(0))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Session is already finalized and cannot be modified");
        }
    }

    private ResponseStatusException notFound(String what) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Receiving " + what + " not found");
    }
}
