package com.traceability.places;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

/**
 * Warehouse places — the ONE answer to "does this tenant have places": {@code tenants.places_enabled} (V189, default
 * false; since the rollout the owner / manager switch in Settings › Places, {@link PlacesSettingsService}). Read on
 * the caller's connection and tenant context, like FlexibleLabeling; /me exposes it as {@code places}.
 *
 * <p>Rollout (2026-11-01) — DORMANT UNTIL SET UP. A location is "set up" ({@link #setUp}) when places are on AND it
 * has at least one live (non-archived) shelf or box. Every place feature tied to a location (pick places, Lookup's
 * place row, stock-take stickers, put-away lists) acts as places-off for a location that isn't set up; /me's
 * {@code placesDormant} (on, but no location set up) hides the rest (Put away, its badge and tile). Settings › Places
 * itself needs only the switch.
 */
public final class Places {

    /** The statuses a piece may hold a place in — the same list pieces_bin_guard (V189) clears outside of. */
    public static final List<String> PLACEABLE_STATUSES = List.of("available", "on_hold", "damaged");

    private Places() {}

    /** A tenants row the caller can't see (no / another tenant context under RLS) reads as off, never an error. */
    public static boolean enabled(JdbcTemplate jdbc, UUID tenantId) {
        List<Boolean> on = jdbc.queryForList(
            "SELECT places_enabled FROM tenants WHERE id = ?", Boolean.class, tenantId);
        return !on.isEmpty() && Boolean.TRUE.equals(on.get(0));
    }

    /** A live shelf or box at location {@code l} of tenant {@code t} — {@code l}, {@code t} are SQL expressions. */
    static String liveShelfOrBoxAt(String t, String l) {
        return "EXISTS (SELECT 1 FROM bins b WHERE b.tenant_id = " + t + " AND b.location_id = " + l +
               " AND b.kind IN ('shelf', 'box') AND b.archived_at IS NULL)";
    }

    /** Places on and the location set up (a live shelf or box there). A null location is never set up. */
    public static boolean setUp(JdbcTemplate jdbc, UUID tenantId, UUID locationId) {
        if (locationId == null) return false;
        return firstTrue(jdbc.queryForList(
            "SELECT places_enabled AND " + liveShelfOrBoxAt("t.id", "?") + " FROM tenants t WHERE t.id = ?",
            Boolean.class, locationId, tenantId));
    }

    /** Places on and the MAIN warehouse (locations.is_fulfillment) set up — one statement. */
    public static boolean mainSetUp(JdbcTemplate jdbc, UUID tenantId) {
        return firstTrue(jdbc.queryForList(
            "SELECT t.places_enabled AND EXISTS (SELECT 1 FROM locations l WHERE l.tenant_id = t.id AND l.is_fulfillment " +
            "AND " + liveShelfOrBoxAt("t.id", "l.id") + ") FROM tenants t WHERE t.id = ?",
            Boolean.class, tenantId));
    }

    /** Places on and at least one location set up — the rest of the app shows place features only then. */
    public static boolean anySetUp(JdbcTemplate jdbc, UUID tenantId) {
        return firstTrue(jdbc.queryForList(
            "SELECT t.places_enabled AND EXISTS (SELECT 1 FROM bins b WHERE b.tenant_id = t.id " +
            "AND b.kind IN ('shelf', 'box') AND b.archived_at IS NULL) FROM tenants t WHERE t.id = ?",
            Boolean.class, tenantId));
    }

    /** As {@link #enabled}: a tenants row the caller can't see reads as off. */
    private static boolean firstTrue(List<Boolean> row) {
        return !row.isEmpty() && Boolean.TRUE.equals(row.get(0));
    }

    /** 404 (bodyless, like FlexibleLabeling) unless the tenant has places — the endpoints don't exist for it. */
    static void require(JdbcTemplate jdbc, UUID tenantId) {
        if (!enabled(jdbc, tenantId)) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }

    /** 404, exactly as with places off, unless the main warehouse is set up. */
    static void requireMainSetUp(JdbcTemplate jdbc, UUID tenantId) {
        if (!mainSetUp(jdbc, tenantId)) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }
}
