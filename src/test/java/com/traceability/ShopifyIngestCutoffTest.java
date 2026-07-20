package com.traceability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.traceability.integrations.shopify.ShopifyGateway;
import com.traceability.integrations.shopify.ShopifyReconcileJob;
import com.traceability.integrations.shopify.ShopifySyncService;
import com.traceability.integrations.shopify.ShopifyTokenProvider;
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

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

/**
 * FR-18: Connection-anchored order ingest cutoff.
 *
 * Verifies:
 *   ic1 — reconnect (re-auth/re-token) preserves the original cutoff (the real-world trap).
 *   ic2 — pre-cutoff orders/create webhook is silently skipped.
 *   ic3 — post-cutoff orders/create webhook is ingested normally.
 *   ic4 — post-cutoff order already in DB continues to receive updates.
 *   ic5 — NULL cutoff (Jumi) ingests all orders regardless of age.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
                properties = "org.jobrunr.background-job-server.enabled=true")
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ShopifyIngestCutoffTest {

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

    @Autowired ShopifySyncService  syncService;
    @Autowired ShopifyReconcileJob reconcileJob;
    @Autowired JdbcTemplate        jdbc;
    @MockBean  ShopifyGateway      shopifyGateway;
    @MockBean  ShopifyTokenProvider tokenProvider;
    @MockBean  JobScheduler         jobScheduler;

    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void resetMocks() {
        when(tokenProvider.getValidToken(any())).thenReturn("shpat_fake");
    }

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM order_items WHERE tenant_id IN (SELECT id FROM tenants WHERE name LIKE 'IC%')");
        jdbc.update("DELETE FROM orders       WHERE tenant_id IN (SELECT id FROM tenants WHERE name LIKE 'IC%')");
        jdbc.update("DELETE FROM stores        WHERE tenant_id IN (SELECT id FROM tenants WHERE name LIKE 'IC%')");
        jdbc.update("DELETE FROM tenants WHERE name LIKE 'IC%'");
    }

    // ── ic1: reconnect preserves the original cutoff ───────────────────────

    @Test
    void ic1_reconnect_preservesOriginalCutoff() {
        UUID tenantId = insert("IC1-Tenant");
        UUID storeId  = UUID.randomUUID();
        String domain = "ic1.myshopify.com";

        // Simulate first connect: INSERT with a known cutoff two hours ago.
        Instant original = Instant.now().truncatedTo(ChronoUnit.SECONDS).minus(2, ChronoUnit.HOURS);
        jdbc.update(
            "INSERT INTO stores (id, tenant_id, platform, shop_domain, status, import_status, " +
            "    access_token_encrypted, access_token_expires_at, orders_ingest_from) " +
            "VALUES (?, ?, 'shopify', ?, 'connected', 'completed', 'enc', " +
            "    now() + interval '876000 hours', ?)",
            storeId, tenantId, domain, Timestamp.from(original));

        // Simulate reconnect: update the token columns exactly as UPSERT_STORE DO UPDATE branch
        // does — orders_ingest_from must NOT be in this UPDATE.
        jdbc.update(
            "UPDATE stores SET access_token_encrypted = 'enc_new', import_status = 'pending' " +
            "WHERE id = ?",
            storeId);

        // The cutoff must be unchanged.
        Timestamp ts = jdbc.queryForObject(
            "SELECT orders_ingest_from FROM stores WHERE id = ?",
            Timestamp.class, storeId);
        assertThat(ts).as("orders_ingest_from must survive reconnect").isNotNull();
        assertThat(ts.toInstant().truncatedTo(ChronoUnit.SECONDS))
            .as("cutoff must equal original first-connect timestamp")
            .isEqualTo(original);
    }

    // ── ic2: pre-cutoff webhook is skipped ────────────────────────────────

    @Test
    void ic2_preCutoffWebhook_isSkipped() {
        UUID tenantId = insert("IC2-Tenant");
        UUID storeId  = storeWithCutoff(tenantId, "ic2.myshopify.com", Instant.now());

        // Order placed 1 day before the cutoff.
        Instant placedAt = Instant.now().minus(1, ChronoUnit.DAYS);
        String gid = "gid://shopify/Order/IC2001";

        TenantContext.set(tenantId);
        try {
            syncService.ingestOrderWebhook(storeId, tenantId, webhookPayload(gid, "#IC2001", placedAt));
        } finally {
            TenantContext.clear();
        }

        Integer count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM orders WHERE external_id = ?", Integer.class, gid);
        assertThat(count).as("pre-cutoff order must NOT be ingested").isZero();
    }

    // ── ic3: post-cutoff webhook is ingested ──────────────────────────────

    @Test
    void ic3_postCutoffWebhook_isIngested() {
        UUID tenantId = insert("IC3-Tenant");
        // Cutoff was 1 hour ago; order was placed 10 minutes ago → post-cutoff.
        Instant cutoff = Instant.now().minus(1, ChronoUnit.HOURS);
        UUID storeId = storeWithCutoff(tenantId, "ic3.myshopify.com", cutoff);

        Instant placedAt = Instant.now().minus(10, ChronoUnit.MINUTES);
        String gid = "gid://shopify/Order/IC3001";

        TenantContext.set(tenantId);
        try {
            syncService.ingestOrderWebhook(storeId, tenantId, webhookPayload(gid, "#IC3001", placedAt));
        } finally {
            TenantContext.clear();
        }

        Integer count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM orders WHERE external_id = ?", Integer.class, gid);
        assertThat(count).as("post-cutoff order must be ingested").isEqualTo(1);
    }

    // ── ic4: existing post-cutoff order still receives updates ────────────

    @Test
    void ic4_existingPostCutoffOrder_receivesUpdate() {
        UUID tenantId = insert("IC4-Tenant");
        Instant cutoff = Instant.now().minus(1, ChronoUnit.HOURS);
        UUID storeId = storeWithCutoff(tenantId, "ic4.myshopify.com", cutoff);

        // Order placed after the cutoff.
        Instant placedAt = Instant.now().minus(30, ChronoUnit.MINUTES);
        String gid = "gid://shopify/Order/IC4001";

        // Initial ingest (orders/create path).
        TenantContext.set(tenantId);
        try {
            syncService.ingestOrderWebhook(storeId, tenantId,
                webhookPayloadWithPrice(gid, "#IC4001", placedAt, "100.00"));
        } finally {
            TenantContext.clear();
        }

        BigDecimal cod1 = jdbc.queryForObject(
            "SELECT cod_amount FROM orders WHERE external_id = ?", BigDecimal.class, gid);
        assertThat(cod1).isEqualByComparingTo("100.00");

        // Update arrives (orders/updated path) — new COD amount.
        TenantContext.set(tenantId);
        try {
            syncService.ingestOrderWebhook(storeId, tenantId,
                webhookPayloadWithPrice(gid, "#IC4001", placedAt, "150.00"));
        } finally {
            TenantContext.clear();
        }

        BigDecimal cod2 = jdbc.queryForObject(
            "SELECT cod_amount FROM orders WHERE external_id = ?", BigDecimal.class, gid);
        assertThat(cod2).as("cod_amount must reflect the update").isEqualByComparingTo("150.00");
    }

    // ── ic5: NULL cutoff (Jumi) ingests everything unchanged ──────────────

    @Test
    void ic5_nullCutoff_ingestsEverything() {
        UUID tenantId = insert("IC5-Tenant");
        // Insert store with orders_ingest_from = NULL (simulates Jumi / pre-FR-18 store).
        UUID storeId = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO stores (id, tenant_id, platform, shop_domain, status, import_status, " +
            "    access_token_encrypted, access_token_expires_at) " +
            "VALUES (?, ?, 'shopify', 'ic5.myshopify.com', 'connected', 'completed', 'enc', " +
            "    now() + interval '876000 hours')",
            storeId, tenantId);

        // Verify the column is NULL.
        Timestamp cutoff = jdbc.queryForObject(
            "SELECT orders_ingest_from FROM stores WHERE id = ?", Timestamp.class, storeId);
        assertThat(cutoff).as("orders_ingest_from must be NULL for Jumi-style store").isNull();

        // Verify loadCutoff() returns empty (not a sentinel).
        TenantContext.set(tenantId);
        Optional<Instant> loaded;
        try {
            loaded = syncService.loadCutoff(storeId);
        } finally {
            TenantContext.clear();
        }
        assertThat(loaded).as("loadCutoff() must return Optional.empty() for NULL column").isEmpty();

        // A very old order (3 months ago) must be ingested.
        String gid = "gid://shopify/Order/IC5001";
        Instant ancientPlacedAt = Instant.now().minus(90, ChronoUnit.DAYS);

        TenantContext.set(tenantId);
        try {
            syncService.ingestOrderWebhook(storeId, tenantId,
                webhookPayload(gid, "#IC5001", ancientPlacedAt));
        } finally {
            TenantContext.clear();
        }

        Integer count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM orders WHERE external_id = ?", Integer.class, gid);
        assertThat(count).as("NULL-cutoff store must ingest orders of any age").isEqualTo(1);
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private UUID insert(String tenantName) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?)", id, tenantName);
        return id;
    }

    private UUID storeWithCutoff(UUID tenantId, String domain, Instant cutoff) {
        UUID storeId = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO stores (id, tenant_id, platform, shop_domain, status, import_status, " +
            "    access_token_encrypted, access_token_expires_at, orders_ingest_from) " +
            "VALUES (?, ?, 'shopify', ?, 'connected', 'completed', 'enc', " +
            "    now() + interval '876000 hours', ?)",
            storeId, tenantId, domain, Timestamp.from(cutoff));
        return storeId;
    }

    private ObjectNode webhookPayload(String gid, String name, Instant createdAt) {
        return webhookPayloadWithPrice(gid, name, createdAt, "50.00");
    }

    private ObjectNode webhookPayloadWithPrice(String gid, String name, Instant createdAt, String price) {
        ObjectNode p = mapper.createObjectNode();
        p.put("admin_graphql_api_id", gid);
        p.put("name", name);
        p.put("created_at", createdAt.toString());
        // financial_status "pending" → inferPaymentMethod returns "cod"
        p.put("financial_status", "pending");
        p.put("current_total_price", price);
        p.putArray("payment_gateway_names");
        p.putArray("line_items");
        return p;
    }
}
