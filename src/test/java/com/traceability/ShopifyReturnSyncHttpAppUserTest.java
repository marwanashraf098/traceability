package com.traceability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.traceability.identity.JwtService;
import com.traceability.integrations.bosta.BostaV2Client;
import com.traceability.integrations.shopify.ReturnsWriteResponse;
import com.traceability.integrations.shopify.ShopifyGateway;
import com.traceability.integrations.shopify.ShopifyReturnsGateway;
import com.traceability.integrations.shopify.ShopifyReturnsWriteGateway;
import com.traceability.integrations.shopify.ShopifyTokenProvider;
import com.traceability.inventory.UlidGenerator;
import com.traceability.portal.ShopifyReturnSyncJob;
import com.traceability.tenancy.TenantContext;
import org.jobrunr.jobs.lambdas.IocJobLambda;
import org.jobrunr.scheduling.JobScheduler;
import org.junit.jupiter.api.*;
import org.mockito.invocation.Invocation;
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

import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * S5a drawer actions over the REAL HTTP path with the WHOLE app on app_user + RLS — the way prod runs.
 *
 * Prod 2026-11-09 (RR-ZMCAUG): Try again answered a bare 404 every time. The controller's ownership check was a bare
 * JdbcTemplate read outside any transaction; TenantAwareConnection sets app.current_tenant only when a transaction
 * starts, so on app_user RLS hid the row. ShopifyReturnSyncTest / ShopifyExchangeReturnSyncTest post the same URLs
 * but run the app as postgres (BYPASSRLS), which is why they never saw it. The URLs here are the ones
 * frontend/src/api.ts builds (pinned by shopifySyncHttpPath.test.tsx).
 *
 *   h1  capped failed refund request → POST …/shopify-sync/retry → 202 {queued}, attempts 0, error cleared, an
 *       audit row, the job runs → exactly ONE new returnCreate
 *   h2  …/shopify-sync/check → 202 (job enqueued); …/shopify-sync/checked on a needs_review request → 204, resolved
 *   h3  cross-tenant: B posts A's id on all three → 404, A's row unchanged; same-tenant control: A → 202
 *   h4  a tenant not in TRACED_SHOPIFY_RETURNS_SYNC_TENANTS → bare 404 (no body), nothing written (golden)
 *
 * Revert check: putting the bare read back in ShopifyReturnSyncController.own() turns h1, h2 and h3's control red
 * (404).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ShopifyReturnSyncHttpAppUserTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            TestPostgres.newContainer()
                    .withDatabaseName("traceability_test")
                    .withUsername("postgres")
                    .withPassword("postgres");

    static {
        POSTGRES.start();
        // Migrations grant to app_user; the role must exist with a password before the app connects as it.
        try (var c = java.sql.DriverManager.getConnection(POSTGRES.getJdbcUrl(), "postgres", "postgres");
             var st = c.createStatement()) {
            st.execute("CREATE ROLE app_user LOGIN PASSWORD 'testpw'");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static final UUID TENANT_A = UUID.fromString("5a0f0000-0000-4000-8000-0000000000a1");
    static final UUID TENANT_B = UUID.fromString("5a0f0000-0000-4000-8000-0000000000b2");
    static final UUID TENANT_OFF = UUID.fromString("5a0f0000-0000-4000-8000-0000000000c3");

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
        r.add("traced.shopify-returns-sync.tenants", () -> TENANT_A + "," + TENANT_B);
    }

    @Autowired TestRestTemplate rest;
    @Autowired JwtService jwt;
    @Autowired ObjectMapper mapper;
    @Autowired ShopifyReturnSyncJob syncJob;
    @Autowired JdbcTemplate appJdbc;
    @MockBean ShopifyGateway shopifyGateway;
    @MockBean ShopifyTokenProvider tokenProvider;
    @MockBean JobScheduler jobScheduler;
    @MockBean BostaV2Client bostaV2;
    @MockBean ShopifyReturnsGateway reads;
    @MockBean ShopifyReturnsWriteGateway writes;

    /** Fixtures and assertions only — postgres, outside the app. The app itself never uses this connection. */
    JdbcTemplate pg;

    static final AtomicLong IDS = new AtomicLong(57_000_000_000L);
    final Map<String, List<ShopifyReturnsWriteGateway.Returnable>> returnable = new HashMap<>();
    final Map<String, ObjectNode> returns = new HashMap<>();
    Shop a, b, off;

    @BeforeAll
    void setUp() {
        pg = new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        a = new Shop(TENANT_A, "a");
        b = new Shop(TENANT_B, "b");
        off = new Shop(TENANT_OFF, "off");
    }

    @BeforeEach
    void stubs() {
        reset(shopifyGateway, tokenProvider, jobScheduler, bostaV2, reads, writes);
        when(tokenProvider.getValidToken(any())).thenReturn("tok");
        when(reads.fetchFallbackReturnReasonId(anyString(), anyString())).thenReturn("gid://shopify/ReturnReasonDefinition/1");
        when(writes.returnableFulfillments(anyString(), anyString(), anyString())).thenAnswer(i ->
            new ShopifyReturnsWriteGateway.Returnables(returnable.getOrDefault(i.getArgument(2, String.class), List.of()), false));
        when(writes.returnCreate(anyString(), anyString(), anyString(), any(), anyList())).thenAnswer(i -> {
            String orderGid = i.getArgument(2);
            List<ShopifyReturnsWriteGateway.CreateLine> lines = i.getArgument(4);
            long n = IDS.incrementAndGet();
            ObjectNode ret = mapper.createObjectNode().put("id", "gid://shopify/Return/" + n).put("name", "#X-R" + n)
                .put("status", "OPEN");
            ret.putObject("order").put("id", orderGid);
            ArrayNode ln = ret.putObject("returnLineItems").putArray("nodes");
            ret.putObject("reverseFulfillmentOrders").putArray("nodes");
            for (ShopifyReturnsWriteGateway.CreateLine l : lines) {
                String lineItem = returnable.get(orderGid).stream()
                    .filter(r -> r.fulfillmentLineItemGid().equals(l.fulfillmentLineItemGid())).findFirst().orElseThrow().lineItemGid();
                ObjectNode node = ln.addObject().put("id", "gid://shopify/ReturnLineItem/" + n).put("quantity", l.quantity())
                    .put("processedQuantity", 0).put("returnReasonNote", l.note());
                node.putObject("fulfillmentLineItem").put("id", l.fulfillmentLineItemGid()).putObject("lineItem").put("id", lineItem);
            }
            returns.put(ret.path("id").asText(), ret);
            ObjectNode p = mapper.createObjectNode();
            p.set("return", ret.deepCopy());
            p.putArray("userErrors");
            return new ReturnsWriteResponse(ReturnsWriteResponse.Kind.OK, p, null, null, List.of());
        });
        when(reads.fetchReturn(anyString(), anyString(), anyString())).thenAnswer(i -> {
            ObjectNode r = returns.get(i.getArgument(2, String.class));
            return r == null ? null : r.deepCopy();
        });
    }

    @AfterEach
    void clear() { TenantContext.clear(); }

    // ── fixture ────────────────────────────────────────────────────────────────────────────────

    class Shop {
        final UUID tenant, store = UUID.randomUUID(), product, variant, owner;
        final String ownerToken, domain;

        Shop(UUID tenant, String tag) {
            this.tenant = tenant;
            this.domain = "s5h-" + tag + ".myshopify.com";
            pg.update("INSERT INTO tenants (id, name, portal_slug, shopify_returns_sync_enabled, shopify_returns_sync_since) " +
                "VALUES (?, ?, ?, true, now() - interval '1 day')", tenant, "S5h " + tag, "s5h-" + tag);
            pg.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status, connection_type, returns_access, " +
                "access_token_scopes, import_status) VALUES (?, ?, 'shopify', ?, 'connected', 'oauth', 'granted', " +
                "'read_orders,write_inventory,read_products,write_returns,read_returns', 'idle')", store, tenant, domain);
            owner = pg.queryForObject("INSERT INTO users (tenant_id, name, email, password_hash, role, active) " +
                "VALUES (?, 'owner', ?, 'x', 'owner'::user_role, true) RETURNING id",
                UUID.class, tenant, "owner-" + UUID.randomUUID() + "@test.com");
            product = pg.queryForObject("INSERT INTO products (tenant_id, store_id, external_id, title, status) " +
                "VALUES (?, ?, ?, 'Linen Shirt', 'active') RETURNING id", UUID.class, tenant, store,
                "gid://shopify/Product/" + IDS.incrementAndGet());
            long n = IDS.incrementAndGet();
            variant = pg.queryForObject("INSERT INTO variants (tenant_id, product_id, external_id, sku, title, " +
                "    shopify_inventory_item_id) VALUES (?, ?, ?, ?, 'White / M', ?) RETURNING id", UUID.class, tenant, product,
                "gid://shopify/ProductVariant/" + n, "LS-" + n, "gid://shopify/InventoryItem/" + n);
            ownerToken = jwt.issueAccessToken(owner, tenant, "owner");
        }
    }

    /**
     * An approved refund request (decided after switch-on) for a delivered Shopify order whose line Shopify can
     * return, with the given Shopify sync state.
     */
    UUID request(Shop s, String syncStatus, int attempts, String error) {
        String gid = "gid://shopify/Order/" + IDS.incrementAndGet();
        String lineGid = "gid://shopify/LineItem/" + IDS.incrementAndGet();
        String fli = "gid://shopify/FulfillmentLineItem/" + IDS.incrementAndGet();
        UUID order = pg.queryForObject(
            "INSERT INTO orders (tenant_id, store_id, external_id, number, status, customer_name, customer_phone, cod_amount, " +
            "    payment_method, placed_at) VALUES (?, ?, ?, ?, 'delivered', 'Mona Adel', '01012345678', 900, 'cod', " +
            "    now() - interval '8 days') RETURNING id",
            UUID.class, s.tenant, s.store, gid, "#" + (IDS.incrementAndGet() % 100000));
        UUID item = pg.queryForObject("INSERT INTO order_items (tenant_id, order_id, variant_id, quantity, external_id) " +
            "VALUES (?, ?, ?, 1, ?) RETURNING id", UUID.class, s.tenant, order, s.variant, lineGid);
        String piece = UlidGenerator.generate();
        pg.update("INSERT INTO pieces (id, tenant_id, variant_id, barcode, short_code, status, current_order_id, " +
            "    last_event_at, last_user_id) VALUES (?, ?, ?, ?, ?, 'delivered'::piece_status, ?, now(), ?)",
            piece, s.tenant, s.variant, "PC-" + piece, "P" + String.format("%06d", Math.floorMod(piece.hashCode(), 999_999) + 1),
            order, s.owner);
        pg.update("INSERT INTO allocations (tenant_id, order_item_id, piece_id, status) VALUES (?, ?, ?, 'packed'::allocation_status)",
            s.tenant, item, piece);
        UUID rr = pg.queryForObject("INSERT INTO return_requests (tenant_id, order_id, type, status, reference, decided_at, " +
            "    shopify_sync_status, shopify_sync_attempts, shopify_sync_error) " +
            "VALUES (?, ?, 'refund', 'approved', ?, now(), ?, ?, ?) RETURNING id",
            UUID.class, s.tenant, order, reference(), syncStatus, attempts, error);
        pg.update("INSERT INTO return_request_items (tenant_id, request_id, piece_id, variant_id, reason_code) " +
            "VALUES (?, ?, ?, ?, 'wrong_size')", s.tenant, rr, piece, s.variant);
        returnable.put(gid, List.of(new ShopifyReturnsWriteGateway.Returnable(fli, lineGid, 1, Instant.parse("2026-11-01T10:00:00Z"))));
        return rr;
    }

    static String reference() {
        String alphabet = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ";
        StringBuilder sb = new StringBuilder("RR-");
        java.util.concurrent.ThreadLocalRandom rnd = java.util.concurrent.ThreadLocalRandom.current();
        for (int i = 0; i < 6; i++) sb.append(alphabet.charAt(rnd.nextInt(alphabet.length())));
        return sb.toString();
    }

    Map<String, Object> row(UUID rr) {
        return pg.queryForMap("SELECT shopify_sync_status, shopify_sync_attempts, shopify_sync_error, shopify_return_gid " +
            "FROM return_requests WHERE id = ?", rr);
    }

    @SuppressWarnings("rawtypes")
    ResponseEntity<Map> post(String token, String path) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        h.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(null, h), Map.class);
    }

    /** The URL frontend/src/api.ts builds: `${BASE}/return-requests/${id}/shopify-sync/${action}`, BASE = /api/v1. */
    static String url(UUID rr, String action) {
        return "/api/v1/return-requests/" + rr + "/shopify-sync/" + action;
    }

    int calls(String method) {
        return (int) mockingDetails(writes).getInvocations().stream().filter(i -> i.getMethod().getName().equals(method)).count();
    }

    /** The (tenant, request) of every ShopifyReturnSyncJob enqueued so far. */
    List<UUID[]> syncJobsEnqueued() {
        List<UUID[]> out = new ArrayList<>();
        for (Invocation i : mockingDetails(jobScheduler).getInvocations()) {
            if (!i.getMethod().getName().equals("enqueue")) continue;
            for (Object arg : i.getArguments()) {
                if (!(arg instanceof IocJobLambda<?> lambda)) continue;
                ShopifyReturnSyncJob probe = mock(ShopifyReturnSyncJob.class);
                try {
                    @SuppressWarnings("unchecked") IocJobLambda<ShopifyReturnSyncJob> l = (IocJobLambda<ShopifyReturnSyncJob>) lambda;
                    l.accept(probe);
                } catch (ClassCastException notOurs) {
                    continue;
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
                for (Invocation p : mockingDetails(probe).getInvocations()) {
                    out.add(new UUID[] { p.getArgument(1), p.getArgument(0) });   // job.sync(requestId, tenantId)
                }
            }
        }
        return out;
    }

    // ── tests ──────────────────────────────────────────────────────────────────────────────────

    @Test
    void h0_theAppReallyRunsAsAppUser() {
        assertThat(appJdbc.queryForObject("SELECT current_user", String.class)).isEqualTo("app_user");
        // …and a bare read outside a transaction sees no tenant row — the prod failure mode this class guards.
        TenantContext.set(TENANT_A);
        assertThat(appJdbc.queryForObject("SELECT COUNT(*) FROM tenants", Integer.class)).isZero();
    }

    @Test
    @SuppressWarnings("rawtypes")
    void h1_cappedFailed_tryAgain_202_freshCycle_audited_exactlyOneNewCreate() {
        UUID rr = request(a, "failed", 5, "userErrors [NOT_FOUND]");

        ResponseEntity<Map> r = post(a.ownerToken, url(rr, "retry"));
        assertThat(r.getStatusCode().value()).isEqualTo(202);
        assertThat(r.getBody()).containsEntry("status", "queued").containsEntry("fromStatus", "failed");
        assertThat(row(rr)).containsEntry("shopify_sync_attempts", 0).containsEntry("shopify_sync_error", null);
        assertThat(pg.queryForObject("SELECT COUNT(*) FROM audit_log WHERE tenant_id = ? AND action = 'shopify_return_sync_retry' " +
            "AND actor_user_id = ? AND target_id = ?", Integer.class, a.tenant, a.owner, rr.toString())).isEqualTo(1);
        assertThat(pg.queryForObject("SELECT COUNT(*) FROM return_request_events WHERE request_id = ? " +
            "AND event_type = 'shopify_sync_retry'", Integer.class, rr)).isEqualTo(1);

        List<UUID[]> jobs = syncJobsEnqueued();
        assertThat(jobs).anySatisfy(j -> assertThat(j[1]).isEqualTo(rr));
        for (UUID[] j : jobs) syncJob.sync(j[1], j[0]);

        assertThat(calls("returnCreate")).isEqualTo(1);
        assertThat(row(rr).get("shopify_sync_status")).isEqualTo("created");
        assertThat(row(rr).get("shopify_return_gid")).isNotNull();
    }

    @Test
    void h2_checkAgain_202_and_iCheckedIt_204() {
        UUID ambiguous = request(a, "failed_ambiguous", 1, "timeout");
        assertThat(post(a.ownerToken, url(ambiguous, "check")).getStatusCode().value()).isEqualTo(202);
        assertThat(syncJobsEnqueued()).anySatisfy(j -> assertThat(j[1]).isEqualTo(ambiguous));

        UUID review = request(a, "needs_review", 0, null);
        assertThat(post(a.ownerToken, url(review, "checked")).getStatusCode().value()).isEqualTo(204);
        assertThat(row(review).get("shopify_sync_status")).isEqualTo("resolved");
    }

    @Test
    void h3_crossTenant_404_onAllThree_withSameTenantControl() {
        UUID rr = request(a, "needs_review", 3, "x");
        Map<String, Object> before = row(rr);
        for (String action : List.of("retry", "check", "checked")) {
            assertThat(post(b.ownerToken, url(rr, action)).getStatusCode().value()).as(action).isEqualTo(404);
        }
        assertThat(row(rr)).isEqualTo(before);
        assertThat(syncJobsEnqueued()).noneSatisfy(j -> assertThat(j[1]).isEqualTo(rr));

        // Same-tenant control: A reaches the handler (a coded 409 — needs_review isn't a Try again state — not a 404).
        assertThat(post(a.ownerToken, url(rr, "retry")).getStatusCode().value()).isEqualTo(409);
        assertThat(post(a.ownerToken, url(rr, "check")).getStatusCode().value()).isEqualTo(202);
        assertThat(post(a.ownerToken, url(rr, "checked")).getStatusCode().value()).isEqualTo(204);
    }

    @Test
    @SuppressWarnings("rawtypes")
    void h4_unlistedTenant_bare404_nothingWritten() {
        UUID rr = request(off, "failed", 5, "e");
        Map<String, Object> before = row(rr);
        for (String action : List.of("retry", "check", "checked")) {
            ResponseEntity<Map> r = post(off.ownerToken, url(rr, action));
            assertThat(r.getStatusCode().value()).as(action).isEqualTo(404);
            assertThat(r.getBody()).as(action).isNull();
        }
        assertThat(row(rr)).isEqualTo(before);
        assertThat(syncJobsEnqueued()).isEmpty();
    }
}
