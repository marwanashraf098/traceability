package com.traceability;

import com.traceability.tenancy.TenantAwareDataSource;
import com.traceability.tenancy.TenantContext;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
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
 * Q1 — V139 schema and the revised hatch #15 (resolve_scan_pairing): a pairing is anchored to a
 * tablet and a worker, not a pack session; the hatch resolves only a live, matching, unexpired
 * pairing whose worker is active, and returns nothing but (tenant_id, pairing_id); one live
 * pairing per tablet and per worker; app_user still can never read a credential hash.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StationPhoneSchemaTest {

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

    record Station(UUID tenant, UUID user, String device) {}

    private Station station(String name) {
        PackFixtures f = new PackFixtures(jdbc, name);
        return new Station(f.tenant, f.user("Ahmed", "worker"), device());
    }

    private static String device() { return "tab" + UUID.randomUUID().toString().replace("-", ""); }

    private static String h(String s) { return "hash-" + s + "-" + UUID.randomUUID(); }

    /** A pairing as the service creates it (no pack session): pair code valid 2 min, pairing 12 h. */
    private UUID pairing(Station s, String pairHash) {
        return jdbc.queryForObject(
            "INSERT INTO scan_pairings (tenant_id, station_device_id, station_user_id, pair_code_hash, " +
            "                           pair_code_expires_at, expires_at) " +
            "VALUES (?, ?, ?, ?, now() + interval '2 minutes', now() + interval '12 hours') RETURNING id",
            UUID.class, s.tenant, s.device, s.user, pairHash);
    }

    private void claim(UUID pairing, String secretHash) {
        jdbc.update("UPDATE scan_pairings SET claimed_at = now(), device_secret_hash = ? WHERE id = ?", secretHash, pairing);
    }

    private void revoke(UUID pairing) {
        jdbc.update("UPDATE scan_pairings SET revoked_at = now(), revoked_reason = 'unpaired' WHERE id = ?", pairing);
    }

    /** Hatch #15 exactly as the phone's requests call it: app_user, no tenant GUC. */
    private List<Map<String, Object>> hatch(String kind, String hash) {
        return rawAppUser.queryForList("SELECT * FROM resolve_scan_pairing(?, ?)", kind, hash);
    }

    private void assertResolves(String kind, String hash, Station s, UUID pairing) {
        List<Map<String, Object>> rows = hatch(kind, hash);
        assertThat(rows).as(kind + " resolves").hasSize(1);
        assertThat(rows.get(0)).as("nothing but the two ids").containsOnlyKeys("tenant_id", "pairing_id");
        assertThat(rows.get(0).get("tenant_id")).isEqualTo(s.tenant);
        assertThat(rows.get(0).get("pairing_id")).isEqualTo(pairing);
    }

    // ── hatch #15 ────────────────────────────────────────────────────────────

    @Test
    void hatch_pairCode_resolvesWithNoPackSession_andEveryNegativePathIsEmpty() {
        Station s = station("Hatch PC");
        String pc = h("pc");
        UUID p = pairing(s, pc);
        assertResolves("pair_code", pc, s, p);                                         // positive control

        assertThat(hatch("pair_code", pc + "x")).as("wrong hash").isEmpty();
        assertThat(hatch("device_secret", pc)).as("right hash, wrong kind").isEmpty();
        assertThat(hatch("password", pc)).as("unknown kind").isEmpty();
        assertThat(hatch("pair_code", null)).as("null hash").isEmpty();

        jdbc.update("UPDATE scan_pairings SET pair_code_expires_at = now() - interval '1 second' WHERE id = ?", p);
        assertThat(hatch("pair_code", pc)).as("pair code expired").isEmpty();

        String pc2 = h("pc2");
        UUID p2 = pairing(station("Hatch PC 2"), pc2);
        claim(p2, h("ds"));
        assertThat(hatch("pair_code", pc2)).as("code claimed (can't be claimed twice)").isEmpty();

        Station s3 = station("Hatch PC 3");
        String pc3 = h("pc3");
        UUID p3 = pairing(s3, pc3);
        jdbc.update("UPDATE scan_pairings SET expires_at = now() - interval '1 second' WHERE id = ?", p3);
        assertThat(hatch("pair_code", pc3)).as("pairing expired (12 h)").isEmpty();

        Station s4 = station("Hatch PC 4");
        String pc4 = h("pc4");
        UUID p4 = pairing(s4, pc4);
        revoke(p4);
        assertThat(hatch("pair_code", pc4)).as("revoked").isEmpty();

        Station s5 = station("Hatch PC 5");
        String pc5 = h("pc5");
        UUID p5 = pairing(s5, pc5);
        jdbc.update("UPDATE users SET active = false WHERE id = ?", s5.user);
        assertThat(hatch("pair_code", pc5)).as("worker deactivated").isEmpty();
        jdbc.update("UPDATE users SET active = true WHERE id = ?", s5.user);
        assertResolves("pair_code", pc5, s5, p5);
    }

    /** V127's "pack session open" condition is gone: an ended pack session no longer ends a pairing. */
    @Test
    void hatch_ignoresPackSessions_anEndedOneNoLongerEndsThePairing() {
        Station s = station("Hatch session");
        String ds = h("ds");
        UUID session = jdbc.queryForObject(
            "INSERT INTO pack_sessions (tenant_id, user_id, mode) VALUES (?, ?, 'waybill_scan') RETURNING id",
            UUID.class, s.tenant, s.user);
        UUID p = pairing(s, h("pc"));
        jdbc.update("UPDATE scan_pairings SET pack_session_id = ? WHERE id = ?", session, p);   // a historical link
        claim(p, ds);
        jdbc.update("UPDATE pack_sessions SET status = 'ended', ended_at = now() WHERE id = ?", session);
        assertResolves("device_secret", ds, s, p);
    }

    @Test
    void hatch_deviceSecret_onlyOnceClaimed_unexpired_unrevoked_activeWorker() {
        Station s = station("Hatch DS");
        String ds = h("ds");
        UUID p = pairing(s, h("pc"));
        assertThat(hatch("device_secret", ds)).as("not claimed yet").isEmpty();
        claim(p, ds);
        assertResolves("device_secret", ds, s, p);                                     // positive control

        assertThat(hatch("device_secret", ds + "x")).as("wrong hash").isEmpty();

        jdbc.update("UPDATE users SET active = false WHERE id = ?", s.user);
        assertThat(hatch("device_secret", ds)).as("worker deactivated").isEmpty();
        jdbc.update("UPDATE users SET active = true WHERE id = ?", s.user);
        assertResolves("device_secret", ds, s, p);                                     // back once reactivated

        jdbc.update("UPDATE scan_pairings SET expires_at = now() - interval '1 second' WHERE id = ?", p);
        assertThat(hatch("device_secret", ds)).as("expired").isEmpty();
        jdbc.update("UPDATE scan_pairings SET expires_at = now() + interval '1 hour' WHERE id = ?", p);
        assertResolves("device_secret", ds, s, p);

        revoke(p);
        assertThat(hatch("device_secret", ds)).as("revoked").isEmpty();
    }

    @Test
    void hatch_workerOfAnotherTenant_neverResolves() {
        // users.tenant_id must equal the pairing's tenant — a pairing pointing at another tenant's
        // user (only writable by the owner connection) resolves to nothing.
        Station a = station("Hatch cross A"), b = station("Hatch cross B");
        String pc = h("pc");
        jdbc.queryForObject(
            "INSERT INTO scan_pairings (tenant_id, station_device_id, station_user_id, pair_code_hash, " +
            "                           pair_code_expires_at, expires_at) " +
            "VALUES (?, ?, ?, ?, now() + interval '2 minutes', now() + interval '12 hours') RETURNING id",
            UUID.class, a.tenant, a.device, b.user, pc);
        assertThat(hatch("pair_code", pc)).isEmpty();
    }

    @Test
    void hatch_isSecurityDefiner_fixedSearchPath_returnsOnlyTheTwoIds_executableOnlyByAppUser() {
        Map<String, Object> fn = jdbc.queryForMap(
            "SELECT p.prosecdef, p.provolatile, array_to_string(p.proconfig, ',') AS config, " +
            "       pg_get_function_result(p.oid) AS result " +
            "FROM pg_proc p WHERE p.proname = 'resolve_scan_pairing'");
        assertThat(fn.get("prosecdef")).isEqualTo(true);
        assertThat(fn.get("provolatile")).isEqualTo("s");
        assertThat((String) fn.get("config")).isEqualTo("search_path=pg_catalog, public");
        assertThat((String) fn.get("result")).isEqualTo("TABLE(tenant_id uuid, pairing_id uuid)");
        assertThat(jdbc.queryForObject(
            "SELECT has_function_privilege('app_user', 'resolve_scan_pairing(text, text)', 'EXECUTE')", Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject(
            "SELECT has_function_privilege('public', 'resolve_scan_pairing(text, text)', 'EXECUTE')", Boolean.class)).isFalse();
        assertThat(jdbc.queryForObject("SELECT pg_get_functiondef('resolve_scan_pairing(text, text)'::regprocedure)",
            String.class)).doesNotContain("pack_sessions").contains("u.active");
    }

    // ── one live pairing per tablet and per worker ───────────────────────────

    @Test
    void oneLivePairingPerTablet_andPerWorker_revokedOnesDontCount() {
        Station s = station("One active");
        UUID first = pairing(s, h("a"));

        UUID mona = jdbc.queryForObject("INSERT INTO users (tenant_id, name, email, password_hash, role) " +
            "VALUES (?, 'Mona', ?, 'x', 'worker') RETURNING id", UUID.class, s.tenant, "mona-" + UUID.randomUUID() + "@t");

        assertThatThrownBy(() -> pairing(new Station(s.tenant, mona, s.device), h("b")))
            .as("same tablet, another worker").isInstanceOf(DuplicateKeyException.class)
            .hasMessageContaining("scan_pairings_one_active_per_device");
        assertThatThrownBy(() -> pairing(new Station(s.tenant, s.user, device()), h("c")))
            .as("same worker, another tablet").isInstanceOf(DuplicateKeyException.class)
            .hasMessageContaining("scan_pairings_one_active_per_worker");

        revoke(first);
        UUID second = pairing(s, h("d"));                                       // once revoked: allowed
        assertThat(second).isNotEqualTo(first);

        // Another tenant may use the same tablet id string (per-tenant uniqueness).
        Station t = station("Other tenant");
        assertThat(pairing(new Station(t.tenant, t.user, s.device), h("e"))).isNotNull();
    }

    @Test
    void aLivePairingNeedsATablet_andTheTabletIdHasV139sShape() {
        Station s = station("Anchor");
        assertThatThrownBy(() -> pairing(new Station(s.tenant, s.user, null), h("a")))
            .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("scan_pairings_anchor");
        assertThatThrownBy(() -> pairing(new Station(s.tenant, s.user, "short"), h("b")))
            .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> pairing(new Station(s.tenant, s.user, "has spaces and is long enough"), h("c")))
            .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(
            "UPDATE scan_pairings SET revoked_at = now(), revoked_reason = 'session_ended_typo' WHERE id = ?", pairing(s, h("d"))))
            .isInstanceOf(DataIntegrityViolationException.class);
        for (String reason : List.of("station_locked", "signed_out")) {
            Station r = station("Reason " + reason);
            UUID p = pairing(r, h(reason));
            assertThat(jdbc.update("UPDATE scan_pairings SET revoked_at = now(), revoked_reason = ? WHERE id = ?", reason, p))
                .isEqualTo(1);
        }
    }

    // ── app_user: hashes unreadable; the new columns readable / target writable ─

    @Test
    void appUser_cannotReadEitherHash_canReadTheNewColumns_canWriteOnlyTheTarget() {
        Station s = station("Creds");
        UUID p = pairing(s, h("pc"));
        claim(p, h("ds"));

        for (String col : List.of("pair_code_hash", "device_secret_hash")) {
            assertThat(rawAppUser.queryForObject(
                "SELECT has_column_privilege('app_user', 'scan_pairings', ?, 'SELECT')", Boolean.class, col))
                .as("SELECT on " + col).isFalse();
        }
        assertThat(rawAppUser.queryForObject(
            "SELECT has_table_privilege('app_user', 'scan_pairings', 'SELECT')", Boolean.class)).isFalse();
        for (String col : List.of("station_device_id", "active_target", "active_target_at")) {
            assertThat(rawAppUser.queryForObject(
                "SELECT has_column_privilege('app_user', 'scan_pairings', ?, 'SELECT')", Boolean.class, col))
                .as("SELECT on " + col).isTrue();
        }
        assertThat(rawAppUser.queryForObject(
            "SELECT has_column_privilege('app_user', 'scan_pairings', 'station_device_id', 'UPDATE')", Boolean.class))
            .as("a pairing can't be moved to another tablet").isFalse();

        assertThat(rawAppUser.queryForObject(
            "SELECT has_table_privilege('app_user', 'scan_pairings', 'DELETE')", Boolean.class)).isFalse();

        // Even in its own tenant: SELECT *, either hash, writing a hash, DELETE — all refused.
        for (String sql : List.of("SELECT * FROM scan_pairings WHERE id = ?",
                                  "SELECT pair_code_hash FROM scan_pairings WHERE id = ?",
                                  "SELECT device_secret_hash FROM scan_pairings WHERE id = ?")) {
            TenantContext.runAs(s.tenant, () -> appUserTx.executeWithoutResult(t ->
                assertThatThrownBy(() -> appUser.queryForList(sql, p)).as(sql)
                    .isInstanceOf(DataAccessException.class).rootCause().hasMessageContaining("permission denied")));
        }
        for (String sql : List.of("UPDATE scan_pairings SET pair_code_hash = 'x' WHERE id = ?",
                                  "DELETE FROM scan_pairings WHERE id = ?")) {
            TenantContext.runAs(s.tenant, () -> appUserTx.executeWithoutResult(t ->
                assertThatThrownBy(() -> appUser.update(sql, p)).as(sql)
                    .isInstanceOf(DataAccessException.class).rootCause().hasMessageContaining("permission denied")));
        }
        TenantContext.runAs(s.tenant, () -> appUserTx.executeWithoutResult(t -> {
            assertThat(appUser.update("UPDATE scan_pairings SET active_target = 'Pick & Pack', active_target_at = now() " +
                "WHERE id = ?", p)).isEqualTo(1);
            assertThat(appUser.queryForObject("SELECT station_device_id FROM scan_pairings WHERE id = ?", String.class, p))
                .isEqualTo(s.device);
        }));

        // Own tenant only (same-tenant positive control above).
        Station other = station("Creds other");
        UUID otherPairing = pairing(other, h("o"));
        List<UUID> seen = TenantContext.runAs(s.tenant, () -> appUserTx.execute(t ->
            appUser.queryForList("SELECT id FROM scan_pairings WHERE id IN (?, ?)", UUID.class, p, otherPairing)));
        assertThat(seen).containsExactly(p);
        int touched = TenantContext.runAs(s.tenant, () -> appUserTx.execute(t ->
            appUser.update("UPDATE scan_pairings SET active_target = 'x' WHERE id = ?", otherPairing)));
        assertThat(touched).isZero();
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
