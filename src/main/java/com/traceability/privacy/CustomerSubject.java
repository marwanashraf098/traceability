package com.traceability.privacy;

import com.traceability.inventory.ShipmentLinkService;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Whose data a GDPR webhook is about, inside one tenant — the ONE definition shared by
 * customers/redact ({@link CustomerRedaction}) and customers/data_request ({@link CustomerDataRequestService}),
 * so the export shows exactly what a redact would clear.
 *
 * <ul>
 *   <li>orders — the payload's order GIDs; for an export also every order whose phone canonicalises
 *       to the customer's phone (a redact stays scoped to orders_to_redact — Shopify deliberately leaves
 *       recent orders out of that list, see ShopifyRedactTouchupTest);</li>
 *   <li>plus the internal replacement orders of exchanges made for those orders (they copy the customer);</li>
 *   <li>exchanges — matched to, replacing, or booked from a request on one of those orders;</li>
 *   <li>phones — canonical 01XXXXXXXXX of those orders and of the payload's phone;</li>
 *   <li>references — the orders' numbers ('#' stripped) and numeric Shopify ids, for unlinked Bosta
 *       deliveries, which carry no order link.</li>
 * </ul>
 *
 * Not a bean: built on the caller's JdbcTemplate, so it joins the caller's transaction and runs under
 * app_user + RLS (same pattern as ReturnRequestLifecycle). Every query also names the tenant explicitly.
 */
public record CustomerSubject(
        UUID tenantId,
        List<UUID> orderIds,
        List<UUID> exchangeIds,
        List<String> shopifyOrderGids,
        List<String> phones,
        List<String> orderNumbers,
        List<String> shopifyNumericIds) {

    static final String GID_PREFIX = "gid://shopify/Order/";

    /** SQL canonical phone of a text expression — the same rule as ShipmentLinkService.normalizePhone's 11-digit form. */
    public static String canonicalPhoneSql(String expr) {
        return "('0' || RIGHT(REGEXP_REPLACE(COALESCE(" + expr + ", ''), '[^0-9]', '', 'g'), 10))";
    }

    public static CustomerSubject resolve(JdbcTemplate jdbc, UUID tenantId, Collection<String> requestedGids,
                                          String payloadPhone, boolean includePhoneMatches) {
        String[] gids = requestedGids.toArray(new String[0]);
        String phone = ShipmentLinkService.normalizePhone(payloadPhone);

        Set<UUID> orders = new LinkedHashSet<>(jdbc.queryForList(
            "SELECT id FROM orders WHERE tenant_id = ? AND external_id = ANY(?::text[])",
            UUID.class, tenantId, gids));
        if (includePhoneMatches && phone != null) {
            orders.addAll(jdbc.queryForList(
                "SELECT id FROM orders WHERE tenant_id = ? AND customer_phone IS NOT NULL AND "
                    + canonicalPhoneSql("customer_phone") + " = ?",
                UUID.class, tenantId, phone));
        }

        UUID[] direct = orders.toArray(new UUID[0]);
        List<UUID> exchanges = new ArrayList<>();
        for (var row : jdbc.queryForList(
                "SELECT id, outbound_order_id FROM exchanges WHERE tenant_id = ? AND (" +
                "  matched_order_id = ANY(?::uuid[]) OR outbound_order_id = ANY(?::uuid[]) OR " +
                "  return_request_id IN (SELECT id FROM return_requests WHERE tenant_id = ? AND order_id = ANY(?::uuid[])))",
                tenantId, direct, direct, tenantId, direct)) {
            exchanges.add((UUID) row.get("id"));
            if (row.get("outbound_order_id") != null) orders.add((UUID) row.get("outbound_order_id"));
        }

        UUID[] all = orders.toArray(new UUID[0]);
        Set<String> outGids = new LinkedHashSet<>(requestedGids);
        Set<String> phones = new LinkedHashSet<>();
        if (phone != null) phones.add(phone);
        Set<String> numbers = new LinkedHashSet<>();
        for (var row : jdbc.queryForList(
                "SELECT external_id, number, customer_phone FROM orders WHERE tenant_id = ? AND id = ANY(?::uuid[])",
                tenantId, all)) {
            String ext = (String) row.get("external_id");
            if (ext != null && ext.startsWith(GID_PREFIX)) outGids.add(ext);
            String p = ShipmentLinkService.normalizePhone((String) row.get("customer_phone"));
            if (p != null) phones.add(p);
            String n = (String) row.get("number");
            if (n != null && !n.isBlank()) numbers.add(n.replaceFirst("^#", "").trim());
        }
        List<String> numeric = outGids.stream()
            .map(g -> g.substring(GID_PREFIX.length()))
            .filter(s -> s.matches("[0-9]+"))
            .toList();

        return new CustomerSubject(tenantId, List.copyOf(orders), List.copyOf(exchanges), List.copyOf(outGids),
            List.copyOf(phones), List.copyOf(numbers), numeric);
    }

    /** Payload numeric Shopify order ids → order GIDs (the form orders.external_id stores). */
    public static List<String> gidsOf(Iterable<com.fasterxml.jackson.databind.JsonNode> orderNodes) {
        List<String> out = new ArrayList<>();
        for (var n : orderNodes) {
            long id = n.isObject() ? n.path("id").asLong(0) : n.asLong(0);
            if (id > 0) out.add(GID_PREFIX + id);
        }
        return out;
    }

    UUID[] orderIdArray()     { return orderIds.toArray(new UUID[0]); }
    UUID[] exchangeIdArray()  { return exchangeIds.toArray(new UUID[0]); }
    String[] gidArray()       { return shopifyOrderGids.toArray(new String[0]); }
    String[] phoneArray()     { return phones.toArray(new String[0]); }
    String[] numberArray()    { return orderNumbers.toArray(new String[0]); }
    String[] numericIdArray() { return shopifyNumericIds.toArray(new String[0]); }

    /** WHERE fragment (alias u) for unlinked Bosta deliveries of this subject; binds 4 arrays — see {@link #unlinkedArgs}. */
    static final String UNLINKED_MATCH =
        "(" + canonicalPhoneSql("u.raw #>> '{receiver,phone}'") + " = ANY(?::text[]) " +
        " OR ltrim(u.business_reference, '#') = ANY(?::text[]) " +
        " OR ltrim(split_part(u.business_reference, ':', 2), '#') = ANY(?::text[]) " +
        " OR u.raw #>> '{shopifyInfo,orderId}' = ANY(?::text[]))";

    Object[] unlinkedArgs() {
        return new Object[]{phoneArray(), numberArray(), numberArray(), numericIdArray()};
    }
}
