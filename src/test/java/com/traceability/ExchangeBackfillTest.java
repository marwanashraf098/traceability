package com.traceability;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FR-EXCHANGE Phase 1 — V74 migration backfill. Two-stage Flyway run (same pattern as
 * NotTracedBackfillTest): migrate to V73 first to seed pre-existing unlinked_bosta_deliveries
 * rows exactly as the fleet-confirmed shape looked before this feature existed, then migrate
 * to latest so only V74 runs its DELETE/INSERT/UPDATE against real, pre-existing rows.
 *
 * Fixture matrix (4 rows, one per outcome):
 *   A — EXCHANGE, resolved=false, single-item raw    → backfilled into exchanges, resolved=true
 *   B — EXCHANGE, resolved=false, raw IS NULL        → left untouched (defensive guard)
 *   C — EXCHANGE, already resolved=true before V74   → left untouched, out of scope by design
 *   D — SEND (non-exchange), resolved=false          → left untouched, scoped correctly
 */
@Testcontainers
class ExchangeBackfillTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("traceability_test")
                    .withUsername("postgres")
                    .withPassword("postgres");

    @Test
    void v74Backfill_migratesOnlyEligibleUnresolvedExchangeRows() throws Exception {
        // 1. Migrate up to V73 — schema as it existed before this feature.
        Flyway toV73 = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .target("73")
                .load();
        MigrateResult r1 = toV73.migrate();
        assertThat(r1.success).as("migration to V73 must succeed").isTrue();

        UUID tenantId = UUID.randomUUID();

        String rawA = """
            {"type":{"code":30,"value":"Exchange"},
             "specs":{"packageDetails":{"description":"Yellow stripes bucket hat size XS/S/M/L ","itemsCount":1}},
             "returnSpecs":{"packageDetails":{"description":"red checkered bucket hat","descriptionAr":"قبعة دلو مربعة حمراء","itemsCount":1}},
             "cod":"0","goodsInfo":{"amount":"600"}}
            """;
        String rawC = """
            {"type":{"code":30,"value":"Exchange"},
             "specs":{"packageDetails":{"description":"Already resolved outbound","itemsCount":1}},
             "returnSpecs":{"packageDetails":{"description":"Already resolved inbound","descriptionAr":"مُسوّى بالفعل","itemsCount":1}},
             "cod":"0","goodsInfo":{"amount":"150"}}
            """;

        try (Connection conn = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {

            exec(conn, "INSERT INTO tenants (id, name) VALUES (?, 'ExchangeBackfillTenant')", tenantId);

            insertUnlinked(conn, tenantId, "877468285", "EXCHANGE", false, rawA);
            insertUnlinked(conn, tenantId, "6336637079", "EXCHANGE", false, null);
            insertUnlinked(conn, tenantId, "184907356", "EXCHANGE", true, rawC);
            insertUnlinked(conn, tenantId, "9293360461", "SEND", false, rawA);
        }

        // 2. Apply V74 — the migration under test.
        Flyway toLatest = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load();
        MigrateResult r2 = toLatest.migrate();
        assertThat(r2.success).as("V74 must succeed").isTrue();
        assertThat(r2.migrationsExecuted)
            .as("V74 + V75 + V76 + V77 pending after V73 (V75-77 are index-only Overview-trends " +
                "migrations, no data touched, added after this test was written)")
            .isEqualTo(4);

        try (Connection conn = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {

            // Row A: backfilled correctly, resolved flips to true.
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT status, outbound_description, inbound_description, " +
                    "       inbound_description_ar, cod, goods_value " +
                    "FROM exchanges WHERE tenant_id = ? AND tracking_number = '877468285'")) {
                ps.setObject(1, tenantId);
                try (ResultSet rs = ps.executeQuery()) {
                    assertThat(rs.next()).as("row A must be backfilled into exchanges").isTrue();
                    assertThat(rs.getString("status")).isEqualTo("needs_mapping");
                    assertThat(rs.getString("outbound_description"))
                        .isEqualTo("Yellow stripes bucket hat size XS/S/M/L ");
                    assertThat(rs.getString("inbound_description")).isEqualTo("red checkered bucket hat");
                    assertThat(rs.getString("inbound_description_ar")).isEqualTo("قبعة دلو مربعة حمراء");
                    assertThat(rs.getBigDecimal("cod")).isEqualByComparingTo(BigDecimal.ZERO);
                    assertThat(rs.getBigDecimal("goods_value")).isEqualByComparingTo(new BigDecimal("600"));
                }
            }
            assertThat(resolvedFlag(conn, tenantId, "877468285"))
                .as("row A's unlinked source row must flip to resolved=true")
                .isTrue();

            // Row B: raw IS NULL — left untouched, not backfilled.
            assertThat(countExchanges(conn, tenantId, "6336637079"))
                .as("row B (raw IS NULL) must not produce an exchanges row")
                .isZero();
            assertThat(resolvedFlag(conn, tenantId, "6336637079"))
                .as("row B must stay resolved=false — it was never represented anywhere")
                .isFalse();

            // Row C: already resolved before V74 ran — out of scope by design (WHERE resolved=false).
            assertThat(countExchanges(conn, tenantId, "184907356"))
                .as("row C was already resolved before V74 — not in the backfill set, no exchanges row")
                .isZero();
            assertThat(resolvedFlag(conn, tenantId, "184907356")).isTrue();

            // Row D: non-exchange type — completely out of scope.
            assertThat(countExchanges(conn, tenantId, "9293360461"))
                .as("row D (bosta_order_type=SEND) must never produce an exchanges row")
                .isZero();
            assertThat(resolvedFlag(conn, tenantId, "9293360461"))
                .as("row D must be untouched by the exchange backfill")
                .isFalse();

            // Exactly one exchanges row total for this tenant — only row A qualified.
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT COUNT(*) FROM exchanges WHERE tenant_id = ?")) {
                ps.setObject(1, tenantId);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    assertThat(rs.getInt(1)).isEqualTo(1);
                }
            }
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static void exec(Connection conn, String sql, Object... params) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) ps.setObject(i + 1, params[i]);
            ps.executeUpdate();
        }
    }

    private static void insertUnlinked(Connection conn, UUID tenantId, String tracking,
                                        String orderType, boolean resolved, String raw) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO unlinked_bosta_deliveries " +
                "  (tenant_id, tracking_number, bosta_state_code, bosta_order_type, raw, resolved) " +
                "VALUES (?, ?, 10, ?, ?::jsonb, ?)")) {
            ps.setObject(1, tenantId);
            ps.setString(2, tracking);
            ps.setString(3, orderType);
            ps.setString(4, raw);
            ps.setBoolean(5, resolved);
            ps.executeUpdate();
        }
    }

    private static int countExchanges(Connection conn, UUID tenantId, String tracking) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COUNT(*) FROM exchanges WHERE tenant_id = ? AND tracking_number = ?")) {
            ps.setObject(1, tenantId);
            ps.setString(2, tracking);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private static boolean resolvedFlag(Connection conn, UUID tenantId, String tracking) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT resolved FROM unlinked_bosta_deliveries WHERE tenant_id = ? AND tracking_number = ?")) {
            ps.setObject(1, tenantId);
            ps.setString(2, tracking);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getBoolean(1);
            }
        }
    }
}
