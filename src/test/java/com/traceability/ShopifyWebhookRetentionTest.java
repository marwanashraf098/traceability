package com.traceability;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.traceability.integrations.shopify.ShopifyGateway;
import com.traceability.integrations.shopify.ShopifyWebhookProcessorJob;
import com.traceability.integrations.shopify.ShopifyWebhookRetention;
import com.traceability.notifications.EmailGateway;
import com.traceability.privacy.CustomerDataRequestService;
import com.traceability.privacy.CustomerRedaction;
import com.traceability.tenancy.TenantAwareDataSource;
import com.traceability.tenancy.TenantContext;
import org.jobrunr.jobs.lambdas.JobLambda;
import org.jobrunr.scheduling.JobScheduler;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.DefaultResponseErrorHandler;
import org.springframework.web.client.RestTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * shopify_webhook_events retention (write-churn slice B, 2026-10-21) — {@link ShopifyWebhookRetention}.
 *
 *   rt1 processed rows older than N (superseded included) are deleted; newer ones are kept
 *   rt2 pending, retry-due and failed rows are never deleted, at any age
 *   rt3 supersede guard: a tenant's older runnable event holds its cutoff back — the newer processed event for the
 *       same object stays, and the ordering check still supersedes the stale payload; another tenant is unaffected
 *   rt4 an exhausted failure older than 30 days doesn't hold the cutoff back; a younger one does
 *   rt5 dedupe: a webhook_id redelivered within the window is still rejected (HTTP, the real controller)
 *   rt6 cap: 20,500 qualifying rows → exactly 20,000 deleted in one run, the other 500 the next
 *   rt7 days under 3 → refused (logged), nothing deleted
 *   rt8 RLS: run as app_user (no BYPASSRLS), every tenant's rows go through its own tenant context
 *   rt9 GDPR: a data request whose webhook row was deleted still exports (410/404 rules unchanged), and the
 *       customer / shop redaction still runs
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(OutputCaptureExtension.class)
class ShopifyWebhookRetentionTest {

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
        r.add("traced.webhook-retention.pause-ms", () -> "0");
    }

    @LocalServerPort int port;
    @Autowired JdbcTemplate                jdbc;
    @Autowired ObjectMapper                mapper;
    @Autowired PlatformTransactionManager  txm;
    @Autowired DataSource                  dataSource;
    @Autowired ShopifyWebhookRetention     retention;
    @Autowired ShopifyWebhookProcessorJob  processor;
    @Autowired CustomerDataRequestService  dataRequests;
    @MockBean  JobScheduler                jobScheduler;
    @MockBean  ShopifyGateway              shopifyGateway;
    @MockBean  EmailGateway                emailGateway;
    @Value("${shopify.client-secret}") String clientSecret;

    record T(UUID id, String shop) {}

    @BeforeEach
    void clean() {
        reset(jobScheduler);
        jdbc.update("DELETE FROM shopify_webhook_events");
    }

    @Test
    void rt1_processedOlderThanN_deleted_newerKept() {
        T a = tenant("rt1");
        UUID d10 = processed(a, "orders/updated", gid("1"), days(10));
        UUID d8 = processed(a, "orders/updated", gid("2"), days(8));
        UUID superseded = processed(a, "orders/updated", gid("3"), days(9));
        jdbc.update("UPDATE shopify_webhook_events SET superseded_at = processed_at WHERE id = ?", superseded);
        UUID d6 = processed(a, "orders/updated", gid("4"), days(6));
        UUID h1 = processed(a, "products/update", gid("5"), "1 hour");

        ShopifyWebhookRetention.Result r = retention.run();

        assertThat(r.refused()).isFalse();
        assertThat(r.perTenant()).containsEntry(a.id(), 3);
        assertThat(exists(d10, d8, superseded)).containsOnly(false);
        assertThat(exists(d6, h1)).containsOnly(true);
    }

    @Test
    void rt2_pendingRetryDueAndFailed_neverDeleted() {
        T a = tenant("rt2");
        UUID pending = event(a, "orders/updated", gid("1"), days(60));
        UUID retryDue = event(a, "orders/updated", gid("2"), days(60));
        jdbc.update("UPDATE shopify_webhook_events SET process_error = 'boom', retry_count = 2, " +
            "next_retry_at = now() - interval '1 minute' WHERE id = ?", retryDue);
        UUID exhausted = event(a, "orders/updated", gid("3"), days(60));
        jdbc.update("UPDATE shopify_webhook_events SET process_error = 'boom', retry_count = 5 WHERE id = ?", exhausted);
        UUID leased = event(a, "orders/updated", gid("4"), days(60));
        jdbc.update("UPDATE shopify_webhook_events SET next_retry_at = now() + interval '30 minutes' WHERE id = ?", leased);
        UUID processedOlder = processed(a, "orders/updated", gid("5"), days(70));   // so the run visits this tenant

        retention.run();
        retention.run();

        assertThat(exists(pending, retryDue, exhausted, leased)).containsOnly(true);
        assertThat(exists(processedOlder)).containsOnly(false);
    }

    @Test
    void rt3_supersedeGuard_olderRunnableEventHoldsTheCutoff_otherTenantUnaffected() {
        T a = tenant("rt3a"), b = tenant("rt3b");
        String gid = gid("9301");
        UUID older = event(a, "orders/updated", order(gid, "#9301", "voided"), days(20));          // still pending
        UUID newer = processed(a, "orders/updated", order(gid, "#9301", "paid"), days(10));       // applied later
        UUID beforeOlder = processed(a, "orders/updated", gid("9302"), days(25));
        UUID otherTenant = processed(b, "orders/updated", gid("9303"), days(10));

        retention.run();

        assertThat(exists(newer)).as("newer than the tenant's oldest runnable event — kept").containsOnly(true);
        assertThat(exists(beforeOlder)).as("older than every runnable event — can supersede nothing").containsOnly(false);
        assertThat(exists(otherTenant)).as("tenant B has nothing runnable: its cutoff is now - 7 days").containsOnly(false);

        processor.process(older, a.id());   // e.g. the manual reprocess, three weeks late

        assertThat(jdbc.queryForObject("SELECT superseded_at IS NOT NULL FROM shopify_webhook_events WHERE id = ?",
            Boolean.class, older)).as("the stale payload is still superseded").isTrue();
        assertThat(jdbc.queryForList("SELECT raw->>'financial_status' FROM orders WHERE tenant_id = ? AND external_id = ?",
            String.class, a.id(), gid)).as("never applied").isEmpty();
    }

    @Test
    void rt4_exhaustedFailure_olderThan30Days_doesNotHoldTheCutoff_youngerDoes() {
        T a = tenant("rt4a"), c = tenant("rt4c");
        exhausted(a, days(40));
        UUID aOld = processed(a, "orders/updated", gid("1"), days(20));
        exhausted(c, days(20));
        UUID cBefore = processed(c, "orders/updated", gid("2"), days(25));
        UUID cAfter = processed(c, "orders/updated", gid("3"), days(10));

        retention.run();

        assertThat(exists(aOld)).as("a 40-day-old dead row doesn't freeze the cutoff").containsOnly(false);
        assertThat(exists(cBefore)).containsOnly(false);
        assertThat(exists(cAfter)).as("a 20-day-old exhausted row still holds the cutoff").containsOnly(true);
    }

    @Test
    void rt5_dedupe_redeliveryWithinTheWindow_stillRejected() {
        T a = tenant("rt5");
        jdbc.update("UPDATE stores SET access_token_encrypted = 'x' WHERE tenant_id = ?", a.id());
        String wid = "wh-" + UUID.randomUUID();
        assertThat(post(a, wid).getStatusCode().value()).isEqualTo(200);
        jdbc.update("UPDATE shopify_webhook_events SET processed_at = now() - interval '6 days', " +
            "received_at = now() - interval '6 days' WHERE webhook_id = ?", wid);
        processed(a, "orders/updated", gid("other"), days(10));   // something the run does delete

        assertThat(retention.run().total()).isEqualTo(1);
        assertThat(post(a, wid).getStatusCode().value()).isEqualTo(200);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM shopify_webhook_events WHERE webhook_id = ?", Integer.class, wid))
            .isEqualTo(1);
        verify(jobScheduler, times(1)).enqueue(any(JobLambda.class));
    }

    @Test
    void rt6_cap_20000PerRun_theRestNextRun() {
        T a = tenant("rt6");
        jdbc.update("INSERT INTO shopify_webhook_events (tenant_id, topic, shop_domain, webhook_id, payload_raw, " +
            "received_at, processed_at) SELECT ?, 'inventory_levels/update', ?, 'wh-rt6-' || g, '{}'::jsonb, " +
            "now() - interval '10 days' - g * interval '1 second', now() - interval '10 days' " +
            "FROM generate_series(1, 20500) g", a.id(), a.shop());
        UUID young = processed(a, "orders/updated", gid("1"), days(1));

        ShopifyWebhookRetention.Result first = retention.run();
        assertThat(first.total()).isEqualTo(20_000);
        assertThat(count(a)).isEqualTo(501);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM shopify_webhook_events WHERE tenant_id = ? " +
            "AND webhook_id LIKE 'wh-rt6-%' AND (substring(webhook_id from 8))::int <= 500", Integer.class, a.id()))
            .as("oldest first: the 500 newest of the backlog (g 1..500) are what's left").isEqualTo(500);

        assertThat(retention.run().total()).isEqualTo(500);
        assertThat(count(a)).isEqualTo(1);
        assertThat(exists(young)).containsOnly(true);
    }

    @Test
    void rt7_daysUnder3_refused_nothingDeleted(CapturedOutput out) {
        T a = tenant("rt7");
        UUID old = processed(a, "orders/updated", gid("1"), days(30));

        ShopifyWebhookRetention.Result r = new ShopifyWebhookRetention(jdbc, txm, dataSource, 2, 1000, 20000, 0).run();

        assertThat(r.refused()).isTrue();
        assertThat(r.total()).isZero();
        assertThat(exists(old)).containsOnly(true);
        assertThat(out.getOut()).contains("Shopify webhook retention refused").contains("days=2");
    }

    @Test
    void rt8_rls_appUser_eachTenantUnderItsOwnContext() {
        T a = tenant("rt8a"), b = tenant("rt8b");
        UUID aOld = processed(a, "orders/updated", gid("1"), days(10));
        UUID bOld = processed(b, "orders/updated", gid("2"), days(10));
        UUID aNew = processed(a, "orders/updated", gid("3"), days(1));
        DataSource app = new TenantAwareDataSource(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "app_user", "testpw"));
        ShopifyWebhookRetention asApp = new ShopifyWebhookRetention(new JdbcTemplate(app),
            new DataSourceTransactionManager(app), dataSource, 7, 1000, 20000, 0);

        ShopifyWebhookRetention.Result r = asApp.run();

        assertThat(r.perTenant()).containsEntry(a.id(), 1).containsEntry(b.id(), 1);
        assertThat(exists(aOld, bOld)).containsOnly(false);
        assertThat(exists(aNew)).containsOnly(true);
        assertThat(new JdbcTemplate(app).queryForObject("SELECT COUNT(*) FROM shopify_webhook_events", Integer.class))
            .as("no tenant context → RLS shows nothing").isZero();
    }

    @Test
    @SuppressWarnings("unchecked")
    void rt9_gdpr_dataRequestOutlivesItsWebhookRow_exportAndRedactStillWork() throws Exception {
        T a = tenant("rt9");
        String gid = gid("9901");
        String customerId = "7700991100";
        String payload = "{\"shop_domain\":\"" + a.shop() + "\",\"orders_requested\":[9901]," +
            "\"customer\":{\"id\":" + customerId + ",\"phone\":\"+201000000991\"},\"data_request\":{\"id\":1}}";
        UUID requestEvent = processed(a, "customers/data_request", payload, days(10));
        UUID orderEvent = processed(a, "orders/updated", order(gid, "#9901", "paid"), days(10));
        JsonNode json = mapper.readTree(payload);
        CustomerDataRequestService.Recorded rec = TenantContext.runAs(a.id(),
            () -> dataRequests.record(a.id(), requestEvent, a.shop(), json));
        assertThat(rec.created()).isTrue();

        retention.run();
        assertThat(exists(requestEvent, orderEvent)).containsOnly(false);

        CustomerDataRequestService.Export export = TenantContext.runAs(a.id(),
            () -> dataRequests.export(a.id(), rec.id(), null));
        Map<String, Object> doc = mapper.readValue(export.json(), Map.class);
        assertThat((List<?>) doc.get("shopify_webhook_payloads")).as("the deleted rows are simply not held").isEmpty();
        assertThat(jdbc.queryForObject("SELECT status FROM customer_data_requests WHERE id = ?", String.class, rec.id()))
            .isEqualTo("downloaded");

        CustomerRedaction redaction = new CustomerRedaction(jdbc);
        CustomerRedaction.Result customer = TenantContext.runAs(a.id(),
            () -> redaction.redactCustomer(a.id(), List.of(gid), customerId, "+201000000991"));
        assertThat(customer.dataRequests()).isEqualTo(1);
        CustomerRedaction.Result shop = TenantContext.runAs(a.id(), () -> redaction.redactShop(a.id()));
        assertThat(shop.dataRequests()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT customer_phone IS NULL AND pii_redacted_at IS NOT NULL " +
            "FROM customer_data_requests WHERE id = ?", Boolean.class, rec.id())).isTrue();
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private T tenant(String name) {
        UUID id = UUID.randomUUID();
        String shop = name + "-" + id.toString().substring(0, 8) + ".myshopify.com";
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?)", id, name);
        jdbc.update("INSERT INTO stores (id, tenant_id, shop_domain, platform, status) VALUES (?, ?, ?, 'shopify', 'connected')",
            UUID.randomUUID(), id, shop);
        return new T(id, shop);
    }

    private static String days(int n) {
        return n + " days";
    }

    private String gid(String n) {
        return "gid://shopify/Order/" + n + Math.abs(UUID.randomUUID().hashCode() % 100000);
    }

    /** A payload: a bare object GID, or a full JSON payload when it starts with '{'. */
    private String body(String gidOrJson) {
        return gidOrJson.startsWith("{") ? gidOrJson : "{\"admin_graphql_api_id\":\"" + gidOrJson + "\"}";
    }

    private String order(String gid, String name, String financialStatus) {
        return "{\"id\":" + gid.substring(gid.lastIndexOf('/') + 1) + ",\"admin_graphql_api_id\":\"" + gid + "\"," +
            "\"name\":\"" + name + "\",\"created_at\":\"2026-10-01T08:00:00+03:00\"," +
            "\"financial_status\":\"" + financialStatus + "\",\"current_total_price\":\"100.00\"," +
            "\"payment_gateway_names\":[\"Cash on Delivery (COD)\"],\"line_items\":[]}";
    }

    private UUID event(T t, String topic, String gidOrJson, String age) {
        return jdbc.queryForObject(
            "INSERT INTO shopify_webhook_events (tenant_id, topic, shop_domain, webhook_id, payload_raw, received_at) " +
            "VALUES (?, ?, ?, ?, ?::jsonb, now() - ?::interval) RETURNING id",
            UUID.class, t.id(), topic, t.shop(), "wh-" + UUID.randomUUID(), body(gidOrJson), age);
    }

    private UUID processed(T t, String topic, String gidOrJson, String age) {
        UUID id = event(t, topic, gidOrJson, age);
        jdbc.update("UPDATE shopify_webhook_events SET processed_at = received_at + interval '5 seconds' WHERE id = ?", id);
        return id;
    }

    private void exhausted(T t, String age) {
        UUID id = event(t, "orders/updated", gid("x"), age);
        jdbc.update("UPDATE shopify_webhook_events SET process_error = 'gave up', retry_count = 5, next_retry_at = NULL " +
            "WHERE id = ?", id);
    }

    private List<Boolean> exists(UUID... ids) {
        List<Boolean> out = new ArrayList<>();
        for (UUID id : ids) {
            out.add(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM shopify_webhook_events WHERE id = ?)", Boolean.class, id));
        }
        return out;
    }

    private int count(T t) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM shopify_webhook_events WHERE tenant_id = ?", Integer.class, t.id());
    }

    private ResponseEntity<Void> post(T t, String wid) {
        RestTemplate rest = new RestTemplate();
        rest.setErrorHandler(new DefaultResponseErrorHandler() {
            @Override public boolean hasError(ClientHttpResponse r) { return false; }
        });
        byte[] body = ("{\"id\":1,\"admin_graphql_api_id\":\"" + gid("rt5") + "\"}").getBytes(StandardCharsets.UTF_8);
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.set("X-Shopify-Topic", "orders/updated");
        h.set("X-Shopify-Shop-Domain", t.shop());
        h.set("X-Shopify-Hmac-Sha256", hmac(body));
        h.set("X-Shopify-Webhook-Id", wid);
        return rest.exchange("http://localhost:" + port + "/webhooks/shopify/orders/updated", HttpMethod.POST,
            new HttpEntity<>(body, h), Void.class);
    }

    private String hmac(byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(clientSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getEncoder().encodeToString(mac.doFinal(body));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
