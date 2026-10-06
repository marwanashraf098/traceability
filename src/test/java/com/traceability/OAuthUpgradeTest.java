package com.traceability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.traceability.identity.model.TokenResponse;
import com.traceability.integrations.bosta.BostaGateway;
import com.traceability.integrations.bosta.BostaV2Client;
import com.traceability.integrations.shopify.ShopifyGateway;
import com.traceability.integrations.shopify.ShopifyLocationGateway;
import com.traceability.notifications.EmailGateway;
import com.traceability.security.EncryptionService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.DefaultResponseErrorHandler;
import org.springframework.web.client.RestTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Build C — custom_app_cc → official OAuth upgrade, end to end, with the WHOLE app on app_user (RLS ON for
 * every request, filter and job — the production wiring) and a real JobRunr background server running the
 * enqueued import / webhook jobs. Mocked: only the outside world — ShopifyGateway, ShopifyLocationGateway,
 * BostaGateway / BostaV2Client, EmailGateway.
 *
 * How the app_user-primary context boots (ShopifyConnectAmbientContextTest found it couldn't): app_user is
 * created WITH its test password before Spring starts, so the primary pool can connect while Flyway (owner
 * pool) migrates; V1's "create app_user if missing" leaves it alone.
 *
 *   u1 — e2e upgrade: owner logs in → POST /oauth/initiate (TenantContext from the request filter, same-shop
 *        guard passes under RLS) → signed callback → the old app's Traced subscriptions are deleted with the
 *        OLD token (a foreign one is left), same stores row / tenant, connection_type oauth, custom-app secrets
 *        cleared, outcome recorded, scope cache dropped → the real job server runs the import (order upserted
 *        with its customer name) and registers the 6 topics with the NEW token → tenant-scoped reads return
 *        the tenant's rows; existing data untouched.
 *   u2 — after the flip, an app/uninstalled signed by the OLD custom app is rejected (401) and the store stays
 *        connected.
 *   u3 — the trap: the merchant uninstalls the custom app BEFORE upgrading → its app/uninstalled disconnects the
 *        store; Settings → Connections reports it disconnected with the shop; recovery = Connect with Shopify:
 *        the same row comes back connected on OAuth, the cleanup is recorded as failed (the old app is gone).
 *   u4 — an expired custom-app token: the cleanup re-exchanges with the stored Client ID / Secret.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
// This context runs a real JobRunr background server — close it after the class so it never keeps polling
// (recurring jobs included) while later test classes run.
@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class OAuthUpgradeTest {

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
        r.add("org.jobrunr.background-job-server.enabled", () -> "true");
        r.add("org.jobrunr.background-job-server.poll-interval-in-seconds", () -> "5");
        r.add("traced.catalog-backfill.enabled", () -> "false");
    }

    static final String OLD_TOKEN = "shpat_old_custom_app";
    static final String OLD_CLIENT_ID = "old-client-id";
    static final String OLD_SECRET = "old-custom-app-secret";
    static final String NEW_TOKEN = "shpat_new_official";

    @LocalServerPort int port;
    @Autowired EncryptionService encryption;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate appJdbc;   // the app's own @Primary pool
    @Value("${shopify.client-secret}") String officialSecret;
    @Value("${shopify.webhook-base-url}") String webhookBase;

    @MockBean ShopifyGateway shopify;
    @MockBean ShopifyLocationGateway locations;
    @MockBean BostaGateway bosta;
    @MockBean BostaV2Client bostaV2;
    @MockBean EmailGateway email;

    JdbcTemplate owner;   // postgres — fixtures and assertions only
    RestTemplate http;

    record Shop(UUID tenant, UUID store, UUID ownerUser, String domain, UUID existingOrder) {}

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
        reset(shopify);
        when(shopify.fetchProductsPage(anyString(), eq(NEW_TOKEN), isNull()))
            .thenReturn(new ShopifyGateway.ProductPage(List.of(), false, null));
        when(shopify.listWebhookSubscriptions(anyString(), eq(NEW_TOKEN))).thenReturn(List.of());
        when(shopify.exchangeCode(anyString(), anyString()))
            .thenReturn(new ShopifyGateway.TokenResponse(NEW_TOKEN, "shprt_new", 3600L, 7776000L,
                "read_products,read_orders,read_fulfillments,read_customers,write_inventory,write_locations,write_shipping"));
    }

    // ── u1 ────────────────────────────────────────────────────────────────────

    @Test
    void u1_endToEnd_customAppStore_upgradesInPlace_oldWebhooksDeleted_importRuns() throws Exception {
        assertThat(appJdbc.queryForObject("SELECT current_user || ':' || rolbypassrls FROM pg_roles WHERE rolname = current_user",
            String.class)).as("the whole app runs as app_user, RLS not bypassable").isEqualTo("app_user:false");
        Shop s = customAppShop("u1", Instant.now().plus(Duration.ofHours(10)));
        String ours = webhookBase + "/webhooks/shopify/";
        when(shopify.listWebhookSubscriptions(s.domain(), OLD_TOKEN)).thenReturn(List.of(
            new ShopifyGateway.WebhookSubscription("gid://shopify/WebhookSubscription/1", "ORDERS_CREATE", ours + "orders/create"),
            new ShopifyGateway.WebhookSubscription("gid://shopify/WebhookSubscription/2", "APP_UNINSTALLED", ours + "app/uninstalled"),
            new ShopifyGateway.WebhookSubscription("gid://shopify/WebhookSubscription/9", "ORDERS_CREATE", "https://elsewhere.example/hook")));
        String importedGid = "gid://shopify/Order/" + System.nanoTime();
        when(shopify.fetchOrdersPage(eq(s.domain()), eq(NEW_TOKEN), isNull(), anyString()))
            .thenReturn(new ShopifyGateway.OrderPage(List.of(new ShopifyGateway.Order(importedGid, "#U1-NEW", null, null, null,
                "pending", List.of(), new BigDecimal("120.00"), List.of(), Instant.now(),
                mapper.readTree("{\"id\":\"" + importedGid + "\",\"shippingAddress\":{\"name\":\"Mona Upgraded\",\"city\":\"Cairo\"}}"))),
                false, null));
        Map<String, Object> existingBefore = owner.queryForMap("SELECT * FROM orders WHERE id = ?", s.existingOrder());

        String token = login(s.ownerUser());
        String nonce = initiate(token, s.domain());
        ResponseEntity<Void> cb = callback(nonce, s.domain(), "code-u1");
        assertThat(cb.getStatusCode()).isEqualTo(HttpStatus.FOUND);

        Map<String, Object> row = owner.queryForMap("SELECT * FROM stores WHERE shop_domain = ?", s.domain());
        assertThat(row.get("id")).as("same stores row").isEqualTo(s.store());
        assertThat(row.get("tenant_id")).as("same tenant").isEqualTo(s.tenant());
        assertThat(row).containsEntry("connection_type", "oauth").containsEntry("status", "connected")
            .containsEntry("api_secret_encrypted", null).containsEntry("client_id_encrypted", null)
            .containsEntry("oauth_upgraded_from", "custom_app_cc").containsEntry("legacy_webhook_cleanup_status", "done");
        assertThat((String) row.get("legacy_webhook_cleanup_detail")).contains("deleted 2").contains("1 pointing elsewhere");
        assertThat(encryption.decrypt((String) row.get("access_token_encrypted"))).isEqualTo(NEW_TOKEN);

        verify(shopify).deleteWebhookSubscription(s.domain(), OLD_TOKEN, "gid://shopify/WebhookSubscription/1");
        verify(shopify).deleteWebhookSubscription(s.domain(), OLD_TOKEN, "gid://shopify/WebhookSubscription/2");
        verify(shopify, never()).deleteWebhookSubscription(anyString(), anyString(), eq("gid://shopify/WebhookSubscription/9"));
        verify(shopify, atLeastOnce()).forgetOrderPiiTier(s.domain());

        // The real JobRunr server runs what the callback enqueued: import + webhook registration (NEW token).
        await(() -> "completed".equals(owner.queryForObject(
            "SELECT import_status::text FROM stores WHERE id = ?", String.class, s.store())));
        await(() -> mockingDetails(shopify).getInvocations().stream()
            .filter(i -> i.getMethod().getName().equals("registerWebhook")).count() >= 6);
        for (String topic : List.of("orders/create", "orders/updated", "orders/cancelled", "products/create",
                                    "products/update", "app/uninstalled")) {
            verify(shopify).registerWebhook(s.domain(), NEW_TOKEN, topic, ours + topic);
        }
        assertThat(owner.queryForMap("SELECT tenant_id, store_id, customer_name FROM orders WHERE external_id = ?", importedGid))
            .containsEntry("tenant_id", s.tenant()).containsEntry("store_id", s.store())
            .containsEntry("customer_name", "Mona Upgraded");
        assertThat(owner.queryForMap("SELECT * FROM orders WHERE id = ?", s.existingOrder()))
            .as("existing data untouched").isEqualTo(existingBefore);

        // Tenant-scoped reads over HTTP, as app_user under RLS: the tenant's rows, not zero.
        ResponseEntity<String> stores = get("/api/v1/shopify/stores", token);
        assertThat(stores.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(stores.getBody()).contains(s.store().toString());
        assertThat(get("/api/v1/orders?q=U1", token).getBody()).contains("#U1-OLD").contains("#U1-NEW");
    }

    // ── u2 ────────────────────────────────────────────────────────────────────

    @Test
    void u2_afterTheFlip_oldAppUninstall_cannotDisconnect() throws Exception {
        Shop s = customAppShop("u2", Instant.now().plus(Duration.ofHours(10)));
        when(shopify.listWebhookSubscriptions(s.domain(), OLD_TOKEN)).thenReturn(List.of());
        callback(initiate(login(s.ownerUser()), s.domain()), s.domain(), "code-u2");
        assertThat(owner.queryForObject("SELECT connection_type FROM stores WHERE id = ?", String.class, s.store()))
            .isEqualTo("oauth");

        ResponseEntity<Void> r = postWebhook("app/uninstalled", s.domain(), OLD_SECRET, "{\"id\":1}");

        assertThat(r.getStatusCode()).as("phase-B HMAC is gone with the custom-app secret").isEqualTo(HttpStatus.UNAUTHORIZED);
        Thread.sleep(6000);   // one job-server poll: nothing may have been enqueued
        assertThat(owner.queryForObject("SELECT status::text FROM stores WHERE id = ?", String.class, s.store()))
            .isEqualTo("connected");
        assertThat(owner.queryForObject("SELECT count(*) FROM shopify_webhook_events WHERE tenant_id = ?",
            Integer.class, s.tenant())).isZero();
    }

    // ── u3 ────────────────────────────────────────────────────────────────────

    @Test
    void u3_trap_uninstallBeforeUpgrade_disconnects_thenConnectWithShopifyRecovers() throws Exception {
        Shop s = customAppShop("u3", Instant.now().plus(Duration.ofHours(10)));

        // The merchant deletes the custom app first: its app/uninstalled is still signed by a secret we hold.
        ResponseEntity<Void> r = postWebhook("app/uninstalled", s.domain(), OLD_SECRET, "{\"id\":1}");
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        await(() -> "disconnected".equals(owner.queryForObject(
            "SELECT status::text FROM stores WHERE id = ?", String.class, s.store())));

        String token = login(s.ownerUser());
        @SuppressWarnings("unchecked")
        Map<String, Object> shopifyCard = (Map<String, Object>) mapper.readValue(
            get("/api/v1/connections", token).getBody(), Map.class).get("shopify");
        assertThat(shopifyCard).containsEntry("connected", false).containsEntry("status", "disconnected")
            .containsEntry("shopDomain", s.domain()).containsEntry("connectionType", "custom_app_cc");

        // Recovery: Settings → Connections → "Connect with Shopify" (OAuth). The old app is gone → its token fails.
        when(shopify.listWebhookSubscriptions(s.domain(), OLD_TOKEN))
            .thenThrow(new com.traceability.integrations.shopify.ShopifyException("HTTP 401 Invalid API key or access token"));
        ResponseEntity<Void> cb = callback(initiate(token, s.domain()), s.domain(), "code-u3");

        assertThat(cb.getStatusCode()).isEqualTo(HttpStatus.FOUND);
        Map<String, Object> row = owner.queryForMap("SELECT * FROM stores WHERE shop_domain = ?", s.domain());
        assertThat(row.get("id")).isEqualTo(s.store());
        assertThat(row).containsEntry("status", "connected").containsEntry("connection_type", "oauth")
            .containsEntry("api_secret_encrypted", null).containsEntry("legacy_webhook_cleanup_status", "failed");
        assertThat((String) row.get("legacy_webhook_cleanup_detail")).contains("could not list");
    }

    // ── u4 ────────────────────────────────────────────────────────────────────

    @Test
    void u4_expiredCustomAppToken_cleanupReExchangesWithStoredCredentials() throws Exception {
        Shop s = customAppShop("u4", Instant.now().minus(Duration.ofHours(1)));
        when(shopify.exchangeClientCredentials(s.domain(), OLD_CLIENT_ID, OLD_SECRET))
            .thenReturn(new ShopifyGateway.TokenResponse("shpat_reexchanged", null, 86399L, 0L, null));
        when(shopify.listWebhookSubscriptions(s.domain(), "shpat_reexchanged")).thenReturn(List.of(
            new ShopifyGateway.WebhookSubscription("gid://shopify/WebhookSubscription/41", "ORDERS_UPDATED",
                webhookBase + "/webhooks/shopify/orders/updated")));

        callback(initiate(login(s.ownerUser()), s.domain()), s.domain(), "code-u4");

        verify(shopify).deleteWebhookSubscription(s.domain(), "shpat_reexchanged", "gid://shopify/WebhookSubscription/41");
        assertThat(owner.queryForObject("SELECT legacy_webhook_cleanup_status FROM stores WHERE id = ?", String.class, s.store()))
            .isEqualTo("done");
    }

    // ── fixtures ──────────────────────────────────────────────────────────────

    private Shop customAppShop(String tag, Instant tokenExpiresAt) {
        UUID tenant = UUID.randomUUID(), store = UUID.randomUUID(), user = UUID.randomUUID();
        String domain = "upgrade-" + tag + "-" + tenant.toString().substring(0, 6) + ".myshopify.com";
        owner.update("INSERT INTO tenants (id, name) VALUES (?, ?)", tenant, "Upgrade " + tag);
        owner.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role, active) " +
                     "VALUES (?, ?, 'Owner', ?, ?, 'owner', true)",
            user, tenant, "owner-" + user + "@test.local", passwordEncoder.encode("pass123"));
        owner.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status, import_status, connection_type, " +
                     "    access_token_encrypted, access_token_expires_at, client_id_encrypted, api_secret_encrypted, " +
                     "    orders_ingest_from) " +
                     "VALUES (?, ?, 'shopify', ?, 'connected', 'completed', 'custom_app_cc', ?, ?, ?, ?, now() - interval '30 days')",
            store, tenant, domain, encryption.encrypt(OLD_TOKEN), java.sql.Timestamp.from(tokenExpiresAt),
            encryption.encrypt(OLD_CLIENT_ID), encryption.encrypt(OLD_SECRET));
        UUID order = owner.queryForObject(
            "INSERT INTO orders (tenant_id, store_id, external_id, number, status, payment_method, placed_at, " +
            "    customer_name, customer_phone, pii_source) VALUES (?, ?, ?, ?, 'new', 'cod', now() - interval '2 days', " +
            "    'Existing Customer', '01011112222', 'bosta') RETURNING id",
            UUID.class, tenant, store, "gid://shopify/Order/" + System.nanoTime(), "#" + tag.toUpperCase() + "-OLD");
        return new Shop(tenant, store, user, domain, order);
    }

    private String login(UUID userId) {
        String mail = owner.queryForObject("SELECT email FROM users WHERE id = ?", String.class, userId);
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<TokenResponse> resp = http.postForEntity(base() + "/api/v1/auth/login",
            new HttpEntity<>(Map.of("email", mail, "password", "pass123"), h), TokenResponse.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        return resp.getBody().accessToken();
    }

    /** POST /oauth/initiate as the owner — returns the state nonce from the consent URL. */
    @SuppressWarnings("unchecked")
    private String initiate(String token, String shop) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        h.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<Map> r = http.exchange(base() + "/api/v1/shopify/oauth/initiate", HttpMethod.POST,
            new HttpEntity<>(Map.of("shop", shop), h), Map.class);
        assertThat(r.getStatusCode()).as("initiate: %s", r.getBody()).isEqualTo(HttpStatus.OK);
        String url = (String) r.getBody().get("consentUrl");
        return url.substring(url.indexOf("state=") + "state=".length());
    }

    private ResponseEntity<Void> callback(String nonce, String shop, String code) {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("code", code);
        p.put("shop", shop);
        p.put("state", nonce);
        p.put("timestamp", String.valueOf(Instant.now().getEpochSecond()));
        TreeMap<String, String> sorted = new TreeMap<>(p);
        StringBuilder canonical = new StringBuilder();
        sorted.forEach((k, v) -> { if (!canonical.isEmpty()) canonical.append('&'); canonical.append(k).append('=').append(v); });
        p.put("hmac", hex(hmac(officialSecret, canonical.toString())));
        StringBuilder q = new StringBuilder();
        p.forEach((k, v) -> { if (!q.isEmpty()) q.append('&'); q.append(k).append('=').append(v); });
        return http.getForEntity(base() + "/auth/shopify/callback?" + q, Void.class);
    }

    private ResponseEntity<Void> postWebhook(String topic, String shop, String secret, String body) {
        byte[] raw = body.getBytes(StandardCharsets.UTF_8);
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.set("X-Shopify-Topic", topic);
        h.set("X-Shopify-Shop-Domain", shop);
        h.set("X-Shopify-Hmac-Sha256", Base64.getEncoder().encodeToString(hmac(secret, raw)));
        h.set("X-Shopify-Webhook-Id", "wh-" + UUID.randomUUID());
        String[] parts = topic.split("/", 2);
        return http.exchange(base() + "/webhooks/shopify/" + parts[0] + "/" + parts[1], HttpMethod.POST,
            new HttpEntity<>(raw, h), Void.class);
    }

    private ResponseEntity<String> get(String path, String token) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        return http.exchange(base() + path, HttpMethod.GET, new HttpEntity<>(h), String.class);
    }

    private static byte[] hmac(String secret, String data) { return hmac(secret, data.getBytes(StandardCharsets.UTF_8)); }

    private static byte[] hmac(String secret, byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(data);
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long end = System.currentTimeMillis() + 60_000;
        while (System.currentTimeMillis() < end) {
            if (condition.getAsBoolean()) return;
            Thread.sleep(500);
        }
        assertThat(condition.getAsBoolean()).as("condition not met within 60 s").isTrue();
    }

    private String base() { return "http://localhost:" + port; }
}
