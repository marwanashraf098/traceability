package com.traceability.bazar;

import com.traceability.account.AuditService;
import com.traceability.tenancy.TenantContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

/**
 * Bazar B9a — a store that signs up to sell at a bazar (onboarding_prefs.intent = 'bazar', web or App Store) gets
 * {@code tenants.bazar_enabled} switched on in the SAME signup transaction — ONLY while the server setting
 * {@code BAZAR_SIGNUP_ENABLED} ({@code traced.bazar.signup-enabled}, read once at startup, default false) is on.
 * Off: nothing is written; the store stays off until ops switch it on (today's rollout, V231). A normal signup never
 * calls this. One {@code audit_log} row {@code bazar_enabled_at_signup} when it is set.
 */
@Component
public class BazarSignup {

    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final boolean enabled;

    public BazarSignup(JdbcTemplate jdbc, AuditService audit,
                       @Value("${traced.bazar.signup-enabled:false}") boolean enabled) {
        this.jdbc    = jdbc;
        this.audit   = audit;
        this.enabled = enabled;
    }

    public boolean enabled() { return enabled; }

    /** Caller's (the signup's) transaction, TenantContext = the new tenant. No-op while the setting is off. */
    @Transactional
    public void enableAtSignup(UUID ownerUserId) {
        if (!enabled) return;
        UUID tenantId = TenantContext.require();
        int n = jdbc.update("UPDATE tenants SET bazar_enabled = true WHERE id = ? AND NOT bazar_enabled", tenantId);
        if (n == 1) {
            audit.record(ownerUserId, "bazar_enabled_at_signup", "tenant", tenantId.toString(),
                Map.of("source", "signup_intent"));
        }
    }
}
