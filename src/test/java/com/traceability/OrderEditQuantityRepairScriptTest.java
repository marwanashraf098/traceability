package com.traceability;

import org.jobrunr.scheduling.JobScheduler;
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

import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * scripts/ops/2026-10-21-order-edit-quantity-repair.sql, run with psql exactly as on prod (postgres role,
 * -v ON_ERROR_STOP=1).
 *
 *   rs1 dry run: lists the candidate lines before → after and the GraphQL lines it leaves alone; writes nothing
 *   rs2 a candidate line with an active allocation aborts the commit run — nothing written
 *   rs3 commit: only the candidates change (removed line → 0, reduced line → its current quantity); the
 *       frozen order, the cancelled order, the internal order, the untouched line and the GraphQL line keep
 *       theirs; the in-transaction proof and the after-commit read-back both pass
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class OrderEditQuantityRepairScriptTest {

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

    @Autowired JdbcTemplate jdbc;
    @MockBean  JobScheduler jobScheduler;

    static final String SCRIPT = "scripts/ops/2026-10-21-order-edit-quantity-repair.sql";

    PackFixtures f;
    UUID actor, variantM, variantS;
    UUID removedM, keptS, reduced, frozenLine, cancelledLine, internalLine, graphqlLine;
    String pieceS;

    @BeforeAll
    void seed() {
        f = new PackFixtures(jdbc, "Dolt-repair");
        actor = f.user("Ops", "owner");
        variantM = f.variant("BLACK LEATHER SET", "BLS-M", null);
        variantS = f.variant("BLACK LEATHER SET", "BLS-S", null);

        UUID o1 = f.order("#16443", 0);
        f.forward(o1);
        removedM = line(o1, variantM, 1, 0);
        keptS = line(o1, variantS, 1, 1);

        UUID o2 = f.order("#16500", 0);
        reduced = line(o2, variantM, 3, 1);

        UUID o3 = f.order("#80211", 3);
        String tn = f.leg(o3, "forward", "returned", null);
        jdbc.update("INSERT INTO shipment_status_history (tenant_id, shipment_id, internal_state, occurred_at) " +
            "SELECT tenant_id, id, 'with_courier', now() - interval '2 days' FROM shipments WHERE tracking_number = ?", tn);
        frozenLine = line(o3, variantM, 1, 0);

        UUID o4 = f.orderWith("#16510", "cancelled", 0, "EXT-" + UUID.randomUUID());
        cancelledLine = line(o4, variantM, 1, 0);

        UUID o5 = f.orderWith("EX-1", "new", 0, "internal:exchange:" + PackFixtures.nextTracking());
        internalLine = line(o5, variantM, 1, 0);

        UUID o6 = f.order("#16520", 0);
        graphqlLine = jdbc.queryForObject(
            "INSERT INTO order_items (tenant_id, order_id, variant_id, quantity, external_id, raw) VALUES (?, ?, ?, 1, ?, " +
            "jsonb_build_object('gid', ?::text, 'quantity', 1, 'variantGid', 'gid://shopify/ProductVariant/1')) RETURNING id",
            UUID.class, f.tenant, o6, variantS, "gid://shopify/LineItem/" + UUID.randomUUID(), "gid://shopify/LineItem/x");

        pieceS = f.piece(variantS, actor);
    }

    @Test @Order(1)
    void rs1_dryRun_listsCandidates_writesNothing() throws Exception {
        String before = fingerprint();
        ExecResult r = psql(false);
        assertThat(r.getExitCode()).as(r.getStderr()).isZero();
        String out = r.getStdout();
        assertThat(out).contains("DRY RUN").doesNotContain("COMMITTED");
        assertThat(line(out, "#16443")).contains("BLS-M").containsPattern("\\|\\s+1 \\|\\s+0\\s*$");
        assertThat(line(out, "#16500")).containsPattern("\\|\\s+3 \\|\\s+1\\s*$");
        assertThat(out).doesNotContain("#80211").doesNotContain("#16510").doesNotContain("EX-1");
        assertThat(out).contains("#16520");   // the GraphQL line — listed, never changed
        assertThat(fingerprint()).as("dry run writes nothing").isEqualTo(before);
    }

    @Test @Order(2)
    void rs2_liveAllocationOnACandidate_abortsTheCommitRun() throws Exception {
        String before = fingerprint();
        jdbc.update("INSERT INTO allocations (piece_id, order_item_id, tenant_id, status) VALUES (?, ?, ?, 'active')",
            f.pieceId(pieceS), reduced, f.tenant);
        try {
            ExecResult r = psql(true);
            assertThat(r.getExitCode()).isNotZero();
            assertThat(r.getStderr()).contains("ABORTED").contains("#16500");
            assertThat(r.getStdout()).doesNotContain("COMMITTED");
        } finally {
            jdbc.update("DELETE FROM allocations WHERE order_item_id = ?", reduced);
        }
        assertThat(fingerprint()).isEqualTo(before);
    }

    @Test @Order(3)
    void rs3_commit_changesOnlyTheCandidates_proofPasses() throws Exception {
        ExecResult r = psql(true);
        assertThat(r.getExitCode()).as(r.getStderr()).isZero();
        assertThat(r.getStdout()).contains("COMMITTED");
        assertThat(r.getStderr()).contains("proof: wrote exactly the 2 listed rows");
        assertThat(r.getStdout()).containsPattern("rows_written_by_repair \\| outside_the_list \\| listed_at_new_quantity \\| listed")
            .containsPattern("\\n\\s+2 \\|\\s+0 \\|\\s+2 \\|\\s+2\\s*\\n");

        assertThat(qty(removedM)).isZero();
        assertThat(qty(reduced)).isEqualTo(1);
        assertThat(qty(keptS)).isEqualTo(1);
        assertThat(qty(frozenLine)).as("frozen order keeps what was shipped").isEqualTo(1);
        assertThat(qty(cancelledLine)).isEqualTo(1);
        assertThat(qty(internalLine)).isEqualTo(1);
        assertThat(qty(graphqlLine)).isEqualTo(1);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /** A REST-shaped line (raw quantity + current_quantity → V149 original_qty / current_qty). */
    private UUID line(UUID order, UUID variant, int quantity, int current) {
        return jdbc.queryForObject(
            "INSERT INTO order_items (tenant_id, order_id, variant_id, quantity, external_id, raw) VALUES (?, ?, ?, ?, ?, " +
            "jsonb_build_object('admin_graphql_api_id', ?::text, 'quantity', ?::int, 'current_quantity', ?::int)) RETURNING id",
            UUID.class, f.tenant, order, variant, quantity, "gid://shopify/LineItem/" + UUID.randomUUID(),
            "gid://shopify/LineItem/raw", quantity, current);
    }

    private int qty(UUID item) {
        return jdbc.queryForObject("SELECT quantity FROM order_items WHERE id = ?", Integer.class, item);
    }

    private ExecResult psql(boolean commit) throws Exception {
        POSTGRES.copyFileToContainer(MountableFile.forHostPath(Path.of(SCRIPT).toAbsolutePath()), "/tmp/repair.sql");
        return commit
            ? POSTGRES.execInContainer("psql", "-U", "postgres", "-d", "traceability_test", "-v", "ON_ERROR_STOP=1",
                "-v", "commit=yes", "-f", "/tmp/repair.sql")
            : POSTGRES.execInContainer("psql", "-U", "postgres", "-d", "traceability_test", "-v", "ON_ERROR_STOP=1",
                "-f", "/tmp/repair.sql");
    }

    /** Every order_items and allocations row, as text. */
    private String fingerprint() {
        return String.join("\n", jdbc.queryForList(
            "SELECT oi::text FROM order_items oi UNION ALL SELECT a::text FROM allocations a ORDER BY 1", String.class));
    }

    private static String line(String out, String needle) {
        return out.lines().filter(l -> l.contains(needle)).findFirst().orElse("<no line for " + needle + ">");
    }
}
