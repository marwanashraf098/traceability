package com.traceability.inventory;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Main reconcile (V200, R1) — the per-variant trigger from Shopify webhooks: orders/updated, orders/cancelled and
 * inventory_levels/update (Main location only) queue every variant they name on main_reconcile_variant_state and stamp
 * last_event_at — the two-run rule's "no Shopify event in between". Traced's own claims queue their variants through
 * the V200 triggers on the claim tables (every return restock and restock skip included). Debounced by MainReconcileJob.
 * Never throws: a failure here must not fail the webhook.
 */
@Component
public class MainReconcileQueue {

    private static final Logger log = LoggerFactory.getLogger(MainReconcileQueue.class);

    static final String MARK_SQL =
        "INSERT INTO main_reconcile_variant_state AS s (tenant_id, variant_id, pending_since, pending_touched_at, " +
        "  pending_source, last_event_at, last_event_source) " +
        "SELECT v.tenant_id, v.id, now(), now(), ?, now(), ? FROM variants v WHERE v.tenant_id = ? AND %s " +
        "ON CONFLICT (tenant_id, variant_id) DO UPDATE SET " +
        "  pending_since = COALESCE(s.pending_since, now()), pending_touched_at = now(), " +
        "  pending_source = EXCLUDED.pending_source, last_event_at = now(), last_event_source = EXCLUDED.last_event_source";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public MainReconcileQueue(JdbcTemplate jdbc, PlatformTransactionManager txm) {
        this.jdbc = jdbc;
        this.tx   = new TransactionTemplate(txm);
    }

    /** Called by the webhook processor after the topic's own handler, under the tenant's context. */
    public void onWebhook(UUID tenantId, String topic, JsonNode payload) {
        try {
            switch (topic) {
                case "inventory_levels/update" -> {
                    String item = numeric(payload.path("inventory_item_id").asText(null));
                    String location = numeric(payload.path("location_id").asText(null));
                    if (item == null || location == null) return;
                    tx.executeWithoutResult(s -> jdbc.update(MARK_SQL.formatted(
                        "v.shopify_inventory_item_id = 'gid://shopify/InventoryItem/' || ? " +
                        "AND EXISTS (SELECT 1 FROM locations l WHERE l.tenant_id = v.tenant_id AND l.is_fulfillment = true " +
                        "            AND l.shopify_location_id = 'gid://shopify/Location/' || ?)"),
                        "webhook:" + topic, topic, tenantId, item, location));
                }
                case "orders/updated", "orders/cancelled" -> {
                    Set<String> gids = variantGids(payload);
                    if (gids.isEmpty()) return;
                    tx.executeWithoutResult(s -> jdbc.update(MARK_SQL.formatted("v.external_id = ANY(CAST(? AS text[]))"),
                        "webhook:" + topic, topic, tenantId, MainReconcileService.textArray(gids)));
                }
                default -> { }
            }
        } catch (RuntimeException e) {
            log.warn("Main reconcile queue: {} for tenant {} not queued: {}", topic, tenantId, e.toString());
        }
    }

    /** Every variant an order payload names: its lines, its fulfillments' lines, its refunds' lines. */
    static Set<String> variantGids(JsonNode order) {
        Set<String> out = new LinkedHashSet<>();
        for (JsonNode li : order.path("line_items")) add(out, li.path("variant_id"));
        for (JsonNode f : order.path("fulfillments")) for (JsonNode li : f.path("line_items")) add(out, li.path("variant_id"));
        for (JsonNode r : order.path("refunds"))
            for (JsonNode rli : r.path("refund_line_items")) add(out, rli.path("line_item").path("variant_id"));
        return out;
    }

    private static void add(Set<String> out, JsonNode id) {
        String n = numeric(id.isNull() || id.isMissingNode() ? null : id.asText(null));
        if (n != null) out.add("gid://shopify/ProductVariant/" + n);
    }

    static String numeric(String s) {
        if (s == null || s.isBlank()) return null;
        String n = s.replaceAll("^.*/", "");
        return n.matches("[0-9]+") ? n : null;
    }
}
