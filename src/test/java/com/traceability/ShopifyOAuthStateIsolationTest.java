package com.traceability;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Isolation (V87): app_user has no direct SELECT on shopify_oauth_state — only INSERT
 * survives the REVOKE; consume and the cleanup sweep both go through SECURITY DEFINER
 * functions (consume_shopify_oauth_state, purge_expired_shopify_oauth_state).
 *
 * Unlike ShopifyOAuthDay1Test/Day2Test/Day3Test — whose @DynamicPropertySource overrides
 * spring.datasource.username to plain postgres, so the entire app under test (including
 * ShopifyOAuthService itself) runs BYPASSRLS there — this test opens a genuine app_user
 * connection, mirroring PasswordResetTest's V86 isolation tests. Those Day tests prove
 * the reroute preserved OAuth handshake behavior; only this test proves the REVOKE
 * actually holds, because it's the only one that runs as app_user at all.
 *
 * webEnvironment = NONE: no HTTP surface needed, just Flyway + TestSetup's app_user
 * password (ApplicationReadyEvent-driven, fires regardless of web environment) + a raw
 * JDBC connection.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
class ShopifyOAuthStateIsolationTest {

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

    @Autowired JdbcTemplate jdbc;

    @Test
    void appUser_directSelectOnShopifyOauthState_isDenied() throws Exception {
        // Seed a row so a permissive grant would return a non-empty result — the assertion
        // is on the SQLException itself, not row count, but a real row makes this an
        // honest test rather than "SELECT from an empty table happens to error."
        UUID tenantId = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, 'Isolation Test Tenant')", tenantId);
        jdbc.update("INSERT INTO shopify_oauth_state (nonce, tenant_id, shop_domain) VALUES " +
                "('isolation-test-nonce', ?, 'isolation-test.myshopify.com')", tenantId);

        try (Connection conn = DriverManager.getConnection(POSTGRES.getJdbcUrl(), "app_user", "testpw")) {
            conn.setAutoCommit(false);
            conn.prepareStatement(
                    "SELECT set_config('app.current_tenant', '" + tenantId + "', true)").execute();
            assertThatThrownBy(() -> conn.createStatement().executeQuery("SELECT COUNT(*) FROM shopify_oauth_state"))
                    .as("app_user must not be able to SELECT shopify_oauth_state directly")
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("permission denied");
        }
    }

    @Test
    void appUser_directInsertOnShopifyOauthState_stillWorks() throws Exception {
        // Positive control alongside the negative above: confirm the REVOKE didn't
        // accidentally also take INSERT — issuance (ShopifyOAuthService.initiateOAuth)
        // must keep working exactly as before.
        try (Connection conn = DriverManager.getConnection(POSTGRES.getJdbcUrl(), "app_user", "testpw")) {
            conn.setAutoCommit(false);
            int rows = conn.prepareStatement(
                    "INSERT INTO shopify_oauth_state (nonce, tenant_id, shop_domain) VALUES " +
                    "('positive-control-nonce', NULL, 'positive-control.myshopify.com')")
                    .executeUpdate();
            assertThat(rows).as("app_user must still be able to INSERT shopify_oauth_state").isEqualTo(1);
            conn.rollback();
        }
    }
}
