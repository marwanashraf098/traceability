package com.traceability.portal;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Returns portal fees — the ONE answer to "are customer fees on for this tenant?" (F1–F4: the settings screen, the
 * portal quote and catalogue, freezing at submit — which the refund deduction then reads — F3 pricing and the F4
 * COD). On when {@code traced.portal-fees.settings-enabled} is true (every tenant) OR the tenant is listed in
 * {@code traced.portal-fees.tenants} ({@code TRACED_PORTAL_FEES_TENANTS}, comma-separated tenant ids). Otherwise off,
 * exactly as before the rollout list existed. Never ask the flag directly — ask this.
 *
 * <p>An entry that isn't a tenant id stops startup (a typo must not silently leave a store off).
 */
@Component
public class PortalFeesRollout {

    private final boolean everyone;
    private final Set<UUID> tenants;

    public PortalFeesRollout(@Value("${traced.portal-fees.settings-enabled:false}") boolean everyone,
                             @Value("${traced.portal-fees.tenants:}") String tenants) {
        this.everyone = everyone;
        this.tenants = parse(tenants);
    }

    /** Every tenant on (true) or off (false), no list — what a hand-built service gets. */
    public static PortalFeesRollout of(boolean everyone) {
        return new PortalFeesRollout(everyone, "");
    }

    /** On for this tenant. A null tenant is never on unless every tenant is. */
    public boolean on(UUID tenantId) {
        return everyone || (tenantId != null && tenants.contains(tenantId));
    }

    /** On for at least one tenant — false means today's behaviour for everyone, with no per-tenant question asked. */
    public boolean anyOn() {
        return everyone || !tenants.isEmpty();
    }

    static Set<UUID> parse(String raw) {
        if (raw == null || raw.isBlank()) return Set.of();
        Set<UUID> out = new LinkedHashSet<>();
        for (String part : raw.split(",")) {
            String s = part.strip();
            if (s.isEmpty()) continue;
            try {
                if (s.length() != 36) throw new IllegalArgumentException();
                out.add(UUID.fromString(s));
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException(
                    "TRACED_PORTAL_FEES_TENANTS has an entry that isn't a tenant id (entry " + (out.size() + 1) + ")");
            }
        }
        return Collections.unmodifiableSet(out);
    }
}
