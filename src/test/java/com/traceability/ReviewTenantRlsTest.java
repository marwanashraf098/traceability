package com.traceability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.traceability.account.AuditService;
import com.traceability.account.UserService;
import com.traceability.identity.AuthService;
import com.traceability.identity.JwtService;
import com.traceability.integrations.bosta.BostaAwbService;
import com.traceability.integrations.bosta.BostaGateway;
import com.traceability.inventory.FulfillService;
import com.traceability.inventory.InventoryLedger;
import com.traceability.inventory.PackPrintBatchService;
import com.traceability.inventory.PackPrintBatchStore;
import com.traceability.inventory.PickupSessionService;
import com.traceability.inventory.ShopifyInventoryService;
import com.traceability.portal.PickupBookingScheduler;
import com.traceability.portal.ReturnRequestService;
import com.traceability.review.OpsReviewTenantController;
import com.traceability.review.ReviewTenantException;
import com.traceability.review.ReviewTenantSeeder;
import com.traceability.review.ReviewTenantService;
import com.traceability.security.EncryptionService;
import com.traceability.tenancy.TenantAwareDataSource;
import com.traceability.tenancy.TenantContext;
import org.jobrunr.scheduling.JobScheduler;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Review mode S5 — the seeder under REAL RLS, as in prod. The ops seed request carries no JWT, so in
 * prod every statement runs as app_user (the @Primary TenantAwareDataSource) with no ambient tenant:
 * each step sets the tenant itself. Here ReviewTenantService.seed() and the whole service chain it
 * drives (InventoryLedger, FulfillService, PackPrintBatchService → BostaAwbService, PickupSessionService,
 * ReturnRequestService) are wired by hand over an app_user TenantAwareDataSource — the established
 * *RlsTest pattern (an app_user-primary Spring context can't boot: ShopifyConnectAmbientContextTest) —
 * and wrapped in transaction proxies on the app_user transaction manager so their @Transactional
 * boundaries behave exactly as the Spring beans' do in prod. The only stand-ins: BostaGateway (never
 * called — asserted) and the Shopify-inventory hook, which in prod is @Async after commit and a no-op
 * for non-exchange orders.
 *
 *   o4c a step that throws → logged with the step and order number, rethrown; the partial fixture
 *       touches no other tenant's rows and no global table; the next seed refuses it (FIXTURE_EXISTS)
 *   o4b the same whole-fixture assertions as ReviewTenantTest.o4, seeded over app_user + RLS; app_user
 *       with no tenant / another tenant sees none of it
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@ExtendWith(OutputCaptureExtension.class)
class ReviewTenantRlsTest {

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

    @Autowired JdbcTemplate        jdbc;          // postgres — setup and assertions only
    @Autowired ReviewTenantService postgresReviewService;   // step A (not under test here)
    @Autowired AuthService         auth;
    @Autowired JwtService          jwt;
    @Autowired UserService         users;
    @Autowired EncryptionService   encryption;
    @Autowired ObjectMapper        mapper;
    @Value("${shopify.app-url}") String appUrl;
    @MockBean  BostaGateway        bostaGateway;
    @MockBean  JobScheduler        jobScheduler;

    private JdbcTemplate               appJdbc;
    private PlatformTransactionManager appTxm;
    private InventoryLedger            ledger;
    private FulfillService             fulfill;
    private PackPrintBatchService      printBatches;
    private PickupSessionService       pickups;
    private ReturnRequestService       returnRequests;

    private UUID failTenant;
    private UUID reviewTenant;

    @BeforeAll
    void wire() {
        // TestSetup (ApplicationReadyEvent) has already set app_user's test password.
        TenantAwareDataSource appDs = new TenantAwareDataSource(
            new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "app_user", "testpw"));
        appJdbc = new JdbcTemplate(appDs);
        appTxm  = new DataSourceTransactionManager(appDs);

        ledger = tx(new InventoryLedger(appJdbc));
        fulfill = tx(new FulfillService(appJdbc, ledger, tx(new AuditService(appJdbc, mapper)), 30));
        BostaAwbService awb = tx(new BostaAwbService(appJdbc, appTxm, bostaGateway, encryption));
        printBatches = tx(new PackPrintBatchService(tx(new PackPrintBatchStore(appJdbc, 30)), awb));
        pickups = tx(new PickupSessionService(appJdbc, appTxm, ledger, mock(ShopifyInventoryService.class)));
        returnRequests = tx(new ReturnRequestService(appJdbc, mock(PickupBookingScheduler.class)));

