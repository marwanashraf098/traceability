package com.traceability.integrations.bosta;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

/**
 * Bosta key health (V229, 2026-11-15) — THE rule for "Bosta refuses this tenant's API key", and the ONE writer of the
 * courier_accounts.key_auth_* columns.
 *
 * <ul>
 *   <li>{@link #recordAuthFailure}: a 401 from Bosta with the tenant's current key. After
 *       {@code bosta.key-health.bad-after-failures} (3) of them in a row, spread over at least
 *       {@code bosta.key-health.bad-after-minutes} (10), the key is KNOWN BAD. A 403 is not counted (it can mean one
 *       endpoint the key may not use, not a revoked key).</li>
 *   <li>{@link #recordOk}: any Bosta success with the key clears everything.</li>
 *   <li>Known bad: the discovery poll skips the tenant, the status poll probes it with one walk page every
 *       {@code bosta.key-health.probe-hours} (6), and a Bosta webhook for it is stored and marked 'skipped' with no
 *       Bosta call. It ends by itself when the merchant saves a new key (the hash no longer matches) or a probe
 *       succeeds. While it lasts, the {@code bosta_key_rejected} alert (HIGH, condition-only) is open — one per tenant.</li>
 * </ul>
 * Every method runs under the caller's tenant context (app_user + RLS); the cross-tenant poll lists use
 * {@link #knownBadSql} on the owner pool. Never throws — key bookkeeping must not fail a webhook or a poll.
 */
@Component
public class BostaKeyHealth {

    private static final Logger log = LoggerFactory.getLogger(BostaKeyHealth.class);

    /** The reason a webhook event is stored as 'skipped' while its tenant's key is known bad. */
    public static final String SKIP_REASON =
        "skipped: bosta_key_rejected — Bosta refuses this tenant's API key (401); not fetched until the key is updated";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final int badAfterFailures;
    private final int badAfterMinutes;
    private final int probeHours;

