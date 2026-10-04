package com.traceability;

import com.traceability.identity.AuthService;
import com.traceability.identity.JwtService;
import com.traceability.identity.model.SignupRequest;
import com.traceability.integrations.bosta.BostaGateway;
import com.traceability.inventory.ScanHelperService;
import com.traceability.review.OpsReviewTenantController;
import com.traceability.review.ReviewCapabilities;
import com.traceability.review.ReviewTenantService;
import com.traceability.tenancy.TenantAwareDataSource;
import com.traceability.tenancy.TenantContext;
import org.jobrunr.scheduling.JobScheduler;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
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

import java.time.LocalDate;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Review mode S7 — the scanHelpers / demoMode capability on /me and GET /api/v1/scan-helpers/{context}.
 *
 *   h1 /me: a real merchant → both false; the review tenant → scanHelpers only; the public demo → both
 *   h2 a real merchant gets 404 from every context (gated server-side, not just hidden in the UI)
 *   h3 the review fixture: each context offers what the seeded flows need (waybills of the unpacked queue
 *      orders, R1001/R1002 for the pickup, R1006's piece for the return, available pieces, lookup samples)
 *   h4 every candidate is accepted by the screen's real scan endpoint (waybill → order opens, piece →
 *      scanned, pickup → ACCEPTED, return → scanned)
 *   h5 a station PIN worker gets them too; unknown context 404; pieces without variantId 400
 *   h6 app_user + RLS: another tenant's context sees none of the review tenant's candidates and reads
 *      the capability as false
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ScanHelpersTest {

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
    @Autowired TestRestTemplate    rest;
    @Autowired JdbcTemplate        jdbc;
    @Autowired JwtService          jwt;
    @Autowired AuthService         auth;
    @Autowired ReviewTenantService reviewTenants;
    @MockBean  BostaGateway        bostaGateway;
    @MockBean  JobScheduler        jobScheduler;

    private static final List<String> CONTEXTS = List.of("waybills", "pickup", "returns", "lookup");

    private UUID review, merchant, demo;
    private String reviewOwner, reviewWorker, merchantOwner, demoOwner;

    @BeforeAll
    void setUp() {
        review = UUID.fromString((String) reviewTenants.create(new OpsReviewTenantController.CreateRequest(
            "Traced Review Store", "App Reviewer", "reviewer@tracedtech.com", "01012345678", "pw-" + UUID.randomUUID(),
            "Review Worker", "4321")).get("tenantId"));
        jdbc.update("INSERT INTO tenant_courier_simulation (tenant_id, note) VALUES (?, 'review')", review);
        reviewTenants.seed(review);
        reviewOwner  = token(review, "owner");
        reviewWorker = token(review, "worker");

        auth.signup(new SignupRequest("Real Merchant", "Owner", "owner@merchant.example", "01098765432",
            "pw-" + UUID.randomUUID(), true, null), null, null);
        merchant = jdbc.queryForObject("SELECT tenant_id FROM users WHERE email = 'owner@merchant.example'", UUID.class);
        merchantOwner = token(merchant, "owner");

        demo = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name, plan, status, is_demo) VALUES (?, 'Demo', 'trial', 'trial', true)", demo);
        jdbc.update("INSERT INTO users (tenant_id, name, email, password_hash, role) VALUES (?, 'Demo', 'demo-owner@tracedtech.com', 'x', 'owner')", demo);
        demoOwner = token(demo, "owner");
    }

    // ── h1 ───────────────────────────────────────────────────────────────────

    @Test @Order(1)
    void h1_me_reportsTheCapabilities() {
        assertThat(get("/api/v1/me", merchantOwner).getBody()).containsEntry("scanHelpers", false).containsEntry("demoMode", false);
        assertThat(get("/api/v1/me", reviewOwner).getBody()).containsEntry("scanHelpers", true).containsEntry("demoMode", false);
        assertThat(get("/api/v1/me", demoOwner).getBody()).containsEntry("scanHelpers", true).containsEntry("demoMode", true);
    }

    // ── h2 ───────────────────────────────────────────────────────────────────

    @Test @Order(2)
    void h2_realMerchant_404FromEveryContext() {
        for (String c : CONTEXTS) {
            assertThat(get("/api/v1/scan-helpers/" + c, merchantOwner).getStatusCode()).as(c).isEqualTo(HttpStatus.NOT_FOUND);
        }
        UUID anyVariant = jdbc.queryForObject("SELECT id FROM variants WHERE tenant_id = ? LIMIT 1", UUID.class, review);
        assertThat(get("/api/v1/scan-helpers/pieces?variantId=" + anyVariant, merchantOwner).getStatusCode())
            .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(get("/api/v1/scan-helpers/lookup", demoOwner).getStatusCode()).as("the demo has the capability")
            .isEqualTo(HttpStatus.OK);
    }

    // ── h3 ───────────────────────────────────────────────────────────────────

    @Test @Order(3)
    void h3_reviewFixture_eachContextOffersWhatTheSeededFlowNeeds() {
        assertThat(labels("waybills")).containsExactly("#R1003", "#R1008", "#R1009", "#R1010", "#R1011");
        assertThat(labels("pickup")).containsExactly("#R1001", "#R1002");
        assertThat(labels("returns")).containsExactly("#R1006");
        assertThat(codes("returns")).containsExactly(jdbc.queryForObject(
            "SELECT p.barcode FROM return_request_items i JOIN pieces p ON p.id = i.piece_id WHERE i.tenant_id = ?",
            String.class, review));
        List<Map<String, Object>> lookup = items("lookup", reviewOwner);
        assertThat(lookup).hasSize(3);
        assertThat(lookup.get(0)).containsEntry("label", "delivered");

        UUID variant = variantOf("#R1008");
        List<String> pieces = codes("pieces?variantId=" + variant);
        assertThat(pieces).isNotEmpty().hasSizeLessThanOrEqualTo(5);
        assertThat(jdbc.queryForList("SELECT barcode FROM pieces WHERE tenant_id = ? AND variant_id = ? AND status = 'available'",
            String.class, review, variant)).containsAll(pieces);
    }

    // ── h4 ───────────────────────────────────────────────────────────────────

    @Test @Order(4)
    @SuppressWarnings("unchecked")
    void h4_everyCandidate_isAcceptedByTheRealScan() {
        // Pickup: a fresh session takes the first offered waybill.
        Map<String, Object> pickup = post("/api/v1/pickup-sessions",
            Map.of("scheduledDate", LocalDate.now().toString()), reviewOwner).getBody();
        String pickupId = String.valueOf(pickup.getOrDefault("sessionId", pickup.get("id")));
        ResponseEntity<Map> pickupScan = post("/api/v1/pickup-sessions/" + pickupId + "/scans",
            Map.of("trackingNumber", codes("pickup").get(0)), reviewOwner);
        assertThat(pickupScan.getBody()).as(String.valueOf(pickupScan.getBody())).containsEntry("outcome", "ACCEPTED");

        // Return: a return session takes R1006's piece.
        String returnSession = String.valueOf(post("/api/v1/returns/sessions", Map.of(), reviewOwner).getBody().get("sessionId"));
        ResponseEntity<Map> returnScan = post("/api/v1/returns/sessions/" + returnSession + "/scan",
            Map.of("scan", codes("returns").get(0)), reviewOwner);
        assertThat(returnScan.getStatusCode()).as(String.valueOf(returnScan.getBody())).isEqualTo(HttpStatus.OK);

        // Waybill mode: the first offered waybill opens its order; a piece chip for its line is scanned.
        jdbc.update("UPDATE tenants SET pick_pack_mode = 'waybill_scan' WHERE id = ?", review);
        String pack = String.valueOf(post("/api/v1/pack-sessions", Map.of(), reviewOwner).getBody().get("id"));
        String waybill = codes("waybills").get(0);
        Map<String, Object> opened = post("/api/v1/pack-sessions/" + pack + "/waybill", Map.of("code", waybill), reviewOwner).getBody();
        assertThat(opened).as(String.valueOf(opened)).containsEntry("result", "opened");
        Map<String, Object> order = (Map<String, Object>) opened.get("order");
        String variant = String.valueOf(((List<Map<String, Object>>) order.get("items")).get(0).get("variant_id"));
        Map<String, Object> scanned = post("/api/v1/pack-sessions/" + pack + "/orders/" + order.get("id") + "/scan",
            Map.of("code", codes("pieces?variantId=" + variant).get(0)), reviewOwner).getBody();
        assertThat(scanned.get("status")).as(String.valueOf(scanned)).isNotEqualTo("rejected");
        verifyNoInteractions(bostaGateway);
    }

    // ── h5 ───────────────────────────────────────────────────────────────────

    @Test @Order(5)
    void h5_stationWorker_unknownContext_missingVariant() {
        assertThat(get("/api/v1/scan-helpers/lookup", reviewWorker).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(get("/api/v1/scan-helpers/everything", reviewOwner).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(get("/api/v1/scan-helpers/pieces", reviewOwner).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(get("/api/v1/scan-helpers/lookup", null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ── h6 ───────────────────────────────────────────────────────────────────

    @Test @Order(6)
    void h6_appUserRls_anotherTenantSeesNoneOfIt() {
        TenantAwareDataSource appDs = new TenantAwareDataSource(
            new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "app_user", "testpw"));
        JdbcTemplate appJdbc = new JdbcTemplate(appDs);
        TransactionTemplate appTx = new TransactionTemplate(new DataSourceTransactionManager(appDs));
        ScanHelperService appHelpers = new ScanHelperService(appJdbc, 30);

        // Positive control: the review tenant's own context.
        assertThat(asTenant(appTx, review, () -> appHelpers.pickup(review))).hasSize(2);
        assertThat(flagAs(appTx, review, () -> ReviewCapabilities.scanHelpers(appJdbc, review))).isTrue();

        // Another tenant's context: nothing of the review tenant, and the flag reads false.
        assertThat(asTenant(appTx, merchant, () -> appHelpers.pickup(review))).isEmpty();
        assertThat(asTenant(appTx, merchant, () -> appHelpers.lookup(review))).isEmpty();
        assertThat(flagAs(appTx, merchant, () -> ReviewCapabilities.scanHelpers(appJdbc, review))).isFalse();
    }

    private static List<Map<String, Object>> asTenant(TransactionTemplate tx, UUID tenant,
                                                      java.util.function.Supplier<List<Map<String, Object>>> read) {
        return TenantContext.runAs(tenant, () -> tx.execute(s -> read.get()));
    }

    private static boolean flagAs(TransactionTemplate tx, UUID tenant, java.util.function.Supplier<Boolean> read) {
        Boolean v = TenantContext.runAs(tenant, () -> tx.execute(s -> read.get()));
        return Boolean.TRUE.equals(v);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private String token(UUID tenant, String role) {
        UUID user = jdbc.queryForObject("SELECT id FROM users WHERE tenant_id = ? AND role = ?::user_role ORDER BY created_at LIMIT 1",
            UUID.class, tenant, role);
        return jwt.issueAccessToken(user, tenant, role);
    }

    private UUID variantOf(String number) {
        return jdbc.queryForObject("SELECT oi.variant_id FROM order_items oi JOIN orders o ON o.id = oi.order_id " +
            "WHERE o.tenant_id = ? AND o.number = ?", UUID.class, review, number);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> items(String context, String token) {
        ResponseEntity<Map> r = get("/api/v1/scan-helpers/" + context, token);
        assertThat(r.getStatusCode()).as(context + " " + r.getBody()).isEqualTo(HttpStatus.OK);
        return (List<Map<String, Object>>) r.getBody().get("items");
    }

    private List<String> labels(String context) {
        return items(context, reviewOwner).stream().map(i -> (String) i.get("label")).toList();
    }

    private List<String> codes(String context) {
        return items(context, reviewOwner).stream().map(i -> (String) i.get("code")).toList();
    }

    private ResponseEntity<Map> get(String path, String token) {
        return rest.exchange("http://localhost:" + port + path, HttpMethod.GET, new HttpEntity<>(headers(token)), Map.class);
    }

    private ResponseEntity<Map> post(String path, Object body, String token) {
        return rest.exchange("http://localhost:" + port + path, HttpMethod.POST, new HttpEntity<>(body, headers(token)), Map.class);
    }

    private static HttpHeaders headers(String token) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) h.setBearerAuth(token);
        return h;
    }
}
