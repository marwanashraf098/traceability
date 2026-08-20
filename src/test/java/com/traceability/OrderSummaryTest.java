package com.traceability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.traceability.fulfillment.OrderController;
import com.traceability.fulfillment.OrderController.FunnelCounts;
import com.traceability.fulfillment.OrderController.OrderSummaryCounts;
import com.traceability.tenancy.TenantAwareDataSource;
import com.traceability.tenancy.TenantContext;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * GET /orders/summary — proves the 5-tile bucket tally is correct against
 * OrderStatusDeriver.derive() (the single source of truth, same as
 * OrderStatusListDetailParityTest proves for list()/detail()), that the 4 breakdown
 * buckets deliberately don't sum to total, and that RLS holds for this aggregate read.
 *
 * OrderController is constructed directly (new, not @Autowired) — same pattern as
 * OrderStatusListDetailParityTest, testing query logic without the HTTP + @PreAuthorize
 * layer.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OrderSummaryTest {

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

    @Autowired JdbcTemplate               jdbc;
    @Autowired ObjectMapper               mapper;
    @Autowired PlatformTransactionManager txm;

    // app_user infrastructure for the RLS test — mirrors OrderStatusListDetailParityTest.
    private JdbcTemplate               appUserJdbc;
    private PlatformTransactionManager appUserTxm;

    private OrderController controller;

    // summary() (like funnel(), which it mirrors) relies solely on the @Transactional
    // annotation for its tenant-GUC SET LOCAL — unlike list()/detail(), it has no internal
    // TransactionTemplate.execute() fallback. @Transactional is Spring AOP: it does nothing
    // on a directly-`new`'d controller (no proxy). TenantAwareConnection only fires
    // SET LOCAL on the setAutoCommit(false) transition, which only a real transaction
    // triggers — a bare jdbc.query() in Hikari's default autocommit mode never does. So
    // these tests must open the transaction themselves, exactly what list()/detail() do
    // internally and what the real @Transactional-proxied HTTP endpoint does at runtime.
    private TransactionTemplate tx;
    private TransactionTemplate appUserTx;

    UUID tenantId, otherTenantId, storeId;

    @BeforeAll
    void setupAppUser() {
        DriverManagerDataSource rawDs =
                new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "app_user", "testpw");
        TenantAwareDataSource appDs = new TenantAwareDataSource(rawDs);
        appUserJdbc = new JdbcTemplate(appDs);
        appUserTxm  = new DataSourceTransactionManager(appDs);
        appUserTx   = new TransactionTemplate(appUserTxm);
    }

    @BeforeAll
    void setupFixture() {
        controller = new OrderController(jdbc, mapper, txm);
        tx         = new TransactionTemplate(txm);

        tenantId      = UUID.randomUUID();
        otherTenantId = UUID.randomUUID();
        storeId       = UUID.randomUUID();

        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, 'SummaryTenant')", tenantId);
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, 'SummaryOtherTenant')", otherTenantId);
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status) " +
                    "VALUES (?, ?, 'shopify', 'summary-test.myshopify.com', 'connected')",
                    storeId, tenantId);
    }

    @BeforeEach void ctx()   { TenantContext.set(tenantId); }
    @AfterEach  void clean() { TenantContext.clear(); }

    // ── helpers ──────────────────────────────────────────────────────────────

    private UUID insertOrder(String extId, String status) {
        return insertOrder(tenantId, storeId, extId, status);
    }

    private UUID insertOrder(UUID forTenantId, UUID forStoreId, String extId, String status) {
        return jdbc.queryForObject(
            "INSERT INTO orders (tenant_id, store_id, external_id, number, status, " +
            "    payment_method, placed_at) " +
            "VALUES (?, ?, ?, ?, ?::order_status, 'cod', now()) RETURNING id",
            UUID.class, forTenantId, forStoreId, extId, "#" + extId, status);
    }

    private void insertForwardShipment(UUID orderId, String tracking, String state) {
        insertForwardShipment(tenantId, orderId, tracking, state);
    }

    private void insertForwardShipment(UUID forTenantId, UUID orderId, String tracking, String state) {
        jdbc.update(
            "INSERT INTO shipments (tenant_id, order_id, provider, tracking_number, " +
            "    internal_state, shipment_leg) " +
            "VALUES (?, ?, 'bosta', ?, ?::shipment_internal_state, 'forward')",
            forTenantId, orderId, tracking, state);
    }

    // ── bucket tally ─────────────────────────────────────────────────────────

    @Test
    void summary_bucketsTallyCorrectly_andBreakdownIntentionallyDoesNotSumToTotal() {
        // Own dedicated tenant — summary() tallies ALL of a tenant's orders, so this test
        // must not share the class-level tenantId with rls_summary_...() below: PER_CLASS
        // lifecycle gives no guaranteed method order, and that test's own seeded order would
        // otherwise leak into this tally depending on which test happens to run first.
        UUID bucketTenantId = UUID.randomUUID();
        UUID bucketStoreId  = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, 'SummaryBucketTenant')", bucketTenantId);
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status) " +
                    "VALUES (?, ?, 'shopify', 'summary-bucket-test.myshopify.com', 'connected')",
                    bucketStoreId, bucketTenantId);

        // Processing: new, picking, packed — 3 orders, no shipment.
        insertOrder(bucketTenantId, bucketStoreId, "SUMMARY-NEW", "new");
        insertOrder(bucketTenantId, bucketStoreId, "SUMMARY-PICKING", "picking");
        insertOrder(bucketTenantId, bucketStoreId, "SUMMARY-PACKED", "packed");

        // With courier: shipment-linked at internal_state='with_courier' — the converged
        // predicate (Orders-rebuild pass (b), review-approved option (a)): withCourier now
        // means literal raw internal_state='with_courier' only, matching list()'s
        // deliveryState filter exactly. A 'created'-state shipment (label printed, not yet
        // collected) is deliberately NOT in this bucket anymore — see
        // summary_labelPrintedNotYetCollected_notCountedAsInTransit below.
        UUID withCourierOrder = insertOrder(bucketTenantId, bucketStoreId, "SUMMARY-AWAITING", "awaiting_pickup");
        insertForwardShipment(bucketTenantId, withCourierOrder, "SUMMARY-TRK-1", "with_courier");

        // Delivered: shipment-linked, terminal delivered.
        UUID deliveredOrder = insertOrder(bucketTenantId, bucketStoreId, "SUMMARY-DELIVERED", "new");
        insertForwardShipment(bucketTenantId, deliveredOrder, "SUMMARY-TRK-2", "delivered");

        // Returned: shipment-linked, terminal returned.
        UUID returnedOrder = insertOrder(bucketTenantId, bucketStoreId, "SUMMARY-RETURNED", "returned");
        insertForwardShipment(bucketTenantId, returnedOrder, "SUMMARY-TRK-3", "returned");

        // Intentionally uncounted in any breakdown bucket, but still part of total: a
        // cancelled order with no shipment (status.cancelled falls through the switch's
        // default branch).
        insertOrder(bucketTenantId, bucketStoreId, "SUMMARY-CANCELLED", "cancelled");

        TenantContext.set(bucketTenantId);
        OrderSummaryCounts counts;
        try {
            counts = tx.execute(status -> controller.summary());
        } finally {
            TenantContext.set(tenantId); // restore — @AfterEach clean() also clears it
        }

        assertThat(counts.processing()).as("new + picking + packed").isEqualTo(3);
        assertThat(counts.withCourier()).as("with_courier shipment").isEqualTo(1);
        assertThat(counts.delivered()).isEqualTo(1);
        assertThat(counts.returned()).isEqualTo(1);

        // Exactly 7 rows for this dedicated tenant — no shared-tenant leakage possible.
        assertThat(counts.total()).isEqualTo(7);

        // The breakdown intentionally does not sum to total — proves the non-sum-to-total
        // design is real (the cancelled order is deliberately uncounted), not an accidental
        // gap a future "helpful" change might silently close.
        long breakdownSum = counts.processing() + counts.withCourier() + counts.delivered() + counts.returned();
        assertThat(counts.total()).as("total must exceed the sum of the 4 breakdown buckets")
            .isGreaterThan(breakdownSum);
    }

    // ── Orders-rebuild pass (b), review-approved option (a): the converged withCourier
    // predicate is narrower than the old packedConfirmed-gated bucket on purpose. A
    // 'created'-state shipment (AWB/label printed, courier hasn't collected it yet) is
    // genuinely not "in transit" — the old bucket was loose. Uncounted here follows the
    // same precedent as the cancelled order in the tally test above: present in total,
    // absent from all 4 breakdown buckets, not a bug. ──────────────────────────────────

    @Test
    void summary_labelPrintedNotYetCollected_notCountedAsInTransit() {
        UUID labelTenantId = UUID.randomUUID();
        UUID labelStoreId  = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, 'SummaryLabelTenant')", labelTenantId);
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status) " +
                    "VALUES (?, ?, 'shopify', 'summary-label-test.myshopify.com', 'connected')",
                    labelStoreId, labelTenantId);

        UUID orderId = insertOrder(labelTenantId, labelStoreId, "LABEL-PRINTED", "awaiting_pickup");
        insertForwardShipment(labelTenantId, orderId, "LABEL-TRK-1", "created");

        TenantContext.set(labelTenantId);
        OrderSummaryCounts counts;
        try {
            counts = tx.execute(status -> controller.summary());
        } finally {
            TenantContext.set(tenantId);
        }

        assertThat(counts.withCourier())
            .as("label printed / not yet collected is NOT 'in transit' under the converged predicate")
            .isEqualTo(0);
        assertThat(counts.processing())
            .as("packedConfirmed is true (order.status='awaiting_pickup') but this order isn't " +
                "a raw pipeline stage either — it falls out of Processing too, same as a " +
                "cancelled/returning order already does")
            .isEqualTo(0);
        assertThat(counts.delivered()).isEqualTo(0);
        assertThat(counts.returned()).isEqualTo(0);
        assertThat(counts.total())
            .as("still visible in the tenant's total — findable under All, just not bucketed")
            .isEqualTo(1);
    }

    // ── Slice 1 (status-split fix): never-packed webhook-matched order ─────────
    // reclassification tripwire — the merchant-facing miscount this whole fix targets.

    @Test
    void summary_neverPackedWebhookMatchedOrder_countsAsProcessing_notWithCourier() {
        UUID reclassTenantId = UUID.randomUUID();
        UUID reclassStoreId  = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, 'SummaryReclassTenant')", reclassTenantId);
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status) " +
                    "VALUES (?, ?, 'shopify', 'summary-reclass-test.myshopify.com', 'connected')",
                    reclassStoreId, reclassTenantId);

        // Mode-B webhook auto-match: shipment exists at label_created while order.status is
        // still 'picking' — packing has NOT happened. Before this fix, this order counted as
        // "With courier" (a shipment record existing was wrongly treated as proof of packing).
        UUID orderId = insertOrder(reclassTenantId, reclassStoreId, "RECLASS-EARLY-MATCH", "picking");
        insertForwardShipment(reclassTenantId, orderId, "RECLASS-TRK-1", "created");

        TenantContext.set(reclassTenantId);
        OrderSummaryCounts counts;
        try {
            counts = tx.execute(status -> controller.summary());
        } finally {
            TenantContext.set(tenantId);
        }

        assertThat(counts.processing()).as("never-packed order must count as Processing").isEqualTo(1);
        assertThat(counts.withCourier()).as("never-packed order must NOT count as With courier").isEqualTo(0);
    }

    @Test
    void funnel_neverPackedWebhookMatchedOrder_countsAsPicking_notCourier() {
        UUID reclassTenantId = UUID.randomUUID();
        UUID reclassStoreId  = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, 'FunnelReclassTenant')", reclassTenantId);
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status) " +
                    "VALUES (?, ?, 'shopify', 'funnel-reclass-test.myshopify.com', 'connected')",
                    reclassStoreId, reclassTenantId);

        // Same shape as the summary test above, routed through funnel() instead (today-scoped —
        // insertOrder() already sets placed_at=now(), so this order is in scope). Routes by its
        // own raw order.status ('picking') rather than a merged Processing bucket, since funnel()
        // keeps New/Picking/Packed as separate buckets.
        UUID orderId = insertOrder(reclassTenantId, reclassStoreId, "RECLASS-FUNNEL-1", "picking");
        insertForwardShipment(reclassTenantId, orderId, "RECLASS-FUNNEL-TRK-1", "created");

        TenantContext.set(reclassTenantId);
        FunnelCounts counts;
        try {
            counts = tx.execute(status -> controller.funnel());
        } finally {
            TenantContext.set(tenantId);
        }

        assertThat(counts.picking()).as("never-packed order must count as Picking (its own raw status)").isEqualTo(1);
        assertThat(counts.courier()).as("never-packed order must NOT count as Courier").isEqualTo(0);
    }

    // ── RLS ──────────────────────────────────────────────────────────────────

    @Test
    void rls_summary_sameTenantPositiveControl_crossTenantNegativeControl() {
        insertOrder("SUMMARY-RLS", "new");

        OrderController appUserController = new OrderController(appUserJdbc, mapper, appUserTxm);

        // Positive control: app_user WITH the correct tenant GUC sees this tenant's orders.
        OrderSummaryCounts sameTenant = TenantContext.runAs(tenantId,
            () -> appUserTx.execute(status -> appUserController.summary()));
        assertThat(sameTenant.total()).isGreaterThanOrEqualTo(1);

        // Negative control: app_user under a DIFFERENT tenant's GUC sees none of them —
        // RLS scopes the read to zero rows (an aggregate read, so this is 200/total==0,
        // not a 404 the way single-entity detail() is).
        OrderSummaryCounts otherTenant = TenantContext.runAs(otherTenantId,
            () -> appUserTx.execute(status -> appUserController.summary()));
        assertThat(otherTenant.total())
            .as("app_user under a different tenant's GUC must see zero of this tenant's orders")
            .isEqualTo(0);
    }
}
