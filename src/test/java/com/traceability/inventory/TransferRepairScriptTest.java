package com.traceability.inventory;

import com.traceability.integrations.shopify.ShopifyGateway;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * scripts/ops/2026-10-08-transfer-shopify-repair.sql through real psql in the database container.
 * Fixture (main warehouse linked, initial seed 2 days ago):
 *   pBefore  moved out 5 days ago (before the seed)  → seed_excluded, no claim
 *   pAfter   sent out on a round trip 1 day ago      → queue_minus_1 (one claim for its transfer)
 *   pSold    sold at the destination, left 1 day ago → report only
 *   Jumi     a piece out at the purged tenant        → excluded entirely
 *   tr1 dry run → nothing written; tr2 commit → one queued repair claim; tr3 commit again → no new row
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class TransferRepairScriptTest {

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

    static final String SCRIPT = "2026-10-08-transfer-shopify-repair.sql";
    static final UUID JUMI = UUID.fromString("07fc572c-2158-412d-ae31-ec61e22378b7");

    @Autowired JdbcTemplate jdbc;
    @MockBean ShopifyGateway shopify;

    UUID tenant, afterTransfer;
    int seq;

    @BeforeAll
    void fixture() {
        tenant = UUID.randomUUID();
        UUID[] t = setupTenant(tenant, "Repair Co", true);
        UUID main = t[0], away = t[1], variant = t[2], owner = t[3];
        jdbc.update("INSERT INTO shopify_inventory_adjustments (tenant_id, batch_id, variant_id, location_id, delta, " +
            "trigger_type, trigger_id, payload, status, created_at) VALUES (?, ?, ?, ?, 5, 'initial_seed', 'seed', '{}'::jsonb, " +
            "'applied', now() - interval '2 days')", tenant, UUID.randomUUID(), variant, main);
        outPiece(tenant, variant, away, owner, "relocate_out", "transferred_out", "relocated", "5 days");
        afterTransfer = outPiece(tenant, variant, away, owner, "round_trip", "out_on_transfer", null, "1 day");
        outPiece(tenant, variant, away, owner, "round_trip", "sold", "sold", "1 day");

        UUID[] j = setupTenant(JUMI, "Jumi Worldwide", true);
        outPiece(JUMI, j[2], j[1], j[3], "round_trip", "out_on_transfer", null, "1 day");
    }

    @Test @Order(1)
    void tr1_dryRun_writesNothing_reportsDecisions() throws Exception {
        ExecResult r = psql(false);
        assertThat(r.getExitCode()).as(r.getStderr()).isZero();
        assertThat(r.getStdout()).contains("seed_excluded").contains("queue_minus_1")
            .contains("left after the seed").contains("dry run — ROLLED BACK").doesNotContain("Jumi");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM transfer_shopify_syncs", Integer.class)).isZero();
    }

    @Test @Order(2)
    void tr2_commit_queuesOneClaim_onlyForThePostSeedDeparture() throws Exception {
        ExecResult r = psql(true);
        assertThat(r.getExitCode()).as(r.getStderr()).isZero();
        assertThat(jdbc.queryForList("SELECT transfer_id FROM transfer_shopify_syncs WHERE status = 'queued' AND source = 'repair'",
            UUID.class)).containsExactly(afterTransfer);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM transfer_shopify_syncs WHERE tenant_id = ?", Integer.class, JUMI)).isZero();
    }

    @Test @Order(3)
    void tr3_commitAgain_isIdempotent() throws Exception {
        assertThat(psql(true).getExitCode()).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM transfer_shopify_syncs", Integer.class)).isEqualTo(1);
    }

    private ExecResult psql(boolean commit) throws Exception {
        POSTGRES.copyFileToContainer(MountableFile.forHostPath("scripts/ops/" + SCRIPT), "/tmp/" + SCRIPT);
        List<String> cmd = new ArrayList<>(List.of("psql", "-U", "postgres", "-d", "traceability_test", "-v", "ON_ERROR_STOP=1"));
        if (commit) cmd.addAll(List.of("-v", "commit=yes"));
        cmd.addAll(List.of("-f", "/tmp/" + SCRIPT));
        return POSTGRES.execInContainer(cmd.toArray(String[]::new));
    }

    /** main, away, variant, owner. */
    private UUID[] setupTenant(UUID id, String name, boolean linked) {
        UUID store = UUID.randomUUID(), main = UUID.randomUUID(), away = UUID.randomUUID(), product = UUID.randomUUID(),
            variant = UUID.randomUUID(), owner = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?)", id, name);
        jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role) VALUES (?, ?, 'O', ?, 'h', 'owner')",
            owner, id, "o-" + owner + "@t.local");
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status) VALUES (?, ?, 'shopify', ?, 'connected')",
            store, id, "s-" + id + ".myshopify.com");
        jdbc.update("INSERT INTO locations (id, tenant_id, name, type, is_default, is_fulfillment, shopify_location_id, shopify_sync_status) " +
            "VALUES (?, ?, 'Main', 'warehouse', true, true, ?, ?)", main, id, linked ? "gid://shopify/Location/9" : null,
            linked ? "linked" : "unsynced");
        jdbc.update("INSERT INTO locations (id, tenant_id, name, type, is_default, is_fulfillment) VALUES (?, ?, 'Warehouse 2', 'warehouse', false, false)",
            away, id);
        jdbc.update("INSERT INTO products (id, tenant_id, store_id, external_id, title, status) VALUES (?, ?, ?, ?, 'P', 'active')",
            product, id, store, "gid://shopify/Product/" + product);
        jdbc.update("INSERT INTO variants (id, tenant_id, product_id, external_id, title, sku) VALUES (?, ?, ?, ?, 'V', ?)",
            variant, id, product, "gid://shopify/ProductVariant/" + variant, "SKU-" + name.charAt(0));
        return new UUID[]{main, away, variant, owner};
    }

    /** A piece that left the main warehouse {@code ago} on a transfer of {@code mode}; returns the transfer id. */
    private UUID outPiece(UUID tenant, UUID variant, UUID away, UUID owner, String mode, String pieceStatus,
                          String outcome, String ago) {
        String piece = String.format("01TRREPAIR%016d", ++seq);
        jdbc.update("INSERT INTO pieces (id, tenant_id, variant_id, barcode, short_code, status, current_location_id) " +
            "VALUES (?, ?, ?, ?, ?, ?::piece_status, ?)", piece, tenant, variant, "R-" + piece, "R" + (1000000 + seq), pieceStatus,
            "sold".equals(pieceStatus) ? null : away);
        UUID tr = jdbc.queryForObject(
            "INSERT INTO transfers (tenant_id, transfer_type, destination_location_id, status, created_by, transfer_mode, sent_at, closed_at, created_at) " +
            "VALUES (?, 'other', ?, ?, ?, ?, " +
            "  CASE WHEN ? = 'round_trip' THEN now() - ?::interval END, " +
            "  CASE WHEN ? = 'relocate_out' THEN now() - ?::interval END, now() - ?::interval) RETURNING id",
            UUID.class, tenant, away, "relocate_out".equals(mode) ? "closed" : "sent", owner, mode,
            mode, ago, mode, ago, ago);
        UUID line = jdbc.queryForObject("INSERT INTO transfer_lines (id, tenant_id, transfer_id, variant_id) VALUES (gen_random_uuid(), ?, ?, ?) RETURNING id",
            UUID.class, tenant, tr, variant);
        jdbc.update("INSERT INTO transfer_pieces (id, tenant_id, transfer_id, line_id, piece_id, outcome) VALUES (gen_random_uuid(), ?, ?, ?, ?, ?)",
            tenant, tr, line, piece, outcome);
        return tr;
    }
}
