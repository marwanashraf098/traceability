package com.traceability.courier;

import com.traceability.account.AuditService;
import com.traceability.tenancy.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Non-Bosta couriers G4a — Settings → Returns portal: "Accept returns for orders shipped with other couriers"
 * ({@code tenants.portal_generic_returns_enabled}, V177). Its own endpoint, so the existing portal-settings
 * response is unchanged for every store. Owner writes (the controller gates it), owner + manager read; each
 * change is one audit_log row (from → to). Switching on stamps {@code portal_generic_returns_since}; it can be
 * switched on only while the store ships with other couriers (generic_courier_enabled) — off always works.
 */
@Service
public class GenericReturnsSettingsService {

    public record Settings(boolean available, boolean enabled, Instant since) {}

    private final JdbcTemplate jdbc;
    private final AuditService audit;

    public GenericReturnsSettingsService(JdbcTemplate jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public Settings get() {
        Map<String, Object> t = jdbc.queryForMap(
            "SELECT generic_courier_enabled, portal_generic_returns_enabled, portal_generic_returns_since " +
            "FROM tenants WHERE id = ?", TenantContext.require());
        java.sql.Timestamp since = (java.sql.Timestamp) t.get("portal_generic_returns_since");
        return new Settings(Boolean.TRUE.equals(t.get("generic_courier_enabled")),
            Boolean.TRUE.equals(t.get("portal_generic_returns_enabled")), since == null ? null : since.toInstant());
    }

    @Transactional
    public Settings update(UUID actorUserId, boolean enabled) {
        UUID tenantId = TenantContext.require();
        Map<String, Object> before = jdbc.queryForMap(
            "SELECT generic_courier_enabled, portal_generic_returns_enabled FROM tenants WHERE id = ? FOR UPDATE", tenantId);
        boolean was = Boolean.TRUE.equals(before.get("portal_generic_returns_enabled"));
        if (enabled && !was && !Boolean.TRUE.equals(before.get("generic_courier_enabled"))) {
            throw GenericReturnException.otherCouriersOff();
        }
        if (was != enabled) {
            jdbc.update("UPDATE tenants SET portal_generic_returns_enabled = ?, " +
                "    portal_generic_returns_since = CASE WHEN ? THEN now() ELSE portal_generic_returns_since END WHERE id = ?",
                enabled, enabled, tenantId);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("from", was);
            m.put("to", enabled);
            audit.record(actorUserId, "portal_generic_returns_switch", "tenant", tenantId.toString(), m);
        }
        return get();
    }
}
