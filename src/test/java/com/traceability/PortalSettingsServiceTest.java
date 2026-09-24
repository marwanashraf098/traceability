package com.traceability;

import com.traceability.portal.PortalSettingsService;
import com.traceability.tenancy.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Returns portal Step 4b — only a unique violation on tenants_portal_slug_unique is reported
 * as "That address is already taken." (409). Any other integrity error surfaces unchanged.
 *
 * Unit-level with a mocked JdbcTemplate: no other constraint on the columns the settings
 * UPDATE writes can be tripped through the API (the slug format CHECK and NOT NULLs are
 * pre-validated, and no other unique index covers those columns), so the database can't
 * realistically produce one. The mock drives the error-mapping branch directly.
 */
class PortalSettingsServiceTest {

    private static final String UPDATE_PREFIX = "UPDATE tenants SET portal_slug";

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final PortalSettingsService service = new PortalSettingsService(jdbc);
    private final PortalSettingsService.Settings valid =
        new PortalSettingsService.Settings("snouts", true, false, 30);

    @BeforeEach void tenant() { TenantContext.set(UUID.randomUUID()); }
    @AfterEach  void clear()  { TenantContext.clear(); }

    @Test
    void slugUniqueViolation_is409Taken() {
        failUpdateWith(new DuplicateKeyException(
            "ERROR: duplicate key value violates unique constraint \"tenants_portal_slug_unique\""));
        assertThatThrownBy(() -> service.update(valid))
            .isInstanceOfSatisfying(ResponseStatusException.class, e -> {
                assertThat(e.getStatusCode().value()).isEqualTo(409);
                assertThat(e.getReason()).isEqualTo("That address is already taken.");
            });
    }

    @Test
    void otherUniqueViolation_rethrownUnchanged_notReportedAsTaken() {
        DuplicateKeyException other = new DuplicateKeyException(
            "ERROR: duplicate key value violates unique constraint \"tenants_some_other_unique\"");
        failUpdateWith(other);
        assertThatThrownBy(() -> service.update(valid)).isSameAs(other);
    }

    @Test
    void checkViolation_rethrownUnchanged_notReportedAsTaken() {
        DataIntegrityViolationException check = new DataIntegrityViolationException(
            "ERROR: new row for relation \"tenants\" violates check constraint \"tenants_portal_slug_format\"");
        failUpdateWith(check);
        assertThatThrownBy(() -> service.update(valid)).isSameAs(check);
    }

    private void failUpdateWith(RuntimeException e) {
        // 8 bind args: slug, enabled, autoApprove, window, logoUrl, brandColor, policyText (V103), id.
        when(jdbc.update(startsWith(UPDATE_PREFIX), any(), any(), any(), any(), any(), any(), any(), any())).thenThrow(e);
    }
}
