package com.traceability.labels;

import com.traceability.inventory.LabelPdfRenderer;
import com.traceability.inventory.PackPrintBatchStore;
import com.traceability.inventory.PieceLabelLayout;
import com.traceability.inventory.PrintJobRunner;
import com.traceability.tenancy.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.util.*;

/**
 * Flexible labeling slice 4 — packing labels, printed BEFORE packing in the waybill stack's order.
 *
 * <p>Which orders: scope {@code all} ("Everything ready to pack") = every order in the Pick &amp; Pack queue
 * ({@link PackPrintBatchStore#queueOrderIds} — the queue's own predicate: any courier, no tracking number yet,
 * self-pickup) in the chosen sort; scope {@code new} = the waybill print's own candidates
 * ({@link PackPrintBatchStore#candidates} — Bosta waybills not printed yet, same sort, the first
 * {@link PrintJobRunner#MAX_WAYBILLS_PER_JOB}, never copied); scope {@code last} = the orders of the store's last
 * waybill print in its PDF order; or one order (the queue's single-order "Print labels").
 *
 * <p>Which units: per order (in that sequence), per line (pack-screen order, by id), each UNALLOCATED unit whose
 * variant still has untagged available stock at the main warehouse gets ONE named label (kind {@code pack}, with
 * order_id + order_item_id) — up to the variant's untagged count across the whole print, first come first served
 * in order sequence. A line's issued, unbound pack codes are REUSED (reprinted) before any new one is issued, so the
 * same order item is never issued twice; a per-tenant advisory lock keeps two prints from both issuing.
 *
 * <p>Output: one PDF per part, in sequence — ONE text-only separator as the order's header (tracking number, then
 * order number; the order number alone when there is no tracking number), then that order's labels; nothing after
 * the last order's labels; orders needing no label are skipped.
 * A part holds at most {@link #MAX_LABELS_PER_PART} labels and {@link PrintJobRunner#MAX_WAYBILLS_PER_JOB} orders
 * (what one PDF request takes) and never splits an order.
 */
@Service
public class PackLabelService {

    public static final int MAX_LABELS_PER_PART = LabelCodeService.MAX_CODES_PER_BATCH;
    static final Set<String> SCOPES = Set.of("new", "all", "last");
    static final Set<String> SORTS = Set.of("oldest", "newest");
    private static final PieceLabelLayout.Spec SPEC =
        PieceLabelLayout.Spec.of(LabelCodeService.LABEL_WIDTH_MM, LabelCodeService.LABEL_HEIGHT_MM);
    /** "Still waiting to be packed" — the queue gate's statuses (PICKABLE_SHIPMENT_GATE), without the waybill. */
    private static final String PACKABLE = "o.status IN ('new','ready_to_pick','self_pickup_pending') AND o.on_hold = false";

    /** {@code orderId} set → that one order; else {@code scope} + {@code sort} (sort ignored for scope last). */
    public record Request(String scope, String sort, UUID orderId) {}

    public record Part(int part, int of, int labels, List<UUID> orderIds) {}

    /**
     * labels = labels this print holds (reused + new); orders = orders with at least one label; reused = labels
     * already issued for their line (reprinted, not new); unitsWithoutLabel = unpacked units that get none (their
     * product has no untagged piece left — packed from labeled stock); ordersConsidered = the orders looked at.
     */
    public record Summary(int labels, int orders, int reused, int unitsWithoutLabel, int ordersConsidered, List<Part> parts,
                          boolean queueNeedsLabels) {}

    private record OrderRef(UUID orderId) {}
    private record Item(UUID id, UUID orderId, UUID variantId, int unallocated) {}
    private record Unit(UUID orderId, UUID orderItemId, UUID variantId, UUID existingCodeId) {}
    private record Plan(List<UUID> orders, Map<UUID, List<Unit>> unitsByOrder, int unitsWithoutLabel) {}

    private final JdbcTemplate jdbc;
    private final PackPrintBatchStore candidates;
    private final LabelCodeService codes;

