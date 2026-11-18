package com.traceability.integrations.bosta;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The orders a Bosta businessReference / shopifyInfo.orderId point at (2026-10-05) — the ONE
 * reference rule for linking, shared by ShipmentLinkService.matchByBusinessReference (SEND, RTO,
 * customer-return pickups), the exchange step (ExchangeMatchService.matchByReference) and the
 * "linked via exchange" fulfillment verdict.
 *
 * The reference is read by {@link BostaReferenceParser} (2026-11-17) — as sent, the part after the first ':'
 * and the part before it ("BRK-44868-EG:BRK-44868-EG-R1" → BRK-44868-EG; "blncoeg:#515956" → #515956), each as
 * an order number ('#'-stripped and '#'-prefixed, upper / lower case too) and as an external_id. {@link #resolve}
 * keeps a trailing -R&lt;digits&gt; (forward-leg / return-leg linking: a SEND for a Shopify Return's replacement must
 * never land on the original); {@link #resolveTrip} also strips it (an exchange's ORIGINAL order:
 * "jamilatheboutique:#64968-R1" → #64968). Only when the reference finds nothing is shopifyInfo.orderId tried, as
 * a Shopify order GID. Internal orders (external_id 'internal:…', e.g. an exchange's EXC-… replacement, a
 * pre-connect 'internal:portal:' row) never match — a pre-connect parent is set only by
 * ExchangeMatchService.matchToPreConnectParent. Orders exist in Traced only from the connection on.
 *
 * Returns the distinct matching order ids (at most 2): exactly one = the order; two = ambiguous,
 * never guess. Runs on the caller's connection and tenant context; every query is tenant_id-bound.
 */
public final class OrderReference {

    private OrderReference() {}

    public static List<UUID> resolve(JdbcTemplate jdbc, UUID tenantId, String reference, String shopifyOrderId) {
        return resolve(jdbc, tenantId, reference, shopifyOrderId, false);
    }

    /** {@link #resolve}, with a trailing -R&lt;digits&gt; (a Shopify Return name) also stripped — an exchange's original. */
    public static List<UUID> resolveTrip(JdbcTemplate jdbc, UUID tenantId, String reference, String shopifyOrderId) {
        return resolve(jdbc, tenantId, reference, shopifyOrderId, true);
    }

    private static List<UUID> resolve(JdbcTemplate jdbc, UUID tenantId, String reference, String shopifyOrderId,
                                      boolean stripReturnSuffix) {
        Set<String> numbers = BostaReferenceParser.numberVariants(reference, stripReturnSuffix);
        Set<String> externalIds = new LinkedHashSet<>(BostaReferenceParser.candidates(reference, stripReturnSuffix));
        if (!numbers.isEmpty()) {
            List<UUID> ids = query(jdbc, tenantId, numbers, externalIds);
            if (!ids.isEmpty()) return ids;
        }
        if (shopifyOrderId != null && !shopifyOrderId.isBlank()) {
            return query(jdbc, tenantId, Set.of(), Set.of("gid://shopify/Order/" + shopifyOrderId.trim()));
        }
        return List.of();
    }

    private static List<UUID> query(JdbcTemplate jdbc, UUID tenantId, Set<String> numbers, Set<String> externalIds) {
        List<Object> args = new ArrayList<>();
        args.add(tenantId);
        StringBuilder where = new StringBuilder();
        if (!numbers.isEmpty()) {
            where.append("number IN (").append(String.join(",", Collections.nCopies(numbers.size(), "?"))).append(")");
            args.addAll(numbers);
        }
        if (!externalIds.isEmpty()) {
            if (where.length() > 0) where.append(" OR ");
            where.append("external_id IN (").append(String.join(",", Collections.nCopies(externalIds.size(), "?"))).append(")");
            args.addAll(externalIds);
        }
        return jdbc.queryForList(
            "SELECT DISTINCT id FROM orders WHERE tenant_id = ? AND external_id NOT LIKE 'internal:%' " +
            "  AND (" + where + ") LIMIT 2",
            UUID.class, args.toArray());
    }
}
