package com.traceability;

import com.traceability.identity.JwtService;
import com.traceability.tenancy.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pre-launch fixes: (1) the Stock health count "pieces moved 4+ times" (/stock/summary
 * piecesMovedFourPlus) leaves out voided pieces, exactly like the list it opens (/pieces?minTrips=4);
 * (2) a request parameter of the wrong type or a missing required one is a 400 with the standard
 * {error, message} body app-wide — analytics and non-analytics — never the catch-all 500, and the
 * message names the parameter without echoing the value.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class PrelaunchFixesTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("traceability_test")
                    .withUsername("postgres")
                    .withPassword("postgres");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",      POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        r.add("spring.flyway.url",          POSTGRES::getJdbcUrl);
        r.add("spring.flyway.user",         POSTGRES::getUsername);
        r.add("spring.flyway.password",     POSTGRES::getPassword);
        r.add("analytics.settlement.refresh-enabled", () -> "false");
    }

    static final AtomicLong SEQ = new AtomicLong(System.nanoTime() % 1_000_000_000L);

    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtService jwt;

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    record Tenant(UUID id, UUID variant, String token) {}

    Tenant tenant() {
        UUID id = UUID.randomUUID(), owner = UUID.randomUUID(), store = UUID.randomUUID(), product = UUID.randomUUID(), variant = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, 'Prelaunch')", id);
        jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role) VALUES (?, ?, 'O', ?, 'x', 'owner')",
                    owner, id, "o+" + owner + "@pre.test");
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status) VALUES (?, ?, 'shopify', ?, 'connected')",
                    store, id, "pre-" + id + ".myshopify.com");
        jdbc.update("INSERT INTO products (id, tenant_id, store_id, external_id, title) VALUES (?, ?, ?, ?, 'Tee')", product, id, store, "P-" + product);
        jdbc.update("INSERT INTO variants (id, tenant_id, product_id, external_id, sku, title, price) VALUES (?, ?, ?, ?, 'T', 'T', 100)",
                    variant, id, product, "V-" + variant);
        return new Tenant(id, variant, jwt.issueAccessToken(owner, id, "owner"));
    }

    String pieceWithTrips(Tenant t, String status, int trips) {
        String p = "PRE" + SEQ.incrementAndGet();
        jdbc.update("INSERT INTO pieces (id, tenant_id, variant_id, barcode, short_code, status) VALUES (?, ?, ?, ?, ?, ?::piece_status)",
                    p, t.id(), t.variant(), "PC-" + p, p, status);
        for (int i = 0; i < trips; i++) {
            Instant at = Instant.now().minus(Duration.ofDays(30 - i * 2L));
            jdbc.update("INSERT INTO piece_events (tenant_id, piece_id, event_type, from_status, to_status, occurred_at) " +
                        "VALUES (?, ?, 'courier_update', 'packed', 'with_courier', ?)", t.id(), p, Timestamp.from(at));
        }
        return p;
    }

    ResponseEntity<Map> get(String token, String path) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        return rest.exchange("http://localhost:" + port + path, HttpMethod.GET, new HttpEntity<>(h), Map.class);
    }

    @Test
    void piecesMovedFourPlus_leavesOutVoidedPieces_andMatchesTheList() {
        Tenant t = tenant();
        pieceWithTrips(t, "available", 5);
        pieceWithTrips(t, "with_courier", 4);
        pieceWithTrips(t, "voided", 6);                        // a corrected entry: never counted
        pieceWithTrips(t, "available", 3);                     // under four trips
        Map<String, Object> summary = get(t.token(), "/api/v1/analytics/stock/summary?period=30d").getBody();
        Map<String, Object> list = get(t.token(), "/api/v1/analytics/pieces?minTrips=4").getBody();
        assertThat(summary.get("piecesMovedFourPlus")).isEqualTo(2);
        assertThat(summary.get("piecesMovedFourPlus")).isEqualTo(list.get("total"));
    }

    @Test
    void wrongTypeOrMissingParameter_is400WithTheStandardBody_analyticsAndNot() {
        Tenant t = tenant();
        Map<String, String> cases = new LinkedHashMap<>();
        cases.put("/api/v1/analytics/pieces?minTrips=x", "minTrips");             // analytics, query int
        cases.put("/api/v1/analytics/variants/not-a-uuid/orders", "id");          // analytics, path UUID
        cases.put("/api/v1/orders?page=abc", "page");                              // non-analytics, query int
        cases.put("/api/v1/analytics/revenue/breakdown?period=30d", "by");         // missing required parameter
        for (Map.Entry<String, String> c : cases.entrySet()) {
            ResponseEntity<Map> r = get(t.token(), c.getKey());
            assertThat(r.getStatusCode()).as(c.getKey()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(r.getBody()).as(c.getKey()).containsEntry("error", "BAD_REQUEST_PARAM");
            assertThat((String) r.getBody().get("message")).as(c.getKey()).contains("'" + c.getValue() + "'");
        }
        // The value the caller sent is never echoed back.
        ResponseEntity<Map> echo = get(t.token(), "/api/v1/orders?page=SECRET123");
        assertThat(echo.getBody().toString()).doesNotContain("SECRET123");
    }
}
