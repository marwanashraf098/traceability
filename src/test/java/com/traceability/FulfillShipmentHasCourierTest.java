package com.traceability;

import com.traceability.inventory.FulfillService;
import com.traceability.inventory.InventoryLedger;
import com.traceability.account.AuditService;
import com.traceability.integrations.bosta.AwbPrintResult;
import com.traceability.integrations.bosta.BostaAwbService;
import com.traceability.integrations.bosta.BostaGateway;
import com.traceability.integrations.bosta.NoBostaAccountException;
import com.traceability.security.EncryptionService;
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

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * FulfillService.getOrder()'s shipment_has_courier must mean "Print Waybill can succeed".
 *
 * Production bug (2026-09-23): the flag was derived from shipments.courier_account_id,
 * which no ingest path ever populates — every forward shipment in prod had it NULL, so
 * the flag was always false: Print Waybill hidden and Complete reachable without a print
 * on both pilots. It is now derived from the tenant's courier account using the exact
 * resolution BostaAwbService.printAwb() uses (provider='bosta', status='active').
 *
 * Every fixture here leaves shipments.courier_account_id NULL — the realistic prod shape.
 * getOrder() runs as app_user under the tenant GUC (RLS enforced on orders, shipments
 * and courier_accounts), via a FulfillService wired to the app_user datasource.
 *
 *   a) active Bosta account                   → true  (RED on the pre-fix predicate)
 *   b) no courier account (demo shape)        → false (positive control)
 *   c) disconnected / error account           → false, and printAwb() throws
 *                                               NoBostaAccountException for that tenant
 *   d) self-pickup / no forward shipment      → false
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FulfillShipmentHasCourierTest {

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

    @Autowired JdbcTemplate      jdbc;            // postgres — fixtures only
    @Autowired InventoryLedger   ledger;
    @Autowired AuditService      auditService;
    @Autowired BostaAwbService   awbService;
    @Autowired EncryptionService encryptionService;
    @MockBean  BostaGateway      bostaGateway;
    @MockBean  JobScheduler      jobScheduler;

    // app_user path: TenantAwareDataSource issues SET LOCAL app.current_tenant when a
    // transaction begins, so getOrder() inside appUserTx.execute() is RLS-scoped.
    private TransactionTemplate appUserTx;
    private FulfillService      appUserFulfill;

    @BeforeAll
    void setup() {
        DriverManagerDataSource rawDs = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), "app_user", "testpw");
        TenantAwareDataSource appDs = new TenantAwareDataSource(rawDs);
        appUserTx      = new TransactionTemplate(new DataSourceTransactionManager(appDs));
        appUserFulfill = new FulfillService(new JdbcTemplate(appDs), ledger, auditService, 30);
    }

    @AfterEach
    void clearContext() { TenantContext.clear(); }

    // ── a) active Bosta account → true ────────────────────────────────────────

    @Test
    void a_activeBostaAccount_hasCourierTrue_andPrintSucceeds() {
        UUID tenant = tenant("HasCourierActive");
        courierAccount(tenant, "active");
        UUID orderId = order(tenant, false);
        UUID shipmentId = forwardShipment(tenant, orderId, "2445296555");

        Map<String, Object> order = getOrderAsAppUser(tenant, orderId);
        assertThat(order.get("tracking_number")).isEqualTo("2445296555");
        assertThat(order.get("shipment_has_courier")).isEqualTo(true);

        // flag == print-can-succeed: the same tenant's print resolves an account and prints.
        when(bostaGateway.printMassAwb(anyString(), anyList(), anyString(), anyString()))
            .thenReturn(new AwbPrintResult("PDF".getBytes(), null));
        BostaAwbService.AwbBatchResult printed =
            awbService.printAwb(tenant, List.of(shipmentId), null, null);
        assertThat(printed.pdfBase64List()).hasSize(1);
        assertThat(printed.exceptions()).isEmpty();
    }

    // ── b) no courier account (demo shape) → false ────────────────────────────

    @Test
    void b_noCourierAccount_hasCourierFalse() {
        UUID tenant = tenant("HasCourierNone");
        UUID orderId = order(tenant, false);
        UUID shipmentId = forwardShipment(tenant, orderId, "2445296556");

        assertThat(getOrderAsAppUser(tenant, orderId).get("shipment_has_courier")).isEqualTo(false);
        assertThatThrownBy(() -> awbService.printAwb(tenant, List.of(shipmentId), null, null))
            .isInstanceOf(NoBostaAccountException.class);
    }

    // ── c) disconnected / error account → false, print cannot succeed ─────────

    @Test
    void c1_disconnectedAccount_hasCourierFalse_andPrintThrows() {
        assertInactiveAccountMatchesPrint("disconnected", "2445296557");
    }

    @Test
    void c2_errorAccount_hasCourierFalse_andPrintThrows() {
        assertInactiveAccountMatchesPrint("error", "2445296558");
    }

    private void assertInactiveAccountMatchesPrint(String status, String tracking) {
        UUID tenant = tenant("HasCourier-" + status);
        courierAccount(tenant, status);
        UUID orderId = order(tenant, false);
        UUID shipmentId = forwardShipment(tenant, orderId, tracking);

        assertThat(getOrderAsAppUser(tenant, orderId).get("shipment_has_courier")).isEqualTo(false);
        assertThatThrownBy(() -> awbService.printAwb(tenant, List.of(shipmentId), null, null))
            .isInstanceOf(NoBostaAccountException.class);
        verifyNoInteractions(bostaGateway);
    }

    // ── d) self-pickup / no forward shipment → false ──────────────────────────

    @Test
    void d1_selfPickup_noShipment_hasCourierFalse() {
        UUID tenant = tenant("HasCourierSelfPickup");
        courierAccount(tenant, "active");
        UUID orderId = order(tenant, true);

        Map<String, Object> order = getOrderAsAppUser(tenant, orderId);
        assertThat(order.get("is_self_pickup")).isEqualTo(true);
        assertThat(order.get("shipment_id")).isNull();
        assertThat(order.get("shipment_has_courier")).isEqualTo(false);
    }

    @Test
    void d2_returnLegOnly_noForwardShipment_hasCourierFalse() {
        UUID tenant = tenant("HasCourierReturnOnly");
        courierAccount(tenant, "active");
        UUID orderId = order(tenant, false);
        jdbc.update(
            "INSERT INTO shipments (tenant_id, order_id, provider, tracking_number, internal_state, shipment_leg) " +
            "VALUES (?, ?, 'bosta', '2445296559', 'created', 'return')",
            tenant, orderId);

        Map<String, Object> order = getOrderAsAppUser(tenant, orderId);
        assertThat(order.get("shipment_id")).isNull();
        assertThat(order.get("shipment_has_courier")).isEqualTo(false);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Map<String, Object> getOrderAsAppUser(UUID tenant, UUID orderId) {
        return TenantContext.runAs(tenant, () ->
            appUserTx.execute(s -> appUserFulfill.getOrder(orderId)));
    }

    private UUID tenant(String name) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?)", id, name);
        return id;
    }

    private void courierAccount(UUID tenant, String status) {
        jdbc.update("INSERT INTO courier_accounts " +
                    "(tenant_id, provider, api_key_encrypted, webhook_secret, status, awb_format, awb_lang) " +
                    "VALUES (?, 'bosta', ?, 'test-hash', ?::courier_account_status, 'A4', 'ar')",
                    tenant, encryptionService.encrypt("has-courier-key"), status);
    }

    private UUID order(UUID tenant, boolean selfPickup) {
        UUID storeId = UUID.randomUUID();
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status) " +
                    "VALUES (?, ?, 'shopify', ?, 'disconnected')",
                    storeId, tenant, "hc-" + storeId + ".myshopify.com");
        return jdbc.queryForObject(
            "INSERT INTO orders (tenant_id, store_id, external_id, status, is_self_pickup) " +
            "VALUES (?, ?, ?, 'ready_to_pick'::order_status, ?) RETURNING id",
            UUID.class, tenant, storeId, "EXT-" + UUID.randomUUID(), selfPickup);
    }

    /** courier_account_id deliberately omitted (NULL) — every prod forward shipment's shape. */
    private UUID forwardShipment(UUID tenant, UUID orderId, String tracking) {
        return jdbc.queryForObject(
            "INSERT INTO shipments (tenant_id, order_id, provider, tracking_number, internal_state, shipment_leg) " +
            "VALUES (?, ?, 'bosta', ?, 'created', 'forward') RETURNING id",
            UUID.class, tenant, orderId, tracking);
    }
}
