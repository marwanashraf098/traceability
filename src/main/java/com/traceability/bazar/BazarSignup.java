package com.traceability.bazar;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Bazar B9a — the server setting {@code BAZAR_SIGNUP_ENABLED} ({@code traced.bazar.signup-enabled}, read once at
 * startup, default false). Since the wizard entry (2026-11-26) it only decides whether the web signup page offers
 * "I'm here for the bazar" (public GET /api/v1/public/signup-config); it no longer gates the setup wizard
 * ({@link BazarSetupService#on} = bazar on) and, since V244, never switches {@code tenants.bazar_enabled} on.
 */
@Component
public class BazarSignup {

    private final boolean enabled;

    public BazarSignup(@Value("${traced.bazar.signup-enabled:false}") boolean enabled) {
        this.enabled = enabled;
    }

    public boolean enabled() { return enabled; }
}
