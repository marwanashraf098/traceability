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

    static final String LOGO_PREFIX = "https://cdn.shopify.com/";
    private static final java.util.regex.Pattern COLOR = java.util.regex.Pattern.compile("^#[0-9A-Fa-f]{6}$");
    static final int POLICY_MAX = 2000;

    /**
     * PUT body. Full replace: a branding field that is absent or blank is stored as NULL.
     * The 4-argument constructor is the Step 4b shape (no branding).
     */
    public record Settings(String slug, Boolean enabled, Boolean autoApprove, Integer returnWindowDays,
                           String logoUrl, String brandColor, String policyText) {
        public Settings(String slug, Boolean enabled, Boolean autoApprove, Integer returnWindowDays) {
            this(slug, enabled, autoApprove, returnWindowDays, null, null, null);
        }
    }

    /**
     * A 400/409 tied to one field, so the settings page can show the error next to it.
     * {@code field} is null when the error isn't about a single field; {@code code} is stable
     * for the client, the reason is the English message.
     */
    public static class FieldException extends ResponseStatusException {
        private final String field;
        private final String code;

        FieldException(HttpStatus status, String field, String code, String message) {
            super(status, message);
            this.field = field;
            this.code = code;
        }

        public String field() { return field; }
        public String code()  { return code; }
    }

    private final JdbcTemplate jdbc;

    public PortalSettingsService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> get() {
        UUID tenantId = TenantContext.require();
        Map<String, Object> t = jdbc.queryForMap(
            "SELECT portal_slug, portal_enabled, portal_auto_approve, customer_return_window_days, " +
            "       portal_logo_url, portal_brand_color, portal_policy_text " +
            "FROM tenants WHERE id = ?", tenantId);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("slug", t.get("portal_slug"));
        body.put("enabled", t.get("portal_enabled"));
        body.put("autoApprove", t.get("portal_auto_approve"));
        body.put("returnWindowDays", t.get("customer_return_window_days"));
        body.put("logoUrl", t.get("portal_logo_url"));
        body.put("brandColor", t.get("portal_brand_color"));
        body.put("policyText", t.get("portal_policy_text"));
        // Read-only: lets the merchant UI word the approve footer (no Bosta pickup before Step 4c).
        body.put("pickupBooking", PortalService.PICKUP_BOOKING);
        return body;
    }

    @Transactional
    public Map<String, Object> update(Settings s) {
        UUID tenantId = TenantContext.require();
        if (s == null || s.enabled() == null || s.autoApprove() == null || s.returnWindowDays() == null) {
            throw bad(null, "REQUIRED", "enabled, autoApprove and returnWindowDays are required.");
        }
        String slug = s.slug() == null || s.slug().isBlank() ? null : s.slug().trim().toLowerCase(Locale.ROOT);
        if (slug != null && !SLUG.matcher(slug).matches()) {
            throw bad("slug", "SLUG_FORMAT", "The address must be 3–40 characters: lowercase letters, digits and hyphens.");
        }
        if (slug != null && RESERVED.contains(slug)) {
            throw bad("slug", "SLUG_RESERVED", "That address is reserved. Please choose another.");
        }
        if (s.enabled() && slug == null) {
            throw bad("slug", "SLUG_REQUIRED", "Choose an address before turning the returns portal on.");
        }
        if (s.returnWindowDays() < 1 || s.returnWindowDays() > 90) {
            throw bad("returnWindowDays", "WINDOW_RANGE", "The return window must be between 1 and 90 days.");
        }
        // Branding — the same rules as V103's CHECKs, answered as 400s before the UPDATE.
        String logoUrl = blankToNull(s.logoUrl());
        if (logoUrl != null && !logoUrl.startsWith(LOGO_PREFIX)) {
            throw bad("logoUrl", "LOGO_URL", "The logo link must start with " + LOGO_PREFIX);
        }
        String brandColor = blankToNull(s.brandColor());
        if (brandColor != null && !COLOR.matcher(brandColor).matches()) {
            throw bad("brandColor", "BRAND_COLOR", "The brand colour must be a hex colour like #1A2B3C.");
        }
        String policyText = s.policyText() == null || s.policyText().isBlank() ? null : s.policyText();
        if (policyText != null && policyText.length() > POLICY_MAX) {
            throw bad("policyText", "POLICY_LENGTH", "The return policy can be at most " + POLICY_MAX + " characters.");
        }
        try {
            jdbc.update(
                "UPDATE tenants SET portal_slug = ?, portal_enabled = ?, portal_auto_approve = ?, " +
                "    customer_return_window_days = ?, portal_logo_url = ?, portal_brand_color = ?, " +
                "    portal_policy_text = ? WHERE id = ?",
                slug, s.enabled(), s.autoApprove(), s.returnWindowDays(), logoUrl, brandColor, policyText, tenantId);
        } catch (DuplicateKeyException e) {
            if (String.valueOf(e.getMessage()).contains(SLUG_UNIQUE)) {
                throw new FieldException(HttpStatus.CONFLICT, "slug", "SLUG_TAKEN", "That address is already taken.");
            }
            throw e;
        }
        return get();
    }

    /**
     * GET /api/v1/variants — this tenant's variants with their non-returnable flag, optionally
     * filtered by product title, variant title or SKU (case-insensitive substring; % and _ are
     * matched literally). ORDER BY product title, variant title, id.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> listVariants(String search, int page, int size) {
        UUID tenantId = TenantContext.require();
        String pattern = search == null || search.isBlank() ? null
            : "%" + search.trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        String where =
            "FROM variants v JOIN products pr ON pr.id = v.product_id AND pr.tenant_id = v.tenant_id " +
            "WHERE v.tenant_id = ? AND (?::text IS NULL OR pr.title ILIKE ? OR v.title ILIKE ? OR v.sku ILIKE ?) ";
        List<Map<String, Object>> items = jdbc.query(
            "SELECT v.id, pr.title AS product_title, v.title AS variant_title, v.sku, v.non_returnable " + where +
            "ORDER BY pr.title, v.title, v.id LIMIT ? OFFSET ?",
            (rs, i) -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", rs.getObject("id", UUID.class).toString());
                row.put("productTitle", rs.getString("product_title"));
                row.put("variantTitle", rs.getString("variant_title"));
                row.put("sku", rs.getString("sku"));
                row.put("nonReturnable", rs.getBoolean("non_returnable"));
                return row;
            },
            tenantId, pattern, pattern, pattern, pattern, size, page * size);
        Integer total = jdbc.queryForObject("SELECT COUNT(*) " + where, Integer.class,
            tenantId, pattern, pattern, pattern, pattern);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", items);
        result.put("total", total);
        return result;
    }

    /** PUT /api/v1/variants/{id}/non-returnable — 404 when the variant isn't this tenant's. */
    @Transactional
    public void setNonReturnable(UUID variantId, boolean value) {
        UUID tenantId = TenantContext.require();
        int updated = jdbc.update("UPDATE variants SET non_returnable = ? WHERE id = ? AND tenant_id = ?",
            value, variantId, tenantId);
        if (updated != 1) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Variant not found");
    }

    private static FieldException bad(String field, String code, String message) {
        return new FieldException(HttpStatus.BAD_REQUEST, field, code, message);
    }

    private static String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }
}
