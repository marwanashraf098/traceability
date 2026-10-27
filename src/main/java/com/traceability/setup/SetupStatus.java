package com.traceability.setup;

import com.traceability.review.ReviewCapabilities;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;
import java.util.UUID;

/**
 * Onboarding gate (V186) — "what does this tenant still have to set up before using Traced", asked in
 * ONE place. Runs on the caller's JdbcTemplate inside its transaction with TenantContext = tenantId
 * (RLS: a tenant reads only its own tenants / locations rows).
 *
 * {@link #step}:
 *   null       — not gated: the public demo (is_demo) or the review tenant (simulated courier) —
 *                ReviewCapabilities.scanHelpers is exactly that set —, a tenant whose setup is done
 *                (tenants.setup_completed_at, backfilled for every tenant connected at V186), or one
 *                that has connected and whose main warehouse already has an address.
 *   'shopify'  — never connected Shopify (the sticky tenants.shopify_connected_at is NULL). Live
 *                store status is never read: an expired token or needs_reauth never re-locks.
 *   'address'  — connected, the main warehouse (is_fulfillment) has no address yet.
 *
 * {@link #addressNeeded}: the soft banner — not gated (step null), not demo / review, and the main
 * warehouse still has no address (the grandfathered tenants).
 */
public final class SetupStatus {

    private SetupStatus() {}

    public static final String SHOPIFY = "shopify";
    public static final String ADDRESS = "address";

    /** Main warehouse has the three required parts (governorate, city / area, street). */
    static final String ADDRESS_COMPLETE_SQL =
        "address_province_code IS NOT NULL AND address_city IS NOT NULL AND address_street IS NOT NULL";

    public static String step(JdbcTemplate jdbc, UUID tenantId) {
        if (ReviewCapabilities.scanHelpers(jdbc, tenantId)) return null;
        Map<String, Object> t = jdbc.queryForMap(
            "SELECT t.setup_completed_at IS NOT NULL AS done, t.shopify_connected_at IS NOT NULL AS connected, " +
            "       EXISTS (SELECT 1 FROM locations l WHERE l.tenant_id = t.id AND l.is_fulfillment " +
            "                 AND l.address_province_code IS NOT NULL AND l.address_city IS NOT NULL " +
            "                 AND l.address_street IS NOT NULL) AS has_address " +
            "FROM tenants t WHERE t.id = ?", tenantId);
        if (Boolean.TRUE.equals(t.get("done"))) return null;
        if (!Boolean.TRUE.equals(t.get("connected"))) return SHOPIFY;
        if (Boolean.TRUE.equals(t.get("has_address"))) return null;
        return ADDRESS;
    }

    public static boolean addressNeeded(JdbcTemplate jdbc, UUID tenantId, String step) {
        if (step != null) return false;
        if (ReviewCapabilities.scanHelpers(jdbc, tenantId)) return false;
        return !Boolean.TRUE.equals(jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM locations WHERE tenant_id = ? AND is_fulfillment AND "
                + ADDRESS_COMPLETE_SQL + ")",
            Boolean.class, tenantId));
    }
}
