package com.traceability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.traceability.integrations.bosta.BostaDelivery;
import com.traceability.integrations.bosta.BostaGateway;
import com.traceability.integrations.bosta.BostaIngestionHelper;
import com.traceability.integrations.bosta.BostaWebhookJob;
import com.traceability.integrations.shopify.ShopifyGateway;
import com.traceability.integrations.shopify.ShopifyReturnsGateway;
import com.traceability.integrations.shopify.ShopifyReturnsStore;
import com.traceability.integrations.shopify.ShopifyTokenProvider;
import com.traceability.inventory.*;
import com.traceability.security.EncryptionService;
import com.traceability.tenancy.TenantAwareDataSource;
import com.traceability.tenancy.TenantContext;
import org.jobrunr.scheduling.JobScheduler;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * B0 (2026-11-18) — a Shopify-exchange shadow must never stay live next to the Bosta exchange's own
 * internal:exchange: replacement (prod overdue 638012455 / 9609811791 / 2156539448: mapped in October with no original,
 * the Return mirrored on Nov 15 → live shadow, matched by the S1 catch-up on Nov 18 → the shadow stayed live).
 *
 * <pre>
 *   d1  prod order of events: exchange mapped to an internal:exchange: order with NO original → the Return mirrored →
 *       the shadow is created live → the reference match sets the original → AFTER COMMIT the original's shadows are
 *       reconciled → the shadow is stood down (line 0 'stood_down', shadow ended 'all_lines_ended'); the exchange
 *       order is untouched. Revert check: the hook.
 *   d2  the same through the manual attach (S2 drawer)
 *   d3  app_user: tenant B's match of A's exchange does nothing (A's shadow stays live); A's own match stands it down
 *   d4  no shadow (store without Return access): a match changes nothing else — no shadow, the same single exchange
 *       order and line
 *   c1  a live shadow for the Return: mapping another variant → 409 EXCHANGE_SHADOW_CONFLICT, nothing written (no
 *       internal:exchange: order, still needs_mapping); its own variant → the shadow path (no internal:exchange: order);
 *       map-items with two rows → 409 EXCHANGE_SHADOW_CONFLICT. Revert check: the guard.
 *   c2  tryAutoMap never guesses next to a live shadow it can't name an item for (two-variant shadow): stays
 *       needs_mapping, no exception from the webhook path
 * </pre>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ExchangeShadowStandDownTest {

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
        r.add("traced.shopify-returns.backfill.enabled", () -> "false");
        r.add("shopify.webhook-topics-backfill.enabled", () -> "false");
        r.add(ShopifyShadowOrders.S3C_FLAG, () -> "true");
    }

    @MockBean JobScheduler          jobScheduler;
    @MockBean ShopifyGateway        shopifyGateway;
    @MockBean ShopifyTokenProvider  tokenProvider;
    @MockBean ShopifyReturnsGateway returnsGateway;
    @MockBean BostaGateway          bostaGateway;

    @Autowired JdbcTemplate          jdbc;
    @Autowired ObjectMapper          mapper;
    @Autowired EncryptionService     encryption;
    @Autowired ShopifyReturnsStore   returnsStore;
    @Autowired ShopifyShadowOrders   shadowsBean;
    @Autowired BostaWebhookJob       webhookJob;
    @Autowired BostaIngestionHelper  ingestionHelper;
    @Autowired ExchangeMatchService  matchService;
    @Autowired ExchangeService       exchanges;
    @Autowired org.springframework.transaction.PlatformTransactionManager txm;

    JdbcTemplate appJdbc;
    DataSourceTransactionManager appTxm;
    ShopifyShadowOrders appShadows;

    static final AtomicLong IDS = new AtomicLong(86_000_000_000_000L);
    static final Instant APPROVED = Instant.parse("2026-10-15T13:56:40Z");
    static final String RAW_KEY = "b0-bosta-key";

    @BeforeAll
    void appUser() {
        DataSource ds = new TenantAwareDataSource(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "app_user", "testpw"));
        appJdbc = new JdbcTemplate(ds);
        appTxm = new DataSourceTransactionManager(ds);
        appShadows = new ShopifyShadowOrders(appJdbc, appTxm, mapper);
    }

    @BeforeEach
    void stubs() {
        reset(shopifyGateway, tokenProvider, returnsGateway, bostaGateway);
        when(tokenProvider.getValidToken(any())).thenReturn("tok");
    }

    @AfterEach void clear() { TenantContext.clear(); }

    // ── d1 ───────────────────────────────────────────────────────────────────

    @Test
    void d1_prodOrder_mappedThenShadowThenMatched_shadowStoodDown() {
        Shop s = new Shop("D1", true);
        String awb = s.mappedWithoutOriginal();
        UUID exc = s.internalExchangeOrder(awb);
        s.ret();
        assertThat(s.shadowState()).as("mirrored while the exchange had no original → live (prod Nov 15)").isEqualTo("live");

        ExchangeMatchService.ReferenceMatch m = s.as(() -> matchService.matchByReference(s.tenant, awb, false));
        assertThat(m.verdict()).isEqualTo(ExchangeMatchService.ReferenceVerdict.MATCHED);

        assertThat(s.shadowState()).as("stood down after the match committed").isEqualTo("ended");
        assertThat(s.shadowEndReason()).isEqualTo("all_lines_ended");
        assertThat(s.shadowLine()).containsEntry("live_quantity", 0).containsEntry("stop_reason", "stood_down");
        assertThat(jdbc.queryForObject("SELECT status::text FROM orders WHERE id = ?", String.class, exc))
            .as("the exchange's own order is untouched").isEqualTo("new");
        assertThat(jdbc.queryForObject("SELECT outbound_order_id FROM exchanges WHERE tenant_id = ? AND tracking_number = ?",
            UUID.class, s.tenant, awb)).isEqualTo(exc);
    }

    // ── d2 ───────────────────────────────────────────────────────────────────

    @Test
    void d2_manualAttach_standsTheShadowDownToo() {
        Shop s = new Shop("D2", true);
        String awb = s.mappedWithoutOriginal();
        jdbc.update("UPDATE exchanges SET status = 'unmatched' WHERE tenant_id = ? AND tracking_number = ?", s.tenant, awb);
        s.ret();
        assertThat(s.shadowState()).isEqualTo("live");

        UUID ex = jdbc.queryForObject("SELECT id FROM exchanges WHERE tenant_id = ? AND tracking_number = ?", UUID.class, s.tenant, awb);
        s.as(() -> matchService.searchAttach(ex, s.original));
        assertThat(s.shadowState()).isEqualTo("ended");
        assertThat(s.shadowLine()).containsEntry("stop_reason", "stood_down");
    }

    // ── d3 ───────────────────────────────────────────────────────────────────

    @Test
    void d3_appUser_isolation() {
        Shop a = new Shop("D3A", true);
        Shop b = new Shop("D3B", true);
        String awb = a.mappedWithoutOriginal();
        a.ret();
        ExchangeMatchService app = new ExchangeMatchService(appJdbc, mapper);
        app.setShadowReconcile(appShadows, appTxm);
        TransactionTemplate appTx = new TransactionTemplate(appTxm);

        ExchangeMatchService.ReferenceMatch cross = TenantContext.runAs(b.tenant, () -> appTx.execute(t ->
            app.matchByReference(a.tenant, awb, false)));
        assertThat(cross.verdict()).as("RLS hides A's exchange from B").isEqualTo(ExchangeMatchService.ReferenceVerdict.NOT_ELIGIBLE);
        assertThat(a.shadowState()).isEqualTo("live");

        ExchangeMatchService.ReferenceMatch own = TenantContext.runAs(a.tenant, () -> appTx.execute(t ->
            app.matchByReference(a.tenant, awb, false)));
        assertThat(own.verdict()).as("positive control").isEqualTo(ExchangeMatchService.ReferenceVerdict.MATCHED);
        assertThat(a.shadowState()).isEqualTo("ended");
    }

    // ── d4 ───────────────────────────────────────────────────────────────────

    @Test
    void d4_noShadow_singleItemUnchanged() {
        Shop s = new Shop("D4", false);               // store without Return access: no mirror, no shadow
        String awb = s.mappedWithoutOriginal();
        UUID exc = s.internalExchangeOrder(awb);
        assertThat(s.as(() -> matchService.matchByReference(s.tenant, awb, false)).verdict())
            .isEqualTo(ExchangeMatchService.ReferenceVerdict.MATCHED);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM shopify_exchange_shadows WHERE tenant_id = ?", Integer.class, s.tenant)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM orders WHERE tenant_id = ? AND external_id LIKE 'internal:%'",
            Integer.class, s.tenant)).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT variant_id FROM order_items WHERE order_id = ?", UUID.class, exc)).containsExactly(s.v);
        assertThat(jdbc.queryForObject("SELECT status FROM exchanges WHERE tenant_id = ? AND tracking_number = ?",
            String.class, s.tenant, awb)).isEqualTo("matched");
    }

    // ── c1 ───────────────────────────────────────────────────────────────────

    @Test
    void c1_liveShadow_otherVariantRefused_ownVariantUsesShadow_multiRefused() {
        Shop s = new Shop("C1", true);
        s.ret();
        UUID shadow = s.shadow();

        String other = s.needsMappingExchange();
        UUID ex = s.exchangeId(other);
        assertThatThrownBy(() -> s.as(() -> exchanges.map(ex, s.w, s.v)))
            .isInstanceOf(ExchangeShadowConflictException.class)
            .satisfies(e -> assertThat(((ExchangeShadowConflictException) e).errorCode()).isEqualTo("EXCHANGE_SHADOW_CONFLICT"));
        assertThat(s.internalExchangeOrders()).as("never an internal:exchange: order next to the live shadow").isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM exchanges WHERE id = ?", String.class, ex)).isEqualTo("needs_mapping");

        assertThatThrownBy(() -> s.as(() -> exchanges.mapItems(ex,
                List.of(new ExchangeService.ItemRow(s.v, 1, null), new ExchangeService.ItemRow(s.w, 1, null)), List.of(), null)))
            .isInstanceOf(ExchangeShadowConflictException.class);
        assertThat(s.internalExchangeOrders()).isZero();

        s.as(() -> exchanges.map(ex, s.v, s.w));
        assertThat(jdbc.queryForObject("SELECT outbound_order_id FROM exchanges WHERE id = ?", UUID.class, ex)).isEqualTo(shadow);
        assertThat(s.internalExchangeOrders()).isZero();
    }

    // ── c2 ───────────────────────────────────────────────────────────────────

    @Test
    void c2_tryAutoMap_twoVariantShadow_staysNeedsMapping() {
        Shop s = new Shop("C2", true);
        s.ret(2);                                       // the Return exchanges two variants → the shadow has two lines
        // A third variant the description names EXACTLY (product title + every size / colour token) — not on the shadow.
        UUID grey = s.variant(jdbc.queryForObject("SELECT product_id FROM variants WHERE id = ?", UUID.class, s.v),
            IDS.incrementAndGet(), "Grey / M");
        assertThat(s.as(() -> new TransactionTemplate(txm).execute(t ->
                matchService.resolveOutboundVariant("ROUND LONG SLEEVE COTTON TOP - GREY - Grey / M"))).committedVariantId())
            .as("the resolver alone would auto-commit the Grey / M").isEqualTo(grey);
        String awb = s.needsMappingExchange("ROUND LONG SLEEVE COTTON TOP - GREY - Grey / M");
        s.as(() -> { exchanges.tryAutoMap(awb); return null; });
        assertThat(jdbc.queryForObject("SELECT status FROM exchanges WHERE tenant_id = ? AND tracking_number = ?",
            String.class, s.tenant, awb)).isEqualTo("needs_mapping");
        assertThat(s.internalExchangeOrders()).isZero();
    }

    // ── fixture ──────────────────────────────────────────────────────────────

    static String gid(String kind) { return "gid://shopify/" + kind + "/" + IDS.incrementAndGet(); }
    static String tn() { return String.valueOf(5_000_000_000L + IDS.incrementAndGet() % 4_000_000_000L); }

    class Shop {
        final UUID tenant = UUID.randomUUID(), store = UUID.randomUUID(), location = UUID.randomUUID();
        final long restV = IDS.incrementAndGet(), restW = IDS.incrementAndGet();
        final UUID v, w, original;
        final String originalGid, number, ref;

        /** {@code returnsAccess}: the store mirrors Shopify Returns (shadows possible). */
        Shop(String tag, boolean returnsAccess) {
            number = "#OD" + tag;
            ref = "overdue-9573:#OD" + tag + "-R1";       // the Bosta plugin's "<shop>:#<order>-R1" (prod overdue)
            jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?)", tenant, "B0 " + tag);
            jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status, connection_type, returns_access) " +
                "VALUES (?, ?, 'shopify', ?, 'connected', 'oauth', ?)", store, tenant,
                "b0-" + tag.toLowerCase() + "-" + tenant.toString().substring(0, 6) + ".myshopify.com", returnsAccess ? "granted" : null);
            jdbc.update("INSERT INTO locations (id, tenant_id, name, type, is_default, is_fulfillment) " +
                "VALUES (?, ?, 'Main Warehouse', 'warehouse', true, true)", location, tenant);
            jdbc.update("INSERT INTO courier_accounts (tenant_id, provider, api_key_encrypted, webhook_secret, status) " +
                "VALUES (?, 'bosta', ?, ?, 'active')", tenant, encryption.encrypt(RAW_KEY), "wh-" + tenant);
            UUID product = jdbc.queryForObject("INSERT INTO products (tenant_id, store_id, external_id, title, status) " +
                "VALUES (?, ?, ?, 'ROUND LONG SLEEVE COTTON TOP', 'active') RETURNING id", UUID.class, tenant, store, gid("Product"));
            v = variant(product, restV, "Beige / XL");
            w = variant(product, restW, "Beige / L");
            originalGid = gid("Order");
            original = jdbc.queryForObject(
                "INSERT INTO orders (tenant_id, store_id, external_id, number, status, payment_method, placed_at, raw) " +
                "VALUES (?, ?, ?, ?, 'delivered', 'cod', now() - interval '40 days', '{}'::jsonb) RETURNING id",
                UUID.class, tenant, store, originalGid, number);
            UUID item = jdbc.queryForObject("INSERT INTO order_items (tenant_id, order_id, variant_id, quantity, external_id) " +
                "VALUES (?, ?, ?, 1, ?) RETURNING id", UUID.class, tenant, original, w, gid("LineItem"));
            UUID leg = jdbc.queryForObject("INSERT INTO shipments (tenant_id, order_id, provider, tracking_number, internal_state, shipment_leg) " +
                "VALUES (?, ?, 'bosta', ?, 'delivered', 'forward') RETURNING id", UUID.class, tenant, original, tn());
            jdbc.update("INSERT INTO shipment_status_history (tenant_id, shipment_id, internal_state) VALUES (?, ?, 'delivered')", tenant, leg);
        }

        UUID variant(UUID product, long rest, String title) {
            return jdbc.queryForObject("INSERT INTO variants (tenant_id, product_id, external_id, sku, title) VALUES (?, ?, ?, ?, ?) RETURNING id",
                UUID.class, tenant, product, "gid://shopify/ProductVariant/" + rest, "B0-" + rest, title);
        }

        <T> T as(java.util.concurrent.Callable<T> body) { return TenantContext.runAs(tenant, body); }

        /** The prod state of October: the type 30 ingested and auto-mapped (internal:exchange: order, variant V), no original. */
        String mappedWithoutOriginal() {
            // October: mapped (no shadow yet → the exchange's own internal:exchange: order), no original — before the S1
            // parser the "<shop>:#<order>-R1" reference resolved to nothing (prod: matched only on Nov 18).
            String awb = needsMappingExchange();
            as(() -> exchanges.map(exchangeId(awb), v, w));
            assertThat(internalExchangeOrder(awb)).as("old path: the exchange's own order").isNotNull();
            assertThat(jdbc.queryForObject("SELECT matched_order_id FROM exchanges WHERE tenant_id = ? AND tracking_number = ?",
                UUID.class, tenant, awb)).isNull();
            return awb;
        }

        String needsMappingExchange() { return needsMappingExchange("something the resolver can't name"); }

        String needsMappingExchange(String outboundDescription) {
            String awb = tn();
            BostaDelivery d = delivery(awb, ref, outboundDescription);
            jdbc.update("INSERT INTO exchanges (tenant_id, tracking_number, status, outbound_description, inbound_description, raw) " +
                "VALUES (?, ?, 'needs_mapping', ?, 'In', ?::jsonb)", tenant, awb, outboundDescription, d.raw().toString());
            return awb;
        }

        UUID exchangeId(String awb) {
            return jdbc.queryForObject("SELECT id FROM exchanges WHERE tenant_id = ? AND tracking_number = ?", UUID.class, tenant, awb);
        }

        UUID internalExchangeOrder(String awb) {
            List<UUID> ids = jdbc.queryForList("SELECT id FROM orders WHERE tenant_id = ? AND external_id = ?", UUID.class,
                tenant, "internal:exchange:" + awb);
            return ids.isEmpty() ? null : ids.get(0);
        }

        int internalExchangeOrders() {
            return jdbc.queryForObject("SELECT COUNT(*) FROM orders WHERE tenant_id = ? AND external_id LIKE 'internal:exchange:%'",
                Integer.class, tenant);
        }

        void ret() { ret(1); }

        /** OPEN Return "#OD<tag>-R1" exchanging {@code lines} variant(s) (V, then W), mirrored + reconciled. */
        void ret(int lines) {
            ObjectNode r = mapper.createObjectNode();
            r.put("id", gid("Return"));
            r.put("name", "#OD" + number.substring(3) + "-R1");
            r.put("status", "OPEN");
            r.put("createdAt", APPROVED.minusSeconds(3600).toString());
            r.put("requestApprovedAt", APPROVED.toString());
            r.putObject("order").put("id", originalGid);
            r.putObject("returnLineItems").putArray("nodes");
            var nodes = r.putObject("exchangeLineItems").putArray("nodes");
            for (int i = 0; i < lines; i++) {
                ObjectNode n = nodes.addObject();
                n.put("id", gid("ExchangeLineItem"));
                n.put("variantId", "gid://shopify/ProductVariant/" + (i == 0 ? restV : restW));
                n.put("quantity", 1);
                n.put("processedQuantity", 0);
                n.putArray("lineItems");
            }
            as(() -> returnsStore.upsertReturn(store, original, r));
            as(() -> shadowsBean.reconcileForOrder(original));
        }

        UUID shadow() {
            return jdbc.queryForObject("SELECT order_id FROM shopify_exchange_shadows WHERE tenant_id = ?", UUID.class, tenant);
        }

        String shadowState() {
            return jdbc.queryForObject("SELECT state FROM shopify_exchange_shadows WHERE tenant_id = ?", String.class, tenant);
        }

        String shadowEndReason() {
            return jdbc.queryForObject("SELECT end_reason FROM shopify_exchange_shadows WHERE tenant_id = ?", String.class, tenant);
        }

        Map<String, Object> shadowLine() {
            return jdbc.queryForMap("SELECT sl.live_quantity, sl.stop_reason FROM shopify_exchange_shadow_lines sl " +
                "JOIN shopify_exchange_shadows s ON s.id = sl.shadow_id WHERE s.tenant_id = ?", tenant);
        }
    }

    /** A type 30 delivery with the overdue plugin reference; one item each way. */
    BostaDelivery delivery(String tracking, String reference, String outboundDescription) {
        ObjectNode raw = mapper.createObjectNode();
        raw.put("_id", "d-" + tracking);
        raw.put("trackingNumber", tracking);
        raw.putObject("type").put("code", 30).put("value", "Exchange");
        raw.putObject("state").put("code", 10).put("value", "Created");
        raw.put("businessReference", reference);
        raw.putObject("receiver").put("firstName", "Sara").put("lastName", "Ali").put("phone", "01012345678");
        raw.putObject("specs").putObject("packageDetails").put("itemsCount", 1).put("description", outboundDescription);
        raw.putObject("returnSpecs").putObject("packageDetails").put("itemsCount", 1)
            .put("description", "ROUND LONG SLEEVE COTTON TOP - BEIGE - Beige / L");
        raw.put("createdAt", "2026-10-15T13:58:02.000Z");
        raw.put("updatedAt", Instant.parse("2026-10-15T14:00:00Z").plusSeconds(IDS.incrementAndGet() % 1000).toString());
        return BostaDelivery.fromRaw(tracking, raw);
    }

    void ingest(Shop s, BostaDelivery d) {
        when(bostaGateway.fetchDelivery(anyString(), eq(d.trackingNumber()))).thenReturn(d);
        boolean enqueued = TenantContext.runAs(s.tenant,
            () -> ingestionHelper.ingestDelivery(s.tenant, RAW_KEY, d.trackingNumber(), "bosta_poll"));
        assertThat(enqueued).isTrue();
        Long eventId = jdbc.queryForObject("SELECT id FROM webhook_events WHERE tenant_id = ? AND payload->>'trackingNumber' = ? " +
            "ORDER BY received_at DESC, id DESC LIMIT 1", Long.class, s.tenant, d.trackingNumber());
        webhookJob.process(eventId, s.tenant);
        TenantContext.clear();
    }
}
