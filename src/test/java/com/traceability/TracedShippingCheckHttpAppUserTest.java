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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * L1b-2 (approved by Marawan 2026-11-16) — "Check again" (POST /api/v1/locations/shopify/shipping-check) over the real
 * HTTP path with the WHOLE app on app_user (CLAUDE.md: no transaction = no tenant — every DB touch must run inside one,
 * or RLS hides the row and the claim / record silently does nothing).
 *
 *   h1 the owner's check reads, records the verdict on ITS row and answers Ready; the claim is stamped; a second click
 *      inside 2 minutes → 429 RATE_LIMITED, nothing read; another tenant's call never touches A's row (409, not linked)
 *   h2 a worker → 403, nothing read; a manager → 200
 *   h3 GET /locations and GET /connections carry the market fields on app_user
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TracedShippingCheckHttpAppUserTest {

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
    static final AtomicLong IDS = new AtomicLong(59_000_000_000L);
    Shop a, b;

    @BeforeAll
    void setUp() {
        pg = new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        a = new Shop("a", true);
        b = new Shop("b", false);
    }

    @BeforeEach
    void stubs() {
        reset(shopifyGateway, tokenProvider, locations, profiles, models, jobScheduler, bostaV2);
        when(tokenProvider.getValidToken(any())).thenReturn("tok");
        when(models.read(any(), eq("tok"))).thenReturn(Optional.of(ShopifyShippingModelGateway.Model.MARKET_DRIVEN));
        when(locations.readOnlineFulfillment(any(), eq("tok"), any()))
            .thenReturn(Optional.of(new ShopifyLocationGateway.OnlineFulfillment(true, true)));
        when(models.readMarketCoverage(any(), eq("tok"), any())).thenReturn(Optional.of(new MarketShippingCoverage.Result(
            MarketShippingCoverage.Verdict.COVERED, "United States — Standard", List.of("Canada"), "covered")));
        pg.update("UPDATE locations SET shopify_shipping_check_claimed_at = NULL");
    }

    @AfterEach
    void clear() { TenantContext.clear(); }

    class Shop {
        final UUID tenant = UUID.randomUUID(), store = UUID.randomUUID(), owner, manager, worker;
        final String gid = "gid://shopify/Location/" + IDS.incrementAndGet();

        Shop(String tag, boolean linked) {
            pg.update("INSERT INTO tenants (id, name) VALUES (?, ?)", tenant, "Check " + tag);
            pg.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status, connection_type, access_token_scopes) " +
                "VALUES (?, ?, 'shopify', ?, 'connected', 'oauth', 'read_products,write_locations,write_shipping,read_markets')",
                store, tenant, "check-" + tag + "-" + IDS.incrementAndGet() + ".myshopify.com");
            owner = userOf("owner");
            manager = userOf("manager");
            worker = userOf("worker");
            if (linked) {
                pg.update("INSERT INTO locations (tenant_id, name, is_fulfillment, shopify_location_id, shopify_sync_status, " +
                    "  shopify_shipping_model, shopify_online_fulfillment_status) " +
                    "VALUES (?, 'Main', true, ?, 'linked', 'market_driven', 'on')", tenant, gid);
            }
        }

        UUID userOf(String role) {
            return pg.queryForObject("INSERT INTO users (tenant_id, name, email, password_hash, role, active) " +
                "VALUES (?, ?, ?, 'x', ?::user_role, true) RETURNING id",
                UUID.class, tenant, role, role + "-" + UUID.randomUUID() + "@test.com", role);
        }

        String token(String role) {
            UUID id = switch (role) { case "owner" -> owner; case "manager" -> manager; default -> worker; };
            return jwt.issueAccessToken(id, tenant, role);
        }

        Map<String, Object> row() {
            return pg.queryForMap("SELECT shopify_market_shipping_status, shopify_market_shipping_covered_by, " +
                "  shopify_shipping_check_claimed_at FROM locations WHERE tenant_id = ? AND is_fulfillment", tenant);
        }
    }

    @SuppressWarnings("rawtypes")
    ResponseEntity<Map> call(HttpMethod method, String token, String path) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        h.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange(path, method, new HttpEntity<>(null, h), Map.class);
    }

    static final String CHECK = "/api/v1/locations/shopify/shipping-check";

    @Test
    @SuppressWarnings("rawtypes")
    void h1_owner_checks_recordsOnItsRow_throttled_otherTenantUntouched() {
        ResponseEntity<Map> own = call(HttpMethod.POST, a.token("owner"), CHECK);
        assertThat(own.getStatusCode().value()).isEqualTo(200);
        assertThat(own.getBody()).containsEntry("readiness", "ready").containsEntry("coveredBy", "United States — Standard")
            .containsEntry("notAddedFor", List.of("Canada"));
        Map<String, Object> row = a.row();
        assertThat(row.get("shopify_market_shipping_status")).isEqualTo("covered");
        assertThat(row.get("shopify_shipping_check_claimed_at")).isNotNull();
        verify(models, times(1)).readMarketCoverage(any(), any(), eq(a.gid));

        ResponseEntity<Map> again = call(HttpMethod.POST, a.token("owner"), CHECK);
        assertThat(again.getStatusCode().value()).isEqualTo(429);
        assertThat(again.getBody()).containsEntry("error", "RATE_LIMITED");
        assertThat(again.getBody().get("nextAllowedAt")).isNotNull();
        verify(models, times(1)).readMarketCoverage(any(), any(), any());

        // Tenant B has no linked Traced Main: 409, and A's row is exactly as A left it.
        Map<String, Object> before = a.row();
        assertThat(call(HttpMethod.POST, b.token("owner"), CHECK).getStatusCode().value()).isEqualTo(409);
        assertThat(a.row()).isEqualTo(before);
        verify(models, times(1)).readMarketCoverage(any(), any(), any());
    }

    @Test
    void h2_worker403_manager200() {
        assertThat(call(HttpMethod.POST, a.token("worker"), CHECK).getStatusCode().value()).isEqualTo(403);
        verifyNoInteractions(models, locations);
        assertThat(call(HttpMethod.POST, a.token("manager"), CHECK).getStatusCode().value()).isEqualTo(200);
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void h3_reads_carryTheMarketFields_onAppUser() {
        assertThat(call(HttpMethod.POST, a.token("owner"), CHECK).getStatusCode().value()).isEqualTo(200);

        ResponseEntity<List> locs = rest.exchange("/api/v1/locations", HttpMethod.GET,
            new HttpEntity<>(null, bearer(a.token("owner"))), List.class);
        assertThat(locs.getStatusCode().value()).isEqualTo(200);
        Map<String, Object> main = (Map<String, Object>) locs.getBody().get(0);
        assertThat(main).containsEntry("shopify_shipping_readiness", "ready")
            .containsEntry("shopify_market_shipping_missing", List.of("Canada"));

        ResponseEntity<Map> conn = call(HttpMethod.GET, a.token("owner"), "/api/v1/connections");
        Map<String, Object> shopify = (Map<String, Object>) conn.getBody().get("shopify");
        assertThat(shopify).containsEntry("tracedMainShipping", "ready").containsEntry("tracedNotAddedFor", List.of("Canada"))
            .containsEntry("tracedCoveredBy", "United States — Standard");
    }

    static HttpHeaders bearer(String token) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        return h;
    }
}
