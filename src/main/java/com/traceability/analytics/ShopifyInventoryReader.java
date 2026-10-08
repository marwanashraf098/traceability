package com.traceability.analytics;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.traceability.integrations.shopify.ShopifyGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Analytics slice 10 — READS Shopify's per-variant cost (InventoryItem.unitCost) and stock
 * (InventoryItem.inventoryLevels → available per location). ANALYTICS ONLY:
 *   - no inventory / product write of any kind: its only mutation is bulkOperationRunQuery, which
 *     starts a READ export (ShopifyInventoryReadGuardTest);
 *   - it talks to Shopify only through ShopifyGateway.executeGraphQLPublic (no new gateway method);
 *   - nothing on the inventory write path (FR-17 v2) references it or the columns it fills.
 *
 * Access: {@link #probe} reads one variant's cost and one's stock in two separate queries, so a
 * denied field (missing scope / Shopify permission) is attributed to that field alone; the pass
 * then reads only the fields that are allowed. Shopify allows ONE bulk query per app and shop at a
 * time and nothing else in Traced runs one; if it can't start or doesn't finish in time the pass
 * falls back to paged reads (100 variants a page, the gateway's throttle handling).
 */
@Component
public class ShopifyInventoryReader {

    private static final Logger log = LoggerFactory.getLogger(ShopifyInventoryReader.class);

    public enum FieldStatus { OK, ACCESS_DENIED, ERROR }

    /** What Shopify allowed, and the shop's cost currency when a cost was seen. */
    public record Probe(FieldStatus cost, FieldStatus stock, String currency, String error) {}

    /** One variant: numeric ids; cost / currency null when not read or not set; levels by numeric location id. */
    public record Item(String variantId, String inventoryItemId, BigDecimal cost, String currency,
                       Map<String, Integer> levels) {}

    public record Read(String mode, List<Item> items) {}

    /** Fetches a bulk operation's JSONL result. A bean so tests can supply the lines. */
    public interface BulkDownloader {
        List<String> lines(String url) throws Exception;
    }

    static final String COST_PROBE = """
        query { productVariants(first: 1) { nodes { id inventoryItem { id unitCost { amount currencyCode } } } } }
        """;
    static final String STOCK_PROBE = """
        query { productVariants(first: 1) { nodes { id inventoryItem { id inventoryLevels(first: 1) {
          nodes { location { id } quantities(names: ["available"]) { name quantity } } } } } } }
        """;
    static final String BULK_RUN = """
        mutation BulkRead($q: String!) { bulkOperationRunQuery(query: $q) { bulkOperation { id status } userErrors { field message } } }
        """;
    static final String BULK_STATUS = """
        query { currentBulkOperation(type: QUERY) { id status errorCode url objectCount } }
        """;

    private final ShopifyGateway gateway;
    private final ObjectMapper mapper;
    private final BulkDownloader downloader;
    private final Duration pollEvery;
    private final Duration bulkTimeout;

    public ShopifyInventoryReader(ShopifyGateway gateway, ObjectMapper mapper, BulkDownloader downloader,
                                  @Value("${analytics.inventory-sync.bulk-poll-ms:2000}") long pollMs,
                                  @Value("${analytics.inventory-sync.bulk-timeout-s:600}") long timeoutS) {
        this.gateway = gateway;
        this.mapper = mapper;
        this.downloader = downloader;
        this.pollEvery = Duration.ofMillis(pollMs);
        this.bulkTimeout = Duration.ofSeconds(timeoutS);
    }

    // ── probe ───────────────────────────────────────────────────────────────

    public Probe probe(String shop, String token) {
        String currency = null, error = null;
        FieldStatus cost, stock;
        try {
            JsonNode data = gateway.executeGraphQLPublic(shop, token, COST_PROBE, mapper.createObjectNode());
            JsonNode node = data.path("productVariants").path("nodes").path(0);
            cost = node.isMissingNode() || node.path("inventoryItem").has("unitCost") ? FieldStatus.OK : FieldStatus.ACCESS_DENIED;
            currency = text(node.path("inventoryItem").path("unitCost").path("currencyCode"));
        } catch (RuntimeException e) {
            cost = classify(e);
            error = message(e);
        }
        try {
            JsonNode data = gateway.executeGraphQLPublic(shop, token, STOCK_PROBE, mapper.createObjectNode());
            JsonNode node = data.path("productVariants").path("nodes").path(0);
            stock = node.isMissingNode() || node.path("inventoryItem").has("inventoryLevels") ? FieldStatus.OK : FieldStatus.ACCESS_DENIED;
        } catch (RuntimeException e) {
            stock = classify(e);
            if (error == null) error = message(e);
        }
        return new Probe(cost, stock, currency, error);
    }

    static FieldStatus classify(RuntimeException e) {
        String m = message(e).toLowerCase(Locale.ROOT);
        return m.contains("access denied") || m.contains("access_denied") || m.contains("required access")
            ? FieldStatus.ACCESS_DENIED : FieldStatus.ERROR;
    }

    static String message(Throwable e) {
        String m = e.getMessage();
        m = m == null ? e.getClass().getSimpleName() : m;
        return m.length() > 300 ? m.substring(0, 300) : m;
    }

    // ── the pass ────────────────────────────────────────────────────────────

    /** Every variant's allowed fields: bulk, else paged. Throws only when the paged fallback fails too. */
    public Read read(String shop, String token, boolean cost, boolean stock) {
        try {
            List<Item> bulk = bulk(shop, token, cost, stock);
            if (bulk != null) return new Read("bulk", bulk);
        } catch (RuntimeException e) {
            log.warn("Inventory read: bulk export failed for {} ({}) — reading page by page", shop, message(e));
        }
        return new Read("paged", paged(shop, token, cost, stock));
    }

    static String fields(boolean cost, boolean stock, boolean bulk) {
        StringBuilder f = new StringBuilder("id inventoryItem { id");
        if (cost) f.append(" unitCost { amount currencyCode }");
        if (stock) {
            f.append(bulk ? " inventoryLevels { edges { node { location { id } quantities(names: [\"available\"]) { name quantity } } } }"
                          : " inventoryLevels(first: 20) { nodes { location { id } quantities(names: [\"available\"]) { name quantity } } }");
        }
        return f.append(" }").toString();
    }

    /** Null when the bulk export couldn't run (another one in progress, user error, timeout). */
    List<Item> bulk(String shop, String token, boolean cost, boolean stock) {
        String query = "{ productVariants { edges { node { " + fields(cost, stock, true) + " } } } }";
        ObjectNode vars = mapper.createObjectNode().put("q", query);
        JsonNode run = gateway.executeGraphQLPublic(shop, token, BULK_RUN, vars).path("bulkOperationRunQuery");
        JsonNode errors = run.path("userErrors");
        if (errors.isArray() && !errors.isEmpty()) {
            log.info("Inventory read: bulk export not started for {} ({}) — reading page by page", shop,
                errors.get(0).path("message").asText(""));
            return null;
        }
        String id = text(run.path("bulkOperation").path("id"));
        Instant deadline = Instant.now().plus(bulkTimeout);
        while (Instant.now().isBefore(deadline)) {
            JsonNode op = gateway.executeGraphQLPublic(shop, token, BULK_STATUS, mapper.createObjectNode())
                .path("currentBulkOperation");
            if (id != null && !id.equals(text(op.path("id")))) return null;          // not ours any more
            String status = op.path("status").asText("");
            if ("COMPLETED".equals(status)) {
                String url = text(op.path("url"));
                if (url == null) return List.of();                                    // no objects
                try {
                    return parseJsonl(downloader.lines(url));
                } catch (Exception e) {
                    log.warn("Inventory read: bulk result download failed for {} ({})", shop, message(e));
                    return null;
                }
            }
            if (Set.of("FAILED", "CANCELED", "CANCELLED", "EXPIRED").contains(status)) {
                log.info("Inventory read: bulk export {} for {} ({})", status, shop, op.path("errorCode").asText(""));
                return null;
            }
            sleep();
        }
        log.info("Inventory read: bulk export for {} still running after {} — reading page by page", shop, bulkTimeout);
        return null;
    }

    /** Bulk JSONL: variant lines, then each inventory level as its own line with __parentId = the variant. */
    List<Item> parseJsonl(List<String> lines) throws Exception {
        Map<String, Item> byVariant = new LinkedHashMap<>();
        Map<String, Map<String, Integer>> levels = new HashMap<>();
        for (String line : lines) {
            if (line == null || line.isBlank()) continue;
            JsonNode n = mapper.readTree(line);
            String parent = text(n.path("__parentId"));
            if (parent == null) {
                Item it = item(n, Map.of());
                if (it != null) byVariant.put(text(n.path("id")), it);
            } else if (n.has("location")) {
                levels.computeIfAbsent(parent, k -> new LinkedHashMap<>()).put(numeric(text(n.path("location").path("id"))), available(n));
            }
        }
        List<Item> out = new ArrayList<>();
        for (Map.Entry<String, Item> e : byVariant.entrySet()) {
            Item it = e.getValue();
            out.add(new Item(it.variantId(), it.inventoryItemId(), it.cost(), it.currency(),
                levels.getOrDefault(e.getKey(), it.levels())));
        }
        return out;
    }

    List<Item> paged(String shop, String token, boolean cost, boolean stock) {
        String query = "query($cursor: String) { productVariants(first: 100, after: $cursor) { pageInfo { hasNextPage endCursor } "
            + "nodes { " + fields(cost, stock, false) + " } } }";
        List<Item> out = new ArrayList<>();
        String cursor = null;
        do {
            ObjectNode vars = mapper.createObjectNode();
            if (cursor != null) vars.put("cursor", cursor);
            JsonNode conn = gateway.executeGraphQLPublic(shop, token, query, vars).path("productVariants");
            for (JsonNode n : conn.path("nodes")) {
                Map<String, Integer> lv = new LinkedHashMap<>();
                for (JsonNode l : n.path("inventoryItem").path("inventoryLevels").path("nodes")) {
                    lv.put(numeric(text(l.path("location").path("id"))), available(l));
                }
                Item it = item(n, lv);
                if (it != null) out.add(it);
            }
            cursor = conn.path("pageInfo").path("hasNextPage").asBoolean(false) ? text(conn.path("pageInfo").path("endCursor")) : null;
        } while (cursor != null);
        return out;
    }

    private Item item(JsonNode n, Map<String, Integer> levels) {
        String vid = numeric(text(n.path("id")));
        if (vid == null) return null;
        JsonNode item = n.path("inventoryItem");
        JsonNode uc = item.path("unitCost");
        BigDecimal cost = null;
        if (uc.isObject() && uc.path("amount").isValueNode()) {
            try {
                cost = new BigDecimal(uc.path("amount").asText());
            } catch (NumberFormatException ignored) {
                // a malformed amount is no cost
            }
        }
        return new Item(vid, numeric(text(item.path("id"))), cost, text(uc.path("currencyCode")), levels);
    }

    private static Integer available(JsonNode level) {
        for (JsonNode q : level.path("quantities")) {
            if ("available".equals(q.path("name").asText())) return q.path("quantity").isNumber() ? q.path("quantity").asInt() : null;
        }
        return null;
    }

    /** "gid://shopify/ProductVariant/123" → "123"; a bare number stays; null stays null. */
    public static String numeric(String id) {
        if (id == null || id.isBlank()) return null;
        int slash = id.lastIndexOf('/');
        String n = slash >= 0 ? id.substring(slash + 1) : id;
        int q = n.indexOf('?');
        return q >= 0 ? n.substring(0, q) : n;
    }

    private static String text(JsonNode n) {
        return n == null || n.isMissingNode() || n.isNull() ? null : n.asText();
    }

    private void sleep() {
        try {
            Thread.sleep(pollEvery.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting for the bulk export", e);
        }
    }
}