    public PackLabelService(JdbcTemplate jdbc, PackPrintBatchStore candidates, LabelCodeService codes) {
        this.jdbc = jdbc;
        this.candidates = candidates;
        this.codes = codes;
    }

    // ── preview / print ───────────────────────────────────────────────────────

    /** The summary shown before printing ("N labels for M orders") — nothing is issued. */
    @Transactional(readOnly = true)
    public Summary preview(Request req) {
        UUID tenantId = TenantContext.require();
        FlexibleLabeling.require(jdbc, tenantId);
        return withQueueHint(tenantId, req, summarize(plan(tenantId, orders(tenantId, req))));
    }

    /**
     * A waybill scope (new / last) that finds no order needing a label while the queue has some (another courier,
     * no waybill yet, self-pickup) says so — the dialog then points at "Everything ready to pack".
     */
    private Summary withQueueHint(UUID tenantId, Request req, Summary s) {
        if (req.orderId() != null || "all".equals(req.scope()) || s.orders() > 0) return s;
        List<OrderRef> queue = candidates.queueOrderIds("oldest").stream().map(OrderRef::new).toList();
        boolean needs = plan(tenantId, queue).unitsByOrder().values().stream().anyMatch(u -> !u.isEmpty());
        return new Summary(s.labels(), s.orders(), s.reused(), s.unitsWithoutLabel(), s.ordersConsidered(), s.parts(), needs);
    }

    /** Issues the missing codes (reusing a line's unbound ones first) and answers the parts to fetch as PDFs. */
    @Transactional
    public Summary print(Request req, UUID actorUserId) {
        UUID tenantId = TenantContext.require();
        FlexibleLabeling.require(jdbc, tenantId);
        // One print at a time per store: two concurrent prints must not both see "nothing issued yet".
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtext('pack-labels:' || ?::text))::text", String.class, tenantId);
        Plan plan = plan(tenantId, orders(tenantId, req));

