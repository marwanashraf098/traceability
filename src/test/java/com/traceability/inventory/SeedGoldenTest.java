package com.traceability.inventory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.traceability.account.AuditService;
import com.traceability.integrations.shopify.ShopifyGateway;
import com.traceability.integrations.shopify.ShopifyTokenProvider;
import com.traceability.integrations.shopify.StoreRepository;
import com.traceability.tenancy.TenantContext;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.mockito.stubbing.Answer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Seed fix (activation perf, Part B) — golden before/after. The seed's candidate filter, batched
 * reads and pre-lock resolution are fetch-only: what it WRITES to Shopify must be identical.
 *
 * The fixture uses fixed ids, and the fake gateway dispatches on method NAME, so this class
 * compiles and runs against the code before the change too. GOLDEN below is exactly what that
 * run recorded (every inventoryAdjustQuantities call: item, location, delta, reason, idempotency
 * key) plus the initial_seed audit rows; the new code must reproduce it byte for byte.
 *
 * Fixture (Traced on_hand at the fulfillment location / Shopify available at the Traced GID):
 *   A 3 / 0 → seed +3      B 2 / 5 → skipped (non-zero)     C 0 / 0 → nothing
 *   D 0 / 4 → nothing      E 1 / 0 → seed +1                F 0 counted (sold + delivered) / 0 → nothing
 *   G 0 at the fulfillment location (2 at a showroom) / 0 → nothing
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
class SeedGoldenTest {

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

    static final UUID TENANT   = UUID.fromString("5eed0000-0000-4000-8000-000000000001");
    static final UUID STORE    = UUID.fromString("5eed0000-0000-4000-8000-000000000002");
    static final UUID PRODUCT  = UUID.fromString("5eed0000-0000-4000-8000-000000000003");
    static final UUID MAIN     = UUID.fromString("5eed0000-0000-4000-8000-000000000004");
    static final UUID SHOWROOM = UUID.fromString("5eed0000-0000-4000-8000-000000000005");
    static final String SHOP   = "seed-golden.myshopify.com";
    static final String TRACED = "gid://shopify/Location/seed-golden";

    /** Recorded from the pre-change code (see class comment). */
    static final List<String> GOLDEN_ADJUSTMENTS = List.of(
        "gid://shopify/InventoryItem/A|gid://shopify/Location/seed-golden|3|correction|c7453be4-73b5-3474-a938-7b3c83c6fdd4",
        "gid://shopify/InventoryItem/E|gid://shopify/Location/seed-golden|1|correction|041433b5-3cad-30d5-92ce-499dd5bcc140");
    static final List<String> GOLDEN_AUDIT = List.of(
        "5eed0001-0000-4000-8000-000000000001|3|applied",
        "5eed0001-0000-4000-8000-000000000005|1|applied");

    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager txm;
    @Autowired AuditService auditService;
    @Autowired StoreRepository storeRepository;

