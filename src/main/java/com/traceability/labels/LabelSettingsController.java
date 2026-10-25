package com.traceability.labels;

import com.traceability.tenancy.TenantContext;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Flexible labeling slice 8 — Settings → Labels (owner + manager; 404 unless the tenant has flexible labeling).
 *
 *   GET /api/v1/label-settings   {receivingDefaultMode}
 *   PUT /api/v1/label-settings   {receivingDefaultMode: print_stick | count_only}
 *
 * Only the receiving step's pre-selected card. There is deliberately NO way here to switch flexible labeling on or
 * off — tenants.flexible_labeling_enabled stays admin-only.
 */
@RestController
@RequestMapping("/api/v1/label-settings")
public class LabelSettingsController {

    static final Set<String> MODES = Set.of("print_stick", "count_only");

    public record LabelSettings(String receivingDefaultMode) {}

    private final JdbcTemplate jdbc;

    public LabelSettingsController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    @Transactional(readOnly = true)
    public LabelSettings get() {
        UUID tenantId = TenantContext.require();
        FlexibleLabeling.require(jdbc, tenantId);
        return new LabelSettings(jdbc.queryForObject(
            "SELECT receiving_default_mode FROM tenants WHERE id = ?", String.class, tenantId));
    }

    @PutMapping
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    @Transactional
    public LabelSettings put(@RequestBody LabelSettings req) {
        UUID tenantId = TenantContext.require();
        FlexibleLabeling.require(jdbc, tenantId);
        if (req == null || req.receivingDefaultMode() == null || !MODES.contains(req.receivingDefaultMode())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "receivingDefaultMode must be print_stick or count_only");
        }
        jdbc.update("UPDATE tenants SET receiving_default_mode = ? WHERE id = ?", req.receivingDefaultMode(), tenantId);
        return new LabelSettings(req.receivingDefaultMode());
    }
}
