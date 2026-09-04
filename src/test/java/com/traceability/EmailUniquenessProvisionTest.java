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
 * Verifies three guarantees introduced by V42 and the matching code change in
 * ShopifyOAuthService.provisionNewTenant():
 *
 *   eu1 — email-collision provision fails with full rollback: the tenants INSERT
 *         that already succeeded within provision_tenant_from_shopify's transaction
 *         is rolled back together with the failing users INSERT and the never-reached
 *         stores INSERT. No orphan tenant, user, or store row remains.
 *
 *   eu2 — the original tenant's login is unbroken after the failed collision attempt:
 *         auth_lookup_user() returns exactly one row for the collision email, so
 *         AuthService.lookupUser()'s queryForObject() would succeed, not throw.
 *         Assertion runs via app_user (RLS enforced).
 *
 *   eu3 — regression proof: without the constraint, two users sharing an email cause
 *         queryForObject() to throw IncorrectResultSizeDataAccessException (500 in prod,
 *         not 401). The test drops the constraint, inserts duplicates, asserts the
 *         exception, then restores the constraint — proving the constraint is the fix.
 *
 * Constraint name "users_email_unique" matches V42__users_email_unique.sql.
 * See also: ShopifyOAuthService.USERS_EMAIL_CONSTRAINT (same literal, must stay in sync).
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

    // Real service under test — exercises the provisionNewTenant() catch block in eu_prod.
    @Autowired ShopifyOAuthService oauthService;

    @MockBean ShopifyGateway  shopifyGateway;
    @MockBean JobScheduler    jobScheduler;
    @MockBean ShopifyImportJob shopifyImportJob;
    @MockBean EmailGateway    emailGateway;

    // app_user — RLS enforced — for assertions that must be tenant-scoped.
    private JdbcTemplate        appUserJdbc;
    private TransactionTemplate appUserTx;

    // Provisioned in @BeforeAll; shared across eu1 and eu2.
    private UUID   tenantA;
    private String collisionEmail;
    private String shopA;
    private String shopB;

    @BeforeAll
    void setup() {
        DriverManagerDataSource rawDs =
                new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "app_user", "testpw");
        TenantAwareDataSource appUserDs = new TenantAwareDataSource(rawDs);
        appUserJdbc = new JdbcTemplate(appUserDs);
        appUserTx   = new TransactionTemplate(new DataSourceTransactionManager(appUserDs));

        collisionEmail = "collision@eu-test.local";
        shopA          = "eu-shop-a.myshopify.com";
        shopB          = "eu-shop-b.myshopify.com";

        // Provision the first tenant via the same DEFINER function used in production.
        // This establishes the "existing user" that eu1/eu2 reason about.
        Map<String, UUID> ids = jdbc.query(
            "SELECT tenant_id, owner_user_id, store_id " +
            "FROM provision_tenant_from_shopify(?, ?, 'Shop A', 'Africa/Cairo', 'dummy-enc-token-a')",
            rs -> {
                if (!rs.next()) return null;
                return Map.of(
                    "tenantId", rs.getObject("tenant_id",     UUID.class),
                    "userId",   rs.getObject("owner_user_id", UUID.class),
                    "storeId",  rs.getObject("store_id",      UUID.class));
            },
            shopA, collisionEmail);

        assertThat(ids).as("initial provision_tenant_from_shopify must succeed").isNotNull();
        tenantA = ids.get("tenantId");
    }

    // ── eu1: email collision rolls back ALL THREE inserts (including tenants) ───────────────────

    /**
     * The critical atomicity guarantee: provision_tenant_from_shopify inserts in order
     * tenants → users → stores. With V42 UNIQUE(email), the users INSERT (second) fires
     * 23505 before stores is ever reached. PostgreSQL rolls back the entire function
     * invocation — including the already-completed tenants INSERT. No orphan rows survive.
     *
     * Without this test, the rollback of the tenants row specifically is unverified.
     * Asserting delta(tenants) = 0 is the only proof of full atomicity.
     */
    @Test
    @Order(1)
    void eu1_emailCollision_allThreeInsertsRolledBack_noOrphanTenantOrStore() {
        int tenantsBefore = countAll("tenants");

        // Attempt to provision a second tenant with the same owner email.
        // Expected: 23505 on users_email_unique — all three INSERTs rolled back.
        assertThatThrownBy(() ->
            jdbc.query(
                "SELECT tenant_id FROM provision_tenant_from_shopify" +
                "(?, ?, 'Shop B', 'Africa/Cairo', 'dummy-enc-token-b')",
                rs -> null,
                shopB, collisionEmail))
            .hasMessageContaining("users_email_unique");

        // PRIMARY ASSERTION: tenants count is unchanged — the tenants row was rolled back.
        int tenantsAfter = countAll("tenants");
        assertThat(tenantsAfter - tenantsBefore)
            .as("tenants delta must be 0: the tenants INSERT was rolled back together with users")
            .isEqualTo(0);

        // No orphan user row for the collision email (still exactly 1, from shop-a).
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM users WHERE email = ?", Integer.class, collisionEmail))
            .as("exactly one user row for collision email — no duplicate created")
            .isEqualTo(1);

        // No orphan store row for shop-b (stores INSERT was never reached).
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM stores WHERE shop_domain = ?", Integer.class, shopB))
            .as("no store row for shop-b — stores INSERT was never reached")
            .isEqualTo(0);
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
            .as("returned tenant_id must be tenant-a's (the original, non-colliding provisioning)")
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
     * and ShopifyOAuthService.USERS_EMAIL_CONSTRAINT.
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
     * Superseded 2026-09-04 (Option A): this test formerly proved that a cold Shopify-first
     * install colliding on email surfaced through provisionNewTenant()'s catch block as
     * SHOPIFY_EMAIL_ALREADY_REGISTERED (409), by exercising linkOrProvision() → branch() →
     * path2() → provisionNewTenant().
     *
     * As of Option A, path2() no longer calls provisionNewTenant() at all when no owner is
     * found — a cold install creates nothing, unconditionally, regardless of what email the
     * shop would have resolved to. There is no longer any email-collision path to reach
     * through the OAuth flow: fetchShop() (the call that would surface the shop's owner
     * email) is never invoked. provisionNewTenant()'s catch block and the
     * SHOPIFY_EMAIL_ALREADY_REGISTERED code remain in the codebase (proven live by eu1's
     * direct call to provision_tenant_from_shopify) but are unreachable from linkOrProvision()
     * — see ShopifyOAuthService.LinkOutcome's PROVISIONED comment.
     *
     * This test now proves the Option A guarantee instead: even with a shop whose resolved
     * email WOULD collide with an existing tenant's owner, a cold install still creates
     * nothing and never attempts provisioning in the first place.
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
            new ShopifyOAuthService.StateRecord(null, shopC), shopC, "fake-code-c");

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
