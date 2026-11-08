package com.traceability.integrations.shopify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Real {@link ShopifyReturnsWriteGateway}. Reads go through the normal transport (retries are harmless for a read);
 * writes through {@link ShopifyGateway#executeReturnsWriteOnce} (one attempt). Every response is logged at INFO as a
 * SHAPE only — key names, value types, array sizes and a few enum values (status, code, dispositionType) — never an
 * id, name, note, message or any other value (S5a: the unconfirmed Shopify behaviours are confirmed from these logs on
 * the prod test store).
 */
@Service
class ShopifyReturnsWriteGatewayImpl implements ShopifyReturnsWriteGateway {

    private static final Logger log = LoggerFactory.getLogger(ShopifyReturnsWriteGatewayImpl.class);

    static final String RETURNABLE_QUERY = """
            query TracedReturnable($orderId: ID!) {
              returnableFulfillments(orderId: $orderId, first: 10) {
                pageInfo { hasNextPage }
                nodes {
                  id
                  fulfillment { id createdAt }
                  returnableFulfillmentLineItems(first: 50) {
                    pageInfo { hasNextPage }
                    nodes { quantity fulfillmentLineItem { id lineItem { id } } }
                  }
                }
              }
            }
            """;

    static final String ORDER_RETURN_NOTES_QUERY = """
            query TracedOrderReturnNotes($id: ID!) {
              order(id: $id) {
                id
                returns(first: 20) {
                  nodes {
                    id name status createdAt
                    returnLineItems(first: 50) { nodes { quantity returnReasonNote } }
                  }
                }
              }
            }
            """;

    static final String RETURN_CREATE = """
            mutation TracedReturnCreate($returnInput: ReturnInput!) {
              returnCreate(returnInput: $returnInput) {
                return { id name status }
                userErrors { field message code }
              }
            }
            """;

    static final String RETURN_PROCESS = """
            mutation TracedReturnProcess($input: ReturnProcessInput!) {
              returnProcess(input: $input) {
                return { id status }
                userErrors { field message code }
              }
            }
            """;

    static final String REMOVE_FROM_RETURN = """
            mutation TracedRemoveFromReturn($returnId: ID!, $returnLineItems: [ReturnLineItemRemoveFromReturnInput!]) {
              removeFromReturn(returnId: $returnId, returnLineItems: $returnLineItems) {
                return { id status }
                userErrors { field message code }
              }
            }
            """;

    static final String RETURN_CLOSE = """
            mutation TracedReturnClose($id: ID!) {
              returnClose(id: $id) {
                return { id status }
                userErrors { field message code }
              }
            }
            """;

    static final String RETURN_CANCEL = """
            mutation TracedReturnCancel($id: ID!) {
              returnCancel(id: $id) {
                return { id status }
                userErrors { field message code }
              }
            }
            """;

    /** The ONE disposition this gateway sends. */
    static final String DISPOSITION = "NOT_RESTOCKED";

    private final ShopifyGateway shopify;
    private final ObjectMapper mapper;

    ShopifyReturnsWriteGatewayImpl(ShopifyGateway shopify, ObjectMapper mapper) {
        this.shopify = shopify;
        this.mapper = mapper;
    }

    // ── reads ───────────────────────────────────────────────────────────────────────────────────

    @Override
    public Returnables returnableFulfillments(String shopDomain, String token, String orderGid) {
        JsonNode data = read(shopDomain, token, RETURNABLE_QUERY, mapper.createObjectNode().put("orderId", orderGid),
            "returnableFulfillments");
        JsonNode conn = data.path("returnableFulfillments");
        boolean truncated = conn.path("pageInfo").path("hasNextPage").asBoolean(false);
        List<Returnable> out = new ArrayList<>();
        for (JsonNode rf : conn.path("nodes")) {
            Instant at = parseInstant(rf.path("fulfillment").path("createdAt").asText(null));
            JsonNode lines = rf.path("returnableFulfillmentLineItems");
            truncated |= lines.path("pageInfo").path("hasNextPage").asBoolean(false);
            for (JsonNode l : lines.path("nodes")) {
                String fli = l.path("fulfillmentLineItem").path("id").asText("");
                String li = l.path("fulfillmentLineItem").path("lineItem").path("id").asText("");
                int q = l.path("quantity").asInt(0);
                if (!fli.isEmpty() && !li.isEmpty() && q > 0) out.add(new Returnable(fli, li, q, at));
            }
        }
        out.sort(Comparator.comparing(Returnable::fulfilledAt, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(Returnable::fulfillmentLineItemGid));
        return new Returnables(List.copyOf(out), truncated);
    }

    @Override
    public JsonNode orderReturnNotes(String shopDomain, String token, String orderGid) {
        return read(shopDomain, token, ORDER_RETURN_NOTES_QUERY, mapper.createObjectNode().put("id", orderGid), "order")
            .path("order");
    }

    private JsonNode read(String shopDomain, String token, String query, JsonNode vars, String what) {
        JsonNode response = shopify.executeGraphQLResponsePublic(shopDomain, token, query, vars);
        log.info("Shopify returns read {} shop={} shape={}", what, shopDomain, shape(response));
        if (response == null) throw new ShopifyException("Shopify GraphQL returned no response");
        JsonNode errors = response.get("errors");
        if (errors != null && errors.isArray() && errors.size() > 0) {
            List<String> codes = ShopifyHttpGateway.graphQLErrorCodes(errors);
            if (codes.contains("ACCESS_DENIED")) {
                throw new ShopifyReturnsAccessDeniedException("Shopify ACCESS_DENIED for " + what + " on " + shopDomain);
            }
            throw new ShopifyException("Shopify GraphQL error " + codes + " on " + shopDomain);
        }
        JsonNode data = response.get("data");
        if (data == null || data.isNull()) throw new ShopifyException("Shopify GraphQL response has no data field");
        return data;
    }

    // ── writes ──────────────────────────────────────────────────────────────────────────────────

    @Override
    public ReturnsWriteResponse returnCreate(String shopDomain, String token, String orderGid, Instant requestedAt,
                                             List<CreateLine> lines) {
        if (lines == null || lines.isEmpty()) throw new IllegalArgumentException("returnCreate needs at least one line");
        ObjectNode input = mapper.createObjectNode().put("orderId", orderGid);
        if (requestedAt != null) input.put("requestedAt", requestedAt.toString());
        ArrayNode rl = input.putArray("returnLineItems");
        for (CreateLine l : lines) {
            if (l.quantity() <= 0) throw new IllegalArgumentException("returnCreate needs a positive quantity");
            ObjectNode n = rl.addObject().put("fulfillmentLineItemId", l.fulfillmentLineItemGid()).put("quantity", l.quantity());
            if (l.note() != null) {
                n.put("returnReasonNote", noteWithinLimit(l.note()));
            }
            if (l.returnReasonDefinitionGid() != null) n.put("returnReasonDefinitionId", l.returnReasonDefinitionGid());
        }
        ObjectNode vars = mapper.createObjectNode();
        vars.set("returnInput", input);
        return write(shopDomain, token, RETURN_CREATE, vars, "returnCreate");
    }

    /** Shopify's 255-character limit on returnReasonNote, cut by code point (never inside a surrogate pair). */
    static String noteWithinLimit(String note) {
        int max = 255;
        if (note.codePointCount(0, note.length()) <= max) return note;
        return note.substring(0, note.offsetByCodePoints(0, max));
    }

    @Override
    public ReturnsWriteResponse returnProcessNotRestocked(String shopDomain, String token, String returnGid,
                                                          String returnLineGid, String rfoLineGid, int quantity,
                                                          String locationGid) {
        if (quantity <= 0) throw new IllegalArgumentException("returnProcess needs a positive quantity");
        ObjectNode input = mapper.createObjectNode().put("returnId", returnGid).put("notifyCustomer", false);
        ObjectNode line = input.putArray("returnLineItems").addObject().put("id", returnLineGid).put("quantity", quantity);
        if (rfoLineGid != null) {
            ObjectNode d = line.putArray("dispositions").addObject()
                .put("reverseFulfillmentOrderLineItemId", rfoLineGid)
                .put("quantity", quantity)
                .put("dispositionType", DISPOSITION);
            if (locationGid != null) d.put("locationId", locationGid);
        }
        ObjectNode vars = mapper.createObjectNode();
        vars.set("input", input);
        return write(shopDomain, token, RETURN_PROCESS, vars, "returnProcess");
    }

    @Override
    public ReturnsWriteResponse removeFromReturn(String shopDomain, String token, String returnGid, String returnLineGid,
                                                 int quantity) {
        if (quantity <= 0) throw new IllegalArgumentException("removeFromReturn needs a positive quantity");
        ObjectNode vars = mapper.createObjectNode().put("returnId", returnGid);
        vars.putArray("returnLineItems").addObject().put("returnLineItemId", returnLineGid).put("quantity", quantity);
        return write(shopDomain, token, REMOVE_FROM_RETURN, vars, "removeFromReturn");
    }

    @Override
    public ReturnsWriteResponse returnClose(String shopDomain, String token, String returnGid) {
        return write(shopDomain, token, RETURN_CLOSE, mapper.createObjectNode().put("id", returnGid), "returnClose");
    }

    @Override
    public ReturnsWriteResponse returnCancel(String shopDomain, String token, String returnGid) {
        return write(shopDomain, token, RETURN_CANCEL, mapper.createObjectNode().put("id", returnGid), "returnCancel");
    }

    private ReturnsWriteResponse write(String shopDomain, String token, String mutation, JsonNode vars, String field) {
        ReturnsWriteResponse r = shopify.executeReturnsWriteOnce(shopDomain, token, mutation, vars, field);
        log.info("Shopify returns write {} shop={} outcome={} reason={} shape={}", field, shopDomain, r.kind(),
            r.reason(), shape(r.response()));
        for (String e : userErrorDetails(r.response(), field)) {
            log.warn("Shopify returns write {} shop={} userError {}", field, shopDomain, e);
        }
        return r;
    }

    /**
     * Each userError as "field=a.b.c code=X message=…" — the field path segments (schema names and list indexes) and
     * Shopify's message verbatim (approved 2026-11-08: they name schema fields, not customer data). Nothing else of the
     * response; values we sent are never echoed here.
     */
    static List<String> userErrorDetails(JsonNode response, String field) {
        List<String> out = new ArrayList<>();
        if (response == null) return out;
        JsonNode errors = response.path("data").path(field).path("userErrors");
        if (!errors.isArray()) return out;
        for (JsonNode e : errors) {
            List<String> path = new ArrayList<>();
            for (JsonNode seg : e.path("field")) path.add(seg.asText());
            out.add("field=" + (path.isEmpty() ? "-" : String.join(".", path))
                + " code=" + e.path("code").asText("-")
                + " message=" + e.path("message").asText("-"));
        }
        return out;
    }

    // ── shape logging ───────────────────────────────────────────────────────────────────────────

    /** Keys whose (enum) values are safe to log. Everything else is reduced to its type. */
    private static final Set<String> ENUM_KEYS = Set.of("status", "code", "dispositionType", "type");

    /** A PII-free description of a JSON response: keys, value types, array sizes, enum values of ENUM_KEYS. */
    static String shape(JsonNode n) {
        StringBuilder sb = new StringBuilder();
        shape(n, null, sb, 0);
        return sb.toString();
    }

    private static void shape(JsonNode n, String key, StringBuilder sb, int depth) {
        if (n == null || n.isMissingNode()) { sb.append("none"); return; }
        if (depth > 8) { sb.append("…"); return; }
        if (n.isObject()) {
            sb.append('{');
            Iterator<Map.Entry<String, JsonNode>> it = n.fields();
            boolean first = true;
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                if (!first) sb.append(',');
                first = false;
                sb.append(e.getKey()).append(':');
                shape(e.getValue(), e.getKey(), sb, depth + 1);
            }
            sb.append('}');
        } else if (n.isArray()) {
            sb.append('[').append(n.size());
            if (n.size() > 0) { sb.append(':'); shape(n.get(0), key, sb, depth + 1); }
            sb.append(']');
        } else if (n.isNull()) {
            sb.append("null");
        } else if (n.isTextual()) {
            String v = n.asText();
            if (key != null && ENUM_KEYS.contains(key) && v.matches("[A-Z_]{1,40}")) sb.append(v);
            else sb.append("str");
        } else if (n.isNumber()) {
            sb.append("num");
        } else if (n.isBoolean()) {
            sb.append(n.asBoolean());
        } else {
            sb.append("?");
        }
    }

    private static Instant parseInstant(String s) {
        if (s == null || s.isBlank()) return null;
        try { return Instant.parse(s); } catch (Exception e) { return null; }
    }
}
