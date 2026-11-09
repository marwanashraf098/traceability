package com.traceability;

import com.fasterxml.jackson.databind.JsonNode;
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
import com.traceability.inventory.ShopifyReturnIntake;
import com.traceability.inventory.ShopifyShadowOrders;
import com.traceability.inventory.UlidGenerator;
import com.traceability.portal.ReturnRequestService;
import com.traceability.portal.ShopifyReturnSyncJob;
import com.traceability.portal.ShopifyReturnSyncService;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Shopify-native returns S5b (option A, approved 2026-11-08) — portal and Lookup EXCHANGES go to Shopify as a RETURN
 * ONLY (the item coming back, never an exchange line) — and the "Try again" fix (prod 2026-11-08, RR-ZMCAUG: a Try
 * again on a request whose 5 automatic attempts had run out left no new attempt and no trace).
 * Shopify is faked at the {@link ShopifyReturnsWriteGateway} / {@link ShopifyReturnsGateway} boundary; the real
 * stock path (Step 5a's −1 through {@link ShopifyGateway#pushExchangeDispatch}) runs against a mocked gateway.
 *
 * <pre>
 *   r1  capped failed (5 attempts, error stored) → POST Try again → 202 {queued}, attempts 0, error cleared, audited with
 *       the actor (audit_log + request event), the sync job enqueued at once → EXACTLY ONE new returnCreate → created
 *   r2  refusals are coded 409s with EN + AR text (switch off, already in Shopify, in progress); unlisted tenant → 404
 *   x1  portal exchange approved → ONE returnCreate: the old item only, note "Traced RR-… · exchanged for <product /
 *       variant> · collect EGP 150 via Bosta · Exchanged via Traced — don't refund in Shopify"; own courier says "via courier"
 *   x2  old item graded → returnProcess NOT_RESTOCKED; 'exchanged' → returnClose
 *   x3  closed exchange_failed before anything came back → returnCancel (no process, no close)
 *   x4  Lookup exchange: created + nudged; dispatched → Step 5a −1 EXACTLY ONCE (sync on); the Traced-created return,
 *       even mirrored WITH an exchange line, makes no S3b shadow and expects nothing back (S4); control: a native one does
 *   x5  Lookup cancel-exchange before anything came back → returnCancel
 *   x6  switched to refund before approval → synced as a refund (reason label), still one return
 *   x7  cross-tenant: B can't retry / sync A's exchange; A can (same-tenant control). The HTTP calls run on this
 *       class's postgres app (BYPASSRLS — their 404 is the tenant_id filter, not RLS); only the service check runs
 *       as app_user. The real HTTP path on app_user + RLS is ShopifyReturnSyncHttpAppUserTest.
 *   x8  the end arrives BEFORE the grading (exchanged while the old item is still awaited / back but ungraded): no
 *       close, no cancel, no Shopify read while pending; once graded → process THEN close (in that order)
 *   x9  customer owed (cheaper replacement) → "refund EGP X to customer"; nothing owed / to collect → no money part
 *   g1  golden, unlisted tenant: approving / creating an exchange enqueues no sync job, no drawer key, no Shopify call
 * </pre>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ShopifyExchangeReturnSyncTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            TestPostgres.newContainer()
                    .withDatabaseName("traceability_test")
                    .withUsername("postgres")
                    .withPassword("postgres");

    static { POSTGRES.start(); }

    static final UUID TENANT_A = UUID.fromString("5b5b0000-0000-4000-8000-0000000000a1");
    static final UUID TENANT_B = UUID.fromString("5b5b0000-0000-4000-8000-0000000000b2");
    static final UUID TENANT_OFF = UUID.fromString("5b5b0000-0000-4000-8000-0000000000c3");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",      POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        r.add("spring.flyway.url",          POSTGRES::getJdbcUrl);
        r.add("spring.flyway.user",         POSTGRES::getUsername);
        r.add("spring.flyway.password",     POSTGRES::getPassword);
        r.add("analytics.settlement.refresh-enabled", () -> "false");
        r.add("traced.shopify-returns.backfill.enabled", () -> "false");
        r.add("shopify.webhook-topics-backfill.enabled", () -> "false");
        r.add("traced.shopify-returns-sync.tenants", () -> TENANT_A + "," + TENANT_B);
    }

    @Autowired TestRestTemplate rest;
    @Autowired JwtService jwt;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired ShopifyReturnSyncService sync;
    @Autowired ShopifyReturnSyncJob syncJob;
    @Autowired ReturnRequestService requests;
    @Autowired ShopifyShadowOrders shadows;
    @MockBean ShopifyGateway shopifyGateway;
    @MockBean ShopifyTokenProvider tokenProvider;
    @MockBean JobScheduler jobScheduler;
    @MockBean BostaV2Client bostaV2;
    @MockBean ShopifyReturnsGateway reads;
    @MockBean ShopifyReturnsWriteGateway writes;

    static final AtomicLong IDS = new AtomicLong(53_000_000_000L);

    Shop a, b, off;
    Fake shopify;

    @BeforeAll
    void setUp() {
        a = new Shop(TENANT_A, "a");
        b = new Shop(TENANT_B, "b");
        off = new Shop(TENANT_OFF, "off");
    }

    @BeforeEach
    void stubs() {
        reset(shopifyGateway, tokenProvider, jobScheduler, bostaV2, reads, writes);
        when(tokenProvider.getValidToken(any())).thenReturn("tok");
        when(shopifyGateway.resolveInventoryItemId(anyString(), anyString(), anyString())).thenReturn("gid://shopify/InventoryItem/9");
        shopify = new Fake();
        shopify.wire();
    }

    @AfterEach
    void clear() { TenantContext.clear(); }

    // ── fake Shopify returns ───────────────────────────────────────────────────────────────────

    class Fake {
        final Map<String, List<ShopifyReturnsWriteGateway.Returnable>> returnable = new HashMap<>();
        final Map<String, ObjectNode> returns = new LinkedHashMap<>();
        final Deque<ReturnsWriteResponse> createAnswers = new ArrayDeque<>();

        void wire() {
            when(writes.returnableFulfillments(anyString(), anyString(), anyString())).thenAnswer(i ->
                new ShopifyReturnsWriteGateway.Returnables(returnable.getOrDefault(i.getArgument(2, String.class), List.of()), false));
            when(reads.fetchFallbackReturnReasonId(anyString(), anyString())).thenReturn("gid://shopify/ReturnReasonDefinition/1");
            when(writes.returnCreate(anyString(), anyString(), anyString(), any(), anyList())).thenAnswer(i -> {
                ReturnsWriteResponse ans = createAnswers.poll();
                if (ans != null) return ans;
                String orderGid = i.getArgument(2);
                List<ShopifyReturnsWriteGateway.CreateLine> lines = i.getArgument(4);
                long n = IDS.incrementAndGet();
                ObjectNode ret = mapper.createObjectNode().put("id", "gid://shopify/Return/" + n).put("name", "#X-R" + n)
                    .put("status", "OPEN");
                ret.putObject("order").put("id", orderGid);
                ArrayNode ln = ret.putObject("returnLineItems").putArray("nodes");
                ArrayNode rfo = ret.putObject("reverseFulfillmentOrders").putArray("nodes").addObject().put("id", "rfo-" + n)
                    .putObject("lineItems").putArray("nodes");
                for (ShopifyReturnsWriteGateway.CreateLine l : lines) {
                    String lineItem = returnable.get(orderGid).stream()
                        .filter(r -> r.fulfillmentLineItemGid().equals(l.fulfillmentLineItemGid())).findFirst().orElseThrow().lineItemGid();
                    ObjectNode node = ln.addObject().put("id", "gid://shopify/ReturnLineItem/" + n).put("quantity", l.quantity())
                        .put("processedQuantity", 0).put("returnReasonNote", l.note());
                    node.putObject("fulfillmentLineItem").put("id", l.fulfillmentLineItemGid()).putObject("lineItem").put("id", lineItem);
                    ObjectNode o = rfo.addObject().put("id", "rfol-" + n).put("totalQuantity", l.quantity());
                    o.putObject("fulfillmentLineItem").put("id", l.fulfillmentLineItemGid());
                    o.putArray("dispositions");
                }
                returns.put(ret.path("id").asText(), ret);
                ObjectNode p = mapper.createObjectNode();
                p.set("return", ret.deepCopy());
                p.putArray("userErrors");
                return ok(p);
            });
            when(reads.fetchReturn(anyString(), anyString(), anyString())).thenAnswer(i -> {
                ObjectNode r = returns.get(i.getArgument(2, String.class));
                return r == null ? null : r.deepCopy();
            });
            when(writes.returnProcessNotRestocked(anyString(), anyString(), anyString(), anyString(), any(), anyInt(), any()))
                .thenAnswer(i -> {
                    for (JsonNode l : returns.get(i.getArgument(2, String.class)).path("returnLineItems").path("nodes")) {
                        if (l.path("id").asText().equals(i.getArgument(3))) {
                            ((ObjectNode) l).put("processedQuantity", l.path("processedQuantity").asInt() + i.getArgument(5, Integer.class));
                        }
                    }
                    return okEmpty();
                });
            when(writes.returnClose(anyString(), anyString(), anyString())).thenAnswer(i -> {
                returns.get(i.getArgument(2, String.class)).put("status", "CLOSED");
                return okEmpty();
            });
            when(writes.returnCancel(anyString(), anyString(), anyString())).thenAnswer(i -> {
                returns.get(i.getArgument(2, String.class)).put("status", "CANCELED");
                return okEmpty();
            });
            when(writes.removeFromReturn(anyString(), anyString(), anyString(), anyString(), anyInt())).thenAnswer(i -> okEmpty());
        }
    }

    static ReturnsWriteResponse ok(ObjectNode payload) {
        return new ReturnsWriteResponse(ReturnsWriteResponse.Kind.OK, payload, null, null, List.of());
    }

    ReturnsWriteResponse okEmpty() {
        return ok((ObjectNode) mapper.createObjectNode().set("userErrors", mapper.createArrayNode()));
    }

    static ReturnsWriteResponse definite() {
        return new ReturnsWriteResponse(ReturnsWriteResponse.Kind.DEFINITE, null, null, "userErrors [NOT_FOUND]", List.of("NOT_FOUND"));
    }

    // ── fixture ────────────────────────────────────────────────────────────────────────────────

    /** A store on its own courier (Lookup's gate), no Bosta account, Shopify returns approved, sync switched on. */
    class Shop {
        final UUID tenant, store = UUID.randomUUID(), main = UUID.randomUUID(), product, m, l, owner, worker;
        final String ownerToken, workerToken, domain;

        Shop(UUID tenant, String tag) {
            this.tenant = tenant;
            this.domain = "s5b-" + tag + ".myshopify.com";
            jdbc.update("INSERT INTO tenants (id, name, portal_slug, generic_courier_enabled, generic_default_carrier, " +
                "    portal_generic_returns_enabled, portal_exchanges_enabled, shopify_returns_sync_enabled, shopify_returns_sync_since) " +
                "VALUES (?, ?, ?, true, 'QP Express', true, true, true, now() - interval '1 day')",
                tenant, "S5b " + tag, "s5b-" + tag);
            jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status, connection_type, returns_access, " +
                "access_token_scopes, import_status) VALUES (?, ?, 'shopify', ?, 'connected', 'oauth', 'granted', " +
                "'read_orders,write_inventory,read_products,write_returns,read_returns,write_locations,read_locations', 'idle')",
                store, tenant, domain);
            jdbc.update("INSERT INTO locations (id, tenant_id, name, is_fulfillment, shopify_location_id, shopify_sync_status) " +
                "VALUES (?, ?, 'Main', true, ?, 'linked')", main, tenant, "gid://shopify/Location/" + IDS.incrementAndGet());
            owner = user("owner");
            worker = user("worker");
            product = jdbc.queryForObject("INSERT INTO products (tenant_id, store_id, external_id, title, status) " +
                "VALUES (?, ?, ?, 'Linen Shirt', 'active') RETURNING id", UUID.class, tenant, store,
                "gid://shopify/Product/" + IDS.incrementAndGet());
            m = variant("White / M");
            l = variant("White / L");
            ownerToken = jwt.issueAccessToken(owner, tenant, "owner");
            workerToken = jwt.issueAccessToken(worker, tenant, "worker");
        }

        private UUID user(String role) {
            return jdbc.queryForObject("INSERT INTO users (tenant_id, name, email, password_hash, role, active) " +
                "VALUES (?, ?, ?, 'x', ?::user_role, true) RETURNING id",
                UUID.class, tenant, role, role + "-" + UUID.randomUUID() + "@test.com", role);
        }

        private UUID variant(String title) {
            long n = IDS.incrementAndGet();
            return jdbc.queryForObject("INSERT INTO variants (tenant_id, product_id, external_id, sku, title, " +
                "    shopify_inventory_item_id) VALUES (?, ?, ?, ?, ?, ?) RETURNING id", UUID.class, tenant, product,
                "gid://shopify/ProductVariant/" + n, "LS-" + n, title, "gid://shopify/InventoryItem/" + n);
        }

        <T> T as(java.util.concurrent.Callable<T> body) { return TenantContext.runAs(tenant, body); }
    }

    /** A delivered Shopify order: one line (size M) with its LineItem GID, one delivered piece, Shopify can return it. */
    class Order {
        final UUID id, item;
        final String gid, lineGid, fli, piece;
        Order(Shop s, boolean genericLeg) {
            gid = "gid://shopify/Order/" + IDS.incrementAndGet();
            lineGid = "gid://shopify/LineItem/" + IDS.incrementAndGet();
            fli = "gid://shopify/FulfillmentLineItem/" + IDS.incrementAndGet();
            String number = "#" + (IDS.incrementAndGet() % 100000);
            id = jdbc.queryForObject(
                "INSERT INTO orders (tenant_id, store_id, external_id, number, status, customer_name, customer_phone, cod_amount, " +
                "    payment_method, placed_at, shopify_address) VALUES (?, ?, ?, ?, 'delivered', 'Mona Adel', '01012345678', 900, " +
                "    'cod', now() - interval '8 days', '{\"address1\": \"5 Tahrir St\", \"city\": \"Cairo\"}'::jsonb) RETURNING id",
                UUID.class, s.tenant, s.store, gid, number);
            item = jdbc.queryForObject("INSERT INTO order_items (tenant_id, order_id, variant_id, quantity, external_id) " +
                "VALUES (?, ?, ?, 1, ?) RETURNING id", UUID.class, s.tenant, id, s.m, lineGid);
            piece = piece(s, s.m, "delivered", id, null);
            jdbc.update("INSERT INTO allocations (tenant_id, order_item_id, piece_id, status) VALUES (?, ?, ?, 'packed'::allocation_status)",
                s.tenant, item, piece);
            if (genericLeg) {
                jdbc.update("INSERT INTO shipments (tenant_id, order_id, provider, tracking_number, tracking_key, carrier_name, " +
                    "    internal_state, shipment_leg, source, handed_over_at, delivered_at, created_at) VALUES (?, ?, 'generic', ?, ?, " +
                    "    'QP Express', 'delivered', 'forward', 'shopify_fulfillment', now() - interval '6 days', now() - interval '4 days', " +
                    "    now() - interval '7 days')", s.tenant, id, "QP-" + IDS.incrementAndGet(), "QP" + IDS.incrementAndGet());
            }
            shopify.returnable.put(gid, List.of(new ShopifyReturnsWriteGateway.Returnable(fli, lineGid, 1,
                Instant.parse("2026-11-01T10:00:00Z"))));
        }
    }

    String piece(Shop s, UUID variant, String status, UUID order, UUID location) {
        String id = UlidGenerator.generate();
        String code = "P" + String.format("%06d", Math.floorMod(id.hashCode(), 999_999) + 1);
        jdbc.update("INSERT INTO pieces (id, tenant_id, variant_id, barcode, short_code, status, current_order_id, current_location_id, " +
            "    last_event_at, last_user_id) VALUES (?, ?, ?, ?, ?, ?::piece_status, ?, ?, now(), ?)",
            id, s.tenant, variant, "PC-" + id, code, status, order, location, s.worker);
        jdbc.update("INSERT INTO piece_events (tenant_id, piece_id, event_type, actor_user_id, from_status, to_status) " +
            "VALUES (?, ?, 'received', ?, NULL, 'available'::piece_status)", s.tenant, id, s.worker);
        return id;
    }

    /** A portal exchange request for the order's delivered piece → size L, still 'requested'. */
    UUID portalExchange(Shop s, Order o, boolean refundFallback) {
        UUID id = jdbc.queryForObject("INSERT INTO return_requests (tenant_id, order_id, type, status, reference, refund_fallback_ok) " +
            "VALUES (?, ?, 'exchange', 'requested', ?, ?) RETURNING id", UUID.class, s.tenant, o.id, reference(), refundFallback);
        jdbc.update("INSERT INTO return_request_items (tenant_id, request_id, piece_id, variant_id, reason_code, replacement_variant_id) " +
            "VALUES (?, ?, ?, ?, 'wrong_size', ?)", s.tenant, id, o.piece, s.m, s.l);
        piece(s, s.l, "available", null, s.main);   // the replacement is in stock
        return id;
    }

    void approve(Shop s, UUID request) {
        s.as(() -> { requests.approve(request, s.owner); return null; });
    }

    static String reference() {
        String alphabet = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ";
        StringBuilder sb = new StringBuilder("RR-");
        java.util.concurrent.ThreadLocalRandom rnd = java.util.concurrent.ThreadLocalRandom.current();
        for (int i = 0; i < 6; i++) sb.append(alphabet.charAt(rnd.nextInt(alphabet.length())));
        return sb.toString();
    }

    String ref(UUID request) {
        return jdbc.queryForObject("SELECT reference FROM return_requests WHERE id = ?", String.class, request);
    }

    Map<String, Object> syncRow(UUID request) {
        return jdbc.queryForMap("SELECT shopify_sync_status, shopify_sync_reason, shopify_sync_error, shopify_sync_attempts, " +
            "shopify_sync_attempted_at, shopify_return_gid FROM return_requests WHERE id = ?", request);
    }

    void grade(UUID request) {
        jdbc.update("UPDATE return_request_items SET item_status = 'done', active = false, arrived_at = now(), done_at = now(), " +
            "arrived_condition = 'sellable' WHERE request_id = ?", request);
    }

    void status(UUID request, String st) {
        jdbc.update("UPDATE return_requests SET status = ?::return_request_status WHERE id = ?", st, request);
    }

    int calls(String method) {
        return (int) mockingDetails(writes).getInvocations().stream().filter(i -> i.getMethod().getName().equals(method)).count();
    }

    List<ShopifyReturnsWriteGateway.CreateLine> createdLines(int n) {
        List<Invocation> creates = mockingDetails(writes).getInvocations().stream()
            .filter(i -> i.getMethod().getName().equals("returnCreate")).toList();
        return creates.get(n).getArgument(4);
    }

    /** The (tenant, request) of every ShopifyReturnSyncJob enqueued so far (other jobs are left out). */
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
                    out.add(new UUID[] { p.getArgument(1), p.getArgument(0) });
                }
            }
        }
        return out;
    }

    /** Runs every enqueued sync job through the real job bean (as JobRunr would). */
    void runSyncJobs() {
        for (UUID[] j : syncJobsEnqueued()) syncJob.sync(j[1], j[0]);
    }

    ResponseEntity<Map> post(String token, String path) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        h.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(null, h), Map.class);
    }

    ResponseEntity<Map> postJson(String token, String path, Object body) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        h.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(body, h), Map.class);
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> drawer(Shop s, UUID request) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(s.ownerToken);
        return rest.exchange("/api/v1/return-requests/" + request, HttpMethod.GET, new HttpEntity<>(h), Map.class).getBody();
    }

    String expectedNote(UUID request, String tail) {
        return "Traced " + ref(request) + " · exchanged for Linen Shirt / White / L" + tail
            + " · Exchanged via Traced — don't refund in Shopify";
    }

    // ── r: Try again ───────────────────────────────────────────────────────────────────────────

    @Test
    void r1_cappedFailed_tryAgain_freshCycle_audited_exactlyOneNewCreate() {
        Order o = new Order(a, true);
        UUID rr = portalExchange(a, o, false);
        for (int i = 0; i < 5; i++) shopify.createAnswers.add(definite());
        approve(a, rr);
        for (int i = 0; i < 7; i++) sync.sync(a.tenant, rr);
        Map<String, Object> capped = syncRow(rr);
        assertThat(capped.get("shopify_sync_status")).isEqualTo("failed");
        assertThat(capped.get("shopify_sync_attempts")).isEqualTo(5);
        assertThat(capped.get("shopify_sync_error")).isNotNull();
        assertThat(calls("returnCreate")).isEqualTo(5);
        sync.sweepTenant(a.tenant);
        assertThat(calls("returnCreate")).as("the cap holds on its own").isEqualTo(5);
        clearInvocations(jobScheduler);

        ResponseEntity<Map> r = post(a.ownerToken, "/api/v1/return-requests/" + rr + "/shopify-sync/retry");
        assertThat(r.getStatusCode().value()).as(String.valueOf(r.getBody())).isEqualTo(202);
        assertThat(r.getBody()).containsEntry("status", "queued").containsEntry("fromStatus", "failed");
        Map<String, Object> reset = syncRow(rr);
        assertThat(reset.get("shopify_sync_status")).isEqualTo("failed");
        assertThat(reset.get("shopify_sync_attempts")).isEqualTo(0);
        assertThat(reset.get("shopify_sync_error")).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE tenant_id = ? AND action = 'shopify_return_sync_retry' " +
            "AND actor_user_id = ? AND target_id = ?", Integer.class, a.tenant, a.owner, rr.toString())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT actor FROM return_request_events WHERE request_id = ? " +
            "AND event_type = 'shopify_sync_retry'", UUID.class, rr)).isEqualTo(a.owner);
        assertThat(syncJobsEnqueued()).as("enqueued right away").hasSize(1);

        runSyncJobs();
        assertThat(calls("returnCreate")).as("exactly one new attempt").isEqualTo(6);
        assertThat(syncRow(rr).get("shopify_sync_status")).isEqualTo("created");
        assertThat(syncRow(rr).get("shopify_sync_attempts")).isEqualTo(1);
        runSyncJobs();
        sync.sweepTenant(a.tenant);
        assertThat(calls("returnCreate")).isEqualTo(6);
    }

    @Test
    void r2_refusals_codedWithBothLanguages_unlisted404() {
        Order o = new Order(a, true);
        UUID rr = portalExchange(a, o, false);
        approve(a, rr);
        sync.sync(a.tenant, rr);
        assertThat(syncRow(rr).get("shopify_sync_status")).isEqualTo("created");

        ResponseEntity<Map> created = post(a.ownerToken, "/api/v1/return-requests/" + rr + "/shopify-sync/retry");
        assertThat(created.getStatusCode().value()).isEqualTo(409);
        assertThat(created.getBody()).containsEntry("code", "SHOPIFY_SYNC_ALREADY_CREATED").containsKeys("message_en", "message_ar");

        UUID pending = portalExchange(a, new Order(a, true), false);
        approve(a, pending);
        jdbc.update("UPDATE return_requests SET shopify_sync_status = 'pending', shopify_sync_attempted_at = now() WHERE id = ?", pending);
        assertThat(post(a.ownerToken, "/api/v1/return-requests/" + pending + "/shopify-sync/retry").getBody())
            .containsEntry("code", "SHOPIFY_SYNC_IN_PROGRESS");

        UUID failed = portalExchange(a, new Order(a, true), false);
        approve(a, failed);
        jdbc.update("UPDATE return_requests SET shopify_sync_status = 'failed', shopify_sync_attempts = 5 WHERE id = ?", failed);
        jdbc.update("UPDATE tenants SET shopify_returns_sync_enabled = false WHERE id = ?", a.tenant);
        try {
            ResponseEntity<Map> offR = post(a.ownerToken, "/api/v1/return-requests/" + failed + "/shopify-sync/retry");
            assertThat(offR.getStatusCode().value()).isEqualTo(409);
            assertThat(offR.getBody()).containsEntry("code", "SHOPIFY_SYNC_OFF");
            assertThat((String) offR.getBody().get("message_en")).contains("switched off");
            assertThat(syncRow(failed).get("shopify_sync_attempts")).as("a refusal changes nothing").isEqualTo(5);
        } finally {
            jdbc.update("UPDATE tenants SET shopify_returns_sync_enabled = true WHERE id = ?", a.tenant);
        }
        assertThat(post(a.workerToken, "/api/v1/return-requests/" + failed + "/shopify-sync/retry").getStatusCode().value())
            .isEqualTo(403);

        UUID offReq = portalExchange(off, new Order(off, true), false);
        assertThat(post(off.ownerToken, "/api/v1/return-requests/" + offReq + "/shopify-sync/retry").getStatusCode().value())
            .isEqualTo(404);
    }

    // ── x: exchanges ───────────────────────────────────────────────────────────────────────────

    @Test
    void x1_portalExchange_returnOnly_noteNamesTheReplacement_collectViaBosta_ownCourierSaysCourier() {
        // A Bosta order (no generic leg) with a frozen collect of 150.
        Order o = new Order(a, false);
        UUID rr = portalExchange(a, o, false);
        jdbc.update("INSERT INTO return_request_amounts (request_id, tenant_id, item_value, fee_kind, fee_amount, collect_cod) " +
            "VALUES (?, ?, 500, 'exchange', 150, 150)", rr, a.tenant);
        approve(a, rr);
        assertThat(syncJobsEnqueued()).anySatisfy(j -> assertThat(j[1]).isEqualTo(rr));
        runSyncJobs();

        assertThat(calls("returnCreate")).isEqualTo(1);
        assertThat(createdLines(0)).singleElement().satisfies(l -> {
            assertThat(l.fulfillmentLineItemGid()).isEqualTo(o.fli);
            assertThat(l.quantity()).isEqualTo(1);
            assertThat(l.note()).isEqualTo(expectedNote(rr, " · collect EGP 150 via Bosta"));
            assertThat(l.returnReasonDefinitionGid()).isEqualTo("gid://shopify/ReturnReasonDefinition/1");
        });
        assertThat(syncRow(rr).get("shopify_sync_status")).isEqualTo("created");
        assertThat(jdbc.queryForObject("SELECT shopify_sync_verified_at IS NOT NULL FROM return_requests WHERE id = ?",
            Boolean.class, rr)).isTrue();

        // The store's own courier (G4b, booking 'manual'), no frozen collect.
        Order g = new Order(a, true);
        UUID gr = portalExchange(a, g, false);
        approve(a, gr);
        assertThat(jdbc.queryForObject("SELECT booking_status FROM return_requests WHERE id = ?", String.class, gr)).isEqualTo("manual");
        sync.sync(a.tenant, gr);
        assertThat(createdLines(1)).singleElement().satisfies(l -> assertThat(l.note()).isEqualTo(expectedNote(gr, "")));

        Map<String, Object> d = drawer(a, rr);
        assertThat((Map<String, Object>) d.get("shopifySync")).containsEntry("status", "created");
    }

    @Test
    void x2_graded_processedNotRestocked_exchanged_closed() {
        Order o = new Order(a, true);
        UUID rr = portalExchange(a, o, false);
        approve(a, rr);
        sync.sync(a.tenant, rr);
        grade(rr);
        sync.sync(a.tenant, rr);
        assertThat(calls("returnProcessNotRestocked")).isEqualTo(1);
        assertThat(calls("returnClose")).isZero();
        status(rr, "exchanged");
        sync.sync(a.tenant, rr);
        assertThat(calls("returnClose")).isEqualTo(1);
        assertThat(calls("returnCancel")).isZero();
        sync.sync(a.tenant, rr);
        sync.sweepTenant(a.tenant);
        assertThat(calls("returnClose")).isEqualTo(1);
        assertThat(calls("returnProcessNotRestocked")).isEqualTo(1);
    }

    @Test
    void x3_exchangeFailedBeforeAnythingCameBack_cancelled() {
        Order o = new Order(a, true);
        UUID rr = portalExchange(a, o, false);
        approve(a, rr);
        sync.sync(a.tenant, rr);
        clearInvocations(jobScheduler);
        a.as(() -> { requests.close(rr, "exchange_failed", null, a.owner); return null; });
        assertThat(syncJobsEnqueued()).anySatisfy(j -> assertThat(j[1]).isEqualTo(rr));
        runSyncJobs();
        assertThat(calls("returnCancel")).isEqualTo(1);
        assertThat(calls("returnProcessNotRestocked")).isZero();
        assertThat(calls("returnClose")).isZero();
    }

    @Test
    void x4_lookupExchange_decrementedOnce_tracedReturnNeverNative_controlNativeIs() throws Exception {
        Order o = new Order(a, true);
        String spare = piece(a, a.l, "available", null, a.main);
        jdbc.update("UPDATE pieces SET short_code = ? WHERE id = ?", "S" + (IDS.incrementAndGet() % 1_000_000), spare);
        ResponseEntity<Map> created = postJson(a.ownerToken, "/api/v1/pieces/" + spare + "/exchange",
            Map.of("orderId", o.id.toString(), "returningPieceId", o.piece));
        assertThat(created.getStatusCode().value()).as(String.valueOf(created.getBody())).isEqualTo(201);
        UUID rr = UUID.fromString((String) created.getBody().get("requestId"));
        assertThat(syncJobsEnqueued()).as("nudged on create").anySatisfy(j -> assertThat(j[1]).isEqualTo(rr));
        runSyncJobs();
        assertThat(calls("returnCreate")).isEqualTo(1);
        assertThat(createdLines(0).get(0).note()).isEqualTo(expectedNote(rr, ""));

        // The replacement leaves (tracking entered) → Step 5a's −1, exactly once, with the sync on.
        ResponseEntity<Map> booked = postJson(a.ownerToken, "/api/v1/return-requests/" + rr + "/manual-courier",
            Map.of("outboundTrackingNumber", "QP-OUT-" + IDS.incrementAndGet()));
        assertThat(booked.getStatusCode().value()).as(String.valueOf(booked.getBody())).isEqualTo(200);
        awaitDispatch(spare);
        assertThat(jdbc.queryForList("SELECT delta || ':' || status FROM shopify_inventory_adjustments " +
            "WHERE trigger_type = 'exchange_dispatch' AND trigger_id = ?", String.class, spare)).containsExactly("-1:applied");
        verify(shopifyGateway, times(1)).pushExchangeDispatch(anyString(), anyString(), anyString(), anyString(),
            eq(-1), eq("traced://piece/" + spare), anyString());
        sync.sync(a.tenant, rr);
        sync.sweepTenant(a.tenant);
        verify(shopifyGateway, times(1)).pushExchangeDispatch(anyString(), anyString(), anyString(), anyString(),
            anyInt(), anyString(), anyString());
        verify(shopifyGateway, never()).adjustInventoryQuantities(any(), any(), any(), any(), anyInt(), any(), any());

        // The Traced-created return mirrored (even WITH an exchange line, which Traced never sends): no S3b shadow, nothing
        // expected back (S4). Control: a native return with an exchange line on another order gets its shadow.
        String gid = (String) syncRow(rr).get("shopify_return_gid");
        UUID traced = mirror(a, o, gid, "Traced " + ref(rr) + " · exchanged for Linen Shirt / White / L");
        Order nat = new Order(a, true);
        UUID nativeRet = mirror(a, nat, "gid://shopify/Return/" + IDS.incrementAndGet(), "too big");
        a.as(() -> { shadows.reconcileForOrder(o.id); shadows.reconcileForOrder(nat.id); return null; });
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM shopify_exchange_shadows WHERE tenant_id = ? AND return_id = ?",
            Integer.class, a.tenant, traced)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM shopify_exchange_shadows WHERE tenant_id = ? AND return_id = ?",
            Integer.class, a.tenant, nativeRet)).as("control").isEqualTo(1);
        assertThat(a.as(() -> ShopifyReturnIntake.expects(jdbc, a.tenant, "no-piece", o.id, a.m))).isFalse();
        assertThat(a.as(() -> ShopifyReturnIntake.expects(jdbc, a.tenant, "no-piece", nat.id, a.m))).as("control").isTrue();
    }

    /** A mirrored Shopify return of {@code o}: one return line of its line item, plus one exchange line (size L). */
    UUID mirror(Shop s, Order o, String gid, String note) {
        UUID ret = jdbc.queryForObject(
            "INSERT INTO shopify_returns (tenant_id, store_id, return_gid, order_id, order_gid, name, status, raw, " +
            "    shopify_created_at, request_approved_at) VALUES (?, ?, ?, ?, ?, '#M-R1', 'OPEN', '{}'::jsonb, now(), now()) RETURNING id",
            UUID.class, s.tenant, s.store, gid, o.id, o.gid);
        jdbc.update("INSERT INTO shopify_return_lines (tenant_id, return_id, line_gid, fulfillment_line_gid, variant_gid, variant_id, " +
            "quantity, reason_note, raw) VALUES (?, ?, ?, ?, (SELECT external_id FROM variants WHERE id = ?), ?, 1, ?, '{}'::jsonb)",
            s.tenant, ret, "gid://shopify/ReturnLineItem/" + IDS.incrementAndGet(), o.fli, s.m, s.m, note);
        jdbc.update("INSERT INTO shopify_exchange_lines (tenant_id, return_id, line_gid, variant_gid, variant_id, quantity, raw) " +
            "VALUES (?, ?, ?, (SELECT external_id FROM variants WHERE id = ?), ?, 1, '{}'::jsonb)",
            s.tenant, ret, "gid://shopify/ExchangeLineItem/" + IDS.incrementAndGet(), s.l, s.l);
        return ret;
    }

    void awaitDispatch(String pieceId) throws InterruptedException {
        for (int i = 0; i < 200; i++) {
            Integer done = jdbc.queryForObject("SELECT COUNT(*) FROM shopify_inventory_adjustments WHERE trigger_type = 'exchange_dispatch' " +
                "AND trigger_id = ? AND status <> 'pending'", Integer.class, pieceId);
            if (done != null && done >= 1) return;
            Thread.sleep(50);
        }
        throw new AssertionError("timed out waiting for the exchange_dispatch claim of " + pieceId);
    }

    @Test
    void x5_lookupCancelExchange_beforeAnythingCameBack_cancelled() {
        Order o = new Order(a, true);
        String spare = piece(a, a.l, "available", null, a.main);
        jdbc.update("UPDATE pieces SET short_code = ? WHERE id = ?", "S" + (IDS.incrementAndGet() % 1_000_000), spare);
        ResponseEntity<Map> created = postJson(a.ownerToken, "/api/v1/pieces/" + spare + "/exchange",
            Map.of("orderId", o.id.toString(), "returningPieceId", o.piece));
        UUID rr = UUID.fromString((String) created.getBody().get("requestId"));
        runSyncJobs();
        assertThat(calls("returnCreate")).isEqualTo(1);
        clearInvocations(jobScheduler);

        assertThat(post(a.ownerToken, "/api/v1/return-requests/" + rr + "/cancel-exchange").getStatusCode().value()).isEqualTo(204);
        assertThat(syncJobsEnqueued()).as("nudged on cancel").anySatisfy(j -> assertThat(j[1]).isEqualTo(rr));
        runSyncJobs();
        assertThat(calls("returnCancel")).isEqualTo(1);
        assertThat(calls("returnClose")).isZero();
        verify(shopifyGateway, never()).pushExchangeDispatch(any(), any(), any(), any(), anyInt(), any(), any());
    }

    @Test
    void x6_switchedToRefundBeforeApproval_syncedAsARefund_oneReturn() {
        Order o = new Order(a, true);
        UUID rr = portalExchange(a, o, true);
        a.as(() -> { requests.switchToRefundAndApprove(rr, a.owner); return null; });
        runSyncJobs();
        assertThat(calls("returnCreate")).isEqualTo(1);
        assertThat(createdLines(0).get(0).note()).isEqualTo("Traced " + ref(rr) + " · Wrong size");
        assertThat(jdbc.queryForObject("SELECT type FROM return_requests WHERE id = ?", String.class, rr)).isEqualTo("refund");
        grade(rr);
        status(rr, "refunded");
        sync.sync(a.tenant, rr);
        assertThat(calls("returnProcessNotRestocked")).isEqualTo(1);
        assertThat(calls("returnClose")).isEqualTo(1);
        assertThat(calls("returnCreate")).isEqualTo(1);
    }

    @Test
    void x7_crossTenant_bCantRetryOrSyncA_aCan() {
        Order o = new Order(a, true);
        UUID rr = portalExchange(a, o, false);
        approve(a, rr);
        jdbc.update("UPDATE return_requests SET shopify_sync_status = 'failed', shopify_sync_attempts = 5 WHERE id = ?", rr);

        // HTTP on this class's postgres app: the 404 comes from the tenant_id filter (RLS is bypassed here).
        assertThat(post(b.ownerToken, "/api/v1/return-requests/" + rr + "/shopify-sync/retry").getStatusCode().value()).isEqualTo(404);
        assertThat(syncRow(rr).get("shopify_sync_attempts")).isEqualTo(5);
        // The service as app_user under B's tenant sees nothing of A's request.
        com.traceability.tenancy.TenantAwareDataSource ds = new com.traceability.tenancy.TenantAwareDataSource(
            new org.springframework.jdbc.datasource.DriverManagerDataSource(POSTGRES.getJdbcUrl(), "app_user", "testpw"));
        ShopifyReturnSyncService appSync = new ShopifyReturnSyncService(new JdbcTemplate(ds),
            new org.springframework.jdbc.datasource.DataSourceTransactionManager(ds), writes, reads, tokenProvider,
            new com.traceability.portal.ShopifyReturnSyncRollout(TENANT_A + "," + TENANT_B));
        appSync.sync(b.tenant, rr);
        assertThat(calls("returnCreate")).isZero();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> appSync.retry(b.tenant, rr, b.owner))
            .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThat(syncRow(rr).get("shopify_sync_attempts")).isEqualTo(5);

        // Same tenant: A can.
        assertThat(post(a.ownerToken, "/api/v1/return-requests/" + rr + "/shopify-sync/retry").getStatusCode().value()).isEqualTo(202);
        appSync.sync(a.tenant, rr);
        assertThat(calls("returnCreate")).isEqualTo(1);
        assertThat(syncRow(rr).get("shopify_sync_status")).isEqualTo("created");
    }

    // ── order of events, note money ────────────────────────────────────────────────────────────

    @Test
    void x8_exchangedBeforeGraded_endPending_thenProcessThenClose() {
        Order o = new Order(a, true);
        UUID rr = portalExchange(a, o, false);
        approve(a, rr);
        sync.sync(a.tenant, rr);
        assertThat(syncRow(rr).get("shopify_sync_status")).isEqualTo("created");

        // The end comes first: 'exchanged' while the old item is still awaited, then back but not graded.
        status(rr, "exchanged");
        clearInvocations(reads);
        sync.sync(a.tenant, rr);
        sync.sweepTenant(a.tenant);
        jdbc.update("UPDATE return_request_items SET item_status = 'arrived', arrived_at = now() WHERE request_id = ?", rr);
        sync.sync(a.tenant, rr);
        sync.sweepTenant(a.tenant);
        assertThat(calls("returnCancel")).as("never cancelled while the item may still be graded").isZero();
        assertThat(calls("returnClose")).isZero();
        assertThat(calls("returnProcessNotRestocked")).isZero();
        verify(reads, never()).fetchReturn(anyString(), anyString(), anyString());

        // Graded → processed, then closed — in that order.
        jdbc.update("UPDATE return_request_items SET item_status = 'done', active = false, done_at = now(), " +
            "arrived_condition = 'sellable' WHERE request_id = ?", rr);
        sync.sync(a.tenant, rr);
        org.mockito.InOrder order = inOrder(writes);
        order.verify(writes).returnProcessNotRestocked(anyString(), anyString(), anyString(), anyString(), any(), anyInt(), any());
        order.verify(writes).returnClose(anyString(), anyString(), anyString());
        assertThat(calls("returnCancel")).isZero();
        assertThat(calls("returnClose")).isEqualTo(1);
        String gid = (String) syncRow(rr).get("shopify_return_gid");
        assertThat(shopify.returns.get(gid).path("status").asText()).isEqualTo("CLOSED");
    }

    @Test
    void x9_customerOwed_refundToCustomer_zero_noMoneyPart() {
        Order o = new Order(a, false);
        UUID rr = portalExchange(a, o, false);
        jdbc.update("INSERT INTO return_request_amounts (request_id, tenant_id, item_value, fee_kind, fee_amount, collect_cod, " +
            "customer_owed) VALUES (?, ?, 500, 'exchange', 0, 0, 120)", rr, a.tenant);
        approve(a, rr);
        sync.sync(a.tenant, rr);
        assertThat(createdLines(0).get(0).note()).isEqualTo(expectedNote(rr, " · refund EGP 120 to customer"));

        Order z = new Order(a, false);
        UUID zero = portalExchange(a, z, false);
        jdbc.update("INSERT INTO return_request_amounts (request_id, tenant_id, item_value, fee_kind, fee_amount, collect_cod, " +
            "customer_owed) VALUES (?, ?, 500, 'exchange', 0, 0, 0)", zero, a.tenant);
        approve(a, zero);
        sync.sync(a.tenant, zero);
        assertThat(createdLines(1).get(0).note()).isEqualTo(expectedNote(zero, ""))
            .doesNotContain("EGP 0").doesNotContain("collect").doesNotContain("refund EGP");
    }

    // ── golden ─────────────────────────────────────────────────────────────────────────────────

    @Test
    void g1_unlistedTenant_exchangesUntouched() {
        Order o = new Order(off, true);
        UUID rr = portalExchange(off, o, false);
        approve(off, rr);
        Order o2 = new Order(off, true);
        String spare = piece(off, off.l, "available", null, off.main);
        jdbc.update("UPDATE pieces SET short_code = ? WHERE id = ?", "S" + (IDS.incrementAndGet() % 1_000_000), spare);
        ResponseEntity<Map> created = postJson(off.ownerToken, "/api/v1/pieces/" + spare + "/exchange",
            Map.of("orderId", o2.id.toString(), "returningPieceId", o2.piece));
        assertThat(created.getStatusCode().value()).as(String.valueOf(created.getBody())).isEqualTo(201);
        UUID lookup = UUID.fromString((String) created.getBody().get("requestId"));
        assertThat(post(off.ownerToken, "/api/v1/return-requests/" + lookup + "/cancel-exchange").getStatusCode().value()).isEqualTo(204);

        assertThat(syncJobsEnqueued()).isEmpty();
        assertThat(drawer(off, rr)).doesNotContainKey("shopifySync");
        assertThat(drawer(off, lookup)).doesNotContainKey("shopifySync");
        sync.sync(off.tenant, rr);
        sync.sync(off.tenant, lookup);
        assertThat(sync.sweepTenant(off.tenant)).isZero();
        assertThat(mockingDetails(writes).getInvocations()).isEmpty();
        assertThat(syncRow(rr).get("shopify_sync_status")).isNull();
        assertThat(syncRow(lookup).get("shopify_sync_status")).isNull();
    }
}
