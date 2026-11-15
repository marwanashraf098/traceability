package com.traceability;

import com.traceability.integrations.bosta.*;
import com.traceability.inventory.ExceptionService;
import com.traceability.security.EncryptionService;
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

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Bosta key health (V229, 2026-11-15; prod: one tenant's revoked key made ~1,500 failing Bosta calls a day through the
 * shared budget).
 *
 *   k1 three 401s on webhook fetches → the key is known bad: later webhooks are stored and marked 'skipped' with the
 *      reason and make NO Bosta call; the discovery and status polls make no Bosta call for the tenant; exactly ONE
 *      bosta_key_rejected alert (HIGH); a new key is saved → webhooks fetch again, both polls include the tenant, the
 *      alert is gone
 *   k2 the status poll probes a known-bad key once probe-hours passed (one walk page, nothing else); Bosta accepts it →
 *      the state clears and polling resumes; not due → no call at all
 *   k3 a 403 is never counted (an endpoint the key may not use is not a revoked key)
 *   k4 the status poll skips a tenant with nothing to keep current (no in-flight Bosta shipment); one with an in-flight
 *      shipment is walked
 *   k5 discovery: an idle tenant (nothing created in Bosta for 3+ days, nothing new last run) is checked once, then not
 *      again within discovery-idle-every-minutes; an active tenant is checked every run
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BostaKeyHealthTest {

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
        r.add("bosta.key-health.bad-after-minutes", () -> "0");   // no real-clock wait
    }

    @Autowired JdbcTemplate          jdbc;
    @Autowired EncryptionService     encryptionService;
    @Autowired BostaWebhookJob       webhookJob;
    @Autowired BostaDiscoveryPollJob discovery;
    @Autowired BostaStatusPollJob    statusPoll;
    @Autowired ExceptionService      exceptions;
    @MockBean  BostaV2Client         bostaV2;
    @MockBean  BostaGateway          bostaGateway;
    @MockBean  JobScheduler          jobScheduler;

    private final List<UUID> tenants = new ArrayList<>();

    @BeforeEach
    void stub() {
        reset(bostaV2, bostaGateway, jobScheduler);
        when(bostaV2.searchDeliveriesPage(anyString(), anyInt(), anyInt(), anyString())).thenReturn(List.of());
    }

    @AfterEach
    void off() {
        for (UUID t : tenants) jdbc.update("UPDATE courier_accounts SET status = 'disconnected' WHERE tenant_id = ?", t);
        tenants.clear();
        TenantContext.clear();
    }

    @Test
    void k1_knownBadKey_noBostaCalls_oneAlert_resumesOnNewKey() {
        UUID t = tenant("k1", "key-k1-old");
        shipment(t, "9100000001");
        when(bostaGateway.fetchDelivery(eq("key-k1-old"), anyString()))
            .thenThrow(new BostaAuthException("Bosta API error (401 UNAUTHORIZED)", 401, null));

        for (int i = 0; i < 3; i++) webhookJob.process(event(t, "91000001" + i), t);
        verify(bostaGateway, times(3)).fetchDelivery(eq("key-k1-old"), anyString());
        assertThat(knownBad(t)).isTrue();

        List<Long> later = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            long id = event(t, "91000002" + i);
            later.add(id);
            webhookJob.process(id, t);
        }
        verify(bostaGateway, times(3)).fetchDelivery(anyString(), anyString());   // no new call
        for (long id : later) {
            Map<String, Object> e = jdbc.queryForMap("SELECT status::text AS status, error FROM webhook_events WHERE id = ?", id);
            assertThat(e).containsEntry("status", "skipped").containsEntry("error", BostaKeyHealth.SKIP_REASON);
        }

        discovery.discoverAll();
        statusPoll.pollAll();
        verify(bostaV2, never()).searchDeliveriesPage(eq("key-k1-old"), anyInt(), anyInt(), anyString());
        verify(bostaGateway, times(3)).fetchDelivery(anyString(), anyString());

        List<Map<String, Object>> alerts = alerts(t);
        assertThat(alerts).hasSize(1);
        assertThat(alerts.get(0)).containsEntry("severity", "HIGH");
        webhookJob.process(event(t, "9100000300"), t);   // more events: still one alert
        assertThat(alerts(t)).hasSize(1);

        // The merchant saves a new key: everything resumes, the alert clears.
        jdbc.update("UPDATE courier_accounts SET api_key_encrypted = ? WHERE tenant_id = ?",
            encryptionService.encrypt("key-k1-new"), t);
        assertThat(knownBad(t)).isFalse();
        assertThat(alerts(t)).isEmpty();
        webhookJob.process(event(t, "9100000400"), t);
        verify(bostaGateway, times(1)).fetchDelivery(eq("key-k1-new"), eq("9100000400"));
        discovery.discoverAll();
        statusPoll.pollAll();
        verify(bostaV2, atLeast(2)).searchDeliveriesPage(eq("key-k1-new"), anyInt(), anyInt(), anyString());
    }

    @Test
    void k2_probe_onlyWhenDue_successClears() {
        UUID t = tenant("k2", "key-k2");
        shipment(t, "9200000001");
        markBad(t, "now() - interval '1 hour'");

        statusPoll.pollAll();
        verify(bostaV2, never()).searchDeliveriesPage(eq("key-k2"), anyInt(), anyInt(), anyString());

        markBad(t, "now() - interval '7 hours'");
        statusPoll.pollAll();
        verify(bostaV2, times(1)).searchDeliveriesPage(eq("key-k2"), anyInt(), anyInt(), anyString());
        verify(bostaGateway, never()).fetchDelivery(eq("key-k2"), anyString());
        assertThat(knownBad(t)).as("Bosta answered the probe").isFalse();

        statusPoll.pollAll();   // a normal cycle again
        verify(bostaV2, atLeast(2)).searchDeliveriesPage(eq("key-k2"), anyInt(), anyInt(), anyString());
    }

    @Test
    void k3_forbidden_isNotCounted() {
        UUID t = tenant("k3", "key-k3");
        when(bostaGateway.fetchDelivery(eq("key-k3"), anyString()))
            .thenThrow(new BostaAuthException("Bosta API error (403 FORBIDDEN)", 403, null));
        for (int i = 0; i < 4; i++) webhookJob.process(event(t, "93000001" + i), t);
        assertThat(knownBad(t)).isFalse();
        assertThat(jdbc.queryForObject("SELECT key_auth_fail_count FROM courier_accounts WHERE tenant_id = ?", Integer.class, t))
            .isZero();
        verify(bostaGateway, times(4)).fetchDelivery(eq("key-k3"), anyString());
    }

    @Test
    void k4_statusPoll_skipsTenantWithNothingInFlight() {
        UUID idle = tenant("k4idle", "key-k4-idle");
        UUID busy = tenant("k4busy", "key-k4-busy");
        shipment(busy, "9400000001");
        jdbc.update("INSERT INTO shipments (tenant_id, order_id, provider, tracking_number, internal_state) " +
            "VALUES (?, ?, 'bosta', '9400000002', 'delivered'::shipment_internal_state)", idle, order(idle, "9400000002"));
        statusPoll.pollAll();
        verify(bostaV2, never()).searchDeliveriesPage(eq("key-k4-idle"), anyInt(), anyInt(), anyString());
        verify(bostaV2, atLeastOnce()).searchDeliveriesPage(eq("key-k4-busy"), anyInt(), anyInt(), anyString());
    }

    @Test
    void k5_discovery_idleTenant_checkedLessOften() {
        UUID idle = tenant("k5idle", "key-k5-idle");
        UUID active = tenant("k5active", "key-k5-active");
        jdbc.update("UPDATE courier_accounts SET discovery_mark_at = now() - interval '5 days' WHERE tenant_id = ?", idle);
        jdbc.update("UPDATE courier_accounts SET discovery_mark_at = now() - interval '1 hour' WHERE tenant_id = ?", active);
        discovery.discoverAll();
        discovery.discoverAll();
        verify(bostaV2, times(1)).searchDeliveriesPage(eq("key-k5-idle"), anyInt(), anyInt(), anyString());
        verify(bostaV2, times(2)).searchDeliveriesPage(eq("key-k5-active"), anyInt(), anyInt(), anyString());
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private UUID tenant(String name, String key) {
        UUID id = UUID.randomUUID(), store = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?)", id, name);
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status) VALUES (?, ?, 'shopify', ?, 'connected')",
            store, id, name + "-" + id + ".myshopify.com");
        jdbc.update("INSERT INTO courier_accounts (tenant_id, provider, api_key_encrypted, webhook_secret, status) " +
            "VALUES (?, 'bosta', ?, 'h', 'active')", id, encryptionService.encrypt(key));
        tenants.add(id);
        return id;
    }

    private UUID order(UUID t, String tn) {
        UUID store = jdbc.queryForObject("SELECT id FROM stores WHERE tenant_id = ?", UUID.class, t);
        return jdbc.queryForObject("INSERT INTO orders (tenant_id, store_id, external_id, number, status, placed_at) " +
            "VALUES (?, ?, ?, ?, 'new'::order_status, now()) RETURNING id", UUID.class, t, store, "gid://shopify/Order/" + tn, "ORD-" + tn);
    }

    private void shipment(UUID t, String tn) {
        jdbc.update("INSERT INTO shipments (tenant_id, order_id, provider, tracking_number, internal_state, raw) " +
            "VALUES (?, ?, 'bosta', ?, 'with_courier'::shipment_internal_state, '{\"numberOfAttempts\":0}'::jsonb)",
            t, order(t, tn), tn);
    }

    private long event(UUID t, String tn) {
        return jdbc.queryForObject("INSERT INTO webhook_events (source, tenant_id, topic, payload, status, received_at) " +
            "VALUES ('bosta'::webhook_source, ?, 'delivery_update', ?::jsonb, 'pending', now()) RETURNING id", Long.class,
            t, "{\"trackingNumber\":\"" + tn + "\",\"state\":24,\"updatedAt\":\"2026-11-15T10:00:00.000Z\"}");
    }

    /** Known bad as BostaKeyHealth would leave it, with the last probe at {@code probeAt}. */
    private void markBad(UUID t, String probeAt) {
        jdbc.update("UPDATE courier_accounts SET key_auth_fail_count = 3, key_auth_fail_first_at = now() - interval '1 day', " +
            "  key_auth_bad_at = now() - interval '1 day', key_auth_probe_at = " + probeAt + ", " +
            "  key_auth_fail_key_hash = encode(sha256(convert_to(api_key_encrypted, 'UTF8')), 'hex') WHERE tenant_id = ?", t);
    }

    private boolean knownBad(UUID t) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT " + BostaKeyHealth.knownBadSql("ca") +
            " FROM courier_accounts ca WHERE ca.tenant_id = ?", Boolean.class, t));
    }

    private List<Map<String, Object>> alerts(UUID t) {
        return TenantContext.runAs(t, () -> exceptions.detectAllOpen()).stream()
            .filter(a -> "bosta_key_rejected".equals(a.get("type"))).toList();
    }
}
