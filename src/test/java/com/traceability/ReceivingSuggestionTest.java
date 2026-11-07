package com.traceability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.traceability.identity.JwtService;
import com.traceability.integrations.bosta.BostaGateway;
import com.traceability.integrations.shopify.ShopifyGateway;
import com.traceability.integrations.shopify.ShopifyTokenProvider;
import com.traceability.integrations.shopify.ShopifyVariantStockGateway;
import com.traceability.integrations.shopify.ShopifyVariantStockGateway.Page;
import com.traceability.integrations.shopify.ShopifyVariantStockGateway.VariantStock;
import org.jobrunr.scheduling.JobScheduler;
import org.junit.jupiter.api.*;
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
 * Onboarding O4 — the guided first receiving. App runs as app_user (RLS on); Shopify is a mock.
 *
 *   s1  suggestion = the variant with the most Shopify stock that is an ACTIVE product in the Traced catalog
 *       (unknown and archived ones skipped), its total and its on-hand at the Traced Main location (the linked
 *       GID is passed); read only — nothing in the database changes, no other Shopify call
 *   s2  empty / all-zero answers → {suggestion: null}; a page ending at 0 stock reads no further
 *   s3  at most two pages (50 each): a hit on page 2 is found, a third page is never asked for
 *   s4  a Shopify failure (GraphQL codes, or the token) → 200 {suggestion: null}
 *   s5  no connected store → null without any Shopify call; no linked main warehouse → the call without a location
 *   s6  owner / manager only (worker 403)
 *   s7  the guided flow's own calls (POST session → line → finalize) turn progress first_receiving done, and
 *       GET …/pieces carries each piece's short_code (what the label's barcode holds) — the read-only check
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ReceivingSuggestionTest {

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
    @MockBean ShopifyVariantStockGateway stock;
    @MockBean BostaGateway bosta;
    @MockBean JobScheduler jobScheduler;

    final ObjectMapper mapper = new ObjectMapper();
    final HttpClient http = HttpClient.newHttpClient();
    JdbcTemplate owner;

    static final String MAIN_GID = "gid://shopify/Location/9001";

    record Tenant(UUID id, UUID owner, UUID manager, UUID worker, UUID store, UUID location) {}

    @BeforeAll
    void setUp() {
        owner = new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "postgres", "postgres"));
    }

    @BeforeEach
    void resetMocks() {
        reset(stock, tokens, shopify);
        when(tokens.getValidToken(any())).thenReturn("tok");
    }

    @Test @Order(1)
    void s1_mostStockKnownActive_readOnly() throws Exception {
        Tenant t = tenant("s1", "connected", true);
        variant(t, "gid://shopify/ProductVariant/2", "archived");
        UUID hit = variant(t, "gid://shopify/ProductVariant/3", "active");
        variant(t, "gid://shopify/ProductVariant/4", "active");
        when(stock.topVariantsByStock(anyString(), anyString(), any(), any(), anyInt())).thenReturn(new Page(List.of(
            new VariantStock("gid://shopify/ProductVariant/1", 500, 0),   // not in Traced
            new VariantStock("gid://shopify/ProductVariant/2", 400, 0),   // archived
            new VariantStock("gid://shopify/ProductVariant/3", 300, 7),
            new VariantStock("gid://shopify/ProductVariant/4", 200, 0)), true, "c1"));

        String before = snapshot(t);
        Map<String, Object> s = suggestion(t, "owner");
        assertThat(s).isNotNull();
        assertThat(s.get("variantId")).isEqualTo(hit.toString());
        assertThat(s.get("shopifyQuantity")).isEqualTo(300);
        assertThat(s.get("mainOnHand")).isEqualTo(7);
        verify(stock, times(1)).topVariantsByStock(eq("s1-" + t.id() + ".myshopify.com"), eq("tok"), eq(MAIN_GID), isNull(), eq(50));
        verifyNoInteractions(shopify);
        assertThat(snapshot(t)).as("nothing written").isEqualTo(before);
    }

    @Test @Order(2)
    void s2_emptyOrZero_null() throws Exception {
        Tenant t = tenant("s2", "connected", true);
        variant(t, "gid://shopify/ProductVariant/20", "active");
        when(stock.topVariantsByStock(anyString(), anyString(), any(), any(), anyInt()))
            .thenReturn(new Page(List.of(), false, null));
        assertThat(suggestion(t, "owner")).isNull();

        reset(stock);
        when(stock.topVariantsByStock(anyString(), anyString(), any(), any(), anyInt()))
            .thenReturn(new Page(List.of(new VariantStock("gid://shopify/ProductVariant/20", 0, 0)), true, "c"));
        assertThat(suggestion(t, "owner")).as("a known variant with 0 stock is no suggestion").isNull();
        verify(stock, times(1)).topVariantsByStock(anyString(), anyString(), any(), any(), anyInt());
    }

    @Test @Order(3)
    void s3_twoPagesAtMost() throws Exception {
        Tenant t = tenant("s3", "connected", true);
        UUID hit = variant(t, "gid://shopify/ProductVariant/31", "active");
        when(stock.topVariantsByStock(anyString(), anyString(), any(), isNull(), anyInt()))
            .thenReturn(new Page(List.of(new VariantStock("gid://shopify/ProductVariant/30", 90, null)), true, "p1"));
        when(stock.topVariantsByStock(anyString(), anyString(), any(), eq("p1"), anyInt()))
            .thenReturn(new Page(List.of(new VariantStock("gid://shopify/ProductVariant/31", 80, null)), true, "p2"));
        assertThat(suggestion(t, "owner").get("variantId")).isEqualTo(hit.toString());

        reset(stock);
        when(stock.topVariantsByStock(anyString(), anyString(), any(), any(), anyInt()))
            .thenReturn(new Page(List.of(new VariantStock("gid://shopify/ProductVariant/39", 90, null)), true, "next"));
        assertThat(suggestion(t, "owner")).isNull();
        verify(stock, times(2)).topVariantsByStock(anyString(), anyString(), any(), any(), anyInt());
    }

    @Test @Order(4)
    void s4_failures_null() throws Exception {
        Tenant t = tenant("s4", "connected", true);
        variant(t, "gid://shopify/ProductVariant/40", "active");
        when(stock.topVariantsByStock(anyString(), anyString(), any(), any(), anyInt()))
            .thenThrow(new ShopifyVariantStockGateway.VariantStockReadException("ACCESS_DENIED"));
        assertThat(suggestion(t, "owner")).isNull();

        reset(stock, tokens);
        when(tokens.getValidToken(any())).thenThrow(new IllegalStateException("needs reauth"));
        assertThat(suggestion(t, "owner")).isNull();
        verifyNoInteractions(stock);
    }

    @Test @Order(5)
    void s5_noStore_or_noLinkedMain() throws Exception {
        Tenant off = tenant("s5a", "disconnected", true);
        assertThat(suggestion(off, "owner")).isNull();
        verifyNoInteractions(stock);

        Tenant unlinked = tenant("s5b", "connected", false);
        when(stock.topVariantsByStock(anyString(), anyString(), any(), any(), anyInt()))
            .thenReturn(new Page(List.of(), false, null));
        assertThat(suggestion(unlinked, "owner")).isNull();
        verify(stock).topVariantsByStock(anyString(), anyString(), isNull(), isNull(), eq(50));
    }

    @Test @Order(6)
    void s6_roles() throws Exception {
        Tenant t = tenant("s6", "connected", true);
        when(stock.topVariantsByStock(anyString(), anyString(), any(), any(), anyInt()))
            .thenReturn(new Page(List.of(), false, null));
        assertThat(send("GET", "/api/v1/onboarding/receiving-suggestion", token(t, "worker"), null).statusCode()).isEqualTo(403);
        assertThat(send("GET", "/api/v1/onboarding/receiving-suggestion", token(t, "manager"), null).statusCode()).isEqualTo(200);
    }

    @Test @Order(7)
    @SuppressWarnings("unchecked")
    void s7_guidedCalls_finishFirstReceiving_andPiecesCarryShortCode() throws Exception {
        Tenant t = tenant("s7", "connected", true);
        UUID v = variant(t, "gid://shopify/ProductVariant/70", "active");
        assertThat(step(t, "first_receiving")).isFalse();

        HttpResponse<String> created = send("POST", "/api/v1/receiving/sessions", token(t, "owner"),
            Map.of("locationId", t.location().toString()));
        assertThat(created.statusCode()).as(created.body()).isBetween(200, 201);
        String session = (String) mapper.readValue(created.body(), Map.class).get("sessionId");
        HttpResponse<String> line = send("POST", "/api/v1/receiving/sessions/" + session + "/lines", token(t, "owner"),
            Map.of("variantId", v.toString(), "quantity", 3));
        assertThat(line.statusCode()).as(line.body()).isBetween(200, 201);
        HttpResponse<String> fin = send("POST", "/api/v1/receiving/sessions/" + session + "/finalize", token(t, "owner"), null);
        assertThat(fin.statusCode()).as(fin.body()).isBetween(200, 201);

        assertThat(step(t, "first_receiving")).isTrue();
        HttpResponse<String> pieces = send("GET", "/api/v1/receiving/sessions/" + session + "/pieces", token(t, "owner"), null);
        List<Map<String, Object>> list = mapper.readValue(pieces.body(), List.class);
        assertThat(list).hasSize(3);
        assertThat(list).allSatisfy(p -> assertThat((String) p.get("short_code")).matches("P\\d{6}"));
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private Map<String, Object> suggestion(Tenant t, String role) throws Exception {
        HttpResponse<String> r = send("GET", "/api/v1/onboarding/receiving-suggestion", token(t, role), null);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        Map<String, Object> body = mapper.readValue(r.body(), Map.class);
        assertThat(body).containsKey("suggestion");
        return (Map<String, Object>) body.get("suggestion");
    }

    @SuppressWarnings("unchecked")
    private boolean step(Tenant t, String key) throws Exception {
        HttpResponse<String> r = send("GET", "/api/v1/onboarding/progress", token(t, "owner"), null);
        List<Map<String, Object>> steps = (List<Map<String, Object>>) mapper.readValue(r.body(), Map.class).get("steps");
        return steps.stream().filter(s -> key.equals(s.get("key"))).findFirst().map(s -> Boolean.TRUE.equals(s.get("done"))).orElseThrow();
    }

    /** Every row the tenant has in the tables a read could have touched, plus its prefs. */
    private String snapshot(Tenant t) {
        StringBuilder sb = new StringBuilder();
        for (String table : List.of("orders", "order_items", "products", "variants", "pieces", "receipts", "audit_log",
                                    "shopify_inventory_adjustments", "stores", "locations")) {
            sb.append(table).append('=').append(owner.queryForObject(
                "SELECT count(*) || ':' || coalesce(md5(string_agg(x::text, ',' ORDER BY x::text)), '') FROM " + table +
                " x WHERE tenant_id = ?", String.class, t.id())).append(';');
        }
        sb.append(owner.queryForObject("SELECT onboarding_prefs::text FROM tenants WHERE id = ?", String.class, t.id()));
        return sb.toString();
    }

    private HttpResponse<String> send(String method, String path, String token, Object body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
            .header("Authorization", "Bearer " + token)
            .header("Content-Type", "application/json");
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
            : HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private String token(Tenant t, String role) {
        UUID user = switch (role) { case "owner" -> t.owner(); case "manager" -> t.manager(); default -> t.worker(); };
        return jwt.issueAccessToken(user, t.id(), role);
    }

    private Tenant tenant(String tag, String storeStatus, boolean linkedMain) {
        UUID id = UUID.randomUUID(), store = UUID.randomUUID(), loc = UUID.randomUUID();
        owner.update("INSERT INTO tenants (id, name) VALUES (?, ?)", id, "Suggest " + tag);
        owner.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status) VALUES (?, ?, 'shopify', ?, ?::store_status)",
            store, id, tag + "-" + id + ".myshopify.com", storeStatus);
        owner.update("INSERT INTO locations (id, tenant_id, name, type, is_default, is_fulfillment) " +
                     "VALUES (?, ?, 'Main Warehouse', 'warehouse', true, true)", loc, id);
        if (linkedMain) {
            owner.update("UPDATE locations SET shopify_location_id = ?, shopify_sync_status = 'linked' WHERE id = ?", MAIN_GID, loc);
        }
        return new Tenant(id, user(id, "owner", tag), user(id, "manager", tag), user(id, "worker", tag), store, loc);
    }

    private UUID variant(Tenant t, String gid, String productStatus) {
        UUID product = UUID.randomUUID(), variant = UUID.randomUUID();
        owner.update("INSERT INTO products (id, tenant_id, store_id, external_id, title, status) VALUES (?, ?, ?, ?, 'Tee', ?)",
            product, t.id(), t.store(), "gid://shopify/Product/" + product, productStatus);
        owner.update("INSERT INTO variants (id, tenant_id, product_id, external_id, title) VALUES (?, ?, ?, ?, 'Black / M')",
            variant, t.id(), product, gid);
        return variant;
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
