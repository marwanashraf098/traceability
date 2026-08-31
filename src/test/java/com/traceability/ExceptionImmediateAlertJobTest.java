package com.traceability;

import com.traceability.inventory.ExceptionService;
import com.traceability.notifications.EmailGateway;
import com.traceability.notifications.ExceptionImmediateAlertJob;
import com.traceability.tenancy.TenantContext;
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

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Hybrid exception notifications, immediate CRITICAL/HIGH sweep. Calls
 * ExceptionImmediateAlertJob.run() directly with seeded data (same idiom as
 * ShopifyReconcileTest for ShopifyReconcileJob) — does not rely on the recurring trigger.
 *
 * background-job-server.enabled=true so the @ConditionalOnProperty-gated job bean exists
 * to autowire (mirrors ShopifyReconcileTest); JobScheduler is @MockBean so nothing queued
 * elsewhere in the app context actually executes.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
                properties = "org.jobrunr.background-job-server.enabled=true")
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ExceptionImmediateAlertJobTest {

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

    @Autowired ExceptionImmediateAlertJob job;
    @Autowired ExceptionService           exceptionService;
    @Autowired JdbcTemplate               jdbc;

    @MockBean EmailGateway emailGateway;
    @MockBean JobScheduler jobScheduler;

    @BeforeEach
    void resetMocks() {
        reset(emailGateway, jobScheduler);
    }

    // ── Fixture helpers ──────────────────────────────────────────────────────

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

    /** HIGH — detectGuidedUnpack: a cancelled order still packed. */
    private void seedHigh(UUID tenantId, UUID storeId) {
        UUID orderId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO orders (id, tenant_id, store_id, external_id, status, cancel_requested_at) " +
                "VALUES (?, ?, ?, ?, 'packed'::order_status, now())",
                orderId, tenantId, storeId, "ext-high-" + orderId);
    }

    /** MEDIUM — detectUnmatched: an unresolved unlinked Bosta delivery. */
    private void seedMedium(UUID tenantId) {
        jdbc.update(
                "INSERT INTO unlinked_bosta_deliveries " +
                "(tenant_id, tracking_number, bosta_state_code, bosta_order_type, resolved) " +
                "VALUES (?, ?, 10, 'SEND', false)",
                tenantId, "TRK-" + UUID.randomUUID());
    }

    /** LOW — detectBlocked: an order on hold. */
    private void seedLow(UUID tenantId, UUID storeId) {
        UUID orderId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO orders (id, tenant_id, store_id, external_id, status, on_hold, hold_reason) " +
                "VALUES (?, ?, ?, ?, 'new'::order_status, true, 'test hold')",
                orderId, tenantId, storeId, "ext-low-" + orderId);
    }

    private long immediateLedgerCount(UUID tenantId) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM exception_notifications WHERE tenant_id = ? AND channel = 'immediate'",
                Long.class, tenantId);
    }

    private static final String ARABIC_MARKER = "<!-- ============ ARABIC ============ -->";

    private static int countOccurrences(String haystack, String needle) {
        int count = 0, idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) != -1) {
            count++;
            idx += needle.length();
        }
        return count;
    }

    // -----------------------------------------------------------------------
    // (a) one CRITICAL + one HIGH, ledger empty → one batched email, 2 ledger rows.
    // -----------------------------------------------------------------------
    @Test
    void criticalAndHighOpen_ledgerEmpty_oneBatchedEmail_twoLedgerRows() {
        UUID tenantId = seedTenant("ImmA");
        UUID storeId = seedStore(tenantId);
        String ownerEmail = "owner-" + UUID.randomUUID() + "@test.com";
        seedUser(tenantId, ownerEmail, "owner", true);
        seedCritical(tenantId, storeId);
        seedHigh(tenantId, storeId);

        job.run();

        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
        verify(emailGateway, times(1)).send(eq(ownerEmail), anyString(), bodyCaptor.capture());
        String body = bodyCaptor.getValue();
        assertThat(body).contains("marked as lost");                  // CRITICAL item
        assertThat(body).contains("must be physically unpacked");     // HIGH item

        assertThat(immediateLedgerCount(tenantId)).isEqualTo(2L);
    }

    // -----------------------------------------------------------------------
    // (template) no leftover {{ tokens; correct per-severity color+label in each
    // language region; N items render N rows in BOTH the EN and AR regions.
    // -----------------------------------------------------------------------
    @Test
    void alertBody_noLeftoverTokens_correctSeverityColors_andRowCountPerLanguage() {
        UUID tenantId = seedTenant("ImmTpl");
        UUID storeId = seedStore(tenantId);
        String ownerEmail = "owner-" + UUID.randomUUID() + "@test.com";
        seedUser(tenantId, ownerEmail, "owner", true);
        seedCritical(tenantId, storeId);
        seedHigh(tenantId, storeId);

        job.run();

        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
        verify(emailGateway, times(1)).send(eq(ownerEmail), anyString(), bodyCaptor.capture());
        String body = bodyCaptor.getValue();

        assertThat(body).doesNotContain("{{");

        int arabicIdx = body.indexOf(ARABIC_MARKER);
        assertThat(arabicIdx).isGreaterThan(0);
        String enHalf = body.substring(0, arabicIdx);
        String arHalf = body.substring(arabicIdx);

        // CRITICAL row: correct color + label in each language's half.
        assertThat(enHalf).contains("#DC2626").contains("CRITICAL");
        assertThat(arHalf).contains("#DC2626").contains("حرجة");
        // HIGH row: correct color + label in each language's half.
        assertThat(enHalf).contains("#D97706").contains("HIGH");
        assertThat(arHalf).contains("#D97706").contains("عالية");

        // 2 items → exactly 2 rows in the EN region and 2 in the AR region
        // (badge + description only — no per-item link). "border-radius:6px" occurs
        // exactly once per row template (the severity badge cell), so its count scales
        // 1:1 with item count — unlike counting severity label text, which false-positives
        // in Arabic ("العالية" in the intro sentence contains "عالية" as a substring).
        assertThat(countOccurrences(enHalf, "border-radius:6px")).isEqualTo(2);
        assertThat(countOccurrences(arHalf, "border-radius:6px")).isEqualTo(2);
        assertThat(body).doesNotContain("View");
        assertThat(body).doesNotContain("عرض");

        // Descriptions land in the correct language half, not the other.
        assertThat(enHalf).contains("marked as lost");
        assertThat(arHalf).doesNotContain("marked as lost");
    }

    // -----------------------------------------------------------------------
    // (logo) rendered body carries the app-parity wordmark — dark "traced" +
    // blue dot span — NOT the old all-blue "traced•".
    // -----------------------------------------------------------------------
    @Test
    void alertBody_containsCorrectedWordmark_notOldAllBlueTraced() {
        UUID tenantId = seedTenant("ImmWordmark");
        UUID storeId = seedStore(tenantId);
        String ownerEmail = "owner-" + UUID.randomUUID() + "@test.com";
        seedUser(tenantId, ownerEmail, "owner", true);
        seedCritical(tenantId, storeId);

        job.run();

        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
        verify(emailGateway, times(1)).send(eq(ownerEmail), anyString(), bodyCaptor.capture());
        String body = bodyCaptor.getValue();

        assertThat(body).contains("letter-spacing:-0.4px; color:#1F2937;");
        assertThat(body).contains("border-radius:50%; background-color:#2563EB;");
        assertThat(body).doesNotContain("traced<span style=\"color:#2563EB;\">&#8226;</span>");
    }

    // -----------------------------------------------------------------------
    // (b) re-run immediately, same open now in ledger → zero emails.
    // -----------------------------------------------------------------------
    @Test
    void rerunAfterLedgered_zeroEmails() {
        UUID tenantId = seedTenant("ImmB");
        UUID storeId = seedStore(tenantId);
        String ownerEmail = "owner-" + UUID.randomUUID() + "@test.com";
        seedUser(tenantId, ownerEmail, "owner", true);
        seedCritical(tenantId, storeId);

        job.run();
        verify(emailGateway, times(1)).send(eq(ownerEmail), anyString(), anyString());

        reset(emailGateway);

        job.run();
        verify(emailGateway, never()).send(eq(ownerEmail), any(), any());
    }

    // -----------------------------------------------------------------------
    // (c) only MEDIUM/LOW open → zero immediate emails.
    // -----------------------------------------------------------------------
    @Test
    void onlyMediumAndLowOpen_zeroEmails() {
        UUID tenantId = seedTenant("ImmC");
        UUID storeId = seedStore(tenantId);
        String ownerEmail = "owner-" + UUID.randomUUID() + "@test.com";
        seedUser(tenantId, ownerEmail, "owner", true);
        seedMedium(tenantId);
        seedLow(tenantId, storeId);

        job.run();

        verify(emailGateway, never()).send(eq(ownerEmail), any(), any());
        assertThat(immediateLedgerCount(tenantId)).isEqualTo(0L);
    }

    // -----------------------------------------------------------------------
    // (d) ORDERING PROOF: send() throws → zero ledger rows written.
    // -----------------------------------------------------------------------
    @Test
    void sendThrows_zeroLedgerRowsWritten() {
        UUID tenantId = seedTenant("ImmD");
        UUID storeId = seedStore(tenantId);
        String ownerEmail = "owner-" + UUID.randomUUID() + "@test.com";
        seedUser(tenantId, ownerEmail, "owner", true);
        seedCritical(tenantId, storeId);

        doThrow(new RuntimeException("smtp down")).when(emailGateway).send(any(), any(), any());

        job.run(); // per-tenant try/catch swallows the failure; does not propagate

        assertThat(immediateLedgerCount(tenantId)).isEqualTo(0L);
    }

    // -----------------------------------------------------------------------
    // (e) ANTI-DRIFT: seed one of EACH severity; immediate selection EQUALS
    //     detectAllOpen() filtered to {CRITICAL,HIGH} — no independent detector list.
    // -----------------------------------------------------------------------
    @Test
    void antiDrift_selectionEqualsFullDetectFilteredToCriticalHigh() {
        UUID tenantId = seedTenant("ImmE");
        UUID storeId = seedStore(tenantId);
        String ownerEmail = "owner-" + UUID.randomUUID() + "@test.com";
        seedUser(tenantId, ownerEmail, "owner", true);
        seedCritical(tenantId, storeId);
        seedHigh(tenantId, storeId);
        seedMedium(tenantId);
        seedLow(tenantId, storeId);

        Set<String> expectedKeys = TenantContext.runAs(tenantId, () ->
                exceptionService.detectAllOpen().stream()
                        .filter(e -> "CRITICAL".equals(e.get("severity")) || "HIGH".equals(e.get("severity")))
                        .map(e -> e.get("type") + " " + e.get("subject_key"))
                        .collect(Collectors.toSet()));
        assertThat(expectedKeys).hasSize(2);

        job.run();

        List<Map<String, Object>> ledgerRows = jdbc.queryForList(
                "SELECT exception_type, subject_key FROM exception_notifications " +
                "WHERE tenant_id = ? AND channel = 'immediate'", tenantId);
        Set<String> actualKeys = ledgerRows.stream()
                .map(row -> row.get("exception_type") + " " + row.get("subject_key"))
                .collect(Collectors.toSet());

        assertThat(actualKeys).isEqualTo(expectedKeys);
    }

    // -----------------------------------------------------------------------
    // (f) recipients: owner AND manager, NOT worker; inactive excluded.
    // -----------------------------------------------------------------------
    @Test
    void recipients_ownerAndManagerOnly_excludesWorkerAndInactive() {
        UUID tenantId = seedTenant("ImmF");
        UUID storeId = seedStore(tenantId);
        String ownerEmail    = "owner-"    + UUID.randomUUID() + "@test.com";
        String managerEmail  = "manager-"  + UUID.randomUUID() + "@test.com";
        String workerEmail   = "worker-"   + UUID.randomUUID() + "@test.com";
        String inactiveEmail = "inactive-" + UUID.randomUUID() + "@test.com";
        seedUser(tenantId, ownerEmail, "owner", true);
        seedUser(tenantId, managerEmail, "manager", true);
        seedUser(tenantId, workerEmail, "worker", true);
        seedUser(tenantId, inactiveEmail, "owner", false);
        seedCritical(tenantId, storeId);

        job.run();

        verify(emailGateway, times(1)).send(eq(ownerEmail), anyString(), anyString());
        verify(emailGateway, times(1)).send(eq(managerEmail), anyString(), anyString());
        verify(emailGateway, never()).send(eq(workerEmail), any(), any());
        verify(emailGateway, never()).send(eq(inactiveEmail), any(), any());
    }

    // -----------------------------------------------------------------------
    // (g) tenant isolation: tenant A's CRITICAL never emails tenant B's owner.
    // -----------------------------------------------------------------------
    @Test
    void tenantIsolation_tenantACriticalNeverEmailsTenantBOwner() {
        UUID tenantA = seedTenant("ImmG-A");
        UUID storeA  = seedStore(tenantA);
        String ownerAEmail = "ownerA-" + UUID.randomUUID() + "@test.com";
        seedUser(tenantA, ownerAEmail, "owner", true);
        seedCritical(tenantA, storeA);

        UUID tenantB = seedTenant("ImmG-B");
        String ownerBEmail = "ownerB-" + UUID.randomUUID() + "@test.com";
        seedUser(tenantB, ownerBEmail, "owner", true);
        // tenant B has no open exceptions

        job.run();

        verify(emailGateway, times(1)).send(eq(ownerAEmail), anyString(), anyString());
        verify(emailGateway, never()).send(eq(ownerBEmail), any(), any());
    }
}
