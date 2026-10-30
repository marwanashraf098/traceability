package com.traceability.inventory;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.UUID;

/**
 * The Traced location's state in Shopify (V196, 2026-10-30) — the ONE writer of
 * locations.shopify_location_state. Runs on the caller's JdbcTemplate and transaction (tenant-scoped
 * under RLS on app_user), like PieceShopifyRules.
 *
 * Two signals set it: a Shopify write that failed because of the location ({@link #fromError} — the
 * activation Shopify runs before every increment answers "…because the location was deactivated" /
 * "…because the location wasn't found"), and the periodic check (ShopifyLocationCheckJob), which is
 * also the only thing that sets it back to 'active'. While it is not 'active', every update is held
 * (IncrementRecoveryRules.holdReason).
 */
public final class ShopifyLocationState {

    private ShopifyLocationState() {}

    public static final String ACTIVE   = "active";
    public static final String INACTIVE = "inactive";
    public static final String DELETED  = "deleted";

    /**
     * The state a Shopify error names, or null when it doesn't name the location. Only Shopify's own phrases —
     * never Traced's "Location not found: <id>" (a missing Traced row, not a Shopify answer).
     */
    public static String fromError(String error) {
        if (error == null) return null;
        String e = error.toLowerCase();
        if (e.contains("because the location was deactivated")) return INACTIVE;
        if (e.contains("because the location wasn't found") || e.contains("because the location was not found")) return DELETED;
        return null;
    }

    /** Records the state a failed write names on the tenant's linked main warehouse (when it is that location);
     *  true when the state changed. */
    public static boolean noteFailure(JdbcTemplate jdbc, UUID tenantId, UUID locationId, String error) {
        String state = fromError(error);
        if (state == null || locationId == null) return false;
        return record(jdbc, tenantId, locationId, state) != null;
    }

    /**
     * Sets the main warehouse's state; returns the PREVIOUS state when it changed, null when it was already that
     * (or the location isn't the tenant's linked main warehouse).
     */
    public static String record(JdbcTemplate jdbc, UUID tenantId, UUID locationId, String state) {
        List<String> previous = jdbc.queryForList(
            "UPDATE locations l SET shopify_location_state = ?, shopify_location_state_at = now() " +
            "FROM locations old WHERE old.id = l.id AND l.id = ? AND l.tenant_id = ? AND l.is_fulfillment = true " +
            "  AND l.shopify_sync_status = 'linked' AND l.shopify_location_state <> ? " +
            "RETURNING old.shopify_location_state", String.class, state, locationId, tenantId, state);
        return previous.isEmpty() ? null : previous.get(0);
    }
}
