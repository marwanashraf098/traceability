package com.traceability;

import com.traceability.identity.model.AccessTokenResponse;
import com.traceability.integrations.bosta.BostaV2Client;
import com.traceability.integrations.shopify.ShopifyGateway;
import com.traceability.integrations.shopify.ShopifyOrderPriceGateway;
import com.traceability.integrations.shopify.ShopifyTokenProvider;
import com.traceability.inventory.UlidGenerator;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.jobrunr.scheduling.JobScheduler;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Bazar U1b — the two report downloads, GET /bazar-events/{id}/report.pdf and /report/orders.csv, with the WHOLE app
 * running as app_user (RLS on, the real HTTP path): the owner and a manager get their own event's data; a worker of
 * the same tenant is refused (403, owner / manager only); the owner of another tenant gets 404 and none of the
 * event's data; the audit row of the CSV export lands under RLS (WITH CHECK). RlsCoverageTest's COVERED entry for
 * both downloads points here.
 *
 * Revert check: ordersCsv / reportPdf without their {@code @Transactional} → a1 red (no tenant on the connection →
 * the event isn't found under RLS → 404).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BazarReportDownloadsHttpAppUserTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            TestPostgres.newContainer()
                    .withDatabaseName("traceability_test")
                    .withUsername("postgres")
                    .withPassword("postgres");

    static {
        POSTGRES.start();
        try (var c = java.sql.DriverManager.getConnection(POSTGRES.getJdbcUrl(), "postgres", "postgres");
             var st = c.createStatement()) {
            st.execute("CREATE ROLE app_user LOGIN PASSWORD 'testpw'");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",      POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", () -> "app_user");
        r.add("spring.datasource.password", () -> "testpw");
        r.add("spring.flyway.url",          POSTGRES::getJdbcUrl);
        r.add("spring.flyway.user",         POSTGRES::getUsername);
        r.add("spring.flyway.password",     POSTGRES::getPassword);
        r.add("analytics.settlement.refresh-enabled", () -> "false");
        r.add("traced.shopify-returns.backfill.enabled", () -> "false");
        r.add("shopify.webhook-topics-backfill.enabled", () -> "false");
        r.add("traced.catalog-backfill.enabled", () -> "false");
    }

    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;
    @Autowired PasswordEncoder passwordEncoder;
    @MockBean ShopifyGateway shopifyGateway;
    @MockBean ShopifyTokenProvider tokenProvider;
    @MockBean ShopifyOrderPriceGateway prices;
    @MockBean JobScheduler jobScheduler;
    @MockBean BostaV2Client bostaV2;

    /** Fixtures and assertions only (postgres); the app never uses it. */
    JdbcTemplate pg;
    UUID tenant, otherTenant, main, storeId;
    String owner, manager, worker, ownerOther, eventId;
    static final String PRODUCT = "AppUser Linen Dress";
    static final String EVENT = "AppUser Bazar";

    @BeforeAll
    void setup() {
        pg = new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        tenant = UUID.randomUUID();
        otherTenant = UUID.randomUUID();
        main = UUID.randomUUID();
        storeId = UUID.randomUUID();
        pg.update("INSERT INTO tenants (id, name, bazar_enabled) VALUES (?, 'Dl On', true)", tenant);
        pg.update("INSERT INTO tenants (id, name, bazar_enabled) VALUES (?, 'Dl Other', true)", otherTenant);
        pg.update("INSERT INTO locations (id, tenant_id, name, type, is_default, is_fulfillment) " +
            "VALUES (?, ?, 'Main Warehouse', 'warehouse', true, true)", main, tenant);
        pg.update("INSERT INTO locations (id, tenant_id, name, type, is_default, is_fulfillment) " +
            "VALUES (?, ?, 'Other Main', 'warehouse', true, true)", UUID.randomUUID(), otherTenant);
        pg.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status) " +
            "VALUES (?, ?, 'shopify', 'dl-appuser.myshopify.com', 'disconnected')", storeId, tenant);
        owner = login(user(tenant, "owner"));
        manager = login(user(tenant, "manager"));
        worker = login(user(tenant, "worker"));
        ownerOther = login(user(otherTenant, "owner"));

        UUID product = UUID.randomUUID(), variant = UUID.randomUUID();
        pg.update("INSERT INTO products (id, tenant_id, store_id, external_id, title, status, raw) " +
            "VALUES (?, ?, ?, ?, ?, 'active', '{}'::jsonb)", product, tenant, storeId, "P-" + product, PRODUCT);
        pg.update("INSERT INTO variants (id, tenant_id, product_id, external_id, title, price, raw) " +
            "VALUES (?, ?, ?, ?, 'M', 500::numeric, '{}'::jsonb)", variant, tenant, product, "V-" + variant);
        String piece = UlidGenerator.generate();
        pg.update("INSERT INTO pieces (id, tenant_id, variant_id, barcode, short_code, status, current_location_id) " +
            "VALUES (?, ?, ?, ?, 'D0000001', 'available', ?)", piece, tenant, variant, "PC-" + piece, main);

        ResponseEntity<Map> created = call(owner, HttpMethod.POST, "/api/v1/bazar-events", Map.of("name", EVENT,
            "startsOn", "2026-12-01", "endsOn", "2026-12-02", "sellerRoles", List.of("owner"), "locationName", "Dl Hall"));
        assertThat(created.getStatusCode()).as(String.valueOf(created.getBody())).isEqualTo(HttpStatus.CREATED);
        eventId = (String) created.getBody().get("id");
        ResponseEntity<Map> out = call(owner, HttpMethod.POST, ev("/scan-out"), Map.of("barcode", "D0000001"));
        assertThat(out.getBody().get("success")).as(String.valueOf(out.getBody())).isEqualTo(true);
        assertThat(call(owner, HttpMethod.POST, ev("/mark-sent"), Map.of()).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(call(owner, HttpMethod.POST, ev("/start"), Map.of()).getStatusCode()).isEqualTo(HttpStatus.OK);
        ResponseEntity<Map> sold = call(owner, HttpMethod.POST, ev("/sell"),
            Map.of("code", "D0000001", "client_request_id", "c-" + UUID.randomUUID(), "method", "cash"));
        assertThat(sold.getStatusCode()).as(String.valueOf(sold.getBody())).isEqualTo(HttpStatus.OK);
    }

    @Test
    void a1_ownerAndManager_getTheirOwnEventsData_asAppUser() throws Exception {
        ResponseEntity<byte[]> csv = bytes(owner, ev("/report/orders.csv"));
        assertThat(csv.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(new String(csv.getBody(), StandardCharsets.UTF_8)).contains(PRODUCT).contains("D0000001");
        assertThat(pg.queryForObject("SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = 'bazar_orders_export'",
            Integer.class, tenant)).as("audit row under RLS").isEqualTo(1);

        ResponseEntity<byte[]> pdf = bytes(manager, ev("/report.pdf"));
        assertThat(pdf.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(pdf.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PDF);
        try (PDDocument doc = Loader.loadPDF(pdf.getBody())) {
            assertThat(new PDFTextStripper().getText(doc)).contains(EVENT);
        }
    }

    @Test
    void a2_workerOfTheSameTenant_isRefusedBoth() {
        assertThat(bytes(worker, ev("/report/orders.csv")).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(bytes(worker, ev("/report.pdf")).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void a3_anotherTenantsOwner_getsNothing() {
        int audits = pg.queryForObject("SELECT count(*) FROM audit_log WHERE action = 'bazar_orders_export'", Integer.class);
        for (String path : new String[] {"/report/orders.csv", "/report.pdf", "/report.pdf?lang=ar"}) {
            ResponseEntity<byte[]> r = bytes(ownerOther, ev(path));
            assertThat(r.getStatusCode()).as(path).isEqualTo(HttpStatus.NOT_FOUND);
            String body = r.getBody() == null ? "" : new String(r.getBody(), StandardCharsets.UTF_8);
            assertThat(body).as(path).doesNotContain(PRODUCT).doesNotContain(EVENT).doesNotContain("D0000001");
        }
        assertThat(pg.queryForObject("SELECT count(*) FROM audit_log WHERE action = 'bazar_orders_export'", Integer.class))
            .as("no export recorded").isEqualTo(audits);
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private String ev(String path) { return "/api/v1/bazar-events/" + eventId + path; }

    private String user(UUID t, String role) {
        String email = role + "-" + UUID.randomUUID() + "@dl-appuser.test";
        pg.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role, active) VALUES (?, ?, ?, ?, ?, ?::user_role, true)",
            UUID.randomUUID(), t, "Dl " + role, email, passwordEncoder.encode("pass123"), role);
        return email;
    }

    private String login(String email) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<AccessTokenResponse> r = rest.postForEntity(base() + "/api/v1/auth/login",
            new HttpEntity<>(Map.of("email", email, "password", "pass123"), h), AccessTokenResponse.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        return r.getBody().accessToken();
    }

    private String base() { return "http://localhost:" + port; }

    @SuppressWarnings("rawtypes")
    private ResponseEntity<Map> call(String token, HttpMethod m, String path, Object body) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        h.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange(base() + path, m, new HttpEntity<>(body, h), Map.class);
    }

    private ResponseEntity<byte[]> bytes(String token, String path) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        return rest.exchange(base() + path, HttpMethod.GET, new HttpEntity<>(h), byte[].class);
    }
}