        // New codes, grouped per order line, in ≤ 2,000-code issue calls (an order line may straddle two).
        Map<UUID, LabelCodeService.Line> perLine = new LinkedHashMap<>();
        for (UUID orderId : plan.orders()) {
            for (Unit u : plan.unitsByOrder().get(orderId)) {
                if (u.existingCodeId() != null) continue;
                perLine.merge(u.orderItemId(), new LabelCodeService.Line(u.variantId(), 1, u.orderId(), u.orderItemId()),
                    (a, b) -> new LabelCodeService.Line(a.variantId(), a.count() + 1, a.orderId(), a.orderItemId()));
            }
        }
        List<LabelCodeService.Line> chunk = new ArrayList<>();
        int inChunk = 0;
        String key = "pack-print:" + UUID.randomUUID();
        int chunkNo = 0;
        for (LabelCodeService.Line line : perLine.values()) {
            int left = line.count();
            while (left > 0) {
                int take = Math.min(left, LabelCodeService.MAX_CODES_PER_BATCH - inChunk);
                chunk.add(new LabelCodeService.Line(line.variantId(), take, line.orderId(), line.orderItemId()));
                inChunk += take;
                left -= take;
                if (inChunk == LabelCodeService.MAX_CODES_PER_BATCH) {
                    codes.issue(actorUserId, "pack", chunk, key + ":" + chunkNo++);
                    chunk = new ArrayList<>();
                    inChunk = 0;
                }
            }
        }
        if (!chunk.isEmpty()) codes.issue(actorUserId, "pack", chunk, key + ":" + chunkNo);
        return withQueueHint(tenantId, req, summarize(plan));
    }

    // ── one Print for packing (waybills + labels) ─────────────────────────────

    /**
     * The "Print for packing" dialog's counts for one scope + sort (UI pass 2026-10-25): how many Bosta waybills a
     * print would send (the waybill job's own candidates, first {@link PrintJobRunner#MAX_WAYBILLS_PER_JOB}; for
     * {@code last}, the newest waybill print's items whose order isn't cancelled — what its reprint sends) and the
     * label summary for the same scope + sort. {@code lastBatchId} is the newest waybill print (null without one).
     */
    public record PrintPlan(int waybills, UUID lastBatchId, Summary labels) {}

    @Transactional(readOnly = true)
    public PrintPlan printPlan(Request req) {
        UUID tenantId = TenantContext.require();
        FlexibleLabeling.require(jdbc, tenantId);
        if (req == null || req.orderId() != null) throw LabelCodeException.invalidPrintRequest();
        // UUIDv4 is not time-ordered — order by created_at, never id (see CLAUDE.md invariant)
        List<UUID> last = jdbc.queryForList("SELECT id FROM pack_print_batches WHERE tenant_id = ? " +
            "ORDER BY created_at DESC, id DESC LIMIT 1", UUID.class, tenantId);
        UUID lastBatchId = last.isEmpty() ? null : last.get(0);
        int waybills;
        if ("last".equals(req.scope())) {
            Integer n = lastBatchId == null ? 0 : jdbc.queryForObject(
                "SELECT count(*)::int FROM pack_print_batch_items bi " +
                "JOIN merchant_orders o ON o.id = bi.order_id AND o.tenant_id = bi.tenant_id " +
                "WHERE bi.tenant_id = ? AND bi.batch_id = ? AND o.status <> 'cancelled'", Integer.class, tenantId, lastBatchId);
            waybills = n == null ? 0 : n;
        } else {
            if (req.scope() == null || !SCOPES.contains(req.scope()) || req.sort() == null || !SORTS.contains(req.sort())) {
                throw LabelCodeException.invalidPrintRequest();
            }
            waybills = Math.min(PrintJobRunner.MAX_WAYBILLS_PER_JOB, candidates.candidates(req.scope(), req.sort()).size());
        }
        // labels.queueNeedsLabels: this scope has nothing to label but "Everything ready to pack" would (the reason the
        // dialog shows on its disabled labels checkbox).
        return new PrintPlan(waybills, lastBatchId, withQueueHint(tenantId, req, summarize(plan(tenantId, orders(tenantId, req)))));
    }

    // ── which orders ──────────────────────────────────────────────────────────

    private List<OrderRef> orders(UUID tenantId, Request req) {
        if (req == null) throw LabelCodeException.invalidPrintRequest();
        if (req.orderId() != null) {
            Integer ok = jdbc.queryForObject(
                "SELECT count(*)::int FROM merchant_orders o WHERE o.id = ? AND o.tenant_id = ? AND " + PACKABLE,
                Integer.class, req.orderId(), tenantId);
            if (ok == null || ok == 0) throw LabelCodeException.orderNotPackable();
            return List.of(new OrderRef(req.orderId()));
        }
        if (req.scope() == null || !SCOPES.contains(req.scope())) throw LabelCodeException.invalidPrintRequest();
        if ("last".equals(req.scope())) {
            // The store's newest waybill print, in its PDF order; orders packed or cancelled since drop out.
            return jdbc.query(
                "SELECT bi.order_id FROM pack_print_batch_items bi " +
                "JOIN merchant_orders o ON o.id = bi.order_id AND o.tenant_id = bi.tenant_id " +
                "WHERE bi.tenant_id = ? AND bi.batch_id = (" +
                "    SELECT b.id FROM pack_print_batches b WHERE b.tenant_id = ? " +
                // UUIDv4 is not time-ordered — order by created_at, never id (see CLAUDE.md invariant)
                "    ORDER BY b.created_at DESC, b.id DESC LIMIT 1) " +
                "  AND " + PACKABLE + " ORDER BY bi.position",
                (rs, i) -> new OrderRef(rs.getObject(1, UUID.class)), tenantId, tenantId);
        }
        if (req.sort() == null || !SORTS.contains(req.sort())) throw LabelCodeException.invalidPrintRequest();
        if ("all".equals(req.scope())) {
            // "Everything ready to pack": the whole Pick & Pack queue — not the waybill candidates, which are
            // Bosta orders with a waybill only (a store shipping with another courier would see 0 orders).
            return candidates.queueOrderIds(req.sort()).stream().map(OrderRef::new).toList();
        }
        List<PackPrintBatchStore.Candidate> all = candidates.candidates(req.scope(), req.sort());
        return all.subList(0, Math.min(PrintJobRunner.MAX_WAYBILLS_PER_JOB, all.size())).stream()
            .map(c -> new OrderRef(c.orderId())).toList();
    }

    // ── the plan ──────────────────────────────────────────────────────────────

    private Plan plan(UUID tenantId, List<OrderRef> refs) {
        List<UUID> orderIds = refs.stream().map(OrderRef::orderId).distinct().toList();
        if (orderIds.isEmpty()) return new Plan(List.of(), Map.of(), 0);
        UUID[] ids = orderIds.toArray(new UUID[0]);

        Map<UUID, List<Item>> itemsByOrder = new HashMap<>();
        Set<UUID> variants = new HashSet<>();
        jdbc.query(
            "SELECT oi.id, oi.order_id, oi.variant_id, GREATEST(oi.quantity - (" +
            "    SELECT COUNT(*) FROM allocations a " +
            "    WHERE a.order_item_id = oi.id AND a.status IN ('active','packed')), 0)::int AS unallocated " +
            "FROM order_items oi WHERE oi.tenant_id = ? AND oi.order_id = ANY(?) ORDER BY oi.id",
            rs -> {
                Item it = new Item(rs.getObject("id", UUID.class), rs.getObject("order_id", UUID.class),
                    rs.getObject("variant_id", UUID.class), rs.getInt("unallocated"));
                itemsByOrder.computeIfAbsent(it.orderId(), k -> new ArrayList<>()).add(it);
                variants.add(it.variantId());
            },
            tenantId, ids);

        Map<UUID, Deque<UUID>> existing = new HashMap<>();
        jdbc.query(
            "SELECT id, order_item_id FROM label_codes WHERE tenant_id = ? AND order_id = ANY(?) " +
            "  AND kind = 'pack' AND status = 'issued' AND order_item_id IS NOT NULL ORDER BY code",
            rs -> { existing.computeIfAbsent(rs.getObject("order_item_id", UUID.class), k -> new ArrayDeque<>())
                        .add(rs.getObject("id", UUID.class)); },
            tenantId, ids);

        Map<UUID, Integer> budget = new HashMap<>();
        if (!variants.isEmpty()) {
            List<UUID> main = jdbc.queryForList(
                "SELECT id FROM locations WHERE tenant_id = ? AND is_fulfillment", UUID.class, tenantId);
            if (main.isEmpty()) throw LabelCodeException.noMainWarehouse();
            jdbc.query(
                "SELECT variant_id, count(*)::int AS n FROM pieces WHERE tenant_id = ? AND variant_id = ANY(?) " +
                "  AND current_location_id = ? AND short_code IS NULL AND status = 'available' GROUP BY variant_id",
                rs -> { budget.put(rs.getObject("variant_id", UUID.class), rs.getInt("n")); },
                tenantId, variants.toArray(new UUID[0]), main.get(0));
        }

        Map<UUID, List<Unit>> unitsByOrder = new LinkedHashMap<>();
        int without = 0;
        for (UUID orderId : orderIds) {
            List<Unit> units = new ArrayList<>();
            for (Item it : itemsByOrder.getOrDefault(orderId, List.of())) {
                Deque<UUID> reuse = existing.getOrDefault(it.id(), new ArrayDeque<>());
                for (int u = 0; u < it.unallocated(); u++) {
                    int left = budget.getOrDefault(it.variantId(), 0);
                    if (left <= 0) { without++; continue; }
                    budget.put(it.variantId(), left - 1);
                    units.add(new Unit(orderId, it.id(), it.variantId(), reuse.pollFirst()));
                }
            }
            unitsByOrder.put(orderId, units);
        }
        return new Plan(orderIds, unitsByOrder, without);
    }

    private Summary summarize(Plan plan) {
        List<List<UUID>> partOrders = new ArrayList<>();
        List<Integer> partLabels = new ArrayList<>();
        int labels = 0, reused = 0, orders = 0;
        for (UUID orderId : plan.orders()) {
            List<Unit> units = plan.unitsByOrder().get(orderId);
            if (units.isEmpty()) continue;
            orders++;
            labels += units.size();
            reused += (int) units.stream().filter(u -> u.existingCodeId() != null).count();
            int last = partOrders.size() - 1;
            if (last < 0 || (partLabels.get(last) + units.size() > MAX_LABELS_PER_PART && partLabels.get(last) > 0)
                    || partOrders.get(last).size() == PrintJobRunner.MAX_WAYBILLS_PER_JOB) {
                partOrders.add(new ArrayList<>());
                partLabels.add(0);
                last++;
            }
            partOrders.get(last).add(orderId);
            partLabels.set(last, partLabels.get(last) + units.size());
        }
        List<Part> parts = new ArrayList<>();
        for (int i = 0; i < partOrders.size(); i++) {
            parts.add(new Part(i + 1, partOrders.size(), partLabels.get(i), List.copyOf(partOrders.get(i))));
        }
        return new Summary(labels, orders, reused, plan.unitsWithoutLabel(), plan.orders().size(), parts, false);
    }

    // ── the PDF ───────────────────────────────────────────────────────────────

    /**
     * One part: for each order in the given sequence a separator (its header), then its issued, unbound pack codes
     * (line order, then code; at most the line's unpacked units). Orders with none are skipped. Nothing is issued here.
     */
    @Transactional(readOnly = true)
    public byte[] pdf(List<UUID> orderIds) throws IOException {
        UUID tenantId = TenantContext.require();
        FlexibleLabeling.require(jdbc, tenantId);
        if (orderIds == null || orderIds.isEmpty() || orderIds.size() > PrintJobRunner.MAX_WAYBILLS_PER_JOB) {
            throw LabelCodeException.invalidPrintRequest();
        }
        UUID[] ids = orderIds.stream().distinct().toArray(UUID[]::new);
        record Row(UUID orderId, UUID itemId, int unallocated, LabelPdfRenderer.Piece label) {}
        Map<UUID, List<Row>> byOrder = new HashMap<>();
        jdbc.query(
            "SELECT lc.order_id, lc.order_item_id, lc.code, pr.title AS product_title, v.title AS variant_title, v.sku, " +
            "       GREATEST(oi.quantity - (SELECT COUNT(*) FROM allocations a " +
            "           WHERE a.order_item_id = oi.id AND a.status IN ('active','packed')), 0)::int AS unallocated " +
            "FROM label_codes lc " +
            "JOIN order_items oi ON oi.id = lc.order_item_id AND oi.tenant_id = lc.tenant_id " +
            "JOIN merchant_orders o ON o.id = lc.order_id AND o.tenant_id = lc.tenant_id " +
            "JOIN variants v ON v.id = lc.variant_id JOIN products pr ON pr.id = v.product_id " +
            "WHERE lc.tenant_id = ? AND lc.order_id = ANY(?) AND lc.kind = 'pack' AND lc.status = 'issued' " +
            "ORDER BY lc.order_item_id, lc.code",
            rs -> {
                byOrder.computeIfAbsent(rs.getObject("order_id", UUID.class), k -> new ArrayList<>()).add(new Row(
                    rs.getObject("order_id", UUID.class), rs.getObject("order_item_id", UUID.class), rs.getInt("unallocated"),
                    new LabelPdfRenderer.Piece(rs.getString("code"), rs.getString("product_title"),
                        rs.getString("variant_title"), rs.getString("sku"))));
            },
            tenantId, ids);
        Map<UUID, String[]> heads = new HashMap<>();
        jdbc.query(
            "SELECT o.id, o.number, fs.tracking_number FROM merchant_orders o " +
            "LEFT JOIN LATERAL (SELECT tracking_number FROM shipments s " +
            "    WHERE s.order_id = o.id AND s.tenant_id = o.tenant_id AND s.shipment_leg = 'forward' " +
            // UUIDv4 is not time-ordered — order by created_at, never id (see CLAUDE.md invariant)
            "    ORDER BY s.created_at DESC, s.id DESC LIMIT 1) fs ON true " +
            "WHERE o.tenant_id = ? AND o.id = ANY(?)",
            rs -> { heads.put(rs.getObject("id", UUID.class),
                        new String[] { rs.getString("number"), rs.getString("tracking_number") }); },
            tenantId, ids);

        List<LabelPdfRenderer.Page> pages = new ArrayList<>();
        int labels = 0;
        for (UUID orderId : orderIds.stream().distinct().toList()) {
            List<Row> rows = byOrder.getOrDefault(orderId, List.of());
            Map<UUID, Integer> perItem = new HashMap<>();
            // Rows come in line order = the pack screen's (order_items.id, Postgres uuid order), then code order.
            List<LabelPdfRenderer.Page> orderLabels = new ArrayList<>();
            for (Row r : rows) {
                int used = perItem.merge(r.itemId(), 1, Integer::sum);
                if (used <= r.unallocated()) orderLabels.add(r.label());
            }
            if (orderLabels.isEmpty()) continue;
            labels += orderLabels.size();
            // Live test 2026-10-25: the separator is the order's HEADER — before its labels, never after them.
            String[] head = heads.getOrDefault(orderId, new String[] { null, null });
            pages.add(separator(head[0], head[1]));
            pages.addAll(orderLabels);
        }
        if (labels > MAX_LABELS_PER_PART) throw LabelCodeException.invalidPrintRequest();
        if (labels == 0) {
            throw new LabelCodeException(org.springframework.http.HttpStatus.CONFLICT, LabelCodeException.Code.NO_ISSUED_CODES,
                "No labels left to print for these orders", "لا توجد ملصقات متبقية لطباعتها لهذه الطلبات");
        }
        return LabelPdfRenderer.renderPages(pages, SPEC);
    }

    /** Tracking number on line 1 and the order number on line 2; the order number alone when there's no tracking. */
    static LabelPdfRenderer.Separator separator(String orderNumber, String trackingNumber) {
        String number = orderNumber == null ? "" : orderNumber;
        return trackingNumber == null || trackingNumber.isBlank()
            ? new LabelPdfRenderer.Separator(number, null)
            : new LabelPdfRenderer.Separator(trackingNumber, number);
    }

    // ── replacement ───────────────────────────────────────────────────────────

    /**
     * "Print 1 replacement label" in a pack screen: ONE code (kind replacement, with the order refs) for that line's
     * variant, as the normal piece label. Refused when the product has no untagged piece left (the label could never
     * be used). Idempotent by key (LabelCodeService). Any user — workers print it inside a pack session.
     */
    @Transactional
    public byte[] replacement(UUID orderId, UUID orderItemId, String idempotencyKey, UUID actorUserId) throws IOException {
        UUID tenantId = TenantContext.require();
        FlexibleLabeling.require(jdbc, tenantId);
        if (orderId == null || orderItemId == null) throw LabelCodeException.invalidPrintRequest();
        List<UUID> variant = jdbc.queryForList(
            "SELECT oi.variant_id FROM order_items oi JOIN merchant_orders o ON o.id = oi.order_id AND o.tenant_id = oi.tenant_id " +
            "WHERE oi.id = ? AND oi.order_id = ? AND oi.tenant_id = ? AND " + PACKABLE, UUID.class, orderItemId, orderId, tenantId);
        if (variant.isEmpty()) throw LabelCodeException.orderNotPackable();
        Integer untagged = jdbc.queryForObject(
            "SELECT count(*)::int FROM pieces p JOIN locations l ON l.id = p.current_location_id AND l.is_fulfillment " +
            "WHERE p.tenant_id = ? AND p.variant_id = ? AND p.short_code IS NULL AND p.status = 'available'",
            Integer.class, tenantId, variant.get(0));
        if (untagged == null || untagged == 0) throw LabelCodeException.noUntaggedStock();
        LabelCodeService.Batch batch = codes.issue(actorUserId, "replacement",
            List.of(new LabelCodeService.Line(variant.get(0), 1, orderId, orderItemId)), idempotencyKey);
        return codes.batchPdf(batch.id());
    }
}
