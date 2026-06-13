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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs all Flyway migrations against a real PostgreSQL container and
 * verifies structural guarantees: RLS policies on every tenant-scoped
 * table and INSERT-only enforcement on piece_events.
 */
@Testcontainers
class MigrationSmokeTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("traceability_test")
                    .withUsername("postgres")
                    .withPassword("postgres");

    /** Every tenant-scoped table must have the tenant_isolation policy. */
    private static final List<String> TENANT_SCOPED_TABLES = List.of(
            "tenants", "users", "stores", "courier_accounts",
            "locations", "products", "variants", "receipts",
            "pieces", "piece_events", "orders", "order_items",
            "allocations", "shipments", "pickups",
            "pickup_shipments", "webhook_events", "refresh_tokens"
    );

    @Test
    void migrationsSucceedAndStructuralGuaranteesHold() throws Exception {
        // 1. Run migrations
        Flyway flyway = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(),
                        POSTGRES.getUsername(),
                        POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load();

        MigrateResult result = flyway.migrate();
        assertThat(result.success)
                .as("Flyway migrations must succeed")
                .isTrue();
        assertThat(result.migrationsExecuted)
                .as("V1, V2, V3, and V4 must execute")
                .isEqualTo(4);

        try (Connection conn = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(),
                POSTGRES.getPassword())) {

            // 2. Every tenant-scoped table must have the RLS policy
            String policySql = """
                    SELECT COUNT(*) FROM pg_policies
                    WHERE schemaname = 'public'
                      AND policyname  = 'tenant_isolation'
                      AND tablename   = ?
                    """;
            for (String table : TENANT_SCOPED_TABLES) {
                try (PreparedStatement ps = conn.prepareStatement(policySql)) {
                    ps.setString(1, table);
                    try (ResultSet rs = ps.executeQuery()) {
                        rs.next();
                        assertThat(rs.getInt(1))
                                .as("RLS policy 'tenant_isolation' missing on table: " + table)
                                .isGreaterThan(0);
                    }
                }
            }

            // 3. RLS must be enabled (not just defined) on piece_events
            String rlsEnabledSql = """
                    SELECT relrowsecurity FROM pg_class
                    WHERE relname = 'piece_events'
                      AND relnamespace = 'public'::regnamespace
                    """;
            try (PreparedStatement ps = conn.prepareStatement(rlsEnabledSql);
                 ResultSet rs = ps.executeQuery()) {
                rs.next();
                assertThat(rs.getBoolean("relrowsecurity"))
                        .as("RLS must be enabled on piece_events")
                        .isTrue();
            }

            // 4. app_user must NOT have UPDATE on piece_events
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT has_table_privilege('app_user', 'piece_events', 'UPDATE')");
                 ResultSet rs = ps.executeQuery()) {
                rs.next();
                assertThat(rs.getBoolean(1))
                        .as("app_user must NOT have UPDATE on piece_events")
                        .isFalse();
            }

            // 5. app_user must NOT have DELETE on piece_events
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT has_table_privilege('app_user', 'piece_events', 'DELETE')");
                 ResultSet rs = ps.executeQuery()) {
                rs.next();
                assertThat(rs.getBoolean(1))
                        .as("app_user must NOT have DELETE on piece_events")
                        .isFalse();
            }

            // 6. app_user MUST have INSERT on piece_events
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT has_table_privilege('app_user', 'piece_events', 'INSERT')");
                 ResultSet rs = ps.executeQuery()) {
                rs.next();
                assertThat(rs.getBoolean(1))
                        .as("app_user must have INSERT on piece_events")
                        .isTrue();
            }

            // 7. Seed data: all 23 Bosta state mapping rows present
            // (code 41 has two rows: SEND and RTO; defensive codes 22,23,25,40,60,104 included)
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT COUNT(*) FROM bosta_state_mappings");
                 ResultSet rs = ps.executeQuery()) {
                rs.next();
                assertThat(rs.getInt(1))
                        .as("bosta_state_mappings must be seeded")
                        .isEqualTo(23);
            }

            // 8. Seed data: all 22 NDR code rows present (11 forward + 11 return)
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT COUNT(*) FROM ndr_codes");
                 ResultSet rs = ps.executeQuery()) {
                rs.next();
                assertThat(rs.getInt(1))
                        .as("ndr_codes must be seeded")
                        .isEqualTo(22);
            }

            // 9. Critical NDR codes (26-30) must be marked critical
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT COUNT(*) FROM ndr_codes WHERE code BETWEEN 26 AND 30 AND severity = 'critical'");
                 ResultSet rs = ps.executeQuery()) {
                rs.next();
                assertThat(rs.getInt(1))
                        .as("NDR codes 26–30 must have severity=critical")
                        .isEqualTo(5);
            }

            // 10. Two Bosta webhook rows with NULL external_event_id must both insert.
            // A UNIQUE NULLS NOT DISTINCT constraint would block the second row; the
            // partial unique index (WHERE external_event_id IS NOT NULL) must not.
            conn.prepareStatement("""
                    INSERT INTO webhook_events (source, topic, payload)
                    VALUES ('bosta', 'state_change', '{"test":1}'),
                           ('bosta', 'state_change', '{"test":2}')
                    """).execute();
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT COUNT(*) FROM webhook_events WHERE source = 'bosta' AND external_event_id IS NULL");
                 ResultSet rs = ps.executeQuery()) {
                rs.next();
                assertThat(rs.getInt(1))
                        .as("Two Bosta webhook rows with NULL external_event_id must both be stored")
                        .isEqualTo(2);
            }

            // 11. pieces.id column must be type text (ULID), not uuid.
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT data_type FROM information_schema.columns " +
                    "WHERE table_schema = 'public' AND table_name = 'pieces' AND column_name = 'id'");
                 ResultSet rs = ps.executeQuery()) {
                rs.next();
                assertThat(rs.getString("data_type"))
                        .as("pieces.id must be text (app-generated ULID), not uuid")
                        .isEqualTo("text");
            }

            // 12. auth_lookup_user returns a seeded user without app.current_tenant GUC set.
            // SECURITY DEFINER lets the function read across tenants regardless of GUC state.
            conn.prepareStatement("""
                    INSERT INTO tenants (id, name) VALUES
                        ('aaaaaaaa-0000-0000-0000-000000000001', 'SmokeTestTenant')
                    """).execute();
            conn.prepareStatement("""
                    INSERT INTO users (id, tenant_id, name, email, password_hash, role) VALUES
                        ('bbbbbbbb-0000-0000-0000-000000000001',
                         'aaaaaaaa-0000-0000-0000-000000000001',
                         'Smoke User', 'smoke@test.local', '$2a$hash', 'owner')
                    """).execute();
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT user_id::text FROM auth_lookup_user('smoke@test.local')");
                 ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next())
                        .as("auth_lookup_user must return a row for a seeded active user")
                        .isTrue();
                assertThat(rs.getString("user_id"))
                        .as("auth_lookup_user must return the correct user_id")
                        .isEqualTo("bbbbbbbb-0000-0000-0000-000000000001");
            }
        }
    }
}
