package com.traceability;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V99 — corrects orders.address polluted by a CRP's merchant dropOffAddress.
 *
 * Fixtures follow the stored production CRP shape: dropOffAddress = merchant,
 * pickupAddress = customer, separate top-level sender (merchant), receiver = customer,
 * bare numeric tracking numbers. Polluted addresses are seeded in the exact shape
 * populateConsigneePiiFromRaw() wrote (only present keys, untrimmed).
 */
@Testcontainers
class CrpAddressBackfillTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("traceability_test")
                    .withUsername("postgres")
                    .withPassword("postgres");

    private static final String MERCHANT_ADDR_JSON =
        "{\"firstLine\":\"Merchant Warehouse, Plot 7\",\"city\":\"New Cairo\"," +
        "\"zone\":\"Fifth Settlement\",\"district\":\"Industrial Zone\"}";

    @Test
    void v99_correctsOnlyPollutedBostaOrders_idempotent() throws Exception {
        MigrateResult r1 = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration").target("98").load().migrate();
        assertThat(r1.success).isTrue();

        UUID tenant = UUID.randomUUID(), store = UUID.randomUUID();
        UUID withFwd, noFwd, correct, redacted, cityMismatch, shopifySourced;

        try (Connection c = conn()) {
            exec(c, "INSERT INTO tenants (id, name) VALUES (?, 'Jumi-like')", tenant);
            exec(c, "INSERT INTO stores (id, tenant_id, platform, shop_domain, status) " +
                    "VALUES (?, ?, 'shopify', 'v99.myshopify.com', 'disconnected')", store, tenant);

            // 1. Polluted, has a forward leg → forward dropOffAddress (no district → key omitted).
            withFwd = order(c, tenant, store, MERCHANT_ADDR_JSON, "bosta", false);
            leg(c, tenant, withFwd, "forward", "9730600001",
                "{\"type\":{\"code\":10,\"value\":\"Send\"},\"receiver\":{\"fullName\":\"Omar Buyer\",\"phone\":\"+201055550001\"}," +
                "\"dropOffAddress\":{\"firstLine\":\"5 Nile St\",\"city\":{\"_id\":\"c1\",\"name\":\"Cairo\"}," +
                "\"zone\":{\"_id\":\"z1\",\"name\":\"Zamalek\"}}}");
            leg(c, tenant, withFwd, "return", "9730639058", crpRaw("22 Other Pickup Rd", "Giza"));

            // 2. Polluted, no forward leg → CRP pickupAddress.
            noFwd = order(c, tenant, store, MERCHANT_ADDR_JSON, "bosta", false);
            leg(c, tenant, noFwd, "return", "6136538746", crpRaw("12 Customer St, Apt 4", "Giza"));

            // 3. Correct customer address + a CRP → untouched.
            correct = order(c, tenant, store,
                "{\"firstLine\":\"12 Customer St, Apt 4\",\"city\":\"Giza\"}", "bosta", false);
            leg(c, tenant, correct, "return", "6136500003", crpRaw("12 Customer St, Apt 4", "Giza"));

            // 4. Redacted → untouched (even though polluted).
            redacted = order(c, tenant, store, MERCHANT_ADDR_JSON, "bosta", true);
            leg(c, tenant, redacted, "return", "6136500004", crpRaw("1 Redacted Rd", "Giza"));

            // 5. firstLine matches the merchant but city does not → untouched.
            cityMismatch = order(c, tenant, store,
                "{\"firstLine\":\"Merchant Warehouse, Plot 7\",\"city\":\"Alexandria\"}", "bosta", false);
            leg(c, tenant, cityMismatch, "return", "6136500005", crpRaw("9 Sea Rd", "Alexandria"));

            // 6. Polluted but not Bosta-sourced → untouched (pii_source guard).
            shopifySourced = order(c, tenant, store, MERCHANT_ADDR_JSON, "shopify", false);
            leg(c, tenant, shopifySourced, "return", "6136500006", crpRaw("3 Shop Rd", "Giza"));
        }

        MigrateResult r2 = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                // Pinned: later migrations must not change this V99-only count.
                .locations("classpath:db/migration").target("99").load().migrate();
        assertThat(r2.success).isTrue();
        assertThat(r2.migrationsExecuted).as("V99 only").isEqualTo(1);

        try (Connection c = conn()) {
            assertThat(addressEquals(c, withFwd,
                "{\"firstLine\":\"5 Nile St\",\"city\":\"Cairo\",\"zone\":\"Zamalek\"}"))
                .as("forward dropOffAddress, only present keys (no district key)").isTrue();
            assertThat(addressEquals(c, noFwd,
                "{\"firstLine\":\"12 Customer St, Apt 4\",\"city\":\"Giza\",\"zone\":\"Dokki\",\"district\":\"Mesaha\"}"))
                .as("CRP pickupAddress (the customer)").isTrue();
            assertThat(addressEquals(c, correct, "{\"firstLine\":\"12 Customer St, Apt 4\",\"city\":\"Giza\"}")).isTrue();
            assertThat(addressEquals(c, redacted, MERCHANT_ADDR_JSON)).as("redacted untouched").isTrue();
            assertThat(addressEquals(c, cityMismatch,
                "{\"firstLine\":\"Merchant Warehouse, Plot 7\",\"city\":\"Alexandria\"}")).isTrue();
            assertThat(addressEquals(c, shopifySourced, MERCHANT_ADDR_JSON)).as("non-bosta untouched").isTrue();

            // Address only: name/phone/pii_source/pii_redacted_at unchanged on the corrected rows.
            for (UUID id : new UUID[]{withFwd, noFwd}) {
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT customer_name, customer_phone, pii_source, pii_redacted_at FROM orders WHERE id = ?")) {
                    ps.setObject(1, id);
                    try (ResultSet rs = ps.executeQuery()) {
                        rs.next();
                        assertThat(rs.getString(1)).isEqualTo("Seeded Name");
                        assertThat(rs.getString(2)).isEqualTo("01000000000");
                        assertThat(rs.getString(3)).isEqualTo("bosta");
                        assertThat(rs.getObject(4)).isNull();
                    }
                }
            }

            // Re-applying the V99 SQL changes nothing.
            String v99 = new ClassPathResource("db/migration/V99__crp_address_backfill.sql")
                .getContentAsString(StandardCharsets.UTF_8);
            String before = allAddresses(c, tenant);
            int updated;
            try (Statement st = c.createStatement()) {
                updated = st.executeUpdate(v99);
            }
            assertThat(updated).as("idempotent — no row matches the predicate any more").isZero();
            assertThat(allAddresses(c, tenant)).isEqualTo(before);
        }
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    /** Stored production CRP shape: pickupAddress = customer, dropOffAddress = merchant. */
    private static String crpRaw(String customerFirstLine, String customerCity) {
        return "{\"type\":{\"code\":25,\"value\":\"Customer Return Pickup\"}," +
            "\"receiver\":{\"fullName\":\"Mona Customer\",\"phone\":\"+201011112222\"}," +
            "\"sender\":{\"name\":\"Merchant Co\",\"phone\":\"+201099998888\"}," +
            "\"pickupAddress\":{\"firstLine\":\"" + customerFirstLine + "\"," +
            "\"city\":{\"_id\":\"0064Qb0OgcA\",\"name\":\"" + customerCity + "\"}," +
            "\"zone\":{\"_id\":\"CHZJGpbyab\",\"name\":\"Dokki\"}," +
            "\"district\":{\"_id\":\"rdvFQsb5Qdl\",\"name\":\"Mesaha\"}}," +
            "\"dropOffAddress\":{\"firstLine\":\"Merchant Warehouse, Plot 7\"," +
            "\"city\":{\"_id\":\"FceDyHXwpSYYF9zGW\",\"name\":\"New Cairo\"}," +
            "\"zone\":{\"_id\":\"g3jl3V8FMN\",\"name\":\"Fifth Settlement\"}," +
            "\"district\":{\"_id\":\"YFbGMSvcx2j\",\"name\":\"Industrial Zone\"}}," +
            "\"returnAddress\":{\"firstLine\":\"Merchant Warehouse, Plot 7\",\"businessLocationId\":\"P7J9dkmaB\"}," +
            "\"returnSpecs\":{\"packageDetails\":{\"itemsCount\":1,\"description\":\"Hoodie\"}}}";
    }

    private UUID order(Connection c, UUID tenant, UUID store, String addressJson, String piiSource,
                       boolean redacted) throws Exception {
        UUID id = UUID.randomUUID();
        exec(c, "INSERT INTO orders (id, tenant_id, store_id, external_id, number, status, payment_method, placed_at, " +
                "    customer_name, customer_phone, address, pii_source, pii_redacted_at) " +
                "VALUES (?, ?, ?, ?, ?, 'delivered'::order_status, 'cod'::order_payment_method, now(), " +
                "    'Seeded Name', '01000000000', ?::jsonb, ?, CASE WHEN ? THEN now() END)",
                id, tenant, store, "gid://shopify/Order/" + id, "#3853" + id.toString().substring(0, 6),
                addressJson, piiSource, redacted);
        return id;
    }

    private void leg(Connection c, UUID tenant, UUID order, String leg, String tracking, String raw) throws Exception {
        exec(c, "INSERT INTO shipments (tenant_id, order_id, provider, tracking_number, internal_state, shipment_leg, raw) " +
                "VALUES (?, ?, 'bosta', ?, ?::shipment_internal_state, ?, ?::jsonb)",
                tenant, order, tracking, "return".equals(leg) ? "returned" : "delivered", leg, raw);
    }

    private boolean addressEquals(Connection c, UUID order, String expectedJson) throws Exception {
        try (PreparedStatement ps = c.prepareStatement("SELECT address = ?::jsonb FROM orders WHERE id = ?")) {
            ps.setString(1, expectedJson);
            ps.setObject(2, order);
            try (ResultSet rs = ps.executeQuery()) { rs.next(); return rs.getBoolean(1); }
        }
    }

    private String allAddresses(Connection c, UUID tenant) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT id, address::text FROM orders WHERE tenant_id = ? ORDER BY id")) {
            ps.setObject(1, tenant);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) sb.append(rs.getString(1)).append('=').append(rs.getString(2)).append('\n');
            }
        }
        return sb.toString();
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
