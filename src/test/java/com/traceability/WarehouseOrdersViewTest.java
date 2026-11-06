package com.traceability;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Shopify-native returns S3a (V216 / V217) — the two order views and what they rely on:
 * <ul>
 *   <li>merchant_orders hides portal pre-connect rows AND Shopify-native exchange shadow orders; warehouse_orders hides
 *       only portal rows (it is what merchant_orders was until V216). Both: every orders column in order,
 *       security_invoker, app_user's privileges equal to its privileges on orders, predicates on the external_id
 *       prefixes only (never origin).</li>
 *   <li>Under app_user + RLS: a shadow is invisible and un-updatable through merchant_orders, visible and updatable
 *       through warehouse_orders — for its own tenant only (cross-tenant with a same-tenant control).</li>
 *   <li>origin 'shopify_exchange' ⇔ the 'internal:shopify-exchange:' prefix, both ways.</li>
 *   <li>V217: Overview's partial index carries both prefixes, is valid, keeps the order count index-only, and the
 *       migration's SQL re-runs cleanly (the `flyway repair` path).</li>
 * </ul>
 */
@Testcontainers
class WarehouseOrdersViewTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            TestPostgres.newContainer()
                    .withDatabaseName("traceability_test")
                    .withUsername("postgres")
                    .withPassword("postgres");

    @BeforeAll
    static void migrate() {
        FlywayTestSupport.configure()
            .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .locations("classpath:db/migration")
            .load()
            .migrate();
    }

    private static Connection owner() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static List<String> columns(Connection c, String relation) throws SQLException {
        List<String> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT column_name || ' ' || data_type FROM information_schema.columns " +
                "WHERE table_schema = 'public' AND table_name = ? ORDER BY ordinal_position")) {
            ps.setString(1, relation);
            try (ResultSet rs = ps.executeQuery()) { while (rs.next()) out.add(rs.getString(1)); }
        }
        return out;
    }

    private static String where(Statement s, String view) throws SQLException {
        try (ResultSet rs = s.executeQuery("SELECT pg_get_viewdef('public." + view + "'::regclass, true)")) {
            rs.next();
            String def = rs.getString(1).replaceAll("\\s+", " ");
            return def.substring(def.toUpperCase().lastIndexOf("WHERE"));
        }
    }

    @Test
    void bothViewsExposeEveryOrdersColumn_inOrder() throws Exception {
        try (Connection c = owner()) {
            List<String> table = columns(c, "orders");
            assertThat(columns(c, "merchant_orders")).containsExactlyElementsOf(table);
            assertThat(columns(c, "warehouse_orders")).containsExactlyElementsOf(table);
        }
    }

    @Test
    void predicatesAreTheExternalIdPrefixesOnly() throws Exception {
        try (Connection c = owner(); Statement s = c.createStatement()) {
            assertThat(where(s, "merchant_orders"))
                .isEqualTo("WHERE external_id !~~ 'internal:portal:%'::text AND external_id !~~ 'internal:shopify-exchange:%'::text;");
            assertThat(where(s, "warehouse_orders")).isEqualTo("WHERE external_id !~~ 'internal:portal:%'::text;");
        }
    }

    @Test
    void securityInvoker_andAppUserPrivilegesMatchOrders_onBothViews() throws Exception {
        try (Connection c = owner(); Statement s = c.createStatement()) {
            for (String view : List.of("merchant_orders", "warehouse_orders")) {
                try (ResultSet rs = s.executeQuery(
                        "SELECT reloptions::text FROM pg_class WHERE oid = 'public." + view + "'::regclass")) {
                    rs.next();
                    assertThat(rs.getString(1)).as(view).contains("security_invoker=true");
                }
                for (String priv : List.of("SELECT", "INSERT", "UPDATE", "DELETE")) {
                    try (ResultSet rs = s.executeQuery(
                            "SELECT has_table_privilege('app_user', 'orders', '" + priv + "'), " +
                            "       has_table_privilege('app_user', '" + view + "', '" + priv + "')")) {
                        rs.next();
                        assertThat(rs.getBoolean(2)).as(view + " " + priv).isEqualTo(rs.getBoolean(1));
                    }
                }
            }
        }
    }

    @Test
    void shadowRow_hiddenFromMerchantOrders_shownInWarehouseOrders_ownTenantOnly_underAppUserRls() throws Exception {
        UUID tenant = UUID.randomUUID(), other = UUID.randomUUID();
        UUID normal = UUID.randomUUID(), exchange = UUID.randomUUID(), portal = UUID.randomUUID();
        UUID shadow = UUID.randomUUID(), otherShadow = UUID.randomUUID();
        try (Connection c = owner(); Statement s = c.createStatement()) {
            for (UUID t : List.of(tenant, other)) {
                s.execute("INSERT INTO tenants (id, name) VALUES ('" + t + "', 'v216 " + t + "')");
                s.execute("INSERT INTO stores (id, tenant_id, shop_domain) VALUES (gen_random_uuid(), '" + t
                    + "', 'v216-" + t + ".myshopify.com')");
            }
            insertOrder(s, normal, tenant, "gid://shopify/Order/1", "#1001", "'shopify'", "NULL");
            insertOrder(s, exchange, tenant, "internal:exchange:5550001", "EXC-5550001", "'shopify'", "NULL");
            insertOrder(s, portal, tenant, "internal:portal:" + UUID.randomUUID(), "#501", "'portal_pre_connect'",
                "'gid://shopify/Order/2'");
            insertOrder(s, shadow, tenant, "internal:shopify-exchange:" + UUID.randomUUID(), "#1001-X1",
                "'shopify_exchange'", "NULL");
            insertOrder(s, otherShadow, other, "internal:shopify-exchange:" + UUID.randomUUID(), "#1001-X1",
                "'shopify_exchange'", "NULL");

            // The owner (BYPASSRLS) gets the same split — the WHERE, not RLS.
            assertThat(ids(s, "SELECT id FROM merchant_orders WHERE tenant_id = '" + tenant + "'"))
                .containsExactlyInAnyOrder(normal, exchange);
            assertThat(ids(s, "SELECT id FROM warehouse_orders WHERE tenant_id = '" + tenant + "'"))
                .containsExactlyInAnyOrder(normal, exchange, shadow);
        }

        try (Connection c = owner()) {
            c.setAutoCommit(false);
            try (Statement s = c.createStatement()) {
                s.execute("SET LOCAL ROLE app_user");
                s.execute("SET LOCAL app.current_tenant = '" + tenant + "'");
                assertThat(ids(s, "SELECT id FROM merchant_orders")).containsExactlyInAnyOrder(normal, exchange);
                assertThat(ids(s, "SELECT id FROM warehouse_orders")).containsExactlyInAnyOrder(normal, exchange, shadow);
                assertThat(ids(s, "SELECT id FROM orders")).containsExactlyInAnyOrder(normal, exchange, portal, shadow);

                assertThat(s.executeUpdate("UPDATE merchant_orders SET locked_at = now() WHERE id = '" + shadow + "'"))
                    .as("a shadow cannot be updated through merchant_orders").isZero();
                assertThat(s.executeUpdate("UPDATE warehouse_orders SET locked_at = now() WHERE id = '" + shadow + "'"))
                    .as("same tenant: a shadow IS updated through warehouse_orders").isEqualTo(1);
                assertThat(s.executeUpdate("UPDATE warehouse_orders SET locked_at = now() WHERE id = '" + otherShadow + "'"))
                    .as("cross-tenant: RLS still applies through warehouse_orders (security_invoker)").isZero();
                assertThat(s.executeUpdate("UPDATE warehouse_orders SET locked_at = now() WHERE id = '" + portal + "'"))
                    .as("a portal row is never updated through warehouse_orders").isZero();
                assertThat(ids(s, "SELECT id FROM warehouse_orders WHERE id = '" + otherShadow + "'")).isEmpty();
            }
            c.rollback();
        }
    }

    @Test
    void shopifyExchangeOriginAndPrefixAreEquivalent_bothWays() throws Exception {
        UUID tenant = UUID.randomUUID();
        try (Connection c = owner(); Statement s = c.createStatement()) {
            s.execute("INSERT INTO tenants (id, name) VALUES ('" + tenant + "', 'v216 constraints')");
            s.execute("INSERT INTO stores (id, tenant_id, shop_domain) VALUES (gen_random_uuid(), '" + tenant
                + "', 'v216c-" + tenant + ".myshopify.com')");
            assertThatThrownBy(() -> insertOrder(s, UUID.randomUUID(), tenant, "gid://shopify/Order/9", "#9-X1",
                "'shopify_exchange'", "NULL"))
                .as("a shopify_exchange-origin row without the prefix")
                .hasMessageContaining("orders_shopify_exchange_identity_check");
            assertThatThrownBy(() -> insertOrder(s, UUID.randomUUID(), tenant,
                "internal:shopify-exchange:" + UUID.randomUUID(), "#9-X1", "'shopify'", "NULL"))
                .as("a shopify-origin row with the shadow prefix")
                .hasMessageContaining("orders_shopify_exchange_identity_check");
            // the three origins are the whole set
            assertThatThrownBy(() -> insertOrder(s, UUID.randomUUID(), tenant, "x:1", "#9", "'manual'", "NULL"))
                .hasMessageContaining("orders_origin_check");
            insertOrder(s, UUID.randomUUID(), tenant, "internal:shopify-exchange:" + UUID.randomUUID(), "#10-X1",
                "'shopify_exchange'", "NULL");
            // V163's portal pair is unchanged
            assertThatThrownBy(() -> insertOrder(s, UUID.randomUUID(), tenant, "internal:shopify-exchange:" + UUID.randomUUID(),
                "#11", "'portal_pre_connect'", "'gid://shopify/Order/11'"))
                .hasMessageContaining("_identity_check");
        }
    }

    /** V217: the partial index carries both prefixes, is valid, keeps Overview's count index-only; its SQL re-runs. */
    @Test
    void overviewIndex_bothPrefixes_valid_indexOnly_andRerunnable() throws Exception {
        try (Connection c = owner(); Statement s = c.createStatement()) {
            assertIndex(s);

            UUID tenant = UUID.randomUUID();
            s.execute("INSERT INTO tenants (id, name) VALUES ('" + tenant + "', 'v217')");
            s.execute("INSERT INTO stores (id, tenant_id, shop_domain) VALUES (gen_random_uuid(), '" + tenant
                + "', 'v217-" + tenant + ".myshopify.com')");
            // Enough rows for the planner to prefer the partial index; VACUUM sets the visibility map (index-only).
            s.execute("INSERT INTO orders (tenant_id, store_id, external_id, number, status, placed_at) " +
                "SELECT '" + tenant + "', st.id, 'gid://shopify/Order/v217-' || g, '#v217-' || g, 'new', " +
                "       now() - make_interval(hours => g) FROM stores st, generate_series(1, 3000) g " +
                "WHERE st.tenant_id = '" + tenant + "'");
            s.execute("VACUUM ANALYZE orders");
            s.execute("SET enable_seqscan = off");      // the question is whether an index-only plan EXISTS
            s.execute("SET enable_bitmapscan = off");
            StringBuilder plan = new StringBuilder();
            try (ResultSet rs = s.executeQuery("EXPLAIN SELECT placed_at FROM merchant_orders WHERE tenant_id = '"
                    + tenant + "' AND placed_at >= now() - interval '30 days'")) {
                while (rs.next()) plan.append(rs.getString(1)).append('\n');
            }
            assertThat(plan.toString()).as("Overview's order count over merchant_orders").contains(
                "Index Only Scan using orders_merchant_tenant_placed_at_idx");

            // The `flyway repair` path: running V217's SQL again leaves exactly the same single valid index.
            String sql = new String(Files.readAllBytes(Paths.get(
                "src/main/resources/db/migration/V217__merchant_orders_index_shadow.sql")));
            for (String stmt : sql.replaceAll("(?m)^--.*$", "").split(";")) {
                if (!stmt.isBlank()) s.execute(stmt);
            }
            assertIndex(s);
        }
    }

    private static void assertIndex(Statement s) throws SQLException {
        try (ResultSet rs = s.executeQuery(
                "SELECT i.indisvalid, pg_get_expr(i.indpred, i.indrelid) FROM pg_index i " +
                "JOIN pg_class c ON c.oid = i.indexrelid WHERE c.relname = 'orders_merchant_tenant_placed_at_idx'")) {
            assertThat(rs.next()).as("the index exists").isTrue();
            assertThat(rs.getBoolean(1)).as("valid").isTrue();
            assertThat(rs.getString(2)).contains("internal:portal:%").contains("internal:shopify-exchange:%");
        }
        try (ResultSet rs = s.executeQuery("SELECT count(*) FROM pg_class WHERE relname LIKE 'orders_merchant_tenant_placed_at%'")) {
            rs.next();
            assertThat(rs.getInt(1)).as("no leftover build index").isEqualTo(1);
        }
    }

    private static void insertOrder(Statement s, UUID id, UUID tenant, String externalId, String number,
                                    String origin, String gid) throws SQLException {
        s.execute("INSERT INTO orders (id, tenant_id, store_id, external_id, number, status, origin, shopify_order_gid) " +
            "SELECT '" + id + "', '" + tenant + "', st.id, '" + externalId + "', '" + number + "', 'new', " +
            origin + ", " + gid + " FROM stores st WHERE st.tenant_id = '" + tenant + "' LIMIT 1");
    }

    private static List<UUID> ids(Statement s, String sql) throws SQLException {
        List<UUID> out = new ArrayList<>();
        try (ResultSet rs = s.executeQuery(sql)) { while (rs.next()) out.add(rs.getObject(1) instanceof UUID u ? u : null); }
        return out;
    }
}
