package com.traceability;

import com.traceability.identity.JwtService;
import com.traceability.tenancy.TenantAwareDataSource;
import com.traceability.tenancy.TenantContext;
import com.traceability.analytics.StockAnalyticsService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
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
 * GET /api/v1/analytics/pieces?minTrips=4&limit= — the tenant-wide list behind Stock health's
 * "Pieces moved 4+ times": the s4 trip rule (reserved / packed / awaiting pickup → with courier or
 * delivered), voided pieces left out, most trips first, total = all matching, owner only, validated,
 * and app_user RLS keeps it to the tenant's own pieces.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class AnalyticsTripPiecesTest {

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

    final class T {
        final UUID id = UUID.randomUUID(), owner = UUID.randomUUID(), store = UUID.randomUUID(), product = UUID.randomUUID();
        final UUID variant = UUID.randomUUID();
        final String token;

        T(String name) {
            jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?)", id, name);
            jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role) VALUES (?, ?, 'O', ?, 'x', 'owner')",
                        owner, id, "o+" + owner + "@trips.test");
            jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status) VALUES (?, ?, 'shopify', ?, 'connected')",
                        store, id, "trips-" + id + ".myshopify.com");
            jdbc.update("INSERT INTO products (id, tenant_id, store_id, external_id, title) VALUES (?, ?, ?, ?, 'Boxy Tee')", product, id, store, "P-" + product);
            jdbc.update("INSERT INTO variants (id, tenant_id, product_id, external_id, sku, title, price) VALUES (?, ?, ?, ?, 'BXT-L', 'Black / L', 650)",
                        variant, id, product, "V-" + variant);
            token = jwt.issueAccessToken(owner, id, "owner");
        }

        String piece(String status) {
            String p = "TRP" + SEQ.incrementAndGet();
            jdbc.update("INSERT INTO pieces (id, tenant_id, variant_id, barcode, short_code, status) VALUES (?, ?, ?, ?, ?, ?::piece_status)",
                        p, id, variant, "PC-" + p, p, status);
            return p;
        }

        /** {@code n} trips: packed → with_courier, then a non-trip move back. */
        void trips(String piece, int n) {
            for (int i = 0; i < n; i++) {
                Instant at = Instant.now().minus(Duration.ofDays(40 - i * 3L));
                event(piece, "packed", "with_courier", at);
                event(piece, "with_courier", "return_in_transit", at.plus(Duration.ofDays(1)));
            }
        }

        void event(String piece, String from, String to, Instant at) {
            jdbc.update("INSERT INTO piece_events (tenant_id, piece_id, event_type, from_status, to_status, occurred_at) " +
                        "VALUES (?, ?, 'courier_update', ?::piece_status, ?::piece_status, ?)", id, piece, from, to, Timestamp.from(at));
        }

        String token(String role) {
            UUID u = UUID.randomUUID();
            jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role) VALUES (?, ?, 'U', ?, 'x', ?::user_role)",
                        u, id, role + "+" + u + "@trips.test", role);
            return jwt.issueAccessToken(u, id, role);
        }
    }

    ResponseEntity<Map> get(String token, String q) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        return rest.exchange("http://localhost:" + port + "/api/v1/analytics/pieces" + q, HttpMethod.GET, new HttpEntity<>(h), Map.class);
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> pieces(Map<String, Object> body) {
        return (List<Map<String, Object>>) body.get("pieces");
    }

    @Test
    void fourPlusTrips_mostFirst_voidedLeftOut_totalCountsAll() {
        T t = new T("Trips");
        String six = t.piece("available"), five = t.piece("with_courier"), four = t.piece("available"),
            three = t.piece("available"), voided = t.piece("voided");
        t.trips(six, 6);
        t.trips(five, 5);
        t.trips(four, 4);
        t.trips(three, 3);
        t.trips(voided, 7);
        t.event(four, "available", "reserved", Instant.now());             // not a trip
        t.event(four, "reserved", "packed", Instant.now());                // not a trip

        Map<String, Object> body = get(t.token, "").getBody();             // default minTrips = 4
        assertThat(body.get("minTrips")).isEqualTo(4);
        assertThat(body.get("total")).isEqualTo(3);
        List<Map<String, Object>> rows = pieces(body);
        assertThat(rows.stream().map(r -> r.get("pieceId")).toList()).containsExactly(six, five, four);
        assertThat(rows.get(0)).containsEntry("trips", 6).containsEntry("productTitle", "Boxy Tee")
            .containsEntry("variantTitle", "Black / L").containsEntry("sku", "BXT-L").containsEntry("variantId", t.variant.toString());
        assertThat(rows.get(1)).containsEntry("status", "with_courier");

        Map<String, Object> limited = get(t.token, "?minTrips=3&limit=2").getBody();
        assertThat(limited.get("total")).isEqualTo(4);                     // all with 3+, not the page
        assertThat(pieces(limited)).hasSize(2);
    }

    @Test
    void validation_andOwnerOnly() {
        T t = new T("TripsRoles");
        for (String q : List.of("?minTrips=0", "?limit=0", "?limit=501")) {
            assertThat(get(t.token, q).getStatusCode()).as(q).isEqualTo(HttpStatus.BAD_REQUEST);
        }
        assertThat(get(t.token("manager"), "").getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(get(t.token("worker"), "").getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void appUser_rls_seesOnlyTheTenantsPieces() {
        T a = new T("TripsA");
        T b = new T("TripsB");
        a.trips(a.piece("available"), 4);
        b.trips(b.piece("available"), 5);
        TenantAwareDataSource ds = new TenantAwareDataSource(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "app_user", "testpw"));
        StockAnalyticsService svc = new StockAnalyticsService(new JdbcTemplate(ds), null, null, null, null);
        TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        TenantContext.set(a.id);
        StockAnalyticsService.TripPieces seen = tx.execute(s -> svc.tripPieces(4, 100));
        assertThat(seen.total()).isEqualTo(1);
        assertThat(seen.pieces()).allMatch(p -> p.variantId().equals(a.variant));
    }
}
