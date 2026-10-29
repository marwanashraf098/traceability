package com.traceability.labels;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

/**
 * Flexible labeling — the ONE answer to "is it on for this tenant": {@code tenants.flexible_labeling_enabled}
 * (V176; default true and on for every tenant since V193 — an admin-only kill switch). Read on the caller's connection and
 * tenant context, like GenericShipments.enabled; /me exposes it as {@code flexibleLabeling}.
 */
public final class FlexibleLabeling {

    private FlexibleLabeling() {}

    /** A tenants row the caller can't see (no / another tenant context under RLS) reads as off, never an error. */
    public static boolean enabled(JdbcTemplate jdbc, UUID tenantId) {
        List<Boolean> on = jdbc.queryForList(
            "SELECT flexible_labeling_enabled FROM tenants WHERE id = ?", Boolean.class, tenantId);
        return !on.isEmpty() && Boolean.TRUE.equals(on.get(0));
    }

    /** 404 (bodyless, like the scan helpers) unless the tenant has it — the endpoints don't exist for it. */
    static void require(JdbcTemplate jdbc, UUID tenantId) {
        if (!enabled(jdbc, tenantId)) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }
}
