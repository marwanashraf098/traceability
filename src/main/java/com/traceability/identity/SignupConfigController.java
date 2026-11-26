package com.traceability.identity;

import com.traceability.bazar.BazarSignup;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Wizard entry — GET /api/v1/public/signup-config, unauthenticated: what the web signup page may offer.
 * {@code bazarSignup} = BAZAR_SIGNUP_ENABLED (traced.bazar.signup-enabled): the "I'm here for the bazar" link
 * (→ /signup?for=bazar) shows only when it is on. A server setting only — no DB read, no tenant, nothing else.
 */
@RestController
public class SignupConfigController {

    private final BazarSignup bazarSignup;

    public SignupConfigController(BazarSignup bazarSignup) {
        this.bazarSignup = bazarSignup;
    }

    @GetMapping("/api/v1/public/signup-config")
    public Map<String, Object> config() {
        return Map.of("bazarSignup", bazarSignup.enabled());
    }
}
