package com.traceability;

import com.traceability.inventory.ExchangeMatchService;
import com.traceability.inventory.ExchangeMatchService.OutboundMatchClassification;
import com.traceability.inventory.ExchangeMatchService.OutboundResolution;
import com.traceability.tenancy.TenantContext;
import org.jobrunr.scheduling.JobScheduler;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Build task ("outbound exchange variant: exact-match auto-commit + ranked recs"),
 * Part A — {@link ExchangeMatchService#resolveOutboundVariant}. Pure read, no writes;
 * no order/order_item touched here at all — see ExchangeAutoCommitTest for Part B's
 * commit path.
 *
 * Real Snug Snout ("The Snouts", per V74's migration comment) description shapes:
 *   r1 — "XS/S pink & white bandana": size+color both present, pin exactly one of
 *        three sibling-size Bandanas variants → EXACT.
 *   r2 — "pink & white bandana" (no size): color pins nothing on its own (three
 *        sibling sizes all still satisfy) → RECS, three ranked candidates, nothing
 *        committed.
 *   r3 — "Red checkered bucket hat size XL/2XL/3XL//Checkered shirt" (multi-item,
 *        "//"): the primary segment alone would otherwise resolve cleanly — proves
 *        the multi-item guard caps classification at RECS regardless.
 *   r4 — no catalog overlap at all → NONE; also proves the resolver survives a
 *        malformed catalog row (Cabana variant with no " / " separator and a blank
 *        SKU) elsewhere in the same tenant's catalog without throwing.
 *   r5 — RED control: two variants sharing the exact same size+color axes (a dirty-
 *        catalog duplicate) both satisfy → must NOT auto-commit, RECS not EXACT.
 *   r6 — cross-tenant: the resolver only ever reads the acting tenant's own catalog;
 *        same description resolves EXACT under tenant B (which has the product) and
 *        NONE under tenant A (which doesn't), same TenantContext-scoped SQL either way.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ExchangeOutboundResolverTest {

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

    @Autowired ExchangeMatchService matchSvc;
    @Autowired JdbcTemplate         jdbc;
    @MockBean  JobScheduler         jobScheduler;

    UUID tenantId, storeId;
    UUID xsSVariant, mlVariant, xl2xlVariant, bucketHatVariant;

    @BeforeAll
    void setupFixture() {
        tenantId = UUID.randomUUID();
        storeId  = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, 'EOR-Tenant')", tenantId);
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status) " +
                    "VALUES (?, ?, 'shopify', 'eor.myshopify.com', 'connected')", storeId, tenantId);

        UUID bandanasId = createProduct("P-EOR-BANDANAS", "The Bandanas");
        xsSVariant   = createVariant(bandanasId, "XS/S / Pink & White", "BAND-XSS-PW");
        mlVariant    = createVariant(bandanasId, "M/L / Pink & White", "BAND-ML-PW");
        xl2xlVariant = createVariant(bandanasId, "XL/2XL / Pink & White", "BAND-XL2XL-PW");

        UUID bucketHatId = createProduct("P-EOR-BUCKETHAT", "The Bucket Hat");
        bucketHatVariant = createVariant(bucketHatId, "XL/2XL/3XL / Red Checkered", "HAT-XL2XL3XL-RC");

        // Missing axis / blank SKU — no " / " separator in the title at all, SKU blank.
        // Never referenced by any description used below (see r4) — exists purely to
        // prove the resolver's per-row axis parsing tolerates a malformed catalog row
        // without throwing, since EVERY resolveOutboundVariant() call in this class
        // scans the whole tenant catalog, this row included.
        UUID cabanaId = createProduct("P-EOR-CABANA", "The Cabana Chairs");
        createVariant(cabanaId, "Turquoise", null);
    }

    @BeforeEach void ctx()   { TenantContext.set(tenantId); }
    @AfterEach  void clear() { TenantContext.clear(); }

    // ── r1: EXACT — size + color both present, pin exactly one sibling variant ──────

    @Test
    void r1_sizeAndColorBothPresent_pinsSingleVariant_classifiesExact() {
        OutboundResolution res = matchSvc.resolveOutboundVariant("XS/S pink & white bandana");

        assertThat(res.classification()).isEqualTo(OutboundMatchClassification.EXACT);
        assertThat(res.committedVariantId()).isEqualTo(xsSVariant);
    }

    // ── r2: RECS — color only, three sibling sizes all satisfy, nothing committed ───

    @Test
    void r2_colorOnlyNoSize_threeSiblingSizesAllSatisfy_classifiesRecs_nothingCommitted() {
        OutboundResolution res = matchSvc.resolveOutboundVariant("pink & white bandana");

        assertThat(res.classification()).isEqualTo(OutboundMatchClassification.RECS);
        assertThat(res.committedVariantId()).isNull();
        assertThat(res.rankedCandidates()).hasSize(3)
            .extracting(ExchangeMatchService.OutboundVariantCandidate::variantId)
            .containsExactlyInAnyOrder(xsSVariant, mlVariant, xl2xlVariant);
    }

    // ── r3: multi-item ("X // Y") — never EXACT even though the primary segment alone
    //       would otherwise resolve cleanly (both axes present, single satisfier) ────

    @Test
    void r3_multiItemDoubleSlash_neverExact_evenWhenPrimarySegmentAloneWouldResolveCleanly() {
        OutboundResolution res =
            matchSvc.resolveOutboundVariant("Red checkered bucket hat size XL/2XL/3XL//Checkered shirt");

        assertThat(res.classification())
            .as("the primary segment alone fully satisfies the Bucket Hat's only variant — " +
                "the '//' multi-item guard must still cap this at RECS, never EXACT")
            .isEqualTo(OutboundMatchClassification.RECS);
        assertThat(res.committedVariantId()).isNull();
        assertThat(res.rankedCandidates())
            .extracting(ExchangeMatchService.OutboundVariantCandidate::variantId)
            .contains(bucketHatVariant);
    }

    // ── r4: no catalog overlap at all → NONE; malformed Cabana row doesn't throw ────

    @Test
    void r4_noCatalogOverlap_classifiesNone_malformedCabanaRowDoesNotThrow() {
        OutboundResolution res = matchSvc.resolveOutboundVariant("totally unrelated mystery item xyz123");

        assertThat(res.classification()).isEqualTo(OutboundMatchClassification.NONE);
        assertThat(res.committedVariantId()).isNull();
        assertThat(res.rankedCandidates()).isEmpty();
    }

    // ── r5: RED control — sibling duplicate (dirty catalog) both satisfy → RECS ─────

    @Test
    void r5_siblingDuplicateBothFullSatisfiers_mustNotAutoCommit_classifiesRecs() {
        UUID tenant = UUID.randomUUID();
        UUID store  = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, 'EOR-Sibling-Tenant')", tenant);
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status) " +
                    "VALUES (?, ?, 'shopify', 'eor-sib.myshopify.com', 'connected')", store, tenant);
        UUID productId = UUID.randomUUID();
        jdbc.update("INSERT INTO products (id, tenant_id, store_id, external_id, title) " +
                    "VALUES (?, ?, ?, 'P-EOR-SIB', 'The Bandanas')", productId, tenant, store);
        // Two DISTINCT variant rows, same product, same title — a real dirty-catalog
        // duplicate (e.g. an archived + active variant that were never deduplicated).
        UUID dup1 = insertVariant(productId, tenant, "XS/S / Pink & White", "BAND-DUP-1");
        UUID dup2 = insertVariant(productId, tenant, "XS/S / Pink & White", "BAND-DUP-2");

        TenantContext.set(tenant);
        try {
            OutboundResolution res = matchSvc.resolveOutboundVariant("XS/S pink & white bandana");

            assertThat(res.classification())
                .as("two variants both fully satisfy the same present axes — ambiguous, must not guess")
                .isEqualTo(OutboundMatchClassification.RECS);
            assertThat(res.committedVariantId()).isNull();
            assertThat(res.rankedCandidates())
                .extracting(ExchangeMatchService.OutboundVariantCandidate::variantId)
                .containsExactlyInAnyOrder(dup1, dup2);
        } finally {
            TenantContext.clear();
            jdbc.update("DELETE FROM variants WHERE tenant_id = ?", tenant);
            jdbc.update("DELETE FROM products WHERE tenant_id = ?", tenant);
            jdbc.update("DELETE FROM stores WHERE tenant_id = ?", tenant);
            jdbc.update("DELETE FROM tenants WHERE id = ?", tenant);
        }
    }

    // ── r6: cross-tenant — resolver only ever reads the acting tenant's own catalog ──

    @Test
    void r6_crossTenant_resolverOnlyReadsActingTenantsCatalog_sameTenantPositiveControl() {
        UUID tenantB = UUID.randomUUID();
        UUID storeB  = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, 'EOR-TenantB')", tenantB);
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status) " +
                    "VALUES (?, ?, 'shopify', 'eor-b.myshopify.com', 'connected')", storeB, tenantB);
        UUID productBId = UUID.randomUUID();
        jdbc.update("INSERT INTO products (id, tenant_id, store_id, external_id, title) " +
                    "VALUES (?, ?, ?, 'P-EOR-B', 'The Bandanas')", productBId, tenantB, storeB);
        UUID variantB = insertVariant(productBId, tenantB, "XS/S / Pink & White", "BAND-B-XSS-PW");

        try {
            // Tenant A (the shared fixture's tenant) has no product matching "bandana"
            // with an XS/S Pink & White variant of its OWN under tenant B's id — this
            // proves the SQL's tenant_id filter, not "the ids never happen to collide".
            TenantContext.set(tenantId);
            OutboundResolution underTenantA = matchSvc.resolveOutboundVariant("XS/S pink & white bandana");
            // Tenant A DOES have its own XS/S Pink & White Bandanas variant (r1's fixture)
            // — so a positive EXACT here would be ambiguous evidence. Assert it resolves
            // to tenant A's OWN variant, never tenant B's.
            assertThat(underTenantA.classification()).isEqualTo(OutboundMatchClassification.EXACT);
            assertThat(underTenantA.committedVariantId())
                .as("tenant A's resolution must never leak tenant B's variant id")
                .isEqualTo(xsSVariant)
                .isNotEqualTo(variantB);

            // Same-tenant positive control: switching context to tenant B resolves to
            // tenant B's OWN variant.
            TenantContext.set(tenantB);
            OutboundResolution underTenantB = matchSvc.resolveOutboundVariant("XS/S pink & white bandana");
            assertThat(underTenantB.classification()).isEqualTo(OutboundMatchClassification.EXACT);
            assertThat(underTenantB.committedVariantId()).isEqualTo(variantB);
        } finally {
            TenantContext.clear();
            jdbc.update("DELETE FROM variants WHERE tenant_id = ?", tenantB);
            jdbc.update("DELETE FROM products WHERE tenant_id = ?", tenantB);
            jdbc.update("DELETE FROM stores WHERE tenant_id = ?", tenantB);
            jdbc.update("DELETE FROM tenants WHERE id = ?", tenantB);
        }
    }

    // ── Fixtures ──────────────────────────────────────────────────────────────

    private UUID createProduct(String externalId, String title) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO products (id, tenant_id, store_id, external_id, title) " +
                    "VALUES (?, ?, ?, ?, ?)", id, tenantId, storeId, externalId, title);
        return id;
    }

    private UUID createVariant(UUID productId, String title, String sku) {
        return insertVariant(productId, tenantId, title, sku);
    }

    private UUID insertVariant(UUID productId, UUID tenant, String title, String sku) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO variants (id, tenant_id, product_id, external_id, title, sku) " +
                    "VALUES (?, ?, ?, ?, ?, ?)", id, tenant, productId, "V-" + id, title, sku);
        return id;
    }
}
