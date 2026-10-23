package com.traceability.labels;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.*;

/**
 * Flexible labeling slice 4 — what the pack screens (Pick &amp; Pack queue mode and waybill mode, both built on
 * FulfillService.getOrder) show about labels, for a switched-on tenant only (otherwise nothing is added and the
 * order detail is exactly as before). Read on the caller's connection and tenant.
 * <ul>
 *   <li>per line: {@code labelsUnused} — issued, unbound pack / replacement codes printed for this line; and
 *       {@code untaggedStock} — untagged available pieces of the line's variant at the main warehouse;</li>
 *   <li>per order: {@code labels} = {printed, used} — the order's pack / replacement codes (not voided), and how many
 *       of them are bound ("Labels for this order: x of y used").</li>
 * </ul>
 */
public final class PackLabels {

    private PackLabels() {}

    public static void decorate(JdbcTemplate jdbc, UUID tenantId, UUID orderId, Map<String, Object> order,
                                List<Map<String, Object>> items) {
        if (!FlexibleLabeling.enabled(jdbc, tenantId)) return;
        Map<UUID, Integer> unused = new HashMap<>();
        int[] printedUsed = new int[2];
        jdbc.query(
            "SELECT order_item_id, status FROM label_codes WHERE tenant_id = ? AND order_id = ? " +
            "  AND kind IN ('pack','replacement') AND status <> 'voided'",
            rs -> {
                printedUsed[0]++;
                if ("bound".equals(rs.getString("status"))) printedUsed[1]++;
                else if (rs.getObject("order_item_id") != null) {
                    unused.merge(rs.getObject("order_item_id", UUID.class), 1, Integer::sum);
                }
            },
            tenantId, orderId);
        Map<UUID, Integer> untagged = new HashMap<>();
        Set<UUID> variants = new HashSet<>();
        for (Map<String, Object> item : items) variants.add((UUID) item.get("variant_id"));
        if (!variants.isEmpty()) {
            jdbc.query(
                "SELECT p.variant_id, count(*)::int AS n FROM pieces p " +
                "JOIN locations l ON l.id = p.current_location_id AND l.is_fulfillment " +
                "WHERE p.tenant_id = ? AND p.variant_id = ANY(?) AND p.short_code IS NULL AND p.status = 'available' " +
                "GROUP BY p.variant_id",
                rs -> { untagged.put(rs.getObject("variant_id", UUID.class), rs.getInt("n")); },
                tenantId, variants.toArray(new UUID[0]));
        }
        for (Map<String, Object> item : items) {
            item.put("labelsUnused", unused.getOrDefault((UUID) item.get("id"), 0));
            item.put("untaggedStock", untagged.getOrDefault((UUID) item.get("variant_id"), 0));
        }
        Map<String, Object> labels = new LinkedHashMap<>();
        labels.put("printed", printedUsed[0]);
        labels.put("used", printedUsed[1]);
        order.put("labels", labels);
    }
}
