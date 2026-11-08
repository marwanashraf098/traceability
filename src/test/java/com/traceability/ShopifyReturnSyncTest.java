package com.traceability;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.traceability.integrations.shopify.ReturnsWriteResponse;
import com.traceability.integrations.shopify.ShopifyGateway;
import com.traceability.integrations.shopify.ShopifyReturnsGateway;
import com.traceability.integrations.shopify.ShopifyReturnsWriteGateway;
import com.traceability.integrations.shopify.ShopifyTokenProvider;
import com.traceability.inventory.ExceptionService;
import com.traceability.inventory.ShopifyReturnIntake;
import com.traceability.portal.ReturnRequestService;
import com.traceability.portal.ShopifyReturnSyncRollout;
import com.traceability.portal.ShopifyReturnSyncService;
import com.traceability.portal.ShopifyReturnSyncSettingsService;
import com.traceability.returncases.ShopifyReturnsView;
import com.traceability.tenancy.TenantAwareDataSource;
import com.traceability.tenancy.TenantContext;
import org.jobrunr.scheduling.JobScheduler;
import org.junit.jupiter.api.*;
import org.mockito.Mockito;
import org.mockito.invocation.Invocation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Shopify-native returns S5a — a REFUND request made on Traced's portal is also created as a Return in Shopify.
 * Shopify is faked at the {@link ShopifyReturnsWriteGateway} / {@link ShopifyReturnsGateway} boundary by
 * {@link FakeShopify} (one return per order, processed quantities, dispositions); the wire classification of the
 * one-attempt transport is ReturnsWriteClassificationWireTest.
 *
 * <pre>
 *   c1  approved → ONE returnCreate (lines from returnableFulfillments, marker + Traced reason in the note, no
 *       customer text), created + name, read-back verified; a second sync never creates again
 *   c2  DEFINITE refusal → failed, retried, created on the 2nd; 5 refusals → no 6th call + LOW alert
 *   c3  AMBIGUOUS → failed_ambiguous, NEVER re-sent: the marker read finds nothing (stays) then finds it (created)
 *   c5  two senders at once: the claim lets only one call returnCreate
 *   c6  (fix 2026-11-08) the shop's catch-all return reason id goes on every created line; the marker stays in the note
 *   c7  the reason read fails → the return is still created, without a reason, on the same single attempt
 *   c4  write_returns not in the token's scopes → skipped no_permission, no Shopify call; ACCESS_DENIED → skipped
 *   e1  not synced, no write: pre-connect order; not fulfilled in Shopify; fewer returnable units; and nothing at all
 *       for an exchange, a request decided before switch-on, a request still 'requested'
 *   p1  graded unit → returnProcess NOT_RESTOCKED (no location), applied once
 *   p2  NOT_RESTOCKED without a location refused → once more at the Traced Main location → applied
 *   p3  unconfirmed process: re-read shows it processed → applied; not processed → failed_ambiguous, never re-sent
 *   t1  refunded: close; Shopify closed it itself → recorded, no call
 *   t2  closed with nothing back → cancel; a refused cancel → needs_review, not retried
 *   t3  partial: one of two back → process 1, remove 1, close; a refused remove → needs_review, close still sent
 *   u1  arrival undone after Shopify processed it → LOW out_of_step alert, no Shopify write; "I checked it" clears it
 *   x1  a Traced-created mirrored return: not expected back (S4), not an S2 duplicate (card says createdByTraced),
 *       not a Returns list case S; S1b's shopify_restocked_units still counts it; control: a native return is
 *   x2  app_user + RLS: tenant B can't sync / see A's request; A can
 *   h1  approving (listed tenant) enqueues the sync job after commit
 *   s1  owner switch: on stamps since + one audit row; unlisted tenant → GET visible:false, PUT 404
 *   s2  app_user + RLS: the switch GET reads only the caller's own tenant (two listed tenants, both directions)
 *   g0  golden, unlisted tenant: approve enqueues nothing, the drawer has no shopifySync key, sync / sweep do nothing
 * </pre>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ShopifyReturnSyncTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            TestPostgres.newContainer()
                    .withDatabaseName("traceability_test")
                    .withUsername("postgres")
                    .withPassword("postgres");

    static { POSTGRES.start(); }

    static final UUID TENANT_A = UUID.fromString("5a5a0000-0000-4000-8000-0000000000a1");
    static final UUID TENANT_B = UUID.fromString("5a5a0000-0000-4000-8000-0000000000b2");
    static final UUID TENANT_OFF = UUID.fromString("5a5a0000-0000-4000-8000-0000000000c3");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",      POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        r.add("spring.flyway.url",          POSTGRES::getJdbcUrl);
        r.add("spring.flyway.user",         POSTGRES::getUsername);
        r.add("spring.flyway.password",     POSTGRES::getPassword);
        r.add("traced.shopify-returns.backfill.enabled", () -> "false");
        r.add("shopify.webhook-topics-backfill.enabled", () -> "false");
        r.add("traced.shopify-returns-sync.tenants", () -> TENANT_A + "," + TENANT_B);
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired ShopifyReturnSyncService sync;
    @Autowired ShopifyReturnSyncSettingsService settings;
    @Autowired ShopifyReturnSyncRollout rollout;
    @Autowired ReturnRequestService requests;
    @Autowired ExceptionService exceptions;
    @Autowired org.springframework.transaction.PlatformTransactionManager txm;
    @MockBean JobScheduler jobScheduler;
    @MockBean ShopifyGateway shopifyGateway;
    @MockBean ShopifyTokenProvider tokenProvider;
    @MockBean ShopifyReturnsGateway reads;
    @MockBean ShopifyReturnsWriteGateway writes;

    JdbcTemplate appJdbc;
    DataSourceTransactionManager appTxm;
    FakeShopify shopify;

    static final AtomicLong IDS = new AtomicLong(52_000_000_000L);
    static final String ALERT = "shopify_return_sync_check";

    Shop a, b, off;

    @BeforeAll
    void setUp() {
        DataSource ds = new TenantAwareDataSource(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "app_user", "testpw"));
        appJdbc = new JdbcTemplate(ds);
        appTxm = new DataSourceTransactionManager(ds);
        a = new Shop(TENANT_A, "a");
        b = new Shop(TENANT_B, "b");
        off = new Shop(TENANT_OFF, "off");
    }

    @BeforeEach
    void stubs() {
        Mockito.reset(shopifyGateway, tokenProvider, jobScheduler, reads, writes);
        when(tokenProvider.getValidToken(any())).thenReturn("tok");
        shopify = new FakeShopify();
        shopify.wire();
    }

    @AfterEach
    void clear() { TenantContext.clear(); }

    // ── fake Shopify ───────────────────────────────────────────────────────────────────────────

    /** Per order GID: what's returnable, and the return Traced created (lines, processed, dispositions, status). */
    class FakeShopify {
        final Map<String, List<ShopifyReturnsWriteGateway.Returnable>> returnable = new HashMap<>();
        final Map<String, FakeReturn> returns = new LinkedHashMap<>();
        final Deque<ReturnsWriteResponse> createAnswers = new ArrayDeque<>();
        final Deque<ReturnsWriteResponse> processAnswers = new ArrayDeque<>();
        final Deque<ReturnsWriteResponse> closeAnswers = new ArrayDeque<>();
        final Deque<ReturnsWriteResponse> cancelAnswers = new ArrayDeque<>();
        final Deque<ReturnsWriteResponse> removeAnswers = new ArrayDeque<>();
        /** Apply the effect even when the answer is AMBIGUOUS (Shopify did it, the answer was lost). */
        boolean applyOnAmbiguous = false;
        int n = 0;

        void wire() {
            when(writes.returnableFulfillments(anyString(), anyString(), anyString())).thenAnswer(i ->
                new ShopifyReturnsWriteGateway.Returnables(returnable.getOrDefault(i.getArgument(2, String.class), List.of()), false));
            when(writes.returnCreate(anyString(), anyString(), anyString(), any(), anyList())).thenAnswer(i -> {
                String orderGid = i.getArgument(2);
                List<ShopifyReturnsWriteGateway.CreateLine> lines = i.getArgument(4);
                ReturnsWriteResponse ans = createAnswers.isEmpty() ? null : createAnswers.poll();
                boolean apply = ans == null || ans.ok() || (ans.ambiguous() && applyOnAmbiguous);
                FakeReturn fr = null;
                if (apply) {
                    n = (int) IDS.incrementAndGet();
                    fr = new FakeReturn("gid://shopify/Return/" + n, "#R-" + n, orderGid);
                    for (ShopifyReturnsWriteGateway.CreateLine l : lines) {
                        fr.lines.add(new FakeLine("gid://shopify/ReturnLineItem/" + n + l.fulfillmentLineItemGid().hashCode(),
                            l.fulfillmentLineItemGid(), lineItemOf(orderGid, l.fulfillmentLineItemGid()), l.quantity(), l.note()));
                    }
                    returns.put(fr.gid, fr);
                }
                if (ans != null && !ans.ok()) return ans;
                ObjectNode p = mapper.createObjectNode();
                p.putObject("return").put("id", fr.gid).put("name", fr.name).put("status", "OPEN");
                p.putArray("userErrors");
                return ok(p);
            });
            when(reads.fetchReturn(anyString(), anyString(), anyString())).thenAnswer(i -> {
                FakeReturn fr = returns.get(i.getArgument(2, String.class));
                return fr == null ? null : fr.json();
            });
            when(writes.orderReturnNotes(anyString(), anyString(), anyString())).thenAnswer(i -> {
                ObjectNode order = mapper.createObjectNode().put("id", i.getArgument(2, String.class));
                ArrayNode nodes = order.putObject("returns").putArray("nodes");
                for (FakeReturn fr : returns.values()) {
                    if (!fr.orderGid.equals(i.getArgument(2))) continue;
                    ObjectNode r = nodes.addObject().put("id", fr.gid).put("name", fr.name).put("status", fr.status);
                    ArrayNode ln = r.putObject("returnLineItems").putArray("nodes");
                    for (FakeLine l : fr.lines) ln.addObject().put("quantity", l.quantity).put("returnReasonNote", l.note);
                }
                return order;
            });
            when(writes.returnProcessNotRestocked(anyString(), anyString(), anyString(), anyString(), any(), anyInt(), any()))
                .thenAnswer(i -> {
                    ReturnsWriteResponse ans = processAnswers.isEmpty() ? null : processAnswers.poll();
                    FakeReturn fr = returns.get(i.getArgument(2, String.class));
                    boolean apply = ans == null || ans.ok() || (ans.ambiguous() && applyOnAmbiguous);
                    if (apply) {
                        for (FakeLine l : fr.lines) if (l.id.equals(i.getArgument(3))) { l.processed += i.getArgument(5, Integer.class); l.disposed += i.getArgument(5, Integer.class); }
                    }
                    return ans != null ? ans : okEmpty();
                });
            when(writes.returnClose(anyString(), anyString(), anyString())).thenAnswer(i -> {
                ReturnsWriteResponse ans = closeAnswers.isEmpty() ? null : closeAnswers.poll();
                if (ans == null || ans.ok()) returns.get(i.getArgument(2, String.class)).status = "CLOSED";
                return ans != null ? ans : okEmpty();
            });
            when(writes.returnCancel(anyString(), anyString(), anyString())).thenAnswer(i -> {
                ReturnsWriteResponse ans = cancelAnswers.isEmpty() ? null : cancelAnswers.poll();
                if (ans == null || ans.ok()) returns.get(i.getArgument(2, String.class)).status = "CANCELED";
                return ans != null ? ans : okEmpty();
            });
            when(writes.removeFromReturn(anyString(), anyString(), anyString(), anyString(), anyInt())).thenAnswer(i -> {
                ReturnsWriteResponse ans = removeAnswers.isEmpty() ? null : removeAnswers.poll();
                if (ans == null || ans.ok()) {
                    for (FakeLine l : returns.get(i.getArgument(2, String.class)).lines) {
                        if (l.id.equals(i.getArgument(3))) l.quantity -= i.getArgument(4, Integer.class);
                    }
                }
                return ans != null ? ans : okEmpty();
            });
        }

        String lineItemOf(String orderGid, String fli) {
            for (ShopifyReturnsWriteGateway.Returnable r : returnable.getOrDefault(orderGid, List.of())) {
                if (r.fulfillmentLineItemGid().equals(fli)) return r.lineItemGid();
            }
            return null;
        }

        FakeReturn only() {
            assertThat(returns).hasSize(1);
            return returns.values().iterator().next();
        }
    }

    class FakeReturn {
        final String gid, name, orderGid;
        String status = "OPEN";
        final List<FakeLine> lines = new ArrayList<>();
        FakeReturn(String gid, String name, String orderGid) { this.gid = gid; this.name = name; this.orderGid = orderGid; }

        JsonNode json() {
            ObjectNode r = mapper.createObjectNode().put("id", gid).put("name", name).put("status", status);
            r.putObject("order").put("id", orderGid);
            ArrayNode ln = r.putObject("returnLineItems").putArray("nodes");
            ArrayNode rfo = r.putObject("reverseFulfillmentOrders").putArray("nodes").addObject().put("id", "rfo-" + gid)
                .putObject("lineItems").putArray("nodes");
            for (FakeLine l : lines) {
                ObjectNode n = ln.addObject().put("id", l.id).put("quantity", l.quantity).put("processedQuantity", l.processed)
                    .put("returnReasonNote", l.note);
                n.putObject("fulfillmentLineItem").put("id", l.fli).putObject("lineItem").put("id", l.lineItem);
                ObjectNode o = rfo.addObject().put("id", "rfol-" + l.id).put("totalQuantity", l.quantity);
                o.putObject("fulfillmentLineItem").put("id", l.fli);
                ArrayNode d = o.putArray("dispositions");
                if (l.disposed > 0) d.addObject().put("quantity", l.disposed).put("type", "NOT_RESTOCKED");
            }
            return r;
        }
    }

    static class FakeLine {
        final String id, fli, lineItem, note;
        int quantity, processed, disposed;
        FakeLine(String id, String fli, String lineItem, int quantity, String note) {
            this.id = id; this.fli = fli; this.lineItem = lineItem; this.quantity = quantity; this.note = note;
        }
    }

    static ReturnsWriteResponse ok(ObjectNode payload) {
        return new ReturnsWriteResponse(ReturnsWriteResponse.Kind.OK, payload, null, null, List.of());
    }

    ReturnsWriteResponse okEmpty() {
        return ok((ObjectNode) mapper.createObjectNode().set("userErrors", mapper.createArrayNode()));
    }

    static ReturnsWriteResponse definite() {
        return new ReturnsWriteResponse(ReturnsWriteResponse.Kind.DEFINITE, null, null, "userErrors [INVALID]", List.of("INVALID"));
    }

    static ReturnsWriteResponse ambiguous() {
        return new ReturnsWriteResponse(ReturnsWriteResponse.Kind.AMBIGUOUS, null, null, "HTTP 502", List.of());
    }

    static ReturnsWriteResponse denied() {
        return new ReturnsWriteResponse(ReturnsWriteResponse.Kind.ACCESS_DENIED, null, null, "ACCESS_DENIED", List.of());
    }

    // ── fixture ────────────────────────────────────────────────────────────────────────────────

    class Shop {
        final UUID tenant, store = UUID.randomUUID(), main = UUID.randomUUID(), user = UUID.randomUUID(), product;
        final UUID v;
        final String domain;
        final String mainGid = "gid://shopify/Location/" + IDS.incrementAndGet();

        Shop(UUID tenant, String tag) {
            this.tenant = tenant;
            this.domain = "s5a-" + tag + ".myshopify.com";
            jdbc.update("INSERT INTO tenants (id, name, shopify_returns_sync_enabled, shopify_returns_sync_since) " +
                "VALUES (?, ?, true, now() - interval '1 day')", tenant, "S5a " + tag);
            jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role) VALUES (?, ?, 'Owner', ?, 'h', 'owner')",
                user, tenant, "s5a-" + tag + "@test.local");
            jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status, connection_type, returns_access, " +
                "access_token_scopes, import_status, last_sync_at) VALUES (?, ?, 'shopify', ?, 'connected', 'oauth', 'granted', " +
                "'read_orders,write_inventory,read_products,write_returns,read_merchant_managed_fulfillment_orders', 'completed', now())",
                store, tenant, domain);
            jdbc.update("INSERT INTO locations (id, tenant_id, name, type, is_default, is_fulfillment, shopify_location_id, " +
                "shopify_sync_status) VALUES (?, ?, 'Main Warehouse', 'warehouse', true, true, ?, 'linked')", main, tenant, mainGid);
            product = jdbc.queryForObject("INSERT INTO products (tenant_id, store_id, external_id, title, status) " +
                "VALUES (?, ?, ?, 'TEE', 'active') RETURNING id", UUID.class, tenant, store, "gid://shopify/Product/" + IDS.incrementAndGet());
            long vn = IDS.incrementAndGet();
            v = jdbc.queryForObject("INSERT INTO variants (tenant_id, product_id, external_id, title, sku, shopify_inventory_item_id) " +
                "VALUES (?, ?, ?, 'Black / M', ?, ?) RETURNING id", UUID.class, tenant, product,
                "gid://shopify/ProductVariant/" + vn, "S5A-" + vn, "gid://shopify/InventoryItem/" + vn);
        }

        <T> T as(java.util.concurrent.Callable<T> body) { return TenantContext.runAs(tenant, body); }
    }

    /** An order with one line of {@code qty} units; its LineItem GID and the fulfillment line Shopify can return. */
    class Order {
        final UUID id, line;
        final String gid, lineGid, fli;
        Order(Shop s, int qty, int returnable) {
            gid = "gid://shopify/Order/" + IDS.incrementAndGet();
            lineGid = "gid://shopify/LineItem/" + IDS.incrementAndGet();
            fli = "gid://shopify/FulfillmentLineItem/" + IDS.incrementAndGet();
            id = jdbc.queryForObject("INSERT INTO orders (tenant_id, store_id, external_id, number, status, payment_method, placed_at) " +
                "VALUES (?, ?, ?, ?, 'delivered', 'cod', now() - interval '5 days') RETURNING id", UUID.class,
                s.tenant, s.store, gid, "#" + IDS.incrementAndGet() % 100000);
            line = jdbc.queryForObject("INSERT INTO order_items (tenant_id, order_id, variant_id, quantity, external_id) " +
                "VALUES (?, ?, ?, ?, ?) RETURNING id", UUID.class, s.tenant, id, s.v, qty, lineGid);
            if (returnable > 0) {
                shopify.returnable.put(gid, List.of(new ShopifyReturnsWriteGateway.Returnable(fli, lineGid, returnable,
                    Instant.parse("2026-11-01T10:00:00Z"))));
            }
        }
    }

    /** A request on the order with {@code units} untracked items of its line. */
    class Req {
        final UUID id;
        final String reference;
        final List<UUID> items = new ArrayList<>();
        Req(Shop s, Order o, int units, String type, String status, String decidedAt) {
            reference = reference();
            id = jdbc.queryForObject(
                "INSERT INTO return_requests (tenant_id, order_id, type, status, reference, decided_at) " +
                "VALUES (?, ?, ?, ?::return_request_status, ?, " + decidedAt + ") RETURNING id", UUID.class,
                s.tenant, o.id, type, status, reference);
            for (int u = 1; u <= units; u++) {
                items.add(jdbc.queryForObject(
                    "INSERT INTO return_request_items (tenant_id, request_id, order_item_id, unit_no, variant_id, reason_code, " +
                    "    item_status, active) VALUES (?, ?, ?, ?, ?, 'wrong_size', 'awaiting', true) RETURNING id",
                    UUID.class, s.tenant, id, o.line, u, s.v));
            }
        }
        Req(Shop s, Order o, int units) { this(s, o, units, "refund", "approved", "now()"); }
    }

    void grade(UUID item) {
        jdbc.update("UPDATE return_request_items SET item_status = 'done', active = false, arrived_at = now(), done_at = now(), " +
            "arrived_condition = 'sellable' WHERE id = ?", item);
    }

    void ungrade(UUID item) {
        jdbc.update("UPDATE return_request_items SET item_status = 'awaiting', active = true, arrived_at = NULL, done_at = NULL, " +
            "arrived_condition = NULL WHERE id = ?", item);
    }

    void notComing(UUID item) {
        jdbc.update("UPDATE return_request_items SET item_status = 'not_coming', active = false WHERE id = ?", item);
    }

    void status(Req r, String st) {
        jdbc.update("UPDATE return_requests SET status = ?::return_request_status WHERE id = ?", st, r.id);
    }

    Map<String, Object> row(Req r) {
        return jdbc.queryForMap("SELECT shopify_sync_status, shopify_sync_reason, shopify_sync_attempts, shopify_return_gid, " +
            "shopify_return_name, shopify_sync_verified_at FROM return_requests WHERE id = ?", r.id);
    }

    String action(Req r, String action, String key) {
        List<String> s = jdbc.queryForList("SELECT status || COALESCE(':' || reason, '') FROM shopify_return_actions " +
            "WHERE request_id = ? AND action = ? AND unit_key = ?", String.class, r.id, action, key);
        return s.isEmpty() ? null : s.get(0);
    }

    List<Map<String, Object>> alerts(Shop s) {
        return s.as(() -> exceptions.detectAllOpen().stream().filter(x -> ALERT.equals(x.get("type"))).toList());
    }

    int creates() { return (int) mockingDetails(writes).getInvocations().stream()
        .filter(i -> i.getMethod().getName().equals("returnCreate")).count(); }

    int calls(String method) { return (int) mockingDetails(writes).getInvocations().stream()
        .filter(i -> i.getMethod().getName().equals(method)).count(); }

    List<Invocation> callsOf(String method) { return mockingDetails(writes).getInvocations().stream()
        .filter(i -> i.getMethod().getName().equals(method)).toList(); }

    int enqueues() { return (int) mockingDetails(jobScheduler).getInvocations().stream()
        .filter(i -> i.getMethod().getName().equals("enqueue")).count(); }

    static String reference() {
        String alphabet = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ";
        StringBuilder sb = new StringBuilder("RR-");
        java.util.concurrent.ThreadLocalRandom rnd = java.util.concurrent.ThreadLocalRandom.current();
        for (int i = 0; i < 6; i++) sb.append(alphabet.charAt(rnd.nextInt(alphabet.length())));
        return sb.toString();
    }

    // ── create ─────────────────────────────────────────────────────────────────────────────────

    @Test
    void c1_approved_oneCreate_markerAndReasonInNote_verified_neverTwice() {
        Order o = new Order(a, 2, 2);
        Req r = new Req(a, o, 2);

        sync.sync(a.tenant, r.id);

        assertThat(creates()).isEqualTo(1);
        Invocation call = callsOf("returnCreate").get(0);
        assertThat((String) call.getArgument(0)).isEqualTo(a.domain);
        assertThat((String) call.getArgument(2)).isEqualTo(o.gid);
        List<ShopifyReturnsWriteGateway.CreateLine> lines = call.getArgument(4);
        assertThat(lines).singleElement().satisfies(l -> {
            assertThat(l.fulfillmentLineItemGid()).isEqualTo(o.fli);
            assertThat(l.quantity()).isEqualTo(2);
            assertThat(l.note()).isEqualTo("Traced " + r.reference + " · Wrong size");
        });
        Map<String, Object> row = row(r);
        assertThat(row.get("shopify_sync_status")).isEqualTo("created");
        assertThat(row.get("shopify_return_gid")).isEqualTo(shopify.only().gid);
        assertThat(row.get("shopify_return_name")).isEqualTo(shopify.only().name);
        assertThat(row.get("shopify_sync_verified_at")).isNotNull();

        sync.sync(a.tenant, r.id);
        sync.sweepTenant(a.tenant);
        assertThat(creates()).isEqualTo(1);
        assertThat(alerts(a)).noneMatch(x -> r.id.equals(x.get("request_id")));
    }

    @Test
    void c2_definiteRefusal_retried_thenCreated_fiveRefusals_stop_andAlert() {
        Order o = new Order(a, 1, 1);
        Req r = new Req(a, o, 1);
        shopify.createAnswers.add(definite());

        sync.sync(a.tenant, r.id);
        assertThat(row(r).get("shopify_sync_status")).isEqualTo("failed");
        sync.sync(a.tenant, r.id);
        assertThat(row(r).get("shopify_sync_status")).isEqualTo("created");
        assertThat(creates()).isEqualTo(2);
        assertThat(shopify.returns).hasSize(1);

        Order o2 = new Order(a, 1, 1);
        Req r2 = new Req(a, o2, 1);
        for (int i = 0; i < 7; i++) shopify.createAnswers.add(definite());
        for (int i = 0; i < 7; i++) sync.sync(a.tenant, r2.id);
        assertThat(creates()).isEqualTo(2 + 5);
        assertThat(row(r2).get("shopify_sync_attempts")).isEqualTo(5);
        assertThat(alerts(a)).anySatisfy(x -> {
            assertThat(x.get("request_id")).isEqualTo(r2.id);
            assertThat(x.get("check_reason")).isEqualTo("create_failed");
            assertThat(x.get("severity")).isEqualTo("LOW");
        });
    }

    @Test
    void c3_ambiguous_neverResent_settledByTheMarkerRead() {
        Order o = new Order(a, 1, 1);
        Req r = new Req(a, o, 1);
        shopify.createAnswers.add(ambiguous());
        shopify.applyOnAmbiguous = false;

        sync.sync(a.tenant, r.id);
        assertThat(row(r).get("shopify_sync_status")).isEqualTo("failed_ambiguous");
        assertThat(alerts(a)).anyMatch(x -> r.id.equals(x.get("request_id")) && "create_unconfirmed".equals(x.get("check_reason")));

        sync.sync(a.tenant, r.id);
        sync.sweepTenant(a.tenant);
        assertThat(creates()).isEqualTo(1);
        assertThat(row(r).get("shopify_sync_status")).isEqualTo("failed_ambiguous");

        // The answer was lost but Shopify did create it: the marker read finds it, nothing is sent again.
        Order o2 = new Order(a, 1, 1);
        Req r2 = new Req(a, o2, 1);
        shopify.createAnswers.add(ambiguous());
        shopify.applyOnAmbiguous = true;
        sync.sync(a.tenant, r2.id);
        assertThat(row(r2).get("shopify_sync_status")).isEqualTo("failed_ambiguous");
        sync.sync(a.tenant, r2.id);
        assertThat(creates()).isEqualTo(2);
        Map<String, Object> row2 = row(r2);
        assertThat(row2.get("shopify_sync_status")).isEqualTo("created");
        assertThat(row2.get("shopify_return_gid")).isNotNull();
        assertThat(alerts(a)).noneMatch(x -> r2.id.equals(x.get("request_id")));
    }

    @Test
    void c4_noWriteReturnsScope_skipped_noCall_accessDenied_skipped() {
        jdbc.update("UPDATE stores SET access_token_scopes = 'read_orders,read_returns' WHERE id = ?", a.store);
        try {
            Order o = new Order(a, 1, 1);
            Req r = new Req(a, o, 1);
            sync.sync(a.tenant, r.id);
            assertThat(row(r).get("shopify_sync_status")).isEqualTo("skipped");
            assertThat(row(r).get("shopify_sync_reason")).isEqualTo("no_permission");
            assertThat(mockingDetails(writes).getInvocations()).isEmpty();
        } finally {
            jdbc.update("UPDATE stores SET access_token_scopes = " +
                "'read_orders,write_inventory,read_products,write_returns,read_merchant_managed_fulfillment_orders' WHERE id = ?", a.store);
        }
        Order o = new Order(a, 1, 1);
        Req r = new Req(a, o, 1);
        shopify.createAnswers.add(denied());
        sync.sync(a.tenant, r.id);
        assertThat(row(r).get("shopify_sync_status")).isEqualTo("skipped");
        assertThat(row(r).get("shopify_sync_reason")).isEqualTo("no_permission");
        sync.sync(a.tenant, r.id);
        assertThat(creates()).isEqualTo(1);
    }

    @Test
    void c5_twoSenders_theClaimLetsOnlyOneCreate() throws Exception {
        Order o = new Order(a, 1, 1);
        Req r = new Req(a, o, 1);
        java.util.concurrent.CountDownLatch firstRead = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch secondDone = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger reads = new java.util.concurrent.atomic.AtomicInteger();
        List<ShopifyReturnsWriteGateway.Returnable> lines = shopify.returnable.get(o.gid);
        // The first sender reads Shopify, then waits (before its claim) until the second has sent and finished.
        when(writes.returnableFulfillments(anyString(), anyString(), eq(o.gid))).thenAnswer(i -> {
            if (reads.incrementAndGet() == 1) {
                firstRead.countDown();
                assertThat(secondDone.await(20, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            }
            return new ShopifyReturnsWriteGateway.Returnables(lines, false);
        });
        Thread first = new Thread(() -> sync.sync(a.tenant, r.id));
        first.start();
        assertThat(firstRead.await(20, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        sync.sync(a.tenant, r.id);
        secondDone.countDown();
        first.join(30_000);

        assertThat(creates()).isEqualTo(1);
        assertThat(shopify.returns).hasSize(1);
        assertThat(row(r).get("shopify_sync_status")).isEqualTo("created");
    }

    @Test
    void e1_notSynced_reasons_andNothingForExchangeBeforeSwitchOnOrRequested() {
        // Pre-connect portal order: not in merchant_orders.
        UUID pre = jdbc.queryForObject("INSERT INTO orders (tenant_id, store_id, external_id, number, status, payment_method, " +
            "placed_at, origin, shopify_order_gid) VALUES (?, ?, ?, '#P1', 'delivered', 'cod', now(), 'portal_pre_connect', ?) " +
            "RETURNING id", UUID.class, a.tenant, a.store, "internal:portal:" + UUID.randomUUID(),
            "gid://shopify/Order/" + IDS.incrementAndGet());
        UUID preLine = jdbc.queryForObject("INSERT INTO order_items (tenant_id, order_id, variant_id, quantity, external_id) " +
            "VALUES (?, ?, ?, 1, ?) RETURNING id", UUID.class, a.tenant, pre, a.v, "gid://shopify/LineItem/" + IDS.incrementAndGet());
        UUID preReq = jdbc.queryForObject("INSERT INTO return_requests (tenant_id, order_id, type, status, reference, decided_at) " +
            "VALUES (?, ?, 'refund', 'approved', ?, now()) RETURNING id", UUID.class, a.tenant, pre, reference());
        jdbc.update("INSERT INTO return_request_items (tenant_id, request_id, order_item_id, unit_no, variant_id, reason_code, " +
            "item_status, active) VALUES (?, ?, ?, 1, ?, 'other', 'awaiting', true)", a.tenant, preReq, preLine, a.v);
        sync.sync(a.tenant, preReq);
        assertThat(jdbc.queryForObject("SELECT shopify_sync_reason FROM return_requests WHERE id = ?", String.class, preReq))
            .isEqualTo("pre_connect_order");

        Req notFulfilled = new Req(a, new Order(a, 1, 0), 1);
        sync.sync(a.tenant, notFulfilled.id);
        assertThat(row(notFulfilled).get("shopify_sync_reason")).isEqualTo("not_fulfilled_in_shopify");

        Req tooMany = new Req(a, new Order(a, 2, 1), 2);
        sync.sync(a.tenant, tooMany.id);
        assertThat(row(tooMany).get("shopify_sync_reason")).isEqualTo("not_returnable_in_shopify");

        Req exchange = new Req(a, new Order(a, 1, 1), 1, "exchange", "approved", "now()");
        Req before = new Req(a, new Order(a, 1, 1), 1, "refund", "approved", "now() - interval '2 days'");
        Req requested = new Req(a, new Order(a, 1, 1), 1, "refund", "requested", "NULL");
        for (Req q : List.of(exchange, before, requested)) {
            sync.sync(a.tenant, q.id);
            assertThat(row(q).get("shopify_sync_status")).isNull();
        }
        assertThat(creates()).isZero();
        // The drawer: an exchange says "not yet", one decided before switch-on shows nothing.
        Map<String, Object> ex = a.as(() -> requests.detail(exchange.id));
        assertThat(ex.get("shopifySync")).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
            .containsEntry("status", "skipped").containsEntry("reason", "exchange_not_yet");
        assertThat(a.as(() -> requests.detail(before.id))).doesNotContainKey("shopifySync");
        assertThat(a.as(() -> requests.detail(requested.id)).get("shopifySync"))
            .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP).containsEntry("reason", "awaiting_approval");
    }

    // ── process ────────────────────────────────────────────────────────────────────────────────

    Req created(int units) {
        Order o = new Order(a, units, units);
        Req r = new Req(a, o, units);
        sync.sync(a.tenant, r.id);
        assertThat(row(r).get("shopify_sync_status")).isEqualTo("created");
        return r;
    }

    @Test
    void p1_gradedUnit_processedNotRestocked_noLocation_once() {
        Req r = created(1);
        grade(r.items.get(0));

        sync.sync(a.tenant, r.id);

        assertThat(calls("returnProcessNotRestocked")).isEqualTo(1);
        Invocation p = callsOf("returnProcessNotRestocked").get(0);
        FakeReturn fr = shopify.only();
        assertThat((String) p.getArgument(2)).isEqualTo(fr.gid);
        assertThat((String) p.getArgument(3)).isEqualTo(fr.lines.get(0).id);
        assertThat((String) p.getArgument(4)).isEqualTo("rfol-" + fr.lines.get(0).id);
        assertThat((Integer) p.getArgument(5)).isEqualTo(1);
        assertThat((String) p.getArgument(6)).isNull();
        assertThat(action(r, "process", r.items.get(0).toString())).isEqualTo("applied:not_restocked");
        assertThat(fr.lines.get(0).processed).isEqualTo(1);

        sync.sync(a.tenant, r.id);
        assertThat(calls("returnProcessNotRestocked")).isEqualTo(1);
    }

    @Test
    void p2_refusedWithoutLocation_thenAtMain_applied() {
        Req r = created(1);
        grade(r.items.get(0));
        shopify.processAnswers.add(definite());

        sync.sync(a.tenant, r.id);

        List<Invocation> ps = callsOf("returnProcessNotRestocked");
        assertThat(ps).hasSize(2);
        assertThat((String) ps.get(0).getArgument(6)).isNull();
        assertThat((String) ps.get(1).getArgument(6)).isEqualTo(a.mainGid);
        assertThat(action(r, "process", r.items.get(0).toString())).isEqualTo("applied:not_restocked_at_main");
    }

    @Test
    void p3_unconfirmedProcess_readBackDecides_neverResent() {
        Req r = created(2);
        grade(r.items.get(0));
        shopify.processAnswers.add(ambiguous());
        shopify.applyOnAmbiguous = true;
        sync.sync(a.tenant, r.id);
        assertThat(action(r, "process", r.items.get(0).toString())).isEqualTo("applied:confirmed_by_readback");

        grade(r.items.get(1));
        shopify.processAnswers.add(ambiguous());
        shopify.applyOnAmbiguous = false;
        sync.sync(a.tenant, r.id);
        assertThat(action(r, "process", r.items.get(1).toString())).isEqualTo("failed_ambiguous");
        int before = calls("returnProcessNotRestocked");
        sync.sync(a.tenant, r.id);
        sync.sweepTenant(a.tenant);
        assertThat(calls("returnProcessNotRestocked")).isEqualTo(before);
        assertThat(alerts(a)).anyMatch(x -> r.id.equals(x.get("request_id")) && "step_unconfirmed".equals(x.get("check_reason")));
    }

    // ── ending the return ──────────────────────────────────────────────────────────────────────

    @Test
    void t1_refunded_close_andShopifyClosedItItself_noCall() {
        Req r = created(1);
        grade(r.items.get(0));
        sync.sync(a.tenant, r.id);
        status(r, "refunded");
        sync.sync(a.tenant, r.id);
        assertThat(calls("returnClose")).isEqualTo(1);
        assertThat(action(r, "close", "return")).isEqualTo("applied");
        assertThat(shopify.only().status).isEqualTo("CLOSED");

        shopify.returns.clear();
        Req r2 = created(1);
        grade(r2.items.get(0));
        sync.sync(a.tenant, r2.id);
        shopify.only().status = "CLOSED";       // Shopify closed it once every unit was processed
        status(r2, "refunded");
        sync.sync(a.tenant, r2.id);
        assertThat(calls("returnClose")).isEqualTo(1);
        assertThat(action(r2, "close", "return")).isEqualTo("applied:already_closed");
    }

    @Test
    void t2_closedNothingBack_cancel_refusedCancel_needsReview_notRetried() {
        Req r = created(1);
        notComing(r.items.get(0));
        status(r, "closed");
        sync.sync(a.tenant, r.id);
        assertThat(calls("returnCancel")).isEqualTo(1);
        assertThat(action(r, "cancel", "return")).isEqualTo("applied");

        shopify.returns.clear();
        Req r2 = created(1);
        notComing(r2.items.get(0));
        status(r2, "closed");
        shopify.cancelAnswers.add(definite());
        sync.sync(a.tenant, r2.id);
        assertThat(action(r2, "cancel", "return")).isEqualTo("needs_review:refused_by_shopify");
        sync.sync(a.tenant, r2.id);
        sync.sweepTenant(a.tenant);
        assertThat(calls("returnCancel")).isEqualTo(2);
        assertThat(calls("returnClose")).isZero();
    }

    @Test
    void t3_partial_processOne_removeOne_close_refusedRemove_needsReview_closeStillSent() {
        Req r = created(2);
        grade(r.items.get(0));
        notComing(r.items.get(1));
        status(r, "closed");
        sync.sync(a.tenant, r.id);
        FakeReturn fr = shopify.only();
        assertThat(calls("returnProcessNotRestocked")).isEqualTo(1);
        assertThat(calls("removeFromReturn")).isEqualTo(1);
        assertThat((Integer) callsOf("removeFromReturn").get(0).getArgument(4)).isEqualTo(1);
        assertThat(calls("returnClose")).isEqualTo(1);
        assertThat(calls("returnCancel")).isZero();
        assertThat(action(r, "remove", fr.lines.get(0).id)).isEqualTo("applied");

        shopify.returns.clear();
        Req r2 = created(2);
        grade(r2.items.get(0));
        notComing(r2.items.get(1));
        status(r2, "closed");
        shopify.removeAnswers.add(definite());
        sync.sync(a.tenant, r2.id);
        assertThat(action(r2, "remove", shopify.only().lines.get(0).id)).isEqualTo("needs_review:refused_by_shopify");
        assertThat(calls("returnClose")).isEqualTo(2);
        assertThat(calls("removeFromReturn")).isEqualTo(2);
        sync.sync(a.tenant, r2.id);
        assertThat(calls("removeFromReturn")).isEqualTo(2);
    }

    @Test
    void u1_undoAfterProcessed_outOfStepAlert_noShopifyWrite_checkedClears() {
        Req r = created(1);
        grade(r.items.get(0));
        sync.sync(a.tenant, r.id);
        int writesBefore = mockingDetails(writes).getInvocations().size();
        ungrade(r.items.get(0));

        sync.sync(a.tenant, r.id);
        assertThat(mockingDetails(writes).getInvocations().size()).isEqualTo(writesBefore);
        assertThat(alerts(a)).anySatisfy(x -> {
            assertThat(x.get("request_id")).isEqualTo(r.id);
            assertThat(x.get("check_reason")).isEqualTo("out_of_step");
        });
        Map<String, Object> d = a.as(() -> requests.detail(r.id));
        assertThat(d.get("shopifySync")).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
            .containsEntry("outOfStep", true).containsEntry("canMarkChecked", true);

        sync.markChecked(a.tenant, r.id, a.user);
        assertThat(alerts(a)).noneMatch(x -> r.id.equals(x.get("request_id")));
        assertThatThrownBy(() -> sync.markChecked(a.tenant, r.id, a.user)).isInstanceOf(ResponseStatusException.class);
    }

    // ── exclusions ─────────────────────────────────────────────────────────────────────────────

    /** Mirrors a Shopify return on the order (as S1 would) — status OPEN, one line of the order's variant. */
    UUID mirror(Shop s, Order o, String returnGid, String note) {
        UUID ret = jdbc.queryForObject(
            "INSERT INTO shopify_returns (tenant_id, store_id, return_gid, order_id, order_gid, name, status, raw, shopify_created_at) " +
            "VALUES (?, ?, ?, ?, ?, '#M-R1', 'OPEN', '{}'::jsonb, now()) RETURNING id",
            UUID.class, s.tenant, s.store, returnGid, o.id, o.gid);
        String fli = "gid://shopify/FulfillmentLineItem/" + IDS.incrementAndGet();
        jdbc.update("INSERT INTO shopify_return_lines (tenant_id, return_id, line_gid, fulfillment_line_gid, variant_gid, variant_id, " +
            "quantity, reason_note, raw) VALUES (?, ?, ?, ?, (SELECT external_id FROM variants WHERE id = ?), ?, 1, ?, '{}'::jsonb)",
            s.tenant, ret, "gid://shopify/ReturnLineItem/" + IDS.incrementAndGet(), fli, s.v, s.v, note);
        return ret;
    }

    @Test
    void x1_tracedCreatedReturn_notNative_controlNativeIs_S1bStillCounts() {
        Req r = created(1);
        UUID orderId = jdbc.queryForObject("SELECT order_id FROM return_requests WHERE id = ?", UUID.class, r.id);
        String orderGid = jdbc.queryForObject("SELECT external_id FROM orders WHERE id = ?", String.class, orderId);
        Order fake = new Order(a, 1, 1);   // only for the native control below
        String gid = (String) row(r).get("shopify_return_gid");
        UUID traced = jdbc.queryForObject(
            "INSERT INTO shopify_returns (tenant_id, store_id, return_gid, order_id, order_gid, name, status, raw, shopify_created_at) " +
            "VALUES (?, ?, ?, ?, ?, '#T-R1', 'OPEN', '{}'::jsonb, now()) RETURNING id",
            UUID.class, a.tenant, a.store, gid, orderId, orderGid);
        jdbc.update("INSERT INTO shopify_return_lines (tenant_id, return_id, line_gid, fulfillment_line_gid, variant_gid, variant_id, " +
            "quantity, reason_note, raw) VALUES (?, ?, ?, ?, (SELECT external_id FROM variants WHERE id = ?), ?, 1, ?, '{}'::jsonb)",
            a.tenant, traced, "gid://shopify/ReturnLineItem/" + IDS.incrementAndGet(), "gid://shopify/FulfillmentLineItem/" + IDS.incrementAndGet(),
            a.v, a.v, "Traced " + r.reference + " · Wrong size");
        // A second Traced request on the native control's order makes it a duplicate there.
        Req onFake = new Req(a, fake, 1, "refund", "approved", "now() - interval '2 days'");
        UUID nativeRet = mirror(a, fake, "gid://shopify/Return/" + IDS.incrementAndGet(), "too big");

        // S4: not expected back (a delivered piece of the variant on that order), control: expected.
        assertThat(a.as(() -> ShopifyReturnIntake.expects(jdbc, a.tenant, "no-piece", orderId, a.v))).isFalse();
        assertThat(a.as(() -> ShopifyReturnIntake.expects(jdbc, a.tenant, "no-piece", fake.id, a.v))).isTrue();

        // S2 card: not a duplicate, says which request it was created for; control: a duplicate.
        Map<UUID, Map<String, Object>> cards = a.as(() -> new org.springframework.transaction.support.TransactionTemplate(appTxm)
            .execute(st -> new ShopifyReturnsView(appJdbc).forReturns(List.of(traced, nativeRet))));
        assertThat(cards.get(traced)).containsEntry("duplicate", false).containsEntry("createdByTraced", r.reference);
        assertThat(cards.get(nativeRet)).containsEntry("duplicate", true).doesNotContainKey("createdByTraced");

        // Returns list: no case S for the Traced-created return; the native one is listed.
        Integer tracedS = jdbc.queryForObject("SELECT COUNT(*) FROM shopify_returns r WHERE r.id = ? AND NOT " +
            com.traceability.portal.ShopifyReturnSyncRules.tracedCreatedSql("r"), Integer.class, traced);
        Integer nativeS = jdbc.queryForObject("SELECT COUNT(*) FROM shopify_returns r WHERE r.id = ? AND NOT " +
            com.traceability.portal.ShopifyReturnSyncRules.tracedCreatedSql("r"), Integer.class, nativeRet);
        assertThat(tracedS).isZero();
        assertThat(nativeS).isEqualTo(1);
        Map<String, Object> list = a.as(() -> caseList());
        assertThat(String.valueOf(list)).doesNotContain(traced.toString()).contains(nativeRet.toString());

        // S1b: a RESTOCKED disposition on the Traced-created return (a merchant restocking by hand) still counts.
        jdbc.update("INSERT INTO shopify_rfo_dispositions (tenant_id, return_id, rfo_gid, rfo_line_gid, disposition_gid, " +
            "fulfillment_line_gid, type, quantity, location_gid, raw) SELECT ?, ?, 'rfo-x', 'rfol-x', 'disp-x', l.fulfillment_line_gid, " +
            "'RESTOCKED', 1, ?, '{}'::jsonb FROM shopify_return_lines l WHERE l.return_id = ?",
            a.tenant, traced, a.mainGid, traced);
        Integer restocked = jdbc.queryForObject(
            "SELECT shopify_restocked_units(o.raw, o.tenant_id, o.id, v.external_id, ?) FROM orders o, variants v " +
            "WHERE o.id = ? AND v.id = ?", Integer.class, a.mainGid, orderId, a.v);
        assertThat(restocked).isEqualTo(1);
        assertThat(onFake.id).isNotNull();
    }

    @Autowired com.traceability.returncases.ReturnCaseService cases;

    Map<String, Object> caseList() {
        return cases.list(new com.traceability.returncases.ReturnCaseService.Filter(null, null, null, null), null, 100);
    }

    @Test
    void x2_appUser_otherTenantCantSyncOrSee_ownCan() {
        ShopifyReturnSyncService appSync = new ShopifyReturnSyncService(appJdbc, appTxm, writes, reads, tokenProvider, rollout);
        Order o = new Order(a, 1, 1);
        Req r = new Req(a, o, 1);

        appSync.sync(b.tenant, r.id);
        assertThat(row(r).get("shopify_sync_status")).isNull();
        assertThat(creates()).isZero();
        assertThat(b.as(() -> ShopifyReturnSyncService.detailBlock(appJdbc, b.tenant, r.id, true))).isNull();
        assertThat(b.as(() -> appSync.sweepTenant(b.tenant))).isGreaterThanOrEqualTo(0);
        assertThat(row(r).get("shopify_sync_status")).isNull();

        appSync.sync(a.tenant, r.id);
        assertThat(row(r).get("shopify_sync_status")).isEqualTo("created");
        assertThat(creates()).isEqualTo(1);
        grade(r.items.get(0));
        appSync.sync(a.tenant, r.id);
        assertThat(action(r, "process", r.items.get(0).toString())).isEqualTo("applied:not_restocked");
        Integer own = TenantContext.runAs(a.tenant, () -> new org.springframework.transaction.support.TransactionTemplate(appTxm)
            .execute(st -> appJdbc.queryForObject(
                "SELECT COUNT(*) FROM shopify_return_actions WHERE request_id = ?", Integer.class, r.id)));
        assertThat(own).isEqualTo(1);
        Integer seen = TenantContext.runAs(b.tenant, () -> new org.springframework.transaction.support.TransactionTemplate(appTxm)
            .execute(st -> appJdbc.queryForObject(
                "SELECT COUNT(*) FROM shopify_return_actions WHERE request_id = ?", Integer.class, r.id)));
        assertThat(seen).isZero();
    }

    // ── hooks, switch, golden ──────────────────────────────────────────────────────────────────

    @Test
    void h1_approve_listedTenant_enqueuesAfterCommit() {
        Req r = new Req(a, new Order(a, 1, 1), 1, "refund", "requested", "NULL");
        int before = enqueues();
        a.as(() -> { requests.approve(r.id, a.user); return null; });
        assertThat(enqueues()).isEqualTo(before + 1);
    }

    @Test
    void s1_ownerSwitch_onStampsSince_audited_unlistedHidden() {
        jdbc.update("UPDATE tenants SET shopify_returns_sync_enabled = false, shopify_returns_sync_since = NULL WHERE id = ?", b.tenant);
        Map<String, Object> got = b.as(() -> settings.get());
        assertThat(got).containsEntry("visible", true).containsEntry("enabled", false);
        Map<String, Object> on = b.as(() -> settings.set(true, b.user));
        assertThat(on).containsEntry("enabled", true).containsEntry("permissionGranted", true);
        assertThat(on.get("since")).isNotNull();
        b.as(() -> settings.set(true, b.user));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE tenant_id = ? AND action = 'shopify_returns_sync_on'",
            Integer.class, b.tenant)).isEqualTo(1);

        assertThat(off.as(() -> settings.get())).containsOnlyKeys("visible").containsEntry("visible", false);
        assertThatThrownBy(() -> off.as(() -> settings.set(true, off.user))).isInstanceOf(ResponseStatusException.class);
    }

    @Autowired com.traceability.account.AuditService audit;

    @Test
    void s2_appUser_switchReadsOnlyTheCallersOwnTenant_sameTenantControl() {
        // RLS coverage for GET /tenant/shopify-returns-sync: two listed tenants with different switches, read as app_user.
        jdbc.update("UPDATE tenants SET shopify_returns_sync_enabled = true, shopify_returns_sync_since = now() WHERE id = ?", a.tenant);
        jdbc.update("UPDATE tenants SET shopify_returns_sync_enabled = false, shopify_returns_sync_since = NULL WHERE id = ?", b.tenant);
        ShopifyReturnSyncSettingsService appSettings = new ShopifyReturnSyncSettingsService(appJdbc, rollout, audit);
        org.springframework.transaction.support.TransactionTemplate appTx =
            new org.springframework.transaction.support.TransactionTemplate(appTxm);
        Map<String, Object> asB = b.as(() -> appTx.execute(st -> appSettings.get()));
        Map<String, Object> asA = a.as(() -> appTx.execute(st -> appSettings.get()));
        assertThat(asB).containsEntry("visible", true).containsEntry("enabled", false).containsEntry("since", null);
        assertThat(asA).containsEntry("visible", true).containsEntry("enabled", true);
        assertThat(asA.get("since")).isNotNull();
    }

    @Test
    void g0_unlistedTenant_golden_nothingEnqueued_noDrawerKey_noSync() {
        Req r = new Req(off, new Order(off, 1, 1), 1, "refund", "requested", "NULL");
        int before = enqueues();
        off.as(() -> { requests.approve(r.id, off.user); return null; });
        assertThat(enqueues()).isEqualTo(before);
        assertThat(off.as(() -> requests.detail(r.id))).doesNotContainKey("shopifySync");
        sync.sync(off.tenant, r.id);
        assertThat(sync.sweepTenant(off.tenant)).isZero();
        assertThat(row(r).get("shopify_sync_status")).isNull();
        assertThat(mockingDetails(writes).getInvocations()).isEmpty();
        assertThat(alerts(off)).isEmpty();
    }

    // ── return reason (fix 2026-11-08: prod returnCreate without a reason → userErrors NOT_FOUND) ──────────────

    @Test
    void c6_fallbackReasonId_onEveryLine_markerStaysInNote() {
        when(reads.fetchFallbackReturnReasonId(anyString(), anyString()))
            .thenReturn("gid://shopify/ReturnReasonDefinition/9");
        Order o = new Order(a, 2, 2);
        Req r = new Req(a, o, 2);

        sync.sync(a.tenant, r.id);

        assertThat(creates()).isEqualTo(1);
        List<ShopifyReturnsWriteGateway.CreateLine> lines = callsOf("returnCreate").get(0).getArgument(4);
        assertThat(lines).isNotEmpty().allSatisfy(l -> {
            assertThat(l.returnReasonDefinitionGid()).isEqualTo("gid://shopify/ReturnReasonDefinition/9");
            assertThat(l.note()).startsWith("Traced " + r.reference);
        });
        assertThat(row(r).get("shopify_sync_status")).isEqualTo("created");
    }

    @Test
    void c7_reasonReadFails_stillCreated_withoutReason_oneAttempt() {
        when(reads.fetchFallbackReturnReasonId(anyString(), anyString()))
            .thenThrow(new com.traceability.integrations.shopify.ShopifyException("boom"));
        Order o = new Order(a, 1, 1);
        Req r = new Req(a, o, 1);

        sync.sync(a.tenant, r.id);

        assertThat(creates()).isEqualTo(1);
        List<ShopifyReturnsWriteGateway.CreateLine> lines = callsOf("returnCreate").get(0).getArgument(4);
        assertThat(lines).singleElement().satisfies(l -> assertThat(l.returnReasonDefinitionGid()).isNull());
        Map<String, Object> row = row(r);
        assertThat(row.get("shopify_sync_status")).isEqualTo("created");
        assertThat(((Number) row.get("shopify_sync_attempts")).intValue()).isEqualTo(1);
    }
}
