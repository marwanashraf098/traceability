package com.traceability.account;

import com.traceability.identity.CustomUserDetails;
import com.traceability.tenancy.TenantContext;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

/**
 * FR-1.4: Tenant settings — general business configuration.
 *
 * Field ownership (see SHOW FIRST analysis):
 *   tenants.name              — business name
 *   tenants.label_width_mm    — label size (40x25 or 50x25)
 *   tenants.label_height_mm
 *   tenants.default_language  — 'ar' | 'en'
 *   tenants.timezone          — IANA tz string, default 'Africa/Cairo'
 *   tenants.pickup_address    — human-readable business address (≠ Bosta location ID)
 *
 * Bosta-specific fields (awb_format, pickup_business_location_id, etc.) are
 * on courier_accounts and are managed via PUT /api/v1/bosta/settings.
 */
@RestController
@RequestMapping("/api/v1/tenant")
public class TenantController {

    private final JdbcTemplate       jdbc;
    private final TransactionTemplate tx;
    private final AuditService       audit;

    public TenantController(JdbcTemplate jdbc,
                             PlatformTransactionManager txm,
                             AuditService audit) {
        this.jdbc  = jdbc;
        this.tx    = new TransactionTemplate(txm);
        this.audit = audit;
    }

    public record TenantSettingsRequest(
        String name,
        String pickupAddress,
        String labelSize,       // "40x25" or "50x25"
        String defaultLanguage, // "ar" or "en"
        String timezone
    ) {}

    /** GET /api/v1/tenant/settings */
    @GetMapping("/settings")
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    public Map<String, Object> get(@AuthenticationPrincipal CustomUserDetails principal) {
        UUID tenantId = principal.tenantId();
        return TenantContext.runAs(tenantId, () ->
            tx.execute(s -> jdbc.query(
                "SELECT name, label_width_mm, label_height_mm, " +
                "       default_language, timezone, pickup_address " +
                "FROM tenants WHERE id = ?",
                rs -> {
                    if (!rs.next()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Tenant not found");
                    double w = rs.getDouble("label_width_mm");
                    double h = rs.getDouble("label_height_mm");
                    String labelSize = (w == 40 && h == 25) ? "40x25" : "50x25";

                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("name",            rs.getString("name"));
                    m.put("pickupAddress",   rs.getString("pickup_address"));
                    m.put("labelSize",       labelSize);
                    m.put("defaultLanguage", rs.getString("default_language"));
                    m.put("timezone",        rs.getString("timezone"));
                    return m;
                }, tenantId)));
    }

    /** PUT /api/v1/tenant/settings — all fields optional (COALESCE). Owner-only. */
    @PutMapping("/settings")
    @PreAuthorize("hasRole('OWNER')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void update(
            @RequestBody TenantSettingsRequest req,
            @AuthenticationPrincipal CustomUserDetails principal) {

        UUID tenantId = principal.tenantId();

        Double labelW = null, labelH = null;
        if (req.labelSize() != null) {
            switch (req.labelSize()) {
                case "40x25" -> { labelW = 40.0; labelH = 25.0; }
                case "50x25" -> { labelW = 50.0; labelH = 25.0; }
                default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "labelSize must be '40x25' or '50x25'");
            }
        }

        if (req.defaultLanguage() != null
                && !Set.of("ar", "en").contains(req.defaultLanguage())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "defaultLanguage must be 'ar' or 'en'");
        }

        final Double finalW = labelW, finalH = labelH;
        TenantContext.runAs(tenantId, () -> tx.execute(s -> {
            jdbc.update("""
                UPDATE tenants SET
                    name             = COALESCE(?, name),
                    pickup_address   = COALESCE(?, pickup_address),
                    label_width_mm   = COALESCE(?, label_width_mm),
                    label_height_mm  = COALESCE(?, label_height_mm),
                    default_language = COALESCE(?, default_language),
                    timezone         = COALESCE(?, timezone)
                WHERE id = ?
                """,
                req.name(), req.pickupAddress(), finalW, finalH,
                req.defaultLanguage(), req.timezone(), tenantId);
            return null;
        }));

        Map<String, Object> meta = new LinkedHashMap<>();
        if (req.name()            != null) meta.put("name",            req.name());
        if (req.pickupAddress()   != null) meta.put("pickupAddress",   req.pickupAddress());
        if (req.labelSize()       != null) meta.put("labelSize",       req.labelSize());
        if (req.defaultLanguage() != null) meta.put("defaultLanguage", req.defaultLanguage());
        if (req.timezone()        != null) meta.put("timezone",        req.timezone());

        TenantContext.runAs(tenantId, () -> {
            audit.record(principal.userId(), "tenant_settings_update", "tenant",
                tenantId.toString(), meta);
            return null;
        });
    }
}
