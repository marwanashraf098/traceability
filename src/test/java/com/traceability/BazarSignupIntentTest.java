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
 * Bazar B9a — a store that signs up to sell at a bazar. The WHOLE app runs as app_user (RLS on, like production);
 * every call is real HTTP. Fixtures and assertions use the postgres owner.
 *
 *   w1 web /auth/signup with intent 'bazar': onboarding_prefs = {intent: 'bazar'} written in the signup's own
 *      transaction (as app_user) + one audit row; /me gate 'shopify' until connected, then null with NO address
 *      step, setupIntro 'bazar' (owner only) until setupIntroSeen; the address banner and the Get started card's
 *      address / courier steps stay open (reachable later).
 *   w2 normal web signup (no intent, an unknown intent, a non-string intent) — golden: prefs '{}', no audit row,
 *      gate 'shopify' → 'address' → null, intro 'courier'; /me identical whatever was sent.
 *   w3 intent is signup-only: PATCH /onboarding/prefs {intent} → 400, nothing written.
 *   w4 a signup that fails (duplicate email) leaves no tenant and no prefs behind — the write is in the same
 *      transaction.
 *   e1 App Store onboarding with intent 'bazar': prefs written, the embedded gate (/embedded/setup) and /me are
 *      done right after signup (no address step), setupIntro 'bazar'.
 *   e2 App Store onboarding without intent: unchanged — gate 'address', no intent stored.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BazarSignupIntentTest {

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

    // ── w1 ────────────────────────────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void w1_webBazarSignup_skipsAddressStep_landsOnBazarIntroOnce() throws Exception {
        assertThat(appJdbc.queryForObject("SELECT current_user || ':' || rolbypassrls FROM pg_roles WHERE rolname = current_user",
            String.class)).isEqualTo("app_user:false");
        String mail = mail("w1");
        String token = webSignup(mail, "bazar");
        UUID tenant = tenantOf(mail);

        assertThat(mapper.readValue(prefs(tenant), Map.class)).isEqualTo(Map.of("intent", "bazar"));
        assertThat(owner.queryForObject(
            "SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = 'onboarding_prefs_set' " +
            "AND metadata->>'intent' = 'bazar' AND actor_user_id = (SELECT id FROM users WHERE email = ?)",
            Integer.class, tenant, mail)).isEqualTo(1);

        Map<String, Object> me = me(token);
        assertThat(me.get("setupStep")).as("not connected yet").isEqualTo("shopify");
        assertThat(me.get("setupIntro")).isNull();

        connect(tenant, "w1");
        me = me(token);
        assertThat(me.get("setupStep")).as("connected is enough — no address step").isNull();
        assertThat(me.get("setupIntro")).isEqualTo("bazar");
        assertThat(me.get("warehouseAddressNeeded")).as("address banner still reachable").isEqualTo(true);

        // Get started card: address and courier still to do (reachable later).
        Map<String, Object> progress = get("/api/v1/onboarding/progress", token);
        Map<String, Map<String, Object>> steps = new HashMap<>();
        for (Map<String, Object> s : (List<Map<String, Object>>) progress.get("steps")) steps.put((String) s.get("key"), s);
        assertThat(steps.get("shopify").get("done")).isEqualTo(true);
        assertThat(steps.get("address").get("done")).isEqualTo(false);
        assertThat(steps.get("courier").get("done")).isEqualTo(false);
        assertThat(progress.get("card")).isEqualTo("full");

        // Not for a manager.
        String manager = addUserAndLogin(tenant, "manager", mail("w1m"));
        assertThat(me(manager).get("setupIntro")).isNull();
        assertThat(me(manager).get("setupStep")).isNull();

        // Once: the page writes setupIntroSeen.
        ResponseEntity<String> seen = send(HttpMethod.PATCH, "/api/v1/onboarding/prefs", token, Map.of("setupIntroSeen", true));
        assertThat(seen.getStatusCode()).as(seen.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(me(token).get("setupIntro")).isNull();
        assertThat(mapper.readValue(prefs(tenant), Map.class)).containsEntry("intent", "bazar");

        // Saving the address later is the normal address path (the gate is already open, stays open).
        assertThat(me(token).get("setupStep")).isNull();
    }

    // ── w2 ────────────────────────────────────────────────────────────────────

    @Test
    void w2_normalWebSignup_unchanged_golden() throws Exception {
        List<Object> sent = Arrays.asList(null, "orders", "  ", 7, Map.of("x", 1));
        List<String> mes = new ArrayList<>();
        for (int i = 0; i < sent.size(); i++) {
            String mail = mail("w2-" + i);
            Map<String, Object> body = signupBody(mail);
            if (sent.get(i) != null) body.put("intent", sent.get(i));
            ResponseEntity<Map> r = http.postForEntity(base() + "/api/v1/auth/signup", new HttpEntity<>(body, json()), Map.class);
            assertThat(r.getStatusCode()).as("%s → %s (never a 400)", sent.get(i), r.getBody()).isEqualTo(HttpStatus.CREATED);
            String token = (String) r.getBody().get("accessToken");
            UUID tenant = tenantOf(mail);
            assertThat(prefs(tenant)).as("%s", sent.get(i)).isEqualTo("{}");
            assertThat(owner.queryForObject("SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = 'onboarding_prefs_set'",
                Integer.class, tenant)).isZero();

            assertThat(me(token).get("setupStep")).isEqualTo("shopify");
            connect(tenant, "w2" + i);
            assertThat(me(token).get("setupStep")).as("address step as before").isEqualTo("address");
            assertThat(me(token).get("setupIntro")).isNull();
            owner.update("UPDATE locations SET address_province_code = 'EG-C', address_city = 'Nasr City', " +
                         "address_street = '1 Street' WHERE tenant_id = ? AND is_fulfillment", tenant);
            Map<String, Object> me = me(token);
            assertThat(me.get("setupStep")).isNull();
            assertThat(me.get("setupIntro")).isEqualTo("courier");
            assertThat(me.get("warehouseAddressNeeded")).isEqualTo(false);
            mes.add(normalise(me));
        }
        assertThat(mes).hasSize(sent.size());
        assertThat(new HashSet<>(mes)).as("/me is the same whatever (non-bazar) intent was sent").hasSize(1);
    }

    // ── w3 ────────────────────────────────────────────────────────────────────

    @Test
    void w3_intentIsSignupOnly_patchRefused() throws Exception {
        String mail = mail("w3");
        String token = webSignup(mail, null);
        UUID tenant = tenantOf(mail);
        for (Object v : Arrays.asList("bazar", "orders")) {
            ResponseEntity<String> r = send(HttpMethod.PATCH, "/api/v1/onboarding/prefs", token, Map.of("intent", v));
            assertThat(r.getStatusCode()).as("%s → %s", v, r.getBody()).isEqualTo(HttpStatus.BAD_REQUEST);
        }
        ResponseEntity<String> mixed = send(HttpMethod.PATCH, "/api/v1/onboarding/prefs", token,
            Map.of("intent", "bazar", "setupIntroSeen", true));
        assertThat(mixed.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(prefs(tenant)).as("nothing written").isEqualTo("{}");

        assertThat(OnboardingProgressService.signupIntent(" Bazar ")).isEqualTo("bazar");
        assertThat(OnboardingProgressService.signupIntent("bazaar")).isNull();
        assertThat(OnboardingProgressService.signupIntent((String) null)).isNull();
    }

    // ── w4 ────────────────────────────────────────────────────────────────────

    @Test
    void w4_failedSignup_leavesNothing() throws Exception {
        String mail = mail("w4");
        webSignup(mail, "bazar");
        long tenantsBefore = owner.queryForObject("SELECT count(*) FROM tenants", Long.class);
        long auditBefore   = owner.queryForObject("SELECT count(*) FROM audit_log WHERE action = 'onboarding_prefs_set'", Long.class);

        Map<String, Object> body = signupBody(mail);   // same email → duplicate
        body.put("intent", "bazar");
        body.put("tenantName", "Second Co");
        ResponseEntity<String> r = http.postForEntity(base() + "/api/v1/auth/signup", new HttpEntity<>(body, json()), String.class);
        assertThat(r.getStatusCode().is2xxSuccessful()).as(r.getBody()).isFalse();
        assertThat(owner.queryForObject("SELECT count(*) FROM tenants", Long.class)).isEqualTo(tenantsBefore);
        assertThat(owner.queryForObject("SELECT count(*) FROM tenants WHERE name = 'Second Co'", Long.class)).isZero();
        assertThat(owner.queryForObject("SELECT count(*) FROM audit_log WHERE action = 'onboarding_prefs_set'", Long.class))
            .isEqualTo(auditBefore);
    }

    // ── e1 / e2 ───────────────────────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void e1_appStoreBazarSignup_noAddressStep_bazarIntro() throws Exception {
        String shop = "b9a-e1-" + System.nanoTime() + ".myshopify.com";
        String mail = mail("e1");
        Map<String, Object> form = signupBody(mail);
        form.put("intent", "bazar");
        ResponseEntity<Map> r = embeddedSignup(shop, form);
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.OK);
        UUID tenant = tenantOf(mail);
        assertThat(mapper.readValue(prefs(tenant), Map.class)).isEqualTo(Map.of("intent", "bazar"));
        assertThat(owner.queryForObject("SELECT tenant_id FROM stores WHERE shop_domain = ?", UUID.class, shop)).isEqualTo(tenant);

        ResponseEntity<Map> gate = embeddedGet(shop, "/api/v1/embedded/setup");
        assertThat(gate.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(gate.getBody()).containsEntry("setupStep", null);

        String token = login(mail);
        Map<String, Object> me = me(token);
        assertThat(me.get("setupStep")).isNull();
        assertThat(me.get("setupIntro")).isEqualTo("bazar");
        assertThat(me.get("warehouseAddressNeeded")).isEqualTo(true);
    }

    @Test
    void e2_appStoreNormalSignup_unchanged() throws Exception {
        String shop = "b9a-e2-" + System.nanoTime() + ".myshopify.com";
        String mail = mail("e2");
        ResponseEntity<Map> r = embeddedSignup(shop, signupBody(mail));
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.OK);
        UUID tenant = tenantOf(mail);
        assertThat(prefs(tenant)).isEqualTo("{}");
        assertThat(embeddedGet(shop, "/api/v1/embedded/setup").getBody()).containsEntry("setupStep", "address");
        Map<String, Object> me = me(login(mail));
        assertThat(me.get("setupStep")).isEqualTo("address");
        assertThat(me.get("setupIntro")).isNull();
    }

    // ── helpers ───────────────────────────────────────────────────────────────

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
