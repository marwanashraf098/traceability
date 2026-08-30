package com.traceability;

import com.traceability.notifications.EmailGateway;
import com.traceability.notifications.ExceptionDigestJob;
import com.traceability.notifications.ExceptionImmediateAlertJob;
import org.jobrunr.scheduling.JobScheduler;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Hybrid exception notifications, 08:00 Africa/Cairo daily digest. Calls
 * ExceptionDigestJob.run() directly with seeded data — does not rely on the recurring
 * trigger (same idiom as ExceptionImmediateAlertJobTest / ShopifyReconcileTest).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
                properties = "org.jobrunr.background-job-server.enabled=true")
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ExceptionDigestJobTest {

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

    @Autowired ExceptionDigestJob          digestJob;
    @Autowired ExceptionImmediateAlertJob  immediateJob;
    @Autowired JdbcTemplate                jdbc;

    @MockBean EmailGateway emailGateway;
    @MockBean JobScheduler jobScheduler;

    @BeforeEach
    void resetMocks() {
        reset(emailGateway, jobScheduler);
    }

    // ── Fixture helpers (same shapes as ExceptionImmediateAlertJobTest) ────────

    private UUID seedTenant(String name) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?)", id, name);
        return id;
    }

    private UUID seedStore(UUID tenantId) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO stores (id, tenant_id, shop_domain) VALUES (?, ?, ?)",
                id, tenantId, "store-" + id + ".myshopify.com");
        return id;
    }

    private void seedUser(UUID tenantId, String email, String role, boolean active) {
        jdbc.update(
                "INSERT INTO users (id, tenant_id, name, email, role, active) " +
                "VALUES (gen_random_uuid(), ?, ?, ?, ?::user_role, ?)",
                tenantId, role + "-name", email, role, active);
    }

    /** CRITICAL — detectLost: a piece with status='lost'. */
    private void seedCritical(UUID tenantId, UUID storeId) {
        UUID productId = UUID.randomUUID();
        jdbc.update("INSERT INTO products (id, tenant_id, store_id, external_id, title) VALUES (?,?,?,?,?)",
                productId, tenantId, storeId, "prod-" + productId, "Test Product");
        UUID variantId = UUID.randomUUID();
        jdbc.update("INSERT INTO variants (id, tenant_id, product_id, external_id, title) VALUES (?,?,?,?,?)",
                variantId, tenantId, productId, "var-" + variantId, "Test Variant");
        String pieceId = "PC-" + UUID.randomUUID();
        jdbc.update(
                "INSERT INTO pieces (id, tenant_id, variant_id, barcode, short_code, status) " +
                "VALUES (?, ?, ?, ?, ?, 'lost'::piece_status)",
                pieceId, tenantId, variantId, pieceId, pieceId);
    }

    /** LOW — detectBlocked: an order on hold. */
    private void seedLow(UUID tenantId, UUID storeId) {
        UUID orderId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO orders (id, tenant_id, store_id, external_id, status, on_hold, hold_reason) " +
                "VALUES (?, ?, ?, ?, 'new'::order_status, true, 'test hold')",
                orderId, tenantId, storeId, "ext-low-" + orderId);
    }

    private long digestLedgerCount(UUID tenantId) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM exception_notifications WHERE tenant_id = ? AND channel = 'digest'",
                Long.class, tenantId);
    }

    // -----------------------------------------------------------------------
    // (h) open set incl. never-digested → sent; "new" itemizes them; roll-up
    //     matches full open set; digest ledger rows written for the new ones.
    // -----------------------------------------------------------------------
    @Test
    void openSetWithNeverDigested_sentWithNewSectionAndRollup() {
        UUID tenantId = seedTenant("DigH");
        UUID storeId = seedStore(tenantId);
        String ownerEmail = "owner-" + UUID.randomUUID() + "@test.com";
        seedUser(tenantId, ownerEmail, "owner", true);
        seedCritical(tenantId, storeId);
        seedLow(tenantId, storeId);

        digestJob.run();

        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
        verify(emailGateway, times(1)).send(eq(ownerEmail), anyString(), bodyCaptor.capture());
        String body = bodyCaptor.getValue();
        assertThat(body).contains("marked as lost");     // CRITICAL item, itemized as "new"
        assertThat(body).contains("is on hold");          // LOW item, itemized as "new"
        assertThat(body).contains("New since last summary");
        assertThat(body).contains("2</strong> total (1 critical, 1 other)");

        assertThat(digestLedgerCount(tenantId)).isEqualTo(2L);
    }

    // -----------------------------------------------------------------------
    // (i) re-run, same open now digested → excluded from "new", still in roll-up.
    // -----------------------------------------------------------------------
    @Test
    void rerun_alreadyDigestedExcludedFromNew_stillInRollup() {
        UUID tenantId = seedTenant("DigI");
        UUID storeId = seedStore(tenantId);
        String ownerEmail = "owner-" + UUID.randomUUID() + "@test.com";
        seedUser(tenantId, ownerEmail, "owner", true);
        seedCritical(tenantId, storeId);

        digestJob.run();
        reset(emailGateway);

        digestJob.run();

        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
        verify(emailGateway, times(1)).send(eq(ownerEmail), anyString(), bodyCaptor.capture());
        String body = bodyCaptor.getValue();
        assertThat(body).doesNotContain("marked as lost");
        assertThat(body).contains("No new exceptions since the last summary.");
        assertThat(body).contains("1</strong> total (1 critical, 0 other)");

        // No new items to ledger on the second run — count stays at 1 from the first run.
        assertThat(digestLedgerCount(tenantId)).isEqualTo(1L);
    }

    // -----------------------------------------------------------------------
    // (j) zero open → no digest email.
    // -----------------------------------------------------------------------
    @Test
    void zeroOpen_noEmail() {
        UUID tenantId = seedTenant("DigJ");
        String ownerEmail = "owner-" + UUID.randomUUID() + "@test.com";
        seedUser(tenantId, ownerEmail, "owner", true);

        digestJob.run();

        verify(emailGateway, never()).send(eq(ownerEmail), any(), any());
    }

    // -----------------------------------------------------------------------
    // (k) CROSS-CHANNEL: a CRITICAL already alerted immediately still appears
    //     in the digest's "new" section — no cross-channel special-casing.
    // -----------------------------------------------------------------------
    @Test
    void crossChannel_immediateAlreadySent_stillAppearsInDigestNew() {
        UUID tenantId = seedTenant("DigK");
        UUID storeId = seedStore(tenantId);
        String ownerEmail = "owner-" + UUID.randomUUID() + "@test.com";
        seedUser(tenantId, ownerEmail, "owner", true);
        seedCritical(tenantId, storeId);

        immediateJob.run(); // sends immediate email, writes channel='immediate' ledger row
        reset(emailGateway);

        digestJob.run(); // channel='digest' ledger is still empty for this item

        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
        verify(emailGateway, times(1)).send(eq(ownerEmail), anyString(), bodyCaptor.capture());
        String body = bodyCaptor.getValue();
        assertThat(body).contains("New since last summary");
        assertThat(body).contains("marked as lost");
    }

    // -----------------------------------------------------------------------
    // (l) digest recipients owners+managers; tenant isolation holds.
    // -----------------------------------------------------------------------
    @Test
    void recipientsOwnersAndManagers_tenantIsolationHolds() {
        UUID tenantA = seedTenant("DigL-A");
        UUID storeA  = seedStore(tenantA);
        String ownerAEmail   = "ownerA-"   + UUID.randomUUID() + "@test.com";
        String managerAEmail = "managerA-" + UUID.randomUUID() + "@test.com";
        String workerAEmail  = "workerA-"  + UUID.randomUUID() + "@test.com";
        seedUser(tenantA, ownerAEmail, "owner", true);
        seedUser(tenantA, managerAEmail, "manager", true);
        seedUser(tenantA, workerAEmail, "worker", true);
        seedCritical(tenantA, storeA);

        UUID tenantB = seedTenant("DigL-B");
        String ownerBEmail = "ownerB-" + UUID.randomUUID() + "@test.com";
        seedUser(tenantB, ownerBEmail, "owner", true);
        // tenant B has no open exceptions

        digestJob.run();

        verify(emailGateway, times(1)).send(eq(ownerAEmail), anyString(), anyString());
        verify(emailGateway, times(1)).send(eq(managerAEmail), anyString(), anyString());
        verify(emailGateway, never()).send(eq(workerAEmail), any(), any());
        verify(emailGateway, never()).send(eq(ownerBEmail), any(), any());
    }
}
