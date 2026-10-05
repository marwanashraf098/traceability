package com.traceability;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Q1 — V140 on a database that already holds S6 pairings: every live S6 pairing is revoked once
 * ('replaced') and its undelivered scans expired; an already-revoked one keeps its reason; the
 * revoked_reason CHECK V127 created under its default name is the one replaced; the per-session
 * index is gone and the per-tablet / per-worker ones exist.
 */
@Testcontainers
class StationPairingMigrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("traceability_test")
                    .withUsername("postgres")
                    .withPassword("postgres");

    @Test
    void v138_revokesEveryLiveS6PairingOnce_expiresTheirScans_andSwapsTheIndexes() throws Exception {
        MigrateResult r1 = flyway("139").migrate();
        assertThat(r1.success).isTrue();

        UUID tenant = UUID.randomUUID(), user = UUID.randomUUID(), session = UUID.randomUUID();
        UUID live = UUID.randomUUID(), revoked = UUID.randomUUID(), pending = UUID.randomUUID(), done = UUID.randomUUID();
        try (Connection c = conn()) {
            assertThat(scalar(c, "SELECT pg_get_constraintdef(oid) FROM pg_constraint " +
                "WHERE conname = 'scan_pairings_revoked_reason_check'"))
                .as("V127's inline CHECK, default name — the one V140 drops")
                .contains("unpaired").contains("replaced").contains("session_ended").contains("worker_switched")
                .doesNotContain("station_locked");
            exec(c, "INSERT INTO tenants (id, name) VALUES (?, 'S6 shop')", tenant);
            exec(c, "INSERT INTO users (id, tenant_id, name, email, password_hash, role) " +
                    "VALUES (?, ?, 'Ahmed', 'a@v138.test', 'x', 'worker')", user, tenant);
            exec(c, "INSERT INTO pack_sessions (id, tenant_id, user_id, mode) VALUES (?, ?, ?, 'waybill_scan')",
                session, tenant, user);
            exec(c, "INSERT INTO scan_pairings (id, tenant_id, pack_session_id, station_user_id, pair_code_hash, " +
                    "  device_secret_hash, pair_code_expires_at, claimed_at, expires_at) " +
                    "VALUES (?, ?, ?, ?, 'pc-live', 'ds-live', now() + interval '2 minutes', now(), now() + interval '12 hours')",
                live, tenant, session, user);
            exec(c, "INSERT INTO scan_pairings (id, tenant_id, pack_session_id, station_user_id, pair_code_hash, " +
                    "  pair_code_expires_at, expires_at, revoked_at, revoked_reason) " +
                    "VALUES (?, ?, ?, ?, 'pc-old', now(), now() + interval '12 hours', now() - interval '1 hour', 'unpaired')",
                revoked, tenant, session, user);
            exec(c, "INSERT INTO scan_relay_events (id, tenant_id, pairing_id, seq, code) VALUES (?, ?, ?, 1, 'P1')",
                pending, tenant, live);
            exec(c, "INSERT INTO scan_relay_events (id, tenant_id, pairing_id, seq, code, status, message, outcome_at) " +
                    "VALUES (?, ?, ?, 2, 'P2', 'accepted', 'ok', now())", done, tenant, live);
        }

        MigrateResult r2 = flyway("140").migrate();
        assertThat(r2.success).isTrue();
        assertThat(r2.migrationsExecuted).as("V140 only").isEqualTo(1);

        try (Connection c = conn()) {
            assertThat(scalar(c, "SELECT revoked_reason FROM scan_pairings WHERE id = '" + live + "'"))
                .as("the live S6 pairing is revoked once").isEqualTo("replaced");
            assertThat(scalar(c, "SELECT revoked_reason FROM scan_pairings WHERE id = '" + revoked + "'"))
                .as("an already-revoked one keeps its reason").isEqualTo("unpaired");
            assertThat(scalar(c, "SELECT COUNT(*) FROM scan_pairings WHERE revoked_at IS NULL")).isEqualTo("0");
            assertThat(scalar(c, "SELECT status FROM scan_relay_events WHERE id = '" + pending + "'")).isEqualTo("expired");
            assertThat(scalar(c, "SELECT status FROM scan_relay_events WHERE id = '" + done + "'"))
                .as("an answered scan keeps its outcome").isEqualTo("accepted");
            assertThat(scalar(c, "SELECT pack_session_id FROM scan_pairings WHERE id = '" + live + "'"))
                .as("kept for history").isEqualTo(session.toString());

            String check = scalar(c, "SELECT pg_get_constraintdef(oid) FROM pg_constraint " +
                "WHERE conname = 'scan_pairings_revoked_reason_check'");
            for (String reason : new String[] { "unpaired", "replaced", "session_ended", "worker_switched",
                                                "station_locked", "signed_out" }) {
                assertThat(check).contains("'" + reason + "'");
            }
            assertThat(scalar(c, "SELECT COUNT(*) FROM pg_constraint WHERE conrelid = 'scan_pairings'::regclass " +
                "AND contype = 'c' AND pg_get_constraintdef(oid) LIKE '%revoked_reason%unpaired%'"))
                .as("exactly one revoked_reason CHECK").isEqualTo("1");
            assertThat(scalar(c, "SELECT COUNT(*) FROM pg_indexes WHERE indexname = 'scan_pairings_one_active_per_session'"))
                .isEqualTo("0");
            assertThat(scalar(c, "SELECT indexdef FROM pg_indexes WHERE indexname = 'scan_pairings_one_active_per_device'"))
                .contains("UNIQUE").contains("(tenant_id, station_device_id)").contains("WHERE (revoked_at IS NULL)");
            assertThat(scalar(c, "SELECT indexdef FROM pg_indexes WHERE indexname = 'scan_pairings_one_active_per_worker'"))
                .contains("UNIQUE").contains("(tenant_id, station_user_id)").contains("WHERE (revoked_at IS NULL)");
            assertThat(scalar(c, "SELECT is_nullable FROM information_schema.columns " +
                "WHERE table_name = 'scan_pairings' AND column_name = 'pack_session_id'")).isEqualTo("YES");
        }
    }

    private Flyway flyway(String target) {
        return Flyway.configure()
            .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .locations("classpath:db/migration").target(target).load();
    }

    private Connection conn() throws Exception {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static void exec(Connection c, String sql, Object... args) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
            ps.executeUpdate();
        }
    }

    private static String scalar(Connection c, String sql) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getString(1) : null;
        }
    }
}
