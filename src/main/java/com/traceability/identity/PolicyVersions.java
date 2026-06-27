package com.traceability.identity;

/**
 * Single source of truth for the current published policy versions.
 * Bump these when a new privacy policy or terms of service goes live.
 * Values must match the Version line in docs/legal/privacy-policy.md
 * and docs/legal/terms-of-service.md.
 */
public final class PolicyVersions {
    private PolicyVersions() {}

    public static final String PRIVACY = "1.0";
    public static final String TERMS   = "1.0";
}
