package com.traceability.bazar;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.UUID;

/**
 * Bazar POS — the ONE answer to "does this tenant have bazar": {@code tenants.bazar_enabled} (V231, default false,
 * set by ops). Off → every bazar endpoint answers 404 BAZAR_NOT_ENABLED.
 */
public final class Bazar {

    private Bazar() {}

    /** A tenants row the caller can't see (no / another tenant context under RLS) reads as off, never an error. */
    public static boolean enabled(JdbcTemplate jdbc, UUID tenantId) {
        List<Boolean> on = jdbc.queryForList(
            "SELECT bazar_enabled FROM tenants WHERE id = ?", Boolean.class, tenantId);
        return !on.isEmpty() && Boolean.TRUE.equals(on.get(0));
    }
}
