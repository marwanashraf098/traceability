package com.traceability;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Dry-runs scripts/ops/2026-10-02-bosta-preconnect-cleanup.sql against a migrated database
 * seeded with the exact prod ids it targets (no Spring context — the script runs as the DB
 * owner, like in prod).
 *
 *   oc1 as shipped (ends with ROLLBACK) → nothing changes
 *   oc2 switched to COMMIT → the 4 EXC- orders and their rows are gone, 7577553206 dismissed,
 *       44 unlinked rows resolved with 44 owner-attributed audit resolutions ("Ops cleanup by
 *       Traced …"); the 7 post-cutoff NULL-reference rows and unrelated rows untouched
 *   oc3 a dependent row (an order note on an EXC- order) → the guard aborts, nothing changes
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_METHOD)
class OpsPreConnectCleanupScriptTest {

    @Container
    final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("traceability_test").withUsername("postgres").withPassword("postgres");

    static final String BROEK  = "d6e1ffe7-cdb4-4e57-9287-f6f9a6322d2d";
    static final String FEMINE = "a7d6fab9-828d-4b3a-8ab6-71e6bef3d197";
    static final String[][] EXC = {
        // order, tracking, exchange, order_item, shipment
        {"1993fad9-f049-4721-bf6d-ac439ab93f63", "9651000477", "fc1a308d-a69d-4aac-bee7-791de94810f6",
         "8375857f-99bd-48c0-a66c-e732e5cd6d57", "e20c47f8-3b99-41c9-a6ce-756ca85e8543"},
        {"85f739a4-d73d-4cc5-9115-18acf1e3d937", "1785841805", "c6d0cde2-4bfa-4651-8c19-8345bda86c78",
         "84652202-02b0-49e6-addb-e600b7c79f2f", "35056a52-e841-4363-8ea5-5ac9517f4c12"},
        {"a83dbf34-3390-4e3c-9726-3cea30661cfa", "1010896713", "8b46ac95-0c06-4e47-ac43-39cc80819da2",
         "b865065c-4766-41f1-ab7d-afb2a87ff246", "ff234b29-9d4f-4d0c-93b5-d4ddb384427e"},
        {"70fb30b4-7f21-4ad7-b128-4e82b672611a", "4204825497", "7eecc603-ebb8-4e99-861a-5cc172f36f3e",
         "fbc19284-2ac7-429d-a3fe-eeef447f687c", "6ccd67c1-9c43-4232-b51e-c9324b4fe16b"},
    };
    static final long[] NOTIF_IDS = {206, 207, 247, 248};
    static final List<Long> BROEK_UNLINKED = List.of(5560L,5561L,5562L,5563L,5564L,5566L,5568L,5570L,5571L,5572L,
        5575L,5576L,5577L,5578L,5579L,5580L,5581L,5611L,5612L,5613L,5614L,5616L,5627L);
    static final List<Long> FEMINE_UNLINKED = List.of(5582L,5583L,5584L,5585L,5586L,5587L,5588L,5589L,5590L,5591L,
        5598L,5599L,5600L,5601L,5602L,5603L,5604L,5605L,5606L,5607L,5608L,5609L,5610L,5615L,5628L,5631L,5648L,5657L);

    @BeforeEach
    void migrateAndSeed() throws Exception {
        Flyway.configure().dataSource(pg.getJdbcUrl(), "postgres", "postgres")
            .locations("classpath:db/migration").load().migrate();
        try (Connection c = conn(); Statement s = c.createStatement()) {
            for (String t : new String[]{BROEK, FEMINE}) {
                s.execute("INSERT INTO tenants (id, name) VALUES ('" + t + "', 't-" + t.substring(0, 4) + "')");
                s.execute("INSERT INTO users (id, tenant_id, name, email, password_hash, role) VALUES " +
                    "(gen_random_uuid(), '" + t + "', 'Owner', 'o-" + t + "@x.test', 'h', 'owner')");
                s.execute("INSERT INTO stores (id, tenant_id, platform, shop_domain, status) VALUES " +
                    "(gen_random_uuid(), '" + t + "', 'shopify', 's-" + t + ".myshopify.com', 'connected')");
            }
            s.execute("INSERT INTO products (id, tenant_id, store_id, external_id, title) " +
                "SELECT gen_random_uuid(), tenant_id, id, 'P', 'Shirt' FROM stores WHERE tenant_id = '" + BROEK + "'");
            s.execute("INSERT INTO variants (id, tenant_id, product_id, external_id, title) " +
                "SELECT gen_random_uuid(), tenant_id, id, 'V', 'M' FROM products WHERE tenant_id = '" + BROEK + "'");
            for (String[] e : EXC) {
                s.execute("INSERT INTO orders (id, tenant_id, store_id, external_id, number, status) " +
                    "SELECT '" + e[0] + "', '" + BROEK + "', id, 'internal:exchange:" + e[1] + "', 'EXC-" + e[1] + "', 'new' " +
                    "FROM stores WHERE tenant_id = '" + BROEK + "'");
                s.execute("INSERT INTO order_items (id, tenant_id, order_id, variant_id, quantity) " +
                    "SELECT '" + e[3] + "', '" + BROEK + "', '" + e[0] + "', id, 1 FROM variants WHERE tenant_id = '" + BROEK + "'");
                s.execute("INSERT INTO shipments (id, tenant_id, order_id, provider, tracking_number, internal_state) " +
                    "VALUES ('" + e[4] + "', '" + BROEK + "', '" + e[0] + "', 'bosta', '" + e[1] + "', 'with_courier')");
                s.execute("INSERT INTO shipment_status_history (tenant_id, shipment_id, internal_state, provider_state) " +
                    "VALUES ('" + BROEK + "', '" + e[4] + "', 'created', 10), ('" + BROEK + "', '" + e[4] + "', 'with_courier', 21)");
                s.execute("INSERT INTO exchanges (id, tenant_id, tracking_number, status, outbound_order_id, raw) " +
                    "VALUES ('" + e[2] + "', '" + BROEK + "', '" + e[1] + "', 'unmatched', '" + e[0] + "', '{}')");
            }
            for (int i = 0; i < 4; i++) {
                s.execute("INSERT INTO exception_notifications (id, tenant_id, exception_type, subject_key, channel) " +
                    "VALUES (" + NOTIF_IDS[i] + ", '" + BROEK + "', 'exchange_unmapped_state', " +
                    "'exchange_unmapped_state:" + EXC[i][2] + "', 'digest')");
            }
            s.execute("INSERT INTO exchanges (id, tenant_id, tracking_number, status, raw) VALUES " +
                "('277dc26d-450a-4944-b80c-dcd02c3a9df0', '" + BROEK + "', '7577553206', 'needs_mapping', '{}')");
            for (Long id : BROEK_UNLINKED) unlinked(s, id, BROEK);
            for (Long id : FEMINE_UNLINKED) unlinked(s, id, FEMINE);
            unlinked(s, 9999L, BROEK);   // not a target — must stay unresolved
        }
    }

