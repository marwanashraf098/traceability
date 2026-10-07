package com.traceability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.traceability.identity.model.TokenResponse;
import com.traceability.integrations.shopify.ShopifyGateway;
import com.traceability.integrations.shopify.ShopifyStoreExistence;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * "Find your store" over HTTP. ShopifyGateway is mocked (the store check's network part is unit-tested in
 * ShopifyStoreExistenceTest); its default null answer is an inconclusive check, which must let the merchant through.
 *
 *   s1 resolve-store: each accepted format → {shopDomain, source}; junk → 400 NOT_SHOPIFY_ADDRESS; owner only.
 *   s2 initiate lowercases / normalises (fixes the capitals bug) and stores the normalised shop in the state row.
 *   s3 initiate rejects junk with NOT_SHOPIFY_ADDRESS — no state row.
 *   s4 store check: NOT_FOUND → 422 STORE_NOT_FOUND (no state row); EXISTS / INCONCLUSIVE / null → through.
 *   s5 same-shop rule still enforced, before any store check.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StoreFinderTest {

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
    }

    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired ObjectMapper mapper;
    @MockBean ShopifyGateway shopify;

    String freshOwner, boundOwner, manager;

    @BeforeAll
    void setup() {
        UUID fresh = tenant("Finder Fresh");
        freshOwner = login(user(fresh, "owner"));
        manager = login(user(fresh, "manager"));
        UUID bound = tenant("Finder Bound");
        jdbc.update("INSERT INTO stores (tenant_id, platform, shop_domain, status) VALUES (?, 'shopify', 'bound-shop.myshopify.com', 'disconnected')", bound);
        boundOwner = login(user(bound, "owner"));
    }

    @BeforeEach
    void clean() {
        reset(shopify);
        jdbc.update("DELETE FROM shopify_oauth_state");
    }

    // ── s1 ────────────────────────────────────────────────────────────────────

    @Test
    void s1_resolveStore_formats_junk_ownerOnly() throws Exception {
        assertThat(resolve(freshOwner, "https://ABC123.myshopify.com/").getBody())
            .contains("\"shopDomain\":\"abc123.myshopify.com\"").contains("\"source\":\"myshopify\"");
        assertThat(resolve(freshOwner, "admin.shopify.com/store/abc123/orders?x=1").getBody())
            .contains("\"shopDomain\":\"abc123.myshopify.com\"").contains("\"source\":\"admin_link\"");
        assertThat(resolve(freshOwner, "​abc123.myshopify.com/admin").getBody())
            .contains("\"shopDomain\":\"abc123.myshopify.com\"").contains("\"source\":\"admin_link\"");

        ResponseEntity<String> junk = resolve(freshOwner, "thesnouts.com");
        assertThat(junk.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(junk.getBody()).contains("NOT_SHOPIFY_ADDRESS").contains("That's not a Shopify address.");

        assertThat(resolve(manager, "abc123.myshopify.com").getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        verifyNoInteractions(shopify);   // resolve never touches the network
    }

    // ── s2 / s3 ───────────────────────────────────────────────────────────────

    @Test
    void s2_initiate_normalisesCapitalsAndAdminLinks() throws Exception {
        ResponseEntity<String> r = initiate(freshOwner, "  HTTPS://ABC123.MyShopify.com/  ");
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(consentUrl(r)).startsWith("https://abc123.myshopify.com/admin/oauth/authorize");
        assertThat(jdbc.queryForObject("SELECT shop_domain FROM shopify_oauth_state", String.class))
            .isEqualTo("abc123.myshopify.com");

        jdbc.update("DELETE FROM shopify_oauth_state");
        assertThat(consentUrl(initiate(freshOwner, "admin.shopify.com/store/abc123/products")))
            .startsWith("https://abc123.myshopify.com/admin/oauth/authorize");
    }

    @Test
    void s3_initiate_rejectsJunk_noState() {
        for (String junk : new String[]{"my store abc", "thesnouts.com", "abc123.myshopify.com.evil.com", ""}) {
            ResponseEntity<String> r = initiate(freshOwner, junk);
            assertThat(r.getStatusCode()).as(junk).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(r.getBody()).as(junk).contains("NOT_SHOPIFY_ADDRESS");
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM shopify_oauth_state", Integer.class)).isZero();
        verify(shopify, never()).checkStoreExists(anyString());
    }

    // ── s4 ────────────────────────────────────────────────────────────────────

    @Test
    void s4_storeCheck_onlyAClearNotFoundBlocks() throws Exception {
        when(shopify.checkStoreExists("abc132.myshopify.com")).thenReturn(ShopifyStoreExistence.Result.NOT_FOUND);
        ResponseEntity<String> missing = initiate(freshOwner, "abc132.myshopify.com");
        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(missing.getBody()).contains("STORE_NOT_FOUND")
            .contains("We couldn't find a store called abc132.myshopify.com. Check the spelling.");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM shopify_oauth_state", Integer.class)).isZero();

        when(shopify.checkStoreExists("exists.myshopify.com")).thenReturn(ShopifyStoreExistence.Result.EXISTS);
        when(shopify.checkStoreExists("slow.myshopify.com")).thenReturn(ShopifyStoreExistence.Result.INCONCLUSIVE);
        for (String shop : new String[]{"exists.myshopify.com", "slow.myshopify.com", "unmocked.myshopify.com"}) {
            assertThat(initiate(freshOwner, shop).getStatusCode()).as(shop).isEqualTo(HttpStatus.OK);
        }
        verify(shopify).checkStoreExists("unmocked.myshopify.com");   // null answer = inconclusive = through
    }

    // ── s5 ────────────────────────────────────────────────────────────────────

    @Test
    void s5_sameShopRule_stillEnforced_beforeTheStoreCheck() throws Exception {
        ResponseEntity<String> other = initiate(boundOwner, "other-shop.myshopify.com");
        assertThat(other.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(other.getBody()).contains("SHOPIFY_SHOP_MISMATCH");
        verify(shopify, never()).checkStoreExists(anyString());

        assertThat(initiate(boundOwner, "BOUND-SHOP.myshopify.com").getStatusCode())
            .as("its own shop, typed in capitals, is the same shop").isEqualTo(HttpStatus.OK);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private UUID tenant(String name) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?)", id, name);
        return id;
    }

    private UUID user(UUID tenant, String role) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role, active) VALUES (?, ?, 'U', ?, ?, ?::user_role, true)",
            id, tenant, role + "-" + id + "@test.local", passwordEncoder.encode("pass123"), role);
        return id;
    }

    private String login(UUID user) {
        String email = jdbc.queryForObject("SELECT email FROM users WHERE id = ?", String.class, user);
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        return rest.postForEntity(base() + "/api/v1/auth/login", new HttpEntity<>(Map.of("email", email, "password", "pass123"), h),
            TokenResponse.class).getBody().accessToken();
    }

    private ResponseEntity<String> resolve(String token, String input) {
        return post("/api/v1/shopify/resolve-store", token, Map.of("input", input));
    }

    private ResponseEntity<String> initiate(String token, String shop) {
        return post("/api/v1/shopify/oauth/initiate", token, Map.of("shop", shop));
    }

    private ResponseEntity<String> post(String path, String token, Map<String, String> body) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        h.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange(base() + path, HttpMethod.POST, new HttpEntity<>(body, h), String.class);
    }

    private String consentUrl(ResponseEntity<String> r) throws Exception {
        return (String) mapper.readValue(r.getBody(), Map.class).get("consentUrl");
    }

    private String base() { return "http://localhost:" + port; }
}