    public BostaKeyHealth(JdbcTemplate jdbc, PlatformTransactionManager txm,
                          @Value("${bosta.key-health.bad-after-failures:3}") int badAfterFailures,
                          @Value("${bosta.key-health.bad-after-minutes:10}") int badAfterMinutes,
                          @Value("${bosta.key-health.probe-hours:6}") int probeHours) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txm);
        this.badAfterFailures = Math.max(1, badAfterFailures);
        this.badAfterMinutes = Math.max(0, badAfterMinutes);
        this.probeHours = Math.max(1, probeHours);
    }

    /** sha256 of the stored ciphertext of courier_accounts row {@code ca}. */
    static String keyHashSql(String ca) {
        return ciphertextHashSql(ca + ".api_key_encrypted");
    }

    /**
     * THE key hash, of any SQL text expression (a column or a bind parameter {@code ?}). The K2
     * re-encryption (security.ReEncryptionService) uses it to carry key_auth_fail_key_hash over to
     * the new ciphertext, so a known-bad key stays known bad.
     */
    public static String ciphertextHashSql(String textExpr) {
        return "encode(sha256(convert_to(" + textExpr + ", 'UTF8')), 'hex')";
    }

    /** THE known-bad predicate for courier_accounts row {@code ca} (true only for the key that failed). */
    public static String knownBadSql(String ca) {
        return "(" + ca + ".key_auth_bad_at IS NOT NULL AND " + ca + ".key_auth_fail_key_hash = " + keyHashSql(ca) + ")";
    }

    /** Known bad and due for the status poll's probe (one walk page every probe-hours). */
    public String probeDueSql(String ca) {
        return "(" + ca + ".key_auth_probe_at IS NULL OR " + ca + ".key_auth_probe_at < now() - make_interval(hours => " +
            probeHours + "))";
    }

    /** Is this tenant's current Bosta key known bad? Under the tenant's context. False when it can't tell. */
    public boolean knownBad(UUID tenantId) {
        try {
            return Boolean.TRUE.equals(tx.execute(s -> jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM courier_accounts ca WHERE ca.tenant_id = ? AND ca.provider = 'bosta' " +
                "  AND ca.status = 'active' AND " + knownBadSql("ca") + ")", Boolean.class, tenantId)));
        } catch (RuntimeException e) {
            log.warn("Bosta key health: check failed for tenant {}: {}", tenantId, e.toString());
            return false;
        }
    }

    /** Bosta answered {@code httpStatus} to a call with the tenant's key. Only a 401 counts. */
    public void recordAuthFailure(UUID tenantId, int httpStatus) {
        if (httpStatus != 401) return;
        try {
            Boolean becameBad = tx.execute(s -> {
                String h = keyHashSql("ca");
                java.util.List<Boolean> r = jdbc.queryForList(
                    "UPDATE courier_accounts ca SET " +
                    "  key_auth_fail_count = CASE WHEN ca.key_auth_fail_key_hash IS DISTINCT FROM " + h + " THEN 1 " +
                    "                             ELSE ca.key_auth_fail_count + 1 END, " +
                    "  key_auth_fail_first_at = CASE WHEN ca.key_auth_fail_key_hash IS DISTINCT FROM " + h +
                    "                                  OR ca.key_auth_fail_first_at IS NULL THEN now() " +
                    "                                ELSE ca.key_auth_fail_first_at END, " +
                    "  key_auth_bad_at = CASE " +
                    "      WHEN ca.key_auth_fail_key_hash IS DISTINCT FROM " + h + " THEN " +
                    "           CASE WHEN ? <= 1 THEN now() ELSE NULL END " +
                    "      WHEN ca.key_auth_bad_at IS NOT NULL THEN ca.key_auth_bad_at " +
                    "      WHEN ca.key_auth_fail_count + 1 >= ? " +
                    "           AND ca.key_auth_fail_first_at <= now() - make_interval(mins => ?) THEN now() " +
                    "      ELSE NULL END, " +
                    "  key_auth_probe_at = CASE WHEN ca.key_auth_fail_key_hash IS DISTINCT FROM " + h + " THEN NULL " +
                    "                           ELSE ca.key_auth_probe_at END, " +
                    "  key_auth_fail_key_hash = " + h + " " +
                    "WHERE ca.tenant_id = ? AND ca.provider = 'bosta' AND ca.status = 'active' " +
                    "RETURNING ca.key_auth_bad_at IS NOT NULL AND ca.key_auth_bad_at = now()",
                    Boolean.class, badAfterFailures, badAfterFailures, badAfterMinutes, tenantId);
                boolean bad = !r.isEmpty() && Boolean.TRUE.equals(r.get(0));
                // The first probe comes probe-hours after the key went bad, not on the next status cycle.
                if (bad) jdbc.update("UPDATE courier_accounts SET key_auth_probe_at = now() " +
                    "WHERE tenant_id = ? AND provider = 'bosta' AND key_auth_probe_at IS NULL", tenantId);
                return bad;
            });
            if (Boolean.TRUE.equals(becameBad)) {
                log.warn("Bosta key health: tenant {} — Bosta refuses the API key ({} × 401 over >= {} min); its Bosta " +
                    "webhooks are stored without a fetch and polling is paused until the key is updated (probe every {} h)",
                    tenantId, badAfterFailures, badAfterMinutes, probeHours);
            }
        } catch (RuntimeException e) {
            log.warn("Bosta key health: could not record a 401 for tenant {}: {}", tenantId, e.toString());
        }
    }

    /** A Bosta call with the tenant's key succeeded: clears any failure count and the known-bad state. */
    public void recordOk(UUID tenantId) {
        try {
            Integer n = tx.execute(s -> jdbc.update(
                "UPDATE courier_accounts SET key_auth_fail_count = 0, key_auth_fail_first_at = NULL, " +
                "  key_auth_fail_key_hash = NULL, key_auth_bad_at = NULL, key_auth_probe_at = NULL " +
                "WHERE tenant_id = ? AND provider = 'bosta' AND (key_auth_fail_count > 0 OR key_auth_bad_at IS NOT NULL)",
                tenantId));
            if (n != null && n > 0) log.info("Bosta key health: tenant {} — Bosta accepted the key, failure state cleared", tenantId);
        } catch (RuntimeException e) {
            log.warn("Bosta key health: could not clear for tenant {}: {}", tenantId, e.toString());
        }
    }

    /** The status poll is probing this known-bad key now. */
    public void stampProbe(UUID tenantId) {
        try {
            tx.execute(s -> jdbc.update("UPDATE courier_accounts SET key_auth_probe_at = now() " +
                "WHERE tenant_id = ? AND provider = 'bosta'", tenantId));
        } catch (RuntimeException e) {
            log.warn("Bosta key health: could not stamp the probe for tenant {}: {}", tenantId, e.toString());
        }
    }

    /** Convenience: the same as {@link #recordAuthFailure} for an exception, under the current tenant. */
    public void onException(UUID tenantId, Throwable e) {
        if (e instanceof BostaAuthException a) recordAuthFailure(tenantId, a.httpStatus());
    }
}
