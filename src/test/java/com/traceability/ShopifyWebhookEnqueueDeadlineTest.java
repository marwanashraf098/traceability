package com.traceability;

import com.traceability.integrations.shopify.ShopifyGateway;
import com.traceability.notifications.EmailGateway;
import com.traceability.security.EncryptionService;
import org.jobrunr.jobs.lambdas.JobLambda;
import org.jobrunr.scheduling.JobScheduler;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.DefaultResponseErrorHandler;
import org.springframework.web.client.RestTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * The Shopify webhook endpoint answers within its deadline even when the JobRunr enqueue can't
 * (2026-10-04): the enqueue gets shopify.webhook.enqueue-timeout-ms (2 s); the event row is saved and
 * left unprocessed for the sweeper.
 *
 *   wd1 the enqueue hangs (owner pool exhausted): 200 well under Shopify's ~5 s, row saved, unprocessed
 *   wd2 the enqueue throws: 200, row saved, unprocessed
 *   wd3 the normal path is unchanged: 200 and exactly one enqueue
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ShopifyWebhookEnqueueDeadlineTest {

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

    @LocalServerPort int port;
    @Autowired JdbcTemplate      jdbc;
    @Autowired EncryptionService encryptionService;
    @MockBean  JobScheduler      jobScheduler;
    @MockBean  ShopifyGateway    shopifyGateway;
    @MockBean  EmailGateway      emailGateway;
    @Value("${shopify.client-secret}") String clientSecret;

    private final String shop = "deadline-" + UUID.randomUUID().toString().substring(0, 8) + ".myshopify.com";
    private RestTemplate rest;

    @BeforeAll
    void setup() {
        rest = new RestTemplate();
        rest.setErrorHandler(new DefaultResponseErrorHandler() {
            @Override public boolean hasError(ClientHttpResponse r) { return false; }
        });
        UUID tenantId = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, 'DeadlineTenant')", tenantId);
        jdbc.update("INSERT INTO stores (id, tenant_id, shop_domain, platform, access_token_encrypted, status) " +
            "VALUES (?, ?, ?, 'shopify', ?, 'connected')", UUID.randomUUID(), tenantId, shop, encryptionService.encrypt("tok"));
    }

    @BeforeEach
    void resetMocks() { reset(jobScheduler); }

    @Test
    void wd1_enqueueHangs_200WithinDeadline_rowSavedUnprocessed() {
        when(jobScheduler.enqueue(any(JobLambda.class))).thenAnswer(inv -> { Thread.sleep(8_000); return null; });

        String wid = "wh-" + UUID.randomUUID();
        long t0 = System.nanoTime();
        ResponseEntity<Void> r = post(wid);
        long ms = (System.nanoTime() - t0) / 1_000_000;

        assertThat(r.getStatusCode().value()).isEqualTo(200);
        assertThat(ms).as("answered on the 2 s enqueue deadline, well under Shopify's ~5 s").isLessThan(3_500);
        assertUnprocessed(wid);
    }

    @Test
    void wd2_enqueueThrows_200_rowSavedUnprocessed() {
        when(jobScheduler.enqueue(any(JobLambda.class)))
            .thenThrow(new IllegalStateException("Connection is not available, request timed out after 3000ms"));

        String wid = "wh-" + UUID.randomUUID();
        ResponseEntity<Void> r = post(wid);

        assertThat(r.getStatusCode().value()).isEqualTo(200);
        assertUnprocessed(wid);
    }

    @Test
    void wd3_normalPath_unchanged() {
        String wid = "wh-" + UUID.randomUUID();
        ResponseEntity<Void> r = post(wid);

        assertThat(r.getStatusCode().value()).isEqualTo(200);
        verify(jobScheduler, times(1)).enqueue(any(JobLambda.class));
    }

    private void assertUnprocessed(String wid) {
        Map<String, Object> row = jdbc.queryForMap(
            "SELECT processed_at, process_error FROM shopify_webhook_events WHERE webhook_id = ?", wid);
        assertThat(row.get("processed_at")).isNull();
        assertThat(row.get("process_error")).isNull();
    }

    private ResponseEntity<Void> post(String wid) {
        byte[] body = ("{\"id\":1,\"admin_graphql_api_id\":\"gid://shopify/Order/" + Math.abs(wid.hashCode()) + "\"}")
            .getBytes(StandardCharsets.UTF_8);
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.set("X-Shopify-Topic", "orders/updated");
        h.set("X-Shopify-Shop-Domain", shop);
        h.set("X-Shopify-Hmac-Sha256", hmac(body));
        h.set("X-Shopify-Webhook-Id", wid);
        return rest.exchange("http://localhost:" + port + "/webhooks/shopify/orders/updated", HttpMethod.POST,
            new HttpEntity<>(body, h), Void.class);
    }

    private String hmac(byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(clientSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getEncoder().encodeToString(mac.doFinal(body));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
