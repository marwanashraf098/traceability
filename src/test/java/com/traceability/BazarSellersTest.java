package com.traceability;

import com.traceability.identity.AuthRepository;
import com.traceability.identity.model.AccessTokenResponse;
import com.traceability.inventory.UlidGenerator;
import com.traceability.tenancy.TenantAwareDataSource;
import com.traceability.tenancy.TenantContext;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
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

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Bazar POS B6b — seller invites over HTTP: the link (single use, 24 h, bound to user + tenant, opened through hatch
 * #6), who can be invited (workers only), the rate limit, the audit row, the PIN set once then used by the PIN switch,
 * landing on the event, seller_roles still deciding who sells, Remove seller revoking sessions, and no new hatch.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BazarSellersTest {

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
    @Autowired JdbcTemplate     jdbc;
    @Autowired PasswordEncoder  passwordEncoder;
    @Value("${shopify.app-url:http://localhost:5173}") String appUrl;

    final HttpClient noRedirect = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

    UUID tenantA, tenantB, mainA, variantA;
    String ownerA, managerA, ownerB;
    UUID managerAId, ownerAId;
    int seq = 0, pieceSeq = 0;

    @BeforeAll
    void setup() {
        tenantA = UUID.randomUUID();
        tenantB = UUID.randomUUID();
        mainA = UUID.randomUUID();
        variantA = UUID.randomUUID();
        UUID storeId = UUID.randomUUID(), productId = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name, bazar_enabled) VALUES (?, 'Sellers A', true)", tenantA);
        jdbc.update("INSERT INTO tenants (id, name, bazar_enabled) VALUES (?, 'Sellers B', true)", tenantB);
        jdbc.update("INSERT INTO locations (id, tenant_id, name, type, is_default, is_fulfillment) " +
            "VALUES (?, ?, 'Main Warehouse', 'warehouse', true, true)", mainA, tenantA);
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status) " +
            "VALUES (?, ?, 'shopify', 'sellers-a.myshopify.com', 'disconnected')", storeId, tenantA);
        jdbc.update("INSERT INTO products (id, tenant_id, store_id, external_id, title, status) " +
            "VALUES (?, ?, ?, 'PROD-SL', 'Linen Shirt', 'active')", productId, tenantA, storeId);
        jdbc.update("INSERT INTO variants (id, tenant_id, product_id, external_id, title, price) " +
            "VALUES (?, ?, ?, 'VAR-SL', 'Blue', 300.00)", variantA, tenantA, productId);
        ownerAId = UUID.randomUUID();
        managerAId = UUID.randomUUID();
        ownerA   = login(user(ownerAId, tenantA, "owner"));
        managerA = login(user(managerAId, tenantA, "manager"));
        ownerB   = login(user(UUID.randomUUID(), tenantB, "owner"));
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private String user(UUID id, UUID tenant, String role) {
        String email = role + "-" + UUID.randomUUID() + "@bazar-sellers.test";
        jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role, active) " +
            "VALUES (?, ?, ?, ?, ?, ?::user_role, true)",
            id, tenant, "Http " + role, email, passwordEncoder.encode("pass123"), role);
        return email;
    }

    private String login(String email) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<AccessTokenResponse> resp = rest.postForEntity(base() + "/api/v1/auth/login",
            new HttpEntity<>(Map.of("email", email, "password", "pass123"), h), AccessTokenResponse.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        return resp.getBody().accessToken();
    }

    private String base() { return "http://localhost:" + port; }

    @SuppressWarnings("rawtypes")
    private ResponseEntity<Map> call(String token, HttpMethod method, String path, Object body) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        h.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange(base() + path, method, new HttpEntity<>(body, h), Map.class);
    }

    private String piece() {
        String id = UlidGenerator.generate();
        jdbc.update("INSERT INTO pieces (id, tenant_id, variant_id, barcode, short_code, status, current_location_id) " +
            "VALUES (?, ?, ?, ?, ?, 'available', ?)",
            id, tenantA, variantA, "PC-" + id, String.format("P%07d", 7300000 + ++pieceSeq), mainA);
        return jdbc.queryForObject("SELECT short_code FROM pieces WHERE id = ?", String.class, id);
    }

    /** A live event of tenant A with {@code n} pieces out: [eventId, codes…]. */
    @SuppressWarnings("rawtypes")
    private List<String> liveEvent(int n, List<String> sellerRoles) {
        Map<String, Object> body = new HashMap<>(Map.of("name", "Seller Bazar " + (++seq), "startsOn", "2026-12-01",
            "endsOn", "2026-12-02", "locationName", "Seller Hall " + seq, "sellerRoles", sellerRoles,
            "paymentMethods", List.of("cash", "card")));
        ResponseEntity<Map> created = call(ownerA, HttpMethod.POST, "/api/v1/bazar-events", body);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String eventId = (String) created.getBody().get("id");
        List<String> out = new ArrayList<>(List.of(eventId));
        for (int i = 0; i < n; i++) {
            String code = piece();
            assertThat(call(ownerA, HttpMethod.POST, "/api/v1/bazar-events/" + eventId + "/scan-out",
                Map.of("barcode", code)).getBody().get("success")).isEqualTo(true);
            out.add(code);
        }
        assertThat(call(ownerA, HttpMethod.POST, "/api/v1/bazar-events/" + eventId + "/mark-sent", Map.of())
            .getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(call(ownerA, HttpMethod.POST, "/api/v1/bazar-events/" + eventId + "/start", Map.of())
            .getStatusCode()).isEqualTo(HttpStatus.OK);
        return out;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private Map<String, Object> addSeller(String token, String eventId, Map<String, Object> body) {
        ResponseEntity<Map> r = call(token, HttpMethod.POST, "/api/v1/bazar-events/" + eventId + "/sellers", body);
        assertThat(r.getStatusCode()).as(String.valueOf(r.getBody())).isEqualTo(HttpStatus.CREATED);
        return r.getBody();
    }

    @SuppressWarnings("unchecked")
    private static UUID sellerId(Map<String, Object> added) {
        return UUID.fromString(String.valueOf(((Map<String, Object>) added.get("seller")).get("user_id")));
    }

    /** Opens the link without following the redirect. */
    private HttpResponse<String> open(String link) throws Exception {
        String path = link.substring(appUrl.length());
        return noRedirect.send(HttpRequest.newBuilder(URI.create(base() + path)).GET().build(),
            HttpResponse.BodyHandlers.ofString());
    }

    private static String refreshCookie(HttpResponse<String> r) {
        return r.headers().allValues("set-cookie").stream().filter(c -> c.startsWith("traced_refresh="))
            .map(c -> c.substring("traced_refresh=".length(), c.indexOf(';'))).filter(v -> !v.isEmpty())
            .findFirst().orElse(null);
    }

    /** The access token the SPA gets from the refresh cookie (POST /auth/refresh). */
    private ResponseEntity<AccessTokenResponse> refresh(String cookie) {
        HttpHeaders h = new HttpHeaders();
        h.add(HttpHeaders.COOKIE, "traced_refresh=" + cookie);
        return rest.exchange(base() + "/api/v1/auth/refresh", HttpMethod.POST, new HttpEntity<>(null, h),
            AccessTokenResponse.class);
    }

    /** Opens a fresh link and returns the seller's access token. */
    private String signIn(String link) throws Exception {
        HttpResponse<String> r = open(link);
        assertThat(r.statusCode()).isEqualTo(302);
        ResponseEntity<AccessTokenResponse> t = refresh(refreshCookie(r));
        assertThat(t.getStatusCode()).isEqualTo(HttpStatus.OK);
        return t.getBody().accessToken();
    }

    @SuppressWarnings("rawtypes")
    private ResponseEntity<Map> sell(String token, String eventId, String code) {
        return call(token, HttpMethod.POST, "/api/v1/bazar-events/" + eventId + "/sell",
            Map.of("code", code, "client_request_id", "s-" + UUID.randomUUID(), "method", "cash"));
    }

    @SuppressWarnings("rawtypes")
    private ResponseEntity<Map> setPin(String token, String pin) {
        return call(token, HttpMethod.POST, "/api/v1/bazar-sellers/me/pin", Map.of("pin", pin));
    }

    private ResponseEntity<AccessTokenResponse> pinSwitch(String token, UUID userId, String pin) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        h.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange(base() + "/api/v1/auth/pin", HttpMethod.POST,
            new HttpEntity<>(Map.of("userId", userId.toString(), "pin", pin), h), AccessTokenResponse.class);
    }

    // ── the link ────────────────────────────────────────────────────────────

    @Test
    void link_isSingleUse_landsOnTheEvent_andSetsTheRefreshCookie() throws Exception {
        String ev = liveEvent(1, List.of("owner", "worker")).get(0);
        Map<String, Object> added = addSeller(ownerA, ev, Map.of("name", "Mona", "phone", "01012345678"));
        String link = (String) added.get("link");
        assertThat(link).startsWith(appUrl + "/auth/seller-invite?token=");
        assertThat(added.get("workers_allowed")).isEqualTo(true);

        HttpResponse<String> first = open(link);
        assertThat(first.statusCode()).isEqualTo(302);
        assertThat(first.headers().firstValue("location").orElseThrow()).isEqualTo(appUrl + "/bazar/join/" + ev);
        assertThat(refreshCookie(first)).isNotNull();
        // The session is the invited worker's, in tenant A.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM refresh_tokens WHERE user_id = ? AND tenant_id = ? " +
            "AND created_via = 'magic_link' AND revoked_at IS NULL", Integer.class, sellerId(added), tenantA)).isEqualTo(1);

        HttpResponse<String> second = open(link);
        assertThat(second.statusCode()).isEqualTo(302);
        assertThat(second.headers().firstValue("location").orElseThrow()).isEqualTo(appUrl + "/bazar/invite-expired");
        assertThat(refreshCookie(second)).as("no session from a used link").isNull();
    }

    @Test
    void expiredLink_isRefused() throws Exception {
        String ev = liveEvent(1, List.of("owner", "worker")).get(0);
        Map<String, Object> added = addSeller(ownerA, ev, Map.of("name", "Late"));
        String link = (String) added.get("link");
        String raw = link.substring(link.indexOf("token=") + 6);
        // Issued for 24 h …
        assertThat(jdbc.queryForObject("SELECT extract(epoch FROM expires_at - created_at)::int FROM magic_link_tokens " +
            "WHERE token_hash = ?", Integer.class, AuthRepository.sha256(raw))).isBetween(24 * 3600 - 5, 24 * 3600 + 5);
        // … and past it.
        jdbc.update("UPDATE magic_link_tokens SET expires_at = now() - interval '1 second' WHERE token_hash = ?",
            AuthRepository.sha256(raw));
        HttpResponse<String> r = open(link);
        assertThat(r.headers().firstValue("location").orElseThrow()).isEqualTo(appUrl + "/bazar/invite-expired");
        assertThat(refreshCookie(r)).isNull();
    }

    @Test
    @SuppressWarnings("rawtypes")
    void wrongTenant_isRefused() throws Exception {
        String ev = liveEvent(1, List.of("owner", "worker")).get(0);
        Map<String, Object> added = addSeller(ownerA, ev, Map.of("name", "Tenant A worker"));
        UUID workerA = sellerId(added);
        // Tenant B's owner can't add A's event's sellers, list them, or pick A's worker for an event of B.
        assertThat(call(ownerB, HttpMethod.POST, "/api/v1/bazar-events/" + ev + "/sellers", Map.of("name", "X"))
            .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(call(ownerB, HttpMethod.GET, "/api/v1/bazar-events/" + ev + "/sellers", null).getStatusCode())
            .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(call(ownerB, HttpMethod.POST, "/api/v1/bazar-events/" + ev + "/sellers/" + workerA + "/link", null)
            .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        Map<String, Object> bodyB = new HashMap<>(Map.of("name", "B Bazar", "startsOn", "2026-12-01",
            "endsOn", "2026-12-02", "locationName", "B Hall", "sellerRoles", List.of("owner", "worker"),
            "paymentMethods", List.of("cash")));
        String evB = (String) call(ownerB, HttpMethod.POST, "/api/v1/bazar-events", bodyB).getBody().get("id");
        ResponseEntity<Map> pick = call(ownerB, HttpMethod.POST, "/api/v1/bazar-events/" + evB + "/sellers",
            Map.of("user_id", workerA.toString()));
        assertThat(pick.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(pick.getBody().get("code")).isEqualTo("BAZAR_SELLER_NOT_FOUND");

        // A's seller signed in through the link is A's: B's event isn't theirs.
        String sellerToken = signIn((String) added.get("link"));
        assertThat(call(sellerToken, HttpMethod.GET, "/api/v1/bazar-events/" + evB + "/sellers/me", null)
            .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ── who can be invited, who can invite ──────────────────────────────────

    @Test
    @SuppressWarnings("rawtypes")
    void invite_neverCreatesOrPicksAnOwnerOrManager() {
        String ev = liveEvent(1, List.of("owner", "worker")).get(0);
        // A role in the body is ignored: always a worker.
        Map<String, Object> body = new HashMap<>(Map.of("name", "Sneaky", "role", "owner"));
        UUID created = sellerId(addSeller(ownerA, ev, body));
        assertThat(jdbc.queryForObject("SELECT role::text FROM users WHERE id = ?", String.class, created)).isEqualTo("worker");
        assertThat(jdbc.queryForObject("SELECT pin_code IS NULL FROM users WHERE id = ?", Boolean.class, created)).isTrue();
        // An existing manager / owner can't be picked.
        for (UUID notWorker : List.of(managerAId, ownerAId)) {
            ResponseEntity<Map> r = call(ownerA, HttpMethod.POST, "/api/v1/bazar-events/" + ev + "/sellers",
                Map.of("user_id", notWorker.toString()));
            assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(r.getBody().get("code")).isEqualTo("BAZAR_NOT_A_WORKER");
        }
        // A manager can invite; a worker (even a seller) can't.
        String sellerToken = login(user(UUID.randomUUID(), tenantA, "worker"));
        addSeller(managerA, ev, Map.of("name", "By manager"));
        assertThat(call(sellerToken, HttpMethod.POST, "/api/v1/bazar-events/" + ev + "/sellers", Map.of("name", "W"))
            .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @SuppressWarnings("rawtypes")
    void issuance_isRateLimitedPerTenant() {
        UUID t = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name, bazar_enabled) VALUES (?, 'Rate', true)", t);
        UUID ownerId = UUID.randomUUID();
        String owner = login(user(ownerId, t, "owner"));
        Map<String, Object> body = new HashMap<>(Map.of("name", "Rate Bazar", "startsOn", "2026-12-01",
            "endsOn", "2026-12-02", "locationName", "Rate Hall", "sellerRoles", List.of("owner", "worker"),
            "paymentMethods", List.of("cash")));
        String ev = (String) call(owner, HttpMethod.POST, "/api/v1/bazar-events", body).getBody().get("id");
        UUID first = sellerId(addSeller(owner, ev, Map.of("name", "First")));
        // 29 more links this hour (one per seller row is not required: links for the same seller count too).
        jdbc.update("INSERT INTO bazar_seller_invites (tenant_id, event_id, user_id, invited_by, token_hash, expires_at) " +
            "SELECT ?, ?, ?, ?, 'rate-' || g, now() + interval '1 day' FROM generate_series(1, 28) g",
            t, UUID.fromString(ev), first, ownerId);
        assertThat(call(owner, HttpMethod.POST, "/api/v1/bazar-events/" + ev + "/sellers/" + first + "/link", null)
            .getStatusCode()).as("the 30th").isEqualTo(HttpStatus.OK);
        ResponseEntity<Map> over = call(owner, HttpMethod.POST, "/api/v1/bazar-events/" + ev + "/sellers", Map.of("name", "31st"));
        assertThat(over.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(over.getBody().get("code")).isEqualTo("BAZAR_INVITE_RATE_LIMITED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE tenant_id = ? AND name = '31st'", Integer.class, t))
            .as("nothing created when refused").isZero();
        // Another tenant is unaffected.
        String evA = liveEvent(1, List.of("owner", "worker")).get(0);
        addSeller(ownerA, evA, Map.of("name", "Unaffected"));
        // Links older than an hour don't count.
        jdbc.update("UPDATE bazar_seller_invites SET created_at = now() - interval '61 minutes', " +
            "expires_at = now() + interval '1 day' WHERE tenant_id = ?", t);
        addSeller(owner, ev, Map.of("name", "Next hour"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void everyLink_writesAnAuditRow_andAnInviteRow() {
        String ev = liveEvent(1, List.of("owner", "worker")).get(0);
        UUID seller = sellerId(addSeller(ownerA, ev, Map.of("name", "Audited")));
        call(managerA, HttpMethod.POST, "/api/v1/bazar-events/" + ev + "/sellers/" + seller + "/link", null);
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT actor_user_id, metadata->>'event_id' AS event_id, metadata->>'invited_by' AS invited_by, created_at " +
            "FROM audit_log WHERE tenant_id = ? AND action = 'bazar_seller_invite' AND target_type = 'user' " +
            "AND target_id = ? ORDER BY created_at", tenantA, seller.toString());
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).get("actor_user_id")).isEqualTo(ownerAId);
        assertThat(rows.get(1).get("actor_user_id")).isEqualTo(managerAId);
        assertThat(rows).allMatch(r -> ev.equals(r.get("event_id")) && r.get("created_at") != null);
        assertThat(rows.get(1).get("invited_by")).isEqualTo(managerAId.toString());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bazar_seller_invites WHERE user_id = ? AND event_id = ?::uuid",
            Integer.class, seller, ev)).isEqualTo(2);
        // The invite rows are append-only for app_user.
        assertThat(jdbc.queryForObject("SELECT has_table_privilege('app_user', 'bazar_seller_invites', 'UPDATE') " +
            "OR has_table_privilege('app_user', 'bazar_seller_invites', 'DELETE')", Boolean.class)).isFalse();
    }

    // ── PIN ─────────────────────────────────────────────────────────────────

    @Test
    @SuppressWarnings("rawtypes")
    void pin_setOnce_thenRequiredForTheSwitch() throws Exception {
        List<String> ev = liveEvent(1, List.of("owner", "worker"));
        Map<String, Object> added = addSeller(ownerA, ev.get(0), Map.of("name", "Pinny"));
        UUID seller = sellerId(added);
        String token = signIn((String) added.get("link"));

        // No PIN yet: the switch can't reach them, the first-open screen asks for one.
        assertThat(pinSwitch(ownerA, seller, "1234").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(call(token, HttpMethod.GET, "/api/v1/bazar-events/" + ev.get(0) + "/sellers/me", null)
            .getBody().get("needs_pin")).isEqualTo(true);

        assertThat(setPin(token, "12a4").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(setPin(token, "4821").getStatusCode()).isEqualTo(HttpStatus.OK);
        ResponseEntity<Map> again = setPin(token, "0000");
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(again.getBody().get("code")).isEqualTo("BAZAR_PIN_ALREADY_SET");
        assertThat(call(token, HttpMethod.GET, "/api/v1/bazar-events/" + ev.get(0) + "/sellers/me", null)
            .getBody().get("needs_pin")).isEqualTo(false);

        // Switch seller: listed on the roster, wrong PIN refused, right PIN switches to them.
        List roster = rest.exchange(base() + "/api/v1/bazar-events/" + ev.get(0) + "/sellers/roster",
            HttpMethod.GET, new HttpEntity<>(bearer(ownerA)), List.class).getBody();
        assertThat(roster).anyMatch(r -> seller.toString().equals(((Map) r).get("id")));
        assertThat(pinSwitch(ownerA, seller, "0000").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        ResponseEntity<AccessTokenResponse> ok = pinSwitch(ownerA, seller, "4821");
        assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(sell(ok.getBody().accessToken(), ev.get(0), ev.get(1)).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(jdbc.queryForObject("SELECT sold_by FROM bazar_sales WHERE event_id = ?::uuid", UUID.class, ev.get(0)))
            .isEqualTo(seller);
    }

    private static HttpHeaders bearer(String token) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        return h;
    }

    // ── landing, seller_roles, remove ──────────────────────────────────────

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void seller_landsOnTheLiveBazar() throws Exception {
        List<String> ev = liveEvent(1, List.of("owner", "worker"));
        Map<String, Object> added = addSeller(ownerA, ev.get(0), Map.of("name", "Lander"));
        HttpResponse<String> r = open((String) added.get("link"));
        assertThat(r.headers().firstValue("location").orElseThrow()).endsWith("/bazar/join/" + ev.get(0));
        String token = refresh(refreshCookie(r)).getBody().accessToken();
        Map me = call(token, HttpMethod.GET, "/api/v1/bazar-events/" + ev.get(0) + "/sellers/me", null).getBody();
        assertThat(me.get("can_sell")).isEqualTo(true);
        assertThat(me.get("event_name")).isEqualTo("Seller Bazar " + seq);
        Map self = call(token, HttpMethod.GET, "/api/v1/me", null).getBody();
        assertThat(((Map) self.get("bazarLive")).get("eventId")).isEqualTo(ev.get(0));
        assertThat(sell(token, ev.get(0), ev.get(1)).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @SuppressWarnings("rawtypes")
    void seller_withoutWorkerInSellerRoles_isRefused_untilWorkersAreAllowed() throws Exception {
        List<String> ev = liveEvent(1, List.of("owner", "manager"));
        Map<String, Object> added = addSeller(ownerA, ev.get(0), Map.of("name", "Not yet"));
        assertThat(added.get("workers_allowed")).as("the card warns").isEqualTo(false);
        String token = signIn((String) added.get("link"));
        ResponseEntity<Map> refused = sell(token, ev.get(0), ev.get(1));
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(refused.getBody().get("code")).isEqualTo("BAZAR_NOT_A_SELLER");
        assertThat(call(token, HttpMethod.GET, "/api/v1/bazar-events/" + ev.get(0) + "/sellers/me", null)
            .getBody().get("can_sell")).isEqualTo(false);
        // A worker can't allow themselves.
        assertThat(call(token, HttpMethod.POST, "/api/v1/bazar-events/" + ev.get(0) + "/sellers/allow-workers", null)
            .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        assertThat(call(ownerA, HttpMethod.POST, "/api/v1/bazar-events/" + ev.get(0) + "/sellers/allow-workers", null)
            .getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = 'bazar_allow_workers' AND target_id = ?",
            Integer.class, ev.get(0))).isEqualTo(1);
        assertThat(sell(token, ev.get(0), ev.get(1)).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @SuppressWarnings("rawtypes")
    void removeSeller_revokesTheirSessions_andTheyCantSell() throws Exception {
        List<String> ev = liveEvent(2, List.of("owner", "worker"));
        Map<String, Object> added = addSeller(ownerA, ev.get(0), Map.of("name", "Leaver"));
        UUID seller = sellerId(added);
        HttpResponse<String> opened = open((String) added.get("link"));
        String cookie = refreshCookie(opened);
        ResponseEntity<AccessTokenResponse> first = refresh(cookie);
        String token = first.getBody().accessToken();
        assertThat(sell(token, ev.get(0), ev.get(1)).getStatusCode()).isEqualTo(HttpStatus.OK);
        // A second link (unused) for later.
        String spare = (String) call(ownerA, HttpMethod.POST,
            "/api/v1/bazar-events/" + ev.get(0) + "/sellers/" + seller + "/link", null).getBody().get("link");

        ResponseEntity<Map> removed = call(ownerA, HttpMethod.DELETE, "/api/v1/bazar-events/" + ev.get(0) + "/sellers/" + seller, null);
        assertThat(removed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((Number) removed.getBody().get("sessions_revoked")).intValue()).isGreaterThanOrEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM refresh_tokens WHERE user_id = ? AND revoked_at IS NULL",
            Integer.class, seller)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = 'bazar_seller_remove' AND target_id = ?",
            Integer.class, seller.toString())).isEqualTo(1);

        // Every cookie the device held is dead; the still-valid access token can't sell; no bazarLive.
        HttpHeaders h = new HttpHeaders();
        h.add(HttpHeaders.COOKIE, "traced_refresh=" + cookie);
        assertThat(rest.exchange(base() + "/api/v1/auth/refresh", HttpMethod.POST, new HttpEntity<>(null, h), String.class)
            .getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        ResponseEntity<Map> refused = sell(token, ev.get(0), ev.get(2));
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(refused.getBody().get("code")).isEqualTo("BAZAR_NOT_A_SELLER");
        Object live = call(token, HttpMethod.GET, "/api/v1/me", null).getBody().get("bazarLive");
        assertThat(live == null ? null : ((Map) live).get("eventId")).as("not this event's seller any more").isNotEqualTo(ev.get(0));
        assertThat(call(token, HttpMethod.GET, "/api/v1/bazar-events/" + ev.get(0) + "/sellers/roster", null)
            .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        // Not on the list any more.
        assertThat(call(ownerA, HttpMethod.GET, "/api/v1/bazar-events/" + ev.get(0) + "/sellers", null).getBody()
            .get("sellers").toString()).doesNotContain(seller.toString());
        // The unused link opens nothing.
        HttpResponse<String> late = open(spare);
        assertThat(late.headers().firstValue("location").orElseThrow()).isEqualTo(appUrl + "/bazar/invite-expired");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM refresh_tokens WHERE user_id = ? AND revoked_at IS NULL",
            Integer.class, seller)).isZero();
    }

    // ── Remove: an "Add seller" account is turned off; a picked team member stays (B6b review) ──────────

    @SuppressWarnings({"rawtypes", "unchecked"})
    private Map<String, Object> listRow(String ev, UUID user) {
        List<Map> rows = (List<Map>) call(ownerA, HttpMethod.GET, "/api/v1/bazar-events/" + ev + "/sellers", null)
            .getBody().get("sellers");
        return rows.stream().filter(r -> user.toString().equals(r.get("user_id"))).findFirst().orElseThrow();
    }

    private String removeOutcome(String ev, UUID user) {
        @SuppressWarnings("rawtypes")
        ResponseEntity<Map> r = call(ownerA, HttpMethod.DELETE, "/api/v1/bazar-events/" + ev + "/sellers/" + user, null);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        return (String) r.getBody().get("outcome");
    }

    private String auditOutcome(String ev, UUID user) {
        return jdbc.queryForObject("SELECT metadata->>'outcome' FROM audit_log WHERE action = 'bazar_seller_remove' " +
            "AND target_id = ? AND metadata->>'event_id' = ? ORDER BY created_at DESC LIMIT 1",
            String.class, user.toString(), ev);
    }

    @Test
    void removedBazarCreatedSeller_isTurnedOff_andThePinSwitchRefusesThem() throws Exception {
        String ev = liveEvent(1, List.of("owner", "worker")).get(0);
        Map<String, Object> added = addSeller(ownerA, ev, Map.of("name", "Omar"));
        UUID seller = sellerId(added);
        assertThat(setPin(signIn((String) added.get("link")), "2468").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(pinSwitch(ownerA, seller, "2468").getStatusCode()).as("works before").isEqualTo(HttpStatus.OK);
        assertThat(listRow(ev, seller).get("remove_turns_off_account")).isEqualTo(true);

        assertThat(removeOutcome(ev, seller)).isEqualTo("account_turned_off");
        assertThat(jdbc.queryForObject("SELECT active FROM users WHERE id = ?", Boolean.class, seller)).isFalse();
        assertThat(auditOutcome(ev, seller)).isEqualTo("account_turned_off");
        // The existing Team settings path ran (its own audit row).
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = 'user_deactivate' AND target_id = ?",
            Integer.class, seller.toString())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM refresh_tokens WHERE user_id = ? AND revoked_at IS NULL",
            Integer.class, seller)).isZero();
        assertThat(pinSwitch(ownerA, seller, "2468").getStatusCode()).as("PIN switch refused").isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @SuppressWarnings("rawtypes")
    void removedExistingWorker_staysActive_pinSwitchStillWorks_butCantSellThere() {
        List<String> ev = liveEvent(1, List.of("owner", "worker"));
        UUID hana = UUID.randomUUID();
        user(hana, tenantA, "worker");
        jdbc.update("UPDATE users SET pin_code = ? WHERE id = ?", passwordEncoder.encode("1357"), hana);
        addSeller(ownerA, ev.get(0), Map.of("user_id", hana.toString()));
        assertThat(listRow(ev.get(0), hana).get("remove_turns_off_account")).isEqualTo(false);

        assertThat(removeOutcome(ev.get(0), hana)).isEqualTo("removed_from_event");
        assertThat(jdbc.queryForObject("SELECT active FROM users WHERE id = ?", Boolean.class, hana)).isTrue();
        assertThat(auditOutcome(ev.get(0), hana)).isEqualTo("removed_from_event");
        ResponseEntity<AccessTokenResponse> sw = pinSwitch(ownerA, hana, "1357");
        assertThat(sw.getStatusCode()).as("still on the team").isEqualTo(HttpStatus.OK);
        ResponseEntity<Map> refused = sell(sw.getBody().accessToken(), ev.get(0), ev.get(1));
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(refused.getBody().get("code")).isEqualTo("BAZAR_NOT_A_SELLER");
    }

    @Test
    void bazarCreatedSeller_onAnotherLiveEvent_staysActive_untilTheirLastOne() {
        String ev1 = liveEvent(1, List.of("owner", "worker")).get(0);
        String ev2 = liveEvent(1, List.of("owner", "worker")).get(0);
        UUID seller = sellerId(addSeller(ownerA, ev1, Map.of("name", "Two Bazars")));
        addSeller(ownerA, ev2, Map.of("user_id", seller.toString()));
        assertThat(listRow(ev1, seller).get("remove_turns_off_account")).isEqualTo(false);
        assertThat(removeOutcome(ev1, seller)).isEqualTo("removed_from_event");
        assertThat(jdbc.queryForObject("SELECT active FROM users WHERE id = ?", Boolean.class, seller)).isTrue();
        assertThat(listRow(ev2, seller).get("remove_turns_off_account")).isEqualTo(true);
        assertThat(removeOutcome(ev2, seller)).isEqualTo("account_turned_off");
        assertThat(jdbc.queryForObject("SELECT active FROM users WHERE id = ?", Boolean.class, seller)).isFalse();
    }

    // ── RLS (app_user) ──────────────────────────────────────────────────────

    @Test
    void rls_appUser_seesOnlyItsOwnSellersAndInvites() {
        String ev = liveEvent(1, List.of("owner", "worker")).get(0);
        addSeller(ownerA, ev, Map.of("name", "Isolated"));
        TenantAwareDataSource ds = new TenantAwareDataSource(
            new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "app_user", "testpw"));
        JdbcTemplate app = new JdbcTemplate(ds);
        TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        tx.setReadOnly(true);
        for (String table : List.of("bazar_event_sellers", "bazar_seller_invites")) {
            String sql = "SELECT COUNT(*) FROM " + table + " WHERE event_id = ?::uuid";
            try {
                TenantContext.set(tenantB);
                assertThat((Integer) tx.execute(s -> app.queryForObject(sql, Integer.class, ev))).as(table).isZero();
                TenantContext.clear();
                assertThat((Integer) tx.execute(s -> app.queryForObject(sql, Integer.class, ev))).as(table + " no tenant").isZero();
                TenantContext.set(tenantA);   // same-tenant positive control
                assertThat((Integer) tx.execute(s -> app.queryForObject(sql, Integer.class, ev))).as(table).isEqualTo(1);
            } finally {
                TenantContext.clear();
            }
        }
    }

    // ── no new hatch ────────────────────────────────────────────────────────

    @Test
    void noNewSecurityDefinerHatch() {
        List<String> definers = jdbc.queryForList(
            "SELECT p.proname FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace " +
            "WHERE n.nspname = 'public' AND p.prosecdef ORDER BY p.proname", String.class);
        assertThat(definers).containsExactlyInAnyOrder(
            "auth_lookup_user", "resolve_tenant_by_shop_domain", "lookup_refresh_token",
            "resolve_tenant_by_webhook_secret", "consume_magic_link", "verify_and_consume_reset_code",
            "check_password_reset_throttle", "consume_shopify_oauth_state", "purge_expired_shopify_oauth_state",
            "check_demo_rate_limit", "upgrade_custom_app_to_oauth", "resolve_tenant_by_portal_slug",
            "resolve_scan_pairing", "purge_onboarding_artifacts");
        // app_user can set a PIN under RLS (the first-open screen needs no hatch).
        assertThat(jdbc.queryForObject("SELECT has_column_privilege('app_user', 'users', 'pin_code', 'UPDATE')",
            Boolean.class)).isTrue();
    }
}
