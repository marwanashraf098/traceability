package com.traceability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.traceability.integrations.bosta.BostaGateway;
import com.traceability.integrations.shopify.ShopifySyncService;
import com.traceability.integrations.shopify.ShopifyWebhookProcessorJob;
import com.traceability.inventory.FulfillService;
import com.traceability.tenancy.TenantContext;
import org.jobrunr.scheduling.JobScheduler;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Shopify order webhooks — freshness under the row lock and a short lock (2026-11-04, prod: order upserts waiting
 * 10–40 s on each other's row lock).
 *
 *   f1  RACE: an older orders/updated passes the processor's pre-check (supersededReason) while a newer write of the same
 *       order holds the row; it commits after the newer one → the stored order is the newer one, the stale event is
 *       processed + superseded (no error, no retry), its lines and fulfillment tracking are not written
 *   f2  equal Shopify updated_at still applies
 *   f3  orders/create of a new order inserts it (no stored row → insert); a direct ingest of an older payload after a
 *       newer one returns STALE and writes nothing
 *   f4  a stale edit-shaped orders/updated on an order being picked releases nothing and raises nothing
 *   l1  the order row is locked only for the writes: every line's variant is resolved BEFORE UPSERT_ORDER — a variant
 *       re-keyed while the upsert waits for the row lock is still the one the line is written with
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OrderWebhookFreshnessTest {

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
    }

    @Autowired JdbcTemplate               jdbc;
    @Autowired DataSource                 dataSource;
    @Autowired ObjectMapper               mapper;
    @Autowired ShopifyWebhookProcessorJob processorJob;
    @Autowired ShopifySyncService         syncService;
    @Autowired FulfillService             fulfill;
    @MockBean  BostaGateway               bostaGateway;
    @MockBean  JobScheduler               jobScheduler;

    private static final AtomicLong IDS = new AtomicLong(60_100_000_000_000L);
    private static final Instant T0 = Instant.parse("2026-11-04T10:00:00Z");

    private final ExecutorService pool = Executors.newSingleThreadExecutor();

    @AfterAll
    void stopPool() { pool.shutdownNow(); }

    /** One tenant + connected store, variant M. */
    class Shop {
        final PackFixtures f;
        final UUID actor;
        final long restM = IDS.incrementAndGet();
        final UUID variantM;
        final String domain;

        Shop(String tag) {
            f = new PackFixtures(jdbc, "FR-" + tag);
            domain = jdbc.queryForObject("SELECT shop_domain FROM stores WHERE id = ?", String.class, f.store);
            jdbc.update("UPDATE stores SET status = 'connected' WHERE id = ?", f.store);
            actor = f.user("Packer " + tag, "owner");
            UUID product = jdbc.queryForObject(
                "INSERT INTO products (tenant_id, store_id, external_id, title, status) " +
                "VALUES (?, ?, ?, 'LINEN SHIRT', 'active') RETURNING id",
                UUID.class, f.tenant, f.store, "gid://shopify/Product/" + IDS.incrementAndGet());
            variantM = jdbc.queryForObject(
                "INSERT INTO variants (tenant_id, product_id, external_id, sku, title) VALUES (?, ?, ?, ?, 'M') RETURNING id",
                UUID.class, f.tenant, product, "gid://shopify/ProductVariant/" + restM, "LS-M-" + restM);
        }

        UUID event(String topic, ObjectNode payload) {
            return jdbc.queryForObject(
                "INSERT INTO shopify_webhook_events (tenant_id, topic, shop_domain, webhook_id, payload_raw) " +
                "VALUES (?, ?, ?, ?, ?::jsonb) RETURNING id",
                UUID.class, f.tenant, topic, domain, UUID.randomUUID().toString(), payload.toString());
        }

        UUID send(String topic, ObjectNode payload) {
            UUID e = event(topic, payload);
            processorJob.process(e, f.tenant);
            return e;
        }

        UUID orderId(String gid) {
            return jdbc.queryForObject("SELECT id FROM orders WHERE external_id = ? AND tenant_id = ?", UUID.class, gid, f.tenant);
        }

        Integer qty(UUID orderId, long lineId) {
            var q = jdbc.queryForList("SELECT quantity FROM order_items WHERE order_id = ? AND external_id = ?",
                Integer.class, orderId, "gid://shopify/LineItem/" + lineId);
            return q.isEmpty() ? null : q.get(0);
        }

        Instant storedUpdatedAt(UUID orderId) {
            return jdbc.queryForObject("SELECT shopify_order_updated_at(raw) FROM orders WHERE id = ?",
                java.sql.Timestamp.class, orderId).toInstant();
        }
    }

    private Map<String, Object> eventRow(UUID e) {
        return jdbc.queryForMap("SELECT processed_at, superseded_at, process_error, retry_count, next_retry_at " +
            "FROM shopify_webhook_events WHERE id = ?", e);
    }

    // ── f1 ────────────────────────────────────────────────────────────────────

    @Test
    void f1_olderCommitsAfterNewer_storedIsNewer_staleEventSuperseded() throws Exception {
        Shop s = new Shop("f1");
        String gid = gid();
        long lineM = IDS.incrementAndGet();
        s.send("orders/create", order(gid, T0, line(lineM, s.restM, 1), fulfillments()));
        UUID o = s.orderId(gid);

        Instant t1 = T0.plusSeconds(60), t2 = T0.plusSeconds(120);
        ObjectNode older = order(gid, t1, line(lineM, s.restM, 3), fulfillments(fulfillment("70000000101")));
        UUID olderEvent = s.event("orders/updated", older);

        // The newer write of the same order holds the row; the older event passes the pre-check (it sees T0) and waits.
        try (Connection newer = dataSource.getConnection()) {
            newer.setAutoCommit(false);
            try (PreparedStatement ps = newer.prepareStatement(
                    "UPDATE orders SET raw = jsonb_set(raw, '{updated_at}', to_jsonb(?::text)) WHERE id = ?")) {
                ps.setString(1, t2.toString());
                ps.setObject(2, o);
                ps.executeUpdate();
            }
            Future<?> run = pool.submit(() -> processorJob.process(olderEvent, s.f.tenant));
            awaitWaitingOn("INSERT INTO orders");
            newer.commit();
            run.get(30, TimeUnit.SECONDS);
        }

        assertThat(s.storedUpdatedAt(o)).as("the newer copy stays").isEqualTo(t2);
        assertThat(s.qty(o, lineM)).as("the stale payload's lines are not written").isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM order_fulfillment_tracking WHERE order_id = ?",
            Integer.class, o)).as("nothing after the upsert runs for a stale event").isZero();
        Map<String, Object> r = eventRow(olderEvent);
        assertThat(r.get("processed_at")).as("processed").isNotNull();
        assertThat(r.get("superseded_at")).as("superseded").isNotNull();
        assertThat(r.get("process_error")).as("never failed").isNull();
        assertThat(r.get("retry_count")).isEqualTo(0);
        assertThat(r.get("next_retry_at")).as("never retried").isNull();
    }

    // ── f2 ────────────────────────────────────────────────────────────────────

    @Test
    void f2_equalUpdatedAt_stillApplies() {
        Shop s = new Shop("f2");
        String gid = gid();
        long lineM = IDS.incrementAndGet();
        s.send("orders/create", order(gid, T0, line(lineM, s.restM, 1), fulfillments()));
        UUID o = s.orderId(gid);

        UUID e = s.send("orders/updated", order(gid, T0, line(lineM, s.restM, 2), fulfillments()));

        assertThat(s.qty(o, lineM)).as("same updated_at → applied").isEqualTo(2);
        Map<String, Object> r = eventRow(e);
        assertThat(r.get("processed_at")).isNotNull();
        assertThat(r.get("superseded_at")).isNull();
        assertThat(r.get("process_error")).isNull();
    }

    // ── f3 ────────────────────────────────────────────────────────────────────

    @Test
    void f3_createInserts_directOlderIngestIsStale() {
        Shop s = new Shop("f3");
        String gid = gid();
        long lineM = IDS.incrementAndGet();
        UUID created = s.send("orders/create", order(gid, T0.plusSeconds(300), line(lineM, s.restM, 2), fulfillments()));
        UUID o = s.orderId(gid);
        assertThat(s.qty(o, lineM)).isEqualTo(2);
        assertThat(eventRow(created).get("superseded_at")).isNull();

        ShopifySyncService.OrderIngest out = as(s, () ->
            syncService.ingestOrderWebhook(s.f.store, s.f.tenant, order(gid, T0, line(lineM, s.restM, 5), fulfillments())));

        assertThat(out).isEqualTo(ShopifySyncService.OrderIngest.STALE);
        assertThat(s.qty(o, lineM)).isEqualTo(2);
        assertThat(s.storedUpdatedAt(o)).isEqualTo(T0.plusSeconds(300));
    }

    // ── f4 ────────────────────────────────────────────────────────────────────

    @Test
    void f4_staleEditWhilePicking_releasesNothing_raisesNothing() throws Exception {
        Shop s = new Shop("f4");
        String gid = gid();
        long lineM = IDS.incrementAndGet();
        s.send("orders/create", order(gid, T0, line(lineM, s.restM, 1), fulfillments()));
        UUID o = s.orderId(gid);
        s.f.forward(o);
        String piece = s.f.piece(s.variantM, s.actor);
        assertThat(as(s, () -> fulfill.scan(o, piece, s.actor)).success()).isTrue();

        // An edit removing the line (current_quantity 0) that loses the race to a newer copy.
        ObjectNode edit = order(gid, T0.plusSeconds(60), line(lineM, s.restM, 0), fulfillments());
        UUID editEvent = s.event("orders/updated", edit);
        try (Connection newer = dataSource.getConnection()) {
            newer.setAutoCommit(false);
            try (PreparedStatement ps = newer.prepareStatement(
                    "UPDATE orders SET raw = jsonb_set(raw, '{updated_at}', to_jsonb(?::text)) WHERE id = ?")) {
                ps.setString(1, T0.plusSeconds(120).toString());
                ps.setObject(2, o);
                ps.executeUpdate();
            }
            Future<?> run = pool.submit(() -> processorJob.process(editEvent, s.f.tenant));
            awaitWaitingOn("INSERT INTO orders");
            newer.commit();
            run.get(30, TimeUnit.SECONDS);
        }

        assertThat(jdbc.queryForObject("SELECT count(*) FROM allocations a JOIN order_items oi ON oi.id = a.order_item_id " +
            "WHERE oi.order_id = ? AND a.status = 'active'", Integer.class, o)).as("still reserved").isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT shopify_edit_conflict_at IS NULL FROM orders WHERE id = ?", Boolean.class, o))
            .as("no edit alert").isTrue();
        assertThat(s.qty(o, lineM)).isEqualTo(1);
        assertThat(eventRow(editEvent).get("superseded_at")).isNotNull();
    }

    // ── l1 ────────────────────────────────────────────────────────────────────

    @Test
    void l1_variantsResolvedBeforeTheRowLock() throws Exception {
        Shop s = new Shop("l1");
        String gid = gid();
        long lineM = IDS.incrementAndGet();
        s.send("orders/create", order(gid, T0, line(lineM, s.restM, 1), fulfillments()));
        UUID o = s.orderId(gid);

        UUID e = s.event("orders/updated", order(gid, T0.plusSeconds(60), line(lineM, s.restM, 2), fulfillments()));
        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            try (PreparedStatement ps = holder.prepareStatement("SELECT id FROM orders WHERE id = ? FOR UPDATE")) {
                ps.setObject(1, o);
                ps.executeQuery().close();
            }
            Future<?> run = pool.submit(() -> processorJob.process(e, s.f.tenant));
            awaitWaitingOn("INSERT INTO orders");
            // While the upsert waits for the row: the variant is re-keyed. A lookup made after the lock would miss it.
            try (PreparedStatement ps = holder.prepareStatement(
                    "UPDATE variants SET external_id = external_id || '-rekeyed' WHERE id = ?")) {
                ps.setObject(1, s.variantM);
                ps.executeUpdate();
            }
            holder.commit();
            run.get(30, TimeUnit.SECONDS);
        }

        assertThat(eventRow(e).get("process_error")).isNull();
        assertThat(s.qty(o, lineM)).as("the line was written with the variant resolved before the lock").isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT on_hold FROM orders WHERE id = ?", Boolean.class, o))
            .as("not flagged unmapped").isFalse();
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /** Polls until a backend running {@code sqlStart} is waiting on a lock (another connection holds the order row). */
    private void awaitWaitingOn(String sqlStart) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline) {
            Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock' AND ltrim(query) LIKE ?",
                Integer.class, sqlStart + "%");
            if (n != null && n > 0) return;
            Thread.sleep(20);
        }
        throw new AssertionError("no backend waited on a lock running " + sqlStart);
    }

    private <T> T as(Shop s, java.util.function.Supplier<T> fn) {
        TenantContext.set(s.f.tenant);
        try { return fn.get(); } finally { TenantContext.clear(); }
    }

    private static String gid() { return "gid://shopify/Order/" + IDS.incrementAndGet(); }

    private ObjectNode order(String gid, Instant updatedAt, ObjectNode line, ArrayNode fulfillments) {
        ObjectNode p = mapper.createObjectNode();
        p.put("id", Long.parseLong(gid.substring(gid.lastIndexOf('/') + 1)));
        p.put("admin_graphql_api_id", gid);
        p.put("name", "#FR" + (IDS.get() % 100_000));
        p.put("created_at", Instant.now().minusSeconds(3600).toString());
        p.put("updated_at", updatedAt.toString());
        p.put("financial_status", "pending");
        p.put("current_total_price", "950.00");
        p.putArray("payment_gateway_names").add("Cash on Delivery (COD)");
        p.putArray("line_items").add(line);
        p.putArray("refunds");
        p.set("fulfillments", fulfillments);
        return p;
    }

    private ObjectNode line(long id, long variantRest, int current) {
        ObjectNode l = mapper.createObjectNode();
        l.put("id", id);
        l.put("admin_graphql_api_id", "gid://shopify/LineItem/" + id);
        l.put("variant_id", variantRest);
        l.put("title", "LINEN SHIRT");
        l.put("variant_title", "M");
        l.put("quantity", Math.max(current, 1));
        l.put("current_quantity", current);
        l.put("fulfillable_quantity", current);
        l.put("price", "950.00");
        l.putArray("discount_allocations");
        return l;
    }

    private ArrayNode fulfillments(ObjectNode... fs) {
        ArrayNode a = mapper.createArrayNode();
        for (ObjectNode f : fs) a.add(f);
        return a;
    }

    private ObjectNode fulfillment(String tracking) {
        ObjectNode f = mapper.createObjectNode();
        f.put("id", IDS.incrementAndGet());
        f.put("status", "success");
        f.put("tracking_company", "Wijha");
        f.put("updated_at", T0.toString());
        f.putArray("tracking_numbers").add(tracking);
        f.putArray("line_items");
        return f;
    }
}
