package com.traceability;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.traceability.identity.AuthRepository;
import com.traceability.identity.JwtService;
import com.traceability.tenancy.TenantAwareDataSource;
import com.traceability.tenancy.TenantContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Build A (2026-10-05) — a station tablet must not drop when its owner signs in or out elsewhere.
 *
 * Every device here is a raw HTTP client that behaves like a browser: it sends the refresh cookie
 * ONLY to /api/v1/auth/refresh (the cookie's path) and the access token as a Bearer header.
 *
 * d1  owner logs in on B, then logs out on B → the tablet session on A keeps refreshing
 * d2  "Log out of all devices" still revokes every session (reason logout_all)
 * d3  a password reset still revokes every session (reason password_reset)
 * d4  two concurrent refreshes of one token → both 200, the same new cookie, one successor row
 * d5  the same token presented again inside the grace → the same successor (sequential)
 * d6  a rotated token reused outside the grace → 401 + REFRESH_REJECTED reused_outside_grace
 * d7  a PIN switch (no cookie sent, as in a browser) revokes the tablet's previous token (pin_switch)
 * d8  created_via / user_agent recorded for signup, login, refresh, pin
 * d9  a logout on B with B's device id leaves the owner's phone pairing on tablet A alone
 * d10 isolation: app_user sees no refresh tokens without a tenant, none of another tenant's; a
 *     session id of another tenant's token in a JWT revokes nothing
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(OutputCaptureExtension.class)
class DeviceSessionTest {

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
    @Autowired JdbcTemplate    jdbc;
    @Autowired JwtService      jwt;
    @Autowired AuthRepository  authRepository;
    @Autowired PasswordEncoder encoder;

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();

    /** One browser: its refresh cookie value and its current access token. */
    static final class Device {
        final String userAgent;
        String cookie;
        String access;
        Device(String userAgent) { this.userAgent = userAgent; }
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private HttpResponse<String> send(String path, String bearer, String cookie, String userAgent, Object body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .POST(body == null ? HttpRequest.BodyPublishers.noBody()
                                   : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        if (bearer != null)    b.header("Authorization", "Bearer " + bearer);
        if (cookie != null)    b.header("Cookie", "traced_refresh=" + cookie);
        if (userAgent != null) b.header("User-Agent", userAgent);
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String cookieOf(HttpResponse<String> r) {
        return r.headers().allValues("set-cookie").stream()
                .filter(c -> c.startsWith("traced_refresh="))
                .map(c -> c.substring("traced_refresh=".length(), c.indexOf(';')))
                .findFirst().orElse(null);
    }

    private String accessOf(HttpResponse<String> r) throws Exception {
        JsonNode n = json.readTree(r.body());
        return n.get("accessToken").asText();
    }

    private void absorb(Device d, HttpResponse<String> r) throws Exception {
        d.cookie = cookieOf(r);
        d.access = accessOf(r);
    }

    /** Signs a new tenant up on {@code d}; returns {tenantId, ownerId}. */
    private UUID[] signup(Device d, String email) throws Exception {
        HttpResponse<String> r = send("/api/v1/auth/signup", null, null, d.userAgent,
                Map.of("tenantName", "DS " + email, "name", "Owner", "email", email,
                       "phone", "01012345678", "password", "password123", "consent", true));
        assertThat(r.statusCode()).as(r.body()).isEqualTo(201);
        absorb(d, r);
        var claims = jwt.verify(d.access);
        return new UUID[] { UUID.fromString((String) claims.getClaim("tenant")), UUID.fromString(claims.getSubject()) };
    }

    private void login(Device d, String email) throws Exception {
        HttpResponse<String> r = send("/api/v1/auth/login", null, null, d.userAgent,
                Map.of("email", email, "password", "password123"));
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        absorb(d, r);
    }

    /** The browser's /auth/refresh: cookie only, no bearer. */
    private HttpResponse<String> refresh(Device d) throws Exception {
        return send("/api/v1/auth/refresh", null, d.cookie, d.userAgent, null);
    }

    private Map<String, Object> row(String rawCookie) {
        return jdbc.queryForMap("SELECT * FROM refresh_tokens WHERE token_hash = ?", AuthRepository.sha256(rawCookie));
    }

    // ── d1 ───────────────────────────────────────────────────────────────────

    @Test
    void d1_ownerLogsInAndOutOnB_tabletOnAKeepsWorking() throws Exception {
        Device tablet = new Device("Tablet-A");
        Device home = new Device("Laptop-B");
        signup(tablet, "d1-owner@test.com");

        login(home, "d1-owner@test.com");
        HttpResponse<String> afterLogin = refresh(tablet);
        assertThat(afterLogin.statusCode()).as("a login on B leaves A alone").isEqualTo(200);
        absorb(tablet, afterLogin);

        String homeCookie = home.cookie;
        // A browser's logout: Bearer only — the cookie (path /auth/refresh) never reaches /auth/logout.
        assertThat(send("/api/v1/auth/logout", home.access, null, home.userAgent, null).statusCode()).isEqualTo(204);

        assertThat(row(homeCookie).get("revoked_reason")).as("B's own session ended").isEqualTo("logout_device");
        assertThat(refresh(home).statusCode()).as("B can't refresh any more").isEqualTo(401);

        HttpResponse<String> tabletAfter = refresh(tablet);
        assertThat(tabletAfter.statusCode()).as("the tablet on A keeps refreshing").isEqualTo(200);
        absorb(tablet, tabletAfter);
        assertThat(refresh(tablet).statusCode()).isEqualTo(200);
    }

    // ── d2 ───────────────────────────────────────────────────────────────────

    @Test
    void d2_logOutOfAllDevices_revokesEverySession() throws Exception {
        Device tablet = new Device("Tablet-A");
        Device home = new Device("Laptop-B");
        UUID[] ids = signup(tablet, "d2-owner@test.com");
        login(home, "d2-owner@test.com");

        assertThat(send("/api/v1/auth/logout-all", home.access, null, home.userAgent, null).statusCode()).isEqualTo(204);

        assertThat(refresh(tablet).statusCode()).isEqualTo(401);
        assertThat(refresh(home).statusCode()).isEqualTo(401);
        assertThat(jdbc.queryForList(
                "SELECT DISTINCT revoked_reason FROM refresh_tokens WHERE user_id = ?", String.class, ids[1]))
                .containsExactly("logout_all");
    }

    // ── d3 ───────────────────────────────────────────────────────────────────

    @Test
    void d3_passwordReset_revokesEverySession() throws Exception {
        Device tablet = new Device("Tablet-A");
        Device home = new Device("Laptop-B");
        UUID[] ids = signup(tablet, "d3-owner@test.com");
        login(home, "d3-owner@test.com");

        TenantContext.runAs(ids[0], () -> authRepository.completePasswordReset(ids[1], encoder.encode("newpassword1")));

        assertThat(refresh(tablet).statusCode()).isEqualTo(401);
        assertThat(refresh(home).statusCode()).isEqualTo(401);
        assertThat(jdbc.queryForList(
                "SELECT DISTINCT revoked_reason FROM refresh_tokens WHERE user_id = ?", String.class, ids[1]))
                .containsExactly("password_reset");
    }

    // ── d4 / d5 / d6 — rotation grace ────────────────────────────────────────

    @Test
    void d4_twoConcurrentRefreshes_bothSucceed_oneNewPair() throws Exception {
        Device tablet = new Device("Tablet-A");
        UUID[] ids = signup(tablet, "d4-owner@test.com");
        String original = tablet.cookie;

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch go = new CountDownLatch(1);
            List<CompletableFuture<HttpResponse<String>>> calls = List.of(
                    CompletableFuture.supplyAsync(() -> { await(go); return call(() -> refresh(tablet)); }, pool),
                    CompletableFuture.supplyAsync(() -> { await(go); return call(() -> refresh(tablet)); }, pool));
            go.countDown();
            HttpResponse<String> a = calls.get(0).get();
            HttpResponse<String> b = calls.get(1).get();

            assertThat(a.statusCode()).as(a.body()).isEqualTo(200);
            assertThat(b.statusCode()).as(b.body()).isEqualTo(200);
            assertThat(cookieOf(a)).as("both get the same new refresh token").isEqualTo(cookieOf(b));
            assertThat(jwt.verify(accessOf(a)).getClaim("sid")).isEqualTo(jwt.verify(accessOf(b)).getClaim("sid"));
        } finally {
            pool.shutdownNow();
        }
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM refresh_tokens WHERE user_id = ? AND created_via = 'refresh'", Integer.class, ids[1]))
                .as("exactly one successor").isEqualTo(1);
        assertThat(row(original).get("revoked_reason")).isEqualTo("rotated");
    }

    @Test
    void d5_sameTokenAgainInsideGrace_getsTheSameSuccessor() throws Exception {
        Device tablet = new Device("Tablet-A");
        signup(tablet, "d5-owner@test.com");
        String original = tablet.cookie;

        HttpResponse<String> first = refresh(tablet);
        HttpResponse<String> again = refresh(tablet);      // the rotation's response "was lost"
        assertThat(first.statusCode()).isEqualTo(200);
        assertThat(again.statusCode()).as(again.body()).isEqualTo(200);
        assertThat(cookieOf(again)).isEqualTo(cookieOf(first));
        assertThat(row(original).get("replaced_by")).isEqualTo(row(cookieOf(first)).get("id"));

        tablet.cookie = cookieOf(first);
        assertThat(refresh(tablet).statusCode()).as("the successor rotates normally").isEqualTo(200);
    }

    @Test
    void d6_rotatedTokenReusedOutsideGrace_401_andLogged(CapturedOutput output) throws Exception {
        Device tablet = new Device("Tablet-A");
        signup(tablet, "d6-owner@test.com");
        String original = tablet.cookie;
        HttpResponse<String> first = refresh(tablet);
        assertThat(first.statusCode()).isEqualTo(200);

        jdbc.update("UPDATE refresh_tokens SET revoked_at = now() - interval '31 seconds' WHERE token_hash = ?",
                AuthRepository.sha256(original));

        assertThat(refresh(tablet).statusCode()).isEqualTo(401);
        String prefix = row(original).get("id").toString().substring(0, 8);
        assertThat(output.getOut()).contains("REFRESH_REJECTED reason=reused_outside_grace token=" + prefix);

        tablet.cookie = cookieOf(first);
        assertThat(refresh(tablet).statusCode()).as("the live successor is unaffected").isEqualTo(200);
    }

    // ── d7 ───────────────────────────────────────────────────────────────────

    @Test
    void d7_pinSwitch_revokesTheTabletsPreviousToken_withoutTheCookie() throws Exception {
        Device tablet = new Device("Tablet-A");
        UUID[] ids = signup(tablet, "d7-owner@test.com");
        String ownerToken = tablet.cookie;
        UUID worker = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role, pin_code, active) " +
                    "VALUES (?, ?, 'W', 'd7-worker@test.com', 'x', 'worker', ?, true)", worker, ids[0], encoder.encode("4321"));

        HttpResponse<String> r = send("/api/v1/auth/pin", tablet.access, null, tablet.userAgent,
                Map.of("userId", worker.toString(), "pin", "4321"));
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);

        assertThat(row(ownerToken).get("revoked_reason")).as("no orphan left behind").isEqualTo("pin_switch");
        Map<String, Object> workerRow = row(cookieOf(r));
        assertThat(workerRow.get("created_via")).isEqualTo("pin");
        assertThat(workerRow.get("user_agent")).isEqualTo("Tablet-A");
        assertThat(workerRow.get("revoked_at")).isNull();
        assertThat(jwt.verify(accessOf(r)).getClaim("sid")).isEqualTo(workerRow.get("id").toString());
    }

    // ── d8 ───────────────────────────────────────────────────────────────────

    @Test
    void d8_createdViaAndUserAgent_recorded() throws Exception {
        Device a = new Device("UA-signup");
        signup(a, "d8-owner@test.com");
        assertThat(row(a.cookie)).containsEntry("created_via", "signup").containsEntry("user_agent", "UA-signup");

        Device b = new Device("UA-login");
        login(b, "d8-owner@test.com");
        assertThat(row(b.cookie)).containsEntry("created_via", "login").containsEntry("user_agent", "UA-login");

        HttpResponse<String> r = refresh(b);
        assertThat(row(cookieOf(r))).containsEntry("created_via", "refresh").containsEntry("user_agent", "UA-login");
        assertThat(row(b.cookie)).containsEntry("revoked_reason", "rotated");
    }

    // ── d9 ───────────────────────────────────────────────────────────────────

    @Test
    void d9_logoutOnB_leavesTheOwnersPairingOnTabletA() throws Exception {
        Device tablet = new Device("Tablet-A");
        Device home = new Device("Laptop-B");
        UUID[] ids = signup(tablet, "d9-owner@test.com");
        login(home, "d9-owner@test.com");
        UUID pairing = jdbc.queryForObject(
                "INSERT INTO scan_pairings (tenant_id, station_device_id, station_user_id, pair_code_hash, device_secret_hash, " +
                "                           pair_code_expires_at, claimed_at, expires_at) " +
                "VALUES (?, 'tabletAAAAAAAAAAAAAA', ?, ?, ?, now() + interval '2 minutes', now(), now() + interval '12 hours') RETURNING id",
                UUID.class, ids[0], ids[1], "d9-pc-" + UUID.randomUUID(), "d9-ds-" + UUID.randomUUID());

        assertThat(send("/api/v1/auth/logout?deviceId=laptopBBBBBBBBBBBBBB", home.access, null, home.userAgent, null)
                .statusCode()).isEqualTo(204);

        assertThat(jdbc.queryForObject("SELECT revoked_at FROM scan_pairings WHERE id = ?", java.sql.Timestamp.class, pairing))
                .as("the tablet's pairing survives a logout on B").isNull();
    }

    // ── d10 isolation ────────────────────────────────────────────────────────

    @Test
    void d10_isolation_appUserSeesNoOtherTenantsTokens_andAForeignSidRevokesNothing() throws Exception {
        Device a = new Device("A");
        Device b = new Device("B");
        UUID[] tenantA = signup(a, "d10-a@test.com");
        UUID[] tenantB = signup(b, "d10-b@test.com");
        UUID tokenA = (UUID) row(a.cookie).get("id");

        TenantAwareDataSource ds = new TenantAwareDataSource(
                new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "app_user", "testpw"));
        JdbcTemplate appJdbc = new JdbcTemplate(ds);
        TransactionTemplate appTx = new TransactionTemplate(new DataSourceTransactionManager(ds));

        Integer noTenant = appTx.execute(s -> appJdbc.queryForObject("SELECT COUNT(*) FROM refresh_tokens", Integer.class));
        assertThat(noTenant).as("no tenant → no rows").isZero();

        Integer foreign = TenantContext.runAs(tenantB[0], () -> appTx.execute(s -> appJdbc.queryForObject(
                "SELECT COUNT(*) FROM refresh_tokens WHERE tenant_id = ?", Integer.class, tenantA[0])));
        assertThat(foreign).as("tenant B sees none of A's tokens").isZero();

        Integer updated = TenantContext.runAs(tenantB[0], () -> appTx.execute(s -> appJdbc.update(
                "UPDATE refresh_tokens SET revoked_at = now(), revoked_reason = 'logout_device' WHERE id = ?", tokenA)));
        assertThat(updated).as("tenant B can't revoke A's token").isZero();

        // A server-signed JWT for B's owner carrying A's token as sid: the logout revokes nothing of A's.
        String forged = jwt.issueAccessToken(tenantB[1], tenantB[0], "owner", tokenA);
        assertThat(send("/api/v1/auth/logout", forged, null, "B", null).statusCode()).isEqualTo(204);
        assertThat(row(a.cookie).get("revoked_at")).isNull();
        assertThat(refresh(a).statusCode()).isEqualTo(200);
    }

    // ── small utils ──────────────────────────────────────────────────────────

    private interface IoCall<T> { T run() throws Exception; }

    private static <T> T call(IoCall<T> c) {
        try { return c.run(); } catch (Exception e) { throw new RuntimeException(e); }
    }

    private static void await(CountDownLatch l) {
        try { l.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
