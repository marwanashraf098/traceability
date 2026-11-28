package com.traceability;

import com.traceability.identity.JwtService;
import com.traceability.integrations.bosta.BostaV2Client;
import com.traceability.integrations.shopify.ShopifyGateway;
import com.traceability.integrations.shopify.ShopifyOrderPriceGateway;
import com.traceability.integrations.shopify.ShopifyTokenProvider;
import com.traceability.inventory.UlidGenerator;
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

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * V247 — the link-unit endpoints and the close rule over the real HTTP path, the WHOLE app as app_user (CLAUDE.md: an
 * endpoint's isolation test runs the app as app_user), at the URLs frontend/src/pages/Returns.tsx builds.
 *
 *   h1 worker: scan the parcel's AWB, scan the unexpected label → GET carries linkCandidates; POST link-unit → 200
 *      'linked' + unitLink; GET has unitLinks; undo → 200 'pending'; a different-variant label → 409 LINK_REFUSED with
 *      details.reason and both messages
 *   h2 tenant B's token on A's session → 404, A's item untouched
 *   h3 close with an illegal pending row → 200, needsReviewCount 1; GET /exceptions lists return_needs_review; the
 *      owner's POST /exceptions/resolve clears it
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReturnUnitLinkHttpAppUserTest {

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
    @MockBean ShopifyOrderPriceGateway prices;
    @MockBean JobScheduler jobScheduler;
    @MockBean BostaV2Client bostaV2;

    JdbcTemplate pg;
    static final AtomicLong IDS = new AtomicLong(59_000_000_000L);
    Shop a, b;

    @BeforeAll
    void setUp() {
        pg = new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        a = new Shop("a");
        b = new Shop("b");
    }

    @BeforeEach
    void stubs() {
        reset(shopifyGateway, tokenProvider, prices, jobScheduler, bostaV2);
        when(tokenProvider.getValidToken(any())).thenThrow(new IllegalStateException("no Shopify in this test"));
    }

    @AfterEach
    void clear() { TenantContext.clear(); }

    class Shop {
        final UUID tenant = UUID.randomUUID(), store = UUID.randomUUID(), owner, worker, main, product, tee, scarf;
        final String ownerToken, workerToken;

        Shop(String tag) {
            pg.update("INSERT INTO tenants (id, name) VALUES (?, ?)", tenant, "Link " + tag);
            pg.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status) VALUES (?, ?, 'shopify', ?, 'disconnected')",
                store, tenant, "link-" + tag + "-" + IDS.incrementAndGet() + ".myshopify.com");
            owner = pg.queryForObject("INSERT INTO users (tenant_id, name, email, password_hash, role, active) " +
                "VALUES (?, 'owner', ?, 'x', 'owner'::user_role, true) RETURNING id", UUID.class, tenant,
                "owner-" + UUID.randomUUID() + "@test.com");
            worker = pg.queryForObject("INSERT INTO users (tenant_id, name, email, password_hash, role, active) " +
                "VALUES (?, 'worker', ?, 'x', 'worker'::user_role, true) RETURNING id", UUID.class, tenant,
                "worker-" + UUID.randomUUID() + "@test.com");
            main = pg.queryForObject("INSERT INTO locations (tenant_id, name, is_fulfillment) VALUES (?, 'Main', true) RETURNING id",
                UUID.class, tenant);
            product = pg.queryForObject("INSERT INTO products (tenant_id, store_id, external_id, title, status) " +
                "VALUES (?, ?, ?, 'Eagle Tee', 'active') RETURNING id", UUID.class, tenant, store,
                "gid://shopify/Product/" + IDS.incrementAndGet());
            tee = variant("L / Black");
            scarf = variant("Grey");
            ownerToken = jwt.issueAccessToken(owner, tenant, "owner");
            workerToken = jwt.issueAccessToken(worker, tenant, "worker");
        }

        UUID variant(String title) {
            long n = IDS.incrementAndGet();
            return pg.queryForObject("INSERT INTO variants (tenant_id, product_id, external_id, sku, title) " +
                "VALUES (?, ?, ?, ?, ?) RETURNING id", UUID.class, tenant, product, "gid://shopify/ProductVariant/" + n, "ET-" + n, title);
        }

        /** A returned-to-sender forward leg whose order has one untracked line of {@code variant}: [awb, shipmentId, lineId]. */
        String[] rtoParcel(UUID variant) {
            long n = IDS.incrementAndGet();
            UUID order = pg.queryForObject("INSERT INTO orders (tenant_id, store_id, external_id, number, status, payment_method, placed_at) " +
                "VALUES (?, ?, ?, ?, 'new'::order_status, 'cod'::order_payment_method, now()) RETURNING id",
                UUID.class, tenant, store, "gid://shopify/Order/" + n, "#BRK-" + n);
            String awb = String.valueOf(5_600_000_000L + n % 1_000_000L);
            UUID shipment = pg.queryForObject(
                "INSERT INTO shipments (tenant_id, order_id, provider, tracking_number, internal_state, shipment_leg, raw) " +
                "VALUES (?, ?, 'bosta', ?, 'returned'::shipment_internal_state, 'forward', " +
                "'{\"type\": {\"code\": 20, \"value\": \"Return to Origin\"}, \"state\": {\"code\": 46, \"value\": \"Returned to business\"}}'::jsonb) " +
                "RETURNING id", UUID.class, tenant, order, awb);
            UUID line = pg.queryForObject("INSERT INTO order_items (tenant_id, order_id, variant_id, quantity) VALUES (?, ?, ?, 1) RETURNING id",
                UUID.class, tenant, order, variant);
            return new String[] { awb, shipment.toString(), line.toString() };
        }

        /** An available, tagged piece at the main warehouse: its short code. */
        String[] availablePiece(UUID variant) {
            String id = UlidGenerator.generate();
            String code = "H" + String.format("%06d", IDS.incrementAndGet() % 1_000_000L);
            pg.update("INSERT INTO pieces (id, tenant_id, variant_id, barcode, short_code, status, current_location_id, last_event_at) " +
                "VALUES (?, ?, ?, ?, ?, 'available'::piece_status, ?, now())", id, tenant, variant, "HB-" + id, code, main);
            return new String[] { id, code };
        }
    }

    // ── h1 ─────────────────────────────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void h1_worker_linkThenUndo_overHttp_asAppUser_refusalCarriesReason() {
        String s = openSession(a, a.workerToken);
        String[] parcel = a.rtoParcel(a.tee);
        scan(a.workerToken, s, parcel[0]);
        String[] piece = a.availablePiece(a.tee);
        scan(a.workerToken, s, piece[1]);

        Map<String, Object> item = item(get(a.workerToken, s), piece[0]);
        assertThat((List<Map<String, Object>>) item.get("linkCandidates")).singleElement()
            .satisfies(c -> assertThat(c.get("orderItemId")).isEqualTo(parcel[2]));

        ResponseEntity<Map> linked = post(a.workerToken, "/api/v1/returns/sessions/" + s + "/items/" + piece[0] + "/link-unit",
            Map.of("shipmentId", parcel[1], "orderItemId", parcel[2], "unitNo", 1, "condition", "sellable"));
        assertThat(linked.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(linked.getBody().get("disposition")).isEqualTo("linked");
        assertThat((Map<String, Object>) linked.getBody().get("unitLink")).containsEntry("pieceId", piece[0]);
        assertThat((List<?>) get(a.workerToken, s).get("unitLinks")).hasSize(1);

        ResponseEntity<Map> undone = post(a.workerToken, "/api/v1/returns/sessions/" + s + "/items/" + piece[0] + "/link-unit/undo", Map.of());
        assertThat(undone.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(undone.getBody().get("disposition")).isEqualTo("pending");
        assertThat(get(a.workerToken, s)).doesNotContainKey("unitLinks");

        String[] other = a.availablePiece(a.scarf);
        scan(a.workerToken, s, other[1]);
        ResponseEntity<Map> refused = post(a.workerToken, "/api/v1/returns/sessions/" + s + "/items/" + other[0] + "/link-unit",
            Map.of("shipmentId", parcel[1], "orderItemId", parcel[2], "unitNo", 1, "condition", "sellable"));
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(refused.getBody().get("code")).isEqualTo("LINK_REFUSED");
        assertThat((Map<String, Object>) refused.getBody().get("details")).containsEntry("reason", "unit_not_linkable");
        assertThat(refused.getBody().get("message_en")).isNotNull();
        assertThat(refused.getBody().get("message_ar")).isNotNull();
        abandon(a.ownerToken, s);
    }

    // ── h2 ─────────────────────────────────────────────────────────────────────

    @Test
    void h2_otherTenantsToken_404_nothingChanges() {
        String s = openSession(a, a.workerToken);
        String[] parcel = a.rtoParcel(a.tee);
        scan(a.workerToken, s, parcel[0]);
        String[] piece = a.availablePiece(a.tee);
        scan(a.workerToken, s, piece[1]);

        ResponseEntity<Map> cross = post(b.ownerToken, "/api/v1/returns/sessions/" + s + "/items/" + piece[0] + "/link-unit",
            Map.of("shipmentId", parcel[1], "orderItemId", parcel[2], "unitNo", 1, "condition", "sellable"));
        assertThat(cross.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(pg.queryForObject("SELECT COUNT(*) FROM untracked_unit_piece_links WHERE piece_id = ?", Integer.class, piece[0]))
            .isZero();
        assertThat(pg.queryForObject("SELECT disposition FROM return_session_items WHERE piece_id = ?", String.class, piece[0]))
            .isEqualTo("pending");
        abandon(a.ownerToken, s);
    }

    // ── h3 ─────────────────────────────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void h3_closeWithIllegalRow_needsReview_alertListed_resolveClearsIt() {
        String s = openSession(b, b.workerToken);
        String[] piece = b.availablePiece(b.tee);
        scan(b.workerToken, s, piece[1]);

        ResponseEntity<Map> closed = post(b.workerToken, "/api/v1/returns/sessions/" + s + "/close", Map.of());
        assertThat(closed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(closed.getBody()).containsEntry("needsReviewCount", 1);

        List<Map<String, Object>> alerts = alerts(b.ownerToken).stream()
            .filter(e -> "return_needs_review".equals(e.get("type"))).toList();
        assertThat(alerts).singleElement().satisfies(e ->
            assertThat((String) e.get("actionUrl")).isEqualTo("/returns?session=" + s + "&piece=" + piece[0]));
        String key = (String) alerts.get(0).get("subject_key");

        ResponseEntity<Map> resolved = post(b.ownerToken, "/api/v1/exceptions/resolve",
            Map.of("exceptionType", "return_needs_review", "subjectKey", key, "note", "nothing to do"));
        assertThat(resolved.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(alerts(b.ownerToken)).noneMatch(e -> "return_needs_review".equals(e.get("type")));
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private String openSession(Shop s, String token) {
        ResponseEntity<Map> r = post(token, "/api/v1/returns/sessions", Map.of());
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return (String) r.getBody().get("sessionId");
    }

    private void abandon(String token, String session) {
        rest.exchange("/api/v1/returns/sessions/" + session, HttpMethod.DELETE, new HttpEntity<>(headers(token)), Map.class);
    }

    private void scan(String token, String session, String code) {
        Map<String, Object> body = new HashMap<>();
        body.put("scan", code);
        ResponseEntity<Map> r = post(token, "/api/v1/returns/sessions/" + session + "/scan", body);
        assertThat(r.getStatusCode().is2xxSuccessful()).as("scan " + code + " → " + r.getBody()).isTrue();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> get(String token, String session) {
        ResponseEntity<Map> r = rest.exchange("/api/v1/returns/sessions/" + session, HttpMethod.GET,
            new HttpEntity<>(headers(token)), Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        return r.getBody();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> item(Map<String, Object> detail, String pieceId) {
        return ((List<Map<String, Object>>) detail.get("items")).stream()
            .filter(i -> pieceId.equals(i.get("piece_id"))).findFirst().orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> alerts(String token) {
        ResponseEntity<Map> r = rest.exchange("/api/v1/exceptions?size=200", HttpMethod.GET, new HttpEntity<>(headers(token)), Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        return (List<Map<String, Object>>) r.getBody().get("items");
    }

    private ResponseEntity<Map> post(String token, String url, Map<String, ?> body) {
        return rest.exchange(url, HttpMethod.POST, new HttpEntity<>(body, headers(token)), Map.class);
    }

    private static HttpHeaders headers(String token) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }
}
