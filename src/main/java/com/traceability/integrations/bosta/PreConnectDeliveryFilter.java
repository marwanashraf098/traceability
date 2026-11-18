package com.traceability.integrations.bosta;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigInteger;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pre-connect delivery filter (2026-10-02, product rule of 2026-10-01: Traced tracks only
 * orders placed after the merchant connected Shopify — not pre-connect orders, nor their
 * exchanges or returns).
 *
 * Called by BostaWebhookJob for every fetched delivery — the one point real webhooks,
 * the status poll, discovery and backfill all pass through — BEFORE the exchange lane or
 * the unlinked lane can write anything. {@link #shouldIgnore} is true only when ALL hold:
 * <ol>
 *   <li>every Shopify store of the tenant has a non-null {@code orders_ingest_from}
 *       (Jumi's NULL cutoff is permanent — it is never filtered);</li>
 *   <li>the tracking number is not Traced's own: no shipments row, no exchanges row with a
 *       return_request_id, no return_requests row booked with it;</li>
 *   <li>the businessReference does not resolve to a Traced order — read by {@link BostaReferenceParser}
 *       (as sent, the parts after and before a ':', each also without a trailing -R&lt;digits&gt;, '#'-stripped,
 *       '#'-prefixed, any case, and as external_id: BROEK exchanges "BRK-44719-EG:BRK-44719-EG-R1", blnco
 *       "blncoeg:#515956", the Bosta Shopify plugin "jamilatheboutique:#64968-R1"); plus shopifyInfo.orderId
 *       as a Shopify order GID. Stripping the suffix here can only make a delivery resolve (be kept);</li>
 *   <li>and EITHER Bosta's createdAt is before the cutoff, OR — for every type except a customer
 *       return pickup (25) and an exchange (30) with a readable createdAt (2026-11-16) — the reference
 *       has the same prefix/suffix shape as the tenant's order numbers (e.g. "BRK-" + digits + "-EG",
 *       or plain digits) and its number is below the tenant's lowest ingested order number of
 *       that shape. The number is {@link BostaReferenceParser#orderNumber} — the ONE clean order number of the
 *       reference (2026-11-17: the part after the ':' when that is the order name, -R&lt;digits&gt; stripped);
 *       a reference without one (unclear, two different values, odd characters) is never ignored. A 25 / 30 created after the cutoff is kept even when its order predates the
 *       connect (its parent is then imported on demand — PreConnectParentImport).</li>
 * </ol>
 * A null reference created after the cutoff is never ignored. Must be called inside a
 * transaction under TenantContext (reads are RLS-scoped; every query is also tenant_id-bound).
 */
@Component
public class PreConnectDeliveryFilter {

    /** prefix (no digits) + digits + suffix (no digits): "BRK-44742-EG", "70370", "#1001". */
    private static final Pattern NUMBER_SHAPE = Pattern.compile("^(\\D*?)(\\d+)(\\D*)$");

    /** JavaScript Date.toString(), as Bosta's v0 API returns createdAt: "Thu Oct 01 2026 13:54:17 GMT+0000 (...)". */
    private static final DateTimeFormatter JS_DATE =
        DateTimeFormatter.ofPattern("EEE MMM dd yyyy HH:mm:ss 'GMT'xx", Locale.ENGLISH);

    private final JdbcTemplate jdbc;

    public PreConnectDeliveryFilter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean shouldIgnore(UUID tenantId, String trackingNumber, BostaDelivery delivery) {
        Instant cutoff = tenantCutoff(tenantId);
        if (cutoff == null) return false;

        if (isTracedOwned(tenantId, trackingNumber)) return false;

        String reference = delivery.businessReference();
        if (referenceResolves(tenantId, reference, delivery.shopifyOrderId())) return false;

        Instant createdAt = parseCreatedAt(delivery.raw());
        if (createdAt != null && createdAt.isBefore(cutoff)) return true;

        // 2026-11-16 (Storm 2472106867 "#2739:#2739-R1"): a customer return pickup (25) or an exchange (30)
        // Bosta CREATED after the connect is tracked even when its order predates the connect — the order
        // number says nothing about when the courier trip was made. Only a SEND / RTO (a pre-connect order
        // booked late) keeps the number check. An unreadable createdAt keeps the number check for every type.
        if (createdAt != null && createdAtDecides(delivery)) return false;

        return belowLowestOrderNumber(tenantId, reference);
    }

    /**
     * 2026-11-16: a kept customer return pickup (25) / exchange (30) that belongs to an order placed BEFORE the
     * connect — created at / after the cutoff, not Traced's own, its reference resolving to no Traced order, and its
     * number below the tenant's lowest ingested one (exactly what {@link #shouldIgnore} ignored before). Its parent
     * order is then imported on demand (PreConnectParentImport). Same transaction / tenant rules as shouldIgnore.
     * 2026-11-17: a dashboard exchange that was already MAPPED has a forward leg with its own AWB on its
     * internal:exchange: replacement order — that shipment alone doesn't make the trip "Traced's own" here (it is
     * still a pre-connect order's exchange whose original isn't known). Only here: {@link #shouldIgnore} is unchanged.
     */
    public boolean needsPreConnectParent(UUID tenantId, String trackingNumber, BostaDelivery delivery) {
        if (!createdAtDecides(delivery)) return false;
        Instant cutoff = tenantCutoff(tenantId);
        if (cutoff == null) return false;
        Instant createdAt = parseCreatedAt(delivery.raw());
        if (createdAt == null || createdAt.isBefore(cutoff)) return false;
        if (isTracedOwned(tenantId, trackingNumber, true)) return false;
        String reference = delivery.businessReference();
        if (referenceResolves(tenantId, reference, delivery.shopifyOrderId())) return false;
        return belowLowestOrderNumber(tenantId, reference);
    }

    /** Bosta types decided by their own creation time alone: customer return pickup (25), exchange (30). */
    public static boolean createdAtDecides(BostaDelivery delivery) {
        int code = delivery.typeCode();
        if (code == CUSTOMER_RETURN_PICKUP || code == EXCHANGE) return true;
        if (code != -1) return false;
        // No type.code (a list item that carries only the label): Bosta's labels for the same two types.
        String label = delivery.type() == null ? "" : delivery.type().trim().toUpperCase(Locale.ROOT).replace('_', ' ');
        return label.equals("EXCHANGE") || label.equals("CUSTOMER RETURN PICKUP");
    }

    static final int CUSTOMER_RETURN_PICKUP = 25;
    static final int EXCHANGE = 30;

    /** The tenant's connection boundary, or null when it has no store or any store has a NULL cutoff. */
    Instant tenantCutoff(UUID tenantId) {
        Map<String, Object> row = jdbc.queryForMap(
            "SELECT COUNT(*) AS n, bool_or(orders_ingest_from IS NULL) AS any_null, " +
            "       MIN(orders_ingest_from) AS cutoff " +
            "FROM stores WHERE tenant_id = ?", tenantId);
        long n = ((Number) row.get("n")).longValue();
        if (n == 0 || Boolean.TRUE.equals(row.get("any_null"))) return null;
        Object c = row.get("cutoff");
        if (c instanceof Timestamp ts) return ts.toInstant();
        if (c instanceof java.time.OffsetDateTime odt) return odt.toInstant();
        return null;
    }

    private boolean isTracedOwned(UUID tenantId, String trackingNumber) {
        return isTracedOwned(tenantId, trackingNumber, false);
    }

    /**
     * {@code exceptOwnExchangeLeg}: a Bosta shipment that is the forward leg of the internal:exchange: replacement
     * order of THIS AWB's dashboard exchange (exchanges.outbound_order_id, return_request_id NULL) doesn't count.
     */
    private boolean isTracedOwned(UUID tenantId, String trackingNumber, boolean exceptOwnExchangeLeg) {
        String shipmentClause = exceptOwnExchangeLeg
            ? "SELECT 1 FROM shipments s WHERE s.tenant_id = ? AND s.tracking_number = ? AND s.provider = 'bosta' " +
              "  AND NOT EXISTS (SELECT 1 FROM exchanges x JOIN merchant_orders o ON o.id = x.outbound_order_id AND o.tenant_id = x.tenant_id " +
              "                  WHERE x.tenant_id = s.tenant_id AND x.tracking_number = s.tracking_number " +
              "                    AND x.return_request_id IS NULL AND x.outbound_order_id = s.order_id " +
              "                    AND o.external_id LIKE 'internal:exchange:%')"
            : "SELECT 1 FROM shipments WHERE tenant_id = ? AND tracking_number = ? AND provider = 'bosta'";
        return Boolean.TRUE.equals(jdbc.queryForObject(
            "SELECT EXISTS (" + shipmentClause + ") " +
            "    OR EXISTS (SELECT 1 FROM exchanges WHERE tenant_id = ? AND tracking_number = ? " +
            "                 AND return_request_id IS NOT NULL) " +
            "    OR EXISTS (SELECT 1 FROM return_requests WHERE tenant_id = ? AND bosta_tracking_number = ?)",
            Boolean.class, tenantId, trackingNumber, tenantId, trackingNumber, tenantId, trackingNumber));
    }

    private boolean referenceResolves(UUID tenantId, String reference, String shopifyOrderId) {
        List<String> numbers = new ArrayList<>(BostaReferenceParser.numberVariants(reference, true));
        List<String> externalIds = new ArrayList<>(BostaReferenceParser.candidates(reference, true));
        if (shopifyOrderId != null && !shopifyOrderId.isBlank()) {
            externalIds.add("gid://shopify/Order/" + shopifyOrderId.trim());
        }
        if (numbers.isEmpty() && externalIds.isEmpty()) return false;
        List<Object> args = new ArrayList<>();
        args.add(tenantId);
        StringBuilder where = new StringBuilder();
        if (!numbers.isEmpty()) {
            where.append("number IN (").append(placeholders(numbers.size())).append(")");
            args.addAll(numbers);
        }
        if (!externalIds.isEmpty()) {
            if (where.length() > 0) where.append(" OR ");
            where.append("external_id IN (").append(placeholders(externalIds.size())).append(")");
            args.addAll(externalIds);
        }
        return Boolean.TRUE.equals(jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM merchant_orders WHERE tenant_id = ? AND (" + where + "))",
            Boolean.class, args.toArray()));
    }

    private static String placeholders(int n) {
        return String.join(",", java.util.Collections.nCopies(n, "?"));
    }

    /**
     * The reference's candidates for FORWARD / return-leg identity checks (BostaFulfillmentLinkService): as sent,
     * after the ':', before it — no -R&lt;digits&gt; stripping. {@link BostaReferenceParser#candidates}.
     */
    static Set<String> referenceCandidates(String reference) {
        return new LinkedHashSet<>(BostaReferenceParser.candidates(reference, false));
    }

    /**
     * The ONE clean order number of a reference ('#'-stripped), or null — {@link BostaReferenceParser#orderNumber}.
     * Shared by the pre-connect number check and the pre-connect parent import.
     */
    public static String orderNumberPart(String reference) {
        return BostaReferenceParser.orderNumber(reference);
    }

    /**
     * S2 (2026-11-18) — the exchange drawer's "Import from Shopify" offer: this typed order number would be a
     * pre-connect order — the tenant has a connection boundary (every store has a cutoff) and the number is below the
     * lowest ingested one of the same shape. The SAME number rule as {@link #needsPreConnectParent}'s last step; the
     * import itself (PreConnectParentImport) still decides from Shopify's order date. Caller's transaction / tenant.
     */
    public boolean preConnectNumber(UUID tenantId, String number) {
        if (tenantCutoff(tenantId) == null) return false;
        return belowLowestOrderNumber(tenantId, number);
    }

    private boolean belowLowestOrderNumber(UUID tenantId, String reference) {
        String part = orderNumberPart(reference);
        if (part == null) return false;
        Matcher m = NUMBER_SHAPE.matcher(part);
        if (!m.matches()) return false;
        String prefix = m.group(1);
        String suffix = m.group(3);
        BigInteger value = new BigInteger(m.group(2));

        // Lowest number among orders whose number (ignoring a leading '#') has exactly this
        // prefix + digits + suffix. Internal exchange orders ("EXC-…", external_id internal:…)
        // never count.
        java.math.BigDecimal lowest = jdbc.queryForObject(
            "SELECT MIN(mid::numeric) FROM ( " +
            "  SELECT substr(n, length(?) + 1, length(n) - length(?) - length(?)) AS mid FROM ( " +
            "    SELECT ltrim(number, '#') AS n FROM orders " +
            "    WHERE tenant_id = ? AND external_id NOT LIKE 'internal:%' " +
            "  ) o " +
            "  WHERE length(n) > length(?) + length(?) " +
            "    AND left(n, length(?)) = ? AND right(n, length(?)) = ? " +
            ") x WHERE mid ~ '^[0-9]+$'",
            java.math.BigDecimal.class,
            prefix, prefix, suffix,
            tenantId,
            prefix, suffix,
            prefix, prefix, suffix, suffix);
        return lowest != null && new java.math.BigDecimal(value).compareTo(lowest) < 0;
    }

    /** Bosta's createdAt: ISO-8601, or the JS Date.toString() shape the v0 API returns. Null if absent/unreadable. */
    static Instant parseCreatedAt(JsonNode raw) {
        if (raw == null) return null;
        return parseBostaTime(raw.path("createdAt").asText(null));
    }

    /** A Bosta timestamp: ISO-8601, or the JS Date.toString() shape (v0 and v2 lists). Null if absent/unreadable. */
    static Instant parseBostaTime(String s) {
        if (s == null || s.isBlank()) return null;
        s = s.trim();
        try {
            return Instant.parse(s);
        } catch (DateTimeParseException ignored) {
            // fall through
        }
        int paren = s.indexOf(" (");
        String head = paren > 0 ? s.substring(0, paren) : s;
        try {
            return java.time.OffsetDateTime.parse(head, JS_DATE).toInstant();
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