    @Test
    void golden_seedWritesAreIdenticalBeforeAndAfter() {
        fixture();
        Map<String, Integer> shopifyAvailable = Map.of(
            "gid://shopify/InventoryItem/B", 5, "gid://shopify/InventoryItem/D", 4);
        List<String> adjustments = new ArrayList<>();

        Answer<Object> fakeShopify = inv -> switch (inv.getMethod().getName()) {
            case "resolveInventoryItemId" -> itemFor(inv.getArgument(2));
            case "resolveInventoryItemIds" -> {
                Map<String, String> out = new HashMap<>();
                for (Object g : (List<?>) inv.getArgument(2)) out.put((String) g, itemFor((String) g));
                yield out;
            }
            case "fetchAvailableQuantities" -> {
                List<ShopifyGateway.InventoryLevel> out = new ArrayList<>();
                for (Object item : (List<?>) inv.getArgument(3)) {
                    out.add(new ShopifyGateway.InventoryLevel((String) item, shopifyAvailable.getOrDefault((String) item, 0)));
                }
                yield out;
            }
            case "adjustInventoryQuantities" -> {
                adjustments.add(inv.getArgument(2) + "|" + inv.getArgument(3) + "|" + inv.getArgument(4) + "|"
                    + inv.getArgument(5) + "|" + inv.getArgument(6));
                yield null;
            }
            default -> Mockito.RETURNS_DEFAULTS.answer(inv);
        };
        ShopifyGateway shopify = Mockito.mock(ShopifyGateway.class, fakeShopify);
        ShopifyTokenProvider tokens = Mockito.mock(ShopifyTokenProvider.class);
        Mockito.when(tokens.getValidToken(STORE)).thenReturn("tok");
        ShopifyInventoryReconcileService seed = new ShopifyInventoryReconcileService(
            jdbc, txm, shopify, tokens, new ObjectMapper(), auditService, storeRepository);

        TenantContext.set(TENANT);
        ShopifyInventoryReconcileService.ApplyResult result;
        try {
            result = seed.apply(null);
        } finally {
            TenantContext.clear();
        }

        System.out.println("SEED-GOLDEN adjustments=" + adjustments.stream().sorted().toList());
        assertThat(adjustments.stream().sorted().toList()).isEqualTo(GOLDEN_ADJUSTMENTS);
        assertThat(jdbc.queryForList(
            "SELECT variant_id::text || '|' || delta || '|' || status FROM shopify_inventory_adjustments " +
            "WHERE tenant_id = ? AND trigger_type = 'initial_seed' ORDER BY variant_id", String.class, TENANT))
            .isEqualTo(GOLDEN_AUDIT);
        assertThat(result.seeded()).isEqualTo(2);
        assertThat(result.failed()).isZero();
    }

    private static String itemFor(String variantGid) {
        return "gid://shopify/InventoryItem/" + variantGid.substring(variantGid.lastIndexOf('/') + 1);
    }

    private void fixture() {
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, 'Seed golden')", TENANT);
        jdbc.update("INSERT INTO stores (id, tenant_id, shop_domain, status) VALUES (?, ?, ?, 'connected')", STORE, TENANT, SHOP);
        jdbc.update("INSERT INTO products (id, tenant_id, store_id, external_id, title, status) VALUES (?, ?, ?, 'gid://shopify/Product/sg', 'P', 'active')",
            PRODUCT, TENANT, STORE);
        jdbc.update("INSERT INTO locations (id, tenant_id, name, shopify_location_id, shopify_sync_status, is_fulfillment) " +
            "VALUES (?, ?, 'Main Warehouse', ?, 'linked', true)", MAIN, TENANT, TRACED);
        jdbc.update("INSERT INTO locations (id, tenant_id, name, is_fulfillment) VALUES (?, ?, 'Showroom', false)", SHOWROOM, TENANT);
        int piece = 0;
        for (char c : "ABCDEFG".toCharArray()) {
            UUID variant = UUID.fromString(String.format("5eed0001-0000-4000-8000-0000000000%02d", c - 'A' + 1));
            jdbc.update("INSERT INTO variants (id, tenant_id, product_id, external_id, title, sku) VALUES (?, ?, ?, ?, ?, ?)",
                variant, TENANT, PRODUCT, "gid://shopify/ProductVariant/" + c, "V" + c, "SKU-" + c);
            List<String[]> pieces = switch (c) {
                case 'A' -> List.of(p("available", MAIN), p("reserved", MAIN), p("packed", MAIN));
                case 'B' -> List.of(p("available", MAIN), p("awaiting_pickup", MAIN));
                case 'E' -> List.<String[]>of(p("available", MAIN));
                case 'F' -> List.of(p("sold", MAIN), p("delivered", MAIN));
                case 'G' -> List.of(p("available", SHOWROOM), p("available", SHOWROOM));
                default -> List.of();
            };
            for (String[] pc : pieces) {
                String id = String.format("01SEEDGOLDEN%014d", ++piece);
                jdbc.update("INSERT INTO pieces (id, tenant_id, variant_id, status, barcode, short_code, current_location_id) " +
                    "VALUES (?, ?, ?, ?::piece_status, ?, ?, ?::uuid)",
                    id, TENANT, variant, pc[0], "SG-" + piece, String.format("S%06d", piece), pc[1]);
            }
        }
    }

    private static String[] p(String status, UUID location) {
        return new String[]{status, location.toString()};
    }
}
