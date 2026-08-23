package com.traceability.account;

import com.traceability.identity.CustomUserDetails;
import com.traceability.tenancy.TenantContext;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.*;

/**
 * FR-1.2: Onboarding checklist state.
 *
 * Each of the 5 steps has an AUTO signal (derived from real table state) and an
 * optional MANUAL override (tenants.onboarding_manual_steps, V83, set via
 * POST /steps). done = auto || manual. Unchecking a manual override only clears
 * the manual flag — if the auto signal is still true, the step stays done.
 *
 * Signal map:
 *   ① connect_shopify  → stores.status = 'connected'
 *   ② connect_bosta    → courier_accounts.status = 'active'
 *   ③ location         → EXISTS(locations for tenant)
 *   ④ test_label       → label_reprints (any row for tenant)
 *                        Note: initial label print at finalization is not separately tracked;
 *                        label_reprints tracks explicit reprints. Steps ④ and ⑤ often
 *                        complete simultaneously since the first reprint follows the first session.
 *   ⑤ first_receiving  → receipts.finalized_at IS NOT NULL (any finalized session)
 */
@RestController
@RequestMapping("/api/v1/onboarding")
public class OnboardingController {

    private final JdbcTemplate        jdbc;
    private final TransactionTemplate tx;
    private final OnboardingService   onboardingService;

    public OnboardingController(JdbcTemplate jdbc, PlatformTransactionManager txm,
                                 OnboardingService onboardingService) {
        this.jdbc              = jdbc;
        this.tx                = new TransactionTemplate(txm);
        this.onboardingService = onboardingService;
    }

    /**
     * GET /api/v1/onboarding/status
     *
     * Returns each of the 5 checklist steps as {key, done, auto, manual}, where
     * done = auto || manual.
     *
     * Response shape:
     * {
     *   "steps": [
     *     { "key": "connect_shopify",  "done": bool, "auto": bool, "manual": bool },
     *     { "key": "connect_bosta",    "done": bool, "auto": bool, "manual": bool },
     *     { "key": "location",         "done": bool, "auto": bool, "manual": bool },
     *     { "key": "test_label",       "done": bool, "auto": bool, "manual": bool },
     *     { "key": "first_receiving",  "done": bool, "auto": bool, "manual": bool }
     *   ],
     *   "allDone": bool
     * }
     */
    @GetMapping("/status")
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    public Map<String, Object> status(@AuthenticationPrincipal CustomUserDetails principal) {
        UUID tenantId = principal.tenantId();

        return TenantContext.runAs(tenantId, () -> tx.execute(s -> {

            // ① Shopify connected
            boolean shopifyAuto = Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM stores WHERE tenant_id = ? AND status = 'connected')",
                Boolean.class, tenantId));

            // ② Bosta connected
            boolean bostaAuto = Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM courier_accounts " +
                "WHERE tenant_id = ? AND provider = 'bosta' AND status = 'active')",
                Boolean.class, tenantId));

            // ③ At least one location exists
            boolean locationAuto = Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM locations WHERE tenant_id = ?)",
                Boolean.class, tenantId));

            // ④ Test label (explicit reprint — closest available signal)
            boolean labelAuto = Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM label_reprints WHERE tenant_id = ?)",
                Boolean.class, tenantId));

            // ⑤ First receiving session finalized
            boolean receivingAuto = Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM receipts " +
                "WHERE tenant_id = ? AND finalized_at IS NOT NULL)",
                Boolean.class, tenantId));

            List<String> manualSteps = onboardingService.readManualSteps(tenantId);

            List<Map<String, Object>> steps = new ArrayList<>();
            steps.add(step("connect_shopify",  shopifyAuto,   manualSteps));
            steps.add(step("connect_bosta",    bostaAuto,     manualSteps));
            steps.add(step("location",         locationAuto,  manualSteps));
            steps.add(step("test_label",       labelAuto,     manualSteps));
            steps.add(step("first_receiving",  receivingAuto, manualSteps));

            boolean allDone = steps.stream().allMatch(st -> Boolean.TRUE.equals(st.get("done")));

            // Dismiss state for the Overview dashboard's condensed onboarding card —
            // the full checklist page (this endpoint's other consumer) ignores it.
            Boolean dismissed = jdbc.queryForObject(
                "SELECT onboarding_dismissed_at IS NOT NULL FROM tenants WHERE id = ?",
                Boolean.class, tenantId);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("steps",     steps);
            result.put("allDone",   allDone);
            result.put("dismissed", dismissed);
            return result;
        }));
    }

    public record SetStepRequest(String step, boolean checked) {}

    /**
     * POST /api/v1/onboarding/steps — manual override for one checklist step.
     * Not a GET — no RlsCoverageTest entry required.
     */
    @PostMapping("/steps")
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void setStep(@RequestBody SetStepRequest req,
                         @AuthenticationPrincipal CustomUserDetails principal) {
        TenantContext.runAs(principal.tenantId(), () ->
            onboardingService.setManualStep(principal.userId(), req.step(), req.checked()));
    }

    /**
     * POST /api/v1/onboarding/dismiss
     *
     * Manual dismiss for the Overview dashboard's condensed onboarding card.
     * Per-tenant (not per-user) — matches allDone's tenant-wide scope above. The card
     * hides on EITHER this being set OR allDone=true; both independently derived on
     * read, no interaction between them.
     */
    @PostMapping("/dismiss")
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void dismiss(@AuthenticationPrincipal CustomUserDetails principal) {
        UUID tenantId = principal.tenantId();
        TenantContext.runAs(tenantId, () -> tx.execute(s -> {
            jdbc.update(
                "UPDATE tenants SET onboarding_dismissed_at = now() WHERE id = ?",
                tenantId);
            return null;
        }));
    }

    private static Map<String, Object> step(String key, boolean auto, List<String> manualSteps) {
        boolean manual = manualSteps.contains(key);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key",    key);
        m.put("done",   auto || manual);
        m.put("auto",   auto);
        m.put("manual", manual);
        return m;
    }
}
