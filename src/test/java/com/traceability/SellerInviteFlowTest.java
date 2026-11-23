package com.traceability;

import com.traceability.bazar.SellerInviteService;
import com.traceability.identity.AuthRepository;
import com.traceability.identity.model.AccessTokenResponse;
import com.traceability.inventory.UlidGenerator;
import com.traceability.tenancy.TenantAwareDataSource;
import com.traceability.tenancy.TenantContext;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Bazar B10 — a seller invite is opened in two steps (GET → landing page → POST redeem), so a link preview can't use
 * it up; "send again" cancels the earlier link; /auth/magic refuses a seller token; Origin + per-IP limit on the POSTs;
 * a signed-in browser is never switched silently (the redeem ends its previous session); 403 bodies carry no
 * authorities.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(OutputCaptureExtension.class)
class SellerInviteFlowTest {

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
    @Autowired TestRestTemplate    rest;
    @Autowired JdbcTemplate        jdbc;
    @Autowired PasswordEncoder     passwordEncoder;
    @Autowired SellerInviteService invites;
    @Value("${shopify.app-url:http://localhost:5173}") String appUrl;

    final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

    UUID tenantA, tenantB, mainA, variantA, ownerAId;
    String ownerA, ownerAEmail;
    int seq = 0, pieceSeq = 0;

