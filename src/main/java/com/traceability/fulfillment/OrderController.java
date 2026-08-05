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
        Instant notTracedAt, OrderStatusDeriver.DerivedOrderStatus derivedStatus) {}

    public record OrderPage(List<OrderSummary> items, int page, int size, long total) {}

    public record AllocatedPiece(String pieceId, String barcode, String status) {}

    public record OrderItem(
        String id, String productTitle, String variantTitle,
        String sku, int quantity, List<AllocatedPiece> allocatedPieces) {}

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
        List<DeliveryHistoryEntry> deliveryHistory) {}

    public record OrderDetail(
        String id, String number, String customerName, String customerPhone,
        Object address, String paymentMethod, BigDecimal codAmount,
        String status, boolean onHold, String holdReason,
        Instant placedAt, Instant createdAt,
        List<OrderItem> items, List<ShipmentDetail> shipments,
        String bostaLinkStatus, Instant notTracedAt,
        OrderStatusDeriver.DerivedOrderStatus derivedStatus) {}

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
        if (q != null && !q.isBlank()) {
            where.append(" AND (o.number ILIKE ? OR o.customer_name ILIKE ? OR o.customer_phone ILIKE ?)");
            String like = "%" + q.trim() + "%";
            params.add(like); params.add(like); params.add(like);
        }
        if (tracking != null && !tracking.isBlank()) {
            where.append(" AND s.tracking_number ILIKE ?");
            params.add("%" + tracking.trim() + "%");
        }

        // LATERAL picks the latest forward shipment per order (by id DESC) so re-shipped orders
        // (terminated + new active) don't produce duplicate rows or inflate COUNT(*).
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
                       failed_delivery_attempts, is_delayed, sla_breached,
                       number_of_attempts, exception_code,
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
                ORDER BY id DESC
                LIMIT 1
            ) s ON true
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
                   o.not_traced_at
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
                       o.placed_at, o.created_at, o.bosta_link_status, o.not_traced_at
                FROM orders o
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
                        null  // derivedStatus filled below
                    );
                }, orderId);

            // Fetch order items + variants + product titles
            List<OrderItem> items = fetchItems(orderId);

            // Fetch all shipments (both legs) with raw for attempt history.
            // ORDER BY: forward before return, newest first within each leg.
            List<ShipmentDetail> shipments = jdbc.query(
                """
                SELECT id, tracking_number, provider,
                       internal_state, shipment_leg::text AS shipment_leg,
                       number_of_attempts, failed_delivery_attempts,
                       awb_url, exception_code, exception_reason,
                       is_delayed, sla_breached, scheduled_at,
                       courier_name, courier_phone, last_failure_reason,
                       raw::text AS raw_json
                FROM shipments
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

                    return new ShipmentDetail(
                        shipmentId.toString(),
                        rs.getString("tracking_number"),
                        rs.getString("provider"),
                        rs.getString("internal_state"),
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
                        history);
                }, orderId);

            // Latest forward-leg shipment status inputs — deliberately a standalone query,
            // NOT shipments.get(0). shipments is ordered by created_at DESC per leg, while
            // list()'s LATERAL orders by id DESC; this mirrors list()'s exact ORDER BY id
            // DESC LIMIT 1 so the two paths can never derive a different DerivedOrderStatus
            // for the same order (see OrderStatusListDetailParityTest). The max_progress_rank
            // CASE must stay in sync with OrderStatusDeriver.PROGRESS_RANK — same caveat as
            // the LATERAL in list().
            Map<String, Object> fwd = jdbc.query(
                """
                SELECT internal_state, failed_delivery_attempts, number_of_attempts,
                       exception_code, is_delayed, sla_breached,
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
                ORDER BY id DESC
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
                items, shipments, order.bostaLinkStatus(), order.notTracedAt(), derived);
        });
    }

    private static String nullIfBlank(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }

    private List<OrderItem> fetchItems(UUID orderId) {
        // Fetch items — tenant filter on oi is defense-in-depth.
        List<Map<String, Object>> rows = jdbc.queryForList(
            """
            SELECT oi.id AS item_id, oi.variant_id, oi.quantity,
                   v.title AS variant_title, v.sku,
                   p.title AS product_title
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
                pieces
            ));
        }
        return items;
    }
}
