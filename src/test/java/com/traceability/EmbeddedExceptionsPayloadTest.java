package com.traceability;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.traceability.integrations.shopify.ShopifyGateway;
import com.traceability.integrations.shopify.ShopifyImportJob;
import com.traceability.notifications.EmailGateway;
import org.jobrunr.scheduling.JobScheduler;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.DefaultResponseErrorHandler;
import org.springframework.web.client.RestTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.http.HttpClient;
import java.util.UUID;

import static com.traceability.ShopifySessionTokenFilterTest.makeToken;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * GET /api/v1/embedded/exceptions must carry each exception's real type and subject key.
 *
 * Regression (2026-09-25, Shopify App Store review): EmbeddedController read the keys
 * "exceptionType" / "subjectKey" from ExceptionService rows, whose actual keys are the
 * detector SQL aliases "type" / "subject_key" — so every embedded exception went out with
 * type=null and subjectKey=null, and the embedded Overview crashed on null.replace().
 *
 * Fixture mirrors the reviewer's data: a draft-sourced 'new' order with no customer,
 * no Bosta shipment, placed on hold → one open blocked_customer exception.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EmbeddedExceptionsPayloadTest {

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
    @Autowired JdbcTemplate jdbc;

    @MockBean EmailGateway     emailGateway;
    @MockBean ShopifyGateway   shopifyGateway;
    @MockBean JobScheduler     jobScheduler;
    @MockBean ShopifyImportJob importJob;

    @Value("${shopify.client-secret}") String clientSecret;
    @Value("${shopify.client-id}")     String clientId;

    static final String SHOP_HELD  = "embedded-exc-held.myshopify.com";
    static final String SHOP_OTHER = "embedded-exc-other.myshopify.com";

    UUID tenantHeld, tenantOther, heldOrderId;
    final ObjectMapper json = new ObjectMapper();
    RestTemplate rest;

    @BeforeAll
    void setup() {
        rest = buildRestTemplate();
        tenantHeld  = UUID.randomUUID();
        tenantOther = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, 'EmbExcHeld')", tenantHeld);
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, 'EmbExcOther')", tenantOther);

        UUID storeHeld = UUID.randomUUID();
        jdbc.update("INSERT INTO stores (id, tenant_id, shop_domain, status) VALUES (?, ?, ?, 'connected')",
                storeHeld, tenantHeld, SHOP_HELD);
        jdbc.update("INSERT INTO stores (id, tenant_id, shop_domain, status) VALUES (?, ?, ?, 'connected')",
                UUID.randomUUID(), tenantOther, SHOP_OTHER);

        heldOrderId = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO orders (id, tenant_id, store_id, external_id, number, status, placed_at, on_hold) " +
            "VALUES (?, ?, ?, 'EXT-HELD-1', '#1291', 'new'::order_status, now(), true)",
            heldOrderId, tenantHeld, storeHeld);
    }

    @AfterAll
    void teardown() {
        jdbc.update("DELETE FROM orders  WHERE tenant_id IN (?, ?)", tenantHeld, tenantOther);
        jdbc.update("DELETE FROM stores  WHERE tenant_id IN (?, ?)", tenantHeld, tenantOther);
        jdbc.update("DELETE FROM tenants WHERE id IN (?, ?)", tenantHeld, tenantOther);
    }

    @Test
    void heldOrder_exceptionCarriesTypeAndSubjectKey() throws Exception {
        JsonNode body = getJson("/api/v1/embedded/exceptions", SHOP_HELD);

        assertThat(body.get("count").asInt()).isEqualTo(1);
        JsonNode ex = body.get("exceptions").get(0);
        assertThat(ex.get("type").isNull()).as("type must not be null").isFalse();
        assertThat(ex.get("type").asText()).isEqualTo("blocked_customer");
        assertThat(ex.get("severity").asText()).isEqualTo("LOW");
        assertThat(ex.get("subjectKey").isNull()).as("subjectKey must not be null").isFalse();
        assertThat(ex.get("subjectKey").asText()).isEqualTo("blocked:" + heldOrderId);
    }

    @Test
    void otherTenant_seesNoneOfTheHeldTenantsExceptions() throws Exception {
        JsonNode body = getJson("/api/v1/embedded/exceptions", SHOP_OTHER);
        assertThat(body.get("count").asInt()).isZero();
        assertThat(body.get("exceptions")).isEmpty();
    }

    private JsonNode getJson(String path, String shop) throws Exception {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(makeToken(shop, clientId, clientSecret, 300, false));
        ResponseEntity<String> r = rest.exchange("http://localhost:" + port + path,
                HttpMethod.GET, new HttpEntity<>(h), String.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        return json.readTree(r.getBody());
    }

    private static RestTemplate buildRestTemplate() {
        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        RestTemplate rt = new RestTemplate(new JdkClientHttpRequestFactory(client));
        rt.setErrorHandler(new DefaultResponseErrorHandler() {
            @Override public boolean hasError(ClientHttpResponse r) { return false; }
        });
        return rt;
    }
}
