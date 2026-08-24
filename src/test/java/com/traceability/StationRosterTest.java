package com.traceability;

import com.traceability.identity.JwtService;
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

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Worker Station Gate (Phase C) — GET /api/v1/station/roster.
 *
 * Tenant isolation, the active+pin_code-not-null filter, and the locked flag's accuracy
 * are the properties this endpoint exists to get right; it's EXEMPT (not COVERED) in
 * RlsCoverageTest with a pointer to this file.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class StationRosterTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("traceability_test")
                    .withUsername("postgres")
                    .withPassword("postgres");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry r) {
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
    @Autowired JwtService       jwtService;
    @Autowired PasswordEncoder  encoder;

    private String base() { return "http://localhost:" + port; }

    @Test
    void roster_isTenantScoped_activePinHoldersOnly_withAccurateLockedFlag() {
        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, 'RosterTenantA')", tenantA);
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, 'RosterTenantB')", tenantB);

        UUID ownerA = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO users (id, tenant_id, name, email, password_hash, role) " +
                "VALUES (?, ?, 'Owner A', ?, 'x', 'owner')",
                ownerA, tenantA, "roster-ownerA+" + ownerA + "@wsb.test");

        UUID workerA1 = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO users (id, tenant_id, name, email, password_hash, role, pin_code, active) " +
                "VALUES (?, ?, 'Amina', ?, 'x', 'worker', ?, true)",
                workerA1, tenantA, "roster-a1+" + workerA1 + "@wsb.test", encoder.encode("1111"));

        UUID workerA2Locked = UUID.randomUUID();
        Timestamp lockedUntil = Timestamp.from(Instant.now().plusSeconds(900));
        jdbc.update(
                "INSERT INTO users (id, tenant_id, name, email, password_hash, role, pin_code, active, " +
                "    pin_fail_count, pin_locked_until) " +
                "VALUES (?, ?, 'Zaid', ?, 'x', 'worker', ?, true, 5, ?)",
                workerA2Locked, tenantA, "roster-a2+" + workerA2Locked + "@wsb.test",
                encoder.encode("2222"), lockedUntil);

        // Deactivated PIN-holder — must NOT appear.
        UUID workerA3Inactive = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO users (id, tenant_id, name, email, password_hash, role, pin_code, active) " +
                "VALUES (?, ?, 'Deactivated Dana', ?, 'x', 'worker', ?, false)",
                workerA3Inactive, tenantA, "roster-a3+" + workerA3Inactive + "@wsb.test", encoder.encode("3333"));

        // Active worker with no PIN set up yet — must NOT appear.
        UUID workerA4NoPin = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO users (id, tenant_id, name, email, password_hash, role, active) " +
                "VALUES (?, ?, 'No-PIN Nadia', ?, 'x', 'worker', true)",
                workerA4NoPin, tenantA, "roster-a4+" + workerA4NoPin + "@wsb.test");

        UUID workerB1 = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO users (id, tenant_id, name, email, password_hash, role, pin_code, active) " +
                "VALUES (?, ?, 'Basil', ?, 'x', 'worker', ?, true)",
                workerB1, tenantB, "roster-b1+" + workerB1 + "@wsb.test", encoder.encode("4444"));

        String ownerAToken  = jwtService.issueAccessToken(ownerA, tenantA, "owner");
        String workerB1Token = jwtService.issueAccessToken(workerB1, tenantB, "worker");

        // Tenant A, called with an OWNER token: only the two PIN-holding active workers,
        // owner herself excluded (no pin_code), deactivated and no-PIN workers excluded,
        // locked flag correct for each.
        ResponseEntity<List> respA = rest.exchange(
                base() + "/api/v1/station/roster", HttpMethod.GET,
                new HttpEntity<>(bearerHeaders(ownerAToken)), List.class);
        assertThat(respA.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rosterA = respA.getBody();
        assertThat(rosterA).hasSize(2);
        assertThat(rosterA).extracting(r -> r.get("name")).containsExactly("Amina", "Zaid"); // ORDER BY name
        Map<String, Object> amina = rosterA.get(0);
        Map<String, Object> zaid  = rosterA.get(1);
        assertThat(amina.get("id")).isEqualTo(workerA1.toString());
        assertThat(amina.get("locked")).isEqualTo(false);
        assertThat(amina.get("lockedUntil"))
                .as("not locked — lockedUntil must not leak a value")
                .isNull();
        assertThat(zaid.get("id")).isEqualTo(workerA2Locked.toString());
        assertThat(zaid.get("locked"))
                .as("pin_locked_until is in the future — locked flag must be true")
                .isEqualTo(true);
        assertThat(zaid.get("lockedUntil"))
                .as("locked — lockedUntil must be present for the gate's countdown")
                .isNotNull();

        // Tenant B, called with a WORKER token (not owner/manager) — must succeed (not 403)
        // and see only tenant B's own roster, proving the endpoint is both worker-callable
        // and tenant-isolated.
        ResponseEntity<List> respB = rest.exchange(
                base() + "/api/v1/station/roster", HttpMethod.GET,
                new HttpEntity<>(bearerHeaders(workerB1Token)), List.class);
        assertThat(respB.getStatusCode())
                .as("a WORKER token must be able to call the roster endpoint (not 403)")
                .isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rosterB = respB.getBody();
        assertThat(rosterB).hasSize(1);
        assertThat(rosterB.get(0).get("id")).isEqualTo(workerB1.toString());
        assertThat(rosterB.get(0).get("name")).isEqualTo("Basil");
    }

    private static HttpHeaders bearerHeaders(String token) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        return h;
    }
}
