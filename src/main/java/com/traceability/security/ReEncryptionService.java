package com.traceability.security;

import com.traceability.integrations.bosta.BostaKeyHealth;
import com.traceability.portal.RefundDetailsCipher;
import com.traceability.tenancy.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.flyway.FlywayDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/**
 * Encryption key rotation K2b (2026-11-28) — ONE-OFF re-encryption of the stored legacy (v1, unprefixed) values to v2,
 * run by hand through the ops endpoint after K2a (APP_ENCRYPTION_WRITE_VERSION=v2) is live.
 *
 * Columns: stores.access_token_encrypted / refresh_token_encrypted / client_id_encrypted / api_secret_encrypted,
 * courier_accounts.api_key_encrypted, return_requests.refund_details_encrypted (AAD tenant|request through
 * {@link RefundDetailsCipher}, from the row's own ids). shopify_pending_links is NOT touched — its rows expire in 15 min.
 *
 * How: the tenant ids are read on the owner pool (ids only, like RefundDetailsPurgeJob); everything else runs per tenant
 * in {@link TenantContext#runAs} on the app pool (app_user + RLS in prod) — no new SECURITY DEFINER hatch. Per tenant:
 * one read transaction, then one short transaction per row. Per value: skip when already "v2:"; decrypt legacy →
 * encrypt (v2) → decrypt the new value and compare with the original; any failure aborts that ROW (all its legacy values
 * count as failed). Write = compare-and-set: {@code UPDATE … WHERE id = ? AND tenant_id = ? AND <every column read> IS
 * NOT DISTINCT FROM <what was read>}; all of a stores row's columns in ONE UPDATE. 0 rows = skipped_cas (the app
 * rewrote the row meanwhile — it writes v2 itself after K2a), never an error. courier_accounts: the same UPDATE carries
 * key_auth_fail_key_hash over to the new ciphertext ({@link BostaKeyHealth#ciphertextHashSql}), so a known-bad Bosta key
 * stays known bad and a healthy one stays healthy. Idempotent: a second run finds only v2 values.
 *
 * Refuses to apply unless the write version is v2 (EncryptionService refuses to start writing v2 without a V2 key, so
 * v2 means the key is configured; every new value is also checked to start with "v2:" before it is written).
 * Logs counts only — {@code REENCRYPT table= column= migrated= skipped_cas= already_v2= failed=} — and for an error the
 * exception class only; never a plaintext, a ciphertext or a key.
 */
@Service
public class ReEncryptionService {

    private static final Logger log = LoggerFactory.getLogger(ReEncryptionService.class);

    /** The re-encrypted columns, in report order. */
    public enum Col {
        STORES_ACCESS_TOKEN("stores", "access_token_encrypted"),
        STORES_REFRESH_TOKEN("stores", "refresh_token_encrypted"),
        STORES_CLIENT_ID("stores", "client_id_encrypted"),
        STORES_API_SECRET("stores", "api_secret_encrypted"),
        COURIER_API_KEY("courier_accounts", "api_key_encrypted"),
        REFUND_DETAILS("return_requests", "refund_details_encrypted");

        public final String table, column;
        Col(String table, String column) { this.table = table; this.column = column; }
    }

    private static final List<Col> STORE_COLS =
        List.of(Col.STORES_ACCESS_TOKEN, Col.STORES_REFRESH_TOKEN, Col.STORES_CLIENT_ID, Col.STORES_API_SECRET);

    /** Dry-run / verification counts of one column. */
    public record ColumnCounts(String table, String column, long legacy, long v2) {}

    /** What one apply did to one column. */
    public record ColumnResult(String table, String column, int migrated, int skippedCas, int alreadyV2, int failed) {}

    /** Thrown when apply is refused (write version not v2, or a run already in progress). */
    public static class RefusedException extends RuntimeException {
        public RefusedException(String message) { super(message); }
    }

