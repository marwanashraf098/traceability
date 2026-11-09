package com.traceability;

import com.traceability.identity.JwtService;
import com.traceability.integrations.bosta.BostaV2Client;
import com.traceability.integrations.shopify.ShopifyGateway;
import com.traceability.integrations.shopify.ShopifyOrderPriceGateway;
import com.traceability.integrations.shopify.ShopifyTokenProvider;
import com.traceability.inventory.ShopifyShadowOrders;
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
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * The 2026-11-09 sweep for "a DB read / write outside any transaction" (after RR-ZMCAUG's Try again 404): each endpoint
 * below did its DB work with no transaction open, so TenantAwareConnection never set app.current_tenant and on
 * app_user RLS hid every row (reads → nothing) or refused the row (audit_log INSERT → WITH CHECK). The whole app runs
 * as app_user here, over the real HTTP path, at the URLs frontend/src/api.ts builds.
 *
 *   t1  GET /api/v1/return-requests/{id}/refund-suggestion → 200 with an amount (was 404 "Return request not found"
 *       on every request); tenant B → 404, same-tenant control 200
 *   t2  POST /api/v1/shopify-exchanges/{orderId}/release-reservation → 200, the shadow ended, ONE audit row (was:
 *       released, then 500 and no audit row); tenant B → 404, A's shadow still live
 *   t3  POST /api/v1/shopify/location/resend-failed → 200 + ONE audit row (was 500 after the resend)
 *
 * Revert checks: RefundSuggestionService without its readTx → t1 red (404); AuditService.record without
 * {@code @Transactional} → t2 and t3 red (500, no audit row).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TenantTxSweepHttpAppUserTest {

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

    /** Fixtures and assertions only (postgres); the app never uses it. */
    JdbcTemplate pg;
    static final AtomicLong IDS = new AtomicLong(58_000_000_000L);
    Shop a, b, c;

    @BeforeAll
    void setUp() {
        pg = new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        a = new Shop("a");
        b = new Shop("b");
        c = new Shop("c");
        c.healthyShopifySetup();
    }

    @BeforeEach
    void stubs() {
        reset(shopifyGateway, tokenProvider, prices, jobScheduler, bostaV2);
        when(tokenProvider.getValidToken(any())).thenThrow(new IllegalStateException("no Shopify in this test"));
    }

    @AfterEach
    void clear() { TenantContext.clear(); }

    class Shop {
        final UUID tenant = UUID.randomUUID(), store = UUID.randomUUID(), owner, variant;
        final String ownerToken;

        Shop(String tag) {
            pg.update("INSERT INTO tenants (id, name) VALUES (?, ?)", tenant, "Sweep " + tag);
            pg.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status) VALUES (?, ?, 'shopify', ?, 'disconnected')",
                store, tenant, "sweep-" + tag + "-" + IDS.incrementAndGet() + ".myshopify.com");
            owner = pg.queryForObject("INSERT INTO users (tenant_id, name, email, password_hash, role, active) " +
                "VALUES (?, 'owner', ?, 'x', 'owner'::user_role, true) RETURNING id",
                UUID.class, tenant, "owner-" + UUID.randomUUID() + "@test.com");
            UUID product = pg.queryForObject("INSERT INTO products (tenant_id, store_id, external_id, title, status) " +
                "VALUES (?, ?, ?, 'Linen Shirt', 'active') RETURNING id", UUID.class, tenant, store,
                "gid://shopify/Product/" + IDS.incrementAndGet());
            long n = IDS.incrementAndGet();
            variant = pg.queryForObject("INSERT INTO variants (tenant_id, product_id, external_id, sku, title, price) " +
                "VALUES (?, ?, ?, ?, 'White / M', 450.00) RETURNING id", UUID.class, tenant, product,
                "gid://shopify/ProductVariant/" + n, "LS-" + n);
            ownerToken = jwt.issueAccessToken(owner, tenant, "owner");
        }

        /** Connected store with the inventory scopes and a linked Traced Main location — Re-send failed updates runs. */
        void healthyShopifySetup() {
            pg.update("UPDATE stores SET status = 'connected', connection_type = 'oauth', " +
                "access_token_scopes = 'read_orders,read_products,write_inventory,read_locations,write_locations' WHERE id = ?", store);
            pg.update("INSERT INTO locations (tenant_id, name, is_fulfillment, shopify_location_id, shopify_sync_status) " +
                "VALUES (?, 'Main', true, ?, 'linked')", tenant, "gid://shopify/Location/" + IDS.incrementAndGet());
        }

        UUID order(String status) {
            return pg.queryForObject("INSERT INTO orders (tenant_id, store_id, external_id, number, status, placed_at) " +
                "VALUES (?, ?, ?, ?, ?::order_status, now() - interval '5 days') RETURNING id", UUID.class, tenant, store,
                "gid://shopify/Order/" + IDS.incrementAndGet(), "#" + (IDS.incrementAndGet() % 100000), status);
        }

        /** An approved refund request whose one item came back (Arrived, sellable). */
        UUID arrivedRefund() {
            UUID order = order("delivered");
            String piece = UlidGenerator.generate();
            pg.update("INSERT INTO pieces (id, tenant_id, variant_id, barcode, short_code, status, current_order_id) " +
                "VALUES (?, ?, ?, ?, 'P' || LPAD((abs(hashtext(?)) % 999999 + 1)::text, 6, '0'), 'delivered'::piece_status, ?)",
                piece, tenant, variant, "PC-" + piece, piece, order);
            UUID rr = pg.queryForObject("INSERT INTO return_requests (tenant_id, order_id, type, status, reference, decided_at) " +
                "VALUES (?, ?, 'refund', 'received', ?, now()) RETURNING id", UUID.class, tenant, order, reference());
            pg.update("INSERT INTO return_request_items (tenant_id, request_id, piece_id, variant_id, reason_code, item_status, " +
                "    active, arrived_at) VALUES (?, ?, ?, ?, 'wrong_size', 'arrived', true, now())", tenant, rr, piece, variant);
            return rr;
        }

        /** A live Shopify-native exchange shadow (nothing picked) — what Waiting for release lists. */
        UUID liveShadow() {
            long n = IDS.incrementAndGet();
            UUID original = order("delivered");
            String returnGid = "gid://shopify/Return/" + n;
            UUID ret = pg.queryForObject(
                "INSERT INTO shopify_returns (tenant_id, store_id, return_gid, order_id, order_gid, name, status, " +
                "    request_approved_at, raw) VALUES (?, ?, ?, ?, ?, ?, 'OPEN', now(), '{}'::jsonb) RETURNING id",
                UUID.class, tenant, store, returnGid, original, "gid://shopify/Order/" + n, "#" + n + "-R1");
            UUID shadow = pg.queryForObject(
                "INSERT INTO orders (tenant_id, store_id, external_id, number, status, origin, placed_at) " +
                "VALUES (?, ?, ?, ?, 'confirmed'::order_status, 'shopify_exchange', now()) RETURNING id",
                UUID.class, tenant, store, ShopifyShadowOrders.PREFIX + returnGid, "#" + n + "-X1");
            pg.update("INSERT INTO shopify_exchange_shadows (tenant_id, store_id, return_id, order_id, original_order_id, " +
                "    x_index, approved_at) VALUES (?, ?, ?, ?, ?, 1, now())", tenant, store, ret, shadow, original);
            return shadow;
        }

        int audits(String action) {
            return pg.queryForObject("SELECT COUNT(*) FROM audit_log WHERE tenant_id = ? AND action = ? AND actor_user_id = ?",
                Integer.class, tenant, action, owner);
        }
    }

    static String reference() {
        String alphabet = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ";
        StringBuilder sb = new StringBuilder("RR-");
        java.util.concurrent.ThreadLocalRandom rnd = java.util.concurrent.ThreadLocalRandom.current();
        for (int i = 0; i < 6; i++) sb.append(alphabet.charAt(rnd.nextInt(alphabet.length())));
        return sb.toString();
    }

    @SuppressWarnings("rawtypes")
    ResponseEntity<Map> call(HttpMethod method, String token, String path) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        h.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange(path, method, new HttpEntity<>(null, h), Map.class);
    }

    String shadowState(UUID shadowOrder) {
        return pg.queryForObject("SELECT state FROM shopify_exchange_shadows WHERE order_id = ?", String.class, shadowOrder);
    }

    // ── tests ──────────────────────────────────────────────────────────────────────────────────

    @Test
    @SuppressWarnings("rawtypes")
    void t1_refundSuggestion_200_onAppUser_crossTenant404_withControl() {
        UUID rr = a.arrivedRefund();
        String url = "/api/v1/return-requests/" + rr + "/refund-suggestion";

        ResponseEntity<Map> other = call(HttpMethod.GET, b.ownerToken, url);
        assertThat(other.getStatusCode().value()).isEqualTo(404);

        ResponseEntity<Map> own = call(HttpMethod.GET, a.ownerToken, url);
        assertThat(own.getStatusCode().value()).isEqualTo(200);
        assertThat(own.getBody()).containsEntry("amount", "450.00").containsEntry("source", "catalog");
    }

    @Test
    @SuppressWarnings("rawtypes")
    void t2_releaseReservation_200_shadowEnded_oneAuditRow_crossTenant404() {
        UUID shadow = a.liveShadow();
        String url = "/api/v1/shopify-exchanges/" + shadow + "/release-reservation";

        assertThat(call(HttpMethod.POST, b.ownerToken, url).getStatusCode().value()).isEqualTo(404);
        assertThat(shadowState(shadow)).isEqualTo("live");

        ResponseEntity<Map> own = call(HttpMethod.POST, a.ownerToken, url);
        assertThat(own.getStatusCode().value()).isEqualTo(200);
        assertThat(own.getBody()).containsEntry("state", "ended").containsEntry("endReason", "manual_release");
        assertThat(shadowState(shadow)).isEqualTo("ended");
        assertThat(a.audits("shopify_exchange_release")).isEqualTo(1);
        assertThat(b.audits("shopify_exchange_release")).isZero();
    }

    @Test
    @SuppressWarnings("rawtypes")
    void t3_resendFailed_200_oneAuditRow() {
        ResponseEntity<Map> r = call(HttpMethod.POST, c.ownerToken, "/api/v1/shopify/location/resend-failed");
        assertThat(r.getStatusCode().value()).isEqualTo(200);
        assertThat(r.getBody()).containsKeys("increments", "pieceUpdates", "applied");
        assertThat(c.audits("shopify_location_resend_failed")).isEqualTo(1);
        assertThat(a.audits("shopify_location_resend_failed")).isZero();
    }
}
