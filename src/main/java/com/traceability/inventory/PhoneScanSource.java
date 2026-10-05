package com.traceability.inventory;

import com.traceability.tenancy.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

/**
 * Q1b — whether a scan came from the caller's paired phone, decided server-side.
 *
 * A scan endpoint may receive an optional {@code relayEventId} (the tablet's PhoneScanProvider
 * passes the relay event a phone scan arrived as). The scan counts as 'phone' ONLY when that
 * relay event exists in the caller's tenant, belongs to a pairing held by the caller that is
 * still live (not revoked, not past its 12 h expiry), and carries the same code that was
 * scanned (whitespace ignored). Anything else — no id, an unknown id, another worker's or
 * another tenant's pairing (invisible under RLS anyway), a revoked / expired pairing, a code
 * mismatch — is a normal 'hardware' scan: never rejected, logged at INFO. A client-sent
 * "source" is never read.
 *
 * Runs under app_user + RLS (scan_relay_events SELECT; the non-credential scan_pairings
 * columns, V127 / V140 grants) inside a transaction — the tenant GUC is a SET LOCAL applied at
 * transaction start (TenantAwareConnection); it joins the caller's transaction when there is one.
 */
@Component
public class PhoneScanSource {

    private static final Logger log = LoggerFactory.getLogger(PhoneScanSource.class);

    private final JdbcTemplate        jdbc;
    private final TransactionTemplate tx;

    public PhoneScanSource(JdbcTemplate jdbc, PlatformTransactionManager txm) {
        this.jdbc = jdbc;
        this.tx   = new TransactionTemplate(txm);
        this.tx.setReadOnly(true);
    }

    /** True only for a verified phone scan (see the class doc). */
    public boolean isPhone(UUID relayEventId, String scannedCode, UUID userId) {
        if (relayEventId == null) return false;
        UUID tenantId = TenantContext.require();
        String code = normalize(scannedCode);
        boolean verified = !code.isEmpty() && Boolean.TRUE.equals(tx.execute(s -> jdbc.queryForObject(
            "SELECT EXISTS (" +
            "  SELECT 1 FROM scan_relay_events e " +
            "  JOIN scan_pairings p ON p.id = e.pairing_id AND p.tenant_id = e.tenant_id " +
            "  WHERE e.id = ? AND e.tenant_id = ? " +
            "    AND p.station_user_id = ? AND p.revoked_at IS NULL AND now() < p.expires_at " +
            "    AND regexp_replace(e.code, '\\s', '', 'g') = ?)",
            Boolean.class, relayEventId, tenantId, userId, code)));
        if (!verified) {
            log.info("Scan with relayEventId {} not verified as a phone scan (user {}): recorded as hardware",
                relayEventId, userId);
        }
        return verified;
    }

    static String normalize(String code) {
        return code == null ? "" : code.replaceAll("\\s+", "");
    }
}
