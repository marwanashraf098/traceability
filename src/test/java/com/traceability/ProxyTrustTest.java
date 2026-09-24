package com.traceability;

import com.traceability.demo.DemoSeeder;
import com.traceability.demo.DemoStartRequest;
import com.traceability.integrations.shopify.ShopifyGateway;
import com.traceability.integrations.shopify.ShopifyTokenProvider;
import org.jobrunr.scheduling.JobScheduler;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
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
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proxy trust over real HTTP (embedded Tomcat, server.forward-headers-strategy: native).
 * The test client connects from 127.0.0.1, which is inside the internal-proxies range, so it
 * plays the part of nginx: the forwarded headers it sends are trusted exactly as nginx's are.
 *
 * The client IP is observed where the app uses it: demo_leads.ip and the per-IP demo limit.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ProxyTrustTest {

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
        r.add("shopify.api-version",        () -> "2024-10");
        r.add("shopify.client-id",          () -> "test-client-id");
        r.add("shopify.client-secret",      () -> "test-client-secret");
        r.add("shopify.scopes",             () -> "read_products");
        r.add("shopify.webhook-base-url",   () -> "https://test.example.com");
        r.add("bosta.api-base-url",         () -> "https://app.bosta.co");
    }

    @MockBean JobScheduler         jobScheduler;
    @MockBean ShopifyGateway       shopifyGateway;
    @MockBean ShopifyTokenProvider tokenProvider;

    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired DemoSeeder   demoSeeder;

    /** No redirect following, never throws on 4xx/5xx. */
    private final RestTemplate http = new RestTemplate(new JdkClientHttpRequestFactory(
        HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()));

    @BeforeAll
    void setup() {
        http.setErrorHandler(new DefaultResponseErrorHandler() {
            @Override public boolean hasError(ClientHttpResponse response) { return false; }
        });
        demoSeeder.ensureBootstrapped();
        demoSeeder.reseed();
    }

    @BeforeEach
    void clearLeads() {
        jdbc.update("DELETE FROM demo_leads");
    }

    private String base() { return "http://localhost:" + port; }

    private ResponseEntity<String> demoStart(HttpHeaders headers) {
        headers.setContentType(MediaType.APPLICATION_JSON);
        DemoStartRequest body = new DemoStartRequest("Proxy Visitor",
            "proxy-" + UUID.randomUUID() + "@example.com", "+1-202-555-0100", true);
        return http.exchange(base() + "/api/v1/public/demo/start", HttpMethod.POST,
            new HttpEntity<>(body, headers), String.class);
    }

    private List<String> storedIps() {
        return jdbc.queryForList("SELECT host(ip) FROM demo_leads ORDER BY created_at, id", String.class);
    }

    // ── Client IP ────────────────────────────────────────────────────────────

    @Test
    void forwardedHeader_doesNotChangeTheClientIp() {
        HttpHeaders h = new HttpHeaders();
        h.add("Forwarded", "for=198.51.100.99;proto=https;host=evil.example");
        assertThat(demoStart(h).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(storedIps()).containsExactly("127.0.0.1");
    }

    @Test
    void xForwardedFor_fromTheTrustedProxy_setsTheClientIp() {
        HttpHeaders h = new HttpHeaders();
        h.add("X-Forwarded-For", "203.0.113.7");
        assertThat(demoStart(h).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(storedIps()).containsExactly("203.0.113.7");
    }

    @Test
    void xForwardedFor_clientPrependedEntries_areIgnored() {
        // A client-supplied value followed by the address the proxy appended: the rightmost
        // entry that isn't an internal proxy wins, not the leftmost.
        HttpHeaders h = new HttpHeaders();
        h.add("X-Forwarded-For", "198.51.100.1, 203.0.113.8");
        assertThat(demoStart(h).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(storedIps()).containsExactly("203.0.113.8");
    }

    @Test
    void demoRateLimit_keysOnTheResolvedClientIp() {
        for (int i = 0; i < 5; i++) {
            HttpHeaders h = new HttpHeaders();
            h.add("X-Forwarded-For", "203.0.113.10");
            assertThat(demoStart(h).getStatusCode()).as("attempt " + (i + 1)).isEqualTo(HttpStatus.OK);
        }
        HttpHeaders sameIp = new HttpHeaders();
        sameIp.add("X-Forwarded-For", "203.0.113.10");
        ResponseEntity<String> limited = demoStart(sameIp);
        assertThat(limited.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(limited.getBody()).contains("DEMO_RATE_LIMITED");

        HttpHeaders otherIp = new HttpHeaders();
        otherIp.add("X-Forwarded-For", "203.0.113.11");
        assertThat(demoStart(otherIp).getStatusCode()).isEqualTo(HttpStatus.OK);

        // A spoofed Forwarded header can't borrow a fresh address either: this request is
        // still 203.0.113.10 and still limited.
        HttpHeaders spoof = new HttpHeaders();
        spoof.add("X-Forwarded-For", "203.0.113.10");
        spoof.add("Forwarded", "for=198.51.100.200");
        assertThat(demoStart(spoof).getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    // ── SpaController redirects ─────────────────────────────────────────────

    private HttpHeaders nginxHeaders() {
        HttpHeaders h = new HttpHeaders();
        h.add("X-Forwarded-Proto", "https");
        h.add("X-Forwarded-Host", "app.tracedtech.com");
        h.add("X-Forwarded-For", "203.0.113.20");
        return h;
    }

    @Test
    void installRedirect_isHttpsAppHost() {
        ResponseEntity<String> resp = http.exchange(
            base() + "/?shop=proxy-test.myshopify.com&hmac=abc&timestamp=123",
            HttpMethod.GET, new HttpEntity<>(nginxHeaders()), String.class);
        assertThat(resp.getStatusCode().is3xxRedirection()).isTrue();
        assertThat(resp.getHeaders().getFirst(HttpHeaders.LOCATION)).isEqualTo(
            "https://app.tracedtech.com/auth/shopify/install?shop=proxy-test.myshopify.com&hmac=abc&timestamp=123");
    }

    @Test
    void postToGetRedirect_isHttpsAppHost() {
        ResponseEntity<String> resp = http.exchange(
            base() + "/?shop=proxy-test.myshopify.com&host=abc123&embedded=1",
            HttpMethod.POST, new HttpEntity<>(nginxHeaders()), String.class);
        assertThat(resp.getStatusCode().is3xxRedirection()).isTrue();
        assertThat(resp.getHeaders().getFirst(HttpHeaders.LOCATION)).isEqualTo(
            "https://app.tracedtech.com/?shop=proxy-test.myshopify.com&host=abc123&embedded=1");
    }

    @Test
    void forwardedHost_isIgnored_forRedirects() {
        HttpHeaders h = nginxHeaders();
        h.add("Forwarded", "host=evil.example;proto=http");
        ResponseEntity<String> resp = http.exchange(
            base() + "/?shop=proxy-test.myshopify.com&hmac=abc&timestamp=123",
            HttpMethod.GET, new HttpEntity<>(h), String.class);
        assertThat(resp.getHeaders().getFirst(HttpHeaders.LOCATION))
            .startsWith("https://app.tracedtech.com/auth/shopify/install?")
            .doesNotContain("evil");
    }
}
