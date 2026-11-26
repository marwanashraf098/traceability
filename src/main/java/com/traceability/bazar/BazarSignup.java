package com.traceability.bazar;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Bazar B9a — the server setting {@code BAZAR_SIGNUP_ENABLED} ({@code traced.bazar.signup-enabled}, read once at
 * startup, default false). It gates the bazar signup intent's setup wizard ({@link BazarSetupService#on}, GET /me's
 * {@code bazarSetup}): off → every /bazar-setup endpoint answers 404. It no longer switches
 * {@code tenants.bazar_enabled} on at signup — since V244 every tenant has bazar by default.
 */
@Component
public class BazarSignup {

    private final boolean enabled;

    public BazarSignup(@Value("${traced.bazar.signup-enabled:false}") boolean enabled) {
        this.enabled = enabled;
    }

    public boolean enabled() { return enabled; }
}
