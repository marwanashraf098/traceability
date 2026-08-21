package com.traceability.fulfillment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

@RestController
@RequestMapping("/api/v1/orders")
public class OrderController {

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final TransactionTemplate tx;

    public OrderController(JdbcTemplate jdbc, ObjectMapper mapper,
                           PlatformTransactionManager txm) {
        this.jdbc   = jdbc;
        this.mapper = mapper;
        this.tx     = new TransactionTemplate(txm);
    }

    // ── response records ─────────────────────────────────────────────────────

    public record OrderSummary(
        String id, String number, String customerName, String customerPhone,
        String status, boolean onHold, BigDecimal codAmount,
        Instant placedAt, String trackingNumber,
        String deliveryState, String exceptionReason, String bostaLinkStatus,
        int failedDeliveryAttempts, Boolean isDelayed, Boolean slaBreached,
        Instant notTracedAt, boolean isExchange,
        OrderStatusDeriver.DerivedOrderStatus derivedStatus) {}

    public record OrderPage(List<OrderSummary> items, int page, int size, long total) {}

    public record AllocatedPiece(String pieceId, String barcode, String status) {}

    public record OrderItem(
        String id, String productTitle, String variantTitle,
        String sku, int quantity, String imageUrl, List<AllocatedPiece> allocatedPieces) {}

    public record AttemptEntry(
        String attemptDate, String type, boolean succeeded,
        String courierName, String courierPhone, String failureReason) {}

    public record DeliveryHistoryEntry(
        String state, Integer providerState,
        Integer exceptionCode, String exceptionReason,
        Instant occurredAt) {}

    public record ShipmentDetail(
        String id, String trackingNumber, String provider,
        String internalState, String shipmentLeg,
        int numberOfAttempts, int failedDeliveryAttempts,
        String awbUrl, Integer exceptionCode, String exceptionReason,
        Boolean isDelayed, Boolean slaBreached,
        Instant scheduledAt, String courierName, String courierPhone,
        String lastFailureReason,
        List<AttemptEntry> attempts,
        List<DeliveryHistoryEntry> deliveryHistory,
        // A3.1 — leg-scoped badge from this shipment's OWN internal_state only (no
        // order-level precedence). The frontend renders it for the return leg ONLY; the
        // forward leg's status lives solely in the order header (deriveOrderStatus).
        OrderStatusDeriver.LegStatus legStatus) {}

    public record OrderDetail(
        String id, String number, String customerName, String customerPhone,
        Object address, String paymentMethod, BigDecimal codAmount,
        String status, boolean onHold, String holdReason,
        Instant placedAt, Instant createdAt,
        List<OrderItem> items, List<ShipmentDetail> shipments,
        String bostaLinkStatus, Instant notTracedAt, boolean isExchange,
        OrderStatusDeriver.DerivedOrderStatus derivedStatus,
        // Orders-rebuild pass (a), drawer "View in Shopify" — DTO-only add, no new persistence.
        // Pre-built from stores.shop_domain + orders.external_id (Shopify GID form, e.g.
        // "gid://shopify/Order/123"); null whenever either input is missing/unparseable, in
        // which case the frontend omits the button rather than rendering a dead link.
        String shopifyOrderUrl) {}

    // ── fulfillment funnel — today (Overview dashboard) ─────────────────────

    public record FunnelCounts(int newCount, int picking, int packed, int courier, int delivered) {}

    /**
     * FR-Overview §1.3 — bucket counts of TODAY's orders by their derived status.
     * Reuses the exact raw-row shape list()'s LATERAL join already selects, then calls
     * OrderStatusDeriver.derive() per row in Java — the SAME single source of truth
     * used by list()/detail(), never a second derivation. No client-side re-derivation
     * anywhere; this endpoint exists precisely so the frontend never has to.
     *
     * Bucket mapping (5 mockup buckets ← primaryKey):
     *   New       — status.new, status.confirmed, status.ready_to_pick
     *   Picking   — status.picking
     *   Packed    — status.packed
     *   Courier   — status.awaiting_courier, status.in_transit, status.label_created
     *   Delivered — status.delivered
     *
     * status.label_created / status.awaiting_courier (a shipment record exists, AWB linked)
     * sit in Courier ONLY when OrderStatusDeriver.packedConfirmed is true — i.e. Traced's own
     * order.status independently confirms packing happened, or the shipment has progressed
     * far enough (courier physically holding it, or a non-cancelled terminal state) that
     * packing is physically unavoidable. A shipment record existing is NOT, by itself, proof
     * of packing — Mode-B webhook auto-match (ShipmentLinkService.tryMatchDelivery(),
     * unguarded) can create that shipment row while the order is still pre-pack. When
     * packedConfirmed is false, the order routes to New or Picking instead, by its own raw
     * order.status — never counted as Courier while packing hasn't actually happened. See
     * PROGRESS.md "Orders — status-split fix" for the full diagnosis (this comment previously
     * asserted "past packing, AWB linked" unconditionally — that premise was disproven).
     *
     * Any other terminal/exception primaryKey (returning, returned, lost, cancelled,
     * delivery_failed, needs_attention, terminated) falls outside all 5 buckets and is
     * not counted — the funnel is a forward-pipeline view, not the full order lifecycle.
     *
     * "Today" = o.placed_at::date = CURRENT_DATE, same server/session-date boundary
     * dailyCounts() already uses (no tenant-timezone threading exists anywhere in this
     * codebase today — not introduced here).
     */
    @GetMapping("/funnel")
    @PreAuthorize("hasAnyRole('OWNER', 'MANAGER')")
    @Transactional(readOnly = true)
    public FunnelCounts funnel() {
        record StatusRow(String orderStatus, OrderStatusDeriver.DerivedOrderStatus derived) {}

        List<StatusRow> rows = jdbc.query("""
            SELECT o.status, o.not_traced_at,
                   s.internal_state            AS delivery_state,
                   COALESCE(s.failed_delivery_attempts, 0) AS failed_delivery_attempts,
                   COALESCE(s.number_of_attempts, 0)       AS number_of_attempts,
                   s.exception_code, s.is_delayed, s.sla_breached,
                   s.max_progress_rank
            FROM orders o
            LEFT JOIN LATERAL (
                SELECT internal_state, number_of_attempts,
                       exception_code, is_delayed, sla_breached,
                       -- Orders-rebuild pass (b): canonical attempt count, not the
                       -- shipments.failed_delivery_attempts stored counter (which the
                       -- pass-(a) subline and this bucket must now agree with by
                       -- construction — see the /orders/{id}/timeline read). exception is
                       -- NOT 1:1 with a delivery attempt (Bosta states 47/101/102/103 all
                       -- collapse into internal_state='exception' — damage-found/
                       -- investigation/hub-limbo are not attempts); ndr_codes.category=
                       -- 'forward' scopes to the actual §8.4 delivery-attempt NDR codes only.
                       -- LEFT JOIN (not INNER) — exception_code has no FK to ndr_codes, so an
                       -- unrecognized/future code would silently vanish from an INNER JOIN,
                       -- undercounting a real attempt. Fail-open on "code present but
                       -- unmapped" (still counts — better to over-flag than hide a genuine
                       -- delivery problem); fail-closed on "no code at all" (states
                       -- 101/102/103 typically carry none — not an attempt, see above).
                       (SELECT COUNT(*) FROM shipment_status_history h2
                        LEFT JOIN ndr_codes n ON n.code = h2.exception_code
                        WHERE h2.shipment_id = sh.id AND h2.internal_state = 'exception'
                          AND h2.exception_code IS NOT NULL
                          AND (n.category = 'forward' OR n.category IS NULL)
                       ) AS failed_delivery_attempts,
                       COALESCE(
                           (SELECT MAX(CASE h.internal_state
                                       WHEN 'created'      THEN 1
                                       WHEN 'with_courier'  THEN 2
                                       WHEN 'returning'     THEN 3
                                       WHEN 'exception'     THEN 0
                                   END)
                            FROM shipment_status_history h
                            WHERE h.shipment_id = sh.id),
                           CASE sh.internal_state
                               WHEN 'created'      THEN 1
                               WHEN 'with_courier'  THEN 2
                               WHEN 'returning'     THEN 3
                               WHEN 'exception'     THEN 0
                           END
                       ) AS max_progress_rank
                FROM shipments sh
                WHERE order_id = o.id AND tenant_id = o.tenant_id
                  AND shipment_leg = 'forward'
                ORDER BY created_at DESC, id DESC
                LIMIT 1
            ) s ON true
            WHERE o.tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid
              AND o.placed_at::date = CURRENT_DATE
            """,
            (rs, i) -> {
                String orderStatus = rs.getString("status");
                Timestamp notTracedAt = rs.getTimestamp("not_traced_at");
                var derived = OrderStatusDeriver.derive(
                    orderStatus,
                    rs.getString("delivery_state"),
                    rs.getObject("max_progress_rank", Integer.class),
                    rs.getInt("number_of_attempts"),
                    rs.getInt("failed_delivery_attempts"),
                    rs.getObject("exception_code", Integer.class),
                    rs.getObject("is_delayed", Boolean.class),
                    rs.getObject("sla_breached", Boolean.class),
                    notTracedAt != null);
                return new StatusRow(orderStatus, derived);
            });

        int newCount = 0, picking = 0, packed = 0, courier = 0, delivered = 0;
        for (StatusRow row : rows) {
            String primaryKey = row.derived().primaryKey();
            boolean isCourierAwbState =
                "status.awaiting_courier".equals(primaryKey) || "status.label_created".equals(primaryKey);
            if (isCourierAwbState && !row.derived().packedConfirmed()) {
                // A shipment record exists but packing hasn't actually happened yet (the
                // Mode-B early-webhook-match case) — route by the order's own raw status,
                // same as an order with no shipment at all would be, not into Courier.
                if ("picking".equals(row.orderStatus())) picking++; else newCount++;
                continue;
            }
            switch (primaryKey) {
                case "status.new", "status.confirmed", "status.ready_to_pick" -> newCount++;
                case "status.picking" -> picking++;
                case "status.packed" -> packed++;
                case "status.awaiting_courier", "status.in_transit", "status.label_created" -> courier++;
                case "status.delivered" -> delivered++;
                default -> { /* outside the forward-pipeline funnel — not counted */ }
            }
        }
        return new FunnelCounts(newCount, picking, packed, courier, delivered);
    }

