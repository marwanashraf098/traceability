package com.traceability;

import com.traceability.identity.model.LoginRequest;
import com.traceability.identity.model.TokenResponse;
import com.traceability.integrations.bosta.BostaGateway;
import com.traceability.review.OpsSecretGuard;
import com.traceability.review.ReviewTenantSeeder;
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

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Review mode S5 — the review tenant end to end: create (step A), flag (step B, the ops SQL), seed (C).
 *
 *   o1 ops endpoints: missing / wrong X-Ops-Secret → 403 (unset secret → 404: OpsSecretGuardTest);
 *      a non-@tracedtech.com owner → 400 NOT_INTERNAL_EMAIL
 *   o2 step A: tenant + owner (logs in with the caller's password) + default fulfillment location +
 *      one PIN worker; no tenant_ad_attribution row; the password is in neither the response nor the DB
 *   o3 seed on an UNFLAGGED tenant → 409 NOT_SIMULATED, nothing written
 *   o4 seed on a flagged tenant → the whole fixture (store, catalog + our-domain images, 45 ledger-received
 *      pieces, counters past them, 13 orders in their states, one 777… forward leg each, batch #1,
 *      pickup closed, return request approved)
 *   o5 seed again → 409 FIXTURE_EXISTS, nothing changes
 *   o6 as the reviewer: the queue shows the pickable fixture orders in both Pick & Pack modes; a single
 *      print returns a simulated PDF; Bosta never called
 *   o8 the ops SQL: refuses protected / unknown / non-reviewer tenants; flags the right one; a re-run is a no-op
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ReviewTenantTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("traceability_test")
                    .withUsername("postgres")
                    .withPassword("postgres");

    static { POSTGRES.start(); }

    /** A random ops secret and owner password per run — never a literal in the source. */
    static final String OPS_SECRET = "ops-" + UUID.randomUUID();
    static final String PASSWORD   = "pw-" + UUID.randomUUID();

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",      POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        r.add("spring.flyway.url",          POSTGRES::getJdbcUrl);
        r.add("spring.flyway.user",         POSTGRES::getUsername);
        r.add("spring.flyway.password",     POSTGRES::getPassword);
        r.add("traced.ops-secret",          () -> OPS_SECRET);
    }

    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;
    @Autowired JdbcTemplate     jdbc;
    @MockBean  BostaGateway     bostaGateway;
    @MockBean  JobScheduler     jobScheduler;

    private static final String REVIEWER = "reviewer@tracedtech.com";
    private UUID reviewTenant;

    // ── o1 ───────────────────────────────────────────────────────────────────

    @Test @Order(1)
    void o1_missingOrWrongSecret_forbidden_nonInternalEmail_refused() {
        assertThat(ops("/api/v1/ops/review-tenant", createBody("x@tracedtech.com"), null).getStatusCode())
            .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(ops("/api/v1/ops/review-tenant", createBody("x@tracedtech.com"), "wrong").getStatusCode())
            .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(ops("/api/v1/ops/review-tenant/" + UUID.randomUUID() + "/seed", null, "wrong").getStatusCode())
            .isEqualTo(HttpStatus.FORBIDDEN);

        ResponseEntity<Map> external = ops("/api/v1/ops/review-tenant", createBody("someone@gmail.com"), OPS_SECRET);
        assertThat(external.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(external.getBody()).containsEntry("code", "NOT_INTERNAL_EMAIL");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE email IN ('x@tracedtech.com','someone@gmail.com')",
            Integer.class)).isZero();
    }

    // ── o2 ───────────────────────────────────────────────────────────────────

    @Test @Order(2)
    void o2_create_tenantOwnerLocationWorker_noAttribution_passwordNeverEchoed() {
        ResponseEntity<String> raw = rest.exchange(base() + "/api/v1/ops/review-tenant", HttpMethod.POST,
            new HttpEntity<>(createBody(REVIEWER), opsHeaders(OPS_SECRET)), String.class);
        assertThat(raw.getStatusCode()).as(raw.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(raw.getBody()).doesNotContain(PASSWORD);

        UUID tenant = jdbc.queryForObject("SELECT tenant_id FROM users WHERE email = ?", UUID.class, REVIEWER);
        reviewTenant = tenant;
        assertThat(raw.getBody()).contains(tenant.toString());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM locations WHERE tenant_id = ? AND is_fulfillment", Integer.class, tenant))
            .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE tenant_id = ? AND role = 'worker' AND pin_code IS NOT NULL",
            Integer.class, tenant)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tenant_ad_attribution WHERE tenant_id = ?", Integer.class, tenant))
            .isZero();
        assertThat(jdbc.queryForObject("SELECT password_hash FROM users WHERE email = ?", String.class, REVIEWER))
            .doesNotContain(PASSWORD).startsWith("$argon2");

        ResponseEntity<TokenResponse> login = rest.postForEntity(base() + "/api/v1/auth/login",
            new LoginRequest(REVIEWER, PASSWORD), TokenResponse.class);
        assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(login.getBody().accessToken()).isNotBlank();
    }

    // ── o3 ───────────────────────────────────────────────────────────────────

    @Test @Order(3)
    void o3_seedUnflagged_refused_nothingWritten() {
        ResponseEntity<Map> r = ops("/api/v1/ops/review-tenant/" + reviewTenant + "/seed", null, OPS_SECRET);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(r.getBody()).containsEntry("code", "NOT_SIMULATED");
        assertThat(count("orders", reviewTenant)).isZero();
        assertThat(count("stores", reviewTenant)).isZero();
    }

    // ── o8 (flags the tenant the next tests seed) ────────────────────────────

    @Test @Order(4)
    void o8_opsSql_refusesProtectedUnknownAndNonReviewer_flagsTheReviewTenant_rerunIsNoOp() throws Exception {
        assertThatThrownBy(() -> runFlagSql(UUID.fromString("07fc572c-2158-412d-ae31-ec61e22378b7")))
            .hasMessageContaining("protected tenant");
        assertThatThrownBy(() -> runFlagSql(UUID.randomUUID())).hasMessageContaining("does not exist");
        UUID other = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, 'Not the reviewer')", other);
        jdbc.update("INSERT INTO users (tenant_id, name, email, password_hash, role) VALUES (?, 'O', ?, 'x', 'owner')",
            other, "owner-" + other + "@tracedtech.com");
        assertThatThrownBy(() -> runFlagSql(other)).hasMessageContaining("is not reviewer@tracedtech.com");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tenant_courier_simulation", Integer.class)).isZero();

        runFlagSql(reviewTenant);
        runFlagSql(reviewTenant);   // already flagged → NOTICE, no error
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tenant_courier_simulation WHERE tenant_id = ?",
            Integer.class, reviewTenant)).isEqualTo(1);
    }

    // ── o4 ───────────────────────────────────────────────────────────────────

    @Test @Order(5)
    void o4_seed_buildsTheWholeFixture() {
        ResponseEntity<Map> r = ops("/api/v1/ops/review-tenant/" + reviewTenant + "/seed", null, OPS_SECRET);
        assertThat(r.getStatusCode()).as(String.valueOf(r.getBody())).isEqualTo(HttpStatus.OK);
        ReviewFixtureAssertions.assertWholeFixture(jdbc, reviewTenant);
        verifyNoInteractions(bostaGateway);
    }

    // ── o5 ───────────────────────────────────────────────────────────────────

    @Test @Order(6)
    void o5_seedTwice_refused_nothingChanges() {
        int orders = count("orders", reviewTenant), pieces = count("pieces", reviewTenant);
        ResponseEntity<Map> r = ops("/api/v1/ops/review-tenant/" + reviewTenant + "/seed", null, OPS_SECRET);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(r.getBody()).containsEntry("code", "FIXTURE_EXISTS");
        assertThat(count("orders", reviewTenant)).isEqualTo(orders);
        assertThat(count("pieces", reviewTenant)).isEqualTo(pieces);
    }

    // ── o6 ───────────────────────────────────────────────────────────────────

    @Test @Order(7)
    @SuppressWarnings("unchecked")
    void o6_asTheReviewer_queueInBothModes_simulatedPrint_noBosta() {
        String token = rest.postForEntity(base() + "/api/v1/auth/login", new LoginRequest(REVIEWER, PASSWORD),
            TokenResponse.class).getBody().accessToken();
        Set<String> expected = new HashSet<>(List.of("#R1003", "#R1008", "#R1009", "#R1010", "#R1011", "#R1012", "#R1013"));
        for (String mode : List.of("order_queue", "waybill_scan")) {
            jdbc.update("UPDATE tenants SET pick_pack_mode = ? WHERE id = ?", mode, reviewTenant);
            List<Map<String, Object>> queue = rest.exchange(base() + "/api/v1/fulfill/queue", HttpMethod.GET,
                new HttpEntity<>(bearer(token)), List.class).getBody();
            assertThat(queue.stream().map(o -> (String) o.get("number")).toList()).as(mode)
                .containsExactlyInAnyOrderElementsOf(expected);
        }
        UUID shipment = jdbc.queryForObject("SELECT s.id FROM shipments s JOIN orders o ON o.id = s.order_id " +
            "WHERE o.tenant_id = ? AND o.number = '#R1008'", UUID.class, reviewTenant);
        ResponseEntity<Map> print = rest.exchange(base() + "/api/v1/bosta/awb/print", HttpMethod.POST,
            new HttpEntity<>(Map.of("shipmentIds", List.of(shipment)), bearer(token)), Map.class);
        assertThat(print.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((List<String>) print.getBody().get("pdfBase64List")).hasSize(1);
        verifyNoInteractions(bostaGateway);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private Map<String, Object> createBody(String email) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("tenantName", "Traced Review Store");
        b.put("ownerName", "App Reviewer");
        b.put("email", email);
        b.put("phone", "01012345678");
        b.put("password", PASSWORD);
        b.put("workerName", "Review Worker");
        b.put("workerPin", String.valueOf(1000 + new Random().nextInt(9000)));
        return b;
    }

    private ResponseEntity<Map> ops(String path, Object body, String secret) {
        return rest.exchange(base() + path, HttpMethod.POST, new HttpEntity<>(body, opsHeaders(secret)), Map.class);
    }

    private static HttpHeaders opsHeaders(String secret) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        if (secret != null) h.set(OpsSecretGuard.HEADER, secret);
        return h;
    }

    private static HttpHeaders bearer(String token) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    private int count(String table, UUID tenant) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE tenant_id = ?", Integer.class, tenant);
    }

    /** Runs scripts/ops/review-tenant-flag.sql as psql would (the :'tenant_id' variable substituted), own connection. */
    private void runFlagSql(UUID tenant) throws Exception {
        String sql = Files.readString(Path.of("scripts/ops/review-tenant-flag.sql"))
            .replace(":'tenant_id'", "'" + tenant + "'");
        try (Connection c = DriverManager.getConnection(POSTGRES.getJdbcUrl(), "postgres", "postgres");
             Statement st = c.createStatement()) {
            st.execute(sql);
        }
    }

    private String base() { return "http://localhost:" + port; }
}
