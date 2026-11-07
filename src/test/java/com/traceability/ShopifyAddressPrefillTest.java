package com.traceability;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.traceability.identity.JwtService;
import com.traceability.integrations.bosta.BostaGateway;
import com.traceability.integrations.shopify.ShopifyGateway;
import com.traceability.integrations.shopify.ShopifyTokenProvider;
import org.jobrunr.scheduling.JobScheduler;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.DriverManager;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Onboarding O2b — GET /setup/address/shopify-locations, the warehouse-address pre-fill. App runs as app_user (RLS
 * on); Shopify is mocked at the gateway's HTTP edge (the real list gateway parses the answer).
 *
 *   a1  only active locations, never Traced's own (by its linked id even when renamed, and by name), fulfils-online
 *       first, address fields only; ONE read query (includeLegacy false, includeInactive false, no mutation), no other
 *       Shopify call, nothing written in the database
 *   a2  a complete main warehouse address → empty, no Shopify call
 *   a3  a Shopify error (codes) or a thrown failure, or the token failing → 200 with an empty list
 *   a4  no connected store → empty, no Shopify call
 *   a5  owner only (manager and worker 403)
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ShopifyAddressPrefillTest {

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
        r.add("traced.catalog-backfill.enabled", () -> "false");
    }

    @LocalServerPort int port;
    @Autowired JwtService jwt;
    @MockBean ShopifyGateway shopify;
    @MockBean ShopifyTokenProvider tokens;
    @MockBean BostaGateway bosta;
    @MockBean JobScheduler jobScheduler;

    final ObjectMapper mapper = new ObjectMapper();
    final HttpClient http = HttpClient.newHttpClient();
    JdbcTemplate owner;

    static final String TRACED_GID = "gid://shopify/Location/900";

    record Tenant(UUID id, UUID owner, UUID manager, UUID worker, UUID location) {}

    @BeforeAll
    void setUp() {
        owner = new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "postgres", "postgres"));
    }

    @BeforeEach
    void resetMocks() {
        reset(shopify, tokens);
        when(tokens.getValidToken(any())).thenReturn("tok");
    }

    private JsonNode answer(String json) throws Exception { return mapper.readTree(json); }

    static String loc(String id, String name, boolean active, boolean online, String a1, String city, String code) {
        return "{\"id\":\"" + id + "\",\"name\":\"" + name + "\",\"isActive\":" + active + ",\"fulfillsOnlineOrders\":" + online
            + ",\"address\":{\"address1\":" + (a1 == null ? "null" : "\"" + a1 + "\"") + ",\"address2\":\"Floor 2\",\"city\":\""
            + city + "\",\"province\":\"Cairo\",\"provinceCode\":\"" + code + "\",\"zip\":\"11371\",\"phone\":\"+201000000000\","
            + "\"countryCode\":\"EG\"}}";
    }

    @Test @Order(1)
    @SuppressWarnings("unchecked")
    void a1_activeOnly_neverTraced_onlineFirst_readOnly() throws Exception {
        Tenant t = tenant("a1", "connected", true, false);
        when(shopify.executeGraphQLResponsePublic(anyString(), anyString(), anyString(), any())).thenReturn(answer(
            "{\"data\":{\"locations\":{\"nodes\":[" +
            loc("gid://shopify/Location/1", "Back office", true, false, "5 Side St", "Heliopolis", "C") + "," +
            loc(TRACED_GID, "Our warehouse (renamed in Shopify)", true, true, "1 Traced St", "Nasr City", "C") + "," +
            loc("gid://shopify/Location/2", "Shop", true, true, "12 Abbas El Akkad", "Nasr City", "C") + "," +
            loc("gid://shopify/Location/3", "Old store", false, true, "9 Closed St", "Giza", "GZ") + "," +
            loc("gid://shopify/Location/4", "traced main warehouse", true, true, "2 Copy St", "Giza", "GZ") +
            "]}}}"));
        String before = snapshot(t);

        List<Map<String, Object>> list = locations(t, "owner");
        assertThat(list).extracting(m -> m.get("id")).containsExactly("gid://shopify/Location/2", "gid://shopify/Location/1");
        assertThat(list.get(0)).containsOnlyKeys("id", "name", "fulfillsOnlineOrders", "address1", "address2", "city",
            "province", "provinceCode", "countryCode", "zip", "phone");
        assertThat(list.get(0)).containsEntry("address1", "12 Abbas El Akkad").containsEntry("provinceCode", "C")
            .containsEntry("city", "Nasr City");

        ArgumentCaptor<String> q = ArgumentCaptor.forClass(String.class);
        verify(shopify, times(1)).executeGraphQLResponsePublic(eq("a1-" + t.id() + ".myshopify.com"), eq("tok"), q.capture(), any());
        verifyNoMoreInteractions(shopify);
        assertThat(q.getValue()).contains("locations(first: 50, includeLegacy: false, includeInactive: false)")
            .doesNotContain("mutation").doesNotContain("order");
        assertThat(snapshot(t)).as("nothing written").isEqualTo(before);
    }

    @Test @Order(2)
    void a2_completeAddress_noShopifyCall() throws Exception {
        Tenant t = tenant("a2", "connected", true, true);
        assertThat(locations(t, "owner")).isEmpty();
        verifyNoInteractions(shopify);
    }

    @Test @Order(3)
    void a3_failures_empty() throws Exception {
        Tenant t = tenant("a3", "connected", false, false);
        when(shopify.executeGraphQLResponsePublic(anyString(), anyString(), anyString(), any()))
            .thenReturn(answer("{\"errors\":[{\"message\":\"x\",\"extensions\":{\"code\":\"ACCESS_DENIED\"}}]}"));
        assertThat(locations(t, "owner")).isEmpty();

        reset(shopify);
        when(shopify.executeGraphQLResponsePublic(anyString(), anyString(), anyString(), any()))
            .thenThrow(new RuntimeException("timeout"));
        assertThat(locations(t, "owner")).isEmpty();

        reset(shopify, tokens);
        when(tokens.getValidToken(any())).thenThrow(new IllegalStateException("needs reauth"));
        assertThat(locations(t, "owner")).isEmpty();
        verifyNoInteractions(shopify);
    }

    @Test @Order(4)
    void a4_noStore_noCall() throws Exception {
        Tenant t = tenant("a4", "disconnected", false, false);
        assertThat(locations(t, "owner")).isEmpty();
        verifyNoInteractions(shopify);
    }

    @Test @Order(5)
    void a5_ownerOnly() throws Exception {
        Tenant t = tenant("a5", "connected", false, false);
        assertThat(send(t, "manager").statusCode()).isEqualTo(403);
        assertThat(send(t, "worker").statusCode()).isEqualTo(403);
        verifyNoInteractions(shopify);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> locations(Tenant t, String role) throws Exception {
        HttpResponse<String> r = send(t, role);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        return (List<Map<String, Object>>) mapper.readValue(r.body(), Map.class).get("locations");
    }

    private HttpResponse<String> send(Tenant t, String role) throws Exception {
        UUID user = switch (role) { case "owner" -> t.owner(); case "manager" -> t.manager(); default -> t.worker(); };
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/setup/address/shopify-locations"))
            .header("Authorization", "Bearer " + jwt.issueAccessToken(user, t.id(), role)).GET().build(),
            HttpResponse.BodyHandlers.ofString());
    }

    /** Every row the read could have touched, plus the tenant's own row. */
    private String snapshot(Tenant t) {
        StringBuilder sb = new StringBuilder();
        for (String table : List.of("locations", "stores", "orders", "audit_log", "shopify_inventory_adjustments")) {
            sb.append(table).append('=').append(owner.queryForObject(
                "SELECT count(*) || ':' || coalesce(md5(string_agg(x::text, ',' ORDER BY x::text)), '') FROM " + table +
                " x WHERE tenant_id = ?", String.class, t.id())).append(';');
        }
        sb.append(owner.queryForObject("SELECT row_to_json(t)::text FROM tenants t WHERE id = ?", String.class, t.id()));
        return sb.toString();
    }

    private Tenant tenant(String tag, String storeStatus, boolean linkedMain, boolean completeAddress) {
        UUID id = UUID.randomUUID(), loc = UUID.randomUUID();
        owner.update("INSERT INTO tenants (id, name) VALUES (?, ?)", id, "Prefill " + tag);
        owner.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status) VALUES (?, ?, 'shopify', ?, ?::store_status)",
            UUID.randomUUID(), id, tag + "-" + id + ".myshopify.com", storeStatus);
        owner.update("INSERT INTO locations (id, tenant_id, name, type, is_default, is_fulfillment) " +
                     "VALUES (?, ?, 'Main Warehouse', 'warehouse', true, true)", loc, id);
        if (linkedMain) owner.update("UPDATE locations SET shopify_location_id = ?, shopify_sync_status = 'linked' WHERE id = ?", TRACED_GID, loc);
        if (completeAddress) {
            owner.update("UPDATE locations SET address_province_code = 'C', address_city = 'Nasr City', address_street = '12 Street' WHERE id = ?", loc);
        }
        return new Tenant(id, user(id, "owner", tag), user(id, "manager", tag), user(id, "worker", tag), loc);
    }

    private UUID user(UUID tenant, String role, String tag) {
        UUID u = UUID.randomUUID();
        owner.update("INSERT INTO users (id, tenant_id, name, email, password_hash, pin_code, role, active) " +
                     "VALUES (?, ?, ?, ?, ?, ?, ?::user_role, true)",
            u, tenant, role + " " + tag, role + "-" + tag + "-" + u + "@test.local",
            "worker".equals(role) ? null : "h", "worker".equals(role) ? "p" : null, role);
        return u;
    }
}
