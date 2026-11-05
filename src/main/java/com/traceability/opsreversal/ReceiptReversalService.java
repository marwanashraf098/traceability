package com.traceability.opsreversal;

import com.traceability.account.AuditService;
import com.traceability.integrations.shopify.ShopifyGateway;
import com.traceability.integrations.shopify.ShopifyTokenProvider;
import com.traceability.integrations.shopify.StoreRepository;
import com.traceability.inventory.IncrementRecoveryRules;
import com.traceability.inventory.InventoryItemIdService;
import com.traceability.inventory.PieceAdjustService;
import com.traceability.inventory.PieceShopifyRules;
import com.traceability.inventory.ShopifyInventoryService;
import com.traceability.tenancy.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * ONE-OFF admin reversal of ONE finalized receiving session (approved by Marawan 2026-11-05): Floe Wear receipt
 * PO-01 (947d7c0f…), 1,113 pieces / 23 variants, received by mistake. Undone "as if it never arrived" without
 * deleting history. REMOVE this package (and PieceAdjustService.voidPieceSendLater) after the run.
 *
 * <ul>
 *   <li>Hard-wired to {@link #TENANT_ID} / {@link #RECEIPT_ID} — any other receipt is refused; receipt c5002445
 *       (2 Nov) is never read for a write.</li>
 *   <li>Every piece goes through {@link PieceAdjustService#voidPieceSendLater} — the existing void (InventoryLedger
 *       available → voided, one piece_events row each, reason 'other' + {@link #NOTE}, actor = the given tenant user)
 *       and its existing void_correction claim (claim-before-call in the same transaction, skips recorded with a
 *       reason). No raw status update, no delete, no new decrement path.</li>
 *   <li>The claims are sent one at a time, paced ({@code pace-ms}), through
 *       {@link ShopifyInventoryService#pushPieceClaimNow} — the same single-attempt send the void's after-commit hook
 *       uses; failed_ambiguous is never re-sent; a definite failure is re-sent (by later passes here, or the
 *       piece-sync sweep) only while attempts remain.</li>
 *   <li>Guard (whole run before any write, and again per variant inside its transaction, rows locked): every piece of
 *       the receipt is 'available', no current_order_id, no bin, tagged, at the receipt's location, and has no event
 *       but 'received' — or was already voided by this reversal (a resumed run). Anything else aborts.</li>
 *   <li>Shopify: a variant whose current "available" at Main is below its expected decrement is flagged; apply
 *       refuses unless {@code allowNegative}.</li>
 *   <li>Receipt → status 'reversed' once every piece is voided; one audit_log row 'receipt_reversed' carrying the
 *       requesting Traced person's user id.</li>
 * </ul>
 * Runs in a background thread (the run is long); GET the report for progress. Logs are prefixed RECEIPT_REVERSAL.
 */
@Service
public class ReceiptReversalService {

    private static final Logger log = LoggerFactory.getLogger(ReceiptReversalService.class);

    public static final UUID TENANT_ID  = UUID.fromString("caec9b94-78ab-489b-b18d-c9370728ad6f");
    public static final UUID RECEIPT_ID = UUID.fromString("947d7c0f-75c7-44c9-b240-251c70d80e17");
    public static final String REASON = "other";
    public static final String NOTE = "receiving session reversed by Traced support (Marawan), at merchant's request";

    static final String OK = "ok";
    static final String DONE = "already_reversed";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final TransactionTemplate readTx;
    private final PieceAdjustService adjust;
    private final ShopifyInventoryService inventory;
    private final AuditService audit;
    private final StoreRepository stores;
    private final ShopifyTokenProvider tokens;
    private final ShopifyGateway shopify;
    private final InventoryItemIdService itemIds;

    private long paceMs = 400;
    private long retryWaitMs = 30_000;
    private int retryPasses = PieceShopifyRules.MAX_ATTEMPTS - 1;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicReference<Map<String, Object>> report = new AtomicReference<>(Map.of("phase", "idle"));

    public ReceiptReversalService(JdbcTemplate jdbc, PlatformTransactionManager txm, PieceAdjustService adjust,
                                  ShopifyInventoryService inventory, AuditService audit, StoreRepository stores,
                                  ShopifyTokenProvider tokens, ShopifyGateway shopify) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txm);
        this.tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.readTx = new TransactionTemplate(txm);
        this.readTx.setReadOnly(true);
        this.adjust = adjust;
        this.inventory = inventory;
        this.audit = audit;
        this.stores = stores;
        this.tokens = tokens;
        this.shopify = shopify;
        this.itemIds = new InventoryItemIdService(jdbc, txm, shopify);
    }

    @Value("${traced.ops.receipt-reversal.pace-ms:400}")
    public void setPaceMs(long v) { this.paceMs = v; }

    @Value("${traced.ops.receipt-reversal.retry-wait-ms:30000}")
    public void setRetryWaitMs(long v) { this.retryWaitMs = v; }

    // ── Entry points ────────────────────────────────────────────────────────────

    public record Request(UUID receiptId, UUID actorUserId, UUID requestedByUserId, boolean apply,
                          boolean allowNegative) {}

    /** Starts a dry run or an apply in the background. 409 while one is running. */
    public Map<String, Object> start(Request req) {
        if (!RECEIPT_ID.equals(req.receiptId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "This one-off only reverses receipt " + RECEIPT_ID);
        }
        if (req.actorUserId() == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "actorUserId is required");
        if (req.apply() && req.requestedByUserId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "requestedByUserId is required to apply");
        }
        if (!running.compareAndSet(false, true)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A run is already in progress — GET the report");
        }
        Map<String, Object> r = newReport(req);
        report.set(r);
        Thread t = new Thread(() -> {
            try {
                TenantContext.runAs(TENANT_ID, () -> run(req, r));
            } catch (Throwable e) {
                log.error("RECEIPT_REVERSAL run failed", e);
                r.put("phase", "error");
                r.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
            } finally {
                r.put("finished_at", Instant.now().toString());
                running.set(false);
            }
        }, "receipt-reversal");
        t.setDaemon(true);
        t.start();
        return Map.of("started", true, "mode", req.apply() ? "apply" : "dry_run");
    }

    /** The current / last run's report, with live claim outcomes per variant. */
    public Map<String, Object> report() {
        Map<String, Object> r = new LinkedHashMap<>(report.get());
        r.put("running", running.get());
        try {
            TenantContext.runAs(TENANT_ID, () -> {
                r.put("receipt_status", readTx.execute(s -> jdbc.queryForObject(
                    "SELECT status FROM receipts WHERE id = ? AND tenant_id = ?", String.class, RECEIPT_ID, TENANT_ID)));
                r.put("shopify", claimOutcomes());
            });
        } catch (Exception e) {
            r.put("report_error", e.getMessage());
        }
        return r;
    }

    // ── The run ───────────────────────────────────────────────────────────────────

    private void run(Request req, Map<String, Object> r) {
        r.put("phase", "planning");
        String receiptStatus = receiptStatus();
        r.put("receipt_status_at_start", receiptStatus);
        if (!"finalized".equals(receiptStatus) && !"reversed".equals(receiptStatus)) {
            stop(r, "RECEIPT_NOT_FINALIZED", "Receipt status is " + receiptStatus);
            return;
        }
        String actorCheck = readTx.execute(s -> jdbc.query(
            "SELECT CASE WHEN NOT active THEN 'inactive' WHEN role::text NOT IN ('owner', 'manager') THEN 'role' ELSE 'ok' END " +
            "FROM users WHERE id = ? AND tenant_id = ?",
            rs -> rs.next() ? rs.getString(1) : "not_in_tenant", req.actorUserId(), TENANT_ID));
        if (!"ok".equals(actorCheck)) {
            stop(r, "ACTOR_INVALID", "actorUserId must be an active owner / manager of the tenant (" + actorCheck + ")");
            return;
        }

        List<Map<String, Object>> plan = plan();
        r.put("variants", plan);
        r.put("totals", totals(plan));
        boolean blocked = plan.stream().anyMatch(v -> ((Number) v.get("blocked")).intValue() > 0);
        List<String> negative = plan.stream().filter(v -> Boolean.TRUE.equals(v.get("would_go_negative")))
            .map(v -> (String) v.get("sku")).toList();
        List<String> unread = plan.stream().filter(v -> v.get("shopify_available") == null
                && ((Number) v.get("expected_shopify_delta")).intValue() != 0)
            .map(v -> (String) v.get("sku")).toList();
        r.put("would_go_negative", negative);
        r.put("shopify_unread", unread);
        logPlan(plan);

        if (!req.apply()) { r.put("phase", "dry_run_done"); return; }

        if (blocked) { stop(r, "GUARD_FAILED", "A piece of the receipt is no longer untouched — see variants[].blocked_pieces"); return; }
        if ((!negative.isEmpty() || !unread.isEmpty()) && !req.allowNegative()) {
            stop(r, "WOULD_GO_NEGATIVE", "Shopify available at Main is below the decrement (or unreadable) for " +
                negative + unread + " — pass allowNegative=true to go ahead anyway");
            return;
        }

        // Void, one transaction per variant, guard re-checked inside it with the rows locked.
        r.put("phase", "voiding");
        Map<String, Integer> voided = new LinkedHashMap<>();
        r.put("voided", voided);
        for (Map<String, Object> v : plan) {
            UUID variantId = (UUID) v.get("variant_id");
            String sku = (String) v.get("sku");
            try {
                int n = tx.execute(s -> voidVariant(variantId, req.actorUserId()));
                voided.put(sku, n);
                log.info("RECEIPT_REVERSAL voided variant={} sku={} pieces={}", variantId, sku, n);
            } catch (GuardException e) {
                stop(r, "GUARD_FAILED", "Variant " + sku + ": " + e.getMessage() + " — nothing of this variant was voided; "
                    + "variants before it were. Re-run apply after checking.");
                return;
            }
        }

        // The receipt itself + the audit row naming the person who asked.
        r.put("phase", "marking_receipt");
        Map<String, Object> marked = tx.execute(s -> markReversed(req, plan, voided));
        r.put("receipt", marked);

        r.put("phase", "sending");
        sendAll(r);
        r.put("phase", settled() ? "done" : "sent_not_settled");
        log.info("RECEIPT_REVERSAL finished phase={} outcomes={}", r.get("phase"), claimOutcomes());
    }

    private static final class GuardException extends RuntimeException {
        GuardException(String m) { super(m); }
    }

    private String receiptStatus() {
        return readTx.execute(s -> jdbc.query(
            "SELECT status FROM receipts WHERE id = ? AND tenant_id = ? AND kind = 'inbound'",
            rs -> rs.next() ? rs.getString(1) : "missing", RECEIPT_ID, TENANT_ID));
    }

    // ── Guard ─────────────────────────────────────────────────────────────────────

    /** One row per piece of the receipt (optionally one variant): id, variant_id, verdict. */
    private List<Map<String, Object>> guardRows(UUID variantId) {
        return jdbc.queryForList(
            "SELECT p.id, p.variant_id, " +
            "  CASE WHEN p.status = 'voided' AND ev.non_received = 1 AND ev.reversal_voids = 1 THEN '" + DONE + "' " +
            "       WHEN p.status <> 'available' THEN 'status_' || p.status::text " +
            "       WHEN p.current_order_id IS NOT NULL THEN 'has_current_order' " +
            "       WHEN p.bin_id IS NOT NULL THEN 'in_bin' " +
            "       WHEN p.short_code IS NULL THEN 'untagged' " +
            "       WHEN p.current_location_id IS DISTINCT FROM COALESCE(r.location_id, p.current_location_id) " +
            "            THEN 'not_at_receipt_location' " +
            "       WHEN ev.non_received > 0 THEN 'has_events_besides_received' " +
            "       ELSE '" + OK + "' END AS verdict " +
            "FROM pieces p " +
            "JOIN receipts r ON r.id = p.receipt_id AND r.tenant_id = p.tenant_id " +
            "LEFT JOIN LATERAL (SELECT count(*) FILTER (WHERE e.event_type <> 'received') AS non_received, " +
            "                          count(*) FILTER (WHERE e.event_type = 'voided' AND e.metadata->>'note' = ?) AS reversal_voids " +
            "                   FROM piece_events e WHERE e.piece_id = p.id AND e.tenant_id = p.tenant_id) ev ON true " +
            "WHERE p.tenant_id = ? AND p.receipt_id = ? AND (CAST(? AS uuid) IS NULL OR p.variant_id = CAST(? AS uuid)) " +
            "ORDER BY p.variant_id, p.created_at, p.id",
            NOTE, TENANT_ID, RECEIPT_ID, variantId, variantId);
    }

    private int voidVariant(UUID variantId, UUID actor) {
        // Lock the variant's pieces of this receipt first, then re-check every one of them inside this transaction.
        jdbc.queryForList("SELECT id FROM pieces WHERE tenant_id = ? AND receipt_id = ? AND variant_id = ? " +
            "ORDER BY id FOR UPDATE", String.class, TENANT_ID, RECEIPT_ID, variantId);
        List<Map<String, Object>> rows = guardRows(variantId);
        List<String> bad = rows.stream().filter(x -> !OK.equals(x.get("verdict")) && !DONE.equals(x.get("verdict")))
            .map(x -> x.get("id") + "=" + x.get("verdict")).limit(10).toList();
        if (!bad.isEmpty()) throw new GuardException("guard failed for " + bad);
        int n = 0;
        for (Map<String, Object> x : rows) {
            if (!OK.equals(x.get("verdict"))) continue;
            adjust.voidPieceSendLater((String) x.get("id"), REASON, NOTE, actor);
            n++;
        }
        return n;
    }

    // ── Plan (read-only) ──────────────────────────────────────────────────────────

    private List<Map<String, Object>> plan() {
        List<Map<String, Object>> variants = readTx.execute(s -> jdbc.queryForList(
            "SELECT p.variant_id, v.sku, pr.title AS product, v.title AS variant, v.external_id, " +
            "       v.shopify_inventory_item_id, count(*)::int AS pieces " +
            "FROM pieces p JOIN variants v ON v.id = p.variant_id AND v.tenant_id = p.tenant_id " +
            "JOIN products pr ON pr.id = v.product_id AND pr.tenant_id = v.tenant_id " +
            "WHERE p.tenant_id = ? AND p.receipt_id = ? " +
            "GROUP BY p.variant_id, v.sku, pr.title, v.title, v.external_id, v.shopify_inventory_item_id " +
            "ORDER BY v.sku NULLS LAST, p.variant_id", TENANT_ID, RECEIPT_ID));
        Map<UUID, Integer> available = shopifyAvailable(variants);

        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> v : variants) {
            UUID variantId = (UUID) v.get("variant_id");
            Map<String, Object> row = readTx.execute(s -> {
                Map<String, Object> m = new LinkedHashMap<>();
                List<Map<String, Object>> rows = guardRows(variantId);
                int ok = 0, done = 0, decrement = 0;
                List<String> blockedPieces = new ArrayList<>();
                Map<String, Integer> skips = new TreeMap<>();
                for (Map<String, Object> x : rows) {
                    String verdict = (String) x.get("verdict");
                    if (DONE.equals(verdict)) { done++; continue; }
                    if (!OK.equals(verdict)) { blockedPieces.add(x.get("id") + "=" + verdict); continue; }
                    ok++;
                    PieceShopifyRules.Verdict sv = PieceShopifyRules.countedAtMain(jdbc, TENANT_ID, (String) x.get("id"));
                    if (sv.write()) decrement++; else skips.merge(sv.skipReason(), 1, Integer::sum);
                }
                m.put("variant_id", variantId);
                m.put("sku", v.get("sku"));
                m.put("title", v.get("product") + " — " + v.get("variant"));
                m.put("pieces", v.get("pieces"));
                m.put("to_void", ok);
                m.put("already_voided", done);
                m.put("blocked", blockedPieces.size());
                m.put("blocked_pieces", blockedPieces.stream().limit(10).toList());
                m.put("expected_shopify_delta", -decrement);
                m.put("shopify_skips", skips);
                return m;
            });
            Integer avail = available.get(variantId);
            row.put("shopify_available", avail);
            int dec = -((Number) row.get("expected_shopify_delta")).intValue();
            row.put("would_go_negative", avail != null && avail < dec);
            out.add(row);
        }
        return out;
    }

    /** variant → current "available" at the Traced Main location (read-only). A failed read leaves the map empty. */
    private Map<UUID, Integer> shopifyAvailable(List<Map<String, Object>> variants) {
        Map<UUID, Integer> out = new HashMap<>();
        try {
            StoreRepository.Store store = stores.findActiveStoreByTenant(TENANT_ID).orElse(null);
            String gid = readTx.execute(s -> jdbc.query(
                "SELECT shopify_location_id FROM locations WHERE tenant_id = ? AND is_fulfillment = true",
                rs -> rs.next() ? rs.getString(1) : null, TENANT_ID));
            if (store == null || gid == null) { log.warn("RECEIPT_REVERSAL Shopify read: no store / main not linked"); return out; }
            String token = tokens.getValidToken(store.id());
            List<InventoryItemIdService.VariantRef> refs = new ArrayList<>();
            for (Map<String, Object> v : variants) {
                refs.add(new InventoryItemIdService.VariantRef((UUID) v.get("variant_id"), (String) v.get("external_id"),
                    (String) v.get("shopify_inventory_item_id")));
            }
            Map<UUID, String> items = new HashMap<>();
            for (InventoryItemIdService.VariantRef ref : refs) {
                if (ref.inventoryItemGid() != null && !ref.inventoryItemGid().isBlank()) items.put(ref.id(), ref.inventoryItemGid());
            }
            if (items.size() < refs.size()) items.putAll(itemIds.resolveAll(TENANT_ID, refs, store.shopDomain(), token));
            Map<String, Integer> byItem = new HashMap<>();
            for (ShopifyGateway.MainLevel l : shopify.fetchMainLevels(store.shopDomain(), token, gid,
                    items.values().stream().filter(Objects::nonNull).distinct().toList())) {
                byItem.put(l.inventoryItemGid(), l.available());
            }
            items.forEach((variant, item) -> { if (byItem.containsKey(item)) out.put(variant, byItem.get(item)); });
        } catch (Exception e) {
            log.warn("RECEIPT_REVERSAL Shopify available read failed: {}", e.toString());
        }
        return out;
    }

    private static Map<String, Object> totals(List<Map<String, Object>> plan) {
        Map<String, Object> t = new LinkedHashMap<>();
        for (String k : List.of("pieces", "to_void", "already_voided", "blocked", "expected_shopify_delta")) {
            t.put(k, plan.stream().mapToInt(v -> ((Number) v.get(k)).intValue()).sum());
        }
        t.put("variants", plan.size());
        return t;
    }

    private void logPlan(List<Map<String, Object>> plan) {
        for (Map<String, Object> v : plan) {
            log.info("RECEIPT_REVERSAL plan sku={} pieces={} to_void={} already_voided={} blocked={} shopify_delta={} " +
                     "shopify_available={} would_go_negative={} skips={}",
                v.get("sku"), v.get("pieces"), v.get("to_void"), v.get("already_voided"), v.get("blocked"),
                v.get("expected_shopify_delta"), v.get("shopify_available"), v.get("would_go_negative"), v.get("shopify_skips"));
        }
    }

    // ── Receipt ───────────────────────────────────────────────────────────────────

    private Map<String, Object> markReversed(Request req, List<Map<String, Object>> plan, Map<String, Integer> voided) {
        jdbc.queryForList("SELECT id FROM receipts WHERE id = ? AND tenant_id = ? FOR UPDATE", UUID.class, RECEIPT_ID, TENANT_ID);
        long notDone = guardRows(null).stream().filter(x -> !DONE.equals(x.get("verdict"))).count();
        if (notDone > 0) throw new IllegalStateException(notDone + " piece(s) of the receipt are not voided by this reversal");
        int n = jdbc.update("UPDATE receipts SET status = 'reversed' WHERE id = ? AND tenant_id = ? AND status = 'finalized'",
            RECEIPT_ID, TENANT_ID);
        if (n == 1) {
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("requested_by_user_id", req.requestedByUserId().toString());
            meta.put("piece_events_actor_user_id", req.actorUserId().toString());
            meta.put("note", NOTE);
            meta.put("pieces_voided", guardRows(null).size());
            meta.put("variants", plan.size());
            meta.put("expected_shopify_delta", totals(plan).get("expected_shopify_delta"));
            meta.put("voided_in_this_run", voided);
            audit.record(req.requestedByUserId(), "receipt_reversed", "receipt", RECEIPT_ID.toString(), meta);
            log.info("RECEIPT_REVERSAL receipt {} marked reversed", RECEIPT_ID);
        }
        return Map.of("status", "reversed", "marked_now", n == 1);
    }

    // ── Sends (paced) ─────────────────────────────────────────────────────────────

    private static final String CLAIMS_SQL =
        "FROM shopify_inventory_adjustments sia " +
        "JOIN pieces p ON p.id = sia.trigger_id AND p.tenant_id = sia.tenant_id " +
        "WHERE sia.tenant_id = ? AND sia.trigger_type = 'void_correction' AND p.receipt_id = ? ";

    private void sendAll(Map<String, Object> r) {
        int sent = sendPass(r, "queued");
        r.put("sent_first_pass", sent);
        for (int pass = 1; pass <= retryPasses && hasRetryable(); pass++) {
            sleep(retryWaitMs);
            r.put("retry_pass", pass);
            sendPass(r, "failed");
        }
    }

    /** One attempt per claim in {@code status}, one at a time, {@code paceMs} apart. Stops at a hold. */
    private int sendPass(Map<String, Object> r, String status) {
        List<Long> ids = readTx.execute(s -> jdbc.queryForList(
            "SELECT sia.id " + CLAIMS_SQL + " AND sia.status = ? " +
            (status.equals("failed") ? " AND sia.attempt_count < " + PieceShopifyRules.MAX_ATTEMPTS : "") +
            " ORDER BY sia.variant_id, sia.id", Long.class, TENANT_ID, RECEIPT_ID, status));
        int n = 0;
        for (Long id : ids) {
            IncrementRecoveryRules.SetupProblem hold = readTx.execute(s -> IncrementRecoveryRules.holdReason(jdbc, TENANT_ID));
            if (hold != null) {
                r.put("held", hold.fixEn);
                log.warn("RECEIPT_REVERSAL sends held: {} — the piece-sync sweep sends them once the hold clears", hold.fixEn);
                return n;
            }
            inventory.pushPieceClaimNow(TENANT_ID, id, false);
            n++;
            r.put("sent", ((Number) r.getOrDefault("sent", 0)).intValue() + 1);
            sleep(paceMs);
        }
        return n;
    }

    private boolean hasRetryable() {
        Integer n = readTx.execute(s -> jdbc.queryForObject("SELECT count(*)::int " + CLAIMS_SQL +
            " AND sia.status = 'failed' AND sia.attempt_count < " + PieceShopifyRules.MAX_ATTEMPTS,
            Integer.class, TENANT_ID, RECEIPT_ID));
        return n != null && n > 0;
    }

    /** Settled: nothing queued / pending, and no definite failure with attempts left. */
    private boolean settled() {
        Integer n = readTx.execute(s -> jdbc.queryForObject("SELECT count(*)::int " + CLAIMS_SQL +
            " AND (sia.status IN ('queued', 'pending') OR (sia.status = 'failed' AND sia.attempt_count < " +
            PieceShopifyRules.MAX_ATTEMPTS + "))", Integer.class, TENANT_ID, RECEIPT_ID));
        return n != null && n == 0;
    }

    /** Per variant: claims by status (+ skip reasons), units sent to Shopify, and whether the run has settled. */
    private Map<String, Object> claimOutcomes() {
        List<Map<String, Object>> rows = readTx.execute(s -> jdbc.queryForList(
            "SELECT v.sku, sia.variant_id, sia.status, COALESCE(sia.skip_reason, '') AS skip_reason, count(*)::int AS n, " +
            "       COALESCE(sum(sia.delta) FILTER (WHERE sia.status = 'applied'), 0)::int AS applied_delta " +
            CLAIMS_SQL.replace("JOIN pieces p", "JOIN variants v ON v.id = sia.variant_id AND v.tenant_id = sia.tenant_id JOIN pieces p") +
            "GROUP BY v.sku, sia.variant_id, sia.status, sia.skip_reason ORDER BY v.sku, sia.status",
            TENANT_ID, RECEIPT_ID));
        Map<String, Map<String, Object>> byVariant = new LinkedHashMap<>();
        Map<String, Integer> total = new TreeMap<>();
        int appliedDelta = 0;
        for (Map<String, Object> x : rows) {
            String key = x.get("sku") + " (" + x.get("variant_id") + ")";
            Map<String, Object> m = byVariant.computeIfAbsent(key, k -> new TreeMap<>());
            String st = (String) x.get("status");
            int n = ((Number) x.get("n")).intValue();
            m.merge(st, n, (a, b) -> ((Number) a).intValue() + ((Number) b).intValue());
            total.merge(st, n, Integer::sum);
            if (!((String) x.get("skip_reason")).isEmpty()) {
                m.merge("skip:" + x.get("skip_reason"), n, (a, b) -> ((Number) a).intValue() + ((Number) b).intValue());
            }
            appliedDelta += ((Number) x.get("applied_delta")).intValue();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("settled", settled());
        out.put("totals", total);
        out.put("applied_delta", appliedDelta);
        out.put("by_variant", byVariant);
        return out;
    }

    // ── helpers ───────────────────────────────────────────────────────────────────

    private Map<String, Object> newReport(Request req) {
        Map<String, Object> r = Collections.synchronizedMap(new LinkedHashMap<>());
        r.put("mode", req.apply() ? "apply" : "dry_run");
        r.put("tenant_id", TENANT_ID.toString());
        r.put("receipt_id", RECEIPT_ID.toString());
        r.put("allow_negative", req.allowNegative());
        r.put("started_at", Instant.now().toString());
        r.put("phase", "starting");
        return r;
    }

    private static void stop(Map<String, Object> r, String code, String message) {
        r.put("phase", "stopped");
        r.put("stop_code", code);
        r.put("stop_message", message);
        log.warn("RECEIPT_REVERSAL stopped {}: {}", code, message);
    }

    private static void sleep(long ms) {
        if (ms <= 0) return;
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