    // ── orders summary — all-time, 5-tile calm row (Orders list) ────────────

    public record OrderSummaryCounts(long total, int processing, int withCourier, int delivered, int returned) {}

    /**
     * GET /orders/summary — reuses the exact raw-row shape funnel()/list() already select,
     * calling OrderStatusDeriver.derive() per row in Java — the SAME single source of truth,
     * never a second derivation. Unlike funnel() (today-scoped), this covers ALL of the
     * tenant's orders — the "how's my order book doing overall" row cut from the original
     * Orders redesign for lack of an honest data source (see PROGRESS.md "Orders screen restyle").
     *
     * Bucket mapping mirrors funnel()'s grouping exactly (kept in sync manually — if funnel()'s
     * buckets ever change, this must change with it):
     *   Processing   — status.new, status.confirmed, status.ready_to_pick, status.picking, status.packed,
     *                  AND status.awaiting_courier/status.label_created when packedConfirmed is false
     *                  (a shipment record exists but packing hasn't actually happened yet — see below)
     *   With courier — status.awaiting_courier, status.in_transit, status.label_created, ONLY when
     *                  OrderStatusDeriver.packedConfirmed is true
     *   Delivered    — status.delivered
     *   Returned     — status.returned only (terminal). status.returning (in-progress) is
     *                  deliberately left uncounted.
     *
     * A shipment record existing (label_created/awaiting_courier) is NOT, by itself, proof
     * packing happened — Mode-B webhook auto-match (ShipmentLinkService.tryMatchDelivery(),
     * unguarded) can create that shipment row while the order is still pre-pack. Counting it as
     * "With courier" would overstate how many orders have actually left the warehouse. See
     * PROGRESS.md "Orders — status-split fix" for the full diagnosis.
     *
     * The four breakdown buckets intentionally do not sum to `total` — cancelled, lost, terminated,
     * needs_attention, delivery_failed, self_pickup_pending, and returning are uncounted by design,
     * exactly like funnel() already leaves several primaryKeys outside its 5 buckets. Not a bug.
     *
     * Scale note: unlike funnel(), this has no date filter — every order the tenant has ever placed
     * gets pulled into Java for per-row derivation. Fine at pilot volume; would need a materialized/
     * cached count if order volume grows into the tens of thousands. Not pre-optimized here.
     */
    @GetMapping("/summary")
    @PreAuthorize("hasAnyRole('OWNER', 'MANAGER')")
    public OrderSummaryCounts summary() {
        // tx.execute(), not @Transactional — this method now runs two sequential queries
        // that must share ONE bound connection for TenantAwareConnection's SET LOCAL
        // app.current_tenant to stay in effect across both (SET LOCAL only persists for
        // the life of an open transaction) — same reasoning as detail()/timeline().
        return tx.execute(txs -> {
            List<OrderStatusDeriver.DerivedOrderStatus> rows = jdbc.query("""
                SELECT o.status, o.not_traced_at,
                       s.internal_state            AS delivery_state,
                       COALESCE(s.failed_delivery_attempts, 0) AS failed_delivery_attempts,
                       COALESCE(s.number_of_attempts, 0)       AS number_of_attempts,
                       s.exception_code, s.is_delayed, s.sla_breached,
                       s.max_progress_rank
                FROM orders o
                LEFT JOIN LATERAL (
                    SELECT internal_state, number_of_attempts,
                           exception_code, is_delayed, sla_breached,
                           -- Orders-rebuild pass (b): canonical attempt count, not the
                           -- shipments.failed_delivery_attempts stored counter (which the
                           -- pass-(a) subline and this bucket must now agree with by
                           -- construction — see the /orders/{id}/timeline read). exception is
                           -- NOT 1:1 with a delivery attempt (Bosta states 47/101/102/103 all
                           -- collapse into internal_state='exception' — damage-found/
                           -- investigation/hub-limbo are not attempts); ndr_codes.category=
                           -- 'forward' scopes to the actual §8.4 delivery-attempt NDR codes only.
                           -- LEFT JOIN (not INNER) — see funnel()'s identical comment: an
                           -- unrecognized code must not silently vanish and undercount.
                           (SELECT COUNT(*) FROM shipment_status_history h2
                            LEFT JOIN ndr_codes n ON n.code = h2.exception_code
                            WHERE h2.shipment_id = sh.id AND h2.internal_state = 'exception'
                              AND h2.exception_code IS NOT NULL
                              AND (n.category = 'forward' OR n.category IS NULL)
                           ) AS failed_delivery_attempts,
                           COALESCE(
                               (SELECT MAX(CASE h.internal_state
                                           WHEN 'created'      THEN 1
                                           WHEN 'with_courier'  THEN 2
                                           WHEN 'returning'     THEN 3
                                           WHEN 'exception'     THEN 0
                                       END)
                                FROM shipment_status_history h
                                WHERE h.shipment_id = sh.id),
                               CASE sh.internal_state
                                   WHEN 'created'      THEN 1
                                   WHEN 'with_courier'  THEN 2
                                   WHEN 'returning'     THEN 3
                                   WHEN 'exception'     THEN 0
                               END
                           ) AS max_progress_rank
                    FROM shipments sh
                    WHERE order_id = o.id AND tenant_id = o.tenant_id
                      AND shipment_leg = 'forward'
                    ORDER BY created_at DESC, id DESC
                    LIMIT 1
                ) s ON true
                WHERE o.tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid
                """,
                (rs, i) -> {
                    Timestamp notTracedAt = rs.getTimestamp("not_traced_at");
                    return OrderStatusDeriver.derive(
                        rs.getString("status"),
                        rs.getString("delivery_state"),
                        rs.getObject("max_progress_rank", Integer.class),
                        rs.getInt("number_of_attempts"),
                        rs.getInt("failed_delivery_attempts"),
                        rs.getObject("exception_code", Integer.class),
                        rs.getObject("is_delayed", Boolean.class),
                        rs.getObject("sla_breached", Boolean.class),
                        notTracedAt != null);
                });

            int processing = 0, delivered = 0, returned = 0;
            for (OrderStatusDeriver.DerivedOrderStatus derived : rows) {
                String primaryKey = derived.primaryKey();
                boolean isCourierAwbState =
                    "status.awaiting_courier".equals(primaryKey) || "status.label_created".equals(primaryKey);
                if (isCourierAwbState && !derived.packedConfirmed()) {
                    // A shipment record exists but packing hasn't actually happened yet — Processing
                    // is already one merged bucket, so no need to distinguish which raw pre-pack
                    // status it's really at (unlike funnel()'s finer-grained New/Picking split).
                    processing++;
                    continue;
                }
                switch (primaryKey) {
                    case "status.new", "status.confirmed", "status.ready_to_pick",
                         "status.picking", "status.packed" -> processing++;
                    // withCourier is NOT counted here — see below. It no longer comes from
                    // derived.primaryKey()/packedConfirmed() at all.
                    case "status.delivered" -> delivered++;
                    case "status.returned" -> returned++;
                    default -> { /* cancelled/lost/terminated/needs_attention/delivery_failed/
                                    self_pickup_pending/returning/with_courier bucket — see below */ }
                }
            }

            // Orders-rebuild pass (b) — In-transit convergence (review finding: the tab's
            // count and its click-through list must use ONE predicate, never two that can
            // silently diverge). This is the SAME raw predicate list()'s
            // `deliveryState=with_courier` filter uses — identical WHERE/ORDER BY/LIMIT
            // text to the LATERAL above and in list()/detail()'s fwd query, kept in sync
            // manually (same convention already used for the max_progress_rank CASE across
            // all four). NOT derived.primaryKey()/packedConfirmed() — that's a deliberate,
            // documented divergence from funnel() (untouched, still today-only +
            // packedConfirmed-gated): Orders' "In transit" tab is literal all-time raw
            // shipment-state membership (count==list, by construction); Overview's funnel
            // stays a stricter today-only, packing-confirmed snapshot. Two surfaces, two
            // questions, on purpose.
            long withCourier = jdbc.queryForObject(
                """
                SELECT COUNT(*)
                FROM orders o
                LEFT JOIN LATERAL (
                    SELECT internal_state
                    FROM shipments sh
                    WHERE order_id = o.id AND tenant_id = o.tenant_id
                      AND shipment_leg = 'forward'
                    ORDER BY created_at DESC, id DESC
                    LIMIT 1
                ) s ON true
                WHERE o.tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid
                  AND s.internal_state = 'with_courier'
                """,
                Long.class);

            return new OrderSummaryCounts(rows.size(), processing, (int) (long) withCourier, delivered, returned);
        });
    }

