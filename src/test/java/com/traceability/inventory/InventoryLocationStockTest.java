package com.traceability.inventory;

import com.traceability.tenancy.TenantContext;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Inventory location selector (2026-10-08 diagnosis, Issue 4). Pieces reach another location only
 * through a transfer, which leaves them out_on_transfer (round trip) or transferred_out (relocate) —
 * never 'available' — so a location-scoped count of 'available' showed 0 there.
 *
 *   ls1 one piece relocated + one sent out on a round trip to a second location (real TransferService
 *       path) → GET /inventory/stock?locationId=second: 2 "At location", available null (shown "—");
 *       the main warehouse shows them as 0 and keeps available == on hand.
 *   ls2 the variant drawer: the second location's row has onHand 2, available null; main 0 / 0.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class InventoryLocationStockTest {

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

    @Autowired JdbcTemplate jdbc;
    @Autowired TransferService transfers;
    @Autowired PlatformTransactionManager txm;
    InventoryStockController stock;   // plain instance, as CatalogPaginationTest builds it (no @PreAuthorize proxy)

    final UUID tenant = UUID.randomUUID(), user = UUID.randomUUID(), store = UUID.randomUUID();
    final UUID product = UUID.randomUUID(), variant = UUID.randomUUID();
    final UUID main = UUID.randomUUID(), second = UUID.randomUUID();
    int seq;

    @BeforeAll
    void fixture() {
        stock = new InventoryStockController(jdbc, txm, new VariantStockService(jdbc), Clock.systemUTC());
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, 'Location Stock Co')", tenant);
        jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role) VALUES (?, ?, 'Owner', ?, 'h', 'owner')",
            user, tenant, "ls-" + tenant.toString().substring(0, 6) + "@test.local");
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status) VALUES (?, ?, 'shopify', ?, 'connected')",
            store, tenant, "ls-" + tenant.toString().substring(0, 6) + ".myshopify.com");
        jdbc.update("INSERT INTO products (id, tenant_id, store_id, external_id, title, status) VALUES (?, ?, ?, 'gid://shopify/Product/LS', 'Widget', 'active')",
            product, tenant, store);
        jdbc.update("INSERT INTO variants (id, tenant_id, product_id, external_id, title) VALUES (?, ?, ?, 'gid://shopify/ProductVariant/LS', 'Blue')",
            variant, tenant, product);
        jdbc.update("INSERT INTO locations (id, tenant_id, name, type, is_default, is_fulfillment) VALUES (?, ?, 'Main Warehouse', 'warehouse', true, true)",
            main, tenant);
        jdbc.update("INSERT INTO locations (id, tenant_id, name, type, is_default, is_fulfillment) VALUES (?, ?, 'Warehouse 2', 'warehouse', false, false)",
            second, tenant);

        TenantContext.runAs(tenant, () -> {
            // One piece moved for good (relocate → transferred_out)…
            UUID relocate = transfers.createTransfer("other", second, null, "ls", user, "relocate_out");
            assertThat(transfers.scanOut(relocate, availablePiece(), user).success()).isTrue();
            transfers.closeOneWay(relocate, user);
            // …and one out on a round trip (→ out_on_transfer).
            UUID roundTrip = transfers.createTransfer("showroom", second, null, "ls", user, "round_trip");
            assertThat(transfers.scanOut(roundTrip, availablePiece(), user).success()).isTrue();
            transfers.markSent(roundTrip, user);
        });
        assertThat(jdbc.queryForList("SELECT status::text FROM pieces WHERE tenant_id = ? AND current_location_id = ? ORDER BY 1",
            String.class, tenant, second)).containsExactly("out_on_transfer", "transferred_out");
    }

    @AfterEach
    void clear() { TenantContext.clear(); }

    @Test
    void ls1_stockAtSecondLocation_countsTransferredPieces_availableNull() {
        InventoryStockController.StockVariant atSecond = variantRow(second);
        assertThat(atSecond.onHand()).as("At location").isEqualTo(2);
        assertThat(atSecond.available()).as("nothing there can be picked — shown as a dash").isNull();

        InventoryStockController.StockVariant atMain = variantRow(main);
        assertThat(atMain.onHand()).isZero();
        assertThat(atMain.available()).as("main warehouse unchanged: available == on hand").isEqualTo(0L);
    }

    @Test
    void ls2_drawer_secondLocationRow_onHandTwo_availableNull() {
        List<InventoryStockController.LocationStock> rows =
            TenantContext.runAs(tenant, () -> stock.breakdown(variant)).locations();
        InventoryStockController.LocationStock s = rows.stream().filter(r -> r.locationId().equals(second.toString())).findFirst().orElseThrow();
        InventoryStockController.LocationStock m = rows.stream().filter(r -> r.locationId().equals(main.toString())).findFirst().orElseThrow();
        assertThat(s.onHand()).isEqualTo(2);
        assertThat(s.available()).isNull();
        assertThat(m.onHand()).isZero();
        assertThat(m.available()).isEqualTo(0L);
    }

    private InventoryStockController.StockVariant variantRow(UUID location) {
        InventoryStockController.StockPage page = TenantContext.runAs(tenant,
            () -> stock.stock(null, location, false, null, null, 20));
        return page.items().stream().flatMap(p -> p.variants().stream())
            .filter(v -> v.id().equals(variant.toString())).findFirst().orElseThrow();
    }

    private String availablePiece() {
        String id = String.format("01LOCSTOCK%016d", ++seq);
        jdbc.update("INSERT INTO pieces (id, tenant_id, variant_id, barcode, short_code, status, current_location_id) " +
            "VALUES (?, ?, ?, ?, ?, 'available'::piece_status, ?)", id, tenant, variant, "LS-" + seq, "L" + (1000000 + seq), main);
        return "LS-" + seq;
    }
}
