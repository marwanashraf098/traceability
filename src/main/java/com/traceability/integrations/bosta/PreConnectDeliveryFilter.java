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
 *   <li>the businessReference does not resolve to a Traced order — tried as sent, '#'-stripped,
 *       '#'-prefixed, as external_id, and (when it contains ':') the parts before and after the
 *       ':' the same ways (BROEK exchanges: "BRK-44719-EG:BRK-44719-EG-R1"; blnco:
 *       "blncoeg:#515956"); plus shopifyInfo.orderId as a Shopify order GID;</li>
 *   <li>and EITHER Bosta's createdAt is before the cutoff, OR the reference has the same
 *       prefix/suffix shape as the tenant's order numbers (e.g. "BRK-" + digits + "-EG",
 *       or plain digits) and its number is below the tenant's lowest ingested order number of
 *       that shape.</li>
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

        return belowLowestOrderNumber(tenantId, reference);
    }

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
        return Boolean.TRUE.equals(jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM shipments WHERE tenant_id = ? AND tracking_number = ?) " +
            "    OR EXISTS (SELECT 1 FROM exchanges WHERE tenant_id = ? AND tracking_number = ? " +
            "                 AND return_request_id IS NOT NULL) " +
            "    OR EXISTS (SELECT 1 FROM return_requests WHERE tenant_id = ? AND bosta_tracking_number = ?)",
            Boolean.class, tenantId, trackingNumber, tenantId, trackingNumber, tenantId, trackingNumber));
    }

    private boolean referenceResolves(UUID tenantId, String reference, String shopifyOrderId) {
        List<String> numbers = new ArrayList<>();
        List<String> externalIds = new ArrayList<>();
        for (String candidate : referenceCandidates(reference)) {
            String bare = stripHash(candidate);
            numbers.add(candidate);
            numbers.add(bare);
            numbers.add("#" + bare);
            externalIds.add(candidate);
        }
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
            "SELECT EXISTS (SELECT 1 FROM orders WHERE tenant_id = ? AND (" + where + "))",
            Boolean.class, args.toArray()));
    }

    private static String placeholders(int n) {
        return String.join(",", java.util.Collections.nCopies(n, "?"));
    }

    /** The reference as sent and, when it contains ':', the part before it (both trimmed). */
    static Set<String> referenceCandidates(String reference) {
        Set<String> out = new LinkedHashSet<>();
        if (reference == null || reference.isBlank()) return out;
        String r = reference.trim();
        out.add(r);
        int colon = r.indexOf(':');
        if (colon > 0) {
            String head = r.substring(0, colon).trim();
            if (!head.isEmpty()) out.add(head);
            // blnco's Bosta references are "<shop handle>:#<order number>" ("blncoeg:#515956") —
            // the order number is after the ':'. Trying it too can only make a delivery resolve
            // (be kept), never make one ignored.
            String tail = r.substring(colon + 1).trim();
            if (!tail.isEmpty()) out.add(tail);
        }
        return out;
    }

    /** The order-number part of a reference: before any ':', '#'-stripped. */
    static String orderNumberPart(String reference) {
        if (reference == null || reference.isBlank()) return null;
        String r = reference.trim();
        int colon = r.indexOf(':');
        if (colon > 0) r = r.substring(0, colon).trim();
        return stripHash(r);
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
        String s = raw.path("createdAt").asText(null);
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

    private static String stripHash(String s) {
        String t = s.trim();
        return t.startsWith("#") ? t.substring(1) : t;
    }
}
