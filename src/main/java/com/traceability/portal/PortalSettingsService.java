package com.traceability.portal;

import com.traceability.tenancy.TenantContext;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

/**
 * Returns portal Step 4b — the merchant's portal settings (slug, enabled, auto-approve,
 * return window) and the per-variant non-returnable flag.
 *
 * Slug uniqueness under RLS: tenants.portal_slug has a GLOBAL UNIQUE constraint (V100). The
 * app_user session can only see its own tenant row, but constraint enforcement is not subject
 * to RLS — the unique index is checked against every row. So updating our own row to a slug
 * another tenant already holds raises a unique violation, mapped to 409. No cross-tenant read
 * and no SECURITY DEFINER function is involved.
 */
@Service
public class PortalSettingsService {

    static final Set<String> RESERVED = Set.of(
        "api", "admin", "app", "www", "returns", "static", "assets", "auth", "login", "demo", "help", "support", "status");
    private static final java.util.regex.Pattern SLUG = java.util.regex.Pattern.compile("^[a-z0-9-]{3,40}$");
    private static final String SLUG_UNIQUE = "tenants_portal_slug_unique";

    public record Settings(String slug, Boolean enabled, Boolean autoApprove, Integer returnWindowDays) {}

    private final JdbcTemplate jdbc;

    public PortalSettingsService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> get() {
        UUID tenantId = TenantContext.require();
        Map<String, Object> t = jdbc.queryForMap(
            "SELECT portal_slug, portal_enabled, portal_auto_approve, customer_return_window_days " +
            "FROM tenants WHERE id = ?", tenantId);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("slug", t.get("portal_slug"));
        body.put("enabled", t.get("portal_enabled"));
        body.put("autoApprove", t.get("portal_auto_approve"));
        body.put("returnWindowDays", t.get("customer_return_window_days"));
        return body;
    }

    @Transactional
    public Map<String, Object> update(Settings s) {
        UUID tenantId = TenantContext.require();
        if (s == null || s.enabled() == null || s.autoApprove() == null || s.returnWindowDays() == null) {
            throw bad("enabled, autoApprove and returnWindowDays are required.");
        }
        String slug = s.slug() == null || s.slug().isBlank() ? null : s.slug().trim().toLowerCase(Locale.ROOT);
        if (slug != null && !SLUG.matcher(slug).matches()) {
            throw bad("The address must be 3–40 characters: lowercase letters, digits and hyphens.");
        }
        if (slug != null && RESERVED.contains(slug)) {
            throw bad("That address is reserved. Please choose another.");
        }
        if (s.enabled() && slug == null) {
            throw bad("Choose an address before turning the returns portal on.");
        }
        if (s.returnWindowDays() < 1 || s.returnWindowDays() > 90) {
            throw bad("The return window must be between 1 and 90 days.");
        }
        try {
            jdbc.update(
                "UPDATE tenants SET portal_slug = ?, portal_enabled = ?, portal_auto_approve = ?, " +
                "    customer_return_window_days = ? WHERE id = ?",
                slug, s.enabled(), s.autoApprove(), s.returnWindowDays(), tenantId);
        } catch (DuplicateKeyException e) {
            if (String.valueOf(e.getMessage()).contains(SLUG_UNIQUE)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "That address is already taken.");
            }
            throw e;
        }
        return get();
    }

    /** PUT /api/v1/variants/{id}/non-returnable — 404 when the variant isn't this tenant's. */
    @Transactional
    public void setNonReturnable(UUID variantId, boolean value) {
        UUID tenantId = TenantContext.require();
        int updated = jdbc.update("UPDATE variants SET non_returnable = ? WHERE id = ? AND tenant_id = ?",
            value, variantId, tenantId);
        if (updated != 1) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Variant not found");
    }

    private static ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
