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
 * Onboarding O2 — the setup intro (Flow-3 courier, Flow-4 "You're connected") and its one pref key.
 * App runs as app_user (RLS on); every call is real HTTP.
 *
 *   p1   setupIntroSeen is whitelisted: true / false accepted and stored, shown on GET /progress; a non-boolean
 *        or null → 400 and nothing written
 *   m1   GET /me setupIntro for an OWNER of a set-up tenant: 'courier' while the courier step is neither done
 *        nor skipped → 'ready' once skipped → null once setupIntroSeen; a courier switched on → 'ready'
 *   m2   never for a manager, a worker, or the PIN-switched-in worker on a station tablet
 *   m3   never while the gate is still on (setupStep not null), for a tenant whose card is hidden, for the
 *        demo tenant or the review (simulated courier) tenant
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class OnboardingSetupIntroTest {

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
    void p1_setupIntroSeen_whitelisted() throws Exception {
        Tenant t = tenant("p1", true);
        assertThat(progress(t).get("setupIntroSeen")).isEqualTo(false);

        String before = prefs(t);
        for (Object v : Arrays.asList("yes", 1, null)) {
            Map<String, Object> body = new HashMap<>();
            body.put("setupIntroSeen", v);
            HttpResponse<String> r = send("PATCH", "/api/v1/onboarding/prefs", token(t, "owner"), body);
            assertThat(r.statusCode()).as("%s → %s", v, r.body()).isEqualTo(400);
        }
        assertThat(prefs(t)).as("nothing written").isEqualTo(before);

        HttpResponse<String> ok = send("PATCH", "/api/v1/onboarding/prefs", token(t, "owner"), Map.of("setupIntroSeen", true));
        assertThat(ok.statusCode()).as(ok.body()).isEqualTo(200);
        assertThat(mapper.readValue(prefs(t), Map.class)).containsEntry("setupIntroSeen", true);
        assertThat(progress(t).get("setupIntroSeen")).isEqualTo(true);
    }

    @Test @Order(2)
    void m1_owner_courierThenReadyThenNothing() throws Exception {
        Tenant t = tenant("m1", true);
        assertThat(intro(token(t, "owner"))).isEqualTo("courier");

        patch(t, Map.of("skipped", List.of("courier")));
        assertThat(intro(token(t, "owner"))).as("courier skipped").isEqualTo("ready");

        patch(t, Map.of("setupIntroSeen", true));
        assertThat(intro(token(t, "owner"))).as("Flow-4 shown once").isNull();

        Tenant g = tenant("m1g", true);
        owner.update("UPDATE tenants SET generic_courier_enabled = true WHERE id = ?", g.id());
        assertThat(intro(token(g, "owner"))).as("courier done").isEqualTo("ready");

        Tenant b = tenant("m1b", true);
        owner.update("INSERT INTO courier_accounts (tenant_id, provider, api_key_encrypted, webhook_secret, status) " +
                     "VALUES (?, 'bosta', 'x', ?, 'active')", b.id(), "wh-" + b.id());
        assertThat(intro(token(b, "owner"))).as("Bosta connected").isEqualTo("ready");
    }

    @Test @Order(3)
    void m2_neverForManagerWorkerOrStation() throws Exception {
        Tenant t = tenant("m2", true);
        assertThat(intro(token(t, "owner"))).as("positive control").isEqualTo("courier");
        assertThat(intro(token(t, "manager"))).isNull();
        assertThat(intro(token(t, "worker"))).isNull();

        owner.update("UPDATE users SET pin_code = ? WHERE id = ?", encoder.encode("4321"), t.worker());
        HttpResponse<String> pin = send("POST", "/api/v1/auth/pin", token(t, "owner"),
            Map.of("userId", t.worker().toString(), "pin", "4321"));
        assertThat(pin.statusCode()).as(pin.body()).isEqualTo(200);
        String stationTok = (String) mapper.readValue(pin.body(), Map.class).get("accessToken");
        assertThat(intro(stationTok)).as("station: the switched-in worker").isNull();
    }

    @Test @Order(4)
    void m3_neverWhileGatedHiddenDemoOrReview() throws Exception {
        Tenant gated = tenant("m3g", false);
        assertThat(me(token(gated, "owner")).get("setupStep")).isEqualTo("shopify");
        assertThat(intro(token(gated, "owner"))).isNull();

        Tenant hidden = tenant("m3h", true);
        owner.update("UPDATE tenants SET onboarding_prefs = '{\"card\":\"hidden\"}'::jsonb WHERE id = ?", hidden.id());
        assertThat(intro(token(hidden, "owner"))).isNull();

        Tenant review = tenant("m3r", true);
        owner.update("INSERT INTO tenant_courier_simulation (tenant_id, note) VALUES (?, 'review')", review.id());
        assertThat(intro(token(review, "owner"))).isNull();

        UUID demo = DemoSeeder.DEMO_TENANT_ID;
        owner.update("UPDATE tenants SET onboarding_prefs = '{}'::jsonb WHERE id = ?", demo);
        UUID demoOwner = user(demo, "owner", "m3d");
        assertThat(intro(jwt.issueAccessToken(demoOwner, demo, "owner"))).isNull();
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
