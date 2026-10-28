package com.traceability.places;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

/**
 * Warehouse places — the ONE answer to "does this tenant have places": {@code tenants.places_enabled} (V189, default
 * false, set by ops — there is no merchant switch). Read on the caller's connection and tenant context, like
 * FlexibleLabeling; /me exposes it as {@code places}.
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

    /** 404 (bodyless, like FlexibleLabeling) unless the tenant has places — the endpoints don't exist for it. */
    static void require(JdbcTemplate jdbc, UUID tenantId) {
        if (!enabled(jdbc, tenantId)) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }
}
