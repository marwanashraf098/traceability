package com.traceability.returncases;

import com.traceability.fulfillment.OrderReturnState;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;

import java.sql.Array;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.*;

/**
 * Shopify-native returns S2 — what Traced SHOWS of a Shopify return (R1): the order drawer's "From Shopify" card and
 * the Returns list's Shopify rows. READ-ONLY over the S1 mirror (V206): no reservation, no Pick &amp; Pack, no stock
 * write, no Shopify call, no new intake logic.
 *
 * Per return: its lines coming back (product, variant, qty, reason + notes), the exchange lines going out, the
 * Shopify state ({@link ShopifyReturnState} — the ONE derivation, from the return status and the fulfillment orders
 * of its exchange lines), "Restocked in Shopify ×N" (RESTOCKED dispositions), "In Traced" (what Traced already
 * knows of the returned variants on this order — {@link OrderReturnState}, the same definition the order drawer and
 * the Orders list read) and the duplicate flag ({@link #DUPLICATE_TRACED_REQUEST_SQL}).
 *
 * Not a bean: built on the caller's JdbcTemplate (joins its transaction, works under app_user + RLS). Every query
 * also filters the caller's tenant explicitly (defense in depth). Orders are read through merchant_orders.
 */
public final class ShopifyReturnsView {

    private static final String TENANT = "NULLIF(current_setting('app.current_tenant', true), '')::uuid";

    /** Shopify return statuses that never count as a live return (a duplicate needs a live one). */
    static final String DEAD_SHOPIFY_STATUSES = "('CANCELED', 'DECLINED')";
    /** Traced request statuses that never count as a live request. */
    static final String DEAD_REQUEST_STATUSES = "('rejected', 'cancelled')";

    /**
     * Duplicate return (approved Q6): the order has BOTH a live Traced return request and a live Shopify return.
     * Both are shown, never merged. Binds nothing; {@code %1$s} = the order id expression, {@code %2$s} = tenant.
     */
    static final String DUPLICATE_TRACED_REQUEST_SQL =
        "EXISTS (SELECT 1 FROM return_requests q WHERE q.order_id = %1$s AND q.tenant_id = %2$s " +
        "        AND q.status::text NOT IN " + DEAD_REQUEST_STATUSES + ")";

    private final JdbcTemplate jdbc;