    // ── daily order counts (dashboard chart) ────────────────────────────────

    public record DayCount(String date, int count) {}

    @GetMapping("/daily-counts")
    @PreAuthorize("hasAnyRole('OWNER', 'MANAGER')")
    @Transactional(readOnly = true)
    public List<DayCount> dailyCounts(@RequestParam(defaultValue = "30") int days) {
        days = Math.max(7, Math.min(days, 90));
        String from = LocalDate.now().minusDays(days - 1).toString();
        return jdbc.query("""
                WITH gs AS (
                    SELECT generate_series(?::date, CURRENT_DATE, '1 day'::interval)::date AS day
                ),
                oc AS (
                    SELECT DATE(placed_at) AS day, COUNT(*)::int AS cnt
                    FROM orders
                    WHERE tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid
                      AND placed_at >= ?::date
                    GROUP BY DATE(placed_at)
                )
                SELECT gs.day::text AS date, COALESCE(oc.cnt, 0) AS count
                FROM gs LEFT JOIN oc ON oc.day = gs.day
                ORDER BY gs.day
                """,
                (rs, row) -> new DayCount(rs.getString("date"), rs.getInt("count")),
                from, from);
    }

    // ── list ─────────────────────────────────────────────────────────────────

    @GetMapping
    @PreAuthorize("hasAnyRole('OWNER', 'MANAGER')")
    public OrderPage list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String deliveryState,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String tracking,
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "20") int size) {

        size = Math.min(size, 100);
        int offset = page * size;

        // Build dynamic WHERE clauses.
        // Explicit tenant filter is defense-in-depth on top of RLS — also ensures
        // correct scoping when connecting as BYPASSRLS roles (e.g. in tests).
        List<Object> params = new ArrayList<>();
        StringBuilder where = new StringBuilder(
            "WHERE o.tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid");

        if (status != null && !status.isBlank()) {
            where.append(" AND o.status = ?::order_status");
            params.add(status);
        }
        // Orders-rebuild pass (a) fix — o.status alone cannot express "in transit" /
        // "delivered" / "returned" for the courier pipeline: the app never writes
        // orders.status to 'with_courier' or 'returned' (confirmed by grep — only
        // ShipmentLinkService writes 'awaiting_pickup' and FulfillService writes
        // 'delivered'/'cancelled', the latter gated to self-pickup handover only). The
        // real delivery signal lives solely in the shipment's own internal_state, already
        // selected via the LATERAL `s` alias below — filter on that instead of the raw
        // order pipeline column. Reuses the existing join, no new backend derivation.
        if (deliveryState != null && !deliveryState.isBlank()) {
            where.append(" AND s.internal_state = ?::shipment_internal_state");
            params.add(deliveryState);
        }
        if (q != null && !q.isBlank()) {
            where.append(" AND (o.number ILIKE ? OR o.customer_name ILIKE ? OR o.customer_phone ILIKE ?)");
            String like = "%" + q.trim() + "%";
            params.add(like); params.add(like); params.add(like);
        }
        if (tracking != null && !tracking.isBlank()) {
            where.append(" AND s.tracking_number ILIKE ?");
            params.add("%" + tracking.trim() + "%");
        }

        // LATERAL picks the latest forward shipment per order (by created_at, id DESC as
        // tiebreak) so re-shipped orders (terminated + new active) don't produce duplicate
        // rows or inflate COUNT(*).
        // UUIDv4 is not time-ordered — order by created_at, never id.
        //
        // max_progress_rank mirrors OrderStatusDeriver.PROGRESS_RANK/maxProgressRank() —
        // keep the two in sync manually; OrderStatusListDetailParityTest asserts they agree.
        // COALESCE falls back to the shipment's own current internal_state when history is
        // empty (pre-V40 shipments have zero shipment_status_history rows — see that
        // migration's backfill note — so history-only MAX would silently misrank them).
        String baseJoin = """
            FROM orders o
            LEFT JOIN LATERAL (
                SELECT id, tracking_number, internal_state, exception_reason,
                       is_delayed, sla_breached,
                       number_of_attempts, exception_code,
                       -- Canonical attempt count — see the funnel()/summary() LATERALs'
                       -- identical comment (LEFT JOIN, fail-open on unmapped codes, fail-
                       -- closed on no code). Kept in sync manually across the three.
                       (SELECT COUNT(*) FROM shipment_status_history h2
                        LEFT JOIN ndr_codes n ON n.code = h2.exception_code
                        WHERE h2.shipment_id = sh.id AND h2.internal_state = 'exception'
                          AND h2.exception_code IS NOT NULL
                          AND (n.category = 'forward' OR n.category IS NULL)
                       ) AS failed_delivery_attempts,
                       COALESCE(
                           (SELECT MAX(CASE h.internal_state
                                       WHEN 'created'      THEN 1
                                       WHEN 'with_courier'  THEN 2
                                       WHEN 'returning'     THEN 3
                                       WHEN 'exception'     THEN 0
                                   END)
                            FROM shipment_status_history h
                            WHERE h.shipment_id = sh.id),
                           CASE sh.internal_state
                               WHEN 'created'      THEN 1
                               WHEN 'with_courier'  THEN 2
                               WHEN 'returning'     THEN 3
                               WHEN 'exception'     THEN 0
                           END
                       ) AS max_progress_rank
                FROM shipments sh
                WHERE order_id = o.id AND tenant_id = o.tenant_id
                  AND shipment_leg = 'forward'
                ORDER BY created_at DESC, id DESC
                LIMIT 1
            ) s ON true
            LEFT JOIN exchanges e ON e.outbound_order_id = o.id
            """ + where;

        long total = tx.execute(txs -> jdbc.queryForObject(
            "SELECT COUNT(*) " + baseJoin, Long.class, params.toArray()));

        List<Object> pageParams = new ArrayList<>(params);
        pageParams.add(size);
        pageParams.add(offset);

        List<OrderSummary> items = tx.execute(txs -> jdbc.query(
            """
            SELECT o.id, o.number, o.customer_name, o.customer_phone,
                   o.status, o.on_hold, o.cod_amount, o.placed_at,
                   s.tracking_number,
                   s.internal_state            AS delivery_state,
                   s.exception_reason          AS exception_reason,
                   o.bosta_link_status,
                   COALESCE(s.failed_delivery_attempts, 0) AS failed_delivery_attempts,
                   COALESCE(s.number_of_attempts, 0) AS number_of_attempts,
                   s.exception_code,
                   s.max_progress_rank,
                   s.is_delayed,
                   s.sla_breached,
                   o.not_traced_at,
                   (e.id IS NOT NULL) AS is_exchange
            """ + baseJoin + """
             ORDER BY o.placed_at DESC NULLS LAST, o.created_at DESC
             LIMIT ? OFFSET ?
            """,
            (rs, i) -> {
                Instant notTracedAt = rs.getTimestamp("not_traced_at") != null
                    ? rs.getTimestamp("not_traced_at").toInstant() : null;
                var derived = OrderStatusDeriver.derive(
                    rs.getString("status"),
                    rs.getString("delivery_state"),
                    rs.getObject("max_progress_rank", Integer.class),
                    rs.getInt("number_of_attempts"),
                    rs.getInt("failed_delivery_attempts"),
                    rs.getObject("exception_code", Integer.class),
                    rs.getObject("is_delayed", Boolean.class),
                    rs.getObject("sla_breached", Boolean.class),
                    notTracedAt != null);
                return new OrderSummary(
                    rs.getObject("id", UUID.class).toString(),
                    rs.getString("number"),
                    rs.getString("customer_name"),
                    rs.getString("customer_phone"),
                    rs.getString("status"),
                    rs.getBoolean("on_hold"),
                    rs.getBigDecimal("cod_amount"),
                    rs.getTimestamp("placed_at") != null ? rs.getTimestamp("placed_at").toInstant() : null,
                    rs.getString("tracking_number"),
                    rs.getString("delivery_state"),
                    rs.getString("exception_reason"),
                    rs.getString("bosta_link_status"),
                    rs.getInt("failed_delivery_attempts"),
                    rs.getObject("is_delayed", Boolean.class),
                    rs.getObject("sla_breached", Boolean.class),
                    notTracedAt,
                    rs.getBoolean("is_exchange"),
                    derived
                );
            },
            pageParams.toArray()));

        return new OrderPage(items, page, size, total);
    }

    // ── detail ────────────────────────────────────────────────────────────────

    @GetMapping("/{orderId}")
    @PreAuthorize("hasAnyRole('OWNER', 'MANAGER')")
    public OrderDetail detail(@PathVariable UUID orderId) {
        return tx.execute(txs -> {

            // Fetch order row — explicit tenant filter is defense-in-depth on top of RLS.
            OrderDetail order = jdbc.query(
                """
                SELECT o.id, o.number, o.customer_name, o.customer_phone,
                       o.address, o.payment_method, o.cod_amount,
                       o.status, o.on_hold, o.hold_reason,
                       o.placed_at, o.created_at, o.bosta_link_status, o.not_traced_at,
                       o.external_id, st.shop_domain,
                       (e.id IS NOT NULL) AS is_exchange
                FROM orders o
                LEFT JOIN exchanges e ON e.outbound_order_id = o.id AND e.tenant_id = o.tenant_id
                LEFT JOIN stores st ON st.id = o.store_id
                WHERE o.id = ?
                  AND o.tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid
                """,
                rs -> {
                    if (!rs.next()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found");
                    Object addr = null;
                    String addrJson = rs.getString("address");
                    if (addrJson != null) {
                        try { addr = mapper.readValue(addrJson, Object.class); } catch (Exception e) { addr = addrJson; }
                    }
                    return new OrderDetail(
                        rs.getObject("id", UUID.class).toString(),
                        rs.getString("number"),
                        rs.getString("customer_name"),
                        rs.getString("customer_phone"),
                        addr,
                        rs.getString("payment_method"),
                        rs.getBigDecimal("cod_amount"),
                        rs.getString("status"),
                        rs.getBoolean("on_hold"),
                        rs.getString("hold_reason"),
                        rs.getTimestamp("placed_at") != null ? rs.getTimestamp("placed_at").toInstant() : null,
                        rs.getTimestamp("created_at").toInstant(),
                        null, null,  // items + shipments filled below
                        rs.getString("bosta_link_status"),
                        rs.getTimestamp("not_traced_at") != null ? rs.getTimestamp("not_traced_at").toInstant() : null,
                        rs.getBoolean("is_exchange"),
                        null,  // derivedStatus filled below
                        buildShopifyOrderUrl(rs.getString("shop_domain"), rs.getString("external_id"))
                    );
                }, orderId);

            // Fetch order items + variants + product titles
            List<OrderItem> items = fetchItems(orderId);

            // Fetch all shipments (both legs) with raw for attempt history.
            // ORDER BY: forward before return, newest first within each leg.
            List<ShipmentDetail> shipments = jdbc.query(
                """
                SELECT sh.id, sh.tracking_number, sh.provider,
                       sh.internal_state, sh.shipment_leg::text AS shipment_leg,
                       sh.number_of_attempts,
                       -- Canonical attempt count, leg-aware: ndr_codes.category matches
                       -- shipment_leg's own value ('forward'/'return' are identical
                       -- strings on both sides) — no CASE needed. severity='normal'
                       -- excludes the return leg's 26-30 codes (courier-side evidence of
                       -- loss/tamper, not attempts; harmless no-op on the forward leg,
                       -- whose codes are all 'normal' already). LEFT JOIN — exception_code
                       -- has no FK to ndr_codes; fail-open on "code present but unmapped"
                       -- (still counts, so an unrecognized code is never silently dropped
                       -- and undercounted), fail-closed on "no code at all" (states
                       -- 101/102/103 typically carry none — not an attempt).
                       (SELECT COUNT(*) FROM shipment_status_history h2
                        LEFT JOIN ndr_codes n ON n.code = h2.exception_code
                        WHERE h2.shipment_id = sh.id AND h2.internal_state = 'exception'
                          AND h2.exception_code IS NOT NULL
                          AND (n.category = sh.shipment_leg::text OR n.category IS NULL)
                          AND (n.severity = 'normal' OR n.severity IS NULL)
                       ) AS failed_delivery_attempts,
                       sh.awb_url, sh.exception_code, sh.exception_reason,
                       sh.is_delayed, sh.sla_breached, sh.scheduled_at,
                       sh.courier_name, sh.courier_phone, sh.last_failure_reason,
                       sh.raw::text AS raw_json
                FROM shipments sh
                WHERE order_id = ?
                ORDER BY shipment_leg ASC, created_at DESC
                """,
                (rs, i) -> {
                    UUID shipmentId = rs.getObject("id", UUID.class);

                    // Extract per-attempt history from raw.
                    List<AttemptEntry> attempts = List.of();
                    String rawJson = rs.getString("raw_json");
                    if (rawJson != null) {
                        try {
                            JsonNode raw = mapper.readTree(rawJson);
                            JsonNode attArr = raw.path("attempts");
                            if (attArr.isArray()) {
                                List<AttemptEntry> list = new ArrayList<>();
                                for (JsonNode a : attArr) {
                                    String succeededAt = a.path("succeededAt").asText(null);
                                    boolean succeeded = (succeededAt != null && !succeededAt.isBlank())
                                        || a.path("state").asInt(-1) == 3;
                                    JsonNode aStar = a.path("star");
                                    String aCourierName  = nullIfBlank(aStar.path("name").asText(null));
                                    String aCourierPhone = nullIfBlank(aStar.path("phone").asText(null));
                                    String reason = nullIfBlank(a.path("exception").path("reason").asText(null));
                                    list.add(new AttemptEntry(
                                        a.path("attemptDate").asText(null),
                                        a.path("type").asText(null),
                                        succeeded, aCourierName, aCourierPhone, reason));
                                }
                                attempts = List.copyOf(list);
                            }
                        } catch (Exception ignored) {}
                    }

                    // Fetch delivery history for this specific shipment leg.
                    List<DeliveryHistoryEntry> history = jdbc.query(
                        """
                        SELECT internal_state, provider_state, exception_code,
                               exception_reason, occurred_at
                        FROM shipment_status_history
                        WHERE shipment_id = ?
                        ORDER BY occurred_at ASC
                        """,
                        (hrs, j) -> new DeliveryHistoryEntry(
                            hrs.getString("internal_state"),
                            hrs.getObject("provider_state", Integer.class),
                            hrs.getObject("exception_code", Integer.class),
                            hrs.getString("exception_reason"),
                            hrs.getTimestamp("occurred_at").toInstant()
                        ), shipmentId);

                    Instant scheduledAt = rs.getTimestamp("scheduled_at") != null
                        ? rs.getTimestamp("scheduled_at").toInstant() : null;

                    String internalState = rs.getString("internal_state");

                    return new ShipmentDetail(
                        shipmentId.toString(),
                        rs.getString("tracking_number"),
                        rs.getString("provider"),
                        internalState,
                        rs.getString("shipment_leg"),
                        rs.getInt("number_of_attempts"),
                        rs.getInt("failed_delivery_attempts"),
                        rs.getString("awb_url"),
                        rs.getObject("exception_code", Integer.class),
                        rs.getString("exception_reason"),
                        rs.getObject("is_delayed", Boolean.class),
                        rs.getObject("sla_breached", Boolean.class),
                        scheduledAt,
                        rs.getString("courier_name"),
                        rs.getString("courier_phone"),
                        rs.getString("last_failure_reason"),
                        attempts,
                        history,
                        OrderStatusDeriver.deriveLegStatus(internalState));
                }, orderId);

            // Latest forward-leg shipment status inputs — deliberately a standalone query,
            // NOT shipments.get(0) (that list is ordered by created_at DESC per LEG, i.e.
            // forward vs return, not "latest overall" within a leg the way this needs).
            // Mirrors list()'s LATERAL exactly — same ORDER BY created_at DESC, id DESC —
            // so the two paths can never derive a different DerivedOrderStatus for the same
            // order (see OrderStatusListDetailParityTest). UUIDv4 is not time-ordered —
            // order by created_at, never id. The max_progress_rank CASE must stay in sync
            // with OrderStatusDeriver.PROGRESS_RANK — same caveat as the LATERAL in list().
            Map<String, Object> fwd = jdbc.query(
                """
                SELECT internal_state, number_of_attempts,
                       exception_code, is_delayed, sla_breached,
                       -- Canonical attempt count — see the funnel()/summary()/list()
                       -- LATERALs' identical comment (LEFT JOIN, fail-open on unmapped
                       -- codes, fail-closed on no code). Kept in sync manually across all four.
                       (SELECT COUNT(*) FROM shipment_status_history h2
                        LEFT JOIN ndr_codes n ON n.code = h2.exception_code
                        WHERE h2.shipment_id = sh.id AND h2.internal_state = 'exception'
                          AND h2.exception_code IS NOT NULL
                          AND (n.category = 'forward' OR n.category IS NULL)
                       ) AS failed_delivery_attempts,
                       COALESCE(
                           (SELECT MAX(CASE h.internal_state
                                       WHEN 'created'      THEN 1
                                       WHEN 'with_courier'  THEN 2
                                       WHEN 'returning'     THEN 3
                                       WHEN 'exception'     THEN 0
                                   END)
                            FROM shipment_status_history h
                            WHERE h.shipment_id = sh.id),
                           CASE sh.internal_state
                               WHEN 'created'      THEN 1
                               WHEN 'with_courier'  THEN 2
                               WHEN 'returning'     THEN 3
                               WHEN 'exception'     THEN 0
                           END
                       ) AS max_progress_rank
                FROM shipments sh
                WHERE order_id = ?
                  AND tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid
                  AND shipment_leg = 'forward'
                ORDER BY created_at DESC, id DESC
                LIMIT 1
                """,
                rs -> {
                    if (!rs.next()) return null;
                    Map<String, Object> m = new HashMap<>();
                    m.put("internal_state", rs.getString("internal_state"));
                    m.put("failed_delivery_attempts", rs.getInt("failed_delivery_attempts"));
                    m.put("number_of_attempts", rs.getInt("number_of_attempts"));
                    m.put("exception_code", rs.getObject("exception_code", Integer.class));
                    m.put("is_delayed", rs.getObject("is_delayed", Boolean.class));
                    m.put("sla_breached", rs.getObject("sla_breached", Boolean.class));
                    m.put("max_progress_rank", rs.getObject("max_progress_rank", Integer.class));
                    return m;
                }, orderId);

            OrderStatusDeriver.DerivedOrderStatus derived = OrderStatusDeriver.derive(
                order.status(),
                fwd != null ? (String) fwd.get("internal_state") : null,
                fwd != null ? (Integer) fwd.get("max_progress_rank") : null,
                fwd != null ? (Integer) fwd.get("number_of_attempts") : 0,
                fwd != null ? (Integer) fwd.get("failed_delivery_attempts") : 0,
                fwd != null ? (Integer) fwd.get("exception_code") : null,
                fwd != null ? (Boolean) fwd.get("is_delayed") : null,
                fwd != null ? (Boolean) fwd.get("sla_breached") : null,
                order.notTracedAt() != null);

            return new OrderDetail(
                order.id(), order.number(), order.customerName(), order.customerPhone(),
                order.address(), order.paymentMethod(), order.codAmount(),
                order.status(), order.onHold(), order.holdReason(),
                order.placedAt(), order.createdAt(),
                items, shipments, order.bostaLinkStatus(), order.notTracedAt(),
                order.isExchange(), derived, order.shopifyOrderUrl());
        });
    }

    // ── merged trace timeline — GET /orders/{id}/timeline (Orders rebuild pass (b)) ────
    //
    // Union across real recorded sources only — order creation, piece_events (pick/pack),
    // shipments (AWB linkage, relabeled onto the earliest real shipment_status_history row
    // when one exists — see step 4 below), shipment_status_history (courier waypoints +
    // attempt failures). No source is synthesized. "Inventory reserved" is NOT one of these
    // sources — committed inventory is derived on every read from order_items/order.status
    // (see CatalogController), never a stored event; there is no discrete timestamp to
    // show honestly, so it is omitted rather than approximated. A "Packed" row only
    // appears when a real piece_events 'pack' row exists for this order —
    // packedConfirmed (OrderStatusDeriver) can be true via the shipment-rank branch alone
    // (Mode-B early-webhook-match) with no pack event ever recorded; this endpoint shows
    // less in that case rather than fabricating a time.
    //
    // kind is presentational only, computed AFTER the union+sort: every
    // shipment_status_history 'exception' row is 'fail'; the LAST row in final (phase,
    // occurred_at) order (if not already 'fail') becomes 'now'; everything else 'done'.
    // This never competes with OrderStatusDeriver — both read the same underlying rows, so
    // the drawer's Current-state card (derivedStatus) and this endpoint's 'now' row agree
    // by construction, not by cross-checking each other.
    public record TimelineItem(
        Instant occurredAt, String eventKey, String actorName,
        String locationName, String detail, String kind) {}

    @GetMapping("/{orderId}/timeline")
    @PreAuthorize("hasAnyRole('OWNER', 'MANAGER')")
    public List<TimelineItem> timeline(@PathVariable UUID orderId) {
        // tx.execute() (TransactionTemplate), not @Transactional — same reason as
        // detail(): this method issues many sequential jdbc calls that must share ONE
        // bound connection for TenantAwareConnection's SET LOCAL app.current_tenant to
        // stay in effect across all of them (SET LOCAL only persists for the life of an
        // open transaction). @Transactional-via-AOP-proxy would also work in the real
        // Spring-managed bean, but tx.execute() is the robust, already-established choice
        // for a multi-statement read in this file.
        return tx.execute(txs -> {
            // is_exchange via the SAME join list()/detail() already use — not an
            // external_id string-prefix check.
            Map<String, Object> order = jdbc.query(
                """
                SELECT o.placed_at, o.created_at, (e.id IS NOT NULL) AS is_exchange
                FROM orders o
                LEFT JOIN exchanges e ON e.outbound_order_id = o.id AND e.tenant_id = o.tenant_id
                WHERE o.id = ?
                  AND o.tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid
                """,
                rs -> {
                    if (!rs.next()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found");
                    Map<String, Object> m = new HashMap<>();
                    Timestamp placedAt = rs.getTimestamp("placed_at");
                    Timestamp createdAt = rs.getTimestamp("created_at");
                    m.put("placed_at", (placedAt != null ? placedAt : createdAt).toInstant());
                    m.put("is_exchange", rs.getBoolean("is_exchange"));
                    return m;
                }, orderId);

            List<TimelineItem> items = new ArrayList<>();

            // 1. Order created — omitted for exchanges (synthetic Model-A order created in
            // the SAME map()-time transaction as its forward shipment — ExchangeService.
            // map() — no real customer-facing placement moment exists; the timeline starts
            // at the Bosta leg instead, added below).
            if (!(Boolean) order.get("is_exchange")) {
                items.add(new TimelineItem(
                    (Instant) order.get("placed_at"), "order_created", null, null, null, "done"));
            }

            // 2. Picked — collapsed by DISTINCT actor (handoffs preserve custody as
            // separate rows), each timestamped at that actor's FIRST scan. Resolved off
            // piece_events directly, never allocations (the getSessionPieces staleness class).
            jdbc.query(
                """
                SELECT pe.actor_user_id, MIN(pe.occurred_at) AS first_scan_at, u.name AS actor_name
                FROM piece_events pe
                LEFT JOIN users u ON u.id = pe.actor_user_id
                WHERE pe.order_id = ? AND pe.event_type = 'scan' AND pe.to_status = 'reserved'
                GROUP BY pe.actor_user_id, u.name
                ORDER BY first_scan_at ASC
                """,
                (rs, i) -> items.add(new TimelineItem(
                    rs.getTimestamp("first_scan_at").toInstant(), "picked_up",
                    rs.getString("actor_name"), null, null, "done")),
                orderId);

            // 3. Packed — a single real event in practice (one FulfillService.complete()
            // call packs every reserved piece for the order in one transaction, one actor);
            // omitted entirely if absent, never inferred from packedConfirmed.
            jdbc.query(
                """
                SELECT pe.occurred_at, u.name AS actor_name
                FROM piece_events pe
                LEFT JOIN users u ON u.id = pe.actor_user_id
                WHERE pe.order_id = ? AND pe.event_type = 'pack'
                ORDER BY pe.occurred_at ASC
                LIMIT 1
                """,
                rs -> {
                    if (rs.next()) {
                        items.add(new TimelineItem(rs.getTimestamp("occurred_at").toInstant(),
                            "packed", rs.getString("actor_name"), null, null, "done"));
                    }
                    return null;
                }, orderId);

            // 4. AWB linked + courier waypoints — union across EVERY shipment on this order
            // (both legs, and every re-ship attempt), not just the latest forward shipment
            // list()/detail() use for status display. A re-shipped order genuinely was
            // handed to Bosta twice; showing both is the complete truth, not a bug.
            //
            // AWB-linked timestamp/detail: prefer the EARLIEST real shipment_status_history
            // row for this shipment (whatever its state) over the synthetic
            // shipments.created_at — a real Bosta row at (near-)the same instant as our own
            // ingestion time otherwise renders twice (e.g. "Handed to Bosta" + "Label
            // created" both at 12:21:29 AM for Mode-B orders). Falls back to
            // shipments.created_at ONLY when the shipment has zero status rows yet (just
            // linked, no Bosta webhook received) — the AWB is still a true fact to show, not
            // hide, until a real row exists to carry it instead. Once any real row exists,
            // created_at is never consulted again.
            //
            // Consecutive-collapse (2b): fold a run of shipment_status_history rows sharing
            // the same (internal_state, exception_code) key down to its first occurrence — a
            // repeated webhook re-polling the same state, not a real transition.
            // CONSECUTIVE ONLY: a with_courier -> exception -> with_courier sequence keeps
            // BOTH with_courier rows since they aren't adjacent in the run — the second one
            // is a genuine re-attempt, not a re-poll. Mirrors groupHistory()'s existing
            // frontend precedent (OrderDetail.tsx) exactly, including that same-code
            // exceptions fold like any other repeated state; different codes never merge.
            List<Map<String, Object>> shipmentRows = jdbc.queryForList(
                "SELECT id, created_at, tracking_number, shipment_leg::text AS shipment_leg " +
                "FROM shipments WHERE order_id = ? ORDER BY created_at ASC, id ASC",
                orderId);
            for (Map<String, Object> s : shipmentRows) {
                UUID shipmentId = (UUID) s.get("id");
                boolean isReturn = "return".equals(s.get("shipment_leg"));
                String tracking = (String) s.get("tracking_number");

                List<Map<String, Object>> historyRows = jdbc.queryForList(
                    """
                    SELECT h.internal_state, h.occurred_at, h.exception_code, h.exception_reason,
                           n.category AS ndr_category
                    FROM shipment_status_history h
                    LEFT JOIN ndr_codes n ON n.code = h.exception_code
                    WHERE h.shipment_id = ?
                    ORDER BY h.occurred_at ASC, h.id ASC
                    """, shipmentId);

                Instant awbAt = historyRows.isEmpty()
                    ? ((Timestamp) s.get("created_at")).toInstant()
                    : ((Timestamp) historyRows.get(0).get("occurred_at")).toInstant();
                items.add(new TimelineItem(
                    awbAt, isReturn ? "return_awb_linked" : "awb_linked",
                    null, null, tracking, "done"));

                // The earliest history row is subsumed into the AWB-linked marker above
                // UNLESS it's itself a real failure — an exception must never be silently
                // absorbed into a bland "done" marker, even at the cost of two rows at the
                // same instant (a genuine AWB-exists fact and a genuine first-contact
                // failure are two different truths, not a duplicate).
                boolean firstIsException = !historyRows.isEmpty()
                    && "exception".equals(historyRows.get(0).get("internal_state"));
                int startIdx = (!historyRows.isEmpty() && !firstIsException) ? 1 : 0;

                // Run-key seeded from the subsumed row (when there is one) so an immediate
                // repeat of that same state right after linking still folds away; null when
                // the loop itself will emit row 0 (nothing to seed from yet).
                String lastRunKey = startIdx == 1
                    ? runKey((String) historyRows.get(0).get("internal_state"),
                             (Integer) historyRows.get(0).get("exception_code"))
                    : null;

                // "Back at Bosta hub" tracking (forward leg only) — a 'created' row is only
                // ever the AWB-Linked/label-creation moment on its FIRST appearance (already
                // handled above). Any LATER 'created' row on this same shipment means the
                // parcel physically bounced back to Bosta after already being with the
                // courier at least once — relabel that event, don't call it "Label created"
                // again. reachedWithCourier is set from the raw internal_state of every row
                // this shipment ever had, INCLUDING the subsumed row-0 (which still proves
                // physical custody even though its own TimelineItem became the awb_linked
                // marker, not a shipment_state_with_courier row) and including rows that 2b
                // is about to collapse away as re-poll duplicates (the custody fact is real
                // regardless of whether a distinct row renders for it).
                boolean reachedWithCourier = !historyRows.isEmpty()
                    && "with_courier".equals(historyRows.get(0).get("internal_state"));

                for (int i = startIdx; i < historyRows.size(); i++) {
                    Map<String, Object> row = historyRows.get(i);
                    String state = (String) row.get("internal_state");
                    Integer exceptionCode = (Integer) row.get("exception_code");
                    String key = runKey(state, exceptionCode);
                    boolean isRepoll = key.equals(lastRunKey);
                    lastRunKey = key;
                    if ("with_courier".equals(state)) reachedWithCourier = true;
                    if (isRepoll) {
                        continue; // consecutive re-poll of the same state — not a new event
                    }

                    boolean isException = "exception".equals(state);
                    // Same fail-open/fail-closed rule as the canonical count above: a
                    // present-but-unmapped code still counts as an attempt failure (never
                    // silently relabel a real problem as a generic waypoint); no code at
                    // all (states 101/102/103) does not.
                    String ndrCategory = (String) row.get("ndr_category");
                    boolean isAttemptFailure = isException && !isReturn && exceptionCode != null
                        && (ndrCategory == null || "forward".equals(ndrCategory));
                    String eventKey;
                    if (isAttemptFailure) {
                        eventKey = "delivery_attempt_failed";
                    } else if (!isReturn && "created".equals(state) && reachedWithCourier) {
                        // Reset back to 'created' AFTER already reaching the courier at least
                        // once — a real bounce-back, not the original label-creation moment.
                        // No fabricated hub location beyond the label text itself.
                        eventKey = "back_at_bosta_hub";
                    } else {
                        eventKey = (isReturn ? "return_shipment_state_" : "shipment_state_") + state;
                    }
                    items.add(new TimelineItem(
                        ((Timestamp) row.get("occurred_at")).toInstant(), eventKey, null, null,
                        isException ? (String) row.get("exception_reason") : null,
                        isException ? "fail" : "done"));
                }
            }

            // 5. Phase-primary, occurred_at-within-phase (2c) — order created always first;
            // return-leg events always last (occurred_at ASC within that group); every
            // OTHER forward-side event (picked_up, packed, awb_linked,
            // delivery_attempt_failed, shipment_state_*) sorted purely by occurred_at ASC,
            // no warehouse-vs-courier sub-rank. warehouse-before-courier is NOT a real
            // invariant — in Mode B the AWB is linked before physical pick/pack, so forcing
            // awb_linked after picked_up/packed fabricated a sequence that never happened
            // (live order #2212096474: real AWB-link time was right after order_created,
            // days before pick/pack, but the old 3-way phaseRank still rendered it last).
            // return-after-forward IS a real invariant (a return leg cannot start before
            // the forward leg it's returning), so that's the only phase boundary kept.
            // List.sort is a STABLE sort (TimSort) — ties on (phaseRank, occurredAt) keep
            // insertion order, the deterministic secondary tiebreak, never id as a time
            // proxy. This must NOT regroup a real with_courier -> exception -> with_courier
            // interleaving: all three are forward-phase (rank 1), so the comparator falls
            // through to occurredAt and preserves their real order exactly — 2b's
            // per-shipment collapse already ran before this global merge, so both
            // with_courier rows are already the correct, distinct rows by the time this
            // sort sees them.
            items.sort(Comparator.comparingInt((TimelineItem i) -> phaseRank(i.eventKey()))
                .thenComparing(TimelineItem::occurredAt));

            // 6. kind: fail rows stay fail; the chronologically-last row becomes 'now'
            // unless it's already 'fail' (a delivery_failed order's last row IS the
            // failure — the mockup shows this exact case staying 'fail', not flipping to 'now').
            if (!items.isEmpty()) {
                int lastIdx = items.size() - 1;
                TimelineItem last = items.get(lastIdx);
                if (!"fail".equals(last.kind())) {
                    items.set(lastIdx, new TimelineItem(
                        last.occurredAt(), last.eventKey(), last.actorName(),
                        last.locationName(), last.detail(), "now"));
                }
            }

            return items;
        });
    }

    // Consecutive-collapse fold key (2b) — mirrors groupHistory()'s frontend precedent
    // (OrderDetail.tsx) exactly: exception rows key on (state + code) so two different NDR
    // codes never merge into one row; every other state keys on itself.
    private static String runKey(String state, Integer exceptionCode) {
        return "exception".equals(state)
            ? "exception:" + (exceptionCode == null ? "null" : exceptionCode)
            : state;
    }

    // Phase ordering (2c) — only two real boundaries: order_created is always first, and a
    // return leg can never start before the forward leg it's returning, so return-leg
    // events are always last. Everything else (picked_up, packed, awb_linked,
    // delivery_attempt_failed, shipment_state_*) is ONE undivided forward-phase bucket,
    // ordered purely by occurred_at — warehouse-before-courier is not a real invariant (in
    // Mode B the AWB links before pick/pack) and must not be enforced as one. Prefix-matched
    // rather than an exhaustive per-state enum so any future shipment_internal_state value
    // is classified automatically by which eventKey-building branch produced it.
    private static int phaseRank(String eventKey) {
        if ("order_created".equals(eventKey)) return 0;
        if ("return_awb_linked".equals(eventKey) || eventKey.startsWith("return_shipment_state_")) return 2;
        return 1; // picked_up, packed, awb_linked, delivery_attempt_failed, back_at_bosta_hub, shipment_state_*
    }

    private static String nullIfBlank(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }

    // Orders-rebuild pass (a) — pure URL transform on already-stored data, same pattern as
    // the frontend's shopifyThumbUrl(). external_id is always stored in GID form
    // ("gid://shopify/Order/123", both the GraphQL import path and the REST-payload path use
    // admin_graphql_api_id — see ShopifySyncService) — take the trailing numeric segment.
    // Null on anything unparseable rather than emitting a dead link.
    private static String buildShopifyOrderUrl(String shopDomain, String externalId) {
        if (shopDomain == null || externalId == null) return null;
        String numericId = externalId.substring(externalId.lastIndexOf('/') + 1);
        if (numericId.isEmpty() || !numericId.chars().allMatch(Character::isDigit)) return null;
        return "https://" + shopDomain + "/admin/orders/" + numericId;
    }

    private List<OrderItem> fetchItems(UUID orderId) {
        // Fetch items — tenant filter on oi is defense-in-depth.
        List<Map<String, Object>> rows = jdbc.queryForList(
            """
            SELECT oi.id AS item_id, oi.variant_id, oi.quantity,
                   v.title AS variant_title, v.sku,
                   p.title AS product_title, p.image_url
            FROM order_items oi
            JOIN variants v  ON v.id = oi.variant_id
            JOIN products p  ON p.id = v.product_id
            WHERE oi.order_id = ?
              AND oi.tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid
            ORDER BY oi.id
            """, orderId);

        List<OrderItem> items = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            UUID itemId    = (UUID) row.get("item_id");
            UUID variantId = (UUID) row.get("variant_id");

            // Allocated pieces for this item
            List<AllocatedPiece> pieces = jdbc.query(
                """
                SELECT pc.id AS piece_id, pc.barcode, pc.status
                FROM allocations a
                JOIN pieces pc ON pc.id = a.piece_id
                WHERE a.order_item_id = ? AND a.status IN ('active','packed')
                ORDER BY pc.id
                """,
                (rs, i) -> new AllocatedPiece(
                    rs.getString("piece_id"),
                    rs.getString("barcode"),
                    rs.getString("status")
                ), itemId);

            items.add(new OrderItem(
                itemId.toString(),
                (String) row.get("product_title"),
                (String) row.get("variant_title"),
                (String) row.get("sku"),
                ((Number) row.get("quantity")).intValue(),
                (String) row.get("image_url"),
                pieces
            ));
        }
        return items;
    }
}
