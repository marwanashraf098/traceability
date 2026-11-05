package com.traceability;

import com.traceability.identity.JwtService;
import com.traceability.integrations.shopify.ShopifyGateway;
import com.traceability.integrations.shopify.ShopifyTokenProvider;
import org.jobrunr.scheduling.JobScheduler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
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
 * Returns portal fees F1 — hidden by default ({@code traced.portal-fees.settings-enabled} unset): until F2 charges
 * fees, no merchant can switch one on. GET answers visible:false; every write and the area list answer 404 and
 * write nothing (no row, no audit).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class CustomerFeeSettingsHiddenTest {

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
        r.add("analytics.settlement.refresh-enabled", () -> "false");
    }

    static final String BASE = "/api/v1/tenant/portal-settings/customer-fees";

    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;
    @Autowired JwtService jwt;
    @Autowired JdbcTemplate jdbc;
    @MockBean ShopifyGateway shopifyGateway;
    @MockBean ShopifyTokenProvider tokenProvider;
    @MockBean JobScheduler jobScheduler;

    @Test
    void h1_hidden_getSaysSo_writesAre404_nothingWritten() {
        UUID tenant = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, 'F1 hidden')", tenant);
        UUID owner = jdbc.queryForObject("INSERT INTO users (tenant_id, name, email, password_hash, role, active) " +
            "VALUES (?, 'o', ?, 'x', 'owner'::user_role, true) RETURNING id", UUID.class, tenant, "o-" + tenant + "@test.com");
        jdbc.update("INSERT INTO bosta_districts (district_id, city_id, city_name, district_name) " +
            "VALUES ('H1-D', 'H1-C', 'Cairo', 'Nasr City') ON CONFLICT DO NOTHING");
        String token = jwt.issueAccessToken(owner, tenant, "owner");

        ResponseEntity<Map> get = call(token, BASE, HttpMethod.GET, null);
        assertThat(get.getStatusCode().value()).isEqualTo(200);
        assertThat(get.getBody()).containsEntry("visible", false);

        record W(String path, HttpMethod m, Object body) {}
        for (W w : List.of(
                new W("/rules/refund", HttpMethod.PUT, Map.of("enabled", true, "mode", "fixed", "amount", 50)),
                new W("/merchant-fault-reasons", HttpMethod.PUT, Map.of("reasons", List.of("other"))),
                new W("/exchange-pricing", HttpMethod.PUT, Map.of("anyProduct", true)),
                new W("/area-overrides", HttpMethod.POST, Map.of("kind", "refund", "cityId", "H1-C", "mode", "fixed", "amount", 1)),
                new W("/area-overrides/" + UUID.randomUUID(), HttpMethod.PUT, Map.of("mode", "fixed", "amount", 1)),
                new W("/area-overrides/" + UUID.randomUUID(), HttpMethod.DELETE, null),
                new W("/areas", HttpMethod.GET, null))) {
            assertThat(call(token, BASE + w.path(), w.m(), w.body()).getStatusCode().value()).as(w.toString()).isEqualTo(404);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM portal_fee_rules WHERE tenant_id = ?", Integer.class, tenant)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM portal_fee_area_overrides WHERE tenant_id = ?", Integer.class, tenant)).isZero();
        assertThat(jdbc.queryForObject("SELECT portal_exchange_any_product FROM tenants WHERE id = ?", Boolean.class, tenant)).isFalse();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE tenant_id = ?", Integer.class, tenant)).isZero();
    }

    @SuppressWarnings("rawtypes")
    private ResponseEntity<Map> call(String token, String path, HttpMethod m, Object body) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        h.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange("http://localhost:" + port + path, m, new HttpEntity<>(body, h), Map.class);
    }
}
