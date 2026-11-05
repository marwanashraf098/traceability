package com.traceability.opsreversal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.traceability.TestPostgres;
import com.traceability.account.AuditService;
import com.traceability.integrations.shopify.ShopifyGateway;
import com.traceability.integrations.shopify.ShopifyTokenProvider;
import com.traceability.integrations.shopify.StoreRepository;
import com.traceability.inventory.InventoryLedger;
import com.traceability.inventory.PieceAdjustService;
import com.traceability.inventory.ShopifyInventoryService;
import com.traceability.tenancy.TenantAwareDataSource;
import com.traceability.tenancy.TenantContext;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.util.*;

import static com.traceability.opsreversal.ReceiptReversalService.RECEIPT_ID;
import static com.traceability.opsreversal.ReceiptReversalService.TENANT_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * The one-off receipt reversal on a REAL app_user connection (RLS enforced) — the role it runs as in production:
 * dry run + apply end to end (guard, ledger voids, void_correction claims, paced sends, receipt 'reversed', the
 * audit row naming a requester who belongs to ANOTHER tenant). Fixtures are written as postgres.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReceiptReversalAppUserTest {

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

    static final String SCOPES = "read_orders,write_inventory,read_products,write_locations,read_locations";
    static final String SHOP = "floe-appuser.myshopify.com";
    static final String MAIN_GID = "gid://shopify/Location/main";
    static final UUID OTHER_RECEIPT = UUID.fromString("c5002445-2713-439f-89e2-e15c8718184f");

    @Autowired JdbcTemplate jdbc;            // postgres — fixtures and assertions only
    @Autowired ObjectMapper mapper;
    @MockBean ShopifyGateway shopifyGateway;
    @MockBean ShopifyTokenProvider tokenProvider;

    ReceiptReversalService appService;

    @BeforeAll
    void appUser() {
        DataSource appDs = new TenantAwareDataSource(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "app_user", "testpw"));
        JdbcTemplate appJdbc = new JdbcTemplate(appDs);
        DataSourceTransactionManager appTxm = new DataSourceTransactionManager(appDs);
        StoreRepository stores = new StoreRepository(appJdbc, appTxm);
        ShopifyInventoryService inventory = new ShopifyInventoryService(appJdbc, appTxm, shopifyGateway, tokenProvider,
            mapper, stores);
        AuditService audit = new AuditService(appJdbc, mapper);
        PieceAdjustService adjust = new PieceAdjustService(appJdbc, new InventoryLedger(appJdbc), audit, mapper, inventory);
        appService = new ReceiptReversalService(appJdbc, appTxm, adjust, inventory, audit, stores, tokenProvider, shopifyGateway);
        appService.setPaceMs(0);
        appService.setRetryWaitMs(10);
    }

    @AfterEach
    void clear() { TenantContext.clear(); }

    @Test
    @SuppressWarnings("unchecked")
    void u1_dryRunThenApply_underAppUser_endToEnd() throws Exception {
        when(tokenProvider.getValidToken(any())).thenReturn("tok");
        when(shopifyGateway.fetchMainLevels(any(), any(), any(), any())).thenAnswer(inv -> {
            List<ShopifyGateway.MainLevel> out = new ArrayList<>();
            for (Object i : (List<?>) inv.getArgument(3)) out.add(new ShopifyGateway.MainLevel((String) i, true, 50, 50, 0, 0));
            return out;
        });

        UUID otherTenant = UUID.randomUUID(), owner = UUID.randomUUID(), requester = UUID.randomUUID();
        UUID store = UUID.randomUUID(), main = UUID.randomUUID(), product = UUID.randomUUID(), variant = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, 'Floe Wear'), (?, 'Traced support')", TENANT_ID, otherTenant);
        jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role) VALUES (?, ?, 'Marco', 'marco@u1.local', 'h', 'owner'), " +
            "(?, ?, 'Marawan', 'marawan@u1.local', 'h', 'owner')", owner, TENANT_ID, requester, otherTenant);
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status, import_status, access_token_scopes, last_sync_at) " +
            "VALUES (?, ?, 'shopify', ?, 'connected', 'completed', ?, now())", store, TENANT_ID, SHOP, SCOPES);
        jdbc.update("INSERT INTO locations (id, tenant_id, name, shopify_location_id, shopify_sync_status, is_fulfillment) " +
            "VALUES (?, ?, 'Main Warehouse', ?, 'linked', true)", main, TENANT_ID, MAIN_GID);
        jdbc.update("INSERT INTO products (id, tenant_id, store_id, external_id, title, status) VALUES (?, ?, ?, 'gid://shopify/Product/1', 'Tee', 'active')",
            product, TENANT_ID, store);
        jdbc.update("INSERT INTO variants (id, tenant_id, product_id, external_id, title, sku, shopify_inventory_item_id) " +
            "VALUES (?, ?, ?, 'gid://shopify/ProductVariant/1', 'S', 'SKU-S', 'gid://shopify/InventoryItem/1')", variant, TENANT_ID, product);
        jdbc.update("INSERT INTO receipts (id, tenant_id, reference, received_by, location_id, status, finalized_at, kind) " +
            "VALUES (?, ?, 'PO-01', ?, ?, 'finalized', now() - interval '1 day', 'inbound'), " +
            "       (?, ?, NULL, ?, ?, 'finalized', now() - interval '3 days', 'inbound')",
            RECEIPT_ID, TENANT_ID, owner, main, OTHER_RECEIPT, TENANT_ID, owner, main);
        for (UUID receipt : List.of(RECEIPT_ID, OTHER_RECEIPT)) {
            jdbc.update("INSERT INTO shopify_inventory_adjustments (tenant_id, batch_id, variant_id, location_id, delta, " +
                "trigger_type, trigger_id, status, created_at) VALUES (?, ?, ?, ?, 2, 'receiving_session', ?, 'applied', now() - interval '1 day')",
                TENANT_ID, UUID.randomUUID(), variant, main, receipt.toString());
        }
        int k = 0;
        for (UUID receipt : List.of(RECEIPT_ID, RECEIPT_ID, OTHER_RECEIPT)) {
            String id = "01AU" + UUID.randomUUID().toString().replace("-", "").substring(0, 22).toUpperCase();
            jdbc.update("INSERT INTO pieces (id, tenant_id, variant_id, status, barcode, short_code, current_location_id, receipt_id, created_at) " +
                "VALUES (?, ?, ?, 'available', ?, ?, ?, ?, now() - interval '1 day')",
                id, TENANT_ID, variant, "AU-" + id, String.format("U%07d", ++k), main, receipt);
            jdbc.update("INSERT INTO piece_events (tenant_id, piece_id, event_type, actor_user_id, location_id, to_status, occurred_at) " +
                "VALUES (?, ?, 'received', ?, ?, 'available', now() - interval '1 day')", TENANT_ID, id, owner, main);
        }

        Map<String, Object> dry = run(owner, requester, false);
        assertThat(dry.get("phase")).isEqualTo("dry_run_done");
        assertThat((Map<String, Object>) dry.get("totals")).containsEntry("pieces", 2).containsEntry("expected_shopify_delta", -2);

        Map<String, Object> applied = run(owner, requester, true);
        assertThat(applied.get("phase")).as(String.valueOf(applied)).isEqualTo("done");
        assertThat(jdbc.queryForObject("SELECT status FROM receipts WHERE id = ?", String.class, RECEIPT_ID)).isEqualTo("reversed");
        assertThat(jdbc.queryForObject("SELECT status FROM receipts WHERE id = ?", String.class, OTHER_RECEIPT)).isEqualTo("finalized");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pieces WHERE receipt_id = ? AND status = 'voided'", Integer.class, RECEIPT_ID))
            .isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pieces WHERE receipt_id = ? AND status = 'available'", Integer.class, OTHER_RECEIPT))
            .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT actor_user_id FROM audit_log WHERE action = 'receipt_reversed'", UUID.class))
            .isEqualTo(requester);
        verify(shopifyGateway, times(2)).pushVoidCorrection(eq(SHOP), eq("tok"), eq("gid://shopify/InventoryItem/1"), eq(MAIN_GID),
            eq(-1), startsWith("traced://piece/"), anyString());
        Map<String, Object> shopify = (Map<String, Object>) appService.report().get("shopify");
        assertThat(shopify).containsEntry("settled", true).containsEntry("applied_delta", -2);
    }

    private Map<String, Object> run(UUID actor, UUID requester, boolean apply) throws Exception {
        appService.start(new ReceiptReversalService.Request(RECEIPT_ID, actor, requester, apply, false));
        long deadline = System.currentTimeMillis() + 30_000;
        Map<String, Object> r = appService.report();
        while (Boolean.TRUE.equals(r.get("running"))) {
            if (System.currentTimeMillis() > deadline) throw new AssertionError("run never finished: " + r);
            Thread.sleep(25);
            r = appService.report();
        }
        return r;
    }
}
