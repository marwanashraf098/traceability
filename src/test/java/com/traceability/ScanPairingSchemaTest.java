package com.traceability;

import com.traceability.tenancy.TenantAwareDataSource;
import com.traceability.tenancy.TenantContext;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * S6 — V127 schema: hatch #15 (resolve_scan_pairing) returns a row only for a live,
 * matching, unexpired pairing on an open pack session; app_user can never read a credential
 * hash; scan_relay_events are tenant-isolated (app_user, with a same-tenant positive control).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ScanPairingSchemaTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("traceability_test")
                    .withUsername("postgres")
                    .withPassword("postgres");

    static { POSTGRES.start(); }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",      POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        r.add("spring.flyway.url",          POSTGRES::getJdbcUrl);
        r.add("spring.flyway.user",         POSTGRES::getUsername);
        r.add("spring.flyway.password",     POSTGRES::getPassword);
    }

    @Autowired JdbcTemplate jdbc;              // postgres (BYPASSRLS) — fixtures only

    private JdbcTemplate rawAppUser;           // app_user, no GUC
    private JdbcTemplate appUser;              // app_user, GUC from TenantContext
    private TransactionTemplate appUserTx;

    @BeforeAll
    void setup() {
        DriverManagerDataSource raw = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "app_user", "testpw");
        rawAppUser = new JdbcTemplate(raw);
        TenantAwareDataSource tenantDs = new TenantAwareDataSource(raw);
        appUser = new JdbcTemplate(tenantDs);
        appUserTx = new TransactionTemplate(new DataSourceTransactionManager(tenantDs));
    }

    // ── fixtures ─────────────────────────────────────────────────────────────

    record Station(UUID tenant, UUID user, UUID session) {}

    private Station station(String name) {
        PackFixtures f = new PackFixtures(jdbc, name);
        UUID user = f.user("Ahmed", "worker");
        UUID session = jdbc.queryForObject(
            "INSERT INTO pack_sessions (tenant_id, user_id, mode) VALUES (?, ?, 'waybill_scan') RETURNING id",
            UUID.class, f.tenant, user);
        return new Station(f.tenant, user, session);
    }

    private static String h(String s) { return "hash-" + s + "-" + UUID.randomUUID(); }

    /** A pairing as the service creates it: pair code valid 2 min, pairing valid 12 h. */
    private UUID pairing(Station s, String pairHash) {
        return jdbc.queryForObject(
            "INSERT INTO scan_pairings (tenant_id, pack_session_id, station_user_id, pair_code_hash, " +
            "                           pair_code_expires_at, expires_at) " +
            "VALUES (?, ?, ?, ?, now() + interval '2 minutes', now() + interval '12 hours') RETURNING id",
            UUID.class, s.tenant, s.session, s.user, pairHash);
    }

    private void claim(UUID pairing, String secretHash) {
        jdbc.update("UPDATE scan_pairings SET claimed_at = now(), device_secret_hash = ? WHERE id = ?", secretHash, pairing);
    }

    private List<Map<String, Object>> hatch(String kind, String hash) {
        return rawAppUser.queryForList("SELECT tenant_id, pairing_id FROM resolve_scan_pairing(?, ?)", kind, hash);
    }

    // ── hatch #15 ────────────────────────────────────────────────────────────

    @Test
    void hatch_pairCode_onlyWhileUnclaimed_unexpired_unrevoked_onAnOpenSession() {
        Station s = station("Hatch PC");
        String pc = h("pc");
        UUID p = pairing(s, pc);

        assertThat(hatch("pair_code", pc)).singleElement().satisfies(r -> {
            assertThat(r.get("tenant_id")).isEqualTo(s.tenant);
            assertThat(r.get("pairing_id")).isEqualTo(p);
        });
        assertThat(hatch("pair_code", pc + "x")).as("wrong hash").isEmpty();
        assertThat(hatch("device_secret", pc)).as("pair code is not a device secret").isEmpty();
        assertThat(hatch("other", pc)).as("unknown kind").isEmpty();
        assertThat(hatch("pair_code", null)).as("null hash").isEmpty();

        claim(p, h("ds"));
        assertThat(hatch("pair_code", pc)).as("claimed — the pair code can't be used twice").isEmpty();

        String pc2 = h("pc2");
        Station s2 = station("Hatch PC2");
        UUID p2 = pairing(s2, pc2);
        jdbc.update("UPDATE scan_pairings SET pair_code_expires_at = now() - interval '1 second' WHERE id = ?", p2);
        assertThat(hatch("pair_code", pc2)).as("pair code expired").isEmpty();

        Station s3 = station("Hatch PC3");
        String pc3 = h("pc3");
        UUID p3 = pairing(s3, pc3);
        jdbc.update("UPDATE scan_pairings SET revoked_at = now(), revoked_reason = 'unpaired' WHERE id = ?", p3);
        assertThat(hatch("pair_code", pc3)).as("revoked").isEmpty();

        Station s4 = station("Hatch PC4");
        String pc4 = h("pc4");
        pairing(s4, pc4);
        jdbc.update("UPDATE pack_sessions SET status = 'ended', ended_at = now() WHERE id = ?", s4.session);
        assertThat(hatch("pair_code", pc4)).as("pack session ended").isEmpty();
    }

    @Test
    void hatch_deviceSecret_onlyOnceClaimed_unexpired_unrevoked_onAnOpenSession() {
        Station s = station("Hatch DS");
        String pc = h("pc"), ds = h("ds");
        UUID p = pairing(s, pc);
        assertThat(hatch("device_secret", ds)).as("not claimed yet").isEmpty();

        claim(p, ds);
        assertThat(hatch("device_secret", ds)).singleElement()
            .satisfies(r -> assertThat(r.get("pairing_id")).isEqualTo(p));
        assertThat(hatch("device_secret", ds + "x")).as("wrong hash").isEmpty();

        jdbc.update("UPDATE scan_pairings SET expires_at = now() - interval '1 second' WHERE id = ?", p);
        assertThat(hatch("device_secret", ds)).as("12 h expiry passed").isEmpty();
        jdbc.update("UPDATE scan_pairings SET expires_at = now() + interval '1 hour' WHERE id = ?", p);
        assertThat(hatch("device_secret", ds)).isNotEmpty();

        jdbc.update("UPDATE pack_sessions SET status = 'ended', ended_at = now() WHERE id = ?", s.session);
        assertThat(hatch("device_secret", ds)).as("pack session ended").isEmpty();
        jdbc.update("UPDATE pack_sessions SET status = 'open', ended_at = NULL WHERE id = ?", s.session);
        assertThat(hatch("device_secret", ds)).isNotEmpty();

        jdbc.update("UPDATE scan_pairings SET revoked_at = now(), revoked_reason = 'worker_switched' WHERE id = ?", p);
        assertThat(hatch("device_secret", ds)).as("revoked").isEmpty();
    }

    @Test
    void onePhonePerStation_secondUnrevokedPairingOnASession_isRefused() {
        Station s = station("One phone");
        UUID p = pairing(s, h("a"));
        assertThatThrownBy(() -> pairing(s, h("b"))).isInstanceOf(DuplicateKeyException.class);
        jdbc.update("UPDATE scan_pairings SET revoked_at = now(), revoked_reason = 'replaced' WHERE id = ?", p);
        assertThat(pairing(s, h("c"))).isNotNull();
    }

    // ── app_user can't read credentials ──────────────────────────────────────

    @Test
    void appUser_cannotSelectScanPairings_norEitherHash_evenInItsOwnTenant() {
        Station s = station("Creds");
        String pc = h("pc"), ds = h("ds");
        UUID p = pairing(s, pc);
        claim(p, ds);

        assertThat(rawAppUser.queryForObject(
            "SELECT has_table_privilege('app_user', 'scan_pairings', 'SELECT')", Boolean.class)).isFalse();
        assertThat(rawAppUser.queryForObject(
            "SELECT has_table_privilege('app_user', 'scan_pairings', 'DELETE')", Boolean.class)).isFalse();
        for (String col : List.of("pair_code_hash", "device_secret_hash")) {
            assertThat(rawAppUser.queryForObject(
                "SELECT has_column_privilege('app_user', 'scan_pairings', ?, 'SELECT')", Boolean.class, col))
                .as("SELECT on " + col).isFalse();
        }

        TenantContext.runAs(s.tenant, () -> appUserTx.executeWithoutResult(t -> {
            assertThatThrownBy(() -> appUser.queryForList("SELECT * FROM scan_pairings"))
                .isInstanceOf(DataAccessException.class).rootCause().hasMessageContaining("permission denied");
        }));
        TenantContext.runAs(s.tenant, () -> appUserTx.executeWithoutResult(t -> {
            assertThatThrownBy(() -> appUser.queryForList("SELECT device_secret_hash FROM scan_pairings WHERE id = ?", p))
                .isInstanceOf(DataAccessException.class).rootCause().hasMessageContaining("permission denied");
        }));
        TenantContext.runAs(s.tenant, () -> appUserTx.executeWithoutResult(t -> {
            assertThatThrownBy(() -> appUser.update("UPDATE scan_pairings SET pair_code_hash = 'x' WHERE id = ?", p))
                .isInstanceOf(DataAccessException.class).rootCause().hasMessageContaining("permission denied");
        }));
        TenantContext.runAs(s.tenant, () -> appUserTx.executeWithoutResult(t -> {
            assertThatThrownBy(() -> appUser.update("DELETE FROM scan_pairings WHERE id = ?", p))
                .isInstanceOf(DataAccessException.class).rootCause().hasMessageContaining("permission denied");
        }));
        // What the service does read: the non-credential columns, own tenant only.
        Station other = station("Creds other");
        UUID otherPairing = pairing(other, h("o"));
        List<UUID> seen = TenantContext.runAs(s.tenant, () -> appUserTx.execute(t ->
            appUser.queryForList("SELECT id FROM scan_pairings WHERE id IN (?, ?)", UUID.class, p, otherPairing)));
        assertThat(seen).containsExactly(p);
    }

    // ── relay events: tenant isolation as app_user ───────────────────────────

    @Test
    void relayEvents_crossTenantIsolation_asAppUser_withSameTenantPositiveControl() {
        Station a = station("Relay A"), b = station("Relay B");
        UUID pa = pairing(a, h("a")), pb = pairing(b, h("b"));
        UUID ea = jdbc.queryForObject("INSERT INTO scan_relay_events (tenant_id, pairing_id, seq, code) " +
            "VALUES (?, ?, 1, 'P000001') RETURNING id", UUID.class, a.tenant, pa);
        UUID eb = jdbc.queryForObject("INSERT INTO scan_relay_events (tenant_id, pairing_id, seq, code) " +
            "VALUES (?, ?, 1, 'P000002') RETURNING id", UUID.class, b.tenant, pb);

        List<UUID> asA = TenantContext.runAs(a.tenant, () -> appUserTx.execute(t ->
            appUser.queryForList("SELECT id FROM scan_relay_events WHERE id IN (?, ?)", UUID.class, ea, eb)));
        assertThat(asA).as("same-tenant positive control + no cross-tenant row").containsExactly(ea);

        int updated = TenantContext.runAs(a.tenant, () -> appUserTx.execute(t ->
            appUser.update("UPDATE scan_relay_events SET status = 'accepted', outcome_at = now() WHERE id = ?", eb)));
        assertThat(updated).as("tenant A can't touch tenant B's event").isZero();

        assertThat(rawAppUser.queryForList("SELECT id FROM scan_relay_events WHERE id IN (?, ?)", UUID.class, ea, eb))
            .as("no GUC → nothing").isEmpty();

        TenantContext.runAs(a.tenant, () -> appUserTx.executeWithoutResult(t ->
            assertThatThrownBy(() -> appUser.update(
                "INSERT INTO scan_relay_events (tenant_id, pairing_id, seq, code) VALUES (?, ?, 2, 'X')", b.tenant, pb))
                .isInstanceOf(DataAccessException.class).rootCause().hasMessageContaining("row-level security")));

        assertThatThrownBy(() -> jdbc.update(
            "INSERT INTO scan_relay_events (tenant_id, pairing_id, seq, code) VALUES (?, ?, 1, 'again')", a.tenant, pa))
            .as("UNIQUE (pairing_id, seq)").isInstanceOf(DuplicateKeyException.class);

        TenantContext.runAs(a.tenant, () -> appUserTx.executeWithoutResult(t ->
            assertThatThrownBy(() -> appUser.update("UPDATE scan_relay_events SET code = 'x' WHERE id = ?", ea))
                .isInstanceOf(DataAccessException.class).rootCause().hasMessageContaining("permission denied")));
    }
}
