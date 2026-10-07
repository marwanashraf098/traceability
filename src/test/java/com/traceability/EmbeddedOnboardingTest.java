package com.traceability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.traceability.identity.AuthRepository;
import com.traceability.integrations.bosta.BostaGateway;
import com.traceability.integrations.bosta.BostaV2Client;
import com.traceability.integrations.shopify.ShopifyGateway;
import com.traceability.integrations.shopify.ShopifyLocationGateway;
import com.traceability.integrations.shopify.ShopifySessionTokenExchangeException;
import com.traceability.notifications.EmailGateway;
import com.traceability.security.EncryptionService;
import org.jobrunr.jobs.lambdas.JobLambda;
import org.jobrunr.scheduling.JobScheduler;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.DefaultResponseErrorHandler;
import org.springframework.web.client.RestTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

import static com.traceability.ShopifySessionTokenFilterTest.makeToken;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Build D — onboarding inside the embedded Shopify app, with the WHOLE app on app_user (RLS ON for every
 * request — the production wiring). Mocked: ShopifyGateway (token exchange, shop info), JobScheduler
 * (asserts what gets enqueued), outside-world gateways. Fixtures and assertions use the postgres owner.
 *
 *   s1 signup happy path: tenant + owner + Main Warehouse + store (oauth, ingest floor now) in one
 *      transaction, attribution utm_source=shopify_app_store, import + webhooks + welcome enqueued, and a
 *      ≤10-minute one-time sign-in link in the response.
 *   s2 nothing created on failure before write: bad session token, token exchange refused (uninstalled),
 *      signup-rule violations (exchange never called).
 *   s3 an already-linked shop can't onboard (prefill / signup / pending link → 409, no Shopify call), and the
 *      onboarding principal reaches nothing else under /embedded.
 *   s4 concurrent double submit for one shop → exactly one tenant.
 *   s5 duplicate email → 409 EMAIL_TAKEN with the web signup's wording, nothing created.
 *   s6 the one-time link works once, expires, and is never issued outside the signup response.
 *   s7 rate limit per shop.
 *   p1 pending link: only for a verified session of an unlinked shop; stored hashed + encrypted, 15 minutes.
 *   p2 confirm links the shop to the signed-in owner's tenant and sends the browser to Shopify admin; the
 *      nonce is single-use; an expired one is refused.
 *   p3 refused for a non-owner and for a tenant bound to another shop (the link stays usable).
 *   p4 the token never appears in a URL or the logs.
 *   p5 RLS: app_user sees a pending link only inside a transaction that set its nonce hash.
 *   p6 the consume nulls the token in the same statement; app_user may update only those columns.
 *   p7 the nightly purge (hatch #16) deletes only expired / consumed links and attempts older than 24 h;
 *      app_user can't delete from either table; provision_tenant_from_shopify (hatch #5) is dropped.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(OutputCaptureExtension.class)
class EmbeddedOnboardingTest {

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
        r.add("spring.datasource.username", () -> "app_user");          // RLS on everywhere, like production
        r.add("spring.datasource.password", () -> "testpw");
        r.add("spring.flyway.url",          POSTGRES::getJdbcUrl);
        r.add("spring.flyway.user",         POSTGRES::getUsername);
        r.add("spring.flyway.password",     POSTGRES::getPassword);
        r.add("shopify.app-handle",         () -> "trace-3");
        r.add("traced.catalog-backfill.enabled", () -> "false");
    }

    static final String ACCESS  = "shpat_onboarding_secret_token";
    static final String REFRESH = "shprt_onboarding_secret_refresh";
    static final String SCOPES  = "read_products,read_orders,read_fulfillments,read_customers,write_inventory";

    @LocalServerPort int port;
    @Autowired JdbcTemplate appJdbc;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired EncryptionService encryption;
    @Autowired ObjectMapper mapper;
    @Value("${shopify.client-secret}") String clientSecret;
    @Value("${shopify.client-id}")     String clientId;
    @Value("${shopify.app-url}")       String appUrl;

    @MockBean ShopifyGateway shopify;
    @MockBean ShopifyLocationGateway locations;
    @MockBean BostaGateway bosta;
    @MockBean BostaV2Client bostaV2;
    @MockBean EmailGateway email;
    @MockBean JobScheduler jobs;

    JdbcTemplate owner;
    RestTemplate http;
    DriverManagerDataSource appUserDs;

    // A tenant that already owns a shop (+ a manager), and a tenant with an owner and no store.
    UUID linkedTenant, linkedOwner, linkedManager;
    String linkedShop;
    UUID freeTenant, freeOwner;

    @BeforeAll
    void setup() {
        owner = new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "postgres", "postgres"));
        appUserDs = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "app_user", "testpw");
        http = new RestTemplate(new JdkClientHttpRequestFactory(
            HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()));
        http.setErrorHandler(new DefaultResponseErrorHandler() {
            @Override public boolean hasError(org.springframework.http.client.ClientHttpResponse r) { return false; }
        });

        linkedShop = "linked-" + System.nanoTime() + ".myshopify.com";
        linkedTenant = tenant("Linked Co");
        linkedOwner = user(linkedTenant, "owner", "linked-owner-" + System.nanoTime() + "@onb.test");
        linkedManager = user(linkedTenant, "manager", "linked-mgr-" + System.nanoTime() + "@onb.test");
        owner.update("INSERT INTO stores (tenant_id, shop_domain, status, import_status) VALUES (?, ?, 'connected', 'completed')",
            linkedTenant, linkedShop);

        freeTenant = tenant("Free Co");
        freeOwner = user(freeTenant, "owner", "free-owner-" + System.nanoTime() + "@onb.test");
    }

    @BeforeEach
    void mocks() {
        reset(shopify, jobs);
        when(shopify.exchangeSessionToken(anyString(), anyString()))
            .thenReturn(new ShopifyGateway.TokenResponse(ACCESS, REFRESH, 3600L, 7776000L, SCOPES));
        when(shopify.fetchShop(anyString(), eq(ACCESS)))
            .thenReturn(new ShopifyGateway.ShopInfo("owner@shop.test", "Mona's Linen", "Africa/Cairo"));
        owner.update("DELETE FROM embedded_onboarding_attempts");
    }

    // ── s1 ────────────────────────────────────────────────────────────────────

    @Test
    void s1_signup_createsTenantOwnerStoreInOneTransaction_enqueuesImportWebhooksWelcome() throws Exception {
        assertThat(appJdbc.queryForObject("SELECT current_user || ':' || rolbypassrls FROM pg_roles WHERE rolname = current_user",
            String.class)).isEqualTo("app_user:false");
        String shop = newShop("s1");
        String mail = "s1-" + System.nanoTime() + "@onb.test";

        ResponseEntity<Map> r = signup(shop, mail, "Mona's Linen");
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody().get("shopDomain")).isEqualTo(shop);
        assertThat(r.getBody().get("email")).isEqualTo(mail);
        assertThat(r.getBody().get("signInValidMinutes")).isEqualTo(10);
        assertThat((String) r.getBody().get("signInUrl")).startsWith(appUrl + "/auth/magic?token=");

        UUID tenant = owner.queryForObject("SELECT tenant_id FROM users WHERE email = ?", UUID.class, mail);
        assertThat(owner.queryForMap("SELECT name, role::text AS role FROM users WHERE email = ?", mail))
            .containsEntry("role", "owner");
        assertThat(owner.queryForObject("SELECT name FROM tenants WHERE id = ?", String.class, tenant)).isEqualTo("Mona's Linen");
        assertThat(owner.queryForObject("SELECT count(*) FROM locations WHERE tenant_id = ? AND is_fulfillment", Integer.class, tenant)).isEqualTo(1);
        Map<String, Object> store = owner.queryForMap(
            "SELECT tenant_id, connection_type, status::text AS status, import_status::text AS import_status, " +
            "       orders_ingest_from, access_token_encrypted, refresh_token_encrypted, access_token_scopes " +
            "FROM stores WHERE shop_domain = ?", shop);
        assertThat(store.get("tenant_id")).isEqualTo(tenant);
        assertThat(store).containsEntry("connection_type", "oauth").containsEntry("status", "connected")
            .containsEntry("import_status", "pending").containsEntry("access_token_scopes", SCOPES);
        assertThat(store.get("orders_ingest_from")).isNotNull();
        assertThat(encryption.decrypt((String) store.get("access_token_encrypted"))).isEqualTo(ACCESS);
        assertThat(encryption.decrypt((String) store.get("refresh_token_encrypted"))).isEqualTo(REFRESH);
        assertThat(owner.queryForObject("SELECT utm_source FROM tenant_ad_attribution WHERE tenant_id = ?", String.class, tenant))
            .isEqualTo("shopify_app_store");

        // Shop comes from the verified token; the exchange used that shop and that token.
        verify(shopify).exchangeSessionToken(eq(shop), anyString());
        // import + webhook registration + welcome email.
        verify(jobs, times(3)).enqueue(any(JobLambda.class));

        // The sign-in link: one row, ≤ 10 minutes.
        assertThat(owner.queryForObject(
            "SELECT expires_at <= now() + interval '10 minutes 5 seconds' AND expires_at > now() + interval '9 minutes' " +
            "FROM magic_link_tokens WHERE tenant_id = ?", Boolean.class, tenant)).isTrue();

        // The shop now resolves to its tenant: the normal embedded endpoints answer.
        ResponseEntity<String> status = embedded(HttpMethod.GET, "/api/v1/embedded/stores/status", shop, null);
        assertThat(status.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(status.getBody()).contains(shop);
    }

    // ── s2 ────────────────────────────────────────────────────────────────────

    @Test
    void s2_failureBeforeWrite_createsNothing() throws Exception {
        int tenantsBefore = owner.queryForObject("SELECT count(*) FROM tenants", Integer.class);

        // (a) bad session token (wrong secret) — the filter refuses.
        String shopA = newShop("s2a");
        HttpHeaders bad = json();
        bad.setBearerAuth(makeToken(shopA, clientId, "not-the-secret-but-long-enough-for-hs256-0123456789", 60, false));
        ResponseEntity<String> ra = http.exchange(base() + "/api/v1/embedded/onboarding/signup", HttpMethod.POST,
            new HttpEntity<>(form("s2a-" + System.nanoTime() + "@onb.test", "Co", "pass1234", "01012345678", true), bad), String.class);
        assertThat(ra.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        // (b) the app was uninstalled: Shopify refuses the exchange → 502, nothing written.
        String shopB = newShop("s2b");
        String mailB = "s2b-" + System.nanoTime() + "@onb.test";
        when(shopify.exchangeSessionToken(eq(shopB), anyString()))
            .thenThrow(new ShopifySessionTokenExchangeException(shopB, "401 from Shopify"));
        assertThat(signup(shopB, mailB, "Co").getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(owner.queryForObject("SELECT count(*) FROM users WHERE email = ?", Integer.class, mailB)).isZero();
        assertThat(owner.queryForObject("SELECT count(*) FROM stores WHERE shop_domain = ?", Integer.class, shopB)).isZero();

        // (c) signup rules — refused before any Shopify call.
        reset(shopify);
        String shopC = newShop("s2c");
        String mailC = "s2c-" + System.nanoTime() + "@onb.test";
        assertThat(post(shopC, "/signup", form(mailC, "Co", "short", "01012345678", true)).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(post(shopC, "/signup", form(mailC, "Co", "pass1234", "0123", true)).getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(post(shopC, "/signup", form(mailC, "Co", "pass1234", "01012345678", false)).getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(post(shopC, "/signup", form(mailC, null, "pass1234", "01012345678", true)).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verify(shopify, never()).exchangeSessionToken(anyString(), anyString());

        assertThat(owner.queryForObject("SELECT count(*) FROM tenants", Integer.class)).isEqualTo(tenantsBefore);
        assertThat(owner.queryForObject("SELECT count(*) FROM stores WHERE shop_domain IN (?, ?, ?)", Integer.class, shopA, shopB, shopC)).isZero();
        verify(jobs, never()).enqueue(any(JobLambda.class));
    }

    // ── s3 ────────────────────────────────────────────────────────────────────

    @Test
    void s3_linkedShop_cannotOnboard_andOnboardingPrincipalReachesNothingElse() throws Exception {
        int links = owner.queryForObject("SELECT count(*) FROM shopify_pending_links", Integer.class);
        for (ResponseEntity<String> r : List.of(
                embedded(HttpMethod.GET, "/api/v1/embedded/onboarding/prefill", linkedShop, null),
                embedded(HttpMethod.POST, "/api/v1/embedded/onboarding/signup", linkedShop,
                    form("s3-" + System.nanoTime() + "@onb.test", "Co", "pass1234", "01012345678", true)),
                embedded(HttpMethod.POST, "/api/v1/embedded/onboarding/pending-link", linkedShop, null))) {
            assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(r.getBody()).contains("SHOP_LINKED_ELSEWHERE")
                .contains("This Shopify store is already connected to a different Traced account. Sign in to that account to manage it.");
        }
        verify(shopify, never()).exchangeSessionToken(anyString(), anyString());
        assertThat(owner.queryForObject("SELECT count(*) FROM shopify_pending_links", Integer.class)).isEqualTo(links);

        // An unlinked shop's token still opens nothing outside /onboarding (unchanged NOT_PROVISIONED).
        String unlinked = newShop("s3u");
        ResponseEntity<String> other = embedded(HttpMethod.GET, "/api/v1/embedded/stores/status", unlinked, null);
        assertThat(other.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(other.getBody()).contains("NOT_PROVISIONED");
        assertThat(embedded(HttpMethod.POST, "/api/v1/embedded/token-exchange", unlinked, null).getStatusCode())
            .isEqualTo(HttpStatus.UNAUTHORIZED);

        // A Traced JWT (owner of a real tenant) can't use the onboarding endpoints.
        HttpHeaders h = json();
        h.setBearerAuth(login(freeOwner));
        assertThat(http.exchange(base() + "/api/v1/embedded/onboarding/pending-link", HttpMethod.POST,
            new HttpEntity<>(null, h), String.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ── s4 ────────────────────────────────────────────────────────────────────

    @Test
    void s4_concurrentDoubleSubmit_oneTenant() throws Exception {
        String shop = newShop("s4");
        // Both requests pass "shop still unlinked" before either writes.
        when(shopify.exchangeSessionToken(eq(shop), anyString())).thenAnswer(inv -> {
            Thread.sleep(700);
            return new ShopifyGateway.TokenResponse(ACCESS, REFRESH, 3600L, 7776000L, SCOPES);
        });
        String m1 = "s4a-" + System.nanoTime() + "@onb.test", m2 = "s4b-" + System.nanoTime() + "@onb.test";
        var f1 = CompletableFuture.supplyAsync(() -> signupUnchecked(shop, m1));
        var f2 = CompletableFuture.supplyAsync(() -> signupUnchecked(shop, m2));
        List<HttpStatusCode> codes = List.of(f1.get().getStatusCode(), f2.get().getStatusCode());

        assertThat(codes).containsExactlyInAnyOrder(HttpStatus.OK, HttpStatus.CONFLICT);
        assertThat(owner.queryForObject("SELECT count(*) FROM stores WHERE shop_domain = ?", Integer.class, shop)).isEqualTo(1);
        assertThat(owner.queryForObject("SELECT count(*) FROM users WHERE email IN (?, ?)", Integer.class, m1, m2)).isEqualTo(1);
        assertThat(owner.queryForObject("SELECT count(*) FROM tenants t WHERE NOT EXISTS (SELECT 1 FROM users u WHERE u.tenant_id = t.id)",
            Integer.class)).as("no orphan tenant").isZero();
    }

    // ── s5 ────────────────────────────────────────────────────────────────────

    @Test
    void s5_duplicateEmail_409_webSignupWording_nothingCreated() {
        String shop = newShop("s5");
        String existing = owner.queryForObject("SELECT email FROM users WHERE id = ?", String.class, freeOwner);
        ResponseEntity<Map> r = signup(shop, existing, "Dup Co");
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(r.getBody()).containsEntry("code", "EMAIL_TAKEN")
            .containsEntry("message_en", "This email is already registered. Sign in instead.")
            .containsEntry("message_ar", "هذا البريد الإلكتروني مسجل بالفعل. قم بتسجيل الدخول.");
        assertThat(owner.queryForObject("SELECT count(*) FROM stores WHERE shop_domain = ?", Integer.class, shop)).isZero();
        assertThat(owner.queryForObject("SELECT count(*) FROM tenants WHERE name = 'Dup Co'", Integer.class)).isZero();
        verify(jobs, never()).enqueue(any(JobLambda.class));
    }

    // ── s6 ────────────────────────────────────────────────────────────────────

    @Test
    void s6_signInLink_worksOnce_expires_neverIssuedElsewhere() throws Exception {
        int tokensBefore = owner.queryForObject("SELECT count(*) FROM magic_link_tokens", Integer.class);
        // Prefill, pending link and failed signups issue none.
        String other = newShop("s6x");
        assertThat(embedded(HttpMethod.GET, "/api/v1/embedded/onboarding/prefill", other, null).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(embedded(HttpMethod.POST, "/api/v1/embedded/onboarding/pending-link", other, null).getStatusCode()).isEqualTo(HttpStatus.OK);
        post(other, "/signup", form("s6x@onb.test", "Co", "short", "01012345678", true));
        assertThat(owner.queryForObject("SELECT count(*) FROM magic_link_tokens", Integer.class)).isEqualTo(tokensBefore);

        String url = (String) signup(newShop("s6"), "s6-" + System.nanoTime() + "@onb.test", "Co").getBody().get("signInUrl");
        String path = url.substring(appUrl.length());
        ResponseEntity<String> first = http.getForEntity(base() + path, String.class);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.FOUND);
        assertThat(first.getHeaders().get(HttpHeaders.SET_COOKIE)).anyMatch(c -> c.startsWith("traced_refresh="));
        assertThat(http.getForEntity(base() + path, String.class).getStatusCode()).as("single use").isEqualTo(HttpStatus.UNAUTHORIZED);

        String url2 = (String) signup(newShop("s6b"), "s6b-" + System.nanoTime() + "@onb.test", "Co").getBody().get("signInUrl");
        String raw2 = url2.substring(url2.indexOf("token=") + 6);
        owner.update("UPDATE magic_link_tokens SET expires_at = now() - interval '1 second' WHERE token_hash = ?",
            AuthRepository.sha256(raw2));
        assertThat(http.getForEntity(base() + url2.substring(appUrl.length()), String.class).getStatusCode())
            .as("expired").isEqualTo(HttpStatus.UNAUTHORIZED);

        // Source guard: the sign-in link is issued from the embedded signup only.
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            List<String> callers = files.filter(p -> p.toString().endsWith(".java"))
                .filter(p -> { try { return Files.readString(p).contains("issueSignInLink("); } catch (Exception e) { return false; } })
                .map(p -> p.getFileName().toString()).sorted().toList();
            assertThat(callers).containsExactly("EmbeddedOnboardingService.java", "MagicLinkService.java");
        }
    }

    // ── s7 ────────────────────────────────────────────────────────────────────

    @Test
    void s7_rateLimited_perShop() throws Exception {
        String shop = newShop("s7");
        for (int i = 0; i < 30; i++) {
            assertThat(embedded(HttpMethod.GET, "/api/v1/embedded/onboarding/prefill", shop, null).getStatusCode()).isEqualTo(HttpStatus.OK);
        }
        ResponseEntity<String> r = embedded(HttpMethod.GET, "/api/v1/embedded/onboarding/prefill", shop, null);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(r.getBody()).contains("RATE_LIMITED");
        // The ledger holds no IP in clear.
        assertThat(owner.queryForList("SELECT bucket FROM embedded_onboarding_attempts", String.class))
            .noneMatch(b -> b.contains("127.0.0.1") || b.contains("0:0:0:0"));
    }

    // ── p1 ────────────────────────────────────────────────────────────────────

    @Test
    void p1_pendingLink_onlyForVerifiedUnlinkedShop_storedHashedAndEncrypted() throws Exception {
        int before = owner.queryForObject("SELECT count(*) FROM shopify_pending_links", Integer.class);
        String shop = newShop("p1");
        HttpHeaders bad = json();
        bad.setBearerAuth(makeToken(shop, clientId, "wrong-secret-but-long-enough-for-hs256-0123456789ab", 60, false));
        assertThat(http.exchange(base() + "/api/v1/embedded/onboarding/pending-link", HttpMethod.POST,
            new HttpEntity<>(null, bad), String.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(owner.queryForObject("SELECT count(*) FROM shopify_pending_links", Integer.class)).isEqualTo(before);

        String nonce = pendingLink(shop);
        Map<String, Object> row = owner.queryForMap(
            "SELECT shop_domain, access_token_encrypted, refresh_token_encrypted, " +
            "       expires_at BETWEEN now() + interval '14 minutes' AND now() + interval '15 minutes 5 seconds' AS ttl_ok, " +
            "       consumed_at FROM shopify_pending_links WHERE nonce_hash = ?", AuthRepository.sha256(nonce));
        assertThat(row).containsEntry("shop_domain", shop).containsEntry("ttl_ok", true);
        assertThat(row.get("consumed_at")).isNull();
        assertThat((String) row.get("access_token_encrypted")).doesNotContain(ACCESS);
        assertThat(encryption.decrypt((String) row.get("access_token_encrypted"))).isEqualTo(ACCESS);
        assertThat(owner.queryForObject("SELECT count(*) FROM shopify_pending_links WHERE nonce_hash = ?", Integer.class, nonce))
            .as("the raw nonce is never stored").isZero();
        verify(jobs, never()).enqueue(any(JobLambda.class));
        assertThat(owner.queryForObject("SELECT count(*) FROM stores WHERE shop_domain = ?", Integer.class, shop)).as("no store yet").isZero();
    }

    // ── p2 ────────────────────────────────────────────────────────────────────

    @Test
    void p2_confirm_linksToOwnersTenant_redirectsToShopifyAdmin_singleUse_expiry() throws Exception {
        UUID tenant = tenant("P2 Co");
        UUID p2Owner = user(tenant, "owner", "p2-" + System.nanoTime() + "@onb.test");
        String shop = newShop("p2");
        String nonce = pendingLink(shop);
        String jwt = login(p2Owner);

        ResponseEntity<Map> preview = traced(jwt, "/preview", nonce);
        assertThat(preview.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(preview.getBody()).containsEntry("shopDomain", shop).containsEntry("businessName", "P2 Co");

        ResponseEntity<Map> ok = traced(jwt, "/confirm", nonce);
        assertThat(ok.getStatusCode()).as("%s", ok.getBody()).isEqualTo(HttpStatus.OK);
        String handle = shop.substring(0, shop.indexOf('.'));
        assertThat(ok.getBody()).containsEntry("redirectUrl",
            "https://admin.shopify.com/store/" + handle + "/apps/trace-3?traced_connected=1");

        Map<String, Object> store = owner.queryForMap(
            "SELECT tenant_id, connection_type, status::text AS status, access_token_encrypted FROM stores WHERE shop_domain = ?", shop);
        assertThat(store.get("tenant_id")).isEqualTo(tenant);
        assertThat(store).containsEntry("connection_type", "oauth").containsEntry("status", "connected");
        assertThat(encryption.decrypt((String) store.get("access_token_encrypted"))).isEqualTo(ACCESS);
        verify(jobs, times(2)).enqueue(any(JobLambda.class));   // import + webhooks
        Map<String, Object> used = owner.queryForMap(
            "SELECT consumed_at, consumed_by_tenant, access_token_encrypted, refresh_token_encrypted " +
            "FROM shopify_pending_links WHERE nonce_hash = ?", AuthRepository.sha256(nonce));
        assertThat(used.get("consumed_at")).isNotNull();
        assertThat(used.get("consumed_by_tenant")).isEqualTo(tenant);
        assertThat(used.get("access_token_encrypted")).isNull();
        assertThat(used.get("refresh_token_encrypted")).isNull();

        // Single use.
        ResponseEntity<Map> again = traced(jwt, "/confirm", nonce);
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.GONE);
        assertThat(again.getBody()).containsEntry("code", "PENDING_LINK_INVALID");
        assertThat(traced(jwt, "/preview", nonce).getStatusCode()).isEqualTo(HttpStatus.GONE);

        // Expired.
        String shop2 = newShop("p2e");
        String nonce2 = pendingLink(shop2);
        owner.update("UPDATE shopify_pending_links SET expires_at = now() - interval '1 second' WHERE nonce_hash = ?",
            AuthRepository.sha256(nonce2));
        UUID t2 = tenant("P2E Co");
        String jwt2 = login(user(t2, "owner", "p2e-" + System.nanoTime() + "@onb.test"));
        assertThat(traced(jwt2, "/preview", nonce2).getStatusCode()).isEqualTo(HttpStatus.GONE);
        assertThat(traced(jwt2, "/confirm", nonce2).getStatusCode()).isEqualTo(HttpStatus.GONE);
        assertThat(owner.queryForObject("SELECT count(*) FROM stores WHERE shop_domain = ?", Integer.class, shop2)).isZero();
        // Unknown / garbage.
        assertThat(traced(jwt2, "/confirm", "not-a-real-link").getStatusCode()).isEqualTo(HttpStatus.GONE);
    }

    @Test
    void p2b_concurrentConfirms_oneLinkUse_oneStore() throws Exception {
        String shop = newShop("p2b");
        String nonce = pendingLink(shop);
        String j1 = login(user(tenant("P2B One"), "owner", "p2b1-" + System.nanoTime() + "@onb.test"));
        String j2 = login(user(tenant("P2B Two"), "owner", "p2b2-" + System.nanoTime() + "@onb.test"));
        var f1 = CompletableFuture.supplyAsync(() -> traced(j1, "/confirm", nonce).getStatusCode());
        var f2 = CompletableFuture.supplyAsync(() -> traced(j2, "/confirm", nonce).getStatusCode());
        List<HttpStatusCode> codes = List.of(f1.get(), f2.get());
        assertThat(codes).contains(HttpStatus.OK).hasSize(2).filteredOn(c -> c.equals(HttpStatus.OK)).hasSize(1);
        assertThat(owner.queryForObject("SELECT count(*) FROM stores WHERE shop_domain = ?", Integer.class, shop)).isEqualTo(1);
    }

    // ── p3 ────────────────────────────────────────────────────────────────────

    @Test
    void p3_refused_forNonOwner_andForTenantBoundToAnotherShop_linkStaysUsable() throws Exception {
        String shop = newShop("p3");
        String nonce = pendingLink(shop);

        // A manager — even of a tenant — can't preview or confirm.
        String mgr = login(linkedManager);
        assertThat(traced(mgr, "/preview", nonce).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(traced(mgr, "/confirm", nonce).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        // The owner of a tenant already bound to another shop: same-shop rule, names the linked shop.
        String bound = login(linkedOwner);
        ResponseEntity<Map> r = traced(bound, "/confirm", nonce);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(r.getBody()).containsEntry("code", "SHOPIFY_SHOP_MISMATCH");
        assertThat((String) r.getBody().get("message_en")).contains(linkedShop);
        assertThat(owner.queryForObject("SELECT count(*) FROM stores WHERE shop_domain = ?", Integer.class, shop)).isZero();

        // Not consumed by the refusals: the right owner can still use it.
        assertThat(owner.queryForObject("SELECT consumed_at IS NULL FROM shopify_pending_links WHERE nonce_hash = ?",
            Boolean.class, AuthRepository.sha256(nonce))).isTrue();
        UUID t = tenant("P3 Right Co");
        assertThat(traced(login(user(t, "owner", "p3-" + System.nanoTime() + "@onb.test")), "/confirm", nonce).getStatusCode())
            .isEqualTo(HttpStatus.OK);

        // A shop linked to someone else meanwhile → SHOP_LINKED_ELSEWHERE for another owner's link.
        String shop2 = newShop("p3b");
        String nonce2 = pendingLink(shop2);
        owner.update("INSERT INTO stores (tenant_id, shop_domain, status, import_status) VALUES (?, ?, 'connected', 'completed')",
            linkedTenant, shop2);
        ResponseEntity<Map> elsewhere = traced(login(freeOwner), "/confirm", nonce2);
        assertThat(elsewhere.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(elsewhere.getBody()).containsEntry("code", "SHOP_LINKED_ELSEWHERE");
        owner.update("DELETE FROM stores WHERE shop_domain = ?", shop2);
    }

    // ── p4 ────────────────────────────────────────────────────────────────────

    @Test
    void p4_tokenNeverInUrlsOrLogs(CapturedOutput output) throws Exception {
        String shop = newShop("p4");
        ResponseEntity<Map> created = embeddedMap(HttpMethod.POST, "/api/v1/embedded/onboarding/pending-link", shop);
        String url = (String) created.getBody().get("url");
        assertThat(url).startsWith(appUrl + "/connect/shopify?link=");
        String nonce = url.substring(url.indexOf("link=") + 5);
        assertThat(url).doesNotContain(ACCESS).doesNotContain(REFRESH).doesNotContain(shop);

        UUID t = tenant("P4 Co");
        ResponseEntity<Map> ok = traced(login(user(t, "owner", "p4-" + System.nanoTime() + "@onb.test")), "/confirm", nonce);
        assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((String) ok.getBody().get("redirectUrl")).doesNotContain(ACCESS).doesNotContain(REFRESH).doesNotContain(nonce);

        String signInUrl = (String) signup(newShop("p4s"), "p4s-" + System.nanoTime() + "@onb.test", "Co").getBody().get("signInUrl");
        String signInToken = signInUrl.substring(signInUrl.indexOf("token=") + 6);

        assertThat(output.getAll()).doesNotContain(ACCESS).doesNotContain(REFRESH)
            .doesNotContain(nonce).doesNotContain(signInToken).doesNotContain("pass1234");
    }

    // ── p5 ────────────────────────────────────────────────────────────────────

    @Test
    void p5_rls_pendingLinkVisibleOnlyWithItsNonceHash() throws Exception {
        String nonce = pendingLink(newShop("p5"));
        String hash = AuthRepository.sha256(nonce);
        TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(appUserDs));
        JdbcTemplate app = new JdbcTemplate(appUserDs);

        Integer none = tx.execute(s -> app.queryForObject("SELECT count(*) FROM shopify_pending_links", Integer.class));
        assertThat(none).as("no GUC → no rows").isZero();
        Integer wrong = tx.execute(s -> {
            app.queryForObject("SELECT set_config('app.pending_link', ?, true)", String.class, AuthRepository.sha256("other"));
            return app.queryForObject("SELECT count(*) FROM shopify_pending_links", Integer.class);
        });
        assertThat(wrong).isZero();
        Integer right = tx.execute(s -> {
            app.queryForObject("SELECT set_config('app.pending_link', ?, true)", String.class, hash);
            return app.queryForObject("SELECT count(*) FROM shopify_pending_links", Integer.class);
        });
        assertThat(right).isEqualTo(1);
        // DELETE is not granted; an INSERT for a hash other than the GUC's is refused.
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> tx.execute(s -> {
            app.queryForObject("SELECT set_config('app.pending_link', ?, true)", String.class, hash);
            return app.update("DELETE FROM shopify_pending_links");
        })).rootCause().hasMessageContaining("permission denied");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> tx.execute(s -> {
            app.queryForObject("SELECT set_config('app.pending_link', ?, true)", String.class, hash);
            return app.update("INSERT INTO shopify_pending_links (nonce_hash, shop_domain, expires_at) VALUES ('x', 'y.myshopify.com', now())");
        })).rootCause().hasMessageContaining("row-level security");
    }

    // ── p6 ────────────────────────────────────────────────────────────────────

    @Test
    void p6_consumeNullsTheToken_appUserMayUpdateOnlyTheConsumeColumns() throws Exception {
        String shop = newShop("p6");
        String nonce = pendingLink(shop);
        String hash = AuthRepository.sha256(nonce);
        TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(appUserDs));
        JdbcTemplate app = new JdbcTemplate(appUserDs);

        // Outside the granted columns → refused.
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> tx.execute(s -> {
            app.queryForObject("SELECT set_config('app.pending_link', ?, true)", String.class, hash);
            return app.update("UPDATE shopify_pending_links SET expires_at = now() + interval '1 day'");
        })).rootCause().hasMessageContaining("permission denied");
        // Marking it used while keeping the token → refused by the table (token must be nulled with it).
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> tx.execute(s -> {
            app.queryForObject("SELECT set_config('app.pending_link', ?, true)", String.class, hash);
            return app.update("UPDATE shopify_pending_links SET consumed_at = now()");
        })).rootCause().hasMessageContaining("shopify_pending_links_token_cleared");

        // The real consume: same statement nulls both tokens.
        UUID t = tenant("P6 Co");
        assertThat(traced(login(user(t, "owner", "p6-" + System.nanoTime() + "@onb.test")), "/confirm", nonce).getStatusCode())
            .isEqualTo(HttpStatus.OK);
        Map<String, Object> row = owner.queryForMap(
            "SELECT consumed_at, access_token_encrypted, refresh_token_encrypted FROM shopify_pending_links WHERE nonce_hash = ?", hash);
        assertThat(row.get("consumed_at")).isNotNull();
        assertThat(row.get("access_token_encrypted")).isNull();
        assertThat(row.get("refresh_token_encrypted")).isNull();
    }

    // ── p7 ────────────────────────────────────────────────────────────────────

    @Test
    void p7_purge_deletesOnlyExpiredOrConsumedLinksAndOldAttempts_appUserCannotDelete() {
        String live = "live-" + System.nanoTime(), expired = "exp-" + System.nanoTime(), used = "used-" + System.nanoTime();
        owner.update("INSERT INTO shopify_pending_links (nonce_hash, shop_domain, access_token_encrypted, expires_at) " +
            "VALUES (?, 'live.myshopify.com', 'enc', now() + interval '10 minutes')", live);
        owner.update("INSERT INTO shopify_pending_links (nonce_hash, shop_domain, access_token_encrypted, expires_at) " +
            "VALUES (?, 'exp.myshopify.com', 'enc', now() - interval '1 minute')", expired);
        owner.update("INSERT INTO shopify_pending_links (nonce_hash, shop_domain, expires_at, consumed_at) " +
            "VALUES (?, 'used.myshopify.com', now() + interval '10 minutes', now())", used);
        String oldBucket = "shop:old-" + System.nanoTime(), newBucket = "shop:new-" + System.nanoTime();
        owner.update("INSERT INTO embedded_onboarding_attempts (bucket, attempted_at) VALUES (?, now() - interval '25 hours')", oldBucket);
        owner.update("INSERT INTO embedded_onboarding_attempts (bucket, attempted_at) VALUES (?, now() - interval '23 hours')", newBucket);

        JdbcTemplate app = new JdbcTemplate(appUserDs);
        // The app role can't delete from either table directly.
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> app.update("DELETE FROM shopify_pending_links"))
            .rootCause().hasMessageContaining("permission denied");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> app.update("DELETE FROM embedded_onboarding_attempts"))
            .rootCause().hasMessageContaining("permission denied");

        // The nightly job, on the app's own (app_user) pool → hatch #16.
        new com.traceability.onboarding.OnboardingPurgeJob(appJdbc).purge();

        List<String> links = owner.queryForList("SELECT nonce_hash FROM shopify_pending_links WHERE nonce_hash IN (?, ?, ?)",
            String.class, live, expired, used);
        assertThat(links).containsExactly(live);
        List<String> buckets = owner.queryForList("SELECT bucket FROM embedded_onboarding_attempts WHERE bucket IN (?, ?)",
            String.class, oldBucket, newBucket);
        assertThat(buckets).containsExactly(newBucket);
        assertThat(owner.queryForObject("SELECT count(*) FROM shopify_pending_links WHERE consumed_at IS NOT NULL OR expires_at < now()",
            Integer.class)).as("nothing expired or consumed left").isZero();

        // Hatch #5 is gone.
        assertThat(owner.queryForObject("SELECT to_regprocedure('provision_tenant_from_shopify(text,text,text,text,text)') IS NULL",
            Boolean.class)).isTrue();
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private String base() { return "http://localhost:" + port; }

    private static String newShop(String tag) {
        return "onb-" + tag + "-" + System.nanoTime() + ".myshopify.com";
    }

    private UUID tenant(String name) {
        UUID id = UUID.randomUUID();
        owner.update("INSERT INTO tenants (id, name) VALUES (?, ?)", id, name);
        return id;
    }

    private UUID user(UUID tenant, String role, String mail) {
        UUID id = UUID.randomUUID();
        owner.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role) VALUES (?, ?, 'U', ?, ?, ?::user_role)",
            id, tenant, mail, passwordEncoder.encode("pass123"), role);
        return id;
    }

    @SuppressWarnings("unchecked")
    private String login(UUID userId) {
        String mail = owner.queryForObject("SELECT email FROM users WHERE id = ?", String.class, userId);
        ResponseEntity<Map> resp = http.postForEntity(base() + "/api/v1/auth/login",
            new HttpEntity<>(Map.of("email", mail, "password", "pass123"), json()), Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        return (String) resp.getBody().get("accessToken");
    }

    private static HttpHeaders json() {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    private static Map<String, Object> form(String mail, String tenantName, String password, String phone, boolean consent) {
        Map<String, Object> f = new HashMap<>();
        f.put("tenantName", tenantName);
        f.put("name", "Mona Adel");
        f.put("email", mail);
        f.put("phone", phone);
        f.put("password", password);
        f.put("consent", consent);
        return f;
    }

    private ResponseEntity<String> embedded(HttpMethod method, String path, String shop, Object body) throws Exception {
        HttpHeaders h = json();
        h.setBearerAuth(makeToken(shop, clientId, clientSecret, 60, false));
        return http.exchange(base() + path, method, new HttpEntity<>(body, h), String.class);
    }

    @SuppressWarnings("unchecked")
    private ResponseEntity<Map> embeddedMap(HttpMethod method, String path, String shop) throws Exception {
        HttpHeaders h = json();
        h.setBearerAuth(makeToken(shop, clientId, clientSecret, 60, false));
        return http.exchange(base() + path, method, new HttpEntity<>(null, h), Map.class);
    }

    private ResponseEntity<String> post(String shop, String sub, Map<String, Object> body) throws Exception {
        return embedded(HttpMethod.POST, "/api/v1/embedded/onboarding" + sub, shop, body);
    }

    @SuppressWarnings("unchecked")
    private ResponseEntity<Map> signup(String shop, String mail, String tenantName) {
        try {
            HttpHeaders h = json();
            h.setBearerAuth(makeToken(shop, clientId, clientSecret, 60, false));
            return http.exchange(base() + "/api/v1/embedded/onboarding/signup", HttpMethod.POST,
                new HttpEntity<>(form(mail, tenantName, "pass1234", "010 1234 5678", true), h), Map.class);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private ResponseEntity<Map> signupUnchecked(String shop, String mail) {
        return signup(shop, mail, "Race Co");
    }

    private String pendingLink(String shop) throws Exception {
        ResponseEntity<Map> r = embeddedMap(HttpMethod.POST, "/api/v1/embedded/onboarding/pending-link", shop);
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.OK);
        String url = (String) r.getBody().get("url");
        return url.substring(url.indexOf("link=") + 5);
    }

    @SuppressWarnings("unchecked")
    private ResponseEntity<Map> traced(String jwt, String sub, String nonce) {
        HttpHeaders h = json();
        h.setBearerAuth(jwt);
        return http.exchange(base() + "/api/v1/shopify/pending-link" + sub, HttpMethod.POST,
            new HttpEntity<>(Map.of("link", nonce), h), Map.class);
    }
}
