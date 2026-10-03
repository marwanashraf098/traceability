package com.traceability.integrations.bosta;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

/**
 * Review mode — is this tenant's courier simulated? (A row in tenant_courier_simulation, V130.)
 * The ONE place that question is asked in SQL.
 *
 * Runs on the caller's JdbcTemplate, inside the caller's transaction with TenantContext set to
 * {@code tenantId}: RLS lets a tenant read only its own row, so a missing / different tenant
 * context reads as "real" — the default every caller treats as the strict case.
 */
public final class CourierSimulation {

    private CourierSimulation() {}

    public static boolean isSimulated(JdbcTemplate jdbc, UUID tenantId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM tenant_courier_simulation WHERE tenant_id = ?)",
            Boolean.class, tenantId));
    }
}
