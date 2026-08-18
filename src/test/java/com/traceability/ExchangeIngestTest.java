package com.traceability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.traceability.integrations.bosta.*;
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

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * FR-EXCHANGE Phase 1 — BostaWebhookJob's type.code=30 reroute (step 6.5, ahead of
 * stateMapper.map()). Fixtures use bare-numeric tracking numbers (production-shaped),
 * never prefixed.
 *
 * Matrix:
 *   e1 — single-item exchange (both legs itemsCount=1) → exchanges row created in
 *        needs_mapping, unlinked_bosta_deliveries and shipments both stay empty for it
 *   e2 — a second webhook for the same tracking (state change) refreshes raw but does
 *        NOT duplicate the row or overwrite the descriptions captured at first sighting
 *   e3 — itemsCount != 1 on a leg falls back to the pre-existing generic unmatched lane
 *        (recordUnlinked, match_reason='EXCHANGE_MULTI_ITEM') — no exchanges row
 *   e4 — cross-tenant RLS isolation on exchanges (app_user, GUC-scoped)
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ExchangeIngestTest {

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
    }

    @Autowired JdbcTemplate      jdbc;
    @Autowired ObjectMapper      mapper;
    @Autowired EncryptionService encryptionService;
    @Autowired BostaWebhookJob   bostaWebhookJob;
    @MockBean  BostaGateway      bostaGateway;
    @MockBean  JobScheduler      jobScheduler;

    // app_user datasource — RLS enforced (no BYPASSRLS). Same local harness pattern as
    // UnlinkedResolveTest / IdDescSweepTest (no shared base class in this codebase).
    private JdbcTemplate        appUserJdbc;
    private TransactionTemplate appUserTx;

    private UUID tenantId;
    private UUID tenantBId;

    @BeforeAll
    void setupAppUser() {
        DriverManagerDataSource rawDs =
                new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "app_user", "testpw");
        TenantAwareDataSource appUserDs = new TenantAwareDataSource(rawDs);
        appUserJdbc = new JdbcTemplate(appUserDs);
        appUserTx   = new TransactionTemplate(new DataSourceTransactionManager(appUserDs));
    }

    @BeforeAll
    void createFixtures() {
        tenantId  = UUID.randomUUID();
        tenantBId = UUID.randomUUID();

        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, 'ExchangeIngestTenantA')", tenantId);
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, 'ExchangeIngestTenantB')", tenantBId);
        jdbc.update(
            "INSERT INTO courier_accounts " +
            "  (id, tenant_id, provider, api_key_encrypted, webhook_secret, status) " +
            "VALUES (gen_random_uuid(), ?, 'bosta', ?, 'testhash', 'active')",
            tenantId, encryptionService.encrypt("ex-api-key"));
    }

    @BeforeEach
    void cleanup() {
        reset(bostaGateway);
        jdbc.update("DELETE FROM exchanges                WHERE tenant_id IN (?, ?)", tenantId, tenantBId);
        jdbc.update("DELETE FROM unlinked_bosta_deliveries WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM shipments                 WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM webhook_events             WHERE tenant_id = ?", tenantId);
    }

    // ── e1: single-item exchange → exchanges row, nothing in unlinked/shipments ──────

    @Test
    void e1_singleItemExchange_createsExchangeRow_notUnlinkedNotShipment() {
        String tracking = "877468285";
        Long wid = insertWebhookEvent(tracking, 10, "2026-08-18T10:00:00.000Z");
        when(bostaGateway.fetchDelivery(eq("ex-api-key"), eq(tracking)))
            .thenReturn(new BostaDelivery(tracking, 10, "EXCHANGE", 0, null, null,
                exchangeRaw(30, "Yellow stripes bucket hat size XS/S/M/L ", 1,
                    "red checkered bucket hat", "قبعة دلو مربعة حمراء", 1, "0", "600")));

        bostaWebhookJob.process(wid, tenantId);

        assertThat(webhookStatus(wid)).isEqualTo("processed");

        Map<String, Object> row = jdbc.queryForMap(
            "SELECT * FROM exchanges WHERE tenant_id = ? AND tracking_number = ?",
            tenantId, tracking);
        assertThat(row.get("status")).isEqualTo("needs_mapping");
        assertThat(row.get("outbound_description")).isEqualTo("Yellow stripes bucket hat size XS/S/M/L ");
        assertThat(row.get("inbound_description")).isEqualTo("red checkered bucket hat");
        assertThat(row.get("inbound_description_ar")).isEqualTo("قبعة دلو مربعة حمراء");
        assertThat(((BigDecimal) row.get("cod")).intValue()).isZero();
        assertThat(((BigDecimal) row.get("goods_value")).intValue()).isEqualTo(600);
        assertThat(row.get("outbound_order_id")).isNull();

        assertThat(countUnlinked(tracking)).as("must never reach the generic unmatched lane").isZero();
        assertThat(countShipments(tracking)).as("no shipments row — forward-only leg is a later phase").isZero();
    }

    // ── e2: second webhook for same tracking refreshes raw, doesn't duplicate/reset ──

    @Test
    void e2_secondWebhook_sameTracking_idempotent_descriptionsNotOverwritten() {
        String tracking = "6336637079";
        Long wid1 = insertWebhookEvent(tracking, 10, "2026-08-18T10:00:00.000Z");
        when(bostaGateway.fetchDelivery(eq("ex-api-key"), eq(tracking)))
            .thenReturn(new BostaDelivery(tracking, 10, "EXCHANGE", 0, null, null,
                exchangeRaw(10, "Original outbound desc", 1, "Original inbound desc", "وصف أصلي", 1, "0", "300")));
        bostaWebhookJob.process(wid1, tenantId);

        int countAfterFirst = jdbc.queryForObject(
            "SELECT COUNT(*) FROM exchanges WHERE tenant_id = ? AND tracking_number = ?",
            Integer.class, tenantId, tracking);
        assertThat(countAfterFirst).isEqualTo(1);

        // Second webhook: state changed, and (defensively) a different description in the
        // fetched payload — must NOT overwrite what was captured at first sighting.
        Long wid2 = insertWebhookEvent(tracking, 20, "2026-08-18T10:05:00.000Z");
        when(bostaGateway.fetchDelivery(eq("ex-api-key"), eq(tracking)))
            .thenReturn(new BostaDelivery(tracking, 20, "EXCHANGE", 0, null, null,
                exchangeRaw(20, "Changed outbound desc", 1, "Changed inbound desc", "وصف متغير", 1, "0", "300")));
        bostaWebhookJob.process(wid2, tenantId);

        Map<String, Object> row = jdbc.queryForMap(
            "SELECT * FROM exchanges WHERE tenant_id = ? AND tracking_number = ?",
            tenantId, tracking);
        assertThat(row.get("outbound_description"))
            .as("description captured at first sighting must not be clobbered by a later webhook")
            .isEqualTo("Original outbound desc");
        assertThat(row.get("raw").toString())
            .as("raw must refresh to the latest fetched payload")
            .contains("Changed outbound desc");

        int countAfterSecond = jdbc.queryForObject(
            "SELECT COUNT(*) FROM exchanges WHERE tenant_id = ? AND tracking_number = ?",
            Integer.class, tenantId, tracking);
        assertThat(countAfterSecond).as("no duplicate row on a second webhook").isEqualTo(1);
    }

    // ── e3: itemsCount != 1 on a leg falls back to the generic unmatched lane ────────

    @Test
    void e3_multiItemExchange_fallsBackToUnmatchedLane_notExchangesTable() {
        String tracking = "184907356";
        Long wid = insertWebhookEvent(tracking, 10, "2026-08-18T10:10:00.000Z");
        when(bostaGateway.fetchDelivery(eq("ex-api-key"), eq(tracking)))
            .thenReturn(new BostaDelivery(tracking, 10, "EXCHANGE", 0, null, null,
                exchangeRaw(10, "Two shirts", 2, "One shirt", "قميص واحد", 1, "0", "900")));

        bostaWebhookJob.process(wid, tenantId);

        assertThat(webhookStatus(wid)).isEqualTo("processed");

        int exchangeCount = jdbc.queryForObject(
            "SELECT COUNT(*) FROM exchanges WHERE tenant_id = ? AND tracking_number = ?",
            Integer.class, tenantId, tracking);
        assertThat(exchangeCount).as("multi-item exchange must not be represented in exchanges").isZero();

        Map<String, Object> unlinked = jdbc.queryForMap(
            "SELECT * FROM unlinked_bosta_deliveries WHERE tenant_id = ? AND tracking_number = ?",
            tenantId, tracking);
        assertThat(unlinked.get("resolved")).isEqualTo(false);
        assertThat(unlinked.get("bosta_order_type")).isEqualTo("EXCHANGE");
        assertThat(unlinked.get("match_reason")).isEqualTo("EXCHANGE_MULTI_ITEM");
    }

    // ── e4: cross-tenant RLS isolation on exchanges ──────────────────────────────────

    @Test
    void e4_crossTenantRlsIsolation_onExchanges() {
        String trackingA = "9293360461";
        String trackingB = "9293360462";
        jdbc.update(
            "INSERT INTO exchanges (tenant_id, tracking_number, status, raw) " +
            "VALUES (?, ?, 'needs_mapping', '{}'::jsonb)",
            tenantId, trackingA);
        jdbc.update(
            "INSERT INTO exchanges (tenant_id, tracking_number, status, raw) " +
            "VALUES (?, ?, 'needs_mapping', '{}'::jsonb)",
            tenantBId, trackingB);

        // Tenant A's app_user session must see exactly its own row, never tenant B's.
        Integer visibleToA = TenantContext.runAs(tenantId, () -> appUserTx.execute(s ->
            appUserJdbc.queryForObject("SELECT COUNT(*) FROM exchanges", Integer.class)));
        assertThat(visibleToA).isEqualTo(1);

        Integer crossTenantLeak = TenantContext.runAs(tenantId, () -> appUserTx.execute(s ->
            appUserJdbc.queryForObject(
                "SELECT COUNT(*) FROM exchanges WHERE tracking_number = ?",
                Integer.class, trackingB)));
        assertThat(crossTenantLeak)
            .as("tenant A must not see tenant B's exchange row even when querying its exact tracking number")
            .isZero();

        Integer visibleToB = TenantContext.runAs(tenantBId, () -> appUserTx.execute(s ->
            appUserJdbc.queryForObject("SELECT COUNT(*) FROM exchanges", Integer.class)));
        assertThat(visibleToB).isEqualTo(1);
    }

    // ── helpers ───────────────────────────────────────────────────────────────────

    private Long insertWebhookEvent(String tracking, int state, String updatedAt) {
        String payload = String.format(
            "{\"trackingNumber\":\"%s\",\"state\":%d,\"updatedAt\":\"%s\"}",
            tracking, state, updatedAt);
        return jdbc.queryForObject(
            "INSERT INTO webhook_events " +
            "  (source, tenant_id, topic, payload, status, received_at) " +
            "VALUES ('bosta', ?, 'delivery_update', ?::jsonb, 'pending', now()) RETURNING id",
            Long.class, tenantId, payload);
    }

    private String webhookStatus(Long id) {
        return jdbc.queryForObject("SELECT status FROM webhook_events WHERE id = ?", String.class, id);
    }

    private int countUnlinked(String tracking) {
        return jdbc.queryForObject(
            "SELECT COUNT(*) FROM unlinked_bosta_deliveries WHERE tenant_id = ? AND tracking_number = ?",
            Integer.class, tenantId, tracking);
    }

    private int countShipments(String tracking) {
        return jdbc.queryForObject(
            "SELECT COUNT(*) FROM shipments WHERE tenant_id = ? AND tracking_number = ?",
            Integer.class, tenantId, tracking);
    }

    /** Builds a fleet-shaped raw Bosta exchange payload (§2 of the FR-EXCHANGE spec). */
    private ObjectNode exchangeRaw(int stateCode, String outboundDesc, int outboundCount,
                                    String inboundDesc, String inboundDescAr, int inboundCount,
                                    String cod, String goodsAmount) {
        ObjectNode raw = mapper.createObjectNode();
        ObjectNode type = raw.putObject("type");
        type.put("code", 30);
        type.put("value", "Exchange");
        ObjectNode state = raw.putObject("state");
        state.put("code", stateCode);
        state.put("value", "state-" + stateCode);

        ObjectNode specs = raw.putObject("specs");
        ObjectNode outboundDetails = specs.putObject("packageDetails");
        outboundDetails.put("description", outboundDesc);
        outboundDetails.put("itemsCount", outboundCount);

        ObjectNode returnSpecs = raw.putObject("returnSpecs");
        ObjectNode inboundDetails = returnSpecs.putObject("packageDetails");
        inboundDetails.put("description", inboundDesc);
        inboundDetails.put("descriptionAr", inboundDescAr);
        inboundDetails.put("itemsCount", inboundCount);

        ObjectNode parcels = raw.putObject("parcels");
        parcels.putObject("forward").put("trackingNumber", "placeholder");
        ObjectNode crp = parcels.putObject("crp");
        crp.put("trackingNumber", "placeholder");
        crp.put("desc", "N/A");

        raw.put("cod", cod);
        raw.putObject("goodsInfo").put("amount", goodsAmount);
        raw.putObject("receiver").put("phone", "+201000301512").put("fullName", "Test Receiver");
        raw.put("businessReference", (String) null);
        raw.put("updatedAt", "2026-08-18T10:00:00.000Z");
        return raw;
    }
}
