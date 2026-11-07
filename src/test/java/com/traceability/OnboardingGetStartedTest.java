package com.traceability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.traceability.demo.DemoSeeder;
import com.traceability.identity.JwtService;
import com.traceability.integrations.bosta.BostaGateway;
import com.traceability.integrations.shopify.ShopifyGateway;
import org.jobrunr.scheduling.JobScheduler;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.DriverManager;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Onboarding O3 — what GET /onboarding/progress adds for the Get started card. App runs as app_user (RLS on).
 *
 *   g1   cardChosen: false by default AND for a stored 'full' nobody chose (the V220 backfill writes card with no
 *        stamp); true once the card is written through PATCH (server stamp cardChosenAt); a client can't send
 *        cardChosenAt (400, nothing written); a PATCH without card doesn't stamp
 *   g2   setupCompletedAt: null until the gate's stamp, then that instant (UTC ISO)
 *   g3   bostaConnected: only an ACTIVE Bosta account
 *   g4   card is 'hidden' for the demo and review tenants whatever is stored; a normal tenant (control) keeps 'full'
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class OnboardingGetStartedTest {

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

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",      POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", () -> "app_user");
        r.add("spring.datasource.password", () -> "testpw");
        r.add("spring.flyway.url",          POSTGRES::getJdbcUrl);
        r.add("spring.flyway.user",         POSTGRES::getUsername);
        r.add("spring.flyway.password",     POSTGRES::getPassword);
        r.add("traced.catalog-backfill.enabled", () -> "false");
    }

    @LocalServerPort int port;
    @Autowired JwtService      jwt;
    @Autowired PasswordEncoder encoder;
    @MockBean  ShopifyGateway  shopify;
    @MockBean  BostaGateway    bosta;
    @MockBean  JobScheduler    jobScheduler;

    final ObjectMapper mapper = new ObjectMapper();
    final HttpClient   http   = HttpClient.newHttpClient();
    JdbcTemplate owner;   // postgres — fixtures and assertions only

    record Tenant(UUID id, UUID owner, UUID manager, UUID worker) {}

    @BeforeAll
    void setUp() {
        owner = new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "postgres", "postgres"));
    }

    @Test @Order(1)
    @SuppressWarnings("unchecked")
    void g1_cardChosen_onlyAfterAPatchOfCard() throws Exception {
        Tenant t = tenant("g1", true);
        assertThat(progress(t).get("cardChosen")).isEqualTo(false);
        owner.update("UPDATE tenants SET onboarding_prefs = '{\"card\":\"full\"}'::jsonb WHERE id = ?", t.id());
        assertThat(progress(t).get("cardChosen")).as("backfilled full is not a choice").isEqualTo(false);

        patch(t, Map.of("pickPackChosen", true));
        assertThat(progress(t).get("cardChosen")).as("a PATCH without card").isEqualTo(false);

        String before = prefs(t);
        HttpResponse<String> bad = send("PATCH", "/api/v1/onboarding/prefs", token(t, "owner"),
            Map.of("cardChosenAt", "2026-01-01T00:00:00Z"));
        assertThat(bad.statusCode()).as(bad.body()).isEqualTo(400);
        assertThat(prefs(t)).isEqualTo(before);

        patch(t, Map.of("card", "full"));
        Map<String, Object> p = progress(t);
        assertThat(p.get("card")).isEqualTo("full");
        assertThat(p.get("cardChosen")).isEqualTo(true);
        assertThat(mapper.readValue(prefs(t), Map.class)).containsKey("cardChosenAt");
    }

    @Test @Order(2)
    void g2_setupCompletedAt_fromTheGateStamp() throws Exception {
        Tenant t = tenant("g2", true);
        assertThat(progress(t).get("setupCompletedAt")).isNull();
        owner.update("UPDATE tenants SET setup_completed_at = '2026-10-01T09:30:00Z' WHERE id = ?", t.id());
        assertThat(progress(t).get("setupCompletedAt")).isEqualTo("2026-10-01T09:30:00Z");
    }

    @Test @Order(3)
    void g3_bostaConnected_activeOnly() throws Exception {
        Tenant t = tenant("g3", true);
        assertThat(progress(t).get("bostaConnected")).isEqualTo(false);
        owner.update("INSERT INTO courier_accounts (tenant_id, provider, api_key_encrypted, webhook_secret, status) " +
                     "VALUES (?, 'bosta', 'x', ?, 'disconnected')", t.id(), "wh-" + t.id());
        assertThat(progress(t).get("bostaConnected")).isEqualTo(false);
        owner.update("UPDATE courier_accounts SET status = 'active' WHERE tenant_id = ?", t.id());
        assertThat(progress(t).get("bostaConnected")).isEqualTo(true);
    }

    @Test @Order(4)
    void g4_hiddenForDemoAndReview() throws Exception {
        Tenant normal = tenant("g4n", true);
        assertThat(progress(normal).get("card")).as("control").isEqualTo("full");

        Tenant review = tenant("g4r", true);
        owner.update("INSERT INTO tenant_courier_simulation (tenant_id, note) VALUES (?, 'review')", review.id());
        owner.update("UPDATE tenants SET onboarding_prefs = '{\"card\":\"full\"}'::jsonb WHERE id = ?", review.id());
        assertThat(progress(review).get("card")).isEqualTo("hidden");

        UUID demo = DemoSeeder.DEMO_TENANT_ID;
        owner.update("UPDATE tenants SET onboarding_prefs = '{\"card\":\"full\"}'::jsonb WHERE id = ?", demo);
        UUID demoOwner = user(demo, "owner", "g4d");
        HttpResponse<String> r = send("GET", "/api/v1/onboarding/progress", jwt.issueAccessToken(demoOwner, demo, "owner"), null);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        assertThat(mapper.readValue(r.body(), Map.class).get("card")).isEqualTo("hidden");
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private Object intro(String token) throws Exception {
        Map<String, Object> m = me(token);
        assertThat(m).containsKey("setupIntro");
        return m.get("setupIntro");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> me(String token) throws Exception {
        HttpResponse<String> r = send("GET", "/api/v1/me", token, null);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        return mapper.readValue(r.body(), Map.class);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> progress(Tenant t) throws Exception {
        HttpResponse<String> r = send("GET", "/api/v1/onboarding/progress", token(t, "owner"), null);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        return mapper.readValue(r.body(), Map.class);
    }

    private void patch(Tenant t, Map<String, Object> body) throws Exception {
        HttpResponse<String> r = send("PATCH", "/api/v1/onboarding/prefs", token(t, "owner"), body);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
    }

    private HttpResponse<String> send(String method, String path, String token, Object body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
            .header("Authorization", "Bearer " + token)
            .header("Content-Type", "application/json");
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
            : HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private String prefs(Tenant t) {
        return owner.queryForObject("SELECT onboarding_prefs::text FROM tenants WHERE id = ?", String.class, t.id());
    }

    private String token(Tenant t, String role) {
        UUID user = switch (role) { case "owner" -> t.owner(); case "manager" -> t.manager(); default -> t.worker(); };
        return jwt.issueAccessToken(user, t.id(), role);
    }

    /** setUp = connected Shopify and a complete main warehouse address → the gate is done (setupStep null). */
    private Tenant tenant(String tag, boolean setUp) {
        UUID id = UUID.randomUUID();
        owner.update("INSERT INTO tenants (id, name) VALUES (?, ?)", id, "Intro " + tag);
        owner.update("INSERT INTO locations (tenant_id, name, type, is_default, is_fulfillment) " +
                     "VALUES (?, 'Main Warehouse', 'warehouse', true, true)", id);
        if (setUp) {
            owner.update("UPDATE tenants SET shopify_connected_at = now() WHERE id = ?", id);
            owner.update("UPDATE locations SET address_province_code = 'EG-C', address_city = 'Nasr City', " +
                         "address_street = '12 Street' WHERE tenant_id = ? AND is_fulfillment", id);
        }
        return new Tenant(id, user(id, "owner", tag), user(id, "manager", tag), user(id, "worker", tag));
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
