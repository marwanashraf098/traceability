package com.traceability;

import com.traceability.identity.JwtService;
import com.traceability.integrations.bosta.BostaGateway;
import com.traceability.integrations.shopify.ShopifyGateway;
import com.traceability.setup.MainLocationAddressPushJob;
import com.traceability.setup.MainLocationAddressPusher;
import org.jobrunr.jobs.lambdas.IocJobLambda;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/**
 * Onboarding gate (V185): GET /me's setupStep / warehouseAddressNeeded, the sticky Shopify stamp, the
 * address endpoints and the after-commit Shopify push. The app runs as app_user (RLS on, like production);
 * every step is read through the real GET /me (JWT filter → MeController → the service's transaction).
 *
 *   g1 a never-connected tenant → 'shopify' for owner and manager; a worker → null; no banner
 *   g2 demo (is_demo) and review (tenant_courier_simulation) tenants are never gated, nor bannered
 *   g3 sticky stamp: a store written 'connected' → 'address'; then needs_reauth / error / disconnected → still
 *      'address' (live status never re-locks); a tenant whose only store was written 'disconnected' → 'shopify'
 *   g4 PUT /setup/address: manager / worker → 403 (nothing saved); bad governorate / legacy SU / missing street
 *      → 400 with its code; the owner's save → setupStep null, row saved, setup_completed_at stamped, push
 *      'pending', exactly one job enqueued; GET returns it plus the 27 governorates
 *   g5 grandfathered (setup_completed_at backfilled, no address) → setupStep null + warehouseAddressNeeded;
 *      saving the address clears the banner
 *   g6 address saved before connecting → still 'shopify'; once a store connects → null (address present)
 *   g7 tenant isolation as app_user: A's save leaves B's main warehouse untouched (A's own row is the positive
 *      control); with no tenant set app_user sees no location; the trigger stamps under app_user + RLS
 *   g8 push job: pusher throws → 'failed' + the class name, rethrown, LOW alert warehouse_address_push_failed
 *      listed; PUSHED → 'pushed', alert gone; NOT_LINKED → status cleared; a push for a superseded save
 *      writes nothing
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class OnboardingGateTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
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
    @Autowired TestRestTemplate rest;
    @Autowired JwtService       jwt;
    @Autowired MainLocationAddressPushJob pushJob;
    @MockBean  ShopifyGateway   shopify;
    @MockBean  BostaGateway     bosta;
    @MockBean  JobScheduler     jobScheduler;
    @MockBean  MainLocationAddressPusher pusher;

    JdbcTemplate owner;   // postgres — fixtures and assertions only

    record Tenant(UUID id, UUID location, UUID owner, UUID manager, UUID worker) {}

    static final Map<String, Object> GOOD = Map.of(
        "provinceCode", "c", "city", "Nasr City", "street", "  12   Abbas El Akkad St ", "building", "4", "floor", "2");

    @BeforeAll
    void setUp() {
        owner = new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "postgres", "postgres"));
    }

    @BeforeEach
    void resetMocks() {
        reset(jobScheduler, pusher);
    }

    // ── g1 ───────────────────────────────────────────────────────────────────

    @Test @Order(1)
    void g1_neverConnected_isGatedOnShopify() {
        Tenant t = tenant("fresh");
        assertMe(t, "owner",   "shopify", false);
        assertMe(t, "manager", "shopify", false);
        assertMe(t, "worker",  null,      false);
    }

    // ── g2 ───────────────────────────────────────────────────────────────────

    @Test @Order(2)
    void g2_demoAndReviewTenants_bypass() {
        Tenant demo = tenant("demo");
        owner.update("UPDATE tenants SET is_demo = true WHERE id = ?", demo.id());
        store(demo, "disconnected");
        Tenant review = tenant("review");
        store(review, "disconnected");
        owner.update("INSERT INTO tenant_courier_simulation (tenant_id, note) VALUES (?, 'review')", review.id());
        for (Tenant t : new Tenant[] { demo, review }) {
            assertMe(t, "owner",   null, false);
            assertMe(t, "manager", null, false);
        }
        // Positive control in the same shape: the same tenant without the flag is gated.
        owner.update("DELETE FROM tenant_courier_simulation WHERE tenant_id = ?", review.id());
        assertMe(review, "owner", "shopify", false);
    }

    // ── g3 ───────────────────────────────────────────────────────────────────

    @Test @Order(3)
    void g3_stickyConnectedStamp() {
        Tenant t = tenant("sticky");
        UUID s = store(t, "connected");
        assertMe(t, "owner", "address", false);
        for (String status : List.of("needs_reauth", "error", "disconnected")) {
            owner.update("UPDATE stores SET status = ?::store_status WHERE id = ?", status, s);
            assertMe(t, "owner", "address", false);
        }

        Tenant previously = tenant("previously");
        store(previously, "disconnected");
        assertMe(previously, "owner", "shopify", false);
    }

    // ── g4 ───────────────────────────────────────────────────────────────────

    @Test @Order(4)
    void g4_saveAddress_rolesValidationAndCompletion() {
        Tenant t = tenant("save");
        store(t, "connected");

        assertThat(put(t, "manager", GOOD).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(put(t, "worker",  GOOD).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(get(t, "manager").getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(address(t).get("address_street")).as("nothing saved by a refused call").isNull();

        assertCode(put(t, "owner", Map.of("provinceCode", "XX", "city", "A", "street", "Some street")), "GOVERNORATE_REQUIRED");
        assertCode(put(t, "owner", Map.of("provinceCode", "SU", "city", "A", "street", "Some street")), "GOVERNORATE_REQUIRED");
        assertCode(put(t, "owner", Map.of("provinceCode", "GZ", "city", " ", "street", "Some street")), "CITY_REQUIRED");
        assertCode(put(t, "owner", Map.of("provinceCode", "GZ", "city", "Dokki", "street", "ab")), "STREET_REQUIRED");
        assertCode(put(t, "owner", Map.of("provinceCode", "GZ", "city", "Dokki", "street", "Tahrir St",
            "building", "x".repeat(51))), "ADDRESS_PART_TOO_LONG");
        verify(jobScheduler, never()).enqueue(any(IocJobLambda.class));
        assertMe(t, "owner", "address", false);

        ResponseEntity<Map> ok = put(t, "owner", GOOD);
        assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(ok.getBody()).containsEntry("setupStep", null);
        Map<String, Object> row = address(t);
        assertThat(row).containsEntry("address_province_code", "C").containsEntry("address_city", "Nasr City")
            .containsEntry("address_street", "12 Abbas El Akkad St").containsEntry("address_building", "4")
            .containsEntry("address_floor", "2").containsEntry("address_shopify_push_status", "pending");
        assertThat(owner.queryForObject("SELECT setup_completed_at IS NOT NULL FROM tenants WHERE id = ?",
            Boolean.class, t.id())).isTrue();
        verify(jobScheduler, times(1)).enqueue(any(IocJobLambda.class));
        assertMe(t, "owner",   null, false);
        assertMe(t, "manager", null, false);

        ResponseEntity<Map> g = get(t, "owner");
        assertThat(g.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked") Map<String, Object> a = (Map<String, Object>) g.getBody().get("address");
        assertThat(a).containsEntry("provinceCode", "C").containsEntry("street", "12 Abbas El Akkad St");
        @SuppressWarnings("unchecked") List<Map<String, Object>> provinces = (List<Map<String, Object>>) g.getBody().get("provinces");
        assertThat(provinces).hasSize(27);
        assertThat(provinces).extracting(p -> p.get("code")).doesNotContain("SU", "HU").contains("C", "GZ", "ALX");
    }

    // ── g5 ───────────────────────────────────────────────────────────────────

    @Test @Order(5)
    void g5_grandfathered_softBannerOnly() {
        Tenant t = tenant("grandfathered");
        store(t, "connected");
        owner.update("UPDATE tenants SET setup_completed_at = now() WHERE id = ?", t.id());
        assertMe(t, "owner",   null, true);
        assertMe(t, "manager", null, true);
        assertMe(t, "worker",  null, false);

        assertThat(put(t, "owner", GOOD).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertMe(t, "owner", null, false);
    }

    // ── g6 ───────────────────────────────────────────────────────────────────

    @Test @Order(6)
    void g6_addressBeforeConnect_staysOnShopifyStep() {
        Tenant t = tenant("early");
        ResponseEntity<Map> r = put(t, "owner", GOOD);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody()).containsEntry("setupStep", "shopify");
        assertThat(owner.queryForObject("SELECT setup_completed_at IS NULL FROM tenants WHERE id = ?",
            Boolean.class, t.id())).isTrue();
        assertMe(t, "owner", "shopify", false);
        store(t, "connected");
        assertMe(t, "owner", null, false);
    }

    // ── g7 ───────────────────────────────────────────────────────────────────

    @Test @Order(7)
    void g7_tenantIsolation_asAppUser() throws Exception {
        Tenant a = tenant("iso-a"), b = tenant("iso-b");
        store(a, "connected");
        store(b, "connected");
        assertThat(put(a, "owner", GOOD).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(address(a).get("address_street")).as("positive control: A's own row").isNotNull();
        assertThat(address(b).get("address_street")).as("B untouched").isNull();
        assertMe(b, "owner", "address", false);
        @SuppressWarnings("unchecked") Map<String, Object> bAddr = (Map<String, Object>) get(b, "owner").getBody().get("address");
        assertThat(bAddr).as("B never reads A's address").isNull();

        try (Connection c = DriverManager.getConnection(POSTGRES.getJdbcUrl(), "app_user", "testpw");
             var st = c.createStatement()) {
            try (var rs = st.executeQuery("SELECT count(*) FROM locations WHERE address_street IS NOT NULL")) {
                rs.next();
                assertThat(rs.getInt(1)).as("no tenant set → no rows").isZero();
            }
            // The INVOKER trigger stamps under app_user + RLS, inside the connect transaction.
            Tenant t = tenant("trigger");
            c.setAutoCommit(false);
            st.execute("SET LOCAL app.current_tenant = '" + t.id() + "'");
            st.execute("INSERT INTO stores (tenant_id, platform, shop_domain, status) VALUES ('" + t.id()
                + "', 'shopify', 'gate-trigger-" + UUID.randomUUID() + ".myshopify.com', 'connected')");
            c.commit();
            assertThat(owner.queryForObject("SELECT shopify_connected_at IS NOT NULL FROM tenants WHERE id = ?",
                Boolean.class, t.id())).isTrue();
            assertThat(owner.queryForObject("SELECT shopify_connected_at IS NULL FROM tenants WHERE id = ?",
                Boolean.class, tenant("trigger-other").id())).as("only the store's own tenant").isTrue();
        }
    }

    // ── g8 ───────────────────────────────────────────────────────────────────

    @Test @Order(8)
    void g8_pushJob_failureAlertRetryAndSuccess() throws Exception {
        Tenant t = tenant("push");
        store(t, "connected");
        assertThat(put(t, "owner", GOOD).getStatusCode()).isEqualTo(HttpStatus.OK);

        when(pusher.push(any(), any(), any())).thenThrow(new IOException("boom"));
        assertThatThrownBy(() -> pushJob.push(t.id(), t.location())).hasRootCauseInstanceOf(IOException.class);
        assertThat(address(t)).containsEntry("address_shopify_push_status", "failed")
                              .containsEntry("address_shopify_push_error", "IOException");
        assertThat(alertTypes(t)).contains("warehouse_address_push_failed");

        reset(pusher);
        when(pusher.push(any(), any(), any())).thenReturn(MainLocationAddressPusher.Outcome.PUSHED);
        pushJob.push(t.id(), t.location());
        assertThat(address(t)).containsEntry("address_shopify_push_status", "pushed")
                              .containsEntry("address_shopify_push_error", null);
        assertThat(alertTypes(t)).doesNotContain("warehouse_address_push_failed");
        verify(pusher).push(eq(t.id()), eq(t.location()), argThat(a ->
            "C".equals(a.provinceCode()) && "Nasr City".equals(a.city()) && "12 Abbas El Akkad St".equals(a.street())));

        reset(pusher);
        when(pusher.push(any(), any(), any())).thenReturn(MainLocationAddressPusher.Outcome.NOT_LINKED);
        pushJob.push(t.id(), t.location());
        assertThat(address(t)).containsEntry("address_shopify_push_status", null);

        // A push whose save was superseded mid-flight records nothing (the newer save has its own job).
        reset(pusher);
        when(pusher.push(any(), any(), any())).thenAnswer(inv -> {
            owner.update("UPDATE locations SET address_updated_at = clock_timestamp(), " +
                         "address_shopify_push_status = 'pending' WHERE id = ?", t.location());
            throw new IOException("late");
        });
        assertThatThrownBy(() -> pushJob.push(t.id(), t.location())).isInstanceOf(RuntimeException.class);
        assertThat(address(t)).containsEntry("address_shopify_push_status", "pending");
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private List<String> alertTypes(Tenant t) {
        ResponseEntity<Map> r = rest.exchange(url("/api/v1/exceptions?size=200"), HttpMethod.GET,
            new HttpEntity<>(headers(token(t, "owner"))), Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<String, Object>> items = (List<Map<String, Object>>) r.getBody().get("items");
        return items.stream().map(i -> (String) i.get("type")).toList();
    }

    private void assertMe(Tenant t, String role, String step, boolean banner) {
        ResponseEntity<Map> r = rest.exchange(url("/api/v1/me"), HttpMethod.GET,
            new HttpEntity<>(headers(token(t, role))), Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody()).as("same-response control").containsEntry("role", role);
        assertThat(r.getBody()).as("%s setupStep", role).containsEntry("setupStep", step);
        assertThat(r.getBody()).as("%s banner", role).containsEntry("warehouseAddressNeeded", banner);
    }

    private ResponseEntity<Map> put(Tenant t, String role, Map<String, Object> body) {
        return rest.exchange(url("/api/v1/setup/address"), HttpMethod.PUT,
            new HttpEntity<>(body, headers(token(t, role))), Map.class);
    }

    private ResponseEntity<Map> get(Tenant t, String role) {
        return rest.exchange(url("/api/v1/setup/address"), HttpMethod.GET,
            new HttpEntity<>(headers(token(t, role))), Map.class);
    }

    private static void assertCode(ResponseEntity<Map> r, String code) {
        assertThat(r.getStatusCode()).as(code).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(r.getBody()).as(code).containsEntry("code", code);
    }

    private Map<String, Object> address(Tenant t) {
        return owner.queryForMap("SELECT * FROM locations WHERE id = ?", t.location());
    }

    private String url(String path) { return "http://localhost:" + port + path; }

    private String token(Tenant t, String role) {
        UUID user = switch (role) { case "owner" -> t.owner(); case "manager" -> t.manager(); default -> t.worker(); };
        return jwt.issueAccessToken(user, t.id(), role);
    }

    private static HttpHeaders headers(String token) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.setBearerAuth(token);
        return h;
    }

    private Tenant tenant(String tag) {
        UUID id = UUID.randomUUID(), loc = UUID.randomUUID();
        owner.update("INSERT INTO tenants (id, name) VALUES (?, ?)", id, "Gate " + tag);
        owner.update("INSERT INTO locations (id, tenant_id, name, type, is_default, is_fulfillment) " +
                     "VALUES (?, ?, 'Main Warehouse', 'warehouse', true, true)", loc, id);
        // A second, non-main location — never written by the address save.
        owner.update("INSERT INTO locations (tenant_id, name, type) VALUES (?, 'Shop', 'warehouse')", id);
        return new Tenant(id, loc, user(id, "owner", tag), user(id, "manager", tag), user(id, "worker", tag));
    }

    private UUID store(Tenant t, String status) {
        UUID s = UUID.randomUUID();
        owner.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status) VALUES (?, ?, 'shopify', ?, ?::store_status)",
            s, t.id(), "gate-" + s + ".myshopify.com", status);
        return s;
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
