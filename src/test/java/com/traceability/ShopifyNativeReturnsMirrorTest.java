package com.traceability;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.traceability.identity.JwtService;
import com.traceability.integrations.bosta.BostaGateway;
import com.traceability.integrations.shopify.RegisterShopifyWebhooksJob;
import com.traceability.integrations.shopify.ShopifyGateway;
import com.traceability.integrations.shopify.ShopifyOAuthService;
import com.traceability.integrations.shopify.ShopifyReturnsAccessDeniedException;
import com.traceability.integrations.shopify.ShopifyReturnsBackfill;
import com.traceability.integrations.shopify.ShopifyReturnsGateway;
import com.traceability.integrations.shopify.ShopifyReturnsStore;
import com.traceability.integrations.shopify.ShopifyTokenProvider;
import com.traceability.integrations.shopify.ShopifyWebhookProcessorJob;
import com.traceability.inventory.VariantStockService;
import com.traceability.privacy.CustomerRedaction;
import com.traceability.tenancy.TenantAwareDataSource;
import com.traceability.tenancy.TenantContext;
import org.jobrunr.scheduling.JobScheduler;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Shopify-native returns S1 — the returns / exchanges / fulfillment-order MIRROR (V206). Data only: no reservation,
 * no committed demand, no Pick &amp; Pack change, no stock write, nothing on a warehouse screen.
 *
 *   i1  idempotent upsert: the same returns webhook twice → the same rows
 *   i2  truncated / thin payload (numeric id only, cut-short line arrays) → the full Return is fetched by GID
 *   d1  ACCESS_DENIED → returns access 'not_granted', the event is processed (no error, no retry), one call, no throw;
 *       the backfill stops quietly too
 *   s1  app/scopes_update → scopes refreshed, returns access granted, ONLY the missing topics registered, backfill
 *       requested; the backfill then mirrors the store's returns (only orders Traced holds)
 *   r1  topic rollout: only missing topics are registered; returns / FO topics deferred while their scope isn't granted
 *       (not a failure — the store is stamped); never a delete
 *   p1  a return / fulfillment order of an order Traced doesn't hold (pre-connect) is ignored — no row
 *   l1  exchange-line link, returns/process FIRST, orders/updated after (the race): the order line is linked when it
 *       arrives; l2 orders/updated first → linked by the return upsert; quantities never change
 *   g1  GDPR: customers/redact clears the notes and strips them from every raw (and keeps them stripped on a later
 *       sync); a return of a redacted order is born redacted; returns/* webhooks stripped; export includes the mirror
 *   x1  app_user: tenant B sees none of tenant A's mirror rows and can't point a row at A's order; tenant A (same
 *       tenant) upserts and reads through the real store (positive control)
 *   n1  Settings → Connections: the owner sees "Approve Traced's new Shopify permissions" (open-app link) while the
 *       store hasn't granted the scopes; a manager never; gone once granted
 *   t1  token re-exchange loop guard: while the store hasn't approved the new scopes, an embedded open re-exchanges
 *       the token ONCE for the scope difference, then not again (logged as waiting for approval) — until 24 h pass,
 *       the token gets close to expiry, or app/scopes_update clears the guard; once the token carries the scopes,
 *       opens stop exchanging
 *   z1  GOLDEN: committed demand, the pick queue, the Orders list / counts / detail and the pick detail are
 *       byte-identical before and after ingesting a return with an exchange line (proves no behavior change)
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ShopifyNativeReturnsMirrorTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            TestPostgres.newContainer()
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
        r.add("traced.shopify-returns.backfill.batch-pause-ms", () -> "0");
        r.add("traced.shopify-returns.backfill.enabled", () -> "false");
        r.add("shopify.webhook-topics-backfill.enabled", () -> "false");
        r.add("shopify.new-scopes-notice", () -> "true");
    }

    static final String BASE_SCOPES =
        "read_products,read_orders,read_fulfillments,read_customers,write_inventory,write_locations,write_shipping";
    static final String NEW_SCOPES = BASE_SCOPES + ",write_returns,read_merchant_managed_fulfillment_orders";

    @Autowired JdbcTemplate               jdbc;
    @Autowired ObjectMapper               mapper;
    @Autowired ShopifyWebhookProcessorJob processorJob;
    @Autowired ShopifyReturnsBackfill     backfill;
    @Autowired RegisterShopifyWebhooksJob register;
    @Autowired VariantStockService        stock;
    @Autowired JwtService                 jwt;
    @Autowired TestRestTemplate           rest;
    @Autowired PlatformTransactionManager txm;
    @Autowired ShopifyOAuthService        oauth;
    @LocalServerPort int port;

    @MockBean ShopifyReturnsGateway returnsGateway;
    @MockBean ShopifyGateway        shopifyGateway;
    @MockBean ShopifyTokenProvider  tokenProvider;
    @MockBean BostaGateway          bostaGateway;
    @MockBean JobScheduler          jobScheduler;

    private static final AtomicLong IDS = new AtomicLong(70_100_000_000_000L);
    private static final Instant T0 = Instant.parse("2026-11-05T10:00:00Z");

    @BeforeEach
    void tokens() {
        reset(returnsGateway, shopifyGateway);
        when(tokenProvider.getValidToken(any())).thenReturn("tok");
    }

    // ── fixture ─────────────────────────────────────────────────────────────────────────────────

    /** One tenant + connected OAuth store, variants M and L. */
    class Shop {
        final PackFixtures f;
        final UUID owner;
        final String domain;
        final long restM = IDS.incrementAndGet(), restL = IDS.incrementAndGet();
        final UUID variantM, variantL;

        Shop(String tag) {
            f = new PackFixtures(jdbc, "SR-" + tag);
            domain = jdbc.queryForObject("SELECT shop_domain FROM stores WHERE id = ?", String.class, f.store);
            jdbc.update("UPDATE stores SET status = 'connected', connection_type = 'oauth', access_token_scopes = ? WHERE id = ?",
                BASE_SCOPES, f.store);
            owner = f.user("Owner " + tag, "owner");
            UUID product = jdbc.queryForObject(
                "INSERT INTO products (tenant_id, store_id, external_id, title, status) " +
                "VALUES (?, ?, ?, 'LINEN SHIRT', 'active') RETURNING id",
                UUID.class, f.tenant, f.store, "gid://shopify/Product/" + IDS.incrementAndGet());
            variantM = variant(product, restM, "M");
            variantL = variant(product, restL, "L");
        }

        private UUID variant(UUID product, long rest, String title) {
            return jdbc.queryForObject(
                "INSERT INTO variants (tenant_id, product_id, external_id, sku, title) VALUES (?, ?, ?, ?, ?) RETURNING id",
                UUID.class, f.tenant, product, "gid://shopify/ProductVariant/" + rest, "LS-" + title + "-" + rest, title);
        }

        UUID event(String topic, JsonNode payload) {
            return jdbc.queryForObject(
                "INSERT INTO shopify_webhook_events (tenant_id, topic, shop_domain, webhook_id, payload_raw) " +
                "VALUES (?, ?, ?, ?, ?::jsonb) RETURNING id",
                UUID.class, f.tenant, topic, domain, UUID.randomUUID().toString(), payload.toString());
        }

        UUID send(String topic, JsonNode payload) {
            UUID e = event(topic, payload);
            processorJob.process(e, f.tenant);
            return e;
        }

        UUID orderId(String gid) {
            return jdbc.queryForObject("SELECT id FROM orders WHERE external_id = ? AND tenant_id = ?", UUID.class, gid, f.tenant);
        }

        int count(String table) {
            return jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE tenant_id = ?", Integer.class, f.tenant);
        }

        Map<String, Object> event(UUID e) {
            return jdbc.queryForMap("SELECT processed_at, process_error, retry_count, next_retry_at " +
                "FROM shopify_webhook_events WHERE id = ?", e);
        }

        void grant() {
            jdbc.update("UPDATE stores SET access_token_scopes = ? WHERE id = ?", NEW_SCOPES, f.store);
        }
    }

    // ── payloads ────────────────────────────────────────────────────────────────────────────────

    static String orderGid() { return "gid://shopify/Order/" + IDS.incrementAndGet(); }
    static String gid(String kind) { return "gid://shopify/" + kind + "/" + IDS.incrementAndGet(); }

    ObjectNode order(String gid, Instant updatedAt, ObjectNode... lines) {
        ObjectNode p = mapper.createObjectNode();
        p.put("id", Long.parseLong(gid.substring(gid.lastIndexOf('/') + 1)));
        p.put("admin_graphql_api_id", gid);
        p.put("name", "#SR" + (IDS.get() % 100_000));
        p.put("created_at", T0.minusSeconds(3600).toString());
        p.put("updated_at", updatedAt.toString());
        p.put("financial_status", "pending");
        p.put("current_total_price", "950.00");
        p.putArray("payment_gateway_names").add("Cash on Delivery (COD)");
        ArrayNode a = p.putArray("line_items");
        for (ObjectNode l : lines) a.add(l);
        p.putArray("refunds");
        p.putArray("fulfillments");
        return p;
    }

    ObjectNode line(long id, long variantRest, int qty) {
        ObjectNode l = mapper.createObjectNode();
        l.put("id", id);
        l.put("admin_graphql_api_id", "gid://shopify/LineItem/" + id);
        l.put("variant_id", variantRest);
        l.put("title", "LINEN SHIRT");
        l.put("quantity", qty);
        l.put("current_quantity", qty);
        l.put("fulfillable_quantity", qty);
        l.put("price", "950.00");
        l.putArray("discount_allocations");
        return l;
    }

    /** A Return as GraphQL returns it (validated query shape). */
    ObjectNode returnNode(String returnGid, String orderGid, String status, long returnedLine, long returnedVariant,
                          long exchangeVariant, Long exchangeOrderLine, String customerNote) {
        ObjectNode r = mapper.createObjectNode();
        r.put("id", returnGid);
        r.put("name", "#247060-R1");
        r.put("status", status);
        r.put("createdAt", T0.toString());
        r.putNull("closedAt");
        r.put("requestApprovedAt", T0.toString());
        r.put("totalQuantity", 1);
        r.putObject("order").put("id", orderGid);
        r.putNull("decline");
        String fli = gid("FulfillmentLineItem");
        ObjectNode rl = r.putObject("returnLineItems");
        rl.putObject("pageInfo").put("hasNextPage", false);
        ObjectNode n = rl.putArray("nodes").addObject();
        n.put("id", gid("ReturnLineItem"));
        n.put("quantity", 1);
        n.put("processedQuantity", 1);
        n.put("returnReasonNote", "too small");
        if (customerNote == null) n.putNull("customerNote"); else n.put("customerNote", customerNote);
        n.putObject("returnReasonDefinition").put("handle", "size-too-small");
        ObjectNode fl = n.putObject("fulfillmentLineItem");
        fl.put("id", fli);
        fl.putObject("lineItem").put("id", "gid://shopify/LineItem/" + returnedLine)
            .putObject("variant").put("id", "gid://shopify/ProductVariant/" + returnedVariant);
        ObjectNode xl = r.putObject("exchangeLineItems");
        xl.putObject("pageInfo").put("hasNextPage", false);
        ObjectNode x = xl.putArray("nodes").addObject();
        x.put("id", gid("ExchangeLineItem"));
        x.put("quantity", 1);
        x.put("processedQuantity", exchangeOrderLine == null ? 0 : 1);
        x.put("variantId", "gid://shopify/ProductVariant/" + exchangeVariant);
        ArrayNode xli = x.putArray("lineItems");
        if (exchangeOrderLine != null) xli.addObject().put("id", "gid://shopify/LineItem/" + exchangeOrderLine);
        ObjectNode rfo = r.putObject("reverseFulfillmentOrders").putArray("nodes").addObject();
        rfo.put("id", gid("ReverseFulfillmentOrder"));
        rfo.put("status", "CLOSED");
        ObjectNode rfl = rfo.putObject("lineItems").putArray("nodes").addObject();
        rfl.put("id", gid("ReverseFulfillmentOrderLineItem"));
        rfl.put("totalQuantity", 1);
        rfl.putObject("fulfillmentLineItem").put("id", fli);
        ObjectNode d = rfl.putArray("dispositions").addObject();
        d.put("id", gid("ReverseFulfillmentOrderDisposition"));
        d.put("type", "RESTOCKED");
        d.put("quantity", 1);
        d.putObject("location").put("id", "gid://shopify/Location/1");
        return r;
    }

    ObjectNode thinReturnWebhook(String returnGid) {
        ObjectNode p = mapper.createObjectNode();
        p.put("id", Long.parseLong(returnGid.substring(returnGid.lastIndexOf('/') + 1)));
        p.put("admin_graphql_api_id", returnGid);
        p.put("status", "closed");
        return p;
    }

    ObjectNode fulfillmentOrder(String foGid, String orderGid, String status, String hold, long lineId, long variantRest) {
        ObjectNode fo = mapper.createObjectNode();
        fo.put("id", foGid);
        fo.put("status", status);
        fo.put("requestStatus", "UNSUBMITTED");
        fo.put("createdAt", T0.toString());
        fo.put("updatedAt", T0.toString());
        fo.putObject("order").put("id", orderGid);
        ArrayNode holds = fo.putArray("fulfillmentHolds");
        if (hold != null) holds.addObject().put("reason", hold);
        ObjectNode li = fo.putObject("lineItems");
        li.putObject("pageInfo").put("hasNextPage", false);
        ObjectNode n = li.putArray("nodes").addObject();
        n.put("id", gid("FulfillmentOrderLineItem"));
        n.put("remainingQuantity", 1);
        n.put("totalQuantity", 1);
        n.putObject("lineItem").put("id", "gid://shopify/LineItem/" + lineId)
            .putObject("variant").put("id", "gid://shopify/ProductVariant/" + variantRest);
        return fo;
    }

    /** A Shopify order Traced holds (via the real orders/create path), line M ×1. Returns its gid. */
    String tracedOrder(Shop s, long lineM) {
        String gid = orderGid();
        s.send("orders/create", order(gid, T0, line(lineM, s.restM, 1)));
        return gid;
    }

    // ── i1 / i2 ─────────────────────────────────────────────────────────────────────────────────

    @Test
    void i1_sameWebhookTwice_sameRows() {
        Shop s = new Shop("i1");
        long lineM = IDS.incrementAndGet();
        String o = tracedOrder(s, lineM);
        String r = gid("Return");
        when(returnsGateway.fetchReturn(eq(s.domain), anyString(), eq(r)))
            .thenReturn(returnNode(r, o, "CLOSED", lineM, s.restM, s.restL, null, null));

        ObjectNode payload = thinReturnWebhook(r);
        s.send("returns/process", payload);
        List<Map<String, Object>> first = snapshot(s);
        s.send("returns/process", payload);
        List<Map<String, Object>> second = snapshot(s);

        assertThat(s.count("shopify_returns")).isEqualTo(1);
        assertThat(s.count("shopify_return_lines")).isEqualTo(1);
        assertThat(s.count("shopify_exchange_lines")).isEqualTo(1);
        assertThat(s.count("shopify_rfo_dispositions")).isEqualTo(1);
        assertThat(second).as("same input → same rows").isEqualTo(first);
        Map<String, Object> ret = jdbc.queryForMap("SELECT name, status, order_id FROM shopify_returns WHERE return_gid = ?", r);
        assertThat(ret.get("name")).isEqualTo("#247060-R1");
        assertThat(ret.get("order_id")).isEqualTo(s.orderId(o));
        Map<String, Object> d = jdbc.queryForMap(
            "SELECT d.type, d.quantity, d.return_line_gid = l.line_gid AS linked FROM shopify_rfo_dispositions d " +
            "JOIN shopify_return_lines l ON l.return_id = d.return_id WHERE d.tenant_id = ?", s.f.tenant);
        assertThat(d).containsEntry("type", "RESTOCKED").containsEntry("quantity", 1).containsEntry("linked", true);
        assertThat(jdbc.queryForObject("SELECT variant_id FROM shopify_exchange_lines WHERE tenant_id = ?", UUID.class, s.f.tenant))
            .isEqualTo(s.variantL);
    }

    private List<Map<String, Object>> snapshot(Shop s) {
        List<Map<String, Object>> rows = new ArrayList<>();
        rows.addAll(jdbc.queryForList("SELECT id, return_gid, order_id, name, status, total_quantity, raw::text FROM shopify_returns WHERE tenant_id = ? ORDER BY id", s.f.tenant));
        rows.addAll(jdbc.queryForList("SELECT id, line_gid, variant_id, quantity, processed_quantity, reason, raw::text FROM shopify_return_lines WHERE tenant_id = ? ORDER BY id", s.f.tenant));
        rows.addAll(jdbc.queryForList("SELECT id, line_gid, variant_id, quantity, order_line_gids::text, raw::text FROM shopify_exchange_lines WHERE tenant_id = ? ORDER BY id", s.f.tenant));
        rows.addAll(jdbc.queryForList("SELECT id, disposition_gid, type, quantity, raw::text FROM shopify_rfo_dispositions WHERE tenant_id = ? ORDER BY id", s.f.tenant));
        return rows;
    }

    @Test
    void i2_truncatedPayload_fullReturnFetchedByGid() {
        Shop s = new Shop("i2");
        long lineM = IDS.incrementAndGet();
        String o = tracedOrder(s, lineM);
        String r = gid("Return");
        ObjectNode full = returnNode(r, o, "OPEN", lineM, s.restM, s.restL, null, null);
        // a second returned line the webhook never carried
        ObjectNode extra = ((ObjectNode) full.path("returnLineItems").path("nodes").get(0)).deepCopy();
        extra.put("id", gid("ReturnLineItem"));
        ((ArrayNode) full.path("returnLineItems").path("nodes")).add(extra);
        when(returnsGateway.fetchReturn(eq(s.domain), anyString(), eq(r))).thenReturn(full);

        // returns/update: numeric id only, a cut-short line array (total 2, 0 sent)
        ObjectNode thin = mapper.createObjectNode();
        thin.put("id", Long.parseLong(r.substring(r.lastIndexOf('/') + 1)));
        thin.putObject("return_line_items").put("total_count", 2).putArray("added");
        UUID e = s.send("returns/update", thin);

        verify(returnsGateway).fetchReturn(eq(s.domain), eq("tok"), eq(r));
        assertThat(s.count("shopify_return_lines")).as("both lines, from the fetch").isEqualTo(2);
        assertThat(s.event(e).get("process_error")).isNull();
    }

    // ── d1 ACCESS_DENIED ────────────────────────────────────────────────────────────────────────

    @Test
    void d1_accessDenied_degradesQuietly_noThrow_noRetry() {
        Shop s = new Shop("d1");
        String o = tracedOrder(s, IDS.incrementAndGet());
        String r = gid("Return");
        when(returnsGateway.fetchReturn(anyString(), anyString(), anyString()))
            .thenThrow(new ShopifyReturnsAccessDeniedException("denied"));

        UUID[] e = new UUID[1];
        assertThatNoException().isThrownBy(() -> e[0] = s.send("returns/request", thinReturnWebhook(r)));
        Map<String, Object> ev = s.event(e[0]);
        assertThat(ev.get("processed_at")).as("processed").isNotNull();
        assertThat(ev.get("process_error")).as("never failed").isNull();
        assertThat(ev.get("retry_count")).isEqualTo(0);
        assertThat(ev.get("next_retry_at")).as("never retried").isNull();
        verify(returnsGateway, times(1)).fetchReturn(anyString(), anyString(), anyString());
        assertThat(jdbc.queryForObject("SELECT returns_access FROM stores WHERE id = ?", String.class, s.f.store))
            .isEqualTo("not_granted");
        assertThat(s.count("shopify_returns")).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM exception_resolutions WHERE tenant_id = ?", Integer.class, s.f.tenant))
            .as("no alert").isZero();

        // The backfill degrades the same way: one call, no throw, stopped as failed (waits for app/scopes_update).
        when(returnsGateway.fetchOrderReturnIds(anyString(), anyString(), anyList()))
            .thenThrow(new ShopifyReturnsAccessDeniedException("denied"));
        s.grant();
        jdbc.update("UPDATE stores SET returns_backfill_status = 'pending', returns_backfill_requested_at = now() WHERE id = ?", s.f.store);
        assertThatNoException().isThrownBy(() -> backfill.run(s.f.store, s.f.tenant));
        verify(returnsGateway, times(1)).fetchOrderReturnIds(anyString(), anyString(), anyList());
        assertThat(jdbc.queryForMap("SELECT returns_backfill_status, returns_backfill_error FROM stores WHERE id = ?", s.f.store))
            .containsEntry("returns_backfill_status", "failed")
            .containsEntry("returns_backfill_error", "returns access not granted");
        assertThat(o).isNotNull();
    }

    // ── s1 app/scopes_update ────────────────────────────────────────────────────────────────────

    @Test
    void s1_scopesUpdate_refreshesScopes_registersMissingTopics_requestsBackfill() {
        Shop s = new Shop("s1");
        long lineM = IDS.incrementAndGet();
        String o = tracedOrder(s, lineM);
        String r = gid("Return");
        String fo = gid("FulfillmentOrder");
        when(shopifyGateway.fetchGrantedScopes(eq(s.domain), anyString())).thenReturn(NEW_SCOPES);
        when(shopifyGateway.listWebhookSubscriptions(anyString(), anyString())).thenReturn(subscriptions(
            "orders/create", "orders/updated", "orders/cancelled", "products/create", "products/update",
            "inventory_items/update", "inventory_levels/update", "app/uninstalled", "app/scopes_update"));

        ObjectNode payload = mapper.createObjectNode();
        payload.putArray("previous").add("read_orders");
        payload.putArray("current").add("read_orders").add("write_returns");
        UUID e = s.send("app/scopes_update", payload);

        assertThat(s.event(e).get("process_error")).isNull();
        Map<String, Object> st = jdbc.queryForMap(
            "SELECT access_token_scopes, returns_access, returns_backfill_status, webhook_topics_version FROM stores WHERE id = ?",
            s.f.store);
        assertThat(st).containsEntry("access_token_scopes", NEW_SCOPES).containsEntry("returns_access", "granted")
            .containsEntry("returns_backfill_status", "pending")
            .containsEntry("webhook_topics_version", RegisterShopifyWebhooksJob.TOPICS_VERSION);
        verify(shopifyGateway).forgetOrderPiiTier(s.domain);
        List<String> registered = registeredTopics(s.domain);
        assertThat(registered).as("only the missing topics").containsExactlyInAnyOrder(
            "returns/request", "returns/approve", "returns/decline", "returns/cancel", "returns/close", "returns/reopen",
            "returns/process", "returns/update", "reverse_fulfillment_orders/dispose",
            "fulfillment_orders/placed_on_hold", "fulfillment_orders/hold_released", "fulfillment_orders/cancelled",
            "fulfillment_orders/order_routing_complete", "fulfillment_orders/split", "fulfillment_orders/merged",
            "fulfillment_orders/moved");
        verify(shopifyGateway, never()).deleteWebhookSubscription(anyString(), anyString(), anyString());

        // The requested backfill (run here directly — JobRunr is mocked): only the orders Traced holds are asked about.
        String other = tracedOrder(s, IDS.incrementAndGet());
        when(returnsGateway.fetchOrderReturnIds(eq(s.domain), anyString(), anyList()))
            .thenReturn(Map.of(o, List.of(r), other, List.of()));
        when(returnsGateway.fetchReturn(eq(s.domain), anyString(), eq(r)))
            .thenReturn(returnNode(r, o, "CLOSED", lineM, s.restM, s.restL, null, null));
        when(returnsGateway.fetchOrderFulfillmentOrderIds(eq(s.domain), anyString(), eq(o))).thenReturn(List.of(fo));
        when(returnsGateway.fetchFulfillmentOrder(eq(s.domain), anyString(), eq(fo)))
            .thenReturn(fulfillmentOrder(fo, o, "ON_HOLD", "AWAITING_PAYMENT", IDS.incrementAndGet(), s.restL));
        backfill.run(s.f.store, s.f.tenant);

        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<List<String>> asked = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(returnsGateway).fetchOrderReturnIds(eq(s.domain), anyString(), asked.capture());
        assertThat(asked.getValue()).containsExactlyInAnyOrder(o, other);
        assertThat(jdbc.queryForObject("SELECT returns_backfill_status FROM stores WHERE id = ?", String.class, s.f.store))
            .isEqualTo("done");
        assertThat(s.count("shopify_returns")).isEqualTo(1);
        assertThat(jdbc.queryForMap("SELECT status, hold_reasons::text AS holds FROM shopify_fulfillment_orders WHERE fo_gid = ?", fo))
            .containsEntry("status", "ON_HOLD").containsEntry("holds", "{AWAITING_PAYMENT}");

        // Safe to re-run: same rows.
        jdbc.update("UPDATE stores SET returns_backfill_status = 'pending', returns_backfill_cursor = NULL WHERE id = ?", s.f.store);
        backfill.run(s.f.store, s.f.tenant);
        assertThat(s.count("shopify_returns")).isEqualTo(1);
        assertThat(s.count("shopify_fulfillment_orders")).isEqualTo(1);
    }

    private List<ShopifyGateway.WebhookSubscription> subscriptions(String... topics) {
        String base = "http://localhost:8080/webhooks/shopify/";
        List<ShopifyGateway.WebhookSubscription> out = new ArrayList<>();
        for (String t : topics) out.add(new ShopifyGateway.WebhookSubscription("gid://x/" + t, t.replace("/", "_").toUpperCase(), base + t));
        return out;
    }

    private List<String> registeredTopics(String domain) {
        org.mockito.ArgumentCaptor<String> topic = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(shopifyGateway, atLeast(0)).registerWebhook(eq(domain), anyString(), topic.capture(), anyString());
        return topic.getAllValues();
    }

    // ── r1 topic rollout ────────────────────────────────────────────────────────────────────────

    @Test
    void r1_topicRollout_onlyMissing_scopeTopicsDeferredUntilGranted() {
        Shop s = new Shop("r1");
        jdbc.update("UPDATE stores SET webhook_topics_version = 1 WHERE id = ?", s.f.store);
        when(shopifyGateway.fetchGrantedScopes(anyString(), anyString())).thenReturn(null);   // live read fails → stored
        when(shopifyGateway.listWebhookSubscriptions(anyString(), anyString())).thenReturn(subscriptions(
            "orders/create", "orders/updated", "orders/cancelled", "products/create", "products/update",
            "inventory_items/update", "inventory_levels/update", "app/uninstalled"));

        RegisterShopifyWebhooksJob.TopicsOutcome out = register.addMissingTopics(s.f.store, s.f.tenant);
        assertThat(out.added()).containsExactly("app/scopes_update");
        assertThat(out.deferred()).hasSize(16);
        assertThat(out.failed()).isEmpty();
        assertThat(registeredTopics(s.domain)).containsExactly("app/scopes_update");
        assertThat(jdbc.queryForObject("SELECT webhook_topics_version FROM stores WHERE id = ?", Integer.class, s.f.store))
            .as("deferred is not a failure").isEqualTo(RegisterShopifyWebhooksJob.TOPICS_VERSION);

        reset(shopifyGateway);
        s.grant();
        when(shopifyGateway.listWebhookSubscriptions(anyString(), anyString())).thenReturn(subscriptions(
            "orders/create", "orders/updated", "orders/cancelled", "products/create", "products/update",
            "inventory_items/update", "inventory_levels/update", "app/uninstalled", "app/scopes_update"));
        out = register.addMissingTopics(s.f.store, s.f.tenant);
        assertThat(out.added()).hasSize(16);
        assertThat(out.deferred()).isEmpty();
        assertThat(registeredTopics(s.domain)).doesNotContain("orders/create", "app/scopes_update");
        verify(shopifyGateway, never()).deleteWebhookSubscription(anyString(), anyString(), anyString());
    }

    // ── p1 pre-connect ──────────────────────────────────────────────────────────────────────────

    @Test
    void p1_orderNotInTraced_ignored() {
        Shop s = new Shop("p1");
        String preConnect = orderGid();                                       // Shopify has it, Traced doesn't
        String r = gid("Return");
        String fo = gid("FulfillmentOrder");
        when(returnsGateway.fetchReturn(anyString(), anyString(), eq(r)))
            .thenReturn(returnNode(r, preConnect, "OPEN", IDS.incrementAndGet(), s.restM, s.restL, null, null));
        when(returnsGateway.fetchFulfillmentOrder(anyString(), anyString(), eq(fo)))
            .thenReturn(fulfillmentOrder(fo, preConnect, "OPEN", null, IDS.incrementAndGet(), s.restM));

        UUID e1 = s.send("returns/request", thinReturnWebhook(r));
        ObjectNode foPayload = mapper.createObjectNode();
        foPayload.putObject("fulfillment_order").put("id", fo).put("status", "on_hold");
        UUID e2 = s.send("fulfillment_orders/placed_on_hold", foPayload);

        assertThat(s.count("shopify_returns")).isZero();
        assertThat(s.count("shopify_fulfillment_orders")).isZero();
        assertThat(s.event(e1).get("process_error")).isNull();
        assertThat(s.event(e2).get("process_error")).isNull();
    }

    // ── l1 / l2 exchange-line link ──────────────────────────────────────────────────────────────

    @Test
    void l1_returnsProcessFirst_thenOrdersUpdated_lineLinked() {
        Shop s = new Shop("l1");
        long lineM = IDS.incrementAndGet(), lineL = IDS.incrementAndGet();
        String o = tracedOrder(s, lineM);
        String r = gid("Return");
        ObjectNode ret = returnNode(r, o, "CLOSED", lineM, s.restM, s.restL, lineL, null);
        when(returnsGateway.fetchReturn(anyString(), anyString(), eq(r))).thenReturn(ret);
        s.send("returns/process", thinReturnWebhook(r));
        assertThat(linked(s, lineL)).as("the order line isn't stored yet").isNull();

        // Shopify then adds the exchange line to the original order.
        s.send("orders/updated", order(o, T0.plusSeconds(60), line(lineM, s.restM, 1), line(lineL, s.restL, 1)));
        String xGid = ret.path("exchangeLineItems").path("nodes").get(0).path("id").asText();
        assertThat(linked(s, lineL)).as("linked when the order line arrives").isEqualTo(xGid);
        assertThat(linked(s, lineM)).isNull();
        assertThat(qty(s, lineL)).as("quantity untouched by the link").isEqualTo(1);
    }

    @Test
    void l2_ordersUpdatedFirst_thenReturnsProcess_lineLinked() {
        Shop s = new Shop("l2");
        long lineM = IDS.incrementAndGet(), lineL = IDS.incrementAndGet();
        String o = tracedOrder(s, lineM);
        s.send("orders/updated", order(o, T0.plusSeconds(60), line(lineM, s.restM, 1), line(lineL, s.restL, 1)));
        assertThat(linked(s, lineL)).isNull();
        int before = qty(s, lineL);

        String r = gid("Return");
        ObjectNode ret = returnNode(r, o, "CLOSED", lineM, s.restM, s.restL, lineL, null);
        when(returnsGateway.fetchReturn(anyString(), anyString(), eq(r))).thenReturn(ret);
        s.send("returns/process", thinReturnWebhook(r));
        assertThat(linked(s, lineL)).isEqualTo(ret.path("exchangeLineItems").path("nodes").get(0).path("id").asText());
        assertThat(qty(s, lineL)).isEqualTo(before);
    }

    private String linked(Shop s, long line) {
        List<String> v = jdbc.queryForList("SELECT shopify_exchange_line_gid FROM order_items WHERE tenant_id = ? AND external_id = ?",
            String.class, s.f.tenant, "gid://shopify/LineItem/" + line);
        return v.isEmpty() ? null : v.get(0);
    }

    private int qty(Shop s, long line) {
        return jdbc.queryForObject("SELECT quantity FROM order_items WHERE tenant_id = ? AND external_id = ?",
            Integer.class, s.f.tenant, "gid://shopify/LineItem/" + line);
    }

    // ── g1 GDPR ─────────────────────────────────────────────────────────────────────────────────

    @Test
    void g1_redactCoversMirror_andStaysRedacted_exportIncludesIt() {
        Shop s = new Shop("g1");
        long lineM = IDS.incrementAndGet();
        String o = tracedOrder(s, lineM);
        String r = gid("Return");
        ObjectNode ret = returnNode(r, o, "DECLINED", lineM, s.restM, s.restL, null, "please call me PHONEX-555");
        ret.putObject("decline").put("reason", "OTHER").put("note", "Dear Mona, sorry");
        when(returnsGateway.fetchReturn(anyString(), anyString(), eq(r))).thenReturn(ret);
        ObjectNode hook = thinReturnWebhook(r);
        hook.putObject("order").put("admin_graphql_api_id", o);
        hook.putArray("return_line_items").addObject().put("customer_note", "please call me PHONEX-555");
        UUID hookEvent = s.send("returns/decline", hook);
        assertThat(jdbc.queryForObject("SELECT customer_note FROM shopify_return_lines WHERE tenant_id = ?", String.class, s.f.tenant))
            .isEqualTo("please call me PHONEX-555");

        // export (before redaction) includes the mirror
        UUID req = jdbc.queryForObject(
            "INSERT INTO customer_data_requests (tenant_id, webhook_event_id, shop_domain, shopify_customer_id, orders_requested, expires_at) " +
            "VALUES (?, ?, ?, '1', ARRAY[?], now() + interval '30 days') RETURNING id",
            UUID.class, s.f.tenant, hookEvent, s.domain, o);
        String export = exportJson(s, req);
        assertThat(export).contains("\"shopify_returns\"").contains("#247060-R1").contains("please call me PHONEX-555");

        CustomerRedaction.Result res = TenantContext.runAs(s.f.tenant, () -> new TransactionTemplate(txm).execute(t ->
            new CustomerRedaction(jdbc).redactCustomer(s.f.tenant, List.of(o), null, null)));
        assertThat(res.shopifyReturns()).isEqualTo(1);
        assertRedacted(s, r, hookEvent);

        // a later sync brings the notes again → still redacted
        s.send("returns/update", thinReturnWebhook(r));
        assertRedacted(s, r, hookEvent);

        // a return of an already-redacted order is born redacted
        String r2 = gid("Return");
        ObjectNode ret2 = returnNode(r2, o, "OPEN", lineM, s.restM, s.restL, null, "my address is 5 Nile St");
        when(returnsGateway.fetchReturn(anyString(), anyString(), eq(r2))).thenReturn(ret2);
        s.send("returns/request", thinReturnWebhook(r2));
        assertThat(jdbc.queryForObject("SELECT pii_redacted_at IS NOT NULL FROM shopify_returns WHERE return_gid = ?", Boolean.class, r2)).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM shopify_return_lines l JOIN shopify_returns rr ON rr.id = l.return_id " +
            "WHERE rr.return_gid = ? AND (l.customer_note IS NOT NULL OR l.raw::text LIKE '%Nile%')", Integer.class, r2)).isZero();
    }

    private void assertRedacted(Shop s, String r, UUID hookEvent) {
        Map<String, Object> ret = jdbc.queryForMap("SELECT decline_note, pii_redacted_at, raw::text AS raw FROM shopify_returns WHERE return_gid = ?", r);
        assertThat(ret.get("pii_redacted_at")).isNotNull();
        assertThat(ret.get("decline_note")).isNull();
        assertThat((String) ret.get("raw")).doesNotContain("PHONEX").doesNotContain("Dear Mona").doesNotContain("too small");
        Map<String, Object> l = jdbc.queryForMap("SELECT l.customer_note, l.reason_note, l.raw::text AS raw FROM shopify_return_lines l " +
            "JOIN shopify_returns rr ON rr.id = l.return_id WHERE rr.return_gid = ?", r);
        assertThat(l.get("customer_note")).isNull();
        assertThat(l.get("reason_note")).isNull();
        assertThat((String) l.get("raw")).doesNotContain("PHONEX").doesNotContain("too small");
        assertThat(jdbc.queryForObject("SELECT payload_raw::text FROM shopify_webhook_events WHERE id = ?", String.class, hookEvent))
            .doesNotContain("PHONEX");
    }

    private String exportJson(Shop s, UUID requestId) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(jwt.issueAccessToken(s.owner, s.f.tenant, "owner"));
        ResponseEntity<String> resp = rest.exchange("http://localhost:" + port + "/api/v1/privacy/data-requests/" + requestId + "/export",
            HttpMethod.GET, new HttpEntity<>(h), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        return resp.getBody();
    }

    // ── x1 app_user ─────────────────────────────────────────────────────────────────────────────

    @Test
    void x1_appUser_crossTenantSeesNothing_sameTenantWorks() {
        Shop a = new Shop("x1a");
        Shop b = new Shop("x1b");
        long lineM = IDS.incrementAndGet();
        String o = tracedOrder(a, lineM);
        UUID orderA = a.orderId(o);

        DriverManagerDataSource raw = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "app_user", "testpw");
        TenantAwareDataSource appDs = new TenantAwareDataSource(raw);
        JdbcTemplate appJdbc = new JdbcTemplate(appDs);
        TransactionTemplate appTx = new TransactionTemplate(new DataSourceTransactionManager(appDs));
        ShopifyReturnsStore appStore = new ShopifyReturnsStore(appJdbc, mapper);
        String r = gid("Return");
        ObjectNode ret = returnNode(r, o, "OPEN", lineM, a.restM, a.restL, null, null);

        // same tenant (positive control): the real store finds A's order and upserts under app_user + RLS
        UUID found = app(appTx, a.f.tenant, () -> appStore.tracedOrderId(a.f.store, o));
        assertThat(found).isEqualTo(orderA);
        app(appTx, a.f.tenant, () -> appStore.upsertReturn(a.f.store, orderA, ret));
        assertThat(app(appTx, a.f.tenant, () ->
            appJdbc.queryForObject("SELECT count(*) FROM shopify_returns", Integer.class))).isEqualTo(1);
        assertThat(app(appTx, a.f.tenant, () ->
            appJdbc.queryForObject("SELECT count(*) FROM shopify_return_lines", Integer.class))).isEqualTo(1);

        // tenant B: sees none of A's rows, can't find A's order, and can't point a row at it
        for (String table : List.of("shopify_returns", "shopify_return_lines", "shopify_exchange_lines",
                                    "shopify_rfo_dispositions", "shopify_fulfillment_orders")) {
            assertThat(app(appTx, b.f.tenant, () ->
                appJdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class))).as(table).isZero();
        }
        assertThat(app(appTx, b.f.tenant, () -> appStore.tracedOrderId(a.f.store, o))).isNull();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> app(appTx, b.f.tenant, () ->
                appStore.upsertReturn(b.f.store, orderA, returnNode(gid("Return"), o, "OPEN", lineM, a.restM, a.restL, null, null))))
            .as("composite FK: B can't point at A's order").isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        // app_user has no DELETE on the mirror
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> app(appTx, a.f.tenant, () ->
                appJdbc.update("DELETE FROM shopify_returns")))
            .isInstanceOf(org.springframework.dao.DataAccessException.class);
    }

    private static <T> T app(TransactionTemplate tx, UUID tenant, java.util.function.Supplier<T> body) {
        return TenantContext.runAs(tenant, (java.util.concurrent.Callable<T>) () -> tx.execute(st -> body.get()));
    }

    // ── n1 owner notice ─────────────────────────────────────────────────────────────────────────

    @Test
    void n1_newPermissionsNotice_ownerOnly_untilGranted() throws Exception {
        Shop s = new Shop("n1");
        UUID manager = s.f.user("Manager n1", "manager");
        JsonNode owner = mapper.readTree(getAs(s, s.owner, "owner", "/api/v1/connections")).path("shopify");
        assertThat(owner.path("newPermissions").path("openAppUrl").asText())
            .startsWith("https://admin.shopify.com/store/").contains(s.domain.replace(".myshopify.com", ""));
        JsonNode mgr = mapper.readTree(getAs(s, manager, "manager", "/api/v1/connections")).path("shopify");
        assertThat(mgr.has("newPermissions")).as("owner only").isFalse();
        s.grant();
        JsonNode granted = mapper.readTree(getAs(s, s.owner, "owner", "/api/v1/connections")).path("shopify");
        assertThat(granted.has("newPermissions")).as("gone once granted").isFalse();
    }

    private String getAs(Shop s, UUID user, String role, String path) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(jwt.issueAccessToken(user, s.f.tenant, role));
        ResponseEntity<String> resp = rest.exchange("http://localhost:" + port + path, HttpMethod.GET, new HttpEntity<>(h), String.class);
        assertThat(resp.getStatusCode()).as(path).isEqualTo(HttpStatus.OK);
        return resp.getBody();
    }

    // ── z1 golden: no behavior change ───────────────────────────────────────────────────────────

    @Test
    void z1_golden_ingestingAReturnChangesNothingMerchantsOrWorkersSee() throws Exception {
        Shop s = new Shop("z1");
        long lineM = IDS.incrementAndGet(), lineL = IDS.incrementAndGet();
        String o = tracedOrder(s, lineM);
        // Shopify added the exchange line to the original order (stored as Traced does today).
        s.send("orders/updated", order(o, T0.plusSeconds(60), line(lineM, s.restM, 1), line(lineL, s.restL, 1)));
        UUID orderId = s.orderId(o);
        s.f.forward(orderId);                                                 // a created forward leg: pickable
        // plus a second, unrelated open order of the exchange variant
        String other = orderGid();
        s.send("orders/create", order(other, T0, line(IDS.incrementAndGet(), s.restL, 2)));
        s.f.forward(s.orderId(other));

        Map<String, String> before = observe(s, orderId);
        assertThat(before.get("queue")).contains(orderId.toString());

        String r = gid("Return");
        String fo = gid("FulfillmentOrder");
        when(returnsGateway.fetchReturn(anyString(), anyString(), eq(r)))
            .thenReturn(returnNode(r, o, "CLOSED", lineM, s.restM, s.restL, lineL, null));
        when(returnsGateway.fetchFulfillmentOrder(anyString(), anyString(), eq(fo)))
            .thenReturn(fulfillmentOrder(fo, o, "ON_HOLD", "AWAITING_PAYMENT", lineL, s.restL));
        s.send("returns/process", thinReturnWebhook(r));
        ObjectNode foPayload = mapper.createObjectNode();
        foPayload.putObject("fulfillment_order").put("id", fo);
        s.send("fulfillment_orders/placed_on_hold", foPayload);
        assertThat(linked(s, lineL)).as("the mirror really ran").isNotNull();
        assertThat(s.count("shopify_fulfillment_orders")).isEqualTo(1);

        Map<String, String> after = observe(s, orderId);
        // S2 (approved 2026-11-06): the order detail now SHOWS the Shopify return, read-only — the "From Shopify" card
        // (shopifyReturns) and the "Exchange item for #…-R1" label (items[].exchangeFor). Only those two are stripped;
        // every other field of the detail, and every other observation, must still be byte-identical.
        after.put("orderDetail", withoutS2Display(after.get("orderDetail")));
        before.put("orderDetail", mapper.writeValueAsString(mapper.readTree(before.get("orderDetail"))));   // same round-trip
        for (String k : before.keySet()) {
            assertThat(after.get(k)).as(k + " byte-identical").isEqualTo(before.get(k));
        }
    }

    /** The order detail JSON minus S2's two display-only fields (asserted present first). */
    private String withoutS2Display(String detailJson) throws Exception {
        ObjectNode d = (ObjectNode) mapper.readTree(detailJson);
        assertThat(d.has("shopifyReturns")).as("S2 card present").isTrue();
        d.remove("shopifyReturns");
        int labelled = 0;
        for (com.fasterxml.jackson.databind.JsonNode item : d.get("items")) {
            if (item.has("exchangeFor")) labelled++;
            ((ObjectNode) item).remove("exchangeFor");
        }
        assertThat(labelled).as("S2 exchange line label").isEqualTo(1);
        return mapper.writeValueAsString(d);
    }

    private Map<String, String> observe(Shop s, UUID orderId) throws Exception {
        Map<String, String> out = new LinkedHashMap<>();
        Map<UUID, VariantStockService.VariantStock> all = TenantContext.runAs(s.f.tenant, () -> stock.computeAll());
        out.put("committed", mapper.writeValueAsString(new TreeMap<>(Map.of(
            "M", String.valueOf(all.get(s.variantM)), "L", String.valueOf(all.get(s.variantL))))));
        out.put("queue", get(s, "/api/v1/fulfill/queue"));
        out.put("pickDetail", get(s, "/api/v1/fulfill/" + orderId));
        out.put("ordersList", get(s, "/api/v1/orders"));
        out.put("ordersCounts", get(s, "/api/v1/orders/counts"));
        out.put("orderDetail", get(s, "/api/v1/orders/" + orderId));
        return out;
    }

    private String get(Shop s, String path) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(jwt.issueAccessToken(s.owner, s.f.tenant, "owner"));
        ResponseEntity<String> resp = rest.exchange("http://localhost:" + port + path, HttpMethod.GET, new HttpEntity<>(h), String.class);
        assertThat(resp.getStatusCode()).as(path).isEqualTo(HttpStatus.OK);
        return resp.getBody();
    }

    // ── t1: token re-exchange loop guard ─────────────────────────────────────────────────────────

    @Test
    void t1_scopeDifferenceReexchangesOncePerScopeSet_notOnEveryOpen() {
        Shop s = new Shop("t1");
        jdbc.update("UPDATE stores SET access_token_expires_at = now() + interval '1 hour', " +
                    "refresh_token_encrypted = 'enc-refresh' WHERE id = ?", s.f.store);
        when(shopifyGateway.exchangeSessionToken(eq(s.domain), any()))
            .thenReturn(new ShopifyGateway.TokenResponse("shpat_t1", "shprt_t1", 3600L, 7776000L, BASE_SCOPES));

        // first open: the stored token lacks the new scopes → one re-exchange; Shopify still grants only the old ones
        for (int i = 0; i < 5; i++) assertThat(oauth.acquireOrRefreshViaSessionToken(s.f.tenant, s.domain, "st")).isTrue();
        verify(shopifyGateway, times(1)).exchangeSessionToken(eq(s.domain), any());
        Map<String, Object> g = jdbc.queryForMap(
            "SELECT scopes_awaiting_approval, scopes_awaiting_since FROM stores WHERE id = ?", s.f.store);
        assertThat((String) g.get("scopes_awaiting_approval")).contains("read_returns")
            .contains("read_merchant_managed_fulfillment_orders");
        assertThat(g.get("scopes_awaiting_since")).isNotNull();

        // 24 h later (a missed app/scopes_update) → once more, then quiet again
        jdbc.update("UPDATE stores SET scopes_awaiting_since = now() - interval '25 hours' WHERE id = ?", s.f.store);
        for (int i = 0; i < 3; i++) oauth.acquireOrRefreshViaSessionToken(s.f.tenant, s.domain, "st");
        verify(shopifyGateway, times(2)).exchangeSessionToken(eq(s.domain), any());

        // a token close to expiry still exchanges (normal refresh, guard or not)
        jdbc.update("UPDATE stores SET access_token_expires_at = now() + interval '5 minutes' WHERE id = ?", s.f.store);
        oauth.acquireOrRefreshViaSessionToken(s.f.tenant, s.domain, "st");
        oauth.acquireOrRefreshViaSessionToken(s.f.tenant, s.domain, "st");
        verify(shopifyGateway, times(3)).exchangeSessionToken(eq(s.domain), any());

        // a different declared scope set (the app added yet another scope) is not covered by the stored guard
        jdbc.update("UPDATE stores SET scopes_awaiting_approval = 'read_orders' WHERE id = ?", s.f.store);
        oauth.acquireOrRefreshViaSessionToken(s.f.tenant, s.domain, "st");
        oauth.acquireOrRefreshViaSessionToken(s.f.tenant, s.domain, "st");
        verify(shopifyGateway, times(4)).exchangeSessionToken(eq(s.domain), any());

        // the merchant approves → app/scopes_update clears the guard → the next open re-exchanges once, the new token
        // carries the scopes → the guard is cleared and later opens don't exchange
        when(shopifyGateway.fetchGrantedScopes(eq(s.domain), anyString())).thenReturn(BASE_SCOPES);
        when(shopifyGateway.listWebhookSubscriptions(anyString(), anyString())).thenReturn(List.of());
        ObjectNode payload = mapper.createObjectNode();
        payload.putArray("previous").add("read_orders");
        payload.putArray("current").add("read_orders").add("write_returns");
        s.send("app/scopes_update", payload);
        assertThat(jdbc.queryForObject("SELECT scopes_awaiting_approval FROM stores WHERE id = ?", String.class, s.f.store))
            .isNull();
        when(shopifyGateway.exchangeSessionToken(eq(s.domain), any()))
            .thenReturn(new ShopifyGateway.TokenResponse("shpat_t1b", "shprt_t1b", 3600L, 7776000L, NEW_SCOPES));
        for (int i = 0; i < 3; i++) oauth.acquireOrRefreshViaSessionToken(s.f.tenant, s.domain, "st");
        verify(shopifyGateway, times(5)).exchangeSessionToken(eq(s.domain), any());
        assertThat(jdbc.queryForMap(
            "SELECT access_token_scopes, scopes_awaiting_approval, scopes_awaiting_since FROM stores WHERE id = ?", s.f.store))
            .containsEntry("access_token_scopes", NEW_SCOPES)
            .containsEntry("scopes_awaiting_approval", null).containsEntry("scopes_awaiting_since", null);
    }


    // ── f1 / f2 fulfillment-order refresh (S1 follow-up, prod #1006) ───────────────────────

    /**
     * Shopify sends no fulfillment_orders/* webhook when an FO is fulfilled: the FO is released (hold_released →
     * OPEN, mirrored), then fulfilled in Shopify (FO → CLOSED). The order's orders/updated is the signal — the open FO
     * is re-fetched by GID and the mirror shows CLOSED. A final FO is never fetched again.
     */
    @Test
    void f1_fulfilledAfterRelease_ordersUpdatedClosesTheMirroredFo() {
        Shop s = new Shop("f1");
        long lineM = IDS.incrementAndGet();
        String o = tracedOrder(s, lineM);
        String fo = gid("FulfillmentOrder");
        when(returnsGateway.fetchFulfillmentOrder(anyString(), anyString(), eq(fo)))
            .thenReturn(fulfillmentOrder(fo, o, "OPEN", null, lineM, s.restM));
        ObjectNode released = mapper.createObjectNode();
        released.putObject("fulfillment_order").put("id", fo).put("status", "open");
        s.send("fulfillment_orders/hold_released", released);
        assertThat(jdbc.queryForObject("SELECT status FROM shopify_fulfillment_orders WHERE fo_gid = ?", String.class, fo))
            .isEqualTo("OPEN");

        // Fulfilled in Shopify: no FO webhook — only orders/updated.
        reset(returnsGateway);
        when(returnsGateway.fetchFulfillmentOrder(anyString(), anyString(), eq(fo)))
            .thenReturn(fulfillmentOrder(fo, o, "CLOSED", null, lineM, s.restM));
        UUID e = s.send("orders/updated", order(o, T0.plusSeconds(60), line(lineM, s.restM, 1)));
        assertThat(s.event(e).get("process_error")).isNull();
        verify(returnsGateway, times(1)).fetchFulfillmentOrder(eq(s.domain), anyString(), eq(fo));
        assertThat(jdbc.queryForObject("SELECT status FROM shopify_fulfillment_orders WHERE fo_gid = ?", String.class, fo))
            .isEqualTo("CLOSED");

        // CLOSED is final: the next orders/updated fetches nothing.
        reset(returnsGateway);
        s.send("orders/updated", order(o, T0.plusSeconds(120), line(lineM, s.restM, 1)));
        verifyNoInteractions(returnsGateway);
        assertThat(s.count("shopify_fulfillment_orders")).isEqualTo(1);
    }

    /** A normal order (no mirrored FO): orders/updated makes zero Shopify returns / FO calls. */
    @Test
    void f2_orderWithNoMirroredFo_zeroShopifyCalls() {
        Shop s = new Shop("f2");
        long lineM = IDS.incrementAndGet();
        String o = tracedOrder(s, lineM);
        // another order of the same store HAS an open mirrored FO — must not leak into this one
        long lineOther = IDS.incrementAndGet();
        String other = tracedOrder(s, lineOther);
        String fo = gid("FulfillmentOrder");
        when(returnsGateway.fetchFulfillmentOrder(anyString(), anyString(), eq(fo)))
            .thenReturn(fulfillmentOrder(fo, other, "OPEN", null, lineOther, s.restM));
        ObjectNode released = mapper.createObjectNode();
        released.putObject("fulfillment_order").put("id", fo);
        s.send("fulfillment_orders/hold_released", released);

        reset(returnsGateway, tokenProvider);
        when(tokenProvider.getValidToken(any())).thenReturn("tok");
        UUID e1 = s.send("orders/updated", order(o, T0.plusSeconds(60), line(lineM, s.restM, 1)));
        UUID e2 = s.send("orders/updated", order(o, T0.plusSeconds(120), line(lineM, s.restM, 1)));
        verifyNoInteractions(returnsGateway);
        verify(tokenProvider, never()).getValidToken(any());
        for (UUID e : List.of(e1, e2)) {
            assertThat(s.event(e).get("processed_at")).isNotNull();
            assertThat(s.event(e).get("process_error")).isNull();
        }
    }
}