    @Test
    void oc1_asShipped_endsWithRollback_changesNothing() throws Exception {
        String before = state();
        run(script());
        assertThat(state()).isEqualTo(before);
    }

    @Test
    void oc2_switchedToCommit_appliesExactlyTheTargets() throws Exception {
        run(script().replace("ROLLBACK;   -- ← change to COMMIT", "COMMIT;   -- ← change to COMMIT"));
        assertThat(count("SELECT COUNT(*) FROM orders WHERE number LIKE 'EXC-%'")).isZero();
        assertThat(count("SELECT COUNT(*) FROM shipments")).isZero();
        assertThat(count("SELECT COUNT(*) FROM shipment_status_history")).isZero();
        assertThat(count("SELECT COUNT(*) FROM order_items")).isZero();
        assertThat(count("SELECT COUNT(*) FROM exchanges WHERE status <> 'dismissed'")).isZero();
        assertThat(count("SELECT COUNT(*) FROM exchanges WHERE tracking_number = '7577553206' AND status = 'dismissed'")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM exception_notifications")).isZero();
        assertThat(count("SELECT COUNT(*) FROM unlinked_bosta_deliveries WHERE resolved AND match_reason = 'PRE_CONNECT_CLEANUP'"))
            .isEqualTo(44);
        // The 7 NULL-reference rows created after the cutoff stay unresolved, as does a non-target.
        assertThat(count("SELECT COUNT(*) FROM unlinked_bosta_deliveries WHERE NOT resolved " +
            "AND id IN (5616, 5627, 5615, 5628, 5631, 5648, 5657, 9999)")).isEqualTo(8);
        assertThat(count("SELECT COUNT(*) FROM exception_resolutions WHERE exception_type = 'unmatched_delivery' " +
            "AND note = 'Ops cleanup by Traced (pre-connect, 2026-10-02)' " +
            "AND resolved_by IN (SELECT id FROM users WHERE role = 'owner')")).isEqualTo(44);
    }

    @Test
    void oc3_dependentRow_guardAborts_nothingChanges() throws Exception {
        try (Connection c = conn(); Statement s = c.createStatement()) {
            s.execute("INSERT INTO order_notes (tenant_id, order_id, body, created_by) " +
                "SELECT '" + BROEK + "', '" + EXC[0][0] + "', 'note', id FROM users WHERE tenant_id = '" + BROEK + "'");
        }
        String before = state();
        assertThatThrownBy(() -> run(script().replace("ROLLBACK;   -- ← change to COMMIT", "COMMIT;   -- ← change to COMMIT")))
            .isInstanceOf(SQLException.class).hasMessageContaining("ABORT");
        assertThat(state()).isEqualTo(before);
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private void unlinked(Statement s, long id, String tenant) throws SQLException {
        s.execute("INSERT INTO unlinked_bosta_deliveries (id, tenant_id, tracking_number, bosta_state_code, " +
            "bosta_order_type, match_reason) VALUES (" + id + ", '" + tenant + "', 'T" + id + "', 10, 'SEND', 'NO_MATCH')");
    }

    private String script() throws Exception {
        return Files.readString(Path.of("scripts/ops/2026-10-02-bosta-preconnect-cleanup.sql"));
    }

    private void run(String sql) throws SQLException {
        try (Connection c = conn(); Statement s = c.createStatement()) {
            s.execute(sql);
        }
    }

    private String state() throws SQLException {
        return count("SELECT COUNT(*) FROM orders") + "|" + count("SELECT COUNT(*) FROM shipments") + "|"
            + count("SELECT COUNT(*) FROM shipment_status_history") + "|" + count("SELECT COUNT(*) FROM exchanges WHERE status = 'dismissed'") + "|"
            + count("SELECT COUNT(*) FROM exchanges") + "|" + count("SELECT COUNT(*) FROM exception_notifications") + "|"
            + count("SELECT COUNT(*) FROM unlinked_bosta_deliveries WHERE resolved") + "|"
            + count("SELECT COUNT(*) FROM exception_resolutions");
    }

    private int count(String sql) throws SQLException {
        try (Connection c = conn(); Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private Connection conn() throws SQLException {
        return DriverManager.getConnection(pg.getJdbcUrl(), "postgres", "postgres");
    }
}
