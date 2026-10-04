package com.traceability.review;

import com.traceability.integrations.bosta.CourierSimulation;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

/**
 * Review mode S7 — what a tenant's screens may offer beyond the real product, asked in ONE place.
 * Runs on the caller's JdbcTemplate inside its transaction with TenantContext = {@code tenantId}
 * (RLS: a tenant reads only its own tenants / tenant_courier_simulation rows, so a missing or
 * different context reads as a real merchant — every flag false).
 *
 *   scanHelpers — click-to-scan chips (no scanner / printer needed): the public demo tenant
 *                 (is_demo) and a simulated-courier review tenant. Real merchants: never.
 *   demoMode    — the public demo only (is_demo): e.g. the station exit without a password.
 */
public final class ReviewCapabilities {

    private ReviewCapabilities() {}

    public static boolean demoMode(JdbcTemplate jdbc, UUID tenantId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM tenants WHERE id = ? AND is_demo)", Boolean.class, tenantId));
    }

    public static boolean scanHelpers(JdbcTemplate jdbc, UUID tenantId) {
        return demoMode(jdbc, tenantId) || CourierSimulation.isSimulated(jdbc, tenantId);
    }
}
