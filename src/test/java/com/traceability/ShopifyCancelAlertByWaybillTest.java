package com.traceability;

import com.traceability.inventory.ExceptionService;
import com.traceability.inventory.ShipmentLinkService;
import com.traceability.tenancy.TenantAwareDataSource;
import com.traceability.tenancy.TenantContext;
import org.jobrunr.scheduling.JobScheduler;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * "Shopify cancel vs in flight" (HIGH) decided from the order's NEWEST LIVE forward waybill, never the stored
 * 'awaiting_pickup' (2026-11-04). Every order here carries shopify_cancel_requested_at (the webhook's 409 stamp).
 * Alerts are read through an app_user ExceptionService (RLS on).
 *
 *   c1  waybill still pre-pickup (created, never moved)          → shopify_cancel_vs_inflight
 *   c2  waybill with the courier                                  → shopify_cancel_vs_inflight
 *   c3  waybill delivered                                         → cancelled_but_delivered, NOT cancel-vs-in-flight
 *   c4  waybill returned / returning                              → neither
 *   c5  stored status 'new' (not awaiting_pickup), waybill moving → shopify_cancel_vs_inflight
 *   c6  no live forward waybill (none, or deleted in Bosta)       → neither
 *   x1  cross-tenant as app_user: tenant B sees none of A's; A sees its own (positive control)
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ShopifyCancelAlertByWaybillTest {

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

    static final String IN_FLIGHT = "shopify_cancel_vs_inflight";
    static final String DELIVERED = "cancelled_but_delivered";

    @Autowired JdbcTemplate jdbc;   // postgres — fixtures only
    @MockBean  JobScheduler jobScheduler;

    ExceptionService    appExceptions;
    TransactionTemplate appTx;
    final AtomicLong seq = new AtomicLong(7_770_000_000L - 1_000_000L);

    record T(UUID tenant, UUID store) {}

    @BeforeAll
    void appUser() {
        TenantAwareDataSource ds = new TenantAwareDataSource(
            new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "app_user", "testpw"));
        JdbcTemplate appJdbc = new JdbcTemplate(ds);
        appTx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        ShipmentLinkService link = new ShipmentLinkService(appJdbc, null, null, null, null, null, null, null, null);
        appExceptions = new ExceptionService(appJdbc, Clock.systemUTC(), link);
    }

    @AfterEach
    void clear() { TenantContext.clear(); }

    @Test
    void c1_prePickupWaybill_cancelVsInFlight() {
        T t = tenant("c1");
        UUID o = flagged(t, "awaiting_pickup");
        String tn = leg(t, o, "created");
        assertThat(alerts(t, IN_FLIGHT)).singleElement()
            .satisfies(a -> assertThat(a).containsEntry("order_id", o).containsEntry("tracking_number", tn)
                .containsEntry("severity", "HIGH"));
        assertThat(alerts(t, DELIVERED)).isEmpty();
    }

    @Test
    void c2_withTheCourier_cancelVsInFlight() {
        T t = tenant("c2");
        UUID o = flagged(t, "awaiting_pickup");
        leg(t, o, "with_courier");
        assertThat(alerts(t, IN_FLIGHT)).extracting(a -> a.get("order_id")).containsExactly(o);
    }

    @Test
    void c3_delivered_isCancelledButDelivered_notCancelVsInFlight() {
        T t = tenant("c3");
        UUID o = flagged(t, "awaiting_pickup");
        String tn = leg(t, o, "delivered");
        assertThat(alerts(t, IN_FLIGHT)).isEmpty();
        assertThat(alerts(t, DELIVERED)).singleElement()
            .satisfies(a -> assertThat(a).containsEntry("order_id", o).containsEntry("tracking_number", tn)
                .containsEntry("subject_key", "cancelled_but_delivered:order:" + o));
    }

    @Test
    void c4_returnedOrReturning_neither() {
        T t = tenant("c4");
        leg(t, flagged(t, "awaiting_pickup"), "returned");
        leg(t, flagged(t, "awaiting_pickup"), "returning");
        assertThat(alerts(t, IN_FLIGHT)).isEmpty();
        assertThat(alerts(t, DELIVERED)).isEmpty();
    }

    @Test
    void c5_storedStatusNotAwaitingPickup_stillDecidedByTheWaybill() {
        T t = tenant("c5");
        UUID o = flagged(t, "new");   // shipped without Traced's pack flow: stored status never moved
        leg(t, o, "with_courier");
        assertThat(alerts(t, IN_FLIGHT)).extracting(a -> a.get("order_id")).containsExactly(o);
    }

    @Test
    void c6_noLiveForwardWaybill_neither() {
        T t = tenant("c6");
        flagged(t, "delivered");                                     // self-pickup handover: no forward leg
        UUID deleted = flagged(t, "awaiting_pickup");
        String tn = leg(t, deleted, "created");
        jdbc.update("UPDATE shipments SET provider_deleted_at = now(), provider_deleted_reason = 'deleted_in_bosta' WHERE tracking_number = ?", tn);
        assertThat(alerts(t, IN_FLIGHT)).isEmpty();
        assertThat(alerts(t, DELIVERED)).isEmpty();
    }

    @Test
    void x1_crossTenant_appUser_positiveControl() {
        T a = tenant("xa"), b = tenant("xb");
        UUID oa = flagged(a, "awaiting_pickup");
        leg(a, oa, "with_courier");
        UUID od = flagged(a, "awaiting_pickup");
        leg(a, od, "delivered");
        assertThat(alerts(b, IN_FLIGHT)).isEmpty();
        assertThat(alerts(b, DELIVERED)).isEmpty();
        assertThat(alerts(a, IN_FLIGHT)).as("positive control").extracting(x -> x.get("order_id")).containsExactly(oa);
        assertThat(alerts(a, DELIVERED)).as("positive control").extracting(x -> x.get("order_id")).containsExactly(od);
    }

    // ── fixtures ──────────────────────────────────────────────────────────────────────────────────────────────

    private List<Map<String, Object>> alerts(T t, String type) {
        return TenantContext.runAs(t.tenant(), () -> appTx.execute(s -> appExceptions.detectAllOpen())).stream()
            .filter(a -> type.equals(a.get("type"))).toList();
    }

    private T tenant(String name) {
        UUID tenant = UUID.randomUUID(), store = UUID.randomUUID();
        String tag = name + "-" + tenant.toString().substring(0, 6);
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?)", tenant, "Tenant " + tag);
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status) VALUES (?, ?, 'shopify', ?, 'disconnected')",
            store, tenant, tag + ".myshopify.com");
        return new T(tenant, store);
    }

    /** An order with the Shopify-cancel stamp the webhook writes on a 409. */
    private UUID flagged(T t, String status) {
        return jdbc.queryForObject("INSERT INTO orders (tenant_id, store_id, external_id, number, status, payment_method, " +
            "placed_at, shopify_cancel_requested_at) VALUES (?, ?, ?, ?, ?::order_status, 'cod', now(), now()) RETURNING id",
            UUID.class, t.tenant(), t.store(), "EXT-" + UUID.randomUUID(), "#SC" + seq.incrementAndGet(), status);
    }

    private String leg(T t, UUID order, String state) {
        String tn = String.valueOf(seq.incrementAndGet());
        jdbc.update("INSERT INTO shipments (tenant_id, order_id, provider, tracking_number, internal_state, shipment_leg) " +
            "VALUES (?, ?, 'bosta', ?, ?::shipment_internal_state, 'forward')", t.tenant(), order, tn, state);
        return tn;
    }
}