        failTenant   = createFlagged("review-fail@tracedtech.com");
        reviewTenant = createFlagged("reviewer@tracedtech.com");
    }

    // ── o4c ──────────────────────────────────────────────────────────────────

    @Test @Order(1)
    void o4c_failingStep_loggedWithStepAndOrder_rethrown_partialStaysInItsTenant(CapturedOutput output) {
        PickupSessionService failingPickups = mock(PickupSessionService.class);
        when(failingPickups.openSession(any(), any(), any(), any(), any())).thenReturn(UUID.randomUUID());
        IllegalStateException boom = new IllegalStateException("simulated pickup failure");
        when(failingPickups.scan(any(), any(), any(), anyString())).thenThrow(boom);

        try {
            Map<String, Integer> before = otherRowsSnapshot(failTenant);
            assertThatThrownBy(() -> service(failingPickups).seed(failTenant)).isSameAs(boom);
            assertThat(output.getAll())
                .contains("Review seed FAILED at step 'pickup scan #R1004' (tenant " + failTenant + ")")
                .contains("PARTIAL");
            assertThat(TenantContext.get()).as("no tenant left on the thread").isNull();

            assertThat(ReviewFixtureAssertions.count(jdbc, "orders", failTenant)).as("the partial fixture").isEqualTo(6);
            assertThat(otherRowsSnapshot(failTenant)).as("no other tenant's rows, no global table touched")
                .isEqualTo(before);
            assertThatThrownBy(() -> service(pickups).seed(failTenant))
                .isInstanceOfSatisfying(ReviewTenantException.class, e -> assertThat(e.errorCode()).isEqualTo("FIXTURE_EXISTS"));
        } finally {
            // Test-only fix-up: the placeholder shop_domain is unique across the DB (one review tenant per
            // DB), so free it for o4b's tenant. In prod a partial fixture is cleared by the reset script.
            jdbc.update("UPDATE stores SET shop_domain = 'failed-run.invalid' WHERE tenant_id = ?", failTenant);
        }
    }

    // ── o4b ──────────────────────────────────────────────────────────────────

    @Test @Order(2)
    void o4b_seedOverAppUserWithRls_buildsTheWholeFixture() {
        TenantContext.clear();   // the ops request has no JWT → no ambient tenant
        service(pickups).seed(reviewTenant);

        ReviewFixtureAssertions.assertWholeFixture(jdbc, reviewTenant);
        verifyNoInteractions(bostaGateway);

        Integer noTenant = new TransactionTemplate(appTxm).execute(s -> appJdbc.queryForObject(
            "SELECT COUNT(*) FROM orders", Integer.class));
        assertThat(noTenant).as("app_user, no tenant: sees nothing").isZero();
        Integer otherTenant = TenantContext.runAs(failTenant, () -> new TransactionTemplate(appTxm).execute(s ->
            appJdbc.queryForObject("SELECT COUNT(*) FROM orders WHERE tenant_id = ?", Integer.class, reviewTenant)));
        assertThat(otherTenant).as("app_user as another tenant: sees none of it").isZero();
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private ReviewTenantService service(PickupSessionService pickupService) {
        ReviewTenantSeeder seeder = new ReviewTenantSeeder(appJdbc, appTxm, ledger, fulfill, printBatches,
            pickupService, returnRequests, appUrl);
        return new ReviewTenantService(auth, jwt, users, seeder, appJdbc, appTxm);
    }

    /** Step A through the real service (postgres datasource), then the flag row step B writes. */
    private UUID createFlagged(String email) {
        Map<String, Object> created = postgresReviewService.create(new OpsReviewTenantController.CreateRequest(
            "Review " + email, "App Reviewer", email, "01012345678", "pw-" + UUID.randomUUID(),
            "Review Worker", String.valueOf(1000 + new Random().nextInt(9000))));
        UUID tenant = UUID.fromString((String) created.get("tenantId"));
        jdbc.update("INSERT INTO tenant_courier_simulation (tenant_id, note) VALUES (?, 'review test')", tenant);
        return tenant;
    }

    /**
     * Row counts of everything that isn't {@code tenant}'s: other tenants' rows in every tenant-scoped
     * table, and every table without a tenant_id (JobRunr's own tables excluded — its server polls).
     */
    private Map<String, Integer> otherRowsSnapshot(UUID tenant) {
        Map<String, Integer> out = new TreeMap<>();
        List<Map<String, Object>> tables = jdbc.queryForList(
            "SELECT t.table_name, EXISTS (SELECT 1 FROM information_schema.columns c WHERE c.table_schema = 'public' " +
            "  AND c.table_name = t.table_name AND c.column_name = 'tenant_id') AS scoped " +
            "FROM information_schema.tables t WHERE t.table_schema = 'public' AND t.table_type = 'BASE TABLE' " +
            "AND t.table_name NOT LIKE 'jobrunr%'");
        for (Map<String, Object> t : tables) {
            String name = (String) t.get("table_name");
            boolean scoped = (Boolean) t.get("scoped");
            out.put(name, scoped
                ? jdbc.queryForObject("SELECT COUNT(*) FROM \"" + name + "\" WHERE tenant_id IS DISTINCT FROM ?", Integer.class, tenant)
                : jdbc.queryForObject("SELECT COUNT(*) FROM \"" + name + "\"", Integer.class));
        }
        return out;
    }

    /** The prod bean's @Transactional boundaries, on the app_user transaction manager. */
    @SuppressWarnings("unchecked")
    private <T> T tx(T target) {
        ProxyFactory pf = new ProxyFactory(target);
        pf.setProxyTargetClass(true);
        pf.addAdvice(new TransactionInterceptor(appTxm, new AnnotationTransactionAttributeSource()));
        return (T) pf.getProxy();
    }
}
