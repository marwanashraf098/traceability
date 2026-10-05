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
 * The reference is tried through {@link PreConnectDeliveryFilter#referenceCandidates} — as sent and,
 * when it contains ':', the parts before and after it ("BRK-44868-EG:BRK-44868-EG-R1" → BRK-44868-EG;
 * "blncoeg:#515956" → #515956) — each as an order number ('#'-stripped and '#'-prefixed too) and as an
 * external_id. Only when the reference finds nothing is shopifyInfo.orderId tried, as a Shopify order
 * GID. Internal orders (external_id 'internal:…', e.g. an exchange's EXC-… replacement) never match.
 * Orders exist in Traced only from the connection on, so a pre-connect order never resolves.
 *
 * Returns the distinct matching order ids (at most 2): exactly one = the order; two = ambiguous,
 * never guess. Runs on the caller's connection and tenant context; every query is tenant_id-bound.
 */
public final class OrderReference {

    private OrderReference() {}

    public static List<UUID> resolve(JdbcTemplate jdbc, UUID tenantId, String reference, String shopifyOrderId) {
        Set<String> numbers = new LinkedHashSet<>();
        Set<String> externalIds = new LinkedHashSet<>();
        for (String c : PreConnectDeliveryFilter.referenceCandidates(reference)) {
            String bare = c.startsWith("#") ? c.substring(1).trim() : c;
            if (bare.isEmpty()) continue;
            numbers.add(c);
            numbers.add(bare);
            numbers.add("#" + bare);
            externalIds.add(c);
        }
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
