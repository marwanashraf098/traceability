package com.traceability.fulfillment;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

/**
 * Orders list tabs and filters (2026-10-30, Marawan's decisions) — the ONE definition the list ({@code GET /orders})
 * and the tab counts ({@code GET /orders/counts}, {@code withCounts}) both append, so a tab's count and its list
 * agree by construction. SQL fragments over {@code merchant_orders o} and the order's latest live forward leg
 * {@code s} (columns id, internal_state, provider — {@link OrderController#LATEST_FORWARD_LEG_SQL}).
 *
 * <p>Shipping tabs are EXCLUSIVE, in priority Cancelled → To ship → In transit → Delivered → Returned; each
 * predicate below already excludes the ones before it, so an order is in at most one. An order whose leg is
 * lost / terminated / cancelled is in none (All only). Needs attention and All overlap them by design.
 * <ul>
 *   <li>cancelled — {@code o.status = 'cancelled'}, whatever its leg (the deriver already shows "Cancelled").</li>
 *   <li>toShip — not cancelled, not handed over (status delivered), no leg or a 'created' one; an order Shopify
 *       shows fulfilled with no leg was shipped outside Traced — not to ship.</li>
 *   <li>inTransit — not cancelled, the leg is with the courier either way: with_courier, exception, returning.</li>
 *   <li>delivered — not cancelled, the leg delivered, or a handed-over self-pickup (no leg, status delivered).</li>
 *   <li>returned — not cancelled, the leg returned.</li>
 * </ul>
 * Filters: traced (not_traced_at), returns ({@link OrderReturnState}), courier (the leg's provider, else with no
 * live leg the Shopify carrier class), payment (payment_method; an internal exchange order has none), days
 * (placed_at from the start of the Cairo day N−1 days ago; All time = no bound).
 */
public final class OrderListFilters {

    private OrderListFilters() {}

    public static final ZoneId CAIRO = ZoneId.of("Africa/Cairo");

    private static final String NOT_CANCELLED = "o.status <> 'cancelled'";

    /** Tab key → predicate. Insertion order = priority. */
    public static final Map<String, String> SHIPPING_TABS = new java.util.LinkedHashMap<>();
    static {
        SHIPPING_TABS.put("cancelled", "o.status = 'cancelled'");
        SHIPPING_TABS.put("toShip", NOT_CANCELLED + " AND o.status <> 'delivered'"
            + " AND (s.id IS NULL OR s.internal_state IS NULL OR s.internal_state = 'created')"
            + " AND NOT (s.id IS NULL AND COALESCE(o.shopify_fulfilled, false))");
        SHIPPING_TABS.put("inTransit", NOT_CANCELLED
            + " AND s.internal_state IN ('with_courier', 'exception', 'returning')");
        SHIPPING_TABS.put("delivered", NOT_CANCELLED
            + " AND (s.internal_state = 'delivered' OR (s.id IS NULL AND o.status = 'delivered'))");
        SHIPPING_TABS.put("returned", NOT_CANCELLED + " AND s.internal_state = 'returned'");
    }

    public static final List<String> TABS =
        List.of("all", "attention", "toShip", "inTransit", "delivered", "returned", "cancelled");

    /** The LATERAL the counts query uses (the list's own LATERAL selects the same three columns). */
    static final String LEG_LATERAL = "LEFT JOIN LATERAL (SELECT sh.id, sh.internal_state, sh.provider::text AS provider "
        + OrderController.LATEST_FORWARD_LEG_SQL + ") s ON true ";

    /** The filters other than tab / reason / search. Values are validated; null = not filtered. */
    public record Filters(String traced, String returns, String courier, String payment, Integer days) {

        public static Filters parse(String traced, String returns, String courier, String payment, String days,
                                    Integer defaultDays) {
            return new Filters(
                oneOf("traced", traced, "traced", "not_traced"),
                oneOf("returns", returns, "pending", "inspected"),
                oneOf("courier", courier, "bosta", "other"),
                oneOf("payment", payment, "cod", "prepaid"),
                parseDays(days, defaultDays));
        }

        /** Appends every filter but days. */
        void appendNonDate(StringBuilder where, List<Object> params) {
            if ("traced".equals(traced))     where.append(" AND o.not_traced_at IS NULL");
            if ("not_traced".equals(traced)) where.append(" AND o.not_traced_at IS NOT NULL");
            if ("pending".equals(returns))   where.append(OrderReturnState.PENDING_FILTER_SQL);
            if ("inspected".equals(returns)) where.append(OrderReturnState.INSPECTED_FILTER_SQL);
            if ("bosta".equals(courier))
                where.append(" AND (s.provider = 'bosta' OR (s.id IS NULL AND o.shipping_carrier_class = 'bosta'))");
            if ("other".equals(courier))
                where.append(" AND (s.provider = 'generic' OR (s.id IS NULL AND o.shipping_carrier_class = 'other_known'))");
            if (payment != null) {
                where.append(" AND o.payment_method = ?::order_payment_method");
                params.add(payment);
            }
        }

        /** The placed_at lower bound, or null for All time. */
        Timestamp cutoff() {
            return days == null ? null : Timestamp.from(OrderListFilters.cutoff(days, Instant.now()));
        }
    }

    /** Start of the Cairo day {@code days − 1} days before {@code now}'s Cairo day — "last N days" includes today. */
    public static Instant cutoff(int days, Instant now) {
        LocalDate today = now.atZone(CAIRO).toLocalDate();
        return today.minusDays(days - 1L).atStartOfDay(CAIRO).toInstant();
    }

    private static Integer parseDays(String days, Integer defaultDays) {
        if (days == null || days.isBlank()) return defaultDays;
        if ("all".equals(days)) return null;
        return switch (days) {
            case "7" -> 7; case "30" -> 30; case "90" -> 90;
            default -> throw bad("days must be 7, 30, 90 or all");
        };
    }

    private static String oneOf(String name, String value, String... allowed) {
        if (value == null || value.isBlank()) return null;
        for (String a : allowed) if (a.equals(value)) return value;
        throw bad(name + " must be one of " + String.join(", ", allowed));
    }

    static String tab(String tab) {
        if (tab == null || tab.isBlank()) return null;
        if (!TABS.contains(tab)) throw bad("tab must be one of " + String.join(", ", TABS));
        return tab;
    }

    static String reason(String reason) {
        if (reason == null || reason.isBlank()) return null;
        if (!com.traceability.inventory.ExceptionService.REASON_GROUPS.contains(reason))
            throw bad("reason must be one of " + String.join(", ", com.traceability.inventory.ExceptionService.REASON_GROUPS));
        return reason;
    }

    static ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
