package com.traceability.labels;

import com.traceability.TestPostgres;
import com.traceability.identity.JwtService;
import org.junit.jupiter.api.*;
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

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Flexible labeling slice 8 — Settings → Labels (GET / PUT /api/v1/label-settings).
 *   s1  the default is print_stick; an owner saves count_only and reads it back; a manager may change it too
 *   s2  a worker is refused (403); an unknown mode is 400 and changes nothing
 *   s3  switch off: 404 for both, the stored value untouched
 *   s4  the column: NOT NULL, default 'print_stick', CHECK refuses anything else
 *   s5  there is no way through this API to switch flexible labeling itself on or off
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LabelSettingsTest {

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
    @Autowired JwtService jwt;
    @Autowired JdbcTemplate jdbc;

    UUID tenant, owner, manager, worker;

    @BeforeAll
    void setup() {
        tenant = UUID.randomUUID(); owner = UUID.randomUUID(); manager = UUID.randomUUID(); worker = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name, flexible_labeling_enabled) VALUES (?, 'LS Tenant', true)", tenant);
        jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role) VALUES " +
            "(?, ?, 'O', 'ls-o@test.local', 'h', 'owner'), (?, ?, 'M', 'ls-m@test.local', 'h', 'manager'), " +
            "(?, ?, 'W', 'ls-w@test.local', 'h', 'worker')", owner, tenant, manager, tenant, worker, tenant);
    }

    @BeforeEach
    void each() {
        jdbc.update("UPDATE tenants SET flexible_labeling_enabled = true, receiving_default_mode = DEFAULT WHERE id = ?", tenant);
    }

    @Test
    void s1_default_saved_and_read_back_by_owner_and_manager() {
        assertThat(get(owner, "owner").getBody()).containsEntry("receivingDefaultMode", "print_stick");
        ResponseEntity<Map> saved = put(owner, "owner", "count_only");
        assertThat(saved.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(get(owner, "owner").getBody()).containsEntry("receivingDefaultMode", "count_only");
        assertThat(mode()).isEqualTo("count_only");

        assertThat(put(manager, "manager", "print_stick").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(get(manager, "manager").getBody()).containsEntry("receivingDefaultMode", "print_stick");
    }

    @Test
    void s2_worker_refused_and_unknown_mode_rejected() {
        assertThat(get(worker, "worker").getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(put(worker, "worker", "count_only").getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(put(owner, "owner", "blank_labels").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(mode()).isEqualTo("print_stick");
    }

    @Test
    void s3_switch_off_is_404_and_changes_nothing() {
        jdbc.update("UPDATE tenants SET flexible_labeling_enabled = false, receiving_default_mode = 'count_only' WHERE id = ?", tenant);
        assertThat(get(owner, "owner").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(put(owner, "owner", "print_stick").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(mode()).isEqualTo("count_only");
    }

    @Test
    void s4_the_column_defaults_and_refuses_unknown_modes() {
        UUID t = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, 'LS New')", t);
        assertThat(jdbc.queryForObject("SELECT receiving_default_mode FROM tenants WHERE id = ?", String.class, t)).isEqualTo("print_stick");
        assertThatThrownBy(() -> jdbc.update("UPDATE tenants SET receiving_default_mode = 'other' WHERE id = ?", t))
            .hasMessageContaining("receiving_default_mode");
        assertThatThrownBy(() -> jdbc.update("UPDATE tenants SET receiving_default_mode = NULL WHERE id = ?", t))
            .hasMessageContaining("receiving_default_mode");
        jdbc.update("DELETE FROM tenants WHERE id = ?", t);
    }

    @Test
    void s5_the_switch_itself_is_not_writable_here() {
        HttpHeaders h = auth(owner, "owner");
        h.setContentType(MediaType.APPLICATION_JSON);
        rest.exchange(base() + "/api/v1/label-settings", HttpMethod.PUT,
            new HttpEntity<>(Map.of("receivingDefaultMode", "count_only", "flexibleLabelingEnabled", false), h), Map.class);
        assertThat(jdbc.queryForObject("SELECT flexible_labeling_enabled FROM tenants WHERE id = ?", Boolean.class, tenant)).isTrue();
    }

    private String mode() {
        return jdbc.queryForObject("SELECT receiving_default_mode FROM tenants WHERE id = ?", String.class, tenant);
    }

    private ResponseEntity<Map> get(UUID user, String role) {
        return rest.exchange(base() + "/api/v1/label-settings", HttpMethod.GET, new HttpEntity<>(auth(user, role)), Map.class);
    }

    private ResponseEntity<Map> put(UUID user, String role, String mode) {
        HttpHeaders h = auth(user, role);
        h.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange(base() + "/api/v1/label-settings", HttpMethod.PUT,
            new HttpEntity<>(Map.of("receivingDefaultMode", mode), h), Map.class);
    }

    private HttpHeaders auth(UUID user, String role) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(jwt.issueAccessToken(user, tenant, role));
        return h;
    }

    private String base() { return "http://localhost:" + port; }
}
