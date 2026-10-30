package com.traceability;

import com.traceability.identity.JwtService;
import com.traceability.integrations.bosta.BostaGateway;
import com.traceability.integrations.shopify.ShopifyGateway;
import org.jobrunr.scheduling.JobScheduler;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.DriverManager;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The app-wide Shopify upgrade notice: GET /me's shopifyUpgradeStage and
 * POST /api/v1/shopify/custom-app-deletion/confirm (V166). The app runs as app_user (RLS on, like production).
 *
 * Every stage is read through the real GET /me request (JWT filter → MeController → the service's transaction), and
 * each /me response also carries the caller's role — the same-response positive control.
 *
 *   n1 stage: custom_app_cc + connected + offered → 'upgrade' (owner and manager); not offered → null;
 *      disconnected → null; upgraded oauth unconfirmed → 'delete_old_app'; confirmed → null; plain oauth → null;
 *      no store → null; upgraded then back on a custom app → null; a worker → null on every one of them
 *   n2 confirm: a manager → 403 (row unchanged); wrong state (custom app / plain oauth / no store) → 409
 *   n3 confirm: the owner → 204, confirmed_at/by written, /me → null; a repeat → 204 with no rewrite
 *   n4 tenant isolation: confirming tenant A leaves tenant B's upgraded store untouched and B still sees
 *      'delete_old_app' (same-tenant positive control: A's own row is written); app_user with no tenant set
 *      can't write the confirmation at all
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ShopifyUpgradeNoticeTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            TestPostgres.newContainer()
                    .withDatabaseName("traceability_test")
                    .withUsername("postgres")
                    .withPassword("postgres");

    static {
        POSTGRES.start();
        try (var c = DriverManager.getConnection(POSTGRES.getJdbcUrl(), "postgres", "postgres");
             var st = c.createStatement()) {
            st.execute("CREATE ROLE app_user LOGIN PASSWORD 'testpw'");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static final String OFFERED     = "offered-shop.myshopify.com";
    static final String NOT_OFFERED = "not-offered-shop.myshopify.com";

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",      POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", () -> "app_user");          // RLS on everywhere, like production
        r.add("spring.datasource.password", () -> "testpw");
        r.add("spring.flyway.url",          POSTGRES::getJdbcUrl);
        r.add("spring.flyway.user",         POSTGRES::getUsername);
        r.add("spring.flyway.password",     POSTGRES::getPassword);
        r.add("traced.catalog-backfill.enabled", () -> "false");
        r.add("shopify.oauth-available",    () -> "true");
        // The rollout list: the disconnected custom app's shop is listed too, so only its status keeps it out.
        r.add("shopify.oauth-upgrade-shops", () -> OFFERED + ",disconnected-shop.myshopify.com");
    }

    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;
    @Autowired JwtService       jwt;
    @MockBean  ShopifyGateway   shopify;
    @MockBean  BostaGateway     bosta;
    @MockBean  JobScheduler     jobScheduler;

    JdbcTemplate owner;   // postgres — fixtures and assertions only

    record Tenant(UUID id, UUID store, UUID owner, UUID manager, UUID worker) {}

    Tenant offered, notOffered, disconnected, upgradedA, upgradedB, confirmed, plainOauth, noStore, backOnCustom;

    @BeforeAll
    void setUp() {
        owner = new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "postgres", "postgres"));
        offered      = tenant("offered",      OFFERED,                          "connected",    "custom_app_cc", null);
        notOffered   = tenant("notOffered",   NOT_OFFERED,                      "connected",    "custom_app_cc", null);
        disconnected = tenant("disconnected", "disconnected-shop.myshopify.com", "disconnected", "custom_app_cc", null);
        upgradedA    = tenant("upgradedA",    "upgraded-a.myshopify.com",       "connected",    "oauth", "custom_app_cc");
        upgradedB    = tenant("upgradedB",    "upgraded-b.myshopify.com",       "connected",    "oauth", "custom_app_cc");
        confirmed    = tenant("confirmed",    "confirmed.myshopify.com",        "connected",    "oauth", "custom_app_cc");
        owner.update("UPDATE stores SET custom_app_deletion_confirmed_at = now() - interval '1 day', " +
                     "custom_app_deletion_confirmed_by = ? WHERE id = ?", confirmed.owner(), confirmed.store());
        plainOauth   = tenant("plainOauth",   "plain-oauth.myshopify.com",      "connected",    "oauth", null);
        noStore      = tenant("noStore",      null,                             null,           null, null);
        // Upgraded once, then reconnected through the custom app (the CC connect path keeps oauth_upgraded_from):
        // it is a custom app again, never in the delete stage.
        backOnCustom = tenant("backOnCustom", "back-on-custom.myshopify.com",   "connected",    "custom_app_cc", "custom_app_cc");
    }

    // ── n1 ───────────────────────────────────────────────────────────────────

    @Test @Order(1)
    void n1_stageDerivation() {
        assertStage(offered,      "upgrade");
        assertStage(notOffered,   null);
        assertStage(disconnected, null);
        assertStage(upgradedA,    "delete_old_app");
        assertStage(confirmed,    null);
        assertStage(plainOauth,   null);
        assertStage(noStore,      null);
        assertStage(backOnCustom, null);
    }

    // ── n2 ───────────────────────────────────────────────────────────────────

    @Test @Order(2)
    void n2_confirm_managerForbidden_wrongStateConflict() {
        assertThat(confirm(token(upgradedA, "manager")).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(confirmedAt(upgradedA)).as("a manager's attempt writes nothing").isNull();
        assertThat(confirm(token(upgradedA, "worker")).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        for (Tenant t : new Tenant[] { offered, notOffered, disconnected, plainOauth, noStore, backOnCustom }) {
            ResponseEntity<Map> r = confirm(token(t, "owner"));
            assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(r.getBody()).containsEntry("error", "NOT_AWAITING_DELETION");
        }
        assertThat(owner.queryForObject("SELECT count(*) FROM stores WHERE custom_app_deletion_confirmed_at IS NOT NULL",
            Integer.class)).as("only the pre-confirmed fixture").isEqualTo(1);
    }

    // ── n3 + n4 ──────────────────────────────────────────────────────────────

    @Test @Order(3)
    void n3_n4_ownerConfirms_ownRowOnly_repeatIsNoOp() {
        assertThat(confirm(token(upgradedA, "owner")).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        // Same-tenant positive control: A's row is written, by A's owner.
        Map<String, Object> a = owner.queryForMap(
            "SELECT custom_app_deletion_confirmed_at AS at, custom_app_deletion_confirmed_by AS by FROM stores WHERE id = ?",
            upgradedA.store());
        assertThat(a.get("at")).isNotNull();
        assertThat(a.get("by")).isEqualTo(upgradedA.owner());
        assertStage(upgradedA, null);

        // Isolation: B's upgraded store is untouched and B still sees the notice.
        assertThat(confirmedAt(upgradedB)).isNull();
        assertStage(upgradedB, "delete_old_app");

        // Repeat → 204, no rewrite.
        Object first = confirmedAt(upgradedA);
        assertThat(confirm(token(upgradedA, "owner")).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(confirmedAt(upgradedA)).as("repeat keeps the first confirmation").isEqualTo(first);
        assertThat(confirm(token(confirmed, "owner")).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(owner.queryForObject("SELECT custom_app_deletion_confirmed_at < now() - interval '1 hour' " +
            "FROM stores WHERE id = ?", Boolean.class, confirmed.store())).as("old confirmation not rewritten").isTrue();
    }

    @Test @Order(4)
    void n4_appUserWithoutTenant_cannotConfirm() {
        JdbcTemplate appUser = new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "app_user", "testpw"));
        int updated = appUser.update(
            "UPDATE stores SET custom_app_deletion_confirmed_at = now() WHERE id = ? AND custom_app_deletion_confirmed_at IS NULL",
            upgradedB.store());
        assertThat(updated).as("RLS: no tenant set → no row visible").isZero();
        assertThat(confirmedAt(upgradedB)).isNull();
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private void assertStage(Tenant t, String expected) {
        assertThat(me(t, "owner")).as("owner").isEqualTo(expected);
        assertThat(me(t, "manager")).as("manager").isEqualTo(expected);
        assertThat(me(t, "worker")).as("worker never sees it").isNull();
    }

    private Object me(Tenant t, String role) {
        ResponseEntity<Map> r = rest.exchange("http://localhost:" + port + "/api/v1/me", HttpMethod.GET,
            new HttpEntity<>(headers(token(t, role))), Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        // Same-response positive control: the caller's own row resolved under RLS on this very request.
        assertThat(r.getBody()).containsEntry("role", role);
        assertThat(r.getBody()).containsKey("shopifyUpgradeStage");
        return r.getBody().get("shopifyUpgradeStage");
    }

    private ResponseEntity<Map> confirm(String token) {
        return rest.exchange("http://localhost:" + port + "/api/v1/shopify/custom-app-deletion/confirm",
            HttpMethod.POST, new HttpEntity<>(headers(token)), Map.class);
    }

    private Object confirmedAt(Tenant t) {
        return owner.queryForObject("SELECT custom_app_deletion_confirmed_at FROM stores WHERE id = ?", Object.class, t.store());
    }

    private String token(Tenant t, String role) {
        UUID user = switch (role) { case "owner" -> t.owner(); case "manager" -> t.manager(); default -> t.worker(); };
        return jwt.issueAccessToken(user, t.id(), role);
    }

    private static HttpHeaders headers(String token) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.setBearerAuth(token);
        return h;
    }

    private Tenant tenant(String tag, String shop, String status, String connectionType, String upgradedFrom) {
        UUID id = UUID.randomUUID(), store = UUID.randomUUID();
        owner.update("INSERT INTO tenants (id, name) VALUES (?, ?)", id, "Notice " + tag);
        UUID o = user(id, "owner", tag), m = user(id, "manager", tag), w = user(id, "worker", tag);
        if (shop != null) {
            boolean cc = "custom_app_cc".equals(connectionType);
            owner.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status, connection_type, " +
                         "    client_id_encrypted, api_secret_encrypted, oauth_upgraded_from) " +
                         "VALUES (?, ?, 'shopify', ?, ?::store_status, ?, ?, ?, ?)",
                store, id, shop, status, connectionType, cc ? "cid" : null, cc ? "secret" : null, upgradedFrom);
        }
        return new Tenant(id, shop != null ? store : null, o, m, w);
    }

    private UUID user(UUID tenant, String role, String tag) {
        UUID u = UUID.randomUUID();
        owner.update("INSERT INTO users (id, tenant_id, name, email, password_hash, pin_code, role, active) " +
                     "VALUES (?, ?, ?, ?, ?, ?, ?::user_role, true)",
            u, tenant, role + " " + tag, role + "-" + tag + "-" + u + "@test.local",
            "worker".equals(role) ? null : "h", "worker".equals(role) ? "p" : null, role);
        return u;
    }
}
