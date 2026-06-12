package com.traceability;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/**
 * Test-only service. Runs inside a real @Transactional boundary so
 * TenantAwareConnection fires SET LOCAL before any query. Used by test (e)
 * to assert that the GUC is set correctly through the full filter chain.
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
        Integer locationCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM locations", Integer.class);
        return Map.of("guc", guc, "locationCount", locationCount);
    }
}