    public ShopifyReturnsView(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ── public API ─────────────────────────────────────────────────────────────────────────────

    /** The order drawer's cards: every Shopify return of the order, oldest first. Empty when there is none. */
    public List<Map<String, Object>> cardsForOrder(UUID orderId) {
        List<UUID> ids = jdbc.queryForList(
            "SELECT r.id FROM shopify_returns r JOIN merchant_orders mo ON mo.id = r.order_id AND mo.tenant_id = r.tenant_id " +
            "WHERE r.order_id = ? AND r.tenant_id = " + TENANT +
            " ORDER BY COALESCE(r.shopify_created_at, r.created_at), r.id", UUID.class, orderId);
        if (ids.isEmpty()) return List.of();
        Map<UUID, Map<String, Object>> byId = load(ids);
        List<Map<String, Object>> out = new ArrayList<>();
        for (UUID id : ids) if (byId.containsKey(id)) out.add(byId.get(id));
        return out;
    }

    /** The Returns list's Shopify rows: the same block per return id (rows whose return is gone are left out). */
    public Map<UUID, Map<String, Object>> forReturns(Collection<UUID> returnIds) {
        if (returnIds.isEmpty()) return Map.of();
        return load(new ArrayList<>(returnIds));
    }

    /**
     * Order lines that are a Shopify exchange line (linked in S1 by order_items.shopify_exchange_line_gid): line id →
     * the return's name (e.g. "#1006-R1"). Empty when none.
     */
    public Map<UUID, String> exchangeLineReturnNames(UUID orderId) {
        Map<UUID, String> out = new HashMap<>();
        jdbc.query(
            "SELECT oi.id, r.name FROM order_items oi " +
            "JOIN shopify_exchange_lines el ON el.tenant_id = oi.tenant_id AND el.line_gid = oi.shopify_exchange_line_gid " +
            "JOIN shopify_returns r ON r.id = el.return_id AND r.tenant_id = el.tenant_id " +
            "WHERE oi.order_id = ? AND oi.shopify_exchange_line_gid IS NOT NULL AND oi.tenant_id = " + TENANT,
            (RowCallbackHandler) rs -> {
                String name = rs.getString("name");
                if (name != null) out.put(rs.getObject("id", UUID.class), name);
            }, orderId);
        return out;
    }

    // ── loading ────────────────────────────────────────────────────────────────────────────────

    private Map<UUID, Map<String, Object>> load(List<UUID> ids) {
        String[] idArr = ids.stream().map(UUID::toString).toArray(String[]::new);

        Map<UUID, Map<String, Object>> head = new LinkedHashMap<>();
        Set<UUID> orderIds = new LinkedHashSet<>();
        jdbc.query(
            "SELECT r.id, r.name, r.status, r.order_id, mo.number AS order_number, " +
            "       COALESCE(r.shopify_created_at, r.created_at) AS requested_at, r.closed_at, r.lines_truncated, " +
            "       (r.status NOT IN " + DEAD_SHOPIFY_STATUSES + " AND " +
            DUPLICATE_TRACED_REQUEST_SQL.formatted("r.order_id", "r.tenant_id") + ") AS duplicate, " +
            "       (SELECT array_agg(q.reference ORDER BY q.created_at, q.id) FROM return_requests q " +
            "         WHERE q.order_id = r.order_id AND q.tenant_id = r.tenant_id " +
            "           AND q.status::text NOT IN " + DEAD_REQUEST_STATUSES + ") AS traced_refs " +
            "FROM shopify_returns r JOIN merchant_orders mo ON mo.id = r.order_id AND mo.tenant_id = r.tenant_id " +
            "WHERE r.id = ANY (?::uuid[]) AND r.tenant_id = " + TENANT,
            (RowCallbackHandler) rs -> {
                UUID id = rs.getObject("id", UUID.class);
                UUID orderId = rs.getObject("order_id", UUID.class);
                orderIds.add(orderId);
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", id.toString());
                m.put("orderId", orderId.toString());
                m.put("orderNumber", rs.getString("order_number"));
                m.put("name", rs.getString("name"));
                m.put("status", rs.getString("status"));
                m.put("requestedAt", iso(rs.getTimestamp("requested_at")));
                m.put("closedAt", iso(rs.getTimestamp("closed_at")));
                m.put("linesTruncated", rs.getBoolean("lines_truncated"));
                boolean dup = rs.getBoolean("duplicate");
                m.put("duplicate", dup);
                m.put("duplicateOf", dup ? strings(rs.getArray("traced_refs")) : List.of());
                m.put("comingBack", new ArrayList<Map<String, Object>>());
                m.put("goingOut", new ArrayList<Map<String, Object>>());
                m.put("restockedInShopify", 0);
                head.put(id, m);
            }, (Object) idArr);
        if (head.isEmpty()) return head;

        // Coming back: the return lines.
        Map<UUID, Set<UUID>> returnVariants = new HashMap<>();
        Map<UUID, Integer> returnUnits = new HashMap<>();
        jdbc.query(
            "SELECT l.return_id, l.quantity, l.reason, l.reason_note, l.customer_note, " +
            "       v.id AS variant_id, v.title AS variant_title, v.sku, p.title AS product_title, p.image_url " +
            "FROM shopify_return_lines l LEFT JOIN variants v ON v.id = l.variant_id " +
            "LEFT JOIN products p ON p.id = v.product_id " +
            "WHERE l.return_id = ANY (?::uuid[]) AND l.tenant_id = " + TENANT + " ORDER BY l.return_id, l.created_at, l.id",
            (RowCallbackHandler) rs -> {
                UUID rid = rs.getObject("return_id", UUID.class);
                Map<String, Object> m = head.get(rid);
                if (m == null) return;
                Map<String, Object> line = line(rs);
                line.put("reason", rs.getString("reason"));
                line.put("reasonNote", rs.getString("reason_note"));
                line.put("customerNote", rs.getString("customer_note"));
                cast(m.get("comingBack")).add(line);
                UUID variantId = rs.getObject("variant_id", UUID.class);
                if (variantId != null) returnVariants.computeIfAbsent(rid, k -> new HashSet<>()).add(variantId);
                returnUnits.merge(rid, rs.getInt("quantity"), Integer::sum);
            }, (Object) idArr);

        // Going out: the exchange lines.
        jdbc.query(
            "SELECT x.return_id, x.quantity, v.id AS variant_id, v.title AS variant_title, v.sku, " +
            "       p.title AS product_title, p.image_url " +
            "FROM shopify_exchange_lines x LEFT JOIN variants v ON v.id = x.variant_id " +
            "LEFT JOIN products p ON p.id = v.product_id " +
            "WHERE x.return_id = ANY (?::uuid[]) AND x.tenant_id = " + TENANT + " ORDER BY x.return_id, x.created_at, x.id",
            (RowCallbackHandler) rs -> {
                Map<String, Object> m = head.get(rs.getObject("return_id", UUID.class));
                if (m != null) cast(m.get("goingOut")).add(line(rs));
            }, (Object) idArr);

        // The fulfillment orders of the exchange lines (an FO line is an order line an exchange line became).
        Map<UUID, List<ShopifyReturnState.Fo>> fos = new HashMap<>();
        jdbc.query(
            "SELECT r.id AS return_id, f.status, f.hold_reasons FROM shopify_returns r " +
            "JOIN shopify_fulfillment_orders f ON f.tenant_id = r.tenant_id AND f.order_id = r.order_id " +
            "WHERE r.id = ANY (?::uuid[]) AND r.tenant_id = " + TENANT +
            "  AND EXISTS (SELECT 1 FROM jsonb_array_elements(f.line_items) li " +
            "              JOIN shopify_exchange_lines el ON el.return_id = r.id AND el.tenant_id = r.tenant_id " +
            "              WHERE li->>'lineItemGid' = ANY (el.order_line_gids)) " +
            "ORDER BY r.id, f.shopify_created_at, f.id",
            (RowCallbackHandler) rs -> fos.computeIfAbsent(rs.getObject("return_id", UUID.class), k -> new ArrayList<>())
                .add(new ShopifyReturnState.Fo(rs.getString("status"), strings(rs.getArray("hold_reasons")))),
            (Object) idArr);

        // Restocked in Shopify: RESTOCKED dispositions of the return (any location).
        jdbc.query(
            "SELECT d.return_id, SUM(d.quantity)::int AS n FROM shopify_rfo_dispositions d " +
            "WHERE d.return_id = ANY (?::uuid[]) AND d.tenant_id = " + TENANT + " AND d.type = 'RESTOCKED' AND d.quantity > 0 " +
            "GROUP BY d.return_id",
            (RowCallbackHandler) rs -> {
                Map<String, Object> m = head.get(rs.getObject("return_id", UUID.class));
                if (m != null) m.put("restockedInShopify", rs.getInt("n"));
            }, (Object) idArr);

        Map<UUID, Map<UUID, Evidence>> evidence = evidence(orderIds);
        Map<UUID, Evidence> intakes = intakeEvidence(idArr);
        for (Map.Entry<UUID, Map<String, Object>> e : head.entrySet()) {
            Map<String, Object> m = e.getValue();
            ShopifyReturnState.Derived d = ShopifyReturnState.derive(
                (String) m.get("status"), fos.getOrDefault(e.getKey(), List.of()));
            m.put("state", d.state());
            m.put("holdReasons", d.holdReasons());
            m.put("kind", cast(m.get("goingOut")).isEmpty() ? "return" : "exchange");
            m.put("inTraced", inTraced(d.state(), returnUnits.getOrDefault(e.getKey(), 0),
                returnVariants.getOrDefault(e.getKey(), Set.of()),
                evidence.getOrDefault(UUID.fromString((String) m.get("orderId")), Map.of()),
                intakes.get(e.getKey())));
        }
        keptAside(head, idArr);
        return head;
    }

    /**
     * S3b — "#…-X1 · kept aside": the exchange shadow order Traced keeps for this return (V219). Key present only when a
     * shadow exists (byte-identical otherwise). state: kept_aside | short (ShopifyShadowOrders.shortages, oldest first)
     * | ended (+ endReason); units = what Traced still keeps aside.
     */
    private void keptAside(Map<UUID, Map<String, Object>> head, String[] idArr) {
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT s.return_id, s.order_id, o.number, s.state, s.end_reason, " +
            "       (SELECT COALESCE(SUM(sl.live_quantity), 0) FROM shopify_exchange_shadow_lines sl " +
            "         WHERE sl.shadow_id = s.id AND sl.tenant_id = s.tenant_id) AS units " +
            "FROM shopify_exchange_shadows s JOIN warehouse_orders o ON o.id = s.order_id AND o.tenant_id = s.tenant_id " +
            "WHERE s.return_id = ANY (?::uuid[]) AND s.tenant_id = " + TENANT, (Object) idArr);
        if (rows.isEmpty()) return;
        Map<UUID, Integer> shortByOrder = new java.util.HashMap<>();
        for (com.traceability.inventory.ShopifyShadowOrders.VariantShort vs
                : com.traceability.inventory.ShopifyShadowOrders.shortages(jdbc).values()) {
            for (com.traceability.inventory.ShopifyShadowOrders.ShadowShort x : vs.shadows()) {
                shortByOrder.merge(x.orderId(), x.shortBy(), Integer::sum);
            }
        }
        for (Map<String, Object> r : rows) {
            Map<String, Object> m = head.get((UUID) r.get("return_id"));
            if (m == null) continue;
            UUID orderId = (UUID) r.get("order_id");
            Map<String, Object> k = new LinkedHashMap<>();
            k.put("orderId", orderId.toString());
            k.put("number", r.get("number"));
            int shortBy = shortByOrder.getOrDefault(orderId, 0);
            String state = "ended".equals(r.get("state")) ? "ended" : shortBy > 0 ? "short" : "kept_aside";
            k.put("state", state);
            k.put("units", ((Number) r.get("units")).intValue());
            k.put("shortBy", shortBy);
            k.put("endReason", r.get("end_reason"));
            m.put("keptAside", k);
        }
    }

