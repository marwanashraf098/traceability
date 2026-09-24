package com.traceability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.traceability.identity.model.AccessTokenResponse;
import com.traceability.inventory.UlidGenerator;
import com.traceability.portal.PortalTokenService;
import com.traceability.tenancy.TenantContext;
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

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Returns portal Step 4b — customer submission (POST /api/v1/portal/{slug}/requests), merchant
 * return-request endpoints, portal settings and the variant non-returnable flag, over HTTP.
 *
 * Fixtures as in 4a: Bosta-linked forward leg with a bare numeric tracking number and a set
 * delivered_at, orders.number with '#', phones in non-canonical form, delivered pieces with
 * packed allocations. The cross-tenant proof on a real app_user connection is in
 * ReturnRequestsRlsTest.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PortalRequestsTest {

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

    static final String UNAUTHORIZED = "Your session has expired. Please look up your order again.";
    static final String INVALID      = "We couldn't accept this return request. Please start again.";
    static final String CONFLICT     = "Some items are no longer available. Please start again.";
    static final String SECRET       = "test-portal-token-secret-at-least-32-bytes!!";

    @LocalServerPort int port;
    @Autowired TestRestTemplate   rest;
    @Autowired JdbcTemplate       jdbc;
    @Autowired PasswordEncoder    passwordEncoder;
    @Autowired PortalTokenService tokens;
    @Autowired ObjectMapper       mapper;
    final TestRestTemplate jdkRest = new TestRestTemplate(
        new org.springframework.boot.web.client.RestTemplateBuilder()
            .requestFactory(() -> new org.springframework.http.client.JdkClientHttpRequestFactory()));

    record Tenant(UUID id, UUID store, UUID variant, UUID variant2, UUID owner, String slug) {}

    Tenant a, b, auto;
    String ownerA, managerA, workerA, ownerB;

    @BeforeAll
    void setup() {
        a    = tenant("Snouts Store", "snouts-4b", false);
        b    = tenant("Jumi Store",   "jumi-4b",   false);
        auto = tenant("Auto Store",   "auto-4b",   true);
        ownerA   = login(a.owner(), null);
        managerA = login(user(a.id(), "manager"), null);
        workerA  = login(user(a.id(), "worker"), null);
        ownerB   = login(b.owner(), null);
    }

    @AfterEach
    void cleanup() {
        TenantContext.clear();
        for (Tenant t : List.of(a, b, auto)) {
            jdbc.update("DELETE FROM portal_lookup_attempts WHERE tenant_id = ?", t.id());
            jdbc.update("DELETE FROM return_request_items WHERE tenant_id = ?", t.id());
            jdbc.update("DELETE FROM return_requests WHERE tenant_id = ?", t.id());
            jdbc.update("UPDATE pieces SET current_order_id = NULL WHERE tenant_id = ?", t.id());
            jdbc.update("DELETE FROM allocations WHERE tenant_id = ?", t.id());
            jdbc.update("DELETE FROM pieces WHERE tenant_id = ?", t.id());
            jdbc.update("DELETE FROM shipments WHERE tenant_id = ?", t.id());
            jdbc.update("DELETE FROM order_items WHERE tenant_id = ?", t.id());
            jdbc.update("DELETE FROM orders WHERE tenant_id = ?", t.id());
            jdbc.update("UPDATE variants SET non_returnable = false WHERE tenant_id = ?", t.id());
        }
        jdbc.update("UPDATE tenants SET portal_slug = 'snouts-4b', portal_enabled = true, portal_auto_approve = false, " +
                    "customer_return_window_days = 30 WHERE id = ?", a.id());
    }

    // ── Customer submission ───────────────────────────────────────────────────

    @Test
    void submit_happyPath_twoIdenticalPieces_bound_requested_referenceFormat_noPii() throws Exception {
        Order o = deliveredOrder(a, "#1047", "+20 101 234 5678", 2, 0);
        String token = lookupToken("snouts-4b", "1047", "01012345678");

        ResponseEntity<Map> resp = submit("snouts-4b", token, lines(a.variant(), 2, "wrong_size"), "mona@example.com", "Too small");

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(resp.getBody().keySet()).containsExactlyInAnyOrder("reference", "status");
        assertThat((String) resp.getBody().get("reference")).matches("^RR-[2-9A-HJ-NP-Z]{6}$");
        assertThat(resp.getBody().get("status")).isEqualTo("requested");
        String json = mapper.writeValueAsString(resp.getBody());
        assertThat(json).doesNotContain("mona@example.com").doesNotContain("Too small").doesNotContain("1012345678");

        Map<String, Object> rr = jdbc.queryForMap(
            "SELECT id, status::text AS status, customer_email, customer_note, decided_at FROM return_requests WHERE tenant_id = ?", a.id());
        assertThat(rr.get("status")).isEqualTo("requested");
        assertThat(rr.get("customer_email")).isEqualTo("mona@example.com");
        assertThat(rr.get("customer_note")).isEqualTo("Too small");
        assertThat(jdbc.queryForList("SELECT piece_id FROM return_request_items WHERE request_id = ? AND active",
            String.class, rr.get("id"))).containsExactlyInAnyOrderElementsOf(o.pieces());
    }

    @Test
    void submit_autoApproveTenant_approved_decidedByNull() {
        Order o = deliveredOrder(auto, "#2001", "01022220001", 1, 0);
        ResponseEntity<Map> resp = submit("auto-4b", tokens.issue(auto.id(), o.id()), lines(auto.variant(), 1, "damaged"), null, null);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(resp.getBody().get("status")).isEqualTo("approved");
        Map<String, Object> rr = jdbc.queryForMap(
            "SELECT status::text AS status, decided_at, decided_by FROM return_requests WHERE tenant_id = ?", auto.id());
        assertThat(rr.get("status")).isEqualTo("approved");
        assertThat(rr.get("decided_at")).isNotNull();
        assertThat(rr.get("decided_by")).as("system").isNull();
    }

    @Test
    void submit_token_missing_tampered_expired_otherTenant_401() {
        Order o = deliveredOrder(a, "#3001", "01033330001", 1, 0);
        Map<String, Object> body = lines(a.variant(), 1, "other");
        String good = tokens.issue(a.id(), o.id());
        String[] parts = good.split("\\.");
        String tampered = parts[0].substring(0, 4) + (parts[0].charAt(4) == 'A' ? 'B' : 'A') + parts[0].substring(5) + "." + parts[1];
        String expired = new PortalTokenService(SECRET, mapper,
            Clock.fixed(Instant.now().minus(Duration.ofMinutes(31)), ZoneOffset.UTC)).issue(a.id(), o.id());
        String otherTenants = tokens.issue(b.id(), o.id());

        for (String token : Arrays.asList(null, tampered, expired, otherTenants)) {
            ResponseEntity<Map> r = submit("snouts-4b", token, body, null, null);
            assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(r.getBody()).isEqualTo(Map.of("message", UNAUTHORIZED));
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM return_requests WHERE tenant_id = ?", Integer.class, a.id())).isZero();
    }

    @Test
    void submit_invalidLines_400_genericBody() {
        Order o = deliveredOrder(a, "#4001", "01044440001", 1, 0);
        UUID unknownVariant = UUID.randomUUID();
        String token = tokens.issue(a.id(), o.id());
        jdbc.update("UPDATE variants SET non_returnable = true WHERE id = ?", a.variant2());
        Order other = deliveredOrder(a, "#4002", "01044440002", 1, 0, a.variant2());

        List<ResponseEntity<Map>> failures = List.of(
            submit("snouts-4b", token, lines(a.variant(), 2, "wrong_size"), null, null),       // above returnable
            submit("snouts-4b", tokens.issue(a.id(), other.id()), lines(a.variant2(), 1, "damaged"), null, null), // non_returnable
            submit("snouts-4b", token, lines(unknownVariant, 1, "damaged"), null, null),        // unknown variant
            submit("snouts-4b", token, lines(a.variant2(), 1, "damaged"), null, null),          // variant not on this order
            submit("snouts-4b", token, lines(a.variant(), 0, "damaged"), null, null),           // quantity 0
            submit("snouts-4b", token, lines(a.variant(), 1, "not_a_reason"), null, null),      // bad reason
            submit("snouts-4b", token, lines(a.variant(), 1, "damaged"), "not-an-email", null), // bad email
            submit("snouts-4b", token, lines(a.variant(), 1, "damaged"), null, "x".repeat(301)),// note too long
            submitRaw("snouts-4b", token, "{\"lines\":[{\"variantId\":\"not-a-uuid\",\"quantity\":1}]}"),
            submitRaw("snouts-4b", token, "{not json"));
        for (ResponseEntity<Map> r : failures) {
            assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(r.getBody()).isEqualTo(Map.of("message", INVALID));
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM return_requests WHERE tenant_id = ?", Integer.class, a.id())).isZero();
    }

    @Test
    void submit_raceForTheLastPiece_exactlyOneWins_other409() throws Exception {
        Order o = deliveredOrder(a, "#5001", "01055550001", 1, 0);
        String piece = o.pieces().get(0);

        // A competing submission has bound the same last piece but not committed yet (READ
        // COMMITTED: ours can't see it, so it binds the same piece and then blocks on the
        // one-active-item-per-piece index until the competitor commits).
        try (Connection competitor = DriverManager.getConnection(POSTGRES.getJdbcUrl(), "postgres", "postgres")) {
            competitor.setAutoCommit(false);
            UUID reqId = UUID.randomUUID();
            exec(competitor, "INSERT INTO return_requests (id, tenant_id, order_id, reference) VALUES (?, ?, ?, 'RR-RACE22')",
                reqId, a.id(), o.id());
            exec(competitor, "INSERT INTO return_request_items (tenant_id, request_id, piece_id, variant_id, reason_code) " +
                "VALUES (?, ?, ?, ?, 'damaged')", a.id(), reqId, piece, a.variant());

            ExecutorService pool = Executors.newSingleThreadExecutor();
            Future<ResponseEntity<Map>> ours = pool.submit(() ->
                submit("snouts-4b", tokens.issue(a.id(), o.id()), lines(a.variant(), 1, "wrong_size"), null, null));
            Thread.sleep(1500);
            assertThat(ours.isDone()).as("blocked on the unique index while the competitor is open").isFalse();
            competitor.commit();

            ResponseEntity<Map> r = ours.get(20, TimeUnit.SECONDS);
            pool.shutdown();
            assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(r.getBody()).isEqualTo(Map.of("message", CONFLICT));
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM return_request_items WHERE piece_id = ? AND active",
            Integer.class, piece)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM return_requests WHERE tenant_id = ?", Integer.class, a.id()))
            .as("the losing request rolled back entirely").isEqualTo(1);
    }

    @Test
    void submit_partialThenRemaining_bothSucceed_thirdFails() {
        Order o = deliveredOrder(a, "#6001", "01066660001", 2, 0);
        String token = tokens.issue(a.id(), o.id());
        assertThat(submit("snouts-4b", token, lines(a.variant(), 1, "wrong_size"), null, null).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(submit("snouts-4b", token, lines(a.variant(), 1, "changed_mind"), null, null).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(jdbc.queryForList("SELECT piece_id FROM return_request_items WHERE tenant_id = ? AND active", String.class, a.id()))
            .containsExactlyInAnyOrderElementsOf(o.pieces());
        assertThat(submit("snouts-4b", token, lines(a.variant(), 1, "damaged"), null, null).getStatusCode())
            .as("nothing left to request").isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void submit_orderPushedOutsideWindowAfterLookup_failsEvenWithValidToken() {
        Order o = deliveredOrder(a, "#7001", "01077770001", 1, 0);
        String token = lookupToken("snouts-4b", "7001", "01077770001");
        jdbc.update("UPDATE shipments SET delivered_at = now() - interval '45 days' WHERE order_id = ?", o.id());
        ResponseEntity<Map> r = submit("snouts-4b", token, lines(a.variant(), 1, "damaged"), null, null);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(r.getBody()).isEqualTo(Map.of("message", INVALID));
    }

    // ── Merchant: approve / reject ─────────────────────────────────────────────

    @Test
    void approveReject_onlyFromRequested_rejectFreesPieces() {
        Order o = deliveredOrder(a, "#8001", "01088880001", 2, 0);
        UUID r1 = requestId(submit("snouts-4b", tokens.issue(a.id(), o.id()), lines(a.variant(), 1, "damaged"), null, null));
        UUID r2 = requestId(submit("snouts-4b", tokens.issue(a.id(), o.id()), lines(a.variant(), 1, "wrong_size"), null, null));

        assertThat(post("/api/v1/return-requests/" + r1 + "/approve", null, ownerA).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(post("/api/v1/return-requests/" + r1 + "/approve", null, ownerA).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(post("/api/v1/return-requests/" + r1 + "/reject", Map.of("reason", "late"), ownerA).getStatusCode())
            .isEqualTo(HttpStatus.CONFLICT);
        Map<String, Object> approved = get("/api/v1/return-requests/" + r1, ownerA).getBody();
        assertThat(approved.get("status")).isEqualTo("approved");
        assertThat(approved.get("decidedBy")).isEqualTo(a.owner().toString());

        assertThat(post("/api/v1/return-requests/" + r2 + "/reject", Map.of("reason", " "), managerA).getStatusCode())
            .as("reason required").isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(post("/api/v1/return-requests/" + r2 + "/reject", Map.of("reason", "x".repeat(301)), managerA).getStatusCode())
            .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(returnable(o)).as("before reject: one piece still requested").isEqualTo(0);
        assertThat(post("/api/v1/return-requests/" + r2 + "/reject", Map.of("reason", "Outside policy"), managerA).getStatusCode())
            .isEqualTo(HttpStatus.NO_CONTENT);
        Map<String, Object> rejected = get("/api/v1/return-requests/" + r2, ownerA).getBody();
        assertThat(rejected.get("status")).isEqualTo("rejected");
        assertThat(rejected.get("rejectionReason")).isEqualTo("Outside policy");
        assertThat(((List<Map<String, Object>>) rejected.get("items"))).allSatisfy(i -> assertThat(i.get("active")).isEqualTo(false));
        assertThat(returnable(o)).as("rejected request's piece is returnable again").isEqualTo(1);
        assertThat(post("/api/v1/return-requests/" + r2 + "/approve", null, ownerA).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(post("/api/v1/return-requests/" + UUID.randomUUID() + "/approve", null, ownerA).getStatusCode())
            .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void list_andDetail_showMerchantFields() {
        Order o = deliveredOrder(a, "#8101", "01088880101", 1, 0);
        UUID id = requestId(submit("snouts-4b", tokens.issue(a.id(), o.id()), lines(a.variant(), 1, "not_as_pictured"),
            "cust@example.com", "Colour differs"));
        ResponseEntity<Map> list = rest.exchange(base() + "/api/v1/return-requests?status=requested", HttpMethod.GET,
            new HttpEntity<>(auth(ownerA)), Map.class);
        List<Map<String, Object>> items = (List<Map<String, Object>>) list.getBody().get("items");
        assertThat(items).singleElement().satisfies(r -> {
            assertThat(r.get("orderNumber")).isEqualTo("#8101");
            assertThat(r.get("customerName")).isEqualTo("Mona Customer");
            assertThat(r.get("itemCount")).isEqualTo(1);
            assertThat(r.get("reasonCodes")).isEqualTo(List.of("not_as_pictured"));
            assertThat(r.get("status")).isEqualTo("requested");
        });
        Map<String, Object> d = get("/api/v1/return-requests/" + id, ownerA).getBody();
        assertThat(d.get("email")).isEqualTo("cust@example.com");
        assertThat(d.get("note")).isEqualTo("Colour differs");
        assertThat((List<Map<String, Object>>) d.get("items")).singleElement().satisfies(i -> {
            assertThat(i.get("productTitle")).isEqualTo("Linen Shirt");
            assertThat(i.get("variantTitle")).isEqualTo("Sand / M");
            assertThat(i.get("imageUrl")).isEqualTo("https://cdn.shopify.com/s/files/1/linen.jpg");
            assertThat((String) i.get("shortCode")).startsWith("P");
            assertThat(i.get("reasonCode")).isEqualTo("not_as_pictured");
        });
        assertThat(rest.exchange(base() + "/api/v1/return-requests?status=bogus", HttpMethod.GET,
            new HttpEntity<>(auth(ownerA)), Map.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // ── Settings ──────────────────────────────────────────────────────────────

    @Test
    void settings_validation_takenSlug_enableWithoutSlug_windowRange_andValidUpdate() throws Exception {
        assertThat(put("/api/v1/tenant/portal-settings", settings("returns", true, false, 30), ownerA).getStatusCode())
            .as("reserved").isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(put("/api/v1/tenant/portal-settings", settings("Bad Slug!", true, false, 30), ownerA).getStatusCode())
            .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(put("/api/v1/tenant/portal-settings", settings("jumi-4b", true, false, 30), ownerA).getStatusCode())
            .as("taken by tenant B").isEqualTo(HttpStatus.CONFLICT);
        assertThat(put("/api/v1/tenant/portal-settings", settings(null, true, false, 30), ownerA).getStatusCode())
            .as("enable without slug").isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(put("/api/v1/tenant/portal-settings", settings("snouts-new", true, false, 0), ownerA).getStatusCode())
            .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(put("/api/v1/tenant/portal-settings", settings("snouts-new", true, false, 91), ownerA).getStatusCode())
            .isEqualTo(HttpStatus.BAD_REQUEST);

        ResponseEntity<Map> ok = put("/api/v1/tenant/portal-settings", settings("Snouts-New", true, true, 14), managerA);
        assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(ok.getBody()).containsEntry("slug", "snouts-new").containsEntry("enabled", true)
            .containsEntry("autoApprove", true).containsEntry("returnWindowDays", 14);
        assertThat(get("/api/v1/tenant/portal-settings", ownerA).getBody()).containsEntry("slug", "snouts-new");
        assertThat(jdbc.queryForObject("SELECT customer_return_window_days FROM tenants WHERE id = ?", Integer.class, a.id()))
            .isEqualTo(14);
        try (Connection c = DriverManager.getConnection(POSTGRES.getJdbcUrl(), "app_user", "testpw");
             PreparedStatement ps = c.prepareStatement("SELECT resolve_tenant_by_portal_slug('snouts-new')")) {
            var rs = ps.executeQuery();
            rs.next();
            assertThat(rs.getObject(1, UUID.class)).as("new slug resolves via hatch #14").isEqualTo(a.id());
        }
    }

    @Test
    void settings_takenSlugWithChangedWindow_409_andNothingSaved() {
        assertThat(get("/api/v1/tenant/portal-settings", ownerA).getBody()).containsEntry("returnWindowDays", 30);
        ResponseEntity<Map> r = put("/api/v1/tenant/portal-settings", settings("jumi-4b", true, true, 7), ownerA);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        Map<String, Object> after = jdbc.queryForMap(
            "SELECT portal_slug, portal_auto_approve, customer_return_window_days FROM tenants WHERE id = ?", a.id());
        assertThat(after.get("customer_return_window_days")).as("all-or-nothing").isEqualTo(30);
        assertThat(after.get("portal_auto_approve")).isEqualTo(false);
        assertThat(after.get("portal_slug")).isEqualTo("snouts-4b");
    }

    @Test
    void nonReturnableFlag_setByManager_reflectedInLookup_otherTenantsVariant404() {
        Order o = deliveredOrder(a, "#9001", "01099990001", 1, 0);
        assertThat(put("/api/v1/variants/" + a.variant() + "/non-returnable", Map.of("value", true), managerA).getStatusCode())
            .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(returnable(o)).isEqualTo(0);
        assertThat(put("/api/v1/variants/" + b.variant() + "/non-returnable", Map.of("value", true), ownerA).getStatusCode())
            .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(jdbc.queryForObject("SELECT non_returnable FROM variants WHERE id = ?", Boolean.class, b.variant())).isFalse();
    }

    // ── Roles ─────────────────────────────────────────────────────────────────

    @Test
    void worker_403_onEveryMerchantAndSettingsEndpoint() {
        UUID any = UUID.randomUUID();
        List<ResponseEntity<Map>> responses = List.of(
            get("/api/v1/return-requests", workerA),
            get("/api/v1/return-requests/" + any, workerA),
            post("/api/v1/return-requests/" + any + "/approve", null, workerA),
            post("/api/v1/return-requests/" + any + "/reject", Map.of("reason", "x"), workerA),
            get("/api/v1/tenant/portal-settings", workerA),
            put("/api/v1/tenant/portal-settings", settings("worker-slug", false, false, 30), workerA),
            put("/api/v1/variants/" + a.variant() + "/non-returnable", Map.of("value", true), workerA));
        for (ResponseEntity<Map> r : responses) assertThat(r.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    record Order(UUID id, List<String> pieces) {}

    private Tenant tenant(String name, String slug, boolean autoApprove) {
        UUID id = UUID.randomUUID(), store = UUID.randomUUID(), owner = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name, portal_slug, portal_enabled, portal_auto_approve) VALUES (?, ?, ?, true, ?)",
            id, name, slug, autoApprove);
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status) VALUES (?, ?, 'shopify', ?, 'disconnected')",
            store, id, slug + ".myshopify.com");
        jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role, active) VALUES (?, ?, 'Owner', ?, ?, 'owner', true)",
            owner, id, "owner-" + owner + "@test.local", passwordEncoder.encode("pass123"));
        return new Tenant(id, store, variant(id, store, "Linen Shirt", "Sand / M", "https://cdn.shopify.com/s/files/1/linen.jpg"),
            variant(id, store, "Wool Scarf", "Grey", null), owner, slug);
    }

    private UUID user(UUID tenant, String role) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role, active) VALUES (?, ?, ?, ?, ?, ?::user_role, true)",
            id, tenant, role, role + "-" + id + "@test.local", passwordEncoder.encode("pass123"), role);
        return id;
    }

    private UUID variant(UUID tenant, UUID store, String product, String title, String image) {
        UUID p = UUID.randomUUID(), v = UUID.randomUUID();
        jdbc.update("INSERT INTO products (id, tenant_id, store_id, external_id, title, status, image_url) VALUES (?, ?, ?, ?, ?, 'active', ?)",
            p, tenant, store, "P-" + p, product, image);
        jdbc.update("INSERT INTO variants (id, tenant_id, product_id, external_id, title, sku) VALUES (?, ?, ?, ?, ?, ?)",
            v, tenant, p, "V-" + v, title, "SKU-" + v.toString().substring(0, 6));
        return v;
    }

    private Order deliveredOrder(Tenant t, String number, String phone, int pieces, int deliveredDaysAgo) {
        return deliveredOrder(t, number, phone, pieces, deliveredDaysAgo, t.variant());
    }

    private Order deliveredOrder(Tenant t, String number, String phone, int pieces, int deliveredDaysAgo, UUID variant) {
        UUID order = jdbc.queryForObject(
            "INSERT INTO orders (tenant_id, store_id, external_id, number, status, payment_method, placed_at, " +
            "    customer_name, customer_phone, pii_source) " +
            "VALUES (?, ?, ?, ?, 'delivered'::order_status, 'cod'::order_payment_method, now(), 'Mona Customer', ?, 'bosta') RETURNING id",
            UUID.class, t.id(), t.store(), "gid://shopify/Order/" + UUID.randomUUID(), number, phone);
        jdbc.update("INSERT INTO shipments (tenant_id, order_id, provider, tracking_number, internal_state, shipment_leg, delivered_at) " +
                    "VALUES (?, ?, 'bosta', ?, 'delivered'::shipment_internal_state, 'forward', now() - (interval '1 day' * ?))",
                    t.id(), order, String.valueOf(ThreadLocalRandom.current().nextLong(1_000_000_000L, 9_999_999_999L)),
                    deliveredDaysAgo);
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < pieces; i++) {
            String id = UlidGenerator.generate();
            jdbc.update("INSERT INTO pieces (id, tenant_id, variant_id, barcode, short_code, status, current_order_id, last_event_at) " +
                        "VALUES (?, ?, ?, ?, 'P' || LPAD((abs(hashtext(?)) % 999999 + 1)::text, 6, '0'), 'delivered'::piece_status, ?, now())",
                        id, t.id(), variant, "PC-" + id, id, order);
            UUID item = UUID.randomUUID();
            jdbc.update("INSERT INTO order_items (id, tenant_id, order_id, variant_id, quantity) VALUES (?, ?, ?, ?, 1)",
                        item, t.id(), order, variant);
            jdbc.update("INSERT INTO allocations (tenant_id, order_item_id, piece_id, status) VALUES (?, ?, ?, 'packed')",
                        t.id(), item, id);
            ids.add(id);
        }
        return new Order(order, ids);
    }

    private String lookupToken(String slug, String orderNumber, String phone) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<Map> r = rest.exchange(base() + "/api/v1/portal/" + slug + "/lookup", HttpMethod.POST,
            new HttpEntity<>(Map.of("orderNumber", orderNumber, "phone", phone), h), Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        return (String) r.getBody().get("token");
    }

    /** Returnable quantity of the order's first line, as the portal lookup reports it. */
    private int returnable(Order o) {
        String number = jdbc.queryForObject("SELECT number FROM orders WHERE id = ?", String.class, o.id());
        String phone = jdbc.queryForObject("SELECT customer_phone FROM orders WHERE id = ?", String.class, o.id());
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<Map> r = rest.exchange(base() + "/api/v1/portal/snouts-4b/lookup", HttpMethod.POST,
            new HttpEntity<>(Map.of("orderNumber", number, "phone", phone), h), Map.class);
        List<Map<String, Object>> lines = (List<Map<String, Object>>) r.getBody().get("lines");
        return (Integer) lines.get(0).get("returnableQuantity");
    }

    private static Map<String, Object> lines(UUID variant, int qty, String reason) {
        return Map.of("lines", List.of(Map.of("variantId", variant.toString(), "quantity", qty, "reasonCode", reason)));
    }

    private static Map<String, Object> settings(String slug, boolean enabled, boolean autoApprove, int window) {
        Map<String, Object> m = new HashMap<>();
        m.put("slug", slug);
        m.put("enabled", enabled);
        m.put("autoApprove", autoApprove);
        m.put("returnWindowDays", window);
        return m;
    }

    private ResponseEntity<Map> submit(String slug, String token, Map<String, Object> linesBody, String email, String note) {
        Map<String, Object> body = new HashMap<>(linesBody);
        if (email != null) body.put("email", email);
        if (note != null) body.put("note", note);
        try {
            return submitRaw(slug, token, mapper.writeValueAsString(body));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private ResponseEntity<Map> submitRaw(String slug, String token, String json) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) h.setBearerAuth(token);
        // The JDK HttpURLConnection can't read a 401 body on a streamed POST; use java.net.http.
        return jdkRest.exchange(base() + "/api/v1/portal/" + slug + "/requests", HttpMethod.POST, new HttpEntity<>(json, h), Map.class);
    }

    private UUID requestId(ResponseEntity<Map> created) {
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return jdbc.queryForObject("SELECT id FROM return_requests WHERE reference = ?", UUID.class, created.getBody().get("reference"));
    }

    private String base() { return "http://localhost:" + port; }

    private String login(UUID userId, String unused) {
        String email = jdbc.queryForObject("SELECT email FROM users WHERE id = ?", String.class, userId);
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<AccessTokenResponse> resp = rest.postForEntity(base() + "/api/v1/auth/login",
            new HttpEntity<>(Map.of("email", email, "password", "pass123"), h), AccessTokenResponse.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        return resp.getBody().accessToken();
    }

    private HttpHeaders auth(String token) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    private ResponseEntity<Map> get(String path, String token) {
        return rest.exchange(base() + path, HttpMethod.GET, new HttpEntity<>(auth(token)), Map.class);
    }

    private ResponseEntity<Map> post(String path, Object body, String token) {
        return rest.exchange(base() + path, HttpMethod.POST, new HttpEntity<>(body, auth(token)), Map.class);
    }

    private ResponseEntity<Map> put(String path, Object body, String token) {
        return rest.exchange(base() + path, HttpMethod.PUT, new HttpEntity<>(body, auth(token)), Map.class);
    }

    private static void exec(Connection c, String sql, Object... args) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
            ps.executeUpdate();
        }
    }
}