    private final EncryptionService encryption;
    private final RefundDetailsCipher refundCipher;
    private final JdbcTemplate jdbc;
    private final JdbcTemplate ownerJdbc;
    private final TransactionTemplate tx;
    private final AtomicBoolean running = new AtomicBoolean();

    /** Tests only: runs between a row's read and its compare-and-set write (table, row id). */
    volatile java.util.function.BiConsumer<String, UUID> beforeWriteHook;

    public ReEncryptionService(EncryptionService encryption, RefundDetailsCipher refundCipher, JdbcTemplate jdbc,
                               @FlywayDataSource DataSource ownerDs, PlatformTransactionManager txm) {
        this.encryption = encryption;
        this.refundCipher = refundCipher;
        this.jdbc = jdbc;
        this.ownerJdbc = new JdbcTemplate(ownerDs);
        this.tx = new TransactionTemplate(txm);
    }

    // ── counts ────────────────────────────────────────────────────────────────────────────────

    /** Legacy and v2 count per column, all tenants (each tenant counted under its own RLS context). Changes nothing. */
    public List<ColumnCounts> counts() {
        long[][] sum = new long[Col.values().length][2];
        for (UUID tenantId : tenantIds()) {
            List<ColumnCounts> c = countsForTenant(tenantId);
            for (int i = 0; i < c.size(); i++) {
                sum[i][0] += c.get(i).legacy();
                sum[i][1] += c.get(i).v2();
            }
        }
        List<ColumnCounts> out = new ArrayList<>();
        for (Col col : Col.values()) out.add(new ColumnCounts(col.table, col.column, sum[col.ordinal()][0], sum[col.ordinal()][1]));
        return out;
    }

    public List<ColumnCounts> countsForTenant(UUID tenantId) {
        return TenantContext.runAs(tenantId, () -> tx.execute(s -> {
            List<ColumnCounts> out = new ArrayList<>();
            for (Col col : Col.values()) {
                Map<String, Object> r = jdbc.queryForMap(
                    "SELECT count(*) FILTER (WHERE " + col.column + " NOT LIKE 'v2:%') AS legacy, " +
                    "       count(*) FILTER (WHERE " + col.column + " LIKE 'v2:%')     AS v2 " +
                    "FROM " + col.table + " WHERE tenant_id = ? AND " + col.column + " IS NOT NULL", tenantId);
                out.add(new ColumnCounts(col.table, col.column,
                    ((Number) r.get("legacy")).longValue(), ((Number) r.get("v2")).longValue()));
            }
            return out;
        }));
    }

    // ── apply ─────────────────────────────────────────────────────────────────────────────────

    /** Re-encrypts every tenant's legacy values. Refused unless the write version is v2. */
    public List<ColumnResult> apply() {
        requireV2();
        if (!running.compareAndSet(false, true)) throw new RefusedException("A re-encryption run is already in progress");
        try {
            Tally[] total = Tally.fresh();
            for (UUID tenantId : tenantIds()) {
                Tally[] t = migrate(tenantId);
                for (int i = 0; i < total.length; i++) total[i].add(t[i]);
            }
            List<ColumnResult> out = results(total);
            for (ColumnResult r : out) {
                log.info("REENCRYPT table={} column={} migrated={} skipped_cas={} already_v2={} failed={}",
                    r.table(), r.column(), r.migrated(), r.skippedCas(), r.alreadyV2(), r.failed());
            }
            return out;
        } finally {
            running.set(false);
        }
    }

    /** One tenant only (tests; the same code {@link #apply} runs per tenant). Refused unless the write version is v2. */
    public List<ColumnResult> applyTenant(UUID tenantId) {
        requireV2();
        return results(migrate(tenantId));
    }

    private void requireV2() {
        if (encryption.writeVersion() != EncryptionService.WriteVersion.V2) {
            throw new RefusedException("Re-encryption needs APP_ENCRYPTION_WRITE_VERSION=v2 (and APP_ENCRYPTION_KEY_V2)");
        }
    }

