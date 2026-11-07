package com.traceability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.traceability.identity.JwtService;
import com.traceability.integrations.bosta.BostaGateway;
import com.traceability.integrations.shopify.ShopifyGateway;
import org.jobrunr.scheduling.JobScheduler;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.DriverManager;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Onboarding O5 — GET /me's two tour signals, for every role (a worker can't read /onboarding/progress). App runs as
 * app_user (RLS on).
 *
 *   m1  hasEverClosedPickup: false until the tenant closes a pickup session (an open one doesn't count) — the same
 *       for owner, manager and worker; another tenant's pickups never count
 *   m2  hasEverScannedReturn: false until a return session is CLOSED (abandoned doesn't count), or a piece has a
 *       return_received event — every role
 *   m3  /me exposes nothing else new: exactly these two keys were added, and both are booleans
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MeTourSignalsTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            TestPostgres.newContainer()
                    .withDatabaseName("traceability_test")
                    .withUsername("postgres")
                    .withPassword("postgres");

    static {
        POSTGRES.start();
        try (var c = DriverManager.getConnection(POSTGRES.getJdbcUrl(), "postgres", "postgres");
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
        r.add("traced.catalog-backfill.enabled", () -> "false");
    }

    @LocalServerPort int port;
    @Autowired JwtService jwt;
    @MockBean ShopifyGateway shopify;
    @MockBean BostaGateway bosta;
    @MockBean JobScheduler jobScheduler;

    final ObjectMapper mapper = new ObjectMapper();
    final HttpClient http = HttpClient.newHttpClient();
    JdbcTemplate owner;

    record Tenant(UUID id, UUID location, UUID owner, UUID manager, UUID worker) {}

    static final List<String> ROLES = List.of("owner", "manager", "worker");

    /** /me's keys before O5 (a tenant without places). */
    static final Set<String> BEFORE_O5 = Set.of("name", "email", "role", "scanHelpers", "demoMode", "genericCourier",
        "flexibleLabeling", "setupStep", "warehouseAddressNeeded", "places", "uiState", "setupIntro", "shopifyUpgradeStage");

    @BeforeAll
    void setUp() {
        owner = new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "postgres", "postgres"));
    }

    @Test @Order(1)
    void m1_hasEverClosedPickup_everyRole() throws Exception {
        Tenant t = tenant("m1");
        Tenant other = tenant("m1x");
        owner.update("INSERT INTO pickups (tenant_id, session_status) VALUES (?, 'closed')", other.id());
        for (String role : ROLES) assertThat(me(t, role).get("hasEverClosedPickup")).as(role).isEqualTo(false);

        owner.update("INSERT INTO pickups (tenant_id, session_status) VALUES (?, 'open')", t.id());
        for (String role : ROLES) assertThat(me(t, role).get("hasEverClosedPickup")).as(role + " open").isEqualTo(false);

        owner.update("INSERT INTO pickups (tenant_id, session_status) VALUES (?, 'closed')", t.id());
        for (String role : ROLES) {
            Map<String, Object> m = me(t, role);
            assertThat(m.get("hasEverClosedPickup")).as(role).isEqualTo(true);
            assertThat(m.get("hasEverScannedReturn")).as(role).isEqualTo(false);
        }
    }

    @Test @Order(2)
    void m2_hasEverScannedReturn_closedSessionOrReturnReceived() throws Exception {
        Tenant t = tenant("m2");
        owner.update("INSERT INTO return_sessions (tenant_id, status, opened_by) VALUES (?, 'abandoned', ?)", t.id(), t.owner());
        for (String role : ROLES) assertThat(me(t, role).get("hasEverScannedReturn")).as(role).isEqualTo(false);
        owner.update("INSERT INTO return_sessions (tenant_id, status, opened_by, closed_by, closed_at) " +
                     "VALUES (?, 'closed', ?, ?, now())", t.id(), t.owner(), t.owner());
        for (String role : ROLES) assertThat(me(t, role).get("hasEverScannedReturn")).as(role).isEqualTo(true);

        Tenant ev = tenant("m2e");
        String piece = piece(ev);
        for (String role : ROLES) assertThat(me(ev, role).get("hasEverScannedReturn")).as(role).isEqualTo(false);
        owner.update("INSERT INTO piece_events (tenant_id, piece_id, event_type, actor_user_id) VALUES (?, ?, 'return_received', ?)",
            ev.id(), piece, ev.owner());
        for (String role : ROLES) {
            Map<String, Object> m = me(ev, role);
            assertThat(m.get("hasEverScannedReturn")).as(role).isEqualTo(true);
            assertThat(m.get("hasEverClosedPickup")).as(role).isEqualTo(false);
        }
    }

    @Test @Order(3)
    void m3_onlyTheTwoBooleansAreNew() throws Exception {
        Tenant t = tenant("m3");
        for (String role : ROLES) {
            Map<String, Object> m = me(t, role);
            Set<String> added = new TreeSet<>(m.keySet());
            added.removeAll(BEFORE_O5);
            assertThat(added).as(role).containsExactly("hasEverClosedPickup", "hasEverScannedReturn");
            assertThat(m.get("hasEverClosedPickup")).isInstanceOf(Boolean.class);
            assertThat(m.get("hasEverScannedReturn")).isInstanceOf(Boolean.class);
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private Map<String, Object> me(Tenant t, String role) throws Exception {
        UUID user = switch (role) { case "owner" -> t.owner(); case "manager" -> t.manager(); default -> t.worker(); };
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/me"))
            .header("Authorization", "Bearer " + jwt.issueAccessToken(user, t.id(), role)).GET().build(),
            HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        return mapper.readValue(r.body(), Map.class);
    }

    private Tenant tenant(String tag) {
        UUID id = UUID.randomUUID(), loc = UUID.randomUUID();
        owner.update("INSERT INTO tenants (id, name) VALUES (?, ?)", id, "Tours " + tag);
        owner.update("INSERT INTO locations (id, tenant_id, name, type, is_default, is_fulfillment) " +
                     "VALUES (?, ?, 'Main Warehouse', 'warehouse', true, true)", loc, id);
        return new Tenant(id, loc, user(id, "owner", tag), user(id, "manager", tag), user(id, "worker", tag));
    }

    private String piece(Tenant t) {
        UUID store = UUID.randomUUID(), product = UUID.randomUUID(), variant = UUID.randomUUID();
        owner.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status) VALUES (?, ?, 'shopify', ?, 'disconnected'::store_status)",
            store, t.id(), "m-" + store + ".myshopify.com");
        owner.update("INSERT INTO products (id, tenant_id, store_id, external_id, title, status) VALUES (?, ?, ?, ?, 'T', 'active')",
            product, t.id(), store, "P-" + product);
        owner.update("INSERT INTO variants (id, tenant_id, product_id, external_id, title) VALUES (?, ?, ?, ?, 'V')",
            variant, t.id(), product, "V-" + variant);
        String id = "MT" + UUID.randomUUID().toString().replace("-", "").substring(0, 18).toUpperCase();
        owner.update("INSERT INTO pieces (id, tenant_id, variant_id, barcode, short_code, status, current_location_id) " +
                     "VALUES (?, ?, ?, ?, ?, 'available'::piece_status, ?)", id, t.id(), variant, "BC-" + id, id, t.location());
        return id;
    }

    private UUID user(UUID tenant, String role, String tag) {
        UUID u = UUID.randomUUID();
        owner.update("INSERT INTO users (id, tenant_id, name, email, password_hash, pin_code, role, active) " +
                     "VALUES (?, ?, ?, ?, ?, ?, ?::user_role, true)",
            u, tenant, role + " " + tag, role + "-" + tag + "-" + u + "@test.local",
            "worker".equals(role) ? null : "h", "worker".equals(role) ? "p" : null, role);
        return u;
    }
}
