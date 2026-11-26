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

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Bazar POS B1 — the HTTP boundary: flag off → 404 on every endpoint, workers → 403, the generic Transfers endpoints
 * answer 409 BAZAR_TRANSFER_LOCKED for a bazar's transfer, and the Transfers list / detail tag it with its event.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BazarHttpTest {

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

    UUID onTenant, offTenant;
    String ownerOn, workerOn, ownerOff;

    @BeforeAll
    void setup() {
        onTenant = UUID.randomUUID();
        offTenant = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name, bazar_enabled) VALUES (?, 'Bazar HTTP On', true)", onTenant);
        jdbc.update("INSERT INTO tenants (id, name, bazar_enabled) VALUES (?, 'Bazar HTTP Off', false)", offTenant);
        for (UUID t : new UUID[] { onTenant, offTenant }) {
            jdbc.update("INSERT INTO locations (id, tenant_id, name, type, is_default, is_fulfillment) " +
                "VALUES (gen_random_uuid(), ?, 'Main Warehouse', 'warehouse', true, true)", t);
        }
        ownerOn  = login(user(onTenant, "owner"));
        workerOn = login(user(onTenant, "worker"));
        ownerOff = login(user(offTenant, "owner"));
    }

    private String user(UUID tenant, String role) {
        String email = role + "-" + UUID.randomUUID() + "@bazar-http.test";
        jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role, active) " +
            "VALUES (gen_random_uuid(), ?, ?, ?, ?, ?::user_role, true)",
            tenant, role, email, passwordEncoder.encode("pass123"), role);
        return email;
    }

    private String login(String email) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<AccessTokenResponse> resp = rest.postForEntity(base() + "/api/v1/auth/login",
            new HttpEntity<>(Map.of("email", email, "password", "pass123"), h), AccessTokenResponse.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        return resp.getBody().accessToken();
    }

    private String base() { return "http://localhost:" + port; }

    @SuppressWarnings("rawtypes")
    private ResponseEntity<Map> call(String token, HttpMethod method, String path, Object body) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        h.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange(base() + path, method, new HttpEntity<>(body, h), Map.class);
    }

    private static Map<String, Object> createBody(String hall) {
        return Map.of("name", "HTTP Bazar", "startsOn", "2026-12-01", "endsOn", "2026-12-02", "locationName", hall);
    }

    @Test
    void flagOff_everyEndpointIs404() {
        String id = UUID.randomUUID().toString();
        String v  = UUID.randomUUID().toString();
        Object[][] calls = {
            { HttpMethod.GET,    "/api/v1/bazar-events", null },
            { HttpMethod.POST,   "/api/v1/bazar-events", createBody("Off Hall") },
            { HttpMethod.GET,    "/api/v1/bazar-events/" + id, null },
            { HttpMethod.POST,   "/api/v1/bazar-events/" + id + "/scan-out", Map.of("barcode", "P000001") },
            { HttpMethod.POST,   "/api/v1/bazar-events/" + id + "/mark-sent", Map.of() },
            { HttpMethod.POST,   "/api/v1/bazar-events/" + id + "/start", Map.of() },
            { HttpMethod.GET,    "/api/v1/bazar-events/" + id + "/prices", null },
            { HttpMethod.PUT,    "/api/v1/bazar-events/" + id + "/bulk-rule", Map.of("discountPct", 10) },
            { HttpMethod.PUT,    "/api/v1/bazar-events/" + id + "/prices/" + v, Map.of("price", 100) },
            { HttpMethod.DELETE, "/api/v1/bazar-events/" + id + "/prices/" + v + "/override", null },
        };
        for (Object[] c : calls) {
            @SuppressWarnings("rawtypes")
            ResponseEntity<Map> r = call(ownerOff, (HttpMethod) c[0], (String) c[1], c[2]);
            assertThat(r.getStatusCode()).as(c[0] + " " + c[1]).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(r.getBody().get("code")).as(c[0] + " " + c[1]).isEqualTo("BAZAR_NOT_ENABLED");
        }
    }

    @Test
    void worker_is403() {
        assertThat(call(workerOn, HttpMethod.GET, "/api/v1/bazar-events", null).getStatusCode())
            .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(call(workerOn, HttpMethod.POST, "/api/v1/bazar-events", createBody("Worker Hall")).getStatusCode())
            .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void owner_creates_andTheGenericTransfersEndpointsAreLocked_andTagTheEvent() {
        ResponseEntity<Map> created = call(ownerOn, HttpMethod.POST, "/api/v1/bazar-events", createBody("HTTP Hall"));
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String eventId = (String) created.getBody().get("id");
        String transferId = (String) created.getBody().get("transfer_id");
        assertThat(((Map) created.getBody().get("counts")).get("sent")).isEqualTo(0);

        ResponseEntity<Map> cancel = call(ownerOn, HttpMethod.POST, "/api/v1/transfers/" + transferId + "/cancel", Map.of());
        assertThat(cancel.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(cancel.getBody().get("code")).isEqualTo("BAZAR_TRANSFER_LOCKED");
        assertThat(cancel.getBody().get("message_en")).isNotNull();
        assertThat(cancel.getBody().get("message_ar")).isNotNull();

        ResponseEntity<Map> scan = call(ownerOn, HttpMethod.POST, "/api/v1/transfers/" + transferId + "/scan-out",
            Map.of("barcode", "P000001"));
        assertThat(scan.getBody().get("code")).isEqualTo("BAZAR_TRANSFER_LOCKED");

        ResponseEntity<Map> createBazarType = call(ownerOn, HttpMethod.POST, "/api/v1/transfers",
            Map.of("transferType", "bazar", "destinationLocationId", created.getBody().get("location_id")));
        assertThat(createBazarType.getBody().get("code")).isEqualTo("BAZAR_TRANSFER_LOCKED");

        ResponseEntity<Map> detail = call(ownerOn, HttpMethod.GET, "/api/v1/transfers/" + transferId, null);
        assertThat(detail.getBody().get("transfer_type")).isEqualTo("bazar");
        assertThat(detail.getBody().get("bazar_event_id")).isEqualTo(eventId);
        assertThat(detail.getBody().get("bazar_event_name")).isEqualTo("HTTP Bazar");

        ResponseEntity<java.util.List> list = rest.exchange(base() + "/api/v1/transfers?status=all", HttpMethod.GET,
            new HttpEntity<>(null, bearer(ownerOn)), java.util.List.class);
        Map row = (Map) list.getBody().stream()
            .filter(t -> transferId.equals(((Map) t).get("id"))).findFirst().orElseThrow();
        assertThat(row.get("bazar_event_id")).isEqualTo(eventId);

        ResponseEntity<Map> get = call(ownerOn, HttpMethod.GET, "/api/v1/bazar-events/" + eventId, null);
        assertThat(get.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(get.getBody().get("status")).isEqualTo("draft");
    }

    private HttpHeaders bearer(String token) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        return h;
    }
}
