package com.traceability;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V220 — tenants.onboarding_prefs backfill, on rows that exist at V219:
 *   card              'hidden' ⇔ onboarding_dismissed_at set OR a finalized, non-reversed receipt; else 'full'
 *   pickPackChosen    ⇔ a Pick & Pack 'pack' piece event (not the Lookup exchange dispatch's) OR pick_pack_mode = 'waybill_scan'
 *   testLabelPrinted  ⇔ a label_reprints row OR a finalized print_stick receipt
 */
@Testcontainers
class OnboardingPrefsBackfillTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            TestPostgres.newContainer()
                    .withDatabaseName("traceability_test")
                    .withUsername("postgres")
                    .withPassword("postgres");

    final ObjectMapper mapper = new ObjectMapper();

    @Test
    void v220_backfillsPrefsFromExistingRows() throws Exception {
        MigrateResult r1 = FlywayTestSupport.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration").target("219").load().migrate();
        assertThat(r1.success).isTrue();

        UUID dismissed, withReceipt, reversedOnly, connected, waybill, packed, lookupOnly, reprint, countOnly;
        try (Connection c = conn()) {
            dismissed = tenant(c, "dismissed");
            exec(c, "UPDATE tenants SET onboarding_dismissed_at = now() WHERE id = ?", dismissed);

            withReceipt = tenant(c, "receipt");
            receipt(c, withReceipt, "finalized", "print_stick");

            reversedOnly = tenant(c, "reversed");
            receipt(c, reversedOnly, "reversed", "count_only");

            connected = tenant(c, "connected");
            exec(c, "INSERT INTO stores (tenant_id, platform, shop_domain, status) VALUES (?, 'shopify', ?, 'connected')",
                connected, "bf-" + connected + ".myshopify.com");

            waybill = tenant(c, "waybill");
            exec(c, "UPDATE tenants SET pick_pack_mode = 'waybill_scan' WHERE id = ?", waybill);

            packed = tenant(c, "packed");
            String piece = piece(c, packed);
            exec(c, "INSERT INTO piece_events (tenant_id, piece_id, event_type) VALUES (?, ?, 'received')", packed, piece);
            exec(c, "INSERT INTO piece_events (tenant_id, piece_id, event_type) VALUES (?, ?, 'pack')", packed, piece);

            lookupOnly = tenant(c, "lookuponly");
            String lp = piece(c, lookupOnly);
            exec(c, "INSERT INTO piece_events (tenant_id, piece_id, event_type) VALUES (?, ?, 'received')", lookupOnly, lp);
            exec(c, "INSERT INTO piece_events (tenant_id, piece_id, event_type, metadata) VALUES (?, ?, 'pack', " +
                    "'{\"event\":\"lookup_exchange_dispatch\"}'::jsonb)", lookupOnly, lp);

            reprint = tenant(c, "reprint");
            UUID open = receipt(c, reprint, "open", "print_stick");
            exec(c, "INSERT INTO label_reprints (tenant_id, receipt_id, piece_count) VALUES (?, ?, 2)", reprint, open);

            countOnly = tenant(c, "countonly");
            receipt(c, countOnly, "finalized", "count_only");
        }

        MigrateResult r2 = FlywayTestSupport.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration").target("220").load().migrate();
        assertThat(r2.success).isTrue();
        assertThat(r2.migrationsExecuted).as("V220 only").isEqualTo(1);

        try (Connection c = conn()) {
            assertPrefs(c, dismissed,    "hidden", false, false);
            assertPrefs(c, withReceipt,  "hidden", false, true);    // finalized print_stick → label printed too
            assertPrefs(c, reversedOnly, "full",   false, false);   // a reversed receipt doesn't count
            assertPrefs(c, connected,    "full",   false, false);   // connected but inactive → sees the new card
            assertPrefs(c, waybill,      "full",   true,  false);
            assertPrefs(c, packed,       "full",   true,  false);
            assertPrefs(c, lookupOnly,   "full",   false, false);   // the Lookup exchange's pack event isn't packing
            assertPrefs(c, reprint,      "full",   false, true);    // an open receipt doesn't hide the card
            assertPrefs(c, countOnly,    "hidden", false, false);   // count only prints nothing
        }
    }

    @SuppressWarnings("unchecked")
    private void assertPrefs(Connection c, UUID tenant, String card, boolean pickPackChosen, boolean testLabelPrinted)
            throws Exception {
        String raw;
        try (PreparedStatement ps = c.prepareStatement("SELECT onboarding_prefs::text FROM tenants WHERE id = ?")) {
            ps.setObject(1, tenant);
            try (ResultSet rs = ps.executeQuery()) { rs.next(); raw = rs.getString(1); }
        }
        Map<String, Object> m = mapper.readValue(raw, Map.class);
        assertThat(m).as("tenant prefs %s", raw)
            .containsEntry("card", card)
            .containsEntry("pickPackChosen", pickPackChosen)
            .containsEntry("testLabelPrinted", testLabelPrinted)
            .doesNotContainKeys("skipped", "labelingPace");
    }

    private static UUID tenant(Connection c, String tag) throws Exception {
        UUID id = UUID.randomUUID();
        exec(c, "INSERT INTO tenants (id, name) VALUES (?, ?)", id, "BF " + tag);
        exec(c, "INSERT INTO locations (tenant_id, name, type, is_default, is_fulfillment) " +
                "VALUES (?, 'Main Warehouse', 'warehouse', true, true)", id);
        return id;
    }

    private static UUID receipt(Connection c, UUID tenant, String status, String mode) throws Exception {
        UUID id = UUID.randomUUID();
        exec(c, "INSERT INTO receipts (id, tenant_id, status, mode, finalized_at) VALUES (?, ?, ?, ?, " +
                "CASE WHEN ? = 'open' THEN NULL ELSE now() END)", id, tenant, status, mode, status);
        return id;
    }

    private static String piece(Connection c, UUID tenant) throws Exception {
        UUID store = UUID.randomUUID(), product = UUID.randomUUID(), variant = UUID.randomUUID();
        exec(c, "INSERT INTO stores (id, tenant_id, platform, shop_domain, status) VALUES (?, ?, 'shopify', ?, 'disconnected')",
            store, tenant, "bfp-" + store + ".myshopify.com");
        exec(c, "INSERT INTO products (id, tenant_id, store_id, external_id, title, status) VALUES (?, ?, ?, 'P', 'T', 'active')",
            product, tenant, store);
        exec(c, "INSERT INTO variants (id, tenant_id, product_id, external_id, title) VALUES (?, ?, ?, 'V', 'V')",
            variant, tenant, product);
        String id = "BF" + UUID.randomUUID().toString().replace("-", "").substring(0, 18).toUpperCase();
        exec(c, "INSERT INTO pieces (id, tenant_id, variant_id, barcode, short_code, status) " +
                "VALUES (?, ?, ?, ?, ?, 'available'::piece_status)", id, tenant, variant, "BC-" + id, id);
        return id;
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
}
