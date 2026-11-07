package com.traceability.account;

import com.traceability.identity.CustomUserDetails;
import com.traceability.tenancy.TenantContext;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Onboarding O1 (V220) — the progress model for the Overview "Get started" card. Owner / manager only;
 * a worker gets 403. {@link OnboardingProgressService} derives every step and task; PATCH writes only
 * the whitelisted {@code tenants.onboarding_prefs} keys (anything else → 400, nothing written).
 */
@RestController
@RequestMapping("/api/v1/onboarding")
public class OnboardingProgressController {

    private final OnboardingProgressService svc;

    public OnboardingProgressController(OnboardingProgressService svc) {
        this.svc = svc;
    }

    @GetMapping("/progress")
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    public Map<String, Object> progress(@AuthenticationPrincipal CustomUserDetails principal) {
        return TenantContext.runAs(principal.tenantId(), () -> svc.progress());
    }

    @PatchMapping("/prefs")
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    public Map<String, Object> prefs(@RequestBody(required = false) Map<String, Object> body,
                                     @AuthenticationPrincipal CustomUserDetails principal) {
        return TenantContext.runAs(principal.tenantId(), () -> svc.updatePrefs(principal.userId(), body));
    }
}
