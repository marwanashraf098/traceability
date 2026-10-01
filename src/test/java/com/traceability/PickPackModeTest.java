package com.traceability;

import com.traceability.identity.JwtService;
import org.jobrunr.scheduling.JobScheduler;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pick &amp; Pack S3 commit 3 — packing mode: owner-only write through PUT /tenant/settings
 * {pickPackMode}; GET /fulfill/mode readable by every role; other tenants unaffected.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PickPackModeTest {

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
    @Autowired TestRestTemplate rest;
    @Autowired JdbcTemplate     jdbc;
    @Autowired JwtService       jwt;
    @MockBean  JobScheduler     jobScheduler;

    UUID tenant, otherTenant;
    String owner, manager, worker, otherOwner;

    @BeforeAll
    void setup() {
        tenant = tenant("ModeCo");
        otherTenant = tenant("ModeOther");
        owner      = token(tenant, "owner");
        manager    = token(tenant, "manager");
        worker     = token(tenant, "worker");
        otherOwner = token(otherTenant, "owner");
    }

    @Test
    void ownerCanSwitchMode_everyRoleCanReadIt_otherTenantUnaffected() {
        assertThat(mode(worker)).isEqualTo("order_queue");

        assertThat(put(owner, Map.of("pickPackMode", "waybill_scan")).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(mode(worker)).isEqualTo("waybill_scan");
        assertThat(mode(manager)).isEqualTo("waybill_scan");
        assertThat(mode(owner)).isEqualTo("waybill_scan");
        assertThat(mode(otherOwner)).isEqualTo("order_queue");

        // Manager reads it in Settings (read-only).
        ResponseEntity<Map> settings = rest.exchange(url("/api/v1/tenant/settings"), HttpMethod.GET,
            new HttpEntity<>(auth(manager)), Map.class);
        assertThat(settings.getBody().get("pickPackMode")).isEqualTo("waybill_scan");

        // Other fields untouched by a mode-only PUT (COALESCE).
        assertThat(put(owner, Map.of("pickPackMode", "order_queue")).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(mode(worker)).isEqualTo("order_queue");
    }

    @Test
    void managerAndWorkerCannotChangeMode() {
        assertThat(put(manager, Map.of("pickPackMode", "waybill_scan")).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(put(worker, Map.of("pickPackMode", "waybill_scan")).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(mode(worker)).isEqualTo("order_queue");
    }

    @Test
    void unknownMode_isRejected() {
        assertThat(put(owner, Map.of("pickPackMode", "conveyor")).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String mode(String token) {
        ResponseEntity<Map> r = rest.exchange(url("/api/v1/fulfill/mode"), HttpMethod.GET,
            new HttpEntity<>(auth(token)), Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        return (String) r.getBody().get("mode");
    }

    private ResponseEntity<String> put(String token, Map<String, Object> body) {
        HttpHeaders h = auth(token);
        h.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange(url("/api/v1/tenant/settings"), HttpMethod.PUT, new HttpEntity<>(body, h), String.class);
    }

    private HttpHeaders auth(String token) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        return h;
    }

    private String url(String path) { return "http://localhost:" + port + path; }

    private UUID tenant(String name) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?)", id, name);
        return id;
    }

    private String token(UUID tenant, String role) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role) " +
                    "VALUES (?, ?, ?, ?, 'x', ?::user_role)",
                    id, tenant, role, role + "-" + id + "@test.com", role);
        return jwt.issueAccessToken(id, tenant, role);
    }
}
