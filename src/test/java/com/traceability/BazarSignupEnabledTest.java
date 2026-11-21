package com.traceability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.traceability.account.OnboardingProgressService;
import com.traceability.integrations.bosta.BostaGateway;
import com.traceability.integrations.bosta.BostaV2Client;
import com.traceability.integrations.shopify.ShopifyGateway;
import com.traceability.integrations.shopify.ShopifyLocationGateway;
import com.traceability.notifications.EmailGateway;
import org.jobrunr.scheduling.JobScheduler;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.DefaultResponseErrorHandler;
import org.springframework.web.client.RestTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.http.HttpClient;
import java.sql.DriverManager;
import java.util.*;

import static com.traceability.ShopifySessionTokenFilterTest.makeToken;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

/**
 * Bazar B9a — BAZAR_SIGNUP_ENABLED=true (traced.bazar.signup-enabled). Whole app as app_user, real HTTP.
 *   f1 web bazar signup → tenants.bazar_enabled true in the signup transaction, one bazar_enabled_at_signup audit row,
 *      /me bazar true
 *   f2 App Store bazar signup → the same
 *   f3 normal signups (no intent, another intent, App Store default) → bazar_enabled false, no audit row
 *   f4 a failed bazar signup (duplicate email) leaves no tenant and no audit row
 * (BazarSignupIntentTest runs with the setting off: bazar stays false for every signup.)
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BazarSignupEnabledTest {

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
        r.add("shopify.app-handle",         () -> "trace-3");
        r.add("traced.catalog-backfill.enabled", () -> "false");
        r.add("traced.bazar.signup-enabled", () -> "true");   // BAZAR_SIGNUP_ENABLED=true
    }

    static final String ACCESS  = "shpat_b9a_token";
    static final String REFRESH = "shprt_b9a_refresh";
    static final String SCOPES  = "read_products,read_orders,read_fulfillments,read_customers,write_inventory";
    static final String PASSWORD = "pass1234";

    @LocalServerPort int port;
    @Autowired JdbcTemplate appJdbc;
    @Value("${shopify.client-secret}") String clientSecret;
    @Value("${shopify.client-id}")     String clientId;

    @MockBean ShopifyGateway shopify;
    @MockBean ShopifyLocationGateway locations;
    @MockBean BostaGateway bosta;
    @MockBean BostaV2Client bostaV2;
    @MockBean EmailGateway email;
    @MockBean JobScheduler jobs;

    final ObjectMapper mapper = new ObjectMapper();
    JdbcTemplate owner;
    RestTemplate http;

    @BeforeAll
    void setup() {
        owner = new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "postgres", "postgres"));
        http = new RestTemplate(new JdkClientHttpRequestFactory(
            HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()));
        http.setErrorHandler(new DefaultResponseErrorHandler() {
            @Override public boolean hasError(org.springframework.http.client.ClientHttpResponse r) { return false; }
        });
    }

    @BeforeEach
    void mocks() {
        reset(shopify, jobs);
        when(shopify.exchangeSessionToken(anyString(), anyString()))
            .thenReturn(new ShopifyGateway.TokenResponse(ACCESS, REFRESH, 3600L, 7776000L, SCOPES));
        when(shopify.fetchShop(anyString(), eq(ACCESS)))
            .thenReturn(new ShopifyGateway.ShopInfo("owner@shop.test", "Bazar Co", "Africa/Cairo"));
        owner.update("DELETE FROM embedded_onboarding_attempts");
    }

    @Test
    @SuppressWarnings("unchecked")
    void f1_webBazarSignup_switchesBazarOn_oneAuditRow() throws Exception {
        String mail = mail("f1");
        String token = webSignup(mail, "bazar");
        UUID tenant = tenantOf(mail);
        assertThat(owner.queryForObject("SELECT bazar_enabled FROM tenants WHERE id = ?", Boolean.class, tenant)).isTrue();
        assertThat(owner.queryForObject(
            "SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = 'bazar_enabled_at_signup' " +
            "AND actor_user_id = (SELECT id FROM users WHERE email = ?)", Integer.class, tenant, mail)).isEqualTo(1);
        assertThat(mapper.readValue(prefs(tenant), Map.class)).isEqualTo(Map.of("intent", "bazar"));
        connect(tenant, "f1");
        Map<String, Object> me = me(token);
        assertThat(me.get("bazar")).isEqualTo(true);
        assertThat(me.get("setupIntro")).isEqualTo("bazar");
    }

    @Test
    void f2_appStoreBazarSignup_switchesBazarOn() throws Exception {
        String shop = "b9a-f2-" + System.nanoTime() + ".myshopify.com";
        String mail = mail("f2");
        Map<String, Object> form = signupBody(mail);
        form.put("intent", "bazar");
        assertThat(embeddedSignup(shop, form).getStatusCode()).isEqualTo(HttpStatus.OK);
        UUID tenant = tenantOf(mail);
        assertThat(owner.queryForObject("SELECT bazar_enabled FROM tenants WHERE id = ?", Boolean.class, tenant)).isTrue();
        assertThat(owner.queryForObject(
            "SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = 'bazar_enabled_at_signup'", Integer.class, tenant)).isEqualTo(1);
        assertThat(me(login(mail)).get("bazar")).isEqualTo(true);
    }

    @Test
    void f3_normalSignups_neverTouchBazar() throws Exception {
        String mail = mail("f3");
        webSignup(mail, null);
        String other = mail("f3o");
        webSignup(other, "orders");
        String shop = "b9a-f3-" + System.nanoTime() + ".myshopify.com";
        String app = mail("f3a");
        assertThat(embeddedSignup(shop, signupBody(app)).getStatusCode()).isEqualTo(HttpStatus.OK);
        for (String m : List.of(mail, other, app)) {
            UUID tenant = tenantOf(m);
            assertThat(owner.queryForObject("SELECT bazar_enabled FROM tenants WHERE id = ?", Boolean.class, tenant)).as(m).isFalse();
            assertThat(owner.queryForObject(
                "SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = 'bazar_enabled_at_signup'", Integer.class, tenant)).isZero();
            assertThat(prefs(tenant)).isEqualTo("{}");
        }
    }

    @Test
    void f4_failedBazarSignup_leavesNothing() throws Exception {
        String mail = mail("f4");
        webSignup(mail, "bazar");
        long audits = owner.queryForObject("SELECT count(*) FROM audit_log WHERE action = 'bazar_enabled_at_signup'", Long.class);
        Map<String, Object> body = signupBody(mail);
        body.put("intent", "bazar");
        body.put("tenantName", "Dup Co");
        assertThat(http.postForEntity(base() + "/api/v1/auth/signup", new HttpEntity<>(body, json()), String.class)
            .getStatusCode().is2xxSuccessful()).isFalse();
        assertThat(owner.queryForObject("SELECT count(*) FROM tenants WHERE name = 'Dup Co'", Long.class)).isZero();
        assertThat(owner.queryForObject("SELECT count(*) FROM audit_log WHERE action = 'bazar_enabled_at_signup'", Long.class))
            .isEqualTo(audits);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private void assertBazarOff(UUID tenant) {
        assertThat(owner.queryForObject("SELECT bazar_enabled FROM tenants WHERE id = ?", Boolean.class, tenant))
            .as("BAZAR_SIGNUP_ENABLED off").isFalse();
        assertThat(owner.queryForObject(
            "SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = 'bazar_enabled_at_signup'", Integer.class, tenant)).isZero();
    }

    private String base() { return "http://localhost:" + port; }

    private static String mail(String tag) { return "b9a-" + tag + "-" + System.nanoTime() + "@b9a.test"; }

    private static Map<String, Object> signupBody(String mail) {
        Map<String, Object> f = new HashMap<>();
        f.put("tenantName", "Bazar Co");
        f.put("name", "Mona Adel");
        f.put("email", mail);
        f.put("phone", "+201012345678");
        f.put("password", PASSWORD);
        f.put("consent", true);
        return f;
    }

    @SuppressWarnings("unchecked")
    private String webSignup(String mail, String intent) {
        Map<String, Object> body = signupBody(mail);
        if (intent != null) body.put("intent", intent);
        ResponseEntity<Map> r = http.postForEntity(base() + "/api/v1/auth/signup", new HttpEntity<>(body, json()), Map.class);
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.CREATED);
        return (String) r.getBody().get("accessToken");
    }

    private UUID tenantOf(String mail) {
        return owner.queryForObject("SELECT tenant_id FROM users WHERE email = ?", UUID.class, mail);
    }

    private String prefs(UUID tenant) {
        return owner.queryForObject("SELECT onboarding_prefs::text FROM tenants WHERE id = ?", String.class, tenant);
    }

    /** What a Shopify connect leaves behind for the gate: a stores row (V186 stamps tenants.shopify_connected_at). */
    private void connect(UUID tenant, String tag) {
        owner.update("INSERT INTO stores (tenant_id, shop_domain, status, import_status) VALUES (?, ?, 'connected', 'completed')",
            tenant, "b9a-" + tag + "-" + System.nanoTime() + ".myshopify.com");
        assertThat(owner.queryForObject("SELECT shopify_connected_at IS NOT NULL FROM tenants WHERE id = ?", Boolean.class, tenant))
            .isTrue();
    }

    @SuppressWarnings("unchecked")
    private String login(String mail) {
        ResponseEntity<Map> r = http.postForEntity(base() + "/api/v1/auth/login",
            new HttpEntity<>(Map.of("email", mail, "password", PASSWORD), json()), Map.class);
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.OK);
        return (String) r.getBody().get("accessToken");
    }

    private String addUserAndLogin(UUID tenant, String role, String mail) {
        String hash = owner.queryForObject("SELECT password_hash FROM users WHERE tenant_id = ? AND role = 'owner'", String.class, tenant);
        owner.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role) VALUES (gen_random_uuid(), ?, 'M', ?, ?, ?::user_role)",
            tenant, mail, hash, role);
        return login(mail);
    }

    private Map<String, Object> me(String token) throws Exception { return get("/api/v1/me", token); }

    @SuppressWarnings("unchecked")
    private Map<String, Object> get(String path, String token) throws Exception {
        ResponseEntity<String> r = send(HttpMethod.GET, path, token, null);
        assertThat(r.getStatusCode()).as(r.getBody()).isEqualTo(HttpStatus.OK);
        return mapper.readValue(r.getBody(), Map.class);
    }

    private ResponseEntity<String> send(HttpMethod method, String path, String token, Object body) {
        HttpHeaders h = json();
        h.setBearerAuth(token);
        return http.exchange(base() + path, method, new HttpEntity<>(body, h), String.class);
    }

    /** /me without the per-account values (ids, names, email, phone). */
    private String normalise(Map<String, Object> me) throws Exception {
        Map<String, Object> m = new TreeMap<>(me);
        for (String k : List.of("id", "userId", "tenantId", "name", "email", "phone", "tenantName")) m.remove(k);
        return mapper.writeValueAsString(m);
    }

    @SuppressWarnings("unchecked")
    private ResponseEntity<Map> embeddedSignup(String shop, Map<String, Object> form) throws Exception {
        HttpHeaders h = json();
        h.setBearerAuth(makeToken(shop, clientId, clientSecret, 60, false));
        return http.exchange(base() + "/api/v1/embedded/onboarding/signup", HttpMethod.POST, new HttpEntity<>(form, h), Map.class);
    }

    @SuppressWarnings("unchecked")
    private ResponseEntity<Map> embeddedGet(String shop, String path) throws Exception {
        HttpHeaders h = json();
        h.setBearerAuth(makeToken(shop, clientId, clientSecret, 60, false));
        return http.exchange(base() + path, HttpMethod.GET, new HttpEntity<>(null, h), Map.class);
    }

    private static HttpHeaders json() {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }
}
