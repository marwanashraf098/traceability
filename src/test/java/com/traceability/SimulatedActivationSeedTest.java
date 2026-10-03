package com.traceability;

import com.traceability.integrations.shopify.ShopifyGateway;
import com.traceability.integrations.shopify.ShopifyTokenProvider;
import com.traceability.inventory.ShopifyCatalogActivationService;
import com.traceability.inventory.ShopifyInventoryReconcileService;
import com.traceability.inventory.UlidGenerator;
import com.traceability.tenancy.TenantContext;
import org.jobrunr.scheduling.JobScheduler;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.*;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Review mode S4 — catalog activation and the location seed never send a simulated-courier
 * tenant's fixture variants (external_id not a gid://shopify/ id) into the batch item-id read,
 * so a seeded variant can't fail that read for the reviewer's real variants. Real tenants: unchanged.
 *
 *   v1 activation, simulated tenant (1 real gid + 2 fixture variants): the batch read and the
 *      activation carry only the real one; outcome total 1, failed 0
 *   v2 activation, real tenant with the same mix: the batch read carries all three (today's behaviour)
 *   v3 location seed, simulated tenant: the batch read carries only the real variant; only it is seeded
 *   v4 location seed, real tenant: the batch read carries both (today's behaviour)
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SimulatedActivationSeedTest {

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

    @Autowired JdbcTemplate                     jdbc;
    @Autowired ShopifyCatalogActivationService  activation;
    @Autowired ShopifyInventoryReconcileService seed;

    @MockBean ShopifyGateway       shopifyGateway;
    @MockBean ShopifyTokenProvider tokenProvider;
    @MockBean JobScheduler         jobScheduler;

    private static final String TRACED_GID = "gid://shopify/Location/traced";

    private final class T {
        final UUID tenant = UUID.randomUUID(), owner = UUID.randomUUID(), store = UUID.randomUUID(),
                   location = UUID.randomUUID(), product = UUID.randomUUID();
        final String shop = "act-" + UUID.randomUUID() + ".myshopify.com";
        final String realGid = "gid://shopify/ProductVariant/" + Math.abs(UUID.randomUUID().getMostSignificantBits());
        final String fixture1 = "review-fixture:variant:" + UUID.randomUUID();
        final String fixture2 = "review-fixture:variant:" + UUID.randomUUID();
        final UUID realVariant, fixtureVariant1, fixtureVariant2;

        T(String name, boolean simulated) {
            jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?)", tenant, name);
            if (simulated) jdbc.update("INSERT INTO tenant_courier_simulation (tenant_id, note) VALUES (?, 'test')", tenant);
            jdbc.update("INSERT INTO users (id, tenant_id, name, email, role) VALUES (?, ?, 'Owner', ?, 'owner')",
                owner, tenant, "act-" + owner + "@test.com");
            jdbc.update("INSERT INTO stores (id, tenant_id, shop_domain, status, import_status, access_token_scopes, last_sync_at) " +
                "VALUES (?, ?, ?, 'connected', 'completed', 'read_products,write_inventory', now())", store, tenant, shop);
            jdbc.update("INSERT INTO products (id, tenant_id, store_id, external_id, title, status) " +
                "VALUES (?, ?, ?, ?, 'P', 'active')", product, tenant, store, "gid://shopify/Product/" + product);
            jdbc.update("INSERT INTO locations (id, tenant_id, name, shopify_location_id, shopify_sync_status, is_fulfillment) " +
                "VALUES (?, ?, 'Main', ?, 'linked', true)", location, tenant, TRACED_GID);
            realVariant     = variant(realGid, 2);
            fixtureVariant1 = variant(fixture1, 2);
            fixtureVariant2 = variant(fixture2, 1);
            when(tokenProvider.getValidToken(store)).thenReturn("tok-" + store);
        }

        UUID variant(String externalId, int availablePieces) {
            UUID v = jdbc.queryForObject(
                "INSERT INTO variants (tenant_id, product_id, external_id, title, sku) VALUES (?, ?, ?, 'V', ?) RETURNING id",
                UUID.class, tenant, product, externalId, "SKU-" + UUID.randomUUID());
            for (int i = 0; i < availablePieces; i++) {
                String id = UlidGenerator.generate();
                jdbc.update("INSERT INTO pieces (id, tenant_id, variant_id, barcode, short_code, status, current_location_id) " +
                    "VALUES (?, ?, ?, ?, 'A' || LPAD((abs(hashtext(?)) % 999999 + 1)::text, 6, '0'), 'available', ?)",
                    id, tenant, v, "PC-" + id, id, location);
            }
            return v;
        }
    }

    @BeforeEach
    void stubs() {
        reset(shopifyGateway, tokenProvider);
        // The batch read answers only real Shopify gids, like Shopify's nodes(ids:).
        when(shopifyGateway.resolveInventoryItemIds(anyString(), anyString(), anyList())).thenAnswer(inv -> {
            List<String> gids = inv.getArgument(2);
            return gids.stream().filter(g -> g.startsWith("gid://shopify/"))
                .collect(Collectors.toMap(g -> g, g -> "gid://shopify/InventoryItem/" + g.substring(g.lastIndexOf('/') + 1)));
        });
        when(shopifyGateway.activateInventoryItems(anyString(), anyString(), anyString(), anyList())).thenAnswer(inv -> {
            List<ShopifyGateway.ActivationRequest> reqs = inv.getArgument(3);
            return reqs.stream().map(r -> new ShopifyGateway.ActivationResult(r.inventoryItemGid(), null)).toList();
        });
        when(shopifyGateway.fetchAvailableQuantities(anyString(), anyString(), anyString(), anyList())).thenReturn(List.of());
    }

    @AfterEach
    void clear() { TenantContext.clear(); }

    @Test
    @SuppressWarnings("unchecked")
    void v1_activation_simulated_onlyRealVariants_noFailure() {
        T t = new T("V1", true);
        ShopifyCatalogActivationService.ActivationOutcome out = TenantContext.runAs(t.tenant, activation::activateAll);

        ArgumentCaptor<List<String>> read = ArgumentCaptor.forClass(List.class);
        verify(shopifyGateway).resolveInventoryItemIds(eq(t.shop), anyString(), read.capture());
        assertThat(read.getValue()).containsExactly(t.realGid);
        ArgumentCaptor<List<ShopifyGateway.ActivationRequest>> act = ArgumentCaptor.forClass(List.class);
        verify(shopifyGateway).activateInventoryItems(eq(t.shop), anyString(), eq(TRACED_GID), act.capture());
        assertThat(act.getValue()).hasSize(1);
        assertThat(out.total()).isEqualTo(1);
        assertThat(out.failed()).isZero();
        assertThat(out.failures()).isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void v2_activation_real_unchanged_allVariantsRead() {
        T t = new T("V2", false);
        ShopifyCatalogActivationService.ActivationOutcome out = TenantContext.runAs(t.tenant, activation::activateAll);

        ArgumentCaptor<List<String>> read = ArgumentCaptor.forClass(List.class);
        verify(shopifyGateway).resolveInventoryItemIds(eq(t.shop), anyString(), read.capture());
        assertThat(read.getValue()).containsExactlyInAnyOrder(t.realGid, t.fixture1, t.fixture2);
        assertThat(out.total()).isEqualTo(3);
        assertThat(out.failed()).as("today's behaviour: non-gid variants fail on a real tenant").isEqualTo(2);
    }

    @Test
    @SuppressWarnings("unchecked")
    void v3_locationSeed_simulated_onlyRealVariantRead_andSeeded() {
        T t = new T("V3", true);
        ShopifyInventoryReconcileService.ApplyResult result = TenantContext.runAs(t.tenant, () -> seed.apply(t.owner));

        ArgumentCaptor<List<String>> read = ArgumentCaptor.forClass(List.class);
        verify(shopifyGateway, atLeastOnce()).resolveInventoryItemIds(eq(t.shop), anyString(), read.capture());
        assertThat(read.getAllValues()).allSatisfy(gids -> assertThat(gids).containsOnly(t.realGid));
        assertThat(result.seeded()).isEqualTo(1);
        assertThat(result.failed()).isZero();
    }

    @Test
    @SuppressWarnings("unchecked")
    void v4_locationSeed_real_unchanged_allCandidatesRead() {
        T t = new T("V4", false);
        TenantContext.runAs(t.tenant, () -> seed.apply(t.owner));

        ArgumentCaptor<List<String>> read = ArgumentCaptor.forClass(List.class);
        verify(shopifyGateway, atLeastOnce()).resolveInventoryItemIds(eq(t.shop), anyString(), read.capture());
        assertThat(read.getAllValues().get(0)).containsExactlyInAnyOrder(t.realGid, t.fixture1, t.fixture2);
    }
}
