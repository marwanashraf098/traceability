package com.traceability;

import com.traceability.demo.DemoSeeder;
import com.traceability.integrations.bosta.BostaGateway;
import com.traceability.inventory.StockTakeService;
import com.traceability.places.PlaceCountService;
import com.traceability.places.Places;
import com.traceability.places.PutAwayService;
import com.traceability.review.OpsReviewTenantController;
import com.traceability.review.ReviewTenantService;
import com.traceability.tenancy.TenantContext;
import org.jobrunr.scheduling.JobScheduler;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Places rollout — the public demo and the reviewer account with places switched on.
 *
 *   dm1  demo, on and never set up: reseed runs, the store stays dormant (no shelf or box)
 *   dm2  demo, places used (shelves, a placed piece, an open put-away session, an open place count, a stock take on a
 *        place): reseed runs and removes every places row of the demo tenant; the switch itself is left as it was
 *   rv1  reviewer, on: the review seed builds the whole fixture with no Bosta call and the store is dormant
 *   rv2  reviewer, places used: review-tenant-reset.sql removes the places rows too, then the seed runs again
 */
@SpringBootTest
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PlacesRolloutDemoReviewTest {

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
    }

    @Autowired JdbcTemplate        jdbc;
    @Autowired DemoSeeder          demoSeeder;
    @Autowired ReviewTenantService reviewTenants;
    @Autowired PutAwayService      putAway;
    @Autowired PlaceCountService   counts;
    @Autowired StockTakeService    stockTake;
    @MockBean  BostaGateway        bostaGateway;
    @MockBean  JobScheduler        jobScheduler;

    static final UUID DEMO = DemoSeeder.DEMO_TENANT_ID;
    static final List<String> PLACES_TABLES =
        List.of("bins", "bin_events", "bin_counters", "put_away_sessions", "put_away_session_pieces", "place_counts", "place_count_scans");
    private UUID review;

    @Test @Order(1)
    void dm1_demo_onButNeverSetUp_reseedRuns_staysDormant() {
        demoSeeder.ensureBootstrapped();
        jdbc.update("UPDATE tenants SET places_enabled = true WHERE id = ?", DEMO);
        demoSeeder.reseed();
        assertThat(count("pieces", DEMO)).isPositive();
        assertThat(TenantContext.runAs(DEMO, () -> Places.enabled(jdbc, DEMO))).isTrue();
        assertThat(TenantContext.runAs(DEMO, () -> Places.anySetUp(jdbc, DEMO))).isFalse();
    }

    @Test @Order(2)
    void dm2_demo_placesUsed_reseedRemovesThem_switchKept() {
        UUID owner = jdbc.queryForObject("SELECT id FROM users WHERE tenant_id = ? AND role = 'owner' LIMIT 1", UUID.class, DEMO);
        useplaces(DEMO, owner);
        for (String t : PLACES_TABLES) assertThat(count(t, DEMO)).as(t + " before").isPositive();

        demoSeeder.reseed();
        for (String t : PLACES_TABLES) assertThat(count(t, DEMO)).as(t).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*)::int FROM pieces WHERE tenant_id = ? AND bin_id IS NOT NULL", Integer.class, DEMO))
            .isZero();
        assertThat(jdbc.queryForObject("SELECT places_enabled FROM tenants WHERE id = ?", Boolean.class, DEMO)).isTrue();
        assertThat(TenantContext.runAs(DEMO, () -> Places.anySetUp(jdbc, DEMO))).as("dormant again").isFalse();
        demoSeeder.reseed();
    }

    @Test @Order(3)
    void rv1_reviewer_on_seedBuildsTheWholeFixture_dormant() {
        review = UUID.fromString((String) reviewTenants.create(new OpsReviewTenantController.CreateRequest(
            "Traced Review Store", "App Reviewer", "reviewer@tracedtech.com", "01012345678", "pw-" + UUID.randomUUID(),
            "Review Worker", "4321")).get("tenantId"));
        jdbc.update("INSERT INTO tenant_courier_simulation (tenant_id, note) VALUES (?, 'places rollout test')", review);
        jdbc.update("UPDATE tenants SET places_enabled = true WHERE id = ?", review);
        reviewTenants.seed(review);
        ReviewFixtureAssertions.assertWholeFixture(jdbc, review);
        verifyNoInteractions(bostaGateway);
        assertThat(TenantContext.runAs(review, () -> Places.anySetUp(jdbc, review))).isFalse();
    }

    @Test @Order(4)
    void rv2_reviewer_placesUsed_resetRemovesThem_seedAgain() throws Exception {
        UUID owner = jdbc.queryForObject("SELECT id FROM users WHERE tenant_id = ? AND role = 'owner'", UUID.class, review);
        useplaces(review, owner);
        ExecResult r = psql("review-tenant-reset.sql", review);
        assertThat(r.getExitCode()).as(r.getStderr()).isZero();
        for (String t : PLACES_TABLES) assertThat(count(t, review)).as(t).isZero();
        reviewTenants.seed(review);
        ReviewFixtureAssertions.assertWholeFixture(jdbc, review);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /** A zone › rack › two shelves at the main warehouse, an available piece put on one, then open work on them. */
    private void useplaces(UUID tenant, UUID actor) {
        UUID main = jdbc.queryForObject("SELECT id FROM locations WHERE tenant_id = ? AND is_fulfillment", UUID.class, tenant);
        UUID zone = bin(tenant, main, "zone", null, null, "RZ");
        UUID rack = bin(tenant, main, "rack", zone, "zone", "RZ-R01");
        UUID s1 = bin(tenant, main, "shelf", rack, "rack", "RZ-R01-S1");
        UUID s2 = bin(tenant, main, "shelf", rack, "rack", "RZ-R01-S2");
        jdbc.update("INSERT INTO bin_counters (tenant_id, last_value) VALUES (?, 4) ON CONFLICT (tenant_id) DO NOTHING", tenant);
        jdbc.update("INSERT INTO bin_events (tenant_id, bin_id, event_type, actor_user_id) VALUES (?, ?, 'created', ?)", tenant, s1, actor);
        List<String> pieces = jdbc.queryForList(
            "SELECT id FROM pieces WHERE tenant_id = ? AND current_location_id = ? AND status = 'available' AND short_code IS NOT NULL " +
            "ORDER BY created_at, id LIMIT 2", String.class, tenant, main);
        assertThat(pieces).hasSize(2);
        PutAwayService.Session session = TenantContext.runAs(tenant, () -> putAway.start(actor, main));
        TenantContext.runAs(tenant, () -> putAway.scan(session.id(), scanCode(s1), actor, false));
        TenantContext.runAs(tenant, () -> putAway.scan(session.id(), code(pieces.get(0)), actor, false));
        PlaceCountService.Count count = TenantContext.runAs(tenant, () -> counts.start(actor, s2, null));
        TenantContext.runAs(tenant, () -> counts.scan(count.id(), code(pieces.get(1)), actor, false));
        Map<String, Object> open = TenantContext.runAs(tenant, () -> stockTake.openSession("all", null, main, null, actor));
        UUID st = (UUID) open.get("sessionId");
        TenantContext.runAs(tenant, () -> stockTake.scan(st, scanCode(s2), "good", actor, false));
    }

    private UUID bin(UUID tenant, UUID location, String kind, UUID parent, String parentKind, String code) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO bins (id, tenant_id, location_id, kind, parent_id, parent_kind, code, scan_code) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?)", id, tenant, location, kind, parent, parentKind, code,
            "Q9" + String.format("%05d", Math.abs(id.hashCode()) % 100_000));
        return id;
    }

    private String code(String pieceId) {
        return jdbc.queryForObject("SELECT short_code FROM pieces WHERE id = ?", String.class, pieceId);
    }

    private String scanCode(UUID binId) {
        return jdbc.queryForObject("SELECT scan_code FROM bins WHERE id = ?", String.class, binId);
    }

    private int count(String table, UUID tenant) {
        return jdbc.queryForObject("SELECT count(*)::int FROM " + table + " WHERE tenant_id = ?", Integer.class, tenant);
    }

    /** scripts/ops/&lt;file&gt; through psql in the database container, committed, as ops runs it. */
    private ExecResult psql(String file, UUID tenant) throws Exception {
        POSTGRES.copyFileToContainer(MountableFile.forHostPath("scripts/ops/" + file), "/tmp/" + file);
        return POSTGRES.execInContainer("psql", "-U", "postgres", "-d", "traceability_test", "-v", "ON_ERROR_STOP=1",
            "-v", "tenant_id=" + tenant, "-v", "commit=yes", "-f", "/tmp/" + file);
    }
}
