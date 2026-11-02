package com.traceability;

import com.traceability.courier.GenericTrackingKey;
import com.traceability.inventory.OrderLineFreeze;
import com.traceability.inventory.WaybillResolver;
import com.traceability.tenancy.TenantContext;
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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * scripts/ops/2026-11-01-generic-cancel-history-repair.sql (D6), run with psql exactly as on prod (postgres role,
 * -v ON_ERROR_STOP=1). Fixture: the Maron Fashion shape (a generic Aramex leg cancelled with the old history row and
 * a newer live leg; one still waiting for its refulfill) plus everything the script must leave alone.
 *
 *   z   the script inlines the pre-D7 OrderLineFreeze.frozenSql verbatim (whitespace aside) — it ran on prod
 *       before D7 (2026-11-02) and is kept unchanged (PreD7Freeze)
 *   rs1 dry run: lists the two candidates with the simulated after-check (unfrozen, not moving), lists the three
 *       orders another freeze clause still holds as skipped, never lists the controls, writes nothing
 *   rs2 commit with the wrong expect_orders aborts — nothing written, no backup table
 *   rs3 commit: only the candidate rows are deleted, the backup holds exactly them, app_user can't read it; the
 *       order is unfrozen and its live waybill opens in waybill mode (real OrderLineFreeze + WaybillResolver)
 *   rs4 a second commit run refuses (the backup table exists) — nothing written
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class GenericCancelHistoryRepairScriptTest {

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

    @Autowired JdbcTemplate    jdbc;
    @Autowired WaybillResolver resolver;
    @MockBean  JobScheduler    jobScheduler;

    static final String SCRIPT = "scripts/ops/2026-11-01-generic-cancel-history-repair.sql";
    static final String BACKUP = "ops_backup_2026_11_01_generic_cancel_history";

    PackFixtures f;
    UUID packer, variant;
    UUID refulfilled, waiting, otherHistory, packedLeft, eventLeft, bostaManual, internal, cancelledOrder, pickupSource;
    List<Long> candidateRows;

    @BeforeAll
    void seed() {
        f = new PackFixtures(jdbc, "Maron Fashion");
        jdbc.update("UPDATE tenants SET pick_pack_mode = 'waybill_scan', generic_courier_enabled = true WHERE id = ?", f.tenant);
        packer = f.user("Mona", "worker");
        variant = f.variant("Abaya", "AB-1", null);

        // Candidates.
        refulfilled = order("#37108");
        long r1 = legacyCancel(refulfilled, "ARV3626150");
        generic(refulfilled, "ARV3626181", "created");
        waiting = order("#37344");
        long r2 = legacyCancel(waiting, "ARV3626151");
        candidateRows = List.of(r1, r2);

        // Skipped: another freeze clause still holds.
        otherHistory = order("#37470");
        legacyCancel(otherHistory, "ARV3626152");
        UUID moving = generic(otherHistory, "ARV3626413", "with_courier");
        jdbc.update("INSERT INTO shipment_status_history (tenant_id, shipment_id, internal_state, source) " +
            "VALUES (?, ?, 'with_courier', 'pickup_scan')", f.tenant, moving);

        packedLeft = order("#37493");
        legacyCancel(packedLeft, "ARV3626153");
        String p1 = f.pieceId(f.piece(variant, packer));
        jdbc.update("UPDATE pieces SET status = 'with_courier' WHERE id = ?", p1);
        jdbc.update("INSERT INTO allocations (piece_id, order_item_id, tenant_id, status) VALUES (?, ?, ?, 'packed')",
            p1, item(packedLeft), f.tenant);

        eventLeft = order("#37494");
        legacyCancel(eventLeft, "ARV3626154");
        String p2 = f.pieceId(f.piece(variant, packer));
        jdbc.update("INSERT INTO allocations (piece_id, order_item_id, tenant_id, status) VALUES (?, ?, ?, 'released')",
            p2, item(eventLeft), f.tenant);
        jdbc.update("INSERT INTO piece_events (tenant_id, piece_id, event_type, actor_user_id, order_id, from_status, to_status) " +
            "VALUES (?, ?, 'delivered', ?, ?, 'with_courier', 'delivered')", f.tenant, p2, packer, eventLeft);

        // Controls — never candidates.
        bostaManual = order("#17243");   // the Dolt.eg shape: a Bosta leg with a manual 'cancelled' row
        UUID bostaLeg = jdbc.queryForObject("INSERT INTO shipments (tenant_id, order_id, provider, tracking_number, internal_state) " +
            "VALUES (?, ?, 'bosta', '853795364', 'cancelled') RETURNING id", UUID.class, f.tenant, bostaManual);
        jdbc.update("INSERT INTO shipment_status_history (tenant_id, shipment_id, internal_state, source) VALUES (?, ?, 'cancelled', 'manual')",
            f.tenant, bostaLeg);
        internal = f.orderWith("EXC-1", "new", 0, "internal:exchange:rr:" + UUID.randomUUID());
        legacyCancel(internal, "ARV3626155");
        cancelledOrder = f.orderWith("#37563", "cancelled", 0, "gid://shopify/Order/37563");
        legacyCancel(cancelledOrder, "ARV3626156");
        pickupSource = order("#37616");
        UUID sourced = generic(pickupSource, "ARV3626157", "cancelled");
        jdbc.update("INSERT INTO shipment_status_history (tenant_id, shipment_id, internal_state, source) VALUES (?, ?, 'cancelled', 'pickup_scan')",
            f.tenant, sourced);
    }

    @AfterEach void clear() { TenantContext.clear(); }

    @Test @Order(0)
    void z_scriptInlinesTheFreezeVerbatim() throws Exception {
        String script = Files.readString(Path.of(SCRIPT));
        assertThat(strip(script)).contains(strip(com.traceability.inventory.PreD7Freeze.FROZEN_SQL_O));
    }

    @Test @Order(1)
    void rs1_dryRun_listsCandidatesAndSkips_writesNothing() throws Exception {
        String before = fingerprint();
        ExecResult r = psql(false, null);
        assertThat(r.getExitCode()).as(r.getStderr()).isZero();
        String out = r.getStdout();
        assertThat(out).contains("DRY RUN").doesNotContain("COMMITTED");
        // tenant | order | status | rows | has_live_leg | live_leg | frozen_after | moved_state_after
        assertThat(line(out, "#37108")).containsPattern("\\| new\\s+\\|\\s+1 \\| t\\s+\\| ARV3626181\\s+\\| f\\s+\\|\\s*$");
        assertThat(line(out, "#37344")).containsPattern("\\| new\\s+\\|\\s+1 \\| f\\s+\\|\\s+\\| f\\s+\\|\\s*$");
        assertThat(line(out, "#37470")).contains("other forward history past created");
        assertThat(line(out, "#37493")).contains("a packed piece left custody");
        assertThat(line(out, "#37494")).contains("a piece of the order left custody");
        assertThat(out).doesNotContain("#17243").doesNotContain("EXC-1").doesNotContain("#37563").doesNotContain("#37616");
        assertThat(out).containsPattern("\\n\\s+2 \\|\\s+2 \\| Maron Fashion\\s*\\n");
        assertThat(fingerprint()).as("dry run writes nothing").isEqualTo(before);
        assertThat(backupExists()).isFalse();
    }

    @Test @Order(2)
    void rs2_wrongExpectOrders_aborts_nothingWritten() throws Exception {
        String before = fingerprint();
        ExecResult r = psql(true, "14");
        assertThat(r.getExitCode()).isNotZero();
        assertThat(r.getStderr()).contains("ABORTED").contains("2 candidate orders, expect_orders=14");
        assertThat(r.getStdout()).doesNotContain("COMMITTED");
        assertThat(fingerprint()).isEqualTo(before);
        assertThat(backupExists()).isFalse();
    }

    @Test @Order(3)
    void rs3_commit_deletesOnlyCandidates_backupHoldsThem_orderPacksAgain() throws Exception {
        // D7 (2026-11-02): a cancel row on a leg that never moved no longer freezes, so the order is already
        // unfrozen before the repair; the script still removes the rows (below).
        TenantContext.set(f.tenant);
        assertThat(OrderLineFreeze.isFrozen(jdbc, refulfilled, f.tenant)).as("already unfrozen under D7").isFalse();
        TenantContext.clear();

        List<String> rowsBefore = historyRows(candidateRows);
        String othersBefore = fingerprintExcept(candidateRows);
        ExecResult r = psql(true, "2");
        assertThat(r.getExitCode()).as(r.getStderr()).isZero();
        assertThat(r.getStdout()).contains("COMMITTED");
        assertThat(r.getStderr()).contains("proof: deleted exactly the 2 backed-up rows; every candidate order is unfrozen");
        assertThat(r.getStdout()).containsPattern("backed_up \\| still_present \\| still_frozen")
            .containsPattern("\\n\\s+2 \\|\\s+0 \\|\\s+0\\s*\\n");

        assertThat(historyRows(candidateRows)).isEmpty();
        assertThat(jdbc.queryForList("SELECT b::text FROM " + BACKUP + " b ORDER BY id", String.class))
            .as("the backup holds exactly the deleted rows").isEqualTo(rowsBefore);
        assertThat(fingerprintExcept(candidateRows)).as("every other history row unchanged").isEqualTo(othersBefore);
        assertThat(jdbc.queryForObject("SELECT has_table_privilege('app_user', '" + BACKUP + "', 'SELECT')", Boolean.class))
            .isFalse();

        TenantContext.set(f.tenant);
        assertThat(OrderLineFreeze.isFrozen(jdbc, refulfilled, f.tenant)).isFalse();
        WaybillResolver.Resolution open = resolver.resolve("arv3626181", packer);
        assertThat(open.code()).isEqualTo(WaybillResolver.Code.OPEN);
        assertThat(open.orderId()).isEqualTo(refulfilled);
        assertThat(OrderLineFreeze.isFrozen(jdbc, packedLeft, f.tenant)).as("a skipped order stays frozen").isTrue();
    }

    @Test @Order(4)
    void rs4_secondCommitRun_refuses_nothingWritten() throws Exception {
        String before = fingerprint();
        ExecResult r = psql(true, "0");
        assertThat(r.getExitCode()).isNotZero();
        assertThat(r.getStderr()).contains("ABORTED").contains("already exists");
        assertThat(fingerprint()).isEqualTo(before);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private UUID order(String number) {
        UUID o = f.orderWith(number, "new", 0, "gid://shopify/Order/" + number.substring(1));
        item(o);
        return o;
    }

    private UUID item(UUID order) {
        return jdbc.queryForObject("INSERT INTO order_items (tenant_id, order_id, variant_id, quantity) VALUES (?, ?, ?, 1) RETURNING id",
            UUID.class, f.tenant, order, variant);
    }

    private UUID generic(UUID order, String tn, String state) {
        return jdbc.queryForObject("INSERT INTO shipments (tenant_id, order_id, provider, tracking_number, tracking_key, internal_state, " +
            "source, shopify_fulfillment_id, carrier_name) VALUES (?, ?, 'generic', ?, ?, ?::shipment_internal_state, " +
            "'shopify_fulfillment', ?, 'Aramex') RETURNING id", UUID.class, f.tenant, order, tn, GenericTrackingKey.of(tn), state,
            "F-" + UUID.randomUUID());
    }

    /** A generic leg cancelled by the pre-D6 GenericShipments.cancel(): state + its 'cancelled' history row. */
    private long legacyCancel(UUID order, String tn) {
        UUID leg = generic(order, tn, "cancelled");
        return jdbc.queryForObject("INSERT INTO shipment_status_history (tenant_id, shipment_id, internal_state) " +
            "VALUES (?, ?, 'cancelled') RETURNING id", Long.class, f.tenant, leg);
    }

    private List<String> historyRows(List<Long> ids) {
        return jdbc.queryForList("SELECT h::text FROM shipment_status_history h WHERE id = ANY(?) ORDER BY id",
            String.class, (Object) ids.toArray(Long[]::new));
    }

    private String fingerprintExcept(List<Long> ids) {
        return String.join("\n", jdbc.queryForList("SELECT h::text FROM shipment_status_history h WHERE id <> ALL(?) ORDER BY id",
            String.class, (Object) ids.toArray(Long[]::new)));
    }

    /** Every history, shipment, allocation and piece row, as text. */
    private String fingerprint() {
        return String.join("\n", jdbc.queryForList(
            "SELECT h::text FROM shipment_status_history h UNION ALL SELECT s::text FROM shipments s " +
            "UNION ALL SELECT a::text FROM allocations a UNION ALL SELECT p::text FROM pieces p ORDER BY 1", String.class));
    }

    private boolean backupExists() {
        return jdbc.queryForObject("SELECT to_regclass('public." + BACKUP + "') IS NOT NULL", Boolean.class);
    }

    private ExecResult psql(boolean commit, String expectOrders) throws Exception {
        POSTGRES.copyFileToContainer(MountableFile.forHostPath(Path.of(SCRIPT).toAbsolutePath()), "/tmp/repair.sql");
        return commit
            ? POSTGRES.execInContainer("psql", "-U", "postgres", "-d", "traceability_test", "-v", "ON_ERROR_STOP=1",
                "-v", "commit=yes", "-v", "expect_orders=" + expectOrders, "-f", "/tmp/repair.sql")
            : POSTGRES.execInContainer("psql", "-U", "postgres", "-d", "traceability_test", "-v", "ON_ERROR_STOP=1",
                "-f", "/tmp/repair.sql");
    }

    private static String line(String out, String needle) {
        return out.lines().filter(l -> l.contains(needle)).findFirst().orElse("<no line for " + needle + ">");
    }

    private static String strip(String s) {
        return s.replaceAll("\\s+", "");
    }
}
