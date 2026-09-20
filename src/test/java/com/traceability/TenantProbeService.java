package com.traceability;

import com.traceability.tenancy.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

/**
 * Test-only service. Runs inside a real @Transactional boundary so
 * TenantAwareConnection fires SET LOCAL before any query. Used by test (e)
 * to assert that the GUC is set correctly through the full filter chain.
 *
 * locationCount is scoped to TenantContext.require() (WHERE tenant_id = ?), matching how
 * real product queries scope — NOT a raw cross-tenant COUNT(*). This test class connects as
 * the Testcontainers Postgres superuser (bypasses RLS), so an unscoped COUNT(*) previously
 * only "worked" because this was the only tenant in the database; DemoBootstrapStartupListener
 * now (correctly) creates the demo tenant's own location at every full-context startup, which
 * made the unscoped count wrong, not the listener.
 */
@Service
class TenantProbeService {

    private final JdbcTemplate jdbc;

    TenantProbeService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public Map<String, Object> probe() {
        String guc = jdbc.queryForObject(
                "SELECT COALESCE(current_setting('app.current_tenant', true), 'null')",
                String.class);
        UUID tenantId = TenantContext.require();
        Integer locationCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM locations WHERE tenant_id = ?", Integer.class, tenantId);
        return Map.of("guc", guc, "locationCount", locationCount);
    }
}
