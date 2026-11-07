package com.traceability;

import com.traceability.identity.model.AccessTokenResponse;
import org.junit.jupiter.api.*;
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

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Returns portal settings redesign Pass B — GET /api/v1/variants?nonReturnable=true lists only the excluded variants
 * (Settings' "Products that can't be returned" list). Search, order, paging and the tenant scope are the existing
 * method's. Over HTTP.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PortalVariantsFilterTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            TestPostgres.newContainer()
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
    @Autowired TestRestTemplate rest;
    @Autowired JdbcTemplate     jdbc;
    @Autowired PasswordEncoder  passwordEncoder;

    UUID a, b, storeA, storeB;
    String ownerA, managerA, ownerB;

    @BeforeAll
    void setup() {
        a = tenant("Snouts Store", "snouts-pb");
        b = tenant("Jumi Store", "jumi-pb");
        storeA = store(a, "snouts-pb");
        storeB = store(b, "jumi-pb");
        ownerA   = login(user(a, "owner"));
        managerA = login(user(a, "manager"));
        ownerB   = login(user(b, "owner"));
    }

    @AfterEach
    void reset() {
        for (UUID t : List.of(a, b)) {
            jdbc.update("DELETE FROM variants WHERE tenant_id = ?", t);
            jdbc.update("DELETE FROM products WHERE tenant_id = ?", t);
        }
    }

    @Test
    void filter_returnsOnlyExcludedVariants_withSearchOrderAndPaging() {
        UUID shirtM = variant(a, storeA, "Linen Shirt", "White / M", "2004-White-M");
        UUID shirtL = variant(a, storeA, "Linen Shirt", "White / L", "2004-White-L");
        UUID scarf  = variant(a, storeA, "Silk Scarf", "Sand", "3002-Sand");
        UUID pants  = variant(a, storeA, "Flipped Pants", "Black / XL", "1001-Black-XL");
        exclude(shirtL, scarf, pants);

        ResponseEntity<Map> r = get("/api/v1/variants?nonReturnable=true", ownerA);
        assertThat(items(r)).extracting(m -> m.get("id"))
            .containsExactly(pants.toString(), shirtL.toString(), scarf.toString());
        assertThat(items(r)).allSatisfy(m -> assertThat(m).containsEntry("nonReturnable", true));
        assertThat(r.getBody()).containsEntry("total", 3);

        // Search inside the excluded list (manager allowed, as for the unfiltered list).
        assertThat(items(get("/api/v1/variants?nonReturnable=true&search=linen", managerA))).extracting(m -> m.get("id"))
            .containsExactly(shirtL.toString());
        // Paging and total follow the filter.
        ResponseEntity<Map> page1 = get("/api/v1/variants?nonReturnable=true&page=1&size=2", ownerA);
        assertThat(items(page1)).extracting(m -> m.get("id")).containsExactly(scarf.toString());
        assertThat(page1.getBody()).containsEntry("total", 3);

        // No filter / false: every variant, exactly as before.
        assertThat(get("/api/v1/variants", ownerA).getBody()).containsEntry("total", 4);
        assertThat(items(get("/api/v1/variants?nonReturnable=false", ownerA))).extracting(m -> m.get("id"))
            .containsExactly(pants.toString(), shirtL.toString(), shirtM.toString(), scarf.toString());
    }

    @Test
    void filter_noneExcluded_isEmpty() {
        variant(a, storeA, "Linen Shirt", "White / M", "A-0");
        ResponseEntity<Map> r = get("/api/v1/variants?nonReturnable=true", ownerA);
        assertThat(items(r)).isEmpty();
        assertThat(r.getBody()).containsEntry("total", 0);
    }

    @Test
    void filter_crossTenantIsolation_withSameTenantPositiveControl() {
        UUID mineExcluded   = variant(a, storeA, "Linen Shirt", "White / M", "A-1");
        variant(a, storeA, "Linen Shirt", "White / L", "A-2");                    // mine, returnable
        UUID theirsExcluded = variant(b, storeB, "Linen Shirt", "Black / S", "B-1");
        exclude(mineExcluded, theirsExcluded);

        // Positive control: each tenant sees its own excluded variant.
        assertThat(items(get("/api/v1/variants?nonReturnable=true", ownerA))).extracting(m -> m.get("id"))
            .containsExactly(mineExcluded.toString());
        assertThat(items(get("/api/v1/variants?nonReturnable=true", ownerB))).extracting(m -> m.get("id"))
            .containsExactly(theirsExcluded.toString());
        // Isolation: never the other tenant's, with or without a search that matches it.
        assertThat(items(get("/api/v1/variants?nonReturnable=true&search=B-1", ownerA))).isEmpty();
        assertThat(items(get("/api/v1/variants?nonReturnable=true&search=A-1", ownerB))).isEmpty();
        assertThat(get("/api/v1/variants?nonReturnable=true", ownerA).getBody()).containsEntry("total", 1);
    }

    @Test
    void filter_workerForbidden() {
        String worker = login(user(a, "worker"));
        assertThat(get("/api/v1/variants?nonReturnable=true", worker).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    private void exclude(UUID... ids) {
        for (UUID id : ids) jdbc.update("UPDATE variants SET non_returnable = true WHERE id = ?", id);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> items(ResponseEntity<Map> r) {
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        return (List<Map<String, Object>>) r.getBody().get("items");
    }

    private UUID tenant(String name, String slug) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name, portal_slug, portal_enabled) VALUES (?, ?, ?, true)", id, name, slug);
        return id;
    }

    private UUID store(UUID tenant, String slug) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status) VALUES (?, ?, 'shopify', ?, 'disconnected')",
            id, tenant, slug + ".myshopify.com");
        return id;
    }

    private UUID user(UUID tenant, String role) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role, active) VALUES (?, ?, ?, ?, ?, ?::user_role, true)",
            id, tenant, role, role + "-" + id + "@test.local", passwordEncoder.encode("pass123"), role);
        return id;
    }

    private UUID variant(UUID tenant, UUID store, String product, String title, String sku) {
        UUID p = jdbc.query("SELECT id FROM products WHERE tenant_id = ? AND title = ?",
            rs -> rs.next() ? rs.getObject(1, UUID.class) : null, tenant, product);
        if (p == null) {
            p = UUID.randomUUID();
            jdbc.update("INSERT INTO products (id, tenant_id, store_id, external_id, title, status) VALUES (?, ?, ?, ?, ?, 'active')",
                p, tenant, store, "P-" + p, product);
        }
        UUID v = UUID.randomUUID();
        jdbc.update("INSERT INTO variants (id, tenant_id, product_id, external_id, title, sku) VALUES (?, ?, ?, ?, ?, ?)",
            v, tenant, p, "V-" + v, title, sku);
        return v;
    }

    private String base() { return "http://localhost:" + port; }

    private String login(UUID userId) {
        String email = jdbc.queryForObject("SELECT email FROM users WHERE id = ?", String.class, userId);
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<AccessTokenResponse> resp = rest.postForEntity(base() + "/api/v1/auth/login",
            new HttpEntity<>(Map.of("email", email, "password", "pass123"), h), AccessTokenResponse.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        return resp.getBody().accessToken();
    }

    private HttpHeaders auth(String token) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    private ResponseEntity<Map> get(String path, String token) {
        // A URI, not a template string: the path is already encoded (e.g. search=%25).
        return rest.exchange(java.net.URI.create(base() + path), HttpMethod.GET, new HttpEntity<>(auth(token)), Map.class);
    }

}
