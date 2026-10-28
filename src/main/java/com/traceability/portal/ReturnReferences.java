package com.traceability.portal;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

/**
 * The return request reference — {@code RR-} + 6 characters from an unambiguous alphabet (no 0/O/1/I), unused within
 * the tenant (V102's format CHECK). ONE generator for every way a request is created: the returns portal
 * (PortalService) and Lookup's Exchange (LookupExchangeService, C2). Runs on the caller's JdbcTemplate.
 */
public final class ReturnReferences {

    private static final String REFERENCE_ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ"; // no 0/O/1/I
    private static final int REFERENCE_LENGTH = 6;
    private static final java.security.SecureRandom RANDOM = new java.security.SecureRandom();

    private ReturnReferences() {}

    public static String newReference(JdbcTemplate jdbc, UUID tenantId) {
        for (int attempt = 0; attempt < 10; attempt++) {
            StringBuilder sb = new StringBuilder("RR-");
            for (int i = 0; i < REFERENCE_LENGTH; i++) {
                sb.append(REFERENCE_ALPHABET.charAt(RANDOM.nextInt(REFERENCE_ALPHABET.length())));
            }
            String ref = sb.toString();
            Boolean taken = jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM return_requests WHERE tenant_id = ? AND reference = ?)",
                Boolean.class, tenantId, ref);
            if (!Boolean.TRUE.equals(taken)) return ref;
        }
        throw new IllegalStateException("Could not generate a unique return reference");
    }
}
