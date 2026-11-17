package com.traceability;

import com.traceability.identity.JwtService;
import com.traceability.integrations.bosta.BostaV2Client;
import com.traceability.integrations.shopify.MarketShippingCoverage;
import com.traceability.integrations.shopify.ShopifyDeliveryProfileGateway;
import com.traceability.integrations.shopify.ShopifyGateway;
import com.traceability.integrations.shopify.ShopifyLocationGateway;
import com.traceability.integrations.shopify.ShopifyShippingModelGateway;
import com.traceability.integrations.shopify.ShopifyTokenProvider;
import com.traceability.tenancy.TenantContext;
import org.jobrunr.scheduling.JobScheduler;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * L1b-2 fix (2026-11-17) — the live test on testl1location (country Egypt, read_markets granted, Traced Main in no active
 * rate): the onboarding gate showed "Traced can't check this from here yet" + "I've added it" although Traced had read
 * every market and recorded 'not_covered' — /connections never carried the market answer, so the gate's instruction
 * fell back to the no-access words. Over the real HTTP path with the WHOLE app on app_user.
 *
 *   m1 the Egypt fixture through the REAL rule: Check again → not_covered on the row; /connections carries
 *      tracedMarketCheck 'not_covered' + "Not added for: Egypt, Canada, United States"; /locations the same on the row
 *   m2 granted + the read gave no verdict → 'no_verdict' (never "can't check from here"); granted + never read →
 *      'not_checked'
 *   m3 read_markets not granted → 'no_access' (the only case the gate says "can't check from here"), nothing read
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TracedMarketCheckHttpAppUserTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            TestPostgres.newContainer()
                    .withDatabaseName("traceability_test")
                    .withUsername("postgres")
                    .withPassword("postgres");

    static {
        POSTGRES.start();
        try (var c = java.sql.DriverManager.getConnection(POSTGRES.getJdbcUrl(), "postgres", "postgres");
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
        r.add("analytics.settlement.refresh-enabled", () -> "false");
        r.add("traced.shopify-returns.backfill.enabled", () -> "false");
        r.add("shopify.webhook-topics-backfill.enabled", () -> "false");
        r.add("traced.catalog-backfill.enabled", () -> "false");
    }

    @Autowired TestRestTemplate rest;
    @Autowired JwtService jwt;
    @MockBean ShopifyGateway shopifyGateway;
    @MockBean ShopifyTokenProvider tokenProvider;
    @MockBean ShopifyLocationGateway locations;
    @MockBean ShopifyDeliveryProfileGateway profiles;
    @MockBean ShopifyShippingModelGateway models;
    @MockBean JobScheduler jobScheduler;
    @MockBean BostaV2Client bostaV2;

    JdbcTemplate pg;
    static final AtomicLong IDS = new AtomicLong(61_000_000_000L);
    static final String TRACED_GID = "gid://shopify/Location/1004";   // the fixture's Traced Main
    static final ObjectMapper JSON = new ObjectMapper();
    static final String CHECK = "/api/v1/locations/shopify/shipping-check";

    @BeforeAll
    void setUp() {
        pg = new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
    }

    @BeforeEach
    void stubs() {
        reset(shopifyGateway, tokenProvider, locations, profiles, models, jobScheduler, bostaV2);
        when(tokenProvider.getValidToken(any())).thenReturn("tok");
        when(models.read(any(), eq("tok"))).thenReturn(Optional.of(ShopifyShippingModelGateway.Model.MARKET_DRIVEN));
        when(locations.readOnlineFulfillment(any(), eq("tok"), any()))
            .thenReturn(Optional.of(new ShopifyLocationGateway.OnlineFulfillment(true, true)));
    }

    @AfterEach
    void clear() { TenantContext.clear(); }

    /** The Egypt store's answer through the REAL rule (no mocked verdict). */
    static MarketShippingCoverage.Result egyptVerdict() throws Exception {
        JsonNode data;
        try (var in = TracedMarketCheckHttpAppUserTest.class.getResourceAsStream(
                "/shopify/market-shipping/coverage-egypt-uncovered.json")) {
            data = JSON.readTree(in).path("data");
        }
        List<JsonNode> markets = new ArrayList<>();
        data.path("markets").path("nodes").forEach(markets::add);
        return MarketShippingCoverage.evaluate(data.path("shop").path("shopAddress").path("countryCodeV2").asText(),
            markets, true, TRACED_GID);
    }

    class Shop {
        final UUID tenant = UUID.randomUUID(), store = UUID.randomUUID(), owner;
        final String gid;

        Shop(String tag, String scopes, String gid) {
            this.gid = gid;
            pg.update("INSERT INTO tenants (id, name) VALUES (?, ?)", tenant, "Market check " + tag);
            pg.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status, connection_type, access_token_scopes) " +
                "VALUES (?, ?, 'shopify', ?, 'connected', 'oauth', ?)",
                store, tenant, "mc-" + tag + "-" + IDS.incrementAndGet() + ".myshopify.com", scopes);
            owner = pg.queryForObject("INSERT INTO users (tenant_id, name, email, password_hash, role, active) " +
                "VALUES (?, 'owner', ?, 'x', 'owner'::user_role, true) RETURNING id",
                UUID.class, tenant, "owner-" + UUID.randomUUID() + "@test.com");
            pg.update("INSERT INTO locations (tenant_id, name, is_fulfillment, shopify_location_id, shopify_sync_status, " +
                "  shopify_shipping_model, shopify_online_fulfillment_status) " +
                "VALUES (?, 'Main', true, ?, 'linked', 'market_driven', 'on')", tenant, gid);
        }

        String token() { return jwt.issueAccessToken(owner, tenant, "owner"); }
    }

    static final String GRANTED = "read_products,write_locations,write_shipping,read_markets";

    @SuppressWarnings("rawtypes")
    ResponseEntity<Map> call(HttpMethod method, String token, String path) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        h.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange(path, method, new HttpEntity<>(null, h), Map.class);
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> shopify(Shop s) {
        return (Map<String, Object>) call(HttpMethod.GET, s.token(), "/api/v1/connections").getBody().get("shopify");
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    Map<String, Object> mainRow(Shop s) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(s.token());
        List<Map<String, Object>> rows = rest.exchange("/api/v1/locations", HttpMethod.GET, new HttpEntity<>(null, h),
            List.class).getBody();
        return rows.stream().filter(r -> Boolean.TRUE.equals(r.get("is_fulfillment"))).findFirst().orElseThrow();
    }

    @Test
    void m1_egypt_notCovered_reachesTheGate() throws Exception {
        Shop eg = new Shop("eg", GRANTED, TRACED_GID);
        when(models.readMarketCoverage(any(), eq("tok"), eq(TRACED_GID))).thenReturn(Optional.of(egyptVerdict()));

        assertThat(call(HttpMethod.POST, eg.token(), CHECK).getStatusCode().value()).isEqualTo(200);
        assertThat(pg.queryForObject("SELECT shopify_market_shipping_status FROM locations WHERE tenant_id = ?",
            String.class, eg.tenant)).isEqualTo("not_covered");

        assertThat(shopify(eg)).containsEntry("tracedMainShipping", "add_to_shipping_rates")
            .containsEntry("tracedMarketCheck", "not_covered")
            .containsEntry("tracedNotAddedFor", List.of("Egypt", "Canada", "United States"));
        assertThat(mainRow(eg)).containsEntry("shopify_market_check", "not_covered");
    }

    @Test
    void m2_granted_noVerdict_and_neverRead() {
        Shop nv = new Shop("nv", GRANTED, "gid://shopify/Location/" + IDS.incrementAndGet());
        when(models.readMarketCoverage(any(), eq("tok"), eq(nv.gid))).thenReturn(Optional.of(new MarketShippingCoverage.Result(
            MarketShippingCoverage.Verdict.NO_VERDICT, null, List.of(), "primary_market_not_found")));
        assertThat(call(HttpMethod.POST, nv.token(), CHECK).getStatusCode().value()).isEqualTo(200);
        assertThat(shopify(nv)).containsEntry("tracedMainShipping", "add_to_shipping_rates")
            .containsEntry("tracedMarketCheck", "no_verdict");
        assertThat(mainRow(nv)).containsEntry("shopify_market_check", "no_verdict");

        Shop fresh = new Shop("fresh", GRANTED, "gid://shopify/Location/" + IDS.incrementAndGet());
        assertThat(shopify(fresh)).containsEntry("tracedMarketCheck", "not_checked");
        assertThat(mainRow(fresh)).containsEntry("shopify_market_check", "not_checked");
    }

    @Test
    void m3_notGranted_noAccess_nothingRead() {
        Shop na = new Shop("na", "read_products,write_locations,write_shipping", "gid://shopify/Location/" + IDS.incrementAndGet());
        assertThat(call(HttpMethod.POST, na.token(), CHECK).getStatusCode().value()).isEqualTo(200);
        verify(models, never()).readMarketCoverage(any(), any(), any());
        assertThat(shopify(na)).containsEntry("tracedMarketCheck", "no_access");
        assertThat(mainRow(na)).containsEntry("shopify_market_check", "no_access");
    }
}
