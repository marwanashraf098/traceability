package com.traceability;

import com.traceability.integrations.shopify.*;
import com.traceability.notifications.EmailGateway;
import org.jobrunr.jobs.lambdas.JobLambda;
import org.jobrunr.scheduling.JobScheduler;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.CannotCreateTransactionException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.SQLTransientConnectionException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Shopify webhook events: automatic retry with ordering safety, the sweeper and the re-process
 * (2026-10-04, V136).
 *
 *   wr1 no DB connection (the 10-03 error): rethrown for JobRunr, the row is NOT marked failed
 *   wr2 any other failure: process_error + retry_count 1 + next_retry_at (1 min); once due the sweeper
 *       claims and re-enqueues it; the retry applies it → processed
 *   wr3 ordering — stored copy newer: an older orders/updated delivered AFTER a newer one was applied
 *       (no later event exists, only the stored updated_at tells) is superseded, never applied
 *   wr4 ordering — a later event applied (no updated_at to compare): the earlier one is superseded
 *   wr5 never-enqueued events are swept after 30 min (claimed once — leased), not beyond the window;
 *       legacy failures (no next_retry_at) are not swept
 *   wr6 bounded: after max attempts the error stays, next_retry_at is cleared, the sweeper stops
 *   wr7 an already-processed event is a no-op when run again
 *   wr8 cross-tenant: a claim only succeeds under the event's own tenant
 *   rp1 re-process dry run writes nothing: SUPERSEDED / WOULD_REPROCESS per row with tenant, topic, resource
 *   rp2 re-process apply: through the normal path (REPROCESSED / SUPERSEDED); a second apply finds nothing;
 *       another tenant's rows are untouched
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ShopifyWebhookRetryTest {

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
        r.add("shopify.webhook.retry.max-attempts", () -> "3");
    }

    @Autowired JdbcTemplate                   jdbc;
    @Autowired ShopifyWebhookProcessorJob     processor;
    @Autowired ShopifyWebhookRetrySweeper     sweeper;
    @Autowired ShopifyWebhookReprocessService reprocess;
    @SpyBean   ShopifySyncService             syncService;
    @MockBean  JobScheduler                   jobScheduler;
    @MockBean  ShopifyGateway                 shopifyGateway;
    @MockBean  EmailGateway                   emailGateway;

    private UUID tenant, other;
    private String shop, otherShop;

    @BeforeEach
    void setUp() {
        reset(jobScheduler, syncService);
        jdbc.update("UPDATE shopify_webhook_events SET processed_at = now() WHERE processed_at IS NULL");   // isolate tests
        tenant = UUID.randomUUID();
        other = UUID.randomUUID();
        shop = "wr-" + tenant.toString().substring(0, 8) + ".myshopify.com";
        otherShop = "wr-" + other.toString().substring(0, 8) + ".myshopify.com";
        store(tenant, "RetryTenant", shop);
        store(other, "OtherTenant", otherShop);
    }

    @Test
    void wr1_noConnection_rethrownForJobRunr_notMarkedFailed() {
        UUID e = event(tenant, shop, "orders/create", order("#9001", 1001, "2026-10-04T09:00:00+03:00"), 0);
        doThrow(new CannotCreateTransactionException("Could not open JDBC Connection for transaction",
                new SQLTransientConnectionException("app-pool - Connection is not available")))
            .when(syncService).ingestOrderWebhook(any(), any(), any());

        assertThatThrownBy(() -> processor.process(e, tenant)).isInstanceOf(CannotCreateTransactionException.class);

        assertThat(row(e)).containsEntry("process_error", null).containsEntry("processed_at", null)
            .containsEntry("retry_count", 0);
    }

    @Test
    void wr2_failure_scheduledRetry_sweptAndApplied() {
        UUID e = event(tenant, shop, "orders/create", order("#9002", 1002, "2026-10-04T09:00:00+03:00"), 0);
        doThrow(new IllegalStateException("boom")).doCallRealMethod()
            .when(syncService).ingestOrderWebhook(any(), any(), any());

        processor.process(e, tenant);

        Map<String, Object> r = row(e);
        assertThat(r.get("process_error")).isEqualTo("boom");
        assertThat(r.get("retry_count")).isEqualTo(1);
        assertThat(bool("SELECT next_retry_at BETWEEN now() + INTERVAL '50 seconds' AND now() + INTERVAL '70 seconds' " +
            "FROM shopify_webhook_events WHERE id = ?", e)).as("first backoff 1 min").isTrue();

        sweeper.sweep();
        verify(jobScheduler, never()).enqueue(any(JobLambda.class));   // not due yet

        jdbc.update("UPDATE shopify_webhook_events SET next_retry_at = now() - INTERVAL '1 second' WHERE id = ?", e);
        sweeper.sweep();
        verify(jobScheduler, times(1)).enqueue(any(JobLambda.class));

        processor.process(e, tenant);   // what JobRunr runs
        assertThat(row(e).get("processed_at")).isNotNull();
        assertThat(row(e).get("process_error")).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM orders WHERE tenant_id = ? AND number = '#9002'",
            Integer.class, tenant)).isEqualTo(1);
    }

    @Test
    void wr3_olderPayloadNeverOverwritesNewer() {
        UUID newer = event(tenant, shop, "orders/updated", order("#9003", 1003, "2026-10-04T10:00:00+03:00"), 0);
        processor.process(newer, tenant);
        UUID older = event(tenant, shop, "orders/updated",
            order("#9003", 1003, "2026-10-04T09:00:00+03:00").replace("\"financial_status\":\"pending\"",
                "\"financial_status\":\"voided\""), 60);   // the OLDER payload arrives LATER (Shopify out of order)

        processor.process(older, tenant);

        assertThat(row(older).get("superseded_at")).isNotNull();
        assertThat(row(older).get("processed_at")).isNotNull();
        assertThat(jdbc.queryForObject("SELECT raw->>'updated_at' FROM orders WHERE tenant_id = ? AND number = '#9003'",
            String.class, tenant)).isEqualTo("2026-10-04T10:00:00+03:00");
    }

    @Test
    void wr4_laterEventAlreadyApplied_earlierSuperseded() {
        UUID later = event(tenant, shop, "orders/updated", order("#9004", 1004, null), 0);
        processor.process(later, tenant);
        UUID earlier = event(tenant, shop, "orders/create", order("#9004", 1004, null), -30);

        processor.process(earlier, tenant);

        assertThat(row(earlier).get("superseded_at")).isNotNull();
    }

    @Test
    void wr5_neverEnqueued_sweptOnce_windowAndLegacyRespected() {
        UUID stale = event(tenant, shop, "orders/create", order("#9005", 1005, "2026-10-04T09:00:00+03:00"), -40 * 60);
        UUID fresh = event(tenant, shop, "orders/create", order("#9006", 1006, "2026-10-04T09:00:00+03:00"), -5 * 60);
        UUID ancient = event(tenant, shop, "orders/create", order("#9007", 1007, "2026-10-04T09:00:00+03:00"), -3 * 24 * 3600);
        UUID legacy = event(tenant, shop, "orders/create", order("#9008", 1008, "2026-10-04T09:00:00+03:00"), -40 * 60);
        jdbc.update("UPDATE shopify_webhook_events SET process_error = 'Could not open JDBC Connection for transaction' WHERE id = ?", legacy);

        sweeper.sweep();
        sweeper.sweep();

        verify(jobScheduler, times(1)).enqueue(any(JobLambda.class));
        assertThat(row(stale).get("retry_count")).isEqualTo(1);
        assertThat(row(fresh).get("retry_count")).isEqualTo(0);
        assertThat(row(ancient).get("retry_count")).isEqualTo(0);
        assertThat(row(legacy).get("retry_count")).as("legacy failures are the re-process's").isEqualTo(0);
    }

    @Test
    void wr6_boundedByMaxAttempts() {
        UUID e = event(tenant, shop, "orders/create", order("#9009", 1009, "2026-10-04T09:00:00+03:00"), 0);
        doThrow(new IllegalStateException("still broken")).when(syncService).ingestOrderWebhook(any(), any(), any());

        for (int i = 0; i < 3; i++) processor.process(e, tenant);

        Map<String, Object> r = row(e);
        assertThat(r.get("retry_count")).isEqualTo(3);
        assertThat(r.get("next_retry_at")).as("out of attempts").isNull();
        assertThat(r.get("process_error")).isEqualTo("still broken");
        sweeper.sweep();
        verify(jobScheduler, never()).enqueue(any(JobLambda.class));
    }

    @Test
    void wr7_alreadyProcessed_isANoOp() {
        UUID e = event(tenant, shop, "orders/create", order("#9010", 1010, "2026-10-04T09:00:00+03:00"), 0);
        processor.process(e, tenant);
        reset(syncService);

        processor.process(e, tenant);

        verify(syncService, never()).ingestOrderWebhook(any(), any(), any());
    }

    @Test
    void wr8_claimOnlyUnderTheEventsTenant() {
        UUID e = event(other, otherShop, "orders/create", order("#9011", 1011, "2026-10-04T09:00:00+03:00"), -40 * 60);
        // Package-private claim: reached through the sweep, which pairs each id with its own tenant.
        sweeper.sweep();
        assertThat(row(e).get("retry_count")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM shopify_webhook_events WHERE id = ? AND tenant_id = ?",
            Integer.class, e, tenant)).as("never attributed to another tenant").isZero();
    }

    @Test
    void rp1_dryRun_writesNothing_verdictsPerRow() {
        UUID newer = event(tenant, shop, "orders/updated", order("#9012", 1012, "2026-10-04T10:00:00+03:00"), 0);
        processor.process(newer, tenant);
        UUID stale = event(tenant, shop, "orders/updated", order("#9012", 1012, "2026-10-04T09:00:00+03:00"), -3600);
        jdbc.update("UPDATE shopify_webhook_events SET process_error = 'Could not open JDBC Connection for transaction' WHERE id = ?", stale);
        UUID missing = event(tenant, shop, "orders/create", order("#9013", 1013, "2026-10-04T09:00:00+03:00"), -3600);
        List<Map<String, Object>> before = snapshot();

        List<ShopifyWebhookReprocessService.Row> rows = reprocess.run(tenant, false);

        assertThat(snapshot()).isEqualTo(before);
        assertThat(rows).extracting(ShopifyWebhookReprocessService.Row::eventId, ShopifyWebhookReprocessService.Row::verdict)
            .containsExactly(org.assertj.core.groups.Tuple.tuple(stale, "SUPERSEDED"),
                             org.assertj.core.groups.Tuple.tuple(missing, "WOULD_REPROCESS"));
        assertThat(rows).allSatisfy(r -> {
            assertThat(r.tenant()).isEqualTo("RetryTenant");
            assertThat(r.resource()).contains("gid://shopify/Order/");
        });
    }

    @Test
    void rp2_apply_normalPath_idempotent_tenantScoped() {
        UUID newer = event(tenant, shop, "orders/updated", order("#9014", 1014, "2026-10-04T10:00:00+03:00"), 0);
        processor.process(newer, tenant);
        UUID stale = event(tenant, shop, "orders/updated", order("#9014", 1014, "2026-10-04T09:00:00+03:00"), -3600);
        jdbc.update("UPDATE shopify_webhook_events SET process_error = 'Could not open JDBC Connection for transaction' WHERE id = ?", stale);
        UUID missing = event(tenant, shop, "orders/create", order("#9015", 1015, "2026-10-04T09:00:00+03:00"), -3600);
        UUID othersEvent = event(other, otherShop, "orders/create", order("#9016", 1016, "2026-10-04T09:00:00+03:00"), -3600);

        List<ShopifyWebhookReprocessService.Row> rows = reprocess.run(tenant, true);

        assertThat(rows).extracting(ShopifyWebhookReprocessService.Row::verdict).containsExactly("SUPERSEDED", "REPROCESSED");
        assertThat(jdbc.queryForObject("SELECT raw->>'updated_at' FROM orders WHERE tenant_id = ? AND number = '#9014'",
            String.class, tenant)).isEqualTo("2026-10-04T10:00:00+03:00");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM orders WHERE tenant_id = ? AND number = '#9015'",
            Integer.class, tenant)).isEqualTo(1);
        assertThat(row(othersEvent).get("processed_at")).as("another tenant's event untouched").isNull();

        List<Map<String, Object>> before = snapshot();
        assertThat(reprocess.run(tenant, true)).as("nothing left").isEmpty();
        assertThat(snapshot()).isEqualTo(before);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private void store(UUID t, String name, String domain) {
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?)", t, name);
        jdbc.update("INSERT INTO stores (id, tenant_id, shop_domain, platform, status) VALUES (?, ?, ?, 'shopify', 'connected')",
            UUID.randomUUID(), t, domain);
    }

    private String order(String name, long id, String updatedAt) {
        return "{\"id\":" + id + ",\"admin_graphql_api_id\":\"gid://shopify/Order/" + id + tenant.toString().substring(0, 4).hashCode() +
            "\",\"name\":\"" + name + "\",\"created_at\":\"2026-10-04T08:00:00+03:00\"" +
            (updatedAt == null ? "" : ",\"updated_at\":\"" + updatedAt + "\"") +
            ",\"financial_status\":\"pending\",\"current_total_price\":\"100.00\",\"payment_gateway_names\":[\"Cash on Delivery (COD)\"]" +
            ",\"line_items\":[]}";
    }

    private UUID event(UUID t, String domain, String topic, String payload, int receivedOffsetSeconds) {
        return jdbc.queryForObject(
            "INSERT INTO shopify_webhook_events (tenant_id, topic, shop_domain, webhook_id, payload_raw, received_at) " +
            "VALUES (?, ?, ?, ?, ?::jsonb, now() + (? * INTERVAL '1 second')) RETURNING id",
            UUID.class, t, topic, domain, "wh-" + UUID.randomUUID(), payload, receivedOffsetSeconds);
    }

    private Map<String, Object> row(UUID id) {
        return jdbc.queryForMap("SELECT processed_at, process_error, retry_count, next_retry_at, superseded_at " +
            "FROM shopify_webhook_events WHERE id = ?", id);
    }

    private boolean bool(String sql, Object... args) {
        return Boolean.TRUE.equals(jdbc.queryForObject(sql, Boolean.class, args));
    }

    private List<Map<String, Object>> snapshot() {
        return jdbc.queryForList("SELECT id, processed_at, process_error, retry_count, next_retry_at, superseded_at " +
            "FROM shopify_webhook_events WHERE tenant_id IN (?, ?) ORDER BY id", tenant, other);
    }
}
