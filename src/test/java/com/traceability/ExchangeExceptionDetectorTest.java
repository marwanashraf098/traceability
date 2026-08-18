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
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FR-EXCHANGE Phase 1 — the 19th exception detector, exchange_needs_mapping.
 * Self-resolving like detectGuidedUnpack: mapping flips exchanges.status away from
 * 'needs_mapping', which removes the row from the detector query directly — no
 * exception_resolutions bookkeeping.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ExchangeExceptionDetectorTest {

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

    UUID tenantId;

    @BeforeAll
    void setupFixture() {
        tenantId = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, 'ExchangeDetectorTenant')", tenantId);
    }

    @BeforeEach void ctx()   { TenantContext.set(tenantId); }
    @AfterEach  void clear() { TenantContext.clear(); }

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM exchanges WHERE tenant_id = ?", tenantId);
    }

    @Test
    void needsMapping_surfacesAsMediumException_withTrackingAndDescriptions() {
        UUID exchangeId = jdbc.queryForObject(
            "INSERT INTO exchanges " +
            "  (tenant_id, tracking_number, status, outbound_description, inbound_description, raw) " +
            "VALUES (?, '877468285', 'needs_mapping', 'Yellow hat', 'Red hat', '{}'::jsonb) RETURNING id",
            UUID.class, tenantId);

        List<Map<String, Object>> hits = exceptionsOfType("exchange_needs_mapping");
        assertThat(hits).hasSize(1);
        Map<String, Object> hit = hits.get(0);
        assertThat(hit.get("severity")).isEqualTo("MEDIUM");
        assertThat(hit.get("tracking_number")).isEqualTo("877468285");
        assertThat(hit.get("subject_key")).isEqualTo("exchange_needs_mapping:" + exchangeId);
        assertThat((String) hit.get("descriptionEn")).contains("877468285");
        assertThat(hit.get("actionUrl")).isEqualTo("/exchanges/" + exchangeId);

        ExceptionService.OpenExceptionCounts counts = excSvc.countOpenExceptionsBySeverity();
        assertThat(counts.warning()).as("MEDIUM collapses into the warning bucket").isGreaterThanOrEqualTo(1);
    }

    @Test
    void mapped_selfResolves_noLongerSurfaces() {
        UUID exchangeId = jdbc.queryForObject(
            "INSERT INTO exchanges (tenant_id, tracking_number, status, raw) " +
            "VALUES (?, '6336637079', 'needs_mapping', '{}'::jsonb) RETURNING id",
            UUID.class, tenantId);

        assertThat(exceptionsOfType("exchange_needs_mapping")).hasSize(1);

        // Mapping flips status away from 'needs_mapping' — no resolution row is written,
        // the detector's own WHERE clause removes it (same pattern as detectGuidedUnpack).
        jdbc.update("UPDATE exchanges SET status = 'mapped' WHERE id = ?", exchangeId);

        assertThat(exceptionsOfType("exchange_needs_mapping"))
            .as("mapped exchange must self-resolve out of the exceptions list")
            .isEmpty();
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> exceptions() {
        Map<String, Object> result = excSvc.listExceptions(null, null, 0, 200);
        return (List<Map<String, Object>>) result.get("items");
    }

    private List<Map<String, Object>> exceptionsOfType(String type) {
        return exceptions().stream()
            .filter(e -> type.equals(e.get("type")))
            .collect(Collectors.toList());
    }
}
