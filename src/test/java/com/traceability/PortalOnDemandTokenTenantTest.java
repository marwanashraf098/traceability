package com.traceability;

import com.traceability.integrations.bosta.BostaGateway;
import com.traceability.integrations.bosta.BostaRateLimiter;
import com.traceability.integrations.shopify.ShopifyException;
import com.traceability.integrations.shopify.ShopifyGateway;
import com.traceability.integrations.shopify.ShopifyGraphQLErrorException;
import com.traceability.integrations.shopify.ShopifyStoreNeedsReauthException;
import com.traceability.integrations.shopify.ShopifyTokenProvider;
import com.traceability.portal.PortalBostaBudget;
import com.traceability.portal.PortalOnDemandLookup;
import com.traceability.security.EncryptionService;
import com.traceability.tenancy.TenantAwareDataSource;
import com.traceability.tenancy.TenantContext;
import org.jobrunr.scheduling.JobScheduler;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.HttpClientErrorException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Prod bug 2026-10-11: every on-demand lookup answered UNAVAILABLE. The lookup asked ShopifyTokenProvider
 * for the token with NO tenant context, so as app_user + RLS the stores row was invisible ("Store not
 * found or not visible under current tenant"). PortalOnDemandLookupTest couldn't see it: it mocks the
 * provider and connects as postgres (BYPASSRLS). Here the lookup and the REAL provider run on an app_user
 * connection, called — like PortalService does — with no tenant context. Plus the UNAVAILABLE WARN logs:
 * a cause for each of the three paths, never a token, the phone or a Shopify body.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.MethodName.class)
