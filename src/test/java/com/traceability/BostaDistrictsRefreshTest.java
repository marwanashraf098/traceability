package com.traceability;

import com.traceability.integrations.bosta.BostaDistrictsRefreshService;
import com.traceability.integrations.bosta.BostaV2Client;
import org.jobrunr.scheduling.JobScheduler;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * Returns portal Step 4c-2 — bosta_districts: the refresh (upsert, deactivate-not-delete,
 * empty/401 change nothing) and the grants (app_user may SELECT, never write). The HTTP side
 * (no Authorization header, parsing) is BostaV2ClientTest.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BostaDistrictsRefreshTest {

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

    @Autowired JdbcTemplate jdbc;
    @Autowired BostaDistrictsRefreshService refresh;
    @MockBean BostaV2Client bosta;
    @MockBean JobScheduler jobScheduler;

    static final BostaV2Client.District D10 = new BostaV2Client.District("wY_JL43TilR", "FceDyHXwpSYYF9zGW", "Cairo", "القاهرة",
        "g3jl3V8FMN", "New Cairo", "القاهره الجديده", "1st Settlement - District 10", "التجمع الاول - الحي 10", true, true);
    static final BostaV2Client.District D11 = new BostaV2Client.District("_jLhFsfVsLu", "FceDyHXwpSYYF9zGW", "Cairo", "القاهرة",
        "g3jl3V8FMN", "New Cairo", "القاهره الجديده", "1st Settlement - District 11", "التجمع الاول - الحي 11", false, true);
    static final BostaV2Client.District NASR = new BostaV2Client.District("Iy7-lFD0BE0", "FceDyHXwpSYYF9zGW", "Cairo", "القاهرة",
        "qWckBs-T7", "Nasr City", "مدينة نصر", "Nasr City - 7th District", "مدينة نصر - الحي السابع", true, true);

    @AfterEach void clear() { jdbc.update("DELETE FROM bosta_districts"); }

    @Test
    void refresh_upserts_thenDeactivatesMissing_neverDeletes() {
        when(bosta.fetchAllDistricts()).thenReturn(List.of(D10, D11, NASR));
        assertThat(refresh.isEmpty()).isTrue();

        BostaDistrictsRefreshService.Result first = refresh.refresh();
        assertThat(first.upserted()).isEqualTo(3);
        assertThat(first.deactivated()).isZero();
        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM bosta_districts WHERE district_id = 'wY_JL43TilR'");
        assertThat(row.get("city_name_ar")).isEqualTo("القاهرة");
        assertThat(row.get("zone_name_ar")).isEqualTo("القاهره الجديده");
        assertThat(row.get("district_name_ar")).isEqualTo("التجمع الاول - الحي 10");
        assertThat(row.get("pickup_available")).isEqualTo(true);
        assertThat(pickup("_jLhFsfVsLu")).as("Bosta says no pickup").isFalse();

        // Next day: Nasr City is no longer returned; District 10 was renamed.
        BostaV2Client.District renamed = new BostaV2Client.District(D10.districtId(), D10.cityId(), D10.cityName(),
            D10.cityNameAr(), D10.zoneId(), D10.zoneName(), D10.zoneNameAr(), "First Settlement - 10", D10.districtNameAr(),
            true, true);
        when(bosta.fetchAllDistricts()).thenReturn(List.of(renamed, D11));
        BostaDistrictsRefreshService.Result second = refresh.refresh();

        assertThat(second.upserted()).isEqualTo(2);
        assertThat(second.deactivated()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM bosta_districts", Integer.class)).as("never deleted").isEqualTo(3);
        assertThat(pickup("Iy7-lFD0BE0")).as("no longer returned → not pickup-available").isFalse();
        assertThat(jdbc.queryForObject("SELECT district_name FROM bosta_districts WHERE district_id = 'wY_JL43TilR'", String.class))
            .isEqualTo("First Settlement - 10");
    }

    @Test
    void refresh_emptyResponse_changesNothing() {
        when(bosta.fetchAllDistricts()).thenReturn(List.of(D10, NASR));
        refresh.refresh();
        when(bosta.fetchAllDistricts()).thenReturn(List.of());

        BostaDistrictsRefreshService.Result r = refresh.refresh();

        assertThat(r.upserted()).isZero();
        assertThat(pickup("wY_JL43TilR")).isTrue();
        assertThat(pickup("Iy7-lFD0BE0")).isTrue();
    }

    @Test
    void refresh_401_logsNeedsAuth_changesNothing() {
        when(bosta.fetchAllDistricts()).thenReturn(List.of(D10));
        refresh.refresh();
        when(bosta.fetchAllDistricts()).thenThrow(new BostaV2Client.AuthRequiredException(401));

        BostaDistrictsRefreshService.Result r = refresh.refresh();

        assertThat(r.authRequired()).isTrue();
        assertThat(pickup("wY_JL43TilR")).isTrue();
    }

    @Test
    void grants_appUserCanSelect_butNotInsertUpdateDelete() {
        when(bosta.fetchAllDistricts()).thenReturn(List.of(D10));
        refresh.refresh();
        JdbcTemplate appUser = new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "app_user", "testpw"));

        assertThat(appUser.queryForObject("SELECT COUNT(*) FROM bosta_districts", Integer.class))
            .as("app_user can read the global reference table, no GUC needed").isEqualTo(1);
        assertThatThrownBy(() -> appUser.update("INSERT INTO bosta_districts (district_id, city_id, city_name, district_name) " +
            "VALUES ('x', 'c', 'City', 'District')")).hasStackTraceContaining("permission denied for table bosta_districts");
        assertThatThrownBy(() -> appUser.update("UPDATE bosta_districts SET pickup_available = false"))
            .hasStackTraceContaining("permission denied for table bosta_districts");
        assertThatThrownBy(() -> appUser.update("DELETE FROM bosta_districts"))
            .hasStackTraceContaining("permission denied for table bosta_districts");
        assertThat(pickup("wY_JL43TilR")).isTrue();
    }

    private boolean pickup(String districtId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
            "SELECT pickup_available FROM bosta_districts WHERE district_id = ?", Boolean.class, districtId));
    }
}
