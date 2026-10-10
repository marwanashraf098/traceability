package com.traceability;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

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
 * P4a Guard 2 — merchant_orders (V163) is "SELECT * FROM orders WHERE external_id NOT LIKE 'internal:portal:%'",
 * and Postgres freezes the * when the view is created. A migration that adds an orders column
 * without re-creating the view leaves merchant code unable to see it; this test catches that
 * (CLAUDE.md rule: re-create merchant_orders in the same migration).
 *
 * Also pins what the rest of P4a relies on: security_invoker (RLS on orders applies as app_user),
 * app_user's privileges equal its privileges on orders, a portal row is invisible and
 * un-updatable through the view, the view's predicate (external_id, never origin — origin is the last
 * column of a wide row and filtering on it cost the Orders list 10-15%), and the V163 constraints.
 */
@Testcontainers
class MerchantOrdersViewColumnsTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("traceability_test")
                    .withUsername("postgres")
                    .withPassword("postgres");

    @BeforeAll
    static void migrate() {
        Flyway.configure()
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

    @Test
    void viewColumnsEqualOrdersColumns_inOrder() throws Exception {
        try (Connection c = owner()) {
            List<String> table = columns(c, "orders");
            assertThat(table).contains("origin text", "shopify_order_gid text", "portal_delivery jsonb");
            assertThat(columns(c, "merchant_orders"))
                .as("merchant_orders must expose exactly the columns of orders. A migration that "
                    + "changed an orders column must re-create merchant_orders in the same migration.")
                .containsExactlyElementsOf(table);
        }
    }

    /** Nobody may silently swap the predicate back to origin (or anything else). */
    @Test
    void viewFiltersOnTheExternalIdPrefix() throws Exception {
        try (Connection c = owner(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT pg_get_viewdef('public.merchant_orders'::regclass, true)")) {
            rs.next();
            String where = rs.getString(1).replaceAll("\\s+", " ");
            where = where.substring(where.toUpperCase().lastIndexOf("WHERE"));
            assertThat(where)
                .as("merchant_orders must filter on the external_id prefix only (V163; see CLAUDE.md)")
                .isEqualTo("WHERE external_id !~~ 'internal:portal:%'::text;");
        }
    }

    @Test
    void securityInvoker_andAppUserPrivilegesMatchOrders() throws Exception {
        try (Connection c = owner(); Statement s = c.createStatement()) {
            try (ResultSet rs = s.executeQuery(
                    "SELECT reloptions::text FROM pg_class WHERE oid = 'public.merchant_orders'::regclass")) {
                rs.next();
                assertThat(rs.getString(1)).contains("security_invoker=true");
            }
            for (String priv : List.of("SELECT", "INSERT", "UPDATE", "DELETE")) {
                try (ResultSet rs = s.executeQuery(
                        "SELECT has_table_privilege('app_user', 'orders', '" + priv + "'), " +
                        "       has_table_privilege('app_user', 'merchant_orders', '" + priv + "')")) {
                    rs.next();
                    assertThat(rs.getBoolean(2)).as(priv).isEqualTo(rs.getBoolean(1));
                }
            }
        }
    }

    @Test
    void portalRowIsInvisibleAndUnwritableThroughTheView_underAppUserRls() throws Exception {
        UUID tenant = UUID.randomUUID(), other = UUID.randomUUID();
        UUID shopify = UUID.randomUUID(), portal = UUID.randomUUID(), otherTenants = UUID.randomUUID();
        try (Connection c = owner(); Statement s = c.createStatement()) {
            for (UUID t : List.of(tenant, other)) {
                s.execute("INSERT INTO tenants (id, name) VALUES ('" + t + "', 'v163 " + t + "')");
                s.execute("INSERT INTO stores (id, tenant_id, shop_domain) VALUES (gen_random_uuid(), '" + t
                    + "', 'v163-" + t + ".myshopify.com')");
            }
            insertOrder(s, shopify, tenant, "gid://shopify/Order/1", "#1001", "'shopify'", "NULL");
            insertOrder(s, portal, tenant, "internal:portal:" + UUID.randomUUID(), "#501", "'portal_pre_connect'",
                "'gid://shopify/Order/2'");
            insertOrder(s, otherTenants, other, "gid://shopify/Order/3", "#1001", "'shopify'", "NULL");

            // The owner (BYPASSRLS) still sees no portal row through the view — the WHERE, not RLS.
            assertThat(ids(s, "SELECT id FROM merchant_orders WHERE tenant_id = '" + tenant + "'"))
                .containsExactly(shopify);
        }

        try (Connection c = owner()) {
            c.setAutoCommit(false);
            try (Statement s = c.createStatement()) {
                s.execute("SET LOCAL ROLE app_user");
                s.execute("SET LOCAL app.current_tenant = '" + tenant + "'");
                assertThat(ids(s, "SELECT id FROM merchant_orders")).containsExactly(shopify);
                assertThat(ids(s, "SELECT id FROM orders")).containsExactlyInAnyOrder(shopify, portal);
                assertThat(s.executeUpdate("UPDATE merchant_orders SET locked_at = now() WHERE id = '" + portal + "'"))
                    .as("a portal row cannot be updated through the view").isZero();
                assertThat(s.executeUpdate("UPDATE merchant_orders SET locked_at = now() WHERE id = '" + otherTenants + "'"))
                    .as("RLS still applies through the view (security_invoker)").isZero();
                assertThat(s.executeUpdate("UPDATE merchant_orders SET locked_at = now() WHERE id = '" + shopify + "'"))
                    .isEqualTo(1);
            }
            c.rollback();
        }
    }

    @Test
    void v163Constraints() throws Exception {
        UUID tenant = UUID.randomUUID();
        try (Connection c = owner(); Statement s = c.createStatement()) {
            s.execute("INSERT INTO tenants (id, name) VALUES ('" + tenant + "', 'v163 constraints')");
            s.execute("INSERT INTO stores (id, tenant_id, shop_domain) VALUES (gen_random_uuid(), '" + tenant
                + "', 'v163c-" + tenant + ".myshopify.com')");
            // origin and the internal:portal: prefix are equivalent BOTH ways
            assertThatThrownBy(() -> insertOrder(s, UUID.randomUUID(), tenant, "gid://shopify/Order/9", "#9",
                "'portal_pre_connect'", "'gid://shopify/Order/9'"))
                .as("a portal-origin row without the prefix").hasMessageContaining("orders_portal_identity_check");
            assertThatThrownBy(() -> insertOrder(s, UUID.randomUUID(), tenant, "internal:portal:" + UUID.randomUUID(),
                "#9", "'shopify'", "NULL"))
                .as("a shopify-origin row with an internal:portal: id").hasMessageContaining("orders_portal_identity_check");
            // a portal row needs its Shopify GID
            assertThatThrownBy(() -> insertOrder(s, UUID.randomUUID(), tenant, "internal:portal:" + UUID.randomUUID(),
                "#9", "'portal_pre_connect'", "NULL"))
                .hasMessageContaining("orders_portal_gid_check");
            assertThatThrownBy(() -> insertOrder(s, UUID.randomUUID(), tenant, "x:1", "#9", "'manual'", "NULL"))
                .hasMessageContaining("orders_origin_check");

            // one portal row per Shopify order per tenant
            insertOrder(s, UUID.randomUUID(), tenant, "internal:portal:" + UUID.randomUUID(), "#10",
                "'portal_pre_connect'", "'gid://shopify/Order/10'");
            assertThatThrownBy(() -> insertOrder(s, UUID.randomUUID(), tenant, "internal:portal:" + UUID.randomUUID(),
                "#10", "'portal_pre_connect'", "'gid://shopify/Order/10'"))
                .hasMessageContaining("ux_orders_tenant_shopify_order_gid");
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
