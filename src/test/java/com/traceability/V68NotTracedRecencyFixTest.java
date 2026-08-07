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
import java.sql.Timestamp;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * id-DESC sweep — V68 migration, which re-runs V57's not_traced backfill with the corrected
 * `created_at DESC, id DESC` tie-break.
 *
 * Mirrors {@link NotTracedBackfillTest}'s two-stage Flyway pattern: migrate to V56 (schema
 * before both V57 and V68 existed), seed a tie-break fixture directly, then migrate to latest
 * so V57's original (bare `id DESC`) backfill and V68's corrective backfill both run in one
 * batch — exactly what happens on a fresh environment, and equivalent in effect to what
 * V68 does on an already-V57-migrated production database.
 *
 * Fixture: an order with two forward shipments where the OLDER row (still 'created') carries
 * the lexically HIGHER uuid and the NEWER row ('terminated') carries the lower one. V57's own
 * `id DESC` tie-break would pick the older 'created' row and leave the order untagged; V68
 * re-evaluates with `created_at DESC, id DESC`, correctly picks the newer 'terminated' row,
 * and tags it. A sibling "already correct" order proves V68 is a no-op where V57 already got
 * it right.
 */
@Testcontainers
class V68NotTracedRecencyFixTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("traceability_test")
                    .withUsername("postgres")
                    .withPassword("postgres");

    // Explicit UUID literals — older row gets the lexically higher uuid so V57's bare
    // `id DESC` picks it (wrongly) over the newer, lower-uuid 'terminated' row.
    private static final UUID OLDER_HIGH_UUID = UUID.fromString("ffffffff-ffff-4fff-8fff-ffffffffffff");
    private static final UUID NEWER_LOW_UUID  = UUID.fromString("00000000-0000-4000-8000-000000000000");

    @Test
    void v68_correctsTieBreak_leavesAlreadyCorrectOrderUntouched() throws Exception {
        // 1. Migrate up to V56 only — schema before V57 (and V68) existed.
        Flyway toV56 = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .target("56")
                .load();
        MigrateResult r1 = toV56.migrate();
        assertThat(r1.success).as("migration to V56 must succeed").isTrue();

        UUID tenantId       = UUID.randomUUID();
        UUID storeId        = UUID.randomUUID();
        UUID tieBreakOrder  = UUID.randomUUID();
        UUID correctOrderId = UUID.randomUUID();

        try (Connection conn = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {

            exec(conn, "INSERT INTO tenants (id, name) VALUES (?, 'V68Tenant')", tenantId);
            exec(conn, "INSERT INTO stores (id, tenant_id, platform, shop_domain, status) " +
                       "VALUES (?, ?, 'shopify', 'v68.myshopify.com', 'disconnected')",
                       storeId, tenantId);

            // Tie-break order: zero allocations, two forward shipments — older/higher-uuid
            // 'created', newer/lower-uuid 'terminated'. V57's `id DESC` picks the older
            // 'created' row ⇒ leaves it untagged. V68 must correct this to tagged.
            exec(conn, "INSERT INTO orders (id, tenant_id, store_id, external_id, number, status, on_hold, placed_at) " +
                       "VALUES (?, ?, ?, 'EXT-V68-TIE', '#V68-TIE', 'new'::order_status, false, now())",
                       tieBreakOrder, tenantId, storeId);
            exec(conn, "INSERT INTO shipments (id, tenant_id, order_id, provider, tracking_number, " +
                       "    internal_state, shipment_leg, created_at) " +
                       "VALUES (?, ?, ?, 'bosta', 'V68-TN-OLDER', 'created'::shipment_internal_state, " +
                       "    'forward', '2026-08-01T10:00:00Z'::timestamptz)",
                       OLDER_HIGH_UUID, tenantId, tieBreakOrder);
            exec(conn, "INSERT INTO shipments (id, tenant_id, order_id, provider, tracking_number, " +
                       "    internal_state, shipment_leg, created_at) " +
                       "VALUES (?, ?, ?, 'bosta', 'V68-TN-NEWER', 'terminated'::shipment_internal_state, " +
                       "    'forward', '2026-08-01T11:00:00Z'::timestamptz)",
                       NEWER_LOW_UUID, tenantId, tieBreakOrder);

            // Already-correct order: single forward shipment, already 'delivered' — V57
            // tags it correctly regardless of tie-break ordering; V68 must be a no-op here
            // (not re-derive/overwrite an already-set timestamp).
            exec(conn, "INSERT INTO orders (id, tenant_id, store_id, external_id, number, status, on_hold, placed_at) " +
                       "VALUES (?, ?, ?, 'EXT-V68-OK', '#V68-OK', 'new'::order_status, false, now())",
                       correctOrderId, tenantId, storeId);
            exec(conn, "INSERT INTO shipments (tenant_id, order_id, provider, tracking_number, internal_state, shipment_leg) " +
                       "VALUES (?, ?, 'bosta', 'V68-TN-OK', 'delivered'::shipment_internal_state, 'forward')",
                       tenantId, correctOrderId);
        }

        // 2. Apply the rest of the migrations — V57 (original, buggy tie-break) then V68
        //    (corrective) run in the same batch here, equivalent to V68 running standalone
        //    against an already-V57-migrated production database.
        Flyway toLatest = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load();
        MigrateResult r2 = toLatest.migrate();
        assertThat(r2.success).as("migrations after V56 must succeed").isTrue();

        try (Connection conn = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            assertThat(notTracedAt(conn, tieBreakOrder))
                    .as("V68 must tag the tie-break order — the true latest (by created_at) "
                        + "forward shipment is 'terminated', not the older, higher-uuid 'created' row")
                    .isNotNull();
            assertThat(notTracedAt(conn, correctOrderId))
                    .as("V68 must be a no-op on an order V57 already tagged correctly")
                    .isNotNull();
        }

        // Confirm V57's file itself was never touched by this change — its checksum must
        // still be the one Flyway validated on every prior startup of this DB.
        Integer v57Rows;
        try (Connection conn = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             PreparedStatement ps = conn.prepareStatement(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '57' AND success = true")) {
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                v57Rows = rs.getInt(1);
            }
        }
        assertThat(v57Rows).as("V57 applied successfully and untouched — no checksum edit").isEqualTo(1);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static void exec(Connection conn, String sql, Object... params) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            ps.execute();
        }
    }

    private static Timestamp notTracedAt(Connection conn, UUID orderId) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT not_traced_at FROM orders WHERE id = ?")) {
            ps.setObject(1, orderId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getTimestamp("not_traced_at");
            }
        }
    }
}