    /** Units of a variant that came back on an order, by grade — from {@link OrderReturnState} (pieces and untracked units). */
    private static final class Evidence {
        int pending, sellable, damaged;
    }

    private Map<UUID, Map<UUID, Evidence>> evidence(Set<UUID> orderIds) {
        Map<UUID, Map<UUID, Evidence>> out = new HashMap<>();
        String[] arr = orderIds.stream().map(UUID::toString).toArray(String[]::new);
        // Pieces of the order that came back (same filter as OrderReturnState.ORDER_SUMMARY_SQL), by variant.
        jdbc.query(
            "SELECT DISTINCT ON (oi.order_id, pc.id) oi.order_id, oi.variant_id, rs.state " +
            "FROM order_items oi JOIN allocations a ON a.order_item_id = oi.id JOIN pieces pc ON pc.id = a.piece_id " +
            OrderReturnState.pieceStateLateral("rs", "pc.id", "pc.status", "oi.order_id") +
            " WHERE oi.order_id = ANY (?::uuid[]) AND oi.tenant_id = " + TENANT +
            "  AND rs.state IS NOT NULL AND (rs.rr_id IS NOT NULL OR a.status IN ('active', 'packed')) " +
            // S4: a piece linked to a Shopify return line counts only for that return (intakeEvidence)
            "  AND NOT EXISTS (SELECT 1 FROM shopify_return_intakes si WHERE si.piece_id = pc.id AND si.tenant_id = oi.tenant_id) " +
            "ORDER BY oi.order_id, pc.id, (a.status IN ('active', 'packed')) DESC, a.allocated_at DESC",
            (RowCallbackHandler) rs -> {
                Evidence ev = out.computeIfAbsent(rs.getObject("order_id", UUID.class), k -> new HashMap<>())
                    .computeIfAbsent(rs.getObject("variant_id", UUID.class), k -> new Evidence());
                switch (rs.getString("state")) {
                    case "pending_inspection" -> ev.pending++;
                    case "damaged" -> ev.damaged++;
                    default -> ev.sellable++;   // restocked, back_to_good
                }
            }, (Object) arr);
        // Untracked units of the order that came back, graded at arrival.
        jdbc.query(
            "SELECT u.order_id, oi.variant_id, u.grade FROM (" + OrderReturnState.UNITS_SQL +
            " WHERE u.order_id = ANY (?::uuid[])) u JOIN order_items oi ON oi.id = u.order_item_id",
            (RowCallbackHandler) rs -> {
                Evidence ev = out.computeIfAbsent(rs.getObject("order_id", UUID.class), k -> new HashMap<>())
                    .computeIfAbsent(rs.getObject("variant_id", UUID.class), k -> new Evidence());
                if ("damaged".equals(rs.getString("grade"))) ev.damaged++; else ev.sellable++;
            }, (Object) arr);
        return out;
    }

