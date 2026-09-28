package com.traceability;

import com.traceability.notifications.EmailGateway;
import com.traceability.tenancy.TenantAwareDataSource;
import com.traceability.tenancy.TenantContext;
import org.jobrunr.scheduling.JobScheduler;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Meta signup attribution (V116 tenant_ad_attribution), Build B.
 *  - stored with the signup, validated and capped; bad values become null, never a failed signup
 *  - @tracedtech.com signups and signups with no ad signal store nothing
 *  - an insert failure is contained by a savepoint (tenant + user still created)
 *  - RLS as app_user: own row visible (positive control), other tenant / no GUC → 0 rows,
 *    cross-tenant INSERT refused, DELETE revoked
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SignupAdAttributionTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("traceability")
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
    @Autowired JdbcTemplate jdbc;   // postgres (BYPASSRLS) — verification only
    @MockBean EmailGateway emailGateway;
    @MockBean JobScheduler jobScheduler;

    JdbcTemplate appUserJdbc;
    TransactionTemplate appUserTx;

    static final String FBP = "fb.1.1727500000000.1234567890";
    static final String FBC = "fb.1.1727500000000.IwAR0abc-_XYZ";
    static final String UA  = "Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X)";

    @BeforeAll
    void appUser() {
        TenantAwareDataSource ds = new TenantAwareDataSource(
                new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "app_user", "testpw"));
        appUserJdbc = new JdbcTemplate(ds);
        appUserTx = new TransactionTemplate(new DataSourceTransactionManager(ds));
    }

    private ResponseEntity<String> signup(String email, Object attribution) {
        Map<String, Object> body = new HashMap<>();
        body.put("tenantName", "Attr Co");
        body.put("name", "Owner");
        body.put("email", email);
        body.put("phone", "01012345678");
        body.put("password", "password99");
        body.put("consent", true);
        if (attribution != null) body.put("attribution", attribution);
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.set(HttpHeaders.USER_AGENT, UA);
        RestTemplate rest = new RestTemplate();
        rest.setErrorHandler(new org.springframework.web.client.DefaultResponseErrorHandler() {
            @Override public boolean hasError(org.springframework.http.client.ClientHttpResponse r) { return false; }
        });
        return rest.postForEntity("http://localhost:" + port + "/api/v1/auth/signup",
                new HttpEntity<>(body, h), String.class);
    }

    private static String uniq(String domain) { return "a" + UUID.randomUUID().toString().substring(0, 8) + "@" + domain; }

    private UUID tenantOf(String email) {
        return jdbc.queryForObject("SELECT tenant_id FROM users WHERE email = ?", UUID.class, email);
    }

    private List<Map<String, Object>> rowsFor(UUID tenantId) {
        return jdbc.queryForList("SELECT * FROM tenant_ad_attribution WHERE tenant_id = ?", tenantId);
    }

    @Test
    void validAttributionIsStoredWithIpAndUserAgent() {
        String email = uniq("shop.test");
        ResponseEntity<String> res = signup(email, Map.of(
                "fbp", FBP, "fbc", FBC, "fbclid", "IwAR0abc-_XYZ",
                "utmSource", "facebook", "utmMedium", "paid", "utmCampaign", "launch",
                "utmTerm", "t", "utmContent", "c"));
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        List<Map<String, Object>> rows = rowsFor(tenantOf(email));
        assertThat(rows).hasSize(1);
        Map<String, Object> row = rows.get(0);
        assertThat(row.get("fbp")).isEqualTo(FBP);
        assertThat(row.get("fbc")).isEqualTo(FBC);
        assertThat(row.get("fbclid")).isEqualTo("IwAR0abc-_XYZ");
        assertThat(row.get("utm_source")).isEqualTo("facebook");
        assertThat(row.get("utm_medium")).isEqualTo("paid");
        assertThat(row.get("utm_campaign")).isEqualTo("launch");
        assertThat(row.get("utm_term")).isEqualTo("t");
        assertThat(row.get("utm_content")).isEqualTo("c");
        assertThat((String) row.get("client_ip")).isIn("127.0.0.1", "0:0:0:0:0:0:0:1");
        assertThat(row.get("client_user_agent")).isEqualTo(UA);
        assertThat(row.get("captured_at")).isNotNull();
        assertThat(row.get("connected_event_sent_at")).isNull();
    }

    @Test
    void malformedValuesAreDroppedOrCappedAndNeverFailTheSignup() {
        String email = uniq("shop.test");
        Map<String, Object> junk = new HashMap<>();
        junk.put("fbp", "not-a-cookie");                    // bad format → null
        junk.put("fbc", 12345);                             // not a string → null
        junk.put("fbclid", "has spaces <script>");          // bad chars → null
        junk.put("utmSource", "x".repeat(5000));            // capped to 200
        junk.put("utmMedium", "  \u0000\u0007  ");          // control chars only → null
        junk.put("utmCampaign", Map.of("nested", "object")); // not a string → null
        ResponseEntity<String> res = signup(email, junk);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        Map<String, Object> row = rowsFor(tenantOf(email)).get(0);
        assertThat(row.get("fbp")).isNull();
        assertThat(row.get("fbc")).isNull();
        assertThat(row.get("fbclid")).isNull();
        assertThat((String) row.get("utm_source")).hasSize(200);
        assertThat(row.get("utm_medium")).isNull();
        assertThat(row.get("utm_campaign")).isNull();
    }

    @Test
    void nonObjectAttributionIsIgnored() {
        for (Object weird : List.of("a string", 42, List.of("x"))) {
            String email = uniq("shop.test");
            assertThat(signup(email, weird).getStatusCode()).as("attribution=%s", weird).isEqualTo(HttpStatus.CREATED);
            assertThat(rowsFor(tenantOf(email))).isEmpty();
        }
    }

    @Test
    void noAdSignalStoresNothing() {
        String email = uniq("shop.test");
        assertThat(signup(email, null).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(rowsFor(tenantOf(email))).as("IP/UA alone are never stored").isEmpty();
    }

    @Test
    void tracedtechEmailsStoreNothing() {
        String email = uniq("TracedTech.com");
        assertThat(signup(email, Map.of("fbp", FBP, "utmSource", "facebook")).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        assertThat(rowsFor(tenantOf(email))).isEmpty();
    }

    @Test
    void attributionInsertFailureStillCreatesTheAccount() {
        jdbc.execute("""
                CREATE OR REPLACE FUNCTION test_fail_attr() RETURNS trigger LANGUAGE plpgsql AS
                $$ BEGIN RAISE EXCEPTION 'forced attribution failure'; END $$""");
        jdbc.execute("CREATE TRIGGER test_fail_attr BEFORE INSERT ON tenant_ad_attribution " +
                     "FOR EACH ROW EXECUTE FUNCTION test_fail_attr()");
        try {
            String email = uniq("shop.test");
            assertThat(signup(email, Map.of("fbp", FBP)).getStatusCode()).isEqualTo(HttpStatus.CREATED);
            UUID tenantId = tenantOf(email);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM tenants WHERE id = ?", Integer.class, tenantId)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM locations WHERE tenant_id = ?", Integer.class, tenantId)).isEqualTo(1);
            assertThat(rowsFor(tenantId)).isEmpty();
        } finally {
            jdbc.execute("DROP TRIGGER test_fail_attr ON tenant_ad_attribution");
            jdbc.execute("DROP FUNCTION test_fail_attr()");
        }
    }

    @Test
    void rlsAsAppUser_ownRowOnly_crossTenantInsertRefused_deleteRevoked() {
        String emailA = uniq("shop.test");
        String emailB = uniq("shop.test");
        signup(emailA, Map.of("fbp", FBP));
        signup(emailB, Map.of("fbp", FBP));
        UUID a = tenantOf(emailA);
        UUID b = tenantOf(emailB);
        String countSql = "SELECT count(*) FROM tenant_ad_attribution";

        // Positive control: tenant A's GUC sees exactly A's row.
        Integer ownRows = TenantContext.runAs(a, () ->
                appUserTx.execute(s -> appUserJdbc.queryForObject(countSql, Integer.class)));
        assertThat(ownRows).isEqualTo(1);
        UUID seen = TenantContext.runAs(a, () ->
                appUserTx.execute(s -> appUserJdbc.queryForObject("SELECT tenant_id FROM tenant_ad_attribution", UUID.class)));
        assertThat(seen).isEqualTo(a);

        // Other tenant's row is invisible.
        Integer bSeesA = TenantContext.runAs(b, () -> appUserTx.execute(s -> appUserJdbc.queryForObject(
                "SELECT count(*) FROM tenant_ad_attribution WHERE tenant_id = ?", Integer.class, a)));
        assertThat(bSeesA).isZero();

        // No GUC → fail closed.
        TenantContext.clear();
        Integer noGuc = appUserTx.execute(s -> appUserJdbc.queryForObject(countSql, Integer.class));
        assertThat(noGuc).isZero();

        // Cross-tenant INSERT refused by WITH CHECK.
        String emailC = uniq("shop.test");
        signup(emailC, null);
        UUID c = tenantOf(emailC);
        assertThatThrownBy(() -> TenantContext.runAs(a, () -> appUserTx.execute(s ->
                appUserJdbc.update("INSERT INTO tenant_ad_attribution (tenant_id, fbp) VALUES (?, ?)", c, FBP))))
                .isInstanceOf(DataAccessException.class);

        // DELETE revoked for app_user.
        assertThatThrownBy(() -> TenantContext.runAs(a, () -> appUserTx.execute(s ->
                appUserJdbc.update("DELETE FROM tenant_ad_attribution WHERE tenant_id = ?", a))))
                .isInstanceOf(DataAccessException.class);
        assertThat(rowsFor(a)).hasSize(1);
    }
}
