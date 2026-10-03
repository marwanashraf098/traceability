package com.traceability;

import com.traceability.identity.JwtService;
import com.traceability.identity.model.SignupRequest;
import com.traceability.identity.model.TokenResponse;
import com.traceability.integrations.bosta.BostaGateway;
import com.traceability.notifications.EmailGateway;
import com.traceability.tenancy.TenantAwareDataSource;
import com.traceability.tenancy.TenantContext;
import org.jobrunr.scheduling.JobScheduler;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.dao.DataAccessException;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Review mode S1 — the simulated-courier flag (tenant_courier_simulation, V130).
 *
 *   cs1 connect / sync / visibility-check on a simulated tenant → 409 COURIER_SIMULATED; Bosta
 *       gateway never called, no job enqueued, no courier_accounts row
 *   cs2 a courier_accounts row for a simulated tenant is refused by the DB (check_violation) —
 *       as postgres AND as app_user with the tenant's GUC set
 *   cs3 flagging a tenant that already has a courier row is refused (check_violation)
 *   cs4 /connections on a simulated tenant: bosta connected + simulated; disconnected Shopify
 *       rows hidden
 *   cs5 /connections on a real tenant: simulated=false; its disconnected row still shown
 *   cs6 app_user (RLS, no BYPASSRLS): reads its own flag row only; INSERT/UPDATE/DELETE denied
 *   cs7 onboarding: the Bosta step is done for a simulated tenant, open for a real one
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CourierSimulationTest {

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

    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;
    @Autowired JdbcTemplate     jdbc;
    @Autowired JwtService       jwtService;

    @MockBean BostaGateway bostaGateway;
    @MockBean JobScheduler jobScheduler;
    @MockBean EmailGateway emailGateway;

    private JdbcTemplate        appUserJdbc;
    private TransactionTemplate appUserTx;
    private int seq = 0;

    @BeforeAll
    void appUser() {
        // TestSetup (ApplicationReadyEvent) has already set app_user's password to 'testpw'.
        TenantAwareDataSource ds = new TenantAwareDataSource(
            new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "app_user", "testpw"));
        appUserJdbc = new JdbcTemplate(ds);
        appUserTx   = new TransactionTemplate(new DataSourceTransactionManager(ds));
    }

    @AfterEach
    void resetMocks() {
        reset(bostaGateway, jobScheduler);
        TenantContext.clear();
    }

    // -----------------------------------------------------------------------
    @Test
    void cs1_simulatedTenant_connectSyncVisibility_refused_noBostaCall_noRow() {
        Owner o = signup("cs1");
        simulate(o.tenantId());
        clearInvocations(bostaGateway, jobScheduler);   // signup itself enqueues jobs

        var connect = post(o, "/api/v1/bosta/connect", Map.of("apiKey", "some-real-looking-key"));
        assertThat(connect.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(connect.getBody()).containsEntry("code", "COURIER_SIMULATED");

        var sync = post(o, "/api/v1/bosta/sync", Map.of());
        assertThat(sync.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(sync.getBody()).containsEntry("code", "COURIER_SIMULATED");

        var vis = post(o, "/api/v1/bosta/visibility-check", Map.of());
        assertThat(vis.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(vis.getBody()).containsEntry("code", "COURIER_SIMULATED");

        verifyNoInteractions(bostaGateway);
        verifyNoInteractions(jobScheduler);
        assertThat(courierRows(o.tenantId())).isZero();
    }

    @Test
    void cs2_courierRowForSimulatedTenant_refusedByDb_asPostgresAndAppUser() {
        Owner o = signup("cs2");
        simulate(o.tenantId());

        assertThatThrownBy(() -> insertCourier(jdbc, o.tenantId()))
            .isInstanceOf(DataAccessException.class)
            .satisfies(e -> assertThat(sqlState(e)).isEqualTo("23514"));

        TenantContext.set(o.tenantId());
        assertThatThrownBy(() -> appUserTx.executeWithoutResult(s -> insertCourier(appUserJdbc, o.tenantId())))
            .isInstanceOf(DataAccessException.class)
            .as("the trigger, not RLS, refuses it: the tenant's own flag row is visible under its GUC")
            .satisfies(e -> assertThat(sqlState(e)).isEqualTo("23514"));
        TenantContext.clear();

        assertThat(courierRows(o.tenantId())).isZero();
    }

    @Test
    void cs3_flaggingATenantWithACourierRow_refused() {
        Owner o = signup("cs3");
        insertCourier(jdbc, o.tenantId());

        assertThatThrownBy(() -> simulate(o.tenantId()))
            .isInstanceOf(DataAccessException.class)
            .satisfies(e -> assertThat(sqlState(e)).isEqualTo("23514"));
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM tenant_courier_simulation WHERE tenant_id = ?", Integer.class, o.tenantId()))
            .isZero();
    }

    @Test
    @SuppressWarnings("unchecked")
    void cs4_connections_simulatedTenant_bostaSimulated_disconnectedShopifyRowsHidden() {
        Owner o = signup("cs4");
        insertStore(o.tenantId(), "cs4-placeholder-" + seq + ".myshopify.invalid", "disconnected");
        simulate(o.tenantId());

        Map<String, Object> body = getConnections(o);
        Map<String, Object> bosta   = (Map<String, Object>) body.get("bosta");
        Map<String, Object> shopify = (Map<String, Object>) body.get("shopify");

        assertThat(bosta).containsEntry("connected", true).containsEntry("simulated", true);
        assertThat(shopify.get("shopDomain")).as("placeholder hidden").isNull();
        assertThat(shopify.get("storeId")).isNull();
        assertThat(shopify).containsEntry("connected", false);
        verifyNoInteractions(bostaGateway);
    }

    @Test
    @SuppressWarnings("unchecked")
    void cs5_connections_realTenant_notSimulated_disconnectedRowStillShown() {
        Owner o = signup("cs5");
        String shop = "cs5-shop-" + seq + ".myshopify.com";
        insertStore(o.tenantId(), shop, "disconnected");

        Map<String, Object> body = getConnections(o);
        Map<String, Object> bosta   = (Map<String, Object>) body.get("bosta");
        Map<String, Object> shopify = (Map<String, Object>) body.get("shopify");

        assertThat(bosta).containsEntry("connected", false).containsEntry("simulated", false);
        assertThat(shopify.get("shopDomain")).as("the linked shop stays visible").isEqualTo(shop);
        assertThat(shopify).containsEntry("status", "disconnected");
    }

    @Test
    void cs6_appUser_readsOwnFlagOnly_cannotWrite() {
        Owner a = signup("cs6a");
        Owner b = signup("cs6b");
        simulate(a.tenantId());
        simulate(b.tenantId());

        TenantContext.set(a.tenantId());
        List<UUID> visible = appUserTx.execute(s ->
            appUserJdbc.queryForList("SELECT tenant_id FROM tenant_courier_simulation", UUID.class));
        assertThat(visible).containsExactly(a.tenantId());

        for (String sql : List.of(
                "INSERT INTO tenant_courier_simulation (tenant_id) VALUES ('" + a.tenantId() + "')",
                "UPDATE tenant_courier_simulation SET note = 'x'",
                "DELETE FROM tenant_courier_simulation")) {
            assertThatThrownBy(() -> appUserTx.executeWithoutResult(s -> appUserJdbc.update(sql)))
                .as(sql)
                .isInstanceOf(DataAccessException.class)
                .satisfies(e -> assertThat(sqlState(e)).isEqualTo("42501"));
        }
        TenantContext.clear();

        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM tenant_courier_simulation WHERE tenant_id IN (?, ?)",
            Integer.class, a.tenantId(), b.tenantId())).isEqualTo(2);
    }

    @Test
    @SuppressWarnings("unchecked")
    void cs7_onboarding_bostaStepDoneForSimulated_openForReal() {
        Owner sim  = signup("cs7s");
        Owner real = signup("cs7r");
        simulate(sim.tenantId());

        assertThat(bostaStepDone(sim)).isTrue();
        assertThat(bostaStepDone(real)).isFalse();
    }

    // ---- helpers ------------------------------------------------------------

    private record Owner(String token, UUID tenantId) {}

    private Owner signup(String tag) {
        String u = "cs_" + tag + "_" + (++seq) + "_" + System.nanoTime();
        var req = new SignupRequest("CourierSim " + tag, u, u + "@test.com", "01012345678", "password99", true);
        var resp = rest.postForEntity(base() + "/api/v1/auth/signup", req, TokenResponse.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String token = resp.getBody().accessToken();
        return new Owner(token, UUID.fromString((String) jwtService.verify(token).getClaim("tenant")));
    }

    private void simulate(UUID tenantId) {
        jdbc.update("INSERT INTO tenant_courier_simulation (tenant_id, note) VALUES (?, 'test')", tenantId);
    }

    private static void insertCourier(JdbcTemplate j, UUID tenantId) {
        j.update("INSERT INTO courier_accounts (tenant_id, provider, api_key_encrypted, webhook_secret, status) " +
                 "VALUES (?, 'bosta', 'enc', 'hash-" + UUID.randomUUID() + "', 'active')", tenantId);
    }

    private void insertStore(UUID tenantId, String shop, String status) {
        jdbc.update("INSERT INTO stores (tenant_id, shop_domain, platform, status) " +
                    "VALUES (?, ?, 'shopify', ?::store_status)", tenantId, shop, status);
    }

    private int courierRows(UUID tenantId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM courier_accounts WHERE tenant_id = ?", Integer.class, tenantId);
    }

    @SuppressWarnings("rawtypes")
    private ResponseEntity<Map> post(Owner o, String path, Object body) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(o.token());
        h.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange(base() + path, HttpMethod.POST, new HttpEntity<>(body, h), Map.class);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getConnections(Owner o) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(o.token());
        var resp = rest.exchange(base() + "/api/v1/connections", HttpMethod.GET, new HttpEntity<>(h), Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        return resp.getBody();
    }

    @SuppressWarnings("unchecked")
    private boolean bostaStepDone(Owner o) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(o.token());
        var resp = rest.exchange(base() + "/api/v1/onboarding/status", HttpMethod.GET, new HttpEntity<>(h), Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<String, Object>> steps = (List<Map<String, Object>>) resp.getBody().get("steps");
        return steps.stream().filter(s -> "connect_bosta".equals(s.get("key")))
            .map(s -> Boolean.TRUE.equals(s.get("done"))).findFirst().orElseThrow();
    }

    private static String sqlState(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql && sql.getSQLState() != null) return sql.getSQLState();
        }
        return null;
    }

    private String base() { return "http://localhost:" + port; }
}
