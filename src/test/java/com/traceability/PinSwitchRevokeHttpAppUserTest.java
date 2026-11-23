package com.traceability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.traceability.identity.AuthRepository;
import com.traceability.integrations.bosta.BostaV2Client;
import com.traceability.integrations.shopify.ShopifyGateway;
import com.traceability.integrations.shopify.ShopifyOrderPriceGateway;
import com.traceability.integrations.shopify.ShopifyTokenProvider;
import org.jobrunr.scheduling.JobScheduler;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.URI;
import java.net.http.*;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B10b (2026-11-23) — proves {@code AuthController.pinSwitch} revokes the tablet's previous refresh token when the WHOLE
 * app runs as app_user under RLS (as in prod). The revoke runs through {@code AuthRepository.revokeRefreshTokenById},
 * whose {@code @Transactional} opens the transaction that sets app.current_tenant; without it the UPDATE runs in
 * autocommit with an empty GUC, RLS hides the row, nothing is revoked and the old cookie keeps refreshing — every
 * postgres-connected test (DeviceSessionTest.d7) would still pass.
 *
 * The owner signs up (cookie + access token with sid), PIN-switches to a worker sending only the Bearer (a browser
 * never sends the refresh cookie to /auth/pin); the owner's old refresh row is revoked 'pin_switch' and
 * /auth/refresh with the old cookie answers 401.
 *
 * Revert check (2026-11-23): {@code @Transactional} removed from revokeRefreshTokenById → red (row not revoked, the old
 * cookie refreshed with 200).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PinSwitchRevokeHttpAppUserTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = TestPostgres.newContainer()
            .withDatabaseName("traceability_test").withUsername("postgres").withPassword("postgres");

    static {
        POSTGRES.start();
        try (var c = java.sql.DriverManager.getConnection(POSTGRES.getJdbcUrl(), "postgres", "postgres");
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
        r.add("analytics.settlement.refresh-enabled", () -> "false");
        r.add("traced.shopify-returns.backfill.enabled", () -> "false");
        r.add("shopify.webhook-topics-backfill.enabled", () -> "false");
        r.add("traced.catalog-backfill.enabled", () -> "false");
    }

    @LocalServerPort int port;
    @Autowired PasswordEncoder encoder;
    @MockBean ShopifyGateway shopifyGateway;
    @MockBean ShopifyTokenProvider tokenProvider;
    @MockBean ShopifyOrderPriceGateway prices;
    @MockBean JobScheduler jobScheduler;
    @MockBean BostaV2Client bostaV2;

    JdbcTemplate pg;
    final HttpClient http = HttpClient.newHttpClient();
    final ObjectMapper json = new ObjectMapper();

    @BeforeAll
    void setUp() {
        pg = new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "postgres", "postgres"));
    }

    HttpResponse<String> send(String path, String bearer, String cookie, Object body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .POST(body == null ? HttpRequest.BodyPublishers.noBody()
                                   : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        if (bearer != null) b.header("Authorization", "Bearer " + bearer);
        if (cookie != null) b.header("Cookie", "traced_refresh=" + cookie);
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    static String cookieOf(HttpResponse<String> r) {
        return r.headers().allValues("set-cookie").stream().filter(c -> c.startsWith("traced_refresh="))
                .map(c -> c.substring("traced_refresh=".length(), c.indexOf(';'))).findFirst().orElse(null);
    }

    String accessOf(HttpResponse<String> r) throws Exception {
        return json.readTree(r.body()).get("accessToken").asText();
    }

    @Test
    void pinSwitch_asAppUser_oldRefreshTokenIsRefused() throws Exception {
        HttpResponse<String> s = send("/api/v1/auth/signup", null, null,
                Map.of("tenantName", "Pin probe", "name", "Owner", "email", "pin-probe@test.com",
                       "phone", "01012345678", "password", "password123", "consent", true));
        assertThat(s.statusCode()).as(s.body()).isEqualTo(201);
        String ownerCookie = cookieOf(s), ownerAccess = accessOf(s);
        UUID tenant = pg.queryForObject("SELECT tenant_id FROM users WHERE email = 'pin-probe@test.com'", UUID.class);
        UUID worker = UUID.randomUUID();
        pg.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role, pin_code, active) " +
                  "VALUES (?, ?, 'W', 'pin-probe-w@test.com', 'x', 'worker', ?, true)",
                  worker, tenant, encoder.encode("4321"));

        HttpResponse<String> p = send("/api/v1/auth/pin", ownerAccess, null, Map.of("userId", worker.toString(), "pin", "4321"));
        assertThat(p.statusCode()).as(p.body()).isEqualTo(200);

        Map<String, Object> row = pg.queryForMap("SELECT revoked_at, revoked_reason FROM refresh_tokens WHERE token_hash = ?",
                AuthRepository.sha256(ownerCookie));
        HttpResponse<String> old = send("/api/v1/auth/refresh", null, ownerCookie, null);
        assertThat(row.get("revoked_reason")).as("the previous session ended").isEqualTo("pin_switch");
        assertThat(old.statusCode()).as("the old cookie no longer refreshes").isEqualTo(401);
    }
}
