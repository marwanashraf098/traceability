package com.traceability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.traceability.identity.model.TokenResponse;
import com.traceability.security.EncryptionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Build C — per-store rollout over HTTP: with SHOPIFY_OAUTH_AVAILABLE on and SHOPIFY_OAUTH_UPGRADE_SHOPS naming one
 * shop, GET /connections answers oauthAvailable=true (the banner's input) for that store only. The empty-list and
 * flag-off cases are covered by UpgradeRolloutRuleTest; flag off by default is ConnectionsOnboardingTest.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class OAuthUpgradeRolloutTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("traceability_test").withUsername("postgres").withPassword("postgres");

    static { POSTGRES.start(); }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",      POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        r.add("spring.flyway.url",          POSTGRES::getJdbcUrl);
        r.add("spring.flyway.user",         POSTGRES::getUsername);
        r.add("spring.flyway.password",     POSTGRES::getPassword);
        r.add("shopify.oauth-available",     () -> "true");
        r.add("shopify.oauth-upgrade-shops", () -> " Listed-Shop.myshopify.com ");
    }

    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired EncryptionService encryption;
    @Autowired ObjectMapper mapper;

    @Test
    void listedShop_isOffered_unlistedShop_isNot() throws Exception {
        UUID listed = tenantWithCustomAppStore("listed-shop.myshopify.com");
        UUID unlisted = tenantWithCustomAppStore("other-shop.myshopify.com");

        assertThat(connections(listed)).containsEntry("oauthAvailable", true);
        assertThat(connections(unlisted)).containsEntry("oauthAvailable", false);
    }

    private UUID tenantWithCustomAppStore(String shop) {
        UUID tenant = UUID.randomUUID(), user = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?)", tenant, shop);
        jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role, active) VALUES (?, ?, 'Owner', ?, ?, 'owner', true)",
            user, tenant, "owner-" + user + "@test.local", passwordEncoder.encode("pass123"));
        jdbc.update("INSERT INTO stores (tenant_id, platform, shop_domain, status, connection_type, access_token_encrypted, " +
                    "    access_token_expires_at, client_id_encrypted, api_secret_encrypted) " +
                    "VALUES (?, 'shopify', ?, 'connected', 'custom_app_cc', ?, now() + interval '10 hours', ?, ?)",
            tenant, shop, encryption.encrypt("shpat_x"), encryption.encrypt("cid"), encryption.encrypt("secret"));
        return user;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> connections(UUID user) throws Exception {
        String email = jdbc.queryForObject("SELECT email FROM users WHERE id = ?", String.class, user);
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        String token = rest.postForEntity("http://localhost:" + port + "/api/v1/auth/login",
            new HttpEntity<>(Map.of("email", email, "password", "pass123"), h), TokenResponse.class).getBody().accessToken();
        HttpHeaders auth = new HttpHeaders();
        auth.setBearerAuth(token);
        ResponseEntity<String> r = rest.exchange("http://localhost:" + port + "/api/v1/connections", HttpMethod.GET,
            new HttpEntity<>(auth), String.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        return mapper.readValue(r.getBody(), Map.class);
    }
}
