package com.traceability.inventory;

import com.traceability.tenancy.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/**
 * Pick &amp; Pack S3 — the store's packing mode, readable by everyone who uses Pick &amp; Pack
 * (workers can't read GET /tenant/settings, which is owner/manager-only). Writing it is
 * owner-only, through PUT /api/v1/tenant/settings {pickPackMode}.
 */
@RestController
@RequestMapping("/api/v1/fulfill/mode")
public class PickPackModeController {

    private final JdbcTemplate jdbc;

    public PickPackModeController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    @Transactional(readOnly = true)
    public Map<String, String> mode() {
        return Map.of("mode", currentMode(jdbc));
    }

    /** The tenant's current pick_pack_mode; caller must be inside a transaction with the tenant set. */
    static String currentMode(JdbcTemplate jdbc) {
        UUID tenantId = TenantContext.require();
        return jdbc.queryForObject("SELECT pick_pack_mode FROM tenants WHERE id = ?", String.class, tenantId);
    }
}
