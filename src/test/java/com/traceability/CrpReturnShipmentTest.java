package com.traceability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.traceability.integrations.bosta.*;
import com.traceability.inventory.ShipmentLinkService;
import com.traceability.inventory.UlidGenerator;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * FR-12.6 — CRP (Customer Return Pickup) return shipment leg.
 *
 * Bosta creates a separate delivery (new tracking number, type.code=25) when a customer
 * initiates a return. Prior to V43, ux_active_shipment_per_order (V19) blocked the INSERT
 * when the forward shipment was in state 'delivered' (not excluded from the active-slot
 * definition). V43 adds shipment_leg and rebuilds the index per (order_id, leg).
 *
 * Tests:
 *   crp1 — CRP auto-links as return leg alongside a delivered forward shipment
 *   crp2 — Second webhook for the same CRP tracking is idempotent (one row, same orderId)
 *   crp3 — State-41 mapper resolves to 'returning' after V43 fixes the '41:CRP' seed key
 *   crp4 — AWB service excludes CRP shipment with reason NON_PRINTABLE_TYPE:CRP
 *   crp5 — CRP delivery with no matching order → unlinked NO_MATCH
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CrpReturnShipmentTest {

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

    @Autowired JdbcTemplate          jdbc;
    @Autowired ShipmentLinkService   shipmentLinkSvc;
    @Autowired BostaStateMapper      stateMapper;
    @Autowired BostaAwbService       awbService;
    @Autowired EncryptionService     encryptionService;
    @Autowired ObjectMapper          mapper;

    @MockBean  BostaGateway   bostaGateway;
    @MockBean  JobScheduler   jobScheduler;

    // app_user connection for RLS-scoped assertions
    private JdbcTemplate        appUserJdbc;
    private TransactionTemplate appUserTx;

    private UUID   tenantId;
    private UUID   storeId;
    private UUID   variantId;
    private String encApiKey;

    @BeforeAll
    void setupFixture() {
        DriverManagerDataSource rawDs =
                new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "app_user", "testpw");
        TenantAwareDataSource appUserDs = new TenantAwareDataSource(rawDs);
        appUserJdbc = new JdbcTemplate(appUserDs);
        appUserTx   = new TransactionTemplate(new DataSourceTransactionManager(appUserDs));

        tenantId = UUID.randomUUID();
        UUID userId    = UUID.randomUUID();
        storeId   = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        variantId = UUID.randomUUID();
        encApiKey = encryptionService.encrypt("crp-test-api-key");

        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, 'CRPTenant')", tenantId);
        jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role) " +
                    "VALUES (?, ?, 'Ziad', 'ziad@crp.local', 'h', 'owner')", userId, tenantId);
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status) " +
                    "VALUES (?, ?, 'shopify', 'crp.myshopify.com', 'disconnected')", storeId, tenantId);
        jdbc.update("INSERT INTO products (id, tenant_id, store_id, external_id, title, status) " +
                    "VALUES (?, ?, ?, 'P-CRP', 'Widget', 'active')", productId, tenantId, storeId);
        jdbc.update("INSERT INTO variants (id, tenant_id, product_id, external_id, title, sku) " +
                    "VALUES (?, ?, ?, 'V-CRP', 'Red L', 'WID-RED-L')", variantId, tenantId, productId);
        jdbc.update("INSERT INTO courier_accounts " +
                    "(id, tenant_id, provider, api_key_encrypted, webhook_secret, status) " +
                    "VALUES (gen_random_uuid(), ?, 'bosta', ?, 'crp-hash', 'active')",
                    tenantId, encApiKey);
    }

    @BeforeEach
    void setContext() { TenantContext.set(tenantId); }

    @AfterEach
    void cleanup() {
        TenantContext.clear();
        jdbc.update("DELETE FROM unlinked_bosta_deliveries WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM shipments   WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM order_items WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM orders      WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM webhook_events WHERE tenant_id = ?", tenantId);
    }

    // ── crp1: CRP auto-links as return leg alongside delivered forward shipment ──────────

    /**
     * The core V43 case: forward shipment at 'delivered' no longer blocks a CRP INSERT.
     * isCrpDelivery() routes to createOrFindReturnShipment() → shipment_leg='return'.
     * The forward shipment row must be unmodified.
     */
    @Test
    @Order(1)
    void crp1_crpDelivery_linksAsReturnLeg_forwardShipmentUntouched() {
        String orderNum    = "#CRP-001";
        String fwdTracking = "BOS-FWD-001";
        String crpTracking = "BOS-CRP-001";

        UUID orderId = createOrder(orderNum);

        // Forward shipment: already delivered (the state that blocked CRP before V43)
        UUID fwdShipId = insertShipmentWithState(orderId, fwdTracking, "delivered", "forward", null);

        // CRP delivery: type.code=25, businessRef matches the order number, state 22
        BostaDelivery crp = crpDelivery(crpTracking, 22, orderNum);
        BostaStateMapper.MappedState mapped = stateMapper.map(22, "CUSTOMER RETURN PICKUP");

        ShipmentLinkService.LinkResult result =
            TenantContext.runAs(tenantId, () ->
                shipmentLinkSvc.tryMatchDelivery(tenantId, crpTracking, crp, mapped));

        // Linked — not unlinked
        assertThat(result.orderId())
            .as("CRP must link to the order via businessRef match")
            .isEqualTo(orderId);

        // Return shipment created with correct leg
        String returnLeg = jdbc.queryForObject(
            "SELECT shipment_leg FROM shipments WHERE tracking_number = ? AND tenant_id = ?",
            String.class, crpTracking, tenantId);
        assertThat(returnLeg)
            .as("CRP shipment must have shipment_leg='return'")
            .isEqualTo("return");

        // Forward shipment untouched
        String fwdState = jdbc.queryForObject(
            "SELECT internal_state::text FROM shipments WHERE id = ?", String.class, fwdShipId);
        String fwdLeg   = jdbc.queryForObject(
            "SELECT shipment_leg FROM shipments WHERE id = ?", String.class, fwdShipId);
        assertThat(fwdState).as("forward shipment state must still be delivered").isEqualTo("delivered");
        assertThat(fwdLeg).as("forward shipment leg must still be forward").isEqualTo("forward");

        // Assert via app_user (RLS enforced) — return shipment is tenant-visible
        int visibleCount = TenantContext.runAs(tenantId, () ->
            appUserTx.execute(s ->
                appUserJdbc.queryForObject(
                    "SELECT COUNT(*) FROM shipments WHERE tracking_number = ? AND tenant_id = ?",
                    Integer.class, crpTracking, tenantId)));
        assertThat(visibleCount)
            .as("return shipment must be visible to app_user under RLS")
            .isEqualTo(1);
    }

    // ── crp2: Idempotent second call for same CRP tracking ───────────────────────────────

    /**
     * createOrFindReturnShipment() finds the existing row on the second call — no duplicate INSERT.
     * The ux_active_shipment_per_order_leg UNIQUE index would block a true duplicate anyway,
     * but the find-first path means the DuplicateKeyException branch is never reached.
     */
    @Test
    @Order(2)
    void crp2_secondCrpWebhook_idempotent_singleShipmentRow() {
        String orderNum    = "#CRP-002";
        String crpTracking = "BOS-CRP-002";

        UUID orderId = createOrder(orderNum);
        insertShipmentWithState(orderId, "BOS-FWD-002", "delivered", "forward", null);

        BostaDelivery crp    = crpDelivery(crpTracking, 22, orderNum);
        BostaStateMapper.MappedState mapped = stateMapper.map(22, "CUSTOMER RETURN PICKUP");

        // First call — links
        ShipmentLinkService.LinkResult first = TenantContext.runAs(tenantId, () ->
            shipmentLinkSvc.tryMatchDelivery(tenantId, crpTracking, crp, mapped));
        assertThat(first.orderId()).isEqualTo(orderId);

        // Second call — finds existing row, returns same orderId
        ShipmentLinkService.LinkResult second = TenantContext.runAs(tenantId, () ->
            shipmentLinkSvc.tryMatchDelivery(tenantId, crpTracking, crp, mapped));
        assertThat(second.orderId())
            .as("second call must return same orderId (idempotent find)")
            .isEqualTo(orderId);

        // Exactly one shipment row for the CRP tracking number
        int count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM shipments WHERE tracking_number = ? AND tenant_id = ?",
            Integer.class, crpTracking, tenantId);
        assertThat(count)
            .as("only one shipment row must exist for the CRP tracking number")
            .isEqualTo(1);
    }

    // ── crp3: State-41 mapper resolves to 'returning' after V43 renames seed key ─────────

    /**
     * Before V43, stateMapper.map(41,"CRP") hit '41:CRP' in the cache.
     * After V43, the row is renamed to '41:CUSTOMER RETURN PICKUP' (the actual normalized
     * type.value from Bosta). '41:CRP' must now be unknownCode. '41:CUSTOMER RETURN PICKUP'
     * must resolve to returning/return_in_transit without unknownCode.
     *
     * State 41 has no :ALL fallback — the old broken key would have been the only match.
     * This test proves both sides of the rename.
     */
    @Test
    @Order(3)
    void crp3_stateMapper_state41CrpKey_renamedToActualTypeString() {
        // New correct key
        BostaStateMapper.MappedState crp41 = stateMapper.map(41, "CUSTOMER RETURN PICKUP");
        assertThat(crp41.unknownCode())
            .as("41:CUSTOMER RETURN PICKUP must be found after V43 renames the seed row")
            .isFalse();
        assertThat(crp41.shipmentInternalState()).isEqualTo("returning");
        assertThat(crp41.pieceStatusAfter()).isEqualTo("return_in_transit");

        // Old dead key — must unknownCode (no :ALL fallback for state 41)
        BostaStateMapper.MappedState deadKey = stateMapper.map(41, "CRP");
        assertThat(deadKey.unknownCode())
            .as("41:CRP must be unknownCode after V43 — the seed row was renamed")
            .isTrue();
    }

    // ── crp4: AWB service excludes CRP shipment by type.code ─────────────────────────────

    /**
     * Prior to V43, raw->>'type' extracted the JSON object as text
     * (e.g. '{"code":25,"value":"Customer Return Pickup"}'), never matching "CRP".
     * V43 code fix: extract (raw->'type'->>'code')::int and check == 25.
     *
     * Insert a CRP shipment with type.code=25 in raw. Call printAwb. The shipment must be
     * excluded before any Bosta API call is made (printable list is empty).
     */
    @Test
    @Order(4)
    void crp4_awbService_excludesCrpShipment_byTypeCode() {
        UUID orderId = createOrder("#CRP-004");

        // Build raw JSON with type.code=25 (CRP)
        ObjectNode crpRaw = mapper.createObjectNode();
        crpRaw.putObject("type").put("code", 25).put("value", "Customer Return Pickup");
        crpRaw.put("trackingNumber", "BOS-CRP-004");

        UUID shipId = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO shipments " +
            "(id, tenant_id, order_id, provider, tracking_number, internal_state, shipment_leg, raw) " +
            "VALUES (?, ?, ?, 'bosta', 'BOS-CRP-004', 'with_courier'::shipment_internal_state, 'return', ?::jsonb)",
            shipId, tenantId, orderId, crpRaw.toString());

        // AWB print — CRP must be excluded before Bosta is called; printMassAwb is never invoked
        BostaAwbService.AwbBatchResult result =
            awbService.printAwb(tenantId, List.of(shipId), null, null);

        assertThat(result.pdfBase64List())
            .as("no PDF for an excluded CRP shipment")
            .isEmpty();
        assertThat(result.exceptions())
            .as("CRP shipment must appear in exceptions list")
            .hasSize(1);
        assertThat(result.exceptions().get(0).reason())
            .as("exclusion reason must be NON_PRINTABLE_TYPE:CRP")
            .isEqualTo("NON_PRINTABLE_TYPE:CRP");
        assertThat(result.exceptions().get(0).trackingNumber())
            .isEqualTo("BOS-CRP-004");
    }

    // ── crp5: CRP with no matching order → unlinked NO_MATCH ────────────────────────────

    /**
     * businessRef doesn't match any order number; phone+COD fallback also fails
     * (no receiver.phone in raw). CRP delivery must route to unlinked_bosta_deliveries
     * with reason NO_MATCH — same as any forward delivery that can't be matched.
     */
    @Test
    @Order(5)
    void crp5_crpDelivery_noMatchingOrder_routesToUnlinked() {
        String crpTracking = "BOS-CRP-005";
        BostaDelivery crp  = crpDelivery(crpTracking, 22, "#UNKNOWN-999");
        BostaStateMapper.MappedState mapped = stateMapper.map(22, "CUSTOMER RETURN PICKUP");

        ShipmentLinkService.LinkResult result =
            TenantContext.runAs(tenantId, () ->
                shipmentLinkSvc.tryMatchDelivery(tenantId, crpTracking, crp, mapped));

        assertThat(result.orderId())
            .as("no match → orderId must be null")
            .isNull();
        assertThat(result.unlinkedReason())
            .as("no match → reason must be NO_MATCH")
            .isEqualTo(ShipmentLinkService.REASON_NO_MATCH);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM shipments WHERE tracking_number = ? AND tenant_id = ?",
                Integer.class, crpTracking, tenantId))
            .as("no shipment row must be created for an unmatched CRP delivery")
            .isZero();
    }

    // ── Helpers ──────────────────────────────────────────────────────────────────────────

    private UUID createOrder(String orderNum) {
        return jdbc.queryForObject(
            "INSERT INTO orders (tenant_id, store_id, external_id, number, status, payment_method, placed_at) " +
            "VALUES (?, ?, ?, ?, 'delivered'::order_status, 'cod'::order_payment_method, now()) " +
            "RETURNING id",
            UUID.class, tenantId, storeId, "EXT-" + UUID.randomUUID(), orderNum);
    }

    private UUID insertShipmentWithState(UUID orderId, String tracking,
                                          String state, String leg, String rawJson) {
        UUID id = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO shipments " +
            "(id, tenant_id, order_id, provider, tracking_number, internal_state, shipment_leg, raw) " +
            "VALUES (?, ?, ?, 'bosta', ?, ?::shipment_internal_state, ?, ?::jsonb)",
            id, tenantId, orderId, tracking, state, leg, rawJson);
        return id;
    }

    /**
     * Builds a BostaDelivery for a CRP return pickup.
     * raw.type.code=25 — isCrpDelivery() uses typeCode() derived from this node.
     * businessRef is the order number embedded by the merchant (§8 businessReference).
     */
    private BostaDelivery crpDelivery(String trackingNumber, int stateCode, String businessRef) {
        ObjectNode raw = mapper.createObjectNode();
        raw.put("trackingNumber", trackingNumber);
        ObjectNode typeNode = raw.putObject("type");
        typeNode.put("code", 25);
        typeNode.put("value", "Customer Return Pickup");
        ObjectNode stateNode = raw.putObject("state");
        stateNode.put("code", stateCode);
        raw.put("businessReference", businessRef);
        return new BostaDelivery(trackingNumber, stateCode, "CUSTOMER RETURN PICKUP",
                                  0, businessRef, null, raw);
    }
}
