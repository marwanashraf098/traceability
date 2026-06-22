package com.traceability;

import com.traceability.account.AuditService;
import com.traceability.account.TenantController;
import com.traceability.identity.CustomUserDetails;
import com.traceability.integrations.bosta.BostaGateway;
import com.traceability.tenancy.TenantContext;
import org.jobrunr.scheduling.JobScheduler;
import org.junit.jupiter.api.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

/**
 * FR-1.4 Tenant settings round-trip + V25 schema.
 *
 * Matrix:
 *   s1 — V25 adds default_language, timezone, pickup_address to tenants
 *   s2 — GET /tenant/settings returns correct defaults after V25
 *   s3 — PUT /tenant/settings (via service) updates name, labelSize, language, timezone, address
 *   s4 — PUT writes audit_log entry
 *   s5 — invalid labelSize → 400
 *   s6 — invalid defaultLanguage → 400
 *   s7 — Bosta-specific fields (awb_format, pickup_mode) are NOT on tenants — no duplication
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TenantSettingsTest {

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

    @Autowired TenantController tenantCtl;
    @Autowired AuditService     auditSvc;
    @Autowired JdbcTemplate     jdbc;
    @MockBean  BostaGateway     bostaGateway;
    @MockBean  JobScheduler     jobScheduler;

    UUID tenantId, ownerId;

    @BeforeAll
    void setup() {
        tenantId = UUID.randomUUID();
        ownerId  = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, 'SettingsTenant')", tenantId);
        jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role) " +
                    "VALUES (?, ?, 'Owner', 'owner@s.local', 'h', 'owner')", ownerId, tenantId);
    }

    @BeforeEach
    void ctx() {
        TenantContext.set(tenantId);
        var principal = new CustomUserDetails(ownerId, tenantId, "owner");
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }

    @AfterEach
    void cleanup() {
        TenantContext.clear();
        SecurityContextHolder.clearContext();
        jdbc.update("DELETE FROM audit_log WHERE tenant_id = ?", tenantId);
        // Reset tenant to known state
        jdbc.update("UPDATE tenants SET name='SettingsTenant', pickup_address=NULL, " +
                    "default_language='ar', timezone='Africa/Cairo' WHERE id = ?", tenantId);
    }

    @Test
    void s1_v25_columnsExist() {
        for (String col : List.of("default_language", "timezone", "pickup_address")) {
            Integer cnt = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns " +
                "WHERE table_schema='public' AND table_name='tenants' AND column_name=?",
                Integer.class, col);
            assertThat(cnt).as("V25 must add " + col + " to tenants").isOne();
        }
    }

    @Test
    void s2_getSettings_returnsDefaults() {
        var principal = new com.traceability.identity.CustomUserDetails(ownerId, tenantId, "owner");
        Map<String, Object> settings = tenantCtl.get(principal);
        assertThat(settings.get("defaultLanguage")).isEqualTo("ar");
        assertThat(settings.get("timezone")).isEqualTo("Africa/Cairo");
        assertThat(settings.get("name")).isEqualTo("SettingsTenant");
    }

    @Test
    void s3_putSettings_roundTrip() {
        var principal = new com.traceability.identity.CustomUserDetails(ownerId, tenantId, "owner");
        var req = new TenantController.TenantSettingsRequest(
            "New Name", "123 Nasr City, Cairo", "40x25", "en", "Africa/Cairo");
        tenantCtl.update(req, principal);

        Map<String, Object> row = jdbc.queryForMap(
            "SELECT name, pickup_address, default_language, label_width_mm FROM tenants WHERE id = ?",
            tenantId);
        assertThat(row.get("name")).isEqualTo("New Name");
        assertThat(row.get("pickup_address")).isEqualTo("123 Nasr City, Cairo");
        assertThat(row.get("default_language")).isEqualTo("en");
        double w = ((Number) row.get("label_width_mm")).doubleValue();
        assertThat(w).isEqualTo(40.0);
    }

    @Test
    void s4_putSettings_writesAuditEntry() {
        var principal = new com.traceability.identity.CustomUserDetails(ownerId, tenantId, "owner");
        var req = new TenantController.TenantSettingsRequest(
            "Audited Tenant", null, null, null, null);
        tenantCtl.update(req, principal);

        // TenantController.update() uses runAs() internally which clears TenantContext.
        // Check the audit row directly via the postgres (BYPASSRLS) datasource.
        Integer count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM audit_log WHERE tenant_id = ? AND action = 'tenant_settings_update'",
            Integer.class, tenantId);
        assertThat(count).isOne();
    }

    @Test
    void s5_invalidLabelSize_returns400() {
        var principal = new com.traceability.identity.CustomUserDetails(ownerId, tenantId, "owner");
        var req = new TenantController.TenantSettingsRequest(null, null, "99x99", null, null);
        assertThatThrownBy(() -> tenantCtl.update(req, principal))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("labelSize");
    }

    @Test
    void s6_invalidLanguage_returns400() {
        var principal = new com.traceability.identity.CustomUserDetails(ownerId, tenantId, "owner");
        var req = new TenantController.TenantSettingsRequest(null, null, null, "fr", null);
        assertThatThrownBy(() -> tenantCtl.update(req, principal))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("defaultLanguage");
    }

    @Test
    void s7_bostaFieldsNotOnTenants() {
        // Verify awb_format and pickup_mode do NOT exist on the tenants table
        for (String col : List.of("awb_format", "pickup_mode")) {
            Integer cnt = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns " +
                "WHERE table_schema='public' AND table_name='tenants' AND column_name=?",
                Integer.class, col);
            assertThat(cnt).as(col + " must NOT be on tenants (it lives on courier_accounts)").isZero();
        }
    }
}