    @BeforeAll
    void setup() {
        tenantA = UUID.randomUUID();
        tenantB = UUID.randomUUID();
        mainA = UUID.randomUUID();
        variantA = UUID.randomUUID();
        UUID storeId = UUID.randomUUID(), productId = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name, bazar_enabled) VALUES (?, 'Invite Store', true)", tenantA);
        jdbc.update("INSERT INTO tenants (id, name, bazar_enabled) VALUES (?, 'Other Store', true)", tenantB);
        jdbc.update("INSERT INTO locations (id, tenant_id, name, type, is_default, is_fulfillment) " +
            "VALUES (?, ?, 'Main Warehouse', 'warehouse', true, true)", mainA, tenantA);
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status) " +
            "VALUES (?, ?, 'shopify', 'invite-a.myshopify.com', 'disconnected')", storeId, tenantA);
        jdbc.update("INSERT INTO products (id, tenant_id, store_id, external_id, title, status) " +
            "VALUES (?, ?, ?, 'PROD-IV', 'Linen Shirt', 'active')", productId, tenantA, storeId);
        jdbc.update("INSERT INTO variants (id, tenant_id, product_id, external_id, title, price) " +
            "VALUES (?, ?, ?, 'VAR-IV', 'Blue', 300.00)", variantA, tenantA, productId);
        ownerAId = UUID.randomUUID();
        ownerAEmail = user(ownerAId, tenantA, "owner");
        ownerA = login(ownerAEmail);
    }

    @BeforeEach
    void clearRateLimit() {
        jdbc.update("DELETE FROM embedded_onboarding_attempts WHERE bucket LIKE 'seller-invite-ip:%'");
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private String user(UUID id, UUID tenant, String role) {
        String email = role + "-" + UUID.randomUUID() + "@seller-invite.test";
        jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role, active) " +
            "VALUES (?, ?, ?, ?, ?, ?::user_role, true)",
            id, tenant, "Invite " + role, email, passwordEncoder.encode("pass123"), role);
        return email;
    }

    private String login(String email) {
        return loginResponse(email).getBody().accessToken();
    }

    private ResponseEntity<AccessTokenResponse> loginResponse(String email) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<AccessTokenResponse> resp = rest.postForEntity(base() + "/api/v1/auth/login",
            new HttpEntity<>(Map.of("email", email, "password", "pass123"), h), AccessTokenResponse.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        return resp;
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
            id, tenantA, variantA, "PC-" + id, String.format("P%07d", 7400000 + ++pieceSeq), mainA);
        return jdbc.queryForObject("SELECT short_code FROM pieces WHERE id = ?", String.class, id);
    }

    /** A live event of tenant A with one piece out: [eventId, code]. */
    @SuppressWarnings("rawtypes")
    private List<String> liveEvent() {
        Map<String, Object> body = new HashMap<>(Map.of("name", "Invite Bazar " + (++seq), "startsOn", "2026-12-01",
            "endsOn", "2026-12-02", "locationName", "Invite Hall " + seq, "sellerRoles", List.of("owner", "worker"),
            "paymentMethods", List.of("cash", "card")));
        ResponseEntity<Map> created = call(ownerA, HttpMethod.POST, "/api/v1/bazar-events", body);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String eventId = (String) created.getBody().get("id");
        String code = piece();
        assertThat(call(ownerA, HttpMethod.POST, "/api/v1/bazar-events/" + eventId + "/scan-out",
            Map.of("barcode", code)).getBody().get("success")).isEqualTo(true);
        assertThat(call(ownerA, HttpMethod.POST, "/api/v1/bazar-events/" + eventId + "/mark-sent", Map.of())
            .getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(call(ownerA, HttpMethod.POST, "/api/v1/bazar-events/" + eventId + "/start", Map.of())
            .getStatusCode()).isEqualTo(HttpStatus.OK);
        return List.of(eventId, code);
    }

    /** "Add seller": [link, sellerUserId]. */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private String[] addSeller(String eventId, String name) {
        ResponseEntity<Map> r = call(ownerA, HttpMethod.POST, "/api/v1/bazar-events/" + eventId + "/sellers",
            Map.of("name", name));
        assertThat(r.getStatusCode()).as(String.valueOf(r.getBody())).isEqualTo(HttpStatus.CREATED);
        return new String[] { (String) r.getBody().get("link"),
            String.valueOf(((Map<String, Object>) r.getBody().get("seller")).get("user_id")) };
    }

    @SuppressWarnings("rawtypes")
    private String sendAgain(String eventId, String sellerId) {
        ResponseEntity<Map> r = call(ownerA, HttpMethod.POST,
            "/api/v1/bazar-events/" + eventId + "/sellers/" + sellerId + "/link", null);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        return (String) r.getBody().get("link");
    }

    private static String tokenOf(String link) { return link.substring(link.indexOf("#token=") + 7); }

    private String tOf(String link) {
        String q = link.substring(link.indexOf("?t=") + 3);
        return q.substring(0, q.indexOf('#'));
    }

    /** The GET a link preview / the browser makes (the fragment is never sent). */
    private HttpResponse<String> get(String link) throws Exception {
        String path = link.substring(appUrl.length());
        int hash = path.indexOf('#');
        return http.send(HttpRequest.newBuilder(URI.create(base() + (hash < 0 ? path : path.substring(0, hash))))
            .header("User-Agent", "WhatsApp/2.23.20.0").GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, String t, String token, String origin, String bearer)
            throws Exception {
        StringBuilder json = new StringBuilder("{");
        if (t != null) json.append("\"t\":\"").append(t).append("\",");
        json.append("\"token\":\"").append(token).append("\"}");
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base() + path))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json.toString()));
        if (origin != null) b.header("Origin", origin);
        if (bearer != null) b.header("Authorization", "Bearer " + bearer);
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> check(String t, String token) throws Exception {
        return post("/api/v1/auth/seller-invite/check", t, token, appUrl, null);
    }

    private HttpResponse<String> redeem(String t, String token) throws Exception {
        return post("/api/v1/auth/seller-invite/redeem", t, token, appUrl, null);
    }

    private static String refreshCookie(HttpResponse<String> r) {
        return r.headers().allValues("set-cookie").stream().filter(c -> c.startsWith("traced_refresh="))
            .map(c -> c.substring("traced_refresh=".length(), c.indexOf(';'))).filter(v -> !v.isEmpty())
            .findFirst().orElse(null);
    }

    private java.sql.Timestamp consumedAt(String token) {
        return jdbc.queryForObject("SELECT consumed_at FROM magic_link_tokens WHERE token_hash = ?",
            java.sql.Timestamp.class, AuthRepository.sha256(token));
    }

    private int liveSessions(UUID userId) {
        return jdbc.queryForObject("SELECT count(*) FROM refresh_tokens WHERE user_id = ? AND revoked_at IS NULL",
            Integer.class, userId);
    }

    // ── the link: preview-safe GET, check, redeem ────────────────────────────

    @Test
    void getTwice_thenCheck_thenRedeem_works_andTheGetsWroteNothing() throws Exception {
        List<String> ev = liveEvent();
        String[] s = addSeller(ev.get(0), "Mona");
        String link = s[0];
        assertThat(link).startsWith(appUrl + "/auth/seller-invite?t=" + tenantA + "#token=");
        int attemptsBefore = jdbc.queryForObject("SELECT count(*) FROM embedded_onboarding_attempts", Integer.class);

        // WhatsApp's preview, then the seller's own tap: two GETs.
        for (int i = 0; i < 2; i++) {
            HttpResponse<String> g = get(link);
            assertThat(g.statusCode()).isEqualTo(302);
            assertThat(g.headers().firstValue("location").orElseThrow())
                .isEqualTo(appUrl + "/bazar/invite?t=" + tenantA);
            assertThat(g.headers().allValues("set-cookie")).isEmpty();
        }
        assertThat(jdbc.queryForObject("SELECT consumed_at IS NULL FROM magic_link_tokens WHERE token_hash = ?",
            Boolean.class, AuthRepository.sha256(tokenOf(link)))).as("a GET never consumes").isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM embedded_onboarding_attempts", Integer.class))
            .as("a GET writes nothing").isEqualTo(attemptsBefore);

        HttpResponse<String> c = check(tOf(link), tokenOf(link));
        assertThat(c.statusCode()).isEqualTo(200);
        assertThat(c.body()).contains("\"status\":\"valid\"", "\"store_name\":\"Invite Store\"",
            "\"event_name\":\"Invite Bazar " + seq + "\"", "\"seller_name\":\"Mona\"");
        assertThat(jdbc.queryForObject("SELECT consumed_at IS NULL FROM magic_link_tokens WHERE token_hash = ?",
            Boolean.class, AuthRepository.sha256(tokenOf(link)))).as("check never consumes").isTrue();

        HttpResponse<String> r = redeem(tOf(link), tokenOf(link));
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.body()).contains("\"event_id\":\"" + ev.get(0) + "\"", "\"access_token\":\"");
        assertThat(refreshCookie(r)).isNotNull();
        assertThat(jdbc.queryForObject("SELECT redeemed_at IS NOT NULL FROM bazar_seller_invites WHERE token_hash = ?",
            Boolean.class, AuthRepository.sha256(tokenOf(link)))).isTrue();
        assertThat(liveSessions(UUID.fromString(s[1]))).isEqualTo(1);
    }

    @Test
    void redeemTwice_secondIsRefusedAsUsed() throws Exception {
        String[] s = addSeller(liveEvent().get(0), "Twice");
        assertThat(redeem(tOf(s[0]), tokenOf(s[0])).statusCode()).isEqualTo(200);
        HttpResponse<String> again = redeem(tOf(s[0]), tokenOf(s[0]));
        assertThat(again.statusCode()).isEqualTo(409);
        assertThat(again.body()).contains("\"code\":\"BAZAR_INVITE_REFUSED\"", "\"reason\":\"used\"",
            "Ask the owner to send you a new one");
        assertThat(refreshCookie(again)).isNull();
        assertThat(check(tOf(s[0]), tokenOf(s[0])).body()).contains("\"status\":\"used\"");
        assertThat(liveSessions(UUID.fromString(s[1]))).isEqualTo(1);
    }

    @Test
    void sendAgain_cancelsTheEarlierLink_onCheckAndRedeem_withoutUsingItUp() throws Exception {
        String ev = liveEvent().get(0);
        String[] s = addSeller(ev, "Again");
        String newer = sendAgain(ev, s[1]);

        assertThat(check(tOf(s[0]), tokenOf(s[0])).body()).contains("\"status\":\"replaced\"");
        HttpResponse<String> old = redeem(tOf(s[0]), tokenOf(s[0]));
        assertThat(old.statusCode()).isEqualTo(409);
        assertThat(old.body()).contains("\"reason\":\"replaced\"");
        assertThat(jdbc.queryForObject("SELECT consumed_at IS NULL FROM magic_link_tokens WHERE token_hash = ?",
            Boolean.class, AuthRepository.sha256(tokenOf(s[0])))).as("refused before the consume").isTrue();
        // … and the cancelled link can't sign in through /auth/magic either.
        HttpResponse<String> magic = http.send(HttpRequest.newBuilder(
            URI.create(base() + "/auth/magic?token=" + tokenOf(s[0]))).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(refreshCookie(magic)).isNull();

        assertThat(check(tOf(newer), tokenOf(newer)).body()).contains("\"status\":\"valid\"");
        assertThat(redeem(tOf(newer), tokenOf(newer)).statusCode()).isEqualTo(200);
    }

    @Test
    void refusalReasons_expired_removed_unknown_wrongTenant() throws Exception {
        String ev = liveEvent().get(0);
        // expired
        String[] late = addSeller(ev, "Late");
        jdbc.update("UPDATE bazar_seller_invites SET created_at = now() - interval '2 days', " +
            "expires_at = now() - interval '1 second' WHERE token_hash = ?",
            AuthRepository.sha256(tokenOf(late[0])));
        jdbc.update("UPDATE magic_link_tokens SET expires_at = now() - interval '1 second' WHERE token_hash = ?",
            AuthRepository.sha256(tokenOf(late[0])));
        assertThat(check(tOf(late[0]), tokenOf(late[0])).body()).contains("\"status\":\"expired\"");
        assertThat(redeem(tOf(late[0]), tokenOf(late[0])).body()).contains("\"reason\":\"expired\"", "has expired");

        // removed
        String[] gone = addSeller(ev, "Gone");
        assertThat(call(ownerA, HttpMethod.DELETE, "/api/v1/bazar-events/" + ev + "/sellers/" + gone[1], null)
            .getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(check(tOf(gone[0]), tokenOf(gone[0])).body()).contains("\"status\":\"removed\"");
        assertThat(redeem(tOf(gone[0]), tokenOf(gone[0])).body()).contains("\"reason\":\"removed\"");

        // unknown token; the right token under another tenant; a garbled tenant
        String[] ok = addSeller(ev, "Right");
        assertThat(check(tOf(ok[0]), "not-a-real-token").body()).contains("\"status\":\"unknown\"");
        HttpResponse<String> other = check(tenantB.toString(), tokenOf(ok[0]));
        assertThat(other.body()).contains("\"status\":\"unknown\"").doesNotContain("Invite Store", "Right");
        assertThat(redeem(tenantB.toString(), tokenOf(ok[0])).body()).contains("\"reason\":\"unknown\"");
        assertThat(redeem("garbled", tokenOf(ok[0])).body()).contains("\"reason\":\"unknown\"");
        assertThat(jdbc.queryForObject("SELECT consumed_at IS NULL FROM magic_link_tokens WHERE token_hash = ?",
            Boolean.class, AuthRepository.sha256(tokenOf(ok[0])))).as("a wrong tenant consumes nothing").isTrue();
        assertThat(redeem(tOf(ok[0]), tokenOf(ok[0])).statusCode()).isEqualTo(200);
    }

    @Test
    void legacyLink_withoutTenant_isMovedToTheFragment_andStillRedeems() throws Exception {
        String ev = liveEvent().get(0);
        String[] s = addSeller(ev, "Legacy");
        String token = tokenOf(s[0]);
        HttpResponse<String> g = get(appUrl + "/auth/seller-invite?token=" + token);
        assertThat(g.statusCode()).isEqualTo(302);
        assertThat(g.headers().firstValue("location").orElseThrow()).isEqualTo(appUrl + "/bazar/invite#token=" + token);
        assertThat(consumedAt(token)).isNull();
        assertThat(check(null, token).body()).contains("\"status\":\"legacy\"").doesNotContain("store_name");
        HttpResponse<String> r = redeem(null, token);
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.body()).contains("\"event_id\":\"" + ev + "\"");

        // A legacy link that was replaced: consumed, refused, and its session ended at once.
        String[] t = addSeller(ev, "Legacy replaced");
        sendAgain(ev, t[1]);
        HttpResponse<String> refused = redeem(null, tokenOf(t[0]));
        assertThat(refused.body()).contains("\"reason\":\"replaced\"");
        assertThat(refreshCookie(refused)).isNull();
        assertThat(liveSessions(UUID.fromString(t[1]))).isZero();
    }

    @Test
    void authMagic_refusesASellerInviteToken() throws Exception {
        String[] s = addSeller(liveEvent().get(0), "Magic");
        HttpResponse<String> magic = http.send(HttpRequest.newBuilder(
            URI.create(base() + "/auth/magic?token=" + tokenOf(s[0]))).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(refreshCookie(magic)).isNull();
        assertThat(liveSessions(UUID.fromString(s[1]))).isZero();
    }

    // ── Origin, rate limit ───────────────────────────────────────────────────

    @Test
    void origin_mustBeTheApps() throws Exception {
        String[] s = addSeller(liveEvent().get(0), "Origin");
        // No Origin, and an origin CORS lets through (a Shopify store page) — both refused by the app-url check.
        for (String origin : new String[] { null, "https://evil.myshopify.com" }) {
            for (String path : List.of("/api/v1/auth/seller-invite/check", "/api/v1/auth/seller-invite/redeem")) {
                HttpResponse<String> r = post(path, tOf(s[0]), tokenOf(s[0]), origin, null);
                assertThat(r.statusCode()).as(path + " from " + origin).isEqualTo(403);
                assertThat(r.body()).contains("BAZAR_INVITE_ORIGIN");
            }
        }
        assertThat(consumedAt(tokenOf(s[0]))).isNull();
        // A browser that sends no Origin but a same-app Referer (the Safari fallback) passes.
        HttpResponse<String> viaReferer = http.send(HttpRequest.newBuilder(
                URI.create(base() + "/api/v1/auth/seller-invite/redeem"))
            .header("Content-Type", "application/json").header("Referer", appUrl + "/bazar/invite?t=" + tenantA)
            .POST(HttpRequest.BodyPublishers.ofString(
                "{\"t\":\"" + tOf(s[0]) + "\",\"token\":\"" + tokenOf(s[0]) + "\"}")).build(),
            HttpResponse.BodyHandlers.ofString());
        assertThat(viaReferer.statusCode()).isEqualTo(200);
    }

    @Test
    void missingToken_isRefusedAsMissingToken_andLogged(CapturedOutput out) throws Exception {
        HttpResponse<String> c = check(tenantA.toString(), "");
        assertThat(c.statusCode()).isEqualTo(200);
        assertThat(c.body()).contains("\"status\":\"missing_token\"");
        HttpResponse<String> r = redeem(tenantA.toString(), "");
        assertThat(r.statusCode()).isEqualTo(409);
        assertThat(r.body()).contains("\"reason\":\"missing_token\"").contains("This link is incomplete.");
        assertThat(out.getAll())
            .contains("Seller invite refused reason=missing_token phase=check userId=null tenantId=" + tenantA)
            .contains("Seller invite refused reason=missing_token phase=redeem userId=null tenantId=" + tenantA);
    }

    @Test
    void perIpLimit_refusesWith429() throws Exception {
        for (String ip : List.of("127.0.0.1", "0:0:0:0:0:0:0:1")) {
            String bucket = "seller-invite-ip:" + ReflectionTestUtils.invokeMethod(invites, "hmac", ip);
            for (int i = 0; i < 60; i++) {
                jdbc.update("INSERT INTO embedded_onboarding_attempts (bucket) VALUES (?)", bucket);
            }
        }
        HttpResponse<String> r = check(tenantA.toString(), "anything");
        assertThat(r.statusCode()).isEqualTo(429);
        assertThat(r.body()).contains("BAZAR_INVITE_RATE_LIMITED");
    }

    // ── a signed-in browser ──────────────────────────────────────────────────

    @Test
    void signedInOwner_redeem_endsOnlyThisBrowsersOwnerSession() throws Exception {
        String[] s = addSeller(liveEvent().get(0), "Shared phone");
        ResponseEntity<AccessTokenResponse> thisBrowser = loginResponse(ownerAEmail);
        String otherDevice = loginResponse(ownerAEmail).getBody().accessToken();
        int ownerSessions = liveSessions(ownerAId);

        HttpResponse<String> r = post("/api/v1/auth/seller-invite/redeem", tOf(s[0]), tokenOf(s[0]), appUrl,
            thisBrowser.getBody().accessToken());
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(liveSessions(ownerAId)).as("only this browser's owner session ended").isEqualTo(ownerSessions - 1);
        assertThat(call(otherDevice, HttpMethod.GET, "/api/v1/me", null).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(liveSessions(UUID.fromString(s[1]))).isEqualTo(1);
    }

    // ── logs ─────────────────────────────────────────────────────────────────

    @Test
    void everyRedeemAndRefusal_isLogged_neverTheToken(CapturedOutput out) throws Exception {
        String ev = liveEvent().get(0);
        String[] s = addSeller(ev, "Logged");
        redeem(tOf(s[0]), tokenOf(s[0]));
        redeem(tOf(s[0]), tokenOf(s[0]));
        assertThat(out.getOut()).contains("Seller invite redeemed userId=" + s[1] + " tenantId=" + tenantA
            + " eventId=" + ev);
        assertThat(out.getOut()).contains("Seller invite refused reason=used phase=redeem userId=" + s[1]
            + " tenantId=" + tenantA);
        assertThat(out.getOut()).doesNotContain(tokenOf(s[0])).doesNotContain(AuthRepository.sha256(tokenOf(s[0])));
    }

    // ── the whole seller path: link → confirm → PIN → sell ───────────────────

    @Test
    @SuppressWarnings("rawtypes")
    void link_confirm_pin_sell() throws Exception {
        List<String> ev = liveEvent();
        String[] s = addSeller(ev.get(0), "Full path");
        assertThat(get(s[0]).statusCode()).isEqualTo(302);
        assertThat(check(tOf(s[0]), tokenOf(s[0])).body()).contains("\"status\":\"valid\"");
        HttpResponse<String> r = redeem(tOf(s[0]), tokenOf(s[0]));
        String token = r.body().replaceAll(".*\"access_token\":\"([^\"]+)\".*", "$1");
        Map me = call(token, HttpMethod.GET, "/api/v1/bazar-events/" + ev.get(0) + "/sellers/me", null).getBody();
        assertThat(me.get("needs_pin")).isEqualTo(true);
        assertThat(call(token, HttpMethod.POST, "/api/v1/bazar-sellers/me/pin", Map.of("pin", "4821"))
            .getStatusCode()).isEqualTo(HttpStatus.OK);
        me = call(token, HttpMethod.GET, "/api/v1/bazar-events/" + ev.get(0) + "/sellers/me", null).getBody();
        assertThat(me.get("needs_pin")).isEqualTo(false);
        assertThat(me.get("can_sell")).isEqualTo(true);
        assertThat(call(token, HttpMethod.POST, "/api/v1/bazar-events/" + ev.get(0) + "/sell",
            Map.of("code", ev.get(1), "client_request_id", "s-" + UUID.randomUUID(), "method", "cash"))
            .getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // ── 403 body, app_user ───────────────────────────────────────────────────

    @Test
    @SuppressWarnings("rawtypes")
    void a403Body_carriesNoAuthorities() throws Exception {
        List<String> ev = liveEvent();
        String[] s = addSeller(ev.get(0), "Not a manager");
        HttpResponse<String> r = redeem(tOf(s[0]), tokenOf(s[0]));
        String worker = r.body().replaceAll(".*\"access_token\":\"([^\"]+)\".*", "$1");
        ResponseEntity<Map> denied = call(worker, HttpMethod.GET,
            "/api/v1/bazar-events/" + ev.get(0) + "/days/2026-12-01/close-preview", null);
        assertThat(denied.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(denied.getBody()).containsOnlyKeys("error", "path");
        assertThat(denied.getBody().toString()).doesNotContain("ROLE_");
    }

    @Test
    void appUser_seesNoOtherTenantsInvite_andMayUpdateOnlyRedeemedAt() {
        String[] s = addSeller(liveEvent().get(0), "Isolated invite");
        String hash = AuthRepository.sha256(tokenOf(s[0]));
        TenantAwareDataSource ds = new TenantAwareDataSource(
            new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "app_user", "testpw"));
        JdbcTemplate app = new JdbcTemplate(ds);
        TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        Integer seenByB = TenantContext.runAs(tenantB, () -> tx.execute(st -> app.queryForObject(
            "SELECT count(*) FROM bazar_seller_invites WHERE token_hash = ?", Integer.class, hash)));
        assertThat(seenByB).isZero();
        Integer stampedByB = TenantContext.runAs(tenantB, () -> tx.execute(st -> app.update(
            "UPDATE bazar_seller_invites SET redeemed_at = now() WHERE token_hash = ?", hash)));
        assertThat(stampedByB).isZero();
        assertThatThrownBy(() -> TenantContext.runAs(tenantA, () -> tx.execute(st -> app.update(
            "UPDATE bazar_seller_invites SET expires_at = now() WHERE token_hash = ?", hash))))
            .hasRootCauseMessage("ERROR: permission denied for table bazar_seller_invites");
        assertThat(jdbc.queryForObject("SELECT has_column_privilege('app_user', 'bazar_seller_invites', " +
            "'redeemed_at', 'UPDATE')", Boolean.class)).isTrue();
    }
}