@ExtendWith(OutputCaptureExtension.class)
class PortalOnDemandTokenTenantTest {

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
        r.add("portal.preconnect.bosta-interval-ms", () -> "0");
    }

    static final String TOKEN = "shpat-tenant-ctx-secret";
    static final String REFRESHED = "shpat-refreshed-secret";
    static final String REFRESH = "shprt-tenant-ctx-secret";
    static final String PHONE = "01055501234";
    static final String KEY = "bosta-raw-key-ctx";

    @Autowired JdbcTemplate jdbc;          // postgres — fixtures only
    @Autowired EncryptionService encryption;
    @Autowired PortalBostaBudget budget;
    @Autowired BostaRateLimiter rateLimiter;
    @MockBean JobScheduler jobScheduler;

    JdbcTemplate appUserJdbc;
    DataSourceTransactionManager appUserTxm;
    ShopifyGateway shopify;
    PortalOnDemandLookup lookup;
    UUID tenant, store;

    @BeforeAll
    void setup() {
        TenantAwareDataSource ds = new TenantAwareDataSource(
            new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "app_user", "testpw"));
        appUserJdbc = new JdbcTemplate(ds);
        appUserTxm = new DataSourceTransactionManager(ds);

        tenant = UUID.randomUUID();
        store = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name, portal_slug, portal_enabled, customer_return_window_days) " +
                    "VALUES (?, 'Ctx Store', 'ctx-store', true, 14)", tenant);
        jdbc.update("INSERT INTO stores (id, tenant_id, shop_domain, status, import_status, orders_ingest_from, connection_type) " +
                    "VALUES (?, ?, 'ctx-store.myshopify.com', 'connected', 'idle', now() - interval '10 days', 'oauth')", store, tenant);
        jdbc.update("INSERT INTO courier_accounts (id, tenant_id, provider, api_key_encrypted, webhook_secret, status) " +
                    "VALUES (gen_random_uuid(), ?, 'bosta', ?, 'h-ctx', 'active')", tenant, encryption.encrypt(KEY));
        jdbc.update("INSERT INTO orders (tenant_id, store_id, external_id, number, status, placed_at) " +
                    "VALUES (?, ?, 'gid://shopify/Order/ctx2000', '#2000', 'delivered', now() - interval '5 days')", tenant, store);
    }

    @BeforeEach
    void fresh() {
        TenantContext.clear();
        setToken(Instant.now().plus(Duration.ofHours(1)));
        jdbc.update("UPDATE stores SET status = 'connected' WHERE id = ?", store);
        shopify = mock(ShopifyGateway.class);
        when(shopify.findOrdersByName(anyString(), anyString(), anyString()))
            .thenReturn(new ShopifyGateway.PortalOrderSearch(List.of(), true));
        when(shopify.oldestOrder(anyString(), anyString())).thenReturn(Optional.empty());
        // The REAL provider and the lookup, both on the app_user connection (RLS on).
        ShopifyTokenProvider tokens = new ShopifyTokenProvider(appUserJdbc, shopify, encryption, appUserTxm);
        lookup = new PortalOnDemandLookup(appUserJdbc, appUserTxm, shopify, tokens, mock(BostaGateway.class), encryption,
            budget, rateLimiter, true, 20, 5, 3000, 2000, 30, 60);
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private void setToken(Instant accessExpiresAt) {
        jdbc.update("UPDATE stores SET access_token_encrypted = ?, access_token_expires_at = ?, " +
                    "  refresh_token_encrypted = ?, refresh_token_expires_at = now() + interval '80 days' WHERE id = ?",
            encryption.encrypt(TOKEN), Timestamp.from(accessExpiresAt), encryption.encrypt(REFRESH), store);
    }

    private PortalOnDemandLookup.Result fetch(String number) {
        assertThat(TenantContext.get()).isNull();   // as PortalService calls it: outside any runAs
        return lookup.fetch(tenant, "#" + number, number, number, PHONE, null, System.nanoTime() + 4_000_000_000L);
    }

    private static void assertNoSecrets(CapturedOutput out) {
        assertThat(out.getAll()).doesNotContain(TOKEN).doesNotContain(REFRESHED).doesNotContain(REFRESH)
            .doesNotContain(PHONE).doesNotContain(KEY).doesNotContain("secret-body");
    }

    // ── the fix ───────────────────────────────────────────────────────────────

    @Test
    void t1_freshToken_readUnderTheTenant_asAppUser_searchRuns(CapturedOutput out) {
        PortalOnDemandLookup.Result r = fetch("1500");

        assertThat(r.kind()).isEqualTo(PortalOnDemandLookup.Kind.NOT_FOUND);
        verify(shopify).findOrdersByName("ctx-store.myshopify.com", TOKEN, "1500");
        assertThat(out.getAll()).doesNotContain("UNAVAILABLE");
        assertNoSecrets(out);
    }

    @Test
    void t2_expiringToken_isRefreshedUnderTheTenant_andTheNewTokenUsed(CapturedOutput out) {
        setToken(Instant.now().plus(Duration.ofMinutes(1)));   // inside the 5-minute refresh buffer
        when(shopify.refreshAccessToken("ctx-store.myshopify.com", REFRESH))
            .thenReturn(new ShopifyGateway.TokenResponse(REFRESHED, REFRESH + "-2", 3600, 7_776_000, null));

        PortalOnDemandLookup.Result r = fetch("1501");

        assertThat(r.kind()).isEqualTo(PortalOnDemandLookup.Kind.NOT_FOUND);
        verify(shopify).findOrdersByName("ctx-store.myshopify.com", REFRESHED, "1501");
        Timestamp exp = jdbc.queryForObject("SELECT access_token_expires_at FROM stores WHERE id = ?", Timestamp.class, store);
        assertThat(exp.toInstant()).isAfter(Instant.now().plus(Duration.ofMinutes(50)));   // written back through RLS
        assertNoSecrets(out);
    }

    // ── the UNAVAILABLE causes are logged ─────────────────────────────────────

    @Test
    void u1_tokenFailure_logsTheExceptionClass(CapturedOutput out) {
        // An expiring token whose refresh Shopify rejects (the store is still 'connected' when the lookup starts).
        setToken(Instant.now().plus(Duration.ofMinutes(1)));
        when(shopify.refreshAccessToken(anyString(), anyString()))
            .thenThrow(new ShopifyStoreNeedsReauthException("ctx-store.myshopify.com", "refresh rejected"));

        assertThat(fetch("1502").kind()).isEqualTo(PortalOnDemandLookup.Kind.UNAVAILABLE);
        assertThat(out.getAll()).contains("WARN").contains("tenant " + tenant + " → UNAVAILABLE: Shopify token unavailable (ShopifyStoreNeedsReauthException)");
        verify(shopify, never()).findOrdersByName(anyString(), anyString(), anyString());
        assertNoSecrets(out);
    }

    @Test
    void u2_searchHttpFailure_logsTheStatus_neverTheBody(CapturedOutput out) {
        HttpClientErrorException http = HttpClientErrorException.create(HttpStatus.UNAUTHORIZED, "Unauthorized", null,
            "{\"errors\":\"secret-body\"}".getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
        when(shopify.findOrdersByName(anyString(), anyString(), anyString()))
            .thenThrow(new ShopifyException("Shopify GraphQL HTTP 401 for x: {\"errors\":\"secret-body\"}", http));

        assertThat(fetch("1503").kind()).isEqualTo(PortalOnDemandLookup.Kind.UNAVAILABLE);
        assertThat(out.getAll()).contains("→ UNAVAILABLE: Shopify order search failed (ShopifyException HTTP 401 cause=Unauthorized)");
        assertNoSecrets(out);
    }

    @Test
    void u3_searchGraphQLError_logsTheCodes(CapturedOutput out) {
        when(shopify.findOrdersByName(anyString(), anyString(), anyString()))
            .thenThrow(new ShopifyGraphQLErrorException("Shopify GraphQL error on the portal order lookup", List.of("ACCESS_DENIED")));

        assertThat(fetch("1504").kind()).isEqualTo(PortalOnDemandLookup.Kind.UNAVAILABLE);
        assertThat(out.getAll()).contains("→ UNAVAILABLE: Shopify order search failed (ShopifyGraphQLErrorException codes=[ACCESS_DENIED])");
        assertNoSecrets(out);
    }

    @Test
    void u4_noCustomerFieldReadable_logsTheDeniedTiers(CapturedOutput out) {
        when(shopify.findOrdersByName(anyString(), anyString(), anyString()))
            .thenReturn(new ShopifyGateway.PortalOrderSearch(List.of(), false, List.of("FULL", "ADDRESSES")));

        assertThat(fetch("1505").kind()).isEqualTo(PortalOnDemandLookup.Kind.UNAVAILABLE);
        assertThat(out.getAll()).contains("→ UNAVAILABLE: no customer field readable (ACCESS_DENIED at tiers [FULL, ADDRESSES])");
        assertNoSecrets(out);
    }
}