    private List<UUID> tenantIds() {
        return ownerJdbc.queryForList("SELECT id FROM tenants ORDER BY id", UUID.class);
    }

    private Tally[] migrate(UUID tenantId) {
        Tally[] t = Tally.fresh();
        try {
            TenantContext.runAs(tenantId, (Runnable) () -> {
                migrateStores(tenantId, t);
                migrateCourierAccounts(tenantId, t);
                migrateRefundDetails(tenantId, t);
            });
        } catch (RuntimeException e) {
            log.warn("REENCRYPT tenant={} stopped: {}", tenantId, e.getClass().getSimpleName());
        }
        return t;
    }

    // stores — all four columns of a row in ONE compare-and-set UPDATE.
    private void migrateStores(UUID tenantId, Tally[] t) {
        List<Map<String, Object>> rows = tx.execute(s -> jdbc.queryForList(
            "SELECT id, access_token_encrypted, refresh_token_encrypted, client_id_encrypted, api_secret_encrypted " +
            "FROM stores WHERE tenant_id = ? AND (access_token_encrypted IS NOT NULL OR refresh_token_encrypted IS NOT NULL " +
            "  OR client_id_encrypted IS NOT NULL OR api_secret_encrypted IS NOT NULL) ORDER BY id", tenantId));
        for (Map<String, Object> row : rows) {
            UUID id = (UUID) row.get("id");
            Map<Col, String> fresh = new EnumMap<>(Col.class);
            boolean rowFailed = false;
            for (Col col : STORE_COLS) {
                String old = (String) row.get(col.column);
                if (old == null) continue;
                if (old.startsWith(EncryptionService.V2_PREFIX)) { t[col.ordinal()].alreadyV2++; continue; }
                String nw = reseal(old, encryption::decrypt, encryption::encrypt);
                if (nw == null) { rowFailed = true; break; }
                fresh.put(col, nw);
            }
            if (rowFailed) {
                for (Col col : STORE_COLS) if (isLegacy((String) row.get(col.column))) t[col.ordinal()].failed++;
                log.warn("REENCRYPT table=stores row skipped: verification failed");
                continue;
            }
            if (fresh.isEmpty()) continue;

            StringBuilder sql = new StringBuilder("UPDATE stores SET ");
            List<Object> args = new ArrayList<>();
            String sep = "";
            for (Map.Entry<Col, String> e : fresh.entrySet()) {
                sql.append(sep).append(e.getKey().column).append(" = ?");
                args.add(e.getValue());
                sep = ", ";
            }
            sql.append(" WHERE id = ? AND tenant_id = ?");
            args.add(id);
            args.add(tenantId);
            for (Col col : STORE_COLS) {
                sql.append(" AND ").append(col.column).append(" IS NOT DISTINCT FROM ?::text");
                args.add(row.get(col.column));
            }
            int n = write("stores", id, sql.toString(), args.toArray());
            for (Col col : fresh.keySet()) {
                if (n == 1) t[col.ordinal()].migrated++; else t[col.ordinal()].skippedCas++;
            }
        }
    }

    // courier_accounts — the key-health hash moves with the ciphertext (known bad stays known bad).
    private void migrateCourierAccounts(UUID tenantId, Tally[] t) {
        Tally tally = t[Col.COURIER_API_KEY.ordinal()];
        List<Map<String, Object>> rows = tx.execute(s -> jdbc.queryForList(
            "SELECT id, api_key_encrypted FROM courier_accounts WHERE tenant_id = ? AND api_key_encrypted IS NOT NULL " +
            "ORDER BY id", tenantId));
        for (Map<String, Object> row : rows) {
            UUID id = (UUID) row.get("id");
            String old = (String) row.get("api_key_encrypted");
            if (old.startsWith(EncryptionService.V2_PREFIX)) { tally.alreadyV2++; continue; }
            String nw = reseal(old, encryption::decrypt, encryption::encrypt);
            if (nw == null) {
                tally.failed++;
                log.warn("REENCRYPT table=courier_accounts row skipped: verification failed");
                continue;
            }
            int n = write("courier_accounts", id,
                "UPDATE courier_accounts SET api_key_encrypted = ?, " +
                "  key_auth_fail_key_hash = CASE WHEN key_auth_fail_key_hash = " + BostaKeyHealth.ciphertextHashSql("?::text") +
                "                                THEN " + BostaKeyHealth.ciphertextHashSql("?::text") +
                "                                ELSE key_auth_fail_key_hash END " +
                "WHERE id = ? AND tenant_id = ? AND api_key_encrypted = ?",
                nw, old, nw, id, tenantId, old);
            if (n == 1) tally.migrated++; else tally.skippedCas++;
        }
    }

