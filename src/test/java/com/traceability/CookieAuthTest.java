package com.traceability;

import com.traceability.identity.model.AccessTokenResponse;
import com.traceability.identity.model.LoginRequest;
import com.traceability.identity.model.SignupRequest;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Full matrix for httpOnly-cookie persistent-session auth (Design Part 2).
 *
 * CA1  login → AccessTokenResponse body, Set-Cookie traced_refresh with correct attributes
 * CA2  signup → same
 * CA3  refresh with valid cookie → new access token + rotated Set-Cookie
 * CA4  refresh with no cookie → 401
 * CA5  refresh with revoked cookie → 401
 * CA6  logout → cookie expired (Max-Age=0) + subsequent refresh → 401
 * CA7  refreshToken NEVER in login response body
 * CA8  refreshToken NEVER in signup response body
 * CA9  refreshToken NEVER in refresh response body
 * CA10 cookie attributes: HttpOnly, Secure, SameSite=Lax, Path=/api/v1/auth/refresh
 * CA11 embedded path: traced_refresh cookie NOT attached to /api/v1/embedded/* (path isolation)
 * CA12 CORS: production CORS origins are restrictive (not wildcard)
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CookieAuthTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("traceability_test")
                    .withUsername("postgres")
                    .withPassword("postgres");

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

    private String base() { return "http://localhost:" + port; }

    // Shared state across ordered tests
    private static String userEmail;
    private static String firstCookieValue;   // raw cookie header value from login
    private static String accessToken;

    // -----------------------------------------------------------------------
    // CA1: Login returns AccessTokenResponse body and sets traced_refresh cookie
    // -----------------------------------------------------------------------
    @Test @Order(1)
    void ca1_login_returnsAccessTokenBodyAndSetsCookie() {
        userEmail = "ca-user-" + System.nanoTime() + "@cookie.test";
        // Signup first to create the tenant
        ResponseEntity<AccessTokenResponse> signupResp = doSignup(userEmail, "password123");
        assertThat(signupResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        // Login
        ResponseEntity<AccessTokenResponse> resp = doLogin(userEmail, "password123");
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);

        AccessTokenResponse body = resp.getBody();
        assertThat(body).isNotNull();
        assertThat(body.accessToken()).isNotBlank();
        accessToken = body.accessToken();

        String setCookie = resp.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
        assertThat(setCookie).isNotNull().contains("traced_refresh=");
        firstCookieValue = setCookie.split(";")[0]; // "traced_refresh=<raw>"
    }

    // -----------------------------------------------------------------------
    // CA2: Signup also returns AccessTokenResponse body and sets cookie
    // -----------------------------------------------------------------------
    @Test @Order(2)
    void ca2_signup_returnsAccessTokenBodyAndSetsCookie() {
        String email = "ca-signup-" + System.nanoTime() + "@cookie.test";
        ResponseEntity<AccessTokenResponse> resp = doSignup(email, "password123");
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(resp.getBody().accessToken()).isNotBlank();
        assertThat(resp.getHeaders().getFirst(HttpHeaders.SET_COOKIE))
                .isNotNull().contains("traced_refresh=");
    }

    // -----------------------------------------------------------------------
    // CA3: Refresh with valid cookie → new access token + rotated cookie
    // -----------------------------------------------------------------------
    @Test @Order(3)
    void ca3_refresh_withValidCookie_returnsNewAccessTokenAndRotatesCookie() {
        HttpHeaders h = new HttpHeaders();
        h.add(HttpHeaders.COOKIE, firstCookieValue);

        ResponseEntity<AccessTokenResponse> resp = rest.exchange(
                base() + "/api/v1/auth/refresh", HttpMethod.POST,
                new HttpEntity<>(null, h), AccessTokenResponse.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().accessToken()).isNotBlank();

        // The rotated Set-Cookie must differ from the original (token rotated)
        String newCookie = resp.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
        assertThat(newCookie).isNotNull().contains("traced_refresh=");
        assertThat(newCookie.split(";")[0]).isNotEqualTo(firstCookieValue);

        // Save the new cookie for CA5
        firstCookieValue = newCookie.split(";")[0];
    }

    // -----------------------------------------------------------------------
    // CA4: Refresh with NO cookie → 401
    // -----------------------------------------------------------------------
    @Test @Order(4)
    void ca4_refresh_withNoCookie_returns401() {
        ResponseEntity<String> resp = rest.postForEntity(
                base() + "/api/v1/auth/refresh", HttpEntity.EMPTY, String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // -----------------------------------------------------------------------
    // CA5: Refresh with the already-rotated-away (revoked) cookie → 401
    // -----------------------------------------------------------------------
    @Test @Order(5)
    void ca5_refresh_withRevokedCookie_returns401() {
        // firstCookieValue was updated in CA3 to the rotated token.
        // Rotate it once more so the CA3 cookie is revoked.
        HttpHeaders h = new HttpHeaders();
        h.add(HttpHeaders.COOKIE, firstCookieValue);
        ResponseEntity<AccessTokenResponse> rotateResp = rest.exchange(
                base() + "/api/v1/auth/refresh", HttpMethod.POST,
                new HttpEntity<>(null, h), AccessTokenResponse.class);
        assertThat(rotateResp.getStatusCode()).isEqualTo(HttpStatus.OK);

        // Now firstCookieValue is revoked — using it again must fail.
        ResponseEntity<String> reuse = rest.exchange(
                base() + "/api/v1/auth/refresh", HttpMethod.POST,
                new HttpEntity<>(null, h), String.class);
        assertThat(reuse.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // -----------------------------------------------------------------------
    // CA6: Logout → Max-Age=0 cookie; subsequent refresh → 401
    // -----------------------------------------------------------------------
    @Test @Order(6)
    void ca6_logout_expiresCookieAndRefreshFails() {
        // Fresh login to get a usable cookie
        ResponseEntity<AccessTokenResponse> loginResp = doLogin(userEmail, "password123");
        String cookieVal = loginResp.getHeaders().getFirst(HttpHeaders.SET_COOKIE).split(";")[0];
        String at = loginResp.getBody().accessToken();

        // Logout with Bearer token (endpoint is @PreAuthorize("isAuthenticated()"))
        HttpHeaders logoutH = new HttpHeaders();
        logoutH.setBearerAuth(at);
        logoutH.add(HttpHeaders.COOKIE, cookieVal);
        ResponseEntity<Void> logoutResp = rest.exchange(
                base() + "/api/v1/auth/logout", HttpMethod.POST,
                new HttpEntity<>(null, logoutH), Void.class);
        assertThat(logoutResp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        // Logout response must set Max-Age=0 to expire the cookie
        String afterLogoutCookie = logoutResp.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
        assertThat(afterLogoutCookie).isNotNull().contains("Max-Age=0");

        // The original cookie value is now revoked in the DB; refresh must 401
        HttpHeaders refreshH = new HttpHeaders();
        refreshH.add(HttpHeaders.COOKIE, cookieVal);
        ResponseEntity<String> refreshAttempt = rest.exchange(
                base() + "/api/v1/auth/refresh", HttpMethod.POST,
                new HttpEntity<>(null, refreshH), String.class);
        assertThat(refreshAttempt.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // -----------------------------------------------------------------------
    // CA7: refreshToken NEVER in login response body (structurally absent)
    // -----------------------------------------------------------------------
    @Test @Order(7)
    @SuppressWarnings("unchecked")
    void ca7_loginBody_hasNoRefreshTokenField() {
        ResponseEntity<java.util.Map> resp = rest.postForEntity(
                base() + "/api/v1/auth/login",
                new LoginRequest(userEmail, "password123"),
                java.util.Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).doesNotContainKey("refreshToken");
        assertThat(resp.getBody()).containsKey("accessToken");
    }

    // -----------------------------------------------------------------------
    // CA8: refreshToken NEVER in signup response body
    // -----------------------------------------------------------------------
    @Test @Order(8)
    @SuppressWarnings("unchecked")
    void ca8_signupBody_hasNoRefreshTokenField() {
        String email = "ca-no-rt-" + System.nanoTime() + "@cookie.test";
        ResponseEntity<java.util.Map> resp = rest.postForEntity(
                base() + "/api/v1/auth/signup",
                new SignupRequest("No-RT Corp", "no-rt", email, "password123", true),
                java.util.Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(resp.getBody()).doesNotContainKey("refreshToken");
        assertThat(resp.getBody()).containsKey("accessToken");
    }

    // -----------------------------------------------------------------------
    // CA9: refreshToken NEVER in refresh response body
    // -----------------------------------------------------------------------
    @Test @Order(9)
    @SuppressWarnings("unchecked")
    void ca9_refreshBody_hasNoRefreshTokenField() {
        // Login to get a fresh cookie
        ResponseEntity<AccessTokenResponse> loginResp = doLogin(userEmail, "password123");
        String cookie = loginResp.getHeaders().getFirst(HttpHeaders.SET_COOKIE).split(";")[0];

        HttpHeaders h = new HttpHeaders();
        h.add(HttpHeaders.COOKIE, cookie);
        ResponseEntity<java.util.Map> resp = rest.exchange(
                base() + "/api/v1/auth/refresh", HttpMethod.POST,
                new HttpEntity<>(null, h), java.util.Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).doesNotContainKey("refreshToken");
        assertThat(resp.getBody()).containsKey("accessToken");
    }

    // -----------------------------------------------------------------------
    // CA10: Cookie attributes — HttpOnly, Secure, SameSite=Lax, Path scoped to refresh
    // -----------------------------------------------------------------------
    @Test @Order(10)
    void ca10_cookie_hasCorrectAttributes() {
        ResponseEntity<AccessTokenResponse> resp = doLogin(userEmail, "password123");
        String setCookie = resp.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
        assertThat(setCookie).isNotNull();

        // Spring's ResponseCookie serialises these in a deterministic order
        assertThat(setCookie).contains("traced_refresh=");
        assertThat(setCookie).containsIgnoringCase("HttpOnly");
        assertThat(setCookie).containsIgnoringCase("Secure");
        assertThat(setCookie).containsIgnoringCase("SameSite=Lax");
        assertThat(setCookie).containsIgnoringCase("Path=/api/v1/auth/refresh");
        assertThat(setCookie).containsIgnoringCase("Max-Age=2592000");
    }

    // -----------------------------------------------------------------------
    // CA11: Cookie path isolation — the cookie must NOT appear on other paths.
    //        Verified by confirming the Path attribute is scoped, not "/".
    //        Browser behaviour: a cookie with Path=/api/v1/auth/refresh is never
    //        sent to /api/v1/embedded/* — so ShopifySessionTokenFilter is unaffected.
    // -----------------------------------------------------------------------
    @Test @Order(11)
    void ca11_cookiePath_isScopedToRefreshEndpointNotRoot() {
        ResponseEntity<AccessTokenResponse> resp = doLogin(userEmail, "password123");
        String setCookie = resp.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
        // Must be scoped to the refresh path, not the root "/"
        assertThat(setCookie).contains("Path=/api/v1/auth/refresh");
        assertThat(setCookie).doesNotContain("Path=/;")
                             .doesNotMatch(".*Path=/[^a].*");
    }

    // -----------------------------------------------------------------------
    // CA12: CORS is restrictive — no wildcard "*" allowed-origin in production config.
    //        The design relies on CORS as a defence-in-depth layer for SameSite=Lax.
    //        Verified by checking that a request from an unknown origin is rejected.
    // -----------------------------------------------------------------------
    @Test @Order(12)
    void ca12_cors_unknownOriginRejected() {
        HttpHeaders h = new HttpHeaders();
        h.add("Origin", "https://evil.example.com");
        ResponseEntity<String> resp = rest.exchange(
                base() + "/api/v1/auth/login", HttpMethod.OPTIONS,
                new HttpEntity<>(null, h), String.class);
        // Spring CORS returns 403 for disallowed origins on preflight
        assertThat(resp.getStatusCode().value()).isIn(403, 200); // 200 if no ACAO header present
        String acao = resp.getHeaders().getFirst("Access-Control-Allow-Origin");
        // Must NOT echo the evil origin or return "*"
        assertThat(acao).as("ACAO must not echo untrusted origin or be wildcard")
                .satisfiesAnyOf(
                    v -> assertThat(v).isNull(),
                    v -> assertThat(v).isNotEqualTo("https://evil.example.com")
                             .isNotEqualTo("*"));
    }

    // -----------------------------------------------------------------------
    // helpers
    // -----------------------------------------------------------------------

    private ResponseEntity<AccessTokenResponse> doLogin(String email, String password) {
        return rest.postForEntity(
                base() + "/api/v1/auth/login",
                new LoginRequest(email, password),
                AccessTokenResponse.class);
    }

    private ResponseEntity<AccessTokenResponse> doSignup(String email, String password) {
        return rest.postForEntity(
                base() + "/api/v1/auth/signup",
                new SignupRequest("Cookie Corp", email.split("@")[0], email, password, true),
                AccessTokenResponse.class);
    }
}
