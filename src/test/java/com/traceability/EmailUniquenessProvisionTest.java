package com.traceability;

import com.traceability.integrations.shopify.ShopifyGateway;
import com.traceability.integrations.shopify.ShopifyImportJob;
import com.traceability.integrations.shopify.ShopifyOAuthService;
import com.traceability.notifications.EmailGateway;
import com.traceability.tenancy.TenantAwareDataSource;
import com.traceability.tenancy.TenantContext;
import org.jobrunr.scheduling.JobScheduler;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.dao.IncorrectResultSizeDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Integration tests for V42 — UNIQUE(users.email) constraint.
 *
 *   eu2 — an existing owner's login is unbroken: auth_lookup_user() returns exactly one row for
 *         their email, so AuthService.lookupUser()'s queryForObject() succeeds, not throws.
 *         Assertion runs via app_user (RLS enforced).
 *
 *   eu3 — regression proof: without the constraint, two users sharing an email cause
 *         queryForObject() to throw IncorrectResultSizeDataAccessException (500 in prod,
 *         not 401). The test drops the constraint, inserts duplicates, asserts the
 *         exception, then restores the constraint — proving the constraint is the fix.
 *
 *   eu_prod — a cold Shopify-first OAuth install creates nothing, even when the shop's email
 *         would collide with an existing owner.
 *
 * (eu1 tested the rollback of provision_tenant_from_shopify itself; that function was dropped in
 * V147 — Build D — and the test with it. Embedded signup's duplicate email: EmbeddedOnboardingTest.s5.)
 *
 * Constraint name "users_email_unique" matches V42__users_email_unique.sql and
 * EmbeddedOnboardingService.USERS_EMAIL_CONSTRAINT (same literal, must stay in sync).
 *
 * All RLS-scope assertions use app_user via TenantContext.runAs().
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class EmailUniquenessProvisionTest {

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

    // postgres role — BYPASSRLS — for cross-tenant counts and schema DDL in eu3.
    @Autowired JdbcTemplate jdbc;

    // Real service under test — the cold-install path in eu_prod.
    @Autowired ShopifyOAuthService oauthService;

    @MockBean ShopifyGateway  shopifyGateway;
    @MockBean JobScheduler    jobScheduler;
    @MockBean ShopifyImportJob shopifyImportJob;
    @MockBean EmailGateway    emailGateway;

    // app_user — RLS enforced — for assertions that must be tenant-scoped.
    private JdbcTemplate        appUserJdbc;
    private TransactionTemplate appUserTx;

    // Created in @BeforeAll; used by eu2.
    private UUID   tenantA;
    private String collisionEmail;
    private String shopA;

    @BeforeAll
    void setup() {
        DriverManagerDataSource rawDs =
                new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "app_user", "testpw");
        TenantAwareDataSource appUserDs = new TenantAwareDataSource(rawDs);
        appUserJdbc = new JdbcTemplate(appUserDs);
        appUserTx   = new TransactionTemplate(new DataSourceTransactionManager(appUserDs));

        collisionEmail = "collision@eu-test.local";
        shopA          = "eu-shop-a.myshopify.com";

        // The existing owner (tenant + owner + store) that eu2 / eu_prod reason about.
        tenantA = jdbc.queryForObject("INSERT INTO tenants (name) VALUES ('Shop A') RETURNING id", UUID.class);
        jdbc.update("INSERT INTO users (tenant_id, name, email, role) VALUES (?, 'Owner A', ?, 'owner')",
            tenantA, collisionEmail);
        jdbc.update("INSERT INTO stores (tenant_id, shop_domain, platform, access_token_encrypted, status) " +
            "VALUES (?, ?, 'shopify', 'dummy-enc-token-a', 'connected')", tenantA, shopA);
    }

    // ── eu2: original tenant's login is unbroken after the collision attempt ─────────────────────

    /**
     * auth_lookup_user() must return exactly one row for the collision email.
     * queryForObject() in AuthService.lookupUser() throws IncorrectResultSizeDataAccessException
     * (HTTP 500, not 401) when it gets more than one row — so exactly-one is the login guarantee.
     *
     * Assertion runs via app_user (RLS enforced) inside TenantContext.runAs(tenantA) to prove
     * the row is visible under the correct GUC context, matching the request-path scenario.
     */
    @Test
    @Order(2)
    void eu2_originalUserLoginUnbroken_authLookupReturnsExactlyOneRow() {
        // Call auth_lookup_user via app_user datasource inside the correct tenant GUC.
        // The function is SECURITY DEFINER — it scans all tenants regardless of GUC —
        // but running it through TenantAwareDataSource proves the call path used in production.
        List<Map<String, Object>> rows = TenantContext.runAs(tenantA, () ->
            appUserTx.execute(s ->
                appUserJdbc.queryForList(
                    "SELECT user_id, tenant_id FROM auth_lookup_user(?)",
                    collisionEmail)));

        assertThat(rows)
            .as("auth_lookup_user must return exactly 1 row — more would cause 500 at login")
            .hasSize(1);

        UUID returnedTenant = (UUID) rows.get(0).get("tenant_id");
        assertThat(returnedTenant)
            .as("returned tenant_id must be tenant-a's")
            .isEqualTo(tenantA);

        // Verify queryForObject itself doesn't throw — this is the exact call in AuthService.lookupUser().
        String userId = TenantContext.runAs(tenantA, () ->
            appUserTx.execute(s ->
                appUserJdbc.queryForObject(
                    "SELECT user_id::text FROM auth_lookup_user(?)",
                    String.class, collisionEmail)));

        assertThat(userId)
            .as("queryForObject on auth_lookup_user must succeed (not throw IncorrectResultSize)")
            .isNotNull();
    }

    // ── eu3: regression proof — duplicates cause IncorrectResultSizeDataAccessException ─────────

    /**
     * Without UNIQUE(email), two users sharing the same email cause AuthService.lookupUser()
     * to fail with HTTP 500 (not 401) because only EmptyResultDataAccessException is caught.
     *
     * This test proves that behaviour by temporarily dropping the constraint, inserting a
     * duplicate, asserting the exception, then restoring the constraint. If a future migration
     * removes UNIQUE(email), this test turns red — proving the constraint is load-bearing.
     *
     * Constraint name "users_email_unique" must match V42__users_email_unique.sql
     * and EmbeddedOnboardingService.USERS_EMAIL_CONSTRAINT.
     */
    @Test
    @Order(3)
    void eu3_withoutConstraint_duplicateEmailCausesIncorrectResultSizeException() {
        String eu3Email  = "eu3-dup@test.local";
        UUID   eu3Tenant = UUID.fromString(jdbc.queryForObject(
            "INSERT INTO tenants (name) VALUES ('EU3Tenant') RETURNING id::text", String.class));

        // Drop constraint to simulate the pre-V42 state.
        jdbc.execute("ALTER TABLE users DROP CONSTRAINT users_email_unique");
        try {
            jdbc.update(
                "INSERT INTO users (tenant_id, name, email, role) VALUES (?, 'EU3-U1', ?, 'worker')",
                eu3Tenant, eu3Email);
            jdbc.update(
                "INSERT INTO users (tenant_id, name, email, role) VALUES (?, 'EU3-U2', ?, 'worker')",
                eu3Tenant, eu3Email);

            // auth_lookup_user returns both rows — queryForObject() throws.
            // This is the exact call shape in AuthService.lookupUser().
            assertThatThrownBy(() ->
                jdbc.queryForObject(
                    "SELECT user_id::text FROM auth_lookup_user(?)", String.class, eu3Email))
                .isInstanceOf(IncorrectResultSizeDataAccessException.class)
                .hasMessageContaining("2");
        } finally {
            // Always restore the constraint, even if assertions above fail.
            jdbc.update("DELETE FROM users  WHERE email = ?", eu3Email);
            jdbc.update("DELETE FROM tenants WHERE id = ?",   eu3Tenant);
            jdbc.execute(
                "ALTER TABLE users ADD CONSTRAINT users_email_unique UNIQUE (email)");
        }
    }

    // ── eu_prod: production path — cold install never attempts provisioning ─────────────────────

    /**
     * A cold Shopify-first OAuth install (no owning tenant) creates nothing and never fetches the
     * shop's owner email — even when that email would collide with an existing owner. Merchants
     * without an account sign up inside the embedded app instead (Build D).
     */
    @Test
    @Order(4)
    void eu_prod_coldInstall_neverAttemptsProvisioning_evenWithWouldBeEmailCollision() {
        // shopC has never been seen — resolveShopOwner returns null → Path-2 → cold install.
        String shopC = "eu-shop-c.myshopify.com";
        ShopifyGateway.TokenResponse fakeTokens =
            new ShopifyGateway.TokenResponse("shpat_fake_c", "shprt_fake_c", 3600L, 7776000L, null);

        when(shopifyGateway.exchangeCode(eq(shopC), eq("fake-code-c"))).thenReturn(fakeTokens);

        int tenantsBefore = countAll("tenants");

        ShopifyOAuthService.LinkResult result = oauthService.linkOrProvision(
            new ShopifyOAuthService.StateRecord(null, shopC, null), shopC, "fake-code-c");

        assertThat(result.outcome())
            .as("cold install with no linked tenant must resolve to NOT_LINKED, never throw")
            .isEqualTo(ShopifyOAuthService.LinkOutcome.NOT_LINKED);

        // fetchShop() is what would have surfaced the shop's (colliding) owner email —
        // must never be called, proving zero provisioning attempt was made.
        verify(shopifyGateway, never()).fetchShop(anyString(), anyString());

        // No orphan tenant — nothing was ever attempted, let alone rolled back.
        assertThat(countAll("tenants") - tenantsBefore)
            .as("tenants delta must be 0: cold install creates nothing, regardless of email collision")
            .isEqualTo(0);

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM stores WHERE shop_domain = ?", Integer.class, shopC))
            .as("no store row for shop-c")
            .isEqualTo(0);
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────────────────

    private int countAll(String table) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
        return n != null ? n : 0;
    }
}
