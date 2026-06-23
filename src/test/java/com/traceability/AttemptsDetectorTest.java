package com.traceability;

import com.traceability.inventory.ExceptionService;
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

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FR-11.3: high_attempts detector — surfaces MEDIUM when a non-terminal shipment
 * has number_of_attempts >= 2; excluded by terminal state or exception_resolutions.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AttemptsDetectorTest {

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

    @Autowired ExceptionService excSvc;
    @Autowired JdbcTemplate     jdbc;
    @MockBean  JobScheduler     jobScheduler;

    UUID tenantId, actorId, storeId;

    @BeforeAll
    void setupFixture() {
        tenantId = UUID.randomUUID();
        actorId  = UUID.randomUUID();
        storeId  = UUID.randomUUID();

        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, 'AttemptsTenant')", tenantId);
        jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role) " +
                    "VALUES (?, ?, 'Ops', 'ops@att.local', 'h', 'manager')", actorId, tenantId);
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status) " +
                    "VALUES (?, ?, 'shopify', 'att.myshopify.com', 'disconnected')", storeId, tenantId);
    }

    @BeforeEach void ctx()   { TenantContext.set(tenantId); }
    @AfterEach  void clear() {
        TenantContext.clear();
        jdbc.update("DELETE FROM exception_resolutions WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM shipments WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM orders WHERE tenant_id = ?", tenantId);
    }

    // at1: 2 attempts on non-terminal shipment → MEDIUM, descriptions populated
    @Test
    void at1_twoAttempts_surfacesMedium() {
        UUID orderId = order();
        UUID shipId  = shipment(orderId, "AWB-AT1", "with_courier", 2);

        List<Map<String, Object>> items = exceptionsOfType("high_attempts");
        assertThat(items).hasSize(1);
        Map<String, Object> item = items.get(0);
        assertThat(item).containsEntry("severity", "MEDIUM");
        assertThat(item.get("shipment_id").toString()).isEqualTo(shipId.toString());
        assertThat(item.get("number_of_attempts")).isEqualTo(2);
        assertThat(item.get("descriptionEn").toString()).contains("AWB-AT1");
        assertThat(item.get("descriptionAr").toString()).contains("محاولات");
        assertThat(item.get("suggestedAction")).isEqualTo("contact_customer");
    }

    // at2: 1 attempt → does NOT surface
    @Test
    void at2_oneAttempt_doesNotSurface() {
        UUID orderId = order();
        shipment(orderId, "AWB-AT2", "with_courier", 1);
        assertThat(exceptionsOfType("high_attempts")).isEmpty();
    }

    // at3: 3 attempts but terminal (delivered) → does NOT surface
    @Test
    void at3_terminalShipment_excluded() {
        UUID orderId = order();
        shipment(orderId, "AWB-AT3", "delivered", 3);
        assertThat(exceptionsOfType("high_attempts")).isEmpty();
    }

    // at4: resolved → suppressed by exception_resolutions
    @Test
    void at4_resolved_suppressed() {
        UUID orderId = order();
        UUID shipId  = shipment(orderId, "AWB-AT4", "with_courier", 2);

        // Confirm it surfaces before resolve
        assertThat(exceptionsOfType("high_attempts")).hasSize(1);

        String key = "high_attempts:shipment:" + shipId;
        excSvc.resolve("high_attempts", key, actorId, "Customer contacted, retry scheduled");

        assertThat(exceptionsOfType("high_attempts")).isEmpty();
    }

    // at5: severity ordering — high_attempts (MEDIUM) sorts after HIGH exceptions
    @Test
    void at5_severityOrdering_mediumSortsAfterHigh() {
        // HIGH: stuck shipment (last_synced > 5d ago, default stuck_shipment_days=5)
        UUID o1    = order();
        UUID ship1 = shipment(o1, "AWB-SORT-H", "with_courier", 0);
        jdbc.update("UPDATE shipments SET last_synced_at = now()-interval '6 days' WHERE id = ?", ship1);

        // MEDIUM: high_attempts
        UUID o2 = order();
        shipment(o2, "AWB-SORT-M", "with_courier", 2);

        List<Map<String, Object>> all = exceptions();
        List<String> severities = all.stream().map(e -> (String) e.get("severity")).toList();
        List<String> types      = all.stream().map(e -> (String) e.get("type")).toList();

        int highIdx = severities.indexOf("HIGH");
        int medIdx  = types.indexOf("high_attempts");
        assertThat(highIdx).isGreaterThanOrEqualTo(0);
        assertThat(medIdx).isGreaterThan(highIdx);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private UUID order() {
        return jdbc.queryForObject(
            "INSERT INTO orders (tenant_id, store_id, external_id, number, status, " +
            "    payment_method, placed_at) " +
            "VALUES (?, ?, ?, '#ATT', 'awaiting_pickup'::order_status, 'cod', now()) RETURNING id",
            UUID.class, tenantId, storeId, "ATT-" + UUID.randomUUID());
    }

    private UUID shipment(UUID orderId, String tracking, String state, int attempts) {
        return jdbc.queryForObject(
            "INSERT INTO shipments (id, tenant_id, order_id, tracking_number, " +
            "    internal_state, number_of_attempts) " +
            "VALUES (gen_random_uuid(), ?, ?, ?, ?::shipment_internal_state, ?) RETURNING id",
            UUID.class, tenantId, orderId, tracking, state, attempts);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> exceptions() {
        Map<String, Object> result = excSvc.listExceptions(null, null, 0, 200);
        return (List<Map<String, Object>>) result.get("items");
    }

    private List<Map<String, Object>> exceptionsOfType(String type) {
        return exceptions().stream()
            .filter(e -> type.equals(e.get("type")))
            .collect(java.util.stream.Collectors.toList());
    }
}
