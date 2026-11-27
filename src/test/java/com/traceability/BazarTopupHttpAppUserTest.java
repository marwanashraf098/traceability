package com.traceability;

import com.traceability.identity.model.AccessTokenResponse;
import com.traceability.integrations.bosta.BostaV2Client;
import com.traceability.integrations.shopify.ShopifyGateway;
import com.traceability.integrations.shopify.ShopifyOrderPriceGateway;
import com.traceability.integrations.shopify.ShopifyTokenProvider;
import com.traceability.inventory.UlidGenerator;
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

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Bazar top-up (V245) — the batch endpoints with the WHOLE app running as app_user (RLS on, the real HTTP path): the
 * owner and a manager open a batch, list pieces, send it (retry-safe by request id) and the bazar's counts / batches
 * show it; a worker of the same tenant is refused (403); another tenant's owner gets 404 and changes nothing.
 * RlsCoverageTest's COVERED entry for GET …/batches/current points here.
 *
 * Revert check: BazarService.openBatch / sendBatch without {@code @Transactional} → a1 red (no tenant on the
 * connection under RLS → the event isn't found → 404).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.MethodName.class)
class BazarTopupHttpAppUserTest {

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
    UUID tenant, otherTenant, main, variant;
    String owner, manager, worker, ownerOther, eventId;

    @BeforeAll
    void setup() {
        pg = new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        tenant = UUID.randomUUID();
        otherTenant = UUID.randomUUID();
        main = UUID.randomUUID();
        UUID store = UUID.randomUUID();
        pg.update("INSERT INTO tenants (id, name, bazar_enabled) VALUES (?, 'Topup On', true)", tenant);
        pg.update("INSERT INTO tenants (id, name, bazar_enabled) VALUES (?, 'Topup Other', true)", otherTenant);
        pg.update("INSERT INTO locations (id, tenant_id, name, type, is_default, is_fulfillment) " +
            "VALUES (?, ?, 'Main Warehouse', 'warehouse', true, true)", main, tenant);
        pg.update("INSERT INTO locations (id, tenant_id, name, type, is_default, is_fulfillment) " +
            "VALUES (?, ?, 'Other Main', 'warehouse', true, true)", UUID.randomUUID(), otherTenant);
        pg.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status) " +
            "VALUES (?, ?, 'shopify', 'topup-appuser.myshopify.com', 'disconnected')", store, tenant);
        owner = login(user(tenant, "owner"));
        manager = login(user(tenant, "manager"));
        worker = login(user(tenant, "worker"));
        ownerOther = login(user(otherTenant, "owner"));

        UUID product = UUID.randomUUID();
        variant = UUID.randomUUID();
        pg.update("INSERT INTO products (id, tenant_id, store_id, external_id, title, status, raw) " +
            "VALUES (?, ?, ?, ?, 'Topup Dress', 'active', '{}'::jsonb)", product, tenant, store, "P-" + product);
        pg.update("INSERT INTO variants (id, tenant_id, product_id, external_id, title, price, raw) " +
            "VALUES (?, ?, ?, ?, 'M', 500::numeric, '{}'::jsonb)", variant, tenant, product, "V-" + variant);
        for (int i = 1; i <= 4; i++) piece("U000000" + i);

        ResponseEntity<Map> created = call(owner, HttpMethod.POST, "/api/v1/bazar-events", Map.of("name", "Topup Bazar",
            "startsOn", "2026-12-01", "endsOn", "2026-12-02", "sellerRoles", List.of("owner"), "locationName", "Topup Hall"));
        assertThat(created.getStatusCode()).as(String.valueOf(created.getBody())).isEqualTo(HttpStatus.CREATED);
        eventId = (String) created.getBody().get("id");
        assertThat(call(owner, HttpMethod.POST, ev("/scan-out"), Map.of("barcode", "U0000001")).getBody().get("success"))
            .isEqualTo(true);
        assertThat(call(owner, HttpMethod.POST, ev("/mark-sent"), Map.of()).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(call(owner, HttpMethod.POST, ev("/start"), Map.of()).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @SuppressWarnings("unchecked")
    void a1_owner_opensListsAndSends_retrySafe_theBazarShowsTwoBatches() {
        ResponseEntity<Map> opened = call(owner, HttpMethod.POST, ev("/batches"), Map.of());
        assertThat(opened.getStatusCode()).as(String.valueOf(opened.getBody())).isEqualTo(HttpStatus.OK);
        String batch = (String) opened.getBody().get("id");
        ResponseEntity<Map> scan = call(owner, HttpMethod.POST, ev("/batches/" + batch + "/scan"), Map.of("barcode", "U0000002"));
        assertThat(scan.getBody().get("success")).as(String.valueOf(scan.getBody())).isEqualTo(true);
        Map<String, Object> current = (Map<String, Object>) call(owner, HttpMethod.GET, ev("/batches/current"), null)
            .getBody().get("batch");
        assertThat(current.get("total")).isEqualTo(1);
        assertThat(pg.queryForObject("SELECT status::text FROM pieces WHERE short_code = 'U0000002'", String.class))
            .as("the list moves nothing").isEqualTo("available");

        Map<String, Object> send = Map.of("client_request_id", "a1-send");
        ResponseEntity<Map> sent = call(owner, HttpMethod.POST, ev("/batches/" + batch + "/send"), send);
        assertThat(sent.getStatusCode()).as(String.valueOf(sent.getBody())).isEqualTo(HttpStatus.OK);
        assertThat(sent.getBody().get("sent")).isEqualTo(1);
        ResponseEntity<Map> again = call(owner, HttpMethod.POST, ev("/batches/" + batch + "/send"), send);
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(again.getBody().get("replayed")).isEqualTo(true);

        Map<String, Object> detail = call(owner, HttpMethod.GET, ev(""), null).getBody();
        assertThat(((Map<String, Object>) detail.get("counts")).get("sent")).isEqualTo(2);
        assertThat((List<Map<String, Object>>) detail.get("batches")).hasSize(2);
        assertThat(detail.get("open_batch")).isNull();
        assertThat(pg.queryForObject("SELECT status::text FROM pieces WHERE short_code = 'U0000002'", String.class))
            .isEqualTo("out_on_transfer");
    }

    @Test
    void a2_manager_canTopUpToo() {
        ResponseEntity<Map> opened = call(manager, HttpMethod.POST, ev("/batches"), Map.of());
        assertThat(opened.getStatusCode()).as(String.valueOf(opened.getBody())).isEqualTo(HttpStatus.OK);
        String batch = (String) opened.getBody().get("id");
        assertThat(call(manager, HttpMethod.POST, ev("/batches/" + batch + "/scan"), Map.of("barcode", "U0000003"))
            .getBody().get("success")).isEqualTo(true);
        assertThat(call(manager, HttpMethod.POST, ev("/batches/" + batch + "/send"), Map.of("client_request_id", "a2-send"))
            .getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void a3_worker_isRefused() {
        assertThat(call(worker, HttpMethod.POST, ev("/batches"), Map.of()).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(call(worker, HttpMethod.GET, ev("/batches/current"), null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(pg.queryForObject("SELECT COUNT(*) FROM transfer_send_batches WHERE tenant_id = ? AND status = 'open'",
            Integer.class, tenant)).isZero();
    }

    @Test
    void a4_anotherTenant_getsNotFound_andChangesNothing() {
        String batch = (String) call(owner, HttpMethod.POST, ev("/batches"), Map.of()).getBody().get("id");
        assertThat(call(ownerOther, HttpMethod.POST, ev("/batches"), Map.of()).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(call(ownerOther, HttpMethod.GET, ev("/batches/current"), null).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(call(ownerOther, HttpMethod.POST, ev("/batches/" + batch + "/scan"), Map.of("barcode", "U0000004"))
            .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(call(ownerOther, HttpMethod.POST, ev("/batches/" + batch + "/send"), Map.of("client_request_id", "a4"))
            .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(call(ownerOther, HttpMethod.POST, ev("/batches/" + batch + "/discard"), Map.of())
            .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(pg.queryForObject("SELECT status FROM transfer_send_batches WHERE id = ?::uuid", String.class, batch))
            .isEqualTo("open");
        assertThat(pg.queryForObject("SELECT COUNT(*) FROM transfer_send_batch_items WHERE batch_id = ?::uuid", Integer.class, batch))
            .isZero();
        assertThat(call(owner, HttpMethod.POST, ev("/batches/" + batch + "/discard"), Map.of()).getStatusCode())
            .isEqualTo(HttpStatus.OK);
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private void piece(String code) {
        String id = UlidGenerator.generate();
        pg.update("INSERT INTO pieces (id, tenant_id, variant_id, barcode, short_code, status, current_location_id) " +
            "VALUES (?, ?, ?, ?, ?, 'available', ?)", id, tenant, variant, "PC-" + id, code, main);
    }

    private String ev(String path) { return "/api/v1/bazar-events/" + eventId + path; }

    private String user(UUID t, String role) {
        String email = role + "-" + UUID.randomUUID() + "@topup-appuser.test";
        pg.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role, active) VALUES (?, ?, ?, ?, ?, ?::user_role, true)",
            UUID.randomUUID(), t, "Topup " + role, email, passwordEncoder.encode("pass123"), role);
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
}
