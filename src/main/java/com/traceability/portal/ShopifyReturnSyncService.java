package com.traceability.portal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.traceability.integrations.shopify.ReturnsWriteResponse;
import com.traceability.integrations.shopify.ShopifyGateway;
import com.traceability.integrations.shopify.ShopifyReturnsAccessDeniedException;
import com.traceability.integrations.shopify.ShopifyReturnsGateway;
import com.traceability.integrations.shopify.ShopifyReturnsWriteGateway;
import com.traceability.integrations.shopify.ShopifyStoreDisconnectedException;
import com.traceability.integrations.shopify.ShopifyStoreNeedsReauthException;
import com.traceability.integrations.shopify.ShopifyTokenProvider;
import com.traceability.tenancy.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Shopify-native returns S5a (approved 2026-11-07) — a REFUND request made on Traced's portal is also created as a
 * Return in Shopify, so Shopify's order page, reports and customer history stay right. The ONE writer of
 * {@code return_requests.shopify_sync_*} and {@code shopify_return_actions} (V225), and the ONE caller of
 * {@link ShopifyReturnsWriteGateway}'s writes.
 *
 * <p>State-driven: {@link #sync} reads what Traced knows and does the next Shopify step, so a missed trigger is
 * caught by the sweep. Map of Traced → Shopify:
 * <ul>
 *   <li>approved (decided at/after the tenant's switch-on time) → {@code returnCreate} (OPEN), lines from the live
 *       {@code returnableFulfillments} (oldest fulfillment first), no fee, no exchange line, no notification; every
 *       line note carries the marker "Traced RR-…" and the Traced reason label (never the customer's text).</li>
 *   <li>an item graded / marked arrived (done with arrived_at) → {@code returnProcess} for that unit, NOT_RESTOCKED
 *       (first without a location, then at the Traced Main location if Shopify refuses — unconfirmed which it
 *       needs); Traced restocks through its own +1 as today, Shopify never restocks. No refund.</li>
 *   <li>refunded / closed: nothing processed → {@code returnCancel}; else units that never came back →
 *       {@code removeFromReturn}, then {@code returnClose} (tolerates Shopify having closed it itself).</li>
 *   <li>an arrival undone after Shopify processed it → only the LOW {@code shopify_return_sync_check} alert.</li>
 * </ul>
 * Not synced (recorded, quiet, no retry): pre-connect orders, orders not in Shopify, store not connected, write_returns
 * not approved, a line Shopify can't return.
 *
 * <p>S5b (option A, approved 2026-11-08): an EXCHANGE request (portal or Lookup) is synced the same way, as a return
 * of the item coming back only — never an exchange line ({@link ShopifyReturnSyncRules#exchangeNote} carries the
 * replacement's name, the collect amount and "don't refund in Shopify"). 'exchanged' ends it like refunded (close),
 * exchange_failed / a Lookup cancel before anything came back cancels it. The replacement's stock leaves through
 * Step 5a's −1 only; the Traced-created return is never Shopify-native (no shadow, no expected-back line).
 *
 * <p>Safety (the Bosta booking pattern): claim-before-call (a committed conditional UPDATE / INSERT), ONE attempt per
 * call, no transaction open during a Shopify call, every DB touch in {@code TenantContext.runAs}. DEFINITE failure
 * (nothing applied) → retried up to {@link ShopifyReturnSyncRules#MAX_ATTEMPTS}; AMBIGUOUS → never re-sent: a create
 * is settled by reading the order's returns for the marker, a process / close by re-reading the return, a cancel /
 * remove failure goes to needs_review (no retry).
 */
@Service
public class ShopifyReturnSyncService {

    private static final Logger log = LoggerFactory.getLogger(ShopifyReturnSyncService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ShopifyReturnsWriteGateway writes;
    private final ShopifyReturnsGateway reads;
    private final ShopifyTokenProvider tokens;
    private final ShopifyReturnSyncRollout rollout;

    public ShopifyReturnSyncService(JdbcTemplate jdbc, PlatformTransactionManager txm, ShopifyReturnsWriteGateway writes,
                                    ShopifyReturnsGateway reads, ShopifyTokenProvider tokens,
                                    ShopifyReturnSyncRollout rollout) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txm);
        this.writes = writes;
        this.reads = reads;
        this.tokens = tokens;
        this.rollout = rollout;
    }

    // ── context ────────────────────────────────────────────────────────────────────────────────

    record Ctx(UUID tenantId, UUID requestId, String reference, String status, String type, UUID orderId,
               Instant createdAt, Instant decidedAt, boolean enabled, Instant since,
               String syncStatus, int attempts, Instant attemptedAt, Instant verifiedAt, String returnGid,
               String syncLines, String orderGid, UUID storeId, String shopDomain, String storeStatus, String scopes) {}

    record Item(UUID id, String itemStatus, boolean graded, String lineGid, String reason) {}

    record Action(String action, String unitKey, String status, int attempts) {
        boolean finalForProcess() {
            return Set.of("applied", "skipped", "resolved").contains(status);
        }
        boolean retryable() {
            return "failed".equals(status) && attempts < ShopifyReturnSyncRules.MAX_ATTEMPTS;
        }
    }

    private Ctx load(UUID tenantId, UUID requestId) {
        List<Ctx> rows = jdbc.query(
            "SELECT rr.reference, rr.status::text AS status, rr.type, rr.order_id, rr.created_at, rr.decided_at, " +
            "       t.shopify_returns_sync_enabled, t.shopify_returns_sync_since, " +
            "       rr.shopify_sync_status, rr.shopify_sync_attempts, rr.shopify_sync_attempted_at, " +
            "       rr.shopify_sync_verified_at, rr.shopify_return_gid, rr.shopify_sync_lines::text AS sync_lines, " +
            "       mo.external_id AS order_gid, mo.store_id, st.shop_domain, st.status::text AS store_status, " +
            "       st.access_token_scopes " +
            "FROM return_requests rr JOIN tenants t ON t.id = rr.tenant_id " +
            "LEFT JOIN merchant_orders mo ON mo.id = rr.order_id AND mo.tenant_id = rr.tenant_id " +
            "LEFT JOIN stores st ON st.id = mo.store_id AND st.tenant_id = rr.tenant_id " +
            "WHERE rr.id = ? AND rr.tenant_id = ?",
            (rs, i) -> new Ctx(tenantId, requestId, rs.getString("reference"), rs.getString("status"), rs.getString("type"),
                rs.getObject("order_id", UUID.class), instant(rs.getTimestamp("created_at")),
                instant(rs.getTimestamp("decided_at")), rs.getBoolean("shopify_returns_sync_enabled"),
                instant(rs.getTimestamp("shopify_returns_sync_since")), rs.getString("shopify_sync_status"),
                rs.getInt("shopify_sync_attempts"), instant(rs.getTimestamp("shopify_sync_attempted_at")),
                instant(rs.getTimestamp("shopify_sync_verified_at")), rs.getString("shopify_return_gid"),
                rs.getString("sync_lines"), rs.getString("order_gid"), rs.getObject("store_id", UUID.class),
                rs.getString("shop_domain"), rs.getString("store_status"), rs.getString("access_token_scopes")),
            requestId, tenantId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private List<Item> items(UUID tenantId, UUID requestId) {
        return jdbc.query(
            "SELECT i.id, i.item_status, (i.item_status = 'done' AND i.arrived_at IS NOT NULL) AS graded, i.reason_code, " +
            "       COALESCE(oiu.external_id, (SELECT oi.external_id FROM allocations a " +
            "           JOIN order_items oi ON oi.id = a.order_item_id AND oi.tenant_id = a.tenant_id " +
            "           WHERE a.piece_id = i.piece_id AND a.tenant_id = i.tenant_id AND oi.order_id = rr.order_id " +
            "           ORDER BY a.allocated_at DESC LIMIT 1)) AS line_gid " +
            "FROM return_request_items i JOIN return_requests rr ON rr.id = i.request_id AND rr.tenant_id = i.tenant_id " +
            "LEFT JOIN order_items oiu ON oiu.id = i.order_item_id AND oiu.tenant_id = i.tenant_id " +
            "WHERE i.request_id = ? AND i.tenant_id = ? ORDER BY i.created_at, i.id",
            (rs, n) -> new Item(rs.getObject("id", UUID.class), rs.getString("item_status"), rs.getBoolean("graded"),
                rs.getString("line_gid"), rs.getString("reason_code")),
            requestId, tenantId);
    }

    private Map<String, Action> actions(UUID tenantId, UUID requestId) {
        Map<String, Action> out = new LinkedHashMap<>();
        jdbc.query("SELECT action, unit_key, status, attempts FROM shopify_return_actions " +
                "WHERE tenant_id = ? AND request_id = ? ORDER BY created_at, id",
            rs -> {
                Action a = new Action(rs.getString(1), rs.getString(2), rs.getString(3), rs.getInt(4));
                out.put(a.action() + ":" + a.unitKey(), a);
            }, tenantId, requestId);
        return out;
    }

    /** Is this request one the feature applies to at all (listed, switched on, decided after switch-on, a refund)? */
    static boolean applies(Ctx c) {
        return c != null && c.enabled() && c.since() != null && c.decidedAt() != null
            && !c.decidedAt().isBefore(c.since()) && ShopifyReturnSyncRules.SYNCED_TYPES.contains(c.type())
            && !Set.of("requested", "rejected", "cancelled").contains(c.status());
    }

    // ── entry point ────────────────────────────────────────────────────────────────────────────

    /** The next Shopify step for one request (no-op when nothing is due). Must be called with no transaction open. */
    public void sync(UUID tenantId, UUID requestId) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("ShopifyReturnSyncService.sync must not run inside a transaction");
        }
        if (!rollout.listed(tenantId)) return;
        Ctx c = inTenant(tenantId, () -> load(tenantId, requestId));
        if (!applies(c)) return;
        String s = c.syncStatus();
        if (s == null || ("failed".equals(s) && c.attempts() < ShopifyReturnSyncRules.MAX_ATTEMPTS)) {
            create(c);
        } else if ("pending".equals(s)) {
            markStuckPending(tenantId, requestId);
        } else if ("failed_ambiguous".equals(s)) {
            resolveAmbiguousCreate(c);
        } else if ("created".equals(s)) {
            if (c.verifiedAt() == null) verify(c);
            Ctx again = inTenant(tenantId, () -> load(tenantId, requestId));
            if (again != null && "created".equals(again.syncStatus())) act(again);
        }
    }

    // ── create ─────────────────────────────────────────────────────────────────────────────────

    private void create(Ctx c) {
        UUID t = c.tenantId();
        List<Item> items = inTenant(t, () -> items(t, c.requestId()));
        List<Item> coming = items.stream().filter(i -> !"not_coming".equals(i.itemStatus())).toList();
        if (ShopifyReturnSyncRules.TERMINAL.contains(c.status()) && items.stream().noneMatch(Item::graded)
                && items.stream().noneMatch(i -> ShopifyReturnSyncRules.END_PENDING_ITEM.contains(i.itemStatus()))) {
            skip(c, "request_ended");
            return;
        }
        if (c.orderGid() == null) { skip(c, "pre_connect_order"); return; }
        if (!c.orderGid().startsWith("gid://shopify/Order/")) { skip(c, "not_a_shopify_order"); return; }
        if (c.storeId() == null || !"connected".equals(c.storeStatus())) { skip(c, "store_not_connected"); return; }
        if (!ShopifyGateway.isScopeGranted("write_returns", c.scopes())) { skip(c, "no_permission"); return; }
        if (coming.isEmpty()) { skip(c, "nothing_to_return"); return; }
        Map<String, Integer> wanted = new LinkedHashMap<>();
        Map<String, Set<String>> reasons = new HashMap<>();
        for (Item i : coming) {
            if (i.lineGid() == null || !i.lineGid().startsWith("gid://shopify/LineItem/")) {
                skip(c, "line_not_in_shopify");
                return;
            }
            wanted.merge(i.lineGid(), 1, Integer::sum);
            reasons.computeIfAbsent(i.lineGid(), k -> new LinkedHashSet<>())
                .add(ShopifyReturnSyncRules.REASON_LABELS.getOrDefault(i.reason(), "Other"));
        }

        String token = token(c);
        if (token == null) return;

        ShopifyReturnsWriteGateway.Returnables returnables;
        try {
            returnables = writes.returnableFulfillments(c.shopDomain(), token, c.orderGid());
        } catch (ShopifyReturnsAccessDeniedException e) {
            skip(c, "no_permission");
            return;
        } catch (RuntimeException e) {
            log.warn("Shopify return sync: returnable read failed tenant={} request={} cause={}", t, c.requestId(),
                e.getClass().getSimpleName());
            inTenant(t, () -> jdbc.update(
                "UPDATE return_requests SET shopify_sync_status = 'failed', shopify_sync_attempts = shopify_sync_attempts + 1, " +
                "    shopify_sync_attempted_at = now(), shopify_sync_error = ? " +
                "WHERE id = ? AND tenant_id = ? AND (shopify_sync_status IS NULL OR " +
                "      (shopify_sync_status = 'failed' AND shopify_sync_attempts < ?))",
                "returnable read failed (" + e.getClass().getSimpleName() + ")", c.requestId(), t,
                ShopifyReturnSyncRules.MAX_ATTEMPTS));
            return;
        }
        if (returnables.truncated()) { skip(c, "too_many_lines"); return; }
        int returnableTotal = returnables.lines().stream().mapToInt(ShopifyReturnsWriteGateway.Returnable::quantity).sum();
        String reasonId = fallbackReturnReason(c, token);
        String exchangeNote = "exchange".equals(c.type()) ? inTenant(t, () -> exchangeNote(c)) : null;

        // Allocate each wanted LineItem's units over its returnable fulfillment line items, oldest fulfillment first.
        List<ShopifyReturnsWriteGateway.CreateLine> lines = new ArrayList<>();
        ArrayNode sent = MAPPER.createArrayNode();
        String marker = ShopifyReturnSyncRules.marker(c.reference());
        for (Map.Entry<String, Integer> w : wanted.entrySet()) {
            int left = w.getValue();
            String note = exchangeNote != null ? exchangeNote : marker + " · " + String.join(", ", reasons.get(w.getKey()));
            for (ShopifyReturnsWriteGateway.Returnable r : returnables.lines()) {
                if (left == 0) break;
                if (!r.lineItemGid().equals(w.getKey())) continue;
                int q = Math.min(left, r.quantity());
                lines.add(new ShopifyReturnsWriteGateway.CreateLine(r.fulfillmentLineItemGid(), q, note, reasonId));
                sent.addObject().put("lineItemGid", w.getKey()).put("fulfillmentLineItemGid", r.fulfillmentLineItemGid())
                    .put("quantity", q);
                left -= q;
            }
            if (left > 0) {
                skip(c, returnableTotal == 0 ? "not_fulfilled_in_shopify" : "not_returnable_in_shopify");
                return;
            }
        }

        // Claim before call — committed before Shopify is called; only one sender wins.
        Integer claimed = inTenant(t, () -> jdbc.update(
            "UPDATE return_requests SET shopify_sync_status = 'pending', shopify_sync_attempts = shopify_sync_attempts + 1, " +
            "    shopify_sync_attempted_at = now(), shopify_sync_error = NULL, shopify_sync_reason = NULL, " +
            "    shopify_sync_lines = ?::jsonb " +
            "WHERE id = ? AND tenant_id = ? AND (shopify_sync_status IS NULL OR " +
            "      (shopify_sync_status = 'failed' AND shopify_sync_attempts < ?))",
            sent.toString(), c.requestId(), t, ShopifyReturnSyncRules.MAX_ATTEMPTS));
        if (claimed == null || claimed != 1) return;

        ReturnsWriteResponse r;
        try {
            r = writes.returnCreate(c.shopDomain(), token, c.orderGid(), c.createdAt(), lines);
        } catch (IllegalArgumentException e) {
            r = new ReturnsWriteResponse(ReturnsWriteResponse.Kind.DEFINITE, null, null, "refused before sending", List.of());
        } catch (RuntimeException e) {
            r = new ReturnsWriteResponse(ReturnsWriteResponse.Kind.AMBIGUOUS, null, null,
                "unexpected (" + e.getClass().getSimpleName() + ")", List.of());
        }
        ReturnsWriteResponse res = r;
        if (res.ok()) {
            String gid = res.payload().path("return").path("id").asText("");
            String name = res.payload().path("return").path("name").asText(null);
            if (gid.isEmpty()) {
                finishCreate(c, "failed_ambiguous", null, null, "no return id in the answer");
                return;
            }
            finishCreate(c, "created", gid, name, null);
            inTenant(t, () -> {
                new ReturnRequestLifecycle(jdbc).event(t, c.requestId(), "shopify_return_created", null,
                    eventMeta("return_name", name));
                return null;
            });
            Ctx now = inTenant(t, () -> load(t, c.requestId()));
            if (now != null) verify(now);
        } else if (res.denied()) {
            finishCreate(c, "skipped", null, null, null, "no_permission");
        } else if (res.definite()) {
            finishCreate(c, "failed", null, null, res.reason());
        } else {
            finishCreate(c, "failed_ambiguous", null, null, res.reason());
        }
    }

    private void finishCreate(Ctx c, String status, String gid, String name, String error) {
        finishCreate(c, status, gid, name, error, null);
    }

    private void finishCreate(Ctx c, String status, String gid, String name, String error, String reason) {
        try {
            inTenant(c.tenantId(), () -> jdbc.update(
                "UPDATE return_requests SET shopify_sync_status = ?, shopify_return_gid = ?, shopify_return_name = ?, " +
                "    shopify_sync_error = ?, shopify_sync_reason = ? " +
                "WHERE id = ? AND tenant_id = ? AND shopify_sync_status = 'pending'",
                status, gid, name, error, reason, c.requestId(), c.tenantId()));
        } catch (org.springframework.dao.DuplicateKeyException e) {
            // Another request already holds this Shopify return (should never happen) — never guess: a person checks.
            inTenant(c.tenantId(), () -> jdbc.update(
                "UPDATE return_requests SET shopify_sync_status = 'failed_ambiguous', " +
                "    shopify_sync_error = 'Shopify answered with a return another request holds' " +
                "WHERE id = ? AND tenant_id = ? AND shopify_sync_status = 'pending'", c.requestId(), c.tenantId()));
        }
    }

    /** Recorded, quiet, never retried by itself ("Not synced: <reason>"). Only from not-attempted / failed. */
    private void skip(Ctx c, String reason) {
        inTenant(c.tenantId(), () -> jdbc.update(
            "UPDATE return_requests SET shopify_sync_status = 'skipped', shopify_sync_reason = ?, shopify_sync_error = NULL " +
            "WHERE id = ? AND tenant_id = ? AND (shopify_sync_status IS NULL OR shopify_sync_status = 'failed')",
            reason, c.requestId(), c.tenantId()));
    }

    /** S5b — the exchange's line note: the replacement (product / variant), the frozen collect, the warning. */
    private String exchangeNote(Ctx c) {
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT p.title AS product, v.title AS variant, rr.booking_status, " + ExchangeCod.FROZEN_SQL + " AS collect, " +
            "       (SELECT a.customer_owed FROM return_request_amounts a WHERE a.request_id = rr.id " +
            "          AND a.tenant_id = rr.tenant_id AND a.fee_kind = 'exchange') AS owed " +
            "FROM return_requests rr " +
            "LEFT JOIN return_request_items i ON i.request_id = rr.id AND i.tenant_id = rr.tenant_id " +
            "     AND i.replacement_variant_id IS NOT NULL " +
            "LEFT JOIN variants v ON v.id = i.replacement_variant_id AND v.tenant_id = rr.tenant_id " +
            "LEFT JOIN products p ON p.id = v.product_id AND p.tenant_id = rr.tenant_id " +
            "WHERE rr.id = ? AND rr.tenant_id = ? ORDER BY i.created_at, i.id LIMIT 1", c.requestId(), c.tenantId());
        Map<String, Object> r = rows.isEmpty() ? Map.of() : rows.get(0);
        String product = (String) r.get("product");
        String variant = (String) r.get("variant");
        String label = product == null ? variant
            : (variant == null || variant.isBlank() || "Default Title".equals(variant)) ? product : product + " / " + variant;
        return ShopifyReturnSyncRules.exchangeNote(c.reference(), label, (java.math.BigDecimal) r.get("collect"),
            (java.math.BigDecimal) r.get("owed"), "manual".equals(r.get("booking_status")));
    }

    private void markStuckPending(UUID tenantId, UUID requestId) {
        inTenant(tenantId, () -> jdbc.update(
            "UPDATE return_requests SET shopify_sync_status = 'failed_ambiguous', " +
            "    shopify_sync_error = 'no answer recorded (pending too long)' " +
            "WHERE id = ? AND tenant_id = ? AND shopify_sync_status = 'pending' " +
            "  AND shopify_sync_attempted_at < now() - make_interval(mins => ?)",
            requestId, tenantId, ShopifyReturnSyncRules.STUCK_PENDING_MINUTES));
    }

    /** An unconfirmed create: read the order's returns for this request's marker (a read — never a re-send). */
    private void resolveAmbiguousCreate(Ctx c) {
        if (c.orderGid() == null || c.shopDomain() == null) return;
        String token = token(c);
        if (token == null) return;
        JsonNode order;
        try {
            order = writes.orderReturnNotes(c.shopDomain(), token, c.orderGid());
        } catch (RuntimeException e) {
            log.warn("Shopify return sync: marker read failed tenant={} request={} cause={}", c.tenantId(), c.requestId(),
                e.getClass().getSimpleName());
            return;
        }
        String marker = ShopifyReturnSyncRules.marker(c.reference());
        List<JsonNode> found = new ArrayList<>();
        for (JsonNode ret : order.path("returns").path("nodes")) {
            for (JsonNode l : ret.path("returnLineItems").path("nodes")) {
                if (l.path("returnReasonNote").asText("").contains(marker)) { found.add(ret); break; }
            }
        }
        UUID t = c.tenantId();
        if (found.size() == 1) {
            String gid = found.get(0).path("id").asText();
            String name = found.get(0).path("name").asText(null);
            int n = inTenant(t, () -> jdbc.update(
                "UPDATE return_requests SET shopify_sync_status = 'created', shopify_return_gid = ?, shopify_return_name = ?, " +
                "    shopify_sync_error = NULL WHERE id = ? AND tenant_id = ? AND shopify_sync_status = 'failed_ambiguous'",
                gid, name, c.requestId(), t));
            if (n == 1) {
                inTenant(t, () -> {
                    new ReturnRequestLifecycle(jdbc).event(t, c.requestId(), "shopify_return_found", null,
                        eventMeta("return_name", name));
                    return null;
                });
            }
        } else if (found.size() > 1) {
            inTenant(t, () -> jdbc.update(
                "UPDATE return_requests SET shopify_sync_status = 'needs_review', shopify_sync_reason = 'several_returns_found' " +
                "WHERE id = ? AND tenant_id = ? AND shopify_sync_status = 'failed_ambiguous'", c.requestId(), t));
        }
    }

    // ── read-back ──────────────────────────────────────────────────────────────────────────────

    private void verify(Ctx c) {
        String token = token(c);
        if (token == null) return;
        JsonNode ret;
        try {
            ret = reads.fetchReturn(c.shopDomain(), token, c.returnGid());
        } catch (RuntimeException e) {
            log.warn("Shopify return sync: read-back failed tenant={} request={} cause={}", c.tenantId(), c.requestId(),
                e.getClass().getSimpleName());
            return;
        }
        UUID t = c.tenantId();
        if (ret == null) {
            review(c, "not_found_in_shopify");
            return;
        }
        Map<String, Integer> expected = new HashMap<>();
        try {
            for (JsonNode s : MAPPER.readTree(c.syncLines() == null ? "[]" : c.syncLines())) {
                expected.merge(s.path("fulfillmentLineItemGid").asText(), s.path("quantity").asInt(), Integer::sum);
            }
        } catch (Exception e) {
            review(c, "readback_mismatch");
            return;
        }
        Map<String, Integer> actual = new HashMap<>();
        String marker = ShopifyReturnSyncRules.marker(c.reference());
        boolean markerOk = true;
        for (JsonNode l : ret.path("returnLineItems").path("nodes")) {
            actual.merge(l.path("fulfillmentLineItem").path("id").asText(""), l.path("quantity").asInt(), Integer::sum);
            if (!l.path("returnReasonNote").asText("").contains(marker)) markerOk = false;
        }
        boolean orderOk = c.orderGid() != null && c.orderGid().equals(ret.path("order").path("id").asText());
        if (markerOk && orderOk && expected.equals(actual)) {
            inTenant(t, () -> jdbc.update(
                "UPDATE return_requests SET shopify_sync_verified_at = now() WHERE id = ? AND tenant_id = ? " +
                "AND shopify_sync_status = 'created'", c.requestId(), t));
        } else {
            review(c, "readback_mismatch");
        }
    }

    private void review(Ctx c, String reason) {
        inTenant(c.tenantId(), () -> jdbc.update(
            "UPDATE return_requests SET shopify_sync_status = 'needs_review', shopify_sync_reason = ? " +
            "WHERE id = ? AND tenant_id = ? AND shopify_sync_status = 'created'", reason, c.requestId(), c.tenantId()));
        log.info("Shopify return sync: needs review tenant={} request={} reason={}", c.tenantId(), c.requestId(), reason);
    }

    // ── later actions on the created return ────────────────────────────────────────────────────

    /** A Shopify return line as read: units, processed units, the line item / fulfillment line item it returns. */
    static final class RLine {
        final String id, fli, lineItem;
        final int quantity;
        int processed;
        RLine(String id, String fli, String lineItem, int quantity, int processed) {
            this.id = id; this.fli = fli; this.lineItem = lineItem; this.quantity = quantity; this.processed = processed;
        }
        int unprocessed() { return Math.max(0, quantity - processed); }
    }

    static final class RfoLine {
        final String id, fli;
        int remaining;
        RfoLine(String id, String fli, int remaining) { this.id = id; this.fli = fli; this.remaining = remaining; }
    }

    private void act(Ctx c) {
        UUID t = c.tenantId();
        List<Item> items = inTenant(t, () -> items(t, c.requestId()));
        Map<String, Action> done = inTenant(t, () -> actions(t, c.requestId()));
        boolean terminal = ShopifyReturnSyncRules.TERMINAL.contains(c.status());
        List<Item> toProcess = new ArrayList<>();
        for (Item i : items) {
            if (!i.graded()) continue;
            Action a = done.get("process:" + i.id());
            if (a == null || a.retryable()) toProcess.add(i);
        }
        boolean ended = done.values().stream().anyMatch(a ->
            ("close".equals(a.action()) || "cancel".equals(a.action()))
                && (Set.of("applied", "skipped", "resolved", "failed_ambiguous", "needs_review").contains(a.status())
                    || ("failed".equals(a.status()) && a.attempts() >= ShopifyReturnSyncRules.MAX_ATTEMPTS)));
        if (toProcess.isEmpty() && (!terminal || ended)) return;
        // Process always comes before close / cancel, whichever Traced event came first: while an item is still
        // awaited or came back ungraded, the return's end is PENDING (no Shopify read, no call) — the grading's
        // sync processes it, then ends it. In Traced's own flow 'exchanged' / refunded / closed leave no such item;
        // this guards any out-of-order arrival of the end before the grading.
        boolean endPending = items.stream().anyMatch(i -> ShopifyReturnSyncRules.END_PENDING_ITEM.contains(i.itemStatus()));
        if (toProcess.isEmpty() && endPending) return;

        String token = token(c);
        if (token == null) return;
        JsonNode ret;
        try {
            ret = reads.fetchReturn(c.shopDomain(), token, c.returnGid());
        } catch (RuntimeException e) {
            log.warn("Shopify return sync: return read failed tenant={} request={} cause={}", t, c.requestId(),
                e.getClass().getSimpleName());
            return;
        }
        if (ret == null) { review(c, "not_found_in_shopify"); return; }
        String status = ret.path("status").asText("");
        boolean weCancelled = done.containsKey("cancel:return") && "applied".equals(done.get("cancel:return").status());
        if (("CANCELED".equals(status) || "DECLINED".equals(status)) && !weCancelled) {
            review(c, "ended_in_shopify");
            return;
        }
        List<RLine> lines = new ArrayList<>();
        for (JsonNode l : ret.path("returnLineItems").path("nodes")) {
            lines.add(new RLine(l.path("id").asText(), l.path("fulfillmentLineItem").path("id").asText(""),
                l.path("fulfillmentLineItem").path("lineItem").path("id").asText(""),
                l.path("quantity").asInt(), l.path("processedQuantity").asInt()));
        }
        List<RfoLine> rfo = new ArrayList<>();
        for (JsonNode o : ret.path("reverseFulfillmentOrders").path("nodes")) {
            for (JsonNode li : o.path("lineItems").path("nodes")) {
                int disposed = 0;
                for (JsonNode d : li.path("dispositions")) disposed += d.path("quantity").asInt();
                rfo.add(new RfoLine(li.path("id").asText(), li.path("fulfillmentLineItem").path("id").asText(""),
                    Math.max(0, li.path("totalQuantity").asInt() - disposed)));
            }
        }
        String mainGid = inTenant(t, () -> jdbc.query(
            "SELECT shopify_location_id FROM locations WHERE tenant_id = ? AND is_fulfillment " +
            "AND shopify_location_id IS NOT NULL LIMIT 1", rs -> rs.next() ? rs.getString(1) : null, t));

        for (Item i : toProcess) processUnit(c, token, i, status, lines, rfo, mainGid);

        if (!terminal || endPending) return;
        // Every graded unit must be settled before the return is ended in Shopify.
        Map<String, Action> now = inTenant(t, () -> actions(t, c.requestId()));
        for (Item i : items) {
            if (!i.graded()) continue;
            Action a = now.get("process:" + i.id());
            if (a == null || !a.finalForProcess()) return;
        }
        if (now.containsKey("cancel:return") || now.containsKey("close:return")) {
            Action end = now.containsKey("cancel:return") ? now.get("cancel:return") : now.get("close:return");
            if (!end.retryable()) return;
        }
        boolean processedAny = lines.stream().anyMatch(l -> l.processed > 0);
        if (!processedAny) {
            endReturn(c, token, "cancel", status);
            return;
        }
        for (RLine l : lines) {
            if (l.unprocessed() > 0) removeUnits(c, token, l);
        }
        endReturn(c, token, "close", status);
    }

    private void processUnit(Ctx c, String token, Item i, String shopifyStatus, List<RLine> lines, List<RfoLine> rfo,
                             String mainGid) {
        UUID t = c.tenantId();
        String key = i.id().toString();
        if ("CLOSED".equals(shopifyStatus)) {
            recordWithoutCall(c, "process", key, i.id(), "skipped", "return_closed_in_shopify");
            return;
        }
        RLine line = null;
        for (RLine l : lines) {
            if (l.lineItem.equals(i.lineGid()) && l.unprocessed() > 0) { line = l; break; }
        }
        if (line == null) {
            boolean anyLine = lines.stream().anyMatch(l -> l.lineItem.equals(i.lineGid()));
            recordWithoutCall(c, "process", key, i.id(), "skipped",
                anyLine ? "already_processed_in_shopify" : "not_in_shopify_return");
            return;
        }
        if (!claimAction(c, "process", key, i.id())) return;
        RfoLine rl = null;
        for (RfoLine r : rfo) {
            if (r.fli.equals(line.fli) && r.remaining > 0) { rl = r; break; }
        }
        String rfoId = rl == null ? null : rl.id;
        // Unconfirmed in 2026-04 whether NOT_RESTOCKED needs a location: first without one; a DEFINITE refusal (nothing
        // applied) → once more at the Traced Main location. The reason records which shape Shopify took.
        String lineGid = line.id;
        ReturnsWriteResponse r = call(() -> writes.returnProcessNotRestocked(c.shopDomain(), token, c.returnGid(),
            lineGid, rfoId, 1, null));
        String shape = "not_restocked";
        if (r.definite() && mainGid != null) {
            r = call(() -> writes.returnProcessNotRestocked(c.shopDomain(), token, c.returnGid(), lineGid, rfoId, 1, mainGid));
            shape = "not_restocked_at_main";
        }
        if (r.ok()) {
            line.processed++;
            if (rl != null) rl.remaining--;
            finishAction(c, "process", key, "applied", shape, null);
        } else if (r.denied()) {
            finishAction(c, "process", key, "skipped", "no_permission", null);
        } else if (r.definite()) {
            finishAction(c, "process", key, "failed", null, r.reason());
        } else {
            Integer processedNow = processedOf(c, token, line.id);
            if (processedNow != null && processedNow > line.processed) {
                line.processed++;
                if (rl != null) rl.remaining--;
                finishAction(c, "process", key, "applied", "confirmed_by_readback", r.reason());
            } else {
                finishAction(c, "process", key, "failed_ambiguous", null, r.reason());
            }
        }
    }

    private void removeUnits(Ctx c, String token, RLine l) {
        if (!claimAction(c, "remove", l.id, null)) return;
        ReturnsWriteResponse r = call(() -> writes.removeFromReturn(c.shopDomain(), token, c.returnGid(), l.id,
            l.unprocessed()));
        if (r.ok()) {
            finishAction(c, "remove", l.id, "applied", null, null);
        } else if (r.denied()) {
            finishAction(c, "remove", l.id, "skipped", "no_permission", null);
        } else {
            // Unconfirmed in 2026-04 whether a partly processed return accepts this — a person checks, no retry.
            finishAction(c, "remove", l.id, "needs_review", r.definite() ? "refused_by_shopify" : "no_confirmed_answer",
                r.reason());
        }
    }

    /** cancel (nothing came back) or close. Tolerates Shopify having ended / closed it already. */
    private void endReturn(Ctx c, String token, String action, String shopifyStatus) {
        String wantStatus = "cancel".equals(action) ? "CANCELED" : "CLOSED";
        if (wantStatus.equals(shopifyStatus)) {
            recordWithoutCall(c, action, "return", null, "applied", "already_" + ("cancel".equals(action) ? "cancelled" : "closed"));
            return;
        }
        if (!claimAction(c, action, "return", null)) return;
        ReturnsWriteResponse r = call(() -> "cancel".equals(action)
            ? writes.returnCancel(c.shopDomain(), token, c.returnGid())
            : writes.returnClose(c.shopDomain(), token, c.returnGid()));
        if (r.ok()) {
            finishAction(c, action, "return", "applied", null, null);
            return;
        }
        if (r.denied()) {
            finishAction(c, action, "return", "skipped", "no_permission", null);
            return;
        }
        String statusNow = statusOf(c, token);
        if (wantStatus.equals(statusNow)) {
            finishAction(c, action, "return", "applied", "confirmed_by_readback", r.reason());
        } else if ("cancel".equals(action)) {
            finishAction(c, action, "return", "needs_review", r.definite() ? "refused_by_shopify" : "no_confirmed_answer",
                r.reason());
        } else if (r.definite()) {
            finishAction(c, action, "return", "failed", null, r.reason());
        } else {
            finishAction(c, action, "return", "failed_ambiguous", null, r.reason());
        }
    }

    private Integer processedOf(Ctx c, String token, String returnLineGid) {
        try {
            JsonNode ret = reads.fetchReturn(c.shopDomain(), token, c.returnGid());
            if (ret == null) return null;
            for (JsonNode l : ret.path("returnLineItems").path("nodes")) {
                if (returnLineGid.equals(l.path("id").asText())) return l.path("processedQuantity").asInt();
            }
        } catch (RuntimeException e) {
            log.warn("Shopify return sync: read after an unconfirmed write failed cause={}", e.getClass().getSimpleName());
        }
        return null;
    }

    private String statusOf(Ctx c, String token) {
        try {
            JsonNode ret = reads.fetchReturn(c.shopDomain(), token, c.returnGid());
            return ret == null ? null : ret.path("status").asText(null);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private ReturnsWriteResponse call(Supplier<ReturnsWriteResponse> s) {
        try {
            return s.get();
        } catch (IllegalArgumentException e) {
            return new ReturnsWriteResponse(ReturnsWriteResponse.Kind.DEFINITE, null, null, "refused before sending", List.of());
        } catch (RuntimeException e) {
            return new ReturnsWriteResponse(ReturnsWriteResponse.Kind.AMBIGUOUS, null, null,
                "unexpected (" + e.getClass().getSimpleName() + ")", List.of());
        }
    }

    /** Claim-before-call for one action: a new row, or a definitely failed one within its attempts. */
    private boolean claimAction(Ctx c, String action, String key, UUID itemId) {
        List<UUID> id = inTenant(c.tenantId(), () -> jdbc.queryForList(
            "INSERT INTO shopify_return_actions (tenant_id, request_id, action, unit_key, request_item_id, status, " +
            "    attempts, attempted_at) VALUES (?, ?, ?, ?, ?, 'pending', 1, now()) " +
            "ON CONFLICT (tenant_id, request_id, action, unit_key) DO UPDATE SET status = 'pending', " +
            "    attempts = shopify_return_actions.attempts + 1, attempted_at = now(), error = NULL, updated_at = now() " +
            "WHERE shopify_return_actions.status = 'failed' AND shopify_return_actions.attempts < ? " +
            "RETURNING id",
            UUID.class, c.tenantId(), c.requestId(), action, key, itemId, ShopifyReturnSyncRules.MAX_ATTEMPTS));
        return !id.isEmpty();
    }

    private void finishAction(Ctx c, String action, String key, String status, String reason, String error) {
        inTenant(c.tenantId(), () -> jdbc.update(
            "UPDATE shopify_return_actions SET status = ?, reason = ?, error = ?, updated_at = now(), " +
            "    done_at = CASE WHEN ? IN ('applied', 'skipped') THEN now() END " +
            "WHERE tenant_id = ? AND request_id = ? AND action = ? AND unit_key = ? AND status = 'pending'",
            status, reason, error, status, c.tenantId(), c.requestId(), action, key));
    }

    private void recordWithoutCall(Ctx c, String action, String key, UUID itemId, String status, String reason) {
        inTenant(c.tenantId(), () -> jdbc.update(
            "INSERT INTO shopify_return_actions (tenant_id, request_id, action, unit_key, request_item_id, status, reason, " +
            "    done_at) VALUES (?, ?, ?, ?, ?, ?, ?, now()) " +
            "ON CONFLICT (tenant_id, request_id, action, unit_key) DO UPDATE SET status = EXCLUDED.status, " +
            "    reason = EXCLUDED.reason, done_at = now(), updated_at = now() " +
            "WHERE shopify_return_actions.status = 'failed'",
            c.tenantId(), c.requestId(), action, key, itemId, status, reason));
    }

    // ── sweep ──────────────────────────────────────────────────────────────────────────────────

    /** Requests of one tenant with something due (cheap DB reads; Shopify is only called by {@link #sync}). */
    public int sweepTenant(UUID tenantId) {
        if (!rollout.listed(tenantId)) return 0;
        List<UUID> due = inTenant(tenantId, () -> {
            jdbc.update(
                "UPDATE shopify_return_actions SET status = 'failed_ambiguous', error = 'no answer recorded (pending too long)', " +
                "    updated_at = now() WHERE tenant_id = ? AND status = 'pending' " +
                "  AND attempted_at < now() - make_interval(mins => ?)", tenantId, ShopifyReturnSyncRules.STUCK_PENDING_MINUTES);
            return jdbc.queryForList(
                "SELECT rr.id FROM return_requests rr JOIN tenants t ON t.id = rr.tenant_id " +
                "WHERE rr.tenant_id = ? AND t.shopify_returns_sync_enabled AND t.shopify_returns_sync_since IS NOT NULL " +
                "  AND rr.type IN " + ShopifyReturnSyncRules.SYNCED_TYPES_SQL + " AND rr.decided_at >= t.shopify_returns_sync_since " +
                "  AND rr.status::text NOT IN ('requested', 'rejected', 'cancelled') " +
                "  AND (rr.shopify_sync_status IS NULL " +
                "       OR (rr.shopify_sync_status = 'failed' AND rr.shopify_sync_attempts < ?) " +
                "       OR rr.shopify_sync_status IN ('pending', 'failed_ambiguous') " +
                "       OR (rr.shopify_sync_status = 'created' AND NOT EXISTS (SELECT 1 FROM shopify_return_actions a " +
                "            WHERE a.tenant_id = rr.tenant_id AND a.request_id = rr.id AND a.action IN ('close', 'cancel') " +
                "              AND a.status IN ('applied', 'skipped', 'resolved')))) " +
                "ORDER BY rr.decided_at, rr.id LIMIT 50",
                UUID.class, tenantId, ShopifyReturnSyncRules.MAX_ATTEMPTS);
        });
        int n = 0;
        for (UUID id : due) {
            try {
                sync(tenantId, id);
                n++;
            } catch (RuntimeException e) {
                log.warn("Shopify return sync failed tenant={} request={} cause={}", tenantId, id, e.getClass().getSimpleName());
            }
        }
        return n;
    }

    // ── merchant actions (drawer) ──────────────────────────────────────────────────────────────

    /**
     * "Try again" — a person starts a FRESH cycle: failed (also after the 5 automatic attempts) → attempts 0, the stored
     * error cleared, back through the normal claim; failed_ambiguous → the merchant checked Shopify and it isn't there
     * (same reset); skipped → re-evaluated (e.g. after approving Shopify's new permission); not attempted yet → just
     * run. The caller enqueues the sync right away. Audited (who, from which state / attempts). Every refusal is a
     * {@link ShopifyReturnSyncException} the drawer shows as is — never a silent no-op.
     */
    public Map<String, Object> retry(UUID tenantId, UUID requestId, UUID actor) {
        Ctx c = inTenant(tenantId, () -> load(tenantId, requestId));
        if (c == null) throw new org.springframework.web.server.ResponseStatusException(
            org.springframework.http.HttpStatus.NOT_FOUND);
        if (!c.enabled() || c.since() == null) throw ShopifyReturnSyncException.switchedOff();
        if (!applies(c)) throw ShopifyReturnSyncException.notApplicable();
        String from = c.syncStatus();
        if ("pending".equals(from)) throw ShopifyReturnSyncException.inProgress();
        if (from != null && !Set.of("failed", "failed_ambiguous", "skipped").contains(from)) {
            throw ShopifyReturnSyncException.alreadyInShopify();
        }
        int n = inTenant(tenantId, () -> from == null ? 1 : jdbc.update(
            "UPDATE return_requests SET shopify_sync_status = CASE WHEN shopify_sync_status = 'skipped' THEN NULL " +
            "        ELSE 'failed' END, shopify_sync_attempts = 0, shopify_sync_reason = NULL, shopify_sync_error = NULL " +
            "WHERE id = ? AND tenant_id = ? AND shopify_sync_status = ?", requestId, tenantId, from));
        if (n != 1) throw ShopifyReturnSyncException.changedMeanwhile();
        Map<String, Object> meta = new HashMap<>();
        meta.put("from_status", from == null ? "not_attempted" : from);
        meta.put("from_attempts", c.attempts());
        inTenant(tenantId, () -> {
            new ReturnRequestLifecycle(jdbc).event(tenantId, requestId, "shopify_sync_retry", actor, meta);
            if (audit != null) audit.record(actor, "shopify_return_sync_retry", "return_request", requestId.toString(), meta);
            return null;
        });
        log.info("Shopify return sync: manual retry tenant={} request={} from={} attempts={}", tenantId, requestId,
            meta.get("from_status"), c.attempts());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", "queued");
        out.put("fromStatus", meta.get("from_status"));
        return out;
    }

    private com.traceability.account.AuditService audit;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setAudit(com.traceability.account.AuditService audit) {
        this.audit = audit;
    }

    /**
     * "I checked it in Shopify": a request needing review, and every action waiting for a person (unconfirmed,
     * refused, out of step after an undo) → resolved. 409 when nothing waits.
     */
    public void markChecked(UUID tenantId, UUID requestId, UUID actor) {
        int n = inTenant(tenantId, () -> {
            int a = jdbc.update(
                "UPDATE return_requests SET shopify_sync_status = 'resolved' WHERE id = ? AND tenant_id = ? " +
                "AND shopify_sync_status = 'needs_review'", requestId, tenantId);
            int b = jdbc.update(
                "UPDATE shopify_return_actions a SET status = 'resolved', updated_at = now() " +
                "WHERE a.tenant_id = ? AND a.request_id = ? AND (a.status IN ('failed_ambiguous', 'needs_review') " +
                "   OR (a.status = 'failed' AND a.attempts >= ?) " +
                "   OR (a.action = 'process' AND a.status = 'applied' AND EXISTS (SELECT 1 FROM return_request_items i " +
                "        WHERE i.id = a.request_item_id AND i.tenant_id = a.tenant_id " +
                "          AND NOT (i.item_status = 'done' AND i.arrived_at IS NOT NULL))))",
                tenantId, requestId, ShopifyReturnSyncRules.MAX_ATTEMPTS);
            return a + b;
        });
        if (n == 0) throw new org.springframework.web.server.ResponseStatusException(
            org.springframework.http.HttpStatus.CONFLICT, "Nothing waits for a check on this request.");
        inTenant(tenantId, () -> {
            new ReturnRequestLifecycle(jdbc).event(tenantId, requestId, "shopify_sync_checked", actor, null);
            return null;
        });
    }

    // ── detail block (drawer) ──────────────────────────────────────────────────────────────────

    /**
     * The drawer's "Shopify" state, or null (key absent) when the feature doesn't apply: tenant not listed, switch off
     * with nothing ever synced, a request decided before switch-on. Built on the caller's JdbcTemplate / transaction.
     */
    public static Map<String, Object> detailBlock(JdbcTemplate jdbc, UUID tenantId, UUID requestId, boolean listed) {
        if (!listed) return null;
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT rr.status::text AS status, rr.type, rr.decided_at, rr.shopify_sync_status, rr.shopify_sync_reason, " +
            "       rr.shopify_sync_attempts, rr.shopify_return_name, rr.shopify_return_gid, " +
            "       t.shopify_returns_sync_enabled AS enabled, t.shopify_returns_sync_since AS since, " +
            "       st.shop_domain, mo.external_id AS order_gid, " +
            "       EXISTS (SELECT 1 FROM shopify_return_actions a WHERE a.tenant_id = rr.tenant_id AND a.request_id = rr.id " +
            "               AND (a.status IN ('failed_ambiguous', 'needs_review') OR (a.status = 'failed' AND a.attempts >= ?))) " +
            "         AS actions_need_check, " +
            "       EXISTS (SELECT 1 FROM shopify_return_actions a JOIN return_request_items i ON i.id = a.request_item_id " +
            "               AND i.tenant_id = a.tenant_id WHERE a.tenant_id = rr.tenant_id AND a.request_id = rr.id " +
            "               AND a.action = 'process' AND a.status = 'applied' " +
            "               AND NOT (i.item_status = 'done' AND i.arrived_at IS NOT NULL)) AS out_of_step " +
            "FROM return_requests rr JOIN tenants t ON t.id = rr.tenant_id " +
            "LEFT JOIN merchant_orders mo ON mo.id = rr.order_id AND mo.tenant_id = rr.tenant_id " +
            "LEFT JOIN stores st ON st.id = mo.store_id AND st.tenant_id = rr.tenant_id " +
            "WHERE rr.id = ? AND rr.tenant_id = ?", ShopifyReturnSyncRules.MAX_ATTEMPTS, requestId, tenantId);
        if (rows.isEmpty()) return null;
        Map<String, Object> r = rows.get(0);
        String sync = (String) r.get("shopify_sync_status");
        boolean enabled = Boolean.TRUE.equals(r.get("enabled"));
        Timestamp since = (Timestamp) r.get("since");
        Timestamp decided = (Timestamp) r.get("decided_at");
        Map<String, Object> out = new LinkedHashMap<>();
        if (sync == null) {
            if (!enabled || since == null) return null;
            if (decided != null && decided.before(since)) return null;
            String status = (String) r.get("status");
            if (Set.of("rejected", "cancelled").contains(status)) return null;
            if (!ShopifyReturnSyncRules.SYNCED_TYPES.contains((String) r.get("type"))) return null;
            out.put("status", "waiting");
            out.put("reason", "requested".equals(status) ? "awaiting_approval" : null);
        } else {
            boolean exhausted = "failed".equals(sync)
                && ((Number) r.get("shopify_sync_attempts")).intValue() >= ShopifyReturnSyncRules.MAX_ATTEMPTS;
            out.put("status", sync);
            out.put("reason", r.get("shopify_sync_reason"));
            out.put("attemptsExhausted", exhausted);
            out.put("returnName", r.get("shopify_return_name"));
            out.put("adminUrl", adminUrl((String) r.get("shop_domain"), (String) r.get("order_gid")));
            boolean needCheck = Boolean.TRUE.equals(r.get("actions_need_check"));
            boolean outOfStep = Boolean.TRUE.equals(r.get("out_of_step"));
            out.put("actionsNeedCheck", needCheck);
            out.put("outOfStep", outOfStep);
            out.put("canRetry", Set.of("failed", "failed_ambiguous", "skipped").contains(sync));
            out.put("canMarkChecked", "needs_review".equals(sync) || needCheck || outOfStep);
        }
        return out;
    }

    /** The order's page in Shopify admin (its returns are shown there), or null. */
    static String adminUrl(String shopDomain, String orderGid) {
        if (shopDomain == null || orderGid == null || !orderGid.startsWith("gid://shopify/Order/")) return null;
        String handle = shopDomain.endsWith(".myshopify.com")
            ? shopDomain.substring(0, shopDomain.length() - ".myshopify.com".length()) : null;
        String id = orderGid.substring("gid://shopify/Order/".length());
        if (handle == null || !handle.matches("[a-z0-9][a-z0-9-]*") || !id.matches("[0-9]+")) return null;
        return "https://admin.shopify.com/store/" + handle + "/orders/" + id;
    }

    // ── helpers ────────────────────────────────────────────────────────────────────────────────

    /**
     * The shop's catch-all return reason ("other", else "unknown") for every line of the return we create; null (the
     * line goes without one, as before) when the shop has none or the read fails — a failed read never blocks or
     * spends an attempt. Read before the claim, no transaction open.
     */
    private String fallbackReturnReason(Ctx c, String token) {
        try {
            return reads.fetchFallbackReturnReasonId(c.shopDomain(), token);
        } catch (RuntimeException e) {
            log.warn("Shopify return sync: return reason read failed tenant={} request={} cause={}", c.tenantId(),
                c.requestId(), e.getClass().getSimpleName());
            return null;
        }
    }

    private String token(Ctx c) {
        try {
            return TenantContext.runAs(c.tenantId(), () -> tokens.getValidToken(c.storeId()));
        } catch (ShopifyStoreNeedsReauthException | ShopifyStoreDisconnectedException e) {
            log.info("Shopify return sync: store {} has no usable token ({}) — later", c.storeId(), e.getClass().getSimpleName());
            return null;
        } catch (RuntimeException e) {
            log.warn("Shopify return sync: token read failed tenant={} cause={}", c.tenantId(), e.getClass().getSimpleName());
            return null;
        }
    }

    private <T> T inTenant(UUID tenantId, Supplier<T> body) {
        return TenantContext.runAs(tenantId, () -> tx.execute(s -> body.get()));
    }

    private static Map<String, Object> eventMeta(String k, Object v) {
        Map<String, Object> m = new HashMap<>();
        if (v != null) m.put(k, v);
        return m;
    }

    private static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }
}