    /**
     * S4 — real intake evidence: the pieces Scan returns linked to each return's lines (shopify_return_intakes, V222),
     * graded for the order each came back for (a returned replacement: its shadow order). A linked piece with no grade
     * yet counts as awaiting inspection.
     */
    private Map<UUID, Evidence> intakeEvidence(String[] idArr) {
        Map<UUID, Evidence> out = new HashMap<>();
        jdbc.query(
            "SELECT i.return_id, rs.state FROM shopify_return_intakes i JOIN pieces pc ON pc.id = i.piece_id " +
            OrderReturnState.pieceStateLateral("rs", "pc.id", "pc.status", "i.piece_order_id") +
            " WHERE i.return_id = ANY (?::uuid[]) AND i.tenant_id = " + TENANT,
            (RowCallbackHandler) rs -> {
                Evidence ev = out.computeIfAbsent(rs.getObject("return_id", UUID.class), k -> new Evidence());
                String st = rs.getString("state");
                if (st == null || "pending_inspection".equals(st)) ev.pending++;
                else if ("damaged".equals(st)) ev.damaged++;
                else ev.sellable++;   // restocked, back_to_good
            }, (Object) idArr);
        return out;
    }

    /**
     * "In Traced": what Traced knows of the return's items — the pieces Scan returns linked to its lines (S4), plus
     * (for pieces not linked to any Shopify return: before S4, untracked units) the returned variants on the order.
     * state: expected (nothing back yet, return live) | partial (some units back, all graded, fewer than the return)
     * | awaiting_inspection | sellable | damaged | mixed | none (nothing back and the return ended in Shopify).
     */
    static Map<String, Object> inTraced(String shopifyState, int units, Set<UUID> variants, Map<UUID, Evidence> byVariant,
                                        Evidence linked) {
        int pending = 0, sellable = 0, damaged = 0;
        if (linked != null) {
            pending += linked.pending;
            sellable += linked.sellable;
            damaged += linked.damaged;
        }
        for (UUID v : variants) {
            Evidence ev = byVariant.get(v);
            if (ev == null) continue;
            pending += ev.pending;
            sellable += ev.sellable;
            damaged += ev.damaged;
        }
        int received = pending + sellable + damaged;
        boolean ended = ShopifyReturnState.CANCELLED.equals(shopifyState) || ShopifyReturnState.DECLINED.equals(shopifyState);
        String state;
        if (received == 0) state = ended ? "none" : "expected";
        else if (pending > 0) state = "awaiting_inspection";
        else if (received < units && !ended && !ShopifyReturnState.CLOSED.equals(shopifyState)) state = "partial";
        else if (damaged == 0) state = "sellable";
        else if (sellable == 0) state = "damaged";
        else state = "mixed";
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("state", state);
        m.put("units", units);
        m.put("received", received);
        m.put("pending", pending);
        m.put("sellable", sellable);
        m.put("damaged", damaged);
        return m;
    }

