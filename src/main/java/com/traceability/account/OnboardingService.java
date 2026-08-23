package com.traceability.account;

import com.traceability.tenancy.TenantContext;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * FR-1.2: Manual overrides for onboarding checklist steps.
 *
 * Manual steps are stored on tenants.onboarding_manual_steps (V83) — tenant-level,
 * not per-user: a step ticked by the Owner shows ticked for the Manager too.
 * done = auto || manual, computed in OnboardingController.status(); this service
 * only ever mutates the manual side. Auto signals are never touched here.
 */
@Service
public class OnboardingService {

    // Must match OnboardingController.status()'s step set exactly.
    private static final Set<String> VALID_STEPS = Set.of(
        "connect_shopify", "connect_bosta", "location", "test_label", "first_receiving");

    private final JdbcTemplate jdbc;
    private final AuditService audit;

    public OnboardingService(JdbcTemplate jdbc, AuditService audit) {
        this.jdbc  = jdbc;
        this.audit = audit;
    }

    /** Reads the current manual-step set for the tenant in context. */
    public List<String> readManualSteps(UUID tenantId) {
        return jdbc.queryForList(
            "SELECT elem FROM jsonb_array_elements_text(" +
            "(SELECT onboarding_manual_steps FROM tenants WHERE id = ?)) AS elem",
            String.class, tenantId);
    }

    /**
     * checked=true  — add the step if absent (no duplicates).
     * checked=false — remove all occurrences of the step.
     * Both branches are single self-referencing UPDATEs — atomic, no read-then-write
     * race window between two concurrent toggles.
     */
    @Transactional
    public void setManualStep(UUID actorUserId, String step, boolean checked) {
        UUID tenantId = TenantContext.require();
        if (!VALID_STEPS.contains(step)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unknown step: " + step);
        }

        if (checked) {
            jdbc.update("""
                UPDATE tenants SET onboarding_manual_steps =
                    (SELECT jsonb_agg(DISTINCT elem) FROM jsonb_array_elements_text(
                        onboarding_manual_steps || to_jsonb(ARRAY[?]::text[])) AS elem)
                WHERE id = ?
                """, step, tenantId);
        } else {
            jdbc.update("""
                UPDATE tenants SET onboarding_manual_steps =
                    (SELECT COALESCE(jsonb_agg(elem), '[]'::jsonb)
                     FROM jsonb_array_elements_text(onboarding_manual_steps) AS elem
                     WHERE elem <> ?)
                WHERE id = ?
                """, step, tenantId);
        }

        audit.record(actorUserId, "onboarding_step_manual_set", "tenant", tenantId.toString(),
            Map.of("step", step, "checked", checked));
    }
}
