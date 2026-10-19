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
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Deleted-in-Bosta slice 5 — scripts/ops/2026-10-19-deleted-in-bosta-backfill.sql, run with psql exactly as on
 * prod (postgres role, -v ON_ERROR_STOP=1).
 *
 *   bf1 dry run: lists every stamped leg with what it becomes, writes nothing (every shipments / unlinked row identical)
 *   bf2 commit: only rows passing all four guards close ('cancelled', provider_deleted_at = provider_not_found_at,
 *       reason deleted_in_bosta_backfill, no history row, no piece event); a moved / pieces-out / held leg is
 *       listed and unchanged; a generic leg is never touched; the closed order's unlinked delivery gets
 *       matcher_version NULL, another order's doesn't
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DeletedInBostaBackfillScriptTest {

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
    @MockBean  JobScheduler jobScheduler;

    static final String SCRIPT = "scripts/ops/2026-10-19-deleted-in-bosta-backfill.sql";

    PackFixtures f;
    UUID closes, moved, piecesOut, held, returnFree, generic, untouchedLive;
    long unlinkedMine, unlinkedOther;

    @BeforeAll
    void seed() {
        f = new PackFixtures(jdbc, "Femine-backfill");
        UUID actor = f.user("Ops", "owner");
        UUID variant = f.variant("Abaya", "AB-BF", null);

        UUID o1 = order("70695", variant, actor);
        closes = suspected(f.forward(o1), 3);

        UUID o2 = order("70724", variant, actor);
        moved = suspected(f.forward(o2), 3);
        jdbc.update("INSERT INTO shipment_status_history (tenant_id, shipment_id, internal_state, occurred_at) " +
                    "VALUES (?, ?, 'with_courier', now() - interval '4 days')", f.tenant, moved);

        UUID o3 = order("70800", variant, actor);
        piecesOut = suspected(f.forward(o3), 3);
        String p3 = jdbc.queryForObject("SELECT p.id FROM pieces p JOIN allocations a ON a.piece_id = p.id " +
            "JOIN order_items oi ON oi.id = a.order_item_id WHERE oi.order_id = ?", String.class, o3);
        jdbc.update("UPDATE pieces SET status = 'awaiting_pickup' WHERE id = ?", p3);

        UUID o4 = order("70811", variant, actor);
        held = suspected(f.leg(o4, "return", "created", "{\"type\":{\"code\":25}}"), 2);
        jdbc.update("INSERT INTO return_requests (tenant_id, order_id, status, reference, return_shipment_id) " +
                    "VALUES (?, ?, 'pickup_booked', 'RR-BFKR', ?)", f.tenant, o4, held);

        UUID o5 = order("71070", variant, actor);
        returnFree = suspected(f.leg(o5, "return", "created", "{\"type\":{\"code\":25}}"), 2);

        UUID o6 = order("71274", variant, actor);
        generic = jdbc.queryForObject(
            "INSERT INTO shipments (tenant_id, order_id, provider, tracking_number, tracking_key, carrier_name, source, internal_state, provider_not_found_at) " +
            "VALUES (?, ?, 'generic', 'WJ5500071', 'WJ5500071', 'Wijha', 'merchant_entered', 'created', now() - interval '1 day') RETURNING id",
            UUID.class, f.tenant, o6);

        UUID o7 = order("71300", variant, actor);
        untouchedLive = jdbc.queryForObject("SELECT id FROM shipments WHERE tracking_number = ?", UUID.class, f.forward(o7));

        unlinkedMine = unlinked("#70695", "6100000001");
        unlinkedOther = unlinked("#99999", "6100000002");
    }

    @Test @Order(1)
    void bf1_dryRun_listsEveryStampedLeg_writesNothing() throws Exception {
        String before = fingerprint();
        ExecResult r = psql(false);
        assertThat(r.getExitCode()).as(r.getStderr()).isZero();
        String out = r.getStdout();
        assertThat(out).contains("DRY RUN").doesNotContain("COMMITTED");
        assertThat(line(out, tn(closes))).contains("cancelled (deleted_in_bosta_backfill)").contains("| forward");
        assertThat(line(out, tn(returnFree))).contains("cancelled (deleted_in_bosta_backfill)").contains("return · customer return pickup");
        assertThat(line(out, tn(moved))).contains("LISTED").contains("history moved past created");
        assertThat(line(out, tn(piecesOut))).contains("LISTED").contains("pieces awaiting_pickup / with_courier");
        assertThat(line(out, tn(held))).contains("LISTED").contains("open return request");
        assertThat(out).doesNotContain("WJ5500071").doesNotContain(tn(untouchedLive));
        assertThat(fingerprint()).as("dry run writes nothing").isEqualTo(before);
    }

    @Test @Order(2)
    void bf2_commit_closesOnlyGuardedRows() throws Exception {
        ExecResult r = psql(true);
        assertThat(r.getExitCode()).as(r.getStderr()).isZero();
        assertThat(r.getStdout()).contains("COMMITTED");

        for (UUID closed : new UUID[] { closes, returnFree }) {
            Map<String, Object> row = row(closed);
            assertThat(row.get("internal_state")).isEqualTo("cancelled");
            assertThat(row.get("provider_deleted_reason")).isEqualTo("deleted_in_bosta_backfill");
            assertThat(row.get("same_time")).as("provider_deleted_at = provider_not_found_at read as Cairo time").isEqualTo(true);
        }
        for (UUID kept : new UUID[] { moved, piecesOut, held, generic, untouchedLive }) {
            Map<String, Object> row = row(kept);
            assertThat(row.get("provider_deleted_at")).as(String.valueOf(kept)).isNull();
        }
        assertThat(row(moved).get("internal_state")).isEqualTo("created");
        assertThat(row(held).get("internal_state")).isEqualTo("created");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM shipment_status_history WHERE shipment_id IN (?, ?)",
            Integer.class, closes, returnFree)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM piece_events WHERE tenant_id = ? AND event_type <> 'received'",
            Integer.class, f.tenant)).isZero();
        assertThat(jdbc.queryForObject("SELECT matcher_version FROM unlinked_bosta_deliveries WHERE id = ?", String.class, unlinkedMine)).isNull();
        assertThat(jdbc.queryForObject("SELECT matcher_version FROM unlinked_bosta_deliveries WHERE id = ?", String.class, unlinkedOther))
            .isEqualTo("v-test");
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private ExecResult psql(boolean commit) throws Exception {
        POSTGRES.copyFileToContainer(MountableFile.forHostPath(Path.of(SCRIPT).toAbsolutePath()), "/tmp/backfill.sql");
        return commit
            ? POSTGRES.execInContainer("psql", "-U", "postgres", "-d", "traceability_test", "-v", "ON_ERROR_STOP=1",
                "-v", "commit=yes", "-f", "/tmp/backfill.sql")
            : POSTGRES.execInContainer("psql", "-U", "postgres", "-d", "traceability_test", "-v", "ON_ERROR_STOP=1",
                "-f", "/tmp/backfill.sql");
    }

    private UUID order(String number, UUID variant, UUID actor) {
        UUID o = f.order(number, 2);
        f.item(o, variant, 1);
        String shortCode = f.piece(variant, actor);
        jdbc.update("INSERT INTO allocations (piece_id, order_item_id, tenant_id, status) " +
                    "SELECT ?, oi.id, oi.tenant_id, 'active' FROM order_items oi WHERE oi.order_id = ?",
                    f.pieceId(shortCode), o);
        return o;
    }

    private UUID suspected(String tracking, int daysAgo) {
        UUID id = jdbc.queryForObject("SELECT id FROM shipments WHERE tracking_number = ?", UUID.class, tracking);
        jdbc.update("UPDATE shipments SET provider_not_found_at = now() - (? * INTERVAL '1 day'), " +
            "last_not_found_at = now() - (? * INTERVAL '1 day'), not_found_count = 1 WHERE id = ?", daysAgo, daysAgo, id);
        return id;
    }

    private long unlinked(String reference, String tracking) {
        return jdbc.queryForObject(
            "INSERT INTO unlinked_bosta_deliveries (tenant_id, tracking_number, business_reference, bosta_state_code, " +
            "bosta_order_type, matcher_version) VALUES (?, ?, ?, 10, 'SEND', 'v-test') RETURNING id",
            Long.class, f.tenant, tracking, reference);
    }

    private String tn(UUID shipment) {
        return jdbc.queryForObject("SELECT tracking_number FROM shipments WHERE id = ?", String.class, shipment);
    }

    private Map<String, Object> row(UUID shipment) {
        return jdbc.queryForMap("SELECT internal_state::text AS internal_state, provider_deleted_at, provider_deleted_reason, " +
            "provider_deleted_at = (provider_not_found_at AT TIME ZONE 'Africa/Cairo') AS same_time FROM shipments WHERE id = ?", shipment);
    }

    /** Every shipments and unlinked row of the tenant, as text. */
    private String fingerprint() {
        return String.join("\n", jdbc.queryForList(
            "SELECT s::text FROM shipments s WHERE tenant_id = ? UNION ALL " +
            "SELECT u::text FROM unlinked_bosta_deliveries u WHERE tenant_id = ? ORDER BY 1",
            String.class, f.tenant, f.tenant));
    }

    private static String line(String out, String tracking) {
        return out.lines().filter(l -> l.contains(tracking)).findFirst().orElse("<no line for " + tracking + ">");
    }
}
