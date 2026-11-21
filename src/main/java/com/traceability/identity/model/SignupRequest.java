package com.traceability.identity.model;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * {@code attribution} is optional, untyped JSON on purpose: a malformed value must never turn
 * a signup into a 400 — {@link SignupAttribution#from} keeps only well-formed string fields.
 *
 * {@code intent} (Bazar B9a) is optional too: "bazar" (web {@code /signup?for=bazar}, or "Sell at a bazar" on the
 * App Store onboarding form) is stored as {@code tenants.onboarding_prefs.intent}; anything else — another string, a
 * number, an object — is ignored, never a 400 (untyped like {@code attribution}; OnboardingProgressService.signupIntent).
 */
public record SignupRequest(String tenantName, String name, String email, String phone, String password,
                            boolean consent, JsonNode attribution, JsonNode intent) {

    public SignupRequest(String tenantName, String name, String email, String phone, String password,
                         boolean consent, JsonNode attribution) {
        this(tenantName, name, email, phone, password, consent, attribution, null);
    }

    public SignupRequest(String tenantName, String name, String email, String phone, String password,
                         boolean consent) {
        this(tenantName, name, email, phone, password, consent, null, null);
    }
}
