package com.traceability.integrations.shopify;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Shopify-native returns S1 — the handlers for returns/*, reverse_fulfillment_orders/dispose and fulfillment_orders/*
 * (called by ShopifyWebhookProcessorJob, which already runs inside the event's tenant). Each webhook is a SIGNAL only:
 * its payload is thin (returns/update carries only what changed, return arrays can be truncated, FO / dispose payloads
 * carry no order id), so the handler takes every Return / FulfillmentOrder / ReverseFulfillmentOrder GID it names and
 * fetches the full current state through {@link ShopifyReturnsSync}. Nothing here writes to Shopify.
 */
@Component
public class ShopifyReturnsWebhooks {

    private static final Logger log = LoggerFactory.getLogger(ShopifyReturnsWebhooks.class);

    static final Pattern RETURN_GID = Pattern.compile("^gid://shopify/Return/\\d+$");
    static final Pattern FULFILLMENT_ORDER_GID = Pattern.compile("^gid://shopify/FulfillmentOrder/\\d+$");
    static final Pattern RFO_GID = Pattern.compile("^gid://shopify/ReverseFulfillmentOrder/\\d+$");

    private final ShopifyReturnsSync sync;
    private final ShopifySyncService syncService;

    public ShopifyReturnsWebhooks(ShopifyReturnsSync sync, ShopifySyncService syncService) {
        this.sync = sync;
        this.syncService = syncService;
    }

    public static boolean handles(String topic) {
        return ShopifyReturnsScopes.RETURNS_TOPICS.contains(topic)
            || ShopifyReturnsScopes.FULFILLMENT_ORDER_TOPICS.contains(topic);
    }

    public void handle(UUID tenantId, String shopDomain, String topic, JsonNode payload) {
        UUID storeId = syncService.resolveStore(tenantId, shopDomain);
        if (storeId == null) {
            log.warn("{}: store not found or disconnected shop={} tenant={}", topic, shopDomain, tenantId);
            return;
        }
        if (topic.startsWith("returns/")) {
            Set<String> gids = gids(payload, RETURN_GID);
            // A payload with only the numeric id (no admin_graphql_api_id) still names the return.
            if (gids.isEmpty() && payload.path("id").canConvertToLong()) {
                gids.add("gid://shopify/Return/" + payload.path("id").asLong());
            }
            for (String gid : gids) {
                if (sync.syncReturn(tenantId, storeId, shopDomain, gid) == ShopifyReturnsSync.Outcome.ACCESS_DENIED) return;
            }
            if (gids.isEmpty()) log.warn("{} on {}: no return id in the payload — nothing fetched", topic, shopDomain);
        } else if (topic.startsWith("reverse_fulfillment_orders/")) {
            for (String gid : gids(payload, RFO_GID)) {
                if (sync.syncByReverseFulfillmentOrder(tenantId, storeId, shopDomain, gid)
                        == ShopifyReturnsSync.Outcome.ACCESS_DENIED) return;
            }
        } else if (topic.startsWith("fulfillment_orders/")) {
            // split / merged / moved / cancelled name several fulfillment orders (original, remaining, replacement…).
            for (String gid : gids(payload, FULFILLMENT_ORDER_GID)) {
                if (sync.syncFulfillmentOrder(tenantId, storeId, shopDomain, gid)
                        == ShopifyReturnsSync.Outcome.ACCESS_DENIED) return;
            }
        }
    }

    /** Every string value anywhere in the payload that is a GID of this kind, in payload order, once each. */
    static Set<String> gids(JsonNode payload, Pattern kind) {
        Set<String> out = new LinkedHashSet<>();
        collect(payload, kind, out);
        return out;
    }

    private static void collect(JsonNode n, Pattern kind, Set<String> out) {
        if (n == null) return;
        if (n.isTextual()) {
            if (kind.matcher(n.asText()).matches()) out.add(n.asText());
        } else if (n.isArray()) {
            for (JsonNode e : n) collect(e, kind, out);
        } else if (n.isObject()) {
            for (Iterator<Map.Entry<String, JsonNode>> it = n.fields(); it.hasNext(); ) collect(it.next().getValue(), kind, out);
        }
    }
}
