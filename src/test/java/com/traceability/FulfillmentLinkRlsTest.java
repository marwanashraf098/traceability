package com.traceability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.traceability.integrations.bosta.*;
import com.traceability.integrations.shopify.FulfillmentTrackingCapture;
import com.traceability.inventory.ExceptionService;
import com.traceability.security.EncryptionService;
import com.traceability.tenancy.TenantAwareDataSource;
import com.traceability.tenancy.TenantContext;
import org.jobrunr.jobs.lambdas.JobLambda;
import org.jobrunr.scheduling.JobScheduler;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Prod regression (2026-10-03 hotfix): fulfillment linking under REAL RLS.
 *
 * Every other fulfillment-link test runs the services on the postgres (BYPASSRLS) connection, so
 * they never noticed that BostaWebhookJob.process() — itself wrapped in TenantContext.runAs —
 * CLEARS the tenant on return: every query the link service made after it ran with no tenant
 * (GUC empty) and RLS returned zero rows. In prod the shipment committed, then
 * "SELECT … FROM webhook_events WHERE id = ?" threw EmptyResultDataAccessException and the job /
 * catch-up failed.
 *
 * Here the link service, the capture and the catch-up are wired over an app_user
 * TenantAwareDataSource (the prod connection), the established pattern for one RLS-sensitive path
 * (ShopifyConnectAmbientContextTest); BostaWebhookJob is the Spring bean.
 *
 *   rr1 prod's webhook path: capture (in its transaction) → after-commit enqueue → the job links and
 *       marks the tracking row 'linked'
 *   rr2 prod's catch-up path: an orders_raw-only fulfillment, apply → LINKED, row stored + 'linked'
 *   rr3 a retry on an already-linked order is a clean no-op: no second shipment, no new history,
 *       no new event, no exception
 *   rr4 catch-up isolates rows: a row that throws becomes ERROR, the later rows still run, the
 *       summary is logged
 *   rr5 (B4, 2026-10-04) a 429 never sleeps in the worker: one fetch, 'retry' at the retry-after (never
 *       counted), and the sweeper links it later — replaces the 10-03 in-run retry
 *   rr6 the link's webhook event rescheduled on a rate limit: 'retry', not counted, event pending
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(OutputCaptureExtension.class)
class FulfillmentLinkRlsTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
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
        r.add("bosta.fulfillment-link.catch-up.delay-ms",  () -> "0");
        r.add("bosta.fulfillment-link.max-backoff-ms",     () -> "0");
        r.add("bosta.fulfillment-link.rate-limit-retries", () -> "2");
    }

    @Autowired JdbcTemplate                jdbc;          // postgres — fixtures only
    @Autowired ObjectMapper                mapper;
    @Autowired EncryptionService           encryptionService;
    @Autowired BostaWebhookJob             webhookJob;
    @Autowired ExceptionService            exceptionService;
    @Autowired BostaFulfillmentLinkService beanLinkService;   // rr5: the configured bean
    @MockBean  BostaGateway                bostaGateway;
    @MockBean  JobScheduler                jobScheduler;

    private BostaFulfillmentLinkService linkService;   // over app_user (RLS), like prod
    private FulfillmentTrackingCapture   capture;
    private TransactionTemplate          appUserTx;
    private JdbcTemplate                 appUserJdbc;
    private DataSourceTransactionManager appUserTxm;

    private UUID tenant, store;

    @BeforeAll
    void wireAppUser() {
        TenantAwareDataSource appUserDs = new TenantAwareDataSource(
            new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "app_user", "testpw"));
        appUserJdbc = new JdbcTemplate(appUserDs);
        appUserTxm = new DataSourceTransactionManager(appUserDs);
        appUserTx = new TransactionTemplate(appUserTxm);
        DriverManagerDataSource ownerDs = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "postgres", "postgres");
        linkService = new BostaFulfillmentLinkService(appUserJdbc, ownerDs, appUserTxm, bostaGateway,
            encryptionService, mapper, webhookJob, jobScheduler, 24);
        capture = new FulfillmentTrackingCapture(appUserJdbc, linkService);
    }

    @BeforeEach
    void setUp() {
        reset(bostaGateway, jobScheduler);
        tenant = UUID.randomUUID();
        store = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, 'BROEK-rr')", tenant);
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status, orders_ingest_from) " +
            "VALUES (?, ?, 'shopify', ?, 'connected', ?)", store, tenant, "rr-" + store + ".myshopify.com",
            Timestamp.from(Instant.parse("2026-09-29T10:51:17Z")));
        jdbc.update("INSERT INTO courier_accounts (tenant_id, provider, api_key_encrypted, webhook_secret, status) " +
            "VALUES (?, 'bosta', ?, 'h', 'active')", tenant, encryptionService.encrypt("key-rr-" + tenant));
    }

    @Test
    void rr1_webhookPath_captureEnqueueJob_linksUnderRls() throws Exception {
        String tn = tn("1");
        String gid = "gid://shopify/Order/18912387" + tn.substring(0, 6);
        UUID order = order("BRK-44889-EG", gid, "{}");
        stub(tn, "BRK-44889-EG");

        TenantContext.runAs(tenant, () -> appUserTx.execute(s ->
            capture.capture(store, gid, payload(tn))));

        ArgumentCaptor<JobLambda> job = ArgumentCaptor.forClass(JobLambda.class);
        verify(jobScheduler).enqueue(eq(BostaFulfillmentLinkService.jobId(tenant, order, tn)), job.capture());
        job.getValue().run();   // what JobRunr runs

        assertThat(shipments(tn)).isEqualTo(1);
        assertThat(linkStatus(order, tn)).isEqualTo("linked");
    }

    @Test
    void rr2_catchUp_ordersRawSource_appliesUnderRls() {
        String tn = tn("2");
        UUID order = order("BRK-44866-EG", "gid://shopify/Order/18912" + tn, payload(tn).toString());
        stub(tn, "BRK-44866-EG");
        BostaFulfillmentCatchUpService catchUp =
            new BostaFulfillmentCatchUpService(appUserJdbc, appUserTxm, linkService, capture, mapper, 0);

        List<BostaFulfillmentCatchUpService.Row> rows = catchUp.run(tenant, true);

        assertThat(rows).singleElement().satisfies(r -> {
            assertThat(r.source()).isEqualTo("orders_raw");
            assertThat(r.verdict()).isEqualTo("LINKED");
        });
        assertThat(shipments(tn)).isEqualTo(1);
        assertThat(linkStatus(order, tn)).isEqualTo("linked");
    }

    @Test
    void rr3_retryOnAlreadyLinkedOrder_isACleanNoOp() {
        String tn = tn("3");
        UUID order = order("BRK-44876-EG", "gid://shopify/Order/18913" + tn, "{}");
        tracking(order, tn);
        stub(tn, "BRK-44876-EG");
        linkService.attempt(tenant, order, tn, false);
        int history = count("SELECT COUNT(*) FROM shipment_status_history h JOIN shipments s ON s.id = h.shipment_id " +
            "WHERE s.tracking_number = '" + tn + "'");
        int events = count("SELECT COUNT(*) FROM webhook_events WHERE payload->>'trackingNumber' = '" + tn + "'");

        BostaFulfillmentLinkService.Result retry = linkService.attempt(tenant, order, tn, false);

        assertThat(retry.reason()).isEqualTo("already linked");
        assertThat(shipments(tn)).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM shipment_status_history h JOIN shipments s ON s.id = h.shipment_id " +
            "WHERE s.tracking_number = '" + tn + "'")).isEqualTo(history);
        assertThat(count("SELECT COUNT(*) FROM webhook_events WHERE payload->>'trackingNumber' = '" + tn + "'"))
            .isEqualTo(events);
        assertThat(linkStatus(order, tn)).isEqualTo("linked");
        assertThat(TenantContext.runAs(tenant, () -> exceptionService.detectAllOpen()))
            .noneMatch(e -> "fulfillment_link_problem".equals(e.get("type")));
    }

    @Test
    void rr4_catchUp_failingRowInTheMiddle_isError_laterRowsStillRun(CapturedOutput out) {
        String a = tn("4"), b = tn("5"), c = tn("6");
        UUID oa = order("BRK-1" + a.substring(4) + "-EG", "gid://shopify/Order/1" + a, "{}");
        UUID ob = order("BRK-2" + a.substring(4) + "-EG", "gid://shopify/Order/2" + a, "{}");
        UUID oc = order("BRK-3" + a.substring(4) + "-EG", "gid://shopify/Order/3" + a, "{}");
        tracking(oa, a); tracking(ob, b); tracking(oc, c);
        stub(a, "BRK-1" + a.substring(4) + "-EG");
        stub(c, "BRK-3" + a.substring(4) + "-EG");
        BostaFulfillmentLinkService failing = spy(linkService);
        doThrow(new IllegalStateException("boom on the middle row"))
            .when(failing).attempt(any(UUID.class), any(UUID.class), eq(b), anyBoolean());
        BostaFulfillmentCatchUpService catchUp =
            new BostaFulfillmentCatchUpService(appUserJdbc, appUserTxm, failing, capture, mapper, 0);

        catchUp.runAndLog(tenant, true);

        assertThat(out.getOut()).contains("\"trackingNumber\":\"" + b + "\"").contains("\"verdict\":\"ERROR\"")
            .contains("boom on the middle row").contains("BOSTA_CATCHUP_SUMMARY");
        assertThat(shipments(a)).isEqualTo(1);
        assertThat(shipments(c)).as("the row after the failing one still ran").isEqualTo(1);
    }

    @Test
    void rr5_rateLimited_neverSleepsInTheWorker_retryStateThenLinks() {
        // 2026-10-04 (B4): a 429 no longer waits inside the job — it becomes 'retry' at the
        // retry-after (never counted toward the 24 h) and the sweeper links it later.
        String tn = tn("7");
        UUID order = order("BRK-44855-EG", "gid://shopify/Order/18914" + tn, "{}");
        tracking(order, tn);
        when(bostaGateway.fetchDelivery(eq("key-rr-" + tenant), eq(tn)))
            .thenThrow(new BostaRateLimitException(30))
            .thenReturn(delivery(tn, "BRK-44855-EG"));

        long t0 = System.nanoTime();
        BostaFulfillmentLinkService.Result r = beanLinkService.attempt(tenant, order, tn, false);
        long ms = (System.nanoTime() - t0) / 1_000_000;

        assertThat(r.reason()).isEqualTo("rate limited by Bosta");
        assertThat(ms).as("no sleep in the worker (retry-after was 30 s)").isLessThan(2_000);
        verify(bostaGateway, times(1)).fetchDelivery(anyString(), eq(tn));
        assertThat(linkStatus(order, tn)).isEqualTo("retry");
        assertThat(count("SELECT link_attempts FROM order_fulfillment_tracking WHERE tracking_number = '" + tn + "'"))
            .as("a rate limit is never counted").isZero();

        jdbc.update("UPDATE order_fulfillment_tracking SET link_next_retry_at = now() - INTERVAL '1 second' " +
            "WHERE tracking_number = ?", tn);
        beanLinkService.retryDue();

        assertThat(linkStatus(order, tn)).isEqualTo("linked");
        assertThat(shipments(tn)).isEqualTo(1);
    }

    @Test
    void rr6_linkEventRescheduledOnARateLimit_isARetryNotAFailure() {
        String tn = tn("8");
        UUID order = order("BRK-44856-EG", "gid://shopify/Order/18915" + tn, "{}");
        tracking(order, tn);
        // The link's own fetch succeeds; the webhook job's verify-by-fetch is rate limited.
        when(bostaGateway.fetchDelivery(anyString(), eq(tn)))
            .thenReturn(delivery(tn, "BRK-44856-EG"))
            .thenThrow(new BostaRateLimitException(120));

        BostaFulfillmentLinkService.Result r = beanLinkService.attempt(tenant, order, tn, false);

        assertThat(r.reason()).isEqualTo("rate limited by Bosta");
        assertThat(jdbc.queryForMap(
            "SELECT link_status, link_attempts, link_first_failed_at, " +
            "  link_next_retry_at > now() + INTERVAL '100 seconds' AS after_reschedule " +
            "FROM order_fulfillment_tracking WHERE tracking_number = ?", tn))
            .containsEntry("link_status", "retry").containsEntry("link_attempts", 0)
            .containsEntry("link_first_failed_at", null).containsEntry("after_reschedule", true);
        assertThat(jdbc.queryForObject("SELECT status::text FROM webhook_events WHERE payload->>'trackingNumber' = ?",
            String.class, tn)).as("the event is pending, rescheduled — not failed").isEqualTo("pending");
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private String tn(String salt) {
        return salt + String.format("%09d", Math.abs(tenant.hashCode() % 1_000_000_000));
    }

    private UUID order(String number, String gid, String raw) {
        return jdbc.queryForObject("INSERT INTO orders (tenant_id, store_id, external_id, number, status, placed_at, raw) " +
            "VALUES (?, ?, ?, ?, 'new'::order_status, now(), ?::jsonb) RETURNING id", UUID.class,
            tenant, store, gid, number, raw);
    }

    private void tracking(UUID order, String tn) {
        jdbc.update("INSERT INTO order_fulfillment_tracking (tenant_id, order_id, tracking_number, carrier_raw, " +
            "carrier_class, fulfillment_status) VALUES (?, ?, ?, 'Bosta', 'bosta', 'success')", tenant, order, tn);
    }

    private ObjectNode payload(String tn) {
        ObjectNode p = mapper.createObjectNode();
        ObjectNode f = p.putArray("fulfillments").addObject();
        f.put("id", 7260882305303L);
        f.put("status", "success");
        f.put("service", "manual");
        f.put("tracking_company", "Bosta");
        f.put("tracking_number", tn);
        f.putArray("tracking_numbers").add(tn);
        f.put("tracking_url", "https://bosta.co/tracking-shipments?shipment-number=" + tn);
        return p;
    }

    private BostaDelivery delivery(String tn, String ref) {
        ObjectNode raw = mapper.createObjectNode();
        raw.put("_id", "bosta-" + tn);
        raw.put("trackingNumber", tn);
        raw.put("businessReference", ref);
        raw.put("createdAt", "Thu Oct 01 2026 15:20:00 GMT+0000 (Coordinated Universal Time)");
        raw.put("updatedAt", "2026-10-03T13:12:00.000Z");
        raw.putObject("type").put("code", 10).put("value", "Send");
        raw.putObject("state").put("code", 47);
        return BostaDelivery.fromRaw(tn, raw);
    }

    private void stub(String tn, String ref) {
        when(bostaGateway.fetchDelivery(eq("key-rr-" + tenant), eq(tn))).thenReturn(delivery(tn, ref));
    }

    private int shipments(String tn) {
        return count("SELECT COUNT(*) FROM shipments WHERE tracking_number = '" + tn + "'");
    }

    private String linkStatus(UUID order, String tn) {
        return jdbc.queryForObject("SELECT link_status FROM order_fulfillment_tracking " +
            "WHERE order_id = ? AND tracking_number = ?", String.class, order, tn);
    }

    private int count(String sql) {
        return jdbc.queryForObject(sql, Integer.class);
    }
}
