package com.traceability;

import com.traceability.integrations.bosta.BostaGateway;
import com.traceability.integrations.bosta.BostaV2Client;
import com.traceability.integrations.shopify.ShopifyGateway;
import com.traceability.integrations.shopify.ShopifyLocationGateway;
import com.traceability.integrations.shopify.ShopifyVariantStockGateway;
import com.traceability.notifications.EmailGateway;
import org.jobrunr.scheduling.JobScheduler;
import org.junit.jupiter.api.*;
import org.mockito.Mockito;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.DefaultResponseErrorHandler;
import org.springframework.web.client.RestTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.http.HttpClient;
import java.sql.DriverManager;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Bazar B9c — BAZAR_SIGNUP_ENABLED off (the default, prod): POST /bazar-setup/send-batch answers 404 even for a store
 * that signed up for a bazar, has bazar switched on and a lowered batch; nothing moves and nothing is written.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BazarSetupSendFlagOffTest {

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
        // traced.bazar.signup-enabled left at its default (false)
    }

    @LocalServerPort int port;
    @MockBean ShopifyGateway shopify;
    @MockBean ShopifyLocationGateway shopifyLocations;
    @MockBean ShopifyVariantStockGateway stock;
    @MockBean BostaGateway bosta;
    @MockBean BostaV2Client bostaV2;
    @MockBean EmailGateway email;
    @MockBean JobScheduler jobs;

    @Test
    @SuppressWarnings("unchecked")
    void off_sendBatch404_evenWithALoweredBatch_nothingMoves() {
        JdbcTemplate owner = new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "postgres", "postgres"));
        RestTemplate http = new RestTemplate(new JdkClientHttpRequestFactory(HttpClient.newBuilder().build()));
        http.setErrorHandler(new DefaultResponseErrorHandler() {
            @Override public boolean hasError(org.springframework.http.client.ClientHttpResponse r) { return false; }
        });
        String base = "http://localhost:" + port;
        String mail = "b9c-off-" + System.nanoTime() + "@b9c.test";
        Map<String, Object> f = new HashMap<>(Map.of("tenantName", "Off Co", "name", "Nour", "email", mail,
            "phone", "+201012345678", "password", "pass1234", "consent", true, "intent", "bazar"));
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<Map> r = http.postForEntity(base + "/api/v1/auth/signup", new HttpEntity<>(f, h), Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID tenant = owner.queryForObject("SELECT tenant_id FROM users WHERE email = ?", UUID.class, mail);
        owner.update("UPDATE tenants SET bazar_enabled = true WHERE id = ?", tenant);   // ops switched it on by hand
        // A lowered batch as B9b would have left it (written by hand: the setup itself is off).
        owner.update("UPDATE tenants SET onboarding_prefs = jsonb_set(COALESCE(onboarding_prefs, '{}'::jsonb), '{bazarSetup}', " +
            "jsonb_build_object('eventId', gen_random_uuid()::text, 'requestId', 'x', 'labelledReceiptId', gen_random_uuid()::text, " +
            "'pieces', 1, 'receivedAt', now()::text, 'loweredAt', now()::text)) WHERE id = ?", tenant);
        String prefsBefore = owner.queryForObject("SELECT onboarding_prefs::text FROM tenants WHERE id = ?", String.class, tenant);
        h.setBearerAuth((String) r.getBody().get("accessToken"));
        assertThat(http.exchange(base + "/api/v1/bazar-setup/send-batch", HttpMethod.POST,
            new HttpEntity<>(Map.of("requestId", "off-1"), h), String.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(owner.queryForObject("SELECT onboarding_prefs::text FROM tenants WHERE id = ?", String.class, tenant))
            .isEqualTo(prefsBefore);
        assertThat(owner.queryForObject("SELECT count(*) FROM transfer_pieces WHERE tenant_id = ?", Integer.class, tenant)).isZero();
        assertThat(owner.queryForObject("SELECT count(*) FROM piece_events WHERE tenant_id = ?", Integer.class, tenant)).isZero();
    }
}
