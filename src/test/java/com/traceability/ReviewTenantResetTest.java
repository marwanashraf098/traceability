package com.traceability;

import com.traceability.identity.AuthService;
import com.traceability.identity.model.LoginRequest;
import com.traceability.identity.model.SignupRequest;
import com.traceability.integrations.bosta.BostaGateway;
import com.traceability.integrations.bosta.SimulatedShipments;
import com.traceability.review.OpsReviewTenantController;
import com.traceability.review.ReviewTenantException;
import com.traceability.review.ReviewTenantSeeder;
import com.traceability.review.ReviewTenantService;
import org.jobrunr.scheduling.JobScheduler;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Review mode S6 — scripts/ops/review-tenant-reset.sql, run through real psql in the database
 * container exactly as ops runs it (-v tenant_id, optional -v commit=yes), against a seeded review
 * tenant that also carries a reviewer round's own data, next to a real merchant tenant.
 *
 *   r1 guards: protected / unknown / unflagged tenant, two flagged tenants, an owner other than
 *      reviewer@tracedtech.com → refused, the whole database unchanged
 *   r2 dry run (no commit variable) → rolled back, the whole database unchanged
 *   r3 -v commit=yes → only the tenant row, users, locations (Shopify link cleared), the placeholder
 *      store and the flag remain of the review tenant; every other tenant's rows and every global
 *      table unchanged
 *   r4 step C again → the whole fixture, on the same placeholder store
 *   r5 a seed on a tenant that isn't clean → FIXTURE_EXISTS
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ReviewTenantResetTest {

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

    @Autowired JdbcTemplate        jdbc;
    @Autowired ReviewTenantService reviewTenants;
    @Autowired AuthService         auth;
    @MockBean  BostaGateway        bostaGateway;
    @MockBean  JobScheduler        jobScheduler;

    private static final String PASSWORD = "pw-" + UUID.randomUUID();
    private UUID review;
    private UUID merchant;
    private UUID placeholderStore;

    @BeforeAll
    void setUp() throws Exception {
        review = UUID.fromString((String) reviewTenants.create(new OpsReviewTenantController.CreateRequest(
            "Traced Review Store", "App Reviewer", "reviewer@tracedtech.com", "01012345678", PASSWORD,
            "Review Worker", "4321")).get("tenantId"));
        assertThat(psql("review-tenant-flag.sql", review, false).getExitCode()).isZero();
        reviewTenants.seed(review);
        placeholderStore = jdbc.queryForObject("SELECT id FROM stores WHERE tenant_id = ? AND shop_domain = ?",
            UUID.class, review, ReviewTenantSeeder.PLACEHOLDER_SHOP);

        // A review round's own data: the reviewer's shop, one of its products and orders, the
        // location linked to that shop, a login session.
        shopData(review, "reviewer-round-1.myshopify.com");
        jdbc.update("UPDATE locations SET shopify_location_id = 'gid://shopify/Location/111', shopify_sync_status = 'linked', " +
            "shopify_synced_at = now() WHERE tenant_id = ?", review);
        auth.login(new LoginRequest("reviewer@tracedtech.com", PASSWORD));

        // A real merchant next door, with data of its own.
        auth.signup(new SignupRequest("Real Merchant", "Owner", "owner@merchant.example", "01098765432",
            "pw-" + UUID.randomUUID(), true, null), null, null);
        merchant = jdbc.queryForObject("SELECT tenant_id FROM users WHERE email = 'owner@merchant.example'", UUID.class);
        shopData(merchant, "real-merchant.myshopify.com");
    }

    // ── r1 ───────────────────────────────────────────────────────────────────

    @Test @Order(1)
    void r1_guards_refuse_andChangeNothing() throws Exception {
        Map<String, Long> before = wholeDatabase();

        assertRefused(UUID.fromString("07fc572c-2158-412d-ae31-ec61e22378b7"), "is a protected tenant");
        assertRefused(UUID.randomUUID(), "does not exist");
        assertRefused(merchant, "is not flagged simulated-courier");

        jdbc.update("INSERT INTO tenant_courier_simulation (tenant_id, note) VALUES (?, 'test')", merchant);
        assertRefused(review, "expected exactly one simulated-courier tenant, found 2");
        jdbc.update("DELETE FROM tenant_courier_simulation WHERE tenant_id = ?", review);
        assertRefused(merchant, "owner is not reviewer@tracedtech.com");
        jdbc.update("DELETE FROM tenant_courier_simulation WHERE tenant_id = ?", merchant);
        jdbc.update("INSERT INTO tenant_courier_simulation (tenant_id, note) VALUES (?, 'Shopify App Store review tenant')", review);

        assertThat(wholeDatabase()).isEqualTo(before);
    }

    // ── r2 ───────────────────────────────────────────────────────────────────

    @Test @Order(2)
    void r2_dryRun_rollsBack() throws Exception {
        Map<String, Long> before = wholeDatabase();
        ExecResult r = psql("review-tenant-reset.sql", review, false);
        assertThat(r.getExitCode()).as(r.getStderr()).isZero();
        assertThat(r.getStdout()).contains("dry run — ROLLED BACK");
        assertThat(r.getStderr()).contains("verified");
        assertThat(wholeDatabase()).isEqualTo(before);
    }

    // ── r3 ───────────────────────────────────────────────────────────────────

    @Test @Order(3)
    void r3_commit_keepsOnlyTenantUsersLocationsPlaceholderAndFlag_nobodyElseTouched() throws Exception {
        Map<String, Long> othersBefore = everythingBut(review);
        int users = count("users", review), locations = count("locations", review);

        ExecResult r = psql("review-tenant-reset.sql", review, true);
        assertThat(r.getExitCode()).as(r.getStderr()).isZero();
        assertThat(r.getStdout()).contains("COMMITTED");

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tenants WHERE id = ?", Integer.class, review)).isEqualTo(1);
        assertThat(count("users", review)).isEqualTo(users);
        assertThat(count("locations", review)).isEqualTo(locations);
        assertThat(jdbc.queryForList("SELECT id FROM stores WHERE tenant_id = ?", UUID.class, review))
            .containsExactly(placeholderStore);
        assertThat(count("tenant_courier_simulation", review)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM locations WHERE tenant_id = ? AND (shopify_location_id IS NOT NULL " +
            "OR shopify_sync_status <> 'unsynced' OR shopify_synced_at IS NOT NULL)", Integer.class, review))
            .as("locations unlinked from the old reviewer's shop").isZero();

        for (String table : tenantScopedTables()) {
            if (List.of("users", "locations", "stores", "tenant_courier_simulation").contains(table)) continue;
            assertThat(count(table, review)).as(table).isZero();
        }
        assertThat(everythingBut(review)).as("other tenants' rows and global tables").isEqualTo(othersBefore);
    }

    // ── r4 ───────────────────────────────────────────────────────────────────

    @Test @Order(4)
    void r4_seedAgain_wholeFixture_onTheKeptPlaceholderStore() {
        reviewTenants.seed(review);
        ReviewFixtureAssertions.assertWholeFixture(jdbc, review);
        assertThat(jdbc.queryForObject("SELECT DISTINCT store_id FROM orders WHERE tenant_id = ?", UUID.class, review))
            .isEqualTo(placeholderStore);
        verifyNoInteractions(bostaGateway);
    }

    // ── r5 ───────────────────────────────────────────────────────────────────

    @Test @Order(5)
    void r5_seedOnATenantThatIsNotClean_refused() {
        assertThatThrownBy(() -> reviewTenants.seed(review))
            .isInstanceOfSatisfying(ReviewTenantException.class, e -> assertThat(e.errorCode()).isEqualTo("FIXTURE_EXISTS"));
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private void shopData(UUID tenant, String shop) {
        UUID store = jdbc.queryForObject("INSERT INTO stores (tenant_id, platform, shop_domain, status) " +
            "VALUES (?, 'shopify', ?, 'connected') RETURNING id", UUID.class, tenant, shop);
        UUID product = jdbc.queryForObject("INSERT INTO products (tenant_id, store_id, external_id, title, status) " +
            "VALUES (?, ?, ?, 'Shop product', 'active') RETURNING id", UUID.class, tenant, store, "gid://shopify/Product/" + shop.length());
        UUID variant = jdbc.queryForObject("INSERT INTO variants (tenant_id, product_id, external_id, title, sku, price) " +
            "VALUES (?, ?, ?, 'Default', ?, 100) RETURNING id", UUID.class, tenant, product,
            "gid://shopify/ProductVariant/" + shop.length(), "SKU-" + shop.length());
        UUID order = jdbc.queryForObject("INSERT INTO orders (tenant_id, store_id, external_id, number, payment_method, " +
            "cod_amount, status, placed_at) VALUES (?, ?, ?, '#9001', 'cod', 100, 'new', now()) RETURNING id",
            UUID.class, tenant, store, "gid://shopify/Order/" + shop.length());
        jdbc.update("INSERT INTO order_items (tenant_id, order_id, variant_id, quantity) VALUES (?, ?, ?, 1)", tenant, order, variant);
        if (tenant.equals(review)) SimulatedShipments.ensureForwardShipment(jdbc, tenant, order);
    }

    private void assertRefused(UUID tenant, String message) throws Exception {
        ExecResult r = psql("review-tenant-reset.sql", tenant, true);
        assertThat(r.getExitCode()).as("refused: " + message).isNotZero();
        assertThat(r.getStderr()).contains(message);
    }

    /** scripts/ops/&lt;file&gt; through psql in the database container, as ops runs it. */
    private ExecResult psql(String file, UUID tenant, boolean commit) throws Exception {
        POSTGRES.copyFileToContainer(MountableFile.forHostPath("scripts/ops/" + file), "/tmp/" + file);
        List<String> cmd = new ArrayList<>(List.of("psql", "-U", "postgres", "-d", "traceability_test",
            "-v", "ON_ERROR_STOP=1", "-v", "tenant_id=" + tenant));
        if (commit) cmd.addAll(List.of("-v", "commit=yes"));
        cmd.addAll(List.of("-f", "/tmp/" + file));
        return POSTGRES.execInContainer(cmd.toArray(String[]::new));
    }

    private List<String> tenantScopedTables() {
        return jdbc.queryForList("SELECT c.table_name FROM information_schema.columns c JOIN information_schema.tables t " +
            "ON t.table_schema = c.table_schema AND t.table_name = c.table_name WHERE c.table_schema = 'public' " +
            "AND c.column_name = 'tenant_id' AND t.table_type = 'BASE TABLE'", String.class);
    }

    /** Row count of every public table (JobRunr's own excluded) — the whole database. */
    private Map<String, Long> wholeDatabase() {
        return counts(null);
    }

    /** Row counts of everything that isn't {@code tenant}'s. */
    private Map<String, Long> everythingBut(UUID tenant) {
        return counts(tenant);
    }

    private Map<String, Long> counts(UUID excluded) {
        Set<String> scoped = new HashSet<>(tenantScopedTables());
        Map<String, Long> out = new TreeMap<>();
        for (String t : jdbc.queryForList("SELECT table_name FROM information_schema.tables WHERE table_schema = 'public' " +
                "AND table_type = 'BASE TABLE' AND table_name NOT LIKE 'jobrunr%'", String.class)) {
            out.put(t, excluded != null && scoped.contains(t)
                ? jdbc.queryForObject("SELECT COUNT(*) FROM \"" + t + "\" WHERE tenant_id IS DISTINCT FROM ?", Long.class, excluded)
                : jdbc.queryForObject("SELECT COUNT(*) FROM \"" + t + "\"", Long.class));
        }
        return out;
    }

    private int count(String table, UUID tenant) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE tenant_id = ?", Integer.class, tenant);
    }
}