    // ── helpers ────────────────────────────────────────────────────────────────────────────────

    private static Map<String, Object> line(java.sql.ResultSet rs) throws SQLException {
        Map<String, Object> m = new LinkedHashMap<>();
        UUID variantId = rs.getObject("variant_id", UUID.class);
        m.put("variantId", variantId == null ? null : variantId.toString());
        m.put("productTitle", rs.getString("product_title"));
        m.put("variantTitle", rs.getString("variant_title"));
        m.put("sku", rs.getString("sku"));
        m.put("imageUrl", rs.getString("image_url"));
        m.put("quantity", rs.getInt("quantity"));
        return m;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> cast(Object o) {
        return (List<Map<String, Object>>) o;
    }

    private static String iso(Timestamp t) {
        return t == null ? null : t.toInstant().toString();
    }

    private static List<String> strings(Array a) throws SQLException {
        if (a == null) return List.of();
        Object[] arr = (Object[]) a.getArray();
        List<String> out = new ArrayList<>();
        for (Object o : arr) if (o != null) out.add(o.toString());
        return out;
    }

    /** "↩ Black / XL Henley" style one-line labels for the list (product + variant, × qty when more than one). */
    @SuppressWarnings("unchecked")
    static List<String> labels(Object lines) {
        List<String> out = new ArrayList<>();
        for (Map<String, Object> l : (List<Map<String, Object>>) lines) {
            String product = (String) l.get("productTitle");
            String variant = (String) l.get("variantTitle");
            String label = product == null ? "?" : product + (variant == null || variant.isBlank() ? "" : " " + variant);
            int q = ((Number) l.get("quantity")).intValue();
            out.add(q > 1 ? label + " × " + q : label);
        }
        return out;
    }
}
