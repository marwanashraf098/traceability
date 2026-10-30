package com.traceability;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.traceability.notifications.EmailGateway;
import org.jobrunr.scheduling.JobScheduler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Flexible labeling for everyone (V194, 2026-10-29) — new tenants.
 *   n1 — the column default is true: a new signup's tenant has the switch on, and /me says flexibleLabeling: true
 *   n2 — the switch stays an admin-only kill switch: ops can still turn one tenant off (direct UPDATE) and /me
 *        follows it; no API writes the column (LabelSettingsController's own doc; nothing in src/main updates it)
 * Existing tenants (the UPDATE): FlexibleLabelingRolloutMigrationTest.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FlexibleLabelingRolloutTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            TestPostgres.newContainer()
                    .withDatabaseName("traceability")
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
    @Autowired JdbcTemplate jdbc;   // postgres (BYPASSRLS) — verification only
    @Autowired ObjectMapper mapper;
    @MockBean EmailGateway emailGateway;
    @MockBean JobScheduler jobScheduler;

    private final RestTemplate rest = new RestTemplate();

    @Test
    void n1_newSignup_switchOn() throws Exception {
        String email = "fl" + UUID.randomUUID().toString().substring(0, 8) + "@shop.test";
        String token = signup(email);

        assertThat(jdbc.queryForObject(
            "SELECT column_default FROM information_schema.columns WHERE table_name = 'tenants' " +
            "AND column_name = 'flexible_labeling_enabled'", String.class)).isEqualTo("true");
        assertThat(jdbc.queryForObject("SELECT t.flexible_labeling_enabled FROM tenants t JOIN users u ON u.tenant_id = t.id " +
            "WHERE u.email = ?", Boolean.class, email)).isTrue();
        assertThat(me(token).path("flexibleLabeling").asBoolean(false)).isTrue();
    }

    @Test
    void n2_killSwitch_opsCanStillTurnOneTenantOff() throws Exception {
        String email = "fl" + UUID.randomUUID().toString().substring(0, 8) + "@shop.test";
        String token = signup(email);
        UUID tenant = jdbc.queryForObject("SELECT tenant_id FROM users WHERE email = ?", UUID.class, email);

        jdbc.update("UPDATE tenants SET flexible_labeling_enabled = false WHERE id = ?", tenant);   // ops, by hand

        assertThat(me(token).path("flexibleLabeling").asBoolean(true)).isFalse();
    }

    private String signup(String email) throws Exception {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> res = rest.postForEntity("http://localhost:" + port + "/api/v1/auth/signup",
            new HttpEntity<>(Map.of("tenantName", "Rollout Co", "name", "Owner", "email", email,
                "phone", "01012345678", "password", "password99", "consent", true), h), String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return mapper.readTree(res.getBody()).path("accessToken").asText();
    }

    private JsonNode me(String token) throws Exception {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        ResponseEntity<String> res = rest.exchange("http://localhost:" + port + "/api/v1/me", HttpMethod.GET,
            new HttpEntity<>(h), String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        return mapper.readTree(res.getBody());
    }
}