    // return_requests — AAD = tenant|request, from the row's own ids.
    private void migrateRefundDetails(UUID tenantId, Tally[] t) {
        Tally tally = t[Col.REFUND_DETAILS.ordinal()];
        List<Map<String, Object>> rows = tx.execute(s -> jdbc.queryForList(
            "SELECT id, tenant_id, refund_details_encrypted FROM return_requests " +
            "WHERE tenant_id = ? AND refund_details_encrypted IS NOT NULL ORDER BY id", tenantId));
        for (Map<String, Object> row : rows) {
            UUID id = (UUID) row.get("id");
            UUID rowTenant = (UUID) row.get("tenant_id");
            String old = (String) row.get("refund_details_encrypted");
            if (old.startsWith(EncryptionService.V2_PREFIX)) { tally.alreadyV2++; continue; }
            String nw = reseal(old, v -> refundCipher.decrypt(rowTenant, id, v), p -> refundCipher.encrypt(rowTenant, id, p));
            if (nw == null) {
                tally.failed++;
                log.warn("REENCRYPT table=return_requests row skipped: verification failed");
                continue;
            }
            int n = write("return_requests", id,
                "UPDATE return_requests SET refund_details_encrypted = ? " +
                "WHERE id = ? AND tenant_id = ? AND refund_details_encrypted = ?",
                nw, id, tenantId, old);
            if (n == 1) tally.migrated++; else tally.skippedCas++;
        }
    }

    /** Legacy → plaintext → v2 → verified. Null when anything fails (the caller skips the row; class logged only). */
    private static String reseal(String old, Function<String, String> open, Function<String, String> seal) {
        try {
            String plain = open.apply(old);
            String nw = seal.apply(plain);
            if (!nw.startsWith(EncryptionService.V2_PREFIX) || !plain.equals(open.apply(nw))) return null;
            return nw;
        } catch (RuntimeException e) {
            log.warn("REENCRYPT value failed: {}", e.getClass().getSimpleName());
            return null;
        }
    }

    private int write(String table, UUID id, String sql, Object... args) {
        java.util.function.BiConsumer<String, UUID> hook = beforeWriteHook;
        if (hook != null) hook.accept(table, id);
        Integer n = tx.execute(s -> jdbc.update(sql, args));
        return n == null ? 0 : n;
    }

    private static boolean isLegacy(String v) {
        return v != null && !v.startsWith(EncryptionService.V2_PREFIX);
    }

    private static List<ColumnResult> results(Tally[] t) {
        List<ColumnResult> out = new ArrayList<>();
        for (Col col : Col.values()) {
            Tally x = t[col.ordinal()];
            out.add(new ColumnResult(col.table, col.column, x.migrated, x.skippedCas, x.alreadyV2, x.failed));
        }
        return out;
    }

    private static final class Tally {
        int migrated, skippedCas, alreadyV2, failed;

        static Tally[] fresh() {
            Tally[] t = new Tally[Col.values().length];
            for (int i = 0; i < t.length; i++) t[i] = new Tally();
            return t;
        }

        void add(Tally o) {
            migrated += o.migrated; skippedCas += o.skippedCas; alreadyV2 += o.alreadyV2; failed += o.failed;
        }
    }
}
